#!/usr/bin/env bash
# vehicle_cargobox harness：货箱分割 + 外长/外宽/箱深，判定是否达标。
# 用法: ./dev.sh harness vehicle_cargobox  或  tests/harness/vehicle_cargobox/run.sh
# 真值会话可用 JCHY_DATA 覆盖（默认 /root/WindowsR/JCHY_OFFLINE/Data/100742）。
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${OUTPUT_DIR:-.dev/vehicle_cargobox}"
mkdir -p "$OUT"
echo "[vehicle_cargobox] 货箱分割+尺寸 → $OUT/report.txt"
python3 "$HERE/analyze.py" | tee "$OUT/report.txt"
