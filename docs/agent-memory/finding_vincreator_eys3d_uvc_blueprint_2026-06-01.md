# VINCreator(eYs3D)逆向 = gomob M1.6 UVC 重写蓝本 + 双流死锁旁证

从 vivo PD2324 拉取 VINCreator(`com.vin.uvc` v1.4.11)APK,jadx 反编译 4518 java + binutils 分析 23 so。
完整报告:`tests/vincreator-apk/REVERSE-ENGINEERING.md`(反编译产物在 `.dev/vincreator-jadx/`,gitignored)。

## 结论(经 10 subagent 交叉验证,3 条载荷 claim 对抗核验)

1. **VINCreator 是 eYs3D/Etron(非 Berxel)** 的 UVC 深度相机 App,源根 `EtronSDK_Android/APC/esp_android_usb_camera_sdk`,
   目标 VID=0x1E4E。寄存器空间/深度协议与 P100R3 **不通用**,是蓝本非同源。
2. **fd 注入模型与 gomob BerxelService 主干一致**:`USBMonitor.openDevice→getFileDescriptor`,
   `ApcCamera.nativeConnect(ptr,vid,pid,fd,bus,dev,usbfsPath)`(`UVCCamera.java:341`)。
   但用的是 Etron 私有 `libusb_set_device_fd`,**不是** upstream `libusb_wrap_sys_device`(全 so 零命中)→ gomob 重写应选现代 wrap API。
3. **权限三件套独立印证 gomob 修后版本对**:`FLAG_IMMUTABLE|UPDATE_CURRENT`(`USBMonitor.java:196`)+ RECEIVER_NOT_EXPORTED + setPackage。
   唯一差异:eYs3D 用 explicit Intent + manifest Receiver + 静态 WeakRef 分发(ROM 广播丢失时的加固方向)。
4. **★ 双流死锁旁证(削弱"老 backend 是根因")**:VINCreator 这个**能在 Android 跑双流深度**的商业 App,
   用的恰是 **libusb 1.0.19 经典 android_usbfs backend + saki/eYs3D libuvc fork**(与 Berxel 9.9.190 嵌的 libuvc-0.0.7 同代老栈)。
   既然同代老栈能跑通双流 → "老 backend 本身致 P100R3 双流死"站不住,与 OTG 供电根因([[finding_powered_hub_unblocks_vivo_dual_stream]] / 仓内已订正)一致。**属旁证非铁证**。
5. **eYs3D 应用层做两阶段供电时序**:先 startPreview RGB1300 给深度相机供电 → `waitForRgb1300PowerReadyForDepthOpen`(80ms 沉降/3000ms 超时)才 `apcCamera.open()`(`CameraPresenter.java:5516`)。
   P100R3 是单设备双流没第二路当"供电先导",软件时序只能缓解、根因仍靠带电 hub。

## 对抗核验订正(写报告/引用时注意)

- 原以为"L'+D 是单帧复合 native 拆分省并发"——**证伪**:常规 L'+D 就是 color(IF1)+depth(IF2) **双流并发**(`startPreview(0)`+`startPreview(1)`);native interleave 只是单流时间维丢帧。能减并发的只有 `L+R` 拼接帧或显式 interleave 子模式,且 P100R3 无该 firmware 协议。
- eYs3D 深度是 **SDK 端软件重建**(libDepthMSR/depthfilter/SwPostProc)。**P100R3 相反**:companion 直接交织吐真 metric 深度(0x0600),重建在设备 ASIC,路线 A 直出深度,**不需移植 eYs3D 重建链**(见 [[finding_p100r3_depth_ir_interleaved_2026-05-29]])。

## How to apply

- **M1.6 UVC 重写**:照搬开流原语顺序 `open→setPreviewSize→setPreviewDisplay→setFrameCallback→startPreview(streamId)`,
  color/depth 作同一 native handle 两条流;JNI 签名照 ApcCamera 但底层用 upstream libusb wrap_sys_device;
  双流加"先开一路→确认稳定供电→沉降→再开第二路"显式状态机。
- **深度后处理**可借鉴 eYs3D 分层(DepthMSR 视差→Z / depthfilter 补洞遮挡边缘时域 / SwPostProc OpenCL),
  算子 `HoleFill/occlusion_filter/EdgePreServingFilter/SegmentFill` 对应多视角融合质量需求;但 0xF4xx 寄存器是 Etron ASIC 空间,**别当 P100R3 用**。
- 想要精确 device_filter vid/pid / 解析 param bin / 看 libuvc keepalive,用 apktool 解 res + 反汇编(遗留待查见报告)。
