-- 固定专家发布案例：专家详情页读取真实后端数据，不在 App 内伪造。

BEGIN;

CREATE TABLE help_expert_cases (
    id           BIGSERIAL PRIMARY KEY,
    author_id    BIGINT NOT NULL REFERENCES users(id),
    title        TEXT NOT NULL,
    summary      TEXT NOT NULL DEFAULT '',
    category     TEXT NOT NULL DEFAULT '',
    status       TEXT NOT NULL DEFAULT 'draft',
    published_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT help_expert_cases_status_valid CHECK (status IN ('draft', 'published', 'archived')),
    CONSTRAINT help_expert_cases_publish_time CHECK ((status = 'draft') = (published_at IS NULL))
);

CREATE INDEX idx_help_expert_cases_author_published
    ON help_expert_cases(author_id, published_at DESC, id DESC)
    WHERE status = 'published';

COMMIT;
