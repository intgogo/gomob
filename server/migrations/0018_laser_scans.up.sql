-- M8' 双单元激光（LIDAR-PTZ）车辆外廓扫描会话表。
--
-- 设计约束（对标 0016_scan_fusion 的表纪律，但**请求驱动而非轮询队列**）：
--   1. 与 scan_fusion_jobs（asset 上传 → fusionworker 轮询领单 FOR UPDATE SKIP LOCKED）不同：
--      激光是**交互式 live 流式**——App POST 起扫描，收到请求的那个 laserworker 进程**直接**起
--      cgo 采集 goroutine 并把点经 ws 实时推回（生产者=消费者同进程）。故本表无 attempt_count/
--      next_retry_at/poll-claim：它是会话的持久记录 + 状态机 + stop/断线重连的事实源，不是工作队列。
--   2. 一次扫描 = 两单元(.101/.102)采集 + ICP/site 配准融合，产**三朵 PCD**（fused + unitA + unitB）
--      + 可选 calib(4x4)，全部落 MinIO（gomob-assets，复用 asset presign）。
--   3. owner_user_id 沿用 0017 的语义：scan.fusion_done(kind:laser) 实时推送的路由键，复用
--      internal/signaling/fusion_bridge.go（NATS→ws，领域无关）。
--   4. 状态机：capturing → fusing → done | failed | cancelled（无 pending/processing 轮询态）。

BEGIN;

CREATE TABLE laser_scan_jobs (
    id                   BIGSERIAL PRIMARY KEY,
    session_key          TEXT NOT NULL UNIQUE,
    inspection_id        BIGINT REFERENCES inspections(id) ON DELETE SET NULL,
    owner_user_id        BIGINT REFERENCES users(id) ON DELETE SET NULL,
    unit_a_ip            TEXT NOT NULL,
    unit_b_ip            TEXT NOT NULL,
    align                TEXT NOT NULL DEFAULT 'site',  -- 请求的配准策略；生产起扫必须为 site
    align_method         TEXT,                          -- 实际采用；生产起扫必须返回 site
    keep_ratio           REAL NOT NULL DEFAULT 1.0,     -- 融合云随机降采样保留比 (0,1]
    status               TEXT NOT NULL DEFAULT 'capturing',
    pts_a                INTEGER,
    pts_b                INTEGER,
    fused                INTEGER,
    after_crop           INTEGER,
    fused_object_key     TEXT,
    unit_a_object_key    TEXT,
    unit_b_object_key    TEXT,
    calib_object_key     TEXT,        -- b_to_a 4x4（PCD 旁的 calib，可空）
    b_to_a               JSONB,       -- 4x4 行优先 mm 平移，融合时回填
    stats                JSONB NOT NULL DEFAULT '{}',
    error_message        TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT laser_scan_jobs_status_valid
        CHECK (status IN ('capturing', 'fusing', 'done', 'failed', 'cancelled')),
    CONSTRAINT laser_scan_jobs_align_valid
        CHECK (align IN ('icp', 'none', 'site')),
    CONSTRAINT laser_scan_jobs_keep_ratio_range
        CHECK (keep_ratio > 0 AND keep_ratio <= 1)
);

-- 按归属人 + 时间倒序：端侧 gallery 拉该用户历史激光扫描。
CREATE INDEX idx_laser_scan_jobs_owner
    ON laser_scan_jobs(owner_user_id, created_at DESC)
    WHERE owner_user_id IS NOT NULL;

-- 按 inspection 关联。
CREATE INDEX idx_laser_scan_jobs_inspection
    ON laser_scan_jobs(inspection_id, created_at DESC)
    WHERE inspection_id IS NOT NULL;

-- 找进行中会话（capturing/fusing）：laserworker 重启/单活并发门控。
CREATE INDEX idx_laser_scan_jobs_active
    ON laser_scan_jobs(status, created_at DESC)
    WHERE status IN ('capturing', 'fusing');

COMMIT;
