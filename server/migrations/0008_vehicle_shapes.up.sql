-- M-S9 shape-ref：车型 3D 外廓参考库
--
-- 设计原则（详见 docs/architecture/server/00-server-overview.md §6.z）：
--   1. 一车型多版本（version_name 唯一）；状态机 draft → published → archived
--      与 vin-ref 一致：partial unique 同 vehicle_model 至多 1 published；
--      Publish 在事务里把同 vehicle_model 的旧 published 自动 archive
--   2. mesh 大文件（GB 级 glTF / PLY）不进库；只存 object_key + sha256 + size + format；
--      字节流走 asset 服务的 PresignedGetObject（默认 5 分钟），App 端 HTTP Range 续传
--   3. 几何元数据：triangle_count / point_count / 3D bbox（min/max 各 3 个 REAL）；
--      coverage / qc_score 入库前由 admin 流程 / cv-engine 离线计算填好
--   4. source 区分采集来源（factory_cad / scan_high_res / manual_modeled），
--      让 App 端 / 复核员能区分参考是 CAD 标准还是扫描重建

BEGIN;

CREATE TABLE vehicle_shapes (
    id                BIGSERIAL PRIMARY KEY,
    vehicle_model_id  BIGINT NOT NULL REFERENCES vehicle_models(id) ON DELETE RESTRICT,
    version_name      TEXT NOT NULL,                          -- 例 "v1.0_2024Q1" / "factory_cad_2024_03"
    description       TEXT,
    source            TEXT NOT NULL DEFAULT 'unknown',        -- factory_cad / scan_high_res / manual_modeled / unknown
    captured_at       TIMESTAMPTZ,                            -- 厂家提供 / 扫描重建日期
    captured_by       TEXT,                                   -- 上报人 / 厂家联系方式

    -- mesh 资产（asset MinIO bucket 内）
    mesh_object_key   TEXT NOT NULL,
    mesh_sha256       TEXT NOT NULL,
    mesh_size_bytes   BIGINT NOT NULL,
    mesh_format       TEXT NOT NULL,                          -- glb / ply / stl / obj

    -- 几何元数据（入库前外部计算）
    triangle_count    BIGINT,                                 -- 网格三角面数（PLY/STL/GLB）
    point_count       BIGINT,                                 -- 点云总点数（点云型 PLY 等）
    bbox_min_x        REAL, bbox_min_y REAL, bbox_min_z REAL, -- 局部坐标系 min 角
    bbox_max_x        REAL, bbox_max_y REAL, bbox_max_z REAL, -- max 角

    -- 质检
    coverage          REAL,                                   -- 0-1 扫描覆盖度（点云 / 重建专用）
    qc_score          REAL,                                   -- 0-1 质检分
    qc_notes          TEXT,                                   -- 质检备注

    -- 状态机
    status            TEXT NOT NULL DEFAULT 'draft',          -- draft / published / archived
    note              TEXT,
    created_by        BIGINT REFERENCES users(id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at      TIMESTAMPTZ,
    archived_at       TIMESTAMPTZ,

    UNIQUE (vehicle_model_id, version_name),

    CONSTRAINT vehicle_shapes_format_valid CHECK
        (mesh_format IN ('glb','ply','stl','obj','gltf')),
    CONSTRAINT vehicle_shapes_source_valid CHECK
        (source IN ('factory_cad','scan_high_res','manual_modeled','unknown')),
    CONSTRAINT vehicle_shapes_coverage_range CHECK
        (coverage IS NULL OR (coverage >= 0 AND coverage <= 1)),
    CONSTRAINT vehicle_shapes_qc_range CHECK
        (qc_score IS NULL OR (qc_score >= 0 AND qc_score <= 1)),
    CONSTRAINT vehicle_shapes_size_positive CHECK
        (mesh_size_bytes > 0)
);

-- 同 vehicle_model 至多 1 published
CREATE UNIQUE INDEX uq_vehicle_shapes_one_published
    ON vehicle_shapes(vehicle_model_id) WHERE status = 'published';

-- 列表 / 历史查询
CREATE INDEX idx_vehicle_shapes_vm_status
    ON vehicle_shapes(vehicle_model_id, status, published_at DESC);
CREATE INDEX idx_vehicle_shapes_vm_created
    ON vehicle_shapes(vehicle_model_id, created_at DESC);

-- updated_at 触发器（命名规则与 vehicle_models / llm_templates 对齐）
CREATE OR REPLACE FUNCTION touch_vehicle_shapes_updated_at()
RETURNS TRIGGER AS $$
BEGIN NEW.updated_at = now(); RETURN NEW; END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER touch_vehicle_shapes_updated_at
    BEFORE UPDATE ON vehicle_shapes
    FOR EACH ROW EXECUTE FUNCTION touch_vehicle_shapes_updated_at();

COMMIT;
