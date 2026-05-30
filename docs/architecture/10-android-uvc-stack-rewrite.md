# 10. Android UVC Stack 重写（替换 Berxel Android SDK 的 native USB 层）

> 起始 2026-05-26。前置：[01 深度相机集成](01-depth-camera-integration.md)、[03 JNI 边界](03-jni-boundary.md)。
> 阶段：Phase 0 预研，**未进入实现**。

## 1. 背景与根因

### 1.1 现象矩阵

5 台 Android 测试机实测 P100R3 双流：

| 设备 | SoC | DUAL 结果 |
|---|---|---|
| Xiaomi 25102RKBEC | 高通 SM7435 | ❌ 462-948ms host kill |
| OnePlus PJD110 | 高通 SM8650 | ❌ 398ms |
| HONOR LOG-AN10 | 高通 SM6375 | ❌ 893ms |
| vivo V2324A / PD2324 | **联发科 MT6989** | ❌ 460ms |
| Xiaomi 2510DRK44C | 高通某型 | ✅ 稳 60s+（特例） |

跨 SoC 厂商通病。同时 single-stream DEPTH 在 4 台失败机上一致 `libusb_submit_transfer -1`。

**用户在 Windows PC 上用同一颗相机能单独跑 DEPTH single-stream + 1280×800 DUAL** —— 翻新「P100R3 firmware 通病」假设。

**2026-05-26 Linux PC + Linux SDK V2.0.190 ground truth 实测**（CentOS 9 + Intel C610 xHCI + 同 mixed-bus 拓扑 USB3 SS companion + USB2 HS 主控）：

| 配置 | 首帧 | 稳定性 |
|---|---|---|
| single-stream DEPTH 640×400@45 | 56ms | 67+ 秒稳 |
| DUAL 640×400@45 (flags=3) | 334ms | 61+ 秒稳 |
| **DUAL 1280×800@45 (flags=3)** | **404ms** | **84+ 秒稳，593% CPU 满载** |

三组在 Linux 上全跑通，跟 Android 上同硬件「single-stream DEPTH `submit_transfer -1` / DUAL 460-893ms host kill」形成绝对对照。详见 `.dev/m1.6.3-pc-baseline/report.md`。

### 1.2 真根因：Berxel Android SDK 内嵌的老 USB stack

二进制对比 Linux SDK V2.0.190 vs Android SDK 9.9.190：

| 维度 | Linux SDK | Android SDK |
|---|---|---|
| libusb | 动态链接系统 `libusb-1.0.so.0`（主线 mature） | 静态链接，含 `android_usbfs_backend`（自家移植） |
| libuvc | 自实现 UVC 协议（路径里无 libuvc 痕迹） | `libuvc-0.0.7` Android port（saki4510t 系老 fork，2015 年版本） |
| 构建路径 | `/home/allen/Desktop/code/...` Linux 开发者 | `E:/code/Hawk100_2/3rdparty/libuvc_android/...` Windows 开发者 |
| 错误字符串 `"Unable to negotiate streaming format"` | 不存在 | **存在** ← 跟实测报错对应 |

→ Linux SDK 跟 Android SDK **不是同一份代码**，是两套独立 USB/UVC 实现栈。Linux 端走 mature libusb-1.0 标准路径稳定；Android 端是 Windows 开发者拼的 `libuvc_android` (libuvc-0.0.7 + 内嵌 libusb usbfs backend)，在 stream control 协商 + double-endpoint BULK 上有实现 bug。

### 1.3 订正之前所有错误假设

参考 [[finding-p100r3-dual-endpoint-host-kill-2026-05-18]] 同步订正。

- ❌ ~~"P100R3 firmware 双 BULK endpoint 跨 host 通病"~~ — PC 能跑反证
- ❌ ~~"高通 dwc3+xhci+UCSI BSP 通病"~~ — 联发科 MT6989 也死证伪
- ❌ ~~"firmware single-stream 不激活 DEPTH endpoint"~~ — PC single-stream DEPTH 能跑反证
- ✅ **新真因**：Berxel Android SDK 内嵌的 libuvc-0.0.7 + android_usbfs_backend 实现质量问题

## 2. 方案选型

### 2.1 已排除路径

- **直接搬 Linux SDK .so 到 Android**：Linux ARM64 .so 是 GNU/Linux glibc 二进制（`NEEDED: libc.so.6, libstdc++.so.6, libusb-1.0.so.0, RPATH:/home/adam/...`），跟 Android bionic libc + libc++_shared.so ABI 完全不兼容。
- **找 BSP 特例手机**：5 测 1 活，盲选无法预测，期望低。
- **换深度相机**：放弃 P100R3，沉没成本大。

### 2.2 已选路径：A.2 自己 port 用现代 USB stack 重写

用 **modern libusb-1.0 + pupil-labs/libuvc** 在 Android NDK 重新实现 Berxel SDK 的 native USB stack，替换原版 libBerxelSdk.jni.so + 关联 .so。

### 2.3 加速通道已关闭

**2026-05-26 Berxel 回复无支持精力**，拿不到 Linux SDK 源码，只能靠自 port 突破。工作量 1-2 个月成为基线，不再有 "1-2 周拿源码 NDK 重编" 的备选。这把 Phase 0 反编译 + USB trace 抓取从"可选加速"升级为"必做基础"——所有后续 phase 都依赖 Phase 0 产出的协议表。

## 3. 现有 Android SDK 架构分析

### 3.1 调用链

```
Java (com.berxel.berxelInterface.api.admitmanager.BerxelHawkFunction class, 在 BerxelSDK.jar)
   ↓ JNI
libBerxelSdk.jni.so  ← 53 个 Java_***_berxel* 入口（真正的替换 ABI 边界）
   ↓ dlopen + dlsym 取
libBerxelHawk.so  ← 自定义 172 个 berxelXXX C 函数（业务 + USB IO 合一）
   ↓ 内部静态链接
   libuvc-0.0.7 Android port + libusb android_usbfs_backend
   ↓
/dev/bus/usb/<bus>/<dev> (Android usbfs，需要 AOA / UsbDeviceConnection.fd 拿)
```

vs Linux SDK 分层架构：

```
C++ App
   ↓ link
libBerxelHawk.so (业务层，引用 158 个 berxel* C ABI)
   ↓
libBerxelUvcDriver.so (USB IO，定义 berxel* C ABI + 内部 berxel::BerxelHostProtocol*)
   ↓ NEEDED
系统 libusb-1.0.so.0
```

### 3.2 替换边界

**最干净的替换粒度 = 53 个 JNI 入口函数**（`Java_com_berxel_berxelInterface_api_admitmanager_BerxelHawkFunction_*`）。Java 层 BerxelSDK.jar 不动，所有 Java 调用走新的 libBerxelSdk.jni.so → 新 native stack。

核心 10 个 JNI 函数承载 80% 功能：
- `berxelInit / berxelInitWithLogPath / berxelDestroy`
- `berxelGetDeviceList / berxelOpenDeviceByFd`
- `berxelOpenStream / berxelCloseStream`
- `berxelReadFrame / berxelReleaseFrame`
- `berxelSetStreamFlagMode / berxelGetDeviceParams`

其余 43 个是 getter/setter（exposure / gain / 各种 enable flag），分阶段补完。

## 4. Berxel 私有 USB 协议结构（Linux SDK 反编译初步）

Linux libBerxelUvcDriver.so 暴露 5 个协议实现类：

| 类名 | 适用相机 |
|---|---|
| `BerxelHostProtocol` | 基类抽象 |
| `BerxelHostProtocolHV3` | 旧型号 |
| `BerxelHostProtocolSonix` | **P100R3 走这个**（companion chip 是 Sonix XU 系） |
| `BerxelHostProtocoliTOF` | iToF 系列 |
| `BerxelHostProtocolGMSL` | GMSL serdes 接口 |

关键函数（mangled symbol 已识别）：
- `BerxelUVCLinux::berxelUVCSendControl(BerxelCmdType, ...)` — 统一 USB control 发包入口
- `BerxelHostProtocolSonix::ExecuteCMD / ExecuteCMD2 / ExecuteCMD3 / ExecuteCMD_Ex` — 不同 cmd 形态
- `XU_Set_Cur / XU_Get_Cur` — 标准 UVC Extension Unit SET_CUR/GET_CUR

→ **协议可逆向**，不是黑盒 firmware blob。具体 cmd code、wValue/wIndex、payload 格式待 Ghidra/IDA 反编译。

## 5. 目标架构

```
Java (com.berxel.berxelInterface.api.admitmanager.BerxelHawkFunction, 原 jar 不动)
   ↓ JNI (函数签名兼容)
libBerxelSdk.jni.so  ← 新实现，53 个 JNI 入口
   ↓
libGomobBerxelNative.so  ← 新 C++ 库，实现 53 个 JNI 背后的逻辑
   ├── BerxelHostProtocolSonix 复现（USB control + UVC XU 操作）
   ├── 业务层（depth pixel unpack / 标定 / 点云）— 从 Linux SDK 反编译还原
   └── 依赖
       ├── libusb-1.0.27（主线，NDK 交叉编译，android_usbfs_backend）
       ├── libuvc 1.0.x（pupil-labs/libuvc，active fork）
       └── 现有 Berxel SDK 工厂参数文件解码（params.bin/params100Q.bin/params137.bin）
```

## 6. 风险

| 风险 | 缓解 |
|---|---|
| Linux SDK 业务层（depth unpack / 出厂标定参数读取）反编译困难 | Berxel 无支持精力（2026-05-26 已确认），必须靠 Ghidra/IDA 静态分析 + Wireshark/usbmon USB packet capture 双管齐下；优先反编译 `BerxelHostProtocolSonix::ExecuteCMD*` 与 depth pixel unpack 路径 |
| Sonix XU 私有 vendor command 完全黑盒 | Wireshark + usbmon 抓 Linux PC 上 Sensor Studio 的 USB traffic 直接拿 ground truth |
| pupil-labs/libuvc 跟 Berxel 协议不兼容 | libuvc 只处理标准 UVC 协议；Berxel 私有 XU 命令走 libusb 直发 control transfer，不通过 libuvc |
| Android UsbDeviceConnection.fd 移交给 libusb 的兼容性 | 已有验证路径：用户空间用 `libusb_wrap_sys_device(ctx, intptr_t fd, ...)` 接管 Android 给的 fd |
| 双流 host kill 是否真能解决 | 假设：现代 libusb 在 endpoint claim / alt-setting / streaming start 顺序上严格遵循 USB 3.x 规范；老 libuvc-0.0.7 的错误是触发因。如果实测后双流仍死 → 退到 P100R3 firmware patch 路径，但单流 DEPTH 应该已解 |

## 7. 实施 phase 索引（详见 TODO.md M1.6 系列）

- **Phase 0**：USB 协议反编译 + Linux PC 端实测取 ground truth
- **Phase 1**：Android NDK 编译 libusb-1.0 + pupil-labs/libuvc
- **Phase 2**：Sonix XU 协议复现 + 核心 streaming 路径（10 个 JNI）
- **Phase 3**：完整 53 个 JNI 覆盖 + Berxel SDK 工厂参数兼容
- **Phase 4**：替换原 Android SDK，gomob `third_party/berxel-android/` 切到新实现
- **Phase 5**：5 台测试机回归（COLOR / DEPTH / DUAL）+ harness 验收

## 8. 关联

- [[finding-p100r3-dual-endpoint-host-kill-2026-05-18]]（同步订正根因表述）
- [[finding-p100r3-hardware-decomposition-2026-05-18]] — Sonix XU 系来源
- [[reference-berxel-sdk]] — SDK 资源位置
- [01 深度相机集成](01-depth-camera-integration.md) — Android 端调用栈现状
- [03 JNI 边界](03-jni-boundary.md) — gomob 的 NativeBridge 设计
