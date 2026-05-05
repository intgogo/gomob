#!/bin/bash
# cv_hmac_auth/run.sh — M-S10.2c HMAC 中间件端到端验签
#
# 验证：
#   - GOMOB_HMAC_SECRET 空：所有请求直放（兼容现有 dev / harness）
#   - GOMOB_HMAC_SECRET 设 + GOMOB_CVENGINE_HMAC_REQUIRED=true：缺签拒、错签拒、过期拒、重放拒、正确签放
#   - /healthz / /readyz 始终绕过（k8s probe 不签名）
#   - SigningTransport 客户端正确生成签名通过 server 校验
#
# 走 curl + python（手工签）+ 一个 Go 工具（用 hmacauth.NewSigningTransport）三种验签路径。

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVER_DIR="$PROJ_DIR/server"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/cv_hmac_auth}"
mkdir -p "$OUTPUT_DIR"
RESULTS="$OUTPUT_DIR/results.jsonl"
: > "$RESULTS"

CV=http://127.0.0.1:18810
SECRET="test-hmac-secret-abcdef-123456"

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

# Python: 计算签名（与 pkg/hmacauth/computeSig 完全一致）
hmac_sign() {
    local secret=$1 ts=$2 method=$3 uri=$4 body_file=$5
    python3 - "$secret" "$ts" "$method" "$uri" "$body_file" <<'PY'
import hashlib, hmac, sys
secret = sys.argv[1].encode()
ts = sys.argv[2]
method = sys.argv[3]
uri = sys.argv[4]
with open(sys.argv[5], "rb") as f:
    body = f.read()
body_hash = hashlib.sha256(body).hexdigest()
to_sign = ts + "\n" + method + "\n" + uri + "\n" + body_hash
sig = hmac.new(secret, to_sign.encode(), hashlib.sha256).hexdigest()
print(sig)
PY
}

# 0. 编译 + 启动 cvengine 在 HMAC required 模式
log "0. 编译 + 启动 cvengine（HMAC required 模式）"
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-cvengine ./cmd/cvengine) || exit 3
pkill -9 -f gomob-cvengine 2>/dev/null
sleep 2

LD_LIBRARY_PATH=/usr/local/lib:/usr/local/lib64:/usr/local/onnxruntime/lib \
GOMOB_CVENGINE_HTTP_ADDR=:18810 \
GOMOB_HMAC_SECRET="$SECRET" \
GOMOB_CVENGINE_HMAC_REQUIRED=true \
    "$SERVER_DIR/.dev/bin/gomob-cvengine" > "$OUTPUT_DIR/cvengine.log" 2>&1 &
PID=$!
# 用 SIGKILL；cvengine 偶发 SIGTERM graceful-shutdown 卡住（已知问题），sweep 跑批要快收
trap "kill -9 $PID 2>/dev/null; wait 2>/dev/null" EXIT
sleep 2

# S0 healthz 应该绕过 HMAC（k8s probe）
hc=$(curl -s -o /dev/null -w '%{http_code}' "$CV/healthz")
[[ "$hc" == "200" ]] && S0=true || S0=false
record "S0.healthz_bypass_hmac" "$S0" "$hc" 200 "" "" 0 "/healthz=$hc"

# S0b readyz 同样绕过
hc=$(curl -s -o /dev/null -w '%{http_code}' "$CV/readyz")
[[ "$hc" == "200" ]] && S0b=true || S0b=false
record "S0b.readyz_bypass_hmac" "$S0b" "$hc" 200 "" "" 0 "/readyz=$hc"

# S1 业务端点缺签 → 40110
step "S1.missing_headers_40110" 401 40110 -X GET "$CV/cv/v1/version" > /dev/null

# S2 部分缺签（有 ts 无 sig）→ 40110
step "S2.partial_headers_40110" 401 40110 -X GET "$CV/cv/v1/version" \
    -H "X-Gomob-Hmac-Ts: $(date +%s)" \
    -H "X-Gomob-Hmac-Nonce: abc" > /dev/null

# 准备签名
EMPTY_BODY=$(mktemp)
: > "$EMPTY_BODY"

# S3 ts 过期 → 40111
TS_OLD=$(($(date +%s) - 600))
NONCE_OLD="nonce-$(date +%s%N)"
SIG_OLD=$(hmac_sign "$SECRET" "$TS_OLD" "GET" "/cv/v1/version" "$EMPTY_BODY")
step "S3.ts_expired_40111" 401 40111 -X GET "$CV/cv/v1/version" \
    -H "X-Gomob-Hmac-Ts: $TS_OLD" \
    -H "X-Gomob-Hmac-Nonce: $NONCE_OLD" \
    -H "X-Gomob-Hmac-Sig: $SIG_OLD" > /dev/null

# S4 错签 → 40113
TS_NOW=$(date +%s)
NONCE1="nonce-$(date +%s%N)1"
step "S4.bad_sig_40113" 401 40113 -X GET "$CV/cv/v1/version" \
    -H "X-Gomob-Hmac-Ts: $TS_NOW" \
    -H "X-Gomob-Hmac-Nonce: $NONCE1" \
    -H "X-Gomob-Hmac-Sig: deadbeef0000000000000000000000000000000000000000000000000000abcd" > /dev/null

# S5 用错的 secret 签名 → 40113
NONCE2="nonce-$(date +%s%N)2"
SIG_BAD=$(hmac_sign "wrong-secret" "$TS_NOW" "GET" "/cv/v1/version" "$EMPTY_BODY")
step "S5.wrong_secret_40113" 401 40113 -X GET "$CV/cv/v1/version" \
    -H "X-Gomob-Hmac-Ts: $TS_NOW" \
    -H "X-Gomob-Hmac-Nonce: $NONCE2" \
    -H "X-Gomob-Hmac-Sig: $SIG_BAD" > /dev/null

# S6 正确签 → 200
NONCE_GOOD="nonce-good-$(date +%s%N)"
SIG_GOOD=$(hmac_sign "$SECRET" "$TS_NOW" "GET" "/cv/v1/version" "$EMPTY_BODY")
b=$(step "S6.good_sig_200" 200 0 -X GET "$CV/cv/v1/version" \
    -H "X-Gomob-Hmac-Ts: $TS_NOW" \
    -H "X-Gomob-Hmac-Nonce: $NONCE_GOOD" \
    -H "X-Gomob-Hmac-Sig: $SIG_GOOD")
PHASE=$(echo "$b" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"].get("phase",""))')
[[ -n "$PHASE" ]] && S6b=true || S6b=false
record "S6b.good_sig_returns_data" "$S6b" 0 0 "" "" 0 "phase=$PHASE"

# S7 nonce 重放：第一次 200 / 第二次 40112
NONCE_REPLAY="nonce-replay-$(date +%s%N)"
TS_R=$(date +%s)
SIG_R=$(hmac_sign "$SECRET" "$TS_R" "GET" "/cv/v1/version" "$EMPTY_BODY")
hc1=$(curl -s -o /dev/null -w '%{http_code}' -X GET "$CV/cv/v1/version" \
    -H "X-Gomob-Hmac-Ts: $TS_R" \
    -H "X-Gomob-Hmac-Nonce: $NONCE_REPLAY" \
    -H "X-Gomob-Hmac-Sig: $SIG_R")
[[ "$hc1" == "200" ]] && S7a=true || S7a=false
record "S7a.first_use_200" "$S7a" "$hc1" 200 "" "" 0 "first hc=$hc1"

step "S7b.replay_40112" 401 40112 -X GET "$CV/cv/v1/version" \
    -H "X-Gomob-Hmac-Ts: $TS_R" \
    -H "X-Gomob-Hmac-Nonce: $NONCE_REPLAY" \
    -H "X-Gomob-Hmac-Sig: $SIG_R" > /dev/null

# S8 POST 带 body 的签名（路径有 query string + body 都参与签名）
BODY_FILE=$(mktemp)
echo -n '{"hello":"world"}' > "$BODY_FILE"
TS_B=$(date +%s)
NONCE_B="nonce-body-$(date +%s%N)"
SIG_B=$(hmac_sign "$SECRET" "$TS_B" "POST" "/cv/v1/echo_dim?x=1" "$BODY_FILE")
# echo_dim 期望 image — 我们只验签名不验业务结果（10001 是 OK 的，因为身体不是图）
b=$(curl -s -o /tmp/echo_body.$$ -w '%{http_code}' -X POST "$CV/cv/v1/echo_dim?x=1" \
    -H "Content-Type: application/octet-stream" \
    -H "X-Gomob-Hmac-Ts: $TS_B" \
    -H "X-Gomob-Hmac-Nonce: $NONCE_B" \
    -H "X-Gomob-Hmac-Sig: $SIG_B" \
    --data-binary "@$BODY_FILE")
HBODY=$(cat /tmp/echo_body.$$)
rm -f /tmp/echo_body.$$
SIG_PASSED_BUT_BIZ_400=$([[ "$b" == "400" ]] && echo true || echo false)
record "S8.post_with_body_sig_passes_then_biz_returns" "$SIG_PASSED_BUT_BIZ_400" "$b" 400 "" "" 0 "http=$b body=${HBODY:0:80}"

# S9 SigningTransport 端到端：写一个 Go 小程序用 hmacauth.NewSigningTransport 调
SIGN_GO=$(mktemp /tmp/sign_test_XXXX.go)
cat > "$SIGN_GO" <<'GOEOF'
package main

import (
	"fmt"
	"net/http"
	"os"

	"io.gomob/server/pkg/hmacauth"
)

func main() {
	target := os.Args[1]
	secret := os.Args[2]

	hc := &http.Client{Transport: hmacauth.NewSigningTransport(http.DefaultTransport, secret)}
	resp, err := hc.Get(target + "/cv/v1/version")
	if err != nil {
		fmt.Println("ERR:", err)
		os.Exit(1)
	}
	defer resp.Body.Close()
	fmt.Println("HTTP:", resp.StatusCode)
}
GOEOF

cd "$SERVER_DIR"
SIGN_BIN="$OUTPUT_DIR/sign_test"
go run "$SIGN_GO" "$CV" "$SECRET" > "$OUTPUT_DIR/sign_test.out" 2>&1
SIGN_OUT=$(cat "$OUTPUT_DIR/sign_test.out")
[[ "$SIGN_OUT" == *"HTTP: 200"* ]] && S9=true || S9=false
record "S9.signing_transport_e2e" "$S9" 0 0 "" "" 0 "out=${SIGN_OUT:0:80}"
cd "$PROJ_DIR"

# 重启 cvengine 在 HMAC required=false（非 required，缺签头放行）
log "10. 重启 cvengine（HMAC secret 设但 required=false）"
kill -9 $PID 2>/dev/null; wait 2>/dev/null

LD_LIBRARY_PATH=/usr/local/lib:/usr/local/lib64:/usr/local/onnxruntime/lib \
GOMOB_CVENGINE_HTTP_ADDR=:18810 \
GOMOB_HMAC_SECRET="$SECRET" \
    "$SERVER_DIR/.dev/bin/gomob-cvengine" > "$OUTPUT_DIR/cvengine_lax.log" 2>&1 &
PID=$!
sleep 2

# S10 lax 模式：缺签头应放行（200）
step "S10.lax_missing_headers_passes" 200 0 -X GET "$CV/cv/v1/version" > /dev/null

# S11 lax 模式：但有部分签头时仍校验（缺一返 40110）
step "S11.lax_partial_still_rejected_40110" 401 40110 -X GET "$CV/cv/v1/version" \
    -H "X-Gomob-Hmac-Ts: $(date +%s)" > /dev/null

# 重启 cvengine 在无 HMAC 模式
log "12. 重启 cvengine（HMAC secret 空 → 完全 noop）"
kill -9 $PID 2>/dev/null; wait 2>/dev/null

LD_LIBRARY_PATH=/usr/local/lib:/usr/local/lib64:/usr/local/onnxruntime/lib \
GOMOB_CVENGINE_HTTP_ADDR=:18810 \
    "$SERVER_DIR/.dev/bin/gomob-cvengine" > "$OUTPUT_DIR/cvengine_disabled.log" 2>&1 &
PID=$!
sleep 2

# S12 disabled 模式：完全无验签（缺头、错签都通过）
step "S12.disabled_no_check" 200 0 -X GET "$CV/cv/v1/version" > /dev/null

step "S13.disabled_with_wrong_sig_passes" 200 0 -X GET "$CV/cv/v1/version" \
    -H "X-Gomob-Hmac-Ts: $(date +%s)" \
    -H "X-Gomob-Hmac-Nonce: any" \
    -H "X-Gomob-Hmac-Sig: deadbeef" > /dev/null

# 清理
rm -f "$EMPTY_BODY" "$BODY_FILE" "$SIGN_GO"

log "采样完成 → $RESULTS"
