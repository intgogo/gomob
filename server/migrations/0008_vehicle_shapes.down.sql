BEGIN;
DROP TRIGGER IF EXISTS touch_vehicle_shapes_updated_at ON vehicle_shapes;
DROP FUNCTION IF EXISTS touch_vehicle_shapes_updated_at();
DROP INDEX IF EXISTS uq_vehicle_shapes_one_published;
DROP INDEX IF EXISTS idx_vehicle_shapes_vm_status;
DROP INDEX IF EXISTS idx_vehicle_shapes_vm_created;
DROP TABLE IF EXISTS vehicle_shapes;
COMMIT;
