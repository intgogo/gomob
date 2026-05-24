# P100R3 Android SDK 深度流假成功

## Why

2026-05-14 在 2510DRK44C + Berxel iHawk100RS/P100R3 上实测：

- Android SDK `getDeviceLists()` 同时枚举 `0x0603:0x001f` 主节点和 `0x3558:0x1012` companion UVC 节点。
- 显式 `openDevice(callback, deviceInfo)` 传主节点或 companion 节点后，`setStreamFlagMode` 都会返回 `-3`，frame mode 列表为空。
- 只有无参 `openDevice(callback)` 让 SDK 自己选择内部 UVC 句柄时，才能 `setStreamFlagMode` / `setFrameMode` / `startStreams`，Java 层 `startStreams rc=0`。
- 但 native log 对 depth 显示 `uvc_probe_stream_ctrl Unable to negotiate streaming format` 与 `libusb_submit_transfer failed: -1`，随后仍打印 `Open depth stream succeed`。
- App 侧 `readDepthFrame()` 连续返回 `null`，color 可出首帧；问题不是 Compose 预览或 13I_3D mm 转换。

2026-05-18 在 25102RKBEC / Xiaomi HyperOS 3 / Android 16 + 同一 P100R3（SN `P100R3YB5C16D1B065`）复测：

- `COLOR_ONLY + SINGULAR + 640×400@15` 可稳定出首帧：`COLOR first frame 640x400`。
- `DEPTH_ONLY + SINGULAR + 320×200@15` 仍在 native 层报 `uvc_probe_stream_ctrl Unable to negotiate streaming format` 与 `libusb_submit_transfer failed: -1`；Java `startStreams rc=0`，但 `readDepthFrame()` 连续 null。
- `DUAL + MIX_QVGA + color 640×400@15 + depth 320×200@15` 比 640×400@15 更保守，但依旧在 `startStreams rc=0` 后约 0.4-0.9s 触发两个 USB 节点物理重枚举，且没有首帧。
- 因此“没效果”不是 UI 黑屏，而是 depth UVC negotiation 失败；双流因包含 depth 进一步触发 USB 端口 reset / 设备重枚举。OTG 供电/反向供电应打开，但 color-only 已能出帧，说明根因不是完全无供电。

同日接外部供电 Dell USB-C dock 复测：

- Android 已记住 `0x0603:0x001f` 和 `0x3558:0x1012` 两个 Berxel 节点授权；插拔不再弹权限框是正常现象。
- Dell dock 会额外枚举 `0x413c:0xb06e/0xb06f` HID 节点和 Realtek LAN。Berxel SDK 9.9.190 的 `BerxelHawkUsbManager.getUsbDeviceList()` 会在按 VID/PID 筛选前对所有 USB 设备调用 `UsbDevice.getSerialNumber()`，HyperOS 对未授权 dock 节点抛 `SecurityException`，导致 `openDevice()` 失败。
- 已扩展 `patches/berxel-android/BerxelJarPatch.java`：将 `getUsbDeviceList()` 内的 `UsbDevice.getSerialNumber()` 替换为 `null` 日志值，仅保留 VID/PID 筛选。重打 jar 后 SDK 可越过 dock 干扰并打开 P100R3。
- 外供 + jar patch 后，`COLOR_ONLY + 640×400@15` 仍稳定出首帧；`DEPTH_ONLY + 320×200@15` 可 `startStreams rc=0`，但 30 次 `readDepthFrame()` timeout/null；`DUAL + QVGA_15` 可 `startStreams rc=0`，随后约 463-616ms 内两个 Berxel USB 节点断开重枚举，无首帧。
- 结论：外部供电和 dock serial patch 解决了“权限/扩展坞枚举”阻塞，但没有解决 depth 流不出帧；depth 路径仍需按 SDK/firmware/UVC negotiation 继续定位。

## How to apply

- P100R3 Android 端开流前要确认主节点和 companion 节点都有 USB fd 权限；真正 `openDevice` 必须用无参入口，不要显式传任一 `deviceInfo`。
- 排查 depth 黑屏时必须同时看 app `BerxelService` 日志和 native `BerxelAndroid/libuvc` 日志；仅看 `startStreams rc=0` 会误判。
- `MIX_HD` 下 P100R3 实测 color/depth 都是 `1280×800`，双流共同可用 fps 至少有 `5/10/15/20/25/30/45`，不是 color `1920×1080`。
- Android 真机调试先用 `COLOR_ONLY` 确认 SDK/USB 基础链路，再测 `DEPTH_ONLY`；不要一开始用双流判断 UI 是否可用。
- 外接 USB-C dock 时，若日志出现非 Berxel 节点 `SecurityException`，先确认 `BerxelJarPatch` 已包含 `getUsbDeviceList()` serial 规避，并重装包含 patched jar 的 APK。
- 双流开流后 2s 内 USB 断开且无首帧时，应停止自动重试并提示检查 OTG 供电/线材/带电 Hub，避免把手机 USB 端口拖进无限重枚举。
- 若 depth 仍无首帧，下一步应针对 Berxel Android SDK / firmware / UVC negotiation 定位，不要在 UI 或 depth16 colormap 上继续调参。
