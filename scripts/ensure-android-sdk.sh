#!/bin/bash
# ensure-android-sdk.sh — Android SDK / NDK / build-tools 自校验 + 缺啥补啥
#
# 用法:
#   ./scripts/ensure-android-sdk.sh             仅校验
#   ./scripts/ensure-android-sdk.sh --install   缺失项自动安装
#
# 安装位置: /opt/android-sdk (root 权限) 或 ~/Android/Sdk (普通用户)
# 环境变量: 写入 ~/.bashrc 与 local.properties

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJ_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# 必装清单（与 gradle/libs.versions.toml 保持一致）
REQUIRED_PLATFORMS=("android-35" "android-34")
REQUIRED_BUILD_TOOLS="35.0.0"
REQUIRED_NDK="27.2.12479018"
REQUIRED_CMAKE="3.22.1"

if [[ "$EUID" -eq 0 ]]; then
    SDK_DIR="/opt/android-sdk"
else
    SDK_DIR="$HOME/Android/Sdk"
fi

INSTALL_MODE=0
[[ "${1:-}" == "--install" ]] && INSTALL_MODE=1

ok()   { printf "  \033[32m✓\033[0m %s\n" "$1"; }
miss() { printf "  \033[33m·\033[0m %s — 缺失\n" "$1"; }
err()  { printf "  \033[31m✗\033[0m %s\n" "$1" >&2; }

echo "[ensure-android-sdk] SDK_DIR=$SDK_DIR  install_mode=$INSTALL_MODE"

# 1) Java 17
if java -version 2>&1 | grep -q '"17\.'; then
    ok "Java 17 已装"
else
    err "需要 Java 17。CentOS: dnf install -y java-17-openjdk-devel"
    exit 1
fi
# javac
if ! command -v javac >/dev/null 2>&1; then
    err "javac 缺失（只装了 JRE）。CentOS: dnf install -y java-17-openjdk-devel"
    exit 1
else
    ok "javac 可用"
fi

# 2) cmdline-tools
if [[ -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]]; then
    ok "cmdline-tools 已装"
else
    miss "cmdline-tools"
    if [[ "$INSTALL_MODE" -eq 1 ]]; then
        echo "  下载 commandlinetools-linux..."
        mkdir -p "$SDK_DIR/cmdline-tools"
        cd "$SDK_DIR/cmdline-tools"
        curl -fsSL -o cmdline-tools.zip 'https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip'
        unzip -q cmdline-tools.zip
        mv cmdline-tools latest
        rm cmdline-tools.zip
        cd "$PROJ_DIR"
        ok "cmdline-tools 安装完成"
    else
        echo "  → 用 --install 自动补装"
        exit 1
    fi
fi

export ANDROID_HOME="$SDK_DIR"
export PATH="$SDK_DIR/cmdline-tools/latest/bin:$SDK_DIR/platform-tools:$PATH"

# 接受 license
yes | sdkmanager --licenses >/dev/null 2>&1 || true

# 3) platforms / build-tools / NDK / CMake / platform-tools
check_pkg() {
    local pkg="$1"
    local label="$2"
    local probe_path="$3"
    if [[ -e "$probe_path" ]]; then
        ok "$label"
    else
        miss "$label"
        if [[ "$INSTALL_MODE" -eq 1 ]]; then
            echo "  装 $pkg ..."
            sdkmanager --install "$pkg" >/dev/null 2>&1
            ok "$label 安装完成"
        else
            echo "  → 用 --install 自动补装"
            exit 1
        fi
    fi
}

check_pkg "platform-tools" "platform-tools (adb)" "$SDK_DIR/platform-tools/adb"
check_pkg "build-tools;$REQUIRED_BUILD_TOOLS" "build-tools $REQUIRED_BUILD_TOOLS" "$SDK_DIR/build-tools/$REQUIRED_BUILD_TOOLS"
for plat in "${REQUIRED_PLATFORMS[@]}"; do
    check_pkg "platforms;$plat" "platforms;$plat" "$SDK_DIR/platforms/$plat"
done
check_pkg "ndk;$REQUIRED_NDK" "NDK $REQUIRED_NDK" "$SDK_DIR/ndk/$REQUIRED_NDK"
check_pkg "cmake;$REQUIRED_CMAKE" "cmake $REQUIRED_CMAKE" "$SDK_DIR/cmake/$REQUIRED_CMAKE"

# 4) local.properties 同步
if [[ ! -f "$PROJ_DIR/local.properties" ]] || ! grep -q "sdk.dir=$SDK_DIR" "$PROJ_DIR/local.properties"; then
    echo "sdk.dir=$SDK_DIR" > "$PROJ_DIR/local.properties"
    ok "已写 local.properties (sdk.dir=$SDK_DIR)"
else
    ok "local.properties OK"
fi

# 5) ~/.bashrc 提示
if ! grep -q "ANDROID_HOME=$SDK_DIR" "$HOME/.bashrc" 2>/dev/null; then
    echo ""
    echo "提示: 把以下两行加到 ~/.bashrc 让 adb / sdkmanager 全局可用:"
    echo "    export ANDROID_HOME=$SDK_DIR"
    echo "    export PATH=\"\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$PATH\""
fi

echo "[ensure-android-sdk] 全部齐"
