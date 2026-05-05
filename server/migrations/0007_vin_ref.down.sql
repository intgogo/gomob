BEGIN;

DROP TRIGGER IF EXISTS touch_vin_glyph_sample_count_ins ON vin_glyph_samples;
DROP TRIGGER IF EXISTS touch_vin_glyph_sample_count_del ON vin_glyph_samples;
DROP FUNCTION IF EXISTS touch_vin_glyph_sample_count();

DROP TABLE IF EXISTS vin_glyph_samples;

DROP TRIGGER IF EXISTS touch_vin_glyph_batches_updated_at ON vin_glyph_batches;
DROP FUNCTION IF EXISTS touch_vin_glyph_batches_updated_at();

DROP INDEX IF EXISTS uq_vin_batches_one_published;
DROP INDEX IF EXISTS idx_vin_batches_vm_status;
DROP TABLE IF EXISTS vin_glyph_batches;

COMMIT;
