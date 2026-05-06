#!/bin/bash
# adb-wifi.sh — 局域网 ADB 调试一键脚本
#
# 用法:
#   ./scripts/adb-wifi.sh pair    <IP[:配对端口]> <配对码>   首次配对（Android 11+ 推荐）
#                                                           只给 IP 时自动从 mDNS 查 _adb-tls-pairing._tcp 端口
#   ./scripts/adb-wifi.sh connect <IP[:调试端口]>            已配对设备日常连接
#                                                           只给 IP 时自动从 mDNS 查 _adb-tls-connect._tcp 端口
#   ./scripts/adb-wifi.sh tcpip   <IP:5555>                  Android 10 及以下 USB 转 TCP
#   ./scripts/adb-wifi.sh devices                            列出当前已连接的设备
#   ./scripts/adb-wifi.sh disconnect [IP:端口]               断开（不带参数 = 全部）
#   ./scripts/adb-wifi.sh mdns                               列出局域网内可见的 ADB 服务（debug 用）
#
# 路径说明:
#   adb 在 $ANDROID_HOME/platform-tools/，本脚本会自动补 PATH。
#
# 重要: Android 11+ "无线调试"的 pair 端口每次开关都换，必须临用临查；
#       connect 端口在同一开关周期内一般稳定。所以约定:
#       - 配对前先看 mDNS（脚本会代办），不要相信旧端口
#       - 配对成功后用 `connect <IP[:port]>` 日常使用

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

# 从 `adb mdns services` 找指定 IP 上某类服务的端口
# 用法: mdns_lookup_port <IP> <service-type, 如 _adb-tls-pairing._tcp>
mdns_lookup_port() {
    local ip="$1" svc="$2"
    adb mdns services 2>/dev/null \
        | awk -v ip="$ip" -v svc="$svc" '
            $0 ~ svc && $NF ~ "^"ip":[0-9]+$" {
                split($NF, a, ":"); print a[2]; exit
            }
        '
}

# 把 "<IP>" 或 "<IP>:<port>" 拆出 IP，端口缺则尝试 mDNS 查
# 用法: resolve_addr <addr> <service-type>  → 打印 IP:Port
resolve_addr() {
    local addr="$1" svc="$2"
    if [[ "$addr" == *:* ]]; then
        echo "$addr"; return
    fi
    local port
    port="$(mdns_lookup_port "$addr" "$svc")"
    if [[ -z "$port" ]]; then
        echo "在 mDNS 上找不到 $addr 的 $svc 端口；手机端开"无线调试" → 拿屏幕显示的端口" >&2
        return 1
    fi
    echo "$addr:$port"
}

cmd="${1:-}"; shift || true

case "$cmd" in
    pair)
        addr="${1:-}"; code="${2:-}"
        if [[ -z "$addr" || -z "$code" ]]; then
            echo "用法: $0 pair <IP[:配对端口]> <配对码>"
            echo "（手机：开发者选项 → 无线调试 → 使用配对码配对设备）"
            echo "只给 IP 时自动从 mDNS 查 pair 端口"
            exit 2
        fi
        full="$(resolve_addr "$addr" "_adb-tls-pairing._tcp")" || exit 1
        echo "→ pairing $full"
        # 关键: 直接 `adb pair <addr> <code>` 用位置参数。
        # 旧实现 `echo $code | adb pair <addr>` 在 adb 37+ 必现 protocol fault。
        adb pair "$full" "$code"
        ;;
    connect)
        addr="${1:-}"
        if [[ -z "$addr" ]]; then
            echo "用法: $0 connect <IP[:调试端口]>"
            exit 2
        fi
        full="$(resolve_addr "$addr" "_adb-tls-connect._tcp")" || exit 1
        echo "→ connecting $full"
        adb connect "$full"
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
    mdns)
        adb mdns services
        ;;
    *)
        sed -n '2,18p' "$0"
        exit 2
        ;;
esac
