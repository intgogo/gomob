#!/usr/bin/env bash
# berxel_depth_parity/run.sh — Linux host P100R3 depth raw parity harness。
#
# 流程：
#   1. 编译 harness 内置的原厂 SDK depth oracle 工具和自研 host probe。
#   2. 用原厂 SDK 显式复位 sparse 状态。
#   3. 用自研 SDK 默认 dense controls 采集 active raw depth 多帧。
#   4. 再次复位 sparse，用原厂 SDK dense controls 采集 oracle 多帧。
#   5. 对比 vendor/host raw depth，并输出相机自身相邻帧噪声基线。
#
# 真实物理依赖：
#   - Linux 服务器已插 P100R3，能看到 0603:001f + 3558:1012。
#   - .dev/berxel-sdk-extract/BerxelSDK-Linux-2.0.190 存在，仅作为 oracle。

set -euo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$PROJ_DIR"

STAMP="$(date +%Y%m%d-%H%M%S)"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJ_DIR/.dev/berxel_depth_parity/$STAMP}"
VENDOR_SDK_DIR="${VENDOR_SDK_DIR:-$PROJ_DIR/.dev/berxel-sdk-extract/BerxelSDK-Linux-2.0.190}"
VENDOR_SRC="$PROJ_DIR/tests/harness/berxel_depth_parity/vendor_hawk_depth_read.cpp"
VENDOR_BIN="$PROJ_DIR/.dev/berxel_depth_parity/bin/vendor_hawk_depth_read"
FRAMES="${FRAMES:-20}"
SKIP="${SKIP:-10}"
HOST_SKIP="${HOST_SKIP:-5}"
HOST_DUR_MS="${HOST_DUR_MS:-3200}"
WIDTH="${WIDTH:-640}"
HEIGHT="${HEIGHT:-400}"
CHECK_NO_CONTROLS="${CHECK_NO_CONTROLS:-1}"
SCENE_NAME="${SCENE_NAME:-default}"
SCENE_MATERIAL="${SCENE_MATERIAL:-unknown}"
SCENE_DISTANCE_MM="${SCENE_DISTANCE_MM:-0}"
SCENE_ANGLE_DEG="${SCENE_ANGLE_DEG:-0}"
SCENE_NOTES="${SCENE_NOTES:-}"

mkdir -p "$OUTPUT_DIR" "$(dirname "$VENDOR_BIN")" "$PROJ_DIR/.dev/berxel_depth_parity"
printf '%s\n' "$OUTPUT_DIR" > "$PROJ_DIR/.dev/berxel_depth_parity/latest.txt"
SCENE_NAME="$SCENE_NAME" \
SCENE_MATERIAL="$SCENE_MATERIAL" \
SCENE_DISTANCE_MM="$SCENE_DISTANCE_MM" \
SCENE_ANGLE_DEG="$SCENE_ANGLE_DEG" \
SCENE_NOTES="$SCENE_NOTES" \
WIDTH="$WIDTH" \
HEIGHT="$HEIGHT" \
FRAMES="$FRAMES" \
python3 - "$OUTPUT_DIR/scene.json" <<'PY'
import json
import os
import sys

def number(name: str) -> float:
    try:
        return float(os.environ.get(name, "0"))
    except ValueError:
        return 0.0

scene = {
    "scene_name": os.environ.get("SCENE_NAME", "default"),
    "material": os.environ.get("SCENE_MATERIAL", "unknown"),
    "distance_mm": number("SCENE_DISTANCE_MM"),
    "angle_deg": number("SCENE_ANGLE_DEG"),
    "notes": os.environ.get("SCENE_NOTES", ""),
    "width": int(number("WIDTH")),
    "height": int(number("HEIGHT")),
    "frames": int(number("FRAMES")),
}
with open(sys.argv[1], "w", encoding="utf-8") as out:
    json.dump(scene, out, ensure_ascii=False, indent=2)
    out.write("\n")
PY

log() {
    printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"
}

require_file() {
    if [ ! -f "$1" ]; then
        printf '缺少文件: %s\n' "$1" >&2
        exit 2
    fi
}

build_vendor_tool() {
    require_file "$VENDOR_SRC"
    require_file "$VENDOR_SDK_DIR/Include/BerxelHawkContext.h"
    require_file "$VENDOR_SDK_DIR/libs/libBerxelHawk.so"
    log "build vendor oracle: $VENDOR_BIN"
    g++ -std=c++17 -O2 -Wall -Wextra \
        -I"$VENDOR_SDK_DIR/Include" \
        "$VENDOR_SRC" \
        -L"$VENDOR_SDK_DIR/libs" -lBerxelHawk \
        -Wl,-rpath,"$VENDOR_SDK_DIR/libs" \
        -o "$VENDOR_BIN"
}

vendor_depth() {
    local out_dir="$1"
    shift
    mkdir -p "$out_dir"
    LD_LIBRARY_PATH="$VENDOR_SDK_DIR/libs" timeout 15s "$VENDOR_BIN" \
        --out-dir "$out_dir" \
        --frames "$FRAMES" \
        --skip "$SKIP" \
        --attempts "$((FRAMES + SKIP + 120))" \
        --timeout-ms 100 \
        --width "$WIDTH" \
        --height "$HEIGHT" \
        "$@"
}

reset_sparse() {
    local name="$1"
    local out="$OUTPUT_DIR/$name"
    log "vendor reset sparse: $name"
    vendor_depth "$out" --frames 4 --skip 4 --attempts 80 --temporal 1 --spatial 1 \
        > "$OUTPUT_DIR/$name.log" 2>&1
}

capture_host_default() {
    local out="$OUTPUT_DIR/host-default"
    log "host default dense capture"
    scripts/berxel-host-probe.sh \
        --session-api --depth --master-all \
        --dur-ms "$HOST_DUR_MS" \
        --save-depth-frames "$FRAMES" \
        --save-depth-skip "$HOST_SKIP" \
        --out-dir "$out" \
        > "$OUTPUT_DIR/host-default.log" 2>&1
}

capture_vendor_dense() {
    local out="$OUTPUT_DIR/vendor-dense"
    log "vendor dense oracle capture"
    vendor_depth "$out" --depth-ae 1 --depth-confidence 3 --temporal 0 --spatial 0 \
        > "$OUTPUT_DIR/vendor-dense.log" 2>&1
}

capture_host_no_controls() {
    local out="$OUTPUT_DIR/host-no-controls"
    log "host no-controls sparse capture"
    scripts/berxel-host-probe.sh \
        --session-api --depth --master-all \
        --dur-ms 1800 \
        --no-depth-controls \
        --save-depth-frames 6 \
        --save-depth-skip 3 \
        --out-dir "$out" \
        > "$OUTPUT_DIR/host-no-controls.log" 2>&1
}

compare_depth() {
    local out="$OUTPUT_DIR/analysis/active-vs-vendor"
    log "compare active raw depth"
    python3 native/berxel/host/tools/compare_depth_frames.py \
        --vendor-dir "$OUTPUT_DIR/vendor-dense" \
        --host-dir "$OUTPUT_DIR/host-default" \
        --host-pattern 'depth-frame-*-active.raw' \
        --out-dir "$out" \
        --max-pairs "$FRAMES" \
        > "$OUTPUT_DIR/compare-active.json"
}

build_vendor_tool
reset_sparse reset-before-host
capture_host_default
reset_sparse reset-before-vendor
capture_vendor_dense
compare_depth

if [ "$CHECK_NO_CONTROLS" = "1" ]; then
    reset_sparse reset-before-no-controls
    capture_host_no_controls
fi

log "analyze"
python3 tests/harness/berxel_depth_parity/analyze.py "$OUTPUT_DIR"
