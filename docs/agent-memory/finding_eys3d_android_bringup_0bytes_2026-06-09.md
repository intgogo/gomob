# eYs3D RS-D550 Android 取流接入：bulk 通了（2026-06-09 续2 重大突破）

## ★★★ 2026-06-09 续2：bulk 端点出数据了 —— 一个月"0 字节"根除 ★★★

**结论先行**：Android 真机（小米 2510DRK44C，直插无 hub）首次拿到 eYs3D bulk 数据：
`tick color[done=1133 B=2317404 f=1 e=0] depth[done=929 B=2845128 f=2 e=0]` —— color 2.3MB + depth 2.8MB，0 错误。

**真根因（订正之前所有假设）**：bulk 0 字节 **不是** 缺 USB reset、**不是** flash 握手被 gate、**不是** wrap_sys_device 限制，
而是**自研手拼的 arming 开流序列不完整**。把工作的 eSPDI SDK usbmon 抓包（`.dev/eys3d-sdk/sdk_stream_trace.txt`）
里 **arming 段 153 条控制传输逐字复刻**（kProvenArming 自动生成表，见 `native/eys3d/host/eys3d_proven_replay.h`）
+ 补齐 SDK 的开流收尾（COMMIT IF2 → start trigger reg0xF5=0 → CLEAR_HALT(0x81/0x82)），bulk 立刻出数据。

自研旧序列漏的关键：① 全 XU 能力枚举（GET_LEN/INFO/MIN/MAX/RES/DEF 各 selector）；② PROBE 完整协商
（SET→GET_MIN→GET_MAX→SET→GET_CUR，旧版只 SET+GET_CUR）；③ COMMIT IF2 + start trigger + 双 EP CLEAR_HALT。

**proven 流维持心跳 = 中断 EP 0x83 持续轮询**：SDK 全程 ~3ms 轮询 0x83（贯穿整个 60s+ streaming，
ts 1808420338→1823982949），稳态**零控制写**，纯靠 bulk + 中断轮询维持。SDK 连续吐 75MB color + 74MB depth/~10s。

### 2026-06-09 续11：★★★ COLOR 视频上屏！libuvc 连续 + YUYV→RGB 渲染 ★★★
真机 UI 显示 **"COLOR · 6fps"**，color_frames 持续涨——eYs3D 彩色视频端到端跑通（用户目标"看到 COLOR"达成）。
- **关键修**：① 设备 UVC 描述符 color=**YUYV 1280×480**（libuvc 协商出流；mode25 的 MJPEG 1280×256 不在描述符，协商 -51）。
  ② color pump 原只解 MJPEG(BitmapFactory)，对 YUYV 返 null 丢帧（"frames=0"）→ 加 **YUYV→RGB24(BT.601 整数)** 直转分支
  （`Eys3dCameraService.yuyvToRgb24` + size==1280×480×2 判定）。③ Java arming videoMode 沿用 14bit 0x02（描述符匹配）。
- **看门狗已修**：`lastFrameMs` 任一路（color/depth）出帧即刷新，depth pump 看门狗判活以此为准 → color-only 不被误杀，
  实测 color 持续 20s+ 不掉线（FRAME_TIMEOUT 复位 5s）。COLOR 视频稳定可用。
- **剩余**：① DEPTH 双流（libuvc 第二路 stream，IF2 0x82；depth 14bit maxFrame 0x258000，先 uvc_print_diag/试错定格式）；
  ② mode25 正确 metric 深度（需设备暴露对应格式或换协商）；③ 收尾：清诊断日志、uvc_frame_t 布局校准、commit。
- **当前可用**：插 eYs3D → 深度相机页 → COLOR 6fps 实时彩色（YUYV 1280×480→RGB），稳定不掉线。代码未 commit。

### 2026-06-09 续10：★★★ 连续出流达成！libuvc 取流是真解（自研手卷 bulk 是 2 帧停真凶）★★★
**真机实测 color 连续出流：color_frames 52→58→64→71… 持续涨，bytes 63MB→87MB+，~6-7fps，libuvc 内部 cnt 31000→45000 不停。**
彻底验证：**libuvc(saki4510t,内部 libusb100)的传输/payload 管理能连续出帧，自研手卷 async bulk 才是"吐 2 帧停"真凶**（libusb 后端续8 已排除）。
- **落地（已通）**：`native/eys3d/android/eys3d_libuvc.{h,cpp}`（saki4510t libuvc 最小重建头 + dlopen libuvc.so RTLD_GLOBAL + dlsym）；
  `Eys3dFdSession::Run` 走 libuvc：uvc_init2(NULL,"/dev/bus/usb") → uvc_get_device_with_fd(0x3438,0x0206,fd,bus,dev) → uvc_open
  → uvc_get_stream_ctrl_format_size(YUYV,1280,480,5) → uvc_start_streaming(cb)；cb 喂 core_.OnColorFrame。
  Java arming 过滤掉 VS PROBE/COMMIT(wIndex 1/2)，只发 Etron XU(videoMode/counter/flash)，PROBE/COMMIT 交 libuvc。
  vendor 3 .so（libuvc/libusb100/libjpeg-turbo1500）已 jniLibs。
- **遗留小问题**：uvc_frame_t 的 width/height/frame_format 读出错（width=1228800 height=0 fmt=1280）——saki4510t 结构体在 data 与 width 间多了字段，
  我的重建头偏移差 8B；但 **data + data_bytes 正确**(1228800B=1280×480×2 YUYV)，帧数据没问题。修法：校准 uvc_frame_t 布局（加 outbuf/中间字段）。
- **下一步**：① 校准 uvc_frame_t 布局取对 width/height；② 扩 depth 双流（再开一路 uvc stream，IF2 depth）；③ UI 确认 COLOR(+DEPTH) 画面；
  ④ 收尾：FRAME_TIMEOUT 复位、清诊断日志、commit；⑤ mode25(videoMode=36) 取正确 metric 深度。

### 2026-06-09 续9：vendor libuvc 取流集成——可行性已验证，方案锁定（用户选此路）
- **仓库 third_party/libuvc-android 是 pupil libuvc**（无 uvc_wrap/uvc_init2/uvc_get_device_with_fd，链现代 libusb1.0）→ 不能用于 fd 路径；
  必须用 **vendor libuvc.so（saki4510t fork，链 libusb100）**，但其头本仓没有，需按 saki4510t 重建最小头。
- **vendor libuvc.so 导出齐全**（实测）：uvc_init/init2/exit、uvc_get_device_with_fd、uvc_open/close、**uvc_get_libusb_handle**、
  uvc_get_stream_ctrl_format_size(_fps)、uvc_start_streaming/uvc_stream_start/uvc_stop_streaming、uvc_unref_device。NEEDED=libusb100+libjpeg-turbo（已 staged）。
- **集成方案（锁定）**：dlopen libuvc.so → uvc_init2(&ctx,NULL,"/dev/bus/usb") → uvc_get_device_with_fd(ctx,&dev,0x3438,0x0206,NULL,fd,busnum,devaddr)
  → uvc_open(dev,&devh) → **uvc_get_libusb_handle(devh) 拿底层 libusb_device_handle 做自研 Etron XU arming**（videoMode/counter/flash，避免双开）
  → uvc_get_stream_ctrl_format_size(devh,&ctrl,fmt,w,h,5) → uvc_start_streaming(devh,&ctrl,cb,user,0) → cb(uvc_frame*) 喂 core_。
  格式来自 trace：14bit color=YUYV 1280×480@5(maxFrame 0x12c000=1280×480×2)；depth maxFrame 0x258000。双流=两个 uvc stream。
- **ABI 风险点**：uvc_stream_ctrl_t（只传不读，超额分配 256B 兜底）；uvc_frame_t（回调读 data/data_bytes/width/height/frame_format，前置字段按 saki4510t 标准布局）。
- **里程碑**：先单 color 流验证（最小化 ABI/耦合风险），通=libuvc 传输模式坐实为 2 帧停真凶，再扩 depth 双流。
- **代码状态**：libusb100 后端 + 函数表 + Java arming + 全部诊断/复刻已落 master（未 commit），app 稳定（0 帧优雅显示掉线）。

### 2026-06-09 续8：★ libusb100 后端实测——不修 2 帧停，后端被排除 ★
- **已落地 libusb100 后端**（dlopen RTLD_LOCAL + 函数表，eYs3D 会话专用，Berxel 现代 libusb-1.0 不动）：
  - 关键坑：`libusb_init` 默认枚举 /dev/bus/usb（Android SELinux `avc denied read usb_device dir`）→ `op_init2: could not find usbfs` 失败。
    用 **`libusb_init2(ctx, "/dev/bus/usb")`**（saki4510t fork 变体，显式根路径跳过枚举）+ `libusb_get_device_with_fd(ctx,0x3438,0x0206,NULL,fd,busnum,devaddr)`
    （busnum/devaddr 从 /proc/self/fd readlink 解析）+ `libusb_open` 成功开流。
  - 代码：`native/eys3d/host/eys3d_usb_api.{h,cpp}`（函数表 + host 直填）、`eys3d_fd_session.cpp`（Android dlopen 填表 + init2 + get_device_with_fd）、
    `eys3d_stream_loop.cpp`/`eys3d_usb_device.h` 全部 libusb 符号调用走表；CMake 加 `dl`，build.gradle 加 `third_party/eys3d-vendor/lib` jniLibs。
- **★ 实测结果：bulk 仍吐 ~2 帧对(color 2.47MB/depth 2.91MB，err=0 有 EOF)即停，与现代 libusb wrap_sys_device 完全一样。**
  → **libusb 后端（现代 vs saki4510t libusb100）被排除，不是 2 帧停的原因。**
- **剩余唯二嫌疑**（用同一 libusb100、同设备，自研 2 帧 vs VINCreator 连续）：
  ① **libuvc 的传输/payload 管理**（自研手卷 bulk vs pupil/saki4510t libuvc stream.c）；
  ② **Android eSPDI 的 arming（libESPDI::CVideoDevice）与我复刻的 Linux usbmon trace 不同**——Linux trace 那套 arming 在 Linux+libeSPDI 下连续，
     但 Android 路径的设备控制可能不同，缺的那步让设备只吐 2 帧。
- **下一步**：①用 vendor libuvc.so 的 uvc_stream_start（接 libusb100）替自研 bulk，保留 Java arming，看是否连续——通=libuvc 模式差异；
  ②若仍停=Android arming 差异，需 root usbmon 抓 VINCreator 真机 USB 或反汇编 CVideoDevice 比对开流控制序列。

### 2026-06-09 续7：用户选"精炼版只链 libuvc+libusb100"；落地约束=两个 libusb 冲突
- **根因假设收口**：设备控制(Etron XU arming)自研 Java 已 0-fail 复刻；真正差的只剩 **bulk 取流的 libusb 后端**。
  VINCreator = saki4510t/UVCCamera 系（libUVCCamera+libuvc+**libusb100**，路径串 `esp_android_usb_camera_sdk` 实锤）。
  libusb100 = saki4510t fork，导出 `libusb_get_device_with_fd(ctx,vid,pid,serial,fd,busnum,devaddr)`（替代 wrap_sys_device），
  自包含（仅 liblog/libc++/libm/libc/libdl），导出我用到的全部 libusb_* + transfer ABI 稳定（可复用现代 libusb.h header）。
- **落地约束（关键）**：`gomob_native.so` 里 **Berxel(berxel/src+berxel_dual_session_jni) 与 eYs3D 都链现代 libusb-1.0 + wrap_sys_device**。
  直接把整库换成 libusb100 会断 Berxel（libusb100 无 wrap_sys_device）。一个 .so 不能同时链两个同名符号 libusb。
- **推荐落地（最小且不动 Berxel）**：eYs3D 会话侧 **dlopen("libusb100.so", RTLD_LOCAL) + dlsym 出函数表**，
  RunEys3dStreamLoop / Eys3dUsbDevice 的 ~16 个 libusb 符号调用走函数指针表（fill_bulk/interrupt 是 inline 改 struct，ABI 兼容免改）；
  open 用 get_device_with_fd（busnum/devaddr 从 /proc/self/fd/<fd> readlink /dev/bus/usb/BBB/DDD 解析）。Berxel 仍用现代 libusb-1.0 不动。
  vendor 库已落 `third_party/eys3d-vendor/lib/{arm64-v8a,armeabi-v7a}/{libusb100,libuvc,libjpeg-turbo1500}.so`。
- **验证逻辑**：若换 libusb100 后端后连续出流 → 坐实现代 wrap_sys_device 后端是"吐 2 帧停"真凶；后续长期可移植 pupil-libuvc bulk 或保留 dlopen libusb100。

### 2026-06-09 续6：Android eSPDI 栈解剖（用户选"链厂商整栈验证"路径）
- **Android libESPDI.so 不导出 APC_* C API**（实测 nm count=0；只导出 C++ `CVideoDevice::*` + miniz/OpenCL）。Linux SDK 才有 APC_*。
- **Android eSPDI 取流栈**：`libUVCCamera.so`(JNI，RegisterNatives 绑 Java 类 `com.esp.android.usb.camera.core.ApcCamera`)
  → `libuvc.so`(pupil，bulk transfer) + `libESPDI.so::CVideoDevice`(Etron XU 控制) + `libusb100.so`(老 fork)。
  libUVCCamera 还 NEEDED：libeysov / libDepthMSR / libSwPostProc / libhidapi / libdepthfilter（共 9 个 .so）。
- **fd 接入**：`libusb100` 导出 `libusb_get_device_with_fd(ctx,vid,pid,usbfsName,fd,busNum,devNum)`（对应 nativeConnect 入参）→ 包 fd → libuvc/CVideoDevice。
- **集成两条路**：
  A. **嵌 libUVCCamera JNI 整套**：copy 9 个 .so + 反编译的 `ApcCamera`/`USBMonitor.UsbControlBlock`/`IFrameCallback`/`eys_error`/`StreamInfo` 等 Java 类（jadx 产物，需清理可编译）→ Kotlin 调 `ApcCamera.open(ctrlBlock)`/`setVideoMode`/`startPreview`+frameCallback。最贴 VINCreator，但最重。
  A 是用户选的"整栈验证"路径。
  B. **只链 libuvc.so + libusb100.so**（弃 libUVCCamera/CVideoDevice）：`uvc_wrap(fd)` + 自研 Java Etron-XU arming + `uvc_start_streaming`。轻，但 libuvc 标准 PROBE/COMMIT 与 Etron XU arming 耦合需手调，且 libuvc 链老 libusb100 才是真对照（本仓 third_party/libuvc-android 链的是现代 wrap_sys_device，不算对照）。
- **下一步**：走 A——把 9 个 .so 落 third_party/eys3d-vendor/jniLibs，提取 eSPDI Android SDK 的 Java 类（com.esp.android.usb.camera.core + com.serenegiant.usb USBMonitor），在 Eys3dCameraService 加一条"vendor 栈"分支调 ApcCamera 出帧，验证连续流；通了即坐实自研现代 wrap_sys_device bulk 是真凶，再决定移植 pupil-libuvc+libusb100 还是长期挂厂商栈。

### 2026-06-09 续5：非供电（用户证 VINCreator 同机直插正常）；设备=纯 bulk；缺口在 bulk USB 栈
- **用户订正**：不是供电——VINCreator（tests/vincreator-apk）在**同一台手机、同样直插**能连续出流。纯软件差异。
- **设备=纯 bulk，无 isoc**（Java UsbDevice 枚举实测）：IF0/alt0 INT 0x83 mps32；IF1/alt0 BULK 0x81 mps512；
  IF2/alt0 BULK 0x82 mps512。无任何 isoc alt 设置。color+depth 强耦合（仅 COMMIT IF1 时 color 也≈0，须 COMMIT IF2）。
- **VINCreator USB 栈 = eSPDI libESPDI.so + libUVCCamera.so + libuvc.so(pupil, stream.c) + libusb100.so(老 Android fork)**。
  `nativeConnect(ptr,vid,pid,fd,busNum,devNum,usbfsName)` → libusb on fd。取流是 pupil-libuvc 的 bulk transfer。
- **本仓已 vendor `third_party/libuvc-android`**（pupil libuvc 预编译 .so + 头），但**链接现代 libusb1.0.so + wrap_sys_device**
  （与自研同后端），Berxel 是 vendor class 没用它。
- **关键缩小**：自研已把 arming（干净 0 fail）/ COMMIT IF1/IF2 / trigger / CLEAR_HALT / 中断轮询全部复刻成功，
  设备仍**干净吐 ~2 帧对（err=0/有 EOF）即停**。缺口落在 **bulk 取流 USB 栈**：pupil-libuvc + **libusb100(老 fork)** vs
  自研 **现代 libusb wrap_sys_device**。VINCreator 用老 libusb100 → 与 [[finding_p100r3_dual_endpoint_host_kill_2026-05-18]]
  "Android 老 libuvc/libusb stack" 同一战场，但**结论相反**：eYs3D 是老 libusb100 *能用*、现代 wrap_sys_device *吐 2 帧停*。
- **已排除**：arming 完整性 / native tail / 中断 EP 心跳 / retry / reset / pacing / 控制-bulk 交织顺序 / color warmup 时序 / 供电。
- **下一步（按序）**：①用 `third_party/libuvc-android`（pupil-libuvc）替自研手卷 bulk 取流（保留 Java Etron-XU arming）跑一次——
  若仍 2 帧停，则真凶=现代 wrap_sys_device 后端，需换 libusb100 老 fork 路径；若通，则真凶=自研 bulk transfer 模式。
  ②兜底：直接链 VINCreator 的 libESPDI+libUVCCamera+libuvc+libusb100 验证（破零厂商 SDK，仅定位用）。
- **环境痛点**：标准 UVC 设备反复 open/close 后从 app deviceList 掉线（脏状态 arming 也开始 STALL），每轮干净测试需物理重插。

### 2026-06-09 续4：软件已完整复刻 SDK，仍"吐 2 帧对即停"，矛头转物理/供电
- **干净设备 arming 0 失败**：刚插的干净设备，Java arming `writes ok=40 fail=0 | reads ok=113 fail=0`（全 153 笔成功）。
  STALL 只在跑过几次的脏状态出现（uvcvideo + 反复 open 累积）。**所以 STALL 不是停流主因**。
- **UVC 头诊断**：设备 `err=0`（无错误位）、有 `eof`（干净帧尾），即设备**干净地吐 1 color + 2 depth 帧后停发**（NAK），非报错非断连。
- **native tail 全成功**：`COMMIT_IF2=26 trigger=4 clrHalt81=0 clrHalt82=0`。
- **中断 EP 0x83 主动轮询（timeout=3ms 重提，intr done 持续涨到 2700+）无效**：流仍停 → 中断 EP **确定不是**心跳。
- **color 与 depth 耦合**：want={color-only} 时 color=0 字节 → 设备 color 流依赖 depth 也 COMMIT（14bit interleave），
  无法软件层单流隔离功耗。
- **结论**：软件已把工作的 RHEL9 eSPDI SDK 开流序列**完整复刻并全部成功**（arming 干净 / commit / trigger / clrHalt /
  中断轮询），但 Android 真机仍吐 2 帧对即停，RHEL9 PC 同序列连续 150MB。差异落在**物理层**：最可能是手机 OTG 供电
  不足以维持 color+depth 双流持续采集（与 [[finding_powered_hub_unblocks_vivo_dual_stream_2026-05-27]] 同类：带电 hub 救活双流）。
- **下一步**：①带电 hub 接 eYs3D 重测（最高优先，本家族双流既有解）；②若非供电，试 COMMIT IF2 延后到 color 起流后再发
  （SDK 时序：color 数据 1814023154 早于 COMMIT IF2 1814148301，自研是 COMMIT IF2 紧跟 color bulk 提交即发）。

### 2026-06-09 续3：STALL = 设备状态（uvcvideo 探测），非传输；arming 已挪 Java
- **JAVA-CT 探针**：新开设备裸态下 Java `controlTransfer` 的 counter GET(`a1 81 0a00 0400`)**成功**，libusb 同请求 STALL
  → 一度判定"libusb 传输路径问题"。但把**整段 arming 挪到 Java** controlTransfer 后，失败数与 libusb **完全相同**
  （`writes ok=36 fail=4 | reads ok=95 fail=18`，仍是 counter GET + flash 写）→ 订正：**STALL 是设备状态依赖**，
  全量 arming 上下文里 Java 也 STALL，裸态探针成功只因 counter 刚 SET=01 状态干净。
- **根因指向 uvcvideo 预探测**：eYs3D 是标准 UVC class，内核 uvcvideo 在枚举即绑定生成 `/dev/video2,3`（实测时间戳=插入时刻），
  其 XU 能力探测把设备 entity-4 flash/counter 状态机推到一个位置，使随后自研 arming 的 counter GET-back / flash 写 STALL。
  RHEL9 host eSPDI 抓包无 uvcvideo 探测 → 零 STALL。USB reset 无用（重枚举后 uvcvideo 再探测，状态照旧），且 reset 会 wedge。
- **架构变更（保留）**：arming 走 Java `UsbDeviceConnection.controlTransfer`（[Eys3dProvenArming.kt](../../core/native-bridge/src/main/kotlin/io/gomob/nativebridge/camera/Eys3dCameraService.kt) `armViaJava` + 生成表），
  native `Eys3dOpenPlan.external_arming=true` 跳过 arming 只做 bulk + COMMIT IF2/触发/CLEAR_HALT。Java==libusb 同 STALL，
  但 Java 是 Android 正解架构（设备归 Java 持有），保留。
- **设备反复掉线**：标准 UVC 设备在反复 open/close（+ watchdog 复位 + uvcvideo 争用）下，~3 session 后从 app
  `UsbManager.deviceList` 消失（系统 `dumpsys usb` 仍在）→ active() 回落 Berxel。需物理重插 / 唤醒恢复。
- **下一步（设备回来后按序）**：①跑当前构建抓 UVC payload 头诊断（tick 的 `err=/eof=/h1=`）——看设备停流是 EOF 干净结束
  还是 ERR；②若 uvcvideo 是真凶，试 libusb_detach_kernel_driver / 更彻底卸载 uvcvideo（/dev/video2,3 force-claim 后仍在，
  疑未真卸）；③试整段 arming 重跑 2-3 次看 STALL 能否自愈同步。

### 仍未解决（2026-06-09 续2 frontier）：流吐 ~2 帧即停 + arming 部分 STALL
1. **流吐 ~2 帧(~2.3MB)即停**：byte 计数冻结，URB inflight=32 全 NAK。device 吐完缓冲就停。
   已加中断 EP 0x83 持续轮询（8 URB），但 intr done=0（URB 挂起不完成）且流仍停 → 单纯挂中断 URB 不够。
2. **counter GET-back(`a1 81 0a00 0400`)+ entity-4 flash 写(`21 01 0b00 0400`)在 Android usbfs 下 STALL(LIBUSB_ERROR_PIPE)**，
   RHEL9 host 同设备同请求零 STALL。已加 retry-on-PIPE（协议 STALL 下个 SETUP 自动清），**未及真机验证**（设备 wedge）。
   强假设：flash ZD 表读是"连续采集"配置的前置，缺它 device 只做默认 ~2 帧捕获。
3. **下一步**：①真机验证 retry-on-PIPE 能否推过 counter GET/flash 写；②若仍 STALL，逐字对齐 SDK 中断 EP 轮询节拍
   （短 timeout 提交→unlink→重提，SDK 完成态 status=-2=ENOENT=超时 unlink，非真数据）；③确认流停与 flash STALL 是否同因。

### ⚠ reset 有害已移除（2026-06-09 续2）
`Eys3dCameraService.startInternal` 两段式 USB reset（`cameraResetByFd`/libusb_reset_device）**已删**：
reset 重枚举使 fd 失效 + 丢 per-instance USB 权限，**反复 reset 把设备 wedge**（app `UsbManager.deviceList`
不再列出 0x3438:0x0206，dumpsys 系统侧仍在）→ active() 回落 Berxel。wedge 后**需物理重插相机**恢复。
改单段：open→force-claim(detach uvcvideo)→CameraStack.start，与 SDK 一致（SDK 开流无 reset）。

---

# eYs3D RS-D550 Android 取流接入：UI 通 + 4 bug 修，但 bulk 端点恒 0 字节（2026-06-09 续1，已被上方续2 推翻 reset/flash-gate 假设）

## Why（背景与结论）

把"3D→深度相机"页接通 eYs3D RS-D550（0x3438:0x0206）。**UI 层已完工并真机验证**：页面厂商无关化（纯自动识别），
插 eYs3D 自动走 [Eys3dCameraService]，插 Berxel 走 BerxelService，互不退化。真机日志确认页面正确识别 eYs3D、
开设备、arm mode25。

接通后首次在真机跑 eYs3D 取流，**暴露并修复 4 个先前从未被触发的 native bug**（页面没接 eYs3D 前这条 native 路从没在端上跑过）：

1. **double `libusb_close` 崩溃**：`RunEys3dStreamLoop` 内 `Eys3dUsbDevice` 析构已 close handle，`Eys3dFdSession::Run()` 又 close 一次 → use-after-free。修：Android 侧 Run() 不再 close（与 host 一致）。
2. **`libusb_reset_device` 在 Android 重枚举杀 fd**：wrap_sys_device 下 reset 触发端口重枚举，wrap 的旧 fd 立刻失效（→ `epoll hang-up`、num_connects 攀升、设备"掉线"）。修：流循环内 reset 用 `#if !defined(__ANDROID__)` 屏蔽，改两段式（见下）。
3. **uvcvideo 独占 UVC 流接口**：eYs3D 是标准 UVC class（class 14），内核 uvcvideo 唤醒即绑定并生成 /dev/video2,3，独占 0x81/0x82 → libusb bulk 取 0 字节。修：Java 侧 `UsbDeviceConnection.claimInterface(intf, true)` 强制 detach uvcvideo；native 侧 Android 跳过 libusb_claim（同 fd 不能二次 claim，usbfs 提交 URB 只要 fd 持有认领即可）。（Berxel 是 vendor class 无此问题。）
4. **计数器握手错位**：`RunEys3dStreamLoop`（M6.8b 重构的 host/Android 共用核心）的 selector 0x0a 计数器序列与 proven replay（eys3d_replay_stream）不一致——漏了 color-neg PROBE 与 depth PROBE 之间的两个计数器。固件把计数器当严格事务号，错位则后续 commit/trigger 写不进。修：对齐为连续 0x01..0x16。

**仍未解决（真正的 M6 frontier）**：以上全部修好后，bulk 端点仍 **0 字节 0 错误**。已穷举排除：
reset✓（cameraResetByFd rc=0，两段式 reset→重枚举→新 fd 开流）、uvcvideo detach✓、新 fd✓、USB2✓（端点实测
bulk/max_packet=512，mode25 USB2 plan 正确）、计数器对齐✓、URB 提交✓（inflight=32）、去 IR 写✓（与 proven 逐字）、
暖机✓（放宽到 12s 仍 0）。两个 plan（mode25 与 14bit ProvenWrongModePlan）皆 0 字节 → **与 arming plan 无关**。

关键对照：**这正是 [[finding_rsd550_open_sequence_decoded_2026-06-01]] 里记的"自研 libusb 控制全对但 bulk 被 NAK 0 帧"**
——在 RHEL9 **host** 上同样症状（eSPDI SDK 出 60/60 帧，自研 libusb bulk NAK 0 帧）。即：**0-bulk 是自研取流栈的既有缺口，
不是（纯）Android/wrap_sys_device 问题**。device 对 bulk IN 持续 NAK = 流引擎没真正启动；vendor SDK 能启、自研 arming 没完全复刻。

## ★ 根因已定位（2026-06-09 续）：跳过的 selector 0x0b ZD/flash 读 = 0 帧真因

逐字 diff 工作的 SDK usbmon 抓包 `.dev/eys3d-sdk/sdk_stream_trace.txt` vs 自研序列：SDK 开流做 **45 条 SET_CUR**，自研只 34。
差的就是 **开流 arming 里那段 selector 0x0b/0x0c ZD/flash 读握手**（自研 `eys3d_replay_stream.cpp:84` 注释明写"selector 0x0b 是标定，libusb 下 STALL 且**非开流必需，跳过**"）——但 SDK 做它就出流、自研跳它就 0 帧。**"非开流必需"这个假设是错的，跳过 0x0b 正是 bulk NAK 0 帧的真因**。当年判 0x0b STALL 是因为只发了裸 0x0b 读、没走完整握手。

SDK 完整 0x0b/0x0c 握手（在 ctr 0x0e 与 0x0f 之间，每块）：
```
Ci a1 85 0c00 0400 (2B 状态) → Ci a1 86 0c00 0400 (1B 状态)
Co 21 01 0b00 0400 (16B) = <block>410301 0001<hdr> 00200000 00000000   # 写 flash 读请求
Ci a1 85 0c00 0400 (2B) → Ci a1 86 0c00 0400 (1B) → Ci a1 81 0c00 0400 (32B 读回 flash 数据)
Co 21 01 0b00 0400 (16B) = <block>410301 0001<读回的头如0ecf> ...               # 头喂回再读下一段
... 共 4 块(block 0x0f,0x10 各两次)，中间穿插 ctr 0x0f/0x10/0x11(写 N 设备回读 N+1)
```
之后才是 PROBE-neg(IF1/IF2) → COMMIT IF1 → 挂 bulk URB(2048B) → **~1.5s 暖机后 ep1 出数**(实测 trace line 354)。

## ★★ 实测：0x0b 握手已补但 entity-4 flash 访问 STALL（2026-06-09 续）

已在 RunEys3dStreamLoop 补完整 0x0b/0x0c 握手（entity-3 wIndex0x0300 预读 + entity-4 wIndex0x0400 状态读/写请求/0c00 32B 读回 + 计数器交织），真机实测：
`zd handshake: ok=9 fail=8` —— **entity-3(0x0300) 预读全成功，但 entity-4(0x0400) 的 selector 0x0b/0x0c flash 访问全 STALL（8 笔失败）**，bulk 仍 0 字节。
同一笔 `21 01 0b00 0400` 控制传输：同设备上 eSPDI SDK 成功(C 0 16 >)、自研 libusb STALL。其它 entity-4 XU 写(selector 3 videoMode `0300 0400`、计数器 `0a00 0400`、PROBE)全成功 → **唯独 selector 0x0b/0x0c(flash) STALL**。

SDK 能成功的差异（未复刻的前置条件）：① SDK **持续轮询中断端点 0x83(Ii)**，自研全程不碰中断 EP；② SDK 开设备到开流之间有 **~4s phase-1 设置期**（大量 Ii 轮询 + entity-3 0b 读），自研开流序列是连续快发，无延时/轮询。flash 状态机很可能要靠中断 EP 服务 / 时序就绪才解锁。

## How to apply（下一步，聚焦 eSPDI flash 解锁）

1. **补中断端点 0x83 持续轮询**（异步 interrupt URB 或开流前轮询若干次）+ **phase-1 间隔/重试**，再试 entity-4 selector 0x0b flash 访问是否还 STALL。这是当前最强假设。
2. 若仍 STALL：逐字对照 SDK phase-1（trace 行 1-280，ts 1808xxx）完整复刻设备 open 序列（含所有 Ii 轮询与 entity-3 0b 读），不只 phase-2 开流段。
3. flash 读回数据（每机标定）arming 阶段不需要，可丢；只需让 device 认这套握手"做过"以解锁流引擎。地址 000a/0ecf/0edb 是型号固定偏移可硬编码（非每机），但握手必须真发成功。
4. 兜底：若纯自研 libusb 始终 STALL 0x0b，考虑该步走 SDK eSPDI/Android UVC ioctl，其余仍自研——但破"零厂商 SDK"，仅最后手段。
5. 收尾：bulk 通后清理 bring-up 临时件（eys3d_stream tick/zd 诊断日志、IR 写注释、FRAME_TIMEOUT 暖机值 5s→按需）。
6. mode25(videoMode=36) 仍待 bulk 通后验深度真 metric。

代码现状（master，未 commit）：UI 集成 + 4 修复 + 两段式 reset + 诊断日志（eys3d_stream tag，per-tick completed/bytes/frames/errs/inflight）均已落，app 不再崩（0 帧时优雅显示"eYs3D 流掉线"）。诊断日志与 IR-写注释属 bring-up 临时，bulk 通后清理。

相关：[[finding_rsd550_open_sequence_decoded_2026-06-01]]（proven 开流序列 + bulk NAK 旁证）、[[finding_vincreator_eys3d_uvc_blueprint_2026-06-01]]（eSPDI/VINCreator 逆向蓝本）。

## ★★★ 续12（2026-06-10）：COLOR+DEPTH 双流真机连续出流跑通（IF2 手动 bulk）

里程碑：eYs3D RS-D550(3438:0206) 在 2510DRK44C 真机上 **color+depth 同时连续出流**（各 290+ 帧/30s、0 停顿）。

### 设备真实拓扑（dumpInterfaces 实测，权威）
- IF#0 cls=14.1(VideoControl) — ep0x83/INT/mps32
- IF#1 cls=14.2(VideoStreaming) — **ep0x81/BULK/mps512 = COLOR**
- IF#2 cls=14.2(VideoStreaming) — **ep0x82/BULK/mps512 = DEPTH**
两个独立 VS 接口、各自 bulk 端点，全 alt=0（纯 bulk，无 isoc 备用设置）。

### 关键发现：saki4510t libuvc 只注册 IF1
诊断扫（get_stream_ctrl_format_size 试各格式）全部解析到 `iface=0`，只有一组帧（frame4=1280×480 color / frame1=1280×960 maxFrame0x258000 / frame3=640×480）。`uvc_stream_open_ctrl(bInterfaceNumber=2)` → **rc=-2**。原因：该设备 UVC 描述符有非标子类型（logcat `UsbVCInterface: Unknown VC subtype 0x7/0xd`），libuvc 的 VC 集合解析只挂上 IF1，IF2 永不注册 → **libuvc 多流 API 拿不到 depth**。

### 解法（已落，可复用）：借 libuvc 的 libusb handle 手动跑 IF2 异步 bulk
- color 仍走 libuvc（IF1，连续稳）。
- depth：`uvc_get_libusb_handle(devh)` 拿到 libuvc 内部 libusb100 的 handle → 自己 claim IF2 + PROBE/COMMIT + 在 ep0x82 挂 **异步 bulk URB（16 在飞，timeout=0，回调内去 UVC 包+组帧+自重投）**。
- URB 回调由 **libuvc 自带的 libusb 事件线程驱动**（不另起事件循环），与 color 共用一个 event loop → 这正是 color 连续的同款机制，**避免了"手卷同步 bulk 2 帧停"**。
- libusb 后端一致性：dlopen("libusb100.so") 命中 libuvc 的 NEEDED 同实例（同 soname、同 context），dlsym 出的 submit/alloc/... 与 handle 配套；**绝不能用 RTLD_DEFAULT**（会命中 gomob_native 直链的现代 libusb-1.0，context 不匹配）。
- UVC bulk 去包：每 URB=一个 payload，剥 buf[0]=bHeaderLength 头，按 buf[1] bit1(EOF)/bit0(FID) 组帧；STALL→clear_halt；CANCELLED→停。
- 代码：`native/eys3d/android/eys3d_fd_session.cpp` 的 `DepthBulk`/`StartDepthBulk`/`DepthXferCb`。

### 仍未解决：14bit 模式 depth 内容是垃圾（下一步）
- depth 真帧 1228800B(=1280×480×2) 连续到，但 DiagRawDepth：`col640_distinct=1/480 [列恒定]`、min=0/max=65526 → **疑非真 metric 深度**（单列采样，证据偏弱）。
- 根因方向：14bit(videoMode=0x02) 本就退化（color 能出但 depth 垃圾）；真 metric 需 mode25(videoMode=36)，但 mode25 的 color(MJPEG 1280×256) **不在设备 UVC 描述符**（libuvc 协商 -51），故卡在 14bit。
- 注：Eys3dCameraService 日志 `开流 → mode25` 是**陈旧标签**，实际 arming 是 14bit(0x02)，无模式不匹配。
- 下一步：落盘原始 depth 帧（`DumpRaw`→ /data/data/io.gomob.scan.debug/files/，run-as 拉）离线分析布局/真伪；判断是「解码错」还是「必须换 mode」。

### 设备脆性
进程被杀（reinstall）打断流后未净关闭 → 设备 wedge，下次开流 color/depth 全 0（URB 挂着无回调）。**需拔插一次**恢复。生产需保证 stop 路径净关闭（StopDepthBulk cancel+free + uvc_stop/close）。

## 续13（2026-06-10）：14bit depth 内容确认为「ASIC 退化图样」，真深度必须 mode25

落盘 3 帧 raw depth 离线分析（`.dev/eys3d_depth_analyze.py` + 直接字节查 + 渲 PNG）：
- 1228800B = 614400×u16-LE，~50% 零，max=0xFFF6（**非 14bit 上界**），distinct≈637。
- 渲图：**竖条噪声 + 半幅全黑**（列相关、非 2D 深度光栅）；换 640/byteswap 仍竖条 → 不是布局/字节序问题，是**内容本身退化**。
- 与 [[finding_rsd550_open_sequence_decoded_2026-06-01]] 行 54/84 完全吻合：14bit 模式设备深度 ASIC **不真算视差，吐退化图样**；该 finding 的 mode25 配方=**depthType=36 + color 1280×256 MJPG + depth 640×128 + interleave off**。
- **libuvc 为何拿不到 mode25**：该设备描述符里 MJPEG 帧子类型 0x07 被 Android/libuvc 报 `Unknown VC subtype 0x7` → libuvc 只解析出 YUYV，协商 MJPG=-51；加上 libuvc 只注册 IF1（见续12）→ **libuvc 走不通 mode25**。

### 结论：拿真 metric 深度的路线（下一阶段，substantial）
14bit 这条（libuvc YUYV color + 手动 bulk depth）**plumbing 完备但内容天生是垃圾**，到此为天花板。真深度只能 mode25，而 mode25 libuvc 走不了 → 必须 **color+depth 都改手动 bulk**：
1. arming videoMode 0x02→0x24（depthType=36）。
2. color 手动 bulk ep0x81：MJPG 1280×256（PROBE/COMMIT mode25 color probe）。
3. depth 手动 bulk ep0x82：640×128（×2B；可能含状态行）。
4. 事件循环：续12 的"借 libuvc 事件线程"依赖 libuvc 开流；若 color 不再走 libuvc，需自起 libusb 事件线程（注意旧 RunEys3dStreamLoop 自起事件循环曾 2 帧停——真因疑在 payload/重投管理，本次手动 bulk 已证回调自重投可连续，可复用该模式）。
5. router/帧尺寸按 640×128 改；depth=ASIC 视差 u16-LE，Z=ZDtable[disp] 或 fx·B/disp。

## 续14（2026-06-10）：用户订正 — 当前"彩色"实为低分辨率双目对，真彩色高分辨率（必须查 vincreator）

用户实测订正（关键）：
1. gomob 现在的 COLOR（YUYV 1280×480）**中间一分为二、像两个相机** → 它就是**双目立体对**（L+R，2×640），不是真彩色。
2. **原厂 VINCreator 的 COLOR 分辨率非常高，接近 4000 宽**（绝不是 1280）。≈4000 → 大概率是高分辨率 L'+R' 立体对（2×~1920）或高分辨率 MJPG。
3. 用户要求：**做 eYs3D 必须参考 `tests/vincreator-apk` 反编译代码**（jadx 树 `.dev/vincreator-jadx/`）。

vincreator 逆向已确认的选模机制：
- `CameraModeManager.pidToIdentifier(pid)`：**pid 518(0x0206) → "206"** → `assets/CameraModes/camera_modes_206.csv`（max 1280×960）；pid 1360(0x0550)→"550"。
- CSV 列：`MODE;DESC(L'+D 等);L_RESOLUTION(彩色);D_RESOLUTION(深度);K;T;BITS;COLOR_FPS;DEPTH_FPS;USB_TYPE;RECTIFY;INTERLEAVE_FPS`。
- **但实际可用分辨率来自设备 UVC 描述符的 `StreamInfo[]`（MPreviewSettings.genResolution / mSupportedColorRes），CSV 只给预设/默认映射**。CSV 的 1280×960 上限 ≠ 设备真实上限。
- 故真高分辨率彩色帧在**设备描述符**里（libuvc 漏解析 MJPEG 子类型 0x07，所以没看到）。

下一步：抓 `conn.rawDescriptors`（已加 dumpRawDescriptors→ /data/data/io.gomob.scan.debug/files/eys3d_raw_descriptors.bin + logcat hex），离线解析全部 color/depth 格式分辨率，定位 ~4000 宽高分辨率彩色帧，再决定取流模式（大概率 color+depth 都手动 bulk + 高分辨率帧索引 + mode25 深度）。

## 续15（2026-06-10）：设备完整描述符 + VINCreator ROSIE4 默认模式（权威）

`conn.rawDescriptors`（884B）解析（`.dev/parse_uvc_raw.py`），设备真实能力：
- **IF1 color ep0x81 BULK**：FORMAT[1]=YUYV 5 帧（2560×960/640×480/320×240/**1280×480**(现用,=双目对)/640×240）；
  **FORMAT[2]=MJPEG 9 帧**（**2592×1944=5MP**/2560×960/1600×1200/1280×960/1280×720/1280×256/800×600/640×480/320×240）。
- **IF2 depth ep0x82 BULK**：YUYV 5 帧（1280×960/1280×256/640×480/**640×128**=mode25 深度/320×240）。
- IF0 VC ep0x83 INT。
- **关键**：单镜头高分辨率彩色全在 **MJPEG**（libuvc 漏解析子类型 0x07，所以之前只看到 YUYV → 误用 1280×480 双目对当彩色）。2560×960 是双目对（"一分为二"），单镜头真彩色是 2592×1944/1600×1200/1280×720 等 MJPG。

VINCreator ROSIE4(identifier 206=ComposerKt.referenceKey) 默认（`CameraModeKt.DEFAULT_CAMERA_MODES`，构造 `CameraMode(id,isUsb3,mode,videoMode,rgb{en,Size,fps},depth{en,Size,fps})`）：
- **USB2(手机 OTG,我们这条)**：`mode=25, videoMode=36(0x24), color 1280×256@5, depth 640×128@5` = **mode25 真深度**。
- USB3：`mode=12, videoMode=4, depth 640×480@10, rgb 关`。
- videoMode 寄存器(reg 0xf0)=36 才出真 ASIC 深度；当前 14bit(0x02)是退化图样（续13）。
- **CSV mode 列 ≠ videoMode**：CSV mode 25 → videoMode 36。

### 下一步路线（拿真高分辨率彩色 + 真深度）
1. 默认 1280×256 太低；用户要高分辨率 → 取 MJPEG 单镜头帧（2592×1944 或 1600×1200 或 1280×720）。
2. MJPEG libuvc 走不了 → **color 也改手动 bulk**（ep0x81，按 EOF 组帧，变长 MJPEG；Kotlin color pump 已有 BitmapFactory 解码路径）。
3. 真深度 → videoMode=36 + depth 640×128（IF2 frame4）。
4. **未定**：高分辨率彩色(2592×1944)用哪个 videoMode？是否能与深度并发？默认 videoMode=36 配的是 color 1280×256；2592×1944 是 VINCreator 设置里手选的高分辨率，对应 videoMode 待定（需 usbmon 抓 VINCreator 高分辨率态，或实验）。可能高分辨率彩色与深度不能同时（硬件带宽/模式互斥）。
5. 事件线程：保留 libuvc open + uvc_start_handler_thread 驱动两路手动 bulk URB（续12 机制），或自起 libusb 事件线程。

## 续16（2026-06-10）：MJPEG 高分辨率彩色的死结 — libuvc 不认 MJPEG + 全手动 2 帧停

真机实测两条路都验证完，结论确定：
- **libuvc(saki4510t vendor .so) 不解析本机 MJPEG**：`get_stream_ctrl_format_size(MJPEG, 任意分辨率)` 全 **rc=-51 fmt=0**（格式没注册）。只注册了 UNCOMPRESSED(YUYV)。故 libuvc 走不了单镜头高分辨率 MJPEG。
- **libuvc YUYV + 手动 depth bulk = 连续**（已证 74 帧/各路不停）：color YUYV 1280×480(IF1, libuvc) + depth(IF2, 手动 async bulk 搭 libuvc 事件线程)。这是当前稳定基线（但 color 是双目对/分屏，depth 是 14bit 退化）。
- **全手动 libusb100 双 bulk = 2 帧停**：claim/SET_INTERFACE/COMMIT/触发(F5=0)/中断 0x83 心跳/32URB/5s 超时 全加上，仍 color=2+depth=2 即停（device 吐 ~2.4MB 缓冲后不再发，URB 完成转空包刷屏）。**复刻 libuvc startup 各步都没破 2 帧停** → 设备保活魔法在 libuvc bulk 传输管理内部，难复刻。
- 关键因果：device stereo→depth 流水线只在 **IF1(color) 被持续排空**时才出 depth；libuvc 的 YUYV color 排空能保活，我的手动 color 排空 2 帧即停 → 整条死。

### 拿单镜头高分辨率 MJPEG 的真实选项（都 substantial）
A. **重编 libuvc 支持 MJPEG**：用 pupil libuvc(third_party/libuvc-android 源码,现代,解析 MJPEG 正确) + 现代 libusb wrap_sys_device(fd 注入)，NDK 编出能解析 MJPEG 的 libuvc.so → libuvc 连续出 MJPEG + 手动 depth 搭车。最干净的"对"解，但要建 native 构建 + fd 路径 + 验证。
B. **破全手动 2 帧停**：继续逆 libuvc bulk 传输管理找保活点（研究性，不确定）。
C. **接受高分辨率双目 YUYV 2560×960**(libuvc 直接能出连续，但分屏)。

vendor libuvc.so 为何不认 MJPEG 待查（描述符 FORMAT[2] MJPEG 9 帧明明在；疑 saki4510t parser 对本机 VS 布局有 bug）。

## 续17（2026-06-10）：高分辨率双目 2560×960 彩色 + 深度 连续出流（用户选 C）

用户选"先用高分双目 2560×960"。落地 + 真机验证：
- **COLOR = libuvc YUYV 2560×960@5(高分双目对)**，真机连续(74 帧不停, 4915200B/帧)，UI 显示"彩色单流 · 2560×960"。
- color 协商:Run() 按 {2560×960@5/@3/@10, 1280×480@5} 顺序 get_stream_ctrl_format_size,首个 rc=0 起流。
- **DEPTH = 手动 async bulk(IF2 ep0x82) 搭 libuvc 事件线程**(同续12 机制),连续(75 帧)。
- Kotlin color pump 按字节数判分辨率(2560×960=4915200 / 1280×480=1228800)→ yuyvToRgb24(尺寸驱动,通用)。
- **修:depth 到 UI**——`MAX_DEPTH_W` 640→1280,否则 depth pump buffer(640×480×2=614400) 装不下 14bit depth 真帧(1280×480×2=1228800)→ "等待深度帧"。
- 架构定论(当前 eYs3D 取流主干)：**libuvc 起 YUYV color(IF1,连续) + 手动 bulk depth(IF2,搭 libuvc 事件线程)**。MJPEG 单镜头高分辨率仍需重编 libuvc(续16 选项 A,未做)。

### 设备脆性(开发流程注意)
force-stop 打断流 → 设备 wedge(下次 0 帧)，软恢复(nav away+settle 20s)无效，需**物理拔插**。自然休眠也能解 wedge。
nav 自动化:`.dev/eys3d_nav.py go`(debug 深链 action=DEBUG_BERXEL_START + route=scan3d/depth-camera/info 直达) / `away`(回首页干净释放)。

## 续18（2026-06-10）：导航修复 + reset 自愈 + 相机自动休眠

- **导航 bug**：深链 route `scan3d/depth-camera/info` 是 iHawk 静态内参页(无视频)；要的是 `scan3d/depth-camera`(DepthCameraScreen 实时彩色+深度)。`.dev/eys3d_nav.py` 已改。
- **开流前 USB reset 自愈**：`eys3d_fd_session.cpp::Run()` 起流前 `libusb_reset_device(h)`，清 wedge(0 字节)。本机 2510DRK44C reset rc=0 时 wrap 的 fd 存活、随后出流正常。
- **★ 相机硬件自动休眠**(用户告知)：eYs3D RS-D550 不操作一段时间会**关机**(从 USB 总线整个消失)。区别 wedge：0 字节但仍枚举=wedge(reset 解)；整个掉出总线=休眠(需拔插/重上电)。开发期长暂停会触发休眠。手机也会 Dozing → 测前 `svc power stayon true` + KEYCODE_WAKEUP。

## 续19（2026-06-10）：pupil libuvc 重编接入 → MJPEG 协商通；但 0 数据真因=设备模式 + 彩色/深度硬件互斥

用户选"重编 libuvc 支持 MJPEG"。复用 M1.6.4 已建资产落地：
- **pupil-labs libuvc**(`third_party/libuvc-android/`，含 `frame-mjpeg.c` + `uvc_wrap` fd 注入)patchelf：soname→`libuvc_pupil.so`(避撞 vendor libuvc.so)、NEEDED→`libusb-1.0.so`。打包 `third_party/libuvc-android/jniLibs/`，CMake `IMPORTED uvc_pupil` 直链 gomob_native。
- **Run() 重写**(pupil 直链)：`libusb_set_option(NO_DEVICE_DISCOVERY)`(Android 非 root 必需，否则 libusb_init 扫 /dev/bus/usb 卡死)→ 自建 libusb ctx → `uvc_init(ctx)` → `uvc_wrap(fd)` → reset → MJPEG `uvc_get_stream_ctrl_format_size` + `uvc_start_streaming` + 手动 depth bulk(IF2)。
- **★ 自起 pump 线程**：pupil `uvc_start_handler_thread` 只在 `own_usb_ctx`(自建 ctx)时起事件线程；我们传外部 ctx → 必须自己 `libusb_handle_events_timeout` 循环驱动 color+depth 的 transfer。
- 深度 bulk U 表绑直链 libusb-1.0(`Eys3dUsbSetHostApi()`)，与 pupil 同实例。

**实机结果**：`uvc_wrap rc=0` ✓ `reset rc=0` ✓ **`MJPEG 2592×1944 协商 rc=0`** ✓(vendor 是 -51，pupil 解析 MJPEG 成功=硬骨头啃下)`start_streaming rc=0` ✓ `depth PROBE set=26` ✓ —— **但 color+depth 全 0 字节**，URB 5s 后被看门狗 cancel(status=3)，设备一字节不发。

**0 数据真因 = 设备模式不匹配**：Java arming 发 `videoMode(reg 0xF0)=0x02`(立体 DEPTH_DATA_14BIT)把设备配成立体深度模式；YUYV 双目对匹配它能出流，但 MJPEG 单镜头是**另一种设备模式**，不出数据。reg 0xF0 = depthDataType(eys3d_protocol.h)。

**★★ 硬件铁律(camera_modes_550.csv = RS-D550 真表，匹配 UVC 描述符)**：
- `L(R)` 单镜头模式(14=2592×1944 / 15=1600×1200 / 16=1280×960 ... MJPEG)= 高分彩色，**无深度**(col depth=null)。
- `L'+D scale_down`(28=1280×960 MJPEG 彩色+640×480 深度 / 30=+320×240 / 31=640×480+320×240)= **彩色+深度同时**，中高分。
- `L+R`(29=2560×960 MJPEG)= 双目对(就是之前那个暗的"彩色")。
- ⇒ **最高分彩色(2592×1944)与深度互斥**；要同时彩色+深度上限 mode 28(1280×960 彩色+640×480 深度)。206.csv(全 L'+D 小尺寸立体)是旧/错表，本机用 550 表。

**待解**：mode 14 / mode 28 的正确 videoMode(reg 0xF0) + arming 序列(VINCreator `ApcCamera.setVideoMode(mode.getVideoMode())` → 反汇编取值)；现 arming 写死 0x02 立体，需按目标 color 模式改。pupil libuvc MJPEG 流水线本身已就绪，缺的是把设备切到对的模式。

## 续20（2026-06-10）：硬件分解 + pupil 在 Android 不出流 + 原厂方法(host loop) = 真深度路线

**硬件分解（用户实物照片确认）**：RS-D550 = eYs3D 一体模组（单 VID 0x3438 + eSPDI + 深度 ASIC），板型 = **2 侧边单色 IR 立体相机(白镜头,无 IR-cut) + 1 中间 RGB 彩色相机(蓝镜头,有 IR-cut,5MP) + IR 投射器 + 环境光感 + 4 白光补光 LED**。
- **真彩色 = 中间 RGB**；之前所有"彩色"画面其实是两侧 IR 对(L+R 并排,色度假/偏色)。
- **深度在相机 ASIC 上算**(硬件)，reg 0xF0=depthDataType 控制 ASIC 输出格式，IF2 直出算好的 metric mm；手机不做立体匹配。
- 投射器只在 ASIC 真做主动立体深度的模式才点亮(现 raw 模式没开,IR 相机里看不到散斑)。

**pupil libuvc / libusb-1.0 在本机彻底不出流(订正"重编 libuvc"路线)**：pupil+MJPEG 协商 rc=0(硬骨头啃下)，但 **0 字节；连 pupil+YUYV(vendor 证明能出的格式) 也 0 字节** → 不是 MJPEG/模式问题，是 **libusb-1.0 wrap_sys_device 这套栈在本机驱动不出流**(URB 超时无数据)。vendor saki4510t libuvc + libusb100 才出流。(Berxel 用 libusb-1.0 能出是另一设备。)结论：eYs3D Android 取流**必须用 vendor libusb100**，不能用 libusb-1.0。

**★ 原厂方法 = host `eys3d_stream_loop.cpp` RunEys3dStreamLoop（已写好 __ANDROID__ 分支）**：
- 异步：`StreamCb` 回调只 memcpy payload 到 pending + 重投(不做重活)；`DrainAndAssemble` 在 handle_events 后按 UVC payload 头 FID 翻转切帧、剥 bHeaderLength、满帧喂 core。
- **★ EP 0x83 持续中断心跳(IntrCb, ~3ms 周期 IN)= 流保活**；"缺它设备只吐 ~2.3MB≈2帧即断流"。这就是历史"全手动 2 帧停"真因 = 缺 0x83 心跳。我的 android fd_session BulkPump 没有心跳，靠 libuvc 内部代劳才出流。
- `Mode25Usb2Plan`(videoMode=36)= **真 metric 深度 640×128** + color MJPEG 1280×256；`ProvenWrongModePlan`(vm=0x02,14bit)= 现状,深度垃圾。
- ⚠ 但 host loop 实际 arming 走 kProvenArming(固定 14bit 序列)或 external(Java,也 vm=0x02)，**plan.arm.videomode_reg 没被应用** → 要真 mode25 得改 arming 实际下发 vm=36 + mode25 的 COMMIT IF1(MJPEG)。mode25 完整 arming 序列待抓/构造。

**当前进度**：已恢复 vendor 栈(libuvc YUYV color + 手动 depth bulk + reset)，待真机确诊 vm=0x02 深度真假(DiagRawDepth 判定)，再决定走 host loop + mode25 真深度。pupil 接入物料(libuvc_pupil.so/CMake/jniLibs)保留但 Run() 不用。

## 续21（2026-06-10）：mode25 全手动 = 0 帧，钉死"保活在 libuvc color 排空内"；Android selector-3 控制相位约束

本轮把 host loop 的开流序列逐字搬进 android fd_session 全手动跑 mode25（vendor libusb100，自起 pump/事件循环），系统性测出：

**协商全 OK，bulk 0 字节**：
- color COMMIT IF1(fmt2 MJPG frame2) `negFrame=655360` ✓、depth COMMIT IF2(fmt1 frame4) `negFrame=163840` ✓（均须在【空闲总线】做，见下）。
- F5=0 触发、XU{0x14}/{0x15}/{0x16}(selector 0x0a) start 命令均成功发出。
- 0x83 心跳满速 pump（~540/s）。
- **color+depth bulk 整整 20s 零数据**（URB status=2 TIMED_OUT len=0 反复重投）。比 14bit 全手动的"2 帧停"更差→mode25 全手动**根本不起流**。

**钉死结论**：续20 的"缺 0x83 心跳=2 帧停"**错**。续16 的全手动尝试本就带 0x83 心跳仍 2 帧停。真因=**设备 depth 流水线只在 IF1(color) 被 libuvc 内部 bulk 传输管理持续排空时才出帧**；该保活魔法在 libuvc 内、全手动复刻不出（续16 line 312 已述，本轮 mode25 实测再证）。→ **全手动取流路线对本设备是死路**，无论 14bit 还是 mode25。

**Android selector-3 控制相位约束（新，本轮实测）**：libusb100 wrap_sys_device 后端下——
- 只要有 bulk URB 处于 pending，**同一设备的同步 control_transfer 偶发/必 -7 超时**（host RHEL9 libusb 无此问题，纯 Android 后端差异）。
- **selector-3 寄存器写（wval=0x03xx，如 F5/videoMode）在 bulk pending 下必超时**；必须放【空闲总线】（挂 bulk 之前）发。
- selector-0x0a 写（0x14/0x15/0x16）+ 裸 CLEAR_HALT 在【pump 线程在跑】时可成功（首条偶发 race 超时，需重试/预热）。
- 解法相位：①两路 COMMIT + F5 全在空闲总线 → ②挂 bulk → ③起 pump 线程 → ④发 0x14/0x15/0x16+clrhalt。（已落地，但因保活死结仍 0 帧。）

**mode25 真深度的真正卡点**：需 libuvc 排空 IF1 保活，但 mode25 IF1=MJPEG，vendor libuvc 不认(subtype 0x7)、pupil libuvc 本机不出流(续20)。三条出路：
A. 让 libuvc 能流 MJPEG（pupil 在本机出流 / 重编 vendor parser 修 subtype 0x7）——深，天级。
B. 抓官方 VINCreator/eYs3D Android app 的 mode25 USB trace（usbmon/tcpdump），逐字复刻真正让 IF1+IF2 起流的序列——最确定，需跑官方 app + 抓包。
C. YUYV-keepalive 杂交：libuvc 流 IF1 为 YUYV(保活) + videoMode=mode25 让 IF2 出 ASIC depth + 手动 depth bulk——快但存疑(mode25 下 IF1 可能 MJPEG-only，libuvc YUYV 协商可能失败)。
D. 接受 续17 的 14bit 基线(libuvc YUYV color 连续 + 手动 depth 连续，深度是退化垃圾)，暂不要真深度。

**当前代码态**：android fd_session Run() 是全手动 mode25 路线(本轮)，已证 0 帧。Eys3dCameraService.FRAME_TIMEOUT_MS 临时改 20000(诊断用，待回 5000)。续17 的 libuvc-color 工作版备份在 .dev/eys3d-vendor-path-backup/eys3d_fd_session.cpp。

## 续23（2026-06-10）：option C 实证 + 全手动 100 缓冲实证 → mode25 真深度铁壁确认

本轮把 4 条都测到底，结论钉死：

**option C(YUYV-keepalive 杂交)实证**：
- libuvc 唯一能协商的 YUYV = frame-4 1280×480(14bit 色);committing 它把整机摁进 14bit。
- testC(depth COMMIT 落 14bit):libuvc YUYV 1280×480 连续排空保活 + 手动 depth bulk 连续(30 帧/~6fps),但深度 max=0xFFF6 distinct≈635 = **14bit 退化**(与续13 同签名),非真 metric。
- testC2(depth COMMIT mode25 640×128 在空闲总线成功 negFrame=163840):一旦 IF2 进 mode25→整机 mode25→IF1 变 MJPEG→libuvc YUYV 虽 rc=0 但**设备一字节不发**→color+depth 全 0。
- ⇒ **设备模式统一**:mode25(MJPEG色+真深度) ↔ 14bit(YUYV色+退化深度),不可混;YUYV 保活必然摁回 14bit。

**全手动 mode25 + kInflight=100 实证(证伪"缓冲数"假设)**：
- 把 kInflight 32→100(对齐 libuvc LIBUVC_NUM_TRANSFER_BUFS)+ 1ms 激进 pump + COMMIT(color655360/depth163840 全成功)+ F5 空闲总线成功 + start tail xu14/15/16/ch81/ch82 **全成功** + 0x83 心跳满速(~480/s)。
- **仍 20s 0 帧**。⇒ 缓冲数/pump 勤快度/start 命令都不是保活关键;全手动 mode25 无论怎么调都 0 帧。

**铁壁结论**：Android 上设备只在 **libuvc(vendor saki4510t)真实 streaming 排空 IF1** 时才出帧(连带 IF2 手动 depth 能搭车)。全手动复刻不出(参数完全对齐也 0)。而 libuvc 只能流 YUYV(强制 14bit)、**做不了 mode25 的 MJPEG**(报 Unknown VC subtype 0x7)。pupil libuvc 在本机一字节不出(续20)。Linux host(mainline libusb)全手动能出真 mode25 → 纯 Android libusb100 后端差异。

**⇒ mode25 真深度的唯一出路**(全手动/缓冲/pump/start 序列已全部排除)：
- A. 让 libuvc 能流 MJPEG:**取 saki4510t/UVCCamera 开源 libuvc 源**,修 VS_FRAME_MJPEG(subtype 0x07)解析(vendor 报 Unknown→疑 parser 把 VS 当 VC 或 format 描述符没识别),NDK 重编 → libuvc 连续流 mode25 MJPEG 排空 IF1(保活)+ 手动 depth 搭车。**可行(源开放),工作量中等**。
- B. 抓官方 app mode25 USB trace(root usbmon),看 libuvc/官方驱动到底对 IF1 做了什么让设备推流(全手动复刻不出的那步)。
- D. 退回续17 14bit 连续基线(深度退化,不满足"真深度")。

下一步建议 A(saki4510t 源开放,直指 MJPEG 解析根因)。

## 续24（2026-06-10, fable）：根因彻底锁定 — 取流引擎=libuvc uvc_start_streaming;事件循环假设证伪;libusb-1.0 后端公平测仍 0

新 agent 接手,反汇编 vendor .so + 取 libuvc 源 + 多路真机实验,把每条路测到底:

**反汇编 vendor libuvc.so 实锤**:vendor = **Etron 官方 SDK**(`esp_android_usb_camera_sdk`,作者 alanlin),不是 saki4510t。事件线程 `_uvc_handle_events` 用**阻塞 `libusb_handle_events`** 死循环(非 timeout);bulk transfer: type=BULK,timeout=5000,length=dwMaxPayloadTransferSize(与手动同源同值)。

**实验矩阵(全真机 2510DRK44C,mode25 videoMode=0x24 除非注明)**:
- 全手动 + 我的 `handle_events_timeout` pump:14bit=2 帧停 / mode25=0 帧(device 一字节不发,URB status=TIMED_OUT)。
- 全手动 + **阻塞 `libusb_handle_events`** pump(本轮新试,libusb100 已导出,从没绑过):mode25=0 帧;**14bit=0 帧(比 timeout 的 2 帧更差)**→ **事件循环不是根因,假设证伪**。
- ★ **pupil libuvc 公平一击**(本轮):`uvc_init(NULL)` 自有 ctx → `own_usb_ctx=1` → `uvc_open_internal`(device.c:391)起**阻塞 handler 线程**;`uvc_wrap(fd)` rc=0;`uvc_get_stream_ctrl_format_size(MJPEG 1280×256)` **rc=0 bFormatIndex=2 bFrameIndex=2**(pupil 解析 MJPEG 成功,vendor 做不到);`uvc_start_streaming(MJPEG)` rc=0;手动 depth COMMIT IF2 negFrame=163840。**结果仍 color+depth 全 0 字节**。→ 前一轮"pupil 0 字节"不是因为没 handler 线程(本轮已确保有),是 **libusb-1.0 `wrap_sys_device` 后端在本机根本驱动不出 bulk**(C10 公平确认)。

**铁律总表(本设备 Android 取流)**:
| 栈 | 解析 MJPEG | 本机出流 |
| vendor libuvc(libusb100,get_device_with_fd)+uvc_start_streaming | 否(报 Unknown subtype 0x7) | **是(唯一)** |
| pupil libuvc(libusb-1.0,wrap_sys_device)+uvc_start_streaming+阻塞 handler | 是 | 否(0 字节,公平测) |
| 全手动 bulk(libusb100,任何 pump) | n/a | 否(0~2 帧) |

**取流引擎触发条件 = libuvc 的 `uvc_start_streaming` 全套机制(在 libusb100 后端上)**,纯手动 submit bulk(任何事件循环)都不触发设备开流。testC 的"手动 depth 连续"是因为它搭了 vendor libuvc uvc_start_streaming(YUYV) 起的引擎的车。

**⇒ mode25 真深度唯一出路(已收敛到单点)**:需要一个**既解析 MJPEG 又用 libusb100 后端**的 libuvc。
- 路 A(推荐,可行):取 pupil libuvc 源(`.dev/m1.6.4-build/libuvc-src/`,解析 MJPEG 正确)→ 改设备打开路径 `uvc_wrap`(用 `libusb_wrap_sys_device`)为 **`libusb_get_device_with_fd`(libusb100 专属)+ libusb_open**,`uvc_init` 用 `libusb_init2`→ NDK 链 `third_party/eys3d-vendor/lib/*/libusb100.so` 重编出 libuvc_lusb100.so → 用它 uvc_start_streaming(MJPEG 1280×256) 排空 IF1 保活 + 手动 depth bulk(IF2 640×128)搭其阻塞 handler 线程 = 真 metric 深度。
- 路 B:二进制 patch vendor libuvc.so 的 VS 描述符解析,让它识别 VS_FRAME_MJPEG(subtype 0x07)→ 难(要加解析逻辑非改单字节)。
- 已排除:纯手动调 pump/缓冲/start 命令(续21-23 + 本轮阻塞 EV);pupil 原样(libusb-1.0 后端死)。

**当前代码态**:`native/eys3d/android/eys3d_pupil_session.cpp` = pupil MJPEG 公平测路径(已证 0,保留作参照 + 路 A 改造起点);Run() 当前调它。要恢复可用视频基线(vendor YUYV 14bit 连续)需把 Run() 改回 vendor libuvc uvc_start_streaming(YUYV)+手动 depth。FRAME_TIMEOUT_MS=8000。

## 续25（2026-06-10, fable）：重编 libuvc_lusb100(解析 MJPEG+libusb100 后端)→ 符号隔离 dlopen 通;剩 descriptor 解析崩溃

按续24 唯一出路(路 A)落地"既解析 MJPEG 又用 libusb100 后端的 libuvc":

**已完成(可复用)**:
- 改 pupil libuvc 源(`.dev/m1.6.4-build/libuvc-src/`):①`uvc_init` 的 `libusb_init`→`libusb_init2(ctx,"/dev/bus/usb")`(extern,libusb100 专属);②新增 `uvc_get_device_with_fd(ctx,&devh,vid,pid,sn,fd,bus,dev)`=`libusb_get_device_with_fd`+`libusb_open`+`uvc_open_internal`(替代 `uvc_wrap` 的 `libusb_wrap_sys_device`,libusb100 无该 API);③`#if 0` 掉 `uvc_wrap`(否则未定义 `libusb_wrap_sys_device` 致 dlopen 失败)。
- NDK 编译(aarch64/armv7-android24-clang)链 libusb100.so → `libuvc_lusb100.so`(SONAME=libuvc_lusb100.so,NEEDED=libusb100.so),已放 `third_party/libuvc-android/jniLibs/{arm64-v8a,armeabi-v7a}/`。导出 uvc_get_device_with_fd+全 uvc API 验证 OK。
- 实现 `native/eys3d/android/eys3d_pupil_session.cpp`:dlopen libuvc_lusb100.so 用它流 mode25 MJPEG 彩色(IF1)+手动 depth bulk(IF2)搭其阻塞 handler 线程。

**符号冲突坑(已解)**:libuvc_lusb100.so 直接链进 gomob_native 时,其 NEEDED libusb100.so 的 libusb_* 被 gomob_native 全局的 **libusb-1.0.so(Berxel 用,非 streaming fork)遮蔽** → libusb_open 走错 fork → 崩溃(uvc_get_device_with_fd→libusb_open→libusb_unref_device SIGSEGV)。**修法=不链,改 `dlopen("libuvc_lusb100.so", RTLD_LOCAL)`**(本地组优先,libusb_* 走自己 NEEDED 的 libusb100)→ `dlopen ok=1` 不再遮蔽。CMake 已去掉 uvc_lusb100/uvc_pupil 链接。注:libusb100 缺 `libusb_set_option`/`libusb_wrap_sys_device`(Berxel 需,故不能全局换 libusb100;dlopen 隔离是对的路)。

**剩余阻塞(下一步)**:dlopen 成功后,`uvc_get_device_with_fd`→`uvc_open_internal`→**`uvc_scan_control+420` SIGSEGV(null deref)**——pupil 描述符解析遍历 `info->config->interface[].altsetting[0]` 时崩。疑因:编译时用的是 third_party/libusb-android(mainline 1.0.27)的 libusb.h,但链/跑的是 **libusb100(Etron fork)**,二者 `libusb_config_descriptor`/`libusb_interface`/`libusb_interface_descriptor` **struct 布局可能不一致** → 偏移错位 → 崩。
  - 下一步候选:① 找 Etron/libusb100 的 libusb.h(struct 布局匹配)重编 pupil;② 无 root 拉不到寄存器,可临时 root 看 tombstone 寄存器精确定位 deref;③ 在 uvc_scan_control/uvc_get_device_info 加 null/范围 guard 先不崩、打印 config->bNumInterfaces 等判断是否 ABI 错位;④ 或绕过 uvc_open_internal 的描述符枚举(手动填 info 最小字段)。

**当前代码态(可用)**:Run() = vendor libuvc YUYV 连续基线(实测 color/depth 各 90+ 帧连续不崩,深度 14bit 退化),open_fd=YUYV 1280×480/depth 1280×480。pupil_session(libuvc_lusb100,真深度路线)保留待修 descriptor 崩溃后切回。Java videoMode=0x24。

## 续26（2026-06-10, opus）：libuvc_lusb100 全链路打通(MJPEG 协商✓ 起流✓ 事件循环✓)→ 真根因=arming 是 14bit 抓包,需 mode25 重抓

续25 的 descriptor 崩溃 + 后续坑全部解决，pupil(libuvc_lusb100) 架构**完全跑通**，只剩设备 arming 不匹配。

**逐个攻克(都在 `.dev/m1.6.4-build/libuvc-src/src/`，重编脚本 `.dev/m1.6.4-build/rebuild_lusb100.sh` 一键出两 ABI)**：
1. **descriptor 崩溃非 ABI 不匹配**：诊断日志证 by-index config 的 `bNumInterfaces=3`、`if[0] class=14/sub=1` 全对，struct 布局 OK。真因=libusb100 的 `libusb_get_active_config_descriptor` **失败(rc=-1)**，`libusb_get_config_descriptor(dev,0)`(by-index)成功但**所有接口 `extra`(class-specific 描述符)= NULL、`extra_length`=垃圾** → libuvc 解析不到 MJPEG 格式(`get_stream_ctrl_format_size`=-51)。`uvc_init` 改用 active 会直接失败，必须 by-index。
2. **自注入 extra**(device.c `eys3d_inject_extra`)：自己 `libusb_control_transfer` GET_DESCRIPTOR(config) 取原始字节，按接口切 [接口描述符之后→首个端点描述符之前] 的 CS 块，填回各 altsetting 的 `extra`/`extra_length`。→ **MJPEG 协商成功**(`color try MJPG1280x256@5 rc=0 bFmt=2 bFrame=2 maxFrame=655360`)、`uvc_start_streaming(MJPEG) rc=0`。
3. **teardown 崩溃**(`libusb_free_config_descriptor` Scudo misaligned free)：证 libusb100 的 `libusb_interface_descriptor` **尾部布局与 mainline 不同**(其 `extra` 指针在 mainline 的 `extra_length` 偏移处)。修法=device.c `eys3d_clear_injected_extra`：scan 完即 free 我的 buf + memset 接口描述符 off24..40 清零，libusb free 不再误碰。
4. **★ 事件循环死锁(关键)**：pupil `_uvc_handle_events` 用阻塞 `libusb_handle_events_completed` → libusb100 安卓后端**在阻塞期间提交的新传输不唤醒事件循环(无 eventfd 唤醒)**，handler 线程卡死第一次调用(iter=1 后再无)。修法=init.c 改 `libusb_handle_events_timeout_completed` 100ms 周期轮询。→ 传输开始被收割(depth URB `status=2 TIMED_OUT` 可见，err 每 5s+32)。

**架构现状=100% 工作**：handler 线程轮询✓、MJPEG 解析✓、起流 rc=0✓、URB 收割✓。**但设备 ep0x81/ep0x82 仍 0 字节**(URB 全 timeout)。

**真根因(已定位)**：`Eys3dProvenArming.kt` 是从 **14bit usbmon 抓包**生成(`源：eSPDI SDK usbmon 抓包（14bit 配置）`)，只手改了第 114 行 videoMode=0x24。但 mode25 是完全不同的流几何(MJPG 1280×256 + depth 640×128 vs 14bit 1280×480)，整套 sensor 寄存器写(75-167 行)都是 14bit 调参 → 设备按 14bit 臂、native 按 mode25 COMMIT → 不匹配 → 0 帧。Java arming 日志 `writes ok=27 fail=4`(4 个写失败可能含关键 mode25 寄存器)。

**下一步(唯一缺口)=重抓 mode25 arming**：eSPDI SDK(x86 host-only，`eys3d_sdk_depth` 能流真 mode25，`depthref_mode25/` 是证据)在**本 Linux 主机**跑 mode25 + usbmon 抓包 → `gen_replay_kt.py` 重生成 arming。工具已备：`.dev/eys3d-sdk/grab_mode25.sh`(抓 `sdk_stream_trace_mode25.txt`)、`gen_replay_kt.py`(已改：自动识别 bus:dev、`python3 gen_replay_kt.py sdk_stream_trace_mode25.txt` 生成 mode25 arming，header 自动标 mode25)。**需相机从手机插到本主机 USB**(Jun 1 已在主机跑过 mode25，可行)。抓完→重生成→相机插回手机→重编测。

**其它**：FRAME_TIMEOUT_MS 排障期 30000(稳定回 8000)；Run() `kUsePupilMode25=true` 走 pupil，置 false 回 vendor YUYV 基线;open_fd 仍声明 14bit profile(445-446)，mode25 通后改回 MJPG 1280×256 + 640×128。诊断日志 tag `eys3d_uvc`(libuvc 内)+`eys3d_pupil`(会话)。

## 续27（2026-06-10, opus）：找到 mode25 缺的 SET_INTERFACE 触发；但主机 SDK 自身也从未真流过 mode25 → 参照不可信

接续26 重抓 mode25 arming + 端侧实测：

**架构再确认 OK**：mode25 全新 arming(455 条,videoMode=0x24)Java 端 `writes ok=212 fail=0`(全成功,远好于 14bit 改写的 27ok/4fail),MJPEG 协商 rc=0、起流 rc=0、事件循环收割正常。

**找到 mode25 vs 14bit 关键差异 = SET_INTERFACE 触发**：usbmon 对比:mode25 trace 在 COMMIT IF1 后有且仅有一条 `01 0b 0000 0001`(SET_INTERFACE IF1 alt0);**14bit trace 完全没有**。pupil libuvc `uvc_stream_start` 只在 isochronous 分支发 SET_INTERFACE,bulk 分支不发 → 已在 stream.c bulk 分支补 `libusb_set_interface_alt_setting(devh, interface_id, 0)`。这解释了为何 14bit 能流(无需触发,vendor libuvc 也能)、mode25 不流(需触发,pupil 不发)。

**但补了仍 0 帧**,深查发现根本问题:**主机 eSPDI SDK 自己也从未真流过 mode25**。
- `sdk_stream_trace_mode25.txt` 里 bulk 完成全是 `C Bi:7:010:2 status=-2 len=0`(5 条全错,0 字节),color ep1 一条 bulk 都没提交过 → 抓包那次 SDK 根本没出流。
- 主机直跑 `eys3d_sdk_depth`(任何模式 14bit/mode25)都卡:`OpenDevice2 rc=0` 但随后 `SetAutoExposure rc=-63`、`GetZDTable rc=-3`,然后 GetDepthImage 阻塞(0 帧)。`depthref_mode25/` 目录是空的(Jun1 也没真出过)。
- 但 `eys3d_sdk_stream`(走 V4L2/uvcvideo,非 libusb)能拿 color。→ **主机 APC/libusb 深度路径在本环境坏的**(uvcvideo 占用/claim 冲突/USB2 端口?),不是 mode25 专属。

**结论**:我的"mode25 arming"是从一次【自身未出流】的 SDK 会话抓的 → 不可信参照。要拿可信 mode25 arming,必须先让主机 SDK 真流出 mode25(修 APC/libusb 路径:解 uvcvideo 占用、或换 USB3 口、或用 V4L2 抓)再抓包。或反过来确认 mode25 在 USB2 bulk 下到底能不能流(设备 USB3-capable,mode25 可能需 USB3)。

**当前端代码态**:Run() kUsePupilMode25=true 走 pupil mode25(0 帧);置 false 回 vendor YUYV 14bit 基线(连续出流,深度退化,用户能看到视频)。stream.c 已加 bulk SET_INTERFACE。FRAME_TIMEOUT=30000。open_fd 已声明 mode25 几何(640×128 depth/MJPG)。每次失败会话 + 复位会让设备进 degraded(arming 大量 fail),需拔插断电复位再测。

## 续28（2026-06-10, opus）：★ 原厂 VINCreator 在本手机真流出 mode25 → 功能可达,路线改为集成原厂 SDK

用户指令:"直接在手机上用原厂的 SDK 把功能拉通"。装原厂 VINCreator(`com.vin.uvc`,launcher `com.esp.uvc.main.CameraActivity`,APK 在 tests/vincreator-apk/)实测:

**★★★ 原厂 SDK 在本手机(Redmi annibale)+ 本相机(RS-D550)真流出 mode25 ★★★**(logcat 实证):
- COLOR: `nativeSetPreviewSize width:1280 height:256 fps:1-5 mode:1 camera_switch:0` MJPEG(raw frameSize 4160×832@MJPEG → 解码 1280×256,alloc 983040=1280×256×3)。
- DEPTH: `width:640 height:128 fps:1-5 mode:0 camera_switch:1` isDepth=1 alloc 163840(=640×128×2 mode25 真深度)。
- `GetZDTable pActualLength:4096`(ZD 表拿到,深度→mm;主机 SDK 那次 GetZDTable -3 拿不到 = 主机环境坏,非设备)。
- `_uvc_stream_callback cnt` 持续涨到 46000+,屏上显示 640×128 深度条(截图 .dev/screenshots/vincreator_mode25b.png)。

**结论**:mode25 在手机端**确定可达**;我的 libuvc_lusb100(pupil)0 帧是重写漏了原厂 libUVCCamera/libuvc 做的某步(非设备/非协议不可能)。原厂栈 = jiangdg AndroidUSBCamera(`libUVCCamera1.so`/`libuvc1.so`/`libusb1001.so`)+ `libESPDI.so`(深度/ZD)+ `libjpeg-turbo` + Java `com.serenegiant.usb.UVCCamera`(API: setPreviewSize(w,h,minfps,maxfps,mode,bw,camera_switch))。开**两路 UVCCamera**:color(mode1/switch0)+depth(mode0/switch1)。

**安装坑**:MIUI `INSTALL_FAILED_USER_RESTRICTED` → 用 `adb push apk /data/local/tmp/ + adb shell pm install -r -g` 绕过。

**下一步**:集成原厂 SDK 到 gomob app(jiangdg UVCCamera + eSPDI 的 .so + Java 类 → 接深度页),用原厂已验证栈出 mode25,替代 libuvc_lusb100 自研路线。libuvc_lusb100 经验(extra 注入/事件循环/SET_INTERFACE)留作参照。所有 .so 在 tests/vincreator-apk/extracted/lib/<abi>/,Java 在 classes*.dex。

## 续29（2026-06-10, opus）：★★★ 抓到 VINCreator 手机端真出流 mode25 的完整 USB 序列 → 缺的是"上传标定表" arming

用户改方向:"先抓 VINCreator 精确 USB 序列修自研库"。手机无 root,用 **libusb 日志垫片**法抓:

**垫片法(可复用,`.dev/vinshim/`)**:VINCreator 两套栈——color 走 `libUVCCamera1→libuvc1→libusb1001.so`,depth+ESPDI 走 `libUVCCamera→libuvc→libusb100.so`(NEEDED 实证)。建 shim `libusb100.so`/`libusb1001.so`(SONAME 不变,NEEDED 改 `libusbXXXreal.so`,转发真库 dlopen+forward,只定义要记的 `libusb_control_transfer`,其余符号 ELF 全局落到真库)→ patchelf real 改 soname → 重打包 APK(`zip -0 -X` 存不压,`extractNativeLibs=false` 必须页对齐 `zipalign -p 4`)→ apksigner debug key 重签 → `pm install -r -g`(MIUI 限制用 push+pm)→ `appops set MANAGE_EXTERNAL_STORAGE allow`(否则卡存储权限页不出流)。shim 把每条 control_transfer 打 logcat tag `VINSHIM`。

**抓到完整权威序列**(`.dev/eys3d-sdk/vincreator_mode25_ctrl_seq.txt`,2096 条;原始 `vincreator_usb_proven.log`):
- 写命令分布:**956 条 `wVal=0b00 wIdx=0400`=XU 大块表上传**(16B/块,`73/74.. 41 05 01 00 01 <addr16> <data> ..` 地址递增,= 把整流/ZD/标定表【写进设备】)+ 30 条 `wVal=0300 wIdx=0400`(寄存器,含 `20 f0 24`=videoMode mode25)+ 22 条 `wVal=0a00 wIdx=0400`(1B counter/index)。
- IF1 color COMMIT(行25):`01 00 01 01 ... 00 d0 34 00 00 20 00 00` = **fmt01/frame01**,maxFrame=0x34d000=3461120(4160×832 MJPEG),maxPay=0x2000=8192。
- videoMode(行196):`20 f0 24 00`。
- IF2 depth COMMIT(行838):`01 00 01 04 ... 00 80 02 00 00 04 00 00` = **fmt01/frame04**,maxFrame=0x28000=163840(640×128),maxPay=0x400=1024。
- **无 SET_INTERFACE**(host eSPDI trace 有,Android VINCreator 没有 → Android 不需要)。

**★ 根因定论:自研库 0 帧是因为 arming 缺"上传标定表"那 956 条 XU 写**。我的 EYS3D_PROVEN_ARMING(14bit usbmon 生成,153 条)和主机抓的 mode25(455 条,那次自身没出流)**都没有表上传**。VINCreator 先把表写进设备,设备才出 mode25 深度。color 还得 commit fmt01/frame01(我之前用 libuvc 协商成 fmt02/frame02=另一个 MJPEG 帧,错)。

**下一步(明确)**:从 `vincreator_mode25_ctrl_seq.txt` 重生成 EYS3D_PROVEN_ARMING(全 2096 条有序回放,或至少全部 OUT 写),native 侧:color commit fmt01/frame01 + depth fmt01/frame04 + 手动 bulk(ep0x81/0x82),去掉 stream.c 的 SET_INTERFACE(VINCreator 不发)。表上传补齐后,极可能不再需要 libuvc_lusb100 那套(纯手动 bulk 可能就出流,因为之前"必须 libuvc"的结论是 arming 不全造成的)。

## 续30（2026-06-10, opus）：★ 订正续28/29 + mode25 config 已逐字节对齐 VINCreator gold + 发现 HLSD8 是独立第二颗相机

**订正续29「956 条=上传标定表是 0 帧根因」= 错**。用户指出（已验证）：那 956 条 `wVal=0b00 wIdx=0400` 是**读设备 flash 的标定表**（eYs3D 模组**出厂标定**，ZD/整流表从设备读出，不是端侧上传）。佐证：`grep -oiE "OUT 20 .. .. 00" vincreator_mode25_ctrl_seq.txt | sort -u` 全序列只有**3 条**设备状态写 —— `20 f0 24`(videoMode=mode25)、`20 e0 03`、`20 e2 06`(sensor reg)。本仓 `EYS3D_PROVEN_ARMING.kt`(456 条)**已包含这 3 条**(行52/112/120)+ 那批 `0b00` flash 读块(行146-242)。**即 gomob 的 arming 不缺东西**。

**mode25 stream config 已逐字节对齐 gold**（比对 `.dev/eys3d-sdk/vincreator_mode25_ctrl_seq.txt`）：
- arming 设备状态写 3 条全一致（上）。
- IF1(L'/color) COMMIT `01 00 01 01 ... 5fps`：fmt1 frame1 MJPEG 1280×256@5 —— pupil 会话用 libuvc `get_stream_ctrl_format_size(MJPEG,1280,256,5)` 协商，匹配。
- IF2(depth) COMMIT `01 00 01 04 ...`：fmt1 **frame4**，dwMaxVideoFrameSize=`0x28000`=163840=640×128×2 —— pupil 会话 `CommitIf2(fmt=1,frame=4)`，匹配。
→ **mode25 配置正确性已确认，剩纯真机运行态验证**（pupil libuvc_lusb100 + extra 注入是否连续出真 metric 深度帧）。已修 `Eys3dCameraService.kt armViaJava` 里那条"videoMode 14bit 0x02 / mode25 留后续"的**过时错注释**。

**★ 重大发现：4160×832 那路不是 eYs3D，是独立第二颗 RGB 相机 HLSD8**（续28 把它当 eYs3D color 是错的）。`dumpsys usb` 实证两颗独立相机：RS-D550(0x3438:0x0206=深度) + Image+ HLSD8(0x0C45:0x6366=13MP RGB)。gomob 此前只接深度。本轮已把 HLSD8 完整接入（native Hlsd8Driver/Session + Kotlin Hlsd8CameraService + 双相机路由 + UI 预览 + USB filter）+ 建正射图几何(`native/vin/ortho_rectify`)+ harness PASS。详见 [[finding_hlsd8_rgb_second_camera_2026-06-10]]。

**当前端代码态**：双相机均可 acquire；深度页同时预览深度+L'+HLSD8 RGB。build 双 ABI 绿、native host 全测绿（含 ortho_rectify_test）。device-gated 剩：HLSD8 真机出流 / 双相机标定(R|t) / mode25 真深度帧。

## 续31（2026-06-11, opus）：★ arming live-counter 修复 + bulk 参数与 VINCreator 逐字相同 + 设备 wedge 是当前 0 深度真因

本轮把 mode25 推到「软件层与 VINCreator 逐字相同」，但真机当前**对 gomob 和干净 VINCreator 一视同仁地不产深度** → 0 深度是**设备状态（wedge / 场景），非 gomob 软件 bug**。

**1. arming = counter 门控握手（订正续30「arming 不缺东西」的隐患）**：干净 RS-D550 抓包（`.dev/vinshim/rsd550_clean_seq.txt`，只垫 libusb100）证实 entity-4 的 956 笔 flash 读（bank 01/32/33/f0/f1）是 **counter 门控**：selector `0x0a` 是设备滚动 nonce，每笔 flash 写（`0x0b`）首字节必须 = 最近一次 `0x0a` GET_CUR 活值（36→…→4b）。旧 `EYS3D_PROVEN_ARMING`（456 条静态子集）只读 164 笔且发**陈旧 counter 前缀** → 设备 STALL（实测 writes fail=164）。**修法**：① 从干净抓包生成完整 **2049 条** `Eys3dCleanArming.kt`（base64 blob，`.dev/vinshim/gen_clean_arming.py`）；② `armViaJava` 运行时活读 `0x0a` 覆盖每笔 `0x0b`/`0x0a` 写首字节。**结果**：`writes ok=1008 fail=0 | reads ok=1008 fail=0`，956 flash 全成功，逐字匹配 VINCreator。（注：videoMode 用 `82 f0 14` 读回恒是 `82 15 00 00` —— 这是该读命令的**固定响应**，非 videoMode 当前值，VINCreator 写完 `20 f0 24` 后照样读回 `82 15`，别再被它误导。）

**2. 取流 = libuvc 流 IF2 深度（YUYV 640×128），不开 IF1**：VINCreator VS 序列 = PROBE IF1（**无 COMMIT**）+ PROBE/COMMIT IF2（fmt1/frame4 640×128，maxFrame=0x28000，maxPay=0x400=1024）+ COMMIT 后写 e0/e2 + 末尾再 PROBE IF2。`eys3d_pupil_session.cpp` 重写为 libuvc 流 IF2 YUYV 640×128。

**3. ★ bulk 参数与 VINCreator 逐字相同（增强 shim 抓 VINCreator bulk 实证）**：给 libusb100 垫片加 `libusb_submit_transfer` 抓取（`.dev/vinshim/shim2.c`→`libusb100_dbg.so`，重打包 `com.vin.uvc`）→ **VINCreator depth bulk = `SUBMIT_BULK ep=82 len=1024 timeout=5000`**，与 gomob 完全一致；payload 头 `0c 8c b0 0e`（bmHeaderInfo=0x8c：EOH+SCR+PTS，FID=0 EOF=0，**靠字节数填满 dwMaxVideoFrameSize 发布帧，无 EOF**）。**稳态 20s 内 VINCreator 0 控制活动 = 无 keepalive**（推翻"像 P100R3 那样需 keepalive"假设）。

**4. ★★★ 设备 wedge 是当前 0 深度真因（不是 gomob bug）**：本轮**全部配置**（external-commit / libuvc-commit-同句柄 / 带或不带 SET_INTERFACE）depth bulk 都 `status=2 TIMED_OUT actual=0`、0 帧。但**干净 VINCreator 此刻也一样**（截图红框、无 SN/fps、bulk 全超时）。本会话**最初**一次 VINCreator 跑出过 `SN:476 / UVC:5.00fps`（设备 CAN 产深度）→ 经反复冷启 + claim/release 循环 + 调试 shim 折腾后，设备进 wedge 态（仍枚举 0x3438，但深度引擎不产帧），与记忆「反复 reset/开关 wedge 设备」一致。**软件 USB reset 会进一步 wedge（已知），无法软件恢复 → 需物理重新插拔 + 对准 20+cm 有纹理场景**。

**结论**：gomob mode25 软件链（live-counter 全 arming + libuvc IF2 YUYV bulk，控制序列 + bulk 参数与 VINCreator **逐字相同**）已完成；当前 0 深度对两 app 一视同仁 = 设备态。**剩纯 device-gated 验证**：用户重新插拔深度相机 + 对准有效场景后，进 gomob 深度页看 `eys3d_pupil depth#N [丰富?真深度]` + `tick depth>0`。

**临时诊断待清理**（验证通过后删）：`libuvc-src/src/stream.c` 的 `uvc_stream_diag`（START/D ep=/Dpp# 日志）+ `eys3d_pupil_session.cpp` DiagDepth。`flags=2`(skip SET_INTERFACE) 是正式特性保留。`Eys3dProvenArming.kt` 已被 `Eys3dCleanArming.kt` 取代（armViaJava 不再引用），可删。

## 续32（2026-06-11, opus）：★ 用户重插后设备恢复 → gomob 拿到深度数据但不持续 + gomob 会话 wedge 设备（剩余差异=双句柄分裂）

用户物理重插深度相机后**设备恢复**（干净 VINCreator：距离 285mm、SN 计数、**UVC 5.00fps**、顶部彩色深度点）。在此健康设备上：

**1. gomob 确实拿到深度数据（bulk 路径通）**：紧接 VINCreator 之后开 gomob → 深度回调 `D ep=82 status=0 actual=1024 head=0c 8c 7b 49`（UVC payload 头格式与 VINCreator `0c 8c b0 0e` 一致），`Dpp# got=1012→2024→3036`。**证明 gomob 的 arming+commit+bulk 路径能在 0x82 收到设备数据**。

**2. 但只收到 ~3KB 残留就停**：那 3 笔 = VINCreator 刚 streaming 留在设备/USB 的残留；gomob 排空后 5s 无新数据 → 超时。**gomob 的会话没触发设备【持续】产深度**。试过 `e0/e2 在 COMMIT 后补发`（`postCommitSensorEnable`，VINCreator 原序 COMMIT→e0→e2）SET/GET rc=4 写成功，仍不持续产帧 → **e0/e2 顺序不是因**。

**3. ★ gomob 会话 wedge 设备**：每次 gomob 冷启（open→Java arming→libuvc commit/bulk→30s 超时 death→close）后，**连干净 VINCreator 也不再产深度**（红框/无 SN），需再次物理重插才恢复。⇒ 反复 gomob 测试在「被自己 wedge 的设备」上跑 = 无效；这也是续31「设备 wedge」的真凶（不是单纯冷启次数，是 **gomob 的 USB 会话生命周期**把设备搞挂）。

**★ 剩余唯一差异 = 双句柄分裂**：gomob 的 arming 走 **Java `UsbDeviceConnection.controlTransfer`（USBDEVFS ioctl）**，commit/bulk 走 **libuvc 的 libusb100 wrapped-fd 句柄**（虽同一 fd，但两套句柄/提交路径）。VINCreator 全程 **单一 libusb100 句柄**（enumerate→open→sync `libusb_control_transfer` arming→commit→bulk）。libuvc 的 `uvc_get_device_with_fd` 只 open+claim IF0、**不 reset/set_config**（已查源码），故不是 reset 擦 arming。控制序列 + bulk 参数（`ep=82 len=1024 timeout=5000`）已逐字相同 → 差异只在「谁提交这些传输」。

**下一步候选（第一性最优解）**：把 arming **移到 native、走 libuvc 同一个 libusb100 句柄的 sync `libusb_control_transfer`**（VINCreator 用同步,非之前判 STALL 的 async URB），open+arm+commit+bulk 全在一个 libusb100 会话里，逐字复刻 VINCreator 单句柄模型。极可能同时解决 ②不持续产帧 + ③wedge（双句柄分裂消除）。工作量大（2049 op blob + live counter 移植到 native + JNI 传 blob），且设备脆弱（每测需用户重插），故先与用户确认是否投入此重构。

## 续33（2026-06-11, opus）：★★★ 突破——「只开 IF2」是错前提，缺的是 IF1 彩色保活；双流修复已实现待测

**本会话教训**：基于 `eys3d_pupil_session.cpp` 旧注释「原厂只开 IF2 深度、根本不流 IF1」做了 16+ 次配置实验
（single-handle / skip_vs / flags 0~6 / 截断 / 手填 ctrl / 错误重投 / clear_halt / 16384 大块 bulk / 换 libusb100 版本），
depth bulk **全部 status=2(-EPROTO) 零帧**。逐一证伪后回读本 finding 全史，发现**根因早在 续16/21/23 钉死**：

**★ 铁律（再确认）：设备 stereo→depth ASIC 流水线只在 libuvc 真实 streaming【持续排空 IF1 彩色】时才出 IF2 深度。**
手动 bulk 排空 IF1=2 帧停，只有 libuvc 内部传输管理能保活（续17 用 libuvc-YUYV-color + 手动 depth 实跑连续证此）。

**「只开 IF2」错前提的来源**：`rsd550_clean_seq.txt` 只垫了 **libusb100**，显示「IF1 PROBE 无 COMMIT + IF2 COMMIT」→ 误判
原厂不开 IF1。但 续29 明确 **VINCreator 的 RS-D550 IF1 彩色走 libuvc1→libusb1001.so**、IF2 深度走 libuvc→libusb100.so
→ libusb100-only 垫片**漏抓了走 libusb1001 的 IF1 彩色 COMMIT**。VINCreator 实际**同时流 IF1 彩色 + IF2 深度**，IF1 彩色排空就是保活。

**修复（已实现，`eys3d_pupil_session.cpp`）**：libuvc_lusb100（能解析 MJPEG，续26 证）**双流**——
① IF1 彩色 `get_stream_ctrl_format_size(MJPEG,1280,256,5)` → `start_streaming(ColorKeepaliveCb)`（丢帧纯保活）；
② IF2 深度 `get_stream_ctrl_format_size(YUYV,640,128,5)` → `start_streaming(DepthUvcCb→core.OnRawDepthFrame)`。
两流共用 libuvc handler 线程。arming 仍走 Java armViaJava（live-counter，writes ok=1008 fail=0，逐字匹配 VINCreator）。

**状态**：双 ABI build 绿、APK 就绪，但**装机时无线 adb 断连，未及真机验证**。设备回来后：物理重插相机 + 对准 20+cm 有纹理场景
→ gomob 深度页静置 → 看 logcat `color(IF1 keepalive) #N`（彩色保活在排空）+ `tick color=.../depth=...>0`
+ `depth#N [丰富?真深度]`（IF2 出真 metric 视差）。

**若双流仍不出深度的兜底**：用户已选「移植 eYs3D 原生栈」(native-direct dlopen libUVCCamera.so C++ 符号:
`UVCCamera::connect/setPreviewSize(w,h,minfps,maxfps,mode,bw,isDepth)/setFrameCallback(IFrameCallback)/startPreview`,
开 color(mode1)+depth(mode0)两路,IFrameCallback=`com.esp.android.usb.camera.core.IFrameCallback.onFrame(ByteBuffer,int)`)。
VINCreator native libs 在 `.dev/vinso/lib/<abi>/`(libuvc.so/libUVCCamera.so/libusb100real.so)。但双流修复若通则无需此端口。

**临时诊断待清理**（验证通过后）：stream.c 的 clear_halt/16384-bulk/err_streak-resubmit/uvc_stream_diag、pupil DiagDepth。

## 续34（2026-06-11, opus）：★ IF1 彩色保活双流证伪 libuvc_lusb100 → 锁定移植 eYs3D vendor 栈(端口设计)

**续33 双流真机验证 = 失败但信息量大**：libuvc_lusb100 双流（IF1 MJPEG 1280×256 保活 + IF2 YUYV 640×128 深度）
`color try rc=0 bFmt=2 maxFrame=655360`、两路 `start_streaming rc=0`、`color_ok=1 depth_ok=1`，**但 tick color=0/0B depth=0/0B**
（彩色也 0）。同时刻 VINCreator 正常出深度（cnt 涨 + GetZDTable + isDepth 1，用户确认）、设备健康。
⇒ **铁律再钉**：libuvc_lusb100（重编 pupil + libusb100 后端）**驱动不出本设备 bulk**，连彩色都排空不了；IF1 保活思路对（设备确需彩色排空），
但执行体必须是 **VINCreator 的 eYs3D vendor libuvc/libUVCCamera**（同 libusb100 后端，差异在 libuvc bulk 传输管理 C 码，二进制内不可复刻）。

**⇒ 唯一出路（用户已选）= 移植 VINCreator eYs3D vendor 原生栈**。端口设计如下：

**1. 依赖闭包（10 个 vendor .so，全在 `.dev/vinso/lib/arm64-v8a/`）**：
`libUVCCamera.so`(NEEDED:libusb100/libuvc/libESPDI/libeysov/libDepthMSR/libSwPostProc/libhidapi/libdepthfilter)
+ `libuvc.so`(NEEDED:libjpeg-turbo1500/libusb100) + `libusb100.so`(**用 libusb100real.so 改 soname→libusb100.so**,APK 里的 libusb100.so 是我加的 shim)
+ libESPDI/libeysov/libDepthMSR/libSwPostProc/libhidapi/libdepthfilter/libjpeg-turbo1500。

**2. ★ 冲突点**：gomob `third_party/eys3d-vendor/lib/` 已有**旧版** libuvc.so/libusb100.so/libjpeg-turbo1500（与 VINCreator 版本不同,hash 异）。
直接同名打包冲突。须用 VINCreator 版**整体替换**这 3 个 + 补 7 个新的。旧 `libuvc_lusb100.so`(pupil)弃用。先查 gomob_native 链(非 dlopen)了哪些
(`core/native-bridge/src/main/cpp/CMakeLists.txt`),被链的换版可能断 build;eys3d_fd_session/pupil_session 路弃用后应从 CMake 摘掉。

**3. C++ API（native-direct，dlopen libUVCCamera.so RTLD_GLOBAL + dlsym，免 Java RegisterNatives）**：
- 类 = `ApcCamera`(UVCCamera 子类)；可直接调 UVCCamera C++ 符号:
  `_ZN9UVCCameraC1Ev`(ctor,对象超额分配 16KB)、`connect(fd,vid,pid,busnum,devaddr,const char* usbfs)`(`_ZN9UVCCamera7connectEiiiiiPKc`)、
  `setPreviewSize(w,h,minfps,maxfps,mode,float bw,isDepth)`(`...setPreviewSizeEiiiiifi`)、`setFrameCallback(JNIEnv*,jobject,int,int)`、
  `startPreview(int)`、`GetZDTable(uchar*,int,int*,int)`(深度→mm,可后补)、dtor。
- **开流 recipe（续28 实测日志 + dexdump）**:connect(fd,0x3438,0x0206,bus,dev,"/dev/bus/usb")
  → setPreviewSize(1280,256,1,5,**mode=1**,bw=1.0,**isDepth/switch=0**)彩色
  → setPreviewSize(640,128,1,5,**mode=0**,bw=1.0,**isDepth/switch=1**)深度
  → setFrameCallback(env,cbObj,pixfmt,0)  → startPreview(0)。一个 UVCCamera 两路(isDepth 0/1)。
- 帧回调 = Java `com.esp.android.usb.camera.core.IFrameCallback.onFrame(ByteBuffer,int)`(签名 `(Ljava/nio/ByteBuffer;I)V`);
  setFrameCallback 内 GetMethodID 查 "onFrame"。gomob 建小 Java 类实现它,depth 帧(163840B)→core.OnRawDepthFrame,彩色丢弃。
- usbfs 字符串/参数细节:dexdump `/tmp/vin_dex.txt`(classes.dex)的 `ApcCamera.nativeConnect`(method@a68e)调用方;`UVCCamera.setFrameCallback`@3df07c。

**4. 落地步骤**:① stage 10 .so(arm64,libusb100real→libusb100)到新 jniLibs + 改 build.gradle srcDir;② 新 native 会话
`eys3d_apc_session.cpp`(dlopen+dlsym+ctor+connect+双 setPreviewSize+setFrameCallback+startPreview);③ Java IFrameCallback 实现;
④ Eys3dCameraService 路由到新会话(替 RunEys3dPupilMjpegSession);⑤ 真机测 depth#N 出真 metric 视差。
armeabi-v7a:VINCreator APK 若有则一并 stage,否则 gomob 限 arm64(手机即 arm64)。

**状态**:诊断+端口设计完成,.so 在 .dev/vinso/lib;实现未开始。设备此刻健康(VINCreator 可流)。

## 续35（2026-06-14, opus）：★ eYs3D vendor 栈(libUVCCamera.so)native-direct 移植【实现完成+部署】，待真机验证

续34 端口设计已全部落地实现，build 绿、11 vendor .so 打包、JNI 导出、部署真机。**dexdump 校准订正了续34 recipe 的多处**（续34 是推断，本轮逐字反编译验证）：

**1. ★ setVM 旁路 JNI_OnLoad（关键使能）**：反汇编 `JNI_OnLoad`（0x218ac）= GetEnv→register_uvccamera→register_apccamera→`setVM(vm)`。`setVM`/`getVM` **是导出符号**（`_Z5setVMP7_JavaVM` 0x21934 仅 `str x0,[VM@0xaa710]`；`getEnv` 用它 AttachCurrentThread）。故 native-direct **dlsym `setVM` 直接缓存 VM 即可**，无需调 JNI_OnLoad（避开 register 的 FindClass 依赖）。preview 线程靠此 VM 回调 onFrame。

**2. ★ connect 参数序订正**：C++ `_ZN9UVCCamera7connectEiiiiiPKc` = `connect(vid, pid, fd, busnum, devnum, usbfs)`（dexdump `ApcCamera.open`→nativeConnect: getVenderId/getProductId/getFileDescriptor/getBusNum/getDevNum/getUSBFSName）。**续34 的 `connect(fd,vid,pid,...)` 是错的**。usbfs="/dev/bus/usb"。

**3. ★ 双流 = 一个 UVCCamera 两路，分别 setFrameCallback（dexdump CameraMainActivity$5/$9 实证）**：
- `setPreviewSize(w,h,minfps=1,maxfps,mode=isMJPEG,bw=1.0f,switch=(IF2?1:0))`（`_ZN9UVCCamera14setPreviewSizeEiiiiifi`）。
- 彩色:setPreviewSize(1280,256,1,5,**mode=1**,1.0,**switch=0**) + setFrameCallback(colorCb,**pixfmt=3**=RGBX,**switch=0**) + startPreview(0)。
- 深度:setPreviewSize(640,128,1,5,**mode=0**,1.0,**switch=1**) + setFrameCallback(depthCb,**pixfmt=0**=RAW,**switch=1**) + startPreview(1)。
- setFrameCallback 用 GetObjectClass+GetMethodID("onFrame","(Ljava/nio/ByteBuffer;I)V")，**任意带 onFrame 的对象都行**（gomob 用 `Eys3dApcFrameSink`，无需 vendor Java 类）。

**4. 依赖闭包齐**：libUVCCamera NEEDED 10 vendor .so（libusb100/libuvc/libESPDI/libeysov/libDepthMSR/libSwPostProc/libhidapi/libdepthfilter/libjpeg-turbo1500←libuvc、libMetaVideo←libeysov）+ 系统 libstdc++/liblog/...（vendor 用**系统 libstdc++**，gomob 用 libc++_shared，共存不冲突）。全 staged 到 `third_party/eys3d-vendor/lib/arm64-v8a`。dlopen(RTLD_LOCAL) 隔离 libusb100 不被 gomob 现代 libusb-1.0 遮蔽。

**5. 落地文件**：
- `native/eys3d/android/eys3d_apc_session.{h,cpp}`（Eys3dApcSession:dlopen+dlsym+setVM+ctor(超额 64KB)+connect+双流;OnApcFrame depth→core_.OnRawDepthFrame,color 丢弃保活;stop=stopPreview×2+dtor+free,不 dlclose）。
- `native/jni/camera_session_jni.cpp`:`cameraOpenEys3dApc(fd)`(建 sess+SetGeometric+Open,return ICameraSession*)、`nativeApcFrame(handle,isDepth,buffer,size)`(GetDirectBufferAddress→OnApcFrame)。
- Kotlin:`Eys3dApcFrameSink`(onFrame→nativeApcFrame)、`CameraStack.startEys3dApc(fd)`、`NativeBridge` 两 extern、`Eys3dCameraService` 改走 startEys3dApc(**去 armViaJava** — vendor 栈内部自做 arming/ZD/bulk)、`CameraDetection.detect` Berxel+eYs3D 同插时优先 eYs3D。
- **不走 armViaJava 是关键**:vinshim 抓的 2049 条 counter 门控序列就是 libUVCCamera 自己发的,gomob 再发会与 vendor 握手冲突。

**待验证（device-gated，本轮阻塞）**：测试时相机整机已掉出 OTG 总线（`/sys/bus/usb/devices/` 只剩 root hub，dumpsys UsbDevice=0；RS-D550 久不流自动断电掉线）→ **需用户物理重插**。重插后进深度页看 logcat tag `eys3d_apc`:`connect rc`、`setPreviewSize/startPreview`、`depth#N`（出帧）+ 深度页 DEPTH 面板有图 = vendor 栈移植成功。`armViaJava`/`Eys3dFdSession`/pupil 路径暂留(验证通过后清)。

## 续36（2026-06-14, opus）：★ vendor 栈真机跑到 stream-start 与 VINCreator 逐字一致，但 bulk IN 恒 status=2（深 USB 壁垒）

续35 的 native-direct 端口真机推进，**修了 2 个崩溃/卡点、补了关键 setVideoMode，跑到与 VINCreator 逐字一致**，但最终 bulk 取流挂在 status=2。

**真机修复链（每步真机验证）**：
1. **connect 崩溃**：`UVCCamera::connect+836` → `UVCPreview::setExternalStoragePublicDirectory+40` strdup(null)。connect 内部把 UVCCamera 成员 [0x50](外部存储目录串)strdup 传两路 preview，null 必崩。修=connect 前调 `UVCCamera::setExternalStoragePublicDirectory(_ZN9UVCCamera33...EPKc)` 传 app filesDir（plumb 到 cameraOpenEys3dApc(fd,storageDir)）。
2. **startPreview 不起线程**：`UVCPreview::startPreview` 门控 `[this+0x8]`(预览窗 ANativeWindow)非空才 pthread_create；缺则 log "window does not exist"。gomob 深度走 frame callback 不用 vendor GL，但**线程不起就没 callback**。修=NDK **AImageReader 离屏窗**(`AImageReader_newWithUsage`+`getWindow`+`setImageListener` 丢弃排空)+`UVCCamera::setPreviewDisplay(_ZN9UVCCamera17...EP13ANativeWindowi)`。两路各一窗 → startPreview rc=0(pthread_create 成功)。CMake 加 `mediandk`。
3. **★ setVideoMode(36) 缺失=关键**：dexdump VINCreator 完整序 = connect→getProductVersion→**setVideoMode(CameraMode.getVideoMode())**→setPreviewSize→setPreviewDisplay→setFrameCallback→startPreview(×2)。gomob 漏了 setVideoMode。`UVCCamera::setVideoMode(t)(_ZN9UVCCamera12setVideoModeEt)`→`CVideoDevice::SetVideoMode` 写 videoMode 寄存器(0xf0=0x24=36=mode25)。补 `set_video_mode(cam,36)`(connect 后)→ rc=0。

**现状=与 VINCreator 逐字一致但 bulk 死**：gomob trace 与 VINCreator 完全相同——connect 全子步 result=0、setVideoMode(36) rc=0、get_stream_ctrl end(0)、setPreviewSize alloc 983040(色)/163840(深)、prepare_preview (1280,256)@MJPEG+(640,128)@YUYV、setBuffersGeometry 一致、GetZDTable 4096 nRet:0、uvc_stream_start_bandwidth(bcdUSB=0x0210,bulk mode,submit transfers,end 0)。**但 gomob bulk IN 全 `_uvc_stream_callback status=2`(LIBUSB_TRANSFER_ERROR,即时硬错,0 超时 0 帧),VINCreator 同调用 cnt 持续涨 + depth broken-frame(161936/163840)出数据。**

**已逐一证伪(都不是因)**：
- uvcvideo:VINCreator 流时 /dev/video2,3 在场照样出数据；gomob 带/不带 Java force-claim(DISCONNECT_CLAIM detach)都 status=2。
- Java force-claim:dexdump 实证 esp/eYs3D 路径不在 Java claim(全交 native libusb100);去掉后仍 status=2。
- 现代 libusb-1.0(Berxel 用):eYs3D 会话期间未 init(日志只见 libusb100 v1.0.19),无双 context 冲突；VINCreator 也有双 libusb(100+1001)照样工作。
- HLSD8 并发:gomob 未 acquire(无 Hlsd8 日志/无 /dev/video)。
- bus/dev:VINCreator USBMonitor 实测 RS-D550 = busNum1/devNum4，与 gomob readlink 解析逐字一致。
- 诊断读(dumpInterfaces/dumpRawDescriptors):去掉后仍 status=2。
- setVideoMode 值:36(0x24)=续15/29 gold(reg 0xf0=0x24)，SetVideoMode rc=0 设备接受。

**剩余假设(未破)**：① **AImageReader 离屏窗 vs VINCreator 真 SurfaceView(GL)**——理论上 status=2 是 libusb 事件线程的 URB 错(独立于窗渲染)，但 eYs3D fork 若把 transfer buffer 绑窗 buffer 则可能即时 -EOVERFLOW(未证)。② **跳过 JNI_OnLoad 的 register_uvccamera/register_apccamera**——native-direct 只 setVM，未跑 register_*(其可能初始化流/CVideoDevice 全局态);但 register_* 是 JNI 层、bulk 是纯 libusb，关联存疑。③ **进程环境差异**(gomob 载 Filament/ONNX 等，VINCreator 精简)致 libusb100 URB reap 受扰。

**下一步候选(需用户定，工作量大)**：
- A. **全 Java 路径**(弃 native-direct):反编译提取 VINCreator Java 类(UVCCamera/ApcCamera/USBMonitor+UsbControlBlock/IFrameCallback/StreamInfo…)进 gomob + System.loadLibrary("UVCCamera")(JNI_OnLoad 全初始化)+ 真 SurfaceView。=逐字 VINCreator，最可靠但最重。
- B. **root 手机 usbmon** 抓 gomob vs VINCreator 线级 URB 看 status=2 真 errno(-EPROTO/-EOVERFLOW/-EPIPE)→ 精准定位。当前手机无 root。
- C. 继续 native-direct 试差异(真 SurfaceView 替 AImageReader / dlsym 调 JNI_OnLoad + 最小 Java stub 类让 register_* 跑全)——递减回报。

**代码态(master,未 commit)**：native-direct 端口完整落地(eys3d_apc_session + JNI + Kotlin sink/CameraStack/Service + detect 优先 eYs3D + CMake mediandk),build 双 ABI 绿,跑到 stream-start。诊断临时件:eys3d_apc 日志、AImageReader 离屏窗(终态若走真 surface 则换)。armViaJava/Eys3dFdSession/pupil 路径仍留(apc 通后清)。

### 续36 补：窗 usage / register_* / 符号遮蔽 也证伪
- **窗/渲染非因**:AImageReader usage = CPU-only 与 CPU+GPU(GPU_SAMPLED|GPU_FRAMEBUFFER) 两种都 status=2 → 渲染路径(CPU ANativeWindow_lock / GL EGL)与 bulk URB 错无关。
- **register_uvccamera/register_apccamera 非因**:反汇编证二者**只调 registerNativeMethods**(RegisterNatives),无其它全局初始化 → native-direct 跳过它们(只 setVM)无害。
- **libusb 符号遮蔽非因**:日志 `libusb_init2`=v1.0.19(libusb100 专属,modern 1.0.27 无此 API)→ libUVCCamera 的 libusb_* 确绑 libusb100、未被 gomob 现代 libusb-1.0 遮蔽;submit_transfer 同 lib 同绑定域 → 也走 libusb100。
- **结论**:gomob 与 VINCreator 软件链逐字一致(connect/setVideoMode/PROBE-COMMIT/stream-start)、libusb100 正确绑定、所有可见差异已证伪,但 gomob bulk IN 即时 status=2、VINCreator 同调用出数据。差异落在**进程环境**或**线级 USB 行为**,本机无 root 无法 usbmon 抓线级 URB errno → native-direct 路径在此卡死。**建议转全 Java 路径(提取 VINCreator Java 类 + System.loadLibrary + 真 SurfaceView,= 逐字 VINCreator 进程行为)或 root 手机抓 usbmon。**

## 续37（2026-06-14, opus）：★ status=2 收窄到 DEPTH(IF2) 单流 — 彩色(IF1)不报错

单流隔离测试（Open() 临时只开彩色）：
- **彩色单流(IF1 only)**：status=2 = **0**（无错），但也 0 帧（mode25 color+depth 耦合，单开 IF1 设备不推数据，故无错可报）。
- **彩色+深度双流**：status=2 出现（30+/几秒）。
→ **报错源 = DEPTH(IF2) bulk**。VINCreator 的 IF2 出数据(broken frame 161936/163840)，gomob 的 IF2 即时 status=2(LIBUSB_TRANSFER_ERROR，疑 -EOVERFLOW/-EPROTO，有数据流动时才触发)。

**剩余唯一焦点**：gomob 的 IF2 深度 bulk 为何即时硬错而 VINCreator 同调用出数据。已知二者 connect/setVideoMode(36)/get_stream_ctrl(end 0)/setPreviewSize(alloc 163840)/prepare_preview(640,128 YUYV)/uvc_stream_start_bandwidth(bulk,submit,end 0) 逐字一致；libusb100 正确绑定；窗 usage(CPU/GPU)无关；register_*/JNI_OnLoad 无关。
**最强假设(未证)**：① gomob setPreviewSize 传裸 dims(640,128,mode=0)让 vendor 自协商深度帧，VINCreator 传 StreamInfo(显式 fmt/frame index) → 深度 COMMIT 的 dwMaxPayloadTransferSize/frame 可能不同 → IF2 URB 尺寸错 → 溢出 status=2。需抓 gomob vs VINCreator 深度 COMMIT 的 negFrame/negPayload 对比(vinshim 加 libusb_control_transfer 抓 COMMIT,或 vendor libuvc 加日志)。② 进程环境/线级(无 root 抓不了)。
**下一步**：抓深度 COMMIT 协商值对比是最高优先(若 payload/frame 不同→改 gomob 用 VINCreator 同款 StreamInfo/显式 frame index)。

**代码态**：Open() 已恢复双流(彩色+深度)。其余同续36。

## 续38（2026-06-15, opus）：★★★ 真根因 = 彩色帧格式错(IF1 排空不足)+ 回调局部引用崩溃 — 两修已落

用 libusb100 日志 shim(`.dev/gomob-shim/`,shim2.c 记录 control + bulk submit/complete,soname libusb100.so→NEEDED libusb100real.so)抓 gomob 的 COMMIT + bulk,与 VINCreator 逐字对比,**揪出真因**：

**1. libusb100 版本不是因(已排除)**：gomob staged 的 libusb100.so(200649,md5 91bc)≠ VINCreator APK 的(112408,md5 2508)。换成 VINCreator 精确版后 status=2 依旧 → 版本无关(11 个 vendor .so 里仅 libusb100 曾不同,其余 10 个逐字相同)。已统一用 VINCreator 版。

**2. ★ 真根因 = 彩色 COMMIT 帧错**：shim 抓到 gomob 彩色 COMMIT=**fmt2/frame2 MJPEG 1280×256 maxFrame=655360 maxPay=512**,VINCreator=**fmt1/frame1 maxFrame=0x34d000 maxPay=8192**(=高带宽 YUYV 2560×960,描述符 IFACE1 fmt1/frame1 UNCOMP)。即 gomob 给彩色配了**低带宽 MJPEG 1280×256**,VINCreator 用**高带宽 YUYV 2560×960**。**mode25 下深度依赖彩色 IF1 被【高带宽充分排空】维持耦合;低带宽彩色 → IF1/IF2 双端点 bulk 全 stall(status=2 actual=0)**。这正是项目"铁律"(深度需 IF1 持续排空)的精确化——不只要排空,要**够带宽的彩色帧**。与 gomob 续17 旧基线(YUYV 2560×960 彩色+深度连续)吻合。
   - **修**:`eys3d_apc_session.cpp` 彩色 setPreviewSize(1280,256,mode=1)→**(2560,960,mode=0/UNCOMP)**;离屏窗同改 2560×960。实测彩色 COMMIT→fmt1/frame1(maxFrame=0x4b0000,maxPay=3072),**XFER_DONE ep=81 status=0 actual=3072(IF1 成功排空!)**,status=2 归零。

**3. ★ 回调局部引用崩溃**：彩色一出帧,libUVCCamera `CallVoidMethod(cbObj,onFrame)` JNI ABORT `jobject is an invalid local reference`。因 MakeSink 用 NewObject 返**局部引用**,setFrameCallback 后 cameraOpenEys3dApc 返回即失效,preview 线程晚些回调时已悬空。vendor setFrameCallback **不升级 GlobalRef**。
   - **修**:MakeSink 内 `NewGlobalRef`(+DeleteLocalRef);session 存 color_cb_/depth_cb_,stop 时 `vm_->GetEnv`+DeleteGlobalRef。

**当前态**：两修已编译部署。彩色 IF1 已证 status=0 出数据(keepalive 成立);深度 IF2 应随之出帧——但**第 3 修部署后那次测试设备已掉出 OTG 总线**(之前的崩溃把整机搞掉线,0 USB devices)→ **需用户物理重插再验证**。重插后进深度页看 `eys3d_apc depth#N` + `XFER_DONE ep=82 status=0 actual=1024`。

**收尾(深度通后)**：清 libusb100 shim(换回 third_party/eys3d-vendor/lib 的纯 VINCreator libusb100.so,去 libusb100real.so)、清 VINSHIM/诊断日志、恢复 rgbSource.acquire()、armViaJava/Eys3dFdSession/pupil 路径删除、确认 depth metric mm、TODO/AGENTS_MEMORY 收口。

## 续39（2026-06-15, opus）：★★★★★ eYs3D mode25 深度真机端到端跑通(vendor 栈移植成功)

续38 两修(彩色 YUYV 2560×960 高带宽 + 回调全局引用)部署后,用户重插,真机**深度全程跑通**:
- `eys3d_apc: depth#180 163840B`(=640×128×2 mode25 深度帧)持续不断,~2.5fps,0 status=2、0 崩溃。
- `XFER_DONE ep=81 status=0 actual=3072`(彩色 IF1 排空=keepalive 成立);深度 IF2 随之出帧。
- 彩色帧 9830400B(2560×960×4 RGBX)按设计丢弃(仅保活)。
- 深度页截图(`.dev/screenshots/apc-depth-ok.png`):标题"深度相机 eYs3D RS-D550",DEPTH 面板实时 640×128 深度条 2fps frame#181。**月余"开 color 整机死 / bulk status=2"彻底跑通。**

**完整正确配方(权威,native-direct dlopen libUVCCamera.so + dlsym)**:
1. 11 个 vendor .so 全用 **VINCreator APK 精确版**(尤其 libusb100.so=md5 2508,gomob 曾误用 91bc 版)。
2. setVM(vm)(旁路 JNI_OnLoad) → ctor → setExternalStoragePublicDirectory(filesDir) → connect(vid,pid,fd,bus,dev,"/dev/bus/usb") → **setVideoMode(36)** → 双流。
3. **彩色 = YUYV 2560×960 mode=0**(IFACE1 fmt1/frame1,高带宽 maxPay,充分排空 IF1=mode25 深度耦合命脉;MJPEG 1280×256 带宽不足→双端点 stall)。深度 = YUYV 640×128 mode=0 switch=1 pixfmt=0。
4. 每路:setPreviewSize → **setPreviewDisplay(离屏 AImageReader 窗,startPreview 需非空 ANativeWindow)** → setFrameCallback(**全局引用** sink,pixfmt color=3/depth=0,switch 0/1) → startPreview(0/1)。
5. 帧:libUVCCamera onFrame(ByteBuffer,int seq) → Kotlin sink → nativeApcFrame(用 **GetDirectBufferCapacity** 取真字节数,onFrame 第二 int 是帧序号非字节数) → OnApcFrame:depth(163840B)→core_.OnRawDepthFrame,color 丢弃。
6. 不走 armViaJava(vendor 栈内部自做 arming+ZD)、不 Java force-claim(libusb100 自 detach uvcvideo)。detect() Berxel+eYs3D 同插优先 eYs3D。

**待确认**:深度内容是否真 metric(截图深度条偏稀疏,疑场景远/空,需对准 0.5-2m 有纹理物复核;mode25 理论出真 ASIC 深度非 14bit 退化)。**待收尾**:清 libusb100 shim(换回纯 VINCreator libusb100.so 去 libusb100real)、清 VINSHIM/eys3d_apc 诊断日志、恢复 rgbSource.acquire()、删 armViaJava/Eys3dFdSession/pupil 旧路径、TODO/AGENTS_MEMORY 收口、commit。

## 续40（2026-06-15, opus）：★ 深度渲染崩溃修复(稳定可迭代) + 深度内容=退化噪声(非布局/解码,ASIC 未真算)

**1. ★ 渲染崩溃修复 → 稳定**:深度起线程后 SIGSEGV 在 vendor `UVCPreview::DepthToRGB32`(读未就绪 ZD 表指针 [this+0x207c0],竞态;偶发)。gomob 弃 vendor 深度显示渲染(走 frame callback),故 startPreview(depth) 后立即 `set_preview_display(cam,nullptr,1)` 摘深度窗 → do_preview 每轮判窗=null 跳渲染,callback 照常。**修后 depth#1500 连续 10min+ 0 崩溃、设备不再自掉线 → 可自由迭代无需重插**。彩色窗保留(copyToSurface 不崩)。

**2. ★ size 取值修**:onFrame(ByteBuffer,int) 第二 int 是帧序号非字节数;nativeApcFrame 改用 `GetDirectBufferCapacity` → depth=163840B(640×128×2)、color=9830400B(2560×960×4 RGBX)。

**3. ★ 深度内容 = 退化噪声(关键,与用户"数据处理"判断需对账)**:落盘第 5 帧原始 IF2(163840B,`.dev/apc_depth_raw.bin`)离线分析:
- 640×128 u16,值域满 16-bit(max 65526,**非 11-bit 视差 0..2047**);低 11 bit=视差(max 2045 在范围内),高 5 bit=flags(0..31,32 distinct)。
- **但视差是噪声**:相邻像素 |Δdisp| 中位数 ~600、仅 ~5% 相邻对 <50;**行主序/列主序/转置/偶奇解交织/位掩码 全试,都是竖条噪声**(渲染 `.dev/dep_*.png` 左半全黑+右半竖条,与续13「14bit 退化图样」一模一样)。distinct=640 each×128、奇行恒等(常数复制)。
- **结论**:不是布局/字节序/解码误判 —— 是设备根本没真算 stereo 视差,吐退化噪声。**videoMode=36 写了(shim 实证 `20 f0 24`),但深度 ASIC 未真正进 mode25 stereo 计算**。
- **疑因(待查)**:gomob 取流配置与 VINCreator 在「真正驱动深度 ASIC」上仍有差异——最可能是 mode25 的 stereo 彩色帧/depth-enable 寄存器/IR 投射器。注意续29「彩色 4160×832」疑是 **HLSD8(=4160×832)误记**,RS-D550 mode25 彩色真实分辨率待重核(续15 说 1280×256 MJPG)。

**下一步候选**:① 抓 VINCreator(真出深度)的 IF2 原始帧对比(同 raw→续13 processing 可出真深度=纯处理问题;不同→gomob 配置没驱动 ASIC);② RE libDepthMSR/DepthToRGB32 的 mode25 深度后处理(rectify+视差精化);③ 对比 gomob vs VINCreator 完整控制序列找「depth-enable」缺失步。**临时诊断待清**:OnApcFrame 第 5 帧落盘、libusb100 shim。

## 续41（2026-06-15, opus）：★ 深度内容退化真因 = 缺 vendor 深度 ASIC init(e0/e2 sensor 寄存器,counter 门控);深度【流】已通,内容待续

续40 落盘原始深度离线分析 + 对比 VINCreator 寄存器写序列,定位深度内容退化(竖条噪声)真因:

**寄存器写序列对比(shim 实证)**:gomob 仅写 `20 f0 24`(videoMode,我的 setVideoMode(36));VINCreator 写 **三笔** `20 f0 24` + `20 e0 03` + `20 e2 06`(后两笔=sensor HW 寄存器,深度 ASIC stereo 配置)。**缺 e0/e2 → ASIC 不真算 stereo,吐退化噪声**(原始视差相邻像素 |Δ| 中位 ~600、仅 5% 相邻<50,行/列/掩码/解交织全试均噪声=非布局误判)。

**e0/e2 写不进(关键难点)**:VINCreator 的 e0/e2 在 ctrl seq 是【counter 门控 entity-4 flash 序列中段】(`OUT 20 e0 03` 夹在 `9b 41 05..` ZD 读块 + `a0 04/a0 e2` 查询之间,depth COMMIT 之后)。直接 `UVCCamera::setHWRegister(0xe0,3,flag)` 真机恒 **rc=-21**(flag 试 1/0x11 都不行;非 flag 问题,是 counter/状态门控——不在 vendor 内部 flash 序列里发就被拒)。`setHWPostProcess(true)` rc=0 且触发了 `a0 xx` 查询序列,但**反而把深度流打断**(0 帧)。→ e0/e2 是 vendor 内部 depth-init 序列的一部分,native-direct 单发写不进。

**结论**:gomob native-direct 复刻了 VINCreator 的【取流】(connect/videoMode/双流 COMMIT/bulk),但没复刻【深度 ASIC init 后处理】序列(e0/e2 + a0 + SwPostProc/libDepthMSR)。这正是用户说的"数据处理问题"——是 vendor 深度管线 init,非简单 reshape。**深度【流】端到端通(163840B mode25 帧持续、稳定不崩),深度【内容】退化待解**。

**下一步候选(需用户定)**:
- A. **全 Java ApcCamera 路径**(推荐):提取 VINCreator Java 类(ApcCamera/UVCCamera/USBMonitor/StreamInfo/IFrameCallback…)+ System.loadLibrary("UVCCamera")(JNI_OnLoad 全初始化)+ 用完整 API(createSwPostProc + setPreviewSize(StreamInfo) + setDepthFiltersEnable),= 逐字 VINCreator 进程行为,自然走完 depth-init → 真深度。重(提取/编译 Java 类)但最可靠。
- B. **继续 native-direct RE**:把 e0/e2 写进 counter 门控序列(需复刻 a0 查询 + flash 中段时序),或调通 createSwPostProc/DoImagePostProcessing native-direct(RegisterNatives 绑定,无干净符号,难)。
- C. **取 vendor 处理后的深度**(非 raw):走 libUVCCamera 显示侧 DepthToRGB32/DoImagePostProcessing 拿处理后深度。仍需 createSwPostProc。

**当前代码态(可用)**:Run=apc 路径,深度流稳定(退化内容)。临时诊断待清:OnApcFrame 第 5 帧落盘、libusb100 shim(.dev/gomob-shim)、第三方 vendor lib 的 libusb100=VINCreator 版(md5 2508)。

## 续42（2026-06-15, opus）：★ 用户选「全 Java ApcCamera 路径」拿真深度 — vendor Java 类已提取+编译绿(地基成)

为拿 mode25 真 metric 深度(续41:e0/e2 depth-ASIC init native-direct 写不进 -21),用户选全 Java 路径=逐字复刻 VINCreator 进程行为,让 vendor 栈自然走完 depth-init。

**地基已成**:VINCreator 的 `com.esp.android.usb.camera.core` 包(18 类:ApcCamera/UVCCamera/USBMonitor(+UsbControlBlock)/StreamInfo/IFrameCallback/IStatusCallback/.../glrender 子包)从 `.dev/vincreator-jadx/sources/` 复制到 `core/native-bridge/src/main/java/com/esp/android/usb/camera/core/`,**清 jadx 伪影后编译绿**(UVCCamera 130 + ApcCamera 111 native 声明全保留=RegisterNatives 必需;去 jiangdg ref;glrender GL 路径保留)。包名严格不变(FindClass 用)。build SUCCESSFUL。

**集成设计(待接)**:
1. **native 绑定**:dlopen("libUVCCamera.so",RTLD_LOCAL)隔离(避现代 libusb-1.0 遮蔽 libusb100)+ dlsym JNI_OnLoad 手动调(传 vm)→ register_uvccamera/apccamera(FindClass 到 gomob 内的 Java 类)+ RegisterNatives 绑定 + setVM。【关键:不能 System.loadLibrary(会被 gomob libusb-1.0 遮蔽),用 dlopen RTLD_LOCAL + 手调 JNI_OnLoad】。
2. **UsbControlBlock**:`new USBMonitor(ctx, noopListener)`(仅取 mUsbManager)→ `new UsbControlBlock(monitor, eys3dDevice)`(内部 openDevice 取 fd,gomob 已有权限)→ ApcCamera.open(ctrlBlock)。gomob 不再自开 conn(交 UsbControlBlock)。
3. **ApcCamera 生命周期(复刻 VINCreator startCameraViaDefaults)**:open → setVideoMode(36) → **createSwPostProc(8)** → setPreviewSize(colorStreamInfo)+setFrameCallback(colorCb,3,0)+startPreview(0) → setPreviewSize(depthStreamInfo 640×128)+setFrameCallback(depthCb,0,1)+startPreview(1)。StreamInfo 字段=width/height/bIsFormatMJPEG/interfaceNumber(depth:640,128,false,2)。
4. **深度路由**:gomob 的 IFrameCallback.onFrame(ByteBuffer,int) depth 帧 → DepthFrame 流。createSwPostProc + 完整 setPreviewSize(StreamInfo) 序列应触发 vendor 写 e0/e2 → 真 metric 深度。

**当前态**:esp 类编译绿但未接线(dead code,gomob 仍走 native-direct=退化深度)。深度【流】端到端通(native-direct,稳定)。Java 路径接线(USBMonitor/UsbControlBlock + ApcCamera 生命周期 + 深度路由 + 真机验证)是下一聚焦块。临时诊断待清:OnApcFrame 落盘、libusb100 shim、eys3d_apc 诊断日志。

## 续43（2026-06-15, opus）：★★ 全 Java ApcCamera 路径已接线 + 编译/安装绿 — 待真机验证真深度

按续42 设计接完全 Java 路径，build+install SUCCESSFUL（2510DRK44C）。新增/改：
- **native 绑定** `NativeBridge.bindEys3dVendorJni()`（camera_session_jni.cpp）：`dlopen("libUVCCamera.so", RTLD_NOW|RTLD_LOCAL)` + `dlsym("JNI_OnLoad")` 手调（传 `env->GetJavaVM`）→ vendor RegisterNatives 到 gomob 内同名 esp 类 + setVM。幂等。**必须在 `new ApcCamera()` 前调**（`mNativePtr=nativeCreate()` 是字段初始化）。
- **UVCCamera.java 静态块**去掉 `System.loadLibrary("usb100"/"uvc"/"UVCCamera")`，只留 `isLoaded=true`（避免 app 默认 RTLD_GLOBAL 让 libusb100 符号遮蔽 gomob libusb-1.0）。
- **新 `Eys3dApcCamera.kt`**（PUSH 包装）：`USBMonitor(ctx,noop)` → `UsbControlBlock(mon,device)`（内部 openDevice，gomob 已有权限）→ `new ApcCamera()` → `open(cb)==1` → `setVideoMode(36)` → `getZDTableValue(fileIndex)` → 双流 `setPreviewSize(w,h,1,5,mode,1.0f,sw)`+`setPreviewDisplay(离屏SurfaceTexture,sw)`+`setFrameCallback(cb,pixfmt,sw)`+`startPreview(sw)`+撤窗`setPreviewDisplay(null,sw)`。
- **Eys3dCameraService.kt** 整改：删 native-direct（stack/pump/armViaJava/reset 两段式），改 Eys3dApcCamera 回调 → DepthFrame/ColorFrame 发流 + 看门狗判活。

**权威参数（CameraModeKt.DEFAULT_ROSIE4_U2_MODE 逐字）**：ROSIE4(pid 0x206) → videoMode=**36**、color **1280×256 MJPEG@5**(IF1,pixfmt=3 RGBX)、depth **640×128 YUYV@5**(IF2,pixfmt=0 RAW)。注意 color 是 1280×256 MJPEG（**不是** native-direct 误判的 2560×960 YUYV；那是手 calloc 裸构造的伪需求）。

**★ 关键订正：深度回调给的是「原始视差」不是 mm**。VINCreator 在 Java 侧查表转 metric：`mm = zdTable[disparity]`，`zdTable=apc.getZDTableValue(fileIndex)`(int[4096]，从设备 flash 读)。`JavaCameraUtils.calculateDepth` 按 videoMode 取视差：videoMode∈{4,9,20,25,36,...} → 视差=u16 小端整取（**无 ×8**）；videoMode∈{1,6,17,22} → byte×8。fileIndex 选择（openJob 公式）：USB2 且深度高 128 → **1**；高 480→0；USB3→2。native-direct 同时漏了①深度 ASIC stereo init（缺 e0/e2，吐退化视差）②ZD 表查表（视差→mm）两步。

**待验证（需插相机）**：①bindEys3dVendorJni 返回 >0（JNI_OnLoad RegisterNatives 成功，gomob esp 类被绑）②open==1 ③getZDTableValue len>0 ④深度内容是否真 metric（不再退化噪声）。验证手段：shim 抓 e0/e2 是否在 Java open 期间被写（确认 ASIC init 触发）。

**临时诊断仍在（验证后清）**：libusb100=shim(.dev/gomob-shim，9072B)+libusb100real.so；native-direct 旧代码（Eys3dApcSession/cameraOpenEys3dApc/CameraStack.startEys3dApc/Eys3dProvenArming.kt/Eys3dCleanArming.kt）dead 待删；OnApcFrame 第 5 帧落盘。

## 续44（2026-06-15, opus）：★★★ 全 Java ApcCamera 路径真机出真 metric 深度 — 成 ★★★

2510DRK44C 直插 OTG（无 hub）真机实测：`depth#1..180+` 持续稳定（~2fps），`disp[nz=50% max=2045] mm[nz=50%] center[disp=1052 mm=459mm] flagHi=47%`，center 459mm 跨 180 帧恒定（=稳定真深度，非 native-direct 的逐帧噪声），max disp 2045 vs center 1052（空间有起伏=真表面）。UI「DEPTH·TURBO·2fps·640×128」渲染正常。**月余「mode25 真深度」目标达成**。

开流序列全绿：`bindEys3dVendorJni→0x10006`（vendor JNI_OnLoad RegisterNatives 到 gomob esp 类成功）→ `UsbControlBlock fd=180`→`ApcCamera.open ok product=RS4 usb3=false pid=518`→`getZDTableValue(1)→len=2048`→双流 startStream→深度回调持续。

**两个决定性修正（续43 设计基础上）**：
1. **彩色必须 2560×960 YUYV（mode=0），非 CameraModeKt 预设的 1280×256 MJPEG**。1280×256 MJPEG → IF1/IF2 双端 `status=2` 全超时 0 帧（经验铁律：深度 IF2 需彩色 IF1【高带宽】排空解锁）；换 2560×960 YUYV 后 `ep=81 status=0 DATA` 彩色流通 + 深度 IF2 解锁。CameraModeKt 预设 ≠ 设备实际可流配置。
2. **深度视差取低 11 位**：回调 u16 原始值高 5 位是 flag（置信/有效），低 11 位才是视差（`disp = raw & 0x07FF`）。不掩码则 disp 高达 65526 越界 ZD[2048]→仅 1% 有 mm；掩码后 max=2045、50% 有效 mm。**注意 VINCreator JavaCameraUtils.calculateDepth(videoMode 36) 不掩码取全 u16**——本机帧高位带 flag，必须掩码（与续43 JavaCameraUtils 逐字描述有出入，以实测为准）。
3. **彩色回调必须 drain-only**：在 vendor native 回调线程做 2560×960 RGBX→RGB24（2.4M 像素）转换会阻塞 UVC 取流线程→bulk 背压→双端 status=2 间歇 stall。改成回调仅 return（vendor 复用缓冲=排空）+ 刷看门狗后，深度流稳定到 180+ 帧。eYs3D 彩色本是 L+R 立体对、非展示 RGB（展示走 HLSD8），不出帧。

depth→mm 链：`mm = zdTable[raw & 0x07FF]`，zdTable=`apc.getZDTableValue(1)` int[2048]（USB2 深度高 128→fileIndex=1）。

USBMonitor 构造 `new Handler()` + SurfaceTexture 需 Looper → ApcCamera 生命周期统一在 `HandlerThread("eys3d-apc")` 跑（Dispatchers.IO 无 Looper）。离屏窗用 `SurfaceTexture(false)`（detached，免 GL 纹理），startPreview 后 `setPreviewDisplay(null,sw)` 撤窗跳渲染。

**清理已完成（2026-06-15）**：libusb100 shim→VINCreator 原版（arm64 md5 2508、v7a f3706，APK 已验无 libusb100real）；诊断日志降为健康日志（depth 每 60 帧 valid%/center mm，color 首帧）；删 native-direct 死码（Eys3dApcSession.{h,cpp}/cameraOpenEys3dApc/nativeApcFrame/Eys3dApcFrameSink.kt/CameraStack.startEys3dApc/Eys3dProvenArming.kt/Eys3dCleanArming.kt）。生产清理后真机复验 depth#60 valid=50% center=459mm 持续。commit 待用户指示。

## 续45（2026-06-15, opus）：★ 深度【流+metric 通了但空间退化】= 2 行复制条纹，非真 2D 深度

续44 报"真深度"过早。真机截图 + 离线分析帧 #30（640×128 u16）证：**深度帧只有 3 个唯一行 = 2 条扫描线被竖直复制 64 次**（period 1280 u16 全帧 100% 相等；左半 cols 0-319 全 0，右半 320-639 有值但逐列噪声 Δmm 中位 442mm 仅 3% 平滑）。渲染出来=左半黑+右半细竖条纹（PIL 出图证）。即设备**没在出真 2D stereo 深度**，吐的是 2 行退化图；ZD 查表把退化视差转成 metric mm（center 459mm 跨帧稳=因为就 2 行在复制，非真场景稳定）。**这正是用户早就说的"长这样、数据处理有问题"的条纹**。native-direct 当年的"噪声"应是同一 2 行复制态。

**已排除的假设**（VINCreator ROSIE4=product "RS4" 路径逐字核对）：① setQualityRegister —— `getQualityCfg("RS4")` 找 `QualityCfg/RS4_..._cfg`，**VINCreator assets 里没有 RS4 文件**（只有 EX80xx/YX80xx/HYPATIA/DEFAULT）→ 返回 null → VINCreator 自己也跳过，非缺失项。② setDepthDataType —— 只在老 CameraMainActivity（spinner UI）调，新 CameraPresenter 不调。③ configureInterleaveMode —— 仅当 `isSupportedInterLeaveMode` 才 setInterleaveMode，ROSIE4 大概率不支持=no-op。④ createSwPostProc/doSwPostProc —— 仅老 activity；新路径深度回调直接 calculateDepth→ZD→mm 显示。

**最强未验线索 = 深度 StreamInfo 用硬编码 640×128，VINCreator 用设备枚举 `getStreamInfoList(2)[mStreamInfoIndexDepth]`**（setupDepthSize：宽度 × i，i 对 videoMode≠1/6/17/22 取 1）。若设备实际枚举的深度尺寸 ≠ 640×128，硬编码协商错 → libUVCCamera 误组帧 → 2 行复制 + 左半 0。**下一步（已埋日志，待 replug）**：打印 `getStreamInfoList(1)/(2)` 真实枚举项 + 落帧 #20/#40 比对 static-vs-live。再决定是否改用设备枚举 StreamInfo。其它候选：USB2 mode25 深度本就受限（真 2D 需 USB3 640×480？但手机 OTG 是 USB2）。

诊断码在 `Eys3dApcCamera.kt`（枚举日志 + frame#20/40 落盘）。**当前不是"完成态"——流/绑定/ZD 都对，但深度图退化未解。**

## 续46（2026-06-15, opus）：IR 开了/换 color 模式都没解退化深度；e0/e2=IR 投射器订正；剩 wedge 或 VINCreator oracle

续45 的退化深度（2 行复制条纹、static、左半 0），本轮深挖排除两大候选：

**① e0/e2 = IR 投射器，不是 depth-ASIC 开关（订正旧 finding_p100r3/续41 措辞）**：ApcCamera 常量 `FIRMWARE_ADDRESS_IR_CURRENT_VALUE=224=0xe0`、`FIRMWARE_ADDRESS_IR_MAX_VALUE=226=0xe2`。VINCreator `IRManager.initIR(firstLaunch)` = `setIRCurrentValue(IR_DEF_VALUE=3)`(写 e0=03) + `setIRExtension(false)`→`setIRMaxValue(IR_DEF_MAX_VALUE=6)`(写 e2=06)。即续41 说的"e0=03/e2=06 depth ASIC init"其实是**开 IR 投射器**(current=3,max=6)。gomob 加 `cam.setIRMaxValue(6)`+`setIRCurrentValue(3)` 实测 **rc=0 成功**(min=0 max=6)，**但深度仍 2 行复制退化**——IR 不是病根。

**② color 分辨率 tension（实测）**：`2560×960 YUYV` color → 深度 IF2 持续出整帧但退化；`1280×256 MJPEG` color（mode25 原生、VINCreator 用）→ 彩色能流(ep81 87 包)但**深度 IF2 仅 3 包即停**(无整帧)。即换回 mode25 原生 color 反而拿不到深度。两种 color 都没真 2D 深度。当前代码定回 2560×960（至少深度流动可见，退化）。

**③ 帧确认 static**：帧#20 vs #40 逐位 ~0.0% 不同 = 设备发固定退化帧，非 live 2D。device 枚举深度尺寸含 640×128（我用的就是它，非维度错）。setQualityRegister(RS4 无 cfg)/setDepthDataType(老 activity)/interleave(no-op) 均已排除。

**剩两条路**（未验）：
- **A. 设备 wedge**：我本会话开关 ~15 次，M6.9.7 早记"反复冷启/claim wedge，仅上电首跑能产真深度"。需**完整断电**(拔下等 30s+ 再插)后 gomob **首跑**验真深度，而非快速重插。
- **B. VINCreator 运行时 oracle**：在同机跑 VINCreator 抓它的 IF2 bulk/寄存器/帧，逐字 diff gomob——确认是设备态还是我们漏了运行时某步。

绑定/流/ZD/IR/metric 基建全通；**真 2D 深度仍未解**。

## 续47（2026-06-15, opus）：★★★ 真深度突破 = eYs3D 彩色必须 mode25 原生 1280×256（非 2560×960）★★★

续45/46 的"2 行复制条纹/center 恒 459mm"罐头帧根因揪到：**eYs3D 彩色流分辨率错**。
- **2560×960 YUYV color** → 传感器进【全分辨率模式】→ 深度 ASIC 拿错输入 → 吐固定罐头帧（深度引擎没真算，center 永远 disp1052=459mm，跨所有 session 一模一样）。
- **1280×256 MJPEG color（= mode25 原生 / CameraModeKt ROSIE4_U2 / VINCreator 逐字）** → 传感器【binned 模式】→ **深度 ASIC 真算立体视差**：近物 disp≥200（1 万+像素）、离线渲染见近物轮廓、有效率随场景 7~23% 变化、帧间有变化（真 live）。

**用户关键线索（都对，缺一不通）**：① VINCreator 先开彩色过几秒才出深度 → 加彩色暖机（首帧+4s 再开深度）；② 补光灯在【独立 HLSD8 RGB 相机】模块上，开 HLSD8 才点灯（散斑）→ rgbSource 先 acquire；③ IR 设在【彩色流起来后】（之前设在流前被复位）。配合 keep-surface（不撤窗）+ AE(setExposureMode8)，1280×256 才既出流又出真深度（之前 1280×256 只 3 包不流，是缺这些）。

**当前态**：真深度但【稀疏】（7~23% 有效，大片黑）—— 质量问题非死图。待 densify：eYs3D IR 电流 3→6（更亮散斑）、VINCreator DepthFilterModel 补洞、场景纹理。
**关键参数**：eYs3D color 1280×256 MJPEG@5（sw=0）、depth 640×128 YUYV@5（sw=1）、videoMode 36、IR e0=3/e2=6、AE=8、ZD getZDTableValue(1)、视差低 11 位查 ZD→mm。HLSD8 RGB 压到最小档（1280×256）避免抢 eYs3D 带宽。
