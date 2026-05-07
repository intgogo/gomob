#!/bin/bash
# logs_upload/run.sh — M3 端侧日志同步全链路 harness
#
# 场景：
#   S1  起 cmd/auth + cmd/gateway + cmd/api（带 GOMOB_LOG_UPLOAD_DIR=$OUTPUT_DIR/logs/）
#   S2  注册 + 登录拿 access token
#   S3  curl POST /v1/logs/upload 一批 5 条 → 期 200 accepted=5
#   S4  验证 ${OUTPUT_DIR}/logs/<user_id>/<today>.jsonl 出现 5 行，每行可解析 JSON 含 user_id + recv_ms
#   S5  无 token POST → 期 401 (gateway 拦)
#   S6  POST 空 entries → 期 40111
#   S7  POST 单条缺字段 → 期 10001
#   S8  POST 超大 batch (501 条) → 期 40111
#
# 输出 $OUTPUT_DIR/results.jsonl；analyze.py 给最终 PASS/FAIL。

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVER_DIR="$PROJ_DIR/server"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/logs_upload}"
LOG_ROOT="$OUTPUT_DIR/logs"
mkdir -p "$OUTPUT_DIR" "$LOG_ROOT"
RESULTS="$OUTPUT_DIR/results.jsonl"
: > "$RESULTS"

GATEWAY=http://127.0.0.1:18808
AUTH=http://127.0.0.1:18082
API=http://127.0.0.1:18080

log() { printf "[%s] %s\n" "$(date +%H:%M:%S)" "$*"; }

# ─── 起后端 ────────────────────────────────────────────────────────────────
cd "$SERVER_DIR"
log "构建二进制"
go build -o "$OUTPUT_DIR/bin/auth"    ./cmd/auth      || { echo "build auth fail"; exit 1; }
go build -o "$OUTPUT_DIR/bin/api"     ./cmd/api       || { echo "build api fail"; exit 1; }
go build -o "$OUTPUT_DIR/bin/gateway" ./cmd/gateway   || { echo "build gateway fail"; exit 1; }

# 杀残留
pkill -f "$OUTPUT_DIR/bin/auth"    2>/dev/null || true
pkill -f "$OUTPUT_DIR/bin/api"     2>/dev/null || true
pkill -f "$OUTPUT_DIR/bin/gateway" 2>/dev/null || true
sleep 0.5

log "启动 auth :18082"
GOMOB_AUTH_HTTP_ADDR=:18082 "$OUTPUT_DIR/bin/auth" \
    > "$OUTPUT_DIR/auth.log" 2>&1 &
AUTH_PID=$!

log "启动 api :18080 (LOG_UPLOAD_DIR=$LOG_ROOT)"
GOMOB_API_HTTP_ADDR=:18080 GOMOB_LOG_UPLOAD_DIR="$LOG_ROOT" \
    GOMOB_CATALOG_TARGET= GOMOB_VINREF_TARGET= GOMOB_SHAPEREF_TARGET= \
    "$OUTPUT_DIR/bin/api" > "$OUTPUT_DIR/api.log" 2>&1 &
API_PID=$!

log "启动 gateway :18808"
GOMOB_GATEWAY_HTTP_ADDR=:18808 "$OUTPUT_DIR/bin/gateway" \
    > "$OUTPUT_DIR/gateway.log" 2>&1 &
GATEWAY_PID=$!

cleanup() {
    log "清理 (auth=$AUTH_PID api=$API_PID gateway=$GATEWAY_PID)"
    kill $AUTH_PID $API_PID $GATEWAY_PID 2>/dev/null || true
    wait 2>/dev/null || true
}
trap cleanup EXIT

# 等服务起来
for i in $(seq 1 20); do
    if curl -fs $AUTH/healthz >/dev/null 2>&1 && \
       curl -fs $API/healthz >/dev/null 2>&1 && \
       curl -fs $GATEWAY/healthz >/dev/null 2>&1; then
        log "三服务 ready"
        break
    fi
    sleep 0.3
done

record() {
    local scenario=$1 ok=$2 note=${3:-}
    python3 -c "
import json
print(json.dumps({'scenario': '$scenario', 'ok': '$ok' == 'true', 'note': '''$note'''}))
" >> "$RESULTS"
}

# ─── S1-S2 注册 + 登录 ────────────────────────────────────────────────────────
USER="logsync_$(date +%s)_$$"
PASS="Test123!"

log "S2 注册"
REG=$(curl -s -X POST "$GATEWAY/v1/auth/register" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USER\",\"password\":\"$PASS\",\"real_name\":\"日志同步测试\",\"employee_id\":\"E$(date +%s%N | head -c 8)\"}")
USER_ID=$(echo "$REG" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('user_id',''))")
[[ -n "$USER_ID" ]] || { record S2_register false "无 user_id: $REG"; cat "$RESULTS"; exit 1; }
record S2_register true "user_id=$USER_ID"

log "S2 登录"
LOGIN=$(curl -s -X POST "$GATEWAY/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}")
ACCESS=$(echo "$LOGIN" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('access_token',''))")
[[ -n "$ACCESS" ]] || { record S2_login false "无 access: $LOGIN"; cat "$RESULTS"; exit 1; }
record S2_login true "access_token len=${#ACCESS}"

AUTH_H="Authorization: Bearer $ACCESS"

# ─── S3 上传 5 条 ───────────────────────────────────────────────────────────
log "S3 POST /v1/logs/upload 5 条"
PAYLOAD=$(python3 -c "
import json, time
ts = int(time.time() * 1000)
entries = [
    {'ts_ms': ts+i, 'level':'I','tag':'gomob_native','msg':f'Ingest #{i}'} for i in range(5)
]
print(json.dumps({'entries': entries}))
")
RESP=$(curl -s -X POST "$GATEWAY/v1/logs/upload" \
    -H 'Content-Type: application/json' -H "$AUTH_H" \
    -d "$PAYLOAD")
ACCEPTED=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('accepted',0))")
[[ "$ACCEPTED" == "5" ]] && record S3_upload_5 true "accepted=5" || record S3_upload_5 false "resp=$RESP"

# ─── S4 验证文件落盘 ────────────────────────────────────────────────────────
TODAY=$(date -u +%Y-%m-%d)
JSONL="$LOG_ROOT/$USER_ID/$TODAY.jsonl"
if [[ -f "$JSONL" ]]; then
    LINES=$(wc -l < "$JSONL")
    log "S4 jsonl 落盘 $JSONL (lines=$LINES)"
    [[ "$LINES" == "5" ]] && record S4_jsonl_5lines true "lines=5" || record S4_jsonl_5lines false "lines=$LINES"
    # 验证字段
    FIRST=$(head -1 "$JSONL")
    HAS_USER=$(echo "$FIRST" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('user_id', 0))")
    HAS_RECV=$(echo "$FIRST" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('recv_ms', 0))")
    [[ "$HAS_USER" == "$USER_ID" && "$HAS_RECV" -gt 0 ]] && \
        record S4_jsonl_fields true "user_id=$HAS_USER recv_ms ok" || \
        record S4_jsonl_fields false "user=$HAS_USER recv=$HAS_RECV"
else
    record S4_jsonl_5lines false "$JSONL 不存在"
fi

# ─── S5 无 token → 401 ─────────────────────────────────────────────────────
HTTP=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/v1/logs/upload" \
    -H 'Content-Type: application/json' \
    -d "$PAYLOAD")
[[ "$HTTP" == "401" ]] && record S5_no_token_401 true "" || record S5_no_token_401 false "got $HTTP"

# ─── S6 空 entries → 40111 ──────────────────────────────────────────────────
RESP=$(curl -s -X POST "$GATEWAY/v1/logs/upload" \
    -H 'Content-Type: application/json' -H "$AUTH_H" \
    -d '{"entries":[]}')
CODE=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('code', 0))")
[[ "$CODE" == "40111" ]] && record S6_empty_40111 true "" || record S6_empty_40111 false "code=$CODE resp=$RESP"

# ─── S7 缺字段 → 10001 ──────────────────────────────────────────────────────
RESP=$(curl -s -X POST "$GATEWAY/v1/logs/upload" \
    -H 'Content-Type: application/json' -H "$AUTH_H" \
    -d '{"entries":[{"ts_ms":1,"level":"I","tag":"","msg":""}]}')
CODE=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('code', 0))")
[[ "$CODE" == "10001" ]] && record S7_field_10001 true "" || record S7_field_10001 false "code=$CODE resp=$RESP"

# ─── S8 超量 → 40111 ──────────────────────────────────────────────────────
BIG=$(python3 -c "
import json, time
ts = int(time.time()*1000)
print(json.dumps({'entries': [{'ts_ms':ts,'level':'I','tag':'t','msg':'m'} for _ in range(501)]}))
")
RESP=$(curl -s -X POST "$GATEWAY/v1/logs/upload" \
    -H 'Content-Type: application/json' -H "$AUTH_H" \
    -d "$BIG")
CODE=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('code', 0))")
[[ "$CODE" == "40111" ]] && record S8_oversize_40111 true "" || record S8_oversize_40111 false "code=$CODE resp=$RESP"

log "harness done -> $RESULTS"
cat "$RESULTS"
