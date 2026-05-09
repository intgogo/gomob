-- M5 聊天媒体资产：允许 asset 不绑定 inspection。
--
-- inspection_assets 早期只服务查验流程，聊天图片 / 语音 / 视频片段同样需要
-- 稳定 asset_id 和对象存储 key。保持原表复用，inspection_id 为空表示通用媒体资产。

BEGIN;

ALTER TABLE inspection_assets
    ALTER COLUMN inspection_id DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_assets_orphan_created
    ON inspection_assets(created_at DESC)
    WHERE inspection_id IS NULL;

COMMIT;
