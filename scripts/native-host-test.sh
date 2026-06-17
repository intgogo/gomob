#!/usr/bin/env bash
# native-host-test.sh — 在 Linux host 上跑 native/reconstruction 单测（不依赖 NDK / Android）
# 用于 ICP / TSDF / Marching Cubes 等纯计算模块的快速验证
set -euo pipefail

cd "$(dirname "$0")/.."
mkdir -p .dev/native-host

CXX_FLAGS=(-std=c++17 -O2 -Wall -Wextra -Wno-deprecated-copy -pthread
           -Ithird_party/eigen-3.4.0 -Inative -Inative/berxel/portable)

build_and_run() {
    local name=$1; shift
    echo "=== $name ==="
    g++ "${CXX_FLAGS[@]}" "$@" -o ".dev/native-host/$name"
    ".dev/native-host/$name"
    echo
}

build_and_run icp_test \
    tests/native_host/icp_test.cpp \
    native/reconstruction/icp.cpp

build_and_run tsdf_test \
    tests/native_host/tsdf_test.cpp \
    native/reconstruction/tsdf.cpp \
    native/depth/depth_projection.cpp

build_and_run mc_test \
    tests/native_host/mc_test.cpp \
    native/reconstruction/marching_cubes.cpp \
    native/reconstruction/tsdf.cpp \
    native/depth/depth_projection.cpp

build_and_run scan_session_test \
    tests/native_host/scan_session_test.cpp \
    native/reconstruction/scan_session.cpp \
    native/reconstruction/icp.cpp \
    native/reconstruction/tsdf.cpp \
    native/reconstruction/marching_cubes.cpp \
    native/reconstruction/mesh_export.cpp \
    native/depth/depth_projection.cpp

build_and_run conf_weight_test \
    tests/native_host/conf_weight_test.cpp \
    native/reconstruction/tsdf.cpp \
    native/reconstruction/icp.cpp \
    native/depth/depth_projection.cpp

build_and_run eys3d_depth_test \
    tests/native_host/eys3d_depth_test.cpp \
    native/eys3d/portable/eys3d_depth.cpp

build_and_run eys3d_protocol_test \
    tests/native_host/eys3d_protocol_test.cpp \
    native/eys3d/portable/eys3d_protocol.cpp \
    native/berxel/portable/gomob_berxel_portable.cpp

build_and_run camera_abstraction_test \
    tests/native_host/camera_abstraction_test.cpp \
    native/berxel/portable/gomob_berxel_portable.cpp

build_and_run eys3d_driver_test \
    tests/native_host/eys3d_driver_test.cpp \
    native/eys3d/portable/eys3d_driver.cpp \
    native/eys3d/portable/eys3d_depth.cpp \
    native/berxel/portable/gomob_berxel_portable.cpp

build_and_run eys3d_depth_router_test \
    tests/native_host/eys3d_depth_router_test.cpp \
    native/eys3d/portable/eys3d_depth_router.cpp \
    native/eys3d/portable/eys3d_driver.cpp \
    native/eys3d/portable/eys3d_depth.cpp \
    native/berxel/portable/gomob_berxel_portable.cpp

build_and_run eys3d_session_core_test \
    tests/native_host/eys3d_session_core_test.cpp \
    native/eys3d/portable/eys3d_session_core.cpp \
    native/eys3d/portable/eys3d_depth_router.cpp \
    native/eys3d/portable/eys3d_driver.cpp \
    native/eys3d/portable/eys3d_depth.cpp \
    native/eys3d/portable/eys3d_protocol.cpp \
    native/berxel/portable/gomob_berxel_portable.cpp

build_and_run eys3d_stereo_depth_test \
    tests/native_host/eys3d_stereo_depth_test.cpp \
    native/eys3d/portable/eys3d_stereo_depth.cpp \
    native/eys3d/portable/eys3d_driver.cpp \
    native/eys3d/portable/eys3d_depth.cpp \
    native/berxel/portable/gomob_berxel_portable.cpp

# 双相机正射图几何（depth × HLSD8 RGB → 平面正射纹理）。合成 RGBD 验证 scale/投影/外参处理。
build_and_run ortho_rectify_test \
    tests/native_host/ortho_rectify_test.cpp \
    native/vin/ortho_rectify.cpp
