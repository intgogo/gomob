#!/usr/bin/env bash
# 检查 FireRedASR2S 服务启动所需代码、模型和工具。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
FIRERED_REPO="${GOMOB_FIRERED_ASR2S_REPO:-$ROOT/.dev/vendor/FireRedASR2S}"
MODEL_ROOT="${GOMOB_FIRERED_MODEL_ROOT:-$ROOT/.dev/asr_models/pretrained_models}"
python_bin="${GOMOB_ASR_PYTHON:-$ROOT/.dev/asr_service/venv/bin/python}"
if [[ -d "$FIRERED_REPO" ]]; then
    export PYTHONPATH="$FIRERED_REPO${PYTHONPATH:+:$PYTHONPATH}"
fi
if [[ -x "$python_bin" ]]; then
    export PATH="$(dirname "$python_bin"):$PATH"
fi

check_dir() {
    local label="$1"
    local path="$2"
    if [[ -d "$path" ]]; then
        echo "ok $label: $path"
    else
        echo "missing $label: $path"
        return 1
    fi
}

failed=0
check_dir "FireRedASR2S repo" "$FIRERED_REPO" || failed=1
check_dir "FireRedASR2-AED" "${GOMOB_FIRERED_ASR_MODEL_DIR:-$MODEL_ROOT/FireRedASR2-AED}" || failed=1
check_dir "FireRedVAD/VAD" "${GOMOB_FIRERED_VAD_DIR:-$MODEL_ROOT/FireRedVAD/VAD}" || failed=1
check_dir "FireRedLID" "${GOMOB_FIRERED_LID_DIR:-$MODEL_ROOT/FireRedLID}" || failed=1
check_dir "FireRedPunc" "${GOMOB_FIRERED_PUNC_DIR:-$MODEL_ROOT/FireRedPunc}" || failed=1

if command -v ffmpeg >/dev/null 2>&1; then
    echo "ok ffmpeg: $(command -v ffmpeg)"
else
    if [[ -x "$python_bin" ]] && "$python_bin" - <<'PY'
import imageio_ffmpeg
print(imageio_ffmpeg.get_ffmpeg_exe())
PY
    then
        echo "ok ffmpeg: imageio-ffmpeg"
    else
        echo "missing ffmpeg: ASR 服务需要 ffmpeg 或 imageio-ffmpeg 转 16kHz mono wav"
        failed=1
    fi
fi

if [[ -x "$python_bin" ]]; then
    "$python_bin" - <<'PY' || failed=1
import sys
print("ok python:", sys.version.split()[0])
for name in ["fastapi", "uvicorn", "fireredasr2s", "torch", "soundfile"]:
    try:
        mod = __import__(name)
        print("ok", name, getattr(mod, "__version__", ""))
    except Exception as exc:
        print("missing", name, type(exc).__name__, str(exc)[:120])
        raise SystemExit(1)
PY
else
    echo "missing python env: $python_bin"
    failed=1
fi

exit "$failed"
