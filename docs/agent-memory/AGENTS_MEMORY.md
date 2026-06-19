# 跨 Agent 共享记忆索引

> 本文件是索引，**不是**记忆载体本身。每个条目是一行 `- [标题](文件名.md) — 一句话摘要`。
> 控制在 ~150 行内，超出考虑分主题文件。

## 顶级原则（principle）

- [动手前必答三问](principle_three_questions_before_acting.md) — 每次任务动手前先答清「我要什么/我有什么/我要怎么做」三问；任一答不上来先调研或澄清，不动手。
- [第一性原则选最优, 开发设计不做妥协](principle_first_principles_no_compromise.md) — 方案分叉时按第一性推导最优解, 先调研行业最佳再突破, 不因成本/风险退到小步妥协。
- [省算力的优化必须结果等价](principle_compute_equivalent_optimization.md) — 为省算力/带宽/电做的优化不得偷降扫描保真度，须 harness 证明与全量等价。

## 写作 / 协作纪律（feedback）

- [用户输入要批判性思考, 不许言听计从](feedback_critical_thinking_not_yes_man.md) — 用户的方案/观点/事实断言必须深度分析或验证再回应；发现错误或非最优解主动指出，不附和迁就。
- [无妥协架构原则](feedback_no_compromise.md) — 发现架构问题不调参凑合，直接重做。
- [先设计后实现](feedback_first_principles_design.md) — 不从"现有代码改动最小"出发，先写正确设计再看复用。
- [整体视角验收, 不点状打补丁](feedback_holistic_not_patching.md) — 一帧深度异常/一次配准翻转，回几何与同步链路找根因，不在某层加 if 兜表象。
- [Mock-first 但 I/O 守恒](feedback_mock_first_io_conserved.md) — SDK/真机未就绪可 mock/host harness 顶上，但必须走真实数据通道与契约，不做假 fallback。
- [Phase 0 是骨架不是真实](feedback_phase_0_is_skeleton_not_realism.md) — 早期搭采集/JNI/数据通道骨架对，但每次 native 调用必须真，不许硬编码深度/假位姿伪装重建。
- [验收不能只看 UI，必须验扫描业务结果](feedback_business_verification_not_ui_only.md) — UI 不崩/首帧出来不等于扫描对；退出必须验点云密度/网格质量/外参一致/测量值可量测复现。
- [plan / spec / TODO 写作质量硬规](feedback_plan_writing_quality.md) — 无占位符 / 批判性复审 / 任务按 harness 可验收单元切而非时间切。
- [计划(P 阶段)默认工作流 + 测试要快](feedback_p_phase_default_workflow.md) — 动手前默认先出计划、按 harness 可验收单元切；计划里的测试必须秒级。
- [开发闭环 — 自驱动验证](feedback_dev_loop.md) — 每个阶段性功能完成后，立即自己跑完采样 → 分析闭环，不等用户报问题。
- [声称完成前先自测](feedback_self_test_before_claim_done.md) — 写完代码/功能/harness 必须自己跑通验证，确认无崩溃无空数据行为对再报完成，别把复现甩用户。
- [自主执行纪律不中途请示](feedback_autonomous_execution_no_check_in.md) — auto 模式拿到任务推进到底，不每步问"要不要继续"，真分叉才用 AskUserQuestion。
- [设计决策风格偏好](feedback_design_style.md) — 激进决策 / 长期主义 / 严格反馈不讨好 / 设计与实施文档分层 / 边界纯粹。
- [重要不确定模块必须建 harness](feedback_harness_mandatory.md) — 五条触发标准命中时先建 harness 再写业务代码。
- [Git Push 策略](feedback_git_push_policy.md) — 本地可 commit；只在用户明确要求时 push。
- [UI 改动真实运行；默认不截图](feedback_ui_visual_verification.md) — 优先日志/harness/uiautomator；用户主动要求才截图。
- [用户全程 VNC 远程](feedback_vnc_remote_dev.md) — emulator / 任何 GUI 必须走 DISPLAY=:1（TigerVNC 5901 端口），不能起 Xvfb headless 否则用户看不到。
- [worktree 只在并行任务才开](feedback_worktree_only_for_parallel.md) — 单 Agent 串行推进直接在 master 干；只有多任务真正并行才按 `.worktrees/<branch>` 隔离。

## 项目 / 参考（reference）

- [Berxel SDK 资源位置](reference_berxel_sdk_locations.md) — Windows SDK 头文件 / 样例 / VIN 文档的 SMB 挂载点路径。
- [iHawk P100R3.0 产品规格](reference_iHawkP100R3_spec.md) — gomob 实际硬件型号；工作距离 0.2-8m / 理想 0.25-2m / 精度 ≤1%@1-2m。native 阈值真理源。
- [gogame 方法论源仓](reference_gogame_methodology_origin.md) — 本项目的方法论源头是 `/root/lilw/gogame`，可回溯。
- [CodeGraph 覆盖边界](reference_codegraph_coverage_boundaries.md) — 已索引 Kotlin/C++/Go/Python/C；third_party 厂商二进制不解析，裸名跨包重名需消歧。

## 历史决策（finding / design）

- [**重建主线 2026-05-07 重大方向变更**](finding_multiview_rgbd_pivot_2026-05-07.md) — 实时 SLAM → 多视角 RGBD 配准 + 端云融合；权威设计在 04b；既有 native 沉淀阶段 3 复用。**新工作不要扩 04 路线**。
- [**VIN 还原全量上服务端 2026-06-18**](finding_vin_rectify_serverside_calib_2026-06-18.md) — 订正:真还原管线在libcreator_jni.so(restoreImageFlow:YOLO OBB+RANSAC平面+单应+去阴影+后处理);决策全量上Go cvengine原厂全保真,端侧只拍存传;首批11张数据在.dev/vin_captures。
- [**车辆外廓+VIN 端到端拉通 2026-06-02**](finding_scan_vin_wiring_2026-06-02.md) — 两 mock 屏重写接真底座；bundle 契约=rgbd_bundle.py；新增 /v1/scans GLB 流端点;cvengine 经 devserver 反代+服务端 HMAC(密钥不下发);雷点:Kotlin 注释嵌套/拦截器缓冲二进制 OOM。
- [**激光扫描设备集成 2026-06-03**](finding_laser_scanner_integration_2026-06-03.md) — 车辆外廓页加激光设备(切 berxel/激光);两网络单元 .101/.102 迁 /root/lilw/lidar 几何;Kotlin 网络+native 几何+端侧融合,不引 PCL(复用 IcpRegister);M8.1 已提交 worktree feat/laser-scan-integration。
- [**JCHY 测量/建模层逆向 2026-06-04**](finding_jchy_measurement_layer_re_2026-06-04.md) — 漏掉的「应用软件」逆向(JCHY_simple_3.0.0,带完整 PDB):采集层之上的测量/建模。26 车型+carType 表已解密+8 阶段管线(PCL 聚类+OBB+局部 PointSIFT)+测量字典(LWH/轴距/罐体三段/栏板/护栏/容积)。gomob 全缺此层。完整架构 docs/architecture/16。
- [**激光缩扫描角隔离不了目标→唯一解 3D 框裁剪 2026-06-05**](finding_laser_scan_angle_cannot_depth_isolate_2026-06-05.md) — 真机 scan24 实测:设备只有 pan+俯仰角度闸门、无深度闸门，限到目标立体角后背景仍留 99.3%(藏同一视线)。"圈范围→反算扫描角"物理做不到；唯一能按深度隔离的是 3D 框软件裁剪(M9.11 持久车位框,世界系定向,不依赖自动地面)。自动地面 RANSAC 又拟到天花=不可靠。
- [**激光车位框按镜头独立框 + 第一视角漫游标注 M10 2026-06-05**](finding_laser_roam_percamera_cropbox_2026-06-05.md) — A/B/融合三窗统一轨道查看;每相机背景不同需按镜头独立存框,双框各自去背景再并集测量(纯 Go 不动 C++);第一视角漫游走一圈→拟合最小面积外接矩形 OBB 标注。坑:漫游路径坐标系须与顶视投影同源、yaw 约定三处一致;真机流畅度待复核。
- [**几何车辆部件测量:轴距/前后悬+货箱 2026-06-17**](finding_vehicle_axle_ground_contact_2026-06-17.md) — 轴距=贴地接触带密度峰(轮唯一触地);货箱=顶高最长近顶段+rim取外长宽+恒宽z段定bed算箱深(无 DL)。陷阱:须跑 largestCluster 之前的点否则悬挂轮漏检。axle.go/cargobox.go+harness 对 100742+合成验;货箱无真值靠合成闭环。
- [**激光 A 站(.101)相机纹理补齐 2026-06-15**](finding_laser_a_station_texture_2026-06-15.md) — 原只投影 .102(B)、A 硬涂中性灰;补 .101 采图+calib_101 投影+unit_a 带色。标定 `lidar_cli device calib` 从设备拉,两站名义安装同→config 共用、差异全在 calib JSON。
- [**现场共享标记场自标定 A↔B site 外参 2026-06-15**](finding_laser_site_marker_calib_2026-06-15.md) — 贴 ArUco(36h11),solvePnP+cameraToWorld+umeyama 解 B→A(align=site)。一键 site-calib + 实时取景 site-framing(边扫边推 RGB 帧)。★相机仅转动出帧 0.33fps。★OpenCV 锁 4.6。
- [**激光双机标定改 4 角点 6DoF 2026-06-17**](finding_laser_site_marker_corner_pose_2026-06-17.md) — 仅中心点 umeyama 在 ≤4/共面标记下偏 ~20°(真机融合错位 480mm,从 MinIO 拉云数值证)。改每标记 4 角点带 solvePnP 朝向→单标记即约束 6DoF,2 共面标记复原到机器精度;min_common 4→2。融合/翻转 plumbing 经验证无误。改 lidar_cli 即生效。
- [模拟器在本机的稳定配置 2026-05-08](finding_emulator_setup_2026-05-04.md) — emulator 36.x 必须 `DISPLAY=:1 -gpu host`，并禁 netsim/虚拟 WiFi。
- [Android 实时 WS 与 devserver 注意点](finding_android_realtime_ws_devserver_2026-05-09.md) — App WS 用 http scheme；devserver 包装器需透传 Hijacker。
- [**本地 dev 全栈启动配方 2026-06-04**](finding_dev_stack_local_startup_2026-06-04.md) — devserver(:18808) 手动起易挂→App 全链 unexpected end of stream；附一键 build+env 拉起、dev seed shenhm/shenhm123、症状诊断。
- [KSP2 + Hilt 2.53.x 不兼容](finding_ksp1_required_for_hilt_2_53.md) — `ksp.useKSP2=false` 是当前唯一解。
- [mob3d 设计最新交付核对 2026-05-05](finding_mob3d_handoff_realign_2026-05-05.md) — 现仓本就是 handoff 业务化升级版；本次只补 token + 微观对齐 + FirstPersonViewer 重写 + 删 PlaceholderScreen。
- [P100R3 Android SDK 深度流假成功](finding_p100r3_android_depth_stream_2026-05-14.md) — depth native negotiation 失败但 Java startStreams 返 0；别误查 UI。
- [**VINCreator(eYs3D)逆向=M1.6 UVC 蓝本+双流死旁证 2026-06-01**](finding_vincreator_eys3d_uvc_blueprint_2026-06-01.md) — eYs3D/Etron(非 Berxel)蓝本：老 libusb backend 仍跑双流→旁证 P100R3 双流死真因是 OTG 供电非老栈；fd 注入/权限印证 gomob 修后版对；深度走设备直出路线 A，不移植 eYs3D 软重建。
- [**eYs3D RS-D550 Android 接入 + mode25 config 对齐 gold（续30）2026-06-09→06-10**](finding_eys3d_android_bringup_0bytes_2026-06-09.md) — **续33 真因**：「只开 IF2 深度」是错前提，缺 IF1 彩色持续排空保活(设备只在 libuvc 真流 IF1 才出 IF2 深度)；修法=libuvc 双流，待真机验证。
- [**HLSD8=独立第二颗 RGB 相机；gomob 双相机接入+正射图几何 2026-06-10**](finding_hlsd8_rgb_second_camera_2026-06-10.md) — 实证扫描机=两颗独立 USB 相机：RS-D550 深度+HLSD8 13MP RGB；订正——4160×832 是 HLSD8(标准 UVC)非 eYs3D color。本轮补齐双相机+正射图几何，剩真机出流/双相机标定。
- [**eYs3D native 直驱厂商 C++ 零 Java 出真深度 2026-06-17**](finding_eys3d_zero_vendor_independence_2026-06-15.md) — 弃自研(-EPROTO 未破)，改 native 直调 UVCCamera/UVCPreview/FrameGrabber 取 mode25 真深度(仅留 UsbManager 拿 fd)；真机双流+热力图渲染、valid 50-78%；踩坑/ABI 纪律见正文。
- [**RS-D550 真实开流序列已解码+RHEL9 能出流 2026-06-01**](finding_rsd550_open_sequence_decoded_2026-06-01.md) — 推翻"RHEL9 不兼容"——eSPDI 与自研 libusb 均已 RGBD 双流首光(零运行时 SDK)。激活靠 XU 写+计数器握手(旧 0xE0/0xE3 作废)。深度"列恒定垃圾"真因=模式配错非设备坏;M6.4 推荐 IF1 软件 stereo。
- [Berxel P100R3 自研 SDK 交接 2026-05-29](handoff_berxel_host_sdk_2026-05-29.md) — host parity 已过；2026-05-29 完成 Android 迁移 Step 1：抽 `native/berxel/portable/` libusb-free 层 + `IUvcDevice` 接口 + 修 2 个 host bug，三验证全绿。下一步 JNI 接 `gomob_native.so`。
- [**P100R3 companion 交织真深度+IR/phase 帧 2026-05-29**](finding_p100r3_depth_ir_interleaved_2026-05-29.md) — 订正"只推 IR raw"：0x82 交织真深度(raw/8=mm)+IR，按 pixel[0]==0x0600 分流取深度；6MB blob=温补表非散斑、重建在设备 ASIC→放弃自研结构光(路线 A)；IR 仅作单帧置信。
- [**深度时域降噪 38→10mm 2026-05-29**](finding_depth_temporal_denoise_2026-05-29.md) — 路线 A 直出深度量测头号敌人是逐像素帧间抖动(关 temporal_denoise 换稠密的代价)；滑窗均值压到 ~10mm、零偏移、密度不掉。运动门限必须【自适应噪声底】别硬编，否则噪声>门限退化成每帧 reset 透传。
- [**深度"满屏噪点"=密度优先+设备置信废值;解=真置信+空间降噪 2026-05-29**](finding_depth_noise_real_confidence_2026-05-29.md) — 是真深度非decode bug,但~74%时域不稳;设备confidence饱和废值无效。解=用窗口span派生稳定性真置信(数据保稠密,下游按conf掩码,契合多视角主线)+空间降噪;设备降噪维持关。
- [**P100R3 master 被 color 流挂死只能物理断电 2026-05-29**](finding_p100r3_master_hang_recovery_2026-05-29.md) — color 流挂死 master,自供电 hub 让 xHCI unbind 等软件复位全失效→只能拔 hub 电源砖。补:depth-only 也会因 XU5 keepalive 超时饿死 master,这俩才是 USB 常挂真凶。
- [**P100R3 并发 color+depth = MIX 模式 2026-06-02**](finding_p100r3_mix_color_depth_2026-06-02.md) — **推翻"color/depth 设备特定互斥"**:真因是没进 MIX 模式(原回放 SINGULAR init)。从原厂还原 MIX 配方落资产,enableColor 自动选;host+Android 真机双端 PASS,"开 color 死"根除。
- [**官方 SDK 可逆向 + 设备协商深度帧 1026584≠我方 513280 2026-06-03**](finding_berxel_sdk_acquisition_re_2026-06-03.md) — 官方 so 未混淆可逆向；协商帧含 12B header 被我方丢弃，盲切疑似劈开 depth|IR 拼帧=bad_marker/帧率抖真因(待 DUMP)；marker 分流是官方正解、硬件温补已开勿加软温补，终态按真实帧长拆面弃盲切。
- [**深度飞点剔除：三证合一 2026-05-29**](finding_depth_flying_pixel_removal_2026-05-29.md) — 纯单帧检测过杀 24%；正解=时域不稳∧双侧角度超界夹心∧无共面支撑(放 fuse 后判),命中保 raw+conf 置 0。订正:降级须用 stable_run+frames_seen,旧 reset_age 让慢性飞点删不掉。conf 在 JNI 算了从不 poll 出去=断链待补。
- [**周期纹理致配准位姿翻转 2026-05-31**](finding_periodic_texture_registration_aliasing_2026-05-31.md) — 合成物体用周期正弦纹理→FPFH/Color-ICP 特征混叠→~20% 视角翻转(同输入 1.7↔6mm);换非周期渐变色 10/10 稳。配准 harness 配色须非周期梯度;flaky 融合先疑配准翻转(看顶点/completeness 跳变)。真实重复纹理物体生产同风险。
