# VINCreator(eYs3D)逆向分析 — 给 gomob 的可借鉴点

> 对象:`com.vin.uvc` / VINCreator v1.4.11(vc126),来源见 [README.md](README.md)。
> 方法:jadx 1.5.5 反编译 4518 个 java + binutils(strings/readelf/objdump)分析 23 个 native 库。
> 产出:本报告由 10 个 subagent(6 子系统 reader → 3 对抗核验 → 1 综合)交叉验证生成,
> **3 条载荷关键 claim 经独立反向核验:1 条 partial、1 条 refuted、1 条 partial(见文末核验结论)**。
> 反编译源码在 `.dev/vincreator-jadx/`(gitignored,可重生),file:line 均指该树。

## 概览：VINCreator 是什么，与 Berxel 的关系

**VINCreator(包名 `com.vin.uvc`)是 eYs3D / Etron(APC,Astar / EtronDI 体系)的 Android USB 3D 深度相机商业 App,不是 Berxel 自家产物。** 全栈源根均为 `/home/alanlin/project2/EtronSDK_Android/APC/esp_android_usb_camera_sdk`(由 libESPDI.so 内 strings `videodevice.cpp` 路径坐实),Java 侧基于 saki4510t/UVCCamera 框架二次开发,native 侧是 eYs3D 对 libuvc + libusb 的私有 fork。目标深度相机为 eYs3D 自有 VID=`0x1E4E`(7758)/PID=`0x0163`(355),SDK 版本 1.2.0.12。

它与 gomob 的关系是**蓝本而非同源**:gomob 用的是 Berxel iHawk P100R3(主控 Novatek `0x0603` + companion `0x3558`,Sonix-style XU 体系),VINCreator 用的是 Etron ASIC。两者寄存器空间、深度协议都不通用。但 VINCreator 的价值在于:它是一个**确实能在 Android 上跑通双相机双流深度采集的真实商业 App**,其 UVC 接入层(fd 注入、权限编排、双流并发、深度后处理分层)比 Berxel 9.9.190 嵌的 libuvc-0.0.7 更现代健全,是 gomob M1.6 UVC 重写(`docs/architecture/10-android-uvc-stack-rewrite.md`)最直接的可抄蓝本。

需要强调:**VINCreator 这层只解决取流,深度是 eYs3D SDK 端软件重建/后处理**(libDepthMSR + libdepthfilter + libSwPostProc)。这点与 P100R3 **相反**:P100R3 companion 在 dense controls 后于 0x82 流**直接交织吐真 metric 深度**(0x0600 帧,raw/8=mm),重建在**设备 ASIC**、6MB blob 只是温补表(非散斑参考),gomob 路线 A 已决定**直出设备深度、放弃自研结构光重建**(见 `finding_p100r3_depth_ir_interleaved_2026-05-29`)。故 eYs3D 的深度软件重建链对 gomob **不是必须移植项**,价值在后处理算子而非重建本身。

---

## UVC 开流与 fd 传递机制

eYs3D 沿用「Java 拿 fd → 喂给 native libusb」的免 root 模型,与 gomob BerxelService 取 fd 思路**主干一致**,但底层 API 细节需订正(对抗核验判 partial)。

**Java→native fd 传递链(铁证成立,confidence high):**

- `USBMonitor.java:547` `mConnection = mUsbManager.openDevice(usbDevice)` 拿 `UsbDeviceConnection`
- `USBMonitor.java:577-580` `getFileDescriptor()` 返回 `usbDeviceConnection.getFileDescriptor()`
- `ApcCamera.java:484` 把整套参数喂给 native:
  `nativeConnect(mNativePtr, getVenderId(), getProductId(), getFileDescriptor(), getBusNum(), getDevNum(), getUSBFSName(ctrlBlock))`
- 签名 `UVCCamera.java:341`:`protected final native int nativeConnect(long j, int i, int i2, int i3, int i4, int i5, String str)`,即 **(ptr, vid, pid, fd, busNum, devNum, usbfsPath)**——注意 fd 排在 vid/pid 之后第三个 int 位。
- usbfs 路径 `ApcCamera.java:29` `DEFAULT_USBFS = "/dev/bus/usb"`;busNum/devNum 从 deviceName split 解析(`USBMonitor.java:548`)。
- native strings 印证:libUVCCamera.so 含 `[%d*%s:%d:%s]:connect:fd=%d 0x%x`、`connect:usbfs=%s`;导出符号 `_ZN9UVCCamera7connectEiiiiiPKc`。

**⚠ partial 订正——不是 upstream `libusb_wrap_sys_device`:** 8 个相关 .so 中 `wrap_sys_device` 命中数全为 0。eYs3D 实际走的是 **EtronSDK 私有 libusb fork 的老式 set-fd API**:

- libusb100.so 导出 `libusb_set_device_fd`、`libusb_get_device_with_fd`
- libuvc.so 经 `uvc_get_device_with_fd` 调用上述符号
- 底层是定制 `android_usbfs_backend`(构建路径 `.../EtronSDK_Android/.../libusb/os/android_usbfs.c`)

这是 libusb 1.0.23(2019)之前的私有改法,而非现代标准 wrap API。native 不自行 `open()` usbfs 节点(Android 无权限),fd 必须由 Java 传入并 set 进 device,usbfs path 仅作辅助路径。**对 gomob 的启示:思路可照搬,但若重写应直接选 upstream `libusb_wrap_sys_device`,更干净,不必复刻 Etron 老 fork。**

native 取流由 libuvc 起独立线程跑 ISO 或 BULK,逐帧经 `IFrameCallback.onFrame(ByteBuffer, int)` 回抛,经 direct ByteBuffer 单拷贝。JNI 用 RegisterNatives 动态注册(so 已 strip,无 `Java_` 导出符号)。

---

## USB 权限流程(对照 gomob PendingIntent 雷点)

eYs3D 的 USBMonitor 权限实现**逐条印证 gomob 修后版本是对的**,是验证 gomob「flag=0 才是真雷点」结论的独立外部样本。

| 雷点 | eYs3D 实现 | gomob 修后对照 | 结论 |
|---|---|---|---|
| PendingIntent flag | `USBMonitor.java:196` 用 `FLAG_IMMUTABLE\|FLAG_UPDATE_CURRENT`(S+) | `BerxelService.kt:2574` 一致 | **IMMUTABLE 是正解**,gomob 此前 flag=0 才是雷点(订正 `finding_honor_usb_permission_cache`) |
| RECEIVER_NOT_EXPORTED | `USBMonitor.java:204` TIRAMISU 门槛传 NOT_EXPORTED | `BerxelService.kt:2426` / BerxelContextWrapper(2654-2673) | gomob 用 ContextWrapper 拦 SDK 老二参 registerReceiver 的做法正确 |
| Intent 指向 | **explicit Intent**(指向自家 manifest Receiver 类)+ 静态 WeakRef 分发表 | gomob 用 **implicit action + setPackage** | 唯一差别 |

**唯一可加固点:** eYs3D 走显式 Intent + manifest 静态 Receiver,可能正是为规避某些 ROM 上 implicit PendingIntent 广播丢失。若 gomob 在测试机池(OnePlus/vivo/HONOR/小米)出现进程被杀后 PendingIntent 失效 / 广播收不到,可考虑学 eYs3D 改成显式指向 manifest 注册的 `UsbPermissionReceiver` + 静态 WeakRef 分发。**建议加一条 harness 验证各 ROM 的广播到达率。**

**权限编排(双流场景值得借鉴):** `CameraPresenter` 对 RGB 主摄 + depth 双流做了软件去抖——`schedulePendingUsbPermissionDispatch` / `flushPendingUsbPermissionRequests`(`CameraPresenter.java:5607/5622`),即「主摄上电就绪前 hold depth 权限、按 delay 串行发请求」,避免双 session 并发抢 USB / 抢供电的竞态。配合带电 hub(已验证救活 vivo 双流)是 gomob 双流编排的直接参考模式。`shouldUseImmediateDepthPermissionRequest` / `getDepthPermissionPrerequisiteWaitMs` 的具体时序阈值尚未细读。

---

## 相机模式与双流 / 深度复合机制(重点)

这一节是对抗核验中**唯一被判 refuted 的 claim**,务必如实表述:原 claim「L'+D / L+R 模式是把左+深度复合进一条 UVC 流的单帧、native 再拆分,因此不触发双 endpoint 并发供电冲击」——**证伪**。

eYs3D 用 CSV(`assets/CameraModes/camera_modes_<id>.csv`)+ 硬编码 `DEFAULT_CAMERA_MODES` 描述每个模组的采集模式。每条 `PresetMode` 12 列,解析器列序由 `PresetMode.java:262-309` 明确:
`mode ; description ; lResolution ; dResolution ; ... ; interLeaveModeFPS`

**证据一——CSV 列义被误读:** `camera_modes_550.csv:15`
```
28 ; L'+D scale_down mode ; 1280x960_MJPEG ; 640x480 ; ...
```
`1280x960_MJPEG` 是 **lResolution(左/彩色)**,`640x480` 是 **dResolution(深度)**——是两路不同分辨率的独立流,不是「一个主格式 + 它的 scale_down」。

**证据二——L'+D ≠ 单流交织:** `camera_modes_8036.csv` 把 `L'+D`(行1/2/3/35,interLeaveModeFPS=null)与 `L'+D interleave mode`(行4,末列 interLeaveModeFPS=60)列为**不同条目**;`L+R+D`(行12 `2560x720_YUYV;1280x720`)还另带 dResolution。说明 L'+D 本身就是「左+深度两路」,interleave 只是其中一个专门子模式。

**证据三——上层确是双流并发(双 streaming interface):**
- 彩色:`CameraPresenter.java:3091/3102` 走 `getStreamInfoList(1)`/IF1 + `setFrameCallback(mColorIFrameCallback, 3, 0)` + `startPreview(0)`
- 深度:`CameraPresenter.java:3147/3158` 走 `getStreamInfoList(2)`/IF2 + `setFrameCallback(mDepthIFrameCallback, 0, 1)` + `startPreview(1)`
- `UVCCamera.java:647/654` setPreviewSize 的 `camera_switch = interfaceNumber==2 ? 1 : 0` 证实 **color=IF1、depth=IF2 是不同 UVC streaming interface**

**证据四——native interleave 是「丢帧」不是「拆复合帧」:** libUVCCamera.so 反汇编:
- `UVCPreview::setInterleaveMode`(0x33f90)仅 `strb w1,[x0,#1918]; mov w0,#0; ret`(只存 flag)
- `UVCCamera::setInterleaveMode`(0x2b2f0)把 flag 写进 offset 56 与 64 两个 UVCPreview 对象(=彩色 preview + 深度 preview)
- flag(offset 1918)只在 `UVCPreview::do_preview(uvc_stream_ctrl*)` 内被读(0x37084/0x3722c/0x372fc):判计数后 0x3723c `bl 0x301e8`(`recycle_frame`=丢帧)
- `do_preview` / `prepare_preview` 形参均为**单个** `uvc_stream_ctrl`,每个 UVCPreview 只裹一条流

即 interleave 是「对单条流按奇偶/计数丢弃交替帧」的**时间维交替丢帧**,不是把空间复合大帧拆成左+深度。

**修正后的真实模型:**

1. **常规 `L'+D` = 彩色(IF1)+ 深度(IF2)两路独立 UVC streaming interface 并发**(上层明确 `startPreview(0)` + `startPreview(1)`、挂两个 IFrameCallback)——这恰恰**是**双流并发,而非单帧复合拆分。
2. 真正的「单流」只有两种:`L+R`(2560x960 一帧左右拼接,无独立 dResolution,col4=null);以及独立的 `interleave mode` 子模式(同一条流时间维交替输出不同类型帧,native 用 setInterleaveMode flag 在 do_preview 里 recycle 掉非本类帧)。
3. 因此「L'+D 因单帧复合所以不触发双 endpoint 供电冲击」这一因果**不成立**;能减并发的只有 `L+R` 拼接帧或显式 interleave 单流子模式。

**对 P100R3 的边界提示:** eYs3D 与 P100R3 是不同相机,两者的「interleave」机制不可互相套用——P100R3 companion 是在单 `0x82` endpoint 上时间交织 depth+IR,而 P100R3 无 eYs3D 那套 firmware VideoMode/interleave 协议。深度低分辨率(640x480 vs 彩色 1280x960)+ 缩放 + 11/14bit 位深选择 + SDK 端 rectify,印证 gomob 深度可走低分辨率 + 端云重建路线,不必追求与彩色同分辨率。

---

## 深度处理与 native 库分工(含 libusb / libuvc 版本与 backend 结论)

VINCreator arm64-v8a 打包 **23 个 native 库,两套并存的 USB 采集栈**:
- **旧栈**(GCC 4.9.x / 2015,EtronSDK_Android 源树):`libUVCCamera.so → libuvc.so + libusb100.so + libESPDI.so + libeysov.so`
- **新栈**(clang 18 NDK r522817b / ~2024,dmpreview_det_creator 源树):`libUVCCamera1.so → libuvc1.so + libusb1001.so`

**深度处理分层(全 confidence high):**

| 库 | 职责 | 关键符号证据 |
|---|---|---|
| libESPDI | SDK 主接口 / 视频设备层 + 寄存器读写 + ZD 表 + 点云生成 | `CVideoDevice::Get/SetHWRegister`(HW/ASIC)、`Get/SetFWRegister`、`Get/SetSensorRegister`、`Get/Set/ResetZDTable`;`PlyWriter::APCFrameTo3D`、`PlyFilter::CF_FILTER` |
| libDepthMSR | 视差↔Z 换算 / ZD 表插值 / 像素测距(**dlopen 插件式**,非 NEEDED) | `DepthMSR_d2Z`、`DepthMSR_L_d2Z_ByZDTable`、`GetPixelDisparity`、`xyd2XYZ`;5 个 so 对它 NEEDED 计数全 0 |
| libdepthfilter | 深度图后处理(时域/边缘保持/孔洞填充/遮挡剔除) | `DepthmapFilter::ApplyFilters/TemporalFilter/EdgePreServingFilter/HoleFill`、`occlusion_filter`、`processGaussianLikeFilter_OpenCL`;NEEDED libESPDI |
| libSwPostProc | OpenCL kernel 软件深度后处理引擎 | 内嵌 OpenCL 源 `TemporalMed1/2`、`TemporalIIR2`、`Median`、`Refine`、`ZeroMask`、`SegmentFill`;`CSwPostProc::Process/SetParam` + `struct TAG_POST_PAR{HR_MODE,SEG_*,TEMP*_*,FC_*,RF_*}` |

**深度链路:** ASIC 出原始视差/深度 → libDepthMSR(d2Z/ZDTable) → libdepthfilter → libSwPostProc(OpenCL 后处理)。

**质量寄存器配置(confidence high):** `QualityCfg/*_DM_Quality_Register_Setting.cfg` 每行 `0xADDR,0xMASK,0xVAL`,全写 `0xF4xx` 段深度 ASIC 寄存器,按传感器型号(EX8036/8037/8052/8053/YX8059/YX8062/HYPATIA)各一份(`DEFAULT.cfg:1` `0xF402,0xFF,0xEB`;HYPATIA 与 DEFAULT 无差异)。应用方式是**掩码 read-modify-write + 5 次重试**(`CameraPresenter.java:4506-4544`):`getHWRegisterValue` 读旧值 → `newval = val | (~mask & old)`(4522 行)→ `setHWRegisterValue`(4526)→ 失败 `delay(5)` 重试 → 落到 `nativeSetHWRegister`(`ApcCamera.java:824-834`)。

**IR / 曝光控制(confidence high):**
- IR 投射器强度走 **FW 寄存器**(`ApcCamera.java:78-82` 地址 224=IR_CURRENT / 225=IR_MIN / 226=IR_MAX;EX8029 用 129):`setIRCurrentValue → SetFWRegisterValue`(`ApcCamera.java:881-884`)
- IRManager 按机型分档(`IRManager.java:71-77`):MARY 默认48/最大96/扩展255;普通机型默认3/最大6/扩展15
- 曝光走**标准 UVC AE**(`ExposureManager.java:36` AE==8 自动/1 手动,曝光绝对时间 `[-13,3]`);低光走 powerline-freq + exposure-priority(`LightSourceManager.java`)

**配套库:** libhidapi(标准 hidapi 0.9.0,NEEDED libusb100,无 Sonix/XU 专有命令码,命令在上层,confidence medium);libeysov(360 鱼眼拼接,与深度无关:`eys::fisheye360::*`、`equirectangular`);主 JNI libcreator_jni.so 内嵌 OpenCV 4.12.0;AI 侧 onnxruntime 1.23.2 跑 yolo-obb.onnx。

**⚠ libusb / libuvc 版本与 backend 结论(对抗核验判 partial,如实标注):**

- **后端断言成立(铁证):** libusb100.so strings `android_usbfs_backend`、`android_netlink_start/stop_event_monitor`、`android_netlink_read_message`;源路径 `.../libusb/os/android_usbfs.c`;readelf -sW 实证 `android_usbfs_backend`(OBJECT)、`android_netlink_start_event_monitor`(FUNC)。两个 libusb 均为 libusb **1.0.19**(1.0.19.10903),经典 usbfs 自枚举栈,**无现代 `libusb_wrap_sys_device`**。两个 libuvc 都是 saki4510t→Etron/eYs3D fork(带 `uvc_init2`/`uvc_start_streaming_bandwidth` 扩展),**不是 pupil-labs**。
- **同代断言成立:** libusb100.so `.comment` = `GCC: (GNU) 4.9.x 20150123 (prerelease)`;Berxel 9.9.190 `libBerxelUvcDriver.so` `.comment` = `GCC: (GNU) 4.9 20150123 (prerelease)` 且内嵌同 `android_usbfs_backend` + `libuvc-0.0.7`。同日期工具链 + 同 backend 家族 = 同代,成立。
- **⚠ 工具链断言对 1001 变体证伪:** libusb1001.so / libuvc1.so `.comment` = `clang version 18.0.2 (r522817b)`(~2024 NDK),**不是 2015 GCC**。即 VINCreator 同时打了 2015-GCC 和 2024-clang 两份「同一老 backend 源码」的 libusb。**不能笼统说两个 .so 都是 2015 GCC**;backend 源码年代 ≠ 编译器年代。

---

## 对 gomob 的可行动启示

### A. M1.6 UVC 重写能借鉴什么

1. **JNI 边界签名照 ApcCamera 设计:** `connect(ptr, vid, pid, fd, busNum, devNum, usbfsPath)`(`UVCCamera.java:341` / `ApcCamera.java:484`),注意 fd 位次在 vid/pid 之后。但**用 upstream libusb 现代 `libusb_wrap_sys_device`**,不复刻 Etron 私有 `libusb_set_device_fd` 老 fork(partial 订正,更干净)。
2. **权限三件套已被独立印证正确**(IMMUTABLE flag + RECEIVER_NOT_EXPORTED + setPackage),保持现状即可。若遇 ROM 广播丢失,加固方向是 explicit Intent + manifest Receiver + 静态 WeakRef 分发表(eYs3D 写法)。
3. **开流原语顺序直接照搬:** `open → setPreviewSize → setPreviewDisplay → setFrameCallback → startPreview(streamId)`,color(stream0)/depth(stream1)作为**同一 native handle 的两条流**分别 setFrameCallback + startPreview,回调按 streamId 区分,不是开两个 device。
4. **复用而非从零写的近路:** Etron libusb/libuvc 源码已被 VINCreator 用 clang18 NDK r522817b 成功重编为 libusb1001.so/libuvc1.so——证明这份 eYs3D fork 能编过现代 NDK,可作为 gomob 复用候选(权衡可维护性 vs 自己上 pupil-libuvc)。
5. **双流权限去抖编排可借鉴:** `schedulePendingUsbPermissionDispatch` / `flushPendingUsbPermissionRequests`(`CameraPresenter.java:5607/5622`)的「主摄就绪前 hold depth 权限、串行发请求」软件去抖,降低双 session 抢 USB 竞态。

### B. P100R3 双流死锁根因的旁证(置信度:旁证支持,非铁证)

- **关键旁证:VINCreator 是一个确实能在 Android 跑通 eYs3D 双相机双流深度的商业 App,而它用的恰是 libusb 1.0.19 经典 android_usbfs_backend + saki/eYs3D libuvc fork——与 Berxel 9.9.190 嵌的 libuvc-0.0.7 + 老 libusb 是同代老栈。** 既然同代老栈能跑通双流,「老 libusb backend 本身导致 P100R3 100ms 双流死」就**站不住**,锅更稳地推回**带电 hub 解决的 OTG 三路并发供电不足**(`finding_powered_hub_unblocks_vivo_dual_stream`)。
- **⚠ 必须如实标注:** 这是分析推断,strings/readelf 只能证二进制事实,不能直接证因果;对抗核验整体判 **partial**。「老 backend 非充分原因」与代码库记忆「真因=OTG 供电不足」一致且被支持,但不是铁板钉钉。
- **eYs3D 的应用层供电时序解法可借鉴但不可全套照搬:** VINCreator 显式做两阶段供电时序——先连 RGB1300 主摄并 startPreview(给深度相机供电),打标 `rgb1300PowerReadyAt`,深度 openJob 必须先 `waitForRgb1300PowerReadyForDepthOpen`(轮询 power ready + 80ms 沉降,3000ms 超时 fallback,`CameraPresenter.java:5516-5524/5552`)才允许 `apcCamera.open()`。**这是软件供电时序规避并发电流尖峰**,与「带电 hub 救活 vivo」同向。**但 gomob 的 P100R3 是单设备双流(companion ep=0x82),没有第二路 UVC 设备能当「供电先导」**,所以软件时序只能缓解、根因仍要靠带电 hub。建议 M1.6 把「先开一路 → 确认稳定供电 → 沉降延时 → 再开第二路」做成显式状态机。
- **refuted 修正点须并入论证:** 不能用「L'+D 单帧复合所以省供电」来佐证——常规 L'+D 就是双流并发。能减并发的只有 `L+R` 拼接帧或显式 interleave 单流,且 P100R3 不支持那套 firmware interleave 协议,该省电路径在 P100R3 上不可复用。

### C. 深度重建可参考的资产

1. **分层切分范式可抄:** DepthMSR(视差→Z)/ depthfilter(孔洞/遮挡/边缘保持/时域)/ SwPostProc(OpenCL 中值/IIR/分割填充)三层解耦。滤波算子 `HoleFill`、`occlusion_filter`、`EdgePreServingFilter`、`TemporalMed`、`SegmentFill` 正对应 gomob 多视角融合需要的「补洞/遮挡边界/噪声分布」质量要求;SwPostProc 内联 OpenCL kernel 源码可直接读懂照写。
2. **寄存器调参工程结构可借鉴但地址不可用:** 「按传感器型号一张配置表 + 掩码 read-modify-write + 5 次重试」(`CameraPresenter.java:4506-4544`)的**工程结构**值得 NATIVE_REWRITE 抄;但 `0xF4xx` 是 Etron ASIC 空间,P100R3 是 Sonix-style XU,地址完全不通用——**别把 0xF4xx 表当 P100R3 用**。
3. **IR 预览调参模板:** P100R3 companion 0x82 流交织真深度(0x0600)与 **IR/phase 帧(0x0500,灰度 + phase code)**,后者是现成 IR 散斑源。做 IR 预览亮度/曝光调节可参照 eYs3D 双通道分工——IR 投射器强度走 FW 寄存器(224/225/226)、曝光走标准 UVC AE(8=自动)、低光走 powerline-freq + exposure-priority。
4. **eYs3D 深度走 SDK 软件重建,但 P100R3 不需照搬:** ApcCamera 把 rectify/ZD table/depth filter 全留在专有 native so(libESPDI/libDepthMSR/libdepthfilter)——eYs3D 深度是 **SDK 端软件重建**。但 **P100R3 不同**:companion 在 dense controls 后直接交织吐真 metric 深度(0x0600,raw/8=mm),重建在设备 ASIC,6MB blob 只是温补表不是散斑参考(见 `finding_p100r3_depth_ir_interleaved_2026-05-29`)。**gomob 路线 A 已决定直出设备深度、放弃自研结构光重建,不需移植 eYs3D 这套 SDK 重建链**;VINCreator 对 gomob 的深度价值在后处理算子(补洞/遮挡/时域降噪)而非重建本身。

---

## 对抗核验结论(3 条载荷 claim)

| # | claim | verdict | 要点 |
|---|-------|---------|------|
| 1 | eYs3D Java openDevice 取 fd 传 native libusb_wrap_sys_device | **partial** | fd 传递链铁证成立;但用的是 Etron 私有 `libusb_set_device_fd`/`libusb_get_device_with_fd`,**非** upstream `wrap_sys_device`(全 .so 零命中) |
| 2 | L'+D / L+R 是单帧复合 native 拆分,不触发双 endpoint 并发 | **refuted** | 常规 L'+D 就是 color(IF1)+depth(IF2)**双流并发**;native interleave 只是单流时间维丢帧,不是空间复合拆分 |
| 3 | 两个 libusb 是 2015-GCC 老 backend,说明老 backend 非双流死充分原因 | **partial** | backend=android_usbfs+1.0.19 铁证;但 libusb1001.so 是 **2024 clang18** 重编(非 2015 GCC);因果「老 backend 非充分原因」属推断,与 OTG 供电根因一致但非铁证 |

## 遗留待查

- `nativeSetFrameCallback` 第 4 个 int(color=3 / depth=0)是像素格式枚举还是 endpoint 切换,需反汇编 libUVCCamera.so 确认是否影响 USB 带宽。
- native ApcCamera open→startPreview 是否对深度流发 XU/keepalive/probe-commit 协商,需反编译 libuvc .so 才能与 P100R3 master XU5 keepalive 对比(presenter 层 Java 不可见)。
- VINCreator 跑通双流是否纯靠 Java 喂 fd 绕过 wrap_sys_device(已基本坐实)还是另有特权访问 `/dev/bus/usb`——决定对 gomob 非 root 场景的可复制性。
- `device_filter.xml` 精确 vid/pid、SwPostProc POST_PAR 各档数值(`param/VinSoftSettings.bin` / `VIN_*.bin`)需 apktool 解 res / 解析 bin。
