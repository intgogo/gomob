# Berxel Android SDK 投放约定

本目录是 **Berxel 官方 Android SDK** 文件的投放点，**二进制不进 git**
（见根 `.gitignore`），需要把厂商发的版本按下面的布局放进来。

## 目录布局

```
third_party/berxel-android/
├── libs/
│   └── BerxelSDK.jar              Java/Kotlin API 入口（约 14 MB）
├── jniLibs/
│   ├── arm64-v8a/                 8 个 .so（见下）
│   └── armeabi-v7a/               同上 8 个文件，32 位 ABI
└── docs/
    ├── Berxel Android SDK 开发文档.pdf
    └── Berxel Android SDK Developer Documentation English.pdf
```

每个 ABI 目录下需要 8 个动态库：

```
libBerxelHawk.so          相机管线
libBerxelSdk.jni.so       Java ↔ native 桥（JNI 由 SDK 内部实现）
libBerxelCommonDriver.so  公共驱动
libBerxelInterface.so     接口层
libBerxelLogDriver.so     日志驱动
libBerxelNetDriver.so     网络驱动（联网相机/IP 路径）
libBerxelUvcDriver.so     UVC 驱动（USB-C OTG 接 iHawk 走这里）
libopencv_java3.so        OpenCV 3 运行时（SDK 内部使用）
```

## 接入流程

1. 从厂商发布包（参考 `/root/WindowsR/berxel/BerxelSDK-Android-9.9.190/`）
   把 `lib/BerxelSDK.jar` 复制到 `libs/`
2. 把 `lib/<abi>/*.so` 复制到 `jniLibs/<abi>/`（8 个全要，缺一个就 dlopen 失败）
3. 把 `Document/*.pdf` 复制到 `docs/`（可选，方便本地查 SDK API）
4. `core:native-bridge` 已在 `build.gradle.kts` 配置：
   - `implementation(files("$rootDir/third_party/berxel-android/libs/BerxelSDK.jar"))`
   - `sourceSets["main"].jniLibs.srcDir(...)` 把上面 jniLibs/ 接进 APK
5. App `usb_device_filter.xml` 把 0x0000/0x0000 占位换成 Berxel 真实 VID/PID

## 重要：实际 SDK 形态 ≠ 早期假设

**早期 README 假设的是 ".aar + 暴露 C++ 头给业务链接 libberxel.so"**，
厂家实际发的是 **JAR + 内部 JNI**。差异：

| 维度 | 早期假设（已废） | 实际 SDK |
|------|----------------|---------|
| 业务侧入口 | 我们写 C++ 调 BerxelHawk\*.h | Kotlin 直接 import Java 类 |
| 依赖形式 | flatDir AAR | files(...) JAR |
| .so 数量 | 1 个 libberxel.so | 8 个分工的 .so |
| C++ 头 | 公开给业务 | SDK 私有，业务无需关心 |

因此：
- `settings.gradle.kts` 不再需要 `flatDir { ... aar }` 块
- `native/CMakeLists.txt` 不再尝试找 `BERXEL_SDK_DIR/include/BerxelHawk*.h`
- `core:native-bridge` 走 Kotlin → Berxel Java API → Berxel JNI 这条线，
  我们自己的 `gomob_native.so` **不**链接 Berxel 的 .so

## 退化路径

`libs/BerxelSDK.jar` 不存在时，`core:native-bridge` 仍可编译（jar 依赖是
"如果存在就加进来"），但运行时调用 Berxel API 会崩 ClassNotFoundException。
对应的 feature 应该用 `core:common` 的 capability 探测后给出兜底提示。

## 参考

- Windows 版 SDK：本机 SMB 挂载点 `/root/WindowsR/berxel/sdk/`（含 C++ 头、
  设计文档 `VIN_RGBD_Rectification_Design.md` / `HD_RGB_Texture_Projection_Design.md`）
- Android 版 SDK 发布包：`/root/WindowsR/berxel/BerxelSDK-Android-9.9.190/`
- 详细架构对接：`docs/architecture/01-depth-camera-integration.md`
