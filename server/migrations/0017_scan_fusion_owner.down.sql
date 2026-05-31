BEGIN;

DROP INDEX IF EXISTS idx_scan_fusion_jobs_owner;
ALTER TABLE scan_fusion_jobs DROP COLUMN IF EXISTS owner_user_id;

COMMIT;
