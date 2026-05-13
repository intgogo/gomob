-- M5 实时消息与第一视角协作：消息控制面 + 媒体房间元数据
--
-- 设计要点：
--   1. server_seq 仍由 conversations.next_seq 分配；本迁移只补幂等 client_msg_id、
--      已读水位与会话更新时间。
--   2. 媒体数据面由 LiveKit 承担；本库只保存 room / participant / live session /
--      annotation / recording 的业务元数据与审计锚点。
--   3. unread_count 默认由 last_read_seq 后的他人/系统消息推导，Redis 只能做缓存。

BEGIN;

-- ─── 消息 / 会话控制面 ─────────────────────────────────────────────────────
ALTER TABLE conversations ADD COLUMN subject_kind TEXT;
ALTER TABLE conversations ADD COLUMN subject_id BIGINT;
ALTER TABLE conversations ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX idx_conversations_updated_id
    ON conversations(updated_at DESC, id DESC);

ALTER TABLE messages ADD COLUMN client_msg_id TEXT;
ALTER TABLE messages ADD COLUMN edited_at TIMESTAMPTZ;
ALTER TABLE messages ADD COLUMN deleted_at TIMESTAMPTZ;

CREATE UNIQUE INDEX uq_messages_sender_client_msg
    ON messages(sender_id, client_msg_id)
    WHERE sender_id IS NOT NULL AND client_msg_id IS NOT NULL;

CREATE TABLE conversation_member_states (
    conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id         BIGINT NOT NULL REFERENCES users(id),
    last_read_seq   BIGINT NOT NULL DEFAULT 0,
    muted           BOOLEAN NOT NULL DEFAULT false,
    pinned          BOOLEAN NOT NULL DEFAULT false,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (conversation_id, user_id),
    CONSTRAINT conversation_member_states_read_nonneg CHECK (last_read_seq >= 0)
);

CREATE INDEX idx_conversation_member_states_user
    ON conversation_member_states(user_id, updated_at DESC);

INSERT INTO conversation_member_states(conversation_id, user_id)
SELECT conversation_id, user_id FROM conversation_members
ON CONFLICT DO NOTHING;

-- ─── 媒体房间控制面 ───────────────────────────────────────────────────────
CREATE TABLE media_rooms (
    id              BIGSERIAL PRIMARY KEY,
    provider        TEXT NOT NULL DEFAULT 'livekit',
    provider_room   TEXT NOT NULL UNIQUE,
    kind            TEXT NOT NULL,
    subject_kind    TEXT,
    subject_id      BIGINT,
    created_by      BIGINT NOT NULL REFERENCES users(id),
    status          TEXT NOT NULL DEFAULT 'created',
    started_at      TIMESTAMPTZ,
    ended_at        TIMESTAMPTZ,
    metadata        JSONB NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT media_rooms_provider_valid CHECK (provider IN ('livekit')),
    CONSTRAINT media_rooms_kind_valid CHECK (kind IN ('call', 'first_person_live')),
    CONSTRAINT media_rooms_status_valid CHECK (status IN ('created', 'active', 'ended', 'failed'))
);

CREATE INDEX idx_media_rooms_subject ON media_rooms(subject_kind, subject_id, created_at DESC);
CREATE INDEX idx_media_rooms_status_created ON media_rooms(status, created_at DESC);

CREATE TABLE media_participants (
    room_id      BIGINT NOT NULL REFERENCES media_rooms(id) ON DELETE CASCADE,
    user_id      BIGINT NOT NULL REFERENCES users(id),
    role         TEXT NOT NULL,
    joined_at    TIMESTAMPTZ,
    left_at      TIMESTAMPTZ,
    PRIMARY KEY (room_id, user_id, role),
    CONSTRAINT media_participants_role_valid CHECK (role IN ('publisher', 'viewer', 'moderator'))
);

CREATE INDEX idx_media_participants_user ON media_participants(user_id, joined_at DESC);

CREATE TABLE live_sessions (
    id              BIGSERIAL PRIMARY KEY,
    media_room_id   BIGINT NOT NULL REFERENCES media_rooms(id),
    inspection_id   BIGINT REFERENCES inspections(id),
    publisher_id    BIGINT NOT NULL REFERENCES users(id),
    station_id      BIGINT REFERENCES stations(id),
    title           TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'created',
    started_at      TIMESTAMPTZ,
    ended_at        TIMESTAMPTZ,
    latest_snapshot_asset_id BIGINT REFERENCES inspection_assets(id),
    metadata        JSONB NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT live_sessions_status_valid CHECK (status IN ('created', 'live', 'ended', 'failed'))
);

CREATE INDEX idx_live_sessions_status_created ON live_sessions(status, created_at DESC);
CREATE INDEX idx_live_sessions_inspection ON live_sessions(inspection_id, created_at DESC);

CREATE TABLE live_annotations (
    id              BIGSERIAL PRIMARY KEY,
    live_session_id BIGINT NOT NULL REFERENCES live_sessions(id) ON DELETE CASCADE,
    author_id       BIGINT NOT NULL REFERENCES users(id),
    kind            TEXT NOT NULL,
    payload         JSONB NOT NULL,
    media_ts_ms     BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT live_annotations_kind_valid CHECK (kind IN ('note', 'warning', 'screenshot', 'voice_intervention')),
    CONSTRAINT live_annotations_media_ts_nonneg CHECK (media_ts_ms IS NULL OR media_ts_ms >= 0)
);

CREATE INDEX idx_live_annotations_session_created
    ON live_annotations(live_session_id, created_at ASC);

CREATE TABLE live_recordings (
    id              BIGSERIAL PRIMARY KEY,
    live_session_id BIGINT NOT NULL REFERENCES live_sessions(id) ON DELETE CASCADE,
    asset_id        BIGINT REFERENCES inspection_assets(id),
    egress_id       TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'starting',
    started_at      TIMESTAMPTZ,
    ended_at        TIMESTAMPTZ,
    error_message   TEXT,
    CONSTRAINT live_recordings_status_valid CHECK (status IN ('starting', 'active', 'complete', 'failed'))
);

CREATE UNIQUE INDEX uq_live_recordings_egress ON live_recordings(egress_id);
CREATE INDEX idx_live_recordings_session ON live_recordings(live_session_id, id DESC);

COMMIT;
