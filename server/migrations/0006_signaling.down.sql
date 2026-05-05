BEGIN;
DROP TABLE IF EXISTS pending_calls;
DROP INDEX IF EXISTS uq_conversations_p2p_key;
ALTER TABLE conversations DROP COLUMN IF EXISTS p2p_key;
ALTER TABLE conversations DROP COLUMN IF EXISTS next_seq;
COMMIT;
