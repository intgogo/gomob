BEGIN;

ALTER TABLE laser_scan_jobs
    DROP COLUMN IF EXISTS measured_object_key;

COMMIT;
