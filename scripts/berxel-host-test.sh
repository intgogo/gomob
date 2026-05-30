#!/usr/bin/env bash
# 在 Linux host 上构建/运行 Berxel 自研 SDK 纯逻辑单测。
set -euo pipefail

cd "$(dirname "$0")/.."

OUT_DIR=".dev/native-host"
mkdir -p "$OUT_DIR"

read -r -a LIBUSB_FLAGS <<< "$(pkg-config --cflags --libs libusb-1.0)"

HOST_FLAGS=(-std=c++17 -O2 -Wall -Wextra -Wpedantic \
    -Inative/berxel/host/include -Inative/berxel/portable)

# 纯逻辑单测：只编 portable.cpp，不链 libusb。链接通过即编译期硬证明零 libusb 依赖，
# 这正是 Android native 可直接复用 portable 层的前提。
build_portable_test() {
    local name="$1"
    local test_src="$2"
    echo "=== $name (portable-only, 无 libusb) ==="
    g++ "${HOST_FLAGS[@]}" \
        native/berxel/portable/gomob_berxel_portable.cpp \
        "$test_src" \
        -o "$OUT_DIR/$name"
    "$OUT_DIR/$name"
    echo
}

# host 层单测：portable + host_sdk(libusb)。用于 P100R3DualSession 等依赖设备 IO 的测试。
build_host_sdk_test() {
    local name="$1"
    local test_src="$2"
    echo "=== $name ==="
    g++ "${HOST_FLAGS[@]}" \
        native/berxel/portable/gomob_berxel_portable.cpp \
        native/berxel/host/src/gomob_berxel_host_sdk.cpp \
        "$test_src" \
        "${LIBUSB_FLAGS[@]}" \
        -o "$OUT_DIR/$name"
    "$OUT_DIR/$name"
    echo
}

build_sonix_test() {
    local name="berxel_sonix_protocol_test"
    echo "=== $name ==="
    g++ -std=c++17 -O2 -Wall -Wextra -Wpedantic \
        -Inative/berxel/include \
        native/berxel/src/gomob_berxel_protocol_sonix.cpp \
        tests/native_host/berxel_sonix_protocol_test.cpp \
        -o "$OUT_DIR/$name"
    "$OUT_DIR/$name"
    echo
}

build_sonix_test
build_portable_test berxel_host_payload_test tests/native_host/berxel_host_payload_test.cpp
build_portable_test berxel_depth_processing_test tests/native_host/berxel_depth_processing_test.cpp
build_portable_test berxel_temporal_filter_test tests/native_host/berxel_temporal_filter_test.cpp
build_portable_test berxel_flying_pixel_test tests/native_host/berxel_flying_pixel_test.cpp
build_portable_test berxel_mjpeg_assembler_test tests/native_host/berxel_mjpeg_assembler_test.cpp
build_portable_test berxel_raw_assembler_test tests/native_host/berxel_raw_assembler_test.cpp
build_host_sdk_test berxel_session_state_test tests/native_host/berxel_session_state_test.cpp
build_portable_test berxel_rgbd_pairer_test tests/native_host/berxel_rgbd_pairer_test.cpp
