# Berxel Android SDK 投放约定

本目录是给 **Berxel 官方 Android SDK** 文件保留的占位，文件本身不进 git
（见根 `.gitignore`），需要把厂商发的二进制按下述路径放进来。

## 目录布局

```
third_party/berxel-android/
├── aar/                    放厂商 .aar 整包（如有）
│   └── berxel-sdk-x.y.z.aar
├── include/                C++ 头文件（移植自 Windows 版的 BerxelHawk*.h；
│                            如官方 Android 头与 Windows 头不同则以官方版为准）
│   ├── BerxelHawkContext.h
│   ├── BerxelHawkDevice.h
│   ├── BerxelHawkFrame.h
│   ├── BerxelHawkDefines.h
│   └── BerxelHawkPlatform.h
├── jniLibs/                每个 ABI 一份动态库
│   ├── arm64-v8a/libberxel.so
│   └── armeabi-v7a/libberxel.so
└── docs/                   厂商发的 Android 端开发文档（PDF/MD）
```

## 接入流程

1. 把厂商发的 SDK 解压到本目录，确保按上方布局摆放。
2. 在 root `settings.gradle.kts` 已配置 `flatDir { dirs("third_party/berxel-android/aar") }`，AAR 自动可见。
3. `core:native-bridge/build.gradle.kts` 视情况 `implementation(name = "berxel-sdk-x.y.z", ext = "aar")`。
4. `native/CMakeLists.txt` 已检测 `BERXEL_SDK_DIR/include/BerxelHawkContext.h` 是否存在，
   存在就自动 `-DGOMOB_HAS_BERXEL=1` 并把 `libberxel.so` 接进 `gomob_native` 链接。
5. App `usb_device_filter.xml` 把 0x0000/0x0000 占位换成 Berxel 真实 VID/PID。

## 退化路径

厂商 SDK 没到位之前，编译期通过 `GOMOB_HAS_BERXEL` 宏自动跳过 berxel 调用；
`native/depth/` 走通用针孔模型 + UVC 直读路径，不阻塞工程编译和模拟跑通。

## 参考

Windows 版 SDK 在本机 SMB 挂载点 `/root/WindowsR/berxel/sdk/`，
头文件、用法、标定流程详见 `docs/architecture/01-depth-camera-integration.md`。
