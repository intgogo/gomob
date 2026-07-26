#!/usr/bin/env bash
# 为 laser 服务构建 native 产物：从仓内 server/native/lidar/ 源码构建 liblidar_scan.a
# 与 site-calib/site-framing 共用的 lidar_cli，并把 .a + 头软链进
# server/internal/laser/native/（该目录 gitignore，仅留软链/产物）。
# cgo.go 的 #cgo CFLAGS/LDFLAGS 用 ${SRCDIR}/native 找它们；PCL/OpenCV/yaml-cpp/zstd/boost 为系统库（默认可寻）。
#
# 用法： server/scripts/laser-cgo-setup.sh [Release|Debug]
# 环境： LIDAR_DIR（默认仓内 server/native/lidar）、LIDAR_BUILD_DIR（默认根目录 .dev/lidar-build）、LIDAR_LIB。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"   # server/
LIDAR_DIR="${LIDAR_DIR:-$HERE/native/lidar}"
BUILD="${LIDAR_BUILD_DIR:-$HERE/../.dev/lidar-build}"
NATIVE="$HERE/internal/laser/native"
BUILD_TYPE="${1:-Release}"

# 锁定系统 OpenCV 4.6（带 aruco/objdetect contrib），避开 /usr/local 的 4.5.5（无 aruco）。
OPENCV_DIR="${OpenCV_DIR:-/usr/lib64/cmake/OpenCV}"
echo "[laser-cgo] 配置+构建 lidar_scan + lidar_cli ($BUILD_TYPE) ← $LIDAR_DIR (OpenCV_DIR=$OPENCV_DIR)"
cmake -S "$LIDAR_DIR" -B "$BUILD" -DCMAKE_BUILD_TYPE="$BUILD_TYPE" -DLIDAR_BUILD_TESTS=OFF \
      -DOpenCV_DIR="$OPENCV_DIR" >/dev/null
cmake --build "$BUILD" --target lidar_scan lidar_cli -j"$(nproc)"

LIB="${LIDAR_LIB:-$BUILD/liblidar_scan.a}"
CLI="$BUILD/lidar_cli"
HDR="$LIDAR_DIR/src/lib/lidar_scan.h"
[ -f "$LIB" ] || { echo "缺 liblidar_scan.a: $LIB" >&2; exit 1; }
[ -x "$CLI" ] || { echo "缺 lidar_cli（需 Ceres + OpenCV ArUco）: $CLI" >&2; exit 1; }
[ -f "$HDR" ] || { echo "缺头文件: $HDR" >&2; exit 1; }

mkdir -p "$NATIVE/include"
ln -sfn "$(realpath --relative-to="$NATIVE" "$LIB")" "$NATIVE/liblidar_scan.a"
ln -sfn "$(realpath --relative-to="$NATIVE/include" "$HDR")" "$NATIVE/include/lidar_scan.h"

echo "ok: native 库就位 → $NATIVE；site 标定 CLI → $CLI"
ls -l "$NATIVE/liblidar_scan.a" "$NATIVE/include/lidar_scan.h" "$CLI"
echo "现在可： cd server && CGO_ENABLED=1 go build -tags laser_cgo ./cmd/laserworker"
