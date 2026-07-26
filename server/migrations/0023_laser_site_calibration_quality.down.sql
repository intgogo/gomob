BEGIN;

ALTER TABLE laser_site_calibration
    DROP CONSTRAINT IF EXISTS laser_site_calibration_common_markers_nonnegative,
    DROP CONSTRAINT IF EXISTS laser_site_calibration_rms_nonnegative,
    DROP COLUMN IF EXISTS common_markers,
    DROP COLUMN IF EXISTS rms_error_mm;

COMMIT;
