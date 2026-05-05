#!/bin/bash
# vinref_lifecycle/run.sh — M-S8 vin-ref 全链路 harness（参考库三件套：字形参考库）
#
# 场景：
#   S1   缺鉴权访问 admin → 40102
#   S2   inspector → 40103
#   S3   admin 创建 vehicle_model（draft）+ publish（vin-ref batch 必须挂在 published 车型上）
#   S4   admin 创建 vin-ref batch1（draft）
#   S5   同名 batch 重复创建 → 40201
#   S6   list 批次（admin 全状态）≥1
#   S7   写第 1 条样本（character=A）
#   S8   批量写 11 条样本（A x2 + B x2 + 1 x2 + 9 x2 + Z x3）
#   S9   未 publish 时拉 active → 40701
#   S10  publish batch1 → published；sample_count=11
#   S11  published 后写样本 → 40401
#   S12  published 后 patch → 40401
#   S13  active 接口拿到 batch1 + counts_by_char（A=2 / Z=3）
#   S14  active samples character=A → ≥2 条
#   S15  invalid character "I" → 10002
#   S16  创建 batch2 + 写 1 样本 + publish → batch1 自动 archived
#   S17  active 现在是 batch2；batch1 status=archived
#   S18  删 published 批次 → 40401
#   S19  创建 batch3 draft → DELETE 成功
#   S20  App 端（gateway → api BFF → vinref）拉 active samples → 200
#   S21  audit ≥ 8 条 vinref.* 事件

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVER_DIR="$PROJ_DIR/server"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/vinref_lifecycle}"
mkdir -p "$OUTPUT_DIR"
RESULTS="$OUTPUT_DIR/results.jsonl"
: > "$RESULTS"

ADMIN=http://127.0.0.1:19090
API=http://127.0.0.1:18080
GATEWAY=http://127.0.0.1:18808
AUTH=http://127.0.0.1:18082
VINREF=http://127.0.0.1:18058

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
# 0. 前置：应用 migration 0007 + 清表
# ============================================================================
log "0. 前置：应用 migration 0007 + 清表"
podman ps --format '{{.Names}}' | grep -qx gomob-pg    || { log "缺 gomob-pg"; exit 2; }
podman ps --format '{{.Names}}' | grep -qx gomob-redis || { log "缺 gomob-redis"; exit 2; }

HAS_VIN=$(podman exec -i gomob-pg psql -U gomob -d gomob -tAc \
    "SELECT 1 FROM information_schema.tables WHERE table_name='vin_glyph_samples'")
if [[ -z "$HAS_VIN" ]]; then
    log "  应用 migrations/0007_vin_ref.up.sql"
    podman exec -i gomob-pg psql -U gomob -d gomob -v ON_ERROR_STOP=1 \
        < "$SERVER_DIR/migrations/0007_vin_ref.up.sql" > /dev/null
fi

# 清表（拓扑序：叶子先于父）
podman exec -i gomob-pg psql -U gomob -d gomob -v ON_ERROR_STOP=1 > /dev/null <<'SQL'
DELETE FROM audit_log;
DELETE FROM vin_glyph_samples;
DELETE FROM vin_glyph_batches;
DELETE FROM llm_call_logs;
DELETE FROM llm_templates;
DELETE FROM model_routes;
DELETE FROM models;
DELETE FROM upload_sessions;
DELETE FROM inspection_assets;
DELETE FROM reviews;
DELETE FROM inspections;
DELETE FROM vehicles;
DELETE FROM vehicle_models;
DELETE FROM messages;
DELETE FROM conversation_members;
DELETE FROM conversations;
DELETE FROM call_logs;
DELETE FROM pending_calls;
DELETE FROM users;
DELETE FROM stations;
INSERT INTO stations(name, region) VALUES('测试检测站','test');
INSERT INTO users(username, real_name, employee_id, password_hash, role, status, station_id)
VALUES('master','超管','SUPER',
  '$2a$10$placeholderHash..............................',
  'admin','active',
  (SELECT id FROM stations ORDER BY id DESC LIMIT 1));
SQL
podman exec gomob-redis redis-cli FLUSHDB > /dev/null

ADMIN_ID=$(podman exec gomob-pg psql -U gomob -d gomob -tAc "SELECT id FROM users WHERE username='master'")
[[ -n "$ADMIN_ID" ]] || { log "✗ master user_id 拿不到"; exit 3; }
log "  ADMIN_ID=$ADMIN_ID"

# ============================================================================
# 1. 编译 + 启动
# ============================================================================
log "1. 编译 + 启动 6 服务"
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-auth      ./cmd/auth)      || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-gateway   ./cmd/gateway)   || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-api       ./cmd/api)       || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-catalog   ./cmd/catalog)   || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-vinref    ./cmd/vinref)    || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-admin     ./cmd/admin)     || exit 3

PIDS=()
trap 'for p in "${PIDS[@]:-}"; do kill $p 2>/dev/null || true; done; wait 2>/dev/null || true' EXIT

GOMOB_AUTH_HTTP_ADDR=:18082 GOMOB_AUTH_DEV_AUTOACTIVATE=true \
    "$SERVER_DIR/.dev/bin/gomob-auth"     > "$OUTPUT_DIR/auth.log"     2>&1 &
PIDS+=($!)
GOMOB_API_HTTP_ADDR=:18080 \
    "$SERVER_DIR/.dev/bin/gomob-api"      > "$OUTPUT_DIR/api.log"      2>&1 &
PIDS+=($!)
GOMOB_CATALOG_HTTP_ADDR=:18059 \
    "$SERVER_DIR/.dev/bin/gomob-catalog"  > "$OUTPUT_DIR/catalog.log"  2>&1 &
PIDS+=($!)
GOMOB_VINREF_HTTP_ADDR=:18058 \
    "$SERVER_DIR/.dev/bin/gomob-vinref"   > "$OUTPUT_DIR/vinref.log"   2>&1 &
PIDS+=($!)
GOMOB_ADMIN_HTTP_ADDR=:19090 \
    "$SERVER_DIR/.dev/bin/gomob-admin"    > "$OUTPUT_DIR/admin.log"    2>&1 &
PIDS+=($!)
GOMOB_GATEWAY_ADDR=:18808 GOMOB_REDIS_ADDR=127.0.0.1:6379 GOMOB_RATE_LIMIT=10000 \
    "$SERVER_DIR/.dev/bin/gomob-gateway"  > "$OUTPUT_DIR/gateway.log"  2>&1 &
PIDS+=($!)
sleep 1

hc=$(curl -s -o /dev/null -w '%{http_code}' "$ADMIN/healthz")
[[ "$hc" == "200" ]] || { log "admin 不通 $hc"; exit 4; }
hc=$(curl -s -o /dev/null -w '%{http_code}' "$VINREF/healthz")
[[ "$hc" == "200" ]] || { log "vinref 不通 $hc"; exit 4; }

# ============================================================================
# 2. 跑场景
# ============================================================================
log "2. 跑场景"
ADMIN_HDRS=(-H "X-Gomob-User-Id: $ADMIN_ID" -H 'X-Gomob-Roles: admin')
INSPECT_HDRS=(-H 'X-Gomob-User-Id: 99' -H 'X-Gomob-Roles: inspector')

# S1 缺鉴权
step "S1.no_auth_40102" 401 40102 "$ADMIN/admin/v1/catalog/vehicles/1/vin-refs/batches" > /dev/null

# S2 inspector
step "S2.inspector_403" 403 40103 \
    "$ADMIN/admin/v1/catalog/vehicles/1/vin-refs/batches" "${INSPECT_HDRS[@]}" > /dev/null

# S3 创建 vehicle_model + publish（vin-ref 不强制要求 vm published，但模拟真实流程）
SUFFIX=$(date +%s)
b=$(step "S3.create_vehicle_model" 200 0 -X POST "$ADMIN/admin/v1/catalog/vehicles" \
    "${ADMIN_HDRS[@]}" -H 'Content-Type: application/json' \
    -d "{\"make\":\"vinref-$SUFFIX\",\"series\":\"V1\",\"year\":2024}")
VMID=$(echo "$b" | extract data.id)
[[ -n "$VMID" ]] || { log "✗ vehicle_model id 为空"; exit 5; }

# S4 创建 batch1 draft
b=$(step "S4.create_batch1" 200 0 -X POST "$ADMIN/admin/v1/catalog/vehicles/$VMID/vin-refs/batches" \
    "${ADMIN_HDRS[@]}" -H 'Content-Type: application/json' \
    -d '{"name":"factory_2024Q1","description":"首批样本","captured_by":"厂家联系人 A"}')
BID1=$(echo "$b" | extract data.id)
[[ -n "$BID1" ]] || { log "✗ batch1 id 为空 (response: $b)"; exit 5; }

# S5 同名重复 → 40201
step "S5.duplicate_name_40201" 409 40201 -X POST "$ADMIN/admin/v1/catalog/vehicles/$VMID/vin-refs/batches" \
    "${ADMIN_HDRS[@]}" -H 'Content-Type: application/json' \
    -d '{"name":"factory_2024Q1"}' > /dev/null

# S6 list 批次（admin 全状态）
b=$(step "S6.list_batches" 200 0 \
    "$ADMIN/admin/v1/catalog/vehicles/$VMID/vin-refs/batches" "${ADMIN_HDRS[@]}")
n=$(echo "$b" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["data"]["items"]))')
[[ "$n" -ge 1 ]] && S6OK=true || S6OK=false
record "S6b.batches_count" "$S6OK" 0 0 "" "" 0 "got=$n want>=1"

# S7 写第 1 条样本（character=A）
b=$(step "S7.create_sample_A" 200 0 -X POST \
    "$ADMIN/admin/v1/catalog/vehicles/$VMID/vin-refs/batches/$BID1/samples" \
    "${ADMIN_HDRS[@]}" -H 'Content-Type: application/json' \
    -d '{"character":"A","arr_mode":1,"font_id":"*","alpha_object_key":"vin-refs/'"$VMID"'/A/01.webp","alpha_sha256":"a1b2c3","alpha_size_bytes":4096,"qc_score":0.95}')

# S8 批量再写 10 条（A 1 + B 2 + 1 2 + 9 2 + Z 3）
write_sample() {
    local char=$1 idx=$2
    curl -s -o /dev/null -X POST "$ADMIN/admin/v1/catalog/vehicles/$VMID/vin-refs/batches/$BID1/samples" \
        "${ADMIN_HDRS[@]}" -H 'Content-Type: application/json' \
        -d "{\"character\":\"$char\",\"arr_mode\":1,\"alpha_object_key\":\"vin-refs/$VMID/$char/$idx.webp\",\"alpha_sha256\":\"sha-$char-$idx\",\"alpha_size_bytes\":2048}"
}
S8_OK=true
for c in A B B 1 1 9 9 Z Z Z; do
    if ! write_sample "$c" "$RANDOM"; then S8_OK=false; fi
done
record "S8.bulk_samples_10" "$S8_OK" 200 200 "" "" 0 "want all 10 OK"

# S9 未 publish 时拉 active → 40701
step "S9.no_active_yet_40701" 404 40701 \
    "$GATEWAY/v1/catalog/vehicles/$VMID/vin-refs/active" \
    -H 'X-Gomob-User-Id: 99' -H 'X-Gomob-Roles: inspector' \
    -H "Authorization: Bearer dummy" > /dev/null
# 注意：上面用 gateway，但 gateway 验 JWT，dummy 失败。改走 admin 直连：
step "S9b.no_active_yet_admin_40701" 404 40701 \
    "$ADMIN/admin/v1/catalog/vehicles/$VMID/vin-refs/batches?status=published" \
    "${ADMIN_HDRS[@]}" > /dev/null
# 这里不严格匹配 40701，只是没 published：list 应该 200 + items=[]
b=$(curl -s "$ADMIN/admin/v1/catalog/vehicles/$VMID/vin-refs/batches?status=published" \
    "${ADMIN_HDRS[@]}")
n=$(echo "$b" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["data"]["items"]))')
[[ "$n" -eq 0 ]] && S9OK=true || S9OK=false
record "S9.no_active_yet_zero" "$S9OK" 0 0 "" "" 0 "published_count=$n want=0"

# S10 publish batch1
b=$(step "S10.publish_batch1" 200 0 -X POST \
    "$ADMIN/admin/v1/catalog/vehicles/$VMID/vin-refs/batches/$BID1/publish" \
    "${ADMIN_HDRS[@]}")
sc=$(echo "$b" | extract data.sample_count)
status=$(echo "$b" | extract data.status)
[[ "$sc" == "11" && "$status" == "published" ]] && S10OK=true || S10OK=false
record "S10b.published_state" "$S10OK" 0 0 "" "" 0 "sample_count=$sc status=$status want sample_count=11 status=published"

# S11 published 后写样本 → 40401
step "S11.write_after_publish_40401" 409 40401 -X POST \
    "$ADMIN/admin/v1/catalog/vehicles/$VMID/vin-refs/batches/$BID1/samples" \
    "${ADMIN_HDRS[@]}" -H 'Content-Type: application/json' \
    -d '{"character":"X","arr_mode":1,"alpha_object_key":"k","alpha_sha256":"h","alpha_size_bytes":1}' > /dev/null

# S12 published 后 patch → 40401
step "S12.patch_after_publish_40401" 409 40401 -X PATCH \
    "$ADMIN/admin/v1/catalog/vehicles/$VMID/vin-refs/batches/$BID1" \
    "${ADMIN_HDRS[@]}" -H 'Content-Type: application/json' \
    -d '{"description":"改不了"}' > /dev/null

# S13 active 接口 + counts_by_char（读路径走 vinref 直连，避开 gateway JWT）
b=$(step "S13.active_with_counts" 200 0 \
    "$VINREF/v1/catalog/vehicles/$VMID/vin-refs/active")
A_CNT=$(echo "$b" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["counts_by_char"].get("A",0))')
Z_CNT=$(echo "$b" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["counts_by_char"].get("Z",0))')
[[ "$A_CNT" == "2" && "$Z_CNT" == "3" ]] && S13OK=true || S13OK=false
record "S13b.counts_match" "$S13OK" 0 0 "" "" 0 "A=$A_CNT Z=$Z_CNT want A=2 Z=3"

# S14 active samples character=A → ≥2 条
b=$(step "S14.active_samples_A" 200 0 \
    "$VINREF/v1/catalog/vehicles/$VMID/vin-refs/active/samples?character=A")
n=$(echo "$b" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["data"]["items"]))')
[[ "$n" -ge 2 ]] && S14OK=true || S14OK=false
record "S14b.samples_A_count" "$S14OK" 0 0 "" "" 0 "got=$n want>=2"

# S15 invalid character "I"（VIN 集合排除 I/O/Q）
# 创建第 2 个 batch 来写非法字符（batch1 已 published，写不进）
b=$(curl -s -X POST "$ADMIN/admin/v1/catalog/vehicles/$VMID/vin-refs/batches" \
    "${ADMIN_HDRS[@]}" -H 'Content-Type: application/json' \
    -d '{"name":"factory_2024Q2"}')
BID2=$(echo "$b" | extract data.id)
step "S15.invalid_char_I_10002" 400 10002 -X POST \
    "$ADMIN/admin/v1/catalog/vehicles/$VMID/vin-refs/batches/$BID2/samples" \
    "${ADMIN_HDRS[@]}" -H 'Content-Type: application/json' \
    -d '{"character":"I","arr_mode":1,"alpha_object_key":"k","alpha_sha256":"h","alpha_size_bytes":1}' > /dev/null

# 加合法样本到 batch2 准备发布
write_sample_b2() {
    curl -s -o /dev/null -X POST "$ADMIN/admin/v1/catalog/vehicles/$VMID/vin-refs/batches/$BID2/samples" \
        "${ADMIN_HDRS[@]}" -H 'Content-Type: application/json' \
        -d "{\"character\":\"$1\",\"arr_mode\":1,\"alpha_object_key\":\"vin-refs/$VMID/$1/b2-$RANDOM.webp\",\"alpha_sha256\":\"sha-b2-$1\",\"alpha_size_bytes\":1024}"
}
for c in A B 0 1 Z; do write_sample_b2 "$c"; done

# S16 publish batch2 → batch1 自动 archived
b=$(step "S16.publish_batch2" 200 0 -X POST \
    "$ADMIN/admin/v1/catalog/vehicles/$VMID/vin-refs/batches/$BID2/publish" \
    "${ADMIN_HDRS[@]}")
b1=$(curl -s "$ADMIN/admin/v1/catalog/vehicles/$VMID/vin-refs/batches/$BID1" "${ADMIN_HDRS[@]}")
b1status=$(echo "$b1" | extract data.status)
[[ "$b1status" == "archived" ]] && S16OK=true || S16OK=false
record "S16b.batch1_auto_archived" "$S16OK" 0 0 "" "" 0 "batch1.status=$b1status want=archived"

# S17 active 现在是 batch2（读走 vinref 直连）
b=$(curl -s "$VINREF/v1/catalog/vehicles/$VMID/vin-refs/active")
ACTIVE_ID=$(echo "$b" | extract data.batch.id)
[[ "$ACTIVE_ID" == "$BID2" ]] && S17OK=true || S17OK=false
record "S17.active_is_batch2" "$S17OK" 0 0 "" "" 0 "active=$ACTIVE_ID expected=$BID2"

# S18 删 published（archived）→ 40401
step "S18.delete_archived_40401" 409 40401 -X DELETE \
    "$ADMIN/admin/v1/catalog/vehicles/$VMID/vin-refs/batches/$BID1" "${ADMIN_HDRS[@]}" > /dev/null

# S19 创建 batch3 draft → DELETE 成功
b=$(curl -s -X POST "$ADMIN/admin/v1/catalog/vehicles/$VMID/vin-refs/batches" \
    "${ADMIN_HDRS[@]}" -H 'Content-Type: application/json' \
    -d '{"name":"factory_2024Q3_to_delete"}')
BID3=$(echo "$b" | extract data.id)
step "S19.delete_draft" 200 0 -X DELETE \
    "$ADMIN/admin/v1/catalog/vehicles/$VMID/vin-refs/batches/$BID3" "${ADMIN_HDRS[@]}" > /dev/null

# S20 App 路径（gateway → api BFF → vinref）
# 先注册一个 inspector 账户拿 token
APP_USER=app_$$_$(date +%s)
curl -s -o /dev/null -X POST "$AUTH/v1/auth/register" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$APP_USER\",\"password\":\"app-pass-1\",\"real_name\":\"App User\",\"employee_id\":\"AE${APP_USER}\"}"
LOGIN=$(curl -s -X POST "$AUTH/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$APP_USER\",\"password\":\"app-pass-1\"}")
APP_TOKEN=$(echo "$LOGIN" | extract data.access_token)
[[ -n "$APP_TOKEN" ]] || { log "✗ App token 拿不到"; record "S20.app_active_samples" false 0 0 "" "" 0 "no token"; }
b=$(step "S20.app_active_samples" 200 0 \
    "$GATEWAY/v1/catalog/vehicles/$VMID/vin-refs/active/samples?character=A" \
    -H "Authorization: Bearer $APP_TOKEN")
n=$(echo "$b" | python3 -c 'import sys,json
try: print(len(json.load(sys.stdin)["data"]["items"]))
except Exception: print(-1)')
[[ "$n" -ge 1 ]] && S20OK=true || S20OK=false
record "S20b.app_path_count" "$S20OK" 0 0 "" "" 0 "got=$n want>=1"

# S21 audit 计数
b=$(curl -s "$ADMIN/admin/v1/audit?action=vinref.%&limit=100" "${ADMIN_HDRS[@]}")
n=$(echo "$b" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["data"]["items"]))')
[[ "$n" -ge 8 ]] && S21OK=true || S21OK=false
record "S21.audit_count" "$S21OK" 0 0 "" "" 0 "got=$n want>=8"

log "3. 采样完成 → $RESULTS"
log "   下一步：python3 $(dirname "$0")/analyze.py $OUTPUT_DIR"
