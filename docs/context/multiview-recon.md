# 多视角 RGBD 重建与深度质量 — 历史上下文

> 最后更新: 2026-07-28 | 截至 commit: 工作区 | 维护规则见 AGENTS.md「历史上下文维护」节

## 使命与当前状态

把手持 P100R3 环绕目标拍 8-12 角度 RGBD, 经"端侧采集 + 云端 Open3D multiway_registration + Color-ICP + 全局 PGO + TSDF + 纹理烘焙"融合成 mm 级 GLB mesh; 配套一整层深度后处理 (时域降噪 / 真置信 / 空间降噪 / 飞点剔除 / conf 加权) 把 P100R3 density-first 稠密深度从 ~38mm 抖动拉回标称精度。这是 2026-05-07 pivot 后的重建主线, 权威设计 `docs/architecture/04b-multiview-rgbd-reconstruction.md`。

当前阶段: **软件链路全通、真机端到端未闭环**。服务端 M3.14-M3.17 (融合算法核 + Go worker + 纹理烘焙 + ws 推送桥 + HQ-SAM 分割) 全落且 harness 全绿 (合成 chamfer ~1.3-1.8mm); 端侧 M7 已把外廓采集屏接真底座 (8 角度采集 → bundle 上传 → fusion_done → GLB 回看); 深度后处理三件套 + conf 加权在 portable/native 层已落并经真相机验证。**没走通的**: 真实 8-12 张 P100R3 RGBD 端到端融合 (卡物理多视角采集 + 真机门控)、M3.18-M3.20 (GPU 部署 / 端侧 SAM / 实时预览) 未开工、M2 标定休眠; 且 07-10 起 Berxel 采集屏 UI 入口暂隐藏 (代码保留, 见时间线末条)。2026-06 起团队焦点转向激光工位 (车辆外廓量测业务实际由 laser-station 模块的固定双机位承接, 见 `docs/context/laser-station.md`), 本线处于冻结待真机状态。

## 决策时间线

### 2026-05-06 前提: 砍手机主摄路线 (方向调整)
初始设想是"深度相机 + 手机主摄深度绑定"。05-06 拍板砍掉主摄, 落定 iHawk 单设备 + 三维外廓 + VIN 三主线。本模块所有 RGB 均来自 P100R3 自身 Color 流 (后来 eYs3D 线另有 HLSD8, 属兄弟模块)。证据: commit 3031de2; `docs/architecture/05-calibration-pipeline.md` 头注。

### 2026-05-07 实时 SLAM + 端侧 TSDF 一日攻坚 (M3.1-M3.6)
按老路线一天落齐 native 地基 (ICP + TSDF + Marching Tetrahedra, d10bedc)、RecordingScreen 实时点云预览 (4da21d1/2611f50)、`scan_quality` harness (93f177d)。同日修掉"真机点云 0"根因 = depth 像素 12.4 定点未转 mm (2461878, 属 berxel 模块交叉)。这批沉淀当天即被 pivot 降级为"阶段 3 复用背景", **但代码保留在 `native/reconstruction/`, 不要删**。

### 2026-05-07 ★主线 pivot: 弃实时 SLAM → 多视角 RGBD + 端云融合 (M3)
背景: 用户问"能扫卡车吗", 推导出实时路线三死穴 — 目标/背景分离要端侧 SAM、大体积装不下密集 TSDF、纯贪心 ICP 累积漂移把精度天花板压在 cm 级 (且 ARCore 国产机覆盖 ~50%)。选择: 端拍 8-12 角度 + 云端 Open3D multiway_registration + 全局 PGO — pose 来自点云本身不依赖手机、PGO 天然消漂移可达 mm 级、算法主战场在云端工程量小一个量级、失败单张可重拍。同时明确**端云组合**而非纯云 (阶段 3 端侧实时预览补体验) 亦非纯端 (精度天花板不允许)。三阶段: ①端拍+云融合 ②SAM-HD 替启发式 ③端侧 SAM2+实时预览。证据: `docs/agent-memory/finding_multiview_rgbd_pivot_2026-05-07.md`; 04b 全文; 05-08 首批落码 2322909。

### 2026-05-27 P100R3 出厂参数 = 156B 离线 blob, 无 USB 读 (M1.6.7 / M2 前提)
深入反编译证 `getDeviceIntriscParams` 是单条 memcpy from cache, 初始化时从本地 6MB `<SN>_params.bin` blob 一次性切片, **不存在 on-device XU read**。此前"抓 USB trace 反编译读参协议"假设被证伪。唯一 path = adb pull blob + 定位 156B offset (至今未完成)。影响: 重建/飞点角度上界的 fx/fy 目前用 FOV 反推 (440.4/424.1) 顶着, M2 自标定休眠 (触发条件 = 出厂参数实测不达标, 从未实测触发)。证据: `/root/.claude/projects/-root-lilw-gomob/memory/finding_p100r3_device_params_offline_only_2026-05-27.md`; TODO M1.6.7 / M2; `docs/architecture/05-calibration-pipeline.md` §1 决策门。

### 2026-05-29 深度后处理三件套 (M1.6.15-M1.6.17)
前提: 路线 A 确认 companion 直出真 metric 深度 (`docs/agent-memory/finding_p100r3_depth_ir_interleaved_2026-05-29.md`, 属 berxel 模块), 但拿稠密 (valid≈1.0) 必须关设备降噪, 代价逐像素抖动 ~38mm。三件套全落 portable 层 (`native/berxel/portable/gomob_berxel_portable.{h,cpp}`, host+Android 共用):
1. **时域降噪** `P100R3TemporalFilter`: 有界滑窗均值 N=8。当时认知"固定运动门限 60mm"→ 真相机验证证伪 (噪声底随场景 38~68mm 变, 噪声>门限时每帧 reset 退化成透传的 EMA 陷阱) → **终态: 门限自适应 = max(45mm, 2.0×噪声底 EMA 估计, percent×深度)**, 三场景统一 ~4.1× (live 64.5→15.75mm), 零偏移不掉密度。证据: `docs/agent-memory/finding_depth_temporal_denoise_2026-05-29.md`。
2. **真置信 + 空间降噪**: 用户报"满屏噪点", 三判据坐实是真深度但 ~74% 像素时域不稳; 根因 = 密度优先关设备降噪 + **设备 confidence 是饱和废值 (98.7% 恒 255)**。解 = window_span 派生稳定性置信替换废值 (数据保稠密, 下游按 conf 掩码/加权) + median3→bilateral5 空间降噪 (noise_p50 27→10.5mm, 保边)。"空间降噪单独救得了"被证伪 (speckle 是成片不可靠区非孤立点)。证据: `docs/agent-memory/finding_depth_noise_real_confidence_2026-05-29.md`。
3. **飞点剔除**: 当时首版纯单帧空间检测 → grounding 证过杀 24% → **终态: 三证合一** (时域不稳 ∧ 双侧物理坡度上界夹心 ∧ 无共面支撑), 放 fuse 之后; 命中保 raw 原值 + conf 置 0 (删点由下游跳过, 不造假)。真硬件 removal 0.05%、检测像素局部梯度 33× 于全图。实现期还证伪了 `recent_reset_age` 降级判据 (慢性飞点永远走降级) → 改 `stable_run + frames_seen`。finding 里记的「confidence 在 JNI 算了但从不 poll 出去」断链已在同一里程碑 M1.6.16 内闭合: `berxelDualPollDepthConf` poll 逐像素 conf → Kotlin `dualPollDepthConf` → `DepthFrame.confidence` (落码 commit 7b09152, 2026-05-30 提交), 后经 M6.8b 统一为厂商无关 `cameraPollDepthConf` (c32ea7d, Kotlin 包装沿用); M1.6.20 再把 conf 接进重建 (见下)。证据: `docs/agent-memory/finding_depth_flying_pixel_removal_2026-05-29.md`; TODO M1.6.16「confidence 通道打通」段。

### 2026-05-30 IR 引导精修负面结论 + conf 加权端侧落地 (M1.6.19 / M1.6.20)
- **IR 引导深度精修 = 死路**: 用户第一性质疑"交织 IR 占 40% 带宽应能增强深度"。反汇编证 SDK 内 `EdgeEnhanceInfraRed` 是零调用者死 API; 离线原型证 0x0500 帧是**结构光散斑帧**, Canny 检到散斑非物体边界 (边界 F1 仅 0.25)。**IR 唯一价值 = 散斑局部对比度单帧置信 (AUC 0.72-0.82)**, 与时域置信取小融合。证据: TODO M1.6.19; harness `tests/harness/depth_ir_guided/`; commit 7b09152/bf9229d。
- **conf 加权进端侧 native 重建**: `TsdfVolume::Integrate` 软加权、`IcpRegister` 加权 Kabsch、mesh 门语义改累计置信; 合成 45% 弱回波表面 RMS 14.3→0.81mm (降 94%), 真硬件 chamfer 降 42%。结论定调: **density-first + 置信加权 > 稀疏干净**。证据: TODO M1.6.20; `tests/harness/scan_conf_weighting/`; 04b §5.5。

### 2026-05-31 云端融合垂直切片五连 (M3.14-M3.17)
一天落齐服务端主体 (cb14116→f69410f):
- **M3.14 算法核 + worker + 纹理**: `server/fusion_service/fusion_core.py` — per-view 点云 → 全连接 multiway (line process 剪坏边) → PGO → conf 阈值预掩码 (Open3D 无 per-point 权重的可行等价) → ScalableTSDFVolume → MC; Go `fusionworker` 垂直切片 (bundle 上传自动入队 → /fuse → GLB 存 MinIO → `scan.fusion_done`); UV-atlas 多视角回投影烘焙。两处证伪: **纯顺序位姿图脆弱** (一处误配翻 141° 崩全局) → 改全连接; **补底面加仰角振荡** 反致底簇翻转 → 回退单环轨道 + 改"观测面" chamfer。harness `scan_fusion` 两门过 (干净 1.25-1.37mm; 带噪 conf 加权 ~1.6mm vs 不加权 ~35mm, 降 96%)。
- **M3.15 实时推送桥**: `signaling.FusionBridge` 按 owner_user_id 推 ws 帧 `scan.fusion_done`; NATS 不可用只降级关推送。
- **M3.16 质量 harness 暴露并修配准脆弱性**: 固定 FPFH 尺度对复杂有机体过粗 → 改 `reg_voxel=min(voxel,12mm)` 派生 (Bunny 7.5→1.76mm 稳定); **UV 利用率 ≥70% 硬门实测不可达** (iso-charts/xatlas 在 MC 有机网格都只 ~62-70%, 几何属性非工具问题) → 改软报告, 硬门取 chamfer+覆盖度 (用户确认)。
- **M3.17 HQ-SAM 分割**: 选 HQ-SAM vit_h (Apache-2.0 可商用), 框提示人工给 (开放词表 grounding 模型许可不商用, 不引); mask 作与 conf 平行的深度预掩码通道随 bundle 走 (fusion 不回调 sam_service); 与启发式 ROI A/B 毛刺降 93-95% (≥80% 门过)。harness 期两大方法论沉淀: **周期正弦纹理致 FPFH/Color-ICP 特征混叠 → ~20% 位姿翻转** (换非周期渐变色 10/10 稳, `docs/agent-memory/finding_periodic_texture_registration_aliasing_2026-05-31.md`); **受控对比必须固定位姿重积分**, 不能各分支各自跑 RANSAC (非确定翻转污染被测变量)。
证据: TODO M3.14-M3.17 (超详细); 04b §5.1/§5.2/§5.5。

### 2026-06-02 旁线: RGBD 实时流 → gorob 边缘 (机器人视觉源)
把 gomob+Berxel 当机器人眼睛: 手写 protobuf 的 `RgbdStreamClient` 经 WS 二进制推 RGBD 给 gorob 边缘做权威 TSDF (契约 = gorob `rgbd.proto`, 位姿起步单位阵全靠 gorob ICP, ARCore 仅作可选先验)。**代码已写、未真机实测**, UI 开关未接线。证据: commit 8eca9fc; `docs/architecture/14-rgbd-stream-gorob.md`。

### 2026-06-02~03 M7 端侧拉通: mock 屏接真底座
底座与服务端早已全真, 缺口在端侧中段 (入口卡片指向硬编码 mock 屏、真管线是孤儿路由)。M7.1-M7.6 落齐: `Scan3dBundleUploader` 逐字段复刻 `rgbd_bundle.py` zip 契约 (uint16 小端 mm + conf u8)、`VehicleContourScanViewModel` 8 角度真采集 → 上传 → 等 fusion_done → `GlbModelView` (filament gltfio) 回看; 服务端补 `/v1/scans/{session_key}/result` 流式中转 (GLB 不在 asset 体系且手机连不到 MinIO 内网; owner==nil 必须拒绝)。跨语言契约由 harness `scan_bundle_roundtrip` 守门; 5 维度对抗 review 19 条已修 (EnvelopeErrorInterceptor 全缓冲会 OOM 流式 GLB 等雷点在案)。**遗留**: RGB↔depth 是 approx resize 对齐 (texture 受基线视差偏移, 终态 M2 registration)、真机门控。证据: commit d2ea3e3; `docs/agent-memory/finding_scan_vin_wiring_2026-06-02.md`; TODO M7。

### 2026-06-04 之后: 冻结待真机, 业务重心转移
06 月起焦点转向激光工位 (M8'/M9/M13, 固定双机位承接车辆外廓量测业务) 与 VIN 服务端化; 本线无新功能 commit, 仅横切维护批次触及本线文件 (e9bcbd2 全量审查整改、03565f5 tsdf widen、07fdf97 M14 UI 焕新)。07-01 App 端外廓屏被重做为激光工位纯操作端, 右上角设备下拉「3D 工位 / 3D 相机」; **07-10 起「3D 相机」入口暂隐藏** — `VehicleContourScanRoute` 的 mode 硬编码 Laser、下拉改纯工位选择 StationSwitcher, `BerxelScanScreen` 8 角度采集链路代码原样保留但 **UI 不可达**, 恢复 = trailing 换回 `DeviceSwitcher`（上述两轮改动已随 a979415 落盘; 详见 `docs/context/laser-station.md` / `docs/context/app-ui-designsystem.md`）。多视角线现状即上述"软件全通、真机端到端未闭环"。

## 禁区与已证伪路线

- **不要扩展 `04-reconstruction-pipeline.md` 实时 SLAM 路线**; 新工作一律落 04b。证据: 04 文档头弃用声明 + pivot finding。
- **不要给 native 加稀疏 TSDF/VoxelHashing、PoseSource、手机主摄↔P100R3 双摄外参、ARCore/AREngine pose 源** — 全被多视角配准消化, 做了浪费 (gorob 推流线的 ARCore 可选先验是唯一例外, 且服务于 gorob ICP 初值)。证据: pivot finding「触发情景」节。
- **不要走"完全端侧无后端"方向** — 精度天花板 + 失败不可逆 + 高精度定位三重否决; 端云组合是终态。证据: pivot finding。
- **不要把设备 confidence 当真置信** — 98.7% 饱和废值, 任何阈值掩码无效; 用 window_span 稳定性置信 ∧ IR 散斑单帧置信。证据: finding_depth_noise_real_confidence。
- **不要硬编码时域滤波运动门限** — 固定门限在高噪声场景退化成每帧 reset 透传 (EMA 陷阱); 必须自适应噪声底。证据: finding_depth_temporal_denoise ★节。
- **不要用纯单帧空间检测删飞点** — 过杀 24% 真斜面/边缘; 必须三证合一且放 fuse 后; 命中不改 raw 只清 conf。证据: finding_depth_flying_pixel_removal。
- **不要再试 IR 边缘引导深度精修** — 0x0500 是散斑帧非影像, SDK 内同名 API 是死代码, 边界 F1 0.25; IR 只作单帧置信。证据: TODO M1.6.19。
- **不要再找 P100R3 出厂参数的 USB 读取协议** — 反编译证纯离线 blob memcpy, ltrace/真机 trace 是死路; 唯一 path = adb pull blob 定位 156B offset。证据: auto-memory finding_p100r3_device_params_offline_only。
- **合成配准/重建 harness 不要用周期纹理配色** — FPFH/颜色特征混叠致 ~20% 位姿随机翻转; 用非周期单调渐变。真实周期纹理物体 (铆钉阵/栅栏) 的生产鲁棒性是未了 TODO。证据: finding_periodic_texture_registration_aliasing。
- **受控对比不要让各分支各自跑配准** — Open3D RANSAC 非确定, 翻转噪声污染被测变量; 用一组干净位姿重积分。证据: 同上 finding + M3.17 A/B 记录。
- **飞点合成 harness 必须关 spatial_denoise** — median 会提前吞掉合成飞点, 指标失真。证据: finding_depth_noise_real_confidence。
- **不要把 UV 利用率 ≥70% 当硬门** — MC 有机网格几何上不可达, 软报告即可。证据: TODO M3.16 订正。
- **不要用纯顺序位姿图 / Open3D 0.19 自带 GLB 导出** — 前者一处误配崩全局 (用全连接 multiway + line process); 后者写出损坏文件 (用 trimesh)。证据: TODO M3.14 踩坑订正。
- **不要删 `native/reconstruction/` 旧 ICP/TSDF/MC 沉淀** — 是阶段 3 (M3.20) 端侧实时预览的输入。证据: pivot finding「已弃但保留沉淀」。

## 关键资产指针

- `docs/architecture/04b-multiview-rgbd-reconstruction.md` — 当前权威终态设计 (§5.5 conf 加权贯穿表是各阶段状态账本; 注意其云端行"纹理/GLB 导出待"未同步 — 纹理烘焙已由 M3.14 第三增量落地且 `scan_fusion_texture` harness 过, 以 TODO M3.14 为准)。
- `docs/architecture/04-reconstruction-pipeline.md` — 已弃实时 SLAM 路线, 仅历史追溯。
- `docs/architecture/05-calibration-pipeline.md` — M2 标定管线设计 + §1 "先实测出厂参数再决定自标定"决策门 (休眠)。
- `docs/architecture/14-rgbd-stream-gorob.md` — gorob 边缘推流旁线 (代码在 `feature/scan3d/.../stream/`, 未实测)。
- `server/fusion_service/{fusion_core,app,rgbd_bundle}.py` — 云端融合算法核 / HTTP / bundle 契约真理源。
- `server/sam_service/` — HQ-SAM 分割服务 (权重 2.5GB 不进 git)。
- `server/internal/fusion/` + `server/cmd/fusionworker/` + `server/internal/signaling/fusion_bridge.go` — Go worker 与 ws 推送桥。
- `core/data/src/main/kotlin/io/gomob/data/scan/Scan3dBundleUploader.kt` — 端侧 bundle 打包上传 (契约逐字段复刻 rgbd_bundle.py)。
- `feature/scan3d/.../VehicleContourScanViewModel.kt` / `VehicleContourScanScreen.kt` / `GlbModelView.kt` — 8 角度采集 + GLB 回看 (07-10 起 UI 入口暂隐藏, 见时间线末条)。
- `native/berxel/portable/gomob_berxel_portable.{h,cpp}` — P100R3TemporalFilter + 飞点 + 空间降噪 + 稳定性置信 (host/Android 共用)。
- `native/reconstruction/` — 旧端侧 ICP/spatial_hash/TSDF/MC (阶段 3 复用, conf 加权已进 Integrate/IcpRegister)。
- harness: `tests/harness/scan_fusion{,_e2e,_texture}/`、`scan_multiview_quality/`、`scan_mask_fusion/`、`scan_conf_weighting/`、`scan_bundle_roundtrip/`、`depth_temporal_quality/`、`depth_flying_pixel/`、`depth_ir_guided/`; native host 测试 `tests/native_host/` (temporal/flying/conf_weight); `scripts/berxel-host-test.sh` / `berxel-host-probe.sh` (服务器直连相机 live 采集)。
- agent-memory: `finding_multiview_rgbd_pivot_2026-05-07.md`、`finding_depth_temporal_denoise_2026-05-29.md`、`finding_depth_noise_real_confidence_2026-05-29.md`、`finding_depth_flying_pixel_removal_2026-05-29.md`、`finding_periodic_texture_registration_aliasing_2026-05-31.md`、`finding_scan_vin_wiring_2026-06-02.md`。

## 未竟事项

- **M3.14② 真实端到端**: 真实 8-12 张 P100R3 RGBD → 云融合 1-2 分钟出 ≥50K 顶点 mesh — 卡物理多视角采集 + 真机门控 (M7.7, 受 M1.6/M6.8b device-gating; 2510DRK44C 可跑)。这是全线最大的"真实化"缺口。
- **M3.12 采集体验完整版**: 上一张 RGB 半透明叠加、ORB overlap<30% 重拍提示 (基本 8 角度采集已由 M7.3/7.4 承接)。
- **M3.15 端侧回看真机验证**: gallery 订阅 `scan.fusion_done` ws 帧 + Filament 渲染手势 (服务端桥已落, GlbModelView 已有, 真机视觉未验)。
- **M3.16 真实卡车数据集**: `GOMOB_TRUCK_DATASET` 指向 bundle 即跑, 未就绪诚实 skip。
- **M3.17 后续**: 真实图像分割基准、SAM mask 软权重边界 (04b §5.5 ⬜)、端侧 MobileSAM/SAM2 轻量化。
- **M3.18-M3.20 阶段 2/3 基建**: GPU worker 部署、端侧 SAM2 Mobile (≥5fps)、端侧实时 ICP/TSDF mesh 预览 (云端版完成后自动替换)。
- **M7.7 质量门**: RGB↔Depth 真配准代码已落地（VIN BIN 自包含 bundle + 服务端投影），剩余真实棋盘格 ≤2px、BF301208 多视角 GLB 与真机门控；不再回退 approx resize。
- **「3D 相机」UI 入口**: VIN bundle 主线已恢复 `VehicleContourScanRoute` 的双相机采集入口；Laser 工位仍保留为独立模式。
- **M2 全线休眠**: 触发条件 (出厂参数实测不达标) 尚未实测; 前置是 M1.6.7 出厂参数 blob 156B offset 定位; 飞点角度上界的 FOV 反推 fx/fy 待真内参重验。
- **深度后处理收尾**: Android 真机 PollDepthMm live 路径视觉确认 (portable 同源已硬件验证, M1.6.15); 噪声数字待静态采集复核 (旧采集疑含手持运动, 26%/85mm 可能偏悲观)。
- **配准鲁棒性单列项**: 真实周期纹理物体的对应过滤 (mutual/ratio-test)、宽基线闭环边角度限制、RANSAC 确定化 — finding 里点名、未进 TODO.md。
- **gorob 推流旁线**: UI 开关接线 + 真机 + gorob 边缘联调 (`14-rgbd-stream-gorob.md`「还需接的线」)。

### 2026-07-28 VIN 原始 RGBD bundle 收口

旧的端侧 RGB resize 方案已删除。`VehicleContourScanViewModel` 固定绑定 `vinDepth()`（RS-D550）和 `vinRgb()`（HLSD8），从共享目录按 Depth serial 读取并校验 VINCreator v3 BIN；一次会话锁定两路设备 ID、`640x128_mode25` / `4160x832` profile 与 calibration SHA，断开或 profile 变化即废弃。`Scan3dBundleUploader` 把源文件字节固定写入 zip 根 entry `calibration.bin`，原始彩色图、disparity×8 u16、confidence、时间戳随帧写入 manifest schema v2。

服务端 `rgbd_bundle.unpack` 只接受新契约并 fail-closed 校验 zip entry、manifest、BIN SHA/serial/version/profile、尺寸与同步门；`vin_calibration.py` 复刻 Go restore oracle 的深度公式、坐标轴、Euler→R、R/T、FOV 与私有畸变，双线性采样到深度网格后再交给 Open3D。该决策消除了服务端预置每台 BIN 的依赖；验收剩余真实棋盘格 ≤2px 和真实 BF301208 多视角 bundle 生成非空 GLB。
