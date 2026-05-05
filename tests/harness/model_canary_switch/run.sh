#!/bin/bash
# model_canary_switch/run.sh — M-S5 model-registry canary + 切换 全链路 harness。
#
# 场景：
#   S1  admin 创建 yolo_vin v1.0.0 (draft)
#   S2  worker GET /v1/models/active → 404（未激活）
#   S3  activate v1.0.0
#   S4  worker GET active → v1.0.0
#   S5  创建 v1.1.0 + promote canary
#   S6  PUT route canary_pct=50 + 白名单 user_id=42
#   S7  resolve N=200 不同 user_id：分配比例落在 35–65 区间
#   S8  resolve user_id=42 → canary（白名单优先）
#   S9  同 user_id 多次 resolve 一致（确定性 hash）
#   S10 activate v1.1.0：旧 v1.0.0 自动归档（PG 唯一约束保证一个 active）
#   S11 切换后 resolve 全走 active=v1.1.0（canary 不存在）
#   S12 验证 NATS 收到 model.version.activated 至少 3 条
#   S13 archive active 后再激活归档版本 → 40401

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVER_DIR="$PROJ_DIR/server"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/model_canary_switch}"
mkdir -p "$OUTPUT_DIR"
RESULTS="$OUTPUT_DIR/results.jsonl"
: > "$RESULTS"

MR=http://127.0.0.1:18057

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
try:
    d = json.load(sys.stdin); print(d.get("code",""))
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
log "0. 前置"
podman ps --format '{{.Names}}' | grep -qx gomob-pg   || { log "缺 gomob-pg"; exit 2; }
podman ps --format '{{.Names}}' | grep -qx gomob-nats || { log "缺 gomob-nats"; exit 2; }
podman exec gomob-pg psql -U gomob -d gomob -c "DELETE FROM models; DELETE FROM model_routes;" > /dev/null

# 1. 编译 + 启动 modelregistry
log "1. 编译 + 启动"
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-modelregistry ./cmd/modelregistry) || exit 3

GOMOB_MODELREGISTRY_HTTP_ADDR=:18057 GOMOB_NATS_URL=nats://127.0.0.1:4222 \
    "$SERVER_DIR/.dev/bin/gomob-modelregistry" > "$OUTPUT_DIR/modelregistry.log" 2>&1 &
MR_PID=$!
SUB_PID=""
trap 'kill $MR_PID ${SUB_PID:-} 2>/dev/null; wait 2>/dev/null' EXIT
sleep 1
hc=$(curl -s -o /dev/null -w '%{http_code}' "$MR/healthz")
[[ "$hc" == "200" ]] || { log "modelregistry 不通 $hc"; exit 4; }

# 2. NATS 订阅子进程，写到 nats-events.log
cat > "$OUTPUT_DIR/sub.go" <<'EOF'
package main
import (
    "fmt"
    "os"
    "time"
    "github.com/nats-io/nats.go"
)
func main() {
    conn, err := nats.Connect("nats://127.0.0.1:4222")
    if err != nil { fmt.Println("err:", err); os.Exit(1) }
    defer conn.Close()
    sub, err := conn.SubscribeSync("model.version.activated")
    if err != nil { fmt.Println("sub err:", err); os.Exit(1) }
    fmt.Println("[subscribed]")
    deadline := time.Now().Add(40 * time.Second)
    for time.Now().Before(deadline) {
        msg, err := sub.NextMsg(500 * time.Millisecond)
        if err != nil { continue }
        fmt.Println(string(msg.Data))
    }
}
EOF
(cd "$SERVER_DIR" && go run "$OUTPUT_DIR/sub.go") > "$OUTPUT_DIR/nats-events.log" 2>&1 &
SUB_PID=$!
sleep 1   # 等订阅就绪

# 3. 跑场景
log "2. 跑场景"

# S1
b=$(step "S1.create_v1" 200 0 -X POST "$MR/admin/v1/models" \
    -H 'X-Gomob-User-Id: 1' -H 'X-Gomob-Roles: admin' -H 'Content-Type: application/json' \
    -d '{"name":"yolo_vin","version":"v1.0.0","asset_uri":"models/yolo_vin/v1.0.0.onnx","sha256":"sha-v1","runtime":"onnx"}')
M1=$(echo "$b" | extract data.id)

# S2 active 未激活 → 404
b=$(step "S2.active_404" 404 40301 "$MR/v1/models/active?name=yolo_vin")

# S3 activate v1
b=$(step "S3.activate_v1" 200 0 -X POST "$MR/admin/v1/models/$M1/activate" \
    -H 'X-Gomob-User-Id: 1' -H 'X-Gomob-Roles: admin')

# S4 worker GET active
b=$(step "S4.get_active_v1" 200 0 "$MR/v1/models/active?name=yolo_vin")
ver=$(echo "$b" | extract data.version)
[[ "$ver" == "v1.0.0" ]] && S4OK=true || S4OK=false
record "S4b.version_v1" "$S4OK" 0 0 "" "" 0 "got=$ver"

# S5 create v2 + promote canary
b=$(step "S5a.create_v2" 200 0 -X POST "$MR/admin/v1/models" \
    -H 'X-Gomob-User-Id: 1' -H 'X-Gomob-Roles: admin' -H 'Content-Type: application/json' \
    -d '{"name":"yolo_vin","version":"v1.1.0","asset_uri":"models/yolo_vin/v1.1.0.onnx","sha256":"sha-v2","runtime":"onnx"}')
M2=$(echo "$b" | extract data.id)
b=$(step "S5b.promote_canary" 200 0 -X POST "$MR/admin/v1/models/$M2/promote" \
    -H 'X-Gomob-User-Id: 1' -H 'X-Gomob-Roles: admin')

# S6 route 50%
b=$(step "S6.upsert_route" 200 0 -X PUT "$MR/admin/v1/models/yolo_vin/route" \
    -H 'X-Gomob-User-Id: 1' -H 'X-Gomob-Roles: admin' -H 'Content-Type: application/json' \
    -d '{"canary_pct":50,"canary_user_filter":{"user_ids":[42]}}')

# S7 200 个 user_id resolve（跳过白名单 42）
canary=0; active=0
for i in $(seq 1 200); do
    [[ "$i" == "42" ]] && continue
    r=$(curl -s "$MR/v1/models/resolve?name=yolo_vin&user_id=$i" | extract data.resolved)
    case "$r" in
        canary) canary=$((canary+1)) ;;
        active) active=$((active+1)) ;;
    esac
done
total=$((canary + active))
# 接受 35–65% 浮动（FNV 不是完美均匀）
if [[ "$canary" -ge 70 && "$canary" -le 130 && "$total" == "199" ]]; then S7=true; else S7=false; fi
record "S7.canary_distribution" "$S7" 0 0 "" "" 0 "canary=$canary active=$active total=$total want_canary=70..130"

# S8 user 42 白名单
b=$(step "S8.user42_whitelist" 200 0 "$MR/v1/models/resolve?name=yolo_vin&user_id=42")
resolved=$(echo "$b" | extract data.resolved)
reason=$(echo "$b" | extract data.reason)
[[ "$resolved" == "canary" && "$reason" == "user_whitelist" ]] && S8=true || S8=false
record "S8b.whitelist_canary" "$S8" 0 0 "" "" 0 "resolved=$resolved reason=$reason"

# S9 同一 user_id 重复 resolve 应一致
r1=$(curl -s "$MR/v1/models/resolve?name=yolo_vin&user_id=99" | extract data.resolved)
r2=$(curl -s "$MR/v1/models/resolve?name=yolo_vin&user_id=99" | extract data.resolved)
r3=$(curl -s "$MR/v1/models/resolve?name=yolo_vin&user_id=99" | extract data.resolved)
[[ "$r1" == "$r2" && "$r2" == "$r3" ]] && S9=true || S9=false
record "S9.deterministic_resolve" "$S9" 0 0 "" "" 0 "user99: $r1 / $r2 / $r3"

# S10 activate v2
b=$(step "S10.activate_v2" 200 0 -X POST "$MR/admin/v1/models/$M2/activate" \
    -H 'X-Gomob-User-Id: 1' -H 'X-Gomob-Roles: admin')
v1_status=$(podman exec gomob-pg psql -U gomob -d gomob -tAc "SELECT status FROM models WHERE id=$M1")
[[ "$v1_status" == "archived" ]] && S10b=true || S10b=false
record "S10b.v1_archived" "$S10b" 0 0 "" "" 0 "v1_status=$v1_status"

# S11 切换后 resolve 全走 active
ok_active=0
for i in 1 2 3 7 13 42 99; do
    r=$(curl -s "$MR/v1/models/resolve?name=yolo_vin&user_id=$i")
    resolved=$(echo "$r" | extract data.resolved)
    ver=$(echo "$r" | extract data.model.version)
    if [[ "$resolved" == "active" && "$ver" == "v1.1.0" ]]; then
        ok_active=$((ok_active+1))
    fi
done
[[ "$ok_active" == "7" ]] && S11=true || S11=false
record "S11.all_route_to_v2" "$S11" 0 0 "" "" 0 "active_hits=$ok_active/7"

# S12 NATS 事件 ≥ 3
sleep 0.5
events=$(grep -c '^{' "$OUTPUT_DIR/nats-events.log" || true)
[[ "$events" -ge 3 ]] && S12=true || S12=false
record "S12.nats_events_count" "$S12" 0 0 "" "" 0 "events=$events want>=3"

# S13 archive v2 → 再激活归档版本应 40401
b=$(step "S13a.archive_active" 200 0 -X POST "$MR/admin/v1/models/$M2/archive" \
    -H 'X-Gomob-User-Id: 1' -H 'X-Gomob-Roles: admin')
b=$(step "S13b.reactivate_archived_40401" 409 40401 -X POST "$MR/admin/v1/models/$M2/activate" \
    -H 'X-Gomob-User-Id: 1' -H 'X-Gomob-Roles: admin')

log "3. 采样完成 → $RESULTS"
log "   下一步：python3 $(dirname "$0")/analyze.py $OUTPUT_DIR"

kill $SUB_PID 2>/dev/null
