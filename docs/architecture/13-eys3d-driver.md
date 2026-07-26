# 13 · eYs3D / Etron RS-D550 自研 UVC 驱动

> 状态:M6.1 host 验机已完成,2026-06-01。
> 全自研,**不引入原厂 SDK**(libESPDI/libDepthMSR/libeysov/libuvc fork 一律不链)。
> host-first → portable → Android,挂在 `docs/architecture/12-camera-abstraction.md` 的 `ICameraDriver`/`ICameraSession` 抽象下。
> 关联:`tests/vincreator-apk/REVERSE-ENGINEERING.md`、`docs/agent-memory/finding_vincreator_eys3d_uvc_blueprint_2026-06-01.md`、TODO.md M6。

## 1. 硬件实测(M6.1,`lsusb -v -d 3438:0206`)

| 项 | 值 |
|----|----|
| VID:PID | **`0x3438:0x0206`**(Etron Technology, Inc. / RS-D550)。**非**逆向报告里 VINCreator 主打的 0x1E4E,是同体系不同型号 |
| iManufacturer / iProduct | Etron Technology, Inc. / RS-D550 |
| iSerial | **BF301208**(对应 VINCreator `assets/param/VIN_BF301174.bin`/`VIN_BF301218.bin` 同族标定 blob) |
| USB | bcdUSB 2.10;实测跑 USB2 HS(经带电 Genesys Logic hub `05e3:0610`);BOS 自报 SS capable |
| 设备类 | Miscellaneous / IAD / Video,标准 **UVC 1.00** 复合设备,bNumInterfaces=3,MaxPower 500mA(bus powered) |
| 内核 | uvcvideo 已绑 `7-2.x:1.0`,建 `/dev/video2-5` → **自研 libusb 驱动需先 detach kernel driver** |

### 1.1 接口拓扑

```
IF0  VideoControl
     ├─ INPUT_TERMINAL id=1  Camera Sensor(sensor1)  +AE 控制
     ├─ OUTPUT_TERMINAL id=2 USB Streaming  ← source=4   (喂 IF1)
     ├─ PROCESSING_UNIT id=3 Brightness/Contrast/Hue/Sat/Sharp/Gamma/WB/BacklightComp/PowerLineFreq/WB-Auto
     ├─ EXTENSION_UNIT  id=4 ★Etron XU  GUID {c2b1ccad-abf6-48b8-8e37-32d4f3a3feec}
     │                       bNumControls=5  bmControls(0)=0x06 bmControls(1)=0x0e  (深度模式/寄存器入口)
     ├─ INPUT_TERMINAL id=5  Camera Sensor(sensor2)
     ├─ OUTPUT_TERMINAL id=7 USB Streaming  ← source=5   (喂 IF2)
     └─ status interrupt EP 0x83 (32B, bInterval 11)

IF1  VideoStreaming  EP 0x81 BULK 512B   terminalLink=2 (sensor1)   —— L+R 立体主流
     Format1 UNCOMPRESSED YUY2 {32595559…}(16bpp,即 YUYV):
        2560×960@30(=L+R 拼帧)、1280×480、640×480、320×240、640×240
     Format2 MJPEG(9 帧):1280×960、1280×256、2560×960、800×600、640×480、320×240、2592×1944、1600×1200、1280×720

IF2  VideoStreaming  EP 0x82 BULK 512B   terminalLink=7 (sensor2)   —— 第二路(疑似深度/视差)
     Format1 UNCOMPRESSED YUY2(16bpp):
        1280×960、1280×256、640×480、640×128、320×240
```

### 1.2 与 Berxel P100R3 对比(决定复用面)

| 维度 | RS-D550(eYs3D) | P100R3(Berxel) |
|---|---|---|
| 设备数 | **1**(单 UVC 复合) | 2(master+companion) |
| 取流 | 两路标准 **UVC-over-BULK** 512B(IF1/IF2) | companion BULK-over-UVC 非标 + master MJPEG-BULK |
| 协商 | 标准 UVC probe/commit(`negotiate_uvc_stream` 直接可用) | 同上 + Sonix 特例 |
| 控制 | 标准 PU + **Etron XU id=4**(0xF4xx 寄存器,待解) | Sonix XU5 keepalive + ASIC 寄存器 |
| keepalive | 无需(`interval=0`) | 必须(Novatek 缺陷) |
| 深度 | **端侧重建 或 IF2 on-chip 深度**(待 M6.2 实测) | companion ASIC 直出 metric |

**结论**:RS-D550 比 Berxel 贴 UVC spec,`native/camera/` 通用件(negotiate + BULK assembler + RgbdFramePairer)复用度高;自研重头是 Etron XU 序列 + 深度。

## 2. Etron XU 开流激活协议(libESPDI.so 自反汇编锁定)

> 来源:本仓 `tests/vincreator-apk/extracted/lib/arm64-v8a/libESPDI.so`,用 `llvm-objdump` 自行反汇编
> `CVideoDevice::SetPropertyValue`(0x41b20)/`SetFWRegister`(0x41e48)/`SetHWRegister`(0x42194)/`SetVideoMode`(0x474b0)。
> 不照搬逆向报告——报告间在 wValue / wIndex / wLength 上互相矛盾,以下字节是亲自核对寄存器装载顺序得到,**高置信**。

### 2.1 激活机制结论:协商成功 ≠ 推流,缺的是 SetVideoMode 写 FW 0xF0

RS-D550 标准 UVC probe/commit 全成功但 0 帧(系统 `uvcvideo` 也 0 帧),因为 **Etron 模组开机默认不进流模式;必须先经 XU id=4 写固件寄存器 `0xF0 = videoMode`,firmware 据此决定吐 L/R/depth/interleave,之后标准 VS_COMMIT 才真出帧**。没有独立的 "enable stream" XU——`SetVideoMode` 写 0xF0 就是最接近"开流激活"的私有动作。eYs3D 调用序:`open(标准枚举)→ SetVideoMode(0xF0,mode) → [可选 interleave 0xED / IR 0xE0..E3 / 画质 0xF4xx] → 标准 UVC probe/commit → bulk 拉流`。

### 2.2 底层 XU 控制传输模板(SET_CUR 写 + GET_CUR 回读)

所有 HW/FW 寄存器读写最终汇聚到 `SetPropertyValue`,一发 SET_CUR 写、一发 GET_CUR 回读校验。**反汇编确证的 libusb 实参顺序**(`libusb_control_transfer(ctx, w1=bmReqType, w2=bRequest, w3=wValue, w4=wIndex, x5=data, w6=wLength, w7=timeout)`):

| 字段 | SET 阶段 | GET 阶段 | 反汇编证据 |
|---|---|---|---|
| bmRequestType | `0x21` | `0xA1` | `0x41bec mov w1,#33` / `0x41c20 mov w1,#-95` |
| bRequest | `0x01` SET_CUR | `0x81` GET_CUR | `0x41bf0 mov w2,#1` / `0x41c28 mov w2,#-127` |
| wValue | `0x0300` | `0x0300` | `w3=w24<<8`;FW 分支 `0x41d54` 与 HW 分支 `0x41cac` **都强制 `w24=#3`** → **HW/FW 同 wValue=0x0300**,只靠 payload[0] opcode 区分 |
| wIndex | `0x0400` | `0x0400` | `0x41bfc mov w4,#1024`(=0x0400=XU unit id 4<<8 \| iface 0) |
| wLength | `4` | `4` | `0x41c04 mov w6,#4`(逆向报告称 1024 是把 wIndex/wLength 实参位置搞反了) |

> ★ 纠正逆向报告分歧:(a) FW 的 wValue 是 **0x0300 不是 0x0700**——type=7 只决定走 FW 分支,进分支后 w24 被改回 3;(b) wIndex=**0x0400**、wLength=**4**(不是 wIndex=4 / wLength=1024)。

**payload 4 字节布局**(`x20` 缓冲,**先清零**再填):

| 写类型 | byte[0] opcode | byte[1] | byte[2] | byte[3] | 证据 |
|---|---|---|---|---|---|
| FW 写(0xF0/0xED/0xE0..) | `0x20` | addr | value | `0x00` | `0x41d8c w0=#32; strb [x20]`;`[x20,#1]=addr,[x20,#2]=value` |
| HW 写 8-bit 地址 | `0x00`(未写,需零初始化) | addr | value | `0x00` | `0x41ca8 strb addr; 0x41cb0 strb value`(buf[0] 8-bit 分支不写) |
| HW 写 16-bit 地址(flag bit1) | `0x02` | addr_lo | addr_hi | value | `0x41cd8 w1=#2; strb [x20]; [x20,#3]=value`(`0x41cdc lsr addr>>8`) |
| FW/HW 读(GET 前的 SET) | FW`0xA0`/HW`0x80`/`0x82` | addr | `0x00` | `0x00` | `GetPropertyValue 0x41528 strb #0x80` 等;回读结果取 GET_CUR 后 `buf[1]` |

`SetVideoMode(mode)` 反汇编(`0x474b0`):`w1=#240(0xF0); w2=mode; w3=#17(0x11=flag); bl SetFWRegister` → 等价 FW 写 `20 F0 <mode> 00`。flag 0x11 bit1=0 → 走 8-bit 地址路径。

### 2.3 P100R3 不可照搬

XU unit id=4 / iface=0 / 0xF0/0xED/0xE0..E3/0xF4xx 地址全是 Etron ASIC 私有,与 P100R3 companion(0x3558,Sonix-style)地址空间**完全不通用**,只借协议骨架(SET_CUR+GET_CUR 两阶段、payload opcode 区分类型),地址值一律按各自描述符/抓包重定。

## 2bis. ★ 2026-06-01 RE 定论(SDK 实测 + 6-agent 反编译 + APK assets 提取,取代旧"待实测")

> 权威 finding:`docs/agent-memory/finding_rsd550_open_sequence_decoded_2026-06-01.md`。
> 决策:**深度双路 = 硬件 ASIC 主(复刻 APK)+ 软件 stereo 兼(fallback)**,driver 内按能力选。

### IF2 = on-chip ASIC 深度(视差),不是第二目 raw 也不是 IR
- 反汇编实证:eSPDI `APC_GetDepthImage → CVideoDevice::GetImage(isDepth=1) → V4L2 DQBUF → memcpy`,
  **零 host 软件 stereo**(内嵌 OpenCV StereoBM/SGBM 是死代码,0 xref)。视差由**设备 ASIC 经 IF2 推上来**。
- IF2 11-bit 模式每像素 = **1 个小端 u16 视差值**(有效 0-2047),**不要当 YUY2 拆**。
  `convert_depth_y_to_buffer` 只是把 u16 整值映 RGB 上色,不影响 metric。
- 旧"IF2 因 flash 读 ZD 表失败而死"是**误判**:flash/ZD 读全成功,`FW_UNKNOWN_STRUCT_LEN_OF_STI`
  是 flash 保护探测的非致命错误,不在深度路径上。

### ★ 度量换算:Z_mm = byteswap(ZDtable[视差])
- ZD 表 = flash file id=50+nIndex 的"视差索引→Z(mm)"LUT,11bit 模式 **4096B=2048×u16**;
  `APC_GetZDTable(buf, BufferLength=4096, &len, {nIndex,nDataType})`,**BufferLength 必须精确=4096 否则 -3**。
- 查表纯直接索引无插值,**读出 u16 后做 16-bit byteswap**(`rol cx,8`)。已提取 `tables/zd_dt4_b4096.bin`,
  交叉验证:视差512→502mm、1024→251mm、2047→125mm,与几何 `Z=fx'·B/disp`(fx'=1229.205,B=49.98mm)吻合 ±5%。
- 进表前可选视差线性补偿 `new_disp = disp*depth_comp_pars[0]+depth_comp_pars[1]`(pars 在 RectLogData)。
- rectify 表 file id=40+idx(`APC_GetRectifyTable`,BufferLength **必须=1024**,idx 0-3 有效);
  RectLogData 含 fx'/cx'/cy'/Tx/Q/dist/depth_comp_pars(`APC_GetRectifyMatLogData`/`GetRectifyLogData`)。

### ★ 取流模式配对(camera_modes_206.csv,ROSIE4=PID 0x0206)—— **错配是深度变垃圾的真因**
- **USB2(本设备经带电 hub 实测 480Mbps)→ mode 25**:**color 1280×256 MJPG @5fps + depth 640×128 @5fps**,
  depthDataType=**36**(`SCALE_DOWN_11_BITS`=11_BITS(4)+SCALE_DOWN(32)),interleave=**off**,**color 流必须配对同开**(ASIC 用缩放 L' 算视差)。
- USB3 → videoMode=4(`RECTIFY_11_BITS`)color/depth 640×480@10。
- 教训:曾用 `color 1280×480 YUYV @30fps`(不在 0x0206 合法表)→ ASIC 退化吐 raw(列恒定、u16 69% >16384)。
  **必须用 CSV 精确配对**。判据:修对后 depth u16 落回 0-2047。
- depthDataType=36 经 `SetVideoMode`(写 FW 寄存器 0xF0,见 §2.2)注入,**就是** APK 的 `setVideoMode/nativeSetVideoMode`。

### ★ IR 投射器 arming(钢板等无纹理目标的纹理来源,深度前置)
- 序列 = `APC_SetIRMaxValue(6)` → `APC_SetIRMode(0x03)`(开 ch0+ch1) → `APC_SetCurrentIRValue(3)`(IR_DEF_VALUE=3, MAX=6)。
- 底层都是 XU FW 写(IR 电流寄存器 0xE0 等);自研 libusb 走 §2.2 模板复刻。
- AE 曝光:APK `setExposureMode(8)`(aperture-priority,UVC CT_AUTO_EXPOSURE_MODE=0 写 8);
  host `APC_SetCTPropVal(0,8)` 曾 -63,需在 open 成功后设或改 manual;AE 只影响纹理亮度,不致深度垃圾。

### 完整 APK 取深度序列(host eSPDI / 自研 libusb 都照此)
`open(标准枚举)` → `SetVideoMode(36)` → `SetInterleaveMode(false)` → `OpenDevice2(color 1280×256 MJPG, depth 640×128, fps=5)` →
`AE(8)` → `IR(max6/mode0x03/cur3)` → `GetZDTable(nIndex 据分辨率)` → 取帧 `GetColorImage`+`GetDepthImage`(color 必须配对取) →
每像素 `disp=LE16(depth[2n])` → `Z_mm=byteswap(ZDtable[disp])`。**高质量深度图是 native 上色渲染,后处理(flying-pixel/hole-fill)在 ASIC/native**。

### 软件 stereo fallback(已离线搭原型 `.dev/eys3d-probe/stereo_depth_proto.py`)
IF1 双目(2560×960 或 1280×480 = L|R 并排)→ `cv::stereoRectify`(工厂标定)→ SGBM → `Z=fx'·B/disp` 或 ZD 表。
度量换算已验证;矫正/视差质量需明亮非周期纹理场景调(规则点阵/周期图样对 SGBM 病态)。设备 arm 失败时启用。

### 仍待真机验证(无设备暂搁,RE 已给确定路径)
1. **mode 25 实测出真深度**:`.dev/eys3d-sdk/run_mode25.sh` 一键验(u16 应回 0-2047)。
2. **自研 libusb 复刻 mode25 arming**:★mode25 的 0xF0 写值【已离线锁定=36(0x24)】(jadx 反编译
   `CameraModeKt DEFAULT_ROSIE4_U2_MODE.videoMode=36`,见 finding 终极订正),已落 `Mode25Usb2Plan()`。
   **仅剩流协商细节** device-gated:VS bFrameIndex(1280×256_MJPEG/640×128)需 `lsusb -v` 真机描述符;
   depth 传输帧是否含状态行(影响 frame_bytes)。这些非深度算法,真机一抓即定。
3. **device type==2 分支**:USB2 下 APK 仍 `setVideoMode(36)` 成功 → 0xF0 在 USB2 也写(旧"仅 USB3 写"假设已松动)。
4. **供电**:必须带电 hub(USB2 480Mbps,三路并发电流)。设备 ~15min 硬定时自动关机。

## 2ter. 落进 eys3d_protocol 的写法 + 真机试错矩阵

每条 XU 写直接构造 `gomob::berxel::host::XuPayload{ .w_value=0x0300, .w_index=0x0400, .data={op,addr,val,0x00} }`,经 `replay_xu_payloads(dev, payloads, /*read_back=*/true, "eys3d-activate")` 注入(它内部 `uvc_set_cur(w_value,w_index,data,4)` = `control_transfer(0x21,0x01,0x0300,0x0400,...)`,read_back 时 `uvc_get_cur`=`0xA1/0x81`)。`selector` 字段仅做注释,wire 值已在 w_value 高字节。

```cpp
// eys3d_protocol.cpp(自研,纯数据)
inline XuPayload make_eys3d_fw_write(uint8_t addr, uint8_t value) {
    return XuPayload{ /*selector*/3, /*w_value*/0x0300, /*w_index*/0x0400,
                      {0x20, addr, value, 0x00} };
}
inline XuPayload make_eys3d_set_video_mode(uint8_t mode) {   // 写 FW 0xF0
    return make_eys3d_fw_write(0xF0, mode);
}
inline XuPayload make_eys3d_interleave(bool on) {            // 写 FW 0xED
    return make_eys3d_fw_write(0xED, on ? 0x01 : 0x00);
}
// HW 8-bit:{0x00,addr,value,0x00};HW 16-bit:{0x02,addr_lo,addr_hi,value}
```

**激活序(M6.3 在 `eys3d_probe` 协商前插入)**:`claim(IF0)` → `replay_xu_payloads({set_video_mode(mode)})` → 现有 `negotiate_uvc_stream(IF1/IF2)` → bulk 拉流。

**真机试错矩阵(按置信度,逐行试到出帧)**:

| # | 序列 | 置信 | 说明 |
|---|---|---|---|
| A | SET `21 01 wV=0300 wI=0400 wLen=4 = [20 F0 <mode> 00]` + GET 回读 | 高 | 反汇编直出,先试 mode=14(L+R),再 28(L'+D) |
| B | A 但 device type≠2 时跳过 0xF0,仅标准 probe/commit | 中 | 验"是否真需 0xF0";若 B 出帧说明 RS-D550 走 frame-index 协商 |
| C | A + `[20 ED 01 00]`(interleave on)再协商 | 中 | L'+D scale_down(mode 28)疑似需 interleave |
| D | A 失败时改 wValue=`0x0700`(FW type 原值不被改回的假设) | 低 | 万一 RS-D550 固件 ROM 与反汇编版本 w24 行为不同 |
| E | A 失败时改 wIndex=`0x0004`(unit/iface 实参位互换假设) | 低 | 兜底逆向报告分歧 |

每行先 `lsusb -v` 读实际 XU descriptor 的 bUnitID 校 wIndex 高字节、读 VS frame_desc 校 mode→分辨率;出帧后用 `eys3d_probe` 的 `frame_stats` 判 IF2 字节分布。

## 3. 驱动落点(挂 `native/camera` 抽象)

实际落地结构(✅=已落码+单测,2026-06-01):

```
native/camera/
├─ camera_device.h               # ✅ 再导出 IUvcDevice/XuPayload/协商/组装件到 gomob::camera(零改 Berxel)
├─ camera_session.h              # ✅ ICameraDriver/ICameraSession/CameraCapabilities/CameraFrame/DepthControls
└─ host/usb_context.h            # ✅ gomob::camera::UsbContext(libusb 默认 context;host driver 共用)

native/eys3d/
├─ portable/                     # 纯逻辑,无 libusb/无 Android,Android NDK 直接复用,scripts/native-host-test.sh 覆盖
│  ├─ eys3d_protocol.{h,cpp}     # ✅ Etron XU 写原语 + arming;★videoMode 寄存器值≠depthDataType(已解耦)
│  ├─ eys3d_depth.{h,cpp}        # ✅ ZdTable(flash byteswap)+查表/几何 → metric mm
│  ├─ eys3d_depth_router.{h,cpp} # ✅ IF2 11bit 视差帧 → 剥状态行/字节序 → DepthFinalizer → depthMm
│  ├─ eys3d_driver.{h,cpp}       # ✅ BuildRsd550Capabilities(mode25/USB3 profiles)+ DepthFinalizer
│  ├─ eys3d_session_core.{h,cpp} # ✅ 传输无关会话引擎:arming 装配+路由+帧队列背压+serial+状态机+统计
│  └─ eys3d_stereo_depth.{h,cpp} # ✅ 软件 stereo fallback(自研 SAD+LR一致性+唯一性+亚像素→视差×8→DepthFinalizer)
└─ host/                         # libusb 绑定,scripts/eys3d-host-test.sh 覆盖
   ├─ eys3d_usb_device.h         # ✅ Eys3dUsbDevice:IUvcDevice(libusb;replay_stream 已复用)
   ├─ eys3d_host_session.{h,cpp} # ✅ Eys3dDriver:ICameraDriver + Eys3dHostSession:ICameraSession(传输壳)
   └─ eys3d_replay_stream.cpp    # 取流工具(proven 开流序列实证,仅编译保活)
```

复用 `native/camera/`:`IUvcDevice`、`negotiate_uvc_stream`、`UvcRawFrameAssembler`、`RgbdFramePairer`、`replay_xu_payloads`、`TemporalFilter`。自研只写 `eys3d_protocol` + `eys3d_depth*` + `eys3d_stereo_depth`。

**会话分层(host 与 Android 共用)**:`Eys3dSessionCore`(portable)承担所有平台无关职责(深度路由/帧队列/状态/统计/arming 装配);`Eys3dHostSession`(libusb)/ 未来 `Eys3dFdSession`(Android fd)只是【传输壳】——claim+回放开流序列+异步多 URB+FID 组装,把整帧喂 core。`poll/state/stats` 全委托 core。

**双路深度汇流(硬件主 + 软件兼)**:两条路径最终都进 `Eys3dSessionCore` 的同一 depthMm 队列:
- 硬件 ASIC 路径 — IF2 整帧 → `core.OnRawDepthFrame()`(内部 `DepthRouter` 查 ZD 表/几何)。
- 软件 stereo 路径 — IF1 并排 L/R → `StereoDepthEngine`(SAD block-matching)算出 metric depthMm → `core.OnDepthMmFrame()`(路径无关入口)。

两路共用 `DepthFinalizer`(ZD 表 / 几何),出同一 `CameraFrame(kDepthMm)` 契约,上层无感。路径由 driver 按设备能力(`depth_is_metric_onchip`)选。软件 stereo 算法在【已矫正】L/R 上单测过(合成已知视差恢复 + 度量),真机矫正质量待明亮非周期纹理场景验(M6.5)。

**★ 设备门控遗留**:`Eys3dHostSession` 的开流序列当前用 `ProvenWrongModePlan`(1280×480 错模式,实证能出流但深度退化为列恒定垃圾)。深度 **routing 已对**(core/router 单测过),只是设备 ASIC 跑错模式。**mode25 正确开流值**(reg 0xF0 值 / PROBE 分辨率 / depthType36 的 XU 线编码)需真机 usbmon diff 锁定 → 替换为 `Mode25Usb2Plan`,届时即出 apk 级深度(见 TODO M6.5 ④)。

## 3bis. ★ Android 生产路径:纯 native 直驱厂商 C++ 引擎(零 Java 编排,2026-06-17)

**背景**:自研独立路径(libuvc_gomob / pupil + stock libusb-1.0,§2bis 终态目标)在 Android 真机持续撞 `-EPROTO`(bulk URB 不回收,见 finding_eys3d_zero_vendor_independence)。月余未破。生产路径遂改为**复用厂商已验证的 C++ 起流引擎**:厂商 libUVCCamera.so 内部 = saki4510t UVC 栈(`UVCCamera`/`UVCPreview`/`FrameGrabber` C++ 类)+ libuvc(纯 C)+ libESPDI(控制/ZD)。Java SDK 只是把这套 C++ 引擎包成 Android API 的外壳,非技术必需 → gomob 在 native 直调这套 C++ 引擎,**零 Java 编排**。

**帧出线路**(实现 `native/eys3d/android/eys3d_vendor_cpp_session.cpp`,ABI 契约 `vendor_uvc_abi.h`):
1. `dlopen("libUVCCamera.so", RTLD_NOW|RTLD_LOCAL)` + `dlsym` 取 mangled C++ 方法(this 显式作首参当自由函数调)。
2. over-alloc 裸内存 + 厂商 `UVCCamera()` ctor(厂商写 vtable + 内部建 1 个 `FrameGrabber` + 2 个 `UVCPreview`)。
3. 定位内部 FrameGrabber(`[cam+0x2430]`)→ **校验** `[fg+8]==livePlyCallback`(确认 ABI offset 没漂)→ 才 repoint。
4. `connect(vid,pid,fd,bus,dev,usbfs)` → `setVideoMode(36)` → `setInterleaveMode(false)`。
5. repoint FrameGrabber 回调 `[fg+8]=trampoline`、ctx `[fg+0x10]=session` → 帧全 native 进 core,**绝不碰 `_jobject`**。
6. 彩色流(离屏 ANativeWindow 满足 startPreview 非空门控)→ IR/AE → 暖机 3s → 深度流 → 保活。

**唯一 Java 触点**:① `UsbManager.openDevice` 拿 usbfs fd;② `NativeBridge.bindEys3dVendorJni()` 经厂商 `JNI_OnLoad` 调 `setVM`(startPreview 抓拍线程内 `getVM()→AttachCurrentThread` 需要)。帧路径本身零 JNI。

**ABI 纪律**(厂商 = NDK r12 `std::__ndk1`,gomob = NDK27 `std::__1`):不在 gomob 侧构造/析构任何 `__ndk1` 对象(shared_ptr/vector);回调里厂商 `vector<uchar>&` 当 3 指针 POD 只读(begin/end/cap);`dlopen(RTLD_LOCAL)` 隔离厂商自带 libusb100 不遮蔽 gomob libusb-1.0。

**深度格式订正**：FrameGrabber 回调实机值域与原厂 `restoreImageFlow` 证明是 mode25 原始 `disparity×8`，不是 metric mm。当前 native 只做字节透传，`DepthFrame.sampleFormat=DISPARITY_X8_U16`；VIN 服务端按原厂 BIN 的 `f/B/type` 恢复毫米坐标。禁止在上传前套未知 ZD LUT 丢失原厂输入。

**踩坑订正**(均反汇编/源码实证,详见 finding):connect 参数序 `(vid,pid,fd,...)` 非 `(fd,vid,pid,...)`;usbfs = 根 `/dev/bus/usb` 非完整设备节点;`setExternalStoragePublicDirectory` 必设(connect 内 `strdup`,NULL 崩)— 现经 `configJson→options_json` 下发 app 专属目录;startPreview 需非空 ANativeWindow(`[uvcpreview+8]` 门控);未 setVM → 抓拍线程 `getVM()` 空崩。

### 生命周期不变量（2026-07-16）

厂商 `FrameGrabber::Open()` 创建 worker 后立即返回，worker 稍后才把 `isStarted` 置真。禁止在 `Open()` 返回后立即以一次 `isStarted=false` 判启动失败并销毁会话；这会让仍在启动的厂商线程继续访问已析构 mutex，最终 native abort。生产实现必须同时满足：worker 启动屏障在 started/failed/超时三态之一后才裁决；`Close()` 完成门和 join 先于 callback context、FrameGrabber、窗口及 UVCCamera 对象释放；回调上下文跨 teardown 保持稳定；Kotlin 将 native `Starting` 与 `Error/Stopped` 分开，启动期单次空 poll 不判死，首帧 deadline 为 10 秒。

验证包括 `eys3d_vendor_worker_lifecycle_test` 的延迟启动/安全 Close 竞态、`CameraStackSessionStateTest`、`Eys3dSessionHealthTest`、`CameraStackConcurrencyTest`，以及真机 3 轮启动/首帧/teardown 对称循环。三轮屏障均 `started=1 waited=1ms`，同一 PID 无 SIGSEGV、destroyed-mutex 或 FATAL。

**退役**:Java `Eys3dApcCamera.kt` shim 已删(git 历史可恢复)。厂商 shim 类 `com.esp.android.usb.camera.core.*` 仍保留(bindEys3dVendorJni 的 `FindClass`/`RegisterNatives`/`setVM` 需要),已加 proguard `-keep`。

**仅 arm64-v8a**:厂商 C++(libUVCCamera/libESPDI)只有 arm64-v8a;且 ABI offset(0x2430/+8/+0x10)是 64 位布局,32 位指针宽不同必失配,VINCreator 的 v7a 二进制又是另一代 SDK(缺后处理链)不可移植。故 `Eys3dCameraService` 对 `Build.SUPPORTED_64_BIT_ABIS.isEmpty()` 前置守门→32 位机即时报「需 64 位手机」(arm64 不受影响);补 v7a 需匹配版本 vendor 二进制 + 重做 32 位 struct RE,无现役 32 位扫描设备,低优先级。

### §内参(intrinsics 注入)

mode25 深度内参由原厂 `VIN_BF301208.bin` 与 rectlog 交叉确认：

- 全幅 1280×960 的 `f=1229.20996,cx=648,cy=482.865`；mode25 是竖向中心裁出的 1280×256 带，主点变为 `cy=130.865`，不是把 960 高各向异性压成 256。
- 640×128 深度档对 1280×256 mode25 带统一缩放0.5：`fx=fy=614.60498,cx=324,cy=65.4325`。旧 `fy=163.9` 已撤销。
- VIN 还原的运行时 K/R/T 真理源在服务端原厂 BIN；端侧内参只供预览与诊断。其他需要 metric mm 的 3D 消费者必须先按明确标定转换，不能把 `DISPARITY_X8_U16` 直接当毫米。

### harness

`tests/harness/eys3d_vendor_cpp/`(device-gated,采 logcat)：`run.sh` 只把 `logcat.txt + sample.json` 写入 `OUTPUT_DIR`，`analyze.py` 按统一目录契约输出 PASS/WARN/FAIL。判据为：① 起流链 marker；② ourCb 帧 fps + poll 首帧；③ valid_ratio 与 `centerDispX8` 是否落在 11bit 视差域；④ 零 JNI。2026-07-16 真机标准命令 PASS：RS-D550/HLSD8 均约 `5fps`、深度有效率中位 `51.8%`、帧路径零 JNI。host 自研路径回归仍用 `tests/harness/eys3d_mode25/`。

## 4. 里程碑

见 TODO.md M6:M6.1✅ → M6.2 自研取流(IF1 单流先通,判 IF2 内容)→ M6.3 Etron XU 双流 → M6.4 立体/视差(或 on-chip 深度解析)→ M6.5 metric Z(≤1%@1-2m)→ M6.6 portable 化 → M6.7 Android JNI → M6.8 真机端到端。每级一个 harness。

**Android 生产路径(2026-06-17)**:绕开自研 -EPROTO 硬墙,改 native 直驱厂商 C++ 引擎出 mode25 真深度(§3bis),零 Java 编排。自研全独立路径(§2bis 终态)作在研路线保留。
