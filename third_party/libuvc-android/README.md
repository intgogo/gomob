# libuvc-android — Android NDK 交叉编译的 libuvc (主线 / pupil-labs)

## 版本与来源

- **源**：`https://github.com/libuvc/libuvc`（与 pupil-labs 同源 active fork）
- **build**：`cmake + NDK toolchain` (NDK r27.2.12479018)
- **依赖**：本仓 `third_party/libusb-android` (NEEDED: `libusb-1.0.so`)
- **SONAME**：`libuvc.so`

## 跟 Berxel Android SDK 9.9.190 内嵌版本的差异

| 维度 | Berxel SDK 9.9.190 | gomob third_party 版本 |
|---|---|---|
| libuvc 版本 | 0.0.7 + Windows 开发者 2015 Android port | 主线 active fork（含 frame-mjpeg / probe-commit 修正） |
| stream_ctrl 协商 | 老版本，可能 bug 触发 `Unable to negotiate streaming format` | 主线已修复多个老 fork issue |
| BULK 处理 | 未知（黑盒） | 主线，跟 Linux 用户空间 libuvc 行为一致 |

## 用途

- **标准 UVC VS_PROBE / VS_COMMIT 协商**：跟 USB trace 抓到的主控 246 次 wValue=0x0100 + 单次 wValue=0x0200 对应
- **frame mode 枚举**：`uvc_get_stream_ctrl_format_size` 替代手写 probe
- **BULK 数据通道**：`uvc_start_streaming` + frame callback（depth + color 通道复用）
- **不用于**：Berxel 私有 Sonix XU vendor command（这些走 `libusb_control_transfer` 直发，绕开 libuvc）

## 关键 API（已 verified 导出）

```
uvc_init / uvc_open                        ✅
uvc_get_stream_ctrl_format_size            ✅
uvc_probe_stream_ctrl                      ✅  UVC SET_CUR VS_PROBE
uvc_start_streaming / uvc_stop_streaming   ✅
uvc_stream_open_ctrl / uvc_stream_start    ✅
uvc_stream_start_iso                       ✅
```

## 重建命令

```bash
cd .dev/m1.6.4-build/libuvc-build-arm64  # 或 -armv7
PKG_CONFIG_PATH=.dev/m1.6.4-build/staging/arm64-v8a/lib/pkgconfig \
  cmake .dev/m1.6.4-build/libuvc-src \
  -DCMAKE_TOOLCHAIN_FILE=/opt/android-sdk/ndk/27.2.12479018/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-21 \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_EXAMPLE=OFF -DBUILD_TEST=OFF \
  -DCMAKE_FIND_ROOT_PATH=.dev/m1.6.4-build/staging/arm64-v8a \
  -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH
make -j$(nproc)
```
