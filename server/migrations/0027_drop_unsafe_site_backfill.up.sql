-- 0022 的早期版本曾把 laser_scan_jobs.b_to_a（每次扫描场景精修后的最终姿态）
-- 回填成工位外参。该来源不具备标定语义，必须删除并要求从正式标定资产重新保存。

BEGIN;

DELETE FROM laser_site_calibration
WHERE source IN ('legacy_scan_backfill', 'scan_job_backfill');

COMMIT;
