#!/bin/bash
# catalog_lifecycle/run.sh — M-S7 vehicle-catalog 全链路 harness 采样器。
#
# 场景：
#   S1 admin 直连 catalog 录入 3 款车型档案（draft）
#   S2 inspector 看 published 列表（应全空）
#   S3 admin publish 第 1 款
#   S4 inspector 看列表（1 项）
#   S5 inspector 详情：cache miss
#   S6 inspector 详情：cache hit
#   S7 admin patch 第 1 款（已 published 应 40401）
#   S8 admin patch 第 2 款（draft 可改）
#   S9 admin 重复创建（make/series/year 三元组冲突 → 40201）
#   S10 admin publish 第 2、3 款
#   S11 inspector 列表：3 项（按 cursor 分页 limit=2 试一下）
#   S12 inspector keyword 搜索（"A4" 命中奥迪）
#   S13 admin archive 第 1 款；archived 后再 publish → 40401
#   S14 inspector 看 published 列表（archived 不可见 → 2 项）
#   S15 audit_log 至少 8 条

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVER_DIR="$PROJ_DIR/server"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/catalog_lifecycle}"
mkdir -p "$OUTPUT_DIR"
RESULTS="$OUTPUT_DIR/results.jsonl"
: > "$RESULTS"

GATEWAY=http://127.0.0.1:18808
CATALOG=http://127.0.0.1:18059   # admin 直连（gateway 不暴露 /admin/*）

log() { printf "[%s] %s\n" "$(date +%H:%M:%S)" "$*"; }

record() {
    local scenario=$1 ok=$2 http=$3 ex_http=$4 code=$5 ex_code=$6 lat_ms=$7 note=${8:-}
    python3 -c "
import json
print(json.dumps({
    'scenario': '$scenario',
    'ok': '$ok' == 'true',
    'http_code': int('$http' or 0),
    'expected_http': int('$ex_http' or 0),
    'code': int('$code') if '$code' != '' else None,
    'expected_code': int('$ex_code') if '$ex_code' != '' else None,
    'latency_ms': float('$lat_ms' or 0),
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
try:
    d = json.load(sys.stdin)
    c = d.get("code")
    print(c if c is not None else "")
except Exception:
    print("")
')
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
    d = json.load(sys.stdin)
    keys = sys.argv[1].split('.')
    cur = d
    for k in keys: cur = cur[k]
    print(cur)
except Exception:
    pass
" "$1"
}

# ==========================================================================
# 0. 前置
# ==========================================================================
log "0. 前置"
need() { podman ps --format '{{.Names}}' | grep -qx "$1" || { log "缺 $1"; exit 2; }; }
need gomob-pg
need gomob-redis
podman exec gomob-redis redis-cli FLUSHDB > /dev/null
# 清掉已有车型档案，避免冲突
podman exec gomob-pg psql -U gomob -d gomob -c "DELETE FROM vehicle_models" > /dev/null

# ==========================================================================
# 1. 编译 + 启动 4 服务（auth + api + catalog + gateway）
# ==========================================================================
log "1. 编译 + 启动"
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-auth    ./cmd/auth)    || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-api     ./cmd/api)     || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-catalog ./cmd/catalog) || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-gateway ./cmd/gateway) || exit 3

GOMOB_AUTH_HTTP_ADDR=:18082 GOMOB_AUTH_DEV_AUTOACTIVATE=true \
    "$SERVER_DIR/.dev/bin/gomob-auth"    > "$OUTPUT_DIR/auth.log"    2>&1 &
AUTH_PID=$!
GOMOB_API_HTTP_ADDR=:18080 \
    "$SERVER_DIR/.dev/bin/gomob-api"     > "$OUTPUT_DIR/api.log"     2>&1 &
API_PID=$!
GOMOB_CATALOG_HTTP_ADDR=:18059 \
    "$SERVER_DIR/.dev/bin/gomob-catalog" > "$OUTPUT_DIR/catalog.log" 2>&1 &
CAT_PID=$!
GOMOB_GATEWAY_ADDR=:18808 GOMOB_REDIS_ADDR=127.0.0.1:6379 GOMOB_RATE_LIMIT=100000 \
    "$SERVER_DIR/.dev/bin/gomob-gateway" > "$OUTPUT_DIR/gateway.log" 2>&1 &
GW_PID=$!
trap "kill $AUTH_PID $API_PID $CAT_PID $GW_PID 2>/dev/null; wait 2>/dev/null" EXIT
sleep 1

hc=$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/healthz")
[[ "$hc" == "200" ]] || { log "gateway 不通 $hc"; exit 4; }

# ==========================================================================
# 2. 准备 admin + inspector 用户
# ==========================================================================
log "2. 准备用户"
SUFFIX=$$_$(date +%s%N)
PASS=pwd-1234
ADM=admin_$SUFFIX
INS=insp_$SUFFIX
curl -s -X POST "$GATEWAY/v1/auth/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADM\",\"password\":\"$PASS\",\"real_name\":\"Adm\",\"employee_id\":\"AA$SUFFIX\"}" > /dev/null
podman exec gomob-pg psql -U gomob -d gomob -c "UPDATE users SET role='admin' WHERE username='$ADM'" > /dev/null
ADM_TOK=$(curl -s -X POST "$GATEWAY/v1/auth/login" -H 'Content-Type: application/json' -d "{\"username\":\"$ADM\",\"password\":\"$PASS\"}" | extract data.access_token)

curl -s -X POST "$GATEWAY/v1/auth/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$INS\",\"password\":\"$PASS\",\"real_name\":\"Insp\",\"employee_id\":\"II$SUFFIX\"}" > /dev/null
INS_TOK=$(curl -s -X POST "$GATEWAY/v1/auth/login" -H 'Content-Type: application/json' -d "{\"username\":\"$INS\",\"password\":\"$PASS\"}" | extract data.access_token)
ADM_ID=$(curl -s "$GATEWAY/v1/me" -H "Authorization: Bearer $ADM_TOK" | extract data.id)

# ==========================================================================
# 3. 跑场景
# ==========================================================================
log "3. 跑场景"

# S1: 录入 3 款（admin 直连 catalog）
b=$(step "S1a.create_byd_han" 200 0 -X POST "$CATALOG/admin/v1/catalog/vehicles" \
    -H "X-Gomob-User-Id: $ADM_ID" -H 'X-Gomob-Roles: admin' -H 'Content-Type: application/json' \
    -d '{"make":"比亚迪","series":"汉","year":2024,"engine_type":"EV","outline_features":{"length_mm":4995},"compliance_check_list":["合规项-001"]}')
VM_BYD=$(echo "$b" | extract data.id)
b=$(step "S1b.create_audi_a4l" 200 0 -X POST "$CATALOG/admin/v1/catalog/vehicles" \
    -H "X-Gomob-User-Id: $ADM_ID" -H 'X-Gomob-Roles: admin' -H 'Content-Type: application/json' \
    -d '{"make":"奥迪","series":"A4L","year":2024,"engine_type":"ICE"}')
VM_AUDI=$(echo "$b" | extract data.id)
b=$(step "S1c.create_bmw_3" 200 0 -X POST "$CATALOG/admin/v1/catalog/vehicles" \
    -H "X-Gomob-User-Id: $ADM_ID" -H 'X-Gomob-Roles: admin' -H 'Content-Type: application/json' \
    -d '{"make":"宝马","series":"3系","year":2024}')
VM_BMW=$(echo "$b" | extract data.id)

# S2: inspector 列表全空
b=$(step "S2.inspector_list_empty" 200 0 "$GATEWAY/v1/catalog/vehicles" \
    -H "Authorization: Bearer $INS_TOK")
n=$(echo "$b" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["data"]["items"]))')
[[ "$n" == "0" ]] && S2OK=true || S2OK=false
record "S2b.list_count_zero" "$S2OK" 0 0 "" "" 0 "got=$n want=0"

# S3: publish 第 1 款
b=$(step "S3.publish_byd" 200 0 -X POST "$CATALOG/admin/v1/catalog/vehicles/$VM_BYD/publish" \
    -H "X-Gomob-User-Id: $ADM_ID" -H 'X-Gomob-Roles: admin')

# S4: inspector 列表 1 项
b=$(step "S4.inspector_list_1" 200 0 "$GATEWAY/v1/catalog/vehicles" \
    -H "Authorization: Bearer $INS_TOK")
n=$(echo "$b" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["data"]["items"]))')
[[ "$n" == "1" ]] && S4OK=true || S4OK=false
record "S4b.list_count_1" "$S4OK" 0 0 "" "" 0 "got=$n want=1"

# S5: 详情 cache miss
miss_code=$(curl -s -o /tmp/curl-body.$$ -w '%{http_code}' -D /tmp/curl-hdr.$$ \
    "$GATEWAY/v1/catalog/vehicles/$VM_BYD" -H "Authorization: Bearer $INS_TOK")
miss_cache=$(grep -i 'X-Gomob-Cache' /tmp/curl-hdr.$$ | tr -d '\r' | awk '{print $2}')
rm -f /tmp/curl-body.$$ /tmp/curl-hdr.$$
[[ "$miss_code" == "200" && "$miss_cache" == "miss" ]] && S5OK=true || S5OK=false
record "S5.cache_miss" "$S5OK" "$miss_code" 200 "" "" 0 "X-Gomob-Cache=$miss_cache want=miss"

# S6: 再 GET cache hit
hit_code=$(curl -s -o /tmp/curl-body.$$ -w '%{http_code}' -D /tmp/curl-hdr.$$ \
    "$GATEWAY/v1/catalog/vehicles/$VM_BYD" -H "Authorization: Bearer $INS_TOK")
hit_cache=$(grep -i 'X-Gomob-Cache' /tmp/curl-hdr.$$ | tr -d '\r' | awk '{print $2}')
rm -f /tmp/curl-body.$$ /tmp/curl-hdr.$$
[[ "$hit_code" == "200" && "$hit_cache" == "hit" ]] && S6OK=true || S6OK=false
record "S6.cache_hit" "$S6OK" "$hit_code" 200 "" "" 0 "X-Gomob-Cache=$hit_cache want=hit"

# S7: published 后 PATCH → 40401
b=$(step "S7.patch_published_conflict" 409 40401 -X PATCH "$CATALOG/admin/v1/catalog/vehicles/$VM_BYD" \
    -H "X-Gomob-User-Id: $ADM_ID" -H 'X-Gomob-Roles: admin' -H 'Content-Type: application/json' \
    -d '{"engine_type":"PHEV"}')

# S8: draft PATCH OK
b=$(step "S8.patch_draft_ok" 200 0 -X PATCH "$CATALOG/admin/v1/catalog/vehicles/$VM_AUDI" \
    -H "X-Gomob-User-Id: $ADM_ID" -H 'X-Gomob-Roles: admin' -H 'Content-Type: application/json' \
    -d '{"compliance_check_list":["A4-合规-001","A4-合规-002"]}')

# S9: 重复创建冲突
b=$(step "S9.duplicate_conflict" 409 40201 -X POST "$CATALOG/admin/v1/catalog/vehicles" \
    -H "X-Gomob-User-Id: $ADM_ID" -H 'X-Gomob-Roles: admin' -H 'Content-Type: application/json' \
    -d '{"make":"比亚迪","series":"汉","year":2024}')

# S10: publish 第 2、3 款
b=$(step "S10a.publish_audi" 200 0 -X POST "$CATALOG/admin/v1/catalog/vehicles/$VM_AUDI/publish" \
    -H "X-Gomob-User-Id: $ADM_ID" -H 'X-Gomob-Roles: admin')
b=$(step "S10b.publish_bmw" 200 0 -X POST "$CATALOG/admin/v1/catalog/vehicles/$VM_BMW/publish" \
    -H "X-Gomob-User-Id: $ADM_ID" -H 'X-Gomob-Roles: admin')

# S11: 列表 3 项
b=$(step "S11.list_three" 200 0 "$GATEWAY/v1/catalog/vehicles" \
    -H "Authorization: Bearer $INS_TOK")
n=$(echo "$b" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["data"]["items"]))')
[[ "$n" == "3" ]] && S11OK=true || S11OK=false
record "S11b.list_count_3" "$S11OK" 0 0 "" "" 0 "got=$n want=3"

# S12: keyword 搜索 A4
b=$(step "S12.keyword_a4" 200 0 "$GATEWAY/v1/catalog/vehicles?keyword=A4" \
    -H "Authorization: Bearer $INS_TOK")
n=$(echo "$b" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["data"]["items"]))')
[[ "$n" == "1" ]] && S12OK=true || S12OK=false
record "S12b.keyword_count_1" "$S12OK" 0 0 "" "" 0 "got=$n want=1"

# S13: archive 第 1 款 + 之后 publish → 40401
b=$(step "S13a.archive_byd" 200 0 -X POST "$CATALOG/admin/v1/catalog/vehicles/$VM_BYD/archive" \
    -H "X-Gomob-User-Id: $ADM_ID" -H 'X-Gomob-Roles: admin')
b=$(step "S13b.republish_archived_conflict" 409 40401 -X POST "$CATALOG/admin/v1/catalog/vehicles/$VM_BYD/publish" \
    -H "X-Gomob-User-Id: $ADM_ID" -H 'X-Gomob-Roles: admin')

# S14: archive 后 inspector 看不到
b=$(step "S14.list_after_archive" 200 0 "$GATEWAY/v1/catalog/vehicles" \
    -H "Authorization: Bearer $INS_TOK")
n=$(echo "$b" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["data"]["items"]))')
[[ "$n" == "2" ]] && S14OK=true || S14OK=false
record "S14b.list_count_2" "$S14OK" 0 0 "" "" 0 "got=$n want=2"

# S15: audit_log 至少 8 条 catalog.* 记录
n=$(podman exec gomob-pg psql -U gomob -d gomob -tAc "SELECT count(*) FROM audit_log WHERE action LIKE 'catalog%'")
[[ $n -ge 8 ]] && S15OK=true || S15OK=false
record "S15.audit_count" "$S15OK" 0 0 "" "" 0 "audit_count=$n want>=8"

log "4. 采样完成 → $RESULTS"
log "   下一步：python3 $(dirname "$0")/analyze.py $OUTPUT_DIR"
