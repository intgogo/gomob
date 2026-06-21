#!/bin/bash
# worker_vin_pipeline/run.sh — M-S10.2b worker 整图 → vin_pipeline 一站式路径
#
# 验证：
#   1. App 上传整张 VIN 拍照图（synthetic A.png，对 VMASK 来说 0 检测）
#   2. publish inspection.scan_completed { full_image_object_key:"<key>" }（不带 characters[]）
#   3. worker 走 handleViaPipeline → call /cv/ocr/v1/vin_pipeline
#   4. cv-engine 返 detections=0 → verdict=fail + reasons=[no_chars_detected]
#   5. worker 写 inspections.preliminary_verdict=fail + status=preliminary
#   6. audit_log 记录 mode=pipeline
#
# 与现有 worker_preliminary 的差异：
#   - worker_preliminary 走端侧已切字符路径（characters[]）
#   - worker_vin_pipeline 走整图喂 cv-engine 一次到底（full_image_object_key）

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVER_DIR="$PROJ_DIR/server"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/worker_vin_pipeline}"
mkdir -p "$OUTPUT_DIR"
RESULTS="$OUTPUT_DIR/results.jsonl"
: > "$RESULTS"

ADMIN=http://127.0.0.1:19090
ASSET=http://127.0.0.1:18083
VINREF=http://127.0.0.1:18058
CV=http://127.0.0.1:18810
WORKER=http://127.0.0.1:18085
AUTH=http://127.0.0.1:18082
NATS_URL=nats://127.0.0.1:4222
VMASK="/root/lilw/gosmart/data/vins0.onnx"

log() { printf "[%s] %s\n" "$(date +%H:%M:%S)" "$*"; }

record() {
    local scenario=$1 ok=$2 note=${3:-}
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
STATION_ID=$(podman exec gomob-pg psql -U gomob -d gomob -tAc "SELECT id FROM stations LIMIT 1")

if [[ ! -f "$VMASK" ]]; then
    log "✗ 缺 VMASK $VMASK"
    record "S0.vmask_available" false "缺 $VMASK"
    exit 4
fi

(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-auth     ./cmd/auth)     || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-asset    ./cmd/asset)    || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-vinref   ./cmd/vinref)   || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-catalog  ./cmd/catalog)  || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-admin    ./cmd/admin)    || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-cvengine ./cmd/cvengine) || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-worker   ./cmd/worker)   || exit 3

pkill -9 -f "gomob-cvengine\|gomob-worker\|gomob-vinref\|gomob-asset\|gomob-auth\|gomob-admin\|gomob-catalog" 2>/dev/null
sleep 2

PIDS=()
trap 'for p in "${PIDS[@]:-}"; do kill $p 2>/dev/null || true; done; wait 2>/dev/null || true' EXIT

log "1. 启动服务（cvengine 含 VMASK 模型加载）"
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
GOMOB_CVENGINE_MODELS="VMASK:mask=$VMASK:0|1|2|3|4|5|6|7|8|9|A|B|C|D|E|F|G|H|J|K|L|M|N|P|R|S|T|U|V|W|X|Y|Z" \
    "$SERVER_DIR/.dev/bin/gomob-cvengine" > "$OUTPUT_DIR/cvengine.log" 2>&1 & PIDS+=($!)
sleep 12   # VMASK 354MB 加载需 5-10s
GOMOB_WORKER_HEALTH_ADDR=:18085 \
GOMOB_NATS_URL=$NATS_URL \
GOMOB_CVENGINE_TARGET=http://127.0.0.1:18810 \
GOMOB_MINIO_ENDPOINT=127.0.0.1:9000 GOMOB_MINIO_BUCKET=gomob-assets \
    "$SERVER_DIR/.dev/bin/gomob-worker"   > "$OUTPUT_DIR/worker.log"   2>&1 & PIDS+=($!)
sleep 2

for s in "$AUTH/healthz" "$ASSET/healthz" "$VINREF/healthz" "$ADMIN/healthz" "$CV/healthz" "$WORKER/healthz"; do
    hc=$(curl -s -o /dev/null -w '%{http_code}' "$s")
    if [[ "$hc" != "200" ]]; then log "✗ $s=$hc"; cat "$OUTPUT_DIR/cvengine.log" | tail -10; exit 4; fi
done
record "S0.all_services_up" true "6 服务 healthz=200 + cvengine VMASK 已加载"
record "S0.vmask_available" true "$(stat -c%s $VMASK)B"

# 2. 注册 inspector
INSP_USER=insp_$$_$(date +%s)
curl -s -o /dev/null -X POST "$AUTH/v1/auth/register" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$INSP_USER\",\"password\":\"insp-pass-1\",\"real_name\":\"Insp\",\"employee_id\":\"E${INSP_USER}\"}"
LOGIN=$(curl -s -X POST "$AUTH/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$INSP_USER\",\"password\":\"insp-pass-1\"}")
INSP_TOK=$(echo "$LOGIN" | extract data.access_token)
INSP_UID=$(echo "$LOGIN" | extract data.user.id)
[[ -n "$INSP_TOK" ]] || { log "✗ 缺 inspector token"; exit 5; }
INS_HDRS=(-H "X-Gomob-User-Id: $INSP_UID" -H 'X-Gomob-Roles: inspector')
ADMIN_HDRS=(-H "X-Gomob-User-Id: $ADMIN_ID" -H 'X-Gomob-Roles: admin')

# 3. 生成整张 VIN 拍照图（合成）
log "3. 生成 synthetic VIN 拍照图（VMASK 在合成图上预期 0 检测）"
gen_full() {
    python3 - <<'PY' > "$1"
import struct, zlib, sys
W=H=128
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
}
gen_full "$OUTPUT_DIR/full.png"

# 4. 上传
upload() {
    local file=$1
    local SHA=$(sha256sum "$file" | awk '{print $1}')
    local SIZE=$(stat -c%s "$file")
    local b=$(curl -s -X POST "$ASSET/v1/assets/upload/init" "${INS_HDRS[@]}" \
        -H 'Content-Type: application/json' \
        -d "{\"kind\":\"vin_scan\",\"size_bytes\":$SIZE,\"sha256\":\"$SHA\",\"mime\":\"image/png\",\"chunk_mb\":8}")
    local UPID=$(echo "$b" | extract data.upload_id)
    [[ -n "$UPID" ]] || { echo "INIT_FAIL"; return 1; }
    curl -s -o /dev/null -X PUT "$ASSET/v1/assets/upload/$UPID/chunk/1" "${INS_HDRS[@]}" \
        -H 'Content-Type: application/octet-stream' \
        -H "Content-Length: $SIZE" --data-binary "@$file"
    b=$(curl -s -X POST "$ASSET/v1/assets/upload/$UPID/complete" "${INS_HDRS[@]}" \
        -H 'Content-Type: application/json' -d '{"total_chunks":1}')
    echo "$b" | extract data.object_key
}
FULL_KEY=$(upload "$OUTPUT_DIR/full.png")
[[ -n "$FULL_KEY" ]] || { log "✗ 上传失败"; exit 5; }
record "S1.full_image_uploaded" true "key=$FULL_KEY"

# 5. 创建车型
b=$(curl -s -X POST "$ADMIN/admin/v1/catalog/vehicles" "${ADMIN_HDRS[@]}" \
    -H 'Content-Type: application/json' \
    -d "{\"make\":\"vinpipe-test-$$\",\"series\":\"V1\",\"year\":2025}")
VMID=$(echo "$b" | extract data.id)
record "S2.vehicle_model_created" true "vmid=$VMID"

# 6. 创建 inspection
podman exec -i gomob-pg psql -U gomob -d gomob -v ON_ERROR_STOP=1 > /dev/null <<SQL
INSERT INTO vehicles(vin) VALUES('LSGFY1PIPELINE001') ON CONFLICT DO NOTHING;
INSERT INTO inspections(vehicle_id, inspector_id, station_id, status)
SELECT (SELECT id FROM vehicles WHERE vin='LSGFY1PIPELINE001'),
       $INSP_UID, $STATION_ID, 'scanning';
SQL
INS_ID=$(podman exec gomob-pg psql -U gomob -d gomob -tAc \
    "SELECT id FROM inspections WHERE inspector_id=$INSP_UID ORDER BY id DESC LIMIT 1")
[[ -n "$INS_ID" ]] || { log "✗ 缺 inspection_id"; exit 5; }
record "S3.inspection_created" true "id=$INS_ID status=scanning"

# 7. publish scan_completed —— 用 full_image_object_key（pipeline 路径）
log "7. publish NATS scan_completed (full_image_object_key 模式)"
PAYLOAD=$(python3 -c "
import json
print(json.dumps({
    'inspection_id': $INS_ID,
    'vehicle_model_id': int('$VMID'),
    'vin': 'LSGFY1PIPELINE001',
    'full_image_object_key': '$FULL_KEY',
}))")
echo "$PAYLOAD" > "$OUTPUT_DIR/payload.json"

if podman exec gomob-nats nats --help > /dev/null 2>&1; then
    podman exec -i gomob-nats nats pub inspection.scan_completed "$PAYLOAD" 2>&1 | head -3
else
    cat > /tmp/nats-pub-pipe.go <<'GOEOF'
package main
import (
    "fmt"
    "os"
    "github.com/nats-io/nats.go"
)
func main() {
    nc, err := nats.Connect("nats://127.0.0.1:4222")
    if err != nil { fmt.Println("connect:", err); os.Exit(1) }
    defer nc.Close()
    payload, _ := os.ReadFile(os.Args[1])
    if err := nc.Publish(os.Args[2], payload); err != nil { fmt.Println("publish:", err); os.Exit(1) }
    nc.Flush()
    fmt.Println("published")
}
GOEOF
    (cd "$SERVER_DIR" && go run /tmp/nats-pub-pipe.go "$OUTPUT_DIR/payload.json" inspection.scan_completed)
fi

# 8. 等 worker 处理（VMASK 在 128×128 全黑图上推理 + 0 检测，预期非常快）
log "8. 等待 worker 处理（最多 30s）"
for i in $(seq 1 30); do
    VERDICT=$(podman exec gomob-pg psql -U gomob -d gomob -tAc \
        "SELECT preliminary_verdict FROM inspections WHERE id=$INS_ID" 2>/dev/null)
    if [[ -n "$VERDICT" && "$VERDICT" != " " ]]; then
        log "  worker 已写入 verdict=$VERDICT (耗时 ${i}s)"
        break
    fi
    sleep 1
done

VERDICT=$(podman exec gomob-pg psql -U gomob -d gomob -tAc \
    "SELECT preliminary_verdict FROM inspections WHERE id=$INS_ID")
STATUS=$(podman exec gomob-pg psql -U gomob -d gomob -tAc \
    "SELECT status FROM inspections WHERE id=$INS_ID")
REASONS=$(podman exec gomob-pg psql -U gomob -d gomob -tAc \
    "SELECT preliminary_reasons FROM inspections WHERE id=$INS_ID")

# 9. 验证：合成图 0 检测 → verdict=fail
[[ "$VERDICT" == "fail" ]] && S9OK=true || S9OK=false
record "S4.verdict_fail_zero_dets" "$S9OK" "verdict=$VERDICT want=fail (0 检测)"

[[ "$STATUS" == "preliminary" ]] && S10OK=true || S10OK=false
record "S5.status_preliminary" "$S10OK" "status=$STATUS"

[[ "$REASONS" == *"no_chars_detected"* ]] && S11OK=true || S11OK=false
record "S6.reasons_no_chars_detected" "$S11OK" "reasons=${REASONS:0:80}…"

# 10. worker.log 应有"preliminary 完成 (pipeline)"
if grep -q "preliminary 完成 (pipeline)" "$OUTPUT_DIR/worker.log"; then
    record "S7.worker_pipeline_log" true ""
else
    record "S7.worker_pipeline_log" false "log 未含 preliminary 完成 (pipeline)"
fi

# 11. audit_log 含 mode=pipeline
AUDIT_AFTER=$(podman exec gomob-pg psql -U gomob -d gomob -tAc \
    "SELECT after FROM audit_log WHERE action='worker.preliminary_done' LIMIT 1")
if [[ "$AUDIT_AFTER" == *"\"mode\""*"\"pipeline\""* ]] || [[ "$AUDIT_AFTER" == *"pipeline"* ]]; then
    record "S8.audit_mode_pipeline" true "audit 含 mode=pipeline"
else
    record "S8.audit_mode_pipeline" false "audit after=${AUDIT_AFTER:0:120}"
fi

# 12. NATS preliminary_done 事件已 publish（worker.log 应有 publish 行；不会 publish 失败）
if grep -q "publish preliminary_done 失败" "$OUTPUT_DIR/worker.log"; then
    record "S9.publish_done_no_error" false "log 含 publish 失败"
else
    record "S9.publish_done_no_error" true ""
fi

log "采样完成 → $RESULTS"

# 13. 分析判定 — 三态退码:FAIL→1 / 用法或缺数据→2 / 全通过→0。
#     不再无条件 exit 0:任一场景 ok=false(verdict 不对/状态不对/audit 缺 mode 等)都让 run.sh 落非零退码。
log "13. 分析判定（三态退码）"
python3 "$(dirname "$0")/analyze.py" "$OUTPUT_DIR"
exit $?
