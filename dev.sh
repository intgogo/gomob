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
#   ./dev.sh emu-start    headless 启动 gomob_test AVD（DISPLAY=:1, -gpu host）
#   ./dev.sh emu-stop     杀 emulator
#   ./dev.sh avd-create   创建 gomob_test AVD（首次）
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
    server)
        sub="${1:-doctor}"; shift || true
        case "$sub" in
            doctor) "$PROJ_DIR/server/scripts/ensure-go.sh" ;;
            up) (cd "$PROJ_DIR/server" && docker compose -f configs/dev/docker-compose.yml up -d) ;;
            down) (cd "$PROJ_DIR/server" && docker compose -f configs/dev/docker-compose.yml down) ;;
            build) (cd "$PROJ_DIR/server" && make build) ;;
            run) (cd "$PROJ_DIR/server/cmd/devserver" && go run .) ;;
            *) echo "用法: $0 server {doctor|up|down|build|run}"; exit 2 ;;
        esac
        ;;
    avd-create)
        : "${ANDROID_HOME:?}"
        echo no | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd \
            -n gomob_test -k "system-images;android-34;default;x86_64" -d pixel_7 --force
        ;;
    emu-start)
        : "${ANDROID_HOME:?}"
        # 关键：headless 必须用 -gpu host + DISPLAY=:1（详见 docs/agent-memory/finding_emulator_setup_2026-05-04.md）
        DISPLAY="${DISPLAY:-:1}" setsid "$ANDROID_HOME/emulator/emulator" -avd gomob_test \
            -no-window -no-audio -no-snapshot -no-boot-anim \
            -gpu host -accel on -port 5556 \
            < /dev/null > "$DEV_DIR/emulator.log" 2>&1 & disown
        echo "emulator started (log: $DEV_DIR/emulator.log)"
        echo "等 boot: until [ \"\$(adb -s emulator-5556 shell getprop sys.boot_completed)\" = 1 ]; do sleep 5; done"
        ;;
    emu-stop)
        # 注意: 不能用 pkill -f 'qemu-system' — 会匹配自身 bash 命令行把当前会话杀掉
        pkill -x qemu-system-x86_64-headless 2>/dev/null
        pkill -x qemu-system-x86_64 2>/dev/null
        adb kill-server >/dev/null 2>&1
        echo "emulator stopped"
        ;;
    *)
        sed -n '2,21p' "$0"
        exit 2
        ;;
esac
