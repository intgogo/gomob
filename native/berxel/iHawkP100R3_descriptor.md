# iHawk P100R3 USB Descriptor 真理源

> 来源：vivo PD2324 (Android 15 / MT6989) + iHawk P100R3 实测 dump，2026-05-27。
> 抓法：`adb shell am broadcast -n io.gomob.scan.debug/.DebugSonixReceiver -a io.gomob.scan.debug.DEBUG_USB_DESCRIPTOR_DUMP --ei vid <vid> --ei pid <pid>`
> 原始 log：`.dev/iHawkP100R3_descriptor_raw.log`

iHawk P100R3 在 host 端枚举出 **2 个独立 USB device**（Berxel/Sonix companion 设计的标志）。
每个 device 都是标准 UVC：1×VideoControl + 1×VideoStreaming，无 alt-setting（只有 alt 0）。

---

## 1. Companion 节点（深度 / IR raw）

| 字段 | 值 |
|---|---|
| `idVendor:idProduct` | `0x3558:0x1012` |
| `bcdUSB` | `0x0320` (USB 3.2 SuperSpeed) |
| `bcdDevice` | `0x0001` |
| `bDeviceClass` | 239/2/1 (Miscellaneous + IAD) |
| `ep0 max packet size` | 9 (=2^9=512, USB3 enc) |
| `bMaxPower` | 224 mA |
| `wTotalLength` | 313 bytes |

### Interface 0 — UVC VideoControl（XU 所在）

- class/sub/proto = 14/1/0
- alt 0, 1 endpoint
- **EP 0x81 INTERRUPT IN, MPS 64, interval 6** —— UVC status interrupt EP
- classExtra 80 bytes 解析：

| offset | bDescriptorSubtype | 含义 |
|---|---|---|
| 0x00 | `01` VC_HEADER | bcdUVC=0x0150 wTotalLength=0xe4c0 dwClockFreq=0x01000001 bInCollection=1 baInterfaceNr(0)=1 |
| 0x0d | `03` OUTPUT_TERMINAL | bTerminalID=**4** bAssocTerminal=1 bSourceID=3 wTerminalType=USB_STREAMING |
| 0x16 | `06` EXTENSION_UNIT | bUnitID=**3** (← Sonix XU)；GUID 起 `ab 49 b8 cc b3 85 5e 8d 22 1d 20 01 02 04 00 00`；bNumControls=53 (0x35) |
| 0x33 | `02` INPUT_TERMINAL | bTerminalID=**1** wTerminalType=ITT_CAMERA |
| 0x46 | `05` PROCESSING_UNIT | bUnitID=**2** bSourceID=1 bControlSize=2 |

→ **Sonix XU unit ID = 3**（跟 `BerxelProtocolSonix.kSonixXuUnit = 0x03` 一致 ✓）。

### Interface 1 — UVC VideoStreaming（深度数据出口）

- class/sub/proto = 14/2/0
- alt 0, 1 endpoint
- **EP 0x82 BULK IN, MPS 1024**（USB SS）；SS Companion 描述符 `06 30 0f 00 00 00` → bMaxBurst=15
- classExtra 167 bytes 解析：

| offset | desc | 含义 |
|---|---|---|
| 0x00 | `01` VS_INPUT_HEADER | wTotalLength=0xa7 bEndpointAddress=**0x82** bmInfo=0x04 bTerminalLink=4 bStillCaptureMethod=0 |
| 0x0e | `04` VS_FORMAT_UNCOMPRESSED | bFormatIndex=**1** bNumFrameDescriptors=**4** GUID=`YUY2..` bBitsPerPixel=16 bDefaultFrameIndex=1 |
| 0x29 | `05` VS_FRAME_UNCOMPRESSED 1 | 1280×**801**, dwDefault=0x0003640E (=22222 ×100ns ≈ **45 fps**) |
| 0x47 | `05` VS_FRAME_UNCOMPRESSED 2 | 640×**401**, 45 fps |
| 0x65 | `05` VS_FRAME_UNCOMPRESSED 3 | 320×**201**, 45 fps |
| 0x83 | `05` VS_FRAME_UNCOMPRESSED 4 | 1280×800（额外一档），15 fps（dwDefault=0x001E8480） |
| 0xa1 | `0d` VS_COLORFORMAT | bColorPrimaries=1 bTransferChar=1 bMatrixCoefficients=4 |

**关键点**：深度数据按 YUYV 封装走 BULK，宽度 1280 高 800（descriptor 多 1 行 metadata = 801）。frame size = 1280×801×2 ≈ 2 MB，max 4 MB（descriptor 0x3e9418）。

### 对 M1.6.6 实施的约束

```
companion:
  vendor=0x3558 product=0x1012
  vc_interface=0  (xu_unit_id=3, control_xfer 通过 setup wValue=(sel<<8) wIndex=0x0300)
  vs_interface=1  (alt=0 only)
  bulk_in_ep=0x82 max_packet=1024
```

---

## 2. Master 节点（RGB 彩色）

| 字段 | 值 |
|---|---|
| `idVendor:idProduct` | `0x0603:0x001f` |
| `bcdUSB` | `0x0200` (USB 2.0 High Speed) |
| `bcdDevice` | `0x07ff` |
| `bDeviceClass` | 239/2/1 (IAD) |
| `ep0 max packet size` | 64 |
| `bMaxPower` | 500 mA |
| `wTotalLength` | 774 bytes |

### Interface 0 — UVC VideoControl

- class/sub/proto = 14/1/0
- alt 0, 1 endpoint
- **EP 0x85 INTERRUPT IN, MPS 64, interval 1**
- classExtra 85 bytes：5 unit/terminal 链路 (INPUT_TERMINAL=1, OUTPUT_TERMINAL=2, SELECTOR_UNIT=3, PROCESSING_UNIT=4, EXTENSION_UNIT=**5**) → master 上也有 XU（unit id 5），M1.6.7 可能用到。

### Interface 1 — UVC VideoStreaming（RGB 出口）

- class/sub/proto = 14/2/0
- alt 0, 1 endpoint
- **EP 0x81 BULK IN, MPS 512**（USB HS 上限）
- classExtra 635 bytes —— **2 个 format**：
  - **bFormatIndex=1：MJPEG**（subtype 0x06=VS_FORMAT_MJPEG）—— 3 个 frame size：
    - 1920×1080 @ default 5 fps
    - 1280×720 @ default 15 fps  
    - 640×360 @ default 30 fps
  - **bFormatIndex=2：YUY2 Uncompressed** —— 16 个 frame size，从 256×512 到 1280×720 @ 15/30 fps 多档

→ Master = RGB 彩色相机，主用 MJPEG。

### 对 M1.6.6 实施的约束

```
master:
  vendor=0x0603 product=0x001f
  vc_interface=0  (xu_unit_id=5)
  vs_interface=1  (alt=0 only)
  bulk_in_ep=0x81 max_packet=512
```

---

## 3. 跨 host 一致性

- vivo PD2324 (MT6989) 上 enumerate 全成功，kernel 把 companion VC interface 自动当 HID 绑（需 `libusb_detach_kernel_driver`）。
- Linux PC (CentOS 9 / xHCI) 上 enumerate 一致（详见 `.dev/m1.6.2-usb-trace/`）。
- 别的 Android 测试机待补：2510DRK44C / 25102RKBEC / LOG-AN10 / OnePlus PJD110。

## 4. M1.6.6 推荐 stream control 启动顺序

按 PC ground truth 抓的 trace + 2026-05-27 vivo PD2324 真机验证：

### 4.1 candidate C 验证通过的完整启动顺序

**关键发现（2026-05-27 18:20）**：companion firmware 要 master XU 5 control channel
活跃才推 depth BULK — 单 open companion init+probe+commit 后 firmware 不出数据。

```
1. open master 0x0603:0x001f
   libusb_wrap_sys_device(masterFd) → masterHandle
   libusb_claim_interface(masterHandle, 0)  # VC only，不 claim VS (-1)
2. replay master XU 5 init (前 ~20 条来自 .dev/m1.6.2-usb-trace/bus7-master.pcap)
   每条：control_transfer 0x21 0x01 wValue=0x0100 wIndex=0x0500(unit5/iface0) wLen=64
   payload 'BX' header + opcode bytes
3. 起 master keepalive 线程：50ms 周期重发 XU 5 SET_CUR
   payload bytes 10-13 = uint32 LE counter，每次 +0x36 (54)
4. open companion 0x3558:0x1012
   libusb_wrap_sys_device(companionFd) → companionHandle
   libusb_detach_kernel_driver(companionHandle, 0)  # 避 Android HID claim
   libusb_claim_interface(companionHandle, 0)  # VC
   libusb_claim_interface(companionHandle, 1)  # VS (alt 0)
5. companion Sonix init 7 条 SET_CUR (selector 0x19/0x1e)，每条后跟 GET_CUR
6. UVC PROBE/COMMIT 26 字节标准结构：
   SET_CUR wValue=0x0100 wIndex=1 → GET_CUR wValue=0x0100 → SET_CUR wValue=0x0200
7. BULK IN ep=0x82 读：
   - sync_read 16384 字节 timeout=200ms 在 loop 拉块（vivo 上 1MB 触发 NO_DEVICE）
   - 累计字节按 frame size 切帧
```

**关闭顺序（避免 vivo OTG 462ms host kill）**：
```
1. 停 pull 线程
2. 停 master keepalive
3. wait 200ms
4. close companion session
5. wait 200ms
6. close master session
```

### 4.2 Frame 拼装规则

- companion frame index 2 = **640 × 401 × 2 = 513280 字节/帧**（注意 +1 metadata 行）
- firmware 不发 UVC 1.1 payload header — BULK 出 raw 16-bit depth 数据
- 单 BULK URB ≈ 1MB（dwMaxPayloadTransferSize ≈ 1026584），含 1-2 帧 + 零字节填充
- 拼装策略：累计字节数；满 513280 切一帧；多余字节顺延下一帧
- `BerxelFrameAssembler` 默认 byteCount 模式实现该规则

### 4.3 已知限制（手动调试遗留 2026-05-27）

- vivo Funtouch `usbManager.requestPermission` 静默拒发系统对话框 → 必须靠
  `<usb-device>` manifest filter + 物理 OTG 重插自动授权
- master 节点在新建 Activity context 下不可见 `usbManager.deviceList`，必须在
  接到 USB_DEVICE_ATTACHED 的 Activity（如 SonixDebugScreen 由 MainActivity 拉起）里访问
- 反复 open/close 让 vivo USB state machine 退化，3-4 轮后必须物理重插 OTG
- 50ms 静态 keepalive payload 让 BULK 维持 ~3s 然后 firmware silent-die；5ms 高频会
  撑爆 vivo OTG 带宽。最佳 keepalive 节奏 / counter 模式由
  `tests/harness/depth_singlestream/` 自动扫描确定
