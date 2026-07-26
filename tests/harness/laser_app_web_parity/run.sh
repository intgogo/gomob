#!/usr/bin/env bash
# laser_app_web_parity：服务端 canonical 契约 + 真实 Web/App 映射一致性守门。
#
# Go 先生成同一份真实服务端终态 payload；Web 状态机和 Android DTO/repository mapper 分别消费它，
# 各自把实际领域状态写成 PARITY_JSON，再由 analyze.py 做 canonical 深比较。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
OUT="${OUTPUT_DIR:-$ROOT/.dev/laser_app_web_parity}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

SERVER_LOG="$OUT/server-contract.log"
WEB_LOG="$OUT/web-contract.log"
APP_LOG="$OUT/app-contract.log"
PARITY_LOG="$OUT/parity.log"
FIXTURE="$OUT/server-scan-payload.json"
WEB_OUTPUT="$OUT/web-parity.json"
APP_OUTPUT="$OUT/app-parity.json"
rm -f "$FIXTURE" "$WEB_OUTPUT" "$APP_OUTPUT"
: >"$SERVER_LOG"
: >"$PARITY_LOG"
set +e
(
    cd "$ROOT/server"
    LD_LIBRARY_PATH=/usr/local/lib:/usr/local/lib64:/usr/local/onnxruntime/lib \
        go test ./internal/laser \
        -run 'ClientParity|DownloadMeasuredCloudStrictlyUsesMeasuredObjectKey' -count=1 -v
) 2>&1 | tee -a "$SERVER_LOG"
test_status=${PIPESTATUS[0]}
set -e

if [[ $test_status -ne 0 ]]; then
    echo "异常：服务端 canonical/PCD 契约测试失败，见 $SERVER_LOG" >&2
    exit "$test_status"
fi
PYTHONDONTWRITEBYTECODE=1 python3 - "$SERVER_LOG" "$FIXTURE" <<'PY'
import json
import sys
from pathlib import Path

prefix = "SERVER_SCAN_PAYLOAD:"
lines = [
    line.split(prefix, 1)[1].strip()
    for line in Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace").splitlines()
    if prefix in line
]
if len(lines) != 1:
    raise SystemExit(f"需要唯一 SERVER_SCAN_PAYLOAD，实际 {len(lines)} 条")
payload = json.loads(lines[0])
Path(sys.argv[2]).write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
PY
echo "CLIENT_CONTRACT:measured_pcd:PASS" >>"$PARITY_LOG"

set +e
GOMOB_PARITY_FIXTURE="$FIXTURE" GOMOB_PARITY_OUTPUT="$WEB_OUTPUT" \
    node --test "$ROOT/web/laser-station/app_contract_test.js" \
    2>&1 | tee "$WEB_LOG"
web_status=${PIPESTATUS[0]}
set -e
if [[ $web_status -ne 0 ]]; then
    echo "异常：Web 实际状态机/结果映射测试失败，见 $WEB_LOG" >&2
    exit "$web_status"
fi
if [[ ! -s "$WEB_OUTPUT" ]]; then
    echo "异常：Web 契约测试未产出真实 parity 结果，见 $WEB_LOG" >&2
    exit 1
fi
printf 'PARITY_JSON: %s\n' "$(<"$WEB_OUTPUT")" >>"$PARITY_LOG"
echo "CLIENT_CONTRACT:web:PASS" >>"$PARITY_LOG"

set +e
(
    cd "$ROOT"
    gradle_args=()
    if [[ "${GOMOB_GRADLE_OFFLINE:-0}" == "1" ]]; then
        gradle_args+=(--offline)
    fi
    GOMOB_PARITY_FIXTURE="$FIXTURE" GOMOB_PARITY_OUTPUT="$APP_OUTPUT" \
        ./gradlew "${gradle_args[@]}" :core:data:testDebugUnitTest \
        --tests io.gomob.data.scan.LaserScanResultMappingTest --rerun-tasks
) 2>&1 | tee "$APP_LOG"
app_status=${PIPESTATUS[0]}
set -e
if [[ $app_status -ne 0 ]]; then
    echo "异常：App 实际 DTO/repository 映射测试失败，见 $APP_LOG" >&2
    exit "$app_status"
fi
if [[ ! -s "$APP_OUTPUT" ]]; then
    echo "异常：App 契约测试未产出真实 parity 结果，见 $APP_LOG" >&2
    exit 1
fi
printf 'PARITY_JSON: %s\n' "$(<"$APP_OUTPUT")" >>"$PARITY_LOG"
echo "CLIENT_CONTRACT:app:PASS" >>"$PARITY_LOG"

PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
    -s "$ROOT/tests/harness/laser_app_web_parity" -p 'test_analyze.py' \
    >"$OUT/analyzer-test.log" 2>&1
echo "采样完成 → $PARITY_LOG"
