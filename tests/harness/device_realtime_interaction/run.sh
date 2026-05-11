#!/bin/bash
# device_realtime_interaction/run.sh — 模拟器 + 真机实时消息 / 直播控制面日志 harness。
#
# 默认不做截图：通过 results.jsonl、server logs、adb logcat 判断。

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVER_DIR="$PROJ_DIR/server"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/device_realtime_interaction}"
mkdir -p "$OUTPUT_DIR"
RESULTS="$OUTPUT_DIR/results.jsonl"
DEVICES_JSONL="$OUTPUT_DIR/devices.jsonl"
REVERSE_QUEUE="$OUTPUT_DIR/reverse_queue.tsv"
START_QUEUE="$OUTPUT_DIR/app_start_queue.tsv"
START_RESULTS="$OUTPUT_DIR/app_starts.jsonl"
CAPABILITIES="$OUTPUT_DIR/capabilities.json"
: > "$RESULTS"
: > "$DEVICES_JSONL"
: > "$REVERSE_QUEUE"
: > "$START_QUEUE"
: > "$START_RESULTS"

APP_START="${DEVICE_REALTIME_START_APP:-1}"
ADB="${ADB:-${ANDROID_HOME:-/opt/android-sdk}/platform-tools/adb}"
APP_PACKAGES=("io.gomob.scan.debug" "io.gomob.scan")
APP_REVERSE_PORT="${DEVICE_REALTIME_APP_REVERSE_PORT:-18808}"

PIDS=()
LOGCAT_PIDS=()

log() { printf "[%s] %s\n" "$(date +%H:%M:%S)" "$*"; }

is_tcp_port_busy() {
    [[ -n "$(ss -ltnH "( sport = :$1 )" 2>/dev/null || true)" ]]
}

GATEWAY_PORT="${DEVICE_REALTIME_GATEWAY_PORT:-18808}"
if [[ -z "${DEVICE_REALTIME_GATEWAY:-}" && -z "${DEVICE_REALTIME_WS:-}" && -z "${DEVICE_REALTIME_GATEWAY_PORT:-}" ]]; then
    for cand in 18808 18818 18828 18838; do
        if ! is_tcp_port_busy "$cand"; then
            GATEWAY_PORT="$cand"
            break
        fi
    done
fi
GATEWAY="${DEVICE_REALTIME_GATEWAY:-http://127.0.0.1:$GATEWAY_PORT}"
WS_GATEWAY="${DEVICE_REALTIME_WS:-ws://127.0.0.1:$GATEWAY_PORT/v1/ws}"
if [[ "${DEVICE_REALTIME_ATTACH_APP_TO_HARNESS:-0}" == "1" ]]; then
    APP_REVERSE_PORT="$GATEWAY_PORT"
fi
log "gateway 采样端口：$GATEWAY_PORT ($GATEWAY)"

safe_name() {
    printf "%s" "$1" | tr -c 'A-Za-z0-9_.-' '_'
}

json_escape() {
    printf "%s" "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

restore_dev_seed_login() {
    podman ps --format '{{.Names}}' 2>/dev/null | grep -qx gomob-pg || return 0
    podman exec -i gomob-pg psql -U gomob -d gomob -v ON_ERROR_STOP=1 >/dev/null 2>&1 <<'SQL' || true
DO $$
DECLARE
    sid BIGINT;
    uid BIGINT;
BEGIN
    SELECT id INTO sid FROM stations WHERE name='杭州市西湖区车管所检测站' ORDER BY id LIMIT 1;
    IF sid IS NULL THEN
        INSERT INTO stations(name, region, gateway_addr)
        VALUES('杭州市西湖区车管所检测站', '浙江杭州', '127.0.0.1:18808')
        RETURNING id INTO sid;
    END IF;

    SELECT id INTO uid
    FROM users
    WHERE username='shenhm' OR employee_id='ZAA0120230001'
    ORDER BY CASE WHEN username='shenhm' THEN 0 ELSE 1 END, id
    LIMIT 1;

    IF uid IS NULL THEN
        INSERT INTO users(username, real_name, employee_id, station_id, password_hash, role, status, note, activated_at)
        VALUES(
            'shenhm',
            '沈海明',
            'ZAA0120230001',
            sid,
            '$2a$12$vnvzmvcmNZ5twz/pW5qbTusMOPp2BMH3N/EdPPu9i4CxCTdgFwh5m',
            'inspector',
            'active',
            'devserver seed login',
            now()
        );
    ELSE
        UPDATE users
        SET username='shenhm',
            real_name='沈海明',
            employee_id='ZAA0120230001',
            station_id=sid,
            password_hash='$2a$12$vnvzmvcmNZ5twz/pW5qbTusMOPp2BMH3N/EdPPu9i4CxCTdgFwh5m',
            role='inspector',
            status='active',
            activated_at=COALESCE(activated_at, now())
        WHERE id=uid;
    END IF;
END $$;
SQL
}

ensure_livekit_container() {
    if ! command -v podman >/dev/null 2>&1; then
        log "  podman 不可用，跳过 LiveKit 容器检查"
        return 0
    fi
    if podman ps --format '{{.Names}}' | grep -qx gomob-livekit; then
        log "  LiveKit dev server 已运行：gomob-livekit"
        return 0
    fi
    if podman ps -a --format '{{.Names}}' | grep -qx gomob-livekit; then
        podman start gomob-livekit >/dev/null
        log "  已启动已有 LiveKit dev server：gomob-livekit"
        return 0
    fi
    podman run -d --name gomob-livekit \
        -p 7880:7880 -p 7881:7881 -p 7882:7882/udp \
        docker.io/livekit/livekit-server:latest \
        --dev --bind 0.0.0.0 >/dev/null
    log "  已创建 LiveKit dev server：gomob-livekit (--dev: devkey/secret)"
}

record_device() {
    local serial="$1" kind="$2" model="$3" reverse_ok="$4" app_pkg="$5" app_started="$6" log_file="$7"
    printf '{"serial":"%s","kind":"%s","model":"%s","reverse_ok":%s,"app_package":"%s","app_started":%s,"log_file":"%s"}\n' \
        "$(json_escape "$serial")" \
        "$(json_escape "$kind")" \
        "$(json_escape "$model")" \
        "$reverse_ok" \
        "$(json_escape "$app_pkg")" \
        "$app_started" \
        "$(json_escape "$log_file")" \
        >> "$DEVICES_JSONL"
}

cleanup() {
    restore_dev_seed_login
    for p in "${LOGCAT_PIDS[@]:-}"; do
        kill "$p" 2>/dev/null || true
    done
    if [[ -x "$ADB" ]]; then
        while IFS=$'\t' read -r serial _safe; do
            [[ -n "$serial" ]] || continue
            "$ADB" -s "$serial" reverse tcp:8808 tcp:18808 >/dev/null 2>&1 || true
        done < "$REVERSE_QUEUE"
    fi
    for p in "${PIDS[@]:-}"; do
        kill "$p" 2>/dev/null || true
    done
    wait 2>/dev/null || true
}
trap cleanup EXIT

log "0. 采集 ADB 设备与 logcat（截图流程默认关闭）"
if [[ ! -x "$ADB" ]]; then
    log "adb 不存在：$ADB；设备覆盖会在分析器中标记警告"
else
    mapfile -t DEVICES < <("$ADB" devices | awk 'NR>1 && $2=="device"{print $1}')
    for serial in "${DEVICES[@]:-}"; do
        safe="$(safe_name "$serial")"
        qemu="$("$ADB" -s "$serial" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r' || true)"
        model="$("$ADB" -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)"
        kind="physical"
        [[ "$qemu" == "1" || "$serial" == emulator-* ]] && kind="emulator"

        reverse_ok=false
        if "$ADB" -s "$serial" reverse "tcp:8808" "tcp:$APP_REVERSE_PORT" > "$OUTPUT_DIR/adb-reverse-$safe.log" 2>&1; then
            reverse_ok=true
            log "  $serial($kind) reverse tcp:8808 -> host tcp:$APP_REVERSE_PORT"
            printf "%s\t%s\n" "$serial" "$safe" >> "$REVERSE_QUEUE"
        else
            log "  $serial($kind) reverse 失败，详情 $OUTPUT_DIR/adb-reverse-$safe.log"
        fi
        if "$ADB" -s "$serial" reverse "tcp:7880" "tcp:7880" >> "$OUTPUT_DIR/adb-reverse-$safe.log" 2>&1; then
            log "  $serial($kind) reverse tcp:7880 -> host tcp:7880"
        else
            log "  $serial($kind) LiveKit reverse 失败，详情 $OUTPUT_DIR/adb-reverse-$safe.log"
        fi

        "$ADB" -s "$serial" logcat -c >/dev/null 2>&1 || true
        log_file="$OUTPUT_DIR/adb-$safe.log"
        "$ADB" -s "$serial" logcat -v time \
            'gomob:*' 'gomob_native:*' 'OkHttp:*' \
            'AuthRepository:V' 'GomobApplication:V' \
            'AndroidRuntime:E' 'System.err:W' '*:S' \
            > "$log_file" 2>&1 &
        LOGCAT_PIDS+=($!)

        app_pkg=""
        if [[ "$APP_START" == "1" ]]; then
            for pkg in "${APP_PACKAGES[@]}"; do
                if "$ADB" -s "$serial" shell pm path "$pkg" >/dev/null 2>&1; then
                    app_pkg="$pkg"
                    printf "%s\t%s\t%s\n" "$serial" "$safe" "$pkg" >> "$START_QUEUE"
                    break
                fi
            done
        fi
        record_device "$serial" "$kind" "$model" "$reverse_ok" "$app_pkg" false "$log_file"
    done
fi

log "1. 前置：清表 + 应用消息 / 媒体 migration"
podman ps --format '{{.Names}}' | grep -qx gomob-pg    || { log "缺 gomob-pg";    exit 2; }
podman ps --format '{{.Names}}' | grep -qx gomob-redis || { log "缺 gomob-redis"; exit 2; }

HAS_PENDING=$(podman exec -i gomob-pg psql -U gomob -d gomob -tAc \
    "SELECT 1 FROM information_schema.tables WHERE table_name='pending_calls'")
if [[ -z "$HAS_PENDING" ]]; then
    log "  应用 migrations/0006_signaling.up.sql"
    podman exec -i gomob-pg psql -U gomob -d gomob -v ON_ERROR_STOP=1 \
        < "$SERVER_DIR/migrations/0006_signaling.up.sql" > /dev/null || exit 2
fi
HAS_MEDIA=$(podman exec -i gomob-pg psql -U gomob -d gomob -tAc \
    "SELECT 1 FROM information_schema.tables WHERE table_name='media_rooms'")
if [[ -z "$HAS_MEDIA" ]]; then
    log "  应用 migrations/0010_realtime_message_live.up.sql"
    podman exec -i gomob-pg psql -U gomob -d gomob -v ON_ERROR_STOP=1 \
        < "$SERVER_DIR/migrations/0010_realtime_message_live.up.sql" > /dev/null || exit 2
fi
ASSET_INSPECTION_NULLABLE=$(podman exec -i gomob-pg psql -U gomob -d gomob -tAc \
    "SELECT is_nullable FROM information_schema.columns WHERE table_name='inspection_assets' AND column_name='inspection_id'")
if [[ "$ASSET_INSPECTION_NULLABLE" != "YES" ]]; then
    log "  应用 migrations/0011_message_assets.up.sql"
    podman exec -i gomob-pg psql -U gomob -d gomob -v ON_ERROR_STOP=1 \
        < "$SERVER_DIR/migrations/0011_message_assets.up.sql" > /dev/null || exit 2
fi
HAS_CALL_LOG_ROOM=$(podman exec -i gomob-pg psql -U gomob -d gomob -tAc \
    "SELECT 1 FROM information_schema.columns WHERE table_name='call_logs' AND column_name='room_id'")
if [[ -z "$HAS_CALL_LOG_ROOM" ]]; then
    log "  应用 migrations/0014_call_logs_media_room.up.sql"
    podman exec -i gomob-pg psql -U gomob -d gomob -v ON_ERROR_STOP=1 \
        < "$SERVER_DIR/migrations/0014_call_logs_media_room.up.sql" > /dev/null || exit 2
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
    live_recordings,
    live_annotations,
    live_sessions,
    media_participants,
    media_rooms,
    messages,
    conversation_member_states,
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

log "2. 编译 devserver / deviceinteractionharness"
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-devserver                ./cmd/devserver)                || { log "devserver build 失败"; exit 3; }
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-deviceinteractionharness ./cmd/deviceinteractionharness) || { log "deviceinteractionharness build 失败"; exit 3; }

log "3. 启动 devserver 合体服务"
ensure_livekit_container
GOMOB_LISTEN=":$GATEWAY_PORT" \
GOMOB_DISCOVERY_ADDR= \
GOMOB_DEV_AUTO_ACTIVATE=true \
GOMOB_LIVEKIT_URL="${GOMOB_LIVEKIT_URL:-ws://127.0.0.1:7880}" \
GOMOB_LIVEKIT_API_KEY="${GOMOB_LIVEKIT_API_KEY:-devkey}" \
GOMOB_LIVEKIT_API_SECRET="${GOMOB_LIVEKIT_API_SECRET:-secret}" \
GOMOB_LOG_UPLOAD_DIR="$OUTPUT_DIR/uploaded-logs" \
GOMOB_PENDING_CALL_TTL=3s \
GOMOB_PENDING_CALL_SWEEP=1s \
GOMOB_CATALOG_TARGET= GOMOB_VINREF_TARGET= GOMOB_SHAPEREF_TARGET= \
    "$SERVER_DIR/.dev/bin/gomob-devserver" > "$OUTPUT_DIR/devserver.log" 2>&1 &
PIDS+=($!)

sleep 1
for p in "${PIDS[@]}"; do
    kill -0 "$p" 2>/dev/null || { log "服务启动后已退出 pid=$p，检查 $OUTPUT_DIR/*.log"; exit 4; }
done
hc=$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/healthz")
[[ "$hc" == "200" ]] || { log "devserver /healthz=$hc"; exit 4; }

if [[ "$APP_START" == "1" && -s "$START_QUEUE" ]]; then
    log "3.1 服务健康后启动已安装 App，继续只采日志不截图"
    while IFS=$'\t' read -r serial safe pkg; do
        started=false
        "$ADB" -s "$serial" shell am force-stop "$pkg" >/dev/null 2>&1 < /dev/null || true
        if "$ADB" -s "$serial" shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 \
            > "$OUTPUT_DIR/adb-start-$safe.log" 2>&1 < /dev/null; then
            started=true
        elif "$ADB" -s "$serial" shell am start -n "$pkg/io.gomob.scan.MainActivity" \
            >> "$OUTPUT_DIR/adb-start-$safe.log" 2>&1 < /dev/null; then
            started=true
        fi
        printf '{"serial":"%s","app_started":%s}\n' "$(json_escape "$serial")" "$started" >> "$START_RESULTS"
    done < "$START_QUEUE"
fi

python3 - "$DEVICES_JSONL" "$START_RESULTS" "$CAPABILITIES" "$GATEWAY" "$WS_GATEWAY" "$APP_REVERSE_PORT" <<'PY'
import json
import sys

devices_path, starts_path, out_path, gateway, ws_gateway, app_reverse_port = sys.argv[1:7]
devices = []
try:
    with open(devices_path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                devices.append(json.loads(line))
except FileNotFoundError:
    pass

starts = {}
try:
    with open(starts_path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            starts[row.get("serial")] = bool(row.get("app_started"))
except FileNotFoundError:
    pass
for dev in devices:
    if dev.get("serial") in starts:
        dev["app_started"] = starts[dev.get("serial")]

cap = {
    "gateway": gateway,
    "ws_gateway": ws_gateway,
    "app_reverse_port": app_reverse_port,
    "emulator_count": sum(1 for d in devices if d.get("kind") == "emulator"),
    "physical_count": sum(1 for d in devices if d.get("kind") == "physical"),
    "devices": devices,
    "results": "results.jsonl",
    "live_control_plane": "results.jsonl 中 L1/L2 为能力探测，404/502 表示未实现而非通过",
    "screenshot_policy": "默认不截图，优先 logcat/server/results 分析",
}
with open(out_path, "w", encoding="utf-8") as f:
    json.dump(cap, f, ensure_ascii=False, indent=2)
    f.write("\n")
PY

log "4. 运行设备交互场景客户端"
"$SERVER_DIR/.dev/bin/gomob-deviceinteractionharness" \
    -gateway "$GATEWAY" \
    -ws "$WS_GATEWAY" \
    -out "$RESULTS" \
    > "$OUTPUT_DIR/deviceinteractionharness.log" 2>&1
HARNESS_RC=$?

sleep 1
for p in "${LOGCAT_PIDS[@]:-}"; do
    kill "$p" 2>/dev/null || true
done
LOGCAT_PIDS=()

log "5. 采样完成（client rc=$HARNESS_RC） → $RESULTS"
log "   日志目录：$OUTPUT_DIR"

# 业务失败交给 analyze.py 给三态结论；采样脚本只在基础设施失败时退出非零。
exit 0
