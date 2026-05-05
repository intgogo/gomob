-- M-S4 signaling：单聊 server_seq 单调递增 + p2p 会话唯一化 + 离线视频邀请兜底
--
-- 设计要点（详见 docs/architecture/server/00-server-overview.md §7 / §8）：
--   1. conversations.next_seq — server_seq 分配源；UPDATE 行锁保证原子，
--      杜绝 SELECT MAX(seq)+1 的竞争窗口。
--   2. conversations.p2p_key — p2p 会话归一化 key（LEAST(a,b):GREATEST(a,b)），
--      partial unique index 防止同对用户重复建会话。
--   3. pending_calls — 被叫离线时存 60s TTL；上线时 signaling 一次性下发未过期 invite。

BEGIN;

-- 单聊顺序号源（每会话独立递增）
ALTER TABLE conversations ADD COLUMN next_seq BIGINT NOT NULL DEFAULT 1;

-- p2p 会话归一化 key（仅 p2p 用）
ALTER TABLE conversations ADD COLUMN p2p_key TEXT;
CREATE UNIQUE INDEX uq_conversations_p2p_key
    ON conversations(p2p_key) WHERE p2p_key IS NOT NULL;

-- 离线视频邀请兜底
CREATE TABLE pending_calls (
    id            BIGSERIAL PRIMARY KEY,
    call_id       TEXT NOT NULL UNIQUE,                 -- 客户端 idempotent ID
    caller_id     BIGINT NOT NULL REFERENCES users(id),
    callee_id     BIGINT NOT NULL REFERENCES users(id),
    sdp_offer     JSONB NOT NULL,                       -- 完整 SDP offer
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expire_at     TIMESTAMPTZ NOT NULL,                 -- created_at + ttl（一般 60s）
    status        TEXT NOT NULL DEFAULT 'pending',      -- pending / delivered / expired / cancelled
    delivered_at  TIMESTAMPTZ
);
CREATE INDEX idx_pending_calls_callee_pending
    ON pending_calls(callee_id, expire_at)
    WHERE status = 'pending';
CREATE INDEX idx_pending_calls_expire ON pending_calls(expire_at);

COMMIT;
