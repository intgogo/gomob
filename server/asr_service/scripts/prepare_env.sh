#!/usr/bin/env bash
# 准备 FireRedASR2S 推理服务 Python 环境。
#
# 默认把环境建在 .dev/asr_service/venv；如果系统没有 Python 3.11+，
# 且存在 conda，则改用 .dev/asr_service/conda。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVICE_DIR="$ROOT/server/asr_service"
DEV_DIR="$ROOT/.dev/asr_service"
FIRERED_REPO="${GOMOB_FIRERED_ASR2S_REPO:-$ROOT/.dev/vendor/FireRedASR2S}"
MODEL_ROOT="${GOMOB_FIRERED_MODEL_ROOT:-$ROOT/.dev/asr_models/pretrained_models}"
mkdir -p "$DEV_DIR"

pick_python() {
    if [[ -n "${GOMOB_ASR_PYTHON:-}" ]]; then
        echo "$GOMOB_ASR_PYTHON"
        return
    fi
    for bin in python3.12 python3.11; do
        if command -v "$bin" >/dev/null 2>&1; then
            echo "$bin"
            return
        fi
    done
    echo ""
}

python_bin="$(pick_python)"
if [[ -z "$python_bin" ]]; then
    if command -v conda >/dev/null 2>&1; then
        env_dir="$DEV_DIR/conda"
        if [[ ! -x "$env_dir/bin/python" ]]; then
            conda create -y -p "$env_dir" python=3.11
        fi
        python_bin="$env_dir/bin/python"
    else
        echo "缺少 Python 3.11+；请安装 python3.11，或设置 GOMOB_ASR_PYTHON" >&2
        exit 1
    fi
fi

if ! "$python_bin" - <<'PY'
import sys
raise SystemExit(0 if sys.version_info >= (3, 11) else 1)
PY
then
    echo "FireRedASR2S 要求 Python 3.11+，当前: $("$python_bin" --version)" >&2
    exit 1
fi

venv_dir="$DEV_DIR/venv"
if [[ "$python_bin" == "$DEV_DIR/conda/bin/python" ]]; then
    env_python="$python_bin"
else
    if [[ ! -x "$venv_dir/bin/python" ]]; then
        "$python_bin" -m venv "$venv_dir"
    fi
    env_python="$venv_dir/bin/python"
fi

"$env_python" -m pip install --upgrade pip wheel "setuptools<81"
"$env_python" -m pip install -r "$SERVICE_DIR/requirements.txt"
if [[ -f "$FIRERED_REPO/pyproject.toml" ]]; then
    if [[ -f "$FIRERED_REPO/requirements.txt" ]]; then
        "$env_python" -m pip install -r "$FIRERED_REPO/requirements.txt"
    fi
    "$env_python" -m pip install -e "$FIRERED_REPO" --no-deps
else
    echo "FireRedASR2S 仓库不存在: $FIRERED_REPO" >&2
    exit 1
fi

cat > "$DEV_DIR/env.sh" <<EOF
export GOMOB_ASR_PYTHON="$env_python"
export GOMOB_FIRERED_ASR2S_REPO="$FIRERED_REPO"
export GOMOB_FIRERED_MODEL_ROOT="$MODEL_ROOT"
EOF
if [[ -n "${CUDA_VISIBLE_DEVICES:-}" ]]; then
    printf 'export CUDA_VISIBLE_DEVICES="%s"\n' "$CUDA_VISIBLE_DEVICES" >> "$DEV_DIR/env.sh"
fi

echo "ASR Python: $env_python"
echo "环境变量: $DEV_DIR/env.sh"
