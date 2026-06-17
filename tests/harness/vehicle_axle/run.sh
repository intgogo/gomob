#!/usr/bin/env bash
# vehicle_axle harness：对原厂真值会话复算 轴距/前后悬，判定几何轴心检测是否达标。
# 用法: ./dev.sh harness vehicle_axle   或   tests/harness/vehicle_axle/run.sh
# 真值会话可用 JCHY_DATA 覆盖（默认 /root/WindowsR/JCHY_OFFLINE/Data/100742）。
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${OUTPUT_DIR:-.dev/vehicle_axle}"
mkdir -p "$OUT"
echo "[vehicle_axle] 分析轴心检测 → $OUT/report.txt"
python3 "$HERE/analyze.py" | tee "$OUT/report.txt"
