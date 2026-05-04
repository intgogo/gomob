#!/bin/bash
# adb-wifi.sh — 局域网 ADB 调试一键脚本
#
# 用法:
#   ./scripts/adb-wifi.sh pair    <IP:配对端口> <配对码>   首次配对（Android 11+ 推荐）
#   ./scripts/adb-wifi.sh connect <IP:调试端口>             已配对设备日常连接
#   ./scripts/adb-wifi.sh tcpip   <IP:5555>                 Android 10 及以下 USB 转 TCP
#   ./scripts/adb-wifi.sh devices                            列出当前已连接的设备
#   ./scripts/adb-wifi.sh disconnect [IP:端口]               断开（不带参数 = 全部）
#
# 路径说明:
#   adb 在 $ANDROID_HOME/platform-tools/，本脚本会自动补 PATH。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJ_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ -z "${ANDROID_HOME:-}" ]]; then
    if [[ -d "/opt/android-sdk" ]]; then
        export ANDROID_HOME=/opt/android-sdk
    elif [[ -d "$HOME/Android/Sdk" ]]; then
        export ANDROID_HOME="$HOME/Android/Sdk"
    else
        echo "找不到 Android SDK，先跑 ./scripts/ensure-android-sdk.sh --install"
        exit 1
    fi
fi
export PATH="$ANDROID_HOME/platform-tools:$PATH"

if ! command -v adb >/dev/null 2>&1; then
    echo "adb 不存在；先装 platform-tools"
    exit 1
fi

cmd="${1:-}"; shift || true

case "$cmd" in
    pair)
        addr="${1:-}"; code="${2:-}"
        if [[ -z "$addr" || -z "$code" ]]; then
            echo "用法: $0 pair <IP:配对端口> <配对码>"
            echo "（手机：开发者选项 → 无线调试 → 使用配对码配对设备）"
            exit 2
        fi
        echo "$code" | adb pair "$addr"
        ;;
    connect)
        addr="${1:-}"
        if [[ -z "$addr" ]]; then
            echo "用法: $0 connect <IP:调试端口>"
            exit 2
        fi
        adb connect "$addr"
        adb devices -l
        ;;
    tcpip)
        addr="${1:-}"
        if [[ -z "$addr" ]]; then
            echo "用法: $0 tcpip <IP:5555>"
            echo "前提: 手机已通过 USB 连上电脑且 USB 调试已授权"
            exit 2
        fi
        port="${addr##*:}"
        adb tcpip "${port:-5555}"
        sleep 2
        adb connect "$addr"
        adb devices -l
        ;;
    devices)
        adb devices -l
        ;;
    disconnect)
        addr="${1:-}"
        if [[ -z "$addr" ]]; then
            adb disconnect
        else
            adb disconnect "$addr"
        fi
        ;;
    *)
        sed -n '2,12p' "$0"
        exit 2
        ;;
esac
