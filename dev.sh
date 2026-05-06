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
#   ./dev.sh emu-start    启动 gomob_test AVD（默认带 GUI 窗口走 DISPLAY=:1, -gpu host;
#                         头像式开发请求看 VNC 桌面里的 emulator 窗口。
#                         若需 headless（CI / 截图 only）传 HEADLESS=1）
#   ./dev.sh emu-stop     杀 emulator
#   ./dev.sh avd-create   创建 gomob_test AVD（首次）
#
#   ./dev.sh server doctor   服务端工具链自检（Go/podman/protoc/git）
#   ./dev.sh server up       起 dev 栈（PG/Redis/MinIO/NATS）
#                            默认 podman（容器名 gomob-*）；GOMOB_COMPOSE=docker 切回 docker compose
#   ./dev.sh server down     停 dev 栈（保数据卷）
#   ./dev.sh server ps       看 dev 栈状态
#   ./dev.sh server logs     跟 dev 栈日志
#   ./dev.sh server build    编译所有服务二进制到 server/.dev/bin/
#   ./dev.sh server test     跑 server/ 单元测试
#   ./dev.sh server run      单进程跑 devserver（聚合开发模式）
#   ./dev.sh server migrate  跑 PG migrations
#   ./dev.sh server proto    生成 .pb.go
#   ./dev.sh server clean    清理 server/.dev/bin/
#
# 部署运行时：开发 podman（gomob-* 已 named volume 持久），正式发布 docker compose
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
        out_dir="${OUTPUT_DIR:-$DEV_DIR/$name}"
        OUTPUT_DIR="$out_dir" "$runsh" "$@"
        # 跑完采样自动调分析器（CLAUDE.md "分析器必须输出可判定结论"）
        analyze="$PROJ_DIR/tests/harness/$name/analyze.py"
        if [[ -x "$analyze" || -f "$analyze" ]]; then
            echo
            echo "── analyze ──"
            python3 "$analyze" "$out_dir"
        fi
        ;;
    server)
        sub="${1:-doctor}"; shift || true
        # GOMOB_COMPOSE：podman（默认 / dev）或 docker（正式发布）
        # 开发栈用 podman 手动管 4 个 named-volume 持久容器（gomob-pg/redis/nats/minio），
        # 正式发布走 docker compose 起 dev_*（仍然以 configs/dev/docker-compose.yml 为模板）
        : "${GOMOB_COMPOSE:=podman}"
        PODMAN_CONTAINERS="gomob-pg gomob-redis gomob-nats gomob-minio"
        case "$sub" in
            doctor)  "$PROJ_DIR/server/scripts/server-doctor.sh" ;;
            up)
                if [[ "$GOMOB_COMPOSE" == "docker" ]]; then
                    (cd "$PROJ_DIR/server" && docker compose -f configs/dev/docker-compose.yml up -d)
                else
                    podman start $PODMAN_CONTAINERS 2>&1 | tail -10
                fi
                ;;
            down)
                if [[ "$GOMOB_COMPOSE" == "docker" ]]; then
                    (cd "$PROJ_DIR/server" && docker compose -f configs/dev/docker-compose.yml down)
                else
                    podman stop $PODMAN_CONTAINERS 2>&1 | tail -10
                fi
                ;;
            ps)
                if [[ "$GOMOB_COMPOSE" == "docker" ]]; then
                    (cd "$PROJ_DIR/server" && docker compose -f configs/dev/docker-compose.yml ps)
                else
                    podman ps -a --filter name=gomob- --format \
                        'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
                fi
                ;;
            logs)
                if [[ "$GOMOB_COMPOSE" == "docker" ]]; then
                    (cd "$PROJ_DIR/server" && docker compose -f configs/dev/docker-compose.yml logs -f "$@")
                else
                    podman logs -f "${1:-gomob-pg}"
                fi
                ;;
            build)   (cd "$PROJ_DIR/server" && make build) ;;
            test)    (cd "$PROJ_DIR/server" && go test ./...) ;;
            run)     (cd "$PROJ_DIR/server/cmd/devserver" && go run .) ;;
            migrate) (cd "$PROJ_DIR/server" && ./scripts/migrate.sh "${1:-up}") ;;
            proto)   (cd "$PROJ_DIR/server" && ./scripts/proto-gen.sh) ;;
            clean)   (cd "$PROJ_DIR/server" && rm -rf .dev/bin && echo "→ server/.dev/bin cleaned") ;;
            *) echo "用法: $0 server {doctor|up|down|ps|logs|build|test|run|migrate|proto|clean}"; exit 2 ;;
        esac
        ;;
    avd-create)
        : "${ANDROID_HOME:?}"
        echo no | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd \
            -n gomob_test -k "system-images;android-34;default;x86_64" -d pixel_7 --force
        ;;
    emu-start)
        : "${ANDROID_HOME:?}"
        # 关键：必须 -gpu host + DISPLAY=:1 走 NVIDIA / VNC 桌面
        # 详见 docs/agent-memory/finding_emulator_setup_2026-05-04.md +
        # docs/agent-memory/feedback_vnc_remote_dev.md
        # 默认带 GUI 窗口（用户走 TigerVNC 远程, 需在 :1 桌面看到 emulator）
        # 设 HEADLESS=1 可切回无窗口（CI / 截图脚本场景）
        WIN_FLAG=""
        [[ "${HEADLESS:-0}" == "1" ]] && WIN_FLAG="-no-window"
        DISPLAY="${DISPLAY:-:1}" setsid "$ANDROID_HOME/emulator/emulator" -avd gomob_test \
            $WIN_FLAG -no-audio -no-snapshot -no-boot-anim \
            -gpu host -accel on -port 5556 \
            < /dev/null > "$DEV_DIR/emulator.log" 2>&1 & disown
        echo "emulator started (log: $DEV_DIR/emulator.log)"
        [[ -z "$WIN_FLAG" ]] && echo "GUI 模式 — 在 VNC :1 桌面里能看到 emulator 窗口" \
            || echo "headless 模式 (HEADLESS=1)"
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
