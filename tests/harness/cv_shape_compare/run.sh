#!/bin/bash
# cv_shape_compare/run.sh — M-S9.x cv-engine shape_compare 端到端
#
# 验证 POST /cv/v1/shape_compare：
#   - cv-engine 拉 shape-ref active 元数据 → 与 scan 元数据比对 → score + verdict
#   - 完美匹配 → verdict=pass + score≈1
#   - 部分缺失（无 bbox）仍能给 score
#   - bbox 半重叠 → verdict=warning
#   - 大幅不匹配 → verdict=fail
#   - 该车型无 active shape → 40701
#   - 缺 vmid / 错 json → 10001

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVER_DIR="$PROJ_DIR/server"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/cv_shape_compare}"
mkdir -p "$OUTPUT_DIR"
RESULTS="$OUTPUT_DIR/results.jsonl"
: > "$RESULTS"

ADMIN=http://127.0.0.1:19090
ASSET=http://127.0.0.1:18083
SHAPEREF=http://127.0.0.1:18056
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
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-shaperef ./cmd/shaperef) || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-catalog  ./cmd/catalog)  || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-admin    ./cmd/admin)    || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-cvengine ./cmd/cvengine) || exit 3

pkill -9 -f "gomob-cvengine\|gomob-shaperef\|gomob-asset\|gomob-auth\|gomob-admin\|gomob-catalog" 2>/dev/null
sleep 2

PIDS=()
trap 'for p in "${PIDS[@]:-}"; do kill $p 2>/dev/null || true; done; wait 2>/dev/null || true' EXIT

log "1. 启动服务"
GOMOB_AUTH_HTTP_ADDR=:18082 GOMOB_AUTH_DEV_AUTOACTIVATE=true \
    "$SERVER_DIR/.dev/bin/gomob-auth"     > "$OUTPUT_DIR/auth.log"     2>&1 & PIDS+=($!)
GOMOB_ASSET_HTTP_ADDR=:18083 GOMOB_REDIS_ADDR=127.0.0.1:6379 \
GOMOB_MINIO_ENDPOINT=127.0.0.1:9000 GOMOB_MINIO_BUCKET=gomob-assets \
    "$SERVER_DIR/.dev/bin/gomob-asset"    > "$OUTPUT_DIR/asset.log"    2>&1 & PIDS+=($!)
GOMOB_SHAPEREF_HTTP_ADDR=:18056 GOMOB_MINIO_ENDPOINT=127.0.0.1:9000 GOMOB_MINIO_BUCKET=gomob-assets \
    "$SERVER_DIR/.dev/bin/gomob-shaperef" > "$OUTPUT_DIR/shaperef.log" 2>&1 & PIDS+=($!)
GOMOB_CATALOG_HTTP_ADDR=:18059 \
    "$SERVER_DIR/.dev/bin/gomob-catalog"  > "$OUTPUT_DIR/catalog.log"  2>&1 & PIDS+=($!)
GOMOB_ADMIN_HTTP_ADDR=:19090 \
    "$SERVER_DIR/.dev/bin/gomob-admin"    > "$OUTPUT_DIR/admin.log"    2>&1 & PIDS+=($!)
LD_LIBRARY_PATH=/usr/local/lib:/usr/local/lib64:/usr/local/onnxruntime/lib \
GOMOB_CVENGINE_HTTP_ADDR=:18810 \
GOMOB_SHAPEREF_TARGET=http://127.0.0.1:18056 \
    "$SERVER_DIR/.dev/bin/gomob-cvengine" > "$OUTPUT_DIR/cvengine.log" 2>&1 & PIDS+=($!)
sleep 2

for s in "$AUTH/healthz" "$ASSET/healthz" "$SHAPEREF/healthz" "$ADMIN/healthz" "$CV/healthz"; do
    hc=$(curl -s -o /dev/null -w '%{http_code}' "$s")
    [[ "$hc" == "200" ]] || { log "✗ $s=$hc"; cat "$OUTPUT_DIR/cvengine.log" | tail -10; exit 4; }
done
record "S0.all_services_up" true 200 200 "" "" 0 "5 服务"

# 2. 注册 inspector + 创建 vehicle_model + shape v1（active）
INSP_USER=insp_$$_$(date +%s)
curl -s -o /dev/null -X POST "$AUTH/v1/auth/register" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$INSP_USER\",\"password\":\"insp-pass-1\",\"real_name\":\"Insp\",\"employee_id\":\"E${INSP_USER}\"}"
LOGIN=$(curl -s -X POST "$AUTH/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$INSP_USER\",\"password\":\"insp-pass-1\"}")
INSP_UID=$(echo "$LOGIN" | extract data.user.id)
INS_HDRS=(-H "X-Gomob-User-Id: $INSP_UID" -H 'X-Gomob-Roles: inspector')
ADMIN_HDRS=(-H "X-Gomob-User-Id: $ADMIN_ID" -H 'X-Gomob-Roles: admin')

# 上传一个迷你 mesh（任意字节，元数据手填）
MESH=$(mktemp /tmp/mesh.glb.XXXX)
dd if=/dev/urandom of="$MESH" bs=1024 count=4 2>/dev/null
SHA=$(sha256sum "$MESH" | awk '{print $1}')
SIZE=$(stat -c%s "$MESH")

b=$(curl -s -X POST "$ASSET/v1/assets/upload/init" "${INS_HDRS[@]}" \
    -H 'Content-Type: application/json' \
    -d "{\"kind\":\"scan3d\",\"size_bytes\":$SIZE,\"sha256\":\"$SHA\",\"mime\":\"model/gltf-binary\",\"chunk_mb\":8}")
UPID=$(echo "$b" | extract data.upload_id)
curl -s -o /dev/null -X PUT "$ASSET/v1/assets/upload/$UPID/chunk/1" "${INS_HDRS[@]}" \
    -H 'Content-Type: application/octet-stream' -H "Content-Length: $SIZE" --data-binary "@$MESH"
b=$(curl -s -X POST "$ASSET/v1/assets/upload/$UPID/complete" "${INS_HDRS[@]}" \
    -H 'Content-Type: application/json' -d '{"total_chunks":1}')
KEY=$(echo "$b" | extract data.object_key)
[[ -n "$KEY" ]] || { log "✗ 上传失败"; exit 5; }

b=$(curl -s -X POST "$ADMIN/admin/v1/catalog/vehicles" "${ADMIN_HDRS[@]}" \
    -H 'Content-Type: application/json' -d "{\"make\":\"shape-cmp-$$\",\"series\":\"V1\",\"year\":2025}")
VMID=$(echo "$b" | extract data.id)

# 创建 shape v1 + publish → 这个就是后面 cv-engine 拉的 active ref
b=$(curl -s -X POST "$ADMIN/admin/v1/catalog/vehicles/$VMID/shapes" "${ADMIN_HDRS[@]}" \
    -H 'Content-Type: application/json' \
    -d "{\"version_name\":\"v1.0\",\"description\":\"\",\"source\":\"factory_cad\",\"mesh_object_key\":\"$KEY\",\"mesh_sha256\":\"$SHA\",\"mesh_size_bytes\":$SIZE,\"mesh_format\":\"glb\",\"triangle_count\":1000,\"point_count\":600,\"bbox\":{\"min_x\":0,\"min_y\":0,\"min_z\":0,\"max_x\":1.0,\"max_y\":1.0,\"max_z\":1.0},\"coverage\":0.85,\"qc_score\":0.90}")
SID=$(echo "$b" | extract data.id)
[[ -n "$SID" ]] || { log "✗ 缺 shape_id"; exit 5; }
curl -s -o /dev/null -X POST "$ADMIN/admin/v1/catalog/vehicles/$VMID/shapes/$SID/publish" "${ADMIN_HDRS[@]}"
record "S1.shape_active_seeded" true 200 200 "" "" 0 "vmid=$VMID sid=$SID"

# 3. 完美匹配 scan → verdict=pass
PERFECT_BODY=$(cat <<EOF
{
  "vehicle_model_id": "$VMID",
  "scan": {
    "triangle_count": 1000,
    "point_count": 600,
    "bbox": {"min_x":0,"min_y":0,"min_z":0,"max_x":1.0,"max_y":1.0,"max_z":1.0},
    "coverage": 0.85,
    "qc_score": 0.90
  }
}
EOF
)
b=$(step "S2.perfect_match_200" 200 0 -X POST "$CV/cv/v1/shape_compare" \
    -H 'Content-Type: application/json' \
    -d "$PERFECT_BODY")
echo "$b" > "$OUTPUT_DIR/s2_perfect.json"
VERDICT=$(echo "$b" | extract data.verdict)
SCORE=$(echo "$b" | python3 -c 'import sys,json;print(round(json.load(sys.stdin)["data"]["metadata_quality_score"],3))')
IOU=$(echo "$b" | python3 -c 'import sys,json;print(round(json.load(sys.stdin)["data"]["metrics"]["bbox_iou"],3))')
[[ "$VERDICT" == "pass" ]] && S2OK=true || S2OK=false
record "S3.perfect_verdict_pass" "$S2OK" 0 0 "" "" 0 "verdict=$VERDICT score=$SCORE iou=$IOU"

if [[ "$IOU" == "1" || "$IOU" == "1.0" ]]; then S3OK=true; else S3OK=false; fi
record "S4.perfect_iou_one" "$S3OK" 0 0 "" "" 0 "iou=$IOU"

# 4. bbox 半偏移 → bbox_iou 低 → 应 warning
SHIFTED_BODY=$(cat <<EOF
{
  "vehicle_model_id": "$VMID",
  "scan": {
    "triangle_count": 1000,
    "point_count": 600,
    "bbox": {"min_x":0.7,"min_y":0,"min_z":0,"max_x":1.7,"max_y":1.0,"max_z":1.0},
    "coverage": 0.85,
    "qc_score": 0.90
  }
}
EOF
)
b=$(step "S5.shifted_bbox_200" 200 0 -X POST "$CV/cv/v1/shape_compare" \
    -H 'Content-Type: application/json' -d "$SHIFTED_BODY")
VERDICT2=$(echo "$b" | extract data.verdict)
IOU2=$(echo "$b" | python3 -c 'import sys,json;print(round(json.load(sys.stdin)["data"]["metrics"]["bbox_iou"],3))')
[[ "$VERDICT2" == "warning" ]] && S5OK=true || S5OK=false
record "S6.shifted_verdict_warning" "$S5OK" 0 0 "" "" 0 "verdict=$VERDICT2 iou=$IOU2"

# 5. 大幅不匹配 → verdict=fail
BAD_BODY=$(cat <<EOF
{
  "vehicle_model_id": "$VMID",
  "scan": {
    "triangle_count": 200,
    "point_count": 100,
    "bbox": {"min_x":3,"min_y":3,"min_z":3,"max_x":3.3,"max_y":3.3,"max_z":3.3},
    "coverage": 0.30,
    "qc_score": 0.40
  }
}
EOF
)
b=$(step "S7.bad_match_200" 200 0 -X POST "$CV/cv/v1/shape_compare" \
    -H 'Content-Type: application/json' -d "$BAD_BODY")
VERDICT3=$(echo "$b" | extract data.verdict)
[[ "$VERDICT3" == "fail" ]] && S7OK=true || S7OK=false
record "S8.bad_verdict_fail" "$S7OK" 0 0 "" "" 0 "verdict=$VERDICT3"

# 6. 缺 bbox → 应 reasons 含 bbox_missing；其它字段完美 → 仍可能 pass
NO_BBOX=$(cat <<EOF
{
  "vehicle_model_id": "$VMID",
  "scan": {
    "triangle_count": 1000,
    "point_count": 600,
    "coverage": 0.85,
    "qc_score": 0.90
  }
}
EOF
)
b=$(step "S9.no_bbox_200" 200 0 -X POST "$CV/cv/v1/shape_compare" \
    -H 'Content-Type: application/json' -d "$NO_BBOX")
HAS_REASON=$(echo "$b" | python3 -c '
import sys,json
d=json.load(sys.stdin)["data"]
print("true" if "bbox_missing" in (d.get("reasons") or []) else "false")')
record "S10.no_bbox_reason_present" "$HAS_REASON" 0 0 "" "" 0 ""
BBOX_MISSING_FLAG=$(echo "$b" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["metrics"].get("bbox_missing",False))')
[[ "$BBOX_MISSING_FLAG" == "True" ]] && S10b=true || S10b=false
record "S11.no_bbox_metric_flag" "$S10b" 0 0 "" "" 0 "bbox_missing=$BBOX_MISSING_FLAG"

# 7. 自定义阈值
b=$(step "S12.custom_threshold" 200 0 -X POST "$CV/cv/v1/shape_compare" \
    -H 'Content-Type: application/json' \
    -d "{\"vehicle_model_id\":\"$VMID\",\"scan\":{\"triangle_count\":1000,\"point_count\":600,\"bbox\":{\"min_x\":0,\"min_y\":0,\"min_z\":0,\"max_x\":1,\"max_y\":1,\"max_z\":1},\"coverage\":0.85,\"qc_score\":0.90},\"pass_threshold\":0.99,\"warn_threshold\":0.50}")
PASS_TH=$(echo "$b" | extract data.pass_threshold)
WARN_TH=$(echo "$b" | extract data.warn_threshold)
[[ "$PASS_TH" == "0.99" && "$WARN_TH" == "0.5" ]] && S12=true || S12=false
record "S12b.custom_threshold_reflected" "$S12" 0 0 "" "" 0 "pass=$PASS_TH warn=$WARN_TH"

# 8. 错误：vmid=999999（不存在 active shape）→ 40701
step "S13.no_active_40701" 404 40701 -X POST "$CV/cv/v1/shape_compare" \
    -H 'Content-Type: application/json' \
    -d '{"vehicle_model_id":"999999","scan":{"triangle_count":100}}' > /dev/null

# 9. 错误：缺 vmid → 10001
step "S14.missing_vmid_10001" 400 10001 -X POST "$CV/cv/v1/shape_compare" \
    -H 'Content-Type: application/json' \
    -d '{"scan":{"triangle_count":100}}' > /dev/null

# 10. 错误：vmid 非法 → 10001
step "S15.invalid_vmid_10001" 400 10001 -X POST "$CV/cv/v1/shape_compare" \
    -H 'Content-Type: application/json' \
    -d '{"vehicle_model_id":"-5","scan":{}}' > /dev/null

# 11. 错误：bad json → 10001
step "S16.bad_json_10001" 400 10001 -X POST "$CV/cv/v1/shape_compare" \
    -H 'Content-Type: application/json' \
    -d '{ this is not valid json' > /dev/null

# 12. ref 数据完整性：响应里 ref.triangle_count / coverage 应是上面 seed 的值
b=$(curl -s -X POST "$CV/cv/v1/shape_compare" \
    -H 'Content-Type: application/json' \
    -d "$PERFECT_BODY")
REF_TRI=$(echo "$b" | extract data.ref.triangle_count)
REF_COVERAGE=$(echo "$b" | extract data.ref.coverage)
[[ "$REF_TRI" == "1000" ]] && S17a=true || S17a=false
record "S17.ref_meta_echoed" "$S17a" 0 0 "" "" 0 "tri=$REF_TRI coverage=$REF_COVERAGE"

rm -f "$MESH"
log "采样完成 → $RESULTS"
