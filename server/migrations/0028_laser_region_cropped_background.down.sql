BEGIN;

-- 旧程序只认识 raw/legacy。裁剪后的 A/B 仍是设备系云，回滚时按 raw 兼容读取并重复裁剪。
UPDATE laser_background_revision
SET coordinate_schema = 'raw_unit_frames_v1'
WHERE coordinate_schema = 'region_cropped_unit_frames_v1';

ALTER TABLE laser_background_revision
    DROP CONSTRAINT IF EXISTS laser_background_revision_schema_valid,
    DROP CONSTRAINT IF EXISTS laser_background_revision_payload_complete;

ALTER TABLE laser_background_revision
    DROP COLUMN IF EXISTS region_revision;

ALTER TABLE laser_background_revision
    ADD CONSTRAINT laser_background_revision_schema_valid CHECK (
        coordinate_schema IN ('raw_unit_frames_v1', 'legacy_fused')
    ),
    ADD CONSTRAINT laser_background_revision_payload_complete CHECK (
        (
            coordinate_schema = 'legacy_fused'
            AND length(btrim(COALESCE(legacy_fused_object_key, ''))) > 0
        )
        OR (
            coordinate_schema = 'raw_unit_frames_v1'
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
        )
    );

COMMIT;
