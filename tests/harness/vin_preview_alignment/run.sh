#!/usr/bin/env bash
# VIN 双预览空间对齐：用原厂 BIN 与真机深度帧验证动态投影、覆盖率及固定平移失效程度。
set -euo pipefail

cd "$(dirname "$0")/../../.."

OUT="${OUTPUT_DIR:-.dev/vin_preview_alignment}"
CALIB="${VIN_PREVIEW_CALIBRATION:-/root/WindowsR/VIN_BF301208.bin}"
CAPTURE="${VIN_PREVIEW_CAPTURE:-.dev/vin-live-captures/cap_036_1784196247059}"
HISTORY_ROOT="${VIN_PREVIEW_HISTORY_ROOT:-.dev/vin-live-captures}"

rm -rf "$OUT"
mkdir -p "$OUT"

./gradlew :core:data:compileDebugKotlin :feature:scan3d:compileDebugKotlin --offline >/dev/null
KOTLIN_STDLIB="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.1.0" -name '*.jar' | head -n 1)"
CP="feature/scan3d/build/tmp/kotlin-classes/debug:core/data/build/tmp/kotlin-classes/debug:core/model/build/tmp/kotlin-classes/debug:$KOTLIN_STDLIB"
mkdir -p "$OUT/classes"
javac -encoding UTF-8 -cp "$CP" -d "$OUT/classes" \
    tests/harness/vin_preview_alignment/ProjectionProbe.java
java -cp "$OUT/classes:$CP" io.gomob.feature.scan3d.ProjectionProbe \
    "$CAPTURE/depth.yuv" "$HISTORY_ROOT" > "$OUT/kotlin_result.json"

python3 tests/harness/vin_preview_alignment/probe.py \
    --calibration "$CALIB" \
    --capture "$CAPTURE" \
    --history-root "$HISTORY_ROOT" \
    --roi-contract "$OUT/kotlin_result.json" \
    --output "$OUT/result.json"

python3 tests/harness/vin_preview_alignment/analyze.py "$OUT/result.json" | tee "$OUT/report.txt"
