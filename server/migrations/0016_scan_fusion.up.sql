-- M3.14 多视角 RGBD 云端融合任务队列。
--
-- 设计约束(对标 0015_message_transcripts 的"派生+可重试队列"形态):
--   1. 一个扫描会话(session_key 唯一)的 N 张 RgbdShot 打成一个 bundle zip 传 MinIO;
--      input_object_key 指向该 zip,是融合输入的事实源。
--   2. 融合派生 GLB(result_object_key),可失败可重试;status + next_retry_at + attempt_count 做 DB 轮询队列。
--   3. fusionworker 经 FOR UPDATE SKIP LOCKED 领任务,完成发 NATS scan.fusion_done。

BEGIN;

CREATE TABLE scan_fusion_jobs (
    id                BIGSERIAL PRIMARY KEY,
    session_key       TEXT NOT NULL UNIQUE,
    inspection_id     BIGINT REFERENCES inspections(id) ON DELETE SET NULL,
    input_object_key  TEXT NOT NULL,
    frame_count       INTEGER NOT NULL DEFAULT 0,
    status            TEXT NOT NULL DEFAULT 'pending',
    result_object_key TEXT,
    vertices          INTEGER,
    triangles         INTEGER,
    stats             JSONB NOT NULL DEFAULT '{}',
    error_message     TEXT,
    attempt_count     INTEGER NOT NULL DEFAULT 0,
    next_retry_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT scan_fusion_jobs_status_valid
        CHECK (status IN ('pending', 'processing', 'done', 'failed')),
    CONSTRAINT scan_fusion_jobs_attempt_nonneg CHECK (attempt_count >= 0),
    CONSTRAINT scan_fusion_jobs_frame_count_nonneg CHECK (frame_count >= 0)
);

CREATE INDEX idx_scan_fusion_jobs_queue
    ON scan_fusion_jobs(status, next_retry_at, created_at)
    WHERE status IN ('pending', 'failed');

CREATE INDEX idx_scan_fusion_jobs_inspection
    ON scan_fusion_jobs(inspection_id, created_at DESC);

COMMIT;
