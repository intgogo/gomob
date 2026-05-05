-- M-S7 车型档案库主数据（vehicle-catalog 服务）。
-- 详见 docs/architecture/server/00-server-overview.md §6.z / 02-api-contract.md §13。

BEGIN;

CREATE TABLE vehicle_models (
    id                    BIGSERIAL PRIMARY KEY,
    make                  TEXT NOT NULL,                       -- 例 比亚迪
    series                TEXT NOT NULL,                       -- 例 汉
    year                  INTEGER,                             -- 年款 例 2024
    engine_type           TEXT,                                -- EV / PHEV / ICE / HEV
    outline_features      JSONB NOT NULL DEFAULT '{}'::jsonb,  -- {length_mm,width_mm,height_mm,wheelbase_mm}
    compliance_check_list JSONB NOT NULL DEFAULT '[]'::jsonb,  -- ["合规项-001",...]
    manufacturer_doc_url  TEXT,                                -- 可空：厂家原始 PDF/Word（asset URL）
    status                TEXT NOT NULL DEFAULT 'draft',       -- draft / published / archived
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- (make, series, year) 三元组唯一；同一年款不重复
CREATE UNIQUE INDEX uq_vehicle_models_msy
    ON vehicle_models (make, series, COALESCE(year, 0));

-- 状态过滤索引（绝大多数读路径只看 published）
CREATE INDEX idx_vehicle_models_status_id
    ON vehicle_models (status, id DESC);

-- 关键字搜索：postgres 自带 trigram 模糊（M-S7.4 缓存命中率高时这块用得少）
CREATE INDEX idx_vehicle_models_make_series
    ON vehicle_models (make, series);

-- updated_at 触发器：UPDATE 时自动刷
CREATE OR REPLACE FUNCTION touch_vehicle_models_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_vehicle_models_updated_at
    BEFORE UPDATE ON vehicle_models
    FOR EACH ROW EXECUTE FUNCTION touch_vehicle_models_updated_at();

COMMIT;
