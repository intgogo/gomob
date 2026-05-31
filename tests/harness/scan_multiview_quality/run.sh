#!/usr/bin/env bash
# scan_multiview_quality — M3.16 多视角重建端到端质量评估 harness(Open3D)。
#   合成 Stanford Bunny 8 角度 RGBD → fusion_core 重建 → 量 chamfer / 覆盖度 / UV 利用率。
#   硬门 = chamfer ≤ 5mm + coverage(@5mm≥78% / @10mm≥92%);UV 利用率软报告(见 README)。
#   真实卡车数据:export GOMOB_TRUCK_DATASET=<RgbdShot bundle .zip> 则一并跑(无 GT,仅统计)。
# 需带 open3d 的 Python(默认 .dev/fusion-venv;CI/容器用 server/fusion_service/requirements.txt)。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
PY="${FUSION_PY:-$ROOT/.dev/fusion-venv/bin/python}"
export OUTPUT_DIR="${OUTPUT_DIR:-$ROOT/.dev/scan_multiview_quality}"

if [ ! -x "$PY" ] || ! "$PY" -c "import open3d" 2>/dev/null; then
  echo "缺少带 open3d 的 Python:$PY" >&2
  echo "建环境:python3.11 -m venv .dev/fusion-venv && .dev/fusion-venv/bin/pip install -r server/fusion_service/requirements.txt" >&2
  exit 2
fi

HERE="$ROOT/tests/harness/scan_multiview_quality"
# run.sh 只采样(写 metrics.json);判定交给 analyze.py —— `./dev.sh harness` 会自动用系统 python3 跑它。
"$PY" "$HERE/quality_bench.py"
