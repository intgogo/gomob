#!/usr/bin/env bash
# eys3d_mode25 harness — 真机一键验证 mode25(videoMode=36)硬件深度。
# 流程:设备在否 → 解析 lsusb 描述符自动填帧索引 → 编译自研流工具 → 跑流采样 → analyze.py 判定。
# device-gated:需真机 0x3438:0x0206 + 带电 hub(强制 USB2)。设备 ~15min 自动关机,开机即跑。
set -euo pipefail
cd "$(dirname "$0")/../../.."

OUT="${OUTPUT_DIR:-.dev/eys3d_mode25}"
SECS="${SECS:-8}"
mkdir -p "$OUT"

echo "== 1. 设备在否 =="
if ! lsusb -d 3438:0206 >/dev/null 2>&1; then
    echo "❌ eYs3D 0x3438:0206 离线。接带电 hub、长按开机键,再跑。"
    exit 2
fi
echo "✅ 设备在线"

echo "== 2. 解析 UVC 描述符 → mode25 帧索引 =="
DESC=$(python3 scripts/eys3d-parse-descriptor.py 2>&1 | tee "$OUT/descriptor.txt" || true)
COLOR_IDX=$(printf '%s' "$DESC" | grep -oE 'color_frame_index=[0-9]+' | head -1 | cut -d= -f2 || true)
DEPTH_IDX=$(printf '%s' "$DESC" | grep -oE 'depth_frame_index=[0-9]+'  | head -1 | cut -d= -f2 || true)
ROWS=$(printf '%s' "$DESC" | grep -oE 'depth_status_rows=[0-9]+'      | head -1 | cut -d= -f2 || true)
COLOR_IDX="${COLOR_IDX:-2}"; DEPTH_IDX="${DEPTH_IDX:-4}"; ROWS="${ROWS:-0}"
echo "帧索引: color=$COLOR_IDX depth=$DEPTH_IDX status_rows=$ROWS"

echo "== 3. 编译自研 mode25 流工具(零厂商 SDK)=="
read -r -a LIBUSB_FLAGS <<< "$(pkg-config --cflags --libs libusb-1.0)"
mkdir -p .dev/native-host
g++ -std=c++17 -O2 -pthread -Inative -Inative/berxel/portable -Inative/eys3d/portable \
    native/eys3d/host/eys3d_mode25_stream.cpp \
    native/eys3d/host/eys3d_host_session.cpp \
    native/eys3d/host/eys3d_stream_loop.cpp \
    native/eys3d/portable/eys3d_session_core.cpp \
    native/eys3d/portable/eys3d_depth_router.cpp \
    native/eys3d/portable/eys3d_driver.cpp \
    native/eys3d/portable/eys3d_depth.cpp \
    native/eys3d/portable/eys3d_protocol.cpp \
    native/berxel/portable/gomob_berxel_portable.cpp \
    "${LIBUSB_FLAGS[@]}" -o .dev/native-host/eys3d_mode25_stream
echo "✅ 编译通过"

echo "== 4. 跑 mode25 流采样 ${SECS}s =="
.dev/native-host/eys3d_mode25_stream "$SECS" "$OUT" "$COLOR_IDX" "$DEPTH_IDX" "$ROWS" || true

echo "== 5. 分析判定 =="
python3 tests/harness/eys3d_mode25/analyze.py "$OUT" 640 $((128 + ROWS))
