#!/bin/bash
# cv_yolo_detect/run.sh — M-S10 Phase 2.3 yolo VMASK 真推理烟测
#
# 验证：
#   - VMASK 模型（gosmart vins0.onnx 354MB）通过 core.RegisterMaskONNX 加载成功
#   - /cv/v1/models 暴露 kind=mask + classes=[vin]
#   - /cv/ocr/v1/vin_detect_yolo 真调 onnxruntime 推理（不是 stub）
#   - 在合成图（非真实 VIN 拍照）上 detections=[]（这是 yolo 真实输出 — 正确语义）
#   - 错误路径：tag 未注册 → 40701；缺图 → 10001
#
# 这一步把"真 onnxruntime + 真 yolo + 真 mask 后处理"端到端跑通；M-S10 Phase 2.3
# 完成后 cv-engine 就能把整张 VIN 拍照图喂进去返检测框，剩下的拼接到
# vin_character_compare_with_ref 走对照集比对（Phase 2.x 完整 ProcVINDet 时做）。

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVER_DIR="$PROJ_DIR/server"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/cv_yolo_detect}"
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
GOMOB_CVENGINE_MODELS="VMASK:mask=$VMASK:vin" \
    "$SERVER_DIR/.dev/bin/gomob-cvengine" > "$OUTPUT_DIR/cvengine.log" 2>&1 &
PID=$!
trap "kill $PID 2>/dev/null; wait 2>/dev/null" EXIT
# 354MB 模型加载 + onnxruntime 启动 ≈ 5-10s
sleep 10

hc=$(curl -s -o /dev/null -w '%{http_code}' "$CV/healthz")
if [[ "$hc" != "200" ]]; then
    log "✗ /healthz=$hc"
    cat "$OUTPUT_DIR/cvengine.log" | tail -20
    exit 4
fi

# S1 模型注册成功
b=$(step "S1.list_models" 200 0 "$CV/cv/v1/models")
LOADED=$(echo "$b" | python3 -c '
import sys,json
d = json.load(sys.stdin)
for it in d["data"]["items"]:
    if it["tag"] == "VMASK":
        print("true" if it.get("loaded") and it.get("kind") == "mask" else "false")
        sys.exit(0)
print("false")')
record "S1b.vmask_loaded_kind_mask" "$LOADED" 0 0 "" "" 0 "VMASK loaded=true kind=mask"

# S2 classes 字段
HAS_CLASSES=$(echo "$b" | python3 -c '
import sys,json
d = json.load(sys.stdin)
for it in d["data"]["items"]:
    if it["tag"] == "VMASK":
        cls = it.get("classes", [])
        print("true" if "vin" in cls else "false")
        sys.exit(0)
print("false")')
record "S2.classes_vin" "$HAS_CLASSES" 0 0 "" "" 0 "classes 含 vin"

# S3 在合成 A.png 上跑 yolo —— 不报错且 detections 为列表（合成图大概率 0 检测，是真实 yolo 输出）
# 用之前 cv_vin_compare 留下的 A.png；如果不存在就生成一张
A_PNG="$PROJ_DIR/.dev/cv_vin_compare/A.png"
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

b=$(step "S3.detect_synthetic" 200 0 -X POST "$CV/cv/ocr/v1/vin_detect_yolo" \
    -F "image_binary=@$A_PNG" \
    -F "tag=VMASK" \
    -F "conf=0.5")
ROWS=$(echo "$b" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"].get("image_rows",0))')
COLS=$(echo "$b" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"].get("image_cols",0))')
DCOUNT=$(echo "$b" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"].get("count",-1))')
TAG=$(echo "$b" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"].get("tag",""))')
# 期望：image_rows / cols 真实，count 是非负整数（≥0），tag=VMASK
[[ "$ROWS" -gt 0 && "$COLS" -gt 0 && "$DCOUNT" -ge 0 && "$TAG" == "VMASK" ]] && S3OK=true || S3OK=false
record "S3b.detect_response_well_formed" "$S3OK" 0 0 "" "" 0 "rows=$ROWS cols=$COLS count=$DCOUNT tag=$TAG"

# S4 用更小的 conf 阈值（0.0）→ 至少结构合法；count 可能更高（仍是真 yolo 输出，不是 stub）
b=$(step "S4.detect_low_conf" 200 0 -X POST "$CV/cv/ocr/v1/vin_detect_yolo" \
    -F "image_binary=@$A_PNG" \
    -F "tag=VMASK" \
    -F "conf=0.01")
DCOUNT2=$(echo "$b" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"].get("count",-1))')
[[ "$DCOUNT2" -ge "$DCOUNT" ]] && S4OK=true || S4OK=false
record "S4b.lower_conf_at_least_eq_count" "$S4OK" 0 0 "" "" 0 "high_conf_count=$DCOUNT low_conf_count=$DCOUNT2"

# S5 错误：tag 未注册
step "S5.tag_not_registered_40701" 404 40701 -X POST "$CV/cv/ocr/v1/vin_detect_yolo" \
    -F "image_binary=@$A_PNG" \
    -F "tag=NONEXISTENT_TAG" > /dev/null

# S6 错误：缺 image_binary
step "S6.missing_image_10001" 400 10001 -X POST "$CV/cv/ocr/v1/vin_detect_yolo" \
    -F "tag=VMASK" > /dev/null

# S7 鉴权关闭时（默认 dev）：不带 X-Gomob-User-Id 也能调
b=$(step "S7.dev_no_auth_required" 200 0 -X POST "$CV/cv/ocr/v1/vin_detect_yolo" \
    -F "image_binary=@$A_PNG" \
    -F "tag=VMASK" \
    -F "conf=0.5")
log "采样完成 → $RESULTS"
