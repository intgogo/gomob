-- M-S2 资产上传：会话表（断点续传）+ inspection_assets 关联回填

BEGIN;

-- 上传会话（分片上传中间态）
CREATE TABLE upload_sessions (
    upload_id          TEXT PRIMARY KEY,                     -- 用户可见 ID（短，URL safe）
    s3_upload_id       TEXT NOT NULL,                        -- MinIO 真实 multipart upload id
    user_id            BIGINT NOT NULL REFERENCES users(id),
    inspection_id      BIGINT REFERENCES inspections(id) ON DELETE SET NULL,
    bucket             TEXT NOT NULL,                        -- 例 'assets'
    object_key         TEXT NOT NULL,                        -- 例 'inspection/9001/scan3d/abc.bin'
    kind               TEXT NOT NULL,                        -- scan3d / vin_plate / nameplate / exterior / video / pdf
    expected_size      BIGINT NOT NULL,
    expected_sha256    TEXT NOT NULL,
    mime               TEXT NOT NULL DEFAULT 'application/octet-stream',
    chunk_size         INTEGER NOT NULL DEFAULT 4194304,     -- 4 MB
    status             TEXT NOT NULL DEFAULT 'pending',      -- pending / completed / aborted / expired
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at       TIMESTAMPTZ,
    completed_asset_id BIGINT REFERENCES inspection_assets(id) ON DELETE SET NULL
);
CREATE INDEX idx_upload_sessions_user ON upload_sessions(user_id, created_at DESC);
CREATE INDEX idx_upload_sessions_status ON upload_sessions(status) WHERE status = 'pending';

COMMIT;
