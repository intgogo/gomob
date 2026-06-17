#!/usr/bin/env bash
# 重编 libuvc_gomob.so:自研独立后端 = pupil libuvc 源(解析 MJPEG + eYs3D 定制 arming/bulk 恢复)
#   + 直链【stock libusb-1.0】(third_party/libusb-android,v1.0.27,与 Berxel 同款)。
# 与旧 libuvc_lusb100.so(原厂 libusb100 后端)区别:fd 传递走 libusb_wrap_sys_device、init 走 NO_DEVICE_DISCOVERY,
#   彻底脱离原厂库。产物 NEEDED 必须只有 libusb-1.0.so(无 libusb100.so)。
set -euo pipefail
ROOT=/root/lilw/gomob
SRC=$ROOT/third_party/libuvc-android/src
LUSB=$ROOT/third_party/libusb-android          # stock libusb-1.0 v1.0.27
DST=$ROOT/third_party/libuvc-android/jniLibs
LIBDST=$ROOT/third_party/libuvc-android/lib
NDK=/opt/android-sdk/ndk/27.2.12479018
TOOLCHAIN=$NDK/build/cmake/android.toolchain.cmake
BUILD_BASE=$ROOT/.dev/libuvc-gomob-build
PLAT=android-21

build_one() {
  local abi=$1
  local stage="$BUILD_BASE/staging/$abi"
  local bdir="$BUILD_BASE/build-$abi"
  echo "=== [$abi] 准备 stock libusb staging ==="
  rm -rf "$stage"; mkdir -p "$stage/include/libusb-1.0" "$stage/lib/pkgconfig"
  cp -f "$LUSB/include/libusb-1.0/libusb.h" "$stage/include/libusb-1.0/libusb.h"
  cp -f "$LUSB/lib/$abi/libusb-1.0.so"    "$stage/lib/libusb-1.0.so"
  cat > "$stage/lib/pkgconfig/libusb-1.0.pc" <<EOF
prefix=$stage
exec_prefix=\${prefix}
libdir=\${exec_prefix}/lib
includedir=\${prefix}/include
Name: libusb-1.0
Description: stock libusb-1.0 (Android NDK build, v1.0.27)
Version: 1.0.27
Libs: -L\${libdir} -lusb-1.0
Cflags: -I\${includedir}/libusb-1.0
EOF

  echo "=== [$abi] cmake configure ==="
  rm -rf "$bdir"; mkdir -p "$bdir"
  PKG_CONFIG_PATH="$stage/lib/pkgconfig" cmake -S "$SRC" -B "$bdir" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM="$PLAT" \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_EXAMPLE=OFF -DBUILD_TEST=OFF \
    -DCMAKE_DISABLE_FIND_PACKAGE_JpegPkg=ON \
    -DCMAKE_FIND_ROOT_PATH="$stage" \
    -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH \
    -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH >/dev/null

  echo "=== [$abi] make uvc ==="
  gmake -C "$bdir" uvc -j4

  local so="$bdir/libuvc.so"
  echo "=== [$abi] patchelf soname → libuvc_gomob.so ==="
  patchelf --set-soname libuvc_gomob.so "$so"
  mkdir -p "$DST/$abi" "$LIBDST/$abi"
  cp -f "$so" "$DST/$abi/libuvc_gomob.so"
  cp -f "$so" "$LIBDST/$abi/libuvc_gomob.so"
  echo "-- 投放 $DST/$abi/libuvc_gomob.so --"
  readelf -d "$DST/$abi/libuvc_gomob.so" | grep -iE "SONAME|NEEDED"
  if readelf -d "$DST/$abi/libuvc_gomob.so" | grep -qi "libusb100"; then
    echo "❌ [$abi] NEEDED 含 libusb100 —— 未脱离原厂!"; exit 1
  fi
  echo "✅ [$abi] 纯 stock libusb-1.0 后端"
}

build_one arm64-v8a
build_one armeabi-v7a
echo "=== 完成:libuvc_gomob.so(stock libusb-1.0 后端,零原厂) ==="
