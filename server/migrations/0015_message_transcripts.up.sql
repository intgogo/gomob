-- M5 语音转文字：语音消息派生转写结果。
--
-- 设计约束：
--   1. 原始 voice 消息仍是事实源；转写是派生内容，可失败、可重试。
--   2. message_id 唯一，避免同一语音重复生成多个正式转写。
--   3. engine/model 固化到记录，便于后续模型 A/B 与质量追溯。

BEGIN;

CREATE TABLE message_transcripts (
    id              BIGSERIAL PRIMARY KEY,
    message_id      BIGINT NOT NULL UNIQUE REFERENCES messages(id) ON DELETE CASCADE,
    conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    asset_id        BIGINT NOT NULL REFERENCES inspection_assets(id),
    status          TEXT NOT NULL DEFAULT 'pending',
    engine          TEXT NOT NULL,
    model           TEXT NOT NULL,
    language        TEXT NOT NULL DEFAULT 'zh',
    text            TEXT,
    normalized_text TEXT,
    segments        JSONB NOT NULL DEFAULT '[]',
    confidence      REAL,
    error_message   TEXT,
    attempt_count   INTEGER NOT NULL DEFAULT 0,
    next_retry_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT message_transcripts_status_valid
        CHECK (status IN ('pending', 'processing', 'done', 'failed')),
    CONSTRAINT message_transcripts_attempt_nonneg CHECK (attempt_count >= 0),
    CONSTRAINT message_transcripts_confidence_range
        CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
);

CREATE INDEX idx_message_transcripts_queue
    ON message_transcripts(status, next_retry_at, created_at)
    WHERE status IN ('pending', 'failed');

CREATE INDEX idx_message_transcripts_conversation
    ON message_transcripts(conversation_id, created_at DESC);

COMMIT;
