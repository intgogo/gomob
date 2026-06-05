#!/usr/bin/env bash
# laser_roam_cropbox harness/run.sh — 跑「走一圈→拟合车位框 OBB」几何验收（RoamBoxFitTest）并落 JUnit 结果。
#
# 验证 M10.4 漫游标注核心几何：足迹 (u,v) → 凸包 + 最小面积外接矩形 → 该镜头系 ScanCropBox。
# 锁：尺寸恢复 / 紧包合成车 / 远点排除 / 退化回 null。host JVM 测试，无需真机。
#
# 输出：.dev/laser-roam-cropbox/ 含 JUnit XML + gradle.log；analyze.py 读它判定 正常/异常。
set -euo pipefail
cd "$(dirname "$0")/../../.."

OUT_ROOT="${OUTPUT_DIR:-.dev/laser-roam-cropbox}"
mkdir -p "$OUT_ROOT"

echo "==> run RoamBoxFitTest (walk→OBB 几何)"
./gradlew :feature:scan3d:testDebugUnitTest \
    --tests "io.gomob.feature.scan3d.RoamBoxFitTest" --rerun-tasks \
    2>&1 | tee "$OUT_ROOT/gradle.log" || true

XML_DIR="feature/scan3d/build/test-results/testDebugUnitTest"
rm -f "$OUT_ROOT"/TEST-*.xml
if [ -d "$XML_DIR" ]; then
    cp -f "$XML_DIR"/TEST-*RoamBoxFitTest*.xml "$OUT_ROOT/" 2>/dev/null \
        || cp -f "$XML_DIR"/TEST-*.xml "$OUT_ROOT/" 2>/dev/null || true
fi

echo "==> analyze"
python3 tests/harness/laser_roam_cropbox/analyze.py "$OUT_ROOT"
