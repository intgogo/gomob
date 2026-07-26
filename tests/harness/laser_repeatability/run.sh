#!/usr/bin/env bash
# laser_repeatability：只采样部署库，不触发扫描；dev.sh 随后调用 analyze.py。
#
# 环境：
#   GOMOB_LASER_DB_HOST    部署机（默认 192.168.9.160，本机容器设 local）
#   GOMOB_LASER_BAY        unit_a_ip（默认 192.168.9.101）
#   GOMOB_LASER_N          最近车辆任务数（默认 24，分析时按 revision 精确分组）
#   GOMOB_LASER_TRUTH_LWH  参照物真值 "L,W,H"，毫米；正式验收必填
#   REQUIRE_PRODUCTION      默认 1；要求同 inspection/revision ≥3 次、真值、各项均正常
#                           仅分析器开发可显式设 0，输出不得作为现场验收
set -euo pipefail

HOST="${GOMOB_LASER_DB_HOST:-192.168.9.160}"
BAY="${GOMOB_LASER_BAY:-192.168.9.101}"
N="${GOMOB_LASER_N:-24}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
OUT="${OUTPUT_DIR:-$ROOT/.dev/laser_repeatability}"
mkdir -p "$OUT"

if ! [[ "$N" =~ ^[1-9][0-9]*$ ]]; then
    echo "GOMOB_LASER_N 必须是正整数，实际为 $N" >&2
    exit 2
fi

SQL="SELECT json_build_object(
  'id', id,
  'created_at', created_at,
  'inspection_id', inspection_id,
  'unit_a_ip', unit_a_ip,
  'unit_b_ip', unit_b_ip,
  'mode', stats->>'measure_mode',
  'valid', COALESCE((stats->'measure'->>'valid')::boolean, FALSE),
  'l', (stats->'measure'->>'length_mm')::float,
  'w', (stats->'measure'->>'width_mm')::float,
  'h', (stats->'measure'->>'height_mm')::float,
  'body', (stats->'measure'->>'body_pts')::int,
  'fg', COALESCE((stats->>'fg_points')::int, (stats->>'fg_pts')::int),
  'measured_points', CASE
      WHEN COALESCE(stats->>'measured_points', '') ~ '^[1-9][0-9]*$'
      THEN (stats->>'measured_points')::int ELSE NULL END,
  'measured_object_key', measured_object_key,
  'artifact_xyz_sha256', stats->'measured_artifact'->>'xyz_sha256',
  'artifact_coordinate_schema', stats->'measured_artifact'->>'coordinate_schema',
  'artifact_source_points', CASE
      WHEN COALESCE(stats->'measured_artifact'->>'source_points', '') ~ '^[1-9][0-9]*$'
      THEN (stats->'measured_artifact'->>'source_points')::int ELSE NULL END,
  'artifact_site_revision', stats->'measured_artifact'->>'site_revision',
  'artifact_region_revision', stats->'measured_artifact'->>'region_revision',
  'artifact_background_revision', CASE
      WHEN COALESCE(stats->'measured_artifact'->>'background_revision', '') ~ '^[1-9][0-9]*$'
      THEN (stats->'measured_artifact'->>'background_revision')::bigint ELSE NULL END,
  'artifact_final_b_to_a_sha256', stats->'measured_artifact'->>'final_b_to_a_sha256',
  'site_revision', stats->'site_calibration'->>'matrix_sha256',
  'region_revision', stats->'region_calibration'->>'points_sha256',
  'background_revision', CASE
      WHEN COALESCE(stats->>'background_revision_id', '') ~ '^[1-9][0-9]*$'
      THEN (stats->>'background_revision_id')::bigint ELSE NULL END,
  'background_schema', stats->>'background_schema',
  'ground_source', stats->>'ground_source',
  'ground_stable', COALESCE((stats->>'ground_stable')::boolean, FALSE),
  'ground_reason', stats->>'ground_reason',
  'ground_valid', COALESCE((stats->'ground'->>'valid')::boolean, FALSE),
  'ground_drift_deg', (stats->>'ground_drift_deg')::float,
  'refine_applied', (stats->'b_to_a_refine'->>'applied')::boolean,
  'refine_accepted', (stats->>'refine_accepted')::boolean,
  'refine_dt', (stats->'b_to_a_refine'->>'delta_trans_mm')::float,
  'refine_dr', (stats->'b_to_a_refine'->>'delta_rot_deg')::float)
FROM laser_scan_jobs
WHERE status='done'
  AND unit_a_ip='$BAY'
  AND COALESCE(stats->>'measure_mode', '') <> 'background_captured'
  AND stats ? 'measure'
ORDER BY id DESC
LIMIT $N;"

PSQL=(podman exec gomob-pg psql -U gomob -d gomob -At -v ON_ERROR_STOP=1)
if [[ "$HOST" == "local" ]]; then
    "${PSQL[@]}" -c "$SQL" > "$OUT/stats.jsonl"
else
    # shellcheck disable=SC2029
    ssh -o BatchMode=yes "root@$HOST" \
        "podman exec gomob-pg psql -U gomob -d gomob -At -v ON_ERROR_STOP=1 -c \"$(printf '%s' "$SQL" | sed 's/"/\\"/g')\"" \
        > "$OUT/stats.jsonl"
fi

echo "采样 $(wc -l < "$OUT/stats.jsonl") 条车辆任务 → $OUT/stats.jsonl"
