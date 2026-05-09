BEGIN;

DROP INDEX IF EXISTS idx_assets_orphan_created;

DELETE FROM inspection_assets WHERE inspection_id IS NULL;

ALTER TABLE inspection_assets
    ALTER COLUMN inspection_id SET NOT NULL;

COMMIT;
