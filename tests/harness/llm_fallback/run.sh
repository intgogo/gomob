#!/bin/bash
# llm_fallback/run.sh — M-S11.7 LLM provider failover 端到端
#
# 验证：
#   - 配 GOMOB_DEEPSEEK_API_KEY=fake-key + GOMOB_LLM_FALLBACK_CHAIN=deepseek,mock
#     → 默认 provider 是 FallbackProvider(deepseek, mock)
#   - 不指定 provider 调 chat → deepseek 鉴权失败 → 自动 fallback 到 mock → 200 + 内容来自 mock
#   - 流式同样 fallback：首 chunk 之前 deepseek 失败 → 切 mock → SSE 走完
#   - 指定 provider="mock" → 直接走 mock，不进 fallback 链（registry.Pick 走 providers map）
#   - 不配 fallback chain → deepseek-only 默认；模拟失败时返 502/503
#
# 不需要外网；deepseek "失败" 是因为 fake key 鉴权失败（本地 net.Dial DeepSeek endpoint 也会快速失败）。

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVER_DIR="$PROJ_DIR/server"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/llm_fallback}"
mkdir -p "$OUTPUT_DIR"
RESULTS="$OUTPUT_DIR/results.jsonl"
: > "$RESULTS"

GATEWAY=http://127.0.0.1:18808
LLM=http://127.0.0.1:18811
AUTH=http://127.0.0.1:18082

log() { printf "[%s] %s\n" "$(date +%H:%M:%S)" "$*"; }

record() {
    local scenario=${1:-} ok=${2:-} http=${3:-0} ex_http=${4:-0} code=${5:-} ex_code=${6:-} lat=${7:-0} note=${8:-}
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

# 0. 前置
log "0. 前置：清表 + 编译"
podman ps --format '{{.Names}}' | grep -qx gomob-pg || { log "缺 gomob-pg"; exit 2; }
podman ps --format '{{.Names}}' | grep -qx gomob-redis || { log "缺 gomob-redis"; exit 2; }
podman exec gomob-redis redis-cli FLUSHDB > /dev/null
podman exec gomob-pg psql -U gomob -d gomob -c "DELETE FROM llm_call_logs; DELETE FROM llm_templates;" > /dev/null

(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-auth        ./cmd/auth)        || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-llmgateway  ./cmd/llmgateway)  || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-gateway     ./cmd/gateway)     || exit 3

pkill -9 -f "gomob-llmgateway\|gomob-gateway\|gomob-auth" 2>/dev/null
sleep 1

PIDS=()
trap 'for p in "${PIDS[@]:-}"; do kill -9 $p 2>/dev/null || true; done; wait 2>/dev/null || true' EXIT

log "1. 启动：deepseek(fake-key) + fallback chain → mock"
GOMOB_AUTH_HTTP_ADDR=:18082 GOMOB_AUTH_DEV_AUTOACTIVATE=true \
    "$SERVER_DIR/.dev/bin/gomob-auth"        > "$OUTPUT_DIR/auth.log"        2>&1 & PIDS+=($!)
# fake key + 假 endpoint（连不通的 127.0.0.1:1）→ deepseek 必失败；fallback chain 切 mock
GOMOB_LLM_HTTP_ADDR=:18811 \
GOMOB_DEEPSEEK_API_KEY=fake-key-for-fallback-test \
GOMOB_DEEPSEEK_ENDPOINT=http://127.0.0.1:1 \
GOMOB_DEEPSEEK_MODEL=deepseek-chat \
GOMOB_LLM_TIMEOUT=2s \
GOMOB_LLM_FALLBACK_CHAIN=deepseek,mock \
    "$SERVER_DIR/.dev/bin/gomob-llmgateway"  > "$OUTPUT_DIR/llm.log"         2>&1 & PIDS+=($!)
GOMOB_GATEWAY_ADDR=:18808 GOMOB_REDIS_ADDR=127.0.0.1:6379 GOMOB_RATE_LIMIT=100000 \
    "$SERVER_DIR/.dev/bin/gomob-gateway"     > "$OUTPUT_DIR/gateway.log"     2>&1 & PIDS+=($!)
sleep 1.5

hc=$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/healthz")
[[ "$hc" == "200" ]] || { log "✗ gateway"; cat "$OUTPUT_DIR/llm.log" | tail -10; exit 4; }
record "S0.services_up" true "$hc" 200 "" "" 0 "fallback chain 已配"

# 验证 llm.log 含 "fallback 链已启用"
if grep -q "fallback 链已启用\|fallback 链" "$OUTPUT_DIR/llm.log"; then
    record "S0b.fallback_log_present" true 0 0 "" "" 0 ""
else
    record "S0b.fallback_log_present" false 0 0 "" "" 0 "log 未含 fallback 启用"
fi

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

# 3. 创建一个 template（无 preferred_provider → 走 registry default = FallbackProvider）
b=$(curl -s -X POST "$LLM/admin/v1/llm/templates" \
    -H "X-Gomob-User-Id: $ADM_ID" -H 'X-Gomob-Roles: admin' -H 'Content-Type: application/json' \
    -d '{"name":"fallback_test","version":1,"system_prompt":"sys","user_template":"hello {{.name}}","vars_schema":{"name":"string"}}')
TPL=$(echo "$b" | extract data.id)
[[ -n "$TPL" ]] || { log "✗ template create"; exit 5; }
curl -s -o /dev/null -X POST "$LLM/admin/v1/llm/templates/$TPL/activate" \
    -H "X-Gomob-User-Id: $ADM_ID" -H 'X-Gomob-Roles: admin'
record "S1.template_active" true 0 0 "" "" 0 "id=$TPL"

# 4. 不指定 provider chat → deepseek（fake endpoint）失败 → fallback 到 mock → 200
b=$(step "S2.fallback_chat_ok" 200 0 -X POST "$GATEWAY/v1/llm/chat" \
    -H "Authorization: Bearer $INS_TOK" -H 'Content-Type: application/json' \
    -d '{"template_name":"fallback_test","vars":{"name":"World"}}')
provider=$(echo "$b" | extract data.provider)
content=$(echo "$b" | extract data.content)
[[ -n "$content" ]] && S2B=true || S2B=false
record "S2b.fallback_content_present" "$S2B" 0 0 "" "" 0 "provider=$provider content_len=${#content}"

# 5. provider 字段应包含 fallback(...) 或显示最终命中的 mock
echo "$provider" | grep -qE "fallback\(|mock" && S2C=true || S2C=false
record "S2c.fallback_or_mock_used" "$S2C" 0 0 "" "" 0 "provider=$provider"

# 6. llm.log 应记录 fallback 命中（"fallback 命中后续 provider"）
if grep -q "fallback 命中后续 provider\|provider 失败，尝试下一个" "$OUTPUT_DIR/llm.log"; then
    record "S3.fallback_triggered_in_log" true 0 0 "" "" 0 ""
else
    record "S3.fallback_triggered_in_log" false 0 0 "" "" 0 "log 未含 fallback 命中"
fi

# 7. 流式同样 fallback：deepseek 失败前没吐 chunk → 切 mock 流式吐
SSE_OUT="$OUTPUT_DIR/sse.txt"
curl -sN -X POST "$GATEWAY/v1/llm/chat" \
  -H "Authorization: Bearer $INS_TOK" -H 'Content-Type: application/json' \
  -d '{"template_name":"fallback_test","stream":true,"vars":{"name":"Stream"}}' > "$SSE_OUT" 2>/dev/null
meta_n=$(grep -c '^event: meta'  "$SSE_OUT" || true)
delta_n=$(grep -c '^event: delta' "$SSE_OUT" || true)
done_n=$(grep -c '^event: done'  "$SSE_OUT" || true)
[[ "$meta_n" -ge 1 && "$delta_n" -ge 1 && "$done_n" -ge 1 ]] && S4OK=true || S4OK=false
record "S4.stream_fallback_full_sse" "$S4OK" 0 0 "" "" 0 "meta=$meta_n delta=$delta_n done=$done_n"

# 8. 显式指定 provider="mock" → 直接走 mock，不进 fallback 链
b=$(step "S5.explicit_provider_mock" 200 0 -X POST "$GATEWAY/v1/llm/chat" \
    -H "Authorization: Bearer $INS_TOK" -H 'Content-Type: application/json' \
    -d '{"template_name":"fallback_test","preferred_provider":"mock","vars":{"name":"DirectMock"}}')
prov2=$(echo "$b" | extract data.provider)
[[ "$prov2" == "mock" ]] && S5B=true || S5B=false
record "S5b.explicit_mock_no_fallback" "$S5B" 0 0 "" "" 0 "provider=$prov2"

# 9. 显式 provider="deepseek" + fake endpoint → 502/503，错误码 50001 系列
# DeepSeek 失败时 handler 走 writeProviderError；可能是 50001（unavailable）或 503
b_status=$(curl -s -o /tmp/eb.$$ -w '%{http_code}' -X POST "$GATEWAY/v1/llm/chat" \
    -H "Authorization: Bearer $INS_TOK" -H 'Content-Type: application/json' \
    -d '{"template_name":"fallback_test","preferred_provider":"deepseek","vars":{"name":"Direct"}}')
b_body=$(cat /tmp/eb.$$); rm -f /tmp/eb.$$
[[ "$b_status" == "502" || "$b_status" == "503" || "$b_status" == "500" ]] && S6=true || S6=false
record "S6.explicit_deepseek_fails_no_fallback" "$S6" "$b_status" 0 "" "" 0 "http=$b_status body=${b_body:0:80}"

# 10. llm_call_logs 应有至少 1 条 status=ok（来自 fallback 成功的）+ 至少 1 条 cancelled / error（deepseek 失败的）
N_OK=$(podman exec gomob-pg psql -U gomob -d gomob -tAc "SELECT count(*) FROM llm_call_logs WHERE status='ok'")
N_TOTAL=$(podman exec gomob-pg psql -U gomob -d gomob -tAc "SELECT count(*) FROM llm_call_logs")
[[ "$N_OK" -ge 1 ]] && S7=true || S7=false
record "S7.audit_ok_records" "$S7" 0 0 "" "" 0 "ok=$N_OK total=$N_TOTAL"

# 11. 重启：禁用 fallback chain（GOMOB_LLM_FALLBACK_CHAIN 不设）→ default = deepseek-only → 失败应 502
log "11. 重启 llm（无 fallback chain，纯 deepseek）"
kill -9 ${PIDS[1]} 2>/dev/null
sleep 1

GOMOB_LLM_HTTP_ADDR=:18811 \
GOMOB_DEEPSEEK_API_KEY=fake-key-for-fallback-test \
GOMOB_DEEPSEEK_ENDPOINT=http://127.0.0.1:1 \
GOMOB_LLM_TIMEOUT=2s \
    "$SERVER_DIR/.dev/bin/gomob-llmgateway"  > "$OUTPUT_DIR/llm_no_chain.log" 2>&1 &
PIDS[1]=$!
sleep 1.5

# 不指定 provider → 走 registry default = deepseek（无 fallback wrap）→ 应失败
b_status=$(curl -s -o /tmp/eb.$$ -w '%{http_code}' -X POST "$GATEWAY/v1/llm/chat" \
    -H "Authorization: Bearer $INS_TOK" -H 'Content-Type: application/json' \
    -d '{"template_name":"fallback_test","vars":{"name":"NoChain"}}')
b_body=$(cat /tmp/eb.$$); rm -f /tmp/eb.$$
[[ "$b_status" == "502" || "$b_status" == "503" || "$b_status" == "500" ]] && S8=true || S8=false
record "S8.no_chain_fails_correctly" "$S8" "$b_status" 0 "" "" 0 "http=$b_status body=${b_body:0:80}"

log "采样完成 → $RESULTS"
