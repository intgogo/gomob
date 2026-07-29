#!/bin/bash
# cv_baseline/run.sh — M-S10.8 cv-engine 精度基线 harness
#
# 跑一组已知字符对，记录 IoU / Chamfer 实测值；与 expected.json 的基线对比。
# 偏离 > tol（或不满足 expected_lt / expected_gt）视为回归。
#
# 这是 cv-engine 算法层的 canary：改 judge / proc / OpenCV 版本时跑这个能立刻看到精度抖动。

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVER_DIR="$PROJ_DIR/server"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/cv_baseline}"
mkdir -p "$OUTPUT_DIR"
RESULTS="$OUTPUT_DIR/results.jsonl"
SAMPLES="$OUTPUT_DIR/samples"
mkdir -p "$SAMPLES"
: > "$RESULTS"

CV=http://127.0.0.1:18810

log() { printf "[%s] %s\n" "$(date +%H:%M:%S)" "$*"; }

record() {
    local scenario=$1 ok=$2 actual=$3 note=${4:-}
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
    'actual': $actual,
    'note': '''$note''',
}))
" >> "$RESULTS"
}

# 0. 编译 + 启 cvengine
log "0. 编译 + 启 cvengine"
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-cvengine ./cmd/cvengine) || exit 3
pkill -9 -f gomob-cvengine 2>/dev/null
sleep 2

LD_LIBRARY_PATH=/usr/local/lib:/usr/local/lib64:/usr/local/onnxruntime/lib \
GOMOB_CVENGINE_HTTP_ADDR=:18810 \
    "$SERVER_DIR/.dev/bin/gomob-cvengine" > "$OUTPUT_DIR/cvengine.log" 2>&1 &
PID=$!
trap "kill $PID 2>/dev/null; wait 2>/dev/null" EXIT
sleep 2

hc=$(curl -s -o /dev/null -w '%{http_code}' "$CV/healthz")
[[ "$hc" == "200" ]] || { log "✗ cvengine /healthz=$hc"; exit 4; }

# 1. 生成字符样本
log "1. 生成 6 个字符样本（64x64 PNG）"
python3 - "$SAMPLES" <<'PY'
import os, struct, zlib, sys
out = sys.argv[1]
W = H = 64

def write_png(path, mat):
    raw = b''
    for y in range(H):
        raw += b'\x00' + bytes(mat[y])
    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d))
    sig = b'\x89PNG\r\n\x1a\n'
    ihdr = struct.pack('>IIBBBBB', W, H, 8, 0, 0, 0, 0)
    idat = zlib.compress(raw)
    with open(path, 'wb') as f:
        f.write(sig + chunk(b'IHDR', ihdr) + chunk(b'IDAT', idat) + chunk(b'IEND', b''))

def make_A(shift_x=0):
    mat = [[0]*W for _ in range(H)]
    for y in range(H):
        for x in range(W):
            # 两条斜边（A 形）
            if 12 <= y <= 52 and abs((x - shift_x) - (16 + (52-y)*0.7)) <= 2:
                mat[y][x] = 255
            if 12 <= y <= 52 and abs((x - shift_x) - (48 - (52-y)*0.7)) <= 2:
                mat[y][x] = 255
            # 横梁
            if 32 <= y <= 36 and (24 + shift_x) <= x <= (40 + shift_x):
                mat[y][x] = 255
    return mat

def make_O():
    mat = [[0]*W for _ in range(H)]
    cx, cy = W // 2, H // 2
    for y in range(H):
        for x in range(W):
            dx = (x - cx) / 22.0
            dy = (y - cy) / 26.0
            r2 = dx*dx + dy*dy
            if 0.85 <= r2 <= 1.0:
                mat[y][x] = 255
    return mat

def make_B():
    mat = [[0]*W for _ in range(H)]
    # 左竖
    for y in range(8, 56):
        for x in range(15, 19):
            mat[y][x] = 255
    # 上下两个圈
    for y in range(8, 32):
        for x in range(W):
            dx = (x - 30) / 14.0
            dy = (y - 20) / 12.0
            r2 = dx*dx + dy*dy
            if 0.78 <= r2 <= 1.0:
                mat[y][x] = 255
    for y in range(32, 56):
        for x in range(W):
            dx = (x - 30) / 14.0
            dy = (y - 44) / 12.0
            r2 = dx*dx + dy*dy
            if 0.78 <= r2 <= 1.0:
                mat[y][x] = 255
    return mat

def make_8():
    # 上下两个圆相连
    mat = [[0]*W for _ in range(H)]
    for y in range(H):
        for x in range(W):
            # 上圆
            dx = (x - W//2) / 13.0
            dy = (y - 18) / 11.0
            r1 = dx*dx + dy*dy
            if 0.80 <= r1 <= 1.0:
                mat[y][x] = 255
            # 下圆
            dy2 = (y - 46) / 13.0
            r2 = dx*dx + dy2*dy2
            if 0.80 <= r2 <= 1.0:
                mat[y][x] = 255
    return mat

def make_0():
    mat = [[0]*W for _ in range(H)]
    cx, cy = W // 2, H // 2
    for y in range(H):
        for x in range(W):
            dx = (x - cx) / 16.0
            dy = (y - cy) / 26.0
            r2 = dx*dx + dy*dy
            if 0.85 <= r2 <= 1.0:
                mat[y][x] = 255
    return mat

write_png(os.path.join(out, 'A_clean.png'), make_A(0))
write_png(os.path.join(out, 'A_shift.png'), make_A(8))   # 平移 8 px
write_png(os.path.join(out, 'B_clean.png'), make_B())
write_png(os.path.join(out, 'O_clean.png'), make_O())
write_png(os.path.join(out, '8_clean.png'), make_8())
write_png(os.path.join(out, '0_clean.png'), make_0())
print("生成 6 个字符样本")
PY
ls -la "$SAMPLES"

# 2. 跑基线对
log "2. 调 cv-engine /cv/ocr/v1/vin_character_compare 比对每对"

EXPECTED_FILE="$(dirname "$0")/expected.json"
PAIRS=$(python3 -c "import json,sys; d=json.load(open('$EXPECTED_FILE')); print(len(d['pairs']))")
log "  共 $PAIRS 对"

# 用 python 一次跑所有 pairs，直接更新 results.jsonl
python3 - "$EXPECTED_FILE" "$SAMPLES" "$RESULTS" "$CV" <<'PY'
import json, os, sys, urllib.request, urllib.parse
exp_file, samples, results, cv = sys.argv[1:5]
spec = json.load(open(exp_file))

def call_compare(file_a, file_b, method):
    boundary = '----GomobBoundaryX'
    body = b''
    def part(name, value, filename=None, ctype=None):
        nonlocal body
        body += ('--' + boundary + '\r\n').encode()
        if filename:
            body += f'Content-Disposition: form-data; name="{name}"; filename="{filename}"\r\n'.encode()
            body += f'Content-Type: {ctype}\r\n\r\n'.encode()
            with open(value, 'rb') as f:
                body += f.read()
            body += b'\r\n'
        else:
            body += f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode()
            body += f'{value}\r\n'.encode()
    part('image_binary1', file_a, filename='a.png', ctype='image/png')
    part('image_binary2', file_b, filename='b.png', ctype='image/png')
    part('method', str(method))
    body += ('--' + boundary + '--\r\n').encode()
    req = urllib.request.Request(cv + '/cv/ocr/v1/vin_character_compare', data=body,
        headers={'Content-Type': 'multipart/form-data; boundary=' + boundary})
    with urllib.request.urlopen(req, timeout=10) as resp:
        d = json.load(resp)
    if d.get('code') != 0:
        raise RuntimeError(d)
    return d['data']

with open(results, 'w') as out:
    for p in spec['pairs']:
        a = os.path.join(samples, p['char_a'] + '.png')
        b = os.path.join(samples, p['char_b'] + '.png')
        try:
            data = call_compare(a, b, p['method'])
        except Exception as e:
            out.write(json.dumps({
                'scenario': p['name'],
                'ok': False,
                'actual': None,
                'note': f'调用失败: {e}',
                'http_code': 0, 'expected_http': 0, 'code': None,
                'expected_code': None, 'latency_ms': 0,
            }) + '\n')
            continue

        # 取 IoU 用 similarity（已归一化到 0..1）；Chamfer 用原始 value
        if p['method'] == 0:
            actual = data['similarity']
        else:
            actual = data['value']

        ok = True
        note = ''
        if 'expected' in p:
            tol = p.get('tol', 0.05)
            if abs(actual - p['expected']) > tol:
                ok = False
                note = f'actual={actual:.4f} expected={p["expected"]} tol={tol}'
            else:
                note = f'actual={actual:.4f} expected={p["expected"]} (within tol={tol})'
        if 'expected_lt' in p:
            if actual >= p['expected_lt']:
                ok = False
                note += f' actual={actual:.4f} ≥ expected_lt={p["expected_lt"]}'
            else:
                note += f' actual={actual:.4f} < {p["expected_lt"]} ✓'
        if 'expected_gt' in p:
            if actual <= p['expected_gt']:
                ok = False
                note += f' actual={actual:.4f} ≤ expected_gt={p["expected_gt"]}'
            else:
                note += f' actual={actual:.4f} > {p["expected_gt"]} ✓'

        out.write(json.dumps({
            'scenario': p['name'],
            'ok': ok,
            'actual': actual,
            'note': note,
            'http_code': 0, 'expected_http': 0, 'code': None,
            'expected_code': None, 'latency_ms': 0,
        }) + '\n')

print('done')
PY

# 3. 输出 baseline_observed.json（实测值汇总；可手动 promote 到 expected.json）
log "3. 输出 baseline_observed.json"
python3 - <<PY > /dev/null
import json
rows=[json.loads(l) for l in open("$RESULTS") if l.strip()]
out={r['scenario']:r['actual'] for r in rows if r.get('actual') is not None}
json.dump(out, open("$OUTPUT_DIR/baseline_observed.json","w"), indent=2)
PY

log "采样完成 → $RESULTS"
log "  实测值汇总 → $OUTPUT_DIR/baseline_observed.json"
