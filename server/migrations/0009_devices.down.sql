BEGIN;
DROP INDEX IF EXISTS idx_calibrations_device_version_desc;
DROP TABLE IF EXISTS device_calibrations;

DROP TRIGGER IF EXISTS touch_devices_updated_at ON devices;
DROP FUNCTION IF EXISTS touch_devices_updated_at();
DROP INDEX IF EXISTS idx_devices_user_status_created;
DROP INDEX IF EXISTS uq_devices_serial_active;
DROP TABLE IF EXISTS devices;
COMMIT;
