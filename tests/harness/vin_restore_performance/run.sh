#!/usr/bin/env bash
# VIN 还原性能回归：真实 RGBD 成功路径、判废路径与逐字节等价性。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SERVER_DIR="$ROOT/server"
OUT="${OUTPUT_DIR:-$ROOT/.dev/vin_restore_performance}"
PORT="${VIN_RESTORE_PERF_PORT:-18812}"
URL="http://127.0.0.1:${PORT}/cv/ocr/v1/vin_restore"
MODEL="${VIN_OBB_MODEL:-$ROOT/.dev/vin_models/yolo-obb.onnx}"
CHAR_MODEL="${VIN_CHAR_MODEL:-$ROOT/.dev/vin_models/vins0.onnx}"
CALIB_DIR="${GOMOB_VIN_FACTORY_CALIBRATION_DIR:-/root/WindowsR}"
SUCCESS_CAPTURE="${VIN_RESTORE_PERF_SUCCESS_CAPTURE:-$ROOT/.dev/vin-live-captures/cap_035_1784196074776}"
REJECT_CAPTURE="${VIN_RESTORE_PERF_REJECT_CAPTURE:-$ROOT/.dev/vin-live-captures/cap_034_1784196063978}"
SUCCESS_COUNT="${VIN_RESTORE_PERF_COUNT:-5}"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

if [[ ! "$PORT" =~ ^[0-9]+$ ]] || (( PORT < 1024 || PORT > 65535 )); then
  echo "VIN_RESTORE_PERF_PORT 非法: $PORT" >&2
  exit 2
fi
if ss -ltn "sport = :$PORT" | tail -n +2 | grep -q .; then
  echo "端口 $PORT 已占用；请设置 VIN_RESTORE_PERF_PORT" >&2
  exit 2
fi

for required in \
  "$MODEL" \
  "$CHAR_MODEL" \
  "$SUCCESS_CAPTURE/meta.json" \
  "$SUCCESS_CAPTURE/rgb1300.jpg" \
  "$SUCCESS_CAPTURE/depth.yuv" \
  "$SUCCESS_CAPTURE/restored.png" \
  "$REJECT_CAPTURE/meta.json" \
  "$REJECT_CAPTURE/rgb1300.jpg" \
  "$REJECT_CAPTURE/depth.yuv"; do
  [[ -f "$required" ]] || { echo "缺性能回归输入: $required" >&2; exit 2; }
done

if [[ -f "$ROOT/.dev/onnxruntime/lib/libonnxruntime.so.1.18.1" ]]; then
  ORT_LIB="$ROOT/.dev/onnxruntime/lib"
elif [[ -f /usr/local/onnxruntime/lib/libonnxruntime.so.1.18.1 ]]; then
  ORT_LIB=/usr/local/onnxruntime/lib
else
  echo "缺 ONNX Runtime 1.18.1" >&2
  exit 2
fi
RUNTIME_LIBS="$ORT_LIB:/usr/local/lib:/usr/local/lib64:/usr/local/onnxruntime/lib"

rm -rf "$OUT"
mkdir -p "$OUT"
BIN="$OUT/gomob-cvengine"
SERVER_LOG="$OUT/cvengine.log"

log "编译旁路 cvengine"
(
  cd "$SERVER_DIR"
  LD_LIBRARY_PATH="$RUNTIME_LIBS" go build -o "$BIN" ./cmd/cvengine
) >"$OUT/build.log" 2>&1

MODEL_SPEC="VINOBB:com=$MODEL,VINCHAR:yolo=$CHAR_MODEL:0|1|2|3|4|5|6|7|8|9|A|B|C|D|E|F|G|H|J|K|L|M|N|P|R|S|T|U|V|W|X|Y|Z"
log "启动旁路服务 :$PORT（ORT device=-1，多线程 CPU）"
env \
  LD_LIBRARY_PATH="$RUNTIME_LIBS" \
  GOMOB_CVENGINE_HTTP_ADDR=":$PORT" \
  GOMOB_CVENGINE_ORT_DEVICE_ID=-1 \
  GOMOB_CVENGINE_MODELS="$MODEL_SPEC" \
  GOMOB_VIN_FACTORY_CALIBRATION_DIR="$CALIB_DIR" \
  GOMOB_VIN_FACTORY_CALIBRATION_REQUIRED=true \
  GOMOB_VIN_RESTORE_MODELS_REQUIRED=true \
  "$BIN" >"$SERVER_LOG" 2>&1 &
PID=$!

cleanup() {
  kill "$PID" 2>/dev/null || true
  wait "$PID" 2>/dev/null || true
}
trap cleanup EXIT

ready=false
for _ in {1..180}; do
  if ! kill -0 "$PID" 2>/dev/null; then
    tail -n 80 "$SERVER_LOG" >&2
    echo "cvengine 启动失败" >&2
    exit 3
  fi
  if curl -fsS --max-time 2 "http://127.0.0.1:${PORT}/readyz" >"$OUT/ready.json" 2>/dev/null; then
    ready=true
    break
  fi
  sleep 1
done
[[ "$ready" == true ]] || { tail -n 80 "$SERVER_LOG" >&2; echo "cvengine 就绪超时" >&2; exit 3; }

{
  printf 'cpu_count=%s\n' "$(nproc)"
  printf 'load_average='
  cut -d ' ' -f 1-3 /proc/loadavg
  printf 'ort_device_id=-1\n'
  printf 'success_count=%s\n' "$SUCCESS_COUNT"
} >"$OUT/environment.txt"

log "采样成功还原路径 × $SUCCESS_COUNT"
python3 "$ROOT/tests/harness/vin_restore_performance/sample.py" \
  --url "$URL" \
  --capture "$SUCCESS_CAPTURE" \
  --count "$SUCCESS_COUNT" \
  --output "$OUT/success.json" \
  --save-first-png "$OUT/first-restored.png"

log "采样 VIN 未检出判废路径"
python3 "$ROOT/tests/harness/vin_restore_performance/sample.py" \
  --url "$URL" \
  --capture "$REJECT_CAPTURE" \
  --count 1 \
  --output "$OUT/reject.json"

log "采样完成: $OUT"
