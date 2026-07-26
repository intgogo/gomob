#!/usr/bin/env bash
# eys3d_vendor_cpp harness — 真机验证【纯 native 直驱厂商 C++ 引擎】(零 Java 编排) 出 mode25 真深度。
#
# 帧路径:dlopen libUVCCamera.so → 直调厂商 UVCCamera/UVCPreview/FrameGrabber C++ 类起 mode25 流 →
#   FrameGrabber 回调被 repoint 到我方 trampoline(纯 C 函数指针,不经 _jobject)→ native core → Kotlin poll。
#   厂商 Java ApcCamera shim 已退役;唯一 Java 触点 = UsbManager.openDevice 拿 fd + bindEys3dVendorJni 的 setVM。
#
# 判据(analyze.py 出可判定结论):
#   ① 起流链 marker:open_fd 零Java会话 + FrameGrabber 校验通过(cb==livePlyCallback) + connect rc=0
#   ② ourCb 深度帧持续(fps≥阈) + Kotlin poll 首帧到达
#   ③ valid_ratio + centerDispX8 落在 mode25 11bit 视差域(质量,非致命)
#   ④ 零 JNI 帧路径:无 Java ApcCamera/IFrameCallback marker(退役后应彻底不出现)
#
# device-gated:需真机(adb)连 0x3438:0x0206 + 带电 hub(强制 USB2)。脚本会 am start 拉起 App;
#   ★ 请确保 App 已进入深度相机/激光扫描页(eYs3D 自动 acquire 开流),再等采样完成。
set -uo pipefail
cd "$(dirname "$0")/../../.."

ADB="${ANDROID_HOME:-/opt/android-sdk}/platform-tools/adb"
[[ -x "$ADB" ]] || ADB="adb"
ACT="${ACT:-io.gomob.scan.debug/io.gomob.scan.MainActivity}"
OUT="${OUTPUT_DIR:-.dev/eys3d_vendor_cpp}"
SECS="${SECS:-20}"
mkdir -p "$OUT"
LOG="$OUT/logcat.txt"

# 多设备/多 transport 歧义:认 ADB_DEVICE/ANDROID_SERIAL;未设则自动取第一台 online。
SERIAL="${ADB_DEVICE:-${ANDROID_SERIAL:-}}"
[[ -z "$SERIAL" ]] && SERIAL="$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1; exit}')"
adb_() { if [[ -n "$SERIAL" ]]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi; }

echo "== 1. adb 设备在否 =="
if [[ -z "$SERIAL" ]] || [[ "$(adb_ get-state 2>/dev/null)" != "device" ]]; then
    echo "❌ 无 adb 设备。连真机或 ./scripts/adb-wifi.sh connect <ip:port>;多台时 ADB_DEVICE=<serial> 指定。"
    exit 2
fi
echo "✅ adb 设备在线: $SERIAL"

echo "== 2. 原厂 USB 栈完整性 =="
ABI="$(adb_ shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')"
case "$ABI" in
    arm64-v8a) EXPECTED_USB100_SHA="e5935dda4f4f8c8c356938241b4c5ce492df3fe2d184b2906a76994d0b3609f6" ;;
    armeabi-v7a) EXPECTED_USB100_SHA="a6cfb330dceb5e16a5e43ed0110881e2d3420908f63be75e55fcc8bc05363cd5" ;;
    *)
        echo "❌ 不支持的设备 ABI: $ABI"
        exit 2
        ;;
esac
LOCAL_USB100="third_party/eys3d-vendor/lib/$ABI/libusb100.so"
LOCAL_SHA="$(sha256sum "$LOCAL_USB100" 2>/dev/null | awk '{print $1}')"
if [[ "$LOCAL_SHA" != "$EXPECTED_USB100_SHA" ]]; then
    echo "❌ $LOCAL_USB100 不是 VINCreator 原厂库: $LOCAL_SHA"
    exit 2
fi
if [[ -e "third_party/eys3d-vendor/lib/$ABI/libusb100real.so" ]]; then
    echo "❌ 仍存在诊断真库 libusb100real.so，生产包禁止打包"
    exit 2
fi
echo "✅ $ABI libusb100.so 原厂哈希匹配"

echo "== 3. 前置 =="
# ★ 不 am start：本应用单 Activity，am start 会把 Compose 导航重置回首页起始目的地 → 深度页离开 → 相机 release。
#   请【手动】保持手机停在深度相机页(eYs3D 自动 acquire 开流)。
echo "   → 请保持手机停在深度相机页(勿切走，eYs3D 自动开流)。"

echo "== 4. 清 logcat → 等 ${SECS}s 攒帧 → dump =="
# ★ 该 OEM(annibale)logcat 不认 positional tag 过滤(tag:V *:S 恒空)；改 dump 全量后 grep 自筛(实证可用)。
#   稀疏 tag 流式 -s 又被块缓冲 SIGTERM 丢行，故用 -d dump(退出即 flush)。
adb_ logcat -c >/dev/null 2>&1 || true
START_MS="$(date +%s%3N)"
sleep "$SECS"
END_MS="$(date +%s%3N)"
adb_ logcat -d 2>/dev/null | grep -E "eys3d_vcpp|eys3d_stream|Eys3dCameraService|hlsd8_uvc|Hlsd8CameraService" > "$LOG" || true
python3 - "$OUT/sample.json" "$SECS" "$START_MS" "$END_MS" "$SERIAL" <<'PY'
import json
import sys

path, requested, started_ms, ended_ms, serial = sys.argv[1:]
payload = {
    "duration_seconds": max(0.001, (int(ended_ms) - int(started_ms)) / 1000.0),
    "requested_seconds": float(requested),
    "device_serial": serial,
    "log_file": "logcat.txt",
}
with open(path, "w", encoding="utf-8") as output:
    json.dump(payload, output, ensure_ascii=False, indent=2)
    output.write("\n")
PY
echo "   采样落 $LOG ($(wc -l < "$LOG" 2>/dev/null || echo 0) 行)"
echo "   元数据落 $OUT/sample.json"
