-- gomob 初始数据模型
-- 来源：docs/architecture/06-product-features.md 的功能清单 + server/00-server-overview.md

BEGIN;

-- 检测站组织
CREATE TABLE stations (
    id              BIGSERIAL PRIMARY KEY,
    name            TEXT NOT NULL,                          -- 杭州市西湖区车管所检测站
    region          TEXT,                                   -- 行政区
    gateway_addr    TEXT,                                   -- 网关 IP:Port（"网络设置"展示）
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 用户（查验员 / 监管员 / 复核员 / 管理员）
CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    username        TEXT NOT NULL UNIQUE,                   -- 登录用户名 shenhm
    real_name       TEXT NOT NULL,                          -- 真实姓名 沈海明
    employee_id     TEXT NOT NULL UNIQUE,                   -- 工号 ZAA0120230001
    station_id      BIGINT REFERENCES stations(id),
    password_hash   TEXT NOT NULL,                          -- bcrypt
    role            TEXT NOT NULL DEFAULT 'inspector',      -- inspector / supervisor / reviewer / admin
    status          TEXT NOT NULL DEFAULT 'pending',        -- pending / active / disabled
    note            TEXT,                                   -- 注册时填写的"说明信息"
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_at    TIMESTAMPTZ
);
CREATE INDEX idx_users_status ON users(status);

-- 车辆档案
CREATE TABLE vehicles (
    id              BIGSERIAL PRIMARY KEY,
    vin             TEXT NOT NULL UNIQUE,                   -- 17 位 VIN
    plate_no        TEXT,                                   -- 车牌号 沪A12345
    brand           TEXT,                                   -- 大众系列
    type            TEXT,                                   -- 小型汽车
    model_code      TEXT,                                   -- 车型代码
    year_code       CHAR(1),                                -- 车辆年份代码 F
    factory_date    DATE,                                   -- 出厂日期 2021-07
    color           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_vehicles_vin ON vehicles(vin);

-- 查验记录
CREATE TABLE inspections (
    id              BIGSERIAL PRIMARY KEY,
    vehicle_id      BIGINT NOT NULL REFERENCES vehicles(id),
    inspector_id    BIGINT NOT NULL REFERENCES users(id),
    station_id      BIGINT NOT NULL REFERENCES stations(id),
    -- 智能预审结果
    preliminary_verdict TEXT,                               -- pass / warning / fail / pending
    preliminary_reasons JSONB,                              -- ["车型代码异常","OBD 检测异常"...]
    -- 流程状态
    status          TEXT NOT NULL DEFAULT 'created',        -- created / scanning / preliminary / pending_review / closed
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at       TIMESTAMPTZ
);
CREATE INDEX idx_inspections_inspector ON inspections(inspector_id, created_at DESC);
CREATE INDEX idx_inspections_status ON inspections(status, created_at DESC);

-- 查验资产（图片 / 3D 扫描 / 视频）
CREATE TABLE inspection_assets (
    id              BIGSERIAL PRIMARY KEY,
    inspection_id   BIGINT NOT NULL REFERENCES inspections(id) ON DELETE CASCADE,
    kind            TEXT NOT NULL,                          -- vin_plate / nameplate / exterior / scan3d / video / pdf
    object_key      TEXT NOT NULL,                          -- MinIO key
    sha256          TEXT NOT NULL,                          -- 内容寻址
    size_bytes      BIGINT NOT NULL,
    mime            TEXT NOT NULL,
    metadata        JSONB,                                  -- 扫描参数 / 标定快照 / 时间戳同步
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_assets_inspection ON inspection_assets(inspection_id);

-- 抽查复核
CREATE TABLE reviews (
    id              BIGSERIAL PRIMARY KEY,
    inspection_id   BIGINT NOT NULL REFERENCES inspections(id),
    reviewer_id     BIGINT REFERENCES users(id),
    decision        TEXT,                                   -- correct / incorrect / skipped
    reason          TEXT,
    assigned_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at      TIMESTAMPTZ,
    expire_at       TIMESTAMPTZ                             -- 过期时间（"历史过期"统计）
);
CREATE INDEX idx_reviews_reviewer ON reviews(reviewer_id, decided_at DESC);
CREATE INDEX idx_reviews_expire ON reviews(expire_at) WHERE decided_at IS NULL;

-- 消息（含群、单聊、系统）
CREATE TABLE conversations (
    id              BIGSERIAL PRIMARY KEY,
    kind            TEXT NOT NULL,                          -- p2p / group / system
    title           TEXT,                                   -- 群名 / 系统消息标题
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE conversation_members (
    conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id         BIGINT NOT NULL REFERENCES users(id),
    joined_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (conversation_id, user_id)
);

CREATE TABLE messages (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    sender_id       BIGINT REFERENCES users(id),
    server_seq      BIGINT NOT NULL,                        -- 顺序号（按 conversation 单调递增）
    kind            TEXT NOT NULL,                          -- text / image / video_call / video_clip / system
    payload         JSONB NOT NULL,                         -- 内容
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (conversation_id, server_seq)
);
CREATE INDEX idx_messages_conv ON messages(conversation_id, server_seq DESC);

-- 视频通话记录
CREATE TABLE call_logs (
    id              BIGSERIAL PRIMARY KEY,
    caller_id       BIGINT NOT NULL REFERENCES users(id),
    callee_id       BIGINT NOT NULL REFERENCES users(id),
    started_at      TIMESTAMPTZ NOT NULL,
    ended_at        TIMESTAMPTZ,
    duration_sec    INTEGER,
    status          TEXT NOT NULL                           -- completed / missed / rejected / dropped
);

-- 审计日志
CREATE TABLE audit_log (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT REFERENCES users(id),
    action          TEXT NOT NULL,                          -- e.g. inspection.update_result
    target          TEXT,                                   -- inspection:123
    before          JSONB,
    after           JSONB,
    ip              INET,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMIT;
