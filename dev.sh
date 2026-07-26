#!/bin/bash
# dev.sh — 开发主入口
#
# 子命令:
#   ./dev.sh doctor       自检环境（Java / SDK / NDK / CMake / adb）
#   ./dev.sh build        编译 debug APK（:app:assembleDebug）
#   ./dev.sh release      编译 release APK
#   ./dev.sh install      编译并通过非流式 adb 静默安装到当前设备
#   ./dev.sh run          install + 启动 MainActivity
#   ./dev.sh up           一键启动开发服务栈 + ASR + 后台 devserver + adb reverse
#   ./dev.sh test         跑全部单元测试（:test）
#   ./dev.sh native-test  native host 测试自动门（5 个 host-test runner 真编译真跑真判）
#   ./dev.sh ci           简化 CI 链路：lint + test + assemble + native host 测试
#   ./dev.sh clean        清理 build / .dev/
#   ./dev.sh reverse      给当前 adb 设备配置本机 devserver / LiveKit 端口反向代理
#   ./dev.sh log          实时跟 logcat（仅 gomob.* / gomob_native）
#   ./dev.sh shot <name>  截图当前屏幕到 .dev/screenshots/<name>.png
#   ./dev.sh record <name> [seconds]
#                         高码率录屏到 .dev/app-recordings/<name>.mp4
#   ./dev.sh adb-wifi ... 转发到 scripts/adb-wifi.sh
#   ./dev.sh harness <名> 跑指定 harness（tests/harness/<名>/run.sh）
#   ./dev.sh emu-start    启动 gomob_test AVD（默认带 GUI 窗口走 DISPLAY=:1,
#                         -gpu host + SkiaVK/Vulkan;
#                         同时禁用本机不稳定的 netsim / packet streamer 路径。
#                         用户通过 VNC 桌面里的 emulator 窗口看 app。
#                         可用 EMULATOR_GPU_MODE 覆盖渲染器；headless 传 HEADLESS=1）
#   ./dev.sh emu-stop     杀 emulator
#   ./dev.sh avd-create   创建 gomob_test AVD（首次）
#
#   ./dev.sh server doctor   服务端工具链自检（Go/podman/protoc/git）
#   ./dev.sh server up       起容器栈（gomob-pg/redis/nats/minio/livekit）
#   ./dev.sh server down     停容器栈（保数据卷）
#   ./dev.sh server ps       看容器栈状态
#   ./dev.sh server logs     跟容器日志（默认 gomob-pg；可传 gomob-redis 等）
#   ./dev.sh server build    编译所有服务二进制到 server/.dev/bin/
#   ./dev.sh server test     跑 server/ 单元测试
#   ./dev.sh server run      单进程跑 devserver（先跑 migrations，默认接本地 LiveKit）
#   ./dev.sh server migrate  跑 PG migrations
#   ./dev.sh server proto    生成 .pb.go
#   ./dev.sh server clean    清理 server/.dev/bin/
#
# 容器运行时：统一 podman（OCI 镜像同时跑在 docker / containerd / k8s，无需双栈）
#
# 环境变量:
#   ANDROID_HOME  默认 /opt/android-sdk 或 ~/Android/Sdk
#   ADB_DEVICE    指定 adb 目标（默认随便挑一个）

set -euo pipefail

PROJ_DIR="$(cd "$(dirname "$0")" && pwd)"
DEV_DIR="$PROJ_DIR/.dev"
PODMAN_CONTAINERS="gomob-pg gomob-redis gomob-nats gomob-minio"

# 容器宿主端口段（避开服务器上其它产品默认端口）；真理源在 scripts/lib/dev-ports.sh。
# shellcheck source=scripts/lib/dev-ports.sh
source "$PROJ_DIR/scripts/lib/dev-ports.sh"

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

adb_cmd() {
    adb ${ADB_DEVICE:+-s "$ADB_DEVICE"} "$@"
}

ensure_emulator_hardware_keyboard() {
    local avd_home="${ANDROID_AVD_HOME:-${ANDROID_USER_HOME:-$HOME/.android}/avd}"
    local config="$avd_home/gomob_test.avd/config.ini"
    [[ -f "$config" ]] || {
        echo "gomob_test AVD 配置不存在: $config；先执行 ./dev.sh avd-create" >&2
        return 1
    }
    if grep -Eq '^[[:space:]]*hw\.keyboard[[:space:]]*=' "$config"; then
        sed -i -E 's/^[[:space:]]*hw\.keyboard[[:space:]]*=.*/hw.keyboard = yes/' "$config"
    else
        printf '\nhw.keyboard = yes\n' >> "$config"
    fi
    echo "emulator 宿主键盘已启用: $config"
}

install_debug_apk() {
    local apk="$PROJ_DIR/app/build/outputs/apk/debug/app-debug.apk"
    "$GRADLEW" :app:assembleDebug "$@" 2>&1 | tee "$DEV_DIR/install.log"
    # HyperOS 对流式安装会转入系统安装器确认页；非流式 push install 可直接更新。
    adb_cmd install -r -t -g --no-streaming "$apk" 2>&1 | tee -a "$DEV_DIR/install.log"
}

ensure_dev_reverse() {
    if ! command -v adb >/dev/null 2>&1; then
        echo "adb reverse: adb 不可用，跳过"
        return 0
    fi
    if [[ -n "${ADB_DEVICE:-}" ]]; then
        reverse_one_device "$ADB_DEVICE"
        return 0
    fi
    local devices=()
    mapfile -t devices < <(adb devices | awk -F '\t' '$2 == "device" { print $1 }')
    if [[ ${#devices[@]} -eq 0 ]]; then
        echo "adb reverse: 当前没有可用设备，跳过"
        return 0
    fi
    for serial in "${devices[@]}"; do
        reverse_one_device "$serial"
    done
}

reverse_one_device() {
    local serial="$1"
    if ! adb -s "$serial" get-state >/dev/null 2>&1; then
        echo "adb reverse: $serial 不可用，跳过"
        return 0
    fi
    if adb -s "$serial" reverse tcp:8808 tcp:18808 >/dev/null 2>&1; then
        echo "adb reverse: $serial tcp:8808 -> host tcp:18808"
    else
        echo "adb reverse: $serial tcp:8808 配置失败，App 需要改用局域网服务地址"
    fi
    if adb -s "$serial" reverse tcp:7880 tcp:7880 >/dev/null 2>&1; then
        echo "adb reverse: $serial tcp:7880 -> host tcp:7880"
    else
        echo "adb reverse: $serial tcp:7880 配置失败，LiveKit 需要改用设备可访问地址"
    fi
    # MinIO：server 端用 GOMOB_PORT_MINIO 签 URL，emulator 同端口反代到宿主新端口
    if adb -s "$serial" reverse "tcp:${GOMOB_PORT_MINIO}" "tcp:${GOMOB_PORT_MINIO}" >/dev/null 2>&1; then
        echo "adb reverse: $serial tcp:${GOMOB_PORT_MINIO} -> host tcp:${GOMOB_PORT_MINIO}"
    else
        echo "adb reverse: $serial tcp:${GOMOB_PORT_MINIO} 配置失败，媒体下载需要改用设备可访问地址"
    fi
}

ensure_livekit_container() {
    if ! command -v podman >/dev/null 2>&1; then
        echo "LiveKit dev server: podman 不可用，跳过"
        return 0
    fi
    if podman ps --format '{{.Names}}' | grep -qx gomob-livekit; then
        echo "LiveKit dev server: gomob-livekit 已运行"
        return 0
    fi
    if podman ps -a --format '{{.Names}}' | grep -qx gomob-livekit; then
        podman start gomob-livekit >/dev/null
        echo "LiveKit dev server: 已启动已有 gomob-livekit"
        return 0
    fi
    podman run -d --name gomob-livekit \
        -p 7880:7880 -p 7881:7881 -p 7882:7882/udp \
        docker.io/livekit/livekit-server:latest \
        --dev --bind 0.0.0.0 >/dev/null
    echo "LiveKit dev server: 已创建并启动 gomob-livekit (--dev: devkey/secret)"
}

ensure_one_container() {
    # 用法: ensure_one_container <name> <main_host_port> <main_ctn_port> [podman_run_args... <image> [cmd args...]]
    # 检查容器存在 + 主端口映射；不一致则 rm 重建（named volume 数据保留）。
    local name="$1"; shift
    local host_port="$1"; shift
    local ctn_port="$1"; shift
    if podman container exists "$name" 2>/dev/null; then
        local cur
        cur=$(podman inspect "$name" \
            --format "{{ with (index .NetworkSettings.Ports \"${ctn_port}/tcp\") }}{{ (index . 0).HostPort }}{{ end }}" \
            2>/dev/null || true)
        if [[ "$cur" == "$host_port" ]]; then
            if podman ps --format '{{.Names}}' | grep -qx "$name"; then
                echo "  · $name 已运行 (:${host_port})"
            else
                podman start "$name" >/dev/null && echo "  → $name 启动 (:${host_port})"
            fi
            return 0
        fi
        echo "  ⚠ $name 端口 ${cur:-?} → ${host_port}，重建（named volume 数据保留）"
        podman rm -f "$name" >/dev/null
    fi
    podman run -d --name "$name" "$@" >/dev/null
    echo "  + $name 已创建并启动 (:${host_port})"
}

ensure_server_containers() {
    if ! command -v podman >/dev/null 2>&1; then
        echo "server up: podman 不可用，无法启动容器栈"
        return 1
    fi
    ensure_one_container gomob-pg "$GOMOB_PORT_PG" 5432 \
        -p "${GOMOB_PORT_PG}:5432" \
        -e POSTGRES_USER=gomob -e POSTGRES_PASSWORD=gomob_dev -e POSTGRES_DB=gomob \
        -v gomob-pg-data:/var/lib/postgresql/data \
        docker.io/library/postgres:16-alpine
    ensure_one_container gomob-redis "$GOMOB_PORT_REDIS" 6379 \
        -p "${GOMOB_PORT_REDIS}:6379" \
        -v gomob-redis-data:/data \
        docker.io/library/redis:7-alpine
    # JetStream 启用 (-js) + 文件存储落 named volume (-sd /data)，
    # worker 用 durable consumer 持久消费 inspection.scan_completed，崩溃/重启不丢事件。
    ensure_one_container gomob-nats "$GOMOB_PORT_NATS" 4222 \
        -p "${GOMOB_PORT_NATS}:4222" -p "${GOMOB_PORT_NATS_MON}:8222" \
        -v gomob-nats-data:/data \
        docker.io/library/nats:2-alpine \
        -js -sd /data
    # MinIO 凭据必须跟 server/cmd/devserver/main.go:342-343 的默认值对齐 (gomob/gomob_dev_minio)，
    # 否则 asset handler 初始化时 bucket exists check 报 Access Key not exists → upload 返回 503。
    ensure_one_container gomob-minio "$GOMOB_PORT_MINIO" 9000 \
        -p "${GOMOB_PORT_MINIO}:9000" -p "${GOMOB_PORT_MINIO_CONSOLE}:9001" \
        -e MINIO_ROOT_USER=gomob -e MINIO_ROOT_PASSWORD=gomob_dev_minio \
        -v gomob-minio-data:/data \
        docker.io/minio/minio:latest \
        server /data --console-address :9001
    ensure_livekit_container
}

default_asr_url() {
    echo "http://127.0.0.1:${GOMOB_ASR_PORT:-18091}"
}

asr_service_url() {
    echo "${GOMOB_ASR_URL:-$(default_asr_url)}"
}

asr_health_code() {
    local url
    url="$(asr_service_url)"
    curl -s -o /dev/null -w '%{http_code}' --max-time 2 "${url%/}/healthz" 2>/dev/null || true
}

ensure_asr_service() {
    export GOMOB_ASR_URL
    GOMOB_ASR_URL="$(asr_service_url)"

    if [[ "${GOMOB_ENABLE_ASR:-1}" == "0" ]]; then
        echo "ASR 服务: 已按 GOMOB_ENABLE_ASR=0 跳过"
        unset GOMOB_ASR_URL
        return 0
    fi
    if [[ "$(asr_health_code)" == "200" ]]; then
        echo "ASR 服务: 已运行 ($GOMOB_ASR_URL)"
        return 0
    fi
    if [[ "$GOMOB_ASR_URL" != "$(default_asr_url)" ]]; then
        echo "ASR 服务: $GOMOB_ASR_URL 未就绪，且不是本地默认服务，无法代启动"
        return 1
    fi

    "$PROJ_DIR/server/asr_service/scripts/run.sh"
    if [[ "$(asr_health_code)" == "200" ]]; then
        echo "ASR 服务: /healthz 200"
        return 0
    fi
    echo "ASR 服务: 启动后健康检查失败"
    return 1
}

devserver_health_code() {
    curl -s -o /dev/null -w '%{http_code}' --max-time 1 http://127.0.0.1:18808/healthz 2>/dev/null || true
}

devserver_pid_from_port() {
    ss -ltnp 2>/dev/null | rg ':18808\b' | rg -o 'pid=[0-9]+' | head -1 | cut -d= -f2 || true
}

running_devserver_asr_url() {
    local pid="$1"
    [[ -n "$pid" && -r "/proc/$pid/environ" ]] || return 0
    tr '\0' '\n' < "/proc/$pid/environ" | rg '^GOMOB_ASR_URL=' | sed 's/^GOMOB_ASR_URL=//' || true
}

stop_devserver_background() {
    local pid="${1:-}"
    if [[ -z "$pid" && -f "$DEV_DIR/devserver.pid" ]]; then
        pid="$(cat "$DEV_DIR/devserver.pid" 2>/dev/null || true)"
    fi
    [[ -n "$pid" ]] || pid="$(devserver_pid_from_port)"
    [[ -n "$pid" ]] || return 0

    local pgid
    pgid="$(ps -o pgid= -p "$pid" 2>/dev/null | tr -d ' ' || true)"
    if [[ -n "$pgid" ]]; then
        kill -- "-$pgid" 2>/dev/null || true
    else
        kill "$pid" 2>/dev/null || true
    fi

    for _ in $(seq 1 30); do
        if ! ss -ltnp 2>/dev/null | rg -q ':18808\b'; then
            return 0
        fi
        sleep 1
    done
    echo "devserver: 停止超时，当前仍监听 :18808"
    return 1
}

start_devserver_background() {
    if [[ "$(devserver_health_code)" == "200" ]]; then
        local pid running_asr
        pid="$(devserver_pid_from_port)"
        running_asr="$(running_devserver_asr_url "$pid")"
        if [[ -n "${GOMOB_ASR_URL:-}" && "$running_asr" != "$GOMOB_ASR_URL" ]]; then
            echo "devserver: 已运行但 ASR 配置不一致，重启接入 $GOMOB_ASR_URL"
            stop_devserver_background "$pid"
        else
            echo "devserver: 已运行 (:18808)"
            return 0
        fi
    fi
    if [[ "$(devserver_health_code)" == "200" ]]; then
        echo "devserver: 已运行 (:18808)"
        return 0
    fi
    mkdir -p "$DEV_DIR"
    : > "$DEV_DIR/devserver.log"
    setsid bash -c 'cd "$1" && exec env GOMOB_ASR_URL="$3" ./dev.sh server run >> "$2/devserver.log" 2>&1' \
        bash "$PROJ_DIR" "$DEV_DIR" "${GOMOB_ASR_URL:-}" < /dev/null > /dev/null 2>&1 &
    echo $! > "$DEV_DIR/devserver.pid"
    echo "devserver: 后台启动 pid=$(cat "$DEV_DIR/devserver.pid") log=$DEV_DIR/devserver.log"
}

wait_devserver_health() {
    local timeout="${1:-60}"
    local pid=""
    [[ -f "$DEV_DIR/devserver.pid" ]] && pid="$(cat "$DEV_DIR/devserver.pid" 2>/dev/null || true)"
    for _ in $(seq 1 "$timeout"); do
        if [[ "$(devserver_health_code)" == "200" ]]; then
            echo "devserver: /healthz 200"
            return 0
        fi
        if [[ -n "$pid" ]] && ! kill -0 "$pid" 2>/dev/null; then
            echo "devserver: 启动进程已退出，最近日志："
            tail -120 "$DEV_DIR/devserver.log" 2>/dev/null || true
            return 1
        fi
        sleep 1
    done
    echo "devserver: 等待 /healthz 超时，最近日志："
    tail -160 "$DEV_DIR/devserver.log" 2>/dev/null || true
    return 1
}

dev_up() {
    echo "── server containers ──"
    ensure_server_containers
    echo
    echo "── ASR service ──"
    ensure_asr_service
    echo
    echo "── devserver ──"
    start_devserver_background
    wait_devserver_health 60
    echo
    echo "── adb reverse ──"
    ensure_dev_reverse
    echo
    echo "开发服务已就绪："
    echo "  gateway: http://127.0.0.1:18808"
    echo "  health:  http://127.0.0.1:18808/healthz"
    [[ -n "${GOMOB_ASR_URL:-}" ]] && echo "  asr:     $GOMOB_ASR_URL"
    echo "  log:     $DEV_DIR/devserver.log"
}

case "$cmd" in
    doctor)
        doctor
        ;;
    up|start|dev-up)
        dev_up
        ;;
    build)
        "$GRADLEW" :app:assembleDebug "$@" 2>&1 | tee "$DEV_DIR/build.log"
        ;;
    release)
        "$GRADLEW" :app:assembleRelease "$@" 2>&1 | tee "$DEV_DIR/release.log"
        ;;
    install)
        ensure_dev_reverse
        install_debug_apk "$@"
        ;;
    run)
        ensure_dev_reverse
        install_debug_apk
        adb_cmd shell am start -n io.gomob.scan.debug/io.gomob.scan.MainActivity
        ;;
    reverse)
        ensure_dev_reverse
        ;;
    test)
        "$GRADLEW" test "$@" 2>&1 | tee "$DEV_DIR/test.log"
        ;;
    native-test)
        # native host 自动门:5 个 host-test runner 真编译真跑真判(scripts/host-tests-all.sh)
        set -o pipefail
        "$PROJ_DIR/scripts/host-tests-all.sh" 2>&1 | tee "$DEV_DIR/native-test.log"
        ;;
    ci)
        # gradle + native host 自动门;任一失败 ci 即非零(pipefail 让 | tee 不吞退码)
        set -o pipefail
        rc=0
        "$GRADLEW" lint test assembleDebug "$@" 2>&1 | tee "$DEV_DIR/ci.log" || rc=$?
        "$PROJ_DIR/scripts/host-tests-all.sh" 2>&1 | tee "$DEV_DIR/native-test.log" || rc=$?
        exit "$rc"
        ;;
    clean)
        "$GRADLEW" clean
        rm -rf "$DEV_DIR"/*.log "$DEV_DIR/screenshots"
        ;;
    log)
        adb_cmd logcat -v time \
            'gomob:*' 'gomob_native:*' 'AndroidRuntime:E' 'System.err:W' '*:S' \
            | tee "$DEV_DIR/logcat.log"
        ;;
    shot)
        name="${1:-screen}"
        out="$DEV_DIR/screenshots/${name}.png"
        adb_cmd exec-out screencap -p > "$out"
        echo "→ $out"
        ;;
    record)
        name="${1:-screen-$(date +%Y%m%d-%H%M%S)}"
        seconds="${2:-180}"
        bitrate="${GOMOB_RECORD_BITRATE:-32M}"
        size="${GOMOB_RECORD_SIZE:-}"
        remote="/sdcard/gomob-${name%.mp4}.mp4"
        out_dir="$DEV_DIR/app-recordings"
        out="$out_dir/${name%.mp4}.mp4"
        mkdir -p "$out_dir"
        if [[ -z "$size" ]]; then
            size="$(adb_cmd shell wm size | sed -n 's/Physical size: //p' | tr -d '\r')"
        fi
        if [[ -z "$size" ]]; then
            echo "无法读取设备分辨率；可传 GOMOB_RECORD_SIZE=1080x2400"
            exit 2
        fi
        echo "录屏: size=$size bit-rate=$bitrate time-limit=${seconds}s"
        echo "目标: $out"
        adb_cmd shell rm -f "$remote" >/dev/null 2>&1 || true
        cleanup_recording() {
            local stop_remote="${1:-0}"
            if [[ "$stop_remote" == "1" ]]; then
                adb_cmd shell pkill -2 screenrecord >/dev/null 2>&1 || true
                sleep 1
            fi
            if adb_cmd shell ls "$remote" >/dev/null 2>&1; then
                adb_cmd pull "$remote" "$out" >/dev/null
                adb_cmd shell rm -f "$remote" >/dev/null 2>&1 || true
                echo "→ $out"
            else
                echo "录屏文件未生成: $remote"
            fi
        }
        trap 'echo; cleanup_recording 1; exit 130' INT TERM
        if adb_cmd shell screenrecord --size "$size" --bit-rate "$bitrate" --time-limit "$seconds" "$remote"; then
            cleanup_recording 0
        else
            status=$?
            cleanup_recording 1
            exit "$status"
        fi
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
        # 容器栈统一走 podman（4 个 named-volume 持久容器 gomob-pg/redis/nats/minio）。
        # podman 出 OCI 标准镜像，部署到任意容器引擎都行 — 不再二分 dev/prod 运行时。
        case "$sub" in
            doctor)  "$PROJ_DIR/server/scripts/server-doctor.sh" ;;
            up)      ensure_server_containers ;;
            down)    podman stop  $PODMAN_CONTAINERS gomob-livekit 2>&1 | tail -10 || true ;;
            ps)      podman ps -a --filter name=gomob- \
                         --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}' ;;
            logs)    podman logs -f "${1:-gomob-pg}" ;;
            build)   (cd "$PROJ_DIR/server" && make build) ;;
            test)    (cd "$PROJ_DIR/server" && go test ./...) ;;
            run)     ensure_livekit_container
                     export GOMOB_DB_DSN="${GOMOB_DB_DSN:-$GOMOB_DEFAULT_DB_DSN}"
                     export GOMOB_REDIS_ADDR="${GOMOB_REDIS_ADDR:-$GOMOB_DEFAULT_REDIS_ADDR}"
                     export GOMOB_NATS_URL="${GOMOB_NATS_URL:-$GOMOB_DEFAULT_NATS_URL}"
                     export GOMOB_MINIO_ENDPOINT="${GOMOB_MINIO_ENDPOINT:-$GOMOB_DEFAULT_MINIO_ENDPOINT}"
                     export GOMOB_APP_FEEDBACK_DIR="${GOMOB_APP_FEEDBACK_DIR:-$DEV_DIR/app-feedback}"
                     (cd "$PROJ_DIR/server" && ./scripts/migrate.sh up)
                     (cd "$PROJ_DIR/server/cmd/devserver" && \
                        GOMOB_LIVEKIT_URL="${GOMOB_LIVEKIT_URL:-ws://127.0.0.1:7880}" \
                        GOMOB_LIVEKIT_API_KEY="${GOMOB_LIVEKIT_API_KEY:-devkey}" \
                        GOMOB_LIVEKIT_API_SECRET="${GOMOB_LIVEKIT_API_SECRET:-secret}" \
                        go run .) ;;
            migrate) [[ $# -gt 0 ]] || set -- up
                     export GOMOB_DB_DSN="${GOMOB_DB_DSN:-$GOMOB_DEFAULT_DB_DSN}"
                     (cd "$PROJ_DIR/server" && ./scripts/migrate.sh "$@") ;;
            proto)   (cd "$PROJ_DIR/server" && ./scripts/proto-gen.sh) ;;
            clean)   (cd "$PROJ_DIR/server" && rm -rf .dev/bin && echo "→ server/.dev/bin cleaned") ;;
            *) echo "用法: $0 server {doctor|up|down|ps|logs|build|test|run|migrate|proto|clean}"; exit 2 ;;
        esac
        ;;
    avd-create)
        : "${ANDROID_HOME:?}"
        echo no | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd \
            -n gomob_test -k "system-images;android-34;default;x86_64" -d pixel_7 --force
        ensure_emulator_hardware_keyboard
        ;;
    emu-start)
        : "${ANDROID_HOME:?}"
        ensure_emulator_hardware_keyboard
        # DISPLAY=:1 的 GL 路径会回退 llvmpipe，使 App 与 SystemUI 一起阻塞在
        # gralloc / BufferQueue。固定 guest SystemUI 走 SkiaVK，并关闭本机不稳定的
        # Vulkan 原生交换链；host + gfxstream Vulkan 已通过连续交互验证。
        gpu_mode="${EMULATOR_GPU_MODE:-host}"
        case "$gpu_mode" in
            auto|host|software|lavapipe|swiftshader|swangle) ;;
            *)
                echo "不支持的 EMULATOR_GPU_MODE=$gpu_mode" >&2
                exit 2
                ;;
        esac
        # 2026-05-08: emulator 36.x 在本机 netsim/packet streamer 路径会触发
        # libandroid-webrtc.so 崩溃，默认关掉 WiFi/Modem 仿真链路。
        # 详见 docs/agent-memory/finding_emulator_setup_2026-05-04.md
        # 和 docs/agent-memory/feedback_vnc_remote_dev.md。
        # 默认带 GUI 窗口（用户走 TigerVNC 远程, 需在 :1 桌面看到 emulator）
        # 设 HEADLESS=1 可切回无窗口（CI / 截图脚本场景）
        win_flags=()
        [[ "${HEADLESS:-0}" == "1" ]] && win_flags=(-no-window)
        stable_flags=(
            -feature Vulkan
            -feature -VulkanNativeSwapchain
            -feature -VirtioWifi
            -feature -Mac80211hwsimUserspaceManaged
            -feature -ModemSimulator
            -crash-report-mode never
        )
        DISPLAY="${DISPLAY:-:1}" setsid "$ANDROID_HOME/emulator/emulator" -avd gomob_test \
            "${win_flags[@]}" -no-audio -no-snapshot -no-boot-anim \
            -gpu "$gpu_mode" -systemui-renderer skiavk \
            -accel on -port 5556 "${stable_flags[@]}" \
            < /dev/null > "$DEV_DIR/emulator.log" 2>&1 & disown
        (
            export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
            for _ in {1..90}; do
                state="$(adb -s emulator-5556 get-state 2>/dev/null || true)"
                [[ "$state" == "device" ]] || { sleep 2; continue; }
                adb -s emulator-5556 shell pm path android >/dev/null 2>&1 && break
                sleep 2
            done
            for _ in {1..30}; do
                adb -s emulator-5556 shell pm disable-user --user 0 com.android.bluetooth >/dev/null 2>&1 && break
                sleep 2
            done
            adb -s emulator-5556 shell am force-stop com.android.bluetooth >/dev/null 2>&1 || true
            for _ in {1..30}; do
                if adb -s emulator-5556 shell settings put secure show_ime_with_hard_keyboard 1 >/dev/null 2>&1; then
                    echo "宿主键盘直输已启用，屏幕软键盘保留"
                    break
                fi
                sleep 2
            done
        ) > "$DEV_DIR/emulator-postboot.log" 2>&1 & disown
        echo "emulator started (gpu=$gpu_mode, systemui=skiavk, log: $DEV_DIR/emulator.log)"
        [[ "${#win_flags[@]}" -eq 0 ]] && echo "GUI 模式 — 在 VNC :1 桌面里能看到 emulator 窗口" \
            || echo "headless 模式 (HEADLESS=1)"
        echo "postboot 修正日志: $DEV_DIR/emulator-postboot.log"
        echo "等 boot: until [ \"\$(adb -s emulator-5556 shell getprop sys.boot_completed)\" = 1 ]; do sleep 5; done"
        ;;
    emu-stop)
        # 注意: 不能用宽泛的 pkill -f 'qemu-system' — 会匹配自身 bash 命令行。
        # 也不能只用 pkill -x: Linux comm 名会截断成 qemu-system-x86，匹配不到。
        qemu_pids="$(pgrep -f "^$ANDROID_HOME/emulator/qemu/.*/qemu-system" || true)"
        if [[ -n "$qemu_pids" ]]; then
            printf '%s\n' "$qemu_pids" | xargs -r kill
            sleep 3
            qemu_pids="$(pgrep -f "^$ANDROID_HOME/emulator/qemu/.*/qemu-system" || true)"
            [[ -n "$qemu_pids" ]] && printf '%s\n' "$qemu_pids" | xargs -r kill -9
        fi
        adb kill-server >/dev/null 2>&1
        echo "emulator stopped"
        ;;
    *)
        sed -n '2,21p' "$0"
        exit 2
        ;;
esac
