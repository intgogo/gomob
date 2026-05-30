#!/usr/bin/env bash
# berxel_depth_parity/sweep.sh — 多材质 / 多角度 depth parity sweep。

set -euo pipefail

PROJ_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$PROJ_DIR"

SCENES_FILE="${SCENES_FILE:-$PROJ_DIR/tests/harness/berxel_depth_parity/scenes.example.tsv}"
OUTPUT_ROOT="${OUTPUT_ROOT:-$PROJ_DIR/.dev/berxel_depth_parity_sweep/$(date +%Y%m%d-%H%M%S)}"
FRAMES="${FRAMES:-20}"
SKIP="${SKIP:-10}"
HOST_DUR_MS="${HOST_DUR_MS:-3200}"
CHECK_NO_CONTROLS="${CHECK_NO_CONTROLS:-0}"
AUTO_CONFIRM="${AUTO_CONFIRM:-0}"

mkdir -p "$OUTPUT_ROOT"
SUMMARY="$OUTPUT_ROOT/summary.tsv"
printf 'scene\tstatus\tmaterial\tdistance_mm\tangle_deg\thost_dense\tvendor_dense\tmedian_delta_mm\tmedian_over_noise_mm\tcenter_host_mm\tcenter_vendor_mm\tcenter_host_jitter_mm\tcenter_vendor_jitter_mm\tout_dir\n' > "$SUMMARY"

append_summary() {
    local result_json="$1"
    python3 - "$result_json" "$SUMMARY" <<'PY'
import json
import sys

result = json.load(open(sys.argv[1], encoding="utf-8"))
summary_path = sys.argv[2]
scene = result.get("scene", {})
host_roi = result.get("host_center_roi") or {}
vendor_roi = result.get("vendor_center_roi") or {}

def value(v):
    if v is None:
        return ""
    if isinstance(v, float):
        return f"{v:.4f}"
    return str(v)

row = [
    scene.get("scene_name", ""),
    result.get("status", ""),
    scene.get("material", ""),
    value(scene.get("distance_mm")),
    value(scene.get("angle_deg")),
    value(result.get("host_default_valid_ratio")),
    value(result.get("vendor_dense_valid_ratio")),
    value(result.get("median_depth_delta_mm")),
    value(result.get("median_over_noise_mm")),
    value(host_roi.get("median_mm_median")),
    value(vendor_roi.get("median_mm_median")),
    value(host_roi.get("median_mm_range")),
    value(vendor_roi.get("median_mm_range")),
    result.get("root", ""),
]
with open(summary_path, "a", encoding="utf-8") as out:
    out.write("\t".join(row) + "\n")
PY
}

while IFS=$'\t' read -r scene material distance angle notes; do
    [[ -z "${scene:-}" ]] && continue
    [[ "$scene" == \#* ]] && continue

    out_dir="$OUTPUT_ROOT/$scene"
    printf '\n[%s] 场景 %s material=%s distance=%smm angle=%sdeg\n' \
        "$(date +%H:%M:%S)" "$scene" "$material" "$distance" "$angle"
    printf '说明: %s\n' "${notes:-}"
    if [ "$AUTO_CONFIRM" != "1" ]; then
        read -r -p "摆好标靶后按回车继续，或 Ctrl+C 停止: " _
    fi

    OUTPUT_DIR="$out_dir" \
    FRAMES="$FRAMES" \
    SKIP="$SKIP" \
    HOST_DUR_MS="$HOST_DUR_MS" \
    CHECK_NO_CONTROLS="$CHECK_NO_CONTROLS" \
    SCENE_NAME="$scene" \
    SCENE_MATERIAL="$material" \
    SCENE_DISTANCE_MM="$distance" \
    SCENE_ANGLE_DEG="$angle" \
    SCENE_NOTES="${notes:-}" \
        tests/harness/berxel_depth_parity/run.sh

    append_summary "$out_dir/analysis.json"
done < "$SCENES_FILE"

printf '\n输出: %s\n' "$OUTPUT_ROOT"
printf '汇总: %s\n' "$SUMMARY"
