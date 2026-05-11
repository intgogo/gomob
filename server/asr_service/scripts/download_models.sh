#!/usr/bin/env bash
# 下载 FireRedASR2-AED、VAD、LID、Punc 模型。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
MODEL_ROOT="${GOMOB_FIRERED_MODEL_ROOT:-$ROOT/.dev/asr_models/pretrained_models}"
PROVIDER="${GOMOB_FIRERED_MODEL_PROVIDER:-huggingface}"
python_bin="${GOMOB_ASR_PYTHON:-$ROOT/.dev/asr_service/venv/bin/python}"
if [[ -x "$python_bin" ]]; then
    export PATH="$(dirname "$python_bin"):$PATH"
fi
mkdir -p "$MODEL_ROOT"

download_hf() {
    command -v huggingface-cli >/dev/null 2>&1 || {
        echo "缺少 huggingface-cli：先运行 python -m pip install -U huggingface_hub" >&2
        exit 1
    }
    huggingface-cli download FireRedTeam/FireRedASR2-AED --local-dir "$MODEL_ROOT/FireRedASR2-AED"
    huggingface-cli download FireRedTeam/FireRedVAD --local-dir "$MODEL_ROOT/FireRedVAD"
    huggingface-cli download FireRedTeam/FireRedLID --local-dir "$MODEL_ROOT/FireRedLID"
    huggingface-cli download FireRedTeam/FireRedPunc --local-dir "$MODEL_ROOT/FireRedPunc"
}

download_modelscope() {
    command -v modelscope >/dev/null 2>&1 || {
        echo "缺少 modelscope：先运行 python -m pip install -U modelscope" >&2
        exit 1
    }
    modelscope download --model xukaituo/FireRedASR2-AED --local_dir "$MODEL_ROOT/FireRedASR2-AED"
    modelscope download --model xukaituo/FireRedVAD --local_dir "$MODEL_ROOT/FireRedVAD"
    modelscope download --model xukaituo/FireRedLID --local_dir "$MODEL_ROOT/FireRedLID"
    modelscope download --model xukaituo/FireRedPunc --local_dir "$MODEL_ROOT/FireRedPunc"
}

case "$PROVIDER" in
    huggingface) download_hf ;;
    modelscope) download_modelscope ;;
    *) echo "GOMOB_FIRERED_MODEL_PROVIDER 只支持 huggingface/modelscope" >&2; exit 2 ;;
esac

"$ROOT/server/asr_service/scripts/doctor.sh"
echo "FireRed 模型已就绪: $MODEL_ROOT"
