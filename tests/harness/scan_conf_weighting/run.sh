#!/usr/bin/env bash
# scan_conf_weighting — 置信加权 TSDF/ICP 重建的「行为好不好」harness
#   [1] 合成球面基准(确定性,有真值):加权 vs 均权重建表面 RMS/覆盖/内点 → 硬判定门。
#   [2] 真实硬件数据(探索性):host_capture 采 density-first depth + light-IR,算 conf,
#       加权 vs 均权 TSDF,以时域中值面为参考算 chamfer → 相对趋势观测。
# 公式正确性见单测 tests/native_host/conf_weight_test.cpp(scripts/native-host-test.sh)。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
HERE="$ROOT/tests/harness/scan_conf_weighting"
OUT="${OUTPUT_DIR:-$ROOT/.dev/scan_conf_weighting}"
SDK="${BERXEL_SDK:-$ROOT/.dev/berxel-sdk-extract/BerxelSDK-Linux-2.0.190}"
CAP="${CAPTURE_DIR:-$ROOT/.dev/depth_ir_guided/host_capture}"
mkdir -p "$OUT"

CXX="g++ -std=c++17 -O2 -Ithird_party/eigen-3.4.0 -Inative \
  -Inative/berxel/host/include -Inative/berxel/portable"
cd "$ROOT"

echo "[1/2] 合成球面基准(确定性硬门)"
$CXX "$HERE/recon_conf_bench.cpp" \
  native/reconstruction/tsdf.cpp native/reconstruction/marching_cubes.cpp \
  native/depth/depth_projection.cpp -o "$OUT/recon_conf_bench"
"$OUT/recon_conf_bench"
SYNTH_RC=$?

echo
echo "[2/2] 真实硬件数据基准(探索性)"
# 没有现成 host_capture 数据时,若 SDK + 相机在,顺手采一批
if [ ! -f "$CAP/depth_00.raw" ] && [ -d "$SDK/Include" ] && [ -f "$SDK/libs/libBerxelHawk.so" ]; then
  echo "  无现成 host_capture 数据,尝试现采 18 帧..."
  HCAP_SRC="$ROOT/tests/harness/depth_ir_guided/host_capture.cpp"
  if [ -f "$HCAP_SRC" ]; then
    $CXX "$HCAP_SRC" -L"$SDK/libs" -Wl,-rpath,"$SDK/libs" -lBerxelHawk -o "$OUT/host_capture" 2>/dev/null \
      && LD_LIBRARY_PATH="$SDK/libs" timeout 120 "$OUT/host_capture" --out-dir "$CAP" --frames 18 --fps 30 \
           >/dev/null 2>&1 || echo "  采集失败(相机未插好/占用/供电),跳过真实数据步"
  fi
fi
if [ -f "$CAP/depth_00.raw" ]; then
  $CXX "$HERE/real_recon_bench.cpp" \
    native/reconstruction/tsdf.cpp native/reconstruction/marching_cubes.cpp \
    native/depth/depth_projection.cpp native/berxel/portable/gomob_berxel_portable.cpp \
    -o "$OUT/real_recon_bench"
  "$OUT/real_recon_bench" "$CAP" || true
else
  echo "  无 $CAP/depth_*.raw,跳过真实数据步(合成门已覆盖判定)"
fi

echo
echo "=== 判定取合成基准(确定性) rc=$SYNTH_RC ==="
exit $SYNTH_RC
