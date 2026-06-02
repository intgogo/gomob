#!/usr/bin/env bash
# berxel_camera_parity — 服务器端「统一抽象 open_host vs 原厂 SDK」逐帧深度 parity（最强不退化证据）。
#
# 流程：① 原厂 Linux SDK dense 采 depth raw16；② 我们 BerxelDriver::open_host 采 depthMm；
#       ③ compare_depth_frames.py 逐像素 mm 对齐（vendor raw/8 vs 我们已 mm）。
# device-gated：服务器挂 P100R3(0603:001f + 3558:1012) + .dev/berxel-sdk-extract/BerxelSDK-Linux-2.0.190。
#   两次采集分时独占设备（vendor 进程退出释放后我们再开），需对【静态场景】拍。
set -euo pipefail
PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$PROJ_DIR"

OUT="${OUTPUT_DIR:-$PROJ_DIR/.dev/berxel_camera_parity}"
VENDOR_SDK_DIR="${VENDOR_SDK_DIR:-$PROJ_DIR/.dev/berxel-sdk-extract/BerxelSDK-Linux-2.0.190}"
VENDOR_SRC="$PROJ_DIR/tests/harness/berxel_depth_parity/vendor_hawk_depth_read.cpp"
VENDOR_BIN="$PROJ_DIR/.dev/berxel_camera_parity/bin/vendor_hawk_depth_read"
# 原厂 SDK 用实证档 640×400;open_host 用生产档 1280×800。分布级 parity 分辨率无关(静态场景中位一致)。
VW="${VW:-640}"; VH="${VH:-400}"; FRAMES="${FRAMES:-12}"; SECS="${SECS:-10}"
mkdir -p "$OUT" "$(dirname "$VENDOR_BIN")"

echo "== 0. 前置检查 =="
lsusb -d 0603:001f >/dev/null 2>&1 && lsusb -d 3558:1012 >/dev/null 2>&1 || { echo "❌ Berxel 离线"; exit 2; }
[ -f "$VENDOR_SDK_DIR/libs/libBerxelHawk.so" ] || { echo "❌ vendor SDK 缺: $VENDOR_SDK_DIR"; exit 2; }
echo "✅ 设备在线 + vendor SDK 在位"

echo "== 1. 编 vendor oracle + 统一 open_host probe =="
g++ -std=c++17 -O2 -Wall -Wextra -I"$VENDOR_SDK_DIR/Include" "$VENDOR_SRC" \
    -L"$VENDOR_SDK_DIR/libs" -lBerxelHawk -Wl,-rpath,"$VENDOR_SDK_DIR/libs" -o "$VENDOR_BIN"
read -r -a LIBUSB_FLAGS <<< "$(pkg-config --cflags --libs libusb-1.0)"
mkdir -p .dev/native-host
g++ -std=c++17 -O2 -pthread -Inative -Inative/berxel/portable \
    native/berxel/host/berxel_camera_host_probe.cpp \
    native/jni/berxel_dual_session_jni.cpp \
    native/berxel/portable/gomob_berxel_portable.cpp \
    "${LIBUSB_FLAGS[@]}" -o .dev/native-host/berxel_camera_host_probe
echo "✅ 编译通过"

echo "== 2. 原厂 SDK dense 采 (${VW}x${VH}, ${FRAMES} 帧) =="
LD_LIBRARY_PATH="$VENDOR_SDK_DIR/libs" timeout 40s "$VENDOR_BIN" \
    --out-dir "$OUT/vendor-dense" --frames "$FRAMES" --skip 10 --attempts "$((FRAMES + 140))" \
    --timeout-ms 100 --width "$VW" --height "$VH" \
    --depth-ae 1 --depth-confidence 3 --temporal 0 --spatial 0 \
    > "$OUT/vendor.log" 2>&1 || true
if ! ls "$OUT/vendor-dense"/vendor-depth-*.raw >/dev/null 2>&1; then
    echo "❌ vendor 没出帧（看日志尾）:"; tail -8 "$OUT/vendor.log"; exit 3
fi
echo "✅ vendor 采到 $(ls "$OUT/vendor-dense"/vendor-depth-*.raw | wc -l) 帧"

echo "== 3. 统一 BerxelDriver::open_host 采 (1280x800 生产档) =="
.dev/native-host/berxel_camera_host_probe "$SECS" "$OUT/host-openhost" 45 0 "$FRAMES" \
    > "$OUT/host.log" 2>&1 || true
if ! ls "$OUT/host-openhost"/depthmm_*.bin >/dev/null 2>&1; then
    echo "❌ open_host 没出帧（看日志尾）:"; tail -8 "$OUT/host.log"; exit 3
fi
echo "✅ open_host 采到 $(ls "$OUT/host-openhost"/depthmm_*.bin | wc -l) 帧"

echo "== 4. 分布级 parity 判定（vendor raw/8 vs open_host mm，分辨率无关）=="
python3 tests/harness/berxel_camera_parity/analyze.py "$OUT"
