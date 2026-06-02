#!/usr/bin/env bash
# berxel_camera_host harness — 开发服务器经【厂商无关】ICameraDriver::open_host 对真机验证 Berxel 双流。
# 验证统一抽象(BerxelDriver/BerxelSessionAdapter)在 host 端真机出 metric depth,与 Android open_fd 同序列。
# device-gated：需服务器挂 Berxel(0603:001f master + 3558:1012 companion)。libusb detach 内核驱动可能需 root。
set -euo pipefail
cd "$(dirname "$0")/../../.."

OUT="${OUTPUT_DIR:-.dev/berxel_camera_host}"
SECS="${SECS:-8}"
FPS="${FPS:-45}"
COLOR="${COLOR:-0}"
mkdir -p "$OUT"

echo "== 1. 设备在否 =="
lsusb -d 0603:001f >/dev/null 2>&1 || { echo "❌ Berxel master 0603:001f 离线"; exit 2; }
lsusb -d 3558:1012 >/dev/null 2>&1 || { echo "❌ Berxel companion 3558:1012 离线"; exit 2; }
echo "✅ master + companion 在线"

echo "== 2. 编译 host probe(berxel_dual_session_jni.cpp 经 #ifdef __ANDROID__ host 编) =="
read -r -a LIBUSB_FLAGS <<< "$(pkg-config --cflags --libs libusb-1.0)"
mkdir -p .dev/native-host
g++ -std=c++17 -O2 -pthread -Inative -Inative/berxel/portable \
    native/berxel/host/berxel_camera_host_probe.cpp \
    native/jni/berxel_dual_session_jni.cpp \
    native/berxel/portable/gomob_berxel_portable.cpp \
    "${LIBUSB_FLAGS[@]}" -o .dev/native-host/berxel_camera_host_probe
echo "✅ 编译通过"

echo "== 3. 跑 ${SECS}s open_host 取流(depth ${FPS}fps color=${COLOR}) =="
.dev/native-host/berxel_camera_host_probe "$SECS" "$OUT" "$FPS" "$COLOR" || true

echo "== 4. 分析判定 =="
python3 tests/harness/berxel_camera_host/analyze.py "$OUT" 1280 800
