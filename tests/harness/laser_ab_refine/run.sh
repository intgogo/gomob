#!/usr/bin/env bash
# laser_ab_refine harness：A/B 两镜头点云外参精修闭环。
# 用法：
#   ./dev.sh harness laser_ab_refine
#   A_PCD=unit_a.pcd B_PCD=unit_b.pcd BTOA_JSON=site.json ./dev.sh harness laser_ab_refine
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${OUTPUT_DIR:-.dev/laser_ab_refine}"
mkdir -p "$OUT"
echo "[laser_ab_refine] A/B 点云外参精修闭环 → $OUT/report.txt"
env -u A_PCD -u B_PCD -u BTOA_JSON -u SITE_JSON python3 "$HERE/analyze.py" "$OUT" | tee "$OUT/report.txt"

if [[ -n "${A_PCD:-}" && -n "${B_PCD:-}" && -n "${BTOA_JSON:-${SITE_JSON:-}}" ]]; then
  a_pcd="$(readlink -f "$A_PCD")"
  b_pcd="$(readlink -f "$B_PCD")"
  init_json="$(readlink -f "${BTOA_JSON:-${SITE_JSON:-}}")"
  expected_json=""
  if [[ -n "${EXPECTED_BTOA_JSON:-}" ]]; then
    expected_json="$(readlink -f "$EXPECTED_BTOA_JSON")"
  fi
  echo | tee -a "$OUT/report.txt"
  echo "== 生产 Go 点到面真实 PCD 闭环 ==" | tee -a "$OUT/report.txt"
  (
    cd "$HERE/../../../server"
    LASER_REFINE_A_PCD="$a_pcd" \
    LASER_REFINE_B_PCD="$b_pcd" \
    LASER_REFINE_INIT_JSON="$init_json" \
    LASER_REFINE_EXPECTED_JSON="$expected_json" \
      go test ./internal/laser -run TestRefineBToARealFixture -count=1 -v
  ) | tee -a "$OUT/report.txt"
fi
