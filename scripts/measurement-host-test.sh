#!/usr/bin/env bash
# host 构建/运行车辆外廓测量内核单测（Eigen-only，无 PCL）。链接通过即证零 PCL 依赖。
set -euo pipefail
cd "$(dirname "$0")/.."

OUT_DIR=".dev/native-host"
mkdir -p "$OUT_DIR"

EIGEN="third_party/eigen-3.4.0"
[ -f "$EIGEN/Eigen/Dense" ] || { echo "Eigen 未投放: $EIGEN"; exit 1; }

FLAGS=(-std=c++17 -O2 -Wall -Wextra -Wpedantic -Inative -isystem "$EIGEN")

name="measurement_test"
echo "=== $name (Eigen-only, 无 PCL/libusb) ==="
g++ "${FLAGS[@]}" \
    native/lidar/fusion.cpp \
    native/lidar/io_pcd.cpp \
    native/measurement/preprocess.cpp \
    native/measurement/dimensions.cpp \
    native/measurement/measure_vehicle.cpp \
    tests/native_host/measurement_test.cpp \
    -o "$OUT_DIR/$name"
"$OUT_DIR/$name"
echo

name="vehicle_catalog_test"
echo "=== $name (车型目录 + carType 编解码) ==="
g++ "${FLAGS[@]}" \
    native/measurement/vehicle_catalog.cpp \
    tests/native_host/vehicle_catalog_test.cpp \
    -o "$OUT_DIR/$name"
"$OUT_DIR/$name"
echo
