-- 激光工位区域墙标定的服务端真理源。
--
-- 区域点定义在 unit A / 融合显示坐标系（mm）；B→A 外参属于 site 标定，绝不复制进本表。
-- 起扫时由服务端把当前 site 外参与本区域组合成运行快照，网页与 App 因而使用同一裁剪域。

BEGIN;

CREATE TABLE laser_region_calibration (
    unit_a_ip       TEXT NOT NULL,
    unit_b_ip       TEXT NOT NULL,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    points          JSONB NOT NULL,
    source          TEXT NOT NULL DEFAULT 'unknown',
    source_scan_id  BIGINT REFERENCES laser_scan_jobs(id) ON DELETE SET NULL,
    updated_by      BIGINT REFERENCES users(id) ON DELETE SET NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (unit_a_ip, unit_b_ip),
    CONSTRAINT laser_region_calibration_distinct_units CHECK (unit_a_ip <> unit_b_ip),
    CONSTRAINT laser_region_calibration_points_valid CHECK (
        CASE
            WHEN jsonb_typeof(points) = 'array'
                THEN NOT enabled OR jsonb_array_length(points) >= 3
            ELSE FALSE
        END
    ),
    CONSTRAINT laser_region_calibration_source_nonempty CHECK (length(btrim(source)) > 0)
);

COMMENT ON TABLE laser_region_calibration IS
    '双单元激光工位区域墙：仅存 unit A 显示系 mm 边界点，不复制 B→A 外参';
COMMENT ON COLUMN laser_region_calibration.points IS
    '闭合多边形顶点 [[x,y,z],...]，unit A / 融合显示坐标系 mm；运行时按 XY 虚拟墙解释';

-- 从每个工位最近一次真正启用区域墙的完成扫描接续网页历史状态。
-- stats.region_filter.b_to_a 是当次外参快照，故意不回填，避免产生第二份外参真理源。
INSERT INTO laser_region_calibration (
    unit_a_ip, unit_b_ip, enabled, points, source, source_scan_id, updated_by, updated_at
)
SELECT
    unit_a_ip,
    unit_b_ip,
    TRUE,
    points,
    'legacy_scan_backfill',
    id,
    owner_user_id,
    updated_at
FROM (
    SELECT DISTINCT ON (unit_a_ip, unit_b_ip)
        id,
        owner_user_id,
        unit_a_ip,
        unit_b_ip,
        stats->'region_filter'->'points' AS points,
        updated_at
    FROM laser_scan_jobs
    WHERE status = 'done'
      AND stats #>> '{region_filter,enabled}' = 'true'
      AND jsonb_typeof(stats->'region_filter'->'points') = 'array'
      AND jsonb_array_length(stats->'region_filter'->'points') >= 3
    ORDER BY unit_a_ip, unit_b_ip, id DESC
) latest;

COMMIT;
