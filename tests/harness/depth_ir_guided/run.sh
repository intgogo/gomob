#!/usr/bin/env bash
# IR 引导深度精修 harness:
#   [1-2] 交织 dump 上验证「IR 边缘约束区域拟合」(复刻死 API inner_process_with_IR)是否优于 depth-only → 否。
#   [3]   交织 dump 上验证「IR 散斑对比度作深度置信」→ 是(AUC~0.82)。
#   [4]   host 顺序采集 depth+light-IR,在服务器上(无需手机)重验置信 → 复现。
# 产出 + 判定写 .dev/depth_ir_guided/。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
HERE="$ROOT/tests/harness/depth_ir_guided"
DUMP="${DUMP_DIR:-$ROOT/.dev/depth-4b-analysis}"
OUT="${OUTPUT_DIR:-$ROOT/.dev/depth_ir_guided}"
PY="${PYTHON:-/root/lilw/miniconda3/bin/python3}"   # 需 numpy + cv2
SDK="${BERXEL_SDK:-$ROOT/.dev/berxel-sdk-extract/BerxelSDK-Linux-2.0.190}"

if [ ! -d "$DUMP" ] || ! ls "$DUMP"/dual_raw_*.bin >/dev/null 2>&1; then
  echo "缺少交织 dump:$DUMP/dual_raw_*.bin(DUMP 按钮采的 513280B/帧)" >&2
  exit 2
fi

mkdir -p "$OUT"
echo "[1/4] 原型采样 -> $OUT"
"$PY" "$HERE/prototype.py" "$DUMP" "$OUT" "$@"
echo
echo "[2/4] 判定(IR 作边缘)"
# analyze.py 用非零退出表达"IR 边缘引导无益"判定(非错误),不让它中止后续步骤
"$PY" "$HERE/analyze.py" "$OUT" || true
echo
echo "[3/4] IR 作置信/有效性信号验证(交织 dump)"
"$PY" "$HERE/confidence_probe.py" "$DUMP" "$OUT"
echo
echo "[4/4] host 顺序采集重验置信(厂商 SDK,需相机插在本机)"
if [ -d "$SDK/Include" ] && [ -f "$SDK/libs/libBerxelHawk.so" ]; then
  BIN="$OUT/bin/host_capture"
  mkdir -p "$OUT/bin"
  g++ -std=c++17 -O2 -I"$SDK/Include" "$HERE/host_capture.cpp" \
    -L"$SDK/libs" -Wl,-rpath,"$SDK/libs" -lBerxelHawk -o "$BIN" \
    && echo "  编译 host_capture OK" || { echo "  编译失败,跳过 host 重验"; exit 0; }
  HCAP="$OUT/host_capture"
  if LD_LIBRARY_PATH="$SDK/libs" timeout 120 "$BIN" --out-dir "$HCAP" --frames 18 --fps 30 \
       2>&1 | grep -E "TOTAL|saved=" | tail -3; then
    "$PY" "$HERE/host_confidence.py" "$HCAP" || true
  else
    echo "  host 采集失败(相机未插好 / 占用 / 供电不足),跳过"
  fi
else
  echo "  未找到 BerxelSDK-Linux($SDK),跳过 host 重验(交织 dump 步骤已覆盖结论)"
fi
