#!/bin/bash
# auth_flow/run.sh — M-S1 auth + gateway 全链路 harness 采样器
#
# 场景：
#   S1 注册（公开路径不受限流影响）
#   S2 登录拿 access + refresh
#   S3 /v1/me 通过 gateway（X-Gomob-User-Id 注入路径）
#   S4 /v1/me 直连 auth（Bearer fallback 路径）
#   S5 refresh 拿新 access
#   S6 改密旧密码错 / 旧密码对 / 旧密码登录失败 / 新密码登录成功
#   S7 限流：连发 N 次 me，验证触限
#
# 输出 .dev/auth_flow/results.jsonl（每行一条结果）；analyze.py 给最终判定。

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVER_DIR="$PROJ_DIR/server"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/auth_flow}"
mkdir -p "$OUTPUT_DIR"
RESULTS="$OUTPUT_DIR/results.jsonl"
: > "$RESULTS"

GATEWAY=http://127.0.0.1:18808
AUTH=http://127.0.0.1:18082
RATE_LIMIT=${RATE_LIMIT:-100}

log() { printf "[%s] %s\n" "$(date +%H:%M:%S)" "$*"; }

# 记一行 jsonl 结果
record() {
    local scenario=$1 ok=$2 http=$3 ex_http=$4 code=$5 ex_code=$6 latency_ms=$7 note=${8:-}
    python3 -c "
import json
print(json.dumps({
    'scenario': '$scenario',
    'ok': '$ok' == 'true',
    'http_code': int('$http' or 0),
    'expected_http': int('$ex_http' or 0),
    'code': int('$code') if '$code' != '' else None,
    'expected_code': int('$ex_code') if '$ex_code' != '' else None,
    'latency_ms': float('$latency_ms' or 0),
    'note': '''$note''',
}))
" >> "$RESULTS"
}

# 单次 curl，写出 http_code 到 stdout 第一行，body 到 stdout 第二行起。
# 用法：out=$(curl_lines GET <url>);  http=$(echo "$out" | head -1); body=$(echo "$out" | tail -n +2)
# 由于 token 含 "."，body 多行；另写一个 helper：先一次 curl 拿 body+code 拼接。
curl_with_code() {
    # 输出 "<http_code>\n<body>"
    curl -s -o /tmp/curl-body.$$ -w '%{http_code}' "$@"
    echo
    cat /tmp/curl-body.$$
    rm -f /tmp/curl-body.$$
}

extract_field() {
    python3 -c "
import json,sys
try:
    d = json.load(sys.stdin)
    keys = sys.argv[1].split('.')
    cur = d
    for k in keys:
        cur = cur[k]
    print(cur)
except Exception:
    pass
" "$1"
}

# ==========================================================================
# 0. 前置
# ==========================================================================
log "0. 前置检查"
need_container() {
    if ! podman ps --format '{{.Names}}' | grep -qx "$1"; then
        log "✗ 缺容器 $1（请先 podman start $1 或重新 podman run）"
        exit 2
    fi
}
need_container gomob-pg
need_container gomob-redis
podman exec gomob-redis redis-cli FLUSHDB > /dev/null
log "  Redis 计数已清"

# ==========================================================================
# 1. 编译 + 启动
# ==========================================================================
log "1. 编译 + 启动"
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-auth ./cmd/auth) || { log "auth build 失败"; exit 3; }
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-gateway ./cmd/gateway) || { log "gateway build 失败"; exit 3; }

GOMOB_AUTH_HTTP_ADDR=:18082 GOMOB_AUTH_DEV_AUTOACTIVATE=true \
    "$SERVER_DIR/.dev/bin/gomob-auth" > "$OUTPUT_DIR/auth.log" 2>&1 &
AUTH_PID=$!
GOMOB_GATEWAY_ADDR=:18808 GOMOB_REDIS_ADDR=127.0.0.1:6379 GOMOB_RATE_LIMIT=$RATE_LIMIT \
    "$SERVER_DIR/.dev/bin/gomob-gateway" > "$OUTPUT_DIR/gateway.log" 2>&1 &
GW_PID=$!
log "  auth pid=$AUTH_PID  gateway pid=$GW_PID"
trap "kill $AUTH_PID $GW_PID 2>/dev/null; wait 2>/dev/null" EXIT
sleep 1

# 探活
hc=$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/healthz")
[[ "$hc" == "200" ]] || { log "✗ gateway /healthz $hc"; exit 4; }

# ==========================================================================
# 2. 跑场景
# ==========================================================================
log "2. 跑场景"
SUFFIX=$(date +%s)
USERNAME="harness_$SUFFIX"
EMP="H$SUFFIX"

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
    code=$(echo "$body" | extract_field code)
    if [[ "$http" == "$ex_http" ]] && [[ -z "$ex_code" || "$code" == "$ex_code" ]]; then
        ok=true
    else
        ok=false
    fi
    record "$name" "$ok" "$http" "$ex_http" "$code" "$ex_code" "$lat"
    # 把 body 留给调用方
    echo "$body"
}

# S1
body=$(step "S1.register" 200 0 -X POST "$GATEWAY/v1/auth/register" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USERNAME\",\"password\":\"pwd-old-1\",\"real_name\":\"Harness User\",\"employee_id\":\"$EMP\"}")

# S2
body=$(step "S2.login" 200 0 -X POST "$GATEWAY/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USERNAME\",\"password\":\"pwd-old-1\"}")
ACCESS=$(echo "$body" | extract_field data.access_token)
REFRESH=$(echo "$body" | extract_field data.refresh_token)

# S3
step "S3.me_via_gateway" 200 0 "$GATEWAY/v1/me" -H "Authorization: Bearer $ACCESS" > /dev/null

# S4
step "S4.me_direct" 200 0 "$AUTH/v1/me" -H "Authorization: Bearer $ACCESS" > /dev/null

# S5
body=$(step "S5.refresh" 200 0 -X POST "$GATEWAY/v1/auth/refresh" \
    -H 'Content-Type: application/json' -d "{\"refresh_token\":\"$REFRESH\"}")
ACCESS2=$(echo "$body" | extract_field data.access_token)

# S6a
step "S6a.passwd_wrong" 401 40101 -X POST "$GATEWAY/v1/auth/password" \
    -H "Authorization: Bearer $ACCESS2" -H 'Content-Type: application/json' \
    -d '{"old_password":"WRONG","new_password":"pwd-new-1"}' > /dev/null

# S6b
step "S6b.passwd_ok" 200 0 -X POST "$GATEWAY/v1/auth/password" \
    -H "Authorization: Bearer $ACCESS2" -H 'Content-Type: application/json' \
    -d '{"old_password":"pwd-old-1","new_password":"pwd-new-1"}' > /dev/null

# S6c 旧密码登录应失败
step "S6c.old_login_fail" 401 40101 -X POST "$GATEWAY/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USERNAME\",\"password\":\"pwd-old-1\"}" > /dev/null

# S6d 新密码登录应成功
body=$(step "S6d.new_login_ok" 200 0 -X POST "$GATEWAY/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USERNAME\",\"password\":\"pwd-new-1\"}")
ACCESS3=$(echo "$body" | extract_field data.access_token)

# S7 限流
log "  限流场景：在限额清零后连发 $((RATE_LIMIT + 5)) 次 /v1/me"
podman exec gomob-redis redis-cli FLUSHDB > /dev/null
ok=0; rl=0; other=0
for i in $(seq 1 $((RATE_LIMIT + 5))); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/v1/me" -H "Authorization: Bearer $ACCESS3")
    case "$code" in
        200) ok=$((ok+1)) ;;
        429) rl=$((rl+1)) ;;
        *)   other=$((other+1)) ;;
    esac
done
S7_NOTE="200=$ok 429=$rl other=$other rate_limit=$RATE_LIMIT"
if [[ "$rl" -ge 1 && "$ok" -le "$RATE_LIMIT" && "$other" -eq 0 ]]; then
    S7=true
else
    S7=false
fi
record "S7.rate_limit" "$S7" 0 0 "" "" 0 "$S7_NOTE"
log "  S7 $S7_NOTE"

log "3. 采样完成 → $RESULTS"
log "   下一步：python3 $(dirname "$0")/analyze.py $OUTPUT_DIR"
