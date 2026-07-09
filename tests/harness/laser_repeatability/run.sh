#!/bin/bash
# laser_repeatability — 激光外廓测量重复性/准确度 harness（M13）。
#
# 采样：从部署机 gomob-pg 拉最近 N 次 done 扫描的 stats（生产测量结果本身），
# 判定"多次扫描外廓尺寸是否可复现、是否贴真值"。深挖单次扫描用 server/cmd/laserreplay
# 对落盘 PCD 复算（见本目录 analyze.py 头注释）。
#
# 环境：
#   GOMOB_LASER_DB_HOST   部署机（默认 192.168.9.160，本机跑则设 local）
#   GOMOB_LASER_BAY       工位 bay_key=unit_a_ip（默认 192.168.9.101）
#   GOMOB_LASER_N         采样最近 N 次（默认 12）
#   GOMOB_LASER_TRUTH_LWH 参照物真值 "L,W,H"（mm，可选；给了才判准确度。JCHY 100742=1777,533,759）
set -euo pipefail

HOST="${GOMOB_LASER_DB_HOST:-192.168.9.160}"
BAY="${GOMOB_LASER_BAY:-192.168.9.101}"
N="${GOMOB_LASER_N:-12}"
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
OUT="$ROOT/.dev/laser_repeatability"
mkdir -p "$OUT"

SQL="SELECT json_build_object(
  'id', id, 'created_at', created_at, 'mode', stats->>'measure_mode',
  'valid', (stats->'measure'->>'valid')::boolean,
  'l', (stats->'measure'->>'length_mm')::float, 'w', (stats->'measure'->>'width_mm')::float,
  'h', (stats->'measure'->>'height_mm')::float, 'body', (stats->'measure'->>'body_pts')::int,
  'fg', (stats->>'fg_pts')::int,
  'ground_source', stats->>'ground_source',
  'ground_drift_deg', (stats->>'ground_drift_deg')::float,
  'refine_applied', (stats->'b_to_a_refine'->>'applied')::boolean,
  'refine_dt', (stats->'b_to_a_refine'->>'delta_trans_mm')::float,
  'refine_dr', (stats->'b_to_a_refine'->>'delta_rot_deg')::float)
FROM laser_scan_jobs
WHERE status='done' AND unit_a_ip='$BAY' AND stats->'measure'->>'valid' IS NOT NULL
ORDER BY id DESC LIMIT $N;"

PSQL=(podman exec gomob-pg psql -U gomob -d gomob -At)
if [[ "$HOST" == "local" ]]; then
    "${PSQL[@]}" -c "$SQL" > "$OUT/stats.jsonl"
else
    # shellcheck disable=SC2029
    ssh -o BatchMode=yes "root@$HOST" "podman exec gomob-pg psql -U gomob -d gomob -At -c \"$(printf '%s' "$SQL" | sed 's/"/\\"/g')\"" > "$OUT/stats.jsonl"
fi

echo "采样 $(wc -l < "$OUT/stats.jsonl") 条 → $OUT/stats.jsonl"
exec python3 "$(dirname "$0")/analyze.py" "$OUT/stats.jsonl"
