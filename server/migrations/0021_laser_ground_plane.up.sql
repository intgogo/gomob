-- M13 持久化工位地面平面（外廓测量精度收敛，finding_laser_dimension_error_rootcause_2026-07-09）。
--
-- 为什么需要：地面此前每次扫描对整房间融合云重新 RANSAC 拟合（内点率仅 ~34-40%），法向逐扫描漂移
-- ~2°、d 漂移 ~36mm；测量在地面正交基里做，2° 倾斜投影到车体上 = W ±20mm / H ±13mm 的逐扫描方差
-- ——而融合点云本身的逐扫描重复性实测只有 1mm（job183/184/185 交叉实验：互换地面即完全复现对方读数）。
-- 固定安装下真实地面不动 → 采集空工位背景时拟合一次高质量地面并持久化，此后每次扫描复用；
-- 每扫描的重拟合仅作漂移告警。真机验证：持久化地面后 L/W/H 逐扫描波动 0.6/2/0.2mm。
--
-- 一个装机点（bay_key=unit_a_ip）一行；plane = internal/laser.GroundPlane JSON。

BEGIN;

CREATE TABLE laser_ground_plane (
    bay_key    TEXT PRIMARY KEY,          -- 工位/装机标识，默认 unit_a_ip（与 laser_crop_box 同约定）
    plane      JSONB NOT NULL,            -- GroundPlane: {nx,ny,nz,d,inlier_ratio,valid}，unit_a 世界系(mm)
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE laser_ground_plane IS 'M13 持久化工位地面平面：空工位背景采集时拟合一次，扫描测量复用，消除逐扫描 RANSAC 重拟合方差';

COMMIT;
