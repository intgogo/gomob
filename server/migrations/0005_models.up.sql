-- M-S5 model-registry：AI 模型元数据 + canary 路由。
-- 详见 docs/architecture/server/00-server-overview.md §6.y。

BEGIN;

-- ----- 模型版本元数据 -----
CREATE TABLE models (
    id           BIGSERIAL PRIMARY KEY,
    name         TEXT NOT NULL,                     -- 模型逻辑名 e.g. yolo_vin / glyph_match
    version      TEXT NOT NULL,                     -- 语义版本 e.g. v1.2.0 / v1.2.0-rc1
    asset_uri    TEXT NOT NULL,                     -- 二进制存储 URI（asset bucket models/<name>/<version>.onnx）
    sha256       TEXT NOT NULL,                     -- 二进制内容寻址
    runtime      TEXT NOT NULL DEFAULT 'onnx',      -- onnx / tensorrt / torch / ...
    framework    TEXT,                              -- 例 ultralytics / pytorch
    metadata     JSONB NOT NULL DEFAULT '{}'::jsonb, -- input shape / labels / 训练数据集等
    status       TEXT NOT NULL DEFAULT 'draft',     -- draft / canary / active / archived
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_models_name_version ON models (name, version);
-- 同 name 至多 1 个 active；至多 1 个 canary（用部分唯一索引强约束）
CREATE UNIQUE INDEX uq_models_one_active  ON models (name) WHERE status = 'active';
CREATE UNIQUE INDEX uq_models_one_canary  ON models (name) WHERE status = 'canary';
CREATE INDEX idx_models_name_status_id    ON models (name, status, id DESC);

CREATE OR REPLACE FUNCTION touch_models_updated_at()
RETURNS TRIGGER AS $$ BEGIN NEW.updated_at = now(); RETURN NEW; END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_models_updated_at
    BEFORE UPDATE ON models
    FOR EACH ROW EXECUTE FUNCTION touch_models_updated_at();

-- ----- canary 灰度路由 -----
-- 每个模型 name 一行；canary_pct = 0~100；canary_user_filter 可选用户白名单 / 检测站等
CREATE TABLE model_routes (
    name               TEXT PRIMARY KEY,
    canary_pct         SMALLINT NOT NULL DEFAULT 0,             -- 0 = 全走 active
    canary_user_filter JSONB NOT NULL DEFAULT '{}'::jsonb,      -- {"user_ids":[..], "station_ids":[..]}
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (canary_pct >= 0 AND canary_pct <= 100)
);

CREATE OR REPLACE FUNCTION touch_model_routes_updated_at()
RETURNS TRIGGER AS $$ BEGIN NEW.updated_at = now(); RETURN NEW; END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_model_routes_updated_at
    BEFORE UPDATE ON model_routes
    FOR EACH ROW EXECUTE FUNCTION touch_model_routes_updated_at();

COMMIT;
