#!/usr/bin/env bash
# 构建/运行 Linux host Berxel 双流预览 demo。
set -euo pipefail

cd "$(dirname "$0")/.."

BUILD_DIR=".dev/berxel-host-sdk/vin-rectify-gui-build"
SRC_DIR="native/berxel/host/demo/vin_rectify_gui"
BIN="$BUILD_DIR/gomob_berxel_vin_rectify_gui"
LOG_FILE=".dev/berxel-host-sdk/vin-rectify-gui.log"
DETACH=0
BUILD_ONLY=0
APP_ARGS=()

for arg in "$@"; do
    case "$arg" in
        --detach)
            DETACH=1
            ;;
        --build-only)
            BUILD_ONLY=1
            ;;
        *)
            APP_ARGS+=("$arg")
            ;;
    esac
done

cmake -S "$SRC_DIR" -B "$BUILD_DIR" -DCMAKE_BUILD_TYPE=RelWithDebInfo
cmake --build "$BUILD_DIR" -j"$(nproc)"

if [[ "$BUILD_ONLY" == "1" ]]; then
    exit 0
fi

if [[ "$DETACH" == "1" ]]; then
    setsid "$BIN" "${APP_ARGS[@]}" > "$LOG_FILE" 2>&1 < /dev/null &
    echo "$!"
else
    "$BIN" "${APP_ARGS[@]}"
fi
