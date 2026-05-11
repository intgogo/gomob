#!/usr/bin/env bash
# 启动 FireRedASR2S HTTP 服务。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
DEV_DIR="$ROOT/.dev/asr_service"
mkdir -p "$DEV_DIR"

if [[ -f "$DEV_DIR/env.sh" ]]; then
    # shellcheck disable=SC1091
    source "$DEV_DIR/env.sh"
fi

export GOMOB_ASR_PYTHON="${GOMOB_ASR_PYTHON:-$DEV_DIR/venv/bin/python}"
export GOMOB_FIRERED_ASR2S_REPO="${GOMOB_FIRERED_ASR2S_REPO:-$ROOT/.dev/vendor/FireRedASR2S}"
export GOMOB_FIRERED_MODEL_ROOT="${GOMOB_FIRERED_MODEL_ROOT:-$ROOT/.dev/asr_models/pretrained_models}"
export PYTHONPATH="$GOMOB_FIRERED_ASR2S_REPO${PYTHONPATH:+:$PYTHONPATH}"

"$ROOT/server/asr_service/scripts/doctor.sh"

log="$DEV_DIR/asr-service.log"
pidfile="$DEV_DIR/asr-service.pid"
if [[ -f "$pidfile" ]] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
    echo "ASR 服务已运行: pid=$(cat "$pidfile") log=$log"
    exit 0
fi

setsid bash -c 'cd "$1/server/asr_service" && exec "$2" app.py' _ "$ROOT" "$GOMOB_ASR_PYTHON" >"$log" 2>&1 < /dev/null &
echo $! > "$pidfile"

echo "ASR 服务启动中: pid=$(cat "$pidfile") log=$log"
for _ in $(seq 1 180); do
    if ! kill -0 "$(cat "$pidfile")" 2>/dev/null; then
        echo "ASR 服务启动失败，最近日志:" >&2
        tail -n 80 "$log" >&2 || true
        exit 1
    fi
    if curl -fsS "http://127.0.0.1:${GOMOB_ASR_PORT:-18091}/healthz" >/tmp/gomob-asr-health.json 2>/dev/null; then
        cat /tmp/gomob-asr-health.json
        echo
        echo "ASR 服务已就绪: http://127.0.0.1:${GOMOB_ASR_PORT:-18091}"
        exit 0
    fi
    sleep 1
done
echo "ASR 服务未就绪，最近日志:" >&2
tail -n 80 "$log" >&2 || true
exit 1
echo
