BEGIN;

DROP INDEX IF EXISTS idx_message_transcripts_conversation;
DROP INDEX IF EXISTS idx_message_transcripts_queue;
DROP TABLE IF EXISTS message_transcripts;

COMMIT;
