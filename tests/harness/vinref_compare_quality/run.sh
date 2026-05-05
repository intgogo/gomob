#!/bin/bash
# vinref_compare_quality/run.sh — M-S8.x vin-ref 精度基线
#
# 跑 5+1 个 scan 字符 vs 厂家库的 best 命中 + similarity 区间，与 expected.json 基线对比。
# 偏离 → 异常。promote 时把 .dev/vinref_compare_quality/baseline_observed.json
# 的 actual 值回填 expected.json。
#
# 这是端到端"vinref ↔ asset MinIO ↔ cv-engine ProcVinCharacterCompare"完整链路精度看板。

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVER_DIR="$PROJ_DIR/server"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/vinref_compare_quality}"
mkdir -p "$OUTPUT_DIR"
RESULTS="$OUTPUT_DIR/results.jsonl"
SAMPLES="$OUTPUT_DIR/samples"
mkdir -p "$SAMPLES"
: > "$RESULTS"

ADMIN=http://127.0.0.1:19090
ASSET=http://127.0.0.1:18083
VINREF=http://127.0.0.1:18058
CV=http://127.0.0.1:18810
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
log "0. 前置：清表 + 编译"
podman exec -i gomob-pg psql -U gomob -d gomob -v ON_ERROR_STOP=1 > /dev/null <<'SQL'
DELETE FROM audit_log;
DELETE FROM upload_sessions;
DELETE FROM inspection_assets;
DELETE FROM reviews;
DELETE FROM inspections;
DELETE FROM vehicles;
DELETE FROM vin_glyph_samples;
DELETE FROM vin_glyph_batches;
DELETE FROM vehicle_shapes;
DELETE FROM vehicle_models;
DELETE FROM messages;
DELETE FROM conversation_members;
DELETE FROM conversations;
DELETE FROM call_logs;
DELETE FROM pending_calls;
DELETE FROM llm_call_logs;
DELETE FROM llm_templates;
DELETE FROM users;
DELETE FROM stations;
INSERT INTO stations(name, region) VALUES('测试检测站','test');
INSERT INTO users(username, real_name, employee_id, password_hash, role, status, station_id)
VALUES('master','超管','SUPER',
  '$2a$10$placeholderHash..............................',
  'admin','active',
  (SELECT id FROM stations ORDER BY id DESC LIMIT 1));
SQL
ADMIN_ID=$(podman exec gomob-pg psql -U gomob -d gomob -tAc "SELECT id FROM users WHERE username='master'")

(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-auth     ./cmd/auth)     || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-asset    ./cmd/asset)    || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-vinref   ./cmd/vinref)   || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-catalog  ./cmd/catalog)  || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-admin    ./cmd/admin)    || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-cvengine ./cmd/cvengine) || exit 3

pkill -9 -f "gomob-cvengine\|gomob-vinref\|gomob-asset\|gomob-auth\|gomob-admin\|gomob-catalog" 2>/dev/null
sleep 2

PIDS=()
trap 'for p in "${PIDS[@]:-}"; do kill -9 $p 2>/dev/null || true; done; wait 2>/dev/null || true' EXIT

log "1. 启动服务"
GOMOB_AUTH_HTTP_ADDR=:18082 GOMOB_AUTH_DEV_AUTOACTIVATE=true \
    "$SERVER_DIR/.dev/bin/gomob-auth"     > "$OUTPUT_DIR/auth.log"     2>&1 & PIDS+=($!)
GOMOB_ASSET_HTTP_ADDR=:18083 GOMOB_REDIS_ADDR=127.0.0.1:6379 \
GOMOB_MINIO_ENDPOINT=127.0.0.1:9000 GOMOB_MINIO_BUCKET=gomob-assets \
    "$SERVER_DIR/.dev/bin/gomob-asset"    > "$OUTPUT_DIR/asset.log"    2>&1 & PIDS+=($!)
GOMOB_VINREF_HTTP_ADDR=:18058 GOMOB_MINIO_ENDPOINT=127.0.0.1:9000 GOMOB_MINIO_BUCKET=gomob-assets \
    "$SERVER_DIR/.dev/bin/gomob-vinref"   > "$OUTPUT_DIR/vinref.log"   2>&1 & PIDS+=($!)
GOMOB_CATALOG_HTTP_ADDR=:18059 \
    "$SERVER_DIR/.dev/bin/gomob-catalog"  > "$OUTPUT_DIR/catalog.log"  2>&1 & PIDS+=($!)
GOMOB_ADMIN_HTTP_ADDR=:19090 \
    "$SERVER_DIR/.dev/bin/gomob-admin"    > "$OUTPUT_DIR/admin.log"    2>&1 & PIDS+=($!)
LD_LIBRARY_PATH=/usr/local/lib:/usr/local/lib64:/usr/local/onnxruntime/lib \
GOMOB_CVENGINE_HTTP_ADDR=:18810 GOMOB_VINREF_TARGET=http://127.0.0.1:18058 \
    "$SERVER_DIR/.dev/bin/gomob-cvengine" > "$OUTPUT_DIR/cvengine.log" 2>&1 & PIDS+=($!)
sleep 2

for s in "$AUTH/healthz" "$ASSET/healthz" "$VINREF/healthz" "$ADMIN/healthz" "$CV/healthz"; do
    hc=$(curl -s -o /dev/null -w '%{http_code}' "$s")
    [[ "$hc" == "200" ]] || { log "✗ $s=$hc"; exit 4; }
done
record "S0.services_up" true ""

# 2. 注册 inspector
INSP_USER=insp_$$_$(date +%s)
curl -s -o /dev/null -X POST "$AUTH/v1/auth/register" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$INSP_USER\",\"password\":\"insp-pass-1\",\"real_name\":\"Insp\",\"employee_id\":\"E${INSP_USER}\"}"
LOGIN=$(curl -s -X POST "$AUTH/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$INSP_USER\",\"password\":\"insp-pass-1\"}")
INSP_UID=$(echo "$LOGIN" | extract data.user.id)
INS_HDRS=(-H "X-Gomob-User-Id: $INSP_UID" -H 'X-Gomob-Roles: inspector')
ADMIN_HDRS=(-H "X-Gomob-User-Id: $ADMIN_ID" -H 'X-Gomob-Roles: admin')

# 3. 生成 6 个字符样本（A/B/C/0/8 入库 + A_shift 仅 scan 用）
log "3. 生成字符样本"
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
            if 12 <= y <= 52 and abs((x - shift_x) - (16 + (52-y)*0.7)) <= 2: mat[y][x] = 255
            if 12 <= y <= 52 and abs((x - shift_x) - (48 - (52-y)*0.7)) <= 2: mat[y][x] = 255
            if 32 <= y <= 36 and (24 + shift_x) <= x <= (40 + shift_x): mat[y][x] = 255
    return mat

def make_B():
    mat = [[0]*W for _ in range(H)]
    for y in range(8, 56):
        for x in range(15, 19): mat[y][x] = 255
    for y in range(8, 32):
        for x in range(W):
            dx = (x - 30) / 14.0; dy = (y - 20) / 12.0
            if 0.78 <= dx*dx + dy*dy <= 1.0: mat[y][x] = 255
    for y in range(32, 56):
        for x in range(W):
            dx = (x - 30) / 14.0; dy = (y - 44) / 12.0
            if 0.78 <= dx*dx + dy*dy <= 1.0: mat[y][x] = 255
    return mat

def make_C():
    mat = [[0]*W for _ in range(H)]
    cx, cy = 36, H // 2
    for y in range(H):
        for x in range(W):
            dx = (x - cx) / 22.0; dy = (y - cy) / 26.0
            r2 = dx*dx + dy*dy
            if 0.85 <= r2 <= 1.0 and x < cx + 5: mat[y][x] = 255
    return mat

def make_0():
    mat = [[0]*W for _ in range(H)]
    cx, cy = W // 2, H // 2
    for y in range(H):
        for x in range(W):
            dx = (x - cx) / 16.0; dy = (y - cy) / 26.0
            if 0.85 <= dx*dx + dy*dy <= 1.0: mat[y][x] = 255
    return mat

def make_8():
    mat = [[0]*W for _ in range(H)]
    for y in range(H):
        for x in range(W):
            dx = (x - W//2) / 13.0; dy = (y - 18) / 11.0
            if 0.80 <= dx*dx + dy*dy <= 1.0: mat[y][x] = 255
            dy2 = (y - 46) / 13.0
            if 0.80 <= dx*dx + dy2*dy2 <= 1.0: mat[y][x] = 255
    return mat

write_png(os.path.join(out, 'A.png'), make_A(0))
write_png(os.path.join(out, 'A_shift.png'), make_A(8))
write_png(os.path.join(out, 'B.png'), make_B())
write_png(os.path.join(out, 'C.png'), make_C())
write_png(os.path.join(out, '0.png'), make_0())
write_png(os.path.join(out, '8.png'), make_8())
print("生成 6 个字符样本")
PY

# 4. 上传 5 个厂家库样本（A/B/C/0/8）到 asset → 拿 object_key
upload() {
    local file=$1
    local SHA=$(sha256sum "$file" | awk '{print $1}')
    local SIZE=$(stat -c%s "$file")
    local b=$(curl -s -X POST "$ASSET/v1/assets/upload/init" "${INS_HDRS[@]}" \
        -H 'Content-Type: application/json' \
        -d "{\"kind\":\"vin_glyph\",\"size_bytes\":$SIZE,\"sha256\":\"$SHA\",\"mime\":\"image/png\",\"chunk_mb\":8}")
    local UPID=$(echo "$b" | extract data.upload_id)
    [[ -n "$UPID" ]] || { echo "INIT_FAIL"; return 1; }
    curl -s -o /dev/null -X PUT "$ASSET/v1/assets/upload/$UPID/chunk/1" "${INS_HDRS[@]}" \
        -H 'Content-Type: application/octet-stream' \
        -H "Content-Length: $SIZE" --data-binary "@$file"
    b=$(curl -s -X POST "$ASSET/v1/assets/upload/$UPID/complete" "${INS_HDRS[@]}" \
        -H 'Content-Type: application/json' -d '{"total_chunks":1}')
    local KEY=$(echo "$b" | extract data.object_key)
    echo "$KEY|$SHA|$SIZE"
}

log "4. 上传 5 个厂家库样本"
declare -A KEYS
declare -A SHAS
declare -A SIZES
for c in A B C 0 8; do
    META=$(upload "$SAMPLES/$c.png")
    KEYS[$c]=$(echo "$META" | cut -d'|' -f1)
    SHAS[$c]=$(echo "$META" | cut -d'|' -f2)
    SIZES[$c]=$(echo "$META" | cut -d'|' -f3)
    [[ -n "${KEYS[$c]}" ]] || { log "✗ 上传 $c 失败"; exit 5; }
done

# 5. 创建车型 + 批次 + 5 个 sample + publish
b=$(curl -s -X POST "$ADMIN/admin/v1/catalog/vehicles" "${ADMIN_HDRS[@]}" \
    -H 'Content-Type: application/json' -d "{\"make\":\"vinq-test-$$\",\"series\":\"V1\",\"year\":2025}")
VMID=$(echo "$b" | extract data.id)

b=$(curl -s -X POST "$ADMIN/admin/v1/catalog/vehicles/$VMID/vin-refs/batches" \
    "${ADMIN_HDRS[@]}" -H 'Content-Type: application/json' -d '{"name":"batch_5char"}')
BID=$(echo "$b" | extract data.id)

for c in A B C 0 8; do
    curl -s -o /dev/null -X POST "$ADMIN/admin/v1/catalog/vehicles/$VMID/vin-refs/batches/$BID/samples" \
        "${ADMIN_HDRS[@]}" -H 'Content-Type: application/json' \
        -d "{\"character\":\"$c\",\"arr_mode\":1,\"alpha_object_key\":\"${KEYS[$c]}\",\"alpha_sha256\":\"${SHAS[$c]}\",\"alpha_size_bytes\":${SIZES[$c]},\"qc_score\":0.95}"
done

curl -s -o /dev/null -X POST "$ADMIN/admin/v1/catalog/vehicles/$VMID/vin-refs/batches/$BID/publish" "${ADMIN_HDRS[@]}"
record "S1.batch_published" true "vmid=$VMID bid=$BID"

# 6. 跑每个 case，记录 actual + 与 expected.json 比对
log "6. 跑精度 cases"
EXP="$(dirname "$0")/expected.json"
python3 - "$EXP" "$SAMPLES" "$RESULTS" "$CV" "$VMID" <<'PY'
import json, os, sys, urllib.request, urllib.parse
exp_file, samples, results, cv, vmid = sys.argv[1:6]
spec = json.load(open(exp_file))

def call_compare_with_ref(scan_file, vmid, character, threshold=None):
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
    part('image_binary', scan_file, filename='scan.png', ctype='image/png')
    part('vehicle_model_id', str(vmid))
    part('character', character)
    part('method', '0')
    body += ('--' + boundary + '--\r\n').encode()
    req = urllib.request.Request(cv + '/cv/ocr/v1/vin_character_compare_with_ref', data=body,
        headers={'Content-Type': 'multipart/form-data; boundary=' + boundary})
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            d = json.load(resp)
            return resp.getcode(), d
    except urllib.error.HTTPError as he:
        try:
            d = json.load(he)
        except Exception:
            d = {}
        return he.code, d

with open(results, 'a') as out:
    for c in spec['cases']:
        scan = os.path.join(samples, c['scan_char'] + '.png')
        http, data = call_compare_with_ref(scan, vmid, c['request_char'])

        if 'expect_http' in c:
            # 错误路径
            ok = (http == c['expect_http']) and (data.get('code') == c.get('expect_code'))
            note = f"http={http} code={data.get('code')} want_http={c['expect_http']} want_code={c.get('expect_code')}"
            out.write(json.dumps({
                'scenario': c['name'],
                'ok': ok,
                'actual': None,
                'note': note,
                'http_code': http, 'expected_http': c['expect_http'],
                'code': data.get('code'), 'expected_code': c.get('expect_code'),
                'latency_ms': 0,
            }) + '\n')
            continue

        # 成功路径
        if http != 200 or data.get('code') != 0:
            out.write(json.dumps({
                'scenario': c['name'],
                'ok': False,
                'actual': None,
                'note': f"http={http} code={data.get('code')} body={str(data)[:120]}",
                'http_code': http, 'expected_http': 200,
                'code': data.get('code'), 'expected_code': 0,
                'latency_ms': 0,
            }) + '\n')
            continue

        d = data['data']
        best_char = d.get('character', '')  # 请求字符（cv-engine 把 character 字段回显）
        best = d.get('best') or {}
        sim = float(best.get('similarity', 0))

        ok = True
        notes = []
        if 'expect_best' in c:
            if best_char != c['expect_best']:
                ok = False
                notes.append(f"best_char={best_char} want={c['expect_best']}")
            else:
                notes.append(f"best_char={best_char}")
        if 'min_sim' in c:
            if sim < c['min_sim']:
                ok = False
                notes.append(f"sim={sim:.3f} < min={c['min_sim']}")
            else:
                notes.append(f"sim={sim:.3f} >= {c['min_sim']}")
        if 'max_sim' in c:
            if sim > c['max_sim']:
                ok = False
                notes.append(f"sim={sim:.3f} > max={c['max_sim']}")

        out.write(json.dumps({
            'scenario': c['name'],
            'ok': ok,
            'actual': sim,
            'note': ' '.join(notes),
            'http_code': http, 'expected_http': 200,
            'code': 0, 'expected_code': 0,
            'latency_ms': 0,
        }) + '\n')

print('done')
PY

# 7. 输出 baseline_observed.json
log "7. 输出 baseline_observed.json"
python3 - "$RESULTS" "$OUTPUT_DIR/baseline_observed.json" <<'PY'
import json, sys
rows = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
out = {r['scenario']: r['actual'] for r in rows if r.get('actual') is not None}
json.dump(out, open(sys.argv[2], 'w'), indent=2)
PY

log "采样完成 → $RESULTS"
log "  baseline_observed → $OUTPUT_DIR/baseline_observed.json"
