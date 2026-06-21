#!/bin/bash
# cv_models_smoke/run.sh — M-S10 Phase 2.1 ONNX 模型注册 + /cv/v1/models 烟测
#
# 验证：
#   - 启动期 GOMOB_CVENGINE_MODELS="TAG=path,..." 解析正确
#   - 真 ONNX 文件（106MB vmet1.onnx）通过 gocv.ReadNet 加载成功 → loaded=true + size_bytes 准确
#   - 不存在的 ONNX 路径优雅失败 → loaded=false + error 字段非空，不阻塞其它项
#   - /cv/v1/models 返完整列表（含失败项）+ loaded_count
#   - 不设 GOMOB_CVENGINE_MODELS 时返 items=[]
#
# 这步把"cv-engine 能真实加载 ONNX 模型"这一阻塞问题消除；M-S10.4 的"按 model-registry
# 拉 active 版本"只是把"读本地文件"换成"调 gRPC + 下载"，加载机制本身已在 Phase 2.1 验证。

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVER_DIR="$PROJ_DIR/server"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/cv_models_smoke}"
mkdir -p "$OUTPUT_DIR"
RESULTS="$OUTPUT_DIR/results.jsonl"
: > "$RESULTS"

CV=http://127.0.0.1:18810
# 使用 gosmart data/ 下真实 ONNX 模型做加载测试。
# 这些不入 gomob 仓（GB 级、训练侧产物）；仅在本机 dev 环境用。
# M12.4 机器特定绝对路径参数化：可被 GOMOB_VMET_ONNX 覆盖，默认保持本机现行为。
GOSMART_MODEL="${GOMOB_VMET_ONNX:-/root/lilw/gosmart/data/vmet1.onnx}"

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

# ============================================================================
# 0. 编译
# ============================================================================
log "0. 编译 cvengine"
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-cvengine ./cmd/cvengine) || exit 3
pkill -9 -f gomob-cvengine 2>/dev/null
sleep 1

start_cv() {
    local models_env=$1
    local label=$2
    LD_LIBRARY_PATH=/usr/local/lib:/usr/local/lib64:/usr/local/onnxruntime/lib \
    GOMOB_CVENGINE_HTTP_ADDR=:18810 \
    GOMOB_CVENGINE_MODELS="$models_env" \
        "$SERVER_DIR/.dev/bin/gomob-cvengine" > "$OUTPUT_DIR/cv-$label.log" 2>&1 &
    local pid=$!
    echo $pid
}

# ============================================================================
# 场景 A：不设 models → /cv/v1/models 返 items=[]
# ============================================================================
log "A. 空模型集"
PID=$(start_cv "" "empty")
sleep 1
trap "kill $PID 2>/dev/null; wait 2>/dev/null" EXIT

b=$(step "A1.list_empty" 200 0 "$CV/cv/v1/models")
TOTAL=$(echo "$b" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["total"])')
LOADED=$(echo "$b" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["loaded_count"])')
[[ "$TOTAL" == "0" && "$LOADED" == "0" ]] && A1OK=true || A1OK=false
record "A1b.empty_list" "$A1OK" 0 0 "" "" 0 "total=$TOTAL loaded_count=$LOADED"

kill $PID 2>/dev/null; wait 2>/dev/null
sleep 1

# ============================================================================
# 场景 B：真 ONNX + 不存在路径混用
# ============================================================================
log "B. 真 ONNX + 不存在路径混用"
if [[ ! -f "$GOSMART_MODEL" ]]; then
    log "  ✗ 缺 $GOSMART_MODEL，跳过 ONNX 真加载场景"
    record "B0.real_onnx_available" false 0 0 "" "" 0 "$GOSMART_MODEL not found"
else
    record "B0.real_onnx_available" true 0 0 "" "" 0 "$(stat -c%s $GOSMART_MODEL)B"
    PID=$(start_cv "VMET=$GOSMART_MODEL,BOGUS=/nonexistent/no.onnx" "real")
    # vmet1.onnx 106MB onnxruntime 加载需 2-3s
    sleep 4
    trap "kill $PID 2>/dev/null; wait 2>/dev/null" EXIT

    # B1 listing
    b=$(step "B1.list_two" 200 0 "$CV/cv/v1/models")
    TOTAL=$(echo "$b" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["total"])')
    LOADED=$(echo "$b" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["loaded_count"])')
    [[ "$TOTAL" == "2" && "$LOADED" == "1" ]] && B1OK=true || B1OK=false
    record "B1b.total_2_loaded_1" "$B1OK" 0 0 "" "" 0 "total=$TOTAL loaded_count=$LOADED"

    # B2 VMET 加载成功
    VMET_OK=$(echo "$b" | python3 -c '
import sys,json
d=json.load(sys.stdin)
for it in d["data"]["items"]:
    if it["tag"] == "VMET":
        print("true" if it.get("loaded") and it.get("size_bytes",0) > 100000000 else "false")
        sys.exit(0)
print("false")')
    record "B2.vmet_loaded" "$VMET_OK" 0 0 "" "" 0 "VMET tag loaded=true size>100MB"

    # B3 BOGUS 失败但有 error 字段
    BOGUS_OK=$(echo "$b" | python3 -c '
import sys,json
d=json.load(sys.stdin)
for it in d["data"]["items"]:
    if it["tag"] == "BOGUS":
        loaded = it.get("loaded", True)
        err = it.get("error", "")
        print("true" if (not loaded) and err else "false")
        sys.exit(0)
print("false")')
    record "B3.bogus_failed_with_error" "$BOGUS_OK" 0 0 "" "" 0 "BOGUS loaded=false + error 非空"

    # B4 readyz / version 仍正常（cgo 链未坏）
    b=$(step "B4.readyz_still_ok" 200 0 "$CV/readyz")
    OCV=$(echo "$b" | python3 -c '
import sys,json
try: print(json.load(sys.stdin)["data"]["opencv_version"])
except Exception: print("")')
    [[ -n "$OCV" ]] && B4OK=true || B4OK=false
    record "B4b.opencv_still_loaded" "$B4OK" 0 0 "" "" 0 "opencv=$OCV"

    kill $PID 2>/dev/null; wait 2>/dev/null
    sleep 1
fi

log "采样完成 → $RESULTS"
