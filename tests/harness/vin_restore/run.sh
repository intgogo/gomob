#!/usr/bin/env bash
# VIN 还原离线自测 harness —— 脱离真机，验证"同一 VIN 多角度还原图重合"。
# 输入：端侧 VinCaptureViewModel 落盘的 .dev/vin_captures/cap_*/（rgb1300.jpg + depth.yuv + meta.json）。
# 链路（对齐原厂 libcreator_jni.so::restoreImageFlow）：
#   深度 RANSAC 平面 → 摆正 → YOLO 字符 OBB 锚定(yolo-obb.onnx) → 四角单应正射 → picshadow 去阴影。
# 产出 + 结论写 .dev/vin_restore/。
set -euo pipefail
cd "$(dirname "$0")/../../.."        # → 仓根
CAPS="${1:-.dev/vin_captures}"
OUT="${OUTPUT_DIR:-.dev/vin_restore}"   # 产物落点可被环境变量 OUTPUT_DIR 覆盖（默认 .dev/vin_restore）
MODEL=".dev/vin_models/yolo-obb.onnx"
export OUTPUT_DIR="$OUT"                 # 透传给 restore_obb.py，保证还原与分析落同一目录

[ -d "$CAPS" ] || { echo "无采集数据 $CAPS（先真机拍 + adb pull）"; exit 1; }
[ -f "$MODEL" ] || { echo "缺 YOLO 模型 $MODEL（从 VINCreator APK assets/model/yolo-obb.onnx 拷入）"; exit 1; }

echo "== 1) 还原（深度平面 + OBB 锚定四角单应 + picshadow）=="
python3 tests/harness/vin_restore/restore_obb.py "$CAPS"
echo "== 2) 重合度分析（最终去阴影签名，可判定结论）=="
python3 tests/harness/vin_restore/analyze.py "$OUT" "cap_*_sig.png"
