# libusb-android — Android NDK 交叉编译的 libusb-1.0 主线

## 版本与来源

- **源**：`https://github.com/libusb/libusb` tag `v1.0.27`
- **build**：`ndk-build` (Android NDK r27.2.12479018) 使用 libusb 仓库自带 `android/jni/Android.mk`
- **backend**：`android_usbfs_backend`（libusb 主线内置，不是私有 fork）
- **SONAME**：`libusb-1.0.so`（patch 后跟 pkg-config / `-lusb-1.0` 命名一致）

## 跟 Berxel Android SDK 9.9.190 内嵌版本的差异

| 维度 | Berxel SDK 9.9.190 | gomob third_party 版本 |
|---|---|---|
| 来源 | Windows 开发者 2015 年 fork | libusb 主线 v1.0.27 (2024+) |
| backend | 自家定制 `android_usbfs_backend` | 主线 `android_usbfs_backend` (mature) |
| ABI 验证 | 4 死 1 活（DUAL 460-893ms host kill） | 待 M1.6.6 集成后验证 |

## 关键 API（已 verified）

```
libusb_init / libusb_init_context           ✅
libusb_set_option(NO_DEVICE_DISCOVERY)      ✅  Android 必走
libusb_wrap_sys_device(ctx, fd, &handle)    ✅  接管 Java UsbDeviceConnection.fd
libusb_control_transfer                     ✅  Sonix XU vendor cmd 通过此 API
libusb_bulk_transfer                        ✅  depth frame BULK 通道
libusb_claim_interface                      ✅
libusb_open / libusb_open_device_with_vid_pid ✅
```

## 真机 smoke test（vivo PD2324 / Android 15 / MT6989）

`.dev/m1.6.4-build/smoke/usb_smoke.c` 在 vivo 上跑通：

```
[OK] libusb_set_option(NO_DEVICE_DISCOVERY)
[OK] libusb_init
[OK] libusb version: 1.0.27.11882
[OK] libusb_exit
[PASS] libusb on Android ready for UsbDeviceConnection.fd integration
```

## 集成入口（M1.6.6 时由 native 模块使用）

Android 上不能 libusb 自己枚举（shell/app user 无权访问 `/sys/bus/usb`）。标准模式：

```c
libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
libusb_init(&ctx);
// fd 从 Java UsbDeviceConnection.getFileDescriptor() 经 JNI 传入
libusb_wrap_sys_device(ctx, (intptr_t)fd, &dev_handle);
libusb_claim_interface(dev_handle, interface_num);
libusb_control_transfer(dev_handle, 0x21, 0x01, (0x19 << 8), (0x03 << 8), buf, 512, 1000);
// ↑ Sonix XU SET_CUR selector 0x19, unit 3 — 跟 USB trace ground truth 一致
```

## 重建命令（如需重 build）

```bash
cd .dev/m1.6.4-build/libusb-src/android/jni
# 已 patch: shared LOCAL_MODULE → libusb-1.0（让 SONAME=libusb-1.0.so）
/opt/android-sdk/ndk/27.2.12479018/ndk-build \
  APP_ABI="arm64-v8a armeabi-v7a" \
  NDK_PROJECT_PATH=. \
  APP_BUILD_SCRIPT=Android.mk \
  NDK_APPLICATION_MK=Application.mk \
  APP_ALLOW_MISSING_DEPS=true
# 产物在 libs/<abi>/libusb-1.0.so
```
