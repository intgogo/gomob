#!/bin/bash
# dev.sh — 开发主入口
#
# 子命令:
#   ./dev.sh doctor       自检环境（Java / SDK / NDK / CMake / adb）
#   ./dev.sh build        编译 debug APK（:app:assembleDebug）
#   ./dev.sh release      编译 release APK
#   ./dev.sh install      推到当前已连接设备（:app:installDebug）
#   ./dev.sh run          install + 启动 MainActivity
#   ./dev.sh test         跑全部单元测试（:test）
#   ./dev.sh ci           简化 CI 链路：lint + test + assemble
#   ./dev.sh clean        清理 build / .dev/
#   ./dev.sh log          实时跟 logcat（仅 gomob.* / gomob_native）
#   ./dev.sh shot <name>  截图当前屏幕到 .dev/screenshots/<name>.png
#   ./dev.sh adb-wifi ... 转发到 scripts/adb-wifi.sh
#   ./dev.sh harness <名> 跑指定 harness（tests/harness/<名>/run.sh）
#
# 环境变量:
#   ANDROID_HOME  默认 /opt/android-sdk 或 ~/Android/Sdk
#   ADB_DEVICE    指定 adb 目标（默认随便挑一个）

set -euo pipefail

PROJ_DIR="$(cd "$(dirname "$0")" && pwd)"
DEV_DIR="$PROJ_DIR/.dev"
mkdir -p "$DEV_DIR" "$DEV_DIR/screenshots"

if [[ -z "${ANDROID_HOME:-}" ]]; then
    if [[ -d "/opt/android-sdk" ]]; then
        export ANDROID_HOME=/opt/android-sdk
    elif [[ -d "$HOME/Android/Sdk" ]]; then
        export ANDROID_HOME="$HOME/Android/Sdk"
    fi
fi
export PATH="${ANDROID_HOME:-}/platform-tools:${ANDROID_HOME:-}/cmdline-tools/latest/bin:$PATH"

GRADLEW="$PROJ_DIR/gradlew"
[[ -x "$GRADLEW" ]] || { echo "gradlew 缺失；先跑 gradle wrapper"; exit 1; }

cmd="${1:-doctor}"; shift || true

doctor() {
    echo "── doctor ──"
    java -version 2>&1 | head -1
    command -v javac >/dev/null && echo "javac: $(javac -version 2>&1)" || echo "javac: 缺"
    [[ -d "${ANDROID_HOME:-}" ]] && echo "ANDROID_HOME: $ANDROID_HOME" || echo "ANDROID_HOME: 未设置"
    command -v adb >/dev/null && echo "adb: $(adb version | head -1)" || echo "adb: 缺"
    [[ -d "$ANDROID_HOME/ndk" ]] && echo "NDK: $(ls "$ANDROID_HOME/ndk")" || echo "NDK: 缺"
    [[ -d "$ANDROID_HOME/cmake" ]] && echo "CMake: $(ls "$ANDROID_HOME/cmake")" || echo "CMake: 缺"
    "$GRADLEW" --version 2>/dev/null | grep -E '^(Gradle|Kotlin|JVM)'
    echo "── 详情/补装 ──"
    echo "  ./scripts/ensure-android-sdk.sh --install"
}

case "$cmd" in
    doctor)
        doctor
        ;;
    build)
        "$GRADLEW" :app:assembleDebug "$@" 2>&1 | tee "$DEV_DIR/build.log"
        ;;
    release)
        "$GRADLEW" :app:assembleRelease "$@" 2>&1 | tee "$DEV_DIR/release.log"
        ;;
    install)
        "$GRADLEW" :app:installDebug "$@" 2>&1 | tee "$DEV_DIR/install.log"
        ;;
    run)
        "$GRADLEW" :app:installDebug 2>&1 | tee "$DEV_DIR/install.log"
        adb ${ADB_DEVICE:+-s "$ADB_DEVICE"} shell am start -n io.gomob.scan.debug/io.gomob.scan.MainActivity
        ;;
    test)
        "$GRADLEW" test "$@" 2>&1 | tee "$DEV_DIR/test.log"
        ;;
    ci)
        "$GRADLEW" lint test assembleDebug "$@" 2>&1 | tee "$DEV_DIR/ci.log"
        ;;
    clean)
        "$GRADLEW" clean
        rm -rf "$DEV_DIR"/*.log "$DEV_DIR/screenshots"
        ;;
    log)
        adb ${ADB_DEVICE:+-s "$ADB_DEVICE"} logcat -v time \
            'gomob:*' 'gomob_native:*' 'AndroidRuntime:E' 'System.err:W' '*:S' \
            | tee "$DEV_DIR/logcat.log"
        ;;
    shot)
        name="${1:-screen}"
        out="$DEV_DIR/screenshots/${name}.png"
        adb ${ADB_DEVICE:+-s "$ADB_DEVICE"} exec-out screencap -p > "$out"
        echo "→ $out"
        ;;
    adb-wifi)
        exec "$PROJ_DIR/scripts/adb-wifi.sh" "$@"
        ;;
    harness)
        name="${1:-}"
        [[ -z "$name" ]] && { echo "用法: $0 harness <名>"; exit 2; }
        runsh="$PROJ_DIR/tests/harness/$name/run.sh"
        [[ -x "$runsh" ]] || { echo "harness 不存在: $runsh"; exit 2; }
        shift
        OUTPUT_DIR="${OUTPUT_DIR:-$DEV_DIR/$name}" "$runsh" "$@"
        ;;
    *)
        sed -n '2,21p' "$0"
        exit 2
        ;;
esac
