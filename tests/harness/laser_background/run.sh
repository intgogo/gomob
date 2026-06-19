#!/usr/bin/env bash
# laser_background harness：空工位背景相减（路 B 全自动抠车）的阈值扫参 + 可判定结论。
# 用法: ./dev.sh harness laser_background  或  tests/harness/laser_background/run.sh
# 可选真机闭环：LIVE_PCD=空工位+车的融合云  BG_PCD=空工位背景融合云（同坐标系），有则一并跑真实相减。
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${OUTPUT_DIR:-.dev/laser_background}"
mkdir -p "$OUT"
echo "[laser_background] 背景相减阈值扫参 + 抠车判定 → $OUT/report.txt"
python3 "$HERE/analyze.py" | tee "$OUT/report.txt"
