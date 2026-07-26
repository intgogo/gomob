-- 空工位背景改为服务端区域裁剪后的 A/B 设备系云。
-- 区域 revision 与 site/设备/扫描配置一起参与兼容性判定。

BEGIN;

ALTER TABLE laser_background_revision
    ADD COLUMN IF NOT EXISTS region_revision TEXT;

ALTER TABLE laser_background_revision
    DROP CONSTRAINT IF EXISTS laser_background_revision_schema_valid,
    DROP CONSTRAINT IF EXISTS laser_background_revision_payload_complete;

ALTER TABLE laser_background_revision
    ADD CONSTRAINT laser_background_revision_schema_valid CHECK (
        coordinate_schema IN (
            'raw_unit_frames_v1',
            'region_cropped_unit_frames_v1',
            'legacy_fused'
        )
    ),
    ADD CONSTRAINT laser_background_revision_payload_complete CHECK (
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
    );

COMMENT ON COLUMN laser_background_revision.coordinate_schema IS
    'region_cropped_unit_frames_v1=区域裁剪后 A/B 背景；raw_unit_frames_v1=预裁剪 A/B 背景；legacy_fused=历史融合背景';
COMMENT ON COLUMN laser_background_revision.region_revision IS
    '区域裁剪背景绑定的服务端权威 region SHA-256；区域变化必须重采';

COMMIT;
