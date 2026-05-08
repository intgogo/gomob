---
name: iHawk P100R3.0 产品规格表
description: gomob 实际使用的深度相机型号 P100R3.0 的关键参数（工作距离 / 精度 / FOV / 接口），native 算法所有阈值的真理源
type: reference
---

# iHawk P100R3.0 产品规格

**型号**：Berxel iHawkP100R3.0（**不是** iHawk 072 / iHawk 071 / iHawkP008G —— 它们是不同型号）

**规格书**：[docs/iHawkP100R3.0_V1.2.pdf](../iHawkP100R3.0_V1.2.pdf)（2025-06-30 V1.2，长期储存温度更新）

## 关键参数（来自规格书表 1）

| 维度 | 值 | 工程含义 |
|---|---|---|
| **深度工作范围** | **0.2 - 8m** | 硬过滤窗口；超出范围 SDK 仍吐数据但属无效 |
| **理想工作范围** | **0.25 - 2m** | 厂家保证精度的窗口；默认场景应让目标主体落在此区间 |
| **精度** | **≤1% @ 1-2m** | 1m 处 ±10mm，2m 处 ±20mm；远端 5-8m 退化到 cm 级（仍是有效观测） |
| 基线 | **40mm** | 比 iHawk 072 的 16mm 大 2.5×，远距离精度更好的根本原因 |
| 深度分辨率 / 帧率 | 1280×800 / 640×400 / 320×200 @ 5/10/15/20/25/30/45 fps | 全部分辨率均支持 0.2m 起测 |
| 深度 FOV | **72° × 50.5°** | 1m 处 FOV 覆盖 ~1.45m × 0.95m；2m 处 ~2.9m × 1.9m |
| RGB 分辨率 | 最高 1920×1080 @ 45 fps | 用于纹理烘焙；MJPEG 编码 |
| RGB FOV | 88° × 56.8° | 比深度 FOV 大；纹理采样会有 RGB 边缘无对应深度 |
| 激光波长 | 940nm (VCSEL) | Class 1 + FDA 安全 |
| 接口 | Type-C USB3.0 | 供电 ≥2.5A @ 5V |
| 使用环境 | **室内** | 户外强阳光会淹没 940nm 结构光 → 无效 |
| 硬件能力 | **深度彩色像素对齐 + 时间戳 + 硬件多机同步** | 像素对齐 SDK 自己做，无需手动 RGBD warp |
| 工作温度 | -20℃ 到 50℃ @81% FOV | 工业级温域 |
| IP 防护 | IP54 | 室内防尘防溅 |

## native 代码工作距离常量真理源

[native/depth/depth_projection.cpp](../../native/depth/depth_projection.cpp) 的 `kMinValidDepthMm` /
`kMaxValidDepthMm` **必须**与本规格表"深度工作范围"对齐，当前值 `[200, 8000]`。

历史踩坑：codex 在 2026-05-07 把这两个常量当成 [250, 2500] 写死（误把 iHawk 072 spec 套到 P100R3 上），
导致用户在中远距扫描时全帧被滤光，"前景有效深度只有 3553 px"的诊断 log 就是这个回归。

## 默认 grid 参数选型（[Scan3dRecordingViewModel.kt](../../feature/scan3d/src/main/kotlin/io/gomob/feature/scan3d/Scan3dRecordingViewModel.kt)）

当前 P100R3 默认（桌面 / 单件中物预设）：
- `voxelSizeMm = 6.0f`：trunc=24mm，匹配 P100R3 在 1-2m 处 ±10-20mm 真实噪声底（4mm voxel 比噪声还细，反而拉锯刷新）
- `gridExtentMm = 1500.0f`：能装下沙发 / 单车 / 整机这类大件
- `gridCenterZMm = 1000.0f`：grid 覆盖 z[250, 1750]mm，正好对齐理想精度区间

内存：250³ × 8B ≈ 125MB，端侧能扛。

后续阶段若引入"扫描预设 UI"（桌面物 / 中件 / 大件 / 大场景），可按 preset 切换 voxel/extent。
