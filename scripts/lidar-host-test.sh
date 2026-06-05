#!/usr/bin/env bash
# 在 Linux host 上构建/运行激光几何内核纯逻辑单测（Eigen-only，无 PCL，无 libusb）。
# 链接通过即编译期硬证明 native/lidar 零 PCL 依赖 —— Android native 可直接复用。
set -euo pipefail
cd "$(dirname "$0")/.."

OUT_DIR=".dev/native-host"
mkdir -p "$OUT_DIR"

EIGEN="third_party/eigen-3.4.0"
[ -f "$EIGEN/Eigen/Dense" ] || { echo "Eigen 未投放: $EIGEN"; exit 1; }

FLAGS=(-std=c++17 -O2 -Wall -Wextra -Wpedantic
    -Inative -Inative/reconstruction -isystem "$EIGEN")

name="lidar_fusion_test"
echo "=== $name (Eigen-only, 无 PCL/libusb) ==="
g++ "${FLAGS[@]}" \
    native/lidar/cloud_build.cpp \
    native/lidar/fusion.cpp \
    native/lidar/registration.cpp \
    native/lidar/scan_vehicle.cpp \
    native/lidar/io_pcd.cpp \
    native/reconstruction/icp.cpp \
    tests/native_host/lidar_fusion_test.cpp \
    -o "$OUT_DIR/$name"
"$OUT_DIR/$name"
echo
