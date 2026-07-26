# eYs3D mode25 零原厂自研栈（P1b）— 2026-06-15 / 续 2026-06-16

**结论：零原厂取流栈已建成、端到端跑通、arming/控制握手/mode25 配方全部攻破；唯 RS-D550 mode25 bulk 恒 -EPROTO
(双端点 0 字节，设备不进 streaming 态)。★2026-06-16 末订正：libusb 后端【非】根因(stock 与 saki 开源 libusb 给完全
相同 -EPROTO)；真根因 = mode25 起流/arming 序列复刻不全。工作路径仍是 Java ApcCamera(厂商，出真深度)。**

## ★★★ 2026-06-17 突破：纯 native 直驱厂商 C++ 引擎，零 Java 编排出 mode25 真深度 ★★★

**结论：放弃"零原厂自研取流栈"（-EPROTO 硬墙未破；且按用户架构定调"硬件交互层可由厂商提供"无需破）。改为
【native 直调厂商 libUVCCamera 的 UVCCamera/UVCPreview/FrameGrabber C++ 类】取流，复用厂商已验证起流链，仅留
Java UsbManager 拿 fd。真机 2510DRK44C 实测：eYs3D 彩色 L'(1280×256) + 深度(640×128 mode25 热力图) + HLSD8 RGB
全部渲染，连续 ~4-7fps 不崩，帧路零 JNI。code：`native/eys3d/android/eys3d_vendor_cpp_session.{h,cpp}` +
`vendor_uvc_abi.h`；派发 `eys3d_fd_session.cpp` open_fd 的 kUseVendorCpp；Kotlin `Eys3dCameraService`
USE_INDEPENDENT_NATIVE=true + bindEys3dVendorJni(setVM) + startPollLoop(depth+color)。**

**为何可行（反汇编实证）**：
- FrameGrabber 回调 = 纯 C 函数指针 `cb(depthVec,dW,dH,colorVec,cW,cH,serial,ctx)`，无 jobject → 零 Java 接帧。
- `new UVCCamera`(over-alloc 0x8000 裸内存,vendor ctor) → `connect(vid,pid,fd,bus,dev,"/dev/bus/usb")` 内部
  dup(fd)+uvc_get_device_with_fd+uvc_open+建 2 个 UVCPreview(共享 1 个 FrameGrabber)。把 UVCCamera 内
  FrameGrabber([cam+0x2430])的回调([fg+8])/ctx([fg+0x10]) repoint 到自建 trampoline（运行时校验
  [fg+8]==livePlyCallback 才改,防 ABI 偏移错）→ 帧全 native 进 gomob。
- 控制面经 UVCCamera::setVideoMode(36)/setFWRegister(IR e2=6/e0=3)/setExposureMode(8)/GetZDTable(vendor 内含 CVideoDevice)。

**踩坑链（逐个真机修，皆源码/反汇编定位）**：
1. connect 参数顺序 = **(vid,pid,fd,bus,dev,usbfs)** 非 (fd,vid,pid,...)（ApcCamera.java:484 实证）；错则 uvc_get_device_with_fd 拿 fd=pid=518 → -4。
2. usbfs 传**根目录 "/dev/bus/usb"** 非完整节点（getUSBFSName 砍末两段,UVCCamera.java:728）；设备由 bus/dev 选。
3. **setExternalStoragePublicDirectory 必设**：connect 内部对它 strdup(NULL) 崩。
4. **startPreview 硬要非空 ANativeWindow**([UVCPreview+0x8] 门控)：用 AImageReader 离屏窗口（mediandk,纯 native）；FrameGrabber.isStarted 后 do_preview 旁路绘制,窗口不真渲染。
5. startPreview 起的 **capture_thread_func 调 getVM()→空崩**：必先 vendor `setVM(JavaVM)`（经 bindEys3dVendorJni→JNI_OnLoad，JNI_OnLoad 只做 setVM+RegisterNatives,不碰 libusb）。
6. **2026-07-14 单位订正：FrameGrabber 回调是 raw disparity×8，不是 metric mm**。原厂 BIN 的 `depth_data_type=1` 与 native 固定向量证明 `z=f·B/(raw·0.125)`；旧“1746mm”其实是视差值。端侧用 `DepthSampleFormat.DISPARITY_X8_U16` 明示，VIN 上传保留原始值，服务端原厂标定恢复毫米坐标。

**ABI 纪律**：dlopen(RTLD_LOCAL) 隔离 vendor libusb100(不遮蔽 gomob libusb-1.0)；不在 gomob(__1) 侧构造 __ndk1 shared_ptr/vector（让 vendor 自建自销）；回调里 vendor `vector<uchar>&` 当 3 指针 POD 只读。

**收口订正**：mode25 是 1280×960 全幅的竖向中心裁带 1280×256，再统一缩放到 640×128；当前 `fx=fy=614.60498,cx=324,cy=65.4325`，旧各向异性 `fy≈163.9` 已撤销。其余 native 直驱、日志、external-storage、Java shim 退役与 harness 结论不变。

**尾巴收口（2026-06-17 续）**：
- **v7a 缺口 → 已闭环（不再是 TODO）**：厂商 C++（libUVCCamera/libESPDI）只 arm64-v8a；我方 ABI offset（0x2430/+8/+0x10）是 64 位布局，32 位指针宽不同必失配。VINCreator 的 v7a 二进制是**另一代 SDK**（缺 depthfilter/DepthMSR/SwPostProc 后处理链 + 带 libUVCCamera1/libuvc1 后缀变体），不可移植。结论：补 v7a = 取匹配版本 vendor 二进制 + 重做 32 位 struct RE，**无现役 32 位扫描设备，低优先级**。现态：`Eys3dCameraService` 加 `Build.SUPPORTED_64_BIT_ABIS.isEmpty()` 前置守门 → 32 位机即时报「需 64 位手机」，arm64 不受影响；即便绕过，native `LoadVendorUvcAbi` dlopen 失败也 MarkError 不崩。
- **fy 缩放-vs-裁剪已闭环**：原厂 BIN 同时给出全幅 `cy=482.865` 与 mode25 `cy=130.865`，差值352正好是 960→256 的中心裁带；640×128 再统一缩放0.5，故 `fx=fy=614.60498`。
- **per-device ZD/rectify flash 内参经 JNI 下发**：M6.5 里程碑（非尾巴），终态单一标定真理源。
- **vendor C++ 路多轮稳定性回归已闭环**：2026-07-16 真机 3 轮启动/双路首帧/退出 teardown 对称完成，连续 7 次 VIN 采集无崩溃，PSS 无单调增长。

## 2026-07-16 FrameGrabber 启动与销毁竞态

### Why

`FrameGrabber::Open()` 只负责创建 worker 并立即返回，worker 稍后才设置 started。旧代码在返回后立刻检查一次 `isStarted()`，会在正常调度窗口把启动误判为失败并开始 teardown；厂商线程随后继续运行，触碰已析构 mutex，造成 native abort。这不是手机资源不足，也不是 RGBD 带宽耗尽，而是跨线程生命周期没有建立 happens-before。

### How to apply

- `Open()` 后必须经过 worker 启动屏障，只在 started、明确 failed 或超时后裁决。
- `Close()` 完成门与 join 必须先于 callback context、FrameGrabber、窗口和 UVCCamera 对象释放；极端 join 失败时隔离对象，禁止带活线程析构。
- callback context 生命周期必须覆盖全部厂商回调；Kotlin 用 typed native session state 区分 `Starting` 与终态，启动期单次空 poll 不判死，首帧 deadline 为 10 秒。
- 任何生命周期改动都跑 `eys3d_vendor_worker_lifecycle_test`、三组 CameraStack/Eys3d Kotlin 测试和真机 `eys3d_vendor_cpp` harness；日志必须同时出现启动屏障、首帧和成对 teardown，且无 SIGSEGV/destroyed-mutex/FATAL。

---

## 2026-06-16 续：mode25 stream-start 完全解码 + bulk -EPROTO 硬墙

**最大收获：找到厂商 SDK 的完整 usbmon 抓包** `.dev/eys3d-sdk/sdk_stream_trace_mode25.txt`(mode25,dev010)、
`sdk_stream_trace.txt`(另一更高分辨率模式,**真出流** 61062 带数据完成)。比 VINSHIM 的 class-only 残捕(rsd550_clean_seq.txt)完整得多。

**mode25 起流序列(usbmon 实证,权威)**：arming → setVideoMode `20 f0 24 00` → IR(e2/e3/e0) →
**a0 激活 `a0 00`/`a0 01`/`a0 03`**(回读 a0 03→24) → counter 0a00=live → **COMMIT_IF1**(wIdx1,fmt2/frm2,maxVFS655360,
**maxPay512**) → **SET_INTERFACE(1,0) `01 0b 0000 0001`**(彩色接口) → **COMMIT_IF2**(wIdx2,fmt1/frm4,maxVFS163840,
**maxPay1024**) → 双端点 bulk submit(URB=**maxPay**) + 0x83 中断心跳。

**关键订正(推翻多条旧结论)**：
- **mode25 触发器 = a0 激活 + SET_INTERFACE(1,0)，不是 F5 trigger**。F5(`20 f5 00 00`)+counter bump+启动 clear_halt
  是【14bit host loop】(`native/eys3d/host/eys3d_stream_loop.cpp`,RHEL9 60s/1.39GB 实证)的机制，对 mode25 是噪声/有害。
- **bulk URB 尺寸 = dwMaxPayloadTransferSize(彩 512/深 1024)，不是 16384**。旧 stream.c `max(maxPay,16384)` 是无 usbmon 时的错猜，已改回 maxPay。工作流 URB 严格= maxPay(`sdk_stream_trace.txt` 彩 2048/深 3072)。
- **SET_INTERFACE(1,0) 确实发**(VINSHIM 只抓 class 控制，漏了它，故旧"全程无 SET_INTERFACE"错)。
- **符号互位**：gomob_native 直链 libusb 的调用被进程内 libusb100real(VINSHIM 截 SUBMIT_BULK)符号互位 → async URB 全不回收(intr[done=0])。**只有 libuvc_gomob(dlopen RTLD_LOCAL)绑定干净 stock libusb**(其 0x83 心跳实测 ~570 拍/s、回调正常)。故 RunEys3dStreamLoop 经 Eys3dUsbSetHostApi 直链路在 Android 不可用。

**★★ 硬墙根因订正(2026-06-16 末，实测两后端对照)★★**：
- libuvc_gomob(我的 libuvc + **mainline** libusb) RS-D550 深度 → `D ep=82 status=2`(-EPROTO,0 字节)。
- libuvc_lusb100(**同一份**我的 libuvc 源 + **saki 开源** libusb100) RS-D550 深度 → **完全相同的 `D ep=82 status=2`**。
- 同一 libuvc_lusb100(saki libusb) 跑 HLSD8(标准 UVC) → 正常出流。厂商 APC(libUVCCamera+厂商 libuvc+libusb100,Java) RS-D550 → 真深度。
- ⇒ **libusb 后端不是根因**(两后端同 -EPROTO)；**我的 libuvc 也不是**(HLSD8 在它上面正常)。真根因 = **RS-D550 mode25 起流/arming 序列我方复刻不全**：设备 ACK 全部控制(arming ok=2016 fail=0)、libuvc PROBE/COMMIT/SET_INTERFACE 全 rc=0，但设备**不进 streaming 态**(bulk 端点 -EPROTO=不吐)。只有厂商完整起流链能让它真流。
- (符号互位仅影响 gomob_native 直链 libusb 的 RunEys3dStreamLoop 直链路；与本硬墙无关。)
- **无 working mode25 usbmon 可逐字复刻**(手头 mode25 抓包是失败态:只 submit 深度、无彩色、19s hang+cancel)。

**下一步候选**：
1. **抓一份真·working mode25 全量 usbmon**(Linux 服务器跑 eSPDI SDK + 相机出真流，或 root 测试机抓厂商 APC)，与我方序列逐字 diff 找"让设备进 streaming"的 delta。这是破墙关键。
2. 深挖厂商 `libUVCCamera`/`setVideoMode`/`startPreview` 内部起流逻辑(反编译)，看比我方 arming 多做了什么。
3. 代码位：`eys3d_pupil_session.cpp`(开源 saki libusb 栈 + 已补 native ioctl ArmSkipVs，flag 走通后默认)、
   `eys3d_mode25_libuvc_session.cpp`(libuvc_gomob 路径)、`eys3d_fd_session.cpp`(派发)、`USE_INDEPENDENT_NATIVE`(Eys3dCameraService)。

---

## 2026-06-16 续2：Android native 直驱入口实测（推翻 CVideoDevice 取流假设）

**问题**：eYs3D 为何依赖 Java？答：Java 是厂商把其 C++ 取流引擎(libUVCCamera)包成 Android API 的打包方式，**非技术必需**；深度由 ASIC 片上算，与 Java 无关。

**三入口实测裁决**（nm -DC 厂商 arm64 .so + Linux eSPDI.h + ApcCamera.class 反编译，workflow 5 agent）：
- **A) APC_* C API：死**。Android `libESPDI.so` 导出 0 个 APC_*（只有 C++ CVideoDevice/PlyWriter）；Linux 的 APC_* 是 V4L2 自枚举形态（OpenDevice 系列签名零 fd 参，DEVSELINFO 仅 int index），自己 open /dev/videoN，**无法注入 Android usbfs fd**。只能当逆向真值源。
- **B) CVideoDevice(uvc_device_handle\*)：死（订正上一轮）**。该类只导出 GetZDTable/GetRectifyTable/Get_SetFW/HW/SensorRegister/ReadFlashData —— 纯**控制/标定/flash 通道**，**无 GetImage/任何取流方法**。上一轮"CVideoDevice 可 native 直驱取流"判断**错误，已纠正**。它适合做 ZD/Rectify 离线提取。
- **C) UVCCamera::connect(int fd,...) @libUVCCamera.so：活，推荐**。真正吃 fd 的取流入口是 `UVCCamera::connect(int fd,int,int,int,int,char*)`（内部经 uvc_get_device_with_fd 起流），配 setPreviewSize/startPreview/setFrameCallback/UVCPreview::do_preview 一条龙出深度。register_apccamera 注册的就是 UVCCamera（无独立 ApcCamera 类）。这是 Java ApcCamera 实际包的那条**已验证起流链**，绕开未解的自研 -EPROTO 硬墙。

**native 直驱代价**：厂商不给 UVCCamera/UVCPreview/FrameGrabber 的 C++ 头，需从 mangled 符号手写 C++ ABI（over-alloc this + dlsym mangled 方法符号 cast 调，可免精确成员布局；但 std::__ndk1 shared_ptr&lt;FrameGrabber&gt;/vector 跨 .so + libc++ 二进制兼容是最高风险，错位=静默内存损坏）。**零 Java 接帧**靠 `FrameGrabber` 纯 C 函数指针回调（非 setFrameCallback 的 _jobject），但"能否绕开 _jobject"未验证。工作量中等 1.5-3 周，最大不确定项=无头 C++ ABI。

**mode25 Java 起流序列**（要复刻的真值，Eys3dApcCamera.kt 实证）：connect(fd) → setVideoMode(**36**=SCALE_DOWN_11_BITS) → getZDTableValue(fileIndex=USB2&depth128→1) → 彩色 setPreviewSize(1280,256,1,5,MJPEG,1.0,sw0)+startPreview(0) → IRmax reg0xe2=6 / IRcur reg0xe0=3 / AE setExposureMode(8) → 暖机~4s → 深度 setPreviewSize(640,128,1,5,RAW,1.0,sw1)+startPreview(1)；深度 u16 LE，disp=raw&0x07FF，mm=zdTable[disp]（mode25 无 ×8）。

**架构张力（需用户裁决）**：用户既定"相机硬件交互层可由厂家提供" → 薄 Java ApcCamera shim 本就算合法的"厂商硬件层"；但用户又明确反对 Java。C 路可去 Java 但有 ABI 黑洞风险。更高价值缺口=eYs3D 当前 `zeroIntrinsics()` 不填内外参，**阻塞多视角 RGBD 配准主线**。

---

## 2026-06-15 原始记录

**结论：用户要"完全脱离原厂库"。已建成零原厂取流栈、跑通端到端、攻克 arming，唯卡在 stream-activation 顺序，bulk 仍 0。**

## Why（评估 + 进展）

可行性评估（多 agent 调研）：**可达但需有界逆向**。第一性：立体匹配在设备 ASIC 片上算，原厂 11 个 .so 在取流路径只做 UVC 控制+取流+(彩色)解码；视差→mm 已自研、ZD 表已离线提取。协议字节全在 `.dev/vinshim/rsd550_clean_seq.txt` 抓到（零 0x40/0xc0 私有请求）。

P1b 已完成并真机验证（测试机 2510DRK44C，2026-06-15）：
1. **独立 libuvc 建成** `libuvc_gomob.so`：定制版 libuvc 源提升进 `third_party/libuvc-android/src/`（checked-in），仅改两处 —— `init.c`(libusb_init2→`set_option(NO_DEVICE_DISCOVERY)`+`libusb_init`)、`device.c`(fd 传递改 `libusb_wrap_sys_device`)。`--no-undefined` 严格链接通过，`readelf` NEEDED **只有 libusb-1.0.so（零 libusb100）**。构建脚本 `third_party/libuvc-android/build_gomob_stock.sh`。stock libusb-1.0 后端**根治了**老 `uvc_scan_control` 描述符 ABI 崩溃。
2. **端到端跑通**：`wrap_sys_device` 接管 fd ✓、描述符解析 ✓、VS PROBE/COMMIT 协商值与 VINCreator 一致（color fmt2/frm2/maxPay**512**、depth fmt1/frm4/maxPay1024 —— 注：512 是对的，非 bug）✓、clear_halt ✓、libuvc handler 事件线程在跑 ✓。
3. **arming 攻克**：单句柄 **libusb** arming 会毒化 IF2 bulk（恒 status=2），必须走**裸 USBDEVFS_CONTROL ioctl**（= 旧 armViaJava 机制）。且 minimal 3 写不够，需回放全 2049-op 抓包序列（`eys3d_clean_arming_blob.h`）。**live-counter**：selector 0x0a/0x0b 写的**首字节**必须用 `GET 0x0a00` 读出的设备当前计数器覆盖（抓包值是旧 session 的会被拒）→ 实现后 `ArmFullEntity4: ok=2016 fail=0 ctrOverride=978`。

**唯一剩余卡点（已精确定位）**：bulk 仍 0。根因 = **stream-activation 顺序**。VINCreator 在【两路 VS COMMIT 之后】才发一段 `a0` opcode XU 激活序列（`OUT a0 00 00`/`a0 01`/`a0 03`，回读 `a0 24`=videoMode36）+ 第二轮 flash。当前实现「全 arming 前置 + 跳 VS 交 libuvc commit」把这个 a0 激活（在 blob 里位于 commit 之后）错排到 commit 之前 → 流没激活。

## How to apply

- 终态路径：`ArmFullEntity4` **不跳 VS**、按抓包**原序**ioctl 回放整条 blob（含 IF1/IF2 PROBE/COMMIT + a0 激活 + 第二轮 flash，全程 live-counter），让 libuvc **只做 bulk 收割**（手填 uvc_stream_ctrl + `start_streaming(flags=0x06)`=external-commit+skip-set-interface，不再 probe/commit/set_interface）。manual bulk 会 2 帧停，必须用 libuvc 传输管理收割。
- 调试法：用 libusb shim / libusb_set_option(LOG_LEVEL) 抓自研会话的实际控制+bulk 序列，与 rsd550_clean_seq.txt **逐条 diff** 找 stream-start delta，别盲调。
- 代码位：`native/eys3d/android/eys3d_mode25_libuvc_session.cpp`（独立会话）、`eys3d_fd_session.cpp`(Run 派发)、`Eys3dCameraService.kt`(`USE_INDEPENDENT_NATIVE` flag，**P1b 通过前默认 false 走原厂 Java ApcCamera 工作路径**)。AE(setExposureMode8) 待补（density 用）。
- 相关：[[finding_eys3d_android_bringup_0bytes_2026-06-09]]（续33 IF1 保活铁律）、`docs/architecture/13-eys3d-driver.md`、`docs/architecture/10-android-uvc-stack-rewrite.md`。
