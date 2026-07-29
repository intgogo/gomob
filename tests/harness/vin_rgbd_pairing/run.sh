#!/usr/bin/env bash
# VIN RGBD 配对 harness：验证回调边界、点击后 burst、跳帧、全局最优配对与禁止复用。
set -euo pipefail
cd "$(dirname "$0")/../../.."

OUT="${OUTPUT_DIR:-.dev/vin_rgbd_pairing}"
mkdir -p "$OUT/classes"

./gradlew :core:model:bundleLibCompileToJarDebug :feature:scan3d:compileDebugKotlin --offline >/dev/null

KOTLIN_STDLIB="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.1.0" -name '*.jar' | head -n 1)"
COROUTINES="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0" -name '*.jar' | head -n 1)"
MODEL_JAR="core/model/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar"
FEATURE_CLASSES="feature/scan3d/build/tmp/kotlin-classes/debug"
CP="$FEATURE_CLASSES:$MODEL_JAR:$KOTLIN_STDLIB:$COROUTINES"

javac -encoding UTF-8 -cp "$CP" -d "$OUT/classes" \
    tests/harness/vin_rgbd_pairing/PairingProbe.java
java -cp "$OUT/classes:$CP" io.gomob.feature.scan3d.PairingProbe > "$OUT/result.json"
