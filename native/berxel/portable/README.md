# Berxel P100R3 可移植层（portable）

P100R3 自研 SDK 中**不依赖 Linux libusb / 文件系统**的纯逻辑层，Linux host 与 Android native
共用同一份已验证实现。Android `gomob_native.so` 可直接编译 `gomob_berxel_portable.cpp`。

## 内容

- `gomob_berxel_portable.h` — 公共 API：
  - 数据契约：`UsbId` / `XuPayload` / `P100R3VideoMode` / `P100R3DepthControls` / `UvcFrameInfo` /
    `RgbdFramePairInfo` / `RgbdPairingStats` / `BulkStats` / 会话状态枚举等。
  - `IUvcDevice` 抽象接口：`control_transfer` / `uvc_set_cur` / `uvc_get_cur` / `uvc_get_def` / `bulk_in`。
    host 层 `UsbDevice`(libusb) 实现它；Android 端各自实现一份（`libusb_wrap_sys_device` 或纯 Java 后端）。
  - 帧组装：`UvcRawFrameAssembler` / `UvcMjpegFrameAssembler`。
  - RGBD 配对：`RgbdFramePairer` / `uvc_frame_midpoint_ns`。
  - depth/light-ir：`p100r3_depth_*` helper、`process_p100r3_depth_frame` / `process_p100r3_light_ir_frame`。
  - XU payload 生成与改写：`make_p100r3_*` / `patch_p100r3_*` / `parse_xu_payloads` / `refresh_master_time_sync_payloads`。
  - 协议编排（吃 `IUvcDevice&`）：`replay_xu_payloads` / `apply_p100r3_depth_controls` /
    `negotiate_uvc_stream` / `master_keepalive_loop`。
  - `usb_error_name`（libusb-free，自带错误码表）/ `usb_id_string`。
  - `namespace detail`：跨 TU 共享的底层 helper（UVC payload 解析、帧信息组装、le 读写、hex 等）。
- `gomob_berxel_portable.cpp` — 上述实现，**只 include 标准库，不含任何 `libusb`/`<fstream>`/`<filesystem>`**。

## 边界规则

- portable 层只做协议与数据逻辑；真正的 USB IO（打开设备、claim、bulk 循环线程、文件落盘）留在
  `native/berxel/host/`（Linux）或 Android JNI 层。
- 编译期硬保证：`scripts/berxel-host-test.sh` 把纯逻辑单测（raw/mjpeg assembler、pairer、depth
  processing、payload）**只链 `portable.cpp`、不链 libusb**；链接通过即证明零 libusb 依赖。

## 验证

```bash
scripts/berxel-host-test.sh         # 单测；纯逻辑组 portable-only 编译
scripts/berxel-host-gui.sh --build-only
./dev.sh harness berxel_depth_parity
```
