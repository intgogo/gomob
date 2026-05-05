#!/bin/bash
# llm_streaming/run.sh — M-S11 llm-gateway 流式 / 模板 / 审计 / 取消上游 全链路 harness。
#
# 不依赖外网（mock provider）；如设 GOMOB_DEEPSEEK_API_KEY 则会同时跑 deepseek 一遍。
#
# 场景：
#   S1  admin 创建模板 v1 (draft)
#   S2  inspector 调 chat，模板未 active → 40601
#   S3  activate 模板
#   S4  非流式 chat 通过 gateway
#   S5  SSE 流式 chat，验证 meta+delta+done 三类事件
#   S6  客户端早断（--max-time 极小）→ llm_call_logs 出现 cancelled 记录
#   S7  缺 var → 40601 + 错误信息含 missing key
#   S8  inspector 调写路径 → 40103
#   S9  重复 (name,version) → 40201
#   S10 模板 v2 创建并激活，自动把 v1 归档
#   S11 archive v2 → 列表为空
#   S12 llm_call_logs 至少 3 条（含 ok + cancelled）

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVER_DIR="$PROJ_DIR/server"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/llm_streaming}"
mkdir -p "$OUTPUT_DIR"
RESULTS="$OUTPUT_DIR/results.jsonl"
: > "$RESULTS"

GATEWAY=http://127.0.0.1:18808
LLM=http://127.0.0.1:18811   # admin 直连

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
try:
    d = json.load(sys.stdin)
    print(d.get("code",""))
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
    d = json.load(sys.stdin); cur = d
    for k in sys.argv[1].split('.'): cur = cur[k]
    print(cur)
except Exception: pass
" "$1"
}

# 0. 前置
log "0. 前置"
podman ps --format '{{.Names}}' | grep -qx gomob-pg || { log "缺 gomob-pg"; exit 2; }
podman ps --format '{{.Names}}' | grep -qx gomob-redis || { log "缺 gomob-redis"; exit 2; }
podman exec gomob-redis redis-cli FLUSHDB > /dev/null
podman exec gomob-pg psql -U gomob -d gomob -c "DELETE FROM llm_call_logs; DELETE FROM llm_templates;" > /dev/null

# 1. 编译启动
log "1. 编译 + 启动"
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-auth        ./cmd/auth)        || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-llmgateway  ./cmd/llmgateway)  || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-gateway     ./cmd/gateway)     || exit 3

GOMOB_AUTH_HTTP_ADDR=:18082 GOMOB_AUTH_DEV_AUTOACTIVATE=true \
    "$SERVER_DIR/.dev/bin/gomob-auth"        > "$OUTPUT_DIR/auth.log"        2>&1 &
AUTH_PID=$!
GOMOB_LLM_HTTP_ADDR=:18811 \
    "$SERVER_DIR/.dev/bin/gomob-llmgateway"  > "$OUTPUT_DIR/llm.log"         2>&1 &
LLM_PID=$!
GOMOB_GATEWAY_ADDR=:18808 GOMOB_REDIS_ADDR=127.0.0.1:6379 GOMOB_RATE_LIMIT=100000 \
    "$SERVER_DIR/.dev/bin/gomob-gateway"     > "$OUTPUT_DIR/gateway.log"     2>&1 &
GW_PID=$!
trap "kill $AUTH_PID $LLM_PID $GW_PID 2>/dev/null; wait 2>/dev/null" EXIT
sleep 1

hc=$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/healthz")
[[ "$hc" == "200" ]] || { log "gateway 不通"; exit 4; }

# 2. 准备 admin + inspector
SUFFIX=$$_$(date +%s%N)
PASS=pwd-1234
ADM=adm_$SUFFIX
INS=ins_$SUFFIX
curl -s -X POST "$GATEWAY/v1/auth/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADM\",\"password\":\"$PASS\",\"real_name\":\"Adm\",\"employee_id\":\"AA$SUFFIX\"}" > /dev/null
podman exec gomob-pg psql -U gomob -d gomob -c "UPDATE users SET role='admin' WHERE username='$ADM'" > /dev/null
ADM_TOK=$(curl -s -X POST "$GATEWAY/v1/auth/login" -H 'Content-Type: application/json' -d "{\"username\":\"$ADM\",\"password\":\"$PASS\"}" | extract data.access_token)
ADM_ID=$(curl -s "$GATEWAY/v1/me" -H "Authorization: Bearer $ADM_TOK" | extract data.id)

curl -s -X POST "$GATEWAY/v1/auth/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$INS\",\"password\":\"$PASS\",\"real_name\":\"Ins\",\"employee_id\":\"II$SUFFIX\"}" > /dev/null
INS_TOK=$(curl -s -X POST "$GATEWAY/v1/auth/login" -H 'Content-Type: application/json' -d "{\"username\":\"$INS\",\"password\":\"$PASS\"}" | extract data.access_token)
INS_ID=$(curl -s "$GATEWAY/v1/me" -H "Authorization: Bearer $INS_TOK" | extract data.id)

# 3. 跑场景
log "3. 跑场景"

# S1 创建 v1 draft
b=$(step "S1.create_v1_draft" 200 0 -X POST "$LLM/admin/v1/llm/templates" \
    -H "X-Gomob-User-Id: $ADM_ID" -H 'X-Gomob-Roles: admin' -H 'Content-Type: application/json' \
    -d '{"name":"vin_audit","version":1,"preferred_provider":"mock","system_prompt":"VIN 审核","user_template":"VIN={{.vin}} conf={{.confidence}}","vars_schema":{"vin":"string","confidence":"number"}}')
TPL_V1=$(echo "$b" | extract data.id)

# S2 chat 模板未 active → 40601
b=$(step "S2.chat_inactive_40601" 422 40601 -X POST "$GATEWAY/v1/llm/chat" \
    -H "Authorization: Bearer $INS_TOK" -H 'Content-Type: application/json' \
    -d '{"template_name":"vin_audit","vars":{"vin":"X","confidence":0.5}}')

# S3 activate
b=$(step "S3.activate_v1" 200 0 -X POST "$LLM/admin/v1/llm/templates/$TPL_V1/activate" \
    -H "X-Gomob-User-Id: $ADM_ID" -H 'X-Gomob-Roles: admin')

# S4 非流式 chat
b=$(step "S4.chat_nonstream" 200 0 -X POST "$GATEWAY/v1/llm/chat" \
    -H "Authorization: Bearer $INS_TOK" -H 'Content-Type: application/json' \
    -d '{"template_name":"vin_audit","vars":{"vin":"LXBH12E0XJB123456","confidence":0.93}}')
provider_used=$(echo "$b" | extract data.provider)
content=$(echo "$b" | extract data.content)
[[ "$provider_used" == "mock" && -n "$content" ]] && S4OK=true || S4OK=false
record "S4b.content_nonempty" "$S4OK" 0 0 "" "" 0 "provider=$provider_used content_len=${#content}"

# S5 流式：抓 SSE 事件类型
SSE_OUT="$OUTPUT_DIR/sse.txt"
curl -sN -X POST "$GATEWAY/v1/llm/chat" \
  -H "Authorization: Bearer $INS_TOK" -H 'Content-Type: application/json' \
  -d '{"template_name":"vin_audit","stream":true,"vars":{"vin":"LXBH12E0XJB123456","confidence":0.93}}' > "$SSE_OUT"
meta_n=$(grep -c '^event: meta'  "$SSE_OUT" || true)
delta_n=$(grep -c '^event: delta' "$SSE_OUT" || true)
done_n=$(grep -c '^event: done'  "$SSE_OUT" || true)
[[ "$meta_n" -ge 1 && "$delta_n" -ge 3 && "$done_n" -ge 1 ]] && S5OK=true || S5OK=false
record "S5.sse_events" "$S5OK" 0 0 "" "" 0 "meta=$meta_n delta=$delta_n done=$done_n"

# S6 客户端早断 → cancelled
curl -sN --max-time 0.05 -X POST "$GATEWAY/v1/llm/chat" \
  -H "Authorization: Bearer $INS_TOK" -H 'Content-Type: application/json' \
  -d '{"template_name":"vin_audit","stream":true,"vars":{"vin":"X","confidence":0.5}}' > /dev/null 2>&1 || true
sleep 0.3   # 等 audit 写入
n_cancelled=$(podman exec gomob-pg psql -U gomob -d gomob -tAc "SELECT count(*) FROM llm_call_logs WHERE status='cancelled'")
[[ "$n_cancelled" -ge 1 ]] && S6OK=true || S6OK=false
record "S6.cancelled_recorded" "$S6OK" 0 0 "" "" 0 "cancelled_count=$n_cancelled"

# S7 缺 var → 40601 + 报错带 confidence
b=$(step "S7.missing_var_40601" 422 40601 -X POST "$GATEWAY/v1/llm/chat" \
    -H "Authorization: Bearer $INS_TOK" -H 'Content-Type: application/json' \
    -d '{"template_name":"vin_audit","vars":{"vin":"X"}}')
msg=$(echo "$b" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("message",""))')
echo "$msg" | grep -q "confidence" && S7B=true || S7B=false
record "S7b.error_msg_has_var" "$S7B" 0 0 "" "" 0 "msg=$msg"

# S8 inspector 写路径 → 40103
b=$(step "S8.inspector_write_403" 403 40103 -X POST "$LLM/admin/v1/llm/templates" \
    -H "X-Gomob-User-Id: $INS_ID" -H 'X-Gomob-Roles: inspector' -H 'Content-Type: application/json' \
    -d '{"name":"x","version":1,"user_template":"x"}')

# S9 重复 (name,version) → 40201
b=$(step "S9.duplicate_40201" 409 40201 -X POST "$LLM/admin/v1/llm/templates" \
    -H "X-Gomob-User-Id: $ADM_ID" -H 'X-Gomob-Roles: admin' -H 'Content-Type: application/json' \
    -d '{"name":"vin_audit","version":1,"user_template":"x"}')

# S10 v2 创建并激活，v1 自动归档
b=$(step "S10a.create_v2" 200 0 -X POST "$LLM/admin/v1/llm/templates" \
    -H "X-Gomob-User-Id: $ADM_ID" -H 'X-Gomob-Roles: admin' -H 'Content-Type: application/json' \
    -d '{"name":"vin_audit","version":2,"preferred_provider":"mock","user_template":"v2: {{.vin}}"}')
TPL_V2=$(echo "$b" | extract data.id)
b=$(step "S10b.activate_v2" 200 0 -X POST "$LLM/admin/v1/llm/templates/$TPL_V2/activate" \
    -H "X-Gomob-User-Id: $ADM_ID" -H 'X-Gomob-Roles: admin')
v1_status=$(podman exec gomob-pg psql -U gomob -d gomob -tAc "SELECT status FROM llm_templates WHERE id=$TPL_V1")
[[ "$v1_status" == "archived" ]] && S10c=true || S10c=false
record "S10c.v1_auto_archived" "$S10c" 0 0 "" "" 0 "v1_status=$v1_status"

# S11 archive v2 → active 列表空
b=$(step "S11a.archive_v2" 200 0 -X POST "$LLM/admin/v1/llm/templates/$TPL_V2/archive" \
    -H "X-Gomob-User-Id: $ADM_ID" -H 'X-Gomob-Roles: admin')
b=$(step "S11b.list_active_empty" 200 0 "$GATEWAY/v1/llm/templates" \
    -H "Authorization: Bearer $INS_TOK")
n=$(echo "$b" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["data"]["items"]))')
[[ "$n" == "0" ]] && S11c=true || S11c=false
record "S11c.list_count_zero" "$S11c" 0 0 "" "" 0 "got=$n want=0"

# S12 call_logs ≥ 3 (S4 ok / S6 cancelled / S5 ok)
n=$(podman exec gomob-pg psql -U gomob -d gomob -tAc "SELECT count(*) FROM llm_call_logs")
[[ "$n" -ge 3 ]] && S12=true || S12=false
record "S12.call_logs_count" "$S12" 0 0 "" "" 0 "count=$n want>=3"

log "4. 采样完成 → $RESULTS"
log "   下一步：python3 $(dirname "$0")/analyze.py $OUTPUT_DIR"
