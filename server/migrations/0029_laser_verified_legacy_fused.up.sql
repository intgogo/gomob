-- 已由历史真扫重放验证的融合背景兼容绑定。
-- 这些字段说明“此 legacy 对象与哪个当前 site/region 组合验证通过”，不冒充采集时元数据。

BEGIN;

ALTER TABLE laser_background_revision
    ADD COLUMN IF NOT EXISTS legacy_fused_points BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS legacy_fused_checksum TEXT,
    ADD COLUMN IF NOT EXISTS compatibility_site_revision TEXT,
    ADD COLUMN IF NOT EXISTS compatibility_region_revision TEXT,
    ADD COLUMN IF NOT EXISTS compatibility_evidence JSONB NOT NULL DEFAULT '{}';

ALTER TABLE laser_background_revision
    DROP CONSTRAINT IF EXISTS laser_background_revision_schema_valid,
    DROP CONSTRAINT IF EXISTS laser_background_revision_payload_complete;

ALTER TABLE laser_background_revision
    ADD CONSTRAINT laser_background_revision_schema_valid CHECK (
        coordinate_schema IN (
            'raw_unit_frames_v1',
            'region_cropped_unit_frames_v1',
            'legacy_fused',
            'legacy_verified_region_fused_v1'
        )
    ),
    ADD CONSTRAINT laser_background_revision_payload_complete CHECK (
        (
            coordinate_schema = 'legacy_fused'
            AND length(btrim(COALESCE(legacy_fused_object_key, ''))) > 0
        )
        OR (
            coordinate_schema = 'legacy_verified_region_fused_v1'
            AND length(btrim(COALESCE(legacy_fused_object_key, ''))) > 0
            AND legacy_fused_points > 0
            AND length(btrim(COALESCE(legacy_fused_checksum, ''))) > 0
            AND length(btrim(COALESCE(compatibility_site_revision, ''))) > 0
            AND length(btrim(COALESCE(compatibility_region_revision, ''))) > 0
            AND COALESCE(unit_a_identity->>'ip', '') = unit_a_ip
            AND COALESCE(unit_b_identity->>'ip', '') = unit_b_ip
            AND length(btrim(COALESCE(unit_a_device_config_hash, ''))) > 0
            AND length(btrim(COALESCE(unit_b_device_config_hash, ''))) > 0
            AND length(btrim(COALESCE(unit_a_scan_config_hash, ''))) > 0
            AND length(btrim(COALESCE(unit_b_scan_config_hash, ''))) > 0
            AND jsonb_typeof(compatibility_evidence) = 'object'
            AND compatibility_evidence <> '{}'::jsonb
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
    );

COMMENT ON COLUMN laser_background_revision.compatibility_site_revision IS
    'legacy 融合背景经历史真扫重放验证可兼容的当前 site revision；不是采集时 site';
COMMENT ON COLUMN laser_background_revision.compatibility_region_revision IS
    'legacy 融合背景经历史真扫重放验证可兼容的当前 region revision；不是采集时 region';
COMMENT ON COLUMN laser_background_revision.compatibility_evidence IS
    'legacy 兼容验证证据：背景来源、回放扫描、结果和算法身份';
COMMENT ON COLUMN laser_background_revision.coordinate_schema IS
    'region_cropped_unit_frames_v1=新 A/B 背景；legacy_verified_region_fused_v1=有兼容绑定的历史融合背景；legacy_fused=未验证历史对象';

COMMIT;
