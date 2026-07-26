-- 不可变空工位背景 revision。
--
-- 新背景保存服务端权威区域裁剪后的 A/B 设备系点云；在各自设备系相减后，再用本次最终 B→A 融合。
-- active 只是工位当前指针，revision 的对象键、配置指纹和采集元数据创建后不再覆盖。

BEGIN;

CREATE TABLE laser_background_revision (
    id                           BIGSERIAL PRIMARY KEY,
    unit_a_ip                    TEXT NOT NULL,
    unit_b_ip                    TEXT NOT NULL,
    unit_a_object_key            TEXT,
    unit_b_object_key            TEXT,
    legacy_fused_object_key      TEXT,
    source_scan_id               BIGINT UNIQUE REFERENCES laser_scan_jobs(id) ON DELETE SET NULL,
    site_revision                TEXT,
    region_revision              TEXT,
    unit_a_points                BIGINT NOT NULL DEFAULT 0,
    unit_b_points                BIGINT NOT NULL DEFAULT 0,
    unit_a_checksum              TEXT,
    unit_b_checksum              TEXT,
    unit_a_identity              JSONB NOT NULL DEFAULT '{}',
    unit_b_identity              JSONB NOT NULL DEFAULT '{}',
    unit_a_device_config_hash    TEXT,
    unit_b_device_config_hash    TEXT,
    unit_a_scan_config_hash      TEXT,
    unit_b_scan_config_hash      TEXT,
    coordinate_schema            TEXT NOT NULL,
    captured_by                  BIGINT REFERENCES users(id) ON DELETE SET NULL,
    captured_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    active                       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT laser_background_revision_distinct_units CHECK (unit_a_ip <> unit_b_ip),
    CONSTRAINT laser_background_revision_points_nonnegative CHECK (
        unit_a_points >= 0 AND unit_b_points >= 0
    ),
    CONSTRAINT laser_background_revision_identity_valid CHECK (
        jsonb_typeof(unit_a_identity) = 'object'
        AND jsonb_typeof(unit_b_identity) = 'object'
    ),
    CONSTRAINT laser_background_revision_schema_valid CHECK (
        coordinate_schema IN ('raw_unit_frames_v1', 'region_cropped_unit_frames_v1', 'legacy_fused')
    ),
    CONSTRAINT laser_background_revision_payload_complete CHECK (
        (
            coordinate_schema = 'legacy_fused'
            AND length(btrim(COALESCE(legacy_fused_object_key, ''))) > 0
        )
        OR (
            coordinate_schema IN ('raw_unit_frames_v1', 'region_cropped_unit_frames_v1')
            AND length(btrim(COALESCE(unit_a_object_key, ''))) > 0
            AND length(btrim(COALESCE(unit_b_object_key, ''))) > 0
            AND unit_a_points > 0
            AND unit_b_points > 0
            AND length(btrim(COALESCE(unit_a_checksum, ''))) > 0
            AND length(btrim(COALESCE(unit_b_checksum, ''))) > 0
            AND COALESCE(unit_a_identity->>'ip', '') = unit_a_ip
            AND COALESCE(unit_b_identity->>'ip', '') = unit_b_ip
            AND length(btrim(COALESCE(unit_a_device_config_hash, ''))) > 0
            AND length(btrim(COALESCE(unit_b_device_config_hash, ''))) > 0
            AND length(btrim(COALESCE(unit_a_scan_config_hash, ''))) > 0
            AND length(btrim(COALESCE(unit_b_scan_config_hash, ''))) > 0
            AND length(btrim(COALESCE(site_revision, ''))) > 0
            AND (
                coordinate_schema = 'raw_unit_frames_v1'
                OR length(btrim(COALESCE(region_revision, ''))) > 0
            )
        )
    )
);

CREATE UNIQUE INDEX uq_laser_background_revision_active_station
    ON laser_background_revision (unit_a_ip, unit_b_ip)
    WHERE active;

CREATE INDEX idx_laser_background_revision_station_history
    ON laser_background_revision (unit_a_ip, unit_b_ip, captured_at DESC, id DESC);

COMMENT ON TABLE laser_background_revision IS
    '不可变空工位背景版本；每工位仅一条 active，新链保存区域裁剪后的 A/B 设备系 PCD';
COMMENT ON COLUMN laser_background_revision.coordinate_schema IS
    'region_cropped_unit_frames_v1=区域裁剪后 A/B 背景；raw_unit_frames_v1=预裁剪 A/B 背景；legacy_fused=历史融合背景';
COMMENT ON COLUMN laser_background_revision.site_revision IS
    '采集背景时服务端权威 B→A 外参的 canonical SHA-256；变化即视为安装位姿域变化并要求重采';
COMMENT ON COLUMN laser_background_revision.region_revision IS
    '区域裁剪背景绑定的服务端权威 region SHA-256；区域变化必须重采';

-- 历史背景只有稳定 fused 对象语义，且旧 unit_a/unit_b 可能已经被区域墙过滤，不能冒充原始背景。
-- 尽可能保留对象键和点数用于诊断，但统一标成 legacy_fused，后续上层必须拒绝生产相减并提示重采。
INSERT INTO laser_background_revision (
    unit_a_ip,
    unit_b_ip,
    unit_a_object_key,
    unit_b_object_key,
    legacy_fused_object_key,
    source_scan_id,
    unit_a_points,
    unit_b_points,
    unit_a_identity,
    unit_b_identity,
    coordinate_schema,
    captured_by,
    captured_at,
    active
)
SELECT
    unit_a_ip,
    unit_b_ip,
    unit_a_object_key,
    unit_b_object_key,
    fused_object_key,
    id,
    COALESCE(pts_a, 0),
    COALESCE(pts_b, 0),
    jsonb_build_object('ip', unit_a_ip),
    jsonb_build_object('ip', unit_b_ip),
    'legacy_fused',
    owner_user_id,
    updated_at,
    TRUE
FROM (
    SELECT DISTINCT ON (unit_a_ip, unit_b_ip)
        id,
        owner_user_id,
        unit_a_ip,
        unit_b_ip,
        unit_a_object_key,
        unit_b_object_key,
        fused_object_key,
        pts_a,
        pts_b,
        updated_at
    FROM laser_scan_jobs
    WHERE status = 'done'
      AND length(btrim(COALESCE(fused_object_key, ''))) > 0
      AND (
          stats->>'bg_captured' = 'true'
          OR stats->>'measure_mode' = 'background_captured'
      )
    ORDER BY unit_a_ip, unit_b_ip, id DESC
) latest;

COMMIT;
