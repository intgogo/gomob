# 激光长扫预览必须与权威全量云分层

## Why

旧 App 对 A/B 点云无界累积并每 220ms 全量复制，192MiB Java heap 在约 44 秒稳定 OOM；同时
`PointCloud3dView` 固定预分配 350 万顶点并保活最多 96MiB 上传缓冲，会放大 gfxstream Vulkan 分配等待。
真实点云实测表明空间体素比原始 stride/随机采样更能保留场景覆盖，不能靠堆高点数解决扫描线混叠。

## How to apply

服务端全量 PCD、融合、测量和导出永远保持权威，不用 `keep_ratio` 偷降保真度。App 实时预览使用固定容量
嵌套体素，激光点流走独立小队列；最终回看只下载服务端从权威 PCD 派生的有界样本，并同时保留
`sourcePointCount`/`renderPointCount`。Filament 顶点数按调用方预算创建，异步上传只用完成回调管理的固定双槽。
改动后必须跑 `laser_live_preview_memory` 的 55 秒取消档和自然完成档，两档都要确认无 OOM/ANR 且无遗留任务；
存在正式工位外参时，自然完成档还必须设置 `LASER_LIVE_PREVIEW_REQUIRE_FUSED=1`，确认 fused/A/B 三朵有界
彩色 PCD 与 Filament 完成态全部加载，不能以 A/B-only 代替。分析必须按 scanning/fusing/completed 分段，
完成页至少留观 45 秒，不能把最终云的一次性载入台阶误判成泄漏。scan209 正式 ArUco 真扫严格 PASS：
Dalvik 峰值 29.7MiB，完成态 47 秒稳定，无 OOM、ANR、native/Vulkan 异常。
