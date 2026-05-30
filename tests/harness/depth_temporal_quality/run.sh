#!/usr/bin/env bash
# 深度时域降噪 harness：把 C++ P100R3TemporalFilter 套到录制的 depth raw16 序列，
# 验证融合后相邻帧抖动大幅下降、无系统偏移、密度不退化。
#
# 数据源默认复用 berxel_depth_parity 已采的静态场景序列（无需真机）。
# 验收：vendor-dense（原厂参照）序列上 analyze.py 输出 status=OK。
set -euo pipefail

cd "$(dirname "$0")/../../.."
ROOT="$(pwd)"
OUT_DIR="${OUTPUT_DIR:-$ROOT/.dev/depth_temporal_quality}"
BIN_DIR="$OUT_DIR/bin"
mkdir -p "$BIN_DIR"

PY="${PYTHON:-/root/lilw/miniconda3/bin/python3}"
HARNESS_DIR="$ROOT/tests/harness/depth_temporal_quality"

# 数据源：<标签>:<目录>:<glob 后缀>:<宽>:<高>:<gate|ref>
# gate = 硬验收门（代表性 raw 深度）；ref = 仅诊断（补洞后输出/不同场次，不影响 exit）。
PARITY="$ROOT/.dev/berxel_depth_parity"
SEQUENCES=(
  "vendor-dense:$PARITY/vendor-dense:.raw:640:400:gate"
  "host-default:$PARITY/host-default:-active.raw:640:400:ref"
)

echo "=== 编译 apply_filter（portable-only，无 libusb） ==="
g++ -std=c++17 -O2 -Wall -Wextra -Wpedantic \
    -I"$ROOT/native/berxel/host/include" -I"$ROOT/native/berxel/portable" \
    "$ROOT/native/berxel/portable/gomob_berxel_portable.cpp" \
    "$HARNESS_DIR/apply_filter.cpp" \
    -o "$BIN_DIR/apply_filter"
echo

OVERALL=0
SUMMARY="$OUT_DIR/summary.md"
: > "$SUMMARY"
echo "# Depth Temporal Quality — 多序列汇总" >> "$SUMMARY"
echo >> "$SUMMARY"

for spec in "${SEQUENCES[@]}"; do
    IFS=':' read -r label dir suffix w h kind <<< "$spec"
    if [ ! -d "$dir" ]; then
        echo "!! 跳过 $label：数据目录不存在 $dir（先跑 berxel_depth_parity 采集）"
        echo "- $label: SKIP（无数据 $dir）" >> "$SUMMARY"
        continue
    fi
    echo "=== 序列 $label [$kind] ($dir/*$suffix ${w}x${h}) ==="
    fused_dir="$OUT_DIR/$label-fused"
    rm -rf "$fused_dir"
    # motion-mm=45 是绝对下限；运动门限实际由自适应噪声估计主导（noise_k×噪声底），跨场景/距离泛化。
    "$BIN_DIR/apply_filter" "$dir" "$suffix" "$w" "$h" "$fused_dir" \
        --window 8 --motion-mm 45 --motion-percent 0.03 --min-full 4

    set +e
    "$PY" "$HARNESS_DIR/analyze.py" "$dir" "$suffix" "$fused_dir" "$w" "$h" \
        "$OUT_DIR/$label.json"
    rc=$?
    set -e
    echo
    st=$([ $rc -eq 0 ] && echo OK || ([ $rc -eq 1 ] && echo WARN || echo FAIL))
    gain=$("$PY" -c "import json;print('%.2f'%(json.load(open('$OUT_DIR/$label.json')).get('stability_gain_x') or 0))" 2>/dev/null || echo "?")
    if [ "$kind" = "gate" ]; then
        echo "- $label [门]: $st（增益 ${gain}×，详见 $label.json）" >> "$SUMMARY"
        if [ $rc -ne 0 ]; then OVERALL=$rc; fi
    else
        # 参考序列：补洞后输出/不同场次，门限<噪声底时不改善属预期，不计入 exit。
        echo "- $label [参考]: 增益 ${gain}×（诊断用，门限<其噪声底则不改善，不影响验收）" >> "$SUMMARY"
    fi
done

echo "=== 汇总 ==="
cat "$SUMMARY"
exit $OVERALL
