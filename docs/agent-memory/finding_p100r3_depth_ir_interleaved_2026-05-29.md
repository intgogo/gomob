# P100R3 companion 0x82 交织真深度帧 + IR/phase 帧（订正 2026-05-28 "只推 IR raw"）

## 结论

P100R3 companion（0x3558）UVC 流 ep=0x82，**在应用 dense depth controls（AE=1, confidence=3, temporal_denoise=0, spatial_denoise=0）后**，在同一条流上**不规则交织两类 640×401 16-bit 帧**：

- **真深度帧（Type A，~60%）**：低字节连续（256 个 distinct）、空间平滑（水平相邻中位差 ~8 raw ≈ 1mm）。值 = 13I.3D 定点，`raw/8.0 = mm`。实测对准 0.4m 目标 → 中心 ~402mm，精确命中。
- **IR/phase 帧（Type B，~40%）**：低字节仅 ~10 个离散 phase code、高字节连续 IR 灰度、空间纹理跳变（相邻差 ~256）。即旧 2026-05-28 dump 看到的"IR raw"格式。

出现连续 BB / AAAA（非严格 ABAB），排除"单帧 = [depth][IR] 被 assembler 从中劈开"的假设 —— 是设备**真交织两类完整帧**。`depth_err=0`，非丢包所致。

## 订正了什么

旧 finding（2026-05-28，auto-memory）断言"companion 只推 IR raw、depth 由 SDK 离线重建"。**部分错**：dense depth controls 生效后 companion **直接吐真深度**（Type A），只是混着 IR/phase 帧。NATIVE_REWRITE 不必移植 SDK 结构光重建即可拿到真深度。

## ★ 确定性帧类型标记（正解，2026-05-29 晚补）

帧类型不用内容启发式猜 —— **状态行首像素 `uint16 pixel[0]` 就是帧类型标记**（30/30 dump 实测 100% 可靠，也是原厂 SDK 区分交织帧的方式）：

- `0x0600`（1536）= **真深度帧**（13I_3D, raw/8=mm）；其 row0 是真深度数据，仅 [0] 是标记。
- `0x0500`（1280）= **IR/phase 帧**；row0 除 [0] 标记外全 0，高字节是 IR 灰度、低字节 phase code。
- 对应 row400（被 active height=400 裁掉的状态行）首像素：depth=768(0x0300)、IR=512(0x0200)。

`is_real_depth_frame()` 现在直接判 `pixel[0]==0x0600`，比低字节 distinct 启发式确定、零误判。

**关键认知**：交织是此 depth 模式**设备固有**行为（实测跟 master color 开关、master XU5 keepalive 都无关），不是"取流指令漏选 depth-only"。正解是按这个状态行标记确定性分流，而非找控制关掉 IR。另：**开 master color 会让深度变稀疏**（valid 100%→~70%），是独立 tradeoff，待查。

## How to apply

- **取深度必须按帧标记分类，只用 0x0600 帧**。已实现于 `native/jni/berxel_dual_session_jni.cpp` `is_real_depth_frame()`（判 `pixel[0]==0x0600`），在 `depth_pump` 里只把深度帧存 `latest_depth_transport`、IR 帧(0x0500)存 `latest_ir_transport`（供「切 IR」）+ 计 `ir_skipped`。
- 不分类全 `raw/8` 当 depth → IR 帧渲染成 ~536mm 垃圾 → 预览精细/粗交替（用户 2026-05-29 实机报的现象）。
- Type B 是现成的 LIGHT_IR 散斑源（高字节 IR 灰度），后续要 IR 预览/结构光可直接拿，不用单独开流。
- 真深度有效率 ~0.77（含真实空洞/边界），不是 1.0；之前看到的 valid=1.0 是把 IR 垃圾误算有效。

## 6MB blob = 温补表，重建在设备 ASIC → 放弃自研结构光（2026-05-29 三平台反编译）

之前把 `<SN>_params.bin`(6MB=1280×800×6) 泛称"离线 blob / 散斑参考 + 标定"是**错的**。三平台 libBerxelUvcDriver 反汇编逐字节实证：它由 `setTemperaTureCompensationStatus` 加载，切两张 1280×800×3 逐像素温补系数表喂 `onTemperaTureCompensation`，**只修正已重建深度的温漂，不含散斑参考/基线/参考距离**。散斑→深度确在**设备 ASIC**（host 无任何块匹配/三角化代码；全库唯一 disparity 符号 `Get/SetNCCThreshold` 只经 USB 下发阈值）。字符串 `Device is enable hw temperature compensation, can not use soft temperature compensation`：设备开硬件温补时 SDK 跳过此表 → 直出 0x0600 已温补过。

**决策**：设备直出 metric 深度（0x0600），**放弃自研结构光重建**（参考散斑/基线烘进 firmware DSP，任何可导出数据都拿不到）。可导出标定只有 156B 内参（propID 0x4a）：5 块 color/ir/liteIr 内参 + depth→color R/T。深度质量改走时域降噪见 [[finding_depth_temporal_denoise_2026-05-29]]。详细反编译记录在 `native/berxel/host/docs/depth-pipeline-reverse.md`「6MB params 是温度补偿表」节。

## ★ IR 帧不进 SDK 深度链 = 独立预览流 + 可选自研精修蓝图（2026-05-30 决定性反汇编）

针对"IR 占 ~40% 带宽，厂商不可能传废数据 → IR 必然增强深度"的第一性质疑，逐函数反汇编 `libBerxelUvcDriver.so` 后**证伪"SDK 运行时用 IR 增强深度"**：

- SDK 里**确有** IR→深度精修算法：`EdgeEnhanceInfraRed(depth,ir) → inner_porcess_with_IR_thread → inner_process_with_IR → CannyEdge(IR) + region_fit(depth, IR边缘掩码)`，即"用 IR 清晰边缘约束深度的加权最小二乘区域拟合/补洞"。
- **但整套是导出却零调用者的死 API**，三重证明：① 顶层入口无 PLT JUMP_SLOT(对照深度后处理函数都有)；② 无 `lea` 装载 worker 地址(无 `std::thread` 启动)；③ `libBerxelHawk`/Common/Interface/Net 无一 import，且无 dlsym 名字串。
- 活的深度链(`processDepth*`→`removeNoise/onDenoise/onFillHole/onTemperaTureCompensation*`+`BerxelDepthOptimizer`)**全程不碰 IR**；IR 帧只在 App 显式开 IR/LIGHT_IR 流时被 `BerxelStreamImplIR→BerxelIrProcessor→上层回调`消费，否则丢弃。

**结论**：交织 IR 对"原厂深度质量"零贡献，它是**独立一等输出流**(预览/瞄准/弱光)，被多路复用在同一 ep。用户第一性质疑半对——IR 不是废数据，但厂商运行时没拿它修深度。

**How to apply**：① 深度质量不要假设 SDK 用了 IR；② 复刻厂商 `inner_process_with_IR`(CannyEdge(IR)+region_fit)做离线原型量化后**证伪"IR 边缘引导有益"**(2026-05-30，harness `depth_ir_guided`)：**0x0500 是结构光散斑帧**，Canny 检到散斑非物体边界 → IR 边缘对真边界 F1 仅 0.25(去散斑后 recall 崩、F1 更低)，远低于单帧深度边缘 0.88；留一法补洞 RMS IR 引导 328mm vs depth-only 73mm。**结论：维持 depth-only 精修(真置信+时域+空间降噪)，不接 IR 边缘引导**；也解释厂商为何把这套留作死代码。③ IR 真实价值不在边缘，而在**置信/有效性**——已续测证实(见下节 ★★：散斑局部对比度预测深度不可信 AUC 0.82)。详见 `native/berxel/host/docs/depth-pipeline-reverse.md`「交织 IR 帧不进 SDK 深度链」+ `tests/harness/depth_ir_guided/`。关联 [[finding_depth_noise_real_confidence_2026-05-29]]。

## ★★ IR 作"单帧深度置信"成立(2026-05-30,正面;边缘否、置信是)

否掉 IR 作边缘后,换 IR 作**置信/有效性**信号——**成立且有实用价值**。物理因果:结构光下散斑
**局部对比度=深度可信度**(散斑清晰=图案良好接收=深度强约束;散斑被冲淡=深度靠猜)。真值用
18 帧逐像素时域 MAD(>30mm=不可信),评估单帧 IR 特征预测 AUC:**局部对比度低 AUC 0.82**、
强度低 0.70、饱和 0.50、梯度 0.22(反相关)。对抗验证:① 控制强度(仅亮像素)对比度低 AUC 仍
0.75 而强度低塌到 0.53 → **独立于明暗**;② 中心 ROI AUC 0.85;③ **逐帧单帧** AUC mean 0.76
(0.73–0.77,12 帧极稳)。视觉 `conf_mad_mm.png`(红=不可信,背景/暗区)vs `conf_ir_contrast.png`
(黄=散斑强,前景物体)空间完全反相关。

**符号检验(2026-05-30,排除运动伪影)**:原 dump 不稳(MAD>30)像素 IR 对比度中位 **0.40** vs 好像素 **1.98** —— 不稳处对比度**更低**,方向是散斑物理(弱散斑→不可信),与"运动/边缘致不稳"(会让高对比边缘不稳)**相反**,故 AUC 0.82 非手持运动伪影。**静态噪声是真设备噪声(2026-05-30 同口径订正)**:相机恢复后 host 固定相机近目标(~0.5m),vendor SDK 复位切真 dense(99.8% valid)后,**纯静态**逐像素时域 MAD 中位 **40mm**、MAD>30mm 占 **56%**(对上 M1.6.15 vendor 38mm)。即 density-first 的弱回波像素**静态下就抖 ~40mm,非手持运动**——更坐实"弱散斑→噪"是真物理:IR 散斑对比度低的像素正是这些静态噪声大的弱回波像素。(早先一版误把 probe 卡 sparse 的 13% 强回波易像素 0.1mm 当全图静态噪声,已订正。)**host 重验已闭环,无需手机(见下节 ★★★)**;早先记的"host depth 全 0x0600 不交织 = 异常"**已订正**:那是 host 厂商 SDK 把 depth/light-IR 解复用成两条独立流的**正常**行为,不是异常。

**结论 = 你第一性直觉的正解**:40% 的 IR 带宽对深度**有价值,但价值在置信不在边缘/几何**。IR 给
**零延迟单帧置信图**,补足时域稳定性置信([[finding_depth_noise_real_confidence_2026-05-29]] /
M1.6.17,需积累窗口+静态场景)在首帧/运动场景的短板。**How to apply**:在 portable 深度管线加
IR 散斑局部对比度→单帧置信通道,与时域置信融合(取小/加权),IR 只当权重不碰几何,属 depth-only
精修内的增强。harness `tests/harness/depth_ir_guided/confidence_probe.py`。**已实现(2026-05-30)**:portable `p100r3_ir_speckle_confidence`(local-std/帧中值归一化,曝光自适应)+ `P100R3TemporalFilter::set_prior_confidence` + push `conf=min(时域,IR)`;JNI IR 帧 set/深度帧融合;host 15 PASS、双 ABI 编译过。待真机 poll conf live 验证。

**单帧误差 + 掩码恢复实测(2026-05-30,对比 0.5% 官方标称)**:vendor 正常模式(denoise 开=官方标称模式)单帧 1σ **0.03%** 但仅 11% 密度;density-first 稠密(100%)单帧 vs 真值中位 **0.25%~8.9%**(场景相关,弱像素拉高)、≤0.5% 达标 28~57%。**IR 散斑置信掩码恢复(mask_recovery.py)**:conf≥80 保留 **49% 密度**,单帧误差中位降到 **0.05%(=官方稀疏质量)**、≤0.5% 达标 57%→78%。即 0.5% 标称对的是单帧高置信像素;density-first 弱像素单帧 ~9%,**置信掩码在保 ~50% 密度(比 vendor 11% 稀疏稠 4-5×)下把保留像素拉回标称精度** → 验证 density-first+置信加权 > 稀疏干净(p90 尾 ~15% 仍在,叠加时域滤波更佳)。

## ★★★ host 重验:无需手机,服务器上独立复现 IR 置信(2026-05-30)

之前 IR 置信只在 Android DUMP 交织流验过。现确认**这台 Linux host 用厂商 SDK 直接能拿 depth+light-IR**,在服务器上复跑置信链路闭环,不依赖手机。

**host 流形态(现场实测 BerxelSDK-Linux V2.0.190)**:depth(type 2)、light-IR(type 20)、纯 IR(type 4)是**三条独立 SDK 流**,SDK 内部把 ep=0x82 交织流解复用。Linux SDK 只支持 color+depth 的 MIX(`HawkMixColorDepth`),**depth+light-IR 并发实测零帧**(MIX 双开 13s 0 帧)→ 二者不能并发(light-IR 是结构光原始 IR,depth 由它在器件内算出)。纯 IR 流(type 4)host 实测 rc=-11 不出帧;**light-IR 流(`--light-ir`)正常出 640×400/1280×800 pix=3 纯 10bit IR**。

**因为同传感器逐像素对齐 → 顺序采集即可**:`tests/harness/depth_ir_guided/host_capture.cpp` 单设备会话 SINGULAR 模式先抓 depth(density-first:`setDepthConfidence(3)+setTemporalDenoiseStatus(false)+setSpatialDenoiseStatus(false)`,稠密 99.8%)再抓 light-IR,各 N 帧;`host_confidence.py` 复刻 C++ `p100r3_ir_speckle_confidence` 映射做分析。静态近物场景,置信要的是「散斑对比度 vs 深度时域 MAD」空间相关,顺序采集成立。

**结果(640×400 density-first,静态 ~38cm,两次运行稳定)**:稠密 99.8%、时域稳健 std 中位 ~59mm、61% 不可信 → 独立复现 density-first 真实器件噪声。① IR 置信 **AUC 0.72–0.73**,符号校验强成立(不可信像素散斑对比度 ~0.66 vs 可信 ~2.3)。② 掩码恢复 **conf≥160→保留 30% 密度,单帧误差中位 7.9%→0.32%**(≤0.5% 达标 29%→54%)。**诚实差异**:host 全裸 density-first 比 Android DUMP 更噪(单帧中位 ~8% vs 0.25%),需更高阈值(≥160 vs ≥80),但结论一致——**两条独立路径(Android 交织 / host 顺序)互证 IR 置信成立**。

**How to apply**:IR 置信迭代不必等手机,`bash tests/harness/depth_ir_guided/run.sh` [4/4] 步自动编译+采集+分析(相机插本机 + SDK 在 `.dev/berxel-sdk-extract/`)。host_capture 也是 host 侧拿 density-first 稠密深度 + 纯 IR 散斑的通用入口。

## 复现 / 证据

`.dev/depth-4b-analysis/`：30 帧 dump（DUMP 按钮 → `dual_raw_NN.bin`，每 513280B）+ `analyze.py`。节奏 `ABAABAAAABABABBAABAABABAABABAB`。截图对比 `.dev/screenshots/xiaomi-depth-alt.png`（修前散斑）vs `xiaomi-depth-fixed.png`（修后干净）。设备：小米 2510DRK44C 裸 OTG。
