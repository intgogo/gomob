# Berxel P100R3 深度相机逆向与 native UVC 栈 — 历史上下文

> 最后更新: 2026-07-26 | 截至 commit: 36653a4 | 维护规则见 AGENTS.md「历史上下文」节

## 使命与当前状态

把外接 Berxel iHawk **P100R3.0** 深度相机（USB-C OTG）在 Android 上稳定取到**真实可量测的 RGBD 双流**，喂给多视角重建（M3）与 VIN 拓印（M4）。核心难点从来不是 UI，而是 P100R3 的 USB/UVC 行为在手机 OTG 上极其挑剔——为此走过一条长达一个月的归因链，最终**完全绕开 Berxel Android SDK 的 native 层，自研 libusb-1.0 + Sonix XU 协议栈**。

**当前能用**：
- 自研 native 栈（`NATIVE_REWRITE`）是**生产默认**（`BERXEL_STACK_BACKEND="NATIVE_REWRITE"`，build.gradle 注入）；Berxel 官方 SDK 路径仅保留为 `SDK` 备选。
- **MIX 模式并发 color+depth 双流在 Android 真机打通**（2510DRK44C 直插 OTG，2026-06-02）：depth 稳增、valid=1.0、metric 深度（raw/8=mm）、color MJPEG 解码、0 错误。
- 设备 ASIC **直出 metric 深度**（0x0600 帧），host 无需复刻结构光重建；深度质量走 depth-only 精修（时域降噪 + 空间降噪 + 真置信 + 飞点剔除）。
- 相机抽象层（M6.8b）已把 Berxel 收成一个 `BerxelDriver`，走厂商无关 `cameraOpenByFds → ICameraSession`，为 eYs3D 留注册位；更深的物理迁移提取是 M1.8（TODO 未动工，见未竟事项）。

**当前不能用 / 受限**：
- **裸 OTG 双流只在 2510DRK44C 这一台稳**；生产 BOM 必须含带 PD 供电 hub。但"接 hub 即救活"**只在 vivo PD2324 单机实证**（219MB/0 错误）；OnePlus PJD110 / 25102RKBEC 接 hub 未回归（等 M1.6.9），HONOR 接带电 hub 会掉进 UFP/充电模式不当 host（USB-C 策略锁死）。
- master keepalive `set_cur rc=-7` 超时在部分机型仍在（但已证实**不挡深度**，见时间线）。
- 自研 host probe 的 dense controls 在**刚上电设备**上切不动（只有 vendor SDK 复位能切 dense），Android 路径是否同样依赖预热态未确认（M1.6.17 待项）。
- 15 组回归矩阵（M1.6.9）、出厂参数 blob 解码（M1.6.7）、Android 侧 25fps 对齐（M8）未闭环。

跨模块：eYs3D RS-D550 / HLSD8 是**另一套相机**（第二扫描机），见 `docs/context/eys3d-rsd550-hlsd8.md`；深度后处理如何进重建见 `docs/context/multiview-recon.md`。

## 决策时间线

### 2026-05-06 Berxel SDK 集成走通 + 砍手机主摄 (M1)
BerxelService 生命周期、USB attach intent、Android 12+/14+ ASM bytecode patch 落地，真机 Streaming 走通（`4c4d54b`/`0ddd522`）。同日 `3031de2` **方向调整**：放弃"深度相机 + 手机主摄深度绑定"初始设想，落定 iHawk 单设备 + 三维外廓 + VIN 拓印三主线。SDK 反编译笔记（auto-memory `finding_berxel_sdk_internals_2026-05-07`）验证三个关键设计（拦 registerReceiver / 跳过 getDeviceLists 自枚举 / attach 授权 device 路径）正确，并修 **PendingIntent flag=0 雷点**（`patches/berxel-android/BerxelJarPatch.java` ASM 改 ICONST_0→FLAG_IMMUTABLE），此前误诊为"HONOR USB 脏缓存"（`finding_honor_usb_permission_cache_2026-05-07`）。

### 2026-05-07 真机点云 0 的终极根因 = depth 定点转换 (M1)
`2461878`：depth 像素是**定点格式**，不做转换 → 点云全 0。该 commit 按 12.4（raw/16）修通，后订正为 13I_3D（`raw/8.0 = mm`）。这是"画面对了不等于链路通"的第一课。

### 2026-05-13 双流死锁登场 → firmware ISOC 假设（后被推翻）(M1)
HONOR LOG-AN10 上 depth 流根本跑不通，COLOR_ONLY 稳。当时结论（`finding_berxel_sdk_p100r3_phone_otg_2026-05-13`）：**P100R3 firmware 强制 ISOC 拒 BULK**（setDeviceTransferMode(BULK) 返 -8），native bypass 也救不了。**此假设 2026-05-18 被证伪**：USB descriptor 实测两个 streaming endpoint 都是 BULK（companion USB3 SS mps=1024 / master USB2 HS mps=512），"firmware 强制 ISOC" 不成立。顺手修的真 bug：MainActivity `launchMode=standard`→`singleTask`（USB attach intent 反复 onCreate 看着像崩溃/重启）。

### 2026-05-14 SDK depth "假成功" (M1)
`finding_p100r3_android_depth_stream_2026-05-14`：Java `startStreams rc=0` 但 native 报 `Unable to negotiate streaming format` + `libusb_submit_transfer -1`，`readDepthFrame` 恒 null。教训：**别只看 rc=0 判成功**，要同时看 native/libuvc 日志。只有无参 `openDevice(callback)`（SDK 自选内部 UVC 句柄）才能开流。

### 2026-05-18 硬件分解 + 双流死锁归因反复订正 (M1.6)
硬件解剖（`finding_p100r3_hardware_decomposition_2026-05-18`）：主控 Novatek `0x0603:0x001f`（RGB）+ companion `0x3558:0x1012`（NIR，iManufacturer 自报 "Berxel"，是 Berxel 自有 OEM VID，USB3 capable 用户 PC 实测）；companion 用 Sonix-style XU API（SDK 60+ `SonixCam_*` 符号）。**证伪链**：不是 Himax SH430UH（真 VID 0x2AAD:0x6373）、不是 SN9C2805A（USB2 only）；具体 chip 型号至今未锁定（medium confidence，终判需拆机看丝印或 PC 读 bcdUSB）。结构光方案（1 IR 投影 + 1 IR sensor + 1 RGB），散斑→深度**烘在设备 ASIC**。
双流死锁归因当天多次订正：mixed-bus → 25102RKBEC BSP 特异 → 高通 dwc3+UCSI 通病。多机对照（OnePlus 12 / HONOR / 联发科 vivo 全死，仅 2510DRK44C 活）催生"跨 SoC 通病、只剩 Berxel firmware patch"的（错误）阶段结论。

### 2026-05-26 三连订正定调：真根因 = Android SDK 老 UVC 栈 + Berxel 无支持 (M1.6)
关键翻新触发点：**用户 Windows PC 用同一颗相机能跑 single-stream DEPTH + 1280×800 DUAL**。二进制对比 Linux SDK V2.0.190 vs Android SDK 9.9.190（`finding_p100r3_dual_endpoint_host_kill_2026-05-18` 顶部订正）：Linux 走 mature `libusb-1.0`；**Android 嵌 `libuvc-0.0.7`（saki4510t 系 2015 老 fork）+ 自家 `android_usbfs_backend`**，错误字符串 `Unable to negotiate streaming format` 正是实测报错。CentOS 9 ground truth 实测三组全绿（single DEPTH 56ms / DUAL 640 334ms / DUAL 1280×800@45 404ms 稳 84s，`.dev/m1.6.3-pc-baseline/`）→ firmware/host BSP/mixed-bus/带宽假设**全部证伪**，锁定唯一变量是 Android SDK 内嵌软件层。
**决策**：A 方案 path 2 —— 用 libusb-1.0.27 + pupil-labs/libuvc 在 NDK 重写 native USB 层，替换 53 个 JNI 入口，Java jar 不动（`docs/architecture/10-android-uvc-stack-rewrite.md`）。同日 **Berxel 明确回复"没空支持"**（`reference_berxel_no_support_2026-05-26`）→ 索取源码/firmware patch/协议文档所有加速通道关闭，**一切靠自己反编译/逆向/抓 trace/重写**。

### 2026-05-27 self-port 出数据 → 归因链最后一次大翻转：OTG 供电不足 (M1.6.6)
自研栈里程碑（当日多个 finding）：
- **BULK 出数据**（`finding_p100r3_bulk_no_data_2026-05-27`）：companion 要 **master XU 5 control channel 活跃**（50ms keepalive）才推 depth，是"主从合一"硬约束；vivo 上 3×16384B sync_read 成功，但只拿到 <1 帧就 NO_DEVICE。
- **协议字节级正确**（`finding_protocol_correct_android_stack_bug_2026-05-27`）：`tests/native_host/p100r3_dual_session_linux.cpp` 服务器 Linux 1:1 复现，60s / 1.39GB / 0 错误。当时归因"Android `wrap_sys_device` 路径 + vivo BSP 杀流"。
- **★ 带电 hub 救活 vivo 双流**（`finding_powered_hub_unblocks_vivo_dual_stream_2026-05-27`）：接 PD 供电 hub 后 vivo PD2324 实测 14310 reads / 219MB / 0 错误。**推翻整整一个月的"firmware 硬约束 / 跨 SoC BSP 通病 / Android stack bug"** —— 真因是**手机 OTG 输出电流不够 P100R3 三路并发**（IR 投影 + RGB + companion，估 ~1A）。2510DRK44C 唯一能裸跑 = 它 OTG 供电够，不是拓扑友好。生产 BOM 必须加带电 hub。
- 出厂参数（`finding_p100r3_device_params_offline_only_2026-05-27`）：156B 内参是 `memcpy` 离线 blob，**无 USB 读协议**；唯一 path = adb pull `<SN>_params.bin` 找 offset。

### 2026-05-28→29 depth/IR 交织真深度解码（"只推 IR raw"被推翻）(M1.6.11-19)
先误判（`finding_p100r3_companion_pushes_ir_raw_2026-05-28`）companion 0x82 只推 raw IR、depth 必须移植 SDK 重建。**次日订正**（仓内 `finding_p100r3_depth_ir_interleaved_2026-05-29`）：加 dense depth controls（AE=1, confidence=3, temporal_denoise=0, spatial_denoise=0）后，0x82 **在同一流不规则交织真 metric 深度帧（0x0600, raw/8=mm）+ IR/phase 帧（0x0500）**。分流靠**状态行首像素 `pixel[0]` 标记**（0x0600=深度 / 0x0500=IR，30/30 dump 100% 可靠，也是原厂 SDK 做法），不用内容启发式。**6MB `<SN>_params.bin` = 温度补偿表**（不是散斑参考），散斑→深度在设备 ASIC，host 无匹配/三角化代码 → **放弃自研结构光重建，走"设备直出真深度"路线 A**。M1.7-P2（自研结构光重建）因此**作废**。
自研栈同期抽出 `native/berxel/portable/`（libusb-free 编排层 + `IUvcDevice` 接口，`handoff_berxel_host_sdk_2026-05-29`），并在小米 2510DRK44C **实机跑通 depth-only**（valid=1.0 稠密、center≈423mm、0 错误），P0「depth 当 IR」实机修复。
深度质量四连（M1.6.15-17/19，仓内 `finding_depth_*_2026-05-29/30`，详情见 multiview-recon 上下文）：时域降噪 38→10mm（自适应门限，非硬编码）+ 飞点三证合一剔除 + 真置信（设备 confidence 98.7% 饱和废值 → 用窗口 span 派生稳定性置信）+ 空间降噪；**IR 边缘引导证无益（F1 0.25 vs 0.88）但 IR 作单帧置信成立（AUC 0.82）**。

### 2026-05-29 master 挂死只能物理断电 (M1.6.18)
`finding_p100r3_master_hang_recovery_2026-05-29`：color MJPEG 流会挂死 master Novatek 芯片（掉枚举，dmesg error -71/-110）；companion 不受影响。挂在自供电 + ganged Terminus hub 下游 → **host 任何软复位（uhubctl/xHCI unbind）都够不到 master VBUS**，只能拔 hub 电源砖。depth-only 也会因 XU5 keepalive `set_cur` 超时慢性饿死 master。

### 2026-06-01→02 相机抽象层落地 (M6.8b)
厂商无关接口 `native/camera/`（`camera_session.h`/`camera_device.h`/`camera_registry.h`，命名空间 `gomob::camera`；`IUvcDevice`/assembler/pairer 等复用件**物理仍在 `native/berxel/portable/`，经 `using` 再导出**，物理迁移留给 M1.8）。`BerxelDriver` + `BerxelSessionAdapter`（`native/berxel/host/berxel_camera_adapter.h`）包装 `berxel_dual_session_jni.cpp` 内 DualSession 取流 core 实现 `ICameraSession`；JNI 通用化 `camera_session_jni.cpp`（`cameraOpen/Poll/SetControls/Stop`）；生产唯一路径 = `cameraOpenByFds → BerxelDriver`（legacy `berxelDual*` 已删）。原厂 oracle parity harness `berxel_camera_parity` 真机 PASS：统一抽象层 vs 原厂 SDK **median 偏差 0.1% / p90 偏差 0.4%**，最强不退化证据。host-first + native_host 单测 + harness 守 Berxel 不退化，为 eYs3D 留注册位。

### 2026-06-02 ★ MIX 模式根除"开 color 整机死"(M6.8b)
`c32ea7d` + 仓内 `finding_p100r3_mix_color_depth_2026-06-02`：自研栈一开 color 就整机 0 帧，**真因不是设备特定/URB/供电，而是没进 MIX 模式**（回放的是 SINGULAR init）。从 usbmon 抓原厂 MIX 配方逐位还原落资产：
- master MIX = 21 条（`iHawkP100R3_master_mix_init.json`），关键：StreamFlagMode（BX reg 0x0030）**线上值 0x0000 写两次**（不是反编译误推的 0x02）+ 多一条 cmd0x0007=0x0001 + COLOR OpenStream 640×400@30 中段插入。
- companion MIX = 8 条（`iHawkP100R3_companion_mix_init.json`），相对 depth-only 仅 reg0x19 `01→04` + 末尾 0102 00。
- UVC commit：color fmt1 frame3 interval 333333（30fps）；depth fmt1 frame2 interval 222222（45fps）。
`enableColor` 自动选 MIX/SINGULAR 双资产。**Android 真机 PASS（2510DRK44C 直插无 hub）**：depth_seq→272、pairs=271、valid=1.0、metric≈2335mm、color 7031 chunks、0 错误。**MIX 模式下 keepalive 关闭**（master color bulk 自维持主控，rc=-7 心跳超时彻底退场）。
同日决定性测试订正 M1.6.18：**keepalive set_cur rc=-7 超时是红鲱鱼**（depth-only 下它照样全超时但 depth 完美流，companion 0x82 独立于 master keepalive），深度扫描不需要修 keepalive；"开 color 整机死"当时一度归为"master color 0x81 与 depth 在该机互斥 = 设备特定"，**随即被上面的 MIX 根因收编（非设备特定，是 SINGULAR init 下开 color）**。另 2510DRK44C 上接外接电 hub 后 set_cur 超时依旧 → 该机 keepalive 超时与供电无关。M6.8b ④ 收尾删冻结 legacy `berxelDual*`。

### 2026-06-03 官方 SDK 可逆向 + 协商帧长 1026584 (M8 铺垫)
`finding_berxel_sdk_acquisition_re_2026-06-03`：官方 so 未混淆，导出符号是完整 C++ 类名，可 `nm -DC` + objdump 重建。设备协商的 depth 帧长 = **1026584 = 2×513292**（513292 = 640×401×2 + 12B header），我方硬编码 513280 盲切把双面帧劈开 → marker 错位、帧率抖。**逆向证实**：官方就是靠 pixel[0] marker 0x0600/0x0500 分流（我方同法正确）、401→400 状态行在末行、raw/8=mm、硬件温补已开**别加软温补**。终态方向：用协商真实帧长组帧、剥 12B header、按 marker 拆面，废弃固定盲切。

## 禁区与已证伪路线

- **不要再假设 firmware 拒 BULK / 强制 ISOC**（2026-05-18 证伪：descriptor 实测两 streaming endpoint 都是 BULK）。
- **不要再把双流死归因于 firmware 硬约束 / 跨 SoC BSP 通病 / Android wrap_sys_device bug**（2026-05-27 带电 hub 全部推翻）。**真因是 OTG 供电不足**；但"hub 救活"截至目前只在 vivo 实证，别把它当 5 机已验结论引用（M1.6.9 未跑）。
- **不要再规划"等 Berxel 配合"的任何路径**（源码/firmware patch/协议文档/新 SDK）——2026-05-26 厂商明确无支持，结构性关闭。看似"问一句就能解"的都直接走逆向。
- **不要自研结构光重建 / 移植 SDK 深度重建**（M1.7-P2 作废）：散斑→深度烘在设备 ASIC，参考散斑/基线不可导出；设备 dense controls 后直出 0x0600 metric 深度。可导出标定只有 156B 内参。
- **6MB `<SN>_params.bin` 不是散斑参考，是温度补偿表**（`setTemperaTureCompensationStatus` 加载）；设备硬件温补已开，**不要加软温补**。
- **不要把 DEPTH 当散斑图**（散斑走 LIGHT_IR / 0x0500 帧）；**不要 raw/16**（当前 P100R3 是 13I_3D，raw/8=mm）；**不要用 CPU fillHole 的 processed depth 替代 raw 量测**（只作 VIN/分割实验）。
- **dense depth 不是 CPU 补出来的**，是设备侧 `temporal_denoise=0 + spatial_denoise=0` 触发；depth controls 状态是**粘性**的，测 sparse/dense 前必须显式复位。注意：**刚上电设备上自研 probe 的 dense controls 切不动**（只有 vendor SDK 复位能切），未解，别把 probe 卡 sparse 当设备退化。
- **深度不要接 IR 边缘引导**（离线证 F1 0.25 vs depth-only 0.88；SDK 里 `inner_process_with_IR` 是零调用者死 API）；IR 只作单帧置信权重，不碰几何。
- **master keepalive set_cur rc=-7 超时不挡深度扫描**（companion 0x82 独立），别当阻塞 bug 追；深度路径不需修它。
- **master 挂死别试软复位**（自供电 + ganged hub 物理死局），只能拔 hub 电源砖或拔相机；生产选**支持 PPPS 的带电 hub** 才能远程软复位。
- **只 open master(0603) 或只 open companion(3558) 传显式 deviceInfo 会让 SDK setStreamFlagMode 返 -3**；SDK 路径必须无参 openDevice。
- **StreamFlagMode 不是 0x02**（反编译误推）；MIX 序列里线上值就是 0x0000 写两次，别再 patch。
- 测试机纪律：**2510DRK44C（annibale, USB2）是裸 OTG 双流唯一稳的机**；25102RKBEC/OnePlus PJD110/HONOR LOG-AN10/vivo PD2324 裸插双流必死，做 DUAL 验证要么用 2510DRK44C 要么接带电 hub（HONOR 除外：接带电 hub 会进 UFP/充电模式不当 host，别用它做 hub 对照）。

## 关键资产指针

- `docs/architecture/01-depth-camera-integration.md` — 深度相机集成总入口（帧流/内参/预览）。
- `docs/architecture/10-android-uvc-stack-rewrite.md` — 自研 UVC 栈重写设计（现象矩阵 + 根因 + 53 JNI 边界）。
- `docs/architecture/11-embedded-camera-form.md` — 内嵌相机形态 P0-P4（供电/watchdog 自愈）。
- `docs/architecture/12-camera-abstraction.md` — 多相机驱动抽象层（IUvcDevice/ICameraDriver/ICameraSession）。
- `docs/architecture/03-jni-boundary.md` — JNI 边界契约。
- `native/berxel/portable/gomob_berxel_portable.{h,cpp}` — libusb-free 编排层（assembler/pairer/depth 解码/协议/时域滤波/飞点/置信），host 与 Android 共用。
- `native/berxel/host/` — Linux host 自研 SDK（金标准），`docs/depth-pipeline-reverse.md` + `docs/xu-command-map.md` 是反编译真理源。
- `native/camera/` — 厂商无关抽象（camera_session.h / camera_device.h / camera_registry.h）；`BerxelDriver` 工厂在 `native/berxel/host/berxel_camera_adapter.h`（实现在 berxel_dual_session_jni.cpp）。
- `native/jni/berxel_dual_session_jni.cpp` — Android 双会话驱动（AndroidUvcDevice:IUvcDevice + 12 步序列 + demux）；`camera_session_jni.cpp` — 通用 JNI。
- `native/berxel/iHawkP100R3_descriptor.md` / `iHawkP100R3_init_sequence.json` — 设备描述符与初始化序列。
- `core/native-bridge/.../berxel/` — Kotlin 层：`BerxelNativeStack.kt`（自研栈入口 cameraOpenByFds）、`BerxelService.kt`（生命周期 + backend 切换）、`BerxelStackBackend.kt`（SDK/NATIVE_REWRITE flag，默认 NATIVE_REWRITE）。
- `core/native-bridge/src/main/assets/berxel/` — MIX/SINGULAR 序列资产（master_mix / companion_mix / master_xu5 / init_sequence）。
- `tests/native_host/p100r3_dual_session_linux.cpp` — 协议金标准复现（服务器 60s/1.39GB/0 错误）；`berxel_*_test.cpp` — portable 纯逻辑单测。
- harness：`berxel_depth_parity`（host/vendor 深度对齐 + 噪声底）、`berxel_mix_replay`（MIX 双流回归）、`berxel_mix_trace`（usbmon 抓原厂配方）、`depth_temporal_quality` / `depth_flying_pixel` / `depth_ir_guided`（深度质量）、`depth_singlestream`（keepalive sweep）、`berxel_camera_host` / `berxel_camera_parity`（抽象层不退化）。
- `patches/berxel-android/BerxelJarPatch.java` — jar ASM patch（PendingIntent flag + getUsbDeviceList serial 规避）。
- agent-memory（仓内 `docs/agent-memory/`）：`finding_p100r3_depth_ir_interleaved_2026-05-29`、`finding_p100r3_mix_color_depth_2026-06-02`、`finding_p100r3_master_hang_recovery_2026-05-29`、`finding_berxel_sdk_acquisition_re_2026-06-03`、`handoff_berxel_host_sdk_2026-05-29`、`reference_iHawkP100R3_spec.md`（工作距离 0.2-8m / 理想 0.25-2m / 精度 ≤1%@1-2m，native 阈值真理源）、`reference_berxel_sdk_locations.md`。
- auto-memory（`/root/.claude/projects/.../memory/`，含订正链）：`finding_p100r3_hardware_decomposition_2026-05-18`、`finding_companion_vid_unique_to_berxel_2026-05-18`、`finding_p100r3_dual_endpoint_host_kill_2026-05-18`、`finding_p100r3_bulk_no_data_2026-05-27`、`finding_powered_hub_unblocks_vivo_dual_stream_2026-05-27`、`finding_protocol_correct_android_stack_bug_2026-05-27`、`finding_p100r3_device_params_offline_only_2026-05-27`、`reference_berxel_no_support_2026-05-26`、`project_test_phone`（测试机池最新 IP/端口）。

## 未竟事项

- **M1.6.7** — 补完其余 43 个 JNI（exposure/gain/enable/出厂参数）；解码 `params.bin`：install 原厂 sample APK → adb pull 6MB blob → 在其中定位 156B 内参 offset（brute force fx/fy 合理范围 + 交叉验证）。设备无 USB 读参数协议，只能离线。
- **M1.6.8/9** — Phase 4/5：切 `third_party/berxel-android/` 到新实现；5 台测试机 × 3 stream type × 分辨率完整回归矩阵，**强制接带电 hub**，报告落 `.dev/m1.6.9-regression/`，订正双流死锁 finding 终结论。
- **M1.6.14** — Android 迁移 Step 4：Kotlin 正式切 `startDualNative`，删重复实现（`BerxelFrameAssembler.kt`/`BerxelMjpegAssembler.kt`/包内 `DepthFrame.kt`），修契约 `DepthFrame.data` 真为 16bit mm，删 JNI 异步 BULK pool 死代码。
- **M1.6.17(3)** — 查自研 probe dense 序列：刚上电设备只有 vendor SDK 复位能切 dense、自研 controls payload 不生效（自研栈真实 gap）；需定位缺的复位/预热步骤，并确认 Android JNI 路径（曾拿 valid=1.0）是否同样依赖设备预热态。
- **M1.8** — 多相机抽象层深提取（复用件物理迁 `native/camera` 去掉 `using` 再导出、常量参数化）：M6.8b 已达成行为级统一，M1.8 是结构收尾，TODO 六子项未动工。
- **M1.7-P0** — 内嵌形态分水岭验证（GPIO 可控断电 carrier）：① 关 color 后 master 还秒挂吗；② 心跳实时化后 set_cur rc=-7 是否消失、20min 饿死是否解除；③ 断电→重枚举→续流恢复链可重入。**这一步决定内嵌值不值**。P1-P4（watchdog 自愈 / 半内嵌 / 焊死）待 P0 绿。
- **M8（25fps）** — 修 Android 侧 ~10fps + 秒级延迟：拆 `depth_parser_loop` 为组帧+滤波两级线程（对标官方 processDepthThread）、组帧队列 ≤2-3 帧满丢最旧、color 解码移独立线程、transfer 池 48→20-24。host 测不出，**必须真机验**。
- **M2 iHawk 标定** — 休眠中（触发条件 = M1.3 出厂参数/registration 实测不达标，未触发）。
- **深度质量下游** — Android FrameRenderer 按 conf 阈值掩码（非仅 conf==0）；云端多视角融合按 conf 加权（接 M3.14，见 multiview-recon 上下文）。
