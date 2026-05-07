#!/usr/bin/env bash
# Berxel Android SDK jar 二进制补丁应用脚本
# 详见 BerxelJarPatch.java 顶部注释

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAR_DIR="$ROOT/third_party/berxel-android/libs"
JAR_NAME="BerxelSDK.jar"
JAR_IN="$JAR_DIR/$JAR_NAME"
JAR_BAK="$JAR_DIR/$JAR_NAME.orig"
JAR_TMP="$JAR_DIR/$JAR_NAME.patched.tmp"

ASM_DIR="${ASM_DIR:-/root/.gradle/caches/modules-2/files-2.1/org.ow2.asm}"
ASM_JAR="$(find "$ASM_DIR/asm/9.6" -name 'asm-9.6.jar' 2>/dev/null | head -1)"
ASM_TREE_JAR="$(find "$ASM_DIR/asm-tree/9.6" -name 'asm-tree-9.6.jar' 2>/dev/null | head -1)"

if [[ -z "$ASM_JAR" || -z "$ASM_TREE_JAR" ]]; then
  echo "asm-9.6.jar / asm-tree-9.6.jar 找不到。在 $ASM_DIR 下" >&2
  echo "若 Gradle 缓存路径不同，传 ASM_DIR=<路径> 重跑" >&2
  exit 1
fi

if [[ ! -f "$JAR_IN" ]]; then
  echo "找不到原 jar: $JAR_IN" >&2
  exit 1
fi

# 备份原 jar（首次 patch 时）
if [[ ! -f "$JAR_BAK" ]]; then
  echo "[patch] 备份原 jar → $JAR_BAK"
  cp "$JAR_IN" "$JAR_BAK"
fi

# 编译 patcher
PATCH_DIR="$ROOT/patches/berxel-android"
PATCH_BUILD="$PATCH_DIR/build"
mkdir -p "$PATCH_BUILD"
echo "[patch] 编译 BerxelJarPatch.java"
javac -d "$PATCH_BUILD" -cp "$ASM_JAR:$ASM_TREE_JAR" "$PATCH_DIR/BerxelJarPatch.java"

# 跑 patch（用原始备份做输入，输出到临时文件再 mv —— 避免中途崩了 jar 半残）
echo "[patch] 跑 BerxelJarPatch ${JAR_BAK} → ${JAR_TMP}"
java -cp "$PATCH_BUILD:$ASM_JAR:$ASM_TREE_JAR" BerxelJarPatch "$JAR_BAK" "$JAR_TMP"
mv "$JAR_TMP" "$JAR_IN"

# 校验：用 javap 看 patch 后的方法字节码里是否真的换了
echo "[patch] 验证 patch 结果："
unzip -p "$JAR_IN" com/berxel/berxelInterface/api/admitmanager/BerxelHawkUsbManager.class \
  > "$PATCH_BUILD/BerxelHawkUsbManager.class.patched"
javap -c -p -classpath "$PATCH_BUILD" \
  "com.berxel.berxelInterface.api.admitmanager.BerxelHawkUsbManager" \
  2>&1 | grep -B1 -A1 "PendingIntent.getBroadcast" || true

echo "[patch] OK — Gradle 下次构建会用 patched jar"
