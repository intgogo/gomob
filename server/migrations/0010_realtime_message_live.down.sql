BEGIN;

DROP INDEX IF EXISTS idx_live_recordings_session;
DROP INDEX IF EXISTS uq_live_recordings_egress;
DROP TABLE IF EXISTS live_recordings;

DROP INDEX IF EXISTS idx_live_annotations_session_created;
DROP TABLE IF EXISTS live_annotations;

DROP INDEX IF EXISTS idx_live_sessions_inspection;
DROP INDEX IF EXISTS idx_live_sessions_status_created;
DROP TABLE IF EXISTS live_sessions;

DROP INDEX IF EXISTS idx_media_participants_user;
DROP TABLE IF EXISTS media_participants;

DROP INDEX IF EXISTS idx_media_rooms_status_created;
DROP INDEX IF EXISTS idx_media_rooms_subject;
DROP TABLE IF EXISTS media_rooms;

DROP INDEX IF EXISTS idx_conversation_member_states_user;
DROP TABLE IF EXISTS conversation_member_states;

DROP INDEX IF EXISTS uq_messages_sender_client_msg;
ALTER TABLE messages DROP COLUMN IF EXISTS deleted_at;
ALTER TABLE messages DROP COLUMN IF EXISTS edited_at;
ALTER TABLE messages DROP COLUMN IF EXISTS client_msg_id;

DROP INDEX IF EXISTS idx_conversations_updated_id;
ALTER TABLE conversations DROP COLUMN IF EXISTS updated_at;
ALTER TABLE conversations DROP COLUMN IF EXISTS subject_id;
ALTER TABLE conversations DROP COLUMN IF EXISTS subject_kind;

COMMIT;
