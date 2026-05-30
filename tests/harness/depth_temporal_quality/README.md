# depth_temporal_quality — 深度时域降噪质量 harness

验证 portable 层 `P100R3TemporalFilter` 多帧融合在真实录制序列上的"行为好不好"：
相邻帧抖动(stability)是否大幅下降、有无系统偏移(bias)、密度(density)是否退化。

单测 `tests/native_host/berxel_temporal_filter_test.cpp` 验证融合数学正确；本 harness 验证真实数据上的质量增益。

## 为什么需要时域降噪

P100R3 关掉设备 temporal_denoise 才能拿到稠密深度(valid≈1.0)，代价是逐像素相邻帧抖动 ~38mm，
远超 ≤1%@1-2m 规格。这是量测/复现性的头号敌人（`process_p100r3_depth_frame` 只补洞、对 raw 有效像素零降噪）。
grounding 仿真与本 harness 实测：有界滑窗均值 N=8 能把抖动压到 ~10mm（3.7×）且密度不掉、零偏移。

**陷阱**：朴素小阈值运动感知 EMA 会失效——逐像素噪声(38mm) > 阈值时每帧被判运动→不停 reset→等于透传。
故运动门限必须 ≥ 噪声底（默认 60mm ≈1.6×），由本 harness 扫参确定。

## 运行

```bash
./dev.sh harness depth_temporal_quality      # 或 bash tests/harness/depth_temporal_quality/run.sh
```

无需真机：默认复用 `berxel_depth_parity` 已采的静态场景序列。产出写 `.dev/depth_temporal_quality/`。

## 数据源与验收

| 标签 | 类型 | 说明 |
|------|------|------|
| vendor-dense | **门** | 原厂参照 raw 深度（噪声底 ~38mm）。`analyze.py` 必须 OK，否则 harness 失败。 |
| host-default | 参考 | 自研补洞后输出/不同场次，噪声更高；门限<其噪声底时不改善属预期，仅诊断不计入 exit。 |

验收门（analyze.py）：
- stability 增益 ≥ 2×（理想 ≥ 3×），否则 FAIL/WARN。
- bias（有符号差均值）≤ 8mm —— 均值滤波器不应移动每像素时间均值。
- density 下降 ≤ 2pp。

当前基线：vendor-dense **38.25→10.25mm（3.73×）**、bias 0.51mm、密度 0.9983→0.9984，status=OK。

## 组成

- `apply_filter.cpp` — 只链 portable.cpp（无 libusb），读 .raw 序列→逐帧 `P100R3TemporalFilter::push`→写 fused-NNN.raw + conf-NNN.raw + stats.csv。支持 `--window/--motion-mm/--motion-percent/--min-full` 扫参。
- `analyze.py` — 对比融合前后 stability/bias/deviation/density，输出三态判定。
- `run.sh` — 编译 apply_filter，跑各序列，汇总 `summary.md`。
- `simulate_fusion.py` — numpy 离线仿真（grounding/定参用），对比累积均值/滑窗/EMA 各策略；结论见 `.dev/depth-temporal-analysis/CONCLUSION.md`。

## 改动须重跑

任何对 `P100R3TemporalFilter`、运动门限默认、融合数学的改动，提交前跑本 harness + `scripts/berxel-host-test.sh` 确认无退化。
后续接入真机 burst 采集后，应补一组"手持轻微抖动"序列验证运动门限不过度拖影。
