# 深度时域降噪：滑窗均值 N=8 把 P100R3 直出深度抖动 38→10mm（2026-05-29）

## 结论

P100R3 走路线 A（设备直出 metric 深度）后，量测/复现性的**头号敌人是逐像素相邻帧抖动 ~38mm**，
不是补洞。原因：要拿稠密深度(valid≈1.0)必须关设备 `temporal_denoise`，代价就是不降噪的高时域噪声
（远超 ≤1%@1-2m 规格 ~10-20mm）。`process_p100r3_depth_frame` 只补洞，对 raw 有效像素零降噪。

`P100R3TemporalFilter`（portable 层，host+Android 共用）用**有界滑窗均值 N=8 + 噪声底缩放运动门限**
在 raw valid 像素上融合：harness 实测 vendor-dense **38.25→10.25mm（3.73×）**、bias 0.51mm（零系统偏移）、
密度 0.9983→0.9984（不退化）。

## ★ 运动门限必须【自适应噪声底】（2026-05-29 真相机验证订正）

固定门限不泛化：真硬件实测同一相机不同场景/距离/SDK 噪声底差很多（vendor SDK 干净 ~38mm、
host SDK live ~63mm、host-default ~68mm）。固定 60mm 在 vendor 上 3.7× 但在 live 63mm 场景
**退化成 1.01×（噪声>门限→每帧 reset→透传，活的 EMA 陷阱）**。

正解：门限【自适应】= `max(绝对底45mm, k × 噪声底估计, percent×深度)`。噪声底每帧用
`median(|cur-est|)`（median 抗飞点/运动尾）EMA 估，**k=2.0**（门限=2×噪声，标准运动/离群裕度）。
真硬件验证：归一化后 vendor(38mm) 与 live(63mm) 在同一 k 上增益几乎相同 → 一套默认配置三场景全 ~4.1×
（vendor 4.08×、host-default 3.18×、live 64.5→15.75mm/4.10×），零偏移、密度不掉。**别再硬编门限**。

## Why

- 逐像素噪声近零均值（空间平均后 ROI median 抖动只 ~12mm）→ 时域平均按 ~1/√N 压噪，N=8 边际收益已饱和（N=16 几乎不再降）。
- **运动门限必须 ≥ 噪声底**，否则退化成"每帧 reset"的 EMA 陷阱：噪声>阈值时每帧被判运动→清窗→等于透传。
  噪声底随场景变，故门限不能固定，必须按实测噪声自适应（见上）。
- 均值滤波器**不移动每像素时间均值** → 零系统偏移；之前误判 9.57mm "bias" 是指标定义错（把 `|融合尾段均值−全序列均值|` 的估计残余噪声当 bias），应改用有符号差均值。
- 在 raw valid 上融合（量测真值），融合后再可选补洞；processed/补洞仍只作 VIN/分割/弱置信，不替代量测。多视角扫描"每角度握定拍 burst"是融合理想场景。

## 真硬件验证（2026-05-29）

Linux 服务器直连相机（master 0603 / companion 3558），`scripts/berxel-host-probe.sh --session-api --depth`
采 live 序列。时域降噪默认参数 64.5→15.75mm（4.10×）；飞点剔除 removal 0.05%、检测像素局部梯度中位
968mm vs 全图 29mm（精准命中真断崖、不过杀）。算法在真相机数据上验证通过（Android 端 PollDepthMm
live 路径仍待设备视觉确认，但 portable 层同源已硬件验证）。

## How to apply

- 改 `P100R3TemporalFilter`、运动门限默认或融合数学，提交前跑 `scripts/berxel-host-test.sh`（7/7）+ `./dev.sh harness depth_temporal_quality`（门序列 vendor-dense 须 OK）。
- 服务器直连相机可直接 live 验证：`scripts/berxel-host-probe.sh --session-api --depth --save-depth-frames N --out-dir DIR` 采序列，再套 `tests/harness/depth_temporal_quality/bin/apply_filter` + analyze。
- 新做时域/运动相关滤波，先在 `tests/harness/depth_temporal_quality/simulate_fusion.py` 上用真实序列 grounding，**别拍脑袋取小运动阈值**。
- Android JNI 默认开（`startDualNative(depthTemporal=true)` → cfg[13]≥0）；A/B 关掉传 false。真机验证见 TODO M1.6.15。
- 参数终值与判定阈值随相机/距离可调，真理源是 harness 实测，不是代码默认值。

## 相关
- [[finding_p100r3_depth_ir_interleaved_2026-05-29]] — companion 直出真深度（本滤波器的输入来源）。
- [[finding_multiview_rgbd_pivot_2026-05-07]] — 路线 A 多视角 RGBD；burst 融合是本滤波器主场景。
- grounding 与定参证据：`.dev/depth-temporal-analysis/CONCLUSION.md`、`tests/harness/depth_temporal_quality/README.md`。
