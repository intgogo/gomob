#!/bin/bash
# inspection_lifecycle/run.sh — M-S2 业务主域全链路 harness 采样器。
#
# 场景（一条完整查验从创建到关闭）：
#   S1 注册 inspector + 注册 reviewer（SQL 改 role）
#   S2 inspector 登录、创建查验
#   S3 created → scanning
#   S4 init upload + 3 chunk + complete（24 MB 文件，8 MB × 3）
#   S5 GET /v1/inspections/:id/assets 列表
#   S6 GET /v1/assets/:id/url 拿签名 URL，下载并 sha256 校验
#   S7 写预审结果 → preliminary
#   S8 提交复核 → pending_review
#   S9 admin 派发 review（SQL）
#   S10 reviewer 拉 pending、decide=correct
#   S11 重复 decide → 40401
#   S12 inspector close → closed
#   S13 audit_log 至少 6 条事件（create/start/upload_complete/update_result/submit/review.decide/close）

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVER_DIR="$PROJ_DIR/server"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/inspection_lifecycle}"
mkdir -p "$OUTPUT_DIR"
RESULTS="$OUTPUT_DIR/results.jsonl"
: > "$RESULTS"

GATEWAY=http://127.0.0.1:18808

log() { printf "[%s] %s\n" "$(date +%H:%M:%S)" "$*"; }

record() {
    local scenario=$1 ok=$2 http=$3 ex_http=$4 code=$5 ex_code=$6 lat_ms=$7 note=${8:-}
    python3 -c "
import json
print(json.dumps({
    'scenario': '$scenario',
    'ok': '$ok' == 'true',
    'http_code': int('$http' or 0),
    'expected_http': int('$ex_http' or 0),
    'code': int('$code') if '$code' != '' else None,
    'expected_code': int('$ex_code') if '$ex_code' != '' else None,
    'latency_ms': float('$lat_ms' or 0),
    'note': '''$note''',
}))
" >> "$RESULTS"
}

# step  name  expected_http  expected_code  curl_args...
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
    d = json.load(sys.stdin)
    c = d.get("code")
    print(c if c is not None else "")
except Exception:
    print("")
')
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
    d = json.load(sys.stdin)
    keys = sys.argv[1].split('.')
    cur = d
    for k in keys: cur = cur[k]
    print(cur)
except Exception:
    pass
" "$1"
}

# ==========================================================================
# 0. 前置
# ==========================================================================
log "0. 前置：容器健康"
need() { podman ps --format '{{.Names}}' | grep -qx "$1" || { log "缺 $1"; exit 2; }; }
need gomob-pg
need gomob-redis
need gomob-minio
podman exec gomob-redis redis-cli FLUSHDB > /dev/null

# ==========================================================================
# 1. 编译 + 启动
# ==========================================================================
log "1. 编译 + 启动 4 服务"
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-auth    ./cmd/auth)    || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-api     ./cmd/api)     || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-asset   ./cmd/asset)   || exit 3
(cd "$SERVER_DIR" && go build -o .dev/bin/gomob-gateway ./cmd/gateway) || exit 3

GOMOB_AUTH_HTTP_ADDR=:18082 GOMOB_AUTH_DEV_AUTOACTIVATE=true \
    "$SERVER_DIR/.dev/bin/gomob-auth"    > "$OUTPUT_DIR/auth.log"    2>&1 &
AUTH_PID=$!
GOMOB_API_HTTP_ADDR=:18080 \
    "$SERVER_DIR/.dev/bin/gomob-api"     > "$OUTPUT_DIR/api.log"     2>&1 &
API_PID=$!
GOMOB_ASSET_HTTP_ADDR=:18083 \
    "$SERVER_DIR/.dev/bin/gomob-asset"   > "$OUTPUT_DIR/asset.log"   2>&1 &
ASSET_PID=$!
GOMOB_GATEWAY_ADDR=:18808 GOMOB_REDIS_ADDR=127.0.0.1:6379 GOMOB_RATE_LIMIT=100000 \
    "$SERVER_DIR/.dev/bin/gomob-gateway" > "$OUTPUT_DIR/gateway.log" 2>&1 &
GW_PID=$!
trap "kill $AUTH_PID $API_PID $ASSET_PID $GW_PID 2>/dev/null; wait 2>/dev/null" EXIT
sleep 1

# 探活
hc=$(curl -s -o /dev/null -w '%{http_code}' "$GATEWAY/healthz")
[[ "$hc" == "200" ]] || { log "✗ gateway healthz $hc"; exit 4; }

# ==========================================================================
# 2. 跑场景
# ==========================================================================
log "2. 跑场景"
SUFFIX=$$_$(date +%s%N)
PASS=pwd-1234

# S1 注册 inspector + reviewer
INS_USER="insp_$SUFFIX"
REV_USER="rev_$SUFFIX"
b=$(step "S1a.register_inspector" 200 0 -X POST "$GATEWAY/v1/auth/register" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$INS_USER\",\"password\":\"$PASS\",\"real_name\":\"Insp\",\"employee_id\":\"EI$SUFFIX\"}")
b=$(step "S1b.register_reviewer" 200 0 -X POST "$GATEWAY/v1/auth/register" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$REV_USER\",\"password\":\"$PASS\",\"real_name\":\"Rev\",\"employee_id\":\"ER$SUFFIX\"}")
podman exec gomob-pg psql -U gomob -d gomob -c "UPDATE users SET role='reviewer' WHERE username='$REV_USER'" > /dev/null

# S2 登录 + 创建查验
b=$(step "S2a.login_inspector" 200 0 -X POST "$GATEWAY/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$INS_USER\",\"password\":\"$PASS\"}")
INS_TOK=$(echo "$b" | extract data.access_token)

b=$(step "S2b.login_reviewer" 200 0 -X POST "$GATEWAY/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$REV_USER\",\"password\":\"$PASS\"}")
REV_TOK=$(echo "$b" | extract data.access_token)
REV_ID=$(curl -s "$GATEWAY/v1/me" -H "Authorization: Bearer $REV_TOK" | extract data.id)

# 生成精确 17 字符 VIN：LH + 15 位（用 nanosec，不足补 0，溢出截断）
ns=$(date +%N)
ns=${ns}000000000   # 补到至少 9+ 字符
VIN="LH${ns:0:15}"
VIN=${VIN:0:17}
# 拿一个真实存在的 station_id（避免跨 harness 顺序导致 id 漂移）
STATION_ID=$(podman exec gomob-pg psql -U gomob -d gomob -tAc \
    "SELECT id FROM stations ORDER BY id LIMIT 1")
if [[ -z "$STATION_ID" ]]; then
    STATION_ID=$(podman exec gomob-pg psql -U gomob -d gomob -tAc \
        "INSERT INTO stations(name,region) VALUES('测试检测站','杭州') RETURNING id")
fi
b=$(step "S2c.create_inspection" 200 0 -X POST "$GATEWAY/v1/inspections" \
    -H "Authorization: Bearer $INS_TOK" -H 'Content-Type: application/json' \
    -d "{\"vin\":\"$VIN\",\"plate_no\":\"沪H\",\"brand\":\"奥迪\",\"station_id\":\"$STATION_ID\"}")
INS_ID=$(echo "$b" | extract data.id)
log "  inspection_id=$INS_ID"

# S3 start
b=$(step "S3.start_scanning" 200 0 -X POST "$GATEWAY/v1/inspections/$INS_ID/start" \
    -H "Authorization: Bearer $INS_TOK")

# S4 上传扫描（24 MB / 8 MB 三片）
log "  生成 24 MB 测试文件"
TMPFILE="$OUTPUT_DIR/test.bin"
dd if=/dev/urandom of=$TMPFILE bs=1M count=24 status=none
FILE_SHA=$(sha256sum $TMPFILE | awk '{print $1}')
FILE_SIZE=$(stat -c%s $TMPFILE)

b=$(step "S4a.upload_init" 200 0 -X POST "$GATEWAY/v1/assets/upload/init" \
    -H "Authorization: Bearer $INS_TOK" -H 'Content-Type: application/json' \
    -d "{\"inspection_id\":\"$INS_ID\",\"kind\":\"scan3d\",\"size_bytes\":$FILE_SIZE,\"sha256\":\"$FILE_SHA\",\"mime\":\"application/octet-stream\"}")
UPLOAD_ID=$(echo "$b" | extract data.upload_id)
CHUNK_SIZE=$(echo "$b" | extract data.chunk_size)

split -b $CHUNK_SIZE -d $TMPFILE "$OUTPUT_DIR/chunk-"
N=1; TOTAL=0
for f in "$OUTPUT_DIR"/chunk-*; do
    size=$(stat -c%s "$f")
    b=$(step "S4b.chunk_$N" 200 0 -X PUT "$GATEWAY/v1/assets/upload/$UPLOAD_ID/chunk/$N" \
        -H "Authorization: Bearer $INS_TOK" -H 'Content-Type: application/octet-stream' \
        -H "Content-Length: $size" --data-binary "@$f")
    N=$((N+1)); TOTAL=$((TOTAL+1))
done

b=$(step "S4c.upload_complete" 200 0 -X POST "$GATEWAY/v1/assets/upload/$UPLOAD_ID/complete" \
    -H "Authorization: Bearer $INS_TOK" -H 'Content-Type: application/json' \
    -d "{\"total_chunks\":$TOTAL}")
ASSET_ID=$(echo "$b" | extract data.asset_id)
DL_URL=$(echo "$b" | extract data.download_url)

# S5 列表
b=$(step "S5.list_assets" 200 0 "$GATEWAY/v1/inspections/$INS_ID/assets" \
    -H "Authorization: Bearer $INS_TOK")
asset_count=$(echo "$b" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)["data"]["items"]))')
[[ "$asset_count" == "1" ]] && S5OK=true || S5OK=false
record "S5b.list_count" "$S5OK" 0 0 "" "" 0 "got=$asset_count want=1"

# S6 签名 URL + 下载验 sha
b=$(step "S6a.presign" 200 0 "$GATEWAY/v1/assets/$ASSET_ID/url" \
    -H "Authorization: Bearer $INS_TOK")
EXP=$(echo "$b" | extract data.expires_in)
[[ "$EXP" == "300" ]] && S6E=true || S6E=false
record "S6b.expires_300" "$S6E" 0 0 "" "" 0 "got=$EXP want=300"

curl -s "$DL_URL" -o "$OUTPUT_DIR/dl.bin"
DL_SHA=$(sha256sum "$OUTPUT_DIR/dl.bin" | awk '{print $1}')
DL_SIZE=$(stat -c%s "$OUTPUT_DIR/dl.bin")
[[ "$DL_SHA" == "$FILE_SHA" && "$DL_SIZE" == "$FILE_SIZE" ]] && S6V=true || S6V=false
record "S6c.dl_sha_match" "$S6V" 0 0 "" "" 0 "size=$DL_SIZE sha_eq=$( [[ $DL_SHA == $FILE_SHA ]] && echo yes || echo no)"

# S7 写预审
b=$(step "S7.update_preliminary" 200 0 -X PATCH "$GATEWAY/v1/inspections/$INS_ID/result" \
    -H "Authorization: Bearer $INS_TOK" -H 'Content-Type: application/json' \
    -d '{"verdict":"warning","reasons":["车型代码异常"]}')

# S8 提交复核
b=$(step "S8.submit_review" 200 0 -X POST "$GATEWAY/v1/inspections/$INS_ID/submit" \
    -H "Authorization: Bearer $INS_TOK")

# S9 admin 派发 review
podman exec gomob-pg psql -U gomob -d gomob -c \
    "INSERT INTO reviews(inspection_id, reviewer_id, expire_at) VALUES ($INS_ID, $REV_ID, now() + interval '1 day')" > /dev/null
RV_ID=$(podman exec gomob-pg psql -U gomob -d gomob -tAc \
    "SELECT id FROM reviews WHERE inspection_id=$INS_ID ORDER BY id DESC LIMIT 1")

# S10 reviewer 拉 + decide
b=$(step "S10a.list_pending" 200 0 "$GATEWAY/v1/reviews?bucket=pending" \
    -H "Authorization: Bearer $REV_TOK")
b=$(step "S10b.decide_correct" 200 0 -X POST "$GATEWAY/v1/reviews/$RV_ID/decision" \
    -H "Authorization: Bearer $REV_TOK" -H 'Content-Type: application/json' \
    -d '{"decision":"correct","reason":"已核对"}')

# S11 重复 decide
b=$(step "S11.repeat_decide_conflict" 409 40401 -X POST "$GATEWAY/v1/reviews/$RV_ID/decision" \
    -H "Authorization: Bearer $REV_TOK" -H 'Content-Type: application/json' \
    -d '{"decision":"correct"}')

# S12 close
b=$(step "S12.close_inspection" 200 0 -X POST "$GATEWAY/v1/inspections/$INS_ID/close" \
    -H "Authorization: Bearer $INS_TOK")

# S13 audit_log >= 6 事件
n=$(podman exec gomob-pg psql -U gomob -d gomob -tAc \
    "SELECT count(*) FROM audit_log WHERE target = 'inspection:$INS_ID' OR target = 'review:$RV_ID' OR (action='asset.upload_complete' AND user_id=(SELECT id FROM users WHERE username='$INS_USER'))")
S13_NOTE="audit_count=$n want>=6"
if [[ $n -ge 6 ]]; then S13=true; else S13=false; fi
record "S13.audit_count" "$S13" 0 0 "" "" 0 "$S13_NOTE"

log "3. 采样完成 → $RESULTS"
log "   下一步：python3 $(dirname "$0")/analyze.py $OUTPUT_DIR"

# 不删测试文件，便于 analyze 复查
