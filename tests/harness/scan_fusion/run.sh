#!/usr/bin/env bash
# scan_fusion — M3.14 云端多视角融合算法核 harness(Open3D)。
#   合成已知 GT 物体 → 多视角带噪 RGBD → fusion_core.fuse() → 对齐 GT 算 chamfer。
#   门:① 干净 chamfer ≤ 5mm ② 带噪 conf 加权 chamfer ≤ 不加权。
# 需带 open3d 的 Python(默认 .dev/fusion-venv;CI/容器用 server/fusion_service/requirements.txt)。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
PY="${FUSION_PY:-$ROOT/.dev/fusion-venv/bin/python}"

if [ ! -x "$PY" ] || ! "$PY" -c "import open3d" 2>/dev/null; then
  echo "缺少带 open3d 的 Python:$PY" >&2
  echo "建环境:python3.11 -m venv .dev/fusion-venv && .dev/fusion-venv/bin/pip install -r server/fusion_service/requirements.txt" >&2
  exit 2
fi

"$PY" "$ROOT/tests/harness/scan_fusion/fusion_bench.py"
