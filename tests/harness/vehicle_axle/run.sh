#!/usr/bin/env bash
# vehicle_axle harness：对原厂真值会话复算 轴距/前后悬，判定几何轴心检测是否达标。
# 用法: ./dev.sh harness vehicle_axle   或   tests/harness/vehicle_axle/run.sh
# 真值会话路径：JCHY_DATA 环境变量优先；否则探测 ~/WindowsR/.../100742、/root/WindowsR/.../100742、
#   .dev/vehicle_axle/truth/100742；全缺则 analyze.py loud-fail 退码 1（不静默放过）。
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${OUTPUT_DIR:-.dev/vehicle_axle}"
mkdir -p "$OUT"
echo "[vehicle_axle] 分析轴心检测 → $OUT/report.txt"
python3 "$HERE/analyze.py" | tee "$OUT/report.txt"
