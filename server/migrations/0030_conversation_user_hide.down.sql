BEGIN;

ALTER TABLE conversation_member_states
    DROP CONSTRAINT IF EXISTS conversation_member_states_hidden_nonneg;

ALTER TABLE conversation_member_states
    DROP COLUMN IF EXISTS hidden_through_seq;

COMMIT;
