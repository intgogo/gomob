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

## 复现 / 证据

`.dev/depth-4b-analysis/`：30 帧 dump（DUMP 按钮 → `dual_raw_NN.bin`，每 513280B）+ `analyze.py`。节奏 `ABAABAAAABABABBAABAABABAABABAB`。截图对比 `.dev/screenshots/xiaomi-depth-alt.png`（修前散斑）vs `xiaomi-depth-fixed.png`（修后干净）。设备：小米 2510DRK44C 裸 OTG。
