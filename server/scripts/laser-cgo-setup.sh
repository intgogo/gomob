#!/usr/bin/env bash
# 为 laser_cgo 构建 native 库：从仓内 server/native/lidar/ 源码构建 liblidar_scan.a，
# 并把 .a + 头软链进 server/internal/laser/native/（该目录 gitignore，仅留软链/产物）。
# cgo.go 的 #cgo CFLAGS/LDFLAGS 用 ${SRCDIR}/native 找它们；PCL/OpenCV/yaml-cpp/zstd/boost 为系统库（默认可寻）。
#
# 用法： server/scripts/laser-cgo-setup.sh [Release|Debug]
# 环境： LIDAR_DIR（默认仓内 server/native/lidar，可覆盖指外部源树）、LIDAR_LIB（覆盖 .a 路径）。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"   # server/
LIDAR_DIR="${LIDAR_DIR:-$HERE/native/lidar}"
BUILD="$LIDAR_DIR/build"
NATIVE="$HERE/internal/laser/native"
BUILD_TYPE="${1:-Release}"

# 锁定系统 OpenCV 4.6（带 aruco/objdetect contrib），避开 /usr/local 的 4.5.5（无 aruco）。
OPENCV_DIR="${OpenCV_DIR:-/usr/lib64/cmake/OpenCV}"
echo "[laser-cgo] 配置+构建 lidar_scan ($BUILD_TYPE) ← $LIDAR_DIR (OpenCV_DIR=$OPENCV_DIR)"
cmake -S "$LIDAR_DIR" -B "$BUILD" -DCMAKE_BUILD_TYPE="$BUILD_TYPE" -DLIDAR_BUILD_TESTS=OFF \
      -DOpenCV_DIR="$OPENCV_DIR" >/dev/null
cmake --build "$BUILD" --target lidar_scan -j"$(nproc)"

LIB="${LIDAR_LIB:-$BUILD/liblidar_scan.a}"
HDR="$LIDAR_DIR/src/lib/lidar_scan.h"
[ -f "$LIB" ] || { echo "缺 liblidar_scan.a: $LIB" >&2; exit 1; }
[ -f "$HDR" ] || { echo "缺头文件: $HDR" >&2; exit 1; }

mkdir -p "$NATIVE/include"
ln -sfn "$(realpath --relative-to="$NATIVE" "$LIB")" "$NATIVE/liblidar_scan.a"
ln -sfn "$(realpath --relative-to="$NATIVE/include" "$HDR")" "$NATIVE/include/lidar_scan.h"

echo "ok: native 库就位 → $NATIVE"
ls -l "$NATIVE/liblidar_scan.a" "$NATIVE/include/lidar_scan.h"
echo "现在可： cd server && CGO_ENABLED=1 go build -tags laser_cgo ./cmd/laserworker"
