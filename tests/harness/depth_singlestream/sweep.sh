#!/usr/bin/env bash
# depth_singlestream/sweep.sh — 跑多组 keepalive interval 参数找 vivo 稳定 sweet spot
#
# 前置条件：
#   1. ANDROID_SERIAL 测试机插 P100R3 OTG（最好刚物理重插过一次让 vivo USB state clean）
#   2. App 已经 install + 用户已经走过 SonixDebugScreen 一次（让 master USB 权限缓存住）
#   3. App 当前在 Sonix 调试页（脚本只发广播触发测试，不导航 UI）
#
# 行为：
#   - 对每个 kaMs 值发 HARNESS_RUN 广播
#   - 等 (durMs + 5s) 让测试跑完
#   - 抓 page log 写到 .dev/depth_singlestream/sweep_<kaMs>.json
#   - 收尾跑 analyze.py 综合判定
#
# 用法：
#   ANDROID_SERIAL=adb-... ./sweep.sh
#   或 ./sweep.sh 5 20 50 100 200  ← 自定义 kaMs 序列

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
ADB="${ADB:-/opt/android-sdk/platform-tools/adb}"
SERIAL="${ANDROID_SERIAL:-adb-10ADCQ0FLY001QP-pixTKf._adb-tls-connect._tcp}"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/depth_singlestream}"
PKG="io.gomob.scan.debug"
ACTION="io.gomob.feature.scan3d.SONIX_DEBUG_HARNESS_RUN"
DUR_MS="${DUR_MS:-5000}"
MASTER_N="${MASTER_N:-20}"

mkdir -p "$OUTPUT_DIR"
SUMMARY="$OUTPUT_DIR/sweep_summary.tsv"
echo -e "kaMs\tstatus\tduration_ms\tdata_reads\ttotal_bytes\tframes\tfirst_err\tka_count" > "$SUMMARY"

ADB_CMD="$ADB -s $SERIAL"
KA_LIST=("${@:-5 20 50 100 200}")
# 拆 1 个参数为多个
if [ ${#KA_LIST[@]} -eq 1 ]; then
    read -ra KA_LIST <<< "${KA_LIST[0]}"
fi

log() { echo "[$(date +%H:%M:%S)] $*"; }

verify_on_sonix_page() {
    local top
    top=$($ADB_CMD shell dumpsys activity top 2>&1 | grep "ACTIVITY io.gomob" | head -1)
    if ! echo "$top" | grep -q MainActivity; then
        log "❌ App 不在 MainActivity 上，请先手动打开 app 并导航到 Sonix 调试页"
        return 1
    fi
    return 0
}

run_one() {
    local ka_ms="$1"
    log "=== sweep kaMs=$ka_ms durMs=$DUR_MS ==="
    $ADB_CMD logcat -c
    $ADB_CMD shell am broadcast -a "$ACTION" --el kaMs "$ka_ms" --el durMs "$DUR_MS" --ei masterN "$MASTER_N" 2>&1 | tail -1

    # 等 (durMs + open/close 时间)
    local wait_ms=$((DUR_MS + 8000))
    log "  等 ${wait_ms}ms"
    sleep $((wait_ms / 1000))

    # 抓 page log + logcat
    local log_file="$OUTPUT_DIR/sweep_ka${ka_ms}.log"
    local result_file="$OUTPUT_DIR/sweep_ka${ka_ms}.json"
    $ADB_CMD shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    $ADB_CMD shell cat /sdcard/ui.xml 2>/dev/null > "$OUTPUT_DIR/ui_ka${ka_ms}.xml"
    grep -oE 'text="\[[0-9][^"]+"' "$OUTPUT_DIR/ui_ka${ka_ms}.xml" > "${log_file}.page"
    $ADB_CMD logcat -d > "${log_file}.logcat" 2>&1

    python3 "$(dirname "$0")/analyze.py" \
        --page-log "${log_file}.page" \
        --logcat "${log_file}.logcat" \
        --out "$result_file" || true

    # 抽统计写 summary
    local status duration reads bytes_ first_err ka_count
    status=$(python3 -c "import json; print(json.load(open('$result_file'))['status'])" 2>/dev/null || echo "?")
    duration=$(python3 -c "import json; print(json.load(open('$result_file'))['duration_ms'])" 2>/dev/null || echo "0")
    reads=$(python3 -c "import json; print(json.load(open('$result_file'))['data_reads'])" 2>/dev/null || echo "0")
    bytes_=$(python3 -c "import json; print(json.load(open('$result_file'))['total_bytes'])" 2>/dev/null || echo "0")
    first_err=$(python3 -c "import json; print(json.load(open('$result_file'))['first_error_code'])" 2>/dev/null || echo "?")
    ka_count=$(python3 -c "import json; print(json.load(open('$result_file'))['keepalive_ka_count'])" 2>/dev/null || echo "0")
    # frames 从 page log 抓 (NativeStack 路径写 "frames=N")
    local frames
    frames=$(grep -oE 'frames=[0-9]+' "${log_file}.page" | head -1 | sed 's/frames=//')
    [ -z "$frames" ] && frames="?"

    echo -e "$ka_ms\t$status\t$duration\t$reads\t$bytes_\t$frames\t$first_err\t$ka_count" >> "$SUMMARY"
    log "  → status=$status reads=$reads bytes=$bytes_ frames=$frames first_err=$first_err"

    # 物理拔插提示（每 3 次扫一次让 vivo state 不要太退化）
}

main() {
    verify_on_sonix_page || exit 1
    log "sweep kaMs: ${KA_LIST[*]}"
    log "durMs per test: $DUR_MS"

    for ka in "${KA_LIST[@]}"; do
        run_one "$ka"
        # 间隔 5s 让 vivo USB 调度恢复
        sleep 5
    done

    log "============================================"
    log "sweep done; summary at $SUMMARY"
    echo ""
    column -t -s$'\t' < "$SUMMARY"
}

main "$@"
