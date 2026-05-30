# 深度飞点剔除：三证合一（时域不稳 ∧ 双侧夹心 ∧ 无共面支撑）2026-05-29

## 结论

P100R3 直出深度的飞点（结构光在前/背景断崖插值出的悬浮假点）剔除，**纯单帧空间检测会过杀 24%**
（把真实斜面/边缘当飞点）。正解是 **三证合一**联合判定，在 temporal fuse 之后做：
1. **时域不稳**：`stable_run`（连续未 reset 帧数）< 阈值 或 窗口 span 过大。信号取自 `P100R3TemporalFilter`
   窗口（项目独有）。慢性飞点频繁 reset → stable_run 小；真表面 → 大。暖机（frames_seen < min_stable）只降权不删。
2. **双侧角度超界夹心**：沿 4 方向外探半径 R，被更近的崖和更远的崖同时夹住。阶跃上界
   `step_max(Z)=tan(grazing)·Z/f·Δpx` 随深度/距离缩放——用物理坡度上界替固定 mm 阈值，从根因消除过杀；
   单侧超界=真实遮挡边/物体轮廓，放行。
3. **无共面支撑**：8 邻域共面邻居 < 阈值。斜面/曲面恒有共面邻居 → 否决删除（护盾）。

命中 = 保 raw 原值 + confidence 置 0 + flying_mask=1（"raw 是测量真值"，删点由下游按 conf 跳过，删坏点不造假）。

## Why

- grounding（vendor-dense 真实数据）：单帧"双峰夹心"候选 24%、梯度>60mm 占 33% → 纯单帧必过杀；
  断崖里 91.9% 时域不稳、真稳定边缘仅 2.4% → **时域信号能干净区分真飞点 vs 真边缘**，这是联合判定的根据。
- 检测放 **fuse 之后**：fused 已 ~3.7× 降噪，空间梯度算在 ~10mm 噪声底而非单帧 38mm，避免假断崖。
- 真实飞点是边缘 **1-2px 薄晕的中间幽灵深度**（不是 fg/bg 双稳，也不是 3px 纯块）；合成验证按此建模才不失真。
- 实现期发现：综合方案原用 `recent_reset_age + low_samples→降级`，会让**每帧都跳的飞点因 cnt 涨不起来一直走降级只降权不删**；
  改用 `stable_run + frames_seen` 区分"暖机(信号不足)"vs"慢性飞点(观测多但不稳)"才正确删。

## How to apply

- 改飞点判据/`P100R3TemporalFilter`/运动门限，提交前跑 `scripts/berxel-host-test.sh`（含 8 例飞点单测）
  + `./dev.sh harness depth_flying_pixel`（合成 GT recall≥0.80/geom_keep≥0.99/纯几何 FP=0 + vendor-dense removal<15%）。
- 验证飞点剔除**必须用合成 GT**（真实数据太杂乱无 GT，只能做过杀 sanity）。基线：合成 recall=1.0/geom_keep=1.0、
  真实 vendor-dense removal=0.04%（对照单帧 24%）。
- **真硬件验证（2026-05-29 服务器直连相机 live 采集）**：removal 0.05%/逐帧 3%，检测像素局部梯度中位
  **968mm vs 全图 29mm**（33×）→ 精准命中真深度断崖、不过杀，真飞点特征实锤。
- **断链待补**：confidence 在 JNI 算了但从不 poll 出去；接入需新增 `berxelDualPollDepthConf` + DepthFrame 加
  `confidence: ByteBuffer?`，飞点=conf 0 是其在契约里的唯一表达（不单开 mask 字段）。契约迁移范围需用户定。
- fx/fy 暂用 FOV 反推（440.4/424.1），标定 blob 到位后用真内参+畸变重验角度上界。

## 相关
- [[finding_depth_temporal_denoise_2026-05-29]] — 时域降噪；飞点剔除复用其窗口时域信号、检测在其 fuse 后。
- [[finding_p100r3_depth_ir_interleaved_2026-05-29]] — 路线 A 设备直出深度（飞点剔除的输入）。
- 设计/证据：`.dev/flying-pixel-analysis/{GROUNDING,SYNTHESIS}.md`、`tests/harness/depth_flying_pixel/README.md`。
