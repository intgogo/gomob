BEGIN;

ALTER TABLE laser_site_calibration
    ADD COLUMN rms_error_mm DOUBLE PRECISION,
    ADD COLUMN common_markers INTEGER,
    ADD CONSTRAINT laser_site_calibration_rms_nonnegative
        CHECK (rms_error_mm IS NULL OR rms_error_mm >= 0),
    ADD CONSTRAINT laser_site_calibration_common_markers_nonnegative
        CHECK (common_markers IS NULL OR common_markers >= 0);

-- 旧网页把缺失指标写成 0；无法证明是真零误差，迁移为未知。
UPDATE laser_site_calibration
SET mean_error_mm = NULL,
    max_error_mm = NULL
WHERE source = 'aruco'
  AND mean_error_mm = 0
  AND max_error_mm = 0;

COMMIT;
