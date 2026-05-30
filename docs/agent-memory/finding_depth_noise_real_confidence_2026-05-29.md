# 深度"满屏噪点"根因=密度优先+设备置信废值;解=真置信(温度稳定性)+空间降噪(2026-05-29)

用户盯实物看 demo 深度"全是噪点"。三判据坐实:**是真深度不是 phase 误读/decode bug**
(低字节 256 distinct 非 IR 的 13、直方图连续真实纵深、平滑区相邻像素差仅 ~1mm),
但叠了超规噪声(逐帧 MAD 中位 85mm,~22%@386mm,远超 ≤1% 规格)。

## Why(根因,两条)

1. **密度优先策略**:为怼 valid≈1.0 把设备 `temporal_denoise=0 + spatial_denoise=0` 全关,
   结构光不可靠区(弱纹理/斜入射/边缘)本该被 blank,却被填上噪声深度。speckle(邻差>50mm)占 ~40%。
2. **设备 confidence 是饱和废通道**:实测 98.7% 像素都标 255,噪声像素也标满置信 → 任何 conf 阈值掩码都无效。
   我们建好了 confidence 通道却喂它废值。

真相:**~74% 像素时域不稳,只有 ~24-26% 是测量级**;按时域稳定性掩码后真实表面(手臂等)干净浮现。
**空间降噪单独救不了**(median3+bilateral 实测 noise 27→9mm 但 speckle 仍 33%,因那些不是孤立飞点是成片不可靠区)。

## How to apply(已落地 portable,真帧验证,host 测试全绿)

- **真置信(核心)**:`P100R3TemporalFilter` 用窗口 `window_span`(跳幅)派生稳定性置信替换废值——
  span 越大越不可信,即使窗口攒满。config:`confidence_from_stability/conf_stable_span_mm=20/conf_unstable_span_mm=80/conf_span_percent=0.02/conf_min_valid=8`。
  **数据保稠密**(fused 原值不动),下游(demo 渲染/重建/多视角配准)按 conf 掩码或加权。契合多视角主线:每视角只信可信像素,洞由其它视角补,逐帧硬怼密度反而往配准灌噪声。
- **空间降噪(二次清理)**:`apply_spatial_denoise` = median3 去脉冲 → bilateral5(σ_s=2,σ_r=40mm,range LUT)保边,
  在 fuse 后、飞点前作用 fused。真帧 noise_p50 27→10.5mm、edge_keep 0.89、density 1.0。算力 ~0.68Gop/s,端侧 45fps 余量足。
  **flying 合成 harness 必须 `spatial_denoise_enable=false`** 隔离(否则 median 提前吞掉合成飞点)。
- **设备降噪维持关闭**:端侧全可控可解释,不叠设备黑盒剔点(违"缺洞可解释")。
- demo 处理面板按 `conf>=160` 掩码出黑洞=测量级视图;离线工具 `.dev/denoise-proto/`(dh.py + apply_filter)可复跑。
- **待真机复核**:旧采集疑似含手持运动,26%/85mm 可能偏悲观;master 物理恢复后用静态采集定确切数。
- 关联 [[finding_depth_temporal_denoise_2026-05-29]]、[[finding_depth_flying_pixel_removal_2026-05-29]]、
  [[finding_multiview_rgbd_pivot_2026-05-07]](多视角主线=可信稀疏优于稠密噪声)。
