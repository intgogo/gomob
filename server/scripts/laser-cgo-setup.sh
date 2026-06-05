#!/usr/bin/env bash
# 为 laser_cgo 构建备好 native 库：把 lidar 仓的 liblidar_scan.a + 头软链进 server/internal/laser/native/。
# cgo.go 的 #cgo CFLAGS/LDFLAGS 用 ${SRCDIR}/native 找它们；PCL/flann/yaml-cpp/zstd/boost 为系统库（默认可寻）。
#
# 用法： scripts/laser-cgo-setup.sh
# 环境： LIDAR_DIR（默认 /root/lilw/lidar）、LIDAR_LIB（覆盖 .a 路径）。
# 前置： 在 lidar 仓先构建：cmake --build build --target lidar_scan
set -euo pipefail

LIDAR_DIR="${LIDAR_DIR:-/root/lilw/lidar}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"   # server/
NATIVE="$HERE/internal/laser/native"
LIB="${LIDAR_LIB:-$LIDAR_DIR/build/liblidar_scan.a}"
HDR="$LIDAR_DIR/src/lib/lidar_scan.h"

[ -f "$LIB" ] || { echo "缺 liblidar_scan.a: $LIB" >&2
  echo "  先在 lidar 仓构建：cmake -S $LIDAR_DIR -B $LIDAR_DIR/build && cmake --build $LIDAR_DIR/build --target lidar_scan" >&2
  exit 1; }
[ -f "$HDR" ] || { echo "缺头文件: $HDR" >&2; exit 1; }

mkdir -p "$NATIVE/include"
ln -sf "$LIB" "$NATIVE/liblidar_scan.a"
ln -sf "$HDR" "$NATIVE/include/lidar_scan.h"

echo "ok: native 库就位 → $NATIVE"
echo "  liblidar_scan.a → $LIB"
echo "  include/lidar_scan.h → $HDR"
echo "现在可： go build -tags laser_cgo ./...  /  go test -tags laser_cgo ./internal/laser/"
