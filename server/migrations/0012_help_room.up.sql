-- M5 在线求助固定多人会话：
-- 每个发起人只有一个 subject_kind=online_help / subject_id=user_id 的群会话。

BEGIN;

CREATE UNIQUE INDEX IF NOT EXISTS uq_conversations_subject
    ON conversations(subject_kind, subject_id)
    WHERE subject_kind IS NOT NULL AND subject_id IS NOT NULL;

COMMIT;
