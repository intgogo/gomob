#!/usr/bin/env bash
# depth_singlestream/run.sh — M1.6.6 BerxelNativeStack 真机持续流 harness
#
# 流程：
#   1. 编译 + 安装 debug APK 到 ANDROID_SERIAL
#   2. 冷启 app → 3D tab → 深度相机 → Sonix 调试 (auto stop Berxel)
#   3. 自动点「运行双流测试」按钮
#   4. 抓 UI 日志 + logcat gomob_native → 写 .dev/depth_singlestream/results.jsonl
#
# 配套：analyze.py 读结果给"是否回归"判定（持续时间、字节数、错误码分布）。
#
# 真实物理依赖：
#   - 测试机连 adb；ANDROID_SERIAL 指定（默认 vivo PD2324）
#   - 测试机插 P100R3 OTG，且 SonixDebugScreen 看得到 master + companion 节点
#   - 跑前可能需要物理重插 OTG（vivo Funtouch state machine 复位）
#
# 用法：
#   ANDROID_SERIAL=adb-10ADCQ0FLY001QP-pixTKf._adb-tls-connect._tcp ./run.sh

set -uo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
ADB="${ADB:-/opt/android-sdk/platform-tools/adb}"
SERIAL="${ANDROID_SERIAL:-adb-10ADCQ0FLY001QP-pixTKf._adb-tls-connect._tcp}"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/depth_singlestream}"
PKG="io.gomob.scan.debug"
APK="$PROJ_DIR/app/build/outputs/apk/debug/app-debug.apk"

mkdir -p "$OUTPUT_DIR"
RESULT="$OUTPUT_DIR/result.json"
LOG_RAW="$OUTPUT_DIR/logcat.txt"
UI_DUMP="$OUTPUT_DIR/ui_dump.xml"
PAGE_LOG="$OUTPUT_DIR/page_log.txt"

ADB_CMD="$ADB -s $SERIAL"

log() { echo "[$(date +%H:%M:%S)] $*"; }

ensure_apk() {
    if [ ! -f "$APK" ] || [ "$1" = "rebuild" ]; then
        log "build apk"
        (cd "$PROJ_DIR" && ./dev.sh build) | tail -5
    fi
}

install_apk() {
    log "install apk"
    local i
    for i in 1 2 3 4 5; do
        local out
        out=$($ADB_CMD install -r "$APK" 2>&1 | tail -1)
        if echo "$out" | grep -q Success; then
            log "  install OK"
            return 0
        fi
        log "  install failed ($out) — sleep 10s, may need manual 允许 on vivo"
        sleep 10
    done
    log "❌ install failed after 5 attempts"
    return 1
}

wait_two_berxel_nodes() {
    local attempts=0
    while [ $attempts -lt 20 ]; do
        local n
        n=$($ADB_CMD shell dumpsys usb 2>&1 | grep -c "manufacturer_name=Berxel")
        if [ "$n" -ge 4 ]; then
            log "  $n Berxel entries in dumpsys (2 nodes)"
            return 0
        fi
        attempts=$((attempts + 1))
        sleep 3
    done
    log "❌ 两个 Berxel 节点未到位（dumpsys 未见）— 物理重插 OTG"
    return 1
}

navigate_to_sonix() {
    log "navigate to Sonix 调试 screen"
    $ADB_CMD shell am force-stop "$PKG"
    sleep 2
    $ADB_CMD shell am start -n "$PKG/io.gomob.scan.MainActivity" >/dev/null
    sleep 5
    $ADB_CMD shell input tap 630 2667  # 3D tab
    sleep 2
    $ADB_CMD shell input tap 1152 245  # 深度相机 indicator
    sleep 3
    $ADB_CMD shell input swipe 600 2000 600 800 200; sleep 1
    $ADB_CMD shell input swipe 600 2000 600 800 200; sleep 1
    $ADB_CMD shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    $ADB_CMD shell cat /sdcard/ui.xml > "$UI_DUMP" 2>/dev/null
    local sy
    sy=$(python3 -c "
import re
with open('$UI_DUMP') as f: x=f.read()
for m in re.finditer(r'<node[^/]*text=\"Sonix ASIC 调试\"[^/]*/>', x):
    b = re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', m.group(0))
    if b: print((int(b.group(2))+int(b.group(4)))//2); break
")
    if [ -z "$sy" ]; then
        log "❌ Sonix 入口未找到"
        return 1
    fi
    $ADB_CMD shell input tap 600 "$sy"
    sleep 6
}

ensure_two_devices_in_ui() {
    local attempts=0
    while [ $attempts -lt 10 ]; do
        $ADB_CMD shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
        $ADB_CMD shell cat /sdcard/ui.xml > "$UI_DUMP" 2>/dev/null
        local n
        n=$(grep -c "0x0603\|0x3558" "$UI_DUMP")
        if [ "$n" -ge 2 ]; then
            log "  Sonix UI sees both devices"
            return 0
        fi
        local ref
        ref=$(python3 -c "
import re
with open('$UI_DUMP') as f: x=f.read()
for m in re.finditer(r'<node[^/]*text=\"刷新设备\"[^/]*/>', x):
    b = re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', m.group(0))
    if b: print(f'{(int(b.group(1))+int(b.group(3)))//2} {(int(b.group(2))+int(b.group(4)))//2}'); break
")
        [ -n "$ref" ] && $ADB_CMD shell input tap $ref
        sleep 3
        attempts=$((attempts + 1))
    done
    log "❌ Sonix UI 一直只看到 1 个设备 — vivo USB state degraded，物理重插 OTG"
    return 1
}

tap_dual_stream() {
    log "tap 运行双流测试"
    $ADB_CMD shell input swipe 600 2200 600 600 200; sleep 1
    $ADB_CMD shell input swipe 600 2200 600 600 200; sleep 1
    $ADB_CMD shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    $ADB_CMD shell cat /sdcard/ui.xml > "$UI_DUMP" 2>/dev/null
    local btn
    btn=$(python3 -c "
import re
with open('$UI_DUMP') as f: x=f.read()
for m in re.finditer(r'<node[^/]*text=\"运行双流测试[^\"]*\"[^/]*/>', x):
    b = re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', m.group(0))
    if b: print(f'{(int(b.group(1))+int(b.group(3)))//2} {(int(b.group(2))+int(b.group(4)))//2}'); break
")
    if [ -z "$btn" ]; then
        log "❌ 运行双流测试 按钮未找到"
        return 1
    fi
    $ADB_CMD logcat -c
    $ADB_CMD shell input tap $btn
    log "  tap fired at $(date +%H:%M:%S.%3N)"
}

wait_test_end() {
    local attempts=0
    while [ $attempts -lt 30 ]; do  # 90s 上限
        $ADB_CMD shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
        $ADB_CMD shell cat /sdcard/ui.xml > "$UI_DUMP" 2>/dev/null
        if grep -q "DUAL STREAM TEST 结束" "$UI_DUMP"; then
            log "  test ended"
            return 0
        fi
        sleep 3
        attempts=$((attempts + 1))
    done
    log "❌ 测试超时未结束 (90s)"
    return 1
}

collect_result() {
    $ADB_CMD logcat -d > "$LOG_RAW" 2>&1
    grep -oE 'text="\[[0-9][^"]+"' "$UI_DUMP" > "$PAGE_LOG"
    python3 "$(dirname "$0")/analyze.py" \
        --page-log "$PAGE_LOG" \
        --logcat "$LOG_RAW" \
        --out "$RESULT"
}

main() {
    ensure_apk "${1:-keep}"
    install_apk || exit 1
    wait_two_berxel_nodes || exit 1
    navigate_to_sonix || exit 1
    ensure_two_devices_in_ui || exit 1
    tap_dual_stream || exit 1
    wait_test_end || exit 1
    collect_result
    log "✅ result → $RESULT"
    cat "$RESULT"
}

main "$@"
