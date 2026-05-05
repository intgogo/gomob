#!/bin/bash
# cv_vinref_compare/run.sh — M-S10 Phase 2.2 真业务端到端：
#
#   App ──▶ cv-engine /cv/ocr/v1/vin_character_compare_with_ref
#                       │
#                       ├─▶ vinref /v1/catalog/vehicles/{vmid}/vin-refs/active/samples?character=A
#                       │       └─▶ 返回 N 条 sample（含签名 alpha_url）
#                       ├─▶ 拉每个 alpha_url（MinIO 直链）→ 字节
#                       └─▶ ProcVinCharacterCompare（OpenCV IoU 真算）
#
# 端到端意义：
#   1. 模拟厂家送的"标准 A 字符"上传 → vin-ref 入库 → publish
#   2. 模拟扫描端拍到的 A 字符（同图）→ cv-engine 调 vin-ref 拉 active 样本 → IoU=1.0
#   3. 扫描端拍到 O → cv-engine 比对 ref 库的 A 样本 → IoU 显著低
#
# 不是 stub：所有路径真实穿过 PG / MinIO / OpenCV / 网络。失败 = 真集成跑歪了。

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVER_DIR="$PROJ_DIR/server"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/cv_vinref_compare}"
mkdir -p "$OUTPUT_DIR"
RESULTS="$OUTPUT_DIR/results.jsonl"
: > "$RESULTS"

ADMIN=http://127.0.0.1:19090
VINREF=http://127.0.0.1:18058
ASSET=http://127.0.0.1:18083
CV=http://127.0.0.1:18810
AUTH=http://127.0.0.1:18082

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
# 0. 前置：清表 + 起服务 + 注册 inspector 拿 token
# ============================================================================
log "0. 前置"
podman ps --format '{{.Names}}' | grep -qx gomob-pg    || { log "缺 gomob-pg";    exit 2; }
podman ps --format '{{.Names}}' | grep -qx gomob-redis || { log "缺 gomob-redis"; exit 2; }
podman ps --format '{{.Names}}' | grep -qx gomob-minio || { log "缺 gomob-minio"; exit 2; }

podman exec -i gomob-pg psql -U gomob -d gomob -v ON_ERROR_STOP=1 > /dev/null <<'SQL'
DELETE FROM audit_log;
DELETE FROM vin_glyph_samples;
DELETE FROM vin_glyph_batches;
DELETE FROM vehicle_shapes;
DELETE FROM upload_sessions;
DELETE FROM inspection_assets;
DELETE FROM reviews;
DELETE FROM inspections;
DELETE FROM vehicles;
DELETE FROM vehicle_models;
DELETE FROM messages;
DELETE FROM conversation_members;
DELETE FROM conversations;
DELETE FROM call_logs;
DELETE FROM pending_calls;
DELETE FROM users;
DELETE FROM stations;
INSERT INTO stations(name, region) VALUES('测试检测站','test');
INSERT INTO users(username, real_name, employee_id, password_hash, role, status, station_id)
VALUES('master','超管','SUPER',
  '$2a$10$placeholderHash..............................',
  'admin','active',
  (SELECT id FROM stations ORDER BY id DESC LIMIT 1));
SQL
podman exec gomob-redis redis-cli FLUSHDB > /dev/null
ADMIN_ID=$(podman exec gomob-pg psql -U gomob -d gomob -tAc "SELECT id FROM users WHERE username='master'")
[[ -n "$ADMIN_ID" ]] || { log "✗ master id 拿不到"; exit 3; }

log "1. 编译 6 服务"
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-auth     ./cmd/auth)     || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-asset    ./cmd/asset)    || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-vinref   ./cmd/vinref)   || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-admin    ./cmd/admin)    || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-cvengine ./cmd/cvengine) || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-catalog  ./cmd/catalog)  || exit 3

# 清掉占用 18810 的旧 cvengine
pkill -9 -f gomob-cvengine 2>/dev/null
sleep 1

PIDS=()
trap 'for p in "${PIDS[@]:-}"; do kill -9 $p 2>/dev/null || true; done; wait 2>/dev/null || true' EXIT

log "2. 启动服务"
GOMOB_AUTH_HTTP_ADDR=:18082 GOMOB_AUTH_DEV_AUTOACTIVATE=true \
    "$SERVER_DIR/.dev/bin/gomob-auth"     > "$OUTPUT_DIR/auth.log"     2>&1 &
PIDS+=($!)
GOMOB_ASSET_HTTP_ADDR=:18083 GOMOB_REDIS_ADDR=127.0.0.1:6379 \
GOMOB_MINIO_ENDPOINT=127.0.0.1:9000 GOMOB_MINIO_BUCKET=gomob-assets \
    "$SERVER_DIR/.dev/bin/gomob-asset"    > "$OUTPUT_DIR/asset.log"    2>&1 &
PIDS+=($!)
GOMOB_VINREF_HTTP_ADDR=:18058 GOMOB_MINIO_ENDPOINT=127.0.0.1:9000 GOMOB_MINIO_BUCKET=gomob-assets \
    "$SERVER_DIR/.dev/bin/gomob-vinref"   > "$OUTPUT_DIR/vinref.log"   2>&1 &
PIDS+=($!)
GOMOB_CATALOG_HTTP_ADDR=:18059 \
    "$SERVER_DIR/.dev/bin/gomob-catalog"  > "$OUTPUT_DIR/catalog.log"  2>&1 &
PIDS+=($!)
GOMOB_ADMIN_HTTP_ADDR=:19090 \
    "$SERVER_DIR/.dev/bin/gomob-admin"    > "$OUTPUT_DIR/admin.log"    2>&1 &
PIDS+=($!)
LD_LIBRARY_PATH=/usr/local/lib:/usr/local/lib64:/usr/local/onnxruntime/lib \
GOMOB_CVENGINE_HTTP_ADDR=:18810 GOMOB_VINREF_TARGET=http://127.0.0.1:18058 \
    "$SERVER_DIR/.dev/bin/gomob-cvengine" > "$OUTPUT_DIR/cvengine.log" 2>&1 &
PIDS+=($!)
sleep 1

curl -s -o /dev/null -w "vinref=%{http_code} " "$VINREF/healthz"
curl -s -o /dev/null -w "asset=%{http_code} "  "$ASSET/healthz"
curl -s -o /dev/null -w "cv=%{http_code} "     "$CV/healthz"
curl -s -o /dev/null -w "admin=%{http_code}\n" "$ADMIN/healthz"

ADMIN_HDRS=(-H "X-Gomob-User-Id: $ADMIN_ID" -H 'X-Gomob-Roles: admin')

# 注册 inspector 拿 token（asset 上传需要登录）
INSP_USER=insp_$$_$(date +%s)
curl -s -o /dev/null -X POST "$AUTH/v1/auth/register" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$INSP_USER\",\"password\":\"insp-pass-1\",\"real_name\":\"Inspector\",\"employee_id\":\"E${INSP_USER}\"}"
LOGIN=$(curl -s -X POST "$AUTH/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$INSP_USER\",\"password\":\"insp-pass-1\"}")
INSP_TOK=$(echo "$LOGIN" | extract data.access_token)
INSP_UID=$(echo "$LOGIN" | extract data.user.id)
[[ -n "$INSP_TOK" ]] || { log "✗ 无 inspector token"; exit 4; }
INS_HDRS=(-H "X-Gomob-User-Id: $INSP_UID" -H 'X-Gomob-Roles: inspector')

# ============================================================================
# 3. 准备测试图
# ============================================================================
log "3. 准备测试图（A 字符 + O 字符 + A 平移版）"
gen_png() {
    local out=$1 shape=$2 dx=${3:-0} dy=${4:-0}
    python3 - <<PY > "$out"
import struct, zlib, sys
W=H=64
def chr_bytes(shape, dx, dy):
    rows = []
    for y in range(H):
        row = bytearray(W)
        for x in range(W):
            xx, yy = x-dx, y-dy
            v = 0
            if shape == 'A':
                if 20 <= yy <= 50 and abs(xx - (16 + (50-yy)*0.7)) <= 2: v = 255
                if 20 <= yy <= 50 and abs(xx - (48 - (50-yy)*0.7)) <= 2: v = 255
                if 33 <= yy <= 36 and 24 <= xx <= 40: v = 255
            elif shape == 'O':
                d = ((xx-32)**2 + (yy-32)**2) ** 0.5
                if 14 <= d <= 18: v = 255
            row[x] = v
        rows.append(b'\x00' + bytes(row))
    return b''.join(rows)
raw = chr_bytes('$shape', $dx, $dy)
def chunk(t, d):
    return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d))
sig = b'\x89PNG\r\n\x1a\n'
ihdr = struct.pack('>IIBBBBB', W, H, 8, 0, 0, 0, 0)
idat = zlib.compress(raw)
sys.stdout.buffer.write(sig + chunk(b'IHDR', ihdr) + chunk(b'IDAT', idat) + chunk(b'IEND', b''))
PY
}
gen_png "$OUTPUT_DIR/factory_A.png" A
gen_png "$OUTPUT_DIR/scan_A.png"    A
gen_png "$OUTPUT_DIR/scan_A_shift.png" A 3 2
gen_png "$OUTPUT_DIR/scan_O.png"    O

# ============================================================================
# 4. 通过 asset 上传"厂家 A 模板"，拿 object_key
# ============================================================================
upload_alpha() {
    local file=$1
    local SHA=$(sha256sum "$file" | awk '{print $1}')
    local SIZE=$(stat -c%s "$file")
    local b=$(curl -s -X POST "$ASSET/v1/assets/upload/init" "${INS_HDRS[@]}" \
        -H 'Content-Type: application/json' \
        -d "{\"kind\":\"vin_glyph\",\"size_bytes\":$SIZE,\"sha256\":\"$SHA\",\"mime\":\"image/png\",\"chunk_mb\":8}")
    local UPID=$(echo "$b" | extract data.upload_id)
    [[ -n "$UPID" ]] || { echo "INIT_FAIL:$b" >&2; return 1; }
    curl -s -X PUT "$ASSET/v1/assets/upload/$UPID/chunk/1" "${INS_HDRS[@]}" \
        -H 'Content-Type: application/octet-stream' \
        -H "Content-Length: $SIZE" --data-binary "@$file" > /dev/null
    b=$(curl -s -X POST "$ASSET/v1/assets/upload/$UPID/complete" "${INS_HDRS[@]}" \
        -H 'Content-Type: application/json' -d '{"total_chunks":1}')
    local KEY=$(echo "$b" | extract data.object_key)
    [[ -n "$KEY" ]] || { echo "COMPLETE_FAIL:$b" >&2; return 1; }
    echo "$KEY|$SHA|$SIZE"
}

log "4. 上传厂家 A 模板"
META=$(upload_alpha "$OUTPUT_DIR/factory_A.png")
KEY=$(echo "$META" | cut -d'|' -f1)
SHA=$(echo "$META" | cut -d'|' -f2)
SIZE=$(echo "$META" | cut -d'|' -f3)
[[ -n "$KEY" ]] || { log "✗ 上传失败：$META"; exit 5; }
log "  factory_A.png 落 MinIO: $KEY"

# ============================================================================
# 5. 创建车型 + vin-ref 批次 + 写入 A 样本 + publish
# ============================================================================
log "5. 创建车型 + vin-ref 批次"
SUFFIX=$(date +%s)
b=$(step "S0a.create_vehicle_model" 200 0 -X POST "$ADMIN/admin/v1/catalog/vehicles" \
    "${ADMIN_HDRS[@]}" -H 'Content-Type: application/json' \
    -d "{\"make\":\"vinref-cv-$SUFFIX\",\"series\":\"V1\",\"year\":2024}")
VMID=$(echo "$b" | extract data.id)
[[ -n "$VMID" ]] || { log "✗ vmid 为空"; exit 5; }

b=$(step "S0b.create_batch" 200 0 -X POST \
    "$ADMIN/admin/v1/catalog/vehicles/$VMID/vin-refs/batches" \
    "${ADMIN_HDRS[@]}" -H 'Content-Type: application/json' \
    -d '{"name":"factory_2024Q1","description":"E2E 测试批次"}')
BID=$(echo "$b" | extract data.id)
[[ -n "$BID" ]] || { log "✗ bid 为空"; exit 5; }

b=$(step "S0c.create_sample_A" 200 0 -X POST \
    "$ADMIN/admin/v1/catalog/vehicles/$VMID/vin-refs/batches/$BID/samples" \
    "${ADMIN_HDRS[@]}" -H 'Content-Type: application/json' \
    -d "{\"character\":\"A\",\"arr_mode\":1,\"font_id\":\"*\",\"alpha_object_key\":\"$KEY\",\"alpha_sha256\":\"$SHA\",\"alpha_size_bytes\":$SIZE,\"qc_score\":0.95}")

b=$(step "S0d.publish_batch" 200 0 -X POST \
    "$ADMIN/admin/v1/catalog/vehicles/$VMID/vin-refs/batches/$BID/publish" \
    "${ADMIN_HDRS[@]}")

# ============================================================================
# 6. 跑 cv-engine 真业务端点
# ============================================================================
log "6. 跑 cv-engine 真业务端点"

# S1 完全相同：扫描 A vs 厂家 A → IoU=1.0
b=$(step "S1.compare_with_ref_identical" 200 0 -X POST "$CV/cv/ocr/v1/vin_character_compare_with_ref" \
    -F "image_binary=@$OUTPUT_DIR/scan_A.png" \
    -F "vehicle_model_id=$VMID" \
    -F "character=A" \
    -F "method=0")
SAMPLE_CNT=$(echo "$b" | extract data.sample_count)
BEST_SIM=$(echo "$b" | extract data.best.similarity)
BEST_VAL=$(echo "$b" | extract data.best.value)
GOT_BATCH=$(echo "$b" | extract data.batch_id)
S1_OK=$(python3 -c "
sim=float('$BEST_SIM' or 0)
print('true' if sim >= 0.95 else 'false')")
record "S1b.identical_iou_high" "$S1_OK" 0 0 "" "" 0 "sample_cnt=$SAMPLE_CNT best_sim=$BEST_SIM best_val=$BEST_VAL batch_id=$GOT_BATCH"

# S2 微平移：扫描 A_shift vs 厂家 A → IoU > 0.5 但 < 1.0
b=$(step "S2.compare_with_ref_shift" 200 0 -X POST "$CV/cv/ocr/v1/vin_character_compare_with_ref" \
    -F "image_binary=@$OUTPUT_DIR/scan_A_shift.png" \
    -F "vehicle_model_id=$VMID" \
    -F "character=A" \
    -F "method=0")
SHIFT_SIM=$(echo "$b" | extract data.best.similarity)
S2_OK=$(python3 -c "
sim=float('$SHIFT_SIM' or 0)
print('true' if 0.5 <= sim < 1.0 else 'false')")
record "S2b.shift_iou_mid" "$S2_OK" 0 0 "" "" 0 "best_sim=$SHIFT_SIM want 0.5..1.0"

# S3 不同字符：扫描 O vs 厂家 A → IoU 显著低 < 0.5
b=$(step "S3.compare_with_ref_diff" 200 0 -X POST "$CV/cv/ocr/v1/vin_character_compare_with_ref" \
    -F "image_binary=@$OUTPUT_DIR/scan_O.png" \
    -F "vehicle_model_id=$VMID" \
    -F "character=A" \
    -F "method=0")
DIFF_SIM=$(echo "$b" | extract data.best.similarity)
S3_OK=$(python3 -c "
sim=float('$DIFF_SIM' or 1)
print('true' if sim <= 0.5 else 'false')")
record "S3b.diff_iou_low" "$S3_OK" 0 0 "" "" 0 "best_sim=$DIFF_SIM want<=0.5"

# S4 严格排序
SORT_OK=$(python3 -c "
a=float('$BEST_SIM' or 0); b=float('$SHIFT_SIM' or 0); c=float('$DIFF_SIM' or 0)
print('true' if a > b and b > c else 'false')")
record "S4.sim_sort_identical_gt_shift_gt_diff" "$SORT_OK" 0 0 "" "" 0 "identical=$BEST_SIM shift=$SHIFT_SIM diff=$DIFF_SIM"

# S5 Chamfer 模式
b=$(step "S5.compare_with_ref_chamfer" 200 0 -X POST "$CV/cv/ocr/v1/vin_character_compare_with_ref" \
    -F "image_binary=@$OUTPUT_DIR/scan_A.png" \
    -F "vehicle_model_id=$VMID" \
    -F "character=A" \
    -F "method=1")
CH_VAL=$(echo "$b" | extract data.best.value)
CH_SIM=$(echo "$b" | extract data.best.similarity)
S5_OK=$(python3 -c "
v=float('$CH_VAL' or 999); s=float('$CH_SIM' or 0)
print('true' if v <= 0.5 and s >= 0.9 else 'false')")
record "S5b.chamfer_identical_low" "$S5_OK" 0 0 "" "" 0 "value=$CH_VAL sim=$CH_SIM"

# S6 阈值：sim 阈值 0.95，identical 应不 below_threshold
b=$(step "S6.compare_with_ref_threshold_pass" 200 0 -X POST "$CV/cv/ocr/v1/vin_character_compare_with_ref" \
    -F "image_binary=@$OUTPUT_DIR/scan_A.png" \
    -F "vehicle_model_id=$VMID" \
    -F "character=A" \
    -F "method=0" \
    -F "threshold=0.95")
BELOW=$(echo "$b" | extract data.below_threshold)
[[ "$BELOW" == "False" || "$BELOW" == "" ]] && S6_OK=true || S6_OK=false
record "S6b.threshold_pass_above" "$S6_OK" 0 0 "" "" 0 "below_threshold=$BELOW want=False"

# S7 阈值：sim 阈值 0.95，diff 应 below_threshold=true
b=$(step "S7.compare_with_ref_threshold_fail" 200 0 -X POST "$CV/cv/ocr/v1/vin_character_compare_with_ref" \
    -F "image_binary=@$OUTPUT_DIR/scan_O.png" \
    -F "vehicle_model_id=$VMID" \
    -F "character=A" \
    -F "method=0" \
    -F "threshold=0.95")
BELOW2=$(echo "$b" | extract data.below_threshold)
[[ "$BELOW2" == "True" ]] && S7_OK=true || S7_OK=false
record "S7b.threshold_below" "$S7_OK" 0 0 "" "" 0 "below_threshold=$BELOW2 want=True"

# S8 错误：未知 vehicle_model_id → 40701
step "S8.unknown_vmid_40701" 404 40701 -X POST "$CV/cv/ocr/v1/vin_character_compare_with_ref" \
    -F "image_binary=@$OUTPUT_DIR/scan_A.png" \
    -F "vehicle_model_id=99999999" \
    -F "character=A" \
    -F "method=0" > /dev/null

# S9 错误：未上库的字符 B → 40701
step "S9.no_sample_for_char_40701" 404 40701 -X POST "$CV/cv/ocr/v1/vin_character_compare_with_ref" \
    -F "image_binary=@$OUTPUT_DIR/scan_A.png" \
    -F "vehicle_model_id=$VMID" \
    -F "character=B" \
    -F "method=0" > /dev/null

# S10 错误：非法 character "I"（VIN 排除）→ 10001
step "S10.invalid_char_I_10001" 400 10001 -X POST "$CV/cv/ocr/v1/vin_character_compare_with_ref" \
    -F "image_binary=@$OUTPUT_DIR/scan_A.png" \
    -F "vehicle_model_id=$VMID" \
    -F "character=I" \
    -F "method=0" > /dev/null

# S11 错误：缺 image_binary → 10001
step "S11.missing_image_10001" 400 10001 -X POST "$CV/cv/ocr/v1/vin_character_compare_with_ref" \
    -F "vehicle_model_id=$VMID" \
    -F "character=A" \
    -F "method=0" > /dev/null

# S12 vinref 响应也得自带 alpha_url（cv-engine 走签名 URL 拉的前提）
b=$(step "S12.vinref_returns_alpha_url" 200 0 \
    "$VINREF/v1/catalog/vehicles/$VMID/vin-refs/active/samples?character=A")
URL_LEN=$(echo "$b" | python3 -c '
import sys,json
try:
    d=json.load(sys.stdin); url=d["data"]["items"][0].get("alpha_url","")
    print(len(url))
except Exception: print(0)')
[[ "$URL_LEN" -ge 100 ]] && S12_OK=true || S12_OK=false
record "S12b.alpha_url_present" "$S12_OK" 0 0 "" "" 0 "url_len=$URL_LEN"

log "采样完成 → $RESULTS"
