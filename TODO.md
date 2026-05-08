# TODO

> 单一真理源。**不另起 `docs/plans/`**。完成项从本文件删除，历史记录看 git log。
>
> 写作纪律：`docs/agent-memory/feedback_plan_writing_quality.md`。每条任务必须有明确验收与文档路径。

## 当前主线

1. **M1 iHawk 帧链路**：先把 Color/Depth 字节流、内参、预览跑实。
2. **M3 多视角 RGBD 重建**：阶段 1 先做端侧采集 + 云端融合闭环。
3. **M4 VIN 数码拓印**：复用同一 RGBD 采集链路，接 cv-engine `vin_pipeline`。
4. **M5 实时消息与第一视角协作**：消息控制面自研，视频 / 直播媒体面接自托管 LiveKit。

## M5 实时消息与第一视角协作

> 控制面：gomob 自研 WebSocket + REST + PostgreSQL，负责消息顺序、会话、未读、邀请、审计。
> 媒体面：自托管 LiveKit + coturn + Egress，负责视频通话、第一视角直播、录制。
> docs: `docs/architecture/09-realtime-message-live.md` / `docs/architecture/09-realtime-message-live-implementation.md`

| ID | 任务 | 验收 | 文档 |
|----|------|------|------|
| M5.4 | LiveKit 媒体控制面：接自托管 LiveKit 配置，服务端实现 `POST /v1/media/rooms`、`POST /v1/media/rooms/{id}/token`、`POST /v1/media/rooms/{id}/end`、`POST /v1/livekit/webhook`；`core:media` 接 LiveKit Android SDK，封装 `MediaRoomClient`。 | `tests/harness/livekit_room_lifecycle` 通过：创建 room、签发 publisher/viewer token、两个测试客户端加入、断开后 room status 变 `ended`；服务端拒绝非成员拿 token。 | `docs/architecture/09-realtime-message-live-implementation.md` §5 |
| M5.5 | 1:1 视频通话：`media.invite` 替代旧 P2P SDP 语义；单聊视频按钮创建 call room；被叫前台在线收到来电弹层；通话页全屏远端视频 + 角落本地预览 + 静音/摄像头/挂断；结束后写 `call_logs` 并追加 `kind=video_call` 消息。 | `tests/harness/livekit_call_quality` 通过：同局域网首帧 ≤ 2s，挂断后 conversation 出现 `[视频通话]` 消息；`./dev.sh shot video-call-active` 画面非空且按钮无遮挡。 | `docs/architecture/09-realtime-message-live-implementation.md` §6 |
| M5.6 | 第一视角直播：查验员创建 `live_session` 并发布后摄像头；协作页 `GET /v1/live-sessions?status=live` 展示在线视角；观看页订阅真实 video track；介入语音、标记预警、截图存档写 `live_annotations` 并推 WebSocket。 | `tests/harness/first_person_live_quality` 通过：viewer 收到非空视频帧，P95 延迟 ≤ 1500ms，标预警后 publisher 收到 `live.annotation`；截图 `.dev/screenshots/collaboration-live-list.png`、`.dev/screenshots/first-person-live.png` 通过。 | `docs/architecture/09-realtime-message-live-implementation.md` §7 |
| M5.7 | 直播录制与回放：服务端触发 LiveKit Egress；webhook complete 后把 MP4/HLS 登记到 asset，写 `live_recordings.status='complete'`，推 `recording.ready`；协作页“近期录像”拉真实数据并可播放。 | `tests/harness/live_recording_egress` 通过：10s 测试直播在结束后 30s 内产出可播放 MP4，asset sha256 与对象存储一致。 | `docs/architecture/09-realtime-message-live-implementation.md` §8 |
| M5.8 | 实时协作观测闭环：所有关键日志带 `room_id/live_session_id/conversation_id/client_msg_id/server_seq/media_rtt_ms/packet_loss_pct/first_frame_ms`；harness `analyze.py` 输出正常 / 警告 / 异常 + 原因；更新 registry 中新增 Android 模块和服务依赖。 | `tests/harness/ws_message_order`、`realtime_message_sync`、`livekit_room_lifecycle`、`first_person_live_quality`、`live_recording_egress` 全部输出可判定结论；`docs/architecture/registry/modules.yaml` / `dependencies.yaml` / server registry 与实际模块一致。 | `docs/architecture/09-realtime-message-live-implementation.md` §9 |

## M1 iHawk 接入

> 单设备路线：iHawk 自身 Color + Depth 双流，不接手机主摄。
> docs: `docs/architecture/01-depth-camera-integration.md`

| ID | 任务 | 验收 | 文档 |
|----|------|------|------|
| M1.2 | 暴露 iHawk Color/Depth 帧流：reader 线程把 `Frame.data` 拷成 `DirectByteBuffer`，Color YUYV 转 BGR888，按 `frameIndex` 配成 `RgbdFramePair` 并发到 `SharedFlow` 或 `Channel`。 | 真机连续 60s 输出 Color+Depth pair；pair 保留 frameIndex、timestamp、width、height、format；日志中 fps 稳定且无明显 GC 抖动。 | `docs/architecture/01-depth-camera-integration.md` §3.3 |
| M1.3 | 读取 iHawk 内参与注册参数：接 `getCameraIntriscParams` / `getDeviceIntriscParams`，打开并实测 `setRegistrationEnable`。 | `./dev.sh harness calibration_smoke` 通过；棋盘格 30/50/100cm 三组，深度投 color 边缘误差 ≤ 2 px。 | `tests/harness/calibration_smoke/` |
| M1.4 | Compose Color/Depth 实时预览：Color 贴 Surface，Depth 伪彩 Bitmap，HUD 显示 frameIndex / fps / timestamp / sync delta。 | `./dev.sh run` 后 `./dev.sh shot scan3d-preview`；截图中 Color、Depth、HUD 都可读且无遮挡。 | `feature/scan3d/Scan3dScreen.kt` |

## M2 iHawk 标定

> 触发条件：M1.3 的 SDK 出厂参数或 `setRegistrationEnable` 实测不达标。
> docs: `docs/architecture/05-calibration-pipeline.md`

| ID | 任务 | 验收 | 文档 |
|----|------|------|------|
| M2.1 | calibration native 模块接 OpenCV 4.x，使用 `cv::aruco` / `cv::calibrateCamera` / `cv::stereoCalibrate`；同时决定复用 SDK `libopencv_java3.so` 还是自编 OpenCV 4。 | native host build 通过；Charuco 单图检测能输出角点、id、reprojection 输入数据。 | `docs/architecture/05-calibration-pipeline.md` §3.3 |
| M2.2 | 新建 `feature:calibration`，实现 Charuco 检测向导 UI，按 12 个角度采集 Color/Depth pair。 | 真机采集 12 组样本落 `.dev/calibration/<sessionId>/`；截图覆盖采集页与结果页。 | `docs/architecture/05-calibration-pipeline.md` §3.2 |
| M2.3 | 求解单目内参与 Color/Depth 外参，输出可序列化标定结果。 | Color reprojection ≤ 1.0 px；Depth reprojection ≤ 0.5 mm；失败样本能给出可解释原因。 | `docs/architecture/05-calibration-pipeline.md` |
| M2.4 | 本地 Room 保存 `calibrations`，以 `deviceSerial` 唯一索引复用；接服务端 device calibration 同步。 | 扫描启动前能比对本地 sha256 与服务端 latest；一致跳过，不一致拉取并更新本地。 | `core:database` / `docs/architecture/05-calibration-pipeline.md` |
| M2.5 | 建 `calibration_quality` harness，对比 SDK 出厂参数与自标定结果。 | `./dev.sh harness calibration_quality` 输出“正常 / 警告 / 异常 + 原因”。 | `tests/harness/calibration_quality/` |

## M3 多视角 RGBD 重建

> 当前权威路线：多视角 RGBD 配准 + 端云融合。
> docs: `docs/architecture/04b-multiview-rgbd-reconstruction.md`
>
> 历史 native ICP / scan session / Filament 预览沉淀只作为阶段 3 复用背景；旧 TSDF 实时主线不再扩展。

| ID | 任务 | 验收 | 文档 |
|----|------|------|------|
| M3.12 | 端侧采集模式 UI：引导用户环绕采 8-12 角度；每次拍照保存 RGB、depth、内参、时间戳；显示上一张 RGB 半透明叠加；端侧 ORB overlap < 30% 时提示重拍；中心连通块 ROI 作为初步分割兜底。 | `.dev/scans/<sessionId>/` 内至少 8 组 RGBD pair 完整；每组 RGB/depth timestamp 差 ≤ 50ms；`.dev/screenshots/scan-multiview/` 截图通过。 | `docs/architecture/04b-multiview-rgbd-reconstruction.md` §3.1 |
| M3.13 | 接 asset multipart upload：复用 M-S2.3 分片上传；保存 multiview session 元数据，可放 `inspections.scan_multiview_payload` 或独立 scan 表。 | curl 上传 8 组 RGBD pair 到 MinIO 成功；DB 记录 sessionId、frameCount、每帧 sha256。 | `docs/architecture/04b-multiview-rgbd-reconstruction.md` §3.2 / `docs/architecture/server/02-api-contract.md` |
| M3.14 | cv-engine 加 `object_3d_fusion` worker：NATS 订阅 `scan.captured`，拉帧后跑 Open3D multiway registration、Color-ICP、全局 PGO、Marching Cubes、纹理烘焙，输出 GLB。 | `scan_fusion` harness：Open3D demo 子集 chamfer ≤ 5mm；真实 8 张 RGBD 端到端 1-2 分钟输出 ≥ 50K 顶点 mesh。 | `docs/architecture/04b-multiview-rgbd-reconstruction.md` §3.3 |
| M3.15 | 端侧 gallery 拉取 GLB 回看：订阅或轮询 `scan.fusion_done`，下载 GLB，用 Filament PBR + IBL 渲染，并支持旋转、缩放、平移。 | 1080p 设备回看流畅；`./dev.sh shot gallery-glb` 截图中模型非空且手势可用。 | `docs/architecture/04b-multiview-rgbd-reconstruction.md` §3.4 |
| M3.16 | 建 `scan_multiview_quality` harness：合成 Stanford Bunny 8 角度 RGBD + 真实卡车数据，跑端到端质量评估。 | `./dev.sh harness scan_multiview_quality` 通过；输出 mesh chamfer、点云覆盖度、UV atlas 利用率，UV 利用率 ≥ 70%。 | `tests/harness/scan_multiview_quality/` |
| M3.17 | cv-engine 接 SAM-HD 或 SAM2 服务端分割：用户 prompt + RGB 生成 2D mask，再借 iHawk 像素对齐投到 depth。 | SAM mask 与人工标注 IoU ≥ 0.92；与 M3.14 启发式 ROI 做 A/B，mesh 边缘毛刺下降。 | `docs/architecture/04b-multiview-rgbd-reconstruction.md` §4 |
| M3.18 | GPU worker 部署：cv-engine 容器接 GPU runtime，支持模型卸载与任务排队。 | 单 worker 处理 8 张 RGBD 的 SAM 推理 ≤ 30s；队列满载时状态可观测。 | `docs/architecture/04b-multiview-rgbd-reconstruction.md` §4 |
| M3.19 | 端侧 SAM2 Mobile：模型进 APK，适配 NPU/GPU 后端，首帧 prompt 后做 mask propagation。 | LOG-AN10 上 SAM 推理稳定 ≥ 5 fps；失败时给出设备能力原因。 | `docs/architecture/04b-multiview-rgbd-reconstruction.md` §5 |
| M3.20 | 端侧实时 ICP/TSDF + Filament mesh 预览：只融合 SAM mask 内 voxel，HUD 显示覆盖度与缺漏方向，云端高精度版完成后替换。 | 端侧实时 mesh 增长 ≥ 5 fps；扫描完成 ≤ 1s 出实时版；云端版完成后自动替换。 | `docs/architecture/04b-multiview-rgbd-reconstruction.md` §5 |

## M4 VIN 数码拓印

> 单帧 RGBD → ROI 平面拟合 → 固定法向距离正射重投影 → 1024×512 拓印图 → cv-engine OCR。
> docs: `docs/architecture/08-vin-rectify-design.md`

| ID | 任务 | 验收 | 文档 |
|----|------|------|------|
| M4.1 | vin native 模块：RANSAC 平面拟合、正射相机参数化、像素重投影、双线性 Color 采样、PNG 编码。 | native host test 覆盖平面拟合、ROI 越界、空深度、PNG 输出尺寸 1024×512。 | `docs/architecture/08-vin-rectify-design.md` §2 |
| M4.2 | `feature:scan3d` 加“VIN 数码拓印”入口：Color 实时预览、ROI 选框拖拽、拍照、拓印结果页。 | `./dev.sh run` + `./dev.sh shot vin-rectify`；ROI 拖拽命中区与结果页无遮挡。 | `feature/scan3d/Scan3dScreen.kt` |
| M4.3 | 拓印图本地落盘并推服务端 cv-engine `vin_pipeline`。 | 上传后能拿到 verdict、reasons、字符结果；网络失败保留本地重试记录。 | `core:network` / `docs/architecture/server/03-cvengine-migration.md` |
| M4.4 | 建 `vin_rectify_quality` harness：录制真实 VIN 钢架 RGBD pair，跑 `vinRectify` 与服务端 OCR。 | `./dev.sh harness vin_rectify_quality` 通过；多角度拓印 SSIM ≥ 0.9，OCR 准确率 ≥ 95%。 | `tests/harness/vin_rectify_quality/` |

## 服务端与治理待办

| ID | 任务 | 验收 | 文档 |
|----|------|------|------|
| S1 | App 端接 device calibration 同步：扫描启动前拉 `GET /v1/devices/{id}/calibrations/latest`，与本地 Room sha256 比对。 | sha256 一致时不下载；不一致时拉完整 params 并更新；离线时使用本地最近可用版本并标明版本。 | `docs/architecture/05-calibration-pipeline.md` |
| S2 | shape compare 从元数据级升级到几何级：解析真实 mesh，补 chamfer / Hausdorff / scale consistency。 | 扩展 `cv_shape_compare` harness；真实 mesh 对比能输出几何指标与三态 verdict。 | `docs/architecture/06-product-features.md` §3.4 |
| S3 | 生成并接入 cv-engine gRPC server。 | 安装 protoc 后跑 `server/scripts/proto-gen.sh`；gRPC 端点与 `proto/cvengine.proto` 契约一致，保留 HTTP harness。 | `server/proto/cvengine.proto` |
| S4 | 更新 `docs/architecture/registry/` 机器真理源。 | 任何模块边界、依赖或能力成熟度变化后，同步更新 `modules.yaml`、`dependencies.yaml`、`capabilities.yaml` 并通过校验。 | `docs/architecture/registry/` |
