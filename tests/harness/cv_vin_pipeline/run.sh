#!/bin/bash
# cv_vin_pipeline/run.sh — M-S10 Phase 2.x 一站式 VIN pipeline 端到端
#
# 验证 POST /cv/ocr/v1/vin_pipeline：
#   - 整图喂入 → VMASK 检测 → 字符 mask 抠出 → vin-ref 厂家库对照 → verdict
#   - 合成图（非真实 VIN 拍照）上 detections=0 → verdict=fail + reasons=[no_chars_detected]
#   - 错误路径：tag 未注册 / 缺图 / 缺 vmid / vmid 非法
#   - 自定义阈值 pass_threshold / warn_threshold 反映到响应
#
# 不需要 vin-ref 真起；在 0 检测路径下不会调用 vinref。
# 对 detections>0 的"真有车牌的图"路径，由 worker_preliminary 已经端到端覆盖（间接调
# vin_character_compare_with_ref 走相同的 ProcVinCharacterCompare）。本 harness 专测
# pipeline 这层 orchestration 的契约（响应结构 / verdict 决策 / 错误路径）。

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVER_DIR="$PROJ_DIR/server"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/cv_vin_pipeline}"
mkdir -p "$OUTPUT_DIR"
RESULTS="$OUTPUT_DIR/results.jsonl"
: > "$RESULTS"

CV=http://127.0.0.1:18810
VMASK="/root/lilw/gosmart/data/vins0.onnx"

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

# 0. 前置
log "0. 编译 + 前置"
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-cvengine ./cmd/cvengine) || exit 3
pkill -9 -f gomob-cvengine 2>/dev/null
sleep 2

if [[ ! -f "$VMASK" ]]; then
    log "✗ 缺 VMASK 模型: $VMASK"
    record "S0.vmask_available" false 0 0 "" "" 0 "$VMASK 不存在"
    exit 4
fi
record "S0.vmask_available" true 0 0 "" "" 0 "$(stat -c%s $VMASK)B"

LD_LIBRARY_PATH=/usr/local/lib:/usr/local/lib64:/usr/local/onnxruntime/lib \
GOMOB_CVENGINE_HTTP_ADDR=:18810 \
GOMOB_VINREF_TARGET=http://127.0.0.1:18058 \
GOMOB_CVENGINE_MODELS="VMASK:mask=$VMASK:0|1|2|3|4|5|6|7|8|9|A|B|C|D|E|F|G|H|J|K|L|M|N|P|R|S|T|U|V|W|X|Y|Z" \
    "$SERVER_DIR/.dev/bin/gomob-cvengine" > "$OUTPUT_DIR/cvengine.log" 2>&1 &
PID=$!
trap "kill -9 \$PID 2>/dev/null; wait 2>/dev/null" EXIT
sleep 10

hc=$(curl -s -o /dev/null -w '%{http_code}' "$CV/healthz")
if [[ "$hc" != "200" ]]; then
    log "✗ /healthz=$hc"
    cat "$OUTPUT_DIR/cvengine.log" | tail -30
    exit 4
fi
record "S0b.cvengine_up" true 200 200 "" "" 0 "/healthz=200"

# 准备合成图（用之前 cv_yolo_detect 留下的或重生成）
A_PNG="$PROJ_DIR/.dev/cv_yolo_detect/A.png"
if [[ ! -f "$A_PNG" ]]; then
    A_PNG="$OUTPUT_DIR/A.png"
    python3 - <<'PY' > "$A_PNG"
import struct, zlib, sys
W=H=64
raw = b''
for y in range(H):
    raw += b'\x00' + bytes(0 for _ in range(W))
def chunk(t, d):
    return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d))
sig = b'\x89PNG\r\n\x1a\n'
ihdr = struct.pack('>IIBBBBB', W, H, 8, 0, 0, 0, 0)
idat = zlib.compress(raw)
sys.stdout.buffer.write(sig + chunk(b'IHDR', ihdr) + chunk(b'IDAT', idat) + chunk(b'IEND', b''))
PY
fi

# S1 正常合成图调用 → 200 + 字段完整
b=$(step "S1.pipeline_synthetic_ok" 200 0 -X POST "$CV/cv/ocr/v1/vin_pipeline" \
    -F "image_binary=@$A_PNG" \
    -F "vehicle_model_id=42" \
    -F "tag=VMASK" \
    -F "conf=0.5")
echo "$b" > "$OUTPUT_DIR/s1_resp.json"

ROWS=$(echo "$b" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"].get("image_rows",-1))')
COLS=$(echo "$b" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"].get("image_cols",-1))')
DCOUNT=$(echo "$b" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"].get("detections",-1))')
SCORED=$(echo "$b" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"].get("scored",-1))')
VERDICT=$(echo "$b" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"].get("verdict",""))')
TAG=$(echo "$b" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"].get("tag",""))')
PASS=$(echo "$b" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"].get("pass_threshold",-1))')
WARN=$(echo "$b" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"].get("warn_threshold",-1))')
LOGID=$(echo "$b" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"].get("log_id",""))')
HAS_CHARS=$(echo "$b" | python3 -c 'import sys,json;d=json.load(sys.stdin)["data"];print("true" if isinstance(d.get("characters"),list) else "false")')

[[ "$ROWS" -gt 0 && "$COLS" -gt 0 ]] && S2A=true || S2A=false
record "S2.image_rows_cols_real" "$S2A" 0 0 "" "" 0 "rows=$ROWS cols=$COLS"

[[ "$DCOUNT" -ge 0 && "$SCORED" -ge 0 ]] && S2B=true || S2B=false
record "S3.detections_scored_nonneg" "$S2B" 0 0 "" "" 0 "detections=$DCOUNT scored=$SCORED"

[[ "$TAG" == "VMASK" ]] && S2C=true || S2C=false
record "S4.tag_echoed" "$S2C" 0 0 "" "" 0 "tag=$TAG"

[[ "$PASS" == "0.85" && "$WARN" == "0.6" ]] && S2D=true || S2D=false
record "S5.default_thresholds" "$S2D" 0 0 "" "" 0 "pass=$PASS warn=$WARN"

[[ -n "$LOGID" ]] && S2E=true || S2E=false
record "S6.log_id_assigned" "$S2E" 0 0 "" "" 0 "log_id=$LOGID"

[[ "$HAS_CHARS" == "true" ]] && S2F=true || S2F=false
record "S7.characters_array_present" "$S2F" 0 0 "" "" 0 "characters is array"

# S8: 合成图大概率 0 检测 → verdict=fail + reasons 含 no_chars_detected
HAS_NCD=$(echo "$b" | python3 -c '
import sys,json
d=json.load(sys.stdin)["data"]
if d.get("detections",0) == 0:
    print("true" if "no_chars_detected" in (d.get("reasons") or []) else "false")
else:
    # 真有检测到（不太可能但允许）— 只验证 verdict 是 pass/warning/fail 之一
    print("true" if d.get("verdict") in ("pass","warning","fail") else "false")
')
record "S8.zero_dets_verdict_fail_or_valid_verdict" "$HAS_NCD" 0 0 "" "" 0 "detections=$DCOUNT verdict=$VERDICT"

# S9 自定义阈值反映在响应
b=$(step "S9.custom_thresholds" 200 0 -X POST "$CV/cv/ocr/v1/vin_pipeline" \
    -F "image_binary=@$A_PNG" \
    -F "vehicle_model_id=42" \
    -F "tag=VMASK" \
    -F "pass_threshold=0.95" \
    -F "warn_threshold=0.50")
PASS2=$(echo "$b" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"].get("pass_threshold",-1))')
WARN2=$(echo "$b" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"].get("warn_threshold",-1))')
[[ "$PASS2" == "0.95" && "$WARN2" == "0.5" ]] && S9OK=true || S9OK=false
record "S9b.custom_thresholds_reflected" "$S9OK" 0 0 "" "" 0 "pass=$PASS2 warn=$WARN2"

# S10 错误：tag 未注册 → 40701
step "S10.tag_not_registered_40701" 404 40701 -X POST "$CV/cv/ocr/v1/vin_pipeline" \
    -F "image_binary=@$A_PNG" \
    -F "vehicle_model_id=42" \
    -F "tag=NONEXISTENT_TAG" > /dev/null

# S11 错误：缺 image_binary → 10001
step "S11.missing_image_10001" 400 10001 -X POST "$CV/cv/ocr/v1/vin_pipeline" \
    -F "vehicle_model_id=42" \
    -F "tag=VMASK" > /dev/null

# S12 错误：缺 vehicle_model_id → 10001
step "S12.missing_vmid_10001" 400 10001 -X POST "$CV/cv/ocr/v1/vin_pipeline" \
    -F "image_binary=@$A_PNG" \
    -F "tag=VMASK" > /dev/null

# S13 错误：vehicle_model_id 非法（负数）→ 10001
step "S13.invalid_vmid_10001" 400 10001 -X POST "$CV/cv/ocr/v1/vin_pipeline" \
    -F "image_binary=@$A_PNG" \
    -F "vehicle_model_id=-5" \
    -F "tag=VMASK" > /dev/null

# S14 错误：method 非法 → 10001
step "S14.invalid_method_10001" 400 10001 -X POST "$CV/cv/ocr/v1/vin_pipeline" \
    -F "image_binary=@$A_PNG" \
    -F "vehicle_model_id=42" \
    -F "tag=VMASK" \
    -F "method=99" > /dev/null

# S15 dev 模式默认不带 X-Gomob-User-Id 也能调
step "S15.dev_no_auth" 200 0 -X POST "$CV/cv/ocr/v1/vin_pipeline" \
    -F "image_binary=@$A_PNG" \
    -F "vehicle_model_id=42" \
    -F "tag=VMASK" > /dev/null

log "采样完成 → $RESULTS"
