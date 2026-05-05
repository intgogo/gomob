#!/bin/bash
# admin_lifecycle/run.sh — M-S6 admin BFF 全链路 harness。
#
# 场景：
#   S1  缺鉴权 → 40102
#   S2  inspector 角色 → 40103
#   S3  注册 3 个 pending 用户
#   S4  admin 拉 pending 列表（≥3）
#   S5  approve alice
#   S6  reject bob
#   S7  重复 approve alice → 40401
#   S8  patch alice role=reviewer
#   S9  patch alice 不合法 role → 10002
#   S10 disable alice
#   S11 反代 catalog 创建车型
#   S12 反代 modelregistry 创建模型 + activate
#   S13 反代 llm 创建模板 + activate
#   S14 audit 全量列表（≥8 条）
#   S15 audit 按 action=user.approve 精确过滤（=1）
#   S16 audit 按 action=user.% ILIKE 过滤（≥4：approve/reject/patch/disable）
#   S17 audit 按 user_id=master 过滤
#   S18 audit 按 from 时间过滤（拿到 ≥1 条）

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVER_DIR="$PROJ_DIR/server"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/admin_lifecycle}"
mkdir -p "$OUTPUT_DIR"
RESULTS="$OUTPUT_DIR/results.jsonl"
: > "$RESULTS"

ADMIN=http://127.0.0.1:19090
AUTH=http://127.0.0.1:18082

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

# 0. 前置：清表（按 FK 顺序）
log "0. 前置：清表"
podman ps --format '{{.Names}}' | grep -qx gomob-pg || { log "缺 gomob-pg"; exit 2; }
# 按 FK 拓扑序删（叶子先于父）。用 stdin heredoc + ON_ERROR_STOP，避免 -c 多语句 batch 静默失败。
podman exec -i gomob-pg psql -U gomob -d gomob -v ON_ERROR_STOP=1 > /dev/null <<'SQL'
DELETE FROM audit_log;
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
  (SELECT id FROM stations ORDER BY id DESC LIMIT 1))
ON CONFLICT (username) DO NOTHING;
SQL
podman exec gomob-redis redis-cli FLUSHDB > /dev/null
ADMIN_ID=$(podman exec gomob-pg psql -U gomob -d gomob -tAc "SELECT id FROM users WHERE username='master'")

# 1. 编译 + 启动
log "1. 编译 + 启动 5 服务"
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-auth          ./cmd/auth)          || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-catalog       ./cmd/catalog)       || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-llmgateway    ./cmd/llmgateway)    || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-modelregistry ./cmd/modelregistry) || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-admin         ./cmd/admin)         || exit 3

# auth 关 dev autoactivate，让注册留 pending
GOMOB_AUTH_HTTP_ADDR=:18082 GOMOB_AUTH_DEV_AUTOACTIVATE=false \
    "$SERVER_DIR/.dev/bin/gomob-auth"          > "$OUTPUT_DIR/auth.log"          2>&1 &
PIDS+=($!)
GOMOB_CATALOG_HTTP_ADDR=:18059 \
    "$SERVER_DIR/.dev/bin/gomob-catalog"       > "$OUTPUT_DIR/catalog.log"       2>&1 &
PIDS+=($!)
GOMOB_LLM_HTTP_ADDR=:18811 \
    "$SERVER_DIR/.dev/bin/gomob-llmgateway"    > "$OUTPUT_DIR/llm.log"           2>&1 &
PIDS+=($!)
GOMOB_MODELREGISTRY_HTTP_ADDR=:18057 GOMOB_NATS_URL= \
    "$SERVER_DIR/.dev/bin/gomob-modelregistry" > "$OUTPUT_DIR/modelregistry.log" 2>&1 &
PIDS+=($!)
GOMOB_ADMIN_HTTP_ADDR=:19090 \
    "$SERVER_DIR/.dev/bin/gomob-admin"         > "$OUTPUT_DIR/admin.log"         2>&1 &
PIDS+=($!)
trap 'for p in "${PIDS[@]}"; do kill $p 2>/dev/null; done; wait 2>/dev/null' EXIT
sleep 1

hc=$(curl -s -o /dev/null -w '%{http_code}' "$ADMIN/healthz")
[[ "$hc" == "200" ]] || { log "admin 不通 $hc"; exit 4; }

# 2. 跑场景
log "2. 跑场景"

# S1 缺鉴权 → 40102
b=$(step "S1.no_auth_40102" 401 40102 "$ADMIN/admin/v1/users")

# S2 inspector → 40103
b=$(step "S2.inspector_403" 403 40103 "$ADMIN/admin/v1/users" \
    -H 'X-Gomob-User-Id: 99' -H 'X-Gomob-Roles: inspector')

# S3 注册 3 pending
SUFFIX=$$_$(date +%s%N)
ALICE=alice_$SUFFIX
BOB=bob_$SUFFIX
CAROL=carol_$SUFFIX
for u in "$ALICE" "$BOB" "$CAROL"; do
    curl -s -X POST "$AUTH/v1/auth/register" -H 'Content-Type: application/json' \
        -d "{\"username\":\"$u\",\"password\":\"pwd-1234\",\"real_name\":\"$u\",\"employee_id\":\"E${u}\"}" > /dev/null
done

# S4 admin 拉 pending（≥3）
b=$(step "S4.list_pending" 200 0 "$ADMIN/admin/v1/users?status=pending" \
    -H "X-Gomob-User-Id: $ADMIN_ID" -H 'X-Gomob-Roles: admin')
n=$(echo "$b" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["data"]["items"]))')
[[ "$n" -ge 3 ]] && S4OK=true || S4OK=false
record "S4b.pending_count" "$S4OK" 0 0 "" "" 0 "got=$n want>=3"

ALICE_ID=$(echo "$b" | python3 -c "import sys,json
items=json.load(sys.stdin)['data']['items']
print([u['id'] for u in items if u['real_name']=='$ALICE'][0])")
BOB_ID=$(echo "$b" | python3 -c "import sys,json
items=json.load(sys.stdin)['data']['items']
print([u['id'] for u in items if u['real_name']=='$BOB'][0])")

# S5 approve alice
b=$(step "S5.approve_alice" 200 0 -X POST "$ADMIN/admin/v1/users/$ALICE_ID/approve" \
    -H "X-Gomob-User-Id: $ADMIN_ID" -H 'X-Gomob-Roles: admin')

# S6 reject bob
b=$(step "S6.reject_bob" 200 0 -X POST "$ADMIN/admin/v1/users/$BOB_ID/reject" \
    -H "X-Gomob-User-Id: $ADMIN_ID" -H 'X-Gomob-Roles: admin')

# S7 重复 approve alice → 40401
b=$(step "S7.repeat_approve_40401" 409 40401 -X POST "$ADMIN/admin/v1/users/$ALICE_ID/approve" \
    -H "X-Gomob-User-Id: $ADMIN_ID" -H 'X-Gomob-Roles: admin')

# S8 patch alice role
b=$(step "S8.patch_role_reviewer" 200 0 -X PATCH "$ADMIN/admin/v1/users/$ALICE_ID" \
    -H "X-Gomob-User-Id: $ADMIN_ID" -H 'X-Gomob-Roles: admin' -H 'Content-Type: application/json' \
    -d '{"role":"reviewer"}')

# S9 不合法 role → 10002
b=$(step "S9.invalid_role_10002" 400 10002 -X PATCH "$ADMIN/admin/v1/users/$ALICE_ID" \
    -H "X-Gomob-User-Id: $ADMIN_ID" -H 'X-Gomob-Roles: admin' -H 'Content-Type: application/json' \
    -d '{"role":"hacker"}')

# S10 disable alice
b=$(step "S10.disable_alice" 200 0 -X POST "$ADMIN/admin/v1/users/$ALICE_ID/disable" \
    -H "X-Gomob-User-Id: $ADMIN_ID" -H 'X-Gomob-Roles: admin')

# S11 反代 catalog（make 带 SUFFIX 避免历史残留冲突）
b=$(step "S11.proxy_catalog" 200 0 -X POST "$ADMIN/admin/v1/catalog/vehicles" \
    -H "X-Gomob-User-Id: $ADMIN_ID" -H 'X-Gomob-Roles: admin' -H 'Content-Type: application/json' \
    -d "{\"make\":\"测试-$SUFFIX\",\"series\":\"X1\",\"year\":2024}")

# S12 反代 modelregistry：create + activate（验证子路径反代）
b=$(step "S12a.proxy_model_create" 200 0 -X POST "$ADMIN/admin/v1/models" \
    -H "X-Gomob-User-Id: $ADMIN_ID" -H 'X-Gomob-Roles: admin' -H 'Content-Type: application/json' \
    -d '{"name":"yolo_vin","version":"v1","asset_uri":"models/x","sha256":"abc"}')
M_ID=$(echo "$b" | extract data.id)
b=$(step "S12b.proxy_model_activate" 200 0 -X POST "$ADMIN/admin/v1/models/$M_ID/activate" \
    -H "X-Gomob-User-Id: $ADMIN_ID" -H 'X-Gomob-Roles: admin')

# S13 反代 llm：create + activate
b=$(step "S13a.proxy_llm_create" 200 0 -X POST "$ADMIN/admin/v1/llm/templates" \
    -H "X-Gomob-User-Id: $ADMIN_ID" -H 'X-Gomob-Roles: admin' -H 'Content-Type: application/json' \
    -d '{"name":"vin_audit","version":1,"user_template":"VIN={{.vin}}"}')
TPL_ID=$(echo "$b" | extract data.id)
b=$(step "S13b.proxy_llm_activate" 200 0 -X POST "$ADMIN/admin/v1/llm/templates/$TPL_ID/activate" \
    -H "X-Gomob-User-Id: $ADMIN_ID" -H 'X-Gomob-Roles: admin')

# S14 audit 全量
b=$(step "S14.audit_all" 200 0 "$ADMIN/admin/v1/audit?limit=50" \
    -H "X-Gomob-User-Id: $ADMIN_ID" -H 'X-Gomob-Roles: admin')
n=$(echo "$b" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["data"]["items"]))')
[[ "$n" -ge 8 ]] && S14OK=true || S14OK=false
record "S14b.audit_count" "$S14OK" 0 0 "" "" 0 "got=$n want>=8"

# S15 按 action 精确过滤
b=$(step "S15.audit_action_exact" 200 0 "$ADMIN/admin/v1/audit?action=user.approve" \
    -H "X-Gomob-User-Id: $ADMIN_ID" -H 'X-Gomob-Roles: admin')
n=$(echo "$b" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["data"]["items"]))')
[[ "$n" == "1" ]] && S15OK=true || S15OK=false
record "S15b.action_exact_count" "$S15OK" 0 0 "" "" 0 "got=$n want=1"

# S16 ILIKE
b=$(step "S16.audit_ilike" 200 0 "$ADMIN/admin/v1/audit?action=user.%" \
    -H "X-Gomob-User-Id: $ADMIN_ID" -H 'X-Gomob-Roles: admin')
n=$(echo "$b" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["data"]["items"]))')
[[ "$n" -ge 4 ]] && S16OK=true || S16OK=false
record "S16b.ilike_count" "$S16OK" 0 0 "" "" 0 "got=$n want>=4 (approve/reject/patch/disable)"

# S17 按 user_id（master）
b=$(step "S17.audit_by_user" 200 0 "$ADMIN/admin/v1/audit?user_id=$ADMIN_ID" \
    -H "X-Gomob-User-Id: $ADMIN_ID" -H 'X-Gomob-Roles: admin')
n=$(echo "$b" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["data"]["items"]))')
[[ "$n" -ge 5 ]] && S17OK=true || S17OK=false
record "S17b.by_user_count" "$S17OK" 0 0 "" "" 0 "got=$n want>=5"

# S18 按时间过滤（取最近 1 分钟）
FROM=$(date -u -d '-1 minute' +%Y-%m-%dT%H:%M:%SZ)
b=$(step "S18.audit_by_from" 200 0 "$ADMIN/admin/v1/audit?from=$FROM" \
    -H "X-Gomob-User-Id: $ADMIN_ID" -H 'X-Gomob-Roles: admin')
n=$(echo "$b" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["data"]["items"]))')
[[ "$n" -ge 1 ]] && S18OK=true || S18OK=false
record "S18b.from_count" "$S18OK" 0 0 "" "" 0 "got=$n want>=1 from=$FROM"

log "3. 采样完成 → $RESULTS"
log "   下一步：python3 $(dirname "$0")/analyze.py $OUTPUT_DIR"
