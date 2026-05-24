#!/bin/bash
# realtime_message_sync/run.sh — M5.2 实时消息断线重连补齐 harness。

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVER_DIR="$PROJ_DIR/server"
# shellcheck source=../../../scripts/lib/dev-ports.sh
source "$PROJ_DIR/scripts/lib/dev-ports.sh"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/realtime_message_sync}"
mkdir -p "$OUTPUT_DIR"
RESULTS="$OUTPUT_DIR/results.jsonl"
: > "$RESULTS"

log() { printf "[%s] %s\n" "$(date +%H:%M:%S)" "$*"; }

is_tcp_port_busy() {
    [[ -n "$(ss -ltnH "( sport = :$1 )" 2>/dev/null || true)" ]]
}

GATEWAY_PORT="${REALTIME_MESSAGE_SYNC_GATEWAY_PORT:-18808}"
if [[ -z "${REALTIME_MESSAGE_SYNC_GATEWAY_PORT:-}" ]]; then
    for cand in 18808 18818 18828 18838; do
        if ! is_tcp_port_busy "$cand"; then
            GATEWAY_PORT="$cand"
            break
        fi
    done
fi
GATEWAY="http://127.0.0.1:$GATEWAY_PORT"
WS_GATEWAY="ws://127.0.0.1:$GATEWAY_PORT/v1/ws"
log "gateway 采样端口：$GATEWAY_PORT ($GATEWAY)"

log "0. 前置：清表 + 应用 migration 0006/0010"
podman ps --format '{{.Names}}' | grep -qx gomob-pg    || { log "缺 gomob-pg";    exit 2; }
podman ps --format '{{.Names}}' | grep -qx gomob-redis || { log "缺 gomob-redis"; exit 2; }

HAS_PENDING=$(podman exec -i gomob-pg psql -U gomob -d gomob -tAc \
    "SELECT 1 FROM information_schema.tables WHERE table_name='pending_calls'")
if [[ -z "$HAS_PENDING" ]]; then
    log "  应用 migrations/0006_signaling.up.sql"
    podman exec -i gomob-pg psql -U gomob -d gomob -v ON_ERROR_STOP=1 \
        < "$SERVER_DIR/migrations/0006_signaling.up.sql" > /dev/null
fi
HAS_MEDIA=$(podman exec -i gomob-pg psql -U gomob -d gomob -tAc \
    "SELECT 1 FROM information_schema.tables WHERE table_name='media_rooms'")
if [[ -z "$HAS_MEDIA" ]]; then
    log "  应用 migrations/0010_realtime_message_live.up.sql"
    podman exec -i gomob-pg psql -U gomob -d gomob -v ON_ERROR_STOP=1 \
        < "$SERVER_DIR/migrations/0010_realtime_message_live.up.sql" > /dev/null
fi
ASSET_INSPECTION_NULLABLE=$(podman exec -i gomob-pg psql -U gomob -d gomob -tAc \
    "SELECT is_nullable FROM information_schema.columns WHERE table_name='inspection_assets' AND column_name='inspection_id'")
if [[ "$ASSET_INSPECTION_NULLABLE" != "YES" ]]; then
    log "  应用 migrations/0011_message_assets.up.sql"
    podman exec -i gomob-pg psql -U gomob -d gomob -v ON_ERROR_STOP=1 \
        < "$SERVER_DIR/migrations/0011_message_assets.up.sql" > /dev/null
fi

if ! podman exec -i gomob-pg psql -U gomob -d gomob -v ON_ERROR_STOP=1 > /dev/null <<'SQL'
TRUNCATE TABLE
    audit_log,
    llm_call_logs,
    llm_templates,
    model_routes,
    models,
    upload_sessions,
    inspection_assets,
    reviews,
    inspections,
    vehicles,
    vehicle_models,
    messages,
    conversation_members,
    conversations,
    call_logs,
    pending_calls,
    users,
    stations
RESTART IDENTITY CASCADE;
INSERT INTO stations(name, region) VALUES('测试检测站','test');
SQL
then
    log "清表失败"
    exit 2
fi
podman exec gomob-redis redis-cli FLUSHDB > /dev/null

log "1. 编译 auth / api / gateway / signaling / realtimeharness"
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-auth            ./cmd/auth)            || { log "auth build 失败"; exit 3; }
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-api             ./cmd/api)             || { log "api build 失败"; exit 3; }
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-gateway         ./cmd/gateway)         || { log "gateway build 失败"; exit 3; }
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-signaling       ./cmd/signaling)       || { log "signaling build 失败"; exit 3; }
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-realtimeharness ./cmd/realtimeharness) || { log "realtimeharness build 失败"; exit 3; }

log "2. 启动服务"
PIDS=()
trap 'for p in "${PIDS[@]:-}"; do kill $p 2>/dev/null || true; done; wait 2>/dev/null || true' EXIT

# server binary 默认连 127.0.0.1:5432，宿主 pg 端口段已搬到 dev-ports.sh
export GOMOB_DB_DSN="${GOMOB_DB_DSN:-$GOMOB_DEFAULT_DB_DSN}"

GOMOB_AUTH_HTTP_ADDR=:18082 GOMOB_AUTH_DEV_AUTOACTIVATE=true \
    "$SERVER_DIR/.dev/bin/gomob-auth" > "$OUTPUT_DIR/auth.log" 2>&1 &
PIDS+=($!)

GOMOB_API_HTTP_ADDR=:18080 \
GOMOB_CATALOG_TARGET= GOMOB_VINREF_TARGET= GOMOB_SHAPEREF_TARGET= \
    "$SERVER_DIR/.dev/bin/gomob-api" > "$OUTPUT_DIR/api.log" 2>&1 &
PIDS+=($!)

GOMOB_GATEWAY_ADDR=":$GATEWAY_PORT" GOMOB_REDIS_ADDR="$GOMOB_DEFAULT_REDIS_ADDR" GOMOB_RATE_LIMIT=10000 \
    "$SERVER_DIR/.dev/bin/gomob-gateway" > "$OUTPUT_DIR/gateway.log" 2>&1 &
PIDS+=($!)

GOMOB_SIGNALING_HTTP_ADDR=:18084 \
    "$SERVER_DIR/.dev/bin/gomob-signaling" > "$OUTPUT_DIR/signaling.log" 2>&1 &
PIDS+=($!)

sleep 1
for p in "${PIDS[@]}"; do
    kill -0 "$p" 2>/dev/null || { log "服务启动后已退出 pid=$p，检查 $OUTPUT_DIR/*.log"; exit 4; }
done
hc=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:18080/healthz")
[[ "$hc" == "200" ]] || { log "api /healthz=$hc"; exit 4; }
hc=$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/healthz")
[[ "$hc" == "200" ]] || { log "gateway /healthz=$hc"; exit 4; }
hc=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:18084/healthz")
[[ "$hc" == "200" ]] || { log "signaling /healthz=$hc"; exit 4; }

log "3. 运行 realtimeharness 子程序"
"$SERVER_DIR/.dev/bin/gomob-realtimeharness" \
    -gateway "$GATEWAY" \
    -ws "$WS_GATEWAY" \
    -out "$RESULTS" \
    > "$OUTPUT_DIR/realtimeharness.log" 2>&1
RT_RC=$?

log "4. 采样完成（rc=$RT_RC） → $RESULTS"
log "   下一步：python3 $(dirname "$0")/analyze.py $OUTPUT_DIR"
exit $RT_RC
