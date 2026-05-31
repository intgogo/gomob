#!/usr/bin/env bash
# sam_segmentation — M3.17 HQ-SAM 高精度分割 harness。
#   自包含合成场景(星形前景+纹理背景)→ 人工松框 → sam_core.segment → IoU vs GT。门:IoU ≥ 0.92。
# 需带 torch(CUDA)+ segment-anything-hq 的 venv(默认 .dev/sam-venv)+ HQ-SAM 权重(weights/sam_hq_vit_h.pth)。
#   建环境:python3.11 -m venv .dev/sam-venv
#           .dev/sam-venv/bin/pip install torch torchvision --index-url https://download.pytorch.org/whl/cu121
#           .dev/sam-venv/bin/pip install -r server/sam_service/requirements.txt
#   下权重:aria2c -x16 -c -d server/sam_service/weights -o sam_hq_vit_h.pth \
#             https://huggingface.co/lkeab/hq-sam/resolve/main/sam_hq_vit_h.pth
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
PY="${SAM_PY:-$ROOT/.dev/sam-venv/bin/python}"
export OUTPUT_DIR="${OUTPUT_DIR:-$ROOT/.dev/sam_segmentation}"

if [ ! -x "$PY" ] || ! "$PY" -c "import torch, segment_anything_hq" 2>/dev/null; then
  echo "缺带 torch + segment-anything-hq 的 Python:$PY(见本文件头部建环境步骤)" >&2
  exit 2
fi

# 未指定 GPU 时自动挑显存最空的一张(ViT-H 需 ~6-8GB,避免撞到被占用的卡 OOM)。
# 注:假设 harness 串行跑;若并行跑多个本 harness,查询→进程起之间有窗口可能撞同卡 OOM,
# 并行场景请显式给每个 CUDA_VISIBLE_DEVICES 分卡。
if [ -z "${CUDA_VISIBLE_DEVICES:-}" ] && command -v nvidia-smi >/dev/null 2>&1; then
  export CUDA_VISIBLE_DEVICES="$(nvidia-smi --query-gpu=index,memory.free --format=csv,noheader,nounits \
    | sort -t, -k2 -nr | head -1 | cut -d, -f1 | tr -d ' ')"
  echo "自动选 GPU CUDA_VISIBLE_DEVICES=$CUDA_VISIBLE_DEVICES(显存最空)"
fi

"$PY" "$ROOT/tests/harness/sam_segmentation/seg_bench.py"
