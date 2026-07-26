#!/usr/bin/env bash
# HLSD8↔depth 双相机标定 harness：从真机拉标定采集 → ChArUco 标定 → 出 calibration.json + 结论。
# 前置：① 打印 ChArUco 板(gen_board.py 出图, 100% 打印, 尺量确认 square_mm 填回 board_spec.json)；
#       ② 端侧「深度相机 → HLSD8↔Depth 标定」多姿态采 ≥20 张，覆盖位置/倾角/远近。
# 用法: tests/harness/vin_calib/run.sh [out_dir]
set -euo pipefail
cd "$(dirname "$0")/../../.."   # → 仓根
OUT="${1:-.dev/vin_calib_caps}"
ADB="${ADB:-/opt/android-sdk/platform-tools/adb}"
mkdir -p "$OUT"

echo "== 1) 从真机拉标定采集 vin_calib/ =="
BASE=$($ADB shell 'ls -d /sdcard/Android/data/*/files/vin_calib 2>/dev/null' | tr -d '\r' | head -1 || true)
if [ -n "$BASE" ]; then
  for d in $($ADB shell "ls -d $BASE/calib_* 2>/dev/null" | tr -d '\r'); do
    n=$(basename "$d")
    [ -d "$OUT/$n" ] || $ADB pull "$d" "$OUT/" >/dev/null 2>&1 && echo "  pulled $n" || true
  done
else
  echo "  设备上无 vin_calib/（先端侧「标定采集」拍几张）。用已在 $OUT 的数据继续。"
fi

echo "== 2) ChArUco 标定（内参 + 外参 R|t）=="
set +e
python3 tests/harness/vin_calib/calibrate.py "$OUT" "$OUT/calibration.json"
CALIB_RC=$?

echo "== 3) 整姿态 5 折留出交叉验证 =="
OUTPUT_DIR="$OUT" python3 tests/harness/vin_calib/cross_validate.py "$OUT"
CROSS_RC=$?
set -e

if (( CALIB_RC >= 2 || CROSS_RC >= 2 )); then
  exit 2
fi
if (( CALIB_RC == 1 || CROSS_RC == 1 )); then
  exit 1
fi
