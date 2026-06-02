# RS-D550 真实开流序列已解码 + RHEL9 能出流（推翻"环境不兼容"）2026-06-01

## Why
M6.3 长期卡在"自研 libusb 取流 0 帧"，旧 finding 一度怀疑 RHEL9 uvcvideo/V4L2 不兼容。
2026-06-01 用 **eSPDI 直调小程序**（`.dev/eys3d-sdk/eys3d_sdk_stream.cpp`，`APC_Init`/
`APC_OpenDevice2`/`APC_GetColorImage`/`GetDepthImage`，链 `libeSPDI_X86_64.so`）一次跑通：
**color 60/60 + depth 60/60 帧、122385 笔 BULK、0 错误**。彻底推翻"RHEL9 不兼容"——旧
`trace_7u.txt` 的 0-BULK 是交互菜单没驱动到真取帧/选错 video 节点，不是环境问题。

同时 usbmon 抓到**完整真实开流序列**（`.dev/eys3d-sdk/sdk_stream_trace.txt`，122763 行），
开流原语全部解码，旧 M6.3 里"`0xF0=4`+`0xE0=3`+`0xE3=0x63`"激活猜测**作废**。

## 设备拓扑（标准 UVC 复合设备，lsusb -v 实测）
- IF0 = VideoControl，含 Etron XU **unit 4**，1× 中断 ep 0x83(32B)。uvcvideo 全程在轮询 0x83。
- IF1 = VideoStreaming color/双目，仅 alt0，**bulk ep 0x81**（512B 包）。
- IF2 = VideoStreaming depth，仅 alt0，**bulk ep 0x82**（512B 包）。
- 枚举 6 个 /dev/videoN（含别的相机）；RS-D550 = video2(color)+video4(depth)，VID 0x3438 PID 0x0206。

## 真实开流序列（按 SET_CUR 顺序，45 笔）
1. **XU videoMode/ASIC 寄存器编程**（全在 unit4：`SET_CUR bmReq=0x21 bReq=0x01 wValue=0x0300 wIndex=0x0400`）：
   - `82 f0 14 00`（opcode **0x82**，reg 0xf0=0x14）×多次
   - `20 f0 02 00`（opcode 0x20，reg 0xf0=2）
   - `20 ed 00 00`（reg 0xed=0）
   - **每笔后跟 selector 0x0a 递增计数器握手**：`wValue=0x0a00 wIndex=0x0400 data={01,02,03,...}`（固件事务序号，漏了它写不进）。
2. **flash/ZD 标定读**（selector 0x0b 写请求 → selector 0x0c 读结果，前置 `a1 85`GET_LEN/`a1 86`GET_INFO）；非开流必需，且 libusb 下 SET_CUR 0x0b 会 STALL，跳过。
3. **标准 VS PROBE→GET_CUR→COMMIT**（26B）：
   - IF1(color)：`bFormatIndex=1 bFrameIndex=4 dwFrameInterval=2000000 dwMaxVideoFrameSize=0x12c000(1228800) dwMaxPayloadTransferSize=0x800(2048)`
   - IF2(depth)：`fmt=1 frame=1 maxFrame=0x258000 maxPayload=0xc00(3072)`
4. **无 SET_INTERFACE**（bulk 走 alt0）。post-commit `20 f5 00` + 计数器，但**首帧色彩在它之前**就出，故非色彩起流触发。
5. **COMMIT 后约 1.5s 暖机**，期间 uvcvideo 只是 URB 一直挂起、无任何控制流量，到 1.49s 设备自行出首帧，之后稳定高速。

## 自研 libusb 取流已首光（RGBD 双流，零厂商 SDK）2026-06-01
`native/eys3d/host/eys3d_replay_stream.cpp` 跑通：**color 32 帧/1228800B + depth 16 帧/2457600B
并发出流、5.6MB/s、drops=0**。攻克 M6.3。逐字回放开流序列后，取流必须满足 4 条硬约束：
1. **异步多 URB（无超时）**——sync 单 URB+超时取消会造成 IN token 间隙，设备只 NAK/0 帧或掉到
   53ms/payload 龟速。必须 `libusb_alloc_transfer` 多 URB、timeout=0、回调里 resubmit。
2. **URB 回调绝不做重活**——帧组装放回调外（仅 memcpy 进 capture）。在回调里跑 assembler 会
   阻塞 resubmit→设备掉到 53ms/payload。libuvc(Android) 同样 callback 入队、独立线程组装。
3. **URB 大小 = maxPayloadTransferSize**（color 2048/depth 3072），一 URB 一 payload。payload 是
   512 对齐无短包，大 URB 会把多 payload 拼一起、丢 USB 短包边界→无法切帧。
4. **depth(IF2) 只在 color(IF1) 并发排空时才出流**——共享 stereo→depth 流水线，单独读 ep0x82 立即
   STALL(errs=全部)。必须双端点并发挂 URB。
5. assembler `drop_partial_on_uvc_header` 对 Etron 必须 **false**（每 payload 都带 12B UVC 头，
   true 会每 payload 丢半帧）；逐 payload 剥头累积，每真帧恰好 frame_bytes，按尺寸出帧对齐。

## 拆帧（FID 法，已修条纹）
RS-D550 是**标准 UVC bulk**：每 payload 带 12B UVC 头(`bHeaderLength=0x0c, bmHeaderInfo`)，
**按 FID(bit0) 翻转切帧**（不是 Berxel 那种按 size 切，UvcRawFrameAssembler 不适用）。实测结构：
603×2048(2036 data) + 1×1104(1092 data) + 1×12(EOF 头, 0 data) = 每帧恰 **1228800B data**，
FID 翻转/EOF 标记帧尾。剥 bHeaderLength 头累积即得干净帧。早期按 size 切产生水平条纹是因为漏剥
部分头/边界错位——FID 法彻底解决，color 渲染出干净双目立体对（L/R 近似+基线视差，真实低光场景）。

**帧格式（实测，非 maxVideoFrameSize）**：color IF1 = YUYV **1280×480**(L+R 并排)、每帧 1228800B；
depth IF2 = **YUY2 容器**、每帧也 **1228800B**(614400 px16, ~50% 非零, max 0xFFFC→非 14bit metric)。
**depth 真值编码在 YUY2 容器里，需解码**（eSPDI 靠 `APC_SetDepthDataType`+后处理抽取）=M6.4；
裸 u16 raster 渲染呈竖条纹即因没解 YUYV 容器。

## ★★★ 2026-06-01 重大订正：旧"IF2 因 flash 读 ZD 失败而死"是误判 ★★★
**逆向 libeSPDI(6-agent workflow)+ SDK 实测推翻了下面这段旧根因。** 旧判据保留作教训：
- 旧错因 1：把 `GetSTIStructLen: FW_UNKNOWN_STRUCT_LEN_OF_STI` 当成 ZD 读失败 → **错**。
  反汇编证 `GetSTIStructLen@0x24b170` 只被 `IsSupportProtectedFlash` 调用(**flash 保护能力探测**)，
  **不在 GetZDTable 深度路径上**。真正的 ZD 读走 `FWV3ReadData`，trace 里**全部 status=0 成功**。
- 旧错因 2：usbmon trace 164 笔控制传输**全 OK、0 STALL**；flash/ZD 标定读(sel 0x0b→0x0c)
  返回真实链式标定数据(目录 offset 0x0a→指针 0x0ecf→数据块)。**标定 flash 读根本没失败。**
- ZD 表实测**可读**：`APC_GetZDTable(buf, BufferLength=4096, ..., {nIndex=0,nDataType=4})` rc=0，
  得 4096B=2048×u16(11bit 视差→mm LUT)。旧代码传 65536 → 报 `APC_ErrBufLen(-3)`，是**我 API 用错**。
  rectify 表同理：`APC_GetRectifyTable(buf, 1024, ..., idx)` idx0-3 rc=0(各 1024B)，idx4-9 不存在。

## ★ eSPDI 深度真实链路(反汇编实证 2026-06-01)
- **视差 100% 来自设备 ASIC(经 USB IF2)**，eSPDI **零 host 软件 stereo**：真实路径
  `APC_GetDepthImage` → `CVideoDevice::GetImage(isDepth=1)` → `get_frame`=V4L2 `VIDIOC_DQBUF` 纯出队
  → `memcpy` →(可选)上色 LUT + 每帧 `DisparityCompensate` 仿射 `new_disp=disp*pars[0]+pars[1]`。
- 静态编入的 OpenCV `StereoBM/StereoSGBM` 全段 **0 真实 xref = 死代码**，深度路径从不调用。
- `APC_Convert_Depth_Y_To_Buffer` 是"深度 u16 整值→RGB 上色 LUT"，**不是**从 YUY2 拆 11bit；
  11/14bit 模式每像素就是 1 个小端 u16，直接用，不要去 YUY2 拆。
- **度量**：`Z_mm = ZDtable[disparity]`(纯查表无插值，读出 u16 后 byteswap)；或几何 `Z=fx·B/disp`。
  ZD 表 flash file id=50+nIndex；rectify 表 file id=40+index。

## ★ 但 SDK 实测：深度仍是列恒定竖条纹垃圾(2026-06-01)
按正确配方实测(`eys3d_sdk_depth.cpp`)：color 1280×480 + depth **640×480**(V4L2 枚举确认原生模式，
非我之前瞎填的 1280×480→退化 1280×256)、11bit、**IR 投射器 arm**(`SetIRMaxValue(6)+SetIRMode(0x03)+
SetCurrentIRValue(3)` 回读 cur=3 mode=0x03 生效)→ COLORFUL 深度**仍是竖条纹**，NON_TRANSFER raw
**列内 std≈41(列恒定)、行内 std≈20830(行剧变)**，列值如 `[8337,12072,8337,12072...]` 两值交替=**非物理**。
- 即 IF1 双目本身是好的(有真视差)，但**设备深度 ASIC 没在真算视差，吐退化图样**。
- 唯一未验的 arming 步=**AE 自动曝光**(`APC_SetCTPropVal(CT_AUTO_EXPOSURE_MODE=0, 8)`)，设备自动关机没跑成。
- 关联 SN **BF301208** → VINCreator `VIN_BF301208.bin` 标定族（[[finding_vincreator_eys3d_uvc_blueprint_2026-06-01]]）。

## ★★ 2026-06-01 再订正：硬件深度"列恒定垃圾"很可能是**模式配对错**,不是设备坏 ★★
深读 APK(从 APK assets 提取 `camera_modes_206.csv`)发现：**ROSIE4(PID 0x0206)/USB2 必须用 mode 25**：
**color 1280×256 MJPG @5fps + depth 640×128 @5fps + depthType=36 + interleave off + color 流配对同开**。
我一直用 `color 1280×480 YUYV @30fps`——**不在 0x0206 合法模式表里**，深度 ASIC 遂退化吐 raw（列恒定+
u16 巨值，69% >16384 即 11bit 视差不可能范围）。⇒ **不能据此判设备深度死**；正确模式下大概率出真视差。
- 判据：修对后 depth u16 应集中 0-2047(11bit 视差)，`Z_mm=byteswap(ZDtable[u16])`。
- 一键复验：`.dev/eys3d-sdk/run_mode25.sh`（程序已加 MJPEG color 支持）。**设备 ~15min 硬定时关机**，需开机即测。
- USB2(480Mbps) 实测确认；带电 hub 强制 USB2，故走 mode 25 不是 mode 4(USB3 才用 4=640×480)。

## ★★★ 2026-06-01 终极订正：mode25 的 videoMode 写值=36 已【离线锁定】,不需真机抓 ★★★
靶向 RE workflow(5 agent)从 jadx 反编译的 VINCreator 静态导出,零真机:
- **`CameraModeKt` `DEFAULT_ROSIE4_U2_MODE` = `CameraMode(id, isUsb3=false, mode=25, videoMode=36, color 1280×256@5, depth 640×128@5)`**。ROSIE4 = 本设备 **PID 0x0206**;identifier=550 是 **ROSIE2**(别的相机,mode28),之前把"550.csv=RS-D550=本设备"是误判。
- `CameraPresenter setVideoMode(getVideoMode())` → `SetVideoMode` 反汇编 @0x474b0 写 **FW 0xF0 = 入参** → wire `[0x20,0xF0,0x24,0x00]`。36 = `DEPTH_DATA_11_BITS(4)+SCALE_DOWN_OFFSET(32)`,自洽。
- **⇒ reg 0xF0 写的就是 videoMode 字段 = depthDataType,二者同值;mode25 写 36(0x24)。**
- **旧"proven"序列里的 `20 f0 02` = 工程师手动强跑 `DEPTH_DATA_14_BITS`(1280×480 错配置)的实测值,是深度退化为列恒定垃圾的真因**(不是"mode-id 25"也不是"待真机锁")。
- 已落码:`eys3d_protocol kVideoModeRegMode25=36`、`ArmConfig.videomode_reg` 默认 36、`Mode25Usb2Plan()`(videoMode=36 + 1280×256 MJPEG color + 640×128 depth),fd/host 会话默认切到它。`ProvenWrongModePlan` 固定 0x02 留作回归对比。
- **仍 device-gated(流协商细节,非深度算法)**:VS bFrameIndex(1280×256_MJPEG / 640×128)需 `lsusb -v` 解析真机描述符;depth 传输帧是否含状态行(影响 frame_bytes);AE arming(`APC_SetCTPropVal(CT_AUTO_EXPOSURE_MODE=0,8)`)是否 ASIC 进 live-depth 最后一环(上次设备关机没跑成)。
- 复验:正确 arming(videoMode=36)后,IF2 u16 应集中 0-2047,列内 std 不再恒定;`run_mode25.sh` 一键。

## ⇒ M6.4 路线裁决(2026-06-01 订正版,**硬件深度待 mode25 复验**)
- **设备硬件 ASIC 深度(IF2)**：理论可行(ASIC 直出视差,Z=ZDtable[disp])，但实测列恒定垃圾,
  少 AE arming 待验；且 gomob 运行时**禁用 SDK**，要拿硬件深度须 libusb 全量复刻 arming 序列=高风险高工。
- **端侧软件 stereo(IF1 双目)**：IF1 1280×480 两眼并排是**真立体对**(已验视差)，gomob 已握有
  工厂标定(rectify 表/log)+ZD 表(均已提取烘焙)，自研 rectify+SGBM→视差→`Z=ZDtable[disp]/fx·B`。
  **可靠、零运行时 SDK、契合项目硬约束**=综合推荐主线。
- 提取产物：ZD 表 `.dev/eys3d-sdk/tables/zd_dt4_b4096.bin`、rectify 表 `tables/rectify_{0..3}.bin`、
  标定 `.dev/eys3d-probe/rsd550_calib.json`/`rectlog_1.bin`。逆向产物 `.dev/eys3d-re/`。

## ★ 标定已拿到 + 度量公式（2026-06-01）
`VIN_BF301208.bin`(2420B, SN+日期头) **是标定，但自定义序列化**(非裸 eSPCtrl_RectLogData，
全偏移扫 CamMat 模式 0 命中)，解码需 VINCreator 解析器。**更干净的路**：设备直读——写
`.dev/eys3d-sdk/eys3d_get_calib.cpp` 调 `APC_GetRectifyLogData(idx=1)` rc=0 拿到 1024B
`eSPCtrl_RectLogData`(`.dev/eys3d-sdk/rectlog_1.bin`)。SDK struct 偏移与我 x86 头不完全一致，
按 CamMat `[fx,0,cx,0,fy,cy,0,0,1]` / NewCamMat 模式扫描提取。**两来源交叉验证一致**
(648/482.865/1229.21/49.99 在 rectlog 与 VIN 都在)。
- **矫正内参**: fx=fy=**1229.205**px, cx=**648.0**, cy=**482.865**
- **NewCamMat[3] = -fx·B = -61438.49** → **baseline B = 49.98mm**
- **原始左目内参**: fx=1292.674, cx=637.946, fy=1292.305, cy=496.661
- **★度量深度公式: Z_mm = fx·B / disparity = 61438.49 / disparity_px**（0.5m→123px, 1m→61px, 2m→31px）
- 完整 R/T/dist/LRotaMat/RRotaMat 在 rectlog_1.bin，做 cv::stereoRectify 用。标定存 `.dev/eys3d-probe/rsd550_calib.json`。
- 设备 reset 后 uvcvideo 不重建 video2-5 → eSPDI Init 失败；`echo 0/1 > /sys/bus/usb/devices/7-2.2/authorized` 强制 re-enumerate 即恢复。

## 会话健壮性
连续开关设备会卡 immediate-STALL（上次没干净停流）。**会话开始先 `libusb_reset_device`** 清残留，
之后稳定复现。设备 ~20min 自动关机；带电 hub 强制 USB2 但能跑。

## How to apply
- 别再怀疑 RHEL9/服务器不兼容——它能出流，基线是 eSPDI 直调（V4L2/uvcvideo）。
- 自研 libusb 取流照搬本序列；激活用 opcode **0x82/0x20 + 0x0a 计数器握手**（不是旧的 0xE0/0xE3）。
- 复测异步多 URB；若仍 0 帧，做 eSPDI(uvcvideo) vs 我方 libusb 的 usbmon 控制响应逐行 diff。
- host 路径若只为出深度验证，可直接走 V4L2+UVCIOC XU（标准 OS API，非原厂 SDK）；Android 路径必须啃 libusb 异步。
- 设备约 20min 自动关机（整条 usb 总线断），需长按开关键开机；持续 USB 访问可保活。
- 相关：[[finding_vincreator_eys3d_uvc_blueprint_2026-06-01]]、[[finding_p100r3_dual_endpoint_host_kill_2026-05-18]]（Android UVC 栈重写共用异步 URB 经验）。
