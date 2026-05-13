#!/bin/bash
# ws_message_order/run.sh — M-S4 signaling 全链路 harness 采样器。
#
# 场景由 server/cmd/wsharness 实施（gorilla/websocket 客户端 + JSON 校验），
# 这里负责清表 / 起服务 / 调用 wsharness / 收尾。
#
# 关键场景（详见 wsharness/main.go 注释）：
#   S1   注册 + 登录 user A / user B
#   S2/3 ws 接入 + hello
#   S4   单条单聊 server_seq=1
#   S5   顺序 50 条：seq 严格 [2..51] 单调
#   S6   并发 100 条：seq 严格 [52..151] 单调 + 无重 + 无空
#   S7   msg.fetch since=0 拿到全部 ≥151 条 + 升序
#   S8   非法 msg.send → error 帧 code=10001
#   S16  同 client_msg_id 重发 → server_seq 不重复，收件人只收到一次
#   S17  HTTP 标记已读 → unread_count=0
#   S18  HTTP 会话列表 → last_message 与最新消息一致
#   S18b 发送方会话列表 → 自己发出的消息不制造未读
#   S9   B 离线时 A 发 call.invite → invite_ack online=false
#   S10  B 重连 → 5s 内收到 pending invite (pending=true)
#   S11  call.answer 透传到主叫
#   S12  call.ice 双向透传
#   S13  call.bye 透传
#   S14  TTL 过期：第二条 invite 在 TTL=3s + sweep=1s 后不再投递
#   S15  /v1/signaling/online 自暴露端点

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVER_DIR="$PROJ_DIR/server"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/ws_message_order}"
mkdir -p "$OUTPUT_DIR"
RESULTS="$OUTPUT_DIR/results.jsonl"
: > "$RESULTS"

log() { printf "[%s] %s\n" "$(date +%H:%M:%S)" "$*"; }

is_tcp_port_busy() {
    [[ -n "$(ss -ltnH "( sport = :$1 )" 2>/dev/null || true)" ]]
}

GATEWAY_PORT="${WS_MESSAGE_ORDER_GATEWAY_PORT:-18808}"
if [[ -z "${WS_MESSAGE_ORDER_GATEWAY_PORT:-}" ]]; then
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

# ============================================================================
# 0. 前置：清表（拓扑序）+ 跑迁移
# ============================================================================
log "0. 前置：清表 + 应用 migration 0006/0010"
podman ps --format '{{.Names}}' | grep -qx gomob-pg    || { log "缺 gomob-pg";    exit 2; }
podman ps --format '{{.Names}}' | grep -qx gomob-redis || { log "缺 gomob-redis"; exit 2; }

# 0.a 应用 0006（idempotent：如已应用，ALTER ADD COLUMN 会失败 → 用 IF NOT EXISTS 兜底；
#     这里直接做存在性探测，避免每次重试。）
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

# 0.b 清表：用 TRUNCATE ... CASCADE 避免参考库 / 设备 / 媒体表新增 FK 后清理顺序漂移。
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

# ============================================================================
# 1. 编译
# ============================================================================
log "1. 编译 auth / api / gateway / signaling / wsharness"
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-auth      ./cmd/auth)      || { log "auth build 失败"; exit 3; }
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-api       ./cmd/api)       || { log "api build 失败"; exit 3; }
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-gateway   ./cmd/gateway)   || { log "gateway build 失败"; exit 3; }
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-signaling ./cmd/signaling) || { log "signaling build 失败"; exit 3; }
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-wsharness ./cmd/wsharness) || { log "wsharness build 失败"; exit 3; }

# ============================================================================
# 2. 启动 3 服务
# ============================================================================
log "2. 启动服务"
PIDS=()
trap 'for p in "${PIDS[@]:-}"; do kill $p 2>/dev/null || true; done; wait 2>/dev/null || true' EXIT

# auth：dev 自动激活，registration 后立即可登录
GOMOB_AUTH_HTTP_ADDR=:18082 GOMOB_AUTH_DEV_AUTOACTIVATE=true \
    "$SERVER_DIR/.dev/bin/gomob-auth" > "$OUTPUT_DIR/auth.log" 2>&1 &
PIDS+=($!)

# api：挂 conversations/messages REST，用于 S17/S18
GOMOB_API_HTTP_ADDR=:18080 \
GOMOB_CATALOG_TARGET= GOMOB_VINREF_TARGET= GOMOB_SHAPEREF_TARGET= \
    "$SERVER_DIR/.dev/bin/gomob-api" > "$OUTPUT_DIR/api.log" 2>&1 &
PIDS+=($!)

# gateway：限流不要触发（设大）
GOMOB_GATEWAY_ADDR=":$GATEWAY_PORT" GOMOB_REDIS_ADDR=127.0.0.1:6379 GOMOB_RATE_LIMIT=10000 \
    "$SERVER_DIR/.dev/bin/gomob-gateway" > "$OUTPUT_DIR/gateway.log" 2>&1 &
PIDS+=($!)

# signaling：TTL=3s, sweep=1s 让 S14 TTL 测试可观测
GOMOB_SIGNALING_HTTP_ADDR=:18084 \
GOMOB_PENDING_CALL_TTL=3s \
GOMOB_PENDING_CALL_SWEEP=1s \
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

# ============================================================================
# 3. 运行 wsharness
# ============================================================================
log "3. 运行 wsharness 子程序"
"$SERVER_DIR/.dev/bin/gomob-wsharness" \
    -gateway "$GATEWAY" \
    -ws "$WS_GATEWAY" \
    -out "$RESULTS" \
    -burst 100 \
    -concurrency 5 \
    > "$OUTPUT_DIR/wsharness.log" 2>&1
WS_RC=$?

log "4. 采样完成（rc=$WS_RC） → $RESULTS"
log "   下一步：python3 $(dirname "$0")/analyze.py $OUTPUT_DIR"
exit $WS_RC
