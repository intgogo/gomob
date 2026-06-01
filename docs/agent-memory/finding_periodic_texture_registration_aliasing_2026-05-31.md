# 周期纹理致多视角配准位姿翻转(FPFH/Color-ICP 特征混叠)2026-05-31

建 `scan_mask_fusion` harness 时,目标物体配色用了周期正弦纹理(`synth_dataset.surface_color`),
同一输入跑 5 次有 ~20% 概率某视角位姿翻转(chamfer 1.7mm ↔ 6mm 乱跳、污染 0% ↔ 9%)。
换成非周期线性渐变色(同 `make_gt_mesh` 的 `(v-vmin)/(vmax-vmin)`)后 10/10 完全稳定(1.67–1.75mm)。

**Why**:周期纹理在不同表面位置产生**重复的 FPFH 特征 / 颜色梯度**,非相邻视角(尤其宽基线、近背对)
间出现大量"看着很匹配但其实错位"的对应 → RANSAC 拿到 fitness 假高的错位姿 → 全局优化 line process
拦不住 → 该视角翻转。Open3D RANSAC 本身多线程、即使 `o3d.utility.random.seed(0)` 仍有轻微非确定,
正好让"翻/不翻"在周期纹理这种刀刃条件下随机抖动。这与 M3.16 修的"固定粗 FPFH 尺度致宽基线误配"
是同一类根因(配准对应不可靠),只是触发源换成纹理周期性。非周期渐变色给单调可区分特征 → 无歧义。

**How to apply**:
- 造配准 / 重建 harness 的合成物体,**配色用非周期单调梯度**,不要用周期正弦纹理 ——
  否则量到的是"配准对周期纹理的脆弱性",污染你本想验证的指标(如 mask 机制、TSDF 质量)。
- 调试"同输入结果乱跳"的融合 flaky,先怀疑配准翻转:看 fused 顶点数 / completeness 是否同时跳变
  (翻转会甩出一团远点),而非盯着下游(SAM/腐蚀/voxel)。
- 真实带重复纹理的物体(瓷砖地、卡车铆钉阵列、栅栏)生产上同样有此风险 → 单列鲁棒性 TODO:
  评估 mutual/ratio-test 对应过滤、限制宽基线闭环边的角度跨度、或 RANSAC 确定化(降线程/固定 seed)。
- **受控对比/参数扫描隔离配准**:harness 里比"分割/腐蚀等单一变量对重建表面的影响"时,别让各分支各自
  `fuse_with_poses`(各跑一次 RANSAC,翻转噪声混进结果)。改用**一组干净位姿**(如 GT-mask 融合得到)
  对所有分支 `integrate_tsdf` 重积分,只变被测变量 → 纯比该变量,门稳定不被配准抖动污染
  (scan_mask_fusion 的 A/B 与 erode_sweep 都这么做;另在 metric 采样前 `o3d.utility.random.seed(0)` 锁采样)。
- 相关:[[finding_multiview_rgbd_pivot_2026-05-07]] 是当前重建主线;配准尺度派生修复见 `fusion_core.reg_voxel_m`。
