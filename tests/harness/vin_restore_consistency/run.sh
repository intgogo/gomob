#!/usr/bin/env bash
# VIN 多角度还原一致性：生产 Go Restore 批量跑历史实拍，再判居中/水平/尺度/重合。
set -euo pipefail

cd "$(dirname "$0")/../../.."

OUT="${OUTPUT_DIR:-.dev/vin_restore_consistency}"
MODEL="${VIN_OBB_MODEL:-.dev/vin_models/yolo-obb.onnx}"
CHAR_MODEL="${VIN_CHAR_MODEL:-.dev/vin_models/vins0.onnx}"
CALIB_DIR="${GOMOB_VIN_FACTORY_CALIBRATION_DIR:-/root/WindowsR}"
# 默认使用当前 BF301208 rig 的全分辨率原厂黄金数据；旧 manifest.json 只供显式历史诊断。
MANIFEST="${VIN_CONSISTENCY_MANIFEST:-tests/harness/vin_restore_consistency/manifest_factory_bf301208.json}"
ORT_LIB="${VIN_ORT_LIB:-}"
if [[ -z "$ORT_LIB" ]]; then
  if [[ -f .dev/onnxruntime/lib/libonnxruntime.so.1.18.1 ]]; then
    ORT_LIB="$PWD/.dev/onnxruntime/lib"
  elif [[ -f /usr/local/onnxruntime/lib/libonnxruntime.so.1.18.1 ]]; then
    ORT_LIB=/usr/local/onnxruntime/lib
  else
    echo "缺 host ONNX Runtime 1.18.1；请把库放到 .dev/onnxruntime/lib/" >&2
    exit 1
  fi
fi

[[ -f "$MODEL" ]] || { echo "缺 VIN OBB 模型: $MODEL" >&2; exit 1; }
[[ -f "$CHAR_MODEL" ]] || { echo "缺 VIN 逐字符模型: $CHAR_MODEL" >&2; exit 1; }
[[ -f "$MANIFEST" ]] || { echo "缺一致性数据 manifest: $MANIFEST" >&2; exit 1; }
OUT_ABS="$(realpath -m "$OUT")"
MODEL_ABS="$(realpath "$MODEL")"
CHAR_MODEL_ABS="$(realpath "$CHAR_MODEL")"
MANIFEST_ABS="$(realpath "$MANIFEST")"
rm -rf "$OUT"
mkdir -p "$OUT/.inputs"

python3 - "$MANIFEST_ABS" "$OUT_ABS/.inputs" "$PWD" <<'PY'
import json
import sys
from pathlib import Path

manifest = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
out = Path(sys.argv[2])
root = Path(sys.argv[3])
names = []
for group in manifest["groups"]:
    name = group["physical_object_id"]
    names.append(name)
    paths = []
    for capture in group["captures"]:
        path = Path(capture["path"])
        paths.append(str(path if path.is_absolute() else root / path))
    (out / f"{name}.txt").write_text("\n".join(paths) + "\n", encoding="utf-8")
(out / "groups.txt").write_text("\n".join(names) + "\n", encoding="utf-8")
PY

run_group() {
  local name="$1"
  local list_file="$OUT_ABS/.inputs/$name.txt"
  echo "== $name =="
  VIN_CONSISTENCY=1 \
  VIN_OBB_MODEL="$MODEL_ABS" \
  VIN_CHAR_MODEL="$CHAR_MODEL_ABS" \
  VIN_CAP_LIST="$list_file" \
  VIN_RESTORE_OUT="$OUT_ABS/$name" \
  GOMOB_VIN_FACTORY_CALIBRATION_DIR="$CALIB_DIR" \
  LD_LIBRARY_PATH="$ORT_LIB:/usr/local/lib64:/usr/local/lib:${LD_LIBRARY_PATH:-}" \
    bash -c "cd server && go test ./internal/cvengine/restore -run '^TestRestoreConsistencyBatch$' -count=1 -v"
}

while IFS= read -r group; do
  [[ -n "$group" ]] && run_group "$group"
done < "$OUT_ABS/.inputs/groups.txt"
