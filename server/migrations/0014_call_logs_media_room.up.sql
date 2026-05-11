-- M5.5 通话记录与媒体房间建立稳定关联，保证挂断接口幂等补写 call_logs。

BEGIN;

ALTER TABLE call_logs
    ADD COLUMN room_id BIGINT REFERENCES media_rooms(id) ON DELETE SET NULL,
    ADD COLUMN conversation_id BIGINT REFERENCES conversations(id) ON DELETE SET NULL;

CREATE UNIQUE INDEX uq_call_logs_room
    ON call_logs(room_id);

CREATE INDEX idx_call_logs_conversation_started
    ON call_logs(conversation_id, started_at DESC);

COMMIT;
