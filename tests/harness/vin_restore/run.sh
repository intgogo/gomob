#!/usr/bin/env bash
# VIN 还原离线自测 harness —— 脱离真机，验证"同一 VIN 多角度还原图重合 + 严格 metric"。
# 输入：端侧 VinCaptureViewModel 落盘的 .dev/vin_captures/cap_*/（rgb1300.jpg + depth.yuv + meta.json）。
# 链路（与服务端 Go restore 包逐函数对齐）：
#   深度反投影(尺度订正 DEPTH_SCALE) → OBB 区 RANSAC 平面去透视 → tilt>70 门 →
#   **固定 mm/px 度量网格正射**(端口 native/vin/ortho_rectify.cpp，不钉角点→视角无关) → 去阴影质量闸 → 叠刻度尺。
# 产出（_metric/_ruled/_sig.png + overview）+ 结论写 .dev/vin_restore/。
set -euo pipefail
cd "$(dirname "$0")/../../.."        # → 仓根
CAPS="${1:-.dev/vin_captures}"
OUT="${OUTPUT_DIR:-.dev/vin_restore}"   # 产物落点可被环境变量 OUTPUT_DIR 覆盖（默认 .dev/vin_restore）
MODEL=".dev/vin_models/yolo-obb.onnx"
export OUTPUT_DIR="$OUT"                 # 透传给 ortho_metric.py，保证还原与分析落同一目录

[ -d "$CAPS" ] || { echo "无采集数据 $CAPS（先真机拍 + adb pull）"; exit 1; }
[ -f "$MODEL" ] || { echo "缺 YOLO 模型 $MODEL（从 VINCreator APK assets/model/yolo-obb.onnx 拷入）"; exit 1; }

echo "== 1) 还原（深度平面 + 固定 mm/px 度量网格 + 去阴影质量闸 + 刻度尺）=="
python3 tests/harness/vin_restore/ortho_metric.py "$CAPS" "$OUT"

echo "== 2) 重合分析（同 VIN 多角度 _sig 叠加对齐）=="
python3 tests/harness/vin_restore/analyze.py "$OUT" || true
