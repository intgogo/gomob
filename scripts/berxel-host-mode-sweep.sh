#!/usr/bin/env bash
# 逐档测试 Berxel host COLOR / DEPTH UVC 模式。
set -euo pipefail

cd "$(dirname "$0")/.."

OUT_ROOT="${OUT_ROOT:-.dev/berxel-host-sdk/mode-sweep-$(date +%Y%m%d-%H%M%S)}"
DUR_MS="${DUR_MS:-1800}"
mkdir -p "$OUT_ROOT"

run_case() {
    local name="$1"
    shift
    local dir="$OUT_ROOT/$name"
    mkdir -p "$dir"
    echo "== $name =="
    if "$@" --dur-ms "$DUR_MS" --out-dir "$dir" > "$dir/run.log" 2>&1; then
        echo "$name,ok" >> "$OUT_ROOT/summary.csv"
    else
        echo "$name,fail" >> "$OUT_ROOT/summary.csv"
    fi
    tail -n 24 "$dir/run.log"
    scripts/berxel-host-probe.sh --stop-only --dur-ms 200 > "$dir/stop.log" 2>&1 || true
    sleep 0.6
}

echo "case,status" > "$OUT_ROOT/summary.csv"
scripts/berxel-host-probe.sh --stop-only --dur-ms 200 > "$OUT_ROOT/stop-before.log" 2>&1 || true

run_case color-1920x1080-30 scripts/berxel-host-probe.sh --color --ka-ms 0 --master-all --color-frame 1 --color-interval 333333
run_case color-1280x800-30 scripts/berxel-host-probe.sh --color --ka-ms 0 --master-all --color-frame 2 --color-interval 333333
run_case color-640x400-30 scripts/berxel-host-probe.sh --color --ka-ms 0 --master-all --color-frame 3 --color-interval 333333

run_case depth-1280x801-45 scripts/berxel-host-probe.sh --depth --depth-frame 1 --depth-interval 222222
run_case depth-640x401-45 scripts/berxel-host-probe.sh --depth --depth-frame 2 --depth-interval 222222
run_case depth-320x201-45 scripts/berxel-host-probe.sh --depth --depth-frame 3 --depth-interval 222222
run_case depth-1280x800-5 scripts/berxel-host-probe.sh --depth --depth-frame 4 --depth-interval 2000000

echo "summary: $OUT_ROOT/summary.csv"
