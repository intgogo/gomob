#!/bin/bash
# device_sync/run.sh — M-S3 device 服务全链路 harness（绑定 + 标定云同步）
#
# 端到端：bind → list → patch → calibration upload (v1/重传幂等/v2) → latest/list/byVersion
#         → retire → 跨用户转手 → admin 跨用户列 → audit
#
# 场景：
#   S1   缺鉴权 → 40102
#   S2   bind 缺字段（serial 空）→ 10002
#   S3   bind v1（demo01）→ is_new=true device_id=1
#   S4   bind 同 user 同 serial → is_new=false（幂等）
#   S5   bind 跨用户同 serial → 40203
#   S6   list mine → 1 条
#   S7   get（自己的）→ 200
#   S8   get 别人的 → 40301（屏蔽存在性）
#   S9   patch nickname + firmware
#   S10  touch → last_seen_at 写
#   S11  upload calibration v1 → version=1 is_new=true
#   S12  upload 同 sha256 → is_new=false 不 bump
#   S13  upload 新 sha256 → version=2 calibration_seq=2
#   S14  latest → version=2
#   S15  list calibrations include_params=false → 2 条 version DESC
#   S16  fetch by version=1 → 含 params
#   S17  fetch nonexistent version=99 → 40301
#   S18  retire → ok
#   S19  retired 后 patch → 40401
#   S20  retired 后 upload cal → 40401
#   S21  retired 后跨用户 bind 同 serial → 200（转手 device_id=2）
#   S22  inspector 调 admin → 40103
#   S23  admin AdminList → 含 2 条
#   S24  App 路径：gateway → device → 200
#   S25  audit ≥ 4 条 device.* 事件

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVER_DIR="$PROJ_DIR/server"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/device_sync}"
mkdir -p "$OUTPUT_DIR"
RESULTS="$OUTPUT_DIR/results.jsonl"
: > "$RESULTS"

ADMIN=http://127.0.0.1:19090
GATEWAY=http://127.0.0.1:18808
AUTH=http://127.0.0.1:18082
DEVICE=http://127.0.0.1:18086

log() { printf "[%s] %s\n" "$(date +%H:%M:%S)" "$*"; }

record() {
    local scenario=$1 ok=$2 http=$3 ex_http=$4 code=$5 ex_code=$6 lat=$7 note=${8:-}
    python3 -c "
import json
print(json.dumps({
    'scenario': '$scenario',
    'ok': '$ok' == 'true',
    'http_code': int('$http' or 0),
    'expected_http': int('$ex_http' or 0),
    'code': int('$code') if '$code' != '' else None,
    'expected_code': int('$ex_code') if '$ex_code' != '' else None,
    'latency_ms': float('$lat' or 0),
    'note': '''$note''',
}))
" >> "$RESULTS"
}

step() {
    local name=$1 ex_http=$2 ex_code=$3
    shift 3
    local t0 t1 lat http body code ok
    t0=$(date +%s%N)
    out=$(curl -s -o /tmp/curl-body.$$ -w '%{http_code}' "$@")
    t1=$(date +%s%N)
    lat=$(( (t1 - t0) / 1000000 ))
    http=$out
    body=$(cat /tmp/curl-body.$$)
    rm -f /tmp/curl-body.$$
    code=$(echo "$body" | python3 -c '
import json,sys
try: d=json.load(sys.stdin); print(d.get("code",""))
except Exception: print("")')
    if [[ "$http" == "$ex_http" ]] && [[ -z "$ex_code" || "$code" == "$ex_code" ]]; then
        ok=true
    else
        ok=false
    fi
    record "$name" "$ok" "$http" "$ex_http" "$code" "$ex_code" "$lat"
    echo "$body"
}

extract() {
    python3 -c "
import json,sys
try:
    d=json.load(sys.stdin); cur=d
    for k in sys.argv[1].split('.'): cur=cur[k]
    print(cur)
except Exception: pass
" "$1"
}

# ============================================================================
# 0. 前置：apply migration 0009 + 清表
# ============================================================================
log "0. 前置：应用 migration 0009 + 清表"
podman ps --format '{{.Names}}' | grep -qx gomob-pg    || { log "缺 gomob-pg";    exit 2; }
podman ps --format '{{.Names}}' | grep -qx gomob-redis || { log "缺 gomob-redis"; exit 2; }

HAS_DEV=$(podman exec -i gomob-pg psql -U gomob -d gomob -tAc \
    "SELECT 1 FROM information_schema.tables WHERE table_name='devices'")
if [[ -z "$HAS_DEV" ]]; then
    log "  应用 migrations/0009_devices.up.sql"
    podman exec -i gomob-pg psql -U gomob -d gomob -v ON_ERROR_STOP=1 \
        < "$SERVER_DIR/migrations/0009_devices.up.sql" > /dev/null
fi

# 清表
podman exec -i gomob-pg psql -U gomob -d gomob -v ON_ERROR_STOP=1 > /dev/null <<'SQL'
DELETE FROM device_calibrations;
DELETE FROM devices;
DELETE FROM audit_log;
DELETE FROM users WHERE username LIKE 'dev_%' OR username LIKE 'shenhm_dev_%';
SQL
podman exec gomob-redis redis-cli FLUSHDB > /dev/null

# 准备一个 admin 直接落库（避免依赖管理面注册流程）
podman exec -i gomob-pg psql -U gomob -d gomob -v ON_ERROR_STOP=1 > /dev/null <<'SQL'
INSERT INTO users(username, real_name, employee_id, password_hash, role, status)
VALUES('dev_admin','测试超管','DEV_ADM',
       '$2a$10$placeholderHash..............................',
       'admin','active')
ON CONFLICT (username) DO NOTHING;
SQL
ADMIN_ID=$(podman exec gomob-pg psql -U gomob -d gomob -tAc "SELECT id FROM users WHERE username='dev_admin'")
[[ -n "$ADMIN_ID" ]] || { log "✗ dev_admin user_id 拿不到"; exit 3; }
log "  ADMIN_ID=$ADMIN_ID"

# ============================================================================
# 1. 编译 + 启动 4 服务（auth + gateway + device + admin）
# ============================================================================
log "1. 编译 + 启动"
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-auth    ./cmd/auth)    || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-gateway ./cmd/gateway) || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-device  ./cmd/device)  || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-admin   ./cmd/admin)   || exit 3

PIDS=()
trap 'for p in "${PIDS[@]:-}"; do kill $p 2>/dev/null || true; done; wait 2>/dev/null || true' EXIT

GOMOB_AUTH_HTTP_ADDR=:18082 GOMOB_AUTH_DEV_AUTOACTIVATE=true \
    "$SERVER_DIR/.dev/bin/gomob-auth"     > "$OUTPUT_DIR/auth.log"     2>&1 &
PIDS+=($!)
GOMOB_DEVICE_HTTP_ADDR=:18086 \
    "$SERVER_DIR/.dev/bin/gomob-device"   > "$OUTPUT_DIR/device.log"   2>&1 &
PIDS+=($!)
GOMOB_ADMIN_HTTP_ADDR=:19090 GOMOB_DEVICE_TARGET=http://127.0.0.1:18086 \
    "$SERVER_DIR/.dev/bin/gomob-admin"    > "$OUTPUT_DIR/admin.log"    2>&1 &
PIDS+=($!)
GOMOB_GATEWAY_ADDR=:18808 GOMOB_REDIS_ADDR=127.0.0.1:6379 GOMOB_RATE_LIMIT=10000 \
GOMOB_DEVICE_TARGET=http://127.0.0.1:18086 \
    "$SERVER_DIR/.dev/bin/gomob-gateway"  > "$OUTPUT_DIR/gateway.log"  2>&1 &
PIDS+=($!)
sleep 1

for ep in "$ADMIN/healthz" "$DEVICE/healthz" "$AUTH/healthz" "$GATEWAY/healthz"; do
    hc=$(curl -s -o /dev/null -w '%{http_code}' "$ep")
    [[ "$hc" == "200" ]] || { log "✗ $ep 不通 $hc"; exit 4; }
done

# 准备两个 inspector 用户（demo + shenhm_dev）
SUFFIX=$$_$(date +%s)
USR_A="dev_demo_$SUFFIX"
USR_B="dev_shenhm_$SUFFIX"
for u in "$USR_A" "$USR_B"; do
    curl -s -o /dev/null -X POST "$AUTH/v1/auth/register" -H 'Content-Type: application/json' \
        -d "{\"username\":\"$u\",\"password\":\"pass-1\",\"real_name\":\"$u\",\"employee_id\":\"E$u\"}"
done
LOGIN_A=$(curl -s -X POST "$AUTH/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USR_A\",\"password\":\"pass-1\"}")
TOK_A=$(echo "$LOGIN_A" | extract data.access_token)
UID_A=$(echo "$LOGIN_A" | extract data.user.id)
LOGIN_B=$(curl -s -X POST "$AUTH/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USR_B\",\"password\":\"pass-1\"}")
TOK_B=$(echo "$LOGIN_B" | extract data.access_token)
UID_B=$(echo "$LOGIN_B" | extract data.user.id)
[[ -n "$TOK_A" && -n "$TOK_B" ]] || { log "✗ 注册/登录失败"; exit 5; }
log "  UID_A=$UID_A UID_B=$UID_B"

# ============================================================================
# 2. 跑场景
# ============================================================================
log "2. 跑场景"
ADMIN_HDRS=(-H "X-Gomob-User-Id: $ADMIN_ID" -H 'X-Gomob-Roles: admin')
HDRS_A=(-H "X-Gomob-User-Id: $UID_A" -H 'X-Gomob-Roles: inspector')
HDRS_B=(-H "X-Gomob-User-Id: $UID_B" -H 'X-Gomob-Roles: inspector')

# S1 缺鉴权（走 gateway）
step "S1.no_auth_40102" 401 40102 "$GATEWAY/v1/devices" > /dev/null

# S2 bind serial 空 → 10002（device 服务直连）
step "S2.bind_missing_serial" 400 10002 -X POST "$DEVICE/v1/devices" \
    "${HDRS_A[@]}" -H 'Content-Type: application/json' \
    -d '{"model":"iHawk","firmware_version":"1.2.3"}' > /dev/null

# S3 bind v1
b=$(step "S3.bind_first" 200 0 -X POST "$DEVICE/v1/devices" \
    "${HDRS_A[@]}" -H 'Content-Type: application/json' \
    -d '{"serial_number":"BERX-072-A1","model":"iHawk","firmware_version":"1.2.3","sdk_version":"v2.0.190","nickname":"iHawk-072"}')
DID_A=$(echo "$b" | extract data.device.id)
ISNEW=$(echo "$b" | extract data.is_new)
[[ -n "$DID_A" && "$ISNEW" == "True" ]] && S3OK=true || S3OK=false
record "S3b.is_new_true" "$S3OK" 0 0 "" "" 0 "id=$DID_A is_new=$ISNEW"

# S4 同 user 同 serial 幂等
b=$(step "S4.bind_idempotent" 200 0 -X POST "$DEVICE/v1/devices" \
    "${HDRS_A[@]}" -H 'Content-Type: application/json' \
    -d '{"serial_number":"BERX-072-A1","model":"iHawk","firmware_version":"1.2.4"}')
ISNEW=$(echo "$b" | extract data.is_new)
[[ "$ISNEW" == "False" ]] && S4OK=true || S4OK=false
record "S4b.is_new_false" "$S4OK" 0 0 "" "" 0 "is_new=$ISNEW"

# S5 跨用户同 serial → 40203
step "S5.bind_cross_user_40203" 409 40203 -X POST "$DEVICE/v1/devices" \
    "${HDRS_B[@]}" -H 'Content-Type: application/json' \
    -d '{"serial_number":"BERX-072-A1","model":"iHawk","firmware_version":"1.2.3"}' > /dev/null

# S6 list mine
b=$(step "S6.list_mine" 200 0 "$DEVICE/v1/devices" "${HDRS_A[@]}")
NDEV=$(echo "$b" | python3 -c 'import json,sys;print(len(json.load(sys.stdin)["data"]["items"]))')
[[ "$NDEV" == "1" ]] && S6OK=true || S6OK=false
record "S6b.list_count" "$S6OK" 0 0 "" "" 0 "n=$NDEV want=1"

# S7 get 自己的
step "S7.get_mine" 200 0 "$DEVICE/v1/devices/$DID_A" "${HDRS_A[@]}" > /dev/null

# S8 get 别人的 → 40301
step "S8.get_others_40301" 404 40301 "$DEVICE/v1/devices/$DID_A" "${HDRS_B[@]}" > /dev/null

# S9 patch
b=$(step "S9.patch" 200 0 -X PATCH "$DEVICE/v1/devices/$DID_A" \
    "${HDRS_A[@]}" -H 'Content-Type: application/json' \
    -d '{"nickname":"工位3 主机","firmware_version":"1.2.5"}')
NICK=$(echo "$b" | extract data.device.nickname)
FW=$(echo "$b" | extract data.device.firmware_version)
[[ "$FW" == "1.2.5" ]] && S9OK=true || S9OK=false
record "S9b.patch_applied" "$S9OK" 0 0 "" "" 0 "nick=$NICK fw=$FW want_fw=1.2.5"

# S10 touch
step "S10.touch" 200 0 -X POST "$DEVICE/v1/devices/$DID_A/touch" "${HDRS_A[@]}" > /dev/null
b=$(curl -s "$DEVICE/v1/devices/$DID_A" "${HDRS_A[@]}")
LAST_SEEN=$(echo "$b" | extract data.device.last_seen_at)
[[ -n "$LAST_SEEN" ]] && S10OK=true || S10OK=false
record "S10b.last_seen_set" "$S10OK" 0 0 "" "" 0 "last_seen=$LAST_SEEN"

# S11 upload calibration v1
b=$(step "S11.upload_v1" 200 0 -X POST "$DEVICE/v1/devices/$DID_A/calibrations" \
    "${HDRS_A[@]}" -H 'Content-Type: application/json' \
    -d '{"params":{"K":[[600,0,320],[0,600,240],[0,0,1]],"R":[[1,0,0],[0,1,0],[0,0,1]],"t":[0.05,0,0]},"sha256":"sha-aaa","reprojection_error":0.42,"calibrated_at":"2026-05-05T15:00:00Z"}')
V1=$(echo "$b" | extract data.calibration.version)
ISNEW=$(echo "$b" | extract data.is_new)
[[ "$V1" == "1" && "$ISNEW" == "True" ]] && S11OK=true || S11OK=false
record "S11b.v1_state" "$S11OK" 0 0 "" "" 0 "v=$V1 is_new=$ISNEW"

# S12 upload 同 sha256 → 不 bump
b=$(step "S12.upload_same_sha" 200 0 -X POST "$DEVICE/v1/devices/$DID_A/calibrations" \
    "${HDRS_A[@]}" -H 'Content-Type: application/json' \
    -d '{"params":{"different":true},"sha256":"sha-aaa","calibrated_at":"2026-05-05T15:00:00Z"}')
V=$(echo "$b" | extract data.calibration.version)
ISNEW=$(echo "$b" | extract data.is_new)
[[ "$V" == "1" && "$ISNEW" == "False" ]] && S12OK=true || S12OK=false
record "S12b.idempotent_no_bump" "$S12OK" 0 0 "" "" 0 "v=$V is_new=$ISNEW want v=1 is_new=False"

# S13 upload 新 sha256 → v2
b=$(step "S13.upload_v2" 200 0 -X POST "$DEVICE/v1/devices/$DID_A/calibrations" \
    "${HDRS_A[@]}" -H 'Content-Type: application/json' \
    -d '{"params":{"K":[[610,0,322],[0,610,241],[0,0,1]]},"sha256":"sha-bbb","reprojection_error":0.38,"calibrated_at":"2026-05-05T16:00:00Z"}')
V2=$(echo "$b" | extract data.calibration.version)
[[ "$V2" == "2" ]] && S13OK=true || S13OK=false
record "S13b.v2_bumped" "$S13OK" 0 0 "" "" 0 "v=$V2 want=2"

# S14 latest
b=$(step "S14.latest" 200 0 "$DEVICE/v1/devices/$DID_A/calibrations/latest" "${HDRS_A[@]}")
V=$(echo "$b" | extract data.calibration.version)
SHA=$(echo "$b" | extract data.calibration.sha256)
[[ "$V" == "2" && "$SHA" == "sha-bbb" ]] && S14OK=true || S14OK=false
record "S14b.latest_is_v2" "$S14OK" 0 0 "" "" 0 "v=$V sha=$SHA"

# S15 list calibrations include_params=false
b=$(step "S15.list_cals" 200 0 "$DEVICE/v1/devices/$DID_A/calibrations" "${HDRS_A[@]}")
N=$(echo "$b" | python3 -c 'import json,sys;print(len(json.load(sys.stdin)["data"]["items"]))')
FIRST_V=$(echo "$b" | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["items"][0]["version"])')
HAS_PARAMS=$(echo "$b" | python3 -c 'import json,sys;print("params" in json.load(sys.stdin)["data"]["items"][0])')
[[ "$N" == "2" && "$FIRST_V" == "2" && "$HAS_PARAMS" == "False" ]] && S15OK=true || S15OK=false
record "S15b.list_state" "$S15OK" 0 0 "" "" 0 "n=$N first_v=$FIRST_V has_params=$HAS_PARAMS"

# S16 fetch v1 → 含 params
b=$(step "S16.fetch_v1" 200 0 "$DEVICE/v1/devices/$DID_A/calibrations/1" "${HDRS_A[@]}")
HAS=$(echo "$b" | python3 -c 'import json,sys;print("params" in json.load(sys.stdin)["data"]["calibration"])')
[[ "$HAS" == "True" ]] && S16OK=true || S16OK=false
record "S16b.v1_has_params" "$S16OK" 0 0 "" "" 0 "has_params=$HAS"

# S17 fetch nonexistent
step "S17.fetch_v99_40301" 404 40301 "$DEVICE/v1/devices/$DID_A/calibrations/99" "${HDRS_A[@]}" > /dev/null

# S18 retire
step "S18.retire" 200 0 -X POST "$DEVICE/v1/devices/$DID_A/retire" "${HDRS_A[@]}" > /dev/null

# S19 retired 后 patch → 40401
step "S19.patch_retired_40401" 409 40401 -X PATCH "$DEVICE/v1/devices/$DID_A" \
    "${HDRS_A[@]}" -H 'Content-Type: application/json' \
    -d '{"nickname":"x"}' > /dev/null

# S20 retired 后 upload calibration → 40401
step "S20.upload_retired_40401" 409 40401 -X POST "$DEVICE/v1/devices/$DID_A/calibrations" \
    "${HDRS_A[@]}" -H 'Content-Type: application/json' \
    -d '{"params":{"a":1},"sha256":"sha-ccc","calibrated_at":"2026-05-05T17:00:00Z"}' > /dev/null

# S21 retired 后跨用户 bind 同 serial → 转手成功（device_id=2）
b=$(step "S21.bind_handover" 200 0 -X POST "$DEVICE/v1/devices" \
    "${HDRS_B[@]}" -H 'Content-Type: application/json' \
    -d '{"serial_number":"BERX-072-A1","model":"iHawk","firmware_version":"2.0.0","nickname":"shenhm-iHawk"}')
DID_B=$(echo "$b" | extract data.device.id)
ISNEW=$(echo "$b" | extract data.is_new)
[[ "$ISNEW" == "True" && "$DID_B" != "$DID_A" ]] && S21OK=true || S21OK=false
record "S21b.handover_new_id" "$S21OK" 0 0 "" "" 0 "new_id=$DID_B old_id=$DID_A is_new=$ISNEW"

# S22 inspector 调 admin → 40103
step "S22.inspector_admin_40103" 403 40103 \
    "$ADMIN/admin/v1/devices" "${HDRS_A[@]}" > /dev/null

# S23 admin AdminList → ≥2（A 旧 retired + B 新 active）
b=$(step "S23.admin_list" 200 0 "$ADMIN/admin/v1/devices" "${ADMIN_HDRS[@]}")
N=$(echo "$b" | python3 -c 'import json,sys;print(len(json.load(sys.stdin)["data"]["items"]))')
[[ "$N" -ge 2 ]] && S23OK=true || S23OK=false
record "S23b.admin_list_count" "$S23OK" 0 0 "" "" 0 "n=$N want>=2"

# S24 App 路径：gateway → device
b=$(step "S24.gateway_list" 200 0 "$GATEWAY/v1/devices" \
    -H "Authorization: Bearer $TOK_B")
N=$(echo "$b" | python3 -c 'import json,sys;print(len(json.load(sys.stdin)["data"]["items"]))')
[[ "$N" == "1" ]] && S24OK=true || S24OK=false
record "S24b.gateway_count" "$S24OK" 0 0 "" "" 0 "n=$N want=1"

# S25 audit ≥ 4（bind + patch + retire + calibration_upload）
b=$(curl -s "$ADMIN/admin/v1/audit?action=device.%&limit=50" "${ADMIN_HDRS[@]}")
N=$(echo "$b" | python3 -c 'import json,sys;print(len(json.load(sys.stdin)["data"]["items"]))')
[[ "$N" -ge 4 ]] && S25OK=true || S25OK=false
record "S25.audit_count" "$S25OK" 0 0 "" "" 0 "n=$N want>=4"

log "3. 采样完成 → $RESULTS"
log "   下一步：python3 $(dirname "$0")/analyze.py $OUTPUT_DIR"
