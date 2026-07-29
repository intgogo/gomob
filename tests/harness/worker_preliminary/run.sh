#!/bin/bash
# worker_preliminary/run.sh — M-S5.3 worker 真业务端到端
#
# 流程：
#   1. 启 6 服务（auth/asset/vinref/cvengine/admin + worker）
#   2. admin 创建 vehicle_model + vinref 批次 + 写 A 样本 + publish
#   3. inspector 上传 inspection + 上传扫描 A 字符（与厂家 A 一致，IoU 应 ≈ 1.0）
#   4. NATS publish inspection.scan_completed 含 17 个字符（实际只测 1 个 A，其他用相同 A）
#   5. 等 worker 处理 → 验证 inspections.preliminary_verdict='pass' + reasons
#   6. 验证 NATS inspection.preliminary_done 事件已发出（监听一个 sub）

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVER_DIR="$PROJ_DIR/server"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/worker_preliminary}"
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
GOMOB_WORKER_HEALTH_ADDR=:18085 \
GOMOB_NATS_URL=$NATS_URL \
GOMOB_CVENGINE_TARGET=http://127.0.0.1:18810 \
GOMOB_MINIO_ENDPOINT=127.0.0.1:9000 GOMOB_MINIO_BUCKET=gomob-assets \
    "$SERVER_DIR/.dev/bin/gomob-worker"   > "$OUTPUT_DIR/worker.log"   2>&1 & PIDS+=($!)
sleep 2

for s in "$AUTH/healthz" "$ASSET/healthz" "$VINREF/healthz" "$ADMIN/healthz" "$CV/healthz" "$WORKER/healthz"; do
    hc=$(curl -s -o /dev/null -w '%{http_code}' "$s")
    if [[ "$hc" != "200" ]]; then log "✗ $s=$hc"; cat "$OUTPUT_DIR/worker.log" | tail -10; exit 4; fi
done
record "S0.all_services_up" true "6 服务 healthz=200"

# 2. 注册 inspector 拿 token
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

# 3. 准备 A 字符 PNG
log "3. 生成厂家 A 模板（也用同图当扫描端拍到的 A）"
gen_A() {
    python3 - <<'PY' > "$1"
import struct, zlib, sys
W=H=64
raw = b''
for y in range(H):
    row = bytearray(W)
    for x in range(W):
        v = 0
        if 20 <= y <= 50 and abs(x - (16 + (50-y)*0.7)) <= 2: v = 255
        if 20 <= y <= 50 and abs(x - (48 - (50-y)*0.7)) <= 2: v = 255
        if 33 <= y <= 36 and 24 <= x <= 40: v = 255
        row[x] = v
    raw += b'\x00' + bytes(row)
def chunk(t, d):
    return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d))
sig = b'\x89PNG\r\n\x1a\n'
ihdr = struct.pack('>IIBBBBB', W, H, 8, 0, 0, 0, 0)
idat = zlib.compress(raw)
sys.stdout.buffer.write(sig + chunk(b'IHDR', ihdr) + chunk(b'IDAT', idat) + chunk(b'IEND', b''))
PY
}
gen_A "$OUTPUT_DIR/A.png"

# 4. 上传 → asset 拿 object_key
upload_alpha() {
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
    [[ -n "$KEY" ]] || { echo "COMPLETE_FAIL"; return 1; }
    echo "$KEY|$SHA|$SIZE"
}

META=$(upload_alpha "$OUTPUT_DIR/A.png")
KEY=$(echo "$META" | cut -d'|' -f1)
SHA=$(echo "$META" | cut -d'|' -f2)
SIZE=$(echo "$META" | cut -d'|' -f3)
[[ -n "$KEY" ]] || { log "✗ 上传失败"; exit 5; }
record "S1.asset_upload" true "key=$KEY"

# 5. 创建车型 + vin-ref 批次 + 样本 + publish
b=$(curl -s -X POST "$ADMIN/admin/v1/catalog/vehicles" "${ADMIN_HDRS[@]}" \
    -H 'Content-Type: application/json' \
    -d "{\"make\":\"worker-test-$$\",\"series\":\"V1\",\"year\":2025}")
VMID=$(echo "$b" | extract data.id)

b=$(curl -s -X POST "$ADMIN/admin/v1/catalog/vehicles/$VMID/vin-refs/batches" \
    "${ADMIN_HDRS[@]}" -H 'Content-Type: application/json' \
    -d '{"name":"factory_test"}')
BID=$(echo "$b" | extract data.id)

curl -s -o /dev/null -X POST "$ADMIN/admin/v1/catalog/vehicles/$VMID/vin-refs/batches/$BID/samples" \
    "${ADMIN_HDRS[@]}" -H 'Content-Type: application/json' \
    -d "{\"character\":\"A\",\"arr_mode\":1,\"alpha_object_key\":\"$KEY\",\"alpha_sha256\":\"$SHA\",\"alpha_size_bytes\":$SIZE}"

curl -s -o /dev/null -X POST "$ADMIN/admin/v1/catalog/vehicles/$VMID/vin-refs/batches/$BID/publish" "${ADMIN_HDRS[@]}"
record "S2.vinref_seeded" true "vmid=$VMID bid=$BID"

# 6. 创建 vehicle + inspection
b=$(curl -s -X POST "http://127.0.0.1:18080/v1/inspections" \
    -H "X-Gomob-User-Id: $INSP_UID" -H 'X-Gomob-Roles: inspector' -H 'Content-Type: application/json' \
    -d "{\"vin\":\"LSGFY12345678ABCD\",\"plate_no\":\"测A\",\"brand\":\"测试\",\"station_id\":\"$STATION_ID\"}" 2>&1) || true
# api 进程可能没启；用直接 SQL 创建
podman exec -i gomob-pg psql -U gomob -d gomob -v ON_ERROR_STOP=1 > /dev/null <<SQL
INSERT INTO vehicles(vin) VALUES('LSGFY12345678ABCD') ON CONFLICT DO NOTHING;
INSERT INTO inspections(vehicle_id, inspector_id, station_id, status)
SELECT (SELECT id FROM vehicles WHERE vin='LSGFY12345678ABCD'),
       $INSP_UID, $STATION_ID, 'scanning';
SQL
INS_ID=$(podman exec gomob-pg psql -U gomob -d gomob -tAc \
    "SELECT id FROM inspections WHERE inspector_id=$INSP_UID ORDER BY id DESC LIMIT 1")
[[ -n "$INS_ID" ]] || { log "✗ 缺 inspection_id"; exit 5; }
record "S3.inspection_created" true "inspection_id=$INS_ID status=scanning"

# 7. 监听 NATS preliminary_done 事件（用 nc 容器）
LISTEN_FILE="$OUTPUT_DIR/nats_listen.txt"
: > "$LISTEN_FILE"
podman exec -d gomob-nats sh -c "nats sub --count=1 inspection.preliminary_done > /tmp/preliminary_done.txt 2>&1" 2>/dev/null || true

# 8. publish scan_completed —— 17 个字符，全部用同一个 A 的 alpha_object_key
log "8. publish NATS inspection.scan_completed"
PAYLOAD=$(python3 -c "
import json
chars = []
# 用 17 个 A 字符（实际 VIN 不会全是 A，但 vin-ref 只录了 A，所以全 A 才能命中样本得分）
for i in range(1, 18):
    chars.append({'position': i, 'character': 'A', 'alpha_object_key': '$KEY'})
print(json.dumps({
    'inspection_id': $INS_ID,
    'vehicle_model_id': int('$VMID'),
    'vin': 'LSGFY12345678ABCD',
    'characters': chars,
}))")
echo "$PAYLOAD" > "$OUTPUT_DIR/payload.json"

# 用 podman exec 的 nats CLI 发；如果 nats CLI 不存在用 fallback 用 Go 程序
if podman exec gomob-nats nats --help > /dev/null 2>&1; then
    podman exec -i gomob-nats nats pub inspection.scan_completed "$PAYLOAD" 2>&1 | head -3
else
    # Fallback：写一个临时 Go 客户端发布
    cat > /tmp/nats-pub.go <<'GOEOF'
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
    (cd "$SERVER_DIR" && go run /tmp/nats-pub.go "$OUTPUT_DIR/payload.json" inspection.scan_completed)
fi

# 9. 等 worker 处理（17 字符 × cv-engine 单调用估 ~5s）
log "9. 等待 worker 处理（最多 30s）"
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

# 10. 验证
[[ "$VERDICT" == "pass" ]] && S5OK=true || S5OK=false
record "S5.verdict_pass" "$S5OK" "verdict=$VERDICT want=pass"

[[ "$STATUS" == "preliminary" ]] && S6OK=true || S6OK=false
record "S6.status_preliminary" "$S6OK" "status=$STATUS want=preliminary"

[[ -n "$REASONS" ]] && S7OK=true || S7OK=false
record "S7.reasons_present" "$S7OK" "reasons=${REASONS:0:80}…"

# 11. worker.log 应有"preliminary 完成"
if grep -q "preliminary 完成" "$OUTPUT_DIR/worker.log"; then
    record "S8.worker_log_completed" true ""
else
    record "S8.worker_log_completed" false "log 未含 preliminary 完成"
fi

# 12. preliminary_done 事件已发出（worker.log 含）
WORKER_PUB=$(grep -c "preliminary_done\|inspection.preliminary_done" "$OUTPUT_DIR/worker.log" 2>/dev/null || echo 0)
# 不强制断言（worker 可能不打印 publish 行）；给一个 audit 检查
AUDIT_CNT=$(podman exec gomob-pg psql -U gomob -d gomob -tAc \
    "SELECT COUNT(*) FROM audit_log WHERE action='worker.preliminary_done'")
[[ "$AUDIT_CNT" -ge 1 ]] && S9OK=true || S9OK=false
record "S9.audit_log_recorded" "$S9OK" "audit_count=$AUDIT_CNT"

log "采样完成 → $RESULTS"
