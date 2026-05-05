BEGIN;
DROP TRIGGER IF EXISTS trg_model_routes_updated_at ON model_routes;
DROP FUNCTION IF EXISTS touch_model_routes_updated_at();
DROP TABLE IF EXISTS model_routes;

DROP TRIGGER IF EXISTS trg_models_updated_at ON models;
DROP FUNCTION IF EXISTS touch_models_updated_at();
DROP TABLE IF EXISTS models;
COMMIT;
