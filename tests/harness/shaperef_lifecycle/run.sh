#!/bin/bash
# shaperef_lifecycle/run.sh — M-S9 shape-ref 全链路 harness（参考库三件套：3D 外廓库）
#
# 端到端：asset 上传 mesh → shape-ref 注册元数据 → publish → App 拿签名 URL → 下载验 sha256
#
# 场景：
#   S1   缺鉴权 → 40102
#   S2   inspector → 40103
#   S3   admin 创建 vehicle_model
#   S4   admin 起 mesh upload session（kind=scan3d, 1 chunk 1MB 模拟）
#   S5   上传 + complete → 拿 mesh_object_key + sha256
#   S6   admin 创建 shape v1.0（draft）
#   S7   同名重复 → 40201
#   S8   invalid mesh_format "fbx" → 10002
#   S9   list shapes（admin 全状态）≥1
#   S10  publish v1.0 → status=published
#   S11  published 后 patch → 40401
#   S12  active 接口返回签名 URL（含 expire_at）
#   S13  curl 签名 URL → 200 + sha256 匹配
#   S14  asset 上传 v2 mesh + admin 创建 shape v2.0
#   S15  publish v2.0 → v1.0 自动 archived
#   S16  active 现在是 v2.0
#   S17  删 archived（v1.0）→ 40401
#   S18  创建 v3.0 draft → DELETE 成功
#   S19  App 路径：gateway → api BFF → shape-ref active → 200 + 签名 URL
#   S20  audit ≥ 5 条 shaperef.* 事件

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVER_DIR="$PROJ_DIR/server"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/shaperef_lifecycle}"
mkdir -p "$OUTPUT_DIR"
RESULTS="$OUTPUT_DIR/results.jsonl"
: > "$RESULTS"

ADMIN=http://127.0.0.1:19090
API=http://127.0.0.1:18080
GATEWAY=http://127.0.0.1:18808
AUTH=http://127.0.0.1:18082
SHAPEREF=http://127.0.0.1:18056
ASSET=http://127.0.0.1:18083

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
# 0. 前置：apply migration 0008 + 清表 + 起 6 个服务
# ============================================================================
log "0. 前置：应用 migration 0008 + 清表"
podman ps --format '{{.Names}}' | grep -qx gomob-pg    || { log "缺 gomob-pg";    exit 2; }
podman ps --format '{{.Names}}' | grep -qx gomob-redis || { log "缺 gomob-redis"; exit 2; }
podman ps --format '{{.Names}}' | grep -qx gomob-minio || { log "缺 gomob-minio"; exit 2; }

HAS_SHAPE=$(podman exec -i gomob-pg psql -U gomob -d gomob -tAc \
    "SELECT 1 FROM information_schema.tables WHERE table_name='vehicle_shapes'")
if [[ -z "$HAS_SHAPE" ]]; then
    log "  应用 migrations/0008_vehicle_shapes.up.sql"
    podman exec -i gomob-pg psql -U gomob -d gomob -v ON_ERROR_STOP=1 \
        < "$SERVER_DIR/migrations/0008_vehicle_shapes.up.sql" > /dev/null
fi

# 清表（FK 拓扑序）
podman exec -i gomob-pg psql -U gomob -d gomob -v ON_ERROR_STOP=1 > /dev/null <<'SQL'
DELETE FROM audit_log;
DELETE FROM vehicle_shapes;
DELETE FROM vin_glyph_samples;
DELETE FROM vin_glyph_batches;
DELETE FROM llm_call_logs;
DELETE FROM llm_templates;
DELETE FROM model_routes;
DELETE FROM models;
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
[[ -n "$ADMIN_ID" ]] || { log "✗ master user_id 拿不到"; exit 3; }
log "  ADMIN_ID=$ADMIN_ID"

# ============================================================================
# 1. 编译 + 启动
# ============================================================================
log "1. 编译 + 启动 7 服务"
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-auth     ./cmd/auth)     || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-gateway  ./cmd/gateway)  || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-api      ./cmd/api)      || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-asset    ./cmd/asset)    || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-catalog  ./cmd/catalog)  || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-shaperef ./cmd/shaperef) || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-admin    ./cmd/admin)    || exit 3

PIDS=()
trap 'for p in "${PIDS[@]:-}"; do kill $p 2>/dev/null || true; done; wait 2>/dev/null || true' EXIT

GOMOB_AUTH_HTTP_ADDR=:18082 GOMOB_AUTH_DEV_AUTOACTIVATE=true \
    "$SERVER_DIR/.dev/bin/gomob-auth"     > "$OUTPUT_DIR/auth.log"     2>&1 &
PIDS+=($!)
GOMOB_API_HTTP_ADDR=:18080 \
    "$SERVER_DIR/.dev/bin/gomob-api"      > "$OUTPUT_DIR/api.log"      2>&1 &
PIDS+=($!)
GOMOB_ASSET_HTTP_ADDR=:18083 GOMOB_REDIS_ADDR=127.0.0.1:6379 \
GOMOB_MINIO_ENDPOINT=127.0.0.1:9000 GOMOB_MINIO_BUCKET=gomob-assets \
    "$SERVER_DIR/.dev/bin/gomob-asset"    > "$OUTPUT_DIR/asset.log"    2>&1 &
PIDS+=($!)
GOMOB_CATALOG_HTTP_ADDR=:18059 \
    "$SERVER_DIR/.dev/bin/gomob-catalog"  > "$OUTPUT_DIR/catalog.log"  2>&1 &
PIDS+=($!)
GOMOB_SHAPEREF_HTTP_ADDR=:18056 GOMOB_MINIO_ENDPOINT=127.0.0.1:9000 GOMOB_MINIO_BUCKET=gomob-assets \
    "$SERVER_DIR/.dev/bin/gomob-shaperef" > "$OUTPUT_DIR/shaperef.log" 2>&1 &
PIDS+=($!)
GOMOB_ADMIN_HTTP_ADDR=:19090 \
    "$SERVER_DIR/.dev/bin/gomob-admin"    > "$OUTPUT_DIR/admin.log"    2>&1 &
PIDS+=($!)
GOMOB_GATEWAY_ADDR=:18808 GOMOB_REDIS_ADDR=127.0.0.1:6379 GOMOB_RATE_LIMIT=10000 \
    "$SERVER_DIR/.dev/bin/gomob-gateway"  > "$OUTPUT_DIR/gateway.log"  2>&1 &
PIDS+=($!)
sleep 1

hc=$(curl -s -o /dev/null -w '%{http_code}' "$ADMIN/healthz")
[[ "$hc" == "200" ]] || { log "admin 不通 $hc"; exit 4; }
hc=$(curl -s -o /dev/null -w '%{http_code}' "$SHAPEREF/healthz")
[[ "$hc" == "200" ]] || { log "shaperef 不通 $hc"; exit 4; }
hc=$(curl -s -o /dev/null -w '%{http_code}' "$ASSET/healthz")
[[ "$hc" == "200" ]] || { log "asset 不通 $hc"; exit 4; }

# 准备一个普通 inspector token 用于 asset 上传（asset 要求登录 user）
INSP_USER=insp_$$_$(date +%s)
curl -s -o /dev/null -X POST "$AUTH/v1/auth/register" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$INSP_USER\",\"password\":\"insp-pass-1\",\"real_name\":\"Inspector\",\"employee_id\":\"E${INSP_USER}\"}"
LOGIN=$(curl -s -X POST "$AUTH/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$INSP_USER\",\"password\":\"insp-pass-1\"}")
INSP_TOK=$(echo "$LOGIN" | extract data.access_token)
INSP_UID=$(echo "$LOGIN" | extract data.user.id)
[[ -n "$INSP_TOK" ]] || { log "✗ 拿不到 inspector token"; exit 5; }

# ============================================================================
# 2. 跑场景
# ============================================================================
log "2. 跑场景"
ADMIN_HDRS=(-H "X-Gomob-User-Id: $ADMIN_ID" -H 'X-Gomob-Roles: admin')
INS_HDRS=(-H "X-Gomob-User-Id: $INSP_UID" -H 'X-Gomob-Roles: inspector')

# S1 缺鉴权
step "S1.no_auth_40102" 401 40102 "$ADMIN/admin/v1/catalog/vehicles/1/shapes" > /dev/null

# S2 inspector
step "S2.inspector_403" 403 40103 \
    "$ADMIN/admin/v1/catalog/vehicles/1/shapes" "${INS_HDRS[@]}" > /dev/null

# S3 创建 vehicle_model
SUFFIX=$(date +%s)
b=$(step "S3.create_vehicle_model" 200 0 -X POST "$ADMIN/admin/v1/catalog/vehicles" \
    "${ADMIN_HDRS[@]}" -H 'Content-Type: application/json' \
    -d "{\"make\":\"shaperef-$SUFFIX\",\"series\":\"S1\",\"year\":2024}")
VMID=$(echo "$b" | extract data.id)
[[ -n "$VMID" ]] || { log "✗ vehicle_model id 为空"; exit 5; }

# 工具：上传一个 1MB mesh stub 到 asset，返回 object_key + sha256 + size
upload_mesh() {
    local label=$1
    local TMPFILE="$OUTPUT_DIR/mesh-$label.bin"
    dd if=/dev/urandom of=$TMPFILE bs=1M count=1 status=none
    local FILE_SHA=$(sha256sum $TMPFILE | awk '{print $1}')
    local FILE_SIZE=$(stat -c%s $TMPFILE)
    # init（无 inspection_id → orphan/scan3d/...，最小分片 5MB 但 asset 默认 chunk_mb=8，
    # 文件 1MB 仅 1 片，asset 的 multipart "最后一片可小于 5MB" 例外）
    local b=$(curl -s -X POST "$ASSET/v1/assets/upload/init" "${INS_HDRS[@]}" \
        -H 'Content-Type: application/json' \
        -d "{\"kind\":\"scan3d\",\"size_bytes\":$FILE_SIZE,\"sha256\":\"$FILE_SHA\",\"mime\":\"model/gltf-binary\",\"chunk_mb\":8}")
    local UPLOAD_ID=$(echo "$b" | extract data.upload_id)
    if [[ -z "$UPLOAD_ID" ]]; then
        echo "INIT_FAIL:$b" >&2
        return 1
    fi
    curl -s -X PUT "$ASSET/v1/assets/upload/$UPLOAD_ID/chunk/1" "${INS_HDRS[@]}" \
        -H 'Content-Type: application/octet-stream' \
        -H "Content-Length: $FILE_SIZE" --data-binary "@$TMPFILE" > /dev/null
    b=$(curl -s -X POST "$ASSET/v1/assets/upload/$UPLOAD_ID/complete" "${INS_HDRS[@]}" \
        -H 'Content-Type: application/json' -d '{"total_chunks":1}')
    local KEY=$(echo "$b" | extract data.object_key)
    if [[ -z "$KEY" ]]; then
        echo "COMPLETE_FAIL:$b" >&2
        return 1
    fi
    echo "$KEY|$FILE_SHA|$FILE_SIZE|$TMPFILE"
}

# S4 / S5 上传 mesh1
log "  S4-5: 上传 mesh1"
MESH1=$(upload_mesh v1)
if [[ "$MESH1" == INIT_FAIL:* ]] || [[ "$MESH1" == COMPLETE_FAIL:* ]] || [[ -z "$MESH1" ]]; then
    record "S4.upload_mesh1" false 0 0 "" "" 0 "$MESH1"
    record "S5.upload_complete_mesh1" false 0 0 "" "" 0 "$MESH1"
    log "✗ mesh1 上传失败：$MESH1"
    exit 5
fi
record "S4.upload_mesh1" true 200 200 "" "" 0 ""
record "S5.upload_complete_mesh1" true 200 200 "" "" 0 ""
KEY1=$(echo "$MESH1" | cut -d'|' -f1)
SHA1=$(echo "$MESH1" | cut -d'|' -f2)
SIZE1=$(echo "$MESH1" | cut -d'|' -f3)
FILE1=$(echo "$MESH1" | cut -d'|' -f4)

# S6 创建 shape v1.0
b=$(step "S6.create_shape_v1" 200 0 -X POST "$ADMIN/admin/v1/catalog/vehicles/$VMID/shapes" \
    "${ADMIN_HDRS[@]}" -H 'Content-Type: application/json' \
    -d "{\"version_name\":\"v1.0_2024Q1\",\"description\":\"首个标定外廓\",\"source\":\"factory_cad\",\"mesh_object_key\":\"$KEY1\",\"mesh_sha256\":\"$SHA1\",\"mesh_size_bytes\":$SIZE1,\"mesh_format\":\"glb\",\"triangle_count\":1234,\"bbox\":{\"min_x\":-2.5,\"min_y\":-1.0,\"min_z\":-2.5,\"max_x\":2.5,\"max_y\":1.5,\"max_z\":2.5},\"qc_score\":0.95}")
SID1=$(echo "$b" | extract data.id)
[[ -n "$SID1" ]] || { log "✗ shape v1 id 为空 (response: $b)"; exit 5; }

# S7 同名重复
step "S7.duplicate_version_40201" 409 40201 -X POST \
    "$ADMIN/admin/v1/catalog/vehicles/$VMID/shapes" \
    "${ADMIN_HDRS[@]}" -H 'Content-Type: application/json' \
    -d "{\"version_name\":\"v1.0_2024Q1\",\"mesh_object_key\":\"$KEY1\",\"mesh_sha256\":\"$SHA1\",\"mesh_size_bytes\":$SIZE1,\"mesh_format\":\"glb\"}" > /dev/null

# S8 invalid mesh_format（CHECK 限定 glb/ply/stl/obj/gltf）
step "S8.invalid_format_10002" 400 10002 -X POST \
    "$ADMIN/admin/v1/catalog/vehicles/$VMID/shapes" \
    "${ADMIN_HDRS[@]}" -H 'Content-Type: application/json' \
    -d "{\"version_name\":\"v_fbx\",\"mesh_object_key\":\"$KEY1\",\"mesh_sha256\":\"$SHA1\",\"mesh_size_bytes\":$SIZE1,\"mesh_format\":\"fbx\"}" > /dev/null

# S9 list shapes
b=$(step "S9.list_shapes" 200 0 \
    "$ADMIN/admin/v1/catalog/vehicles/$VMID/shapes" "${ADMIN_HDRS[@]}")
n=$(echo "$b" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["data"]["items"]))')
[[ "$n" -ge 1 ]] && S9OK=true || S9OK=false
record "S9b.shapes_count" "$S9OK" 0 0 "" "" 0 "got=$n want>=1"

# S10 publish v1.0
b=$(step "S10.publish_v1" 200 0 -X POST \
    "$ADMIN/admin/v1/catalog/vehicles/$VMID/shapes/$SID1/publish" "${ADMIN_HDRS[@]}")
status=$(echo "$b" | extract data.status)
[[ "$status" == "published" ]] && S10OK=true || S10OK=false
record "S10b.published_state" "$S10OK" 0 0 "" "" 0 "status=$status want=published"

# S11 published 后 patch
step "S11.patch_after_publish_40401" 409 40401 -X PATCH \
    "$ADMIN/admin/v1/catalog/vehicles/$VMID/shapes/$SID1" \
    "${ADMIN_HDRS[@]}" -H 'Content-Type: application/json' \
    -d '{"description":"改不了"}' > /dev/null

# S12 active 接口 → 含签名 URL + expire_at
b=$(step "S12.active_with_url" 200 0 \
    "$SHAPEREF/v1/catalog/vehicles/$VMID/shape")
DL_URL=$(echo "$b" | extract data.mesh_download_url)
EXP_AT=$(echo "$b" | extract data.mesh_url_expire_at)
GOT_SHA=$(echo "$b" | extract data.mesh_sha256)
[[ -n "$DL_URL" && -n "$EXP_AT" && "$GOT_SHA" == "$SHA1" ]] && S12OK=true || S12OK=false
record "S12b.active_url_present" "$S12OK" 0 0 "" "" 0 "url_len=${#DL_URL} expire=$EXP_AT sha_eq=$( [[ $GOT_SHA == $SHA1 ]] && echo yes || echo no)"

# S13 下载并验 sha256
curl -s "$DL_URL" -o "$OUTPUT_DIR/dl-v1.bin"
DL_SHA=$(sha256sum "$OUTPUT_DIR/dl-v1.bin" | awk '{print $1}')
DL_SIZE=$(stat -c%s "$OUTPUT_DIR/dl-v1.bin")
[[ "$DL_SHA" == "$SHA1" && "$DL_SIZE" == "$SIZE1" ]] && S13OK=true || S13OK=false
record "S13.download_sha_match" "$S13OK" 0 0 "" "" 0 "size=$DL_SIZE want=$SIZE1 sha_eq=$( [[ $DL_SHA == $SHA1 ]] && echo yes || echo no)"

# S14 上传 v2 mesh + 创建 shape v2.0
log "  S14: 上传 mesh2 + 创建 shape v2.0"
MESH2=$(upload_mesh v2)
if [[ "$MESH2" == INIT_FAIL:* ]] || [[ "$MESH2" == COMPLETE_FAIL:* ]] || [[ -z "$MESH2" ]]; then
    record "S14.create_shape_v2" false 0 0 "" "" 0 "$MESH2"
    log "✗ mesh2 上传失败：$MESH2"
    exit 5
fi
KEY2=$(echo "$MESH2" | cut -d'|' -f1)
SHA2=$(echo "$MESH2" | cut -d'|' -f2)
SIZE2=$(echo "$MESH2" | cut -d'|' -f3)
b=$(step "S14.create_shape_v2" 200 0 -X POST "$ADMIN/admin/v1/catalog/vehicles/$VMID/shapes" \
    "${ADMIN_HDRS[@]}" -H 'Content-Type: application/json' \
    -d "{\"version_name\":\"v2.0_2024Q3\",\"source\":\"scan_high_res\",\"mesh_object_key\":\"$KEY2\",\"mesh_sha256\":\"$SHA2\",\"mesh_size_bytes\":$SIZE2,\"mesh_format\":\"ply\",\"point_count\":50000}")
SID2=$(echo "$b" | extract data.id)

# S15 publish v2 → v1 自动 archived
b=$(step "S15.publish_v2" 200 0 -X POST \
    "$ADMIN/admin/v1/catalog/vehicles/$VMID/shapes/$SID2/publish" "${ADMIN_HDRS[@]}")
v1=$(curl -s "$ADMIN/admin/v1/catalog/vehicles/$VMID/shapes/$SID1" "${ADMIN_HDRS[@]}")
v1status=$(echo "$v1" | extract data.status)
[[ "$v1status" == "archived" ]] && S15OK=true || S15OK=false
record "S15b.v1_auto_archived" "$S15OK" 0 0 "" "" 0 "v1.status=$v1status want=archived"

# S16 active 现在是 v2
b=$(curl -s "$SHAPEREF/v1/catalog/vehicles/$VMID/shape")
ACTIVE_ID=$(echo "$b" | extract data.id)
[[ "$ACTIVE_ID" == "$SID2" ]] && S16OK=true || S16OK=false
record "S16.active_is_v2" "$S16OK" 0 0 "" "" 0 "active=$ACTIVE_ID expected=$SID2"

# S17 删 archived（v1.0）
step "S17.delete_archived_40401" 409 40401 -X DELETE \
    "$ADMIN/admin/v1/catalog/vehicles/$VMID/shapes/$SID1" "${ADMIN_HDRS[@]}" > /dev/null

# S18 创建 v3.0 draft → DELETE 成功
MESH3=$(upload_mesh v3)
KEY3=$(echo "$MESH3" | cut -d'|' -f1)
SHA3=$(echo "$MESH3" | cut -d'|' -f2)
SIZE3=$(echo "$MESH3" | cut -d'|' -f3)
b=$(curl -s -X POST "$ADMIN/admin/v1/catalog/vehicles/$VMID/shapes" \
    "${ADMIN_HDRS[@]}" -H 'Content-Type: application/json' \
    -d "{\"version_name\":\"v3.0_draft\",\"mesh_object_key\":\"$KEY3\",\"mesh_sha256\":\"$SHA3\",\"mesh_size_bytes\":$SIZE3,\"mesh_format\":\"glb\"}")
SID3=$(echo "$b" | extract data.id)
step "S18.delete_draft" 200 0 -X DELETE \
    "$ADMIN/admin/v1/catalog/vehicles/$VMID/shapes/$SID3" "${ADMIN_HDRS[@]}" > /dev/null

# S19 App 路径（gateway → api BFF → shaperef）
b=$(step "S19.app_active_via_gateway" 200 0 \
    "$GATEWAY/v1/catalog/vehicles/$VMID/shape" \
    -H "Authorization: Bearer $INSP_TOK")
APP_URL=$(echo "$b" | extract data.mesh_download_url)
[[ -n "$APP_URL" ]] && S19OK=true || S19OK=false
record "S19b.app_url_present" "$S19OK" 0 0 "" "" 0 "url_len=${#APP_URL}"

# S20 audit 计数
b=$(curl -s "$ADMIN/admin/v1/audit?action=shaperef.%&limit=50" "${ADMIN_HDRS[@]}")
n=$(echo "$b" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["data"]["items"]))')
[[ "$n" -ge 5 ]] && S20OK=true || S20OK=false
record "S20.audit_count" "$S20OK" 0 0 "" "" 0 "got=$n want>=5"

log "3. 采样完成 → $RESULTS"
log "   下一步：python3 $(dirname "$0")/analyze.py $OUTPUT_DIR"
