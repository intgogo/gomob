-- M-S8 vin-ref：车驾号字形参考库
--
-- 设计原则（详见 docs/architecture/server/00-server-overview.md §6.z）：
--   1. 按"车型 × 批次 × 字符"三层索引，匹配 gosmart `Sample/SampleRepo` 的批次心智
--      并升级到字符级别（cv-engine 在 M-S10 改 doCompareVin 时按 (vehicle_model_id, character) 拉对照集）
--   2. 状态机 draft → published → archived，与 catalog / llm / models 一致；
--      activate 时事务里 archive 旧 active，partial unique index 保证同一 vehicle_model 至多 1 published
--   3. 字段对齐 gosmart 的 VinMore / Item：character / arr_mode / font_id / font_family_id /
--      alpha (掩膜，webp) / origin (彩色，webp) / qc_score；feature_vector_uri 给 cv-engine
--      未来预提的特征向量留位；新增 position_hint 给"按 VIN 位置可选过滤"留口
--   4. 大文件不进库：图本体走 asset MinIO；表里只存 object_key + sha256 + size + 可选 presign
--   5. CHECK 约束限定 character 只允许 VIN 33 字符（0-9 + A-Z 去 I O Q），大写化由应用层做

BEGIN;

-- 字形参考批次（一次厂家送来的样本集）
CREATE TABLE vin_glyph_batches (
    id                BIGSERIAL PRIMARY KEY,
    vehicle_model_id  BIGINT NOT NULL REFERENCES vehicle_models(id) ON DELETE RESTRICT,
    name              TEXT NOT NULL,                          -- 例 "factory_2024Q1" / "比亚迪汉_2024_03"
    description       TEXT,
    captured_at       TIMESTAMPTZ,                            -- 厂家采集日期（≠ 入库时间）
    captured_by       TEXT,                                   -- 上报人 / 厂家联系方式
    sample_count      INT NOT NULL DEFAULT 0,                 -- 当前批次的样本数（写样本时增量更新）
    status            TEXT NOT NULL DEFAULT 'draft',          -- draft / published / archived
    note              TEXT,                                   -- 质检备注 / 异常记录
    created_by        BIGINT REFERENCES users(id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at      TIMESTAMPTZ,
    archived_at       TIMESTAMPTZ,
    UNIQUE (vehicle_model_id, name)                           -- 同车型批次名唯一
);

-- 同一 vehicle_model 至多 1 个 published 批次（partial unique）
CREATE UNIQUE INDEX uq_vin_batches_one_published
    ON vin_glyph_batches(vehicle_model_id) WHERE status = 'published';

-- 列表 / 拉取常用过滤
CREATE INDEX idx_vin_batches_vm_status
    ON vin_glyph_batches(vehicle_model_id, status, published_at DESC);

-- updated_at 触发器（与 vehicle_models / llm_templates 命名规则一致：touch_<table>_updated_at）
CREATE OR REPLACE FUNCTION touch_vin_glyph_batches_updated_at()
RETURNS TRIGGER AS $$
BEGIN NEW.updated_at = now(); RETURN NEW; END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER touch_vin_glyph_batches_updated_at
    BEFORE UPDATE ON vin_glyph_batches
    FOR EACH ROW EXECUTE FUNCTION touch_vin_glyph_batches_updated_at();

-- 字形样本（每条 = 一个字符的一个参考样图）
CREATE TABLE vin_glyph_samples (
    id                  BIGSERIAL PRIMARY KEY,
    batch_id            BIGINT NOT NULL REFERENCES vin_glyph_batches(id) ON DELETE CASCADE,
    character           CHAR(1) NOT NULL,                     -- VIN 33 字符之一
    arr_mode            SMALLINT NOT NULL DEFAULT 0,          -- VinArrMode: 0 unknown / 1 line / 2 dline / 3 arc
    font_id             TEXT NOT NULL DEFAULT '*',            -- 模板 / 厂家字体 ID（默认 '*' 兼容 gosmart 现状）
    font_family_id      TEXT,                                 -- 字体族 ID（多字体批次可分组）
    position_hint       SMALLINT,                             -- 1..17 VIN 字符位置（NULL = 通用）
    alpha_object_key    TEXT NOT NULL,                        -- MinIO key：alpha 掩膜（webp）
    alpha_sha256        TEXT NOT NULL,
    alpha_size_bytes    BIGINT NOT NULL,
    origin_object_key   TEXT,                                 -- 原始彩色图 object_key（可选）
    origin_sha256       TEXT,
    origin_size_bytes   BIGINT,
    feature_vector_uri  TEXT,                                 -- 预提的特征向量 object_key（M-S10 cv-engine 用）
    qc_score            REAL,                                 -- 0-1：清晰度 / 完整度 质检评分
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT vin_glyph_char_valid CHECK
        (character IN ('0','1','2','3','4','5','6','7','8','9',
                       'A','B','C','D','E','F','G','H','J','K','L','M','N',
                       'P','R','S','T','U','V','W','X','Y','Z')),
    CONSTRAINT vin_glyph_arr_mode_range CHECK (arr_mode BETWEEN 0 AND 3),
    CONSTRAINT vin_glyph_position_range CHECK (position_hint IS NULL OR position_hint BETWEEN 1 AND 17),
    CONSTRAINT vin_glyph_qc_range      CHECK (qc_score IS NULL OR (qc_score >= 0 AND qc_score <= 1))
);

CREATE INDEX idx_vin_samples_batch_char ON vin_glyph_samples(batch_id, character);
CREATE INDEX idx_vin_samples_char       ON vin_glyph_samples(character);

-- sample_count 自动维护：插入 / 删除时同步 batch.sample_count
CREATE OR REPLACE FUNCTION touch_vin_glyph_sample_count() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE vin_glyph_batches SET sample_count = sample_count + 1 WHERE id = NEW.batch_id;
    ELSIF TG_OP = 'DELETE' THEN
        UPDATE vin_glyph_batches SET sample_count = GREATEST(sample_count - 1, 0) WHERE id = OLD.batch_id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER touch_vin_glyph_sample_count_ins
    AFTER INSERT ON vin_glyph_samples
    FOR EACH ROW EXECUTE FUNCTION touch_vin_glyph_sample_count();
CREATE TRIGGER touch_vin_glyph_sample_count_del
    AFTER DELETE ON vin_glyph_samples
    FOR EACH ROW EXECUTE FUNCTION touch_vin_glyph_sample_count();

COMMIT;
