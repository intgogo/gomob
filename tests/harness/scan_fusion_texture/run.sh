#!/usr/bin/env bash
# scan_fusion_texture — M3.14 UV-atlas 纹理烘焙行为基准。
#   合成纹理体 + 低多边形融合 → compute_uvatlas + project_images_to_albedo 烘焙 albedo
#   → 在三角内部点比较「纹理采样色」vs「顶点色插值」对 GT 表面色的误差,门:纹理误差 < 0.85×顶点色误差。
# 需带 open3d/trimesh 的 venv(.dev/fusion-venv)。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
PY="${FUSION_PY:-$ROOT/.dev/fusion-venv/bin/python}"
if [ ! -x "$PY" ] || ! "$PY" -c "import open3d, trimesh" 2>/dev/null; then
  echo "缺带 open3d/trimesh 的 Python:$PY" >&2; exit 2
fi
"$PY" "$ROOT/tests/harness/scan_fusion_texture/texture_bench.py"
