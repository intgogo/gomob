# TODO

> 单一真理源。**不另起 `docs/plans/`**。完成项从本文件删除，历史记录看 git log。
>
> 写作纪律：`docs/agent-memory/feedback_plan_writing_quality.md`。每条任务必须有明确验收与文档路径。

## 当前主线

1. **M1 iHawk 帧链路**：先把 Color/Depth 字节流、内参、预览跑实。
2. **M3 多视角 RGBD 重建**：阶段 1 先做端侧采集 + 云端融合闭环。
3. **M4 VIN 数码拓印**：复用同一 RGBD 采集链路，接 cv-engine `vin_pipeline`。
4. **M5 实时消息与第一视角协作**：消息控制面自研，视频 / 直播媒体面接自托管 LiveKit。

## M5 实时消息与第一视角协作

> 控制面：gomob 自研 WebSocket + REST + PostgreSQL，负责消息顺序、会话、未读、邀请、审计。
> 媒体面：自托管 LiveKit + coturn + Egress，负责视频通话、第一视角直播、录制。
> docs: `docs/architecture/09-realtime-message-live.md` / `docs/architecture/09-realtime-message-live-implementation.md`

| ID | 任务 | 验收 | 文档 |
|----|------|------|------|
| M5.4 | LiveKit 媒体控制面：接自托管 LiveKit 配置，服务端实现 `POST /v1/media/rooms`、`POST /v1/media/rooms/{id}/token`、`POST /v1/media/rooms/{id}/end`、`POST /v1/livekit/webhook`；`core:media` 接 LiveKit Android SDK，封装 `MediaRoomClient`。 | `tests/harness/livekit_room_lifecycle` 通过：创建 room、签发 publisher/viewer token、两个测试客户端加入、断开后 room status 变 `ended`；服务端拒绝非成员拿 token。 | `docs/architecture/09-realtime-message-live-implementation.md` §5 |
| M5.5 | 1:1 视频通话：`media.invite` 替代旧 P2P SDP 语义；单聊视频按钮创建 call room；被叫前台在线收到来电弹层；通话页全屏远端视频 + 角落本地预览 + 静音/摄像头/挂断；结束后写 `call_logs` 并追加 `kind=video_call` 消息。 | `tests/harness/livekit_call_quality` 通过：同局域网首帧 ≤ 2s，挂断后 conversation 出现 `[视频通话]` 消息；uiautomator / instrumentation 确认通话页远端视频容器、角落本地预览、静音 / 摄像头 / 挂断按钮存在且可点击。 | `docs/architecture/09-realtime-message-live-implementation.md` §6 |
| M5.6 | 第一视角直播：查验员创建 `live_session` 并发布后摄像头；协作页 `GET /v1/live-sessions?status=live` 展示在线视角；观看页订阅真实 video track；介入语音、标记预警、截图存档写 `live_annotations` 并推 WebSocket。 | `tests/harness/first_person_live_quality` 通过：viewer 收到非空视频帧，P95 延迟 ≤ 1500ms，标预警后 publisher 收到 `live.annotation`；uiautomator / logcat 确认协作页在线视角可进入，观看页已订阅真实 video track。 | `docs/architecture/09-realtime-message-live-implementation.md` §7 |
| M5.7 | 直播录制与回放：服务端触发 LiveKit Egress；webhook complete 后把 MP4/HLS 登记到 asset，写 `live_recordings.status='complete'`，推 `recording.ready`；协作页“近期录像”拉真实数据并可播放。 | `tests/harness/live_recording_egress` 通过：10s 测试直播在结束后 30s 内产出可播放 MP4，asset sha256 与对象存储一致。 | `docs/architecture/09-realtime-message-live-implementation.md` §8 |
| M5.8 | 实时协作观测闭环：所有关键日志带 `room_id/live_session_id/conversation_id/client_msg_id/server_seq/media_rtt_ms/packet_loss_pct/first_frame_ms`；harness `analyze.py` 输出正常 / 警告 / 异常 + 原因；更新 registry 中新增 Android 模块和服务依赖。 | `tests/harness/ws_message_order`、`realtime_message_sync`、`device_realtime_interaction`、`livekit_room_lifecycle`、`first_person_live_quality`、`live_recording_egress` 全部输出可判定结论；`docs/architecture/registry/modules.yaml` / `dependencies.yaml` / server registry 与实际模块一致。 | `docs/architecture/09-realtime-message-live-implementation.md` §9 |
| M5.9 | 语音消息转文字：采用自托管 FireRedASR2-AED + VAD/LID/Punc；服务端 `message_transcripts` 队列、ASR worker、`msg.transcript.updated` 推送、App 端气泡状态与重试入口落地。 | 无模型环境：发送语音后 payload 含 `transcript_status=pending`，`POST /v1/messages/{id}/transcript/retry` 返回 200；有模型环境：30 秒内语音 <= 2 秒返回，普通短语音平均 CER <= 5%，业务关键词召回率 >= 98%。 | `docs/architecture/09-realtime-message-live-implementation.md` §10 |

## M1 iHawk 接入

> 单设备路线：iHawk 自身 Color + Depth 双流，不接手机主摄。
> docs: `docs/architecture/01-depth-camera-integration.md`
>
> **当前 blocker**：M1.6 — Berxel Android SDK 内嵌的 libuvc-0.0.7 + 自家 libusb stack 在 4/5 测试机上 single-stream DEPTH 和 DUAL 都跑不通；只有 2510DRK44C 这个 BSP 特例稳。M1.2-M1.4 都要等 M1.6 完成才能在主流测试机上做完整验收（2510DRK44C 上可先并行推 M1.2/M1.3）。

| ID | 任务 | 验收 | 文档 |
|----|------|------|------|
| M1.2 | 暴露 iHawk Color/Depth 帧流：reader 线程把 `Frame.data` 拷成 `DirectByteBuffer`，Color YUYV 转 BGR888，按 `frameIndex` 配成 `RgbdFramePair` 并发到 `SharedFlow` 或 `Channel`。 | 真机连续 60s 输出 Color+Depth pair；pair 保留 frameIndex、timestamp、width、height、format；日志中 fps 稳定且无明显 GC 抖动。 | `docs/architecture/01-depth-camera-integration.md` §3.3 |
| M1.3 | 读取 iHawk 内参与注册参数：接 `getCameraIntriscParams` / `getDeviceIntriscParams`，打开并实测 `setRegistrationEnable`。 | `./dev.sh harness calibration_smoke` 通过；棋盘格 30/50/100cm 三组，深度投 color 边缘误差 ≤ 2 px。 | `tests/harness/calibration_smoke/` |
| M1.4 | Compose Color/Depth 实时预览：Color 贴 Surface，Depth 伪彩 Bitmap，HUD 显示 frameIndex / fps / timestamp / sync delta。 | `./dev.sh run` 后通过 logcat / uiautomator / instrumentation 确认 Color、Depth 预览节点存在，HUD 的 frameIndex / fps / timestamp / sync delta 持续更新。 | `feature/scan3d/Scan3dScreen.kt` |

### M1.6 Android UVC stack 重写（A 方案 path 2）

> 目标：用现代 libusb-1.0 + pupil-labs/libuvc 在 Android NDK 重写 Berxel Android SDK 的 native USB 层，替换 libuvc-0.0.7 + 自家 libusb stack。
> docs: `docs/architecture/10-android-uvc-stack-rewrite.md`
> 替换边界：53 个 JNI 入口函数（`Java_com_berxel_berxelInterface_api_admitmanager_BerxelHawkFunction_*`），Java jar 不动。
> **2026-05-26 Berxel 明确回复无支持精力** → 拿源码加速通道关闭，只走自 port 路径，工作量 1-2 个月。

| ID | 任务 | 验收 | 文档 |
|----|------|------|------|
| ~~M1.6.0~~ | ~~联系 Berxel 索取 Linux SDK 源码~~ — **关闭 2026-05-26**：Berxel 回复无支持精力。只能靠自 port 突破。 | — | — |
| ~~M1.6.1~~ | ~~反编译 libBerxelUvcDriver.so 抽 Sonix XU cmd table~~ ✅ **核心架构完成 2026-05-26**：用 objdump 反汇编 `XU_Set_Cur / XU_Get_Cur / XU_Asic_Write / XU_I2C_Write` 链路，识别 Sonix 双轨协议（selector 0x01 ASIC reg I/O + selector 0x19/0x1e batch cmd + UVC standard probe-commit），硬编码 ASIC reg 0x10D0/0x10D8/0x10D9。NDK port 5 个 C 函数入口已定。具体 batch cmd payload schema 留 Phase 1 实施按需补完。报告 `.dev/m1.6.1-protocol-reverse/sonix-cmd-table.md`。 | ✅ | `.dev/m1.6.1-protocol-reverse/sonix-cmd-table.md` |
| ~~M1.6.2~~ | ~~Linux PC USB trace 抓 startStreams(DEPTH) 完整出包~~ ✅ **完成 2026-05-26**：bus7-master.pcap + bus8-companion.pcap 含完整 enumerate→XU vendor cmd→UVC probe-commit→BULK 序列；关键 Berxel Sonix XU 命令 wValue=0x1900/0x1e00 wIndex=768(Unit3/Interface0) 已识别。报告 `.dev/m1.6.2-usb-trace/report.md`。 | ✅ | `.dev/m1.6.2-usb-trace/report.md` |
| ~~M1.6.3~~ | ~~Phase 0 决定性预实验 — Linux PC 跑 Linux SDK sample 拿 ground truth~~ ✅ **完成 2026-05-26**：三组全绿（single-stream DEPTH 56ms 首帧 / DUAL 640 334ms / DUAL 1280×800@45 404ms + 84+ 秒满载稳定）。报告 `.dev/m1.6.3-pc-baseline/report.md`。彻底证伪 firmware/host BSP/mixed-bus/带宽 等假设，A 方案 path 2 锁定。 | ✅ | `.dev/m1.6.3-pc-baseline/report.md` |
| ~~M1.6.4~~ | ~~Android NDK 交叉编译主线 libusb-1.0 + libuvc~~ ✅ **完成 2026-05-26**：libusb-1.0.27（patched SONAME=libusb-1.0.so）+ libuvc 主线 fork，arm64-v8a + armeabi-v7a 4 个 .so 入 `third_party/libusb-android/` + `third_party/libuvc-android/`，含 README + 重建命令。vivo PD2324 真机 smoke test 走通 `libusb_set_option + init + get_version + exit` 全链路。 | ✅ | `third_party/libusb-android/README.md`, `third_party/libuvc-android/README.md` |
| ~~M1.6.5~~ | ~~Sonix XU 协议复现层~~ ✅ **完成 2026-05-26**：`native/berxel/{include,src}/gomob_berxel_protocol_sonix.{h,cpp}` 实现 5 个核心入口（xu_set_cur / xu_get_cur / asic_write / asic_read / batch_cmd），`tests/native_host/berxel_sonix_protocol_test.cpp` host test 6/6 PASS，字节序列（bmRequestType/bRequest/wValue/wIndex/wLength/data）100% 跟 USB trace ground truth 一致。 | ✅ | `tests/native_host/berxel_sonix_protocol_test.cpp` |
| M1.6.6 | Phase 2 — 实现核心 11 个 JNI 入口 + master XU 5 keepalive 协同（companion firmware 硬约束）；通过 `libusb_wrap_sys_device` + kernel HID/UVC driver detach 接管 Android UsbDeviceConnection.fd。当前进度（2026-05-27）：(1) JNI 全 11 入口完成；(2) `BerxelNativeStack.kt` 包装类入 `core:native-bridge`；(3) DI feature flag `BERXEL_STACK_BACKEND=SDK\|NATIVE_REWRITE` + `BerxelStackBackendOverride` runtime 覆盖 + 注入到 `BerxelService.start()` 让 backend 一翻就改路径；(4) `tests/harness/depth_singlestream/` harness 完整跑通。**真机 sweep 关键发现**（vivo PD2324 + kaMs ∈ {5,10,20,30,50,100,200,500} 全测）：master XU 5 keepalive 间隔跟 BULK 拿到字节**不相关**；所有组合都在 ~100ms 内死，最多拿 24×16384=393KB（<1 帧 513KB）。**结论**：M1.6.6 "≥100 帧无 null" 在 vivo PD2324 **不可达**，是 P100R3 firmware × vivo OTG BSP 的硬约束，详 `.dev/m1.6.6-jni-stack/findings.md` 2026-05-27 20:57 段。**剩余**：(c) 在 2510DRK44C（USB2 OTG 唯一通过 BSP）回归验收 NATIVE_REWRITE 路径 ≥10s 稳定流；(d) 评估 async transfer pool + 改 dwMaxPayloadTransferSize 是否在 vivo 上能突破 100ms 死线。 | 已达：feature flag 切换路径 + harness 完整链路 + 真机量化 vivo 死线。待补：2510DRK44C 上 NATIVE_REWRITE 拿 ≥100 帧无 null。 | `feature:scan3d` 集成；`.dev/m1.6.6-jni-stack/findings.md`；`core/native-bridge/.../berxel/BerxelNativeStack.kt`、`BerxelStackBackend.kt` |
| M1.6.7 | Phase 3 — 补完其余 43 个 JNI（exposure / gain / enable flag / 出厂参数读取等）；解码 P100R3 出厂参数文件 (params.bin / params100Q.bin / params137.bin)。 | 全部 53 JNI 跑通 Berxel SDK 原有 sample 应用所需路径；`harness scan_quality` 跟 2510DRK44C 上原 SDK 输出一致。 | — |
| M1.6.8 | Phase 4 — 把 `third_party/berxel-android/` 切到新实现：替换 libBerxelSdk.jni.so，Java jar 保留；ProGuard rules 更新；core/native-bridge 兼容层不动。 | `./dev.sh build` 出 apk；安装到 5 台测试机能 `BerxelService.start()` 成功；不影响 gomob 上层调用。 | `docs/architecture/01-depth-camera-integration.md` §3.5（更新） |
| M1.6.9 | Phase 5 — 5 台测试机完整回归矩阵：COLOR_ONLY / DEPTH_ONLY / DUAL (640×400 + 1280×800) 三种 stream type × 5 设备 = 15 组测试；`./dev.sh harness scan_quality` 通过。**强制接带 PD 供电 hub**（裸 OTG 直插不再要求跑通——见 [[finding_powered_hub_unblocks_vivo_dual_stream_2026-05-27]]）。 | 报告 `.dev/m1.6.9-regression/<deviceModel>.md` × 5；至少 4/5 设备 DUAL 640 拿稳 60s；DUAL 1280 在 USB3 OTG 机器上拿稳；订正 [[finding-p100r3-dual-endpoint-host-kill-2026-05-18]] 终结论。 | `tests/harness/scan_quality/` |
| M1.6.10 | **vivo uvcvideo 抢 master polish**（2026-05-27 记，非阻塞，遗留技术债）：vivo + powered hub 拓扑下 cold start 时 kernel uvcvideo 太快 bind master 0x0603，Android USB filter 把它从 UsbManager.deviceList 隐掉，inline test + NativeStack 都拿不到 master fd。当前 workaround = "接相机 → 立刻进 Sonix 页 → 立刻点测试"短窗口期；超出窗口必须物理 replug。彻底修需 BerxelService 加"持开但不 stream"模式让 inline test / NativeStack 借 fd，或研究 vivo USB filter rule 看能否绕过。 | (a) cold start + Dell hub 后 master 出现在 deviceList；(b) Sonix 页 inline test 连续跑 3 次都拿 ≥ 100MB / 0 错误。 | `feature:scan3d/SonixDebugScreen.kt`、`core/native-bridge/.../BerxelService.kt` |
| M1.6.11 | Linux host 自研 P100R3 SDK depth parity：在 `native/berxel/host` 复现原厂 depth/color/light-ir 双流与控制链路，不链接 Berxel `.so`。当前进度：确认 `DEPTH` 是 `13I_3D` fixed-point (`raw/8`) 而非散斑；`LIGHT_IR` 散斑流已复现到 `1280x800`、非零 100%、max 1023；新增 MJPEG/RAW assembler 和双流 demo。2026-05-28 关键修正：dense depth 不是 CPU `fillHole` 从 sparse 补出来，而是设备侧 `temporal_denoise=0` / `spatial_denoise=0` 触发；host SDK 默认已下发 `AE=1, confidence=3, temporal=0, spatial=0`，Qt6 demo 改为直接显示 active raw depth，processed depth 只作为可选后处理和 confidence mask 实验。新增 `tests/harness/berxel_depth_parity`：每轮先显式 sparse/dense 复位，采 vendor/host 多帧并输出相机自身噪声基线；2026-05-28 实机 PASS `.dev/berxel_depth_parity/`，host dense `1.0000`、vendor dense `0.9984`、host `--no-depth-controls` `0.1559`、median depth delta `1.19mm`、host-vendor median abs diff `37.125mm`，落在相机自身噪声底 `36.625..37.125mm` 内。harness 已自包含 vendor oracle 源码，并新增 `SCENE_*` 元数据、中心 ROI 稳定性指标和 `sweep.sh` 多材质/角度汇总。dual 同步 API 已补 `RgbdPairingStats`：stats 直接暴露 pair 数、drop/queue、last/mean/max host delta 和最后配对帧号；`pairs.csv` 写每对 start/end/midpoint、UVC PTS/SCR、FID 与 payload，`analyze_frame_csv.py` 输出 frame/pair 序号连续性。实机短测 `.dev/berxel-host-sdk/session-api-dual-sync-stats-20260528-232236/`：COLOR 39、DEPTH 82、pair 39，P95 delta `9.24ms`，退出 `stopped/duration_reached`。 | 继续补静态标靶真实采样和 Android 可移植 API；processed depth 仅作为 VIN/分割实验，不得替代 raw 量测；任何 depth 控制/assembler/pairer 改动都跑 `scripts/berxel-host-test.sh` 和 `./dev.sh harness berxel_depth_parity`。 | `native/berxel/host/README.md` / `native/berxel/host/docs/xu-command-map.md` / `native/berxel/host/docs/depth-pipeline-reverse.md` / `tests/harness/berxel_depth_parity/` |
| M1.6.12 | **Android 迁移 Step 1：portable 层抽离 + host bug 修复** ✅ **完成 2026-05-29**：把不依赖 libusb 的纯逻辑（assembler/pairer/depth&light-ir 解码/payload 生成/协议编排）抽到 `native/berxel/portable/`；引入 `IUvcDevice` 接口（A 方案，用户拍板），编排函数改吃 `IUvcDevice&`，host `UsbDevice : public IUvcDevice`；内部 `emit`→`log_line`（避 Qt 宏冲突）；修 oversized 帧元数据 stale + opening 期并发 stop 竞态两个 host bug。构建点改为 host SDK/probe/demo 都编 portable.cpp，纯逻辑 5 个单测改为 **只链 portable.cpp 不链 libusb**（编译期硬证明零依赖）。实机验收：host 单测 7/7、Qt demo build exit 0、depth parity status=OK 不退化。审核报告 `.dev/berxel-host-sdk/migration-audit-2026-05-29.md`。 | ✅ 已达：portable 层 libusb-free 编译+链接通过；三条验证全绿。 | `native/berxel/portable/README.md` / `docs/agent-memory/handoff_berxel_host_sdk_2026-05-29.md` |
| M1.6.13 | **Android 迁移 Step 2-3 代码完成 2026-05-29**：(Step2) portable.cpp 加进 `native/CMakeLists.txt`，跨 arm64/armv7 编进 `gomob_native.so`（NDK clang 零警告 + APK build SUCCESSFUL）。(Step3) 新增 `native/jni/berxel_dual_session_jni.cpp`：`AndroidUvcDevice : IUvcDevice`（libusb_wrap_sys_device 包 fd）+ 双会话驱动原样 mirror host 12 步序列（master XU5 replay→keepalive→companion XU3 replay→dense depth controls→UVC commit depth(0x82)/color(0x81)→bulk pump→RgbdFramePairer），全走 portable 层；JNI `berxelDualStart/Stop/Stats/PollDepthMm` + Kotlin `BerxelNativeStack.startDualNative` + `SonixDebugScreen` 按钮/`HARNESS_DUAL` broadcast。**实机验证（关键修正）**：先在 vivo 触发链路通但被 M1.6.10 master 不在 deviceList + port-disable 挡；**2026-05-29 在小米 2510DRK44C（双流唯一稳）实机跑通 depth-only**：完整 host 12 步序列在 Android 执行（master XU5 replay 246 + companion XU3 replay 7 + dense controls AE=1/conf=3/temporal=0/spatial=0 + UVC commit depth 640x401@45），bulk pump + portable assembler 出 **valid=1.000 稠密 depth、center_median≈423mm（raw/8.0）、depth_err=0、~26-40fps、keepalive ka=240**（对准 ~42cm 目标；对空旷处 valid=0 是场景非 bug）。**P0「depth 当 IR」实机修复确认**。修了一个真 bug：CMake `file(GLOB)` 不重 glob 导致新加 `berxel_dual_session_jni.cpp` 漏编（UnsatisfiedLinkError），已给所有 GLOB 加 `CONFIGURE_DEPENDS`。证据 `.dev/berxel-split/xiaomi-dual-success-2026-05-29.md`。 | ✅ Android 经 portable 链路出真稠密 depth，1:1 复现 host dense 行为。**待**：DUAL(depth+color) 验证；HyperOS 锁 input 注入，可加 manifest 常驻接收器实现零点屏 adb 驱动。 | `native/jni/berxel_dual_session_jni.cpp` / `.../BerxelNativeStack.kt` / `.dev/berxel-split/xiaomi-dual-success-2026-05-29.md` |
| M1.6.14 | **Android 迁移 Step 4（未开始）**：Kotlin 切到 `startDualNative` host 验证链路，删 `BerxelFrameAssembler.kt`/`BerxelMjpegAssembler.kt`/native-bridge 包内 `DepthFrame.kt` 重复实现，修 P0「NATIVE_REWRITE 把 depth 当 IR」（`BerxelService.kt:507-566`）、契约 `DepthFrame.data` 真为 16bit mm（去 jni_bridge.cpp:227 越界读 + fx/fy=0 除零），JNI 删异步 BULK pool 死代码；IR 预览改走独立 LIGHT_IR 流。 | depth 真出 16bit mm；录制/重建链路用真 depth；pair stats 持续更新。 | 审核报告 `.dev/berxel-host-sdk/migration-audit-2026-05-29.md` 第 2/4 节 |
| M1.6.15 | **深度时域降噪（路线 A，2026-05-29）**：设备关 temporal_denoise 换稠密(valid≈1.0)，代价是逐像素相邻帧抖动 ~38mm（远超 ≤1%@1-2m 规格），是量测/复现性头号敌人。portable 层新增 `P100R3TemporalFilter`（有界滑窗均值 N=8 + **自适应噪声门限** + temporal confidence），在 raw valid 像素上融合（量测真值），融合后再可选补洞。已完成：portable 模块 + host-test 7/7 + `depth_temporal_quality` harness + JNI 解析线程接入（cfg[13] 开关，默认开）。**真硬件验证（2026-05-29，服务器直连相机）**：固定门限不泛化（vendor 38mm/3.7× 但 live 63mm 退化 1.01× = EMA 陷阱）→ 改门限自适应 `max(45mm, k×median(|cur-est|), percent×Z)`，k=2.0；归一化后 vendor/host/live 三场景同一配置全 ~4.1×（vendor 4.08×、live 64.5→15.75mm/4.10×）、零偏移、密度不掉。**待**：Android 端 PollDepthMm live 路径设备视觉确认（portable 同源已硬件验证）。 | host-test 7/7 + harness OK + 真相机 live 序列 4.1×（已达）；Android 设备视觉确认（待）。 | `tests/harness/depth_temporal_quality/` / `.dev/depth-temporal-analysis/CONCLUSION.md` / `native/berxel/portable/gomob_berxel_portable.{h,cpp}` |
| M1.6.16 | **深度飞点剔除（路线 A，2026-05-29）**：结构光在前/背景断崖插值出的悬浮假点污染点云、是抖动 p95 主来源。4 视角设计评审（workflow）综合：grounding 证纯单帧检测过杀 24%，须 **三证合一**=时域不稳(stable_run/window_span，取自 TemporalFilter 窗口) ∧ 双侧角度超界夹心(step_max=tan(grazing)·Z/f·Δpx 物理坡度上界，半径外探抓薄带) ∧ 无共面支撑；在 **fuse 之后**做（fused 降噪后梯度更准）；保 raw 原值 + conf=0（删点由下游按 conf 跳过）；暖机只降权不删。已完成：portable `p100r3_flying_spatial_evidence` + `P100R3TemporalFilter::push(flying_mask)` 联合判定 + host-test 8/8 + `depth_flying_pixel` harness（合成 GT recall=1.0/geom_keep=1.0/纯几何 FP=0 + **vendor-dense 真实数据 removal=0.04% 不过杀**，对照单帧 24%）。**confidence 通道打通（2026-05-29）**：JNI depth_parser_loop 传 flying_mask 使飞点剔除在 live 生效（飞点 conf 置 0、fused 原值保留）+ 新增 `berxelDualPollDepthConf` poll 出逐像素 conf（修"算了从不 poll 出去"断链）+ Kotlin `dualPollDepthConf` 包装 + `DepthFrame.confidence: ByteBuffer?` 可选契约 + pull job 同帧（frameNumber 对齐守卫）填充 + `FrameRenderer.depth16ToBitmap` 按 conf==0 抹飞点。两 ABI 编译通过、conf 符号入 .so。**真硬件验证（2026-05-29 服务器直连相机 live）**：removal 0.05%、检测像素局部梯度中位 968mm vs 全图 29mm（精准命中真断崖、不过杀）。**待**：(1) Android 设备视觉确认预览飞点消失（UI 规范）；(2) **P1 下游加权**：3D 重建 `scanSessionIngest` 按 conf 加权/预剔（native 改 + A/B）；(3) 标定 blob 到位后用真 fx/fy+畸变重验角度上界；(4) 真机端侧帧预算实测。 | host-test 8/8 + `./dev.sh harness depth_flying_pixel` 全 OK + conf 通道全链路编译通过（已达）；真机预览飞点消失 + 重建按 conf 加权（待）。 | `tests/harness/depth_flying_pixel/` / `.dev/flying-pixel-analysis/{GROUNDING,SYNTHESIS}.md` / `native/berxel/portable/gomob_berxel_portable.{h,cpp}` |
| M1.6.17 | **深度"满屏噪点"根因+真置信+空间降噪（2026-05-29）**：用户盯实物报 demo 深度全是噪点。判据坐实是真深度（非 phase 误读/decode bug：低字节 256 distinct、直方图连续纵深、平滑区相邻差~1mm）但~74% 像素时域不稳（逐帧 MAD 中位 85mm，远超 ≤1%）。**根因**：(1) 密度优先（设备 temporal/spatial denoise 全关怼 valid≈1.0），(2) 设备 confidence 饱和废值（98.7% 标 255，噪声也满置信，任何 conf 掩码无效）。**解（用户选 A：真置信+保稠密）**：portable `P100R3TemporalFilter` 用窗口 `window_span` 派生稳定性置信替换废值（`confidence_from_stability`，span 大→低置信，即使窗口满）+ `apply_spatial_denoise`（median3 去脉冲→bilateral5 σr40mm 保边，fuse 后飞点前）。数据保稠密，下游按 conf 掩码/加权（契合多视角主线）。**离线真帧验证（30 帧 apply_filter）**：noise_p50 27→10.5mm、edge_keep 0.89、density 1.0；conf 不再饱和（<64 占 19%/≥200 占 24%），conf≥200 掩码后真表面干净浮现（24%，对齐离线 trust 实验）。host 测试 8 portable + sonix 6/6 全绿（flying harness 设 spatial_denoise=false 隔离）。demo 处理面板按 conf≥160 掩码出黑洞=测量级视图，编译通过。**待**：(1) master 物理恢复后真机静态采集复核确切密度（旧采集疑含手持运动，26%/85mm 可能偏悲观）；(2) Android `FrameRenderer` 改按 conf 阈值掩码（非仅 conf==0）；(3) 多视角配准/重建按 conf 加权（接 M1.6.16 P1）。 | 离线真帧 noise 27→10.5mm + 真置信梯度化 + host 全绿 + demo 编译（已达）；真机静态复核 + Android 渲染掩码 + 重建加权（待）。 | `native/berxel/portable/gomob_berxel_portable.{h,cpp}` / `.dev/denoise-proto/` / `docs/agent-memory/finding_depth_noise_real_confidence_2026-05-29.md` |
| M1.6.18 | **修 master keepalive（USB 反复挂的真凶之一，2026-05-29）**：depth-only 模式跑 ~20min master 照样挂（dmesg `7-2.1 error -71` 掉枚举），真因=XU5 keepalive 的 `set_cur` 一直 `rc=-7 TIMEOUT`（~550ms 一次全失败，errs 累计 400+，ok 恒=1），firmware 拿不到 50ms 心跳→饿死；depth 靠 companion 照常推所以表面在跑。自供电+ganged hub 物理无法软断电恢复（[[finding_p100r3_master_hang_recovery_2026-05-29]]），只能物理断电。**待**：master 物理恢复后真机调 set_cur 为何 timeout（endpoint/wValue/时序），止血这条反复挂。生产 BOM 选支持 PPPS 的带电 hub 以便远程软复位。 | 根因定位（keepalive set_cur 全超时→master 饿死，已达）；set_cur 修复（待真机）。 | `docs/agent-memory/finding_p100r3_master_hang_recovery_2026-05-29.md` |

## M1.7 内嵌相机形态验证（P0–P4）

> 方向：把 P100R3 从外接 OTG 改为焊进手机内部。**核心:内嵌真正只解决供电,并把另两个挂死从"物理拔电"降级为"GPIO 软件自愈";严禁把内嵌当挂死解药盲赌,先用最小成本验分水岭。**
> docs: `docs/architecture/11-embedded-camera-form.md`；原始四视角分析 `.dev/embedded-camera-analysis/raw-lenses.md`

| ID | 任务 | 验收 | 文档 |
|----|------|------|------|
| M1.7-P0 | **分水岭验证（最高优先,纯软件+小硬件,无需焊死）**：现外接 + GPIO 可控断电 carrier（或继电器/可控 PD-sink 模拟软断电），Linux host parity 台架。验三件事:① 关 color、RGB 走主摄后 master 还秒挂吗;② 供电稳 + XU5 心跳走实时线程（25ms 重发）后 set_cur rc=-7 是否消失、20min 饿死是否解除;③ "断电→上电→重枚举→重发 init/commit→重启 keepalive"恢复链可重入。先决:需 master 物理恢复（[[finding_p100r3_master_hang_recovery_2026-05-29]]）。 | ① color 关后 ≥30min 无 master 掉枚举;② 心跳实时化后 set_cur 连续 ≥30min 无 -71;③ 软断电后 ≥10 次重枚举+续流成功率曲线，断电时长 N 起步 2s。**这一步决定内嵌值不值**。 | `docs/architecture/11-embedded-camera-form.md` §11.5 |
| M1.7-P2 | **depth 重建（并行,独立大工程,与挂死正交）**：companion 推的是 IR-raw+phase（非成品 depth，现仅 IR 预览）。把 SDK 端结构光重建逻辑移植到 native 出真 depth;156B 出厂参数 blob 接入。**不解决则整个内嵌硬件投入归零（showstopper）。** | native 重建出真 metric depth，与 vendor SDK depth 在标靶上 median abs diff 落入相机噪声底（参 `berxel_depth_parity`）;非仅 IR。 | [[finding_p100r3_companion_pushes_ir_raw_2026-05-28]] / `docs/architecture/11-embedded-camera-form.md` §11.6 |
| M1.7-P1 | watchdog 自愈闭环（同 P0 carrier）：三路独立判活（★用 master 心跳，绝不用 companion depth 帧）;DETECT_FAIL→断电 N 秒→重枚举→续流全自动;扫描会话把"相机重连"做成第一类事件（已采集数据保留、UI 不崩）。 | harness 打点触发原因/断电时长/重枚举耗时/恢复次数;注入故障后自愈成功率 + 会话不崩。 | `docs/architecture/11-embedded-camera-form.md` §11.3 |
| M1.7-P3 | 半内嵌 carrier：深度模组+主摄装同一刚性支架，板载 USB3 hub（INDIVIDUAL+真 PPPS）+ 板级电源树，host 仍外置/手机。 | hub 单口 power-cycle 隔离度（不误触 companion）实测;companion 5G SI 达标不出新 -71;一次性外参标定可行。 | `docs/architecture/11-embedded-camera-form.md` §11.3 |
| M1.7-P4 | 完全内嵌焊死（仅 P0–P3 全绿才进）：定制 FPC/载板 + 出厂标定工装 + 热管理（NTC 过温纳入 watchdog）。 | 外参覆盖温度区间标定;跌落/热循环后外参漂移 ≤1%@1-2m。 | `docs/architecture/11-embedded-camera-form.md` §11.5/11.6 |

## M2 iHawk 标定

> 触发条件：M1.3 的 SDK 出厂参数或 `setRegistrationEnable` 实测不达标。
> docs: `docs/architecture/05-calibration-pipeline.md`

| ID | 任务 | 验收 | 文档 |
|----|------|------|------|
| M2.1 | calibration native 模块接 OpenCV 4.x，使用 `cv::aruco` / `cv::calibrateCamera` / `cv::stereoCalibrate`；同时决定复用 SDK `libopencv_java3.so` 还是自编 OpenCV 4。 | native host build 通过；Charuco 单图检测能输出角点、id、reprojection 输入数据。 | `docs/architecture/05-calibration-pipeline.md` §3.3 |
| M2.2 | 新建 `feature:calibration`，实现 Charuco 检测向导 UI，按 12 个角度采集 Color/Depth pair。 | 真机采集 12 组样本落 `.dev/calibration/<sessionId>/`；uiautomator / instrumentation 覆盖采集页与结果页关键状态。 | `docs/architecture/05-calibration-pipeline.md` §3.2 |
| M2.3 | 求解单目内参与 Color/Depth 外参，输出可序列化标定结果。 | Color reprojection ≤ 1.0 px；Depth reprojection ≤ 0.5 mm；失败样本能给出可解释原因。 | `docs/architecture/05-calibration-pipeline.md` |
| M2.4 | 本地 Room 保存 `calibrations`，以 `deviceSerial` 唯一索引复用；接服务端 device calibration 同步。 | 扫描启动前能比对本地 sha256 与服务端 latest；一致跳过，不一致拉取并更新本地。 | `core:database` / `docs/architecture/05-calibration-pipeline.md` |
| M2.5 | 建 `calibration_quality` harness，对比 SDK 出厂参数与自标定结果。 | `./dev.sh harness calibration_quality` 输出“正常 / 警告 / 异常 + 原因”。 | `tests/harness/calibration_quality/` |

## M3 多视角 RGBD 重建

> 当前权威路线：多视角 RGBD 配准 + 端云融合。
> docs: `docs/architecture/04b-multiview-rgbd-reconstruction.md`
>
> 历史 native ICP / scan session / Filament 预览沉淀只作为阶段 3 复用背景；旧 TSDF 实时主线不再扩展。

| ID | 任务 | 验收 | 文档 |
|----|------|------|------|
| M3.12 | 端侧采集模式 UI：引导用户环绕采 8-12 角度；每次拍照保存 RGB、depth、内参、时间戳；显示上一张 RGB 半透明叠加；端侧 ORB overlap < 30% 时提示重拍；中心连通块 ROI 作为初步分割兜底。 | `.dev/scans/<sessionId>/` 内至少 8 组 RGBD pair 完整；每组 RGB/depth timestamp 差 ≤ 50ms；uiautomator / logcat 确认采集引导、上一张叠加状态、低 overlap 重拍提示可达。 | `docs/architecture/04b-multiview-rgbd-reconstruction.md` §3.1 |
| M3.13 | 接 asset multipart upload：复用 M-S2.3 分片上传；保存 multiview session 元数据，可放 `inspections.scan_multiview_payload` 或独立 scan 表。 | curl 上传 8 组 RGBD pair 到 MinIO 成功；DB 记录 sessionId、frameCount、每帧 sha256。 | `docs/architecture/04b-multiview-rgbd-reconstruction.md` §3.2 / `docs/architecture/server/02-api-contract.md` |
| M3.14 | cv-engine 加 `object_3d_fusion` worker：NATS 订阅 `scan.captured`，拉帧后跑 Open3D multiway registration、Color-ICP、全局 PGO、Marching Cubes、纹理烘焙，输出 GLB。 | `scan_fusion` harness：Open3D demo 子集 chamfer ≤ 5mm；真实 8 张 RGBD 端到端 1-2 分钟输出 ≥ 50K 顶点 mesh。 | `docs/architecture/04b-multiview-rgbd-reconstruction.md` §3.3 |
| M3.15 | 端侧 gallery 拉取 GLB 回看：订阅或轮询 `scan.fusion_done`，下载 GLB，用 Filament PBR + IBL 渲染，并支持旋转、缩放、平移。 | 1080p 设备回看流畅；Filament / instrumentation 确认模型非空、渲染首帧成功，旋转 / 缩放 / 平移手势可用。 | `docs/architecture/04b-multiview-rgbd-reconstruction.md` §3.4 |
| M3.16 | 建 `scan_multiview_quality` harness：合成 Stanford Bunny 8 角度 RGBD + 真实卡车数据，跑端到端质量评估。 | `./dev.sh harness scan_multiview_quality` 通过；输出 mesh chamfer、点云覆盖度、UV atlas 利用率，UV 利用率 ≥ 70%。 | `tests/harness/scan_multiview_quality/` |
| M3.17 | cv-engine 接 SAM-HD 或 SAM2 服务端分割：用户 prompt + RGB 生成 2D mask，再借 iHawk 像素对齐投到 depth。 | SAM mask 与人工标注 IoU ≥ 0.92；与 M3.14 启发式 ROI 做 A/B，mesh 边缘毛刺下降。 | `docs/architecture/04b-multiview-rgbd-reconstruction.md` §4 |
| M3.18 | GPU worker 部署：cv-engine 容器接 GPU runtime，支持模型卸载与任务排队。 | 单 worker 处理 8 张 RGBD 的 SAM 推理 ≤ 30s；队列满载时状态可观测。 | `docs/architecture/04b-multiview-rgbd-reconstruction.md` §4 |
| M3.19 | 端侧 SAM2 Mobile：模型进 APK，适配 NPU/GPU 后端，首帧 prompt 后做 mask propagation。 | LOG-AN10 上 SAM 推理稳定 ≥ 5 fps；失败时给出设备能力原因。 | `docs/architecture/04b-multiview-rgbd-reconstruction.md` §5 |
| M3.20 | 端侧实时 ICP/TSDF + Filament mesh 预览：只融合 SAM mask 内 voxel，HUD 显示覆盖度与缺漏方向，云端高精度版完成后替换。 | 端侧实时 mesh 增长 ≥ 5 fps；扫描完成 ≤ 1s 出实时版；云端版完成后自动替换。 | `docs/architecture/04b-multiview-rgbd-reconstruction.md` §5 |

## M4 VIN 数码拓印

> 单帧 RGBD → ROI 平面拟合 → 固定法向距离正射重投影 → 1024×512 拓印图 → cv-engine OCR。
> docs: `docs/architecture/08-vin-rectify-design.md`

| ID | 任务 | 验收 | 文档 |
|----|------|------|------|
| M4.1 | vin native 模块：RANSAC 平面拟合、正射相机参数化、像素重投影、双线性 Color 采样、PNG 编码。 | native host test 覆盖平面拟合、ROI 越界、空深度、PNG 输出尺寸 1024×512。 | `docs/architecture/08-vin-rectify-design.md` §2 |
| M4.2 | `feature:scan3d` 加“VIN 数码拓印”入口：Color 实时预览、ROI 选框拖拽、拍照、拓印结果页。 | `./dev.sh run` 后通过 uiautomator / instrumentation 确认 ROI 拖拽命中区可操作、拍照链路可触发、拓印结果页可达。 | `feature/scan3d/Scan3dScreen.kt` |
| M4.3 | 拓印图本地落盘并推服务端 cv-engine `vin_pipeline`。 | 上传后能拿到 verdict、reasons、字符结果；网络失败保留本地重试记录。 | `core:network` / `docs/architecture/server/03-cvengine-migration.md` |
| M4.4 | 建 `vin_rectify_quality` harness：录制真实 VIN 钢架 RGBD pair，跑 `vinRectify` 与服务端 OCR。 | `./dev.sh harness vin_rectify_quality` 通过；多角度拓印 SSIM ≥ 0.9，OCR 准确率 ≥ 95%。 | `tests/harness/vin_rectify_quality/` |

## 服务端与治理待办

| ID | 任务 | 验收 | 文档 |
|----|------|------|------|
| S1 | App 端接 device calibration 同步：扫描启动前拉 `GET /v1/devices/{id}/calibrations/latest`，与本地 Room sha256 比对。 | sha256 一致时不下载；不一致时拉完整 params 并更新；离线时使用本地最近可用版本并标明版本。 | `docs/architecture/05-calibration-pipeline.md` |
| S2 | shape compare 从元数据级升级到几何级：解析真实 mesh，补 chamfer / Hausdorff / scale consistency。 | 扩展 `cv_shape_compare` harness；真实 mesh 对比能输出几何指标与三态 verdict。 | `docs/architecture/06-product-features.md` §3.4 |
| S3 | 生成并接入 cv-engine gRPC server。 | 安装 protoc 后跑 `server/scripts/proto-gen.sh`；gRPC 端点与 `proto/cvengine.proto` 契约一致，保留 HTTP harness。 | `server/proto/cvengine.proto` |
| S4 | 更新 `docs/architecture/registry/` 机器真理源。 | 任何模块边界、依赖或能力成熟度变化后，同步更新 `modules.yaml`、`dependencies.yaml`、`capabilities.yaml` 并通过校验。 | `docs/architecture/registry/` |
