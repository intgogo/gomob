BEGIN;
DROP TRIGGER IF EXISTS trg_vehicle_models_updated_at ON vehicle_models;
DROP FUNCTION IF EXISTS touch_vehicle_models_updated_at();
DROP TABLE IF EXISTS vehicle_models;
COMMIT;
