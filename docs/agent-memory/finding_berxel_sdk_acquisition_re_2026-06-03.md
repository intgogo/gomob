# 官方 Berxel Android SDK 取数据可逆向 + 设备协商深度帧 1026584≠我方 513280（2026-06-03）

## 事实

**1) 官方 SDK 未混淆，静态逆向可行。** `BerxelSDK-Android-9.9.190/lib/arm64-v8a/` 下 `libBerxelUvcDriver.so`(3.1MB,4208 dyn syms)/`libBerxelHawk.so`(1096) 虽 stripped 但**导出符号是完整 C++ 类名方法名**，`nm -DC` + `llvm-objdump -d --start/--stop-address` 即可重建。关键类（地址在 UvcDriver.so）：
- 取数/帧组装核心：`BerxelStreamImpl::addData(uchar*,uint)` @0x1af1d8（对标我方 `UvcRawFrameAssembler::push_packet`）、`allocOneFrame`/`submitFrame`/`newFrame`。
- 帧模式/完成：`BerxelStreamImplDepth::initFrameModes`@0x1b0160 / `initFrame` / `processFrame`@0x1b00b4。
- 设备/协议（我方设备走 **Sonix** 系）：`BerxelDeviceSonix`、`BerxelHostProtocolSonix::berxelProtocolOpenStream`@0x19f81c。
- 后处理：`BerxelDepthProcessor::processDepth`@0xad848 → `BerxelDepthAlgorithm`(onDenoise/onFillHoleInpaintColor/onPlaneFitting/onTemperaTureCompensation)。

**2) 设备亲口协商的深度帧长 = 1026584，我方硬编码 513280 不符。** 真机 2510DRK44C 的 commit 日志（`negotiate_uvc_stream` cpp:2012）：
- depth `frameSize=1026584` = **2 × 513292**，513292 = 640×401×2(=513280) **+ 12**。
- color `frameSize=512000` = 640×400×2，干净无加料。
- 我方 `berxel_dual_session_jni.cpp:1072` 写死 `depth_frame_size=513280`，且 `:1004-1008` 协商出的 `depth_neg.max_video_frame_size` 被**丢弃**。

**3) 逆向证实/证伪（用我方真机实证校准反汇编）：**
- ✅ 官方就是靠**状态行 pixel[0] marker 0x0600/0x0500** 分流 depth/IR —— 与我方同法，marker 路线正确，别改成 timestamp。
- ✅ 401→400、状态行在**末行**、raw/8=mm —— 与我方一致。
- ❌ **不要加软温补**：SDK 字符串 `Device is enable hw temperature compensation, can not use soft temperature compensation` 明示硬件温补已开、软温补跳过；0x0600 是 ASIC 直出已温补 metric 深度。反汇编 agent "缺温补"是伪需求。
- ❌ 48→100 async transfer 非必需（我方解耦架构实测 0 错误）。

## Why

我方按 513280 盲切，而设备真帧 513292（+12B header）、协商 maxVideoFrameSize=1026584（疑似 **[depth 面|IR 面] 拼成一个 UVC 传输帧**）。盲切把双面帧从中间劈开 → marker 错位 → STRICT 模式 bad_marker 73% + ir_skipped 飙 + 帧率 3↔16fps 抖。统一解释了所有不稳症状，且对得上官方"知道真实帧长 + 按 marker 拆面"。parse_uvc_payload_header=true（2026-06-03 修）剥掉 12B header 后单帧模式能干净（bad_marker=0/16fps），但 IR 交织态仍会被盲切打乱。

## How to apply

- 改取数前**先 DUMP 验证** 1026584 的真实构成（是否 [depth|IR]、12B header 长相、marker 偏移），别只信反汇编。
- 终态方向：用协商/真实帧长组帧 → 按 marker 0x0600/0x0500 拆 depth/IR 面（对齐官方 addData+processFrame），废弃固定 513280 盲切 + 固定 offset 扫描窗口。
- 删/隔离死路径 `BerxelFrameAssembler.kt`（byteCount/shortRead 盲切，不在 MIX 生产链）。
- 验证：真机 `RUN` 行 `depth_bad_marker/depth_seq<5%`、`depth_seq` 稳定递增至 ~45/s、`valid` 合理、IR 切走不污染 depth。
- 关联 [[finding_p100r3_depth_ir_interleaved_2026-05-29]]（IR 分流/6MB=温补表）、[[finding_p100r3_mix_color_depth_2026-06-02]]（MIX 配方）。
