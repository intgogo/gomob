#!/usr/bin/env bash
# eys3d-host-test.sh — eYs3D host(libusb)层编译 + smoke。
# 与 native-host-test.sh(纯逻辑无 libusb)互补:此处编 driver/会话/UsbContext 等依赖 libusb 的件,
# 以及 host 取流工具(replay_stream/probe)。smoke 不需真机即可证编译自洽 + 工厂离线面。
set -euo pipefail

cd "$(dirname "$0")/.."
mkdir -p .dev/native-host

read -r -a LIBUSB_FLAGS <<< "$(pkg-config --cflags --libs libusb-1.0)"
CXX_FLAGS=(-std=c++17 -O2 -Wall -Wextra -Wno-deprecated-copy -pthread
           -Inative -Inative/berxel/portable -Inative/eys3d/portable -Inative/camera)

PORTABLE_SRCS=(
    native/eys3d/portable/eys3d_session_core.cpp
    native/eys3d/portable/eys3d_depth_router.cpp
    native/eys3d/portable/eys3d_driver.cpp
    native/eys3d/portable/eys3d_depth.cpp
    native/eys3d/portable/eys3d_protocol.cpp
    native/berxel/portable/gomob_berxel_portable.cpp
)

build_and_run() {
    local name=$1; shift
    echo "=== $name ==="
    g++ "${CXX_FLAGS[@]}" "$@" "${LIBUSB_FLAGS[@]}" -o ".dev/native-host/$name"
    ".dev/native-host/$name"
    echo
}

build_only() {
    local name=$1; shift
    echo "=== $name (仅编译) ==="
    g++ "${CXX_FLAGS[@]}" "$@" "${LIBUSB_FLAGS[@]}" -o ".dev/native-host/$name"
    echo "  编译通过: .dev/native-host/$name"
    echo
}

# driver/会话 smoke(可跑,无设备优雅降级)。流逻辑在共享 eys3d_stream_loop.cpp。
build_and_run eys3d_host_session_smoke \
    tests/native_host/eys3d_host_session_smoke.cpp \
    native/eys3d/host/eys3d_host_session.cpp \
    native/eys3d/host/eys3d_usb_api.cpp \
    native/eys3d/host/eys3d_stream_loop.cpp \
    "${PORTABLE_SRCS[@]}"

# eys3d_fd_session_smoke 不在 host 跑:Eys3dFdSession 是生产不可达的实验分发器
# (open_fd 由 kUseVendorCpp=true 永走 Eys3dVendorCppSession,本类从不实例化,见
#  eys3d_fd_session.cpp Run() 的 TODO(R3) 与 TODO.md M11.15),且其分发的
#  eys3d_vendor_cpp_session.cpp 依赖 android/native_window.h + 厂商 FrameGrabber SDK,
#  无法 host 编译。不为生产死代码强行 host 编译 / 造厂商假桩(那是更大的伪造)。
# 活路径由上面的 eys3d_host_session_smoke(真跑)+ 下面的 eys3d_mode25_stream(编译)覆盖;
# 待 M11.15 用 build flag 物理隔离实验路径后,再定 fd/pupil/vendor 的测试形态。
# (orphan 测试源 tests/native_host/eys3d_fd_session_smoke.cpp 一并随 M11.15 处置)

# host 取流工具(需真机才出帧,这里仅编译保证不腐化)
build_only eys3d_replay_stream \
    native/eys3d/host/eys3d_replay_stream.cpp \
    native/eys3d/host/eys3d_usb_api.cpp \
    native/berxel/portable/gomob_berxel_portable.cpp

# mode25 真机流验证工具(自研 Eys3dHostSession + Mode25Usb2Plan,仅编译)
build_only eys3d_mode25_stream \
    native/eys3d/host/eys3d_mode25_stream.cpp \
    native/eys3d/host/eys3d_host_session.cpp \
    native/eys3d/host/eys3d_usb_api.cpp \
    native/eys3d/host/eys3d_stream_loop.cpp \
    "${PORTABLE_SRCS[@]}"

# Berxel host 统一路径 probe(M6.8b ④):berxel_dual_session_jni.cpp 经 #ifdef __ANDROID__ host 编,
# 经 BerxelDriver::open_host 真机取流(需真机才出帧,这里仅编译保证 host 双目标不腐化)。
build_only berxel_camera_host_probe \
    native/berxel/host/berxel_camera_host_probe.cpp \
    native/jni/berxel_dual_session_jni.cpp \
    native/berxel/portable/gomob_berxel_portable.cpp
