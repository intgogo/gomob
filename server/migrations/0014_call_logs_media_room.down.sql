BEGIN;

DROP INDEX IF EXISTS idx_call_logs_conversation_started;
DROP INDEX IF EXISTS uq_call_logs_room;

ALTER TABLE call_logs
    DROP COLUMN IF EXISTS conversation_id,
    DROP COLUMN IF EXISTS room_id;

COMMIT;
