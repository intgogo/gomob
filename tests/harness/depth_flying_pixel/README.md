# depth_flying_pixel — 飞点剔除质量 harness

验证 portable 层飞点剔除（`p100r3_flying_spatial_evidence` + `P100R3TemporalFilter` 联合判定）：
真飞点召回足够高、真实几何不被过杀。纯离线无真机。

## 飞点与设计

飞点（flying pixel）= 结构光在前景/背景断崖之间插值出的"悬浮假点"，污染点云、是相邻帧抖动 p95 的主来源。

grounding（`.dev/flying-pixel-analysis/GROUNDING.md`）实测：**纯单帧检测过杀 24%**（把真实斜面/边缘当飞点）。
真飞点需 **三证合一**判定（设计见 `.dev/flying-pixel-analysis/SYNTHESIS.md`，4 视角设计评审综合）：
1. **时域不稳**：连续稳定帧数 `stable_run` < 阈值（慢性飞点频繁 reset）或窗口 span 过大。信号取自 `P100R3TemporalFilter` 窗口（项目独有；grounding 证断崖里 91.9% 时域不稳 vs 真边缘仅 2.4%）。
2. **双侧角度超界夹心**：沿 4 方向外探半径 R，被"更近的崖"和"更远的崖"同时夹住。阶跃上界 `step_max(Z)=tan(grazing)·Z/f·Δpx` 随深度/距离缩放——把固定 mm 阈值换成物理坡度上界，从根因消除单帧过杀。单侧超界=真实遮挡边/物体轮廓，放行。
3. **无共面支撑**：8 邻域共面邻居 < 阈值。斜面/曲面恒有共面邻居 → 否决删除（护盾）。

检测在 **temporal fuse 之后**做（fused 已 ~3.7× 降噪，梯度算在 ~10mm 噪声底而非单帧 38mm，避免假断崖）。
命中 = 保 raw 原值 + confidence 置 0 + flying_mask=1（"raw 是测量真值"，删点由下游按 conf 跳过）。
暖机（总观测 < min_stable）只降权不硬删（信号不足，宁漏勿杀）。

## 运行

```bash
./dev.sh harness depth_flying_pixel   # 或 bash tests/harness/depth_flying_pixel/run.sh
```

## 验收门

合成 GT 场景（gen_scene.py，6 组覆盖参数 plateau）：
- 含飞点场景（S1 近距前景+远背景断崖、S5 远距版）：真飞点召回 **recall ≥ 0.80** 且真几何保留 **geom_keep ≥ 0.99**。
- 纯几何场景（S2 连续斜面 / S3 球面 / S4 台阶 / S6 薄结构）：**FP == 0**（零误删红线）。
- 真实数据 sanity（vendor-dense，无 GT）：飞点剔除占比 **< 15%**（证不过杀杂乱真实几何）。

当前基线：6 组合成场景全 OK（含飞点 recall=1.000、geom_keep=1.000；纯几何 FP=0）；
**vendor-dense 真实数据 removal_ratio=0.04%**（对照单帧检测会铲 24% → 联合判定只动 0.04%）。

## 组成

- `gen_scene.py` — 合成 GT 场景 + 注入飞点（中间幽灵深度 + 时域不稳 + 深度自适应噪声）+ 逐像素 GT label + 与 grounding 一致性自检。
- `apply.cpp` — 只链 portable.cpp，逐帧 `P100R3TemporalFilter::push(flying_mask)`，累计 detect_count + stats.csv。可扫参（grazing/tstd/min-support/...）。
- `analyze.py` — 对照 GT 算 TP/FP/FN/recall/geom_keep，三态判定。
- `run.sh` — gen → 编译 apply → 逐场景跑 → 合成 GT 判定 + 真实数据过杀 sanity。

## 改动须重跑

任何对飞点判据、`P100R3TemporalFilter`、运动门限的改动，提交前跑本 harness + `scripts/berxel-host-test.sh`。
未尽（真机/标定到位后）：fx/fy 用真内参+畸变重验角度上界；端侧帧预算实测；2px 飞点带与运镜场景。
