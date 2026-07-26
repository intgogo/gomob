# eYs3D / VINCreator 原厂运行库

本目录保存 RS-D550 深度与 HLSD8 彩色相机的原厂 Android 运行库，按 ABI 原样打包。

双相机必须使用不同 SONAME 的两套 UVC / libusb 栈：

- RS-D550：`libUVCCamera.so → libuvc.so → libusb100.so`
- HLSD8：`libuvc1.so → libusb1001.so`，仅调用标准 UVC C API，不加载 `libUVCCamera1.so`

这与 VINCreator 的生产结构一致，避免两颗相机并发初始化和事件处理共享同一个老
`libusb100` 全局实例。`libuvc1.so` 还依赖同后缀的 `libjpeg-turbo15001.so`，三库必须成组投放。

生产包禁止保留抓包诊断层：`libusb100.so` 必须是 VINCreator 原厂库，不能依赖或转发到
`libusb100real.so`，运行日志也不能出现 `VINSHIM`。原厂文件 SHA-256：

- `arm64-v8a/libusb100.so`：`e5935dda4f4f8c8c356938241b4c5ce492df3fe2d184b2906a76994d0b3609f6`
- `armeabi-v7a/libusb100.so`：`a6cfb330dceb5e16a5e43ed0110881e2d3420908f63be75e55fcc8bc05363cd5`
