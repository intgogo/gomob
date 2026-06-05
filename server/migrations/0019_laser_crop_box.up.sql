-- M9.11 持久 3D 裁剪框（车位/扫描区）。
--
-- 为什么需要：双单元激光设备只有 pan(水平) + 俯仰两个角度闸门、无深度闸门，真机实测把扫描限到
-- "车"的立体角后远处办公室点仍保留 99%（背景藏在车的同一视线立体角里）——扫描角根本分不开。
-- 唯一能按深度隔离的是 3D 框软件裁剪。两单元螺丝固定 → 框定义在 unit_a/融合世界系即跨扫描稳定，
-- 不依赖不可靠的自动地面 RANSAC（实测常拟到天花）。用户一次圈定车位框 → 持久化 → 每次扫描裁框内量。
--
-- 一个装机点（一对单元）= 一行，bay_key 默认取 unit_a_ip（固定 master 标识车位）。

BEGIN;

CREATE TABLE laser_crop_box (
    bay_key    TEXT PRIMARY KEY,          -- 工位/装机标识，默认 unit_a_ip
    box        JSONB NOT NULL,            -- CropBox: {center[3], up[3], yaw_deg, half[3]} mm，unit_a 世界系 OBB
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE laser_crop_box IS 'M9.11 持久 3D 裁剪框(车位)：用户一次圈定，每次扫描裁到框内测量，不依赖自动地面';

COMMIT;
