#!/usr/bin/env bash
# scan_mask_fusion — M3.17 ① SAM mask 引导融合端到端 harness。
#   造「目标+地面+干扰」场景 → 真 HQ-SAM 逐视角分割 → mask 引导融合只留目标,与无 mask baseline 对照。
# 需同时带 torch(CUDA)+ segment-anything-hq + open3d + trimesh 的 venv(默认 .dev/sam-venv)+ HQ-SAM 权重。
#   建环境(在 sam-venv 基础上补 open3d/trimesh):
#     .dev/sam-venv/bin/pip install "open3d==0.19.0" "trimesh>=4,<5"
#   (sam-venv 本体见 server/sam_service/README:torch cu121 + segment-anything-hq + 权重 aria2c 下载)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
PY="${SAM_PY:-$ROOT/.dev/sam-venv/bin/python}"
export OUTPUT_DIR="${OUTPUT_DIR:-$ROOT/.dev/scan_mask_fusion}"

if [ ! -x "$PY" ] || ! "$PY" -c "import torch, segment_anything_hq, open3d, trimesh" 2>/dev/null; then
  echo "缺带 torch+segment-anything-hq+open3d+trimesh 的 Python:$PY(见本文件头部建环境步骤)" >&2
  exit 2
fi

# 未指定 GPU 时自动挑显存最空的一张(ViT-H 需 ~6-8GB)。
# 注:假设 harness 串行跑;并行多个本 harness 时查询→进程起之间有窗口可能撞同卡 OOM,请显式分卡。
if [ -z "${CUDA_VISIBLE_DEVICES:-}" ] && command -v nvidia-smi >/dev/null 2>&1; then
  export CUDA_VISIBLE_DEVICES="$(nvidia-smi --query-gpu=index,memory.free --format=csv,noheader,nounits \
    | sort -t, -k2 -nr | head -1 | cut -d, -f1 | tr -d ' ')"
  echo "自动选 GPU CUDA_VISIBLE_DEVICES=$CUDA_VISIBLE_DEVICES(显存最空)"
fi

"$PY" "$ROOT/tests/harness/scan_mask_fusion/mask_fusion_bench.py"
