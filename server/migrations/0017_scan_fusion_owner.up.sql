-- M3.15 给融合任务加 owner_user_id:作为 scan.fusion_done 实时推送的路由键。
--
-- 设计:谁发起扫描上传(asset 上传完成的鉴权用户)就是该融合产物的归属人;
-- 融合完成后 signaling 进程订阅 scan.fusion_done,按 owner_user_id 把事件推给该用户的
-- 所有在线 ws 连接(端侧 gallery 据此拉 GLB 回看)。
-- 可空:harness / inspection-less 直接入队的任务无鉴权用户,owner 为空时不做实时推送(仍可轮询)。

BEGIN;

ALTER TABLE scan_fusion_jobs
    ADD COLUMN owner_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL;

CREATE INDEX idx_scan_fusion_jobs_owner
    ON scan_fusion_jobs(owner_user_id, created_at DESC)
    WHERE owner_user_id IS NOT NULL;

COMMIT;
