-- 会话“删除”是当前用户视角下的历史隐藏，不删除共享消息或成员关系。
-- hidden_through_seq 为包含式水位；后续更大的 server_seq 会自然恢复会话。

BEGIN;

ALTER TABLE conversation_member_states
    ADD COLUMN hidden_through_seq BIGINT;

ALTER TABLE conversation_member_states
    ADD CONSTRAINT conversation_member_states_hidden_nonneg
    CHECK (hidden_through_seq IS NULL OR hidden_through_seq >= 0);

COMMIT;
