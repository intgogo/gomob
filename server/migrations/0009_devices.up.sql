-- M-S3 device：Berxel 相机绑定 + 双摄标定参数版本化云同步
--
-- 设计原则（详见 docs/architecture/server/00-server-overview.md §6.x）：
--   1. devices：物理设备 ↔ 用户绑定。serial_number 全系统唯一（同一物理相机
--      同一时刻只能挂在一个用户名下）；同 user 多设备列表正常。重复 serial 绑回
--      自身（同 user）= patch 行为（更新 firmware/nickname），不报 409。
--      跨用户撞 serial = 409；老主人需先 retire 才能转手。
--   2. device_calibrations：标定参数不可变历史。每次"重新标定"= 写入新 row（version+1）。
--      version 在 devices.calibration_seq 列里维护单调；事务里 FOR UPDATE 取 + 1 写回，
--      避免并发 race。同 sha256 不再 bump（幂等：客户端重传同一份不浪费版本号）。
--   3. params 用 JSONB 存（完整内/外参 + 重投影误差 + ts）；服务端不解析具体字段，
--      由 cv-engine / 端侧 native-bridge 自己消费；server 仅记 reprojection_error 一个 REAL
--      列做最低限度索引/质检告警。
--   4. App 端"扫描启动前比对版本"通过 GET /v1/devices/{id}/calibrations/latest
--      返 (version, sha256, calibrated_at) 完成；只有 sha256 不一致才下载完整 params。

BEGIN;

-- ─── 1) devices：物理设备 ↔ 用户绑定 ───────────────────────────────────────
CREATE TABLE devices (
    id                 BIGSERIAL PRIMARY KEY,
    user_id            BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    serial_number      TEXT NOT NULL,                            -- 物理唯一序列号（Berxel 模组自带）
    manufacturer       TEXT NOT NULL DEFAULT 'berxel',
    model              TEXT NOT NULL,                            -- iHawk / iHawk-Pro / 兼容 UVC 等
    firmware_version   TEXT NOT NULL,
    sdk_version        TEXT,                                     -- 可选：模组 SDK 版本（端侧上报）
    nickname           TEXT,                                     -- 用户取名 'iHawk-072'
    status             TEXT NOT NULL DEFAULT 'active',           -- active / retired
    last_seen_at       TIMESTAMPTZ,                              -- 端侧每次扫描启动时 touch

    calibration_seq    BIGINT NOT NULL DEFAULT 0,                -- 内部版本计数（FOR UPDATE 取 +1）

    note               TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    retired_at         TIMESTAMPTZ,

    -- 一台物理相机同一时刻只能挂一个 active 用户（retired 后允许转手 — 用 partial unique）
    CONSTRAINT devices_status_valid CHECK (status IN ('active','retired')),
    CONSTRAINT devices_manufacturer_valid CHECK (manufacturer IN ('berxel','generic_uvc','unknown'))
);

-- 同 serial 同一时刻至多 1 active；老主人 retire 后可转手
CREATE UNIQUE INDEX uq_devices_serial_active
    ON devices(serial_number) WHERE status = 'active';

-- 列表：按用户 + 创建时间倒序
CREATE INDEX idx_devices_user_status_created
    ON devices(user_id, status, created_at DESC);

CREATE OR REPLACE FUNCTION touch_devices_updated_at()
RETURNS TRIGGER AS $$
BEGIN NEW.updated_at = now(); RETURN NEW; END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER touch_devices_updated_at
    BEFORE UPDATE ON devices
    FOR EACH ROW EXECUTE FUNCTION touch_devices_updated_at();

-- ─── 2) device_calibrations：标定参数不可变历史 ────────────────────────────
CREATE TABLE device_calibrations (
    id                  BIGSERIAL PRIMARY KEY,
    device_id           BIGINT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    version             BIGINT NOT NULL,                         -- 自 devices.calibration_seq
    params              JSONB NOT NULL,                          -- 内 / 外参 / 重投影误差 / ts ...（不透明）
    sha256              TEXT NOT NULL,                           -- 客户端算的 SHA256(canonical(params))
    reprojection_error  REAL,                                    -- 抽出 1 个最关键 metric 做索引/告警
    calibrated_at       TIMESTAMPTZ NOT NULL,                    -- 端侧实际标定时刻（非上传时刻）
    uploaded_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    note                TEXT,

    UNIQUE (device_id, version),
    UNIQUE (device_id, sha256),                                  -- 同设备重传同一份 → 幂等不 bump

    CONSTRAINT calibrations_version_positive CHECK (version > 0),
    CONSTRAINT calibrations_reproj_nonneg
        CHECK (reprojection_error IS NULL OR reprojection_error >= 0)
);

CREATE INDEX idx_calibrations_device_version_desc
    ON device_calibrations(device_id, version DESC);

COMMIT;
