-- M-S11 LLM 网关：模板 + 调用审计。
-- 详见 docs/architecture/server/00-server-overview.md §6.v / 02-api-contract.md §15。

BEGIN;

-- ----- 模板（版本化） -----
CREATE TABLE llm_templates (
    id                 BIGSERIAL PRIMARY KEY,
    name               TEXT NOT NULL,                  -- 例 "vin_audit"
    version            INTEGER NOT NULL,               -- 单调递增（同 name 唯一）
    preferred_provider TEXT NOT NULL DEFAULT 'deepseek', -- deepseek / mock / ...
    preferred_model    TEXT,                           -- 例 deepseek-chat
    system_prompt      TEXT NOT NULL DEFAULT '',
    user_template      TEXT NOT NULL,                  -- Go text/template 语法，{{.vin}} 之类占位
    vars_schema        JSONB NOT NULL DEFAULT '{}'::jsonb, -- 字段类型声明，便于客户端校验
    status             TEXT NOT NULL DEFAULT 'draft',  -- draft / active / archived
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_llm_templates_name_version ON llm_templates (name, version);
CREATE INDEX idx_llm_templates_name_status      ON llm_templates (name, status);

CREATE OR REPLACE FUNCTION touch_llm_templates_updated_at()
RETURNS TRIGGER AS $$ BEGIN NEW.updated_at = now(); RETURN NEW; END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_llm_templates_updated_at
    BEFORE UPDATE ON llm_templates
    FOR EACH ROW EXECUTE FUNCTION touch_llm_templates_updated_at();

-- ----- 调用审计 -----
CREATE TABLE llm_call_logs (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT REFERENCES users(id) ON DELETE SET NULL,
    template_id   BIGINT REFERENCES llm_templates(id) ON DELETE SET NULL,
    template_name TEXT,                                  -- 冗余：模板归档后仍可查
    template_ver  INTEGER,                               -- 冗余
    provider      TEXT NOT NULL,                         -- 实际命中的 provider
    model         TEXT,                                  -- 实际命中的 model
    token_in      INTEGER NOT NULL DEFAULT 0,
    token_out     INTEGER NOT NULL DEFAULT 0,
    latency_ms    INTEGER NOT NULL DEFAULT 0,
    status        TEXT NOT NULL,                         -- ok / error / cancelled
    error         TEXT,                                  -- 失败时的错误摘要
    request_id    TEXT,                                  -- 请求级 trace ID，与日志关联
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_llm_call_logs_user_created ON llm_call_logs (user_id, created_at DESC);
CREATE INDEX idx_llm_call_logs_template     ON llm_call_logs (template_id, created_at DESC);

COMMIT;
