#!/usr/bin/env bash
# 在 Linux host 上构建/运行 Berxel P100R3 自研 SDK 探针。
set -euo pipefail

cd "$(dirname "$0")/.."

OUT_DIR=".dev/berxel-host-sdk"
BIN="$OUT_DIR/berxel_host_probe"
mkdir -p "$OUT_DIR"

read -r -a LIBUSB_FLAGS <<< "$(pkg-config --cflags --libs libusb-1.0)"

g++ -std=c++17 -O2 -Wall -Wextra -Wpedantic \
    -Inative/berxel/host/include -Inative/berxel/portable \
    native/berxel/portable/gomob_berxel_portable.cpp \
    native/berxel/host/src/gomob_berxel_host_sdk.cpp \
    tests/native_host/berxel_host_probe.cpp \
    "${LIBUSB_FLAGS[@]}" \
    -o "$BIN"

"$BIN" "$@"
