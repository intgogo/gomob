#!/bin/bash
# cv_modelregistry_load/run.sh — M-S10.4 model-registry → MinIO → cv-engine 端到端
#
# 验证：
#   1. admin 把真 ONNX（vmet1.onnx 106MB）通过 asset 上传 → 拿 object_key
#   2. admin 在 model-registry 创建 Model（asset_uri=object_key, sha256, metadata={kind:"general"}）
#   3. admin activate Model → status=active
#   4. NATS 收到 model.version.activated（验证 modelregistry 真发了事件）
#   5. cvengine 启动期 GOMOB_CVENGINE_MODEL_NAMES=VMET → loader 拉 model-registry → MinIO
#      → SHA256 校验 → core.RegisterONNX → /cv/v1/models 显示 loaded=true
#   6. NATS 热更：admin 创建 v2 Model + activate → cvengine 收事件 → 重载 → /cv/v1/models 显示新版本

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVER_DIR="$PROJ_DIR/server"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/cv_modelregistry_load}"
mkdir -p "$OUTPUT_DIR"
RESULTS="$OUTPUT_DIR/results.jsonl"
: > "$RESULTS"

ADMIN=http://127.0.0.1:19090
ASSET=http://127.0.0.1:18083
MODELREG=http://127.0.0.1:18057
CV=http://127.0.0.1:18810
AUTH=http://127.0.0.1:18082
# M12.4 机器特定绝对路径参数化：可被 GOMOB_VMET_ONNX 覆盖，默认保持本机现行为。
VMET="${GOMOB_VMET_ONNX:-/root/lilw/gosmart/data/vmet1.onnx}"

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

# 0. 前置：清表 + 起服务
log "0. 前置"
podman ps --format '{{.Names}}' | grep -qx gomob-pg    || { log "缺 gomob-pg"; exit 2; }
podman ps --format '{{.Names}}' | grep -qx gomob-minio || { log "缺 gomob-minio"; exit 2; }
podman ps --format '{{.Names}}' | grep -qx gomob-nats  || { log "缺 gomob-nats"; exit 2; }

[[ -f "$VMET" ]] || { log "缺 $VMET"; exit 3; }
record "S0a.vmet_available" true 0 0 "" "" 0 "$(stat -c%s $VMET)B"

podman exec -i gomob-pg psql -U gomob -d gomob -v ON_ERROR_STOP=1 > /dev/null <<'SQL'
DELETE FROM audit_log;
DELETE FROM model_routes;
DELETE FROM models;
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

# 编译
log "1. 编译"
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-auth          ./cmd/auth)          || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-asset         ./cmd/asset)         || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-modelregistry ./cmd/modelregistry) || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-admin         ./cmd/admin)         || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-cvengine      ./cmd/cvengine)      || exit 3

pkill -9 -f "gomob-cvengine\|gomob-modelregistry" 2>/dev/null
sleep 2

PIDS=()
trap 'for p in "${PIDS[@]:-}"; do kill -9 $p 2>/dev/null || true; done; wait 2>/dev/null || true' EXIT

GOMOB_AUTH_HTTP_ADDR=:18082 GOMOB_AUTH_DEV_AUTOACTIVATE=true \
    "$SERVER_DIR/.dev/bin/gomob-auth"          > "$OUTPUT_DIR/auth.log"          2>&1 &
PIDS+=($!)
GOMOB_ASSET_HTTP_ADDR=:18083 GOMOB_REDIS_ADDR=127.0.0.1:6379 \
GOMOB_MINIO_ENDPOINT=127.0.0.1:9000 GOMOB_MINIO_BUCKET=gomob-assets \
    "$SERVER_DIR/.dev/bin/gomob-asset"         > "$OUTPUT_DIR/asset.log"         2>&1 &
PIDS+=($!)
GOMOB_MODELREGISTRY_HTTP_ADDR=:18057 GOMOB_NATS_URL=nats://127.0.0.1:4222 \
    "$SERVER_DIR/.dev/bin/gomob-modelregistry" > "$OUTPUT_DIR/modelregistry.log" 2>&1 &
PIDS+=($!)
GOMOB_ADMIN_HTTP_ADDR=:19090 \
    "$SERVER_DIR/.dev/bin/gomob-admin"         > "$OUTPUT_DIR/admin.log"         2>&1 &
PIDS+=($!)
sleep 1

ADMIN_HDRS=(-H "X-Gomob-User-Id: $ADMIN_ID" -H 'X-Gomob-Roles: admin')

# 2. 注册 inspector 拿 token（asset 上传需要登录）
INSP_USER=insp_$$_$(date +%s)
curl -s -o /dev/null -X POST "$AUTH/v1/auth/register" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$INSP_USER\",\"password\":\"insp-pass-1\",\"real_name\":\"Insp\",\"employee_id\":\"E${INSP_USER}\"}"
LOGIN=$(curl -s -X POST "$AUTH/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$INSP_USER\",\"password\":\"insp-pass-1\"}")
INSP_TOK=$(echo "$LOGIN" | extract data.access_token)
INSP_UID=$(echo "$LOGIN" | extract data.user.id)
[[ -n "$INSP_TOK" ]] || { log "✗ 缺 inspector token"; exit 5; }
INS_HDRS=(-H "X-Gomob-User-Id: $INSP_UID" -H 'X-Gomob-Roles: inspector')

# 3. 上传 vmet1.onnx 到 asset → 拿 object_key
log "3. 上传 vmet1.onnx (106MB) 到 asset"
SHA=$(sha256sum "$VMET" | awk '{print $1}')
SIZE=$(stat -c%s "$VMET")
b=$(curl -s -X POST "$ASSET/v1/assets/upload/init" "${INS_HDRS[@]}" \
    -H 'Content-Type: application/json' \
    -d "{\"kind\":\"model\",\"size_bytes\":$SIZE,\"sha256\":\"$SHA\",\"mime\":\"application/octet-stream\",\"chunk_mb\":8}")
UPID=$(echo "$b" | extract data.upload_id)
[[ -n "$UPID" ]] || { log "✗ upload init: $b"; exit 5; }
CHSIZE=$(echo "$b" | extract data.chunk_size)
N=1; TOTAL=0
split -b $CHSIZE -d "$VMET" "$OUTPUT_DIR/chunk-"
for f in "$OUTPUT_DIR"/chunk-*; do
    sz=$(stat -c%s "$f")
    curl -s -o /dev/null -X PUT "$ASSET/v1/assets/upload/$UPID/chunk/$N" "${INS_HDRS[@]}" \
        -H 'Content-Type: application/octet-stream' \
        -H "Content-Length: $sz" --data-binary "@$f"
    N=$((N+1)); TOTAL=$((TOTAL+1))
done
b=$(curl -s -X POST "$ASSET/v1/assets/upload/$UPID/complete" "${INS_HDRS[@]}" \
    -H 'Content-Type: application/json' -d "{\"total_chunks\":$TOTAL}")
KEY=$(echo "$b" | extract data.object_key)
[[ -n "$KEY" ]] || { log "✗ asset complete: $b"; exit 5; }
record "S1.asset_upload" true 200 200 "" "" 0 "object_key=$KEY size=$SIZE"
rm -f "$OUTPUT_DIR"/chunk-*

# 4. 在 modelregistry 创建 Model (kind=general 元数据) + activate
log "4. modelregistry create + activate VMET v1"
b=$(step "S2.create_model_v1" 200 0 -X POST "$ADMIN/admin/v1/models" \
    "${ADMIN_HDRS[@]}" -H 'Content-Type: application/json' \
    -d "{\"name\":\"VMET\",\"version\":\"v1\",\"asset_uri\":\"$KEY\",\"sha256\":\"$SHA\",\"runtime\":\"onnx\",\"metadata\":{\"kind\":\"general\"}}")
MID=$(echo "$b" | extract data.id)
[[ -n "$MID" ]] || { log "✗ modelregistry create: $b"; exit 5; }
b=$(step "S3.activate_model_v1" 200 0 -X POST "$ADMIN/admin/v1/models/$MID/activate" \
    "${ADMIN_HDRS[@]}")

# 5. 启动 cvengine 通过 model-registry 加载
log "5. 启动 cvengine（GOMOB_CVENGINE_MODEL_NAMES=VMET）"
LD_LIBRARY_PATH=/usr/local/lib:/usr/local/lib64:/usr/local/onnxruntime/lib \
GOMOB_CVENGINE_HTTP_ADDR=:18810 \
GOMOB_CVENGINE_MODEL_NAMES=VMET \
GOMOB_MODELREGISTRY_TARGET=$MODELREG \
GOMOB_MINIO_ENDPOINT=127.0.0.1:9000 GOMOB_MINIO_BUCKET=gomob-assets \
GOMOB_CVENGINE_MODEL_CACHE="$OUTPUT_DIR/cache" \
GOMOB_NATS_URL=nats://127.0.0.1:4222 \
    "$SERVER_DIR/.dev/bin/gomob-cvengine" > "$OUTPUT_DIR/cvengine.log" 2>&1 &
PIDS+=($!)
# loader: HTTP 调 modelregistry + 106MB MinIO 下载 + sha256 + RegisterONNX 估 5-10s
sleep 12

hc=$(curl -s -o /dev/null -w '%{http_code}' "$CV/healthz")
[[ "$hc" == "200" ]] || { log "✗ cv healthz=$hc"; cat "$OUTPUT_DIR/cvengine.log" | tail -10; exit 5; }

# 6. 验证模型加载成功
b=$(step "S4.list_models_after_load" 200 0 "$CV/cv/v1/models")
LOADED=$(echo "$b" | python3 -c '
import sys,json
d = json.load(sys.stdin)
for it in d["data"]["items"]:
    if it["tag"] == "VMET" and it.get("loaded"):
        print("true")
        sys.exit(0)
print("false")')
record "S4b.vmet_loaded_via_registry" "$LOADED" 0 0 "" "" 0 "VMET 通过 model-registry 路径加载成功"

# 7. 验证缓存文件存在 + sha256 OK
CACHED_FILE=$(ls "$OUTPUT_DIR"/cache/vmet_*.onnx 2>/dev/null | head -1)
if [[ -n "$CACHED_FILE" ]]; then
    CACHED_SIZE=$(stat -c%s "$CACHED_FILE")
    CACHED_SHA=$(sha256sum "$CACHED_FILE" | awk '{print $1}')
    [[ "$CACHED_SIZE" == "$SIZE" && "$CACHED_SHA" == "$SHA" ]] && S5OK=true || S5OK=false
    record "S5.cache_file_sha256_match" "$S5OK" 0 0 "" "" 0 "size=$CACHED_SIZE sha=${CACHED_SHA:0:16}…"
else
    record "S5.cache_file_sha256_match" false 0 0 "" "" 0 "缓存文件不存在"
fi

# 8. 创建 v2 + activate → NATS 热更，cvengine 应自动重载
log "8. NATS 热更：创建 VMET v2 + activate"
b=$(step "S6.create_model_v2" 200 0 -X POST "$ADMIN/admin/v1/models" \
    "${ADMIN_HDRS[@]}" -H 'Content-Type: application/json' \
    -d "{\"name\":\"VMET\",\"version\":\"v2\",\"asset_uri\":\"$KEY\",\"sha256\":\"$SHA\",\"runtime\":\"onnx\",\"metadata\":{\"kind\":\"general\"}}")
MID2=$(echo "$b" | extract data.id)
b=$(step "S7.activate_v2" 200 0 -X POST "$ADMIN/admin/v1/models/$MID2/activate" \
    "${ADMIN_HDRS[@]}")
# 等 NATS 投递 + cvengine 重新下载并加载
sleep 12

# 9. 验证 cvengine 现在用的是 v2（缓存目录里应该有两个文件 v1 和 v2）
V2_CACHED=$(ls "$OUTPUT_DIR"/cache/vmet_v2.onnx 2>/dev/null)
[[ -n "$V2_CACHED" ]] && S8OK=true || S8OK=false
record "S8.hot_reload_v2_cached" "$S8OK" 0 0 "" "" 0 "cache 含 vmet_v2.onnx"

# /cv/v1/models 仍显示 VMET loaded=true（被 v2 覆盖更新）
b=$(curl -s "$CV/cv/v1/models")
STILL_LOADED=$(echo "$b" | python3 -c '
import sys,json
d = json.load(sys.stdin)
for it in d["data"]["items"]:
    if it["tag"] == "VMET" and it.get("loaded"):
        print("true")
        sys.exit(0)
print("false")')
record "S9.vmet_still_loaded_after_reload" "$STILL_LOADED" 0 0 "" "" 0 "热更后 VMET 仍 loaded=true"

# 10. cvengine 日志里应有"热更成功"
HOT_RELOAD_OK="false"
if grep -q "热更成功" "$OUTPUT_DIR/cvengine.log"; then
    HOT_RELOAD_OK="true"
fi
record "S10.cvengine_log_hot_reload" "$HOT_RELOAD_OK" 0 0 "" "" 0 "log 含 hot_reload_success"

log "采样完成 → $RESULTS"
