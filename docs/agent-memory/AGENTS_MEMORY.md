# 跨 Agent 共享记忆索引

> 本文件是索引，**不是**记忆载体本身。每个条目是一行 `- [标题](文件名.md) — 一句话摘要`。
> 控制在 ~150 行内，超出考虑分主题文件。

## 顶级原则（principle）

- [第一性原则选最优, 开发设计不做妥协](principle_first_principles_no_compromise.md) — 项目级硬规则：分叉时按第一性推导最优解，不因实现成本退到妥协解。

## 写作 / 协作纪律（feedback）

- [无妥协架构原则](feedback_no_compromise.md) — 发现架构问题不调参凑合，直接重做。
- [先设计后实现](feedback_first_principles_design.md) — 不从"现有代码改动最小"出发，先写正确设计再看复用。
- [Plan / Spec / TODO 写作质量硬规](feedback_plan_writing_quality.md) — 无占位符 / 批判性复审 / 任务按 harness 可验收单元切。
- [开发闭环：自驱动验证](feedback_dev_loop.md) — 写完功能必须自己跑通采样 → 分析闭环，不等用户报。
- [设计决策风格偏好](feedback_design_style.md) — 激进决策、长期主义、严格反馈、设计/实施文档分层。
- [Git Push 策略](feedback_git_push_policy.md) — 本地可 commit；只在用户明确要求时 push。
- [UI 改动真实运行；默认不截图](feedback_ui_visual_verification.md) — 优先日志/harness/uiautomator；用户主动要求才截图。
- [用户全程 VNC 远程](feedback_vnc_remote_dev.md) — emulator / 任何 GUI 必须走 DISPLAY=:1（TigerVNC 5901 端口），不能起 Xvfb headless 否则用户看不到。
- [重要不确定模块必须建 harness](feedback_harness_mandatory.md) — 五条触发标准命中时先建 harness 再写业务代码。

## 项目 / 参考（reference）

- [Berxel SDK 资源位置](reference_berxel_sdk_locations.md) — Windows SDK 头文件 / 样例 / VIN 文档的 SMB 挂载点路径。
- [iHawk P100R3.0 产品规格](reference_iHawkP100R3_spec.md) — gomob 实际硬件型号；工作距离 0.2-8m / 理想 0.25-2m / 精度 ≤1%@1-2m。native 阈值真理源。
- [gogame 方法论源仓](reference_gogame_methodology_origin.md) — 本项目的方法论源头是 `/root/lilw/gogame`，可回溯。

## 历史决策（finding / design）

- [**重建主线 2026-05-07 重大方向变更**](finding_multiview_rgbd_pivot_2026-05-07.md) — 实时 SLAM → 多视角 RGBD 配准 + 端云融合；权威设计在 04b；既有 native 沉淀阶段 3 复用。**新工作不要扩 04 路线**。
- [**车辆外廓+VIN 端到端拉通 2026-06-02**](finding_scan_vin_wiring_2026-06-02.md) — 两 mock 屏重写接真底座；bundle 契约=rgbd_bundle.py；新增 /v1/scans GLB 流端点;cvengine 经 devserver 反代+服务端 HMAC(密钥不下发);雷点:Kotlin 注释嵌套/拦截器缓冲二进制 OOM。
- [模拟器在本机的稳定配置 2026-05-08](finding_emulator_setup_2026-05-04.md) — emulator 36.x 必须 `DISPLAY=:1 -gpu host`，并禁 netsim/虚拟 WiFi。
- [Android 实时 WS 与 devserver 注意点](finding_android_realtime_ws_devserver_2026-05-09.md) — App WS 用 http scheme；devserver 包装器需透传 Hijacker。
- [KSP2 + Hilt 2.53.x 不兼容](finding_ksp1_required_for_hilt_2_53.md) — `ksp.useKSP2=false` 是当前唯一解。
- [mob3d 设计最新交付核对 2026-05-05](finding_mob3d_handoff_realign_2026-05-05.md) — 现仓本就是 handoff 业务化升级版；本次只补 token + 微观对齐 + FirstPersonViewer 重写 + 删 PlaceholderScreen。
- [P100R3 Android SDK 深度流假成功](finding_p100r3_android_depth_stream_2026-05-14.md) — depth native negotiation 失败但 Java startStreams 返 0；别误查 UI。
- [**VINCreator(eYs3D)逆向=M1.6 UVC 蓝本+双流死旁证 2026-06-01**](finding_vincreator_eys3d_uvc_blueprint_2026-06-01.md) — 反编译证它用 libusb 1.0.19 老 backend 仍跑双流→旁证 P100R3 双流死真因是 OTG 供电非老栈；fd 注入/权限三件套印证 gomob 修后版对；开流原语+深度后处理分层可抄，0xF4xx 寄存器是 Etron ASIC 不可用。报告 `tests/vincreator-apk/REVERSE-ENGINEERING.md`。
- [**RS-D550 真实开流序列已解码+RHEL9 能出流 2026-06-01**](finding_rsd550_open_sequence_decoded_2026-06-01.md) — **推翻"RHEL9 不兼容"**：eSPDI 直调 color/depth 各 60/60 帧。usbmon 解全开流序列：XU(unit4)videoMode 写 `82 f0 14`/`20 f0 02`/`20 ed 00`+selector0x0a 计数器握手(旧 0xE0/0xE3 作废)、标准 VS PROBE/COMMIT(IF1 fmt1/frame4 maxPayload2048、IF2 fmt1/frame1)、无 SET_INTERFACE、COMMIT 后~1.5s 暖机。自研 libusb 控制全对但 bulk 被 NAK 0 帧，待验差异=同步单 URB vs 异步多 URB。
- [Berxel P100R3 自研 SDK 交接 2026-05-29](handoff_berxel_host_sdk_2026-05-29.md) — host parity 已过；2026-05-29 完成 Android 迁移 Step 1：抽 `native/berxel/portable/` libusb-free 层 + `IUvcDevice` 接口 + 修 2 个 host bug，三验证全绿。下一步 JNI 接 `gomob_native.so`。
- [**P100R3 companion 交织真深度+IR/phase 帧 2026-05-29**](finding_p100r3_depth_ir_interleaved_2026-05-29.md) — **订正"只推 IR raw"**：dense controls 后 0x82 不规则交织真深度帧(~60%, raw/8=mm)与 IR/phase 帧(~40%)；按状态行标记 pixel[0]==0x0600 确定性分流取深度。**含 6MB blob=温补表(非散斑)、重建在设备 ASIC、放弃自研结构光的路线 A 决策**。小米 2510DRK44C 实机验证。**2026-05-30 反汇编+离线原型决定性补充：交织 IR 不进 SDK 深度链(EdgeEnhanceInfraRed/inner_process_with_IR 是零调用者死 API)；复刻量化后**证伪"IR 当边缘"**(0x0500 散斑帧,IR 边缘对真边界 F1 仅 0.25 vs 深度 0.88)但**证实"IR 当单帧置信"**(散斑局部对比度预测深度不可信 AUC 0.82,控强度/逐帧/视觉均站得住)→ IR 给零延迟单帧置信图。harness depth_ir_guided**。**host 重验闭环(2026-05-30,无需手机):Linux 厂商 SDK 顺序抓 density-first depth(99.8%稠密)+light-IR(纯IR)复跑,AUC 0.72-0.73、掩码 conf≥160 单帧 7.9%→0.32%,与 Android 交织互证；订正"host 不交织 IR=异常"为 SDK 解复用正常行为。host_capture.cpp/host_confidence.py**。
- [**深度时域降噪 38→10mm 2026-05-29**](finding_depth_temporal_denoise_2026-05-29.md) — 路线 A 直出深度量测头号敌人是逐像素相邻帧抖动 ~38mm；`P100R3TemporalFilter` 滑窗均值 N=8+噪声底缩放运动门限把它压到 ~10mm(3.7×)、零偏移、密度不掉。**运动门限必须≥噪声底，否则退化成每帧 reset 的 EMA 陷阱**。harness `depth_temporal_quality` 验收。
- [**深度"满屏噪点"=密度优先+设备置信废值;解=真置信+空间降噪 2026-05-29**](finding_depth_noise_real_confidence_2026-05-29.md) — 是真深度但~74%时域不稳;设备 confidence 饱和(98.7%=255)无效。解:用窗口 span 派生真置信替换废值(数据保稠密,下游按 conf 掩码/加权,契合多视角主线)+ median3+bilateral 空间降噪(noise 27→10mm)。设备降噪维持关。flying harness 需关空间降噪隔离。已落地 portable,真帧验证,host 测试全绿。
- [**P100R3 master 被 color 流挂死只能物理断电 2026-05-29**](finding_p100r3_master_hang_recovery_2026-05-29.md) — color MJPEG 挂死 master Novatek(掉枚举 error -71);自供电+ganged 的 Terminus hub 物理上没法按端口断电,连 xHCI 控制器 unbind/bind 都救不回(hub 自己供电)→ host 软件无解,只能拔 hub 电源砖 5s。预防=demo 默认 depth-only。
- [**P100R3 并发 color+depth = MIX 模式 2026-06-02**](finding_p100r3_mix_color_depth_2026-06-02.md) — **推翻"color 与 depth 设备特定互斥"**:真因是 NATIVE_REWRITE 没进 MIX 模式(回放 SINGULAR master init)。usbmon 抓原厂 MIX 配方:master 21 条(StreamFlagMode reg0x30 线上=**0x0000 写两次**非 0x02、多 cmd0x0007=01、中段 COLOR OpenStream)+ companion 8 条(cmd#5 reg0x19 01→04)+ color fmt1frame3@30fps/depth fmt1frame2@45fps。落 `iHawkP100R3_{master,companion}_mix_init.json`,enableColor 自动选。**host 实测 color1040/depth146(298mm)/145对/0错;Android 真机 PASS(2510DRK44C 直插无 hub):depth_seq→272/pairs271/valid1.0/metric2335mm/color7031/0错**——month-long"开color整机死"根除。harness `berxel_mix_replay` ✅。删了错的 `p100r3_mix_flag_mode`/`patch_*_stream_flag_mode`。MIX 下 keepalive 关闭(master color bulk 自维持);广播用显式组件 `-n .../DebugBerxelReceiver -f 0x20`。
- [**深度飞点剔除：三证合一 2026-05-29**](finding_depth_flying_pixel_removal_2026-05-29.md) — 纯单帧检测过杀 24%；正解=时域不稳(取自 TemporalFilter 窗口)∧双侧角度超界夹心(物理坡度上界 step_max=tan(grazing)·Z/f)∧无共面支撑，在 fuse 后做、保 raw+conf=0。harness `depth_flying_pixel`：合成 recall=1.0/geom_keep=1.0、真实 vendor removal=0.04%(对照单帧 24%)。**conf 在 JNI 算了从不 poll 出去=断链待补**。
- [**周期纹理致配准位姿翻转 2026-05-31**](finding_periodic_texture_registration_aliasing_2026-05-31.md) — 合成物体用周期正弦纹理→FPFH/Color-ICP 特征混叠→~20% 视角翻转(同输入 1.7↔6mm);换非周期渐变色 10/10 稳。配准 harness 配色须非周期梯度;flaky 融合先疑配准翻转(看顶点/completeness 跳变)。真实重复纹理物体生产同风险。
