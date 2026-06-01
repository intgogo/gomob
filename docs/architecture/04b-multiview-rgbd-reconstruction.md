# 04b — 多视角 RGBD 重建管线（**当前主线**，2026-05-07 起）

> **本文档是当前权威终态设计**。`04-reconstruction-pipeline.md` 描述的是 2026-05-07 之前的"实时
> SLAM 单流连续录制 + 端侧 TSDF"路线，已弃；保留作历史路径追溯，不要再扩展那条路。
>
> 决策记忆：[../agent-memory/finding_multiview_rgbd_pivot_2026-05-07.md](../agent-memory/finding_multiview_rgbd_pivot_2026-05-07.md)

## 1. 业务定义

用户**环绕目标物**采集 8–12 个角度的 RGBD 帧，App 把这些独立角度的点云通过**多视角配准 +
全局 Pose Graph Optimization**融合成完整 mesh，输出 GLB（PBR + 纹理）。

适用目标：从桌面工件（10cm）到大件物体（卡车 6m+，整间客厅）。
不适用：不停运动的物体、户外强阳光下的目标（940nm 结构光被淹没）、纯透明 / 镜面反射体（深度无效）。

## 2. 为什么不走"实时 SLAM"路线

详细取舍见决策记忆。一句话：

- **实时 SLAM**（KinectFusion 风格）依赖 ARCore/IMU 给全局 pose，国产手机 50% 不在 ARCore 名单 → 出货后用户机型不可控；ICP 累积漂移天然限制精度天花板到 cm 级；做不到 mm 级"高精度"。
- **多视角 RGBD 配准 + 离线 PGO** 不依赖任何手机 pose 源，pose 来自点云本身（FPFH+RANSAC+Color-ICP）；全局优化天然消除累积误差，loop closure 免费；mm 级精度可达。

代价是用户体验从"实时反馈"变成"拍 8 张 → 等 1–2 分钟出结果"。阶段 3（端侧 SAM2 Mobile +
实时拼接预览）补回这个体验差。

## 3. 输入 / 输出契约

### 3.1 输入（端侧采集）

每次扫描会话产出 N=8–12 个 `RgbdShot`：

```kotlin
data class RgbdShot(
    val sessionId: String,         // ULID
    val shotIndex: Int,            // 0..N-1
    val timestampNs: Long,
    val rgb: ByteArray,            // MJPEG, 1920×1080，源自 P100R3 RGB stream
    val depth: ShortArray,         // mm 单位，1280×800，源自 P100R3 Depth stream（已与 RGB 像素对齐）
    val intrinsicsRgb: CameraIntrinsics,    // 来自 P100R3 SDK getCameraIntriscParams
    val intrinsicsDepth: CameraIntrinsics,
    val depthToRgbExtrinsics: StereoExtrinsics,  // 出厂参数；P100R3 硬件像素对齐时 ≈ identity
    val userPrompts: List<PromptPoint>?, // 阶段 2 起：用户在 RGB 上点的 prompt 点（SAM 输入）
    val ipOverlapToPrev: Float?,         // ORB 实时算的 overlap 比例（0.0–1.0），引导补拍
)
```

会话采集完成后打成 zip（含 N 个 shot + 全局元信息 manifest.json），通过 [asset multipart
upload](server/02-api-contract.md#5-asset-multipart) 推到云端 MinIO。

### 3.2 输出（云端融合）

```kotlin
data class ScanFusionResult(
    val sessionId: String,
    val mesh: MeshAsset,           // GLB 2.0，PBR base color + normal map
    val pointCloud: PointCloudAsset?,  // PLY，可选
    val stats: FusionStats,        // chamfer / 顶点数 / 面数 / 烘焙覆盖率 / pairwise 平均误差
    val poseTrajectory: List<Pose7>,  // N 个角度的优化后位姿
)
```

云端通过 NATS event `scan.fusion_done` 通知端侧；端侧 gallery 拉 GLB 渲染。

## 4. 管线总览

```
端侧采集（feature:scan3d 重写）
─────────────────────────────────────────────────────────────
  环绕目标 8-12 角度拍照
       ↓
  每帧抓 RGB + depth + 内参 + 时间戳
       ↓
  上一张 RGB 半透明叠 viewfinder + ORB 实时算 overlap
       ↓
  阶段 2 起：用户在 RGB 上点 prompt 点（SAM 提示）
       ↓
  本地 zip 打包 → asset multipart upload (M-S2.3)
                            │
                            ↓ NATS event "scan.captured"
─────────────────────────────────────────────────────────────
云端 worker: object_3d_fusion （新增，沿用 M-S5 worker 架构）
─────────────────────────────────────────────────────────────
  for each shot:
    阶段 1: 启发式 ROI（中心连通块 / 用户拉框）→ 物体点云
    阶段 2: SAM-HD + 用户 prompt → 高精度 2D mask → 物体点云
       ↓
  multiway_registration (Open3D):
    - pairwise: FPFH + RANSAC 粗对齐 → Color-ICP 精修 → 平均误差 < 5mm
    - pose graph: g2o / Ceres 全局优化 + loop closure (末视角 → 首视角)
       ↓
  TSDF integration (融合所有点云到统一 voxel 体)
       ↓
  Marching Cubes → mesh
       ↓
  纹理烘焙 (per-vertex visibility 选最佳 RGB 投影 + UV atlas pack)
       ↓
  导出 GLB / OBJ / PLY → MinIO + DB inspections.scan_assets
       ↓
                            │
                            ↓ NATS event "scan.fusion_done"(带 owner_user_id)
                            ↓ signaling.FusionBridge 按 owner 经 ws 推 type=scan.fusion_done 帧
─────────────────────────────────────────────────────────────
端侧 gallery 收 ws 帧(或轮询)拉 GLB → Filament PBR + IBL 渲染回看
─────────────────────────────────────────────────────────────
```

> M3.15 服务端实时推送桥(2026-05-31 已落):融合任务记 `owner_user_id`(上传发起者),
> `fusionworker` 完成后发的 `scan.fusion_done` 带该 owner;`signaling` 进程经
> `internal/signaling/fusion_bridge.go` 订阅该 topic,按 owner_user_id `hub.Push` 给在线 ws 连接。
> signaling 不耦合 fusion 包(只解出 owner 路由,payload 原样转发);NATS 不可用时降级关推送、
> 端侧可轮询兜底。端侧 Android/Filament gallery 订阅消费待 M3.15 端侧增量(需真机)。

## 5. 关键技术选型

### 5.1 多视角配准（Multiway Registration）

核心算法链 = **特征匹配 → 全局粗对齐 → 局部精修 → Pose Graph 全局优化**。

实现优先级（避免重复造轮子）：

| 层 | 选型 | 备注 |
|---|---|---|
| 特征 | **FPFH**（Fast Point Feature Histograms）| Open3D 内置；33 维特征对各向同性几何鲁棒 |
| 粗对齐 | **RANSAC** based on FPFH correspondence | Open3D `registration_ransac_based_on_feature_matching` |
| 局部精修 | **Color-ICP** ([Park et al. 2017](https://www.cs.cmu.edu/~kaess/pub/Park17iccv.pdf)) | Open3D `registration_colored_icp`；同时优化几何 + RGB 颜色梯度残差，对弱纹理白漆面有救 |
| 全局优化 | **Pose Graph Optimization** | Open3D `global_optimization` 包了 g2o；末视角→首视角 loop closure 必须给 |
| 融合 | **TSDF Integration** | Open3D `ScalableTSDFVolume`；voxel_size = 6mm，sdf_trunc = 24mm，与 P100R3 真实噪声底匹配 |
| 提取 | **Marching Cubes** | Open3D 内置；输出 `TriangleMesh` |
| 纹理烘焙 | **per-vertex visibility + UV atlas** | 自实现：对每个三角面选可见性最好的 RGB 帧投影采样；用 [xatlas](https://github.com/jpcy/xatlas) 做 UV unwrap |

**不自己写**这些算法。Open3D 在 cv-engine（Python）侧调即可。

**配准尺度跟 voxel 派生（M3.16，2026-05-31）**：FPFH 下采样体素与 RANSAC/Color-ICP/全局优化的
最大对应距离不能用固定值。`scan_multiview_quality`(Stanford Bunny 8 视角)暴露:旧固定 FPFH 12mm /
对应 30mm 对复杂有机体特征过粗、对应过松,宽基线视角对(近背对)误配且 fitness 假高(0.64),
全局优化 line process 拦不住 → 位姿翻转(chamfer 在 3 / 7.5mm 间随机跳)。改为
`reg_voxel = min(voxel_size, 12mm)`、对应距离 `= 2.5×reg_voxel`(`fusion_core.FusionConfig.reg_voxel_m/reg_corr_m`)后,
Bunny 8 视角 **7.5→1.76mm 且跨跑稳定**,box+sphere(M3.14)无回归。

**UV unwrap 用 Open3D iso-charts(`compute_uvatlas`),不引入 xatlas**:实测在 marching-cubes 有机网格上
iso-charts 与 xatlas(激进打包)UV 利用率都只 ~62–70%(小 chart 多、曲边界难密铺单位方),
原定"≥70%"不可达 → UV 利用率作**质量监测软报告**(非硬门);硬门取 chamfer + 覆盖度(扫描真实化本质)。
详见 `tests/harness/scan_multiview_quality/README.md`。

### 5.2 物体分割

阶段 1（启发式）→ 阶段 2（SAM-HD）→ 阶段 3（端侧 SAM2 Mobile）三步走。

| 阶段 | 方法 | 精度 | 延迟 |
|---|---|---|---|
| 1 | 中心连通块（继承现有 `BuildForegroundDepth`）+ 用户拉 ROI 框补救 | 边缘有 5–20 像素毛刺；目标贴墙 / 偏中心可能失败 | 端侧 < 10ms |
| 2 | 云端 SAM-HD（ViT-H 或 SAM2 全量）+ 用户每张点 1-2 prompt 点 | 边缘 1-3 像素，几乎人工标注水平 | 云端 GPU 单帧 ~500ms |
| 3 | 端侧 MobileSAM/SAM2 Mobile + mask propagation | 5-7fps 实时 | NPU/GPU ~50ms/帧 |

阶段 1 启发式分割是 MVP 兜底，**不为它做多余优化**——一旦阶段 2 SAM-HD 上线就被替代。

**M3.17 第一增量(2026-05-31)已落 —— 阶段 2 服务端 HQ-SAM**:`server/sam_service`(Python FastAPI + GPU,
形态对标 `fusion_service`)。模型选 **HQ-SAM(sam-hq,Apache-2.0 可商用)`vit_h`**:原版 SAM 加 High-Quality
输出 token + 融合早/末期 ViT 特征,边界更锐(细结构/薄边)。`/segment` 收「RGB + 框/点」→ 出 mask PNG。
**当前边界:框由人工给**(不做自动 grounding —— NVIDIA LocateAnything 等开放词表 grounding 模型可当"自动出框"
前端,但 LocateAnything 非商用许可对本产品是硬约束,暂不引入;Grounded-SAM/Semantic-SAM 留候选)。
box 提示天然定尺度 → 单 mask,正好避开点提示的粒度歧义(点轮胎=轮胎还是整车)。
质量门 `tests/harness/sam_segmentation`:合成星形(细尖)场景 + 人工松框 → IoU ≥ 0.92。

**M3.17 第二增量(2026-05-31)已落 —— mask 投 depth 接进融合主线**:RGB 与 depth 端侧已对齐(§5.3),
故 mask 逐像素直接对应 depth,**无需跨传感器外参**。`RgbdFrame.mask` 作与 `conf` 平行的**深度预掩码通道**
(`fusion_core._masked_depth`:mask 外像素一律置 0,不入点云 / 配准 / 积分,registration 与 integration
用同一份 mask 后深度)。`rgbd_bundle` 加 `mask_{i}.u8` 通道(向后兼容);**masks 随 bundle 走,fusion 不回调
sam_service**——分割是喂给几何层的感知步,上游(人工框→sam_service)算好 mask 冻进 bundle、可让用户先确认,
fusion 只消费,服务解耦无运行时耦合。新增 `mask_erode_px` 旋钮(默认 0):针对真机深度边界飞点 / 混合像素,
合成 raycast 无此类像素故默认关,待真实 P100R3 RGBD(M3.14②)标定再开。
端到端验证 `tests/harness/scan_mask_fusion`(目标+地面+干扰 → 真 SAM 逐视角分割 → mask 引导融合):
SAM IoU ~0.994、mask 引导 chamfer ~1.7mm / 背景污染 ~0.01% / cov@5mm ~94%,baseline(无 mask)污染 ~90%、
对照砍 ~9000×,且贴齐 GT-mask 上界 → **证明 2D 高 IoU 经 mask 预掩能转成干净 3D 物体点云**。
踩坑:目标若用周期纹理会让 FPFH/Color-ICP 特征混叠致 ~20% 位姿翻转(见
`docs/agent-memory/finding_periodic_texture_registration_aliasing_2026-05-31.md`)。

**M3.17 第三增量(2026-05-31)已落 —— 与阶段 1 启发式 ROI 的 A/B,验收 §6 阶段 2 完成标准"毛刺降 ≥80%"**:
`scan_mask_fusion` 忠实复刻 native `BuildForegroundDepth`(中心 ROI 取 P25 种子深度 → 动态深度带
→ 4-连通中心加权块,见 `heuristic_roi.py`)作阶段 1 对照,给它和 SAM **同一人工框**(steelman,
对应阶段 1"用户框补救"路径)。结论:启发式是**纯深度法**,目标坐在地面上、接触处深度连续时,
基座一圈地面被同一连通块吃进来 → mesh 边缘裙边毛刺(顶点数约 SAM 的 1.7×);SAM 按外观切净。
**A/B 与 erode_sweep 都改"固定位姿重积分"**(三种 mask 共用 GT-mask 融合得到的同一组干净位姿,
用 `integrate_tsdf` 重积分)→ 隔离 Open3D RANSAC 多线程非确定翻转,纯比"分割致的毛刺",
门稳定不被"配准谁更稳"的伪因刷过。验证:固定干净位姿后启发式 box 毛刺仍 ~35%(SAM ~2%)→ 毛刺
确是真实地面裙边、非配准翻转。**A/B 边缘毛刺下降 ~93–95%(≥80% 验收过)**,多次跑稳健。
量化:毛刺 = 重建点离目标观测面 > 8mm(>voxel5+噪声~2)的占比。**后续增量**:端侧 MobileSAM/SAM2 轻量化、真实图像基准。

### 5.3 RGB 与 Depth 像素对齐

P100R3 spec 表 1 明确："**深度彩色像素对齐**"是固件能力。意味着：

```
SAM 在 RGB(1920×1080) 上输出 mask(1920×1080)
       ↓ 缩放 / 裁剪到 depth 分辨率(1280×800)
mask × depth → 物体 depth → 反投影 → 物体点云
```

**不需要双摄外参标定**（这是相比"手机主摄 + 外接深度相机"方案的重大简化）。
但要在 P100R3 SDK 启用 `setRegistrationEnable(true)` 让 SDK 出对齐版本的 RGB+Depth。

> **M3.17 mask 机制对本节的硬依赖**:§5.2 第二增量把 SAM mask 当作逐像素对应 depth 的预掩码,
> **前提正是本节"RGB 与 depth 已在端侧(M3.12)对齐到同一内参/分辨率"**。若端侧对齐未落地或失败
> (未启 `setRegistrationEnable`、分辨率不一致),mask 投 depth 会整体错位 → 抠出的不是目标。
> 故真机联调时,RGB↔depth 对齐是 M3.17 mask 链路的前置验收项,不是可选项。

### 5.4 用户引导（采集时）

弱纹理 / 弱几何目标（白漆卡车、玻璃车窗、镜面）pairwise 配准会失败。**采集时主动引导比事后救场更有效**：

- **Overlap 提示**：每按下一张拍照前，用 ORB 特征实时算当前 viewfinder 与上一张 RGB 的 overlap 比例；
  < 30% 时 HUD 显示"和上一张重叠不够，往左转 ~15° 再拍"
- **角度引导**：HUD 上一个 8 段圆环，已采角度填实色，未采角度灰显
- **失败回退**：扫描完成后 cv-engine 跑配准，某两张 pair 配不上时 → 端侧 UI 提示"角度 5 和 6 拼不上，请补拍这两个角度之间"
- **目标锁定**（阶段 2 起）：第一张让用户点 prompt 点指定目标 → 后续帧 SAM 自动用该 prompt 跟踪

### 5.5 深度置信加权（贯穿配准 / 融合）

P100R3 在 density-first 稠密模式下，弱回波 / 散斑弱像素单帧误差可达 ~9%（弱像素静态时域 MAD ~40-60mm，
见 [TODO M1.6.17](../../TODO.md)）。**策略：保稠密 + 按 per-pixel 置信加权**，而非退回 vendor 稀疏（仅 11% 密度）。
置信来源已打通到 `core:model` 的 `DepthFrame.confidence`（uint8，0=无效/飞点，255=高置信）：
时域稳定性置信（窗口 `window_span` 派生，M1.6.17）∧ IR 散斑局部对比度单帧置信（M1.6.19，AUC 0.72-0.82）取小融合 + 飞点清零。

置信应贯穿**每个**重建阶段（设计原则，非全部已实现）：

| 阶段 | 加权方式 | 状态 |
|------|---------|------|
| 端侧 TSDF 积分 | voxel 权重 `w += conf/255`（conf=0 不贡献），SDF 按权混合 | ✅ 端侧已落地（M1.6.20） |
| 端侧 ICP | 加权 Kabsch 刚体拟合，低置信点降权防噪声拉偏位姿 | ✅ 端侧已落地（M1.6.20） |
| 端侧 mesh 门 | `min_weight` 在加权模式下语义=累计置信（满置信观测为单位）；弱区靠多帧累计过门、不空洞 | ✅ 端侧已落地（M1.6.20，harness 系统性恒弱区实测无空洞） |
| 云端 Color-ICP | conf 阈值预掩码(conf<thr 深度置 0,不参与点云/配准/积分),= Open3D 无 per-point 权重下的加权等价 | 🟡 算法核已验（M3.14，`fusion_core.py`，harness `scan_fusion` 门②；服务管线待） |
| 云端 TSDF/纹理 | 同一份 conf-masked 深度喂 `ScalableTSDFVolume`,位姿与体素来自同一可信像素集；纹理烘焙待 | 🟡 算法核已验（M3.14；纹理/GLB 导出待） |
| SAM mask 边界 | 前景 logit 0.3-0.7 软权重，避免硬边界 TSDF 伪影 | ⬜ 待（阶段 2，M3.17） |

**端侧验证**（harness `tests/harness/scan_conf_weighting/` + 单测 `tests/native_host/conf_weight_test.cpp`）：
合成球面带 45% 弱回波，加权重建表面 RMS 14.3→0.81mm（降 94%）、内点 40→100%、覆盖真球冠 98%；
真硬件 density-first depth + IR-conf chamfer 降 42%。即 **density-first + 置信加权 > 稀疏干净**，
保稠密同时把弱像素拉回标称精度。

**云端复刻(M3.14 算法核已验)**：`server/fusion_service/fusion_core.py` 把同一套加权语义落进 Open3D——
因 Open3D Python 的 RGBDImage→PointCloud / TSDF.integrate 无 per-point 权重 API,用 **conf 阈值预掩码**
(conf<thr 深度置 0,不参与点云/配准/积分)作可行等价,且配准与积分共用同一份 conf-masked 深度。
harness `tests/harness/scan_fusion` 实测:干净 chamfer ~1.3mm(全连接 multiway 配准逼近真值);
带噪 40% 弱回波+飞点下 conf 加权 ~1.6mm vs 不加权 ~35mm(**降 ~96%**),收益与端侧 mask_recovery 同源。
服务管线(Go worker / NATS / 上传契约 / 纹理烘焙 / GLB)属 M3.14 后续增量。真"软加权"需自写 C++ TSDF 扩展,列后续。

## 6. 工程实施阶段（与 [TODO.md](../../TODO.md) M3.12–M3.20 对齐）

### 阶段 1：端拍照 + 云融合（业务闭环 MVP，1-2 周）

- M3.12 端侧采集 UI（启发式 ROI 兜底分割）
- M3.13 asset multipart upload 接入
- M3.14 fusion_service(Python/Open3D)+ fusionworker(Go)；UV 纹理用 Open3D `compute_uvatlas`+`project_images_to_albedo`(非 xatlas)。2026-05-31 算法核+垂直切片+纹理烘焙已落,合成 harness 全过；待真实 RGBD 端到端
- M3.15 端侧 gallery GLB 拉回 Filament 渲染
- M3.16 scan_multiview_quality harness（Stanford Bunny 合成 + 卡车真实数据）

**完成标准**：8 张真实卡车 RGBD → 1-2 分钟内出 mesh，顶点 ≥ 50K，目视卡车形状完整无大面积塌陷。

### 阶段 2：SAM-HD 替代启发式（精度提升，2-3 周后）

- M3.17 cv-engine 接 SAM-HD（ViT-H 或 SAM2）+ 端侧 prompt 收集
- M3.18 GPU worker 容器部署

**完成标准**：mesh 边缘毛刺 vs 阶段 1 减少 ≥ 80%；SAM mask IoU vs 人工标注 ≥ 0.92。
**进度(2026-05-31)**:M3.17 服务端 HQ-SAM + mask 投 depth + 与阶段 1 启发式 A/B 已落,
`scan_mask_fusion` 合成 harness 两项均过(IoU 0.994 ≥0.92;毛刺降 ~93–95% ≥80%,固定位姿受控);
真实图像/真机 RGBD 验收待 M3.14② 物理采集。M3.18 GPU worker 容器部署未起。

### 阶段 3：端侧实时拼接预览（体验升级，4-6 周后）

- M3.19 端侧 SAM2 Mobile 集成（NPU/GPU 多后端适配）
- M3.20 端侧实时 ICP/TSDF + Filament 实时 mesh 渲染（继承 M3.1/M3.2 沉淀）

**完成标准**：端侧实时 mesh ≥ 5fps 增长；扫描完成 ≤ 1s 出 cm 级实时版；30s 后云端 mm 级版自动替换。

## 7. 当前已有沉淀的去向

| 既有 | 阶段 1 | 阶段 2 | 阶段 3 |
|---|---|---|---|
| ICP / spatial hash / TSDF / MarchingTetrahedra (M3.1, native) | 不调用 | 不调用 | **复用**为端侧实时预览 |
| scanSessionCreate / Ingest / Finalize (M3.2, NativeBridge) | 不调用 | 不调用 | **复用**为端侧实时管线入口 |
| Recording / Completed UI + Filament 3D 预览 (M3.3) | UI 框架保留，交互改"采集模式" | 加 prompt 输入 | 改回"录像式实时预览" |
| P100R3 spec 工作距离常量 + grid 默认值 (M3.7) | 阶段 1 不用（云端融合自己控制 voxel）| 同 | **阶段 3 复用** |
| Berxel SDK + RGB 像素对齐 (M1.1) | 必须 | 必须 | 必须 |

## 8. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 弱纹理 + 弱几何（白漆 + 平面）pairwise 配准失败 | (1) Color-ICP 利用 RGB 微梯度；(2) UI 引导多拍提高 overlap 密度；(3) 失败检测后端侧主动提示补拍 |
| 大目标采 8 角度 overlap 不够 | 改 12 角度 / 30° 间隔；卡车 + 顶部俯拍补 1-2 张 |
| 室外强光淹没 940nm 结构光 | 业务定位明确"室内"；UI 提示用户避免阳光直射 |
| 云端 worker GPU 排队 | 阶段 1 启发式分割不依赖 GPU；阶段 2 起再考虑 GPU 池化 |
| 离线场景（车间无网） | 本地保留原始 RGBD zip；联网后自动上传跑云端；阶段 3 端侧降级版可单机出 cm 级 mesh |

## 9. 文档对齐

- 业务路线：[../../TODO.md](../../TODO.md) M3 节
- 决策记忆：[../agent-memory/finding_multiview_rgbd_pivot_2026-05-07.md](../agent-memory/finding_multiview_rgbd_pivot_2026-05-07.md)
- P100R3 硬件真理源：[../agent-memory/reference_iHawkP100R3_spec.md](../agent-memory/reference_iHawkP100R3_spec.md)
- 服务端 worker 架构沿用：[server/00-server-overview.md](server/00-server-overview.md) §6
- asset 上传契约：[server/02-api-contract.md](server/02-api-contract.md) §5
- 历史路径（弃）：[04-reconstruction-pipeline.md](04-reconstruction-pipeline.md)
