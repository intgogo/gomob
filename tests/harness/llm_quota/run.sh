#!/bin/bash
# llm_quota/run.sh — M-S11.6 LLM 配额端到端
#
# 验证：
#   - GOMOB_LLM_USER_DAILY_BUDGET=3 → 每用户日 3 次；第 4 次 → 40602
#   - 用户 A 超额不影响用户 B
#   - GOMOB_LLM_TPL_DAILY_BUDGET=2 + 跨用户 → 任一模板第 3 次 → 40602
#   - 关闭限额（budget=0）→ 不计数，无 40602
#   - Redis 不可达（GOMOB_REDIS_ADDR=bad）→ 降级到不限额（不阻塞业务）

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVER_DIR="$PROJ_DIR/server"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/llm_quota}"
mkdir -p "$OUTPUT_DIR"
RESULTS="$OUTPUT_DIR/results.jsonl"
: > "$RESULTS"

GATEWAY=http://127.0.0.1:18808
LLM=http://127.0.0.1:18811
AUTH=http://127.0.0.1:18082

log() { printf "[%s] %s\n" "$(date +%H:%M:%S)" "$*"; }

record() {
    local scenario=${1:-} ok=${2:-} note=${3:-}
    python3 -c "
import json
print(json.dumps({
    'scenario': '$scenario',
    'ok': '$ok' == 'true',
    'http_code': 0,
    'expected_http': 0,
    'code': None,
    'expected_code': None,
    'latency_ms': 0,
    'note': '''$note''',
}))
" >> "$RESULTS"
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

# 0. 前置
log "0. 清表 + 编译"
podman exec gomob-redis redis-cli FLUSHDB > /dev/null
podman exec gomob-pg psql -U gomob -d gomob -c "DELETE FROM llm_call_logs; DELETE FROM llm_templates;" > /dev/null

(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-auth        ./cmd/auth)        || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-llmgateway  ./cmd/llmgateway)  || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-gateway     ./cmd/gateway)     || exit 3

pkill -9 -f "gomob-llmgateway\|gomob-gateway\|gomob-auth" 2>/dev/null
sleep 1

PIDS=()
trap 'for p in "${PIDS[@]:-}"; do kill -9 $p 2>/dev/null || true; done; wait 2>/dev/null || true' EXIT

log "1. 启动：user_budget=3 / tpl_budget=0（先只测 user）"
GOMOB_AUTH_HTTP_ADDR=:18082 GOMOB_AUTH_DEV_AUTOACTIVATE=true \
    "$SERVER_DIR/.dev/bin/gomob-auth"        > "$OUTPUT_DIR/auth.log"        2>&1 & PIDS+=($!)
GOMOB_LLM_HTTP_ADDR=:18811 \
GOMOB_REDIS_ADDR=127.0.0.1:6379 \
GOMOB_LLM_USER_DAILY_BUDGET=3 \
    "$SERVER_DIR/.dev/bin/gomob-llmgateway"  > "$OUTPUT_DIR/llm.log"         2>&1 & PIDS+=($!)
GOMOB_GATEWAY_ADDR=:18808 GOMOB_REDIS_ADDR=127.0.0.1:6379 GOMOB_RATE_LIMIT=100000 \
    "$SERVER_DIR/.dev/bin/gomob-gateway"     > "$OUTPUT_DIR/gateway.log"     2>&1 & PIDS+=($!)
sleep 1.5

hc=$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/healthz")
[[ "$hc" == "200" ]] || { log "✗ gateway"; exit 4; }
record "S0.services_up" true ""

# 验证 llm.log 含 "LLM 配额已启用"
if grep -q "LLM 配额已启用" "$OUTPUT_DIR/llm.log"; then
    record "S0b.quota_log_present" true ""
else
    record "S0b.quota_log_present" false "log 未含 LLM 配额已启用"
fi

# 2. admin + 2 个 inspector
SUFFIX=$$_$(date +%s%N)
PASS=pwd-1234
ADM=adm_$SUFFIX
INS_A=insA_$SUFFIX
INS_B=insB_$SUFFIX
curl -s -X POST "$GATEWAY/v1/auth/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADM\",\"password\":\"$PASS\",\"real_name\":\"Adm\",\"employee_id\":\"AA$SUFFIX\"}" > /dev/null
podman exec gomob-pg psql -U gomob -d gomob -c "UPDATE users SET role='admin' WHERE username='$ADM'" > /dev/null
ADM_TOK=$(curl -s -X POST "$GATEWAY/v1/auth/login" -H 'Content-Type: application/json' -d "{\"username\":\"$ADM\",\"password\":\"$PASS\"}" | extract data.access_token)
ADM_ID=$(curl -s "$GATEWAY/v1/me" -H "Authorization: Bearer $ADM_TOK" | extract data.id)

curl -s -X POST "$GATEWAY/v1/auth/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$INS_A\",\"password\":\"$PASS\",\"real_name\":\"InsA\",\"employee_id\":\"IA$SUFFIX\"}" > /dev/null
TOK_A=$(curl -s -X POST "$GATEWAY/v1/auth/login" -H 'Content-Type: application/json' -d "{\"username\":\"$INS_A\",\"password\":\"$PASS\"}" | extract data.access_token)
ID_A=$(curl -s "$GATEWAY/v1/me" -H "Authorization: Bearer $TOK_A" | extract data.id)

curl -s -X POST "$GATEWAY/v1/auth/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$INS_B\",\"password\":\"$PASS\",\"real_name\":\"InsB\",\"employee_id\":\"IB$SUFFIX\"}" > /dev/null
TOK_B=$(curl -s -X POST "$GATEWAY/v1/auth/login" -H 'Content-Type: application/json' -d "{\"username\":\"$INS_B\",\"password\":\"$PASS\"}" | extract data.access_token)
ID_B=$(curl -s "$GATEWAY/v1/me" -H "Authorization: Bearer $TOK_B" | extract data.id)

# 3. 创建 + activate 模板（preferred_provider="mock" 直接走 mock，不走 fallback）
b=$(curl -s -X POST "$LLM/admin/v1/llm/templates" \
    -H "X-Gomob-User-Id: $ADM_ID" -H 'X-Gomob-Roles: admin' -H 'Content-Type: application/json' \
    -d '{"name":"q_test","version":1,"preferred_provider":"mock","system_prompt":"sys","user_template":"hi {{.x}}","vars_schema":{"x":"string"}}')
TPL=$(echo "$b" | extract data.id)
curl -s -o /dev/null -X POST "$LLM/admin/v1/llm/templates/$TPL/activate" \
    -H "X-Gomob-User-Id: $ADM_ID" -H 'X-Gomob-Roles: admin'
record "S1.template_active" true "id=$TPL"

# 帮助函数：调一次 chat
do_chat() {
    local tok=$1 var=$2
    curl -s -o /tmp/chat_body.$$ -w '%{http_code}' -X POST "$GATEWAY/v1/llm/chat" \
        -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' \
        -d "{\"template_name\":\"q_test\",\"vars\":{\"x\":\"$var\"}}"
}

# 4. 用户 A 调 3 次 → 全 200；第 4 次 → 40602
for i in 1 2 3; do
    h=$(do_chat "$TOK_A" "callA$i")
    [[ "$h" == "200" ]] && OK=true || OK=false
    record "S2.userA_call_${i}_ok" "$OK" "http=$h"
done

h=$(do_chat "$TOK_A" "callA4")
b=$(cat /tmp/chat_body.$$); rm -f /tmp/chat_body.$$
code=$(echo "$b" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("code",""))' 2>/dev/null)
[[ "$h" == "429" && "$code" == "40602" ]] && S3OK=true || S3OK=false
record "S3.userA_call_4_quota_exceeded" "$S3OK" "http=$h code=$code body=${b:0:80}"

# 5. 用户 B 仍能调（独立计数）
h=$(do_chat "$TOK_B" "callB1")
[[ "$h" == "200" ]] && S4OK=true || S4OK=false
record "S4.userB_isolated_from_A" "$S4OK" "http=$h"

# 6. Redis 里的计数应是 user A=3（已超 budget 自减回 3）
A_COUNT=$(podman exec gomob-redis redis-cli GET "llm:quota:user:$ID_A:$(date -u +%Y%m%d)")
B_COUNT=$(podman exec gomob-redis redis-cli GET "llm:quota:user:$ID_B:$(date -u +%Y%m%d)")
[[ "$A_COUNT" == "3" && "$B_COUNT" == "1" ]] && S5OK=true || S5OK=false
record "S5.redis_counts_correct" "$S5OK" "A=$A_COUNT(want=3) B=$B_COUNT(want=1)"

# 7. 重启 + tpl_budget=2 → 跨用户共用模板
log "7. 重启：tpl_budget=2，关 user 限"
kill -9 ${PIDS[1]} 2>/dev/null
sleep 1
podman exec gomob-redis redis-cli FLUSHDB > /dev/null

GOMOB_LLM_HTTP_ADDR=:18811 \
GOMOB_REDIS_ADDR=127.0.0.1:6379 \
GOMOB_LLM_TPL_DAILY_BUDGET=2 \
    "$SERVER_DIR/.dev/bin/gomob-llmgateway"  > "$OUTPUT_DIR/llm_tpl.log"     2>&1 &
PIDS[1]=$!
sleep 1.5

# user A 调 1 次 + user B 调 1 次 → tpl 累计 2，OK
h=$(do_chat "$TOK_A" "tplA1"); [[ "$h" == "200" ]] && record "S6.tpl_call_1_ok" true "http=$h" || record "S6.tpl_call_1_ok" false "http=$h"
h=$(do_chat "$TOK_B" "tplB1"); [[ "$h" == "200" ]] && record "S6b.tpl_call_2_ok" true "http=$h" || record "S6b.tpl_call_2_ok" false "http=$h"

# 第 3 次（任意用户）→ 40602
h=$(do_chat "$TOK_A" "tplA3")
b=$(cat /tmp/chat_body.$$); rm -f /tmp/chat_body.$$
code=$(echo "$b" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("code",""))' 2>/dev/null)
[[ "$h" == "429" && "$code" == "40602" ]] && S7OK=true || S7OK=false
record "S7.tpl_call_3_quota_exceeded" "$S7OK" "http=$h code=$code"

# 8. 重启：禁用配额（budget=0）→ 多次调用都 OK
log "8. 重启：完全禁用配额"
kill -9 ${PIDS[1]} 2>/dev/null
sleep 1
podman exec gomob-redis redis-cli FLUSHDB > /dev/null

GOMOB_LLM_HTTP_ADDR=:18811 \
    "$SERVER_DIR/.dev/bin/gomob-llmgateway"  > "$OUTPUT_DIR/llm_off.log"     2>&1 &
PIDS[1]=$!
sleep 1.5

ALL_OK=true
for i in 1 2 3 4 5; do
    h=$(do_chat "$TOK_A" "off$i")
    [[ "$h" == "200" ]] || ALL_OK=false
done
record "S8.disabled_quota_all_pass" "$ALL_OK" "5 次调用全 200"

# 9. 重启：budget 设但 redis 不可达 → 降级（业务不阻塞）
log "9. 重启：bad redis addr → 配额降级不阻塞"
kill -9 ${PIDS[1]} 2>/dev/null
sleep 1

GOMOB_LLM_HTTP_ADDR=:18811 \
GOMOB_REDIS_ADDR=127.0.0.1:65530 \
GOMOB_LLM_USER_DAILY_BUDGET=2 \
    "$SERVER_DIR/.dev/bin/gomob-llmgateway"  > "$OUTPUT_DIR/llm_badredis.log" 2>&1 &
PIDS[1]=$!
sleep 1.5

# 应启动成功（即使 redis 不通），调用应放行
hc=$(curl -s -o /dev/null -w '%{http_code}' "$LLM/healthz")
record "S9.bad_redis_still_starts" "$([[ "$hc" == "200" ]] && echo true || echo false)" "healthz=$hc"

# 调用应放行（不限额降级）
ALL_OK=true
for i in 1 2 3; do
    h=$(do_chat "$TOK_A" "br$i")
    [[ "$h" == "200" ]] || ALL_OK=false
done
record "S9b.bad_redis_calls_pass" "$ALL_OK" "3 次调用全 200"

# 验证 llm_badredis.log 含运行时降级信息（INCR 失败 → 按放行处理）
if grep -q "redis INCR.*失败.*放行\|按放行处理" "$OUTPUT_DIR/llm_badredis.log"; then
    record "S9c.bad_redis_logged" true ""
else
    record "S9c.bad_redis_logged" false "log 未含 redis INCR 失败按放行"
fi

log "采样完成 → $RESULTS"
