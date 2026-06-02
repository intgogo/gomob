# VINCreator APK 逆向参考资产

`com.vin.uvc`（应用名 **VINCreator**）的 APK 与解压内容，作为 UVC / 深度相机栈逆向参考。
**不是 Berxel，是 eYs3D / Etron 体系**（主类 `com.esp.uvc.*` + `com.jiangdg.demo.*`，
.so 为 `libESPDI/libeysov/libUVCCamera`），详见 [[finding_berxel_sdk_internals_2026-05-07]]。

## 出处

| 项 | 值 |
|----|----|
| 来源设备 | vivo PD2324 / V2324A（测试机池，见 `project_test_phone.md`） |
| 设备路径 | `/data/app/.../com.vin.uvc-.../base.apk` |
| 抓取方式 | `adb pull base.apk`（单 base.apk，无 split） |
| 抓取日期 | 2026-05-31 |
| versionName / versionCode | `1.4.11` / `126` |
| minSdk / targetSdk / compileSdk | 24 / 34 / 34 |
| APK SHA-256 | `452773dfbbad675e6809de38841bf03a3efbe877c33724128f36ac0a2e0740b5` |
| APK 大小 | 107,771,718 B (~103 MiB) |
| launchable-activity | `com.esp.uvc.main.CameraActivity` |
| native-code | `arm64-v8a`、`armeabi-v7a` |
| uses-feature | `android.hardware.usb.host` |

## 目录结构

- `VINCreator-1.4.11-vc126.apk` — 原始 APK（**gitignored**，可随时重抓）。
- `AndroidManifest.txt` — `aapt2 dump xmltree` 出的可读 manifest（**入库**，259 行）。
- `extracted/` — `unzip` 出的全部 APK 内容（**gitignored**，~120 MiB，厂商二进制）。
- `README.md` — 本文件（**入库**）。

> 体积大且可从 APK 重新解压的二进制（`*.apk` / `extracted/`）已在根 `.gitignore` 忽略；
> 仅 README + 可读 manifest 入库，保留出处与清单不污染仓库。

## 重新解压

```bash
export PATH=/opt/android-sdk/build-tools/35.0.0:$PATH
APK=tests/vincreator-apk/VINCreator-1.4.11-vc126.apk
unzip -q -o "$APK" -d tests/vincreator-apk/extracted
aapt2 dump xmltree --file AndroidManifest.xml "$APK" > tests/vincreator-apk/AndroidManifest.txt
```

## 对 gomob 的价值：native UVC 栈（M1.6 重点）

`extracted/lib/<abi>/` 共 23 个 .so，其中与 **Android UVC stack rewrite（M1.6，
`docs/architecture/10-android-uvc-stack-rewrite.md`）** 直接相关的：

| .so | 作用 | 参考点 |
|-----|------|--------|
| `libuvc.so` / `libuvc1.so` | libuvc（UVC 抽象层） | 对照我们要换的现代 libuvc，看它们的版本与 transfer 实现 |
| `libUVCCamera.so` / `libUVCCamera1.so` | jiangdg AndroidUSBCamera JNI 封装 | UVC stream 开流/控制的 Java↔native 桥参考 |
| `libusb100.so` / `libusb1001.so` | libusb-1.0 | 它们用的 libusb android backend 是否现代化（我们死锁根因就在老 backend） |
| `libESPDI.so` / `libeysov.so` | eYs3D 相机 SDK / 取流核心 | 厂商如何在 Android 上跑双流（L+R / L'+D），对照 P100R3 双流问题 |
| `libDepthMSR.so` / `libdepthfilter.so` / `libSwPostProc.so` | 深度重建 / 滤波 / 后处理 | 结构光/双目深度软件管线参考 |
| `libcreator_jni.so` / `libnativelib.so` / `libquaternion.so` | App 自有 JNI / 姿态 | — |
| `libonnxruntime.so` / `libonnxruntime4j_jni.so` | ONNX Runtime | 配合 `assets/model/yolo-obb.onnx`（YOLO-OBB，39 MB）做 ROI/物体检测 |

## assets 关键项

- `assets/CameraModes/camera_modes_*.csv` — 各传感器(171/173/174/193/206/550)分辨率-格式-帧率模式表，
  含 `L+R` 双目、`L'+D scale_down` 深度模式定义（MJPEG/YUYV，2592×1944 起）。
- `assets/param/` — `config.ini`（图像明暗分类阈值，中文注释）、`config.xml`、
  `VIN_*.bin`（标定/固件参数 blob）、`VinSoftSettings.bin`。
- `assets/QualityCfg/*_DM_Quality_Register_Setting.cfg` — 各传感器(EX8036/8037/8052/8053/HYPATIA…)深度质量寄存器配置。
- `assets/model/yolo-obb.onnx` — YOLO 定向框检测模型。
- `assets/kernels.cl` — OpenCL kernel（GPU 深度后处理）。

## 逆向分析报告

完整分析见 **[REVERSE-ENGINEERING.md](REVERSE-ENGINEERING.md)**（jadx 反编译 4518 java + binutils 分析 23 so，
10 个 subagent 交叉验证）。一句话结论:VINCreator 是 **eYs3D/Etron**(非 Berxel)的 UVC 深度相机 App,
是 gomob M1.6 UVC 重写的可抄蓝本;它用 **libusb 1.0.19 经典 android_usbfs 老 backend** 却能跑双流深度,
**旁证 P100R3 双流死的真因是 OTG 供电不足而非老 backend**(见 [[finding_powered_hub_unblocks_vivo_dual_stream_2026-05-27]])。

反编译工具与产物(均 gitignored):
- jadx 1.5.5 + apktool 3.0.2 在 `.dev/tools/`
- 反编译 java 源在 `.dev/vincreator-jadx/sources/`(REVERSE-ENGINEERING.md 的 file:line 指此树)
- 重新生成:`.dev/tools/jadx/bin/jadx -j 12 --no-res -d .dev/vincreator-jadx <apk>`

## 仍遗留待查

- 用 apktool 解 `res/xml/device_filter.xml` 拿精确 vid/pid;解析 `param/VinSoftSettings.bin` / `VIN_*.bin`。
- 反汇编 libuvc.so 看 open→startPreview 是否对深度流发 XU/keepalive,与 P100R3 master XU5 keepalive 对比。
