#!/bin/bash
# cv_vin_compare/run.sh — M-S10 Phase 2 vin_character_compare 真业务端点烟测
#
# 端到端验证：
#   - 同一字符二值图 vs 自身 → IoU 接近 1.0（>= 0.95）
#   - 同字符 + 微小平移 → IoU 仍较高（>= 0.5；旋转搜索 + 重心对齐前后差不大）
#   - 完全不同字符 → IoU 显著低（<= 0.5）
#   - Chamfer 模式：相同 → 接近 0；不同 → > 0
#
# 该 harness 不打 mock，所有相似度都是 OpenCV BitwiseAnd/Or + DistanceTransform
# 真算的产物。失败 = 算法集成跑歪了，不是 stub 的"返回 0.5"。

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVER_DIR="$PROJ_DIR/server"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/cv_vin_compare}"
mkdir -p "$OUTPUT_DIR"
RESULTS="$OUTPUT_DIR/results.jsonl"
: > "$RESULTS"

CV=http://127.0.0.1:18810

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

# ============================================================================
# 0. 编译 + 启动 cvengine
# ============================================================================
log "0. 编译 + 启动 cvengine"
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-cvengine ./cmd/cvengine) || exit 3

LD_LIBRARY_PATH=/usr/local/lib:/usr/local/lib64:/usr/local/onnxruntime/lib \
GOMOB_CVENGINE_HTTP_ADDR=:18810 \
    "$SERVER_DIR/.dev/bin/gomob-cvengine" > "$OUTPUT_DIR/cvengine.log" 2>&1 &
PID=$!
trap "kill $PID 2>/dev/null; wait 2>/dev/null" EXIT
sleep 1

hc=$(curl -s -o /dev/null -w '%{http_code}' "$CV/healthz")
[[ "$hc" == "200" ]] || { log "✗ cvengine /healthz=$hc"; cat "$OUTPUT_DIR/cvengine.log" | tail; exit 4; }

# ============================================================================
# 1. 生成测试字符图（64x64 二值）
# ============================================================================
log "1. 生成测试字符图"
# 用 python 直接画 PNG，不依赖 PIL。
# A：在 64x64 画一个矩形 "A 形"（简化字形）
# B：完全不同的形状（圆形）
# A_shift：A 的同形状但平移 3 像素
gen_png() {
    local out=$1 shape=$2 dx=${3:-0} dy=${4:-0}
    python3 - <<PY > "$out"
import struct, zlib, sys
W=H=64
# 背景 0，字符 255
def chr_bytes(shape, dx=0, dy=0):
    rows = []
    for y in range(H):
        row = bytearray(W)
        for x in range(W):
            xx, yy = x-dx, y-dy
            v = 0
            if shape == 'A':
                # 字形 A：两条斜线 + 横杠
                # 左边斜：x = 16 + (40-y)*0.4
                #   y=20..50 区间画
                if 20 <= yy <= 50 and abs(xx - (16 + (50-yy)*0.7)) <= 2:
                    v = 255
                # 右边斜
                if 20 <= yy <= 50 and abs(xx - (48 - (50-yy)*0.7)) <= 2:
                    v = 255
                # 横杠
                if 33 <= yy <= 36 and 24 <= xx <= 40:
                    v = 255
            elif shape == 'O':
                # 圆形：半径 16，圆心 (32,32)
                d = ((xx-32)**2 + (yy-32)**2) ** 0.5
                if 14 <= d <= 18:
                    v = 255
            row[x] = v
        rows.append(b'\x00' + bytes(row))
    return b''.join(rows)
raw = chr_bytes('$shape', $dx, $dy)
def chunk(t, d):
    return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d))
sig = b'\x89PNG\r\n\x1a\n'
ihdr = struct.pack('>IIBBBBB', W, H, 8, 0, 0, 0, 0)  # 8-bit grayscale
idat = zlib.compress(raw)
sys.stdout.buffer.write(sig + chunk(b'IHDR', ihdr) + chunk(b'IDAT', idat) + chunk(b'IEND', b''))
PY
}

gen_png "$OUTPUT_DIR/A.png" A
gen_png "$OUTPUT_DIR/A_copy.png" A
gen_png "$OUTPUT_DIR/A_shift.png" A 3 2
gen_png "$OUTPUT_DIR/O.png" O
log "  A.png $(stat -c%s "$OUTPUT_DIR/A.png")B  A_shift.png $(stat -c%s "$OUTPUT_DIR/A_shift.png")B  O.png $(stat -c%s "$OUTPUT_DIR/O.png")B"

# ============================================================================
# 2. 跑场景
# ============================================================================
log "2. 跑场景"

# S1 同图自比 IOU → similarity ≥ 0.95
b=$(step "S1.identical_iou" 200 0 -X POST "$CV/cv/ocr/v1/vin_character_compare" \
    -F "image_binary1=@$OUTPUT_DIR/A.png" \
    -F "image_binary2=@$OUTPUT_DIR/A_copy.png" \
    -F "method=0")
SIM_IDENTICAL=$(echo "$b" | extract data.similarity)
VAL_IDENTICAL=$(echo "$b" | extract data.value)
S1_OK=$(python3 -c "print('true' if float('$SIM_IDENTICAL' or 0) >= 0.95 else 'false')")
record "S1b.identical_iou_high" "$S1_OK" 0 0 "" "" 0 "value=$VAL_IDENTICAL similarity=$SIM_IDENTICAL want>=0.95"

# S2 同字符微平移 IOU → similarity ≥ 0.5（旋转搜索 + 膨胀帮一些；非完美但显著高于不同字符）
b=$(step "S2.shifted_iou" 200 0 -X POST "$CV/cv/ocr/v1/vin_character_compare" \
    -F "image_binary1=@$OUTPUT_DIR/A.png" \
    -F "image_binary2=@$OUTPUT_DIR/A_shift.png" \
    -F "method=0")
SIM_SHIFT=$(echo "$b" | extract data.similarity)
S2_OK=$(python3 -c "print('true' if float('$SIM_SHIFT' or 0) >= 0.5 else 'false')")
record "S2b.shifted_iou_mid" "$S2_OK" 0 0 "" "" 0 "similarity=$SIM_SHIFT want>=0.5"

# S3 不同字符 IOU → similarity ≤ 0.5
b=$(step "S3.different_iou" 200 0 -X POST "$CV/cv/ocr/v1/vin_character_compare" \
    -F "image_binary1=@$OUTPUT_DIR/A.png" \
    -F "image_binary2=@$OUTPUT_DIR/O.png" \
    -F "method=0")
SIM_DIFF=$(echo "$b" | extract data.similarity)
S3_OK=$(python3 -c "print('true' if float('$SIM_DIFF' or 0) <= 0.5 else 'false')")
record "S3b.different_iou_low" "$S3_OK" 0 0 "" "" 0 "similarity=$SIM_DIFF want<=0.5"

# S4 IoU 排序：同图 > 平移 > 不同字符（端到端语义校验）
SORT_OK=$(python3 -c "
a=float('$SIM_IDENTICAL' or 0); b=float('$SIM_SHIFT' or 0); c=float('$SIM_DIFF' or 0)
print('true' if a > b and b > c else 'false')")
record "S4.iou_sort_identical_gt_shifted_gt_diff" "$SORT_OK" 0 0 "" "" 0 "identical=$SIM_IDENTICAL shifted=$SIM_SHIFT diff=$SIM_DIFF"

# S5 Chamfer 模式：同图 → value 接近 0；不同 → 显著 > 0
b=$(step "S5.identical_chamfer" 200 0 -X POST "$CV/cv/ocr/v1/vin_character_compare" \
    -F "image_binary1=@$OUTPUT_DIR/A.png" \
    -F "image_binary2=@$OUTPUT_DIR/A_copy.png" \
    -F "method=1")
VAL_CHAMFER_SAME=$(echo "$b" | extract data.value)
SIM_CHAMFER_SAME=$(echo "$b" | extract data.similarity)
S5_OK=$(python3 -c "print('true' if float('$VAL_CHAMFER_SAME' or 999) <= 0.5 and float('$SIM_CHAMFER_SAME' or 0) >= 0.9 else 'false')")
record "S5b.chamfer_same_low" "$S5_OK" 0 0 "" "" 0 "value=$VAL_CHAMFER_SAME similarity=$SIM_CHAMFER_SAME want value<=0.5 sim>=0.9"

b=$(step "S6.different_chamfer" 200 0 -X POST "$CV/cv/ocr/v1/vin_character_compare" \
    -F "image_binary1=@$OUTPUT_DIR/A.png" \
    -F "image_binary2=@$OUTPUT_DIR/O.png" \
    -F "method=1")
VAL_CHAMFER_DIFF=$(echo "$b" | extract data.value)
SIM_CHAMFER_DIFF=$(echo "$b" | extract data.similarity)
S6_OK=$(python3 -c "print('true' if float('$VAL_CHAMFER_DIFF' or 0) > 0.5 and float('$SIM_CHAMFER_DIFF' or 1) <= 0.9 else 'false')")
record "S6b.chamfer_diff_high" "$S6_OK" 0 0 "" "" 0 "value=$VAL_CHAMFER_DIFF similarity=$SIM_CHAMFER_DIFF want value>0.5"

# S7 错误：缺 image_binary2 → 10001
step "S7.missing_image2_10001" 400 10001 -X POST "$CV/cv/ocr/v1/vin_character_compare" \
    -F "image_binary1=@$OUTPUT_DIR/A.png" \
    -F "method=0" > /dev/null

# S8 错误：method=99 → 10001
step "S8.invalid_method_10001" 400 10001 -X POST "$CV/cv/ocr/v1/vin_character_compare" \
    -F "image_binary1=@$OUTPUT_DIR/A.png" \
    -F "image_binary2=@$OUTPUT_DIR/A_copy.png" \
    -F "method=99" > /dev/null

# S9 错误：图坏 → 10001
echo "garbage" > "$OUTPUT_DIR/bad.png"
step "S9.bad_image_10001" 400 10001 -X POST "$CV/cv/ocr/v1/vin_character_compare" \
    -F "image_binary1=@$OUTPUT_DIR/A.png" \
    -F "image_binary2=@$OUTPUT_DIR/bad.png" \
    -F "method=0" > /dev/null

# S10 require_auth=true 路径：缺 X-Gomob-User-Id 应 40102
kill $PID 2>/dev/null; wait 2>/dev/null
LD_LIBRARY_PATH=/usr/local/lib:/usr/local/lib64:/usr/local/onnxruntime/lib \
GOMOB_CVENGINE_HTTP_ADDR=:18810 GOMOB_CVENGINE_REQUIRE_AUTH=true \
    "$SERVER_DIR/.dev/bin/gomob-cvengine" > "$OUTPUT_DIR/cvengine_auth.log" 2>&1 &
PID=$!
sleep 1
step "S10.require_auth_40102" 401 40102 -X POST "$CV/cv/ocr/v1/vin_character_compare" \
    -F "image_binary1=@$OUTPUT_DIR/A.png" \
    -F "image_binary2=@$OUTPUT_DIR/A_copy.png" \
    -F "method=0" > /dev/null
# 给 X-Gomob-User-Id 应放行
b=$(step "S11.require_auth_with_header" 200 0 -X POST "$CV/cv/ocr/v1/vin_character_compare" \
    -H "X-Gomob-User-Id: 1" \
    -F "image_binary1=@$OUTPUT_DIR/A.png" \
    -F "image_binary2=@$OUTPUT_DIR/A_copy.png" \
    -F "method=0")

log "3. 采样完成 → $RESULTS"
log "   下一步：python3 $(dirname "$0")/analyze.py $OUTPUT_DIR"
