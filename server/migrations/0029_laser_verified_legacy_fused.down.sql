BEGIN;

UPDATE laser_background_revision
SET coordinate_schema = 'legacy_fused'
WHERE coordinate_schema = 'legacy_verified_region_fused_v1';

ALTER TABLE laser_background_revision
    DROP CONSTRAINT IF EXISTS laser_background_revision_schema_valid,
    DROP CONSTRAINT IF EXISTS laser_background_revision_payload_complete;

ALTER TABLE laser_background_revision
    DROP COLUMN IF EXISTS legacy_fused_points,
    DROP COLUMN IF EXISTS legacy_fused_checksum,
    DROP COLUMN IF EXISTS compatibility_site_revision,
    DROP COLUMN IF EXISTS compatibility_region_revision,
    DROP COLUMN IF EXISTS compatibility_evidence;

ALTER TABLE laser_background_revision
    ADD CONSTRAINT laser_background_revision_schema_valid CHECK (
        coordinate_schema IN ('raw_unit_frames_v1', 'region_cropped_unit_frames_v1', 'legacy_fused')
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

COMMIT;
