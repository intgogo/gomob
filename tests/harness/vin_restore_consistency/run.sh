#!/usr/bin/env bash
# VIN 多角度还原一致性：生产 Go Restore 批量跑历史实拍，再判居中/水平/尺度/重合。
set -euo pipefail

cd "$(dirname "$0")/../../.."

OUT="${OUTPUT_DIR:-.dev/vin_restore_consistency}"
# VIN 区域(VMASK)与逐字符(VINS)观测来自外部算法服务，本地不再持模型。
# 验收默认走离线回放，保证结论可复现、不受现场服务状态影响；
# 置 VIN_VISION_RECORD=1 时先连真实服务录一遍再回放（换数据/换模型版本时用）。
RECORD_DIR="${VIN_VISION_REPLAY_DIR:-.dev/vin_vision_records}"
DO_RECORD="${VIN_VISION_RECORD:-0}"
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

[[ -f "$MANIFEST" ]] || { echo "缺一致性数据 manifest: $MANIFEST" >&2; exit 1; }
OUT_ABS="$(realpath -m "$OUT")"
RECORD_DIR_ABS="$(realpath -m "$RECORD_DIR")"
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

# 录制阶段：连真实算法服务跑一遍完整 Restore，把 VMASK/VINS 观测按输入图内容寻址落盘。
# 必须跑完整 Restore —— VINS 吃的 probe 正射图是 Restore 内部由深度平面渲染的中间产物。
record_group() {
  local name="$1"
  local list_file="$OUT_ABS/.inputs/$name.txt"
  echo "== 录制 $name =="
  GOMOB_VIN_FACTORY_CALIBRATION_DIR="$CALIB_DIR" \
  LD_LIBRARY_PATH="$ORT_LIB:/usr/local/lib64:/usr/local/lib:${LD_LIBRARY_PATH:-}" \
    bash -c "cd server && go run ./cmd/vinvisionrecord -caps '$list_file' -out '$RECORD_DIR_ABS'"
}

run_group() {
  local name="$1"
  local list_file="$OUT_ABS/.inputs/$name.txt"
  echo "== $name =="
  VIN_CONSISTENCY=1 \
  VIN_VISION_REPLAY_DIR="$RECORD_DIR_ABS" \
  VIN_CAP_LIST="$list_file" \
  VIN_RESTORE_OUT="$OUT_ABS/$name" \
  GOMOB_VIN_FACTORY_CALIBRATION_DIR="$CALIB_DIR" \
  LD_LIBRARY_PATH="$ORT_LIB:/usr/local/lib64:/usr/local/lib:${LD_LIBRARY_PATH:-}" \
    bash -c "cd server && go test ./internal/cvengine/restore -run '^TestRestoreConsistencyBatch$' -count=1 -v"
}

if [[ "$DO_RECORD" == "1" ]]; then
  mkdir -p "$RECORD_DIR_ABS"
  while IFS= read -r group; do
    [[ -n "$group" ]] && record_group "$group"
  done < "$OUT_ABS/.inputs/groups.txt"
fi

if [[ ! -d "$RECORD_DIR_ABS" ]]; then
  echo "缺视觉观测录制目录 $RECORD_DIR_ABS；首次运行请置 VIN_VISION_RECORD=1 连真实算法服务录一次" >&2
  exit 1
fi

while IFS= read -r group; do
  [[ -n "$group" ]] && run_group "$group"
done < "$OUT_ABS/.inputs/groups.txt"

# 逐字节等价门：同一批观测 + 当前几何代码必须复现出字节相同的 PNG。
# 它只对几何代码回归敏感，对 gosmart 模型版本免疫——那正是把它从连真实服务的
# 性能 harness 里挪过来的原因。基线重建见 VIN_EQUIVALENCE_UPDATE_BASELINE=1。
echo "== 逐字节等价 =="
VIN_VISION_REPLAY_DIR="$RECORD_DIR_ABS" \
GOMOB_VIN_FACTORY_CALIBRATION_DIR="$CALIB_DIR" \
LD_LIBRARY_PATH="$ORT_LIB:/usr/local/lib64:/usr/local/lib:${LD_LIBRARY_PATH:-}" \
  bash -c "cd server && go test ./internal/cvengine/restore -run '^TestRestoreByteEquivalence$' -count=1 -v"
