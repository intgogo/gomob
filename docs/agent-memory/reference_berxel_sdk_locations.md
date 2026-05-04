---
name: Berxel SDK 资源位置
description: Windows 端 SDK 二进制 / 头文件 / 样例 / VIN 设计文档的 SMB 挂载点路径
type: reference
---

# Berxel SDK 资源位置

Windows 端 SDK 已挂载到本机 `/root/WindowsR`（CIFS 挂载到 `//192.168.9.187/backup_release`）。

## 关键路径

| 内容 | 路径 |
|------|------|
| SDK 根 | `/root/WindowsR/berxel/sdk/` |
| C++ 头文件 | `/root/WindowsR/berxel/sdk/Include/BerxelHawk{Context,Device,Frame,Defines,Platform}.h` |
| Windows .lib (x64) | `/root/WindowsR/berxel/sdk/Lib/x64/` |
| 样例工程 | `/root/WindowsR/berxel/sdk/Samples/Build/HawkExample.sln` |
| 关键样例：Mix HD RGBD | `/root/WindowsR/berxel/sdk/Samples/HawkMixHDColorDepth/` |
| VIN 工具：命令行 demo | `/root/WindowsR/berxel/sdk/Tools/VinRectifyDemo/vin_rectify_demo.cpp` |
| VIN 工具：Qt 界面 | `/root/WindowsR/berxel/sdk/Tools/VinRectifyGui/src/` |
| 设计文档：VIN RGBD 正射还原 | `/root/WindowsR/berxel/sdk/docs/VIN_RGBD_Rectification_Design.md` |
| 设计文档：HD RGB 纹理投影 | `/root/WindowsR/berxel/sdk/docs/HD_RGB_Texture_Projection_Design.md` |
| 设计文档：相机设置审计 | `/root/WindowsR/berxel/sdk/docs/Camera_Settings_Audit_20260425.md` |
| 设计文档：Mix HD probe | `/root/WindowsR/berxel/sdk/docs/MixHD_1280x800_probe_20260424.md` |
| Berxel 已有 Android APK | `/root/WindowsR/berxel/sdk/Models/VINCreator_standard_target34_v1.4.11/` |
| 标定测试历史输出 | `/root/WindowsR/berxel/sdk/VinRectify*/` |

## 关键事实

- 当前 iHawk 072 实测：Mix HD `1280×800 @8fps` 可设置但读帧 `ret=-11`，自动降级 `640×400`
- 单独彩色流可达 `1920×1080`，但**不是**当前正射还原使用的配准 RGB
- 彩色流支持分辨率：`640×400 / 1280×800 / 1920×1080`（**不**是常规 1280×720）
- Windows 工程需 VS2017 v141 工具集；Lib 拷贝路径见 `/root/WindowsR/berxel/sdk/README.md`

## 在 gomob 中的使用方式

不直接拷贝二进制（Windows .lib 在 Android 上无意义）。本仓 `third_party/berxel-android/`
等待 Berxel 厂家发**Android 端**的 AAR + .so + Android 头文件后投放。

Windows 端的 **设计文档**、**样例代码逻辑**、**标定数据**、**已知问题**对 Android 端
设计有直接参考价值，写设计文档时显式引用上述路径。
