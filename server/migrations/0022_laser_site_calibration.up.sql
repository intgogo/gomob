-- 双单元激光工位外参的服务端权威配置。
-- 旧网页只把外参保存在浏览器 localStorage，Android 起扫无法读取；本表按物理单元 IP 对共享。

BEGIN;

CREATE TABLE laser_site_calibration (
    unit_a_ip       TEXT NOT NULL,
    unit_b_ip       TEXT NOT NULL,
    site_json       JSONB NOT NULL,
    source          TEXT NOT NULL DEFAULT 'unknown',
    mean_error_mm   DOUBLE PRECISION,
    max_error_mm    DOUBLE PRECISION,
    source_scan_id  BIGINT REFERENCES laser_scan_jobs(id) ON DELETE SET NULL,
    updated_by      BIGINT REFERENCES users(id) ON DELETE SET NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (unit_a_ip, unit_b_ip),
    CONSTRAINT laser_site_calibration_matrix_valid CHECK (
        jsonb_typeof(site_json) = 'object'
        AND jsonb_typeof(site_json->'b_to_a') = 'array'
        AND jsonb_array_length(site_json->'b_to_a') = 16
    )
);

-- 禁止从 laser_scan_jobs.b_to_a 回填工位外参：该字段是每次扫描经 RefineBToA
-- 场景精修后的最终姿态，不是不可变的物理工位标定。拿车辆/房间扫描结果反推标定会把
-- 当次场景偏置永久写成 canonical site。旧网页中的正式 site_json 由网页只迁移一次；
-- 没有正式资产时保持未设置，要求重新标定。

COMMIT;
