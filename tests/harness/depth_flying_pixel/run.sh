#!/usr/bin/env bash
# 飞点剔除 harness：合成 GT 场景 → P100R3TemporalFilter 飞点剔除 → 对照 GT 算 recall/geom_keep。
# 纯离线无真机。验收：所有场景 analyze.py OK（有飞点场景 recall≥0.80 且 geom_keep≥0.99；
# 斜面/球/台阶/薄结构等纯几何场景 FP==0）。
set -euo pipefail
cd "$(dirname "$0")/../../.."
ROOT="$(pwd)"
OUT="${OUTPUT_DIR:-$ROOT/.dev/depth_flying_pixel}"
BIN="$OUT/bin"; mkdir -p "$BIN"
PY="${PYTHON:-/root/lilw/miniconda3/bin/python3}"
HD="$ROOT/tests/harness/depth_flying_pixel"
W=640; H=400; N=20
SCENES="$OUT/scenes"
APPLY="$OUT/apply"

echo "=== 生成合成 GT 场景 ==="
"$PY" "$HD/gen_scene.py" "$SCENES" "$N"
echo
echo "=== 编译 apply（portable-only，无 libusb） ==="
g++ -std=c++17 -O2 -Wall -Wextra -Wpedantic \
    -I"$ROOT/native/berxel/host/include" -I"$ROOT/native/berxel/portable" \
    "$ROOT/native/berxel/portable/gomob_berxel_portable.cpp" "$HD/apply.cpp" -o "$BIN/apply"
echo
echo "=== 逐场景跑飞点剔除（默认参数） ==="
rm -rf "$APPLY"; mkdir -p "$APPLY"
for d in "$SCENES"/*/; do
    name="$(basename "$d")"
    "$BIN/apply" "$d" "$W" "$H" "$APPLY/$name" \
        --window 8 --grazing 88 --tstd-floor 60 --tstd-pct 0.03 \
        --min-support 2 --support-band 30 --min-stable 3 --warmup 4
done
echo
echo "=== 判定（合成 GT） ==="
set +e
"$PY" "$HD/analyze.py" "$SCENES" "$APPLY" "$W" "$H" "$OUT/result.json"
rc=$?
set -e

# 真实数据 sanity：杂乱 vendor-dense（无 GT）上飞点剔除占比须 << 过杀阈值，证不误删真实几何。
VENDOR="$ROOT/.dev/berxel_depth_parity/vendor-dense"
if [ -d "$VENDOR" ]; then
    echo
    echo "=== 真实数据过杀 sanity（vendor-dense，无 GT） ==="
    STAGE="$OUT/vendor-stage"; rm -rf "$STAGE"; mkdir -p "$STAGE"
    i=0; for f in $(ls "$VENDOR"/vendor-depth-*.raw 2>/dev/null | sort); do
        cp "$f" "$STAGE/$(printf 'frame-%03d.raw' "$i")"; i=$((i+1)); done
    if [ "$i" -gt 0 ]; then
        "$BIN/apply" "$STAGE" "$W" "$H" "$OUT/vendor-apply" \
            --window 8 --grazing 88 --tstd-floor 60 --tstd-pct 0.03 \
            --min-support 2 --support-band 30 --min-stable 3 --warmup 4 >/dev/null
        set +e
        "$PY" - "$OUT/vendor-apply" "$STAGE" <<'PY'
import sys, json, csv
import numpy as np
ad, stage = sys.argv[1], sys.argv[2]
cnt = np.fromfile(f"{ad}/detect_count.raw", dtype="<u2")
post = json.load(open(f"{ad}/apply_meta.json"))["post_warmup"]
valid = np.fromfile(f"{stage}/frame-000.raw", dtype="<u2") > 0
det = cnt >= np.ceil(0.5 * post)
ratio = float(det[valid].mean())
rows = list(csv.DictReader(open(f"{ad}/stats.csv")))
per = np.mean([int(x["flying_pixels"]) for x in rows]) / max(1, valid.sum())
print(f"  removal_ratio(聚合)={ratio*100:.2f}%  per-frame≈{per*100:.2f}%  门限 15%")
sys.exit(0 if ratio < 0.15 else 3)
PY
        [ $? -ne 0 ] && { echo "  !! 真实数据过杀（>15%）"; rc=2; } || echo "  OK：真实数据不过杀"
        set -e
    fi
fi
exit $rc
