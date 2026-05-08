---
name: 三维重建主线 2026-05-07 重大方向变更：实时 SLAM → 多视角 RGBD 配准 + 端云融合
description: 2026-05-07 决策把扫描重建从"端侧实时 SLAM + TSDF"改成"端侧采集 N 角度 + 云端 Open3D multiway_registration"，理由 / 取舍 / 三阶段实施
type: finding
---

# 重建主线 2026-05-07 重大方向变更

## 决策

把"三维外廓扫描重建"主线从

- ❌ 旧：端侧实时 SLAM（连续录制 30-60s）+ ICP 帧间配准 + 端侧 TSDF + Marching Cubes

切换为

- ✅ 新：**端侧环绕目标采 8-12 角度 RGBD** + 物体语义分割 + **云端 Open3D multiway_registration + Color-ICP + 全局 PGO + 纹理烘焙** + 端侧 GLB 渲染回看

权威设计：[../architecture/04b-multiview-rgbd-reconstruction.md](../architecture/04b-multiview-rgbd-reconstruction.md)

## 决策的因果链（按第一性原理）

### 起点：现象

- 2026-05-07 用户报告"三维外廓扫描显示的点云不对"
- codex 给的诊断：背景 / 桌面 / 手臂大面积内容主导 ICP 配准，目标物只占少数点 → 修了启发式中心连通块前景过滤
- 用户进一步问：能扫卡车吗？能扫城市吗？

### 第一推导：为什么之前做不了大目标

1. iHawk P100R3 spec：测距 0.2-8m，理想 0.25-2m，硬件能扫卡车（之前误用 iHawk 072 的 [250, 2500] spec 把工作距离过滤错了，已修）
2. 但扫卡车需要解决三个问题：
   - **目标 / 背景分离**：启发式 ROI（中心连通块）在大目标 / 贴墙 / 复杂场景失败
   - **大体积重建**：密集 TSDF 装不下 60m³ 卡车
   - **大场景全局漂移**：纯 ICP 走一圈累积漂移到几十 cm

### 第一推导：纯端侧实时 SLAM 解法的代价

- **目标 / 背景分离** → 上 SAM/SAM2 端侧推理（50-200MB 模型 + NPU 适配）
- **大体积重建** → 稀疏 TSDF voxel hashing
- **全局漂移** → ARCore/AREngine/IMU 三层适配（**国产手机 50% 不在 ARCore 名单**）+ 双摄外参标定

工程量约 1-2 个月，且**精度天花板被实时贪心 ICP 限制在 cm 级**——做不到 gomob "高精度扫描设备"定位的 mm 级。

### 第一推导：用户提的多视角方案为什么更优

用户思路：环绕目标拍 8 个角度 + 每个角度 3D 目标分割 + 不同角度点云融合。

这条路本质上是 **离线多视角 RGBD 配准 + 全局 Pose Graph Optimization**，对应 Open3D `multiway_registration`，是工业 3D 扫描的标准流程。重要洞察：

1. **pose 来源从手机外部（ARCore/IMU）回到点云本身**（FPFH+RANSAC 粗对齐 + Color-ICP 精修）→ 不依赖任何手机能力
2. **全局 PGO 天然消除累积漂移**，loop closure 免费 → 精度可达 mm 级
3. **物体分割只需要 SAM 给 2D mask + P100R3 硬件像素对齐**投到 depth → 不需要 3D 分割模型
4. **算法主战场放云端**（Open3D Python + Ceres）→ 端侧只采集，工程量小一个量级
5. **失败时单张可重拍**，不像实时模式跑飞了不可逆

代价：
- 用户体验从"实时反馈"变成"拍 8 张 → 等 1-2 分钟"
- 必须有云端（离线场景退化到本地 raw 暂存）

### 第一推导：用户进一步问"能不能纯端侧实时拼接，省后端"

可行但有真问题：
- SAM2 Mobile 50MB + NPU 适配工程量大
- ICP 实时累积漂移仍存在 → 精度天花板 cm 级
- 失败不可逆
- "高精度扫描设备"定位要求 mm 级 → **必须有云端 PGO**

→ 最终决策：**端云组合**（不是 either/or）。云端做 mm 级终稿；端侧阶段 3 加实时预览补"实时反馈"体验差；离线场景端侧降级出 cm 级。

## 三阶段实施（与 [TODO.md](../../TODO.md) M3.12-M3.20 对齐）

| 阶段 | 内容 | 工期 | 关键产出 |
|---|---|---|---|
| 1 | 端拍照 + 云融合（Open3D multiway_registration + Color-ICP + PGO + MC + 纹理烘焙）| 1-2 周 | "8 张照片 → 卡车 mesh"业务闭环 |
| 2 | 云端加 SAM-HD 替代启发式 ROI | 2-3 周后 | 分割从启发式升到语义级 |
| 3 | 端侧加 SAM2 Mobile + 实时拼接预览 | 4-6 周后 | 用户实时反馈，主动补漏 |

## 已弃但保留沉淀

- [native/reconstruction/](../../native/reconstruction/) 的 ICP / spatial_hash / tsdf / marching_cubes —— 阶段 3 端侧实时预览复用，**不要删**
- [feature/scan3d/](../../feature/scan3d/) Recording UI / Filament 3D 预览 / mesh lit 渲染 —— UI 框架阶段 1 改造为"采集模式"，阶段 3 改回"实时录像式预览"
- M3.7 P100R3 spec 对齐（200/8000mm 工作距离 / grid 默认值）—— 阶段 1 不直接用（云端融合自己控制 voxel），阶段 3 端侧复用

## 关键反例 / 提醒

- **不要再扩展 [04-reconstruction-pipeline.md](../architecture/04-reconstruction-pipeline.md)**（实时 SLAM 路线）；新工作落在 04b
- **不要给当前 native 管线再加稀疏 TSDF / PoseSource / 双摄外参**——这三件事在新方向下都被 Open3D 多视角配准消化掉了，做了浪费
- **不要默认走"完全端侧 / 不要后端"的方向**——精度天花板限制 + 失败不可逆 + "高精度"产品定位都不允许；端云组合是真正第一性最优解
- **不要给阶段 1 启发式 ROI 投入太多优化**——一旦阶段 2 SAM-HD 上线就被替代

## 与已有项目设施的对齐

- 服务端 worker 架构（[server/00-server-overview.md](../architecture/server/00-server-overview.md) §6）已支持新增 worker；object_3d_fusion 沿用 NATS event + MinIO 直拉模式（参考 worker_preliminary）
- asset multipart upload (M-S2.3) 已就绪，端侧 zip 上传直接复用
- M3.1 / M3.2 / M3.3 / M3.7 既有沉淀全部保留作阶段 3 输入

## 触发情景

后续会话中如果发现：
- 有人想给 native 加稀疏 TSDF / VoxelHashing → 拦下来，引到 04b §5
- 有人想接 ARCore/AREngine 给 SessionIngest → 拦下来，多视角方案不需要
- 有人想做手机主摄 ↔ P100R3 双摄外参 → 拦下来，P100R3 硬件像素对齐已够用
- 有人讨论"扫大场景为什么不行"→ 引到本文件 + 04b
