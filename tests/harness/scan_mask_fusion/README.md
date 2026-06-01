# scan_mask_fusion — SAM mask 引导融合端到端 harness(M3.17 ②)

验证 SAM 分割接进融合主线的**价值**:多视角场景里目标周围有地面/杂物时,用 SAM mask 预掩深度,
融合应**只重建目标、剔除背景**;与不带 mask 的 baseline(把地面/杂物一起融进来)硬对照。

## 为什么要这条 harness

`sam_segmentation` 只证 SAM 出的 2D mask 准(IoU);但 **2D mask 准 ≠ 3D 干净**——mask 边界像素
反投影会落到背景平面。本 harness 把 SAM→mask→`RgbdFrame.mask`→`fusion_core` 融合的整条链跑通,
量「重建出的几何里有多少是背景污染」,才算证明这步对 3D 扫描有用。

## 链路

`clutter_dataset`(目标+地面+干扰物 → raycast 多视角噪声 RGBD + 人工松框)
→ 真 HQ-SAM(`sam_core.segment`,box 提示)逐视角分割 → mask 填进 `RgbdFrame.mask`
→ `fusion_core.fuse_with_poses`(mask 外像素预掩,不入点云/配准/积分)→ 对比目标观测面。

三路对照:
- **masked(SAM)**:真 SAM mask 引导 —— 真实结果。
- **gt_masked**:完美目标 mask —— 机制上界,分离「SAM 边界质量」与「机制/参考问题」。
- **baseline**:不带 mask —— 背景全融进来,作价值证明。

## 判定门(analyze.py)

硬门:① SAM IoU 均值 ≥ 0.95 ② masked chamfer ≤ 5mm ③ masked coverage@5mm ≥ 88%
④ masked 污染 ≤ 2% ⑤ **baseline 污染 ≥ 30%**(价值证明:无 mask 确被污染,否则场景无效)
⑥ 污染对照 masked ≤ baseline/5。软报告:GT-mask 上界对照、精度/完整度分量、最差视角 IoU。

实测(8 视角 voxel5mm):SAM IoU ~0.994、masked chamfer ~1.7mm / 污染 ~0.01% / cov@5mm ~94%、
baseline 污染 ~87–97%(随机:floor 主导配准不稳但都远超 30% 价值门)、对照砍 ~9000×;
masked 与 gt_masked 上界同噪声量级(都 ~1.7–1.8mm)→ SAM 已足够好。

## 两个设计要点(踩坑记录)

- **目标用非周期渐变色,不用周期正弦纹理**:周期纹理会让 FPFH/Color-ICP 在非相邻视角**特征混叠** →
  误匹配致 ~20% 概率位姿翻转(chamfer 1.7↔6mm 乱跳)。换非周期渐变后 10/10 稳定。
  这是配准对周期纹理的鲁棒性课题(见 `docs/agent-memory/finding_periodic_texture_registration_aliasing_2026-05-31.md`),
  不该混进本 harness;周期纹理物体的生产鲁棒性单列 TODO。
- **`mask_erode_px` 默认 0**:腐蚀针对真机深度边界飞点/混合像素;合成 raycast 边界是 GT 精确深度、
  无混合像素,开腐蚀只单调削覆盖、无污染收益 —— 由 `metrics.json` 的 `erode_sweep` 字段(本 harness
  对同组 SAM mask 扫 erode 0–4px)复现追溯。真机飞点的腐蚀收益待真实 P100R3 RGBD(M3.14②)标定再开。

## 跑

```bash
# 需 sam-venv 基础上补 open3d/trimesh(torch+sam-hq+open3d 一锅):
.dev/sam-venv/bin/pip install "open3d==0.19.0" "trimesh>=4,<5"
./dev.sh harness scan_mask_fusion          # run.sh 自动挑空闲 GPU + analyze 判定
```

产出 `.dev/scan_mask_fusion/`:`metrics.json` + 首视角 `view0_{scene,gt_mask,sam_mask}.png`(人工复核)。
