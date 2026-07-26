-- 保存与外廓测量完全同源的车辆前景 / 裁剪后点云。
-- fused 仍是区域内完整融合场景；measured 是 MeasureFull 实际消费的点云，客户端默认展示 measured。

BEGIN;

ALTER TABLE laser_scan_jobs
    ADD COLUMN measured_object_key TEXT;

COMMENT ON COLUMN laser_scan_jobs.measured_object_key IS
    '与 stats.measured_points 和外廓结果同源的 measured PCD；无有效隔离/背景采集/raw 时为空';

COMMIT;
