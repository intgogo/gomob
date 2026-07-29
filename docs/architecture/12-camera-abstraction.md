# 12 · 多相机驱动抽象层(Berxel P100R3 + eYs3D 并存,全自研)

> 2026-07-29 拆分订正：本文件中的 eYs3D/HLSD8 共存设计仅作历史记录。当前 gomob 的
> `core:native-bridge` 只保留 Berxel；RS-D550 + HLSD8 的唯一实现位于
> `vendor/vin-rubbing/android/vin-capture`，通过 `VinRgbdRigSession` 提供宿主复用入口。

> 状态:设计稿,2026-06-01。
> 主线约束:host-first、portable 优先、**Berxel 全程不退化**、数据契约下沉 `core:model`、JNI 边界唯一 `core:native-bridge`、**不引入任何原厂 SDK**(Berxel jar / eYs3D libESPDI/libDepthMSR 一律不链)。
> 关联:`docs/architecture/01-depth-camera-integration.md`、`docs/architecture/10-android-uvc-stack-rewrite.md`、`docs/architecture/13-eys3d-driver.md`(待建)、`docs/agent-memory/finding_vincreator_eys3d_uvc_blueprint_2026-06-01.md`、`tests/vincreator-apk/REVERSE-ENGINEERING.md`。

## 0. 第一性判断:抽象切面在哪

抽象层不是"把 Berxel 改个名让 eYs3D 套进去",而是先认清两条数据通路**本质不同**,再切在稳定接缝上。

| 维度 | Berxel P100R3(自研栈现状) | eYs3D / Etron(待自研) | 抽象判断 |
|---|---|---|---|
| USB 拓扑 | **双 USB 设备**:master `0x0603:001f` + companion `0x3558:1012` | **单 USB 设备** `0x3438:0x0206`(RS-D550,2026-06-01 实测;**非**逆向猜的 0x1E4E),双 streaming interface | 设备数不是抽象单位,**stream 数**才是 |
| 取流传输 | companion **BULK over UVC raw**(非标),master color 也 BULK | 标准 **UVC isoc/bulk 双 VS interface**,更贴 spec | 帧组装器可共用,协商参数不同 |
| 协议编排 | Sonix XU:master XU5(`wIndex=0x0500`)keepalive + companion XU3 + ASIC 寄存器 | Etron XU(`0xF4xx` ASIC 空间) | **专有不可复用**,各一套 |
| 深度来源 | companion ASIC **直出 metric**,IR+phase 交织(marker `0x0600`/`0x0500`) | **端侧软件重建**:左右目 raw → 视差 → Z | **根本不同**:P100R3 解交织反演,eYs3D 立体匹配 |
| keepalive | Novatek 设计缺陷,必须周期重放 XU5 | 无(芯片稳定) | 抽象层须可 `interval=0` 跳过 |

**切面结论**:接缝切在 **设备 I/O(`IUvcDevice`)** 与 **会话编排(`ICameraSession`)** 两层之间。

- `IUvcDevice`(已存在,平台无关)— 纯 USB transfer 抽象,Berxel/eYs3D × Linux/Android 四象限各实现,**只需迁命名空间**。
- `ICameraDriver` / `ICameraSession`(新建)— 把"枚举→open(fd)→negotiate→startStream→pollFrame→setControls→close + 能力描述"统一成厂商无关会话面。两者通过 `pollFrame` 的统一出口(metric depth16 + 可选 confidence + 可选 IR)汇合,**深度怎么来由各 driver 内部负责**。

不在 `IUvcDevice` 塞厂商概念(keepalive/交织/视差),也不把 Berxel 双设备结构强加给 eYs3D。

## 1. 抽象接口与分层落点

### 1.1 新增 `native/camera/`(唯一新 native 顶层,厂商无关)

```
native/camera/
├─ camera_device.h        # IUvcDevice 迁此(gomob::berxel → gomob::camera)
├─ camera_session.h       # ICameraDriver / ICameraSession / CameraCapabilities / CameraFrame / DepthControls
├─ uvc_frame.h            # UvcRawFrameAssembler / UvcMjpegFrameAssembler / RgbdFramePairer 迁此
├─ uvc_negotiation.h/.cpp # negotiate_uvc_stream / replay_xu_payloads / parse_xu_payloads 迁此
├─ depth_filter.h/.cpp    # TemporalFilter / FlyingPixelConfig 通用化(去 P100R3 前缀)
└─ CMakeLists.txt         # gomob_camera_core STATIC(无 libusb、无 Android,纯跨平台)
```

`IUvcDevice` 当前在 `native/berxel/portable/gomob_berxel_portable.h:336-371`,签名已完全平台无关(`control_transfer/uvc_set_cur/uvc_get_cur/uvc_get_def/bulk_in`)。迁移仅:命名空间改 `gomob::camera`,文件迁 `native/camera/camera_device.h`,Berxel/eYs3D 各 include。

核心新增接口(`camera_session.h`):

```cpp
namespace gomob::camera {

struct CameraCapabilities {                 // 上层据此定 UI 档位/是否有 conf/IR/是否端侧 metric
  std::string vendor, model;
  bool has_color=true, has_depth=true, has_confidence=false, has_ir=false;
  bool depth_is_metric_onchip=true;         // P100R3 true;eYs3D false(端侧立体重建)
  std::vector<StreamProfile> color_profiles, depth_profiles;
};

enum class CameraStreamKind { kColor, kDepthMm, kConfidence, kIr };
struct CameraFrame { CameraStreamKind kind; uint16_t width,height; int64_t host_ns,device_ns;
                     const uint8_t* data; size_t size; };  // depthMm 统一 uint16 metric

class ICameraSession {                       // 开流后的运行态
public:
  virtual bool start(const SessionCallbacks&)=0;
  virtual int  poll(CameraFrame* out, uint32_t timeout_ms)=0;
  virtual bool set_controls(const DepthControls&)=0;        // 语义控制,见 1.3
  virtual void stop()=0; virtual void join()=0;
  virtual SessionState state() const=0; virtual SessionStats stats() const=0;
  virtual ~ICameraSession()=default;
};

class ICameraDriver {                        // 无设备态的工厂 + 枚举
public:
  virtual CameraCapabilities capabilities() const=0;
  virtual std::vector<UsbId> match_usb_ids() const=0;       // 该 driver 认领的 VID:PID
  virtual std::unique_ptr<ICameraSession> open_host(UsbContext&, const SessionConfig&)=0;  // libusb
  virtual std::unique_ptr<ICameraSession> open_fd(const std::vector<int>& fds, const SessionConfig&)=0; // Android fd
  virtual ~ICameraDriver()=default;
};

}  // namespace gomob::camera
```

`open_fd` 取**一组 fd**(P100R3 传 2:master+companion;eYs3D 传 1)——双/单设备分歧的唯一逃逸口,封在 driver 内。

### 1.2 通用件复用清单(从 berxel/portable 迁出,eYs3D 复用度 ≥ Berxel)

`UvcRawFrameAssembler`/`UvcMjpegFrameAssembler`(FID/EOF/PTS)、`RgbdFramePairer`(时间戳配对)、`negotiate_uvc_stream()`(标准 probe/commit)、`replay_xu_payloads()`/`parse_xu_payloads()`、`TemporalFilter`(原 `P100R3TemporalFilter`,`FlyingPixelConfig` 参数化为 `{focal_length,image_size,grazing_angle}`)。eYs3D 是标准双 VS interface UVC,这些拿来即用。

### 1.3 语义控制(去厂商化)

`DepthControls` 用语义字段而非寄存器值:`confidence_threshold(0-1)`、`temporal_denoise`、`spatial_denoise`、`auto_exposure`、`gain`。各 driver 内部翻译成自家 XU/寄存器。**上层永不见寄存器**。

### 1.4 Berxel 实现 = adapter,不重写(不退化硬保证)

`BerxelDriver : ICameraDriver`,`match_usb_ids()={0x0603:001f, 0x3558:1012}`,`capabilities()` 填 `depth_is_metric_onchip=true, has_ir=true, has_confidence=true`。`open_host/open_fd` 内部**直接包住现有 `P100R3DualSession`**(`native/berxel/host/.../gomob_berxel_host_sdk.h`),套一层 `BerxelSessionAdapter : ICameraSession`。`P100R3DualSession::Impl`(master/companion 双设备编排、keepalive、XU replay、交织解析、`process_p100r3_depth_frame`)**一行不改**。

### 1.5 eYs3D 实现 = 全自研 host-first

```
native/eys3d/
├─ portable/  eys3d_protocol.{h,cpp}(Etron XU 0xF4xx 序列,纯数据)
│             eys3d_stereo_depth.{h,cpp}(左右目→视差→metric Z,核心自研,对标 DepthMSR 但不引)
│             eys3d_driver.{h,cpp}(EYS3DDriver:ICameraDriver / EYS3DSession:ICameraSession)
└─ host/      include/eys3d_host_sdk.h、src(libusb 绑定 EYS3DUsbDevice:IUvcDevice + 枚举)
              demo/run_eys3d.cpp(枚举→open→negotiate→双流→视差→可视化)、assets/eys3d_init.json
```

复用 `native/camera/` 全部通用件;**自研只写** `eys3d_protocol`(Etron XU 序列)与 `eys3d_stereo_depth`(立体匹配出深度——eYs3D 的灵魂,P100R3 没有的算法,等价自研重写 `libDepthMSR`)。keepalive 不实现。

### 1.6 不新增 core 模块,扩 `core:native-bridge`

`NativeBridge.kt` 保留旧 `berxelDualStart` 签名(兼容),新增统一入口:`cameraOpen(model, fds, configJson)` / `cameraPollDepthMm` / `cameraPollColor` / `cameraSetControls` / `cameraStop`。新增 `CameraModel`(sealed class,下沉 `core:model`)、`CameraSessionFactory`、`CameraDriver`。`BerxelNativeStack` 硬编码常量(VID/PID/VS iface/EP/format/frame/interval)改 `CameraDeviceProfile` 注入。VM 层(`Scan3dRecordingViewModel`)对 driver 选择透明。JNI 新增 `native/jni/camera_session_jni.cpp`(通用),marker 判别/raw→mm 下沉各 driver;旧 `berxel_dual_session_jni.cpp` 转兼容壳调 `BerxelDriver`。

### 1.7 feature:scan3d:自动识别 + 类型展示

`CameraService.start()` 枚举 USB,遍历 driver `match_usb_ids()` 选中(P100R3 双节点配对 / eYs3D 单节点)。`BerxelDeviceInfo` 加 `deviceTypeLabel`(下沉 core:model),UI 标题/badge/Sonix 调试页读它,流档位从当前 driver `capabilities().depth_profiles` 注入。

### 1.8 ★ 实施状态(2026-06-01,M6.8b 主体已落+全链编译/单测)

已落地(`dev.sh build` arm64+v7a `.so` + `dev.sh test` 全过):

- **native 抽象**:`native/camera/{camera_device.h,camera_session.h,camera_registry.h,host/usb_context.h}`。`ICameraSession` 加 `snapshot_depth_mm/snapshot_color`(最新帧 consume-once,两相机统一契约;默认空实现不破坏 poll-only 会话)。
- **eYs3D 驱动全栈**:`eys3d/portable/{depth,protocol,depth_router,driver,session_core,stereo_depth}` + `eys3d/host/eys3d_stream_loop.*`(**传输无关取流主循环**,host 与 Android-fd 共调)+ `eys3d/host/eys3d_host_session.*`(host-only,不进 `.so`)+ `eys3d/android/eys3d_fd_session.*`(`Eys3dFdSession`/`Eys3dFdDriver`,`libusb_wrap_sys_device(fd)`)。
- **JNI 统一入口**:`native/jni/camera_session_jni.cpp` — `cameraOpenByFds(vid,pid,fds,configJson)`/`cameraStop`/`cameraPollDepthMm`/`cameraPollColor`/`cameraStats`/`cameraSetControls`/`cameraCapabilitiesJson`。全局 `CameraRegistry` 注册 `Eys3dFdDriver`。**实际签名是 `cameraOpenByFds(vid,pid,...)` 而非 §1.6 的 `cameraOpen(model,...)`**——native registry 按 vid:pid 是单一真理源,Kotlin 不必传 model。
- **Kotlin**:`NativeBridge` 新增 7 个 `camera*` external fun(旧 `berxelDual*` 零改);`core:native-bridge/.../camera/{CameraModel,CameraStack}.kt`(判型 + 会话编排);`CameraModelTest` 过;`usb_device_filter.xml` 加 `0x3438:0x0206`。
- **CMake**:`native/CMakeLists.txt` 接入 `camera/`+`eys3d/portable`+`eys3d/android`+精确 `eys3d/host/eys3d_stream_loop.cpp`(host-only 的 replay/probe/host_session **绝不**进 `.so`)。

#### feature 路由(⑤,2026-06-01 主体落地,中性接口 + 加性改造)

- **中性接口**:`core:native-bridge/.../camera/CameraSource.kt` — `CameraSource`(`deviceLabel`/`sourceState`/`colorFrames`/`depthFrames`/`acquire`/`release`)+ `CameraSourceState`(Idle/NoDevice/WaitingPermission/Opening/Streaming/Error)。两相机出**同一** `ColorFrame`/`DepthFrame` 契约(depth=16bit mm)。
- **eYs3D 独立流服务**:`camera/Eys3dCameraService.kt`(@Singleton)— USB 权限 receiver → 单节点 `UsbManager.openDevice` 取 fd(`UsbDeviceConnection` 全程持有,native `wrap_sys_device` 不夺 fd 所有权)→ `CameraStack.start` → poll 循环 emit core:model 帧 → 引用计数生命周期。镜像 BerxelService,零厂商 SDK。
- **Berxel 加性接入**:`BerxelService : CameraSource` — 已有同名帧流/`acquire`/`release` 补 `override`,新增 `deviceLabel` + 由 `state` lazy 派生的 `sourceState`。**纯加性,既有流式/控制行为逐位不变**(不退化)。
- **路由**:`camera/CameraSourceProvider.kt`(@Singleton)按 `CameraDetection` 选活动源,**默认回落 Berxel**(没插 eYs3D 时与改造前一致)。`Scan3dRecordingViewModel`/`Scan3dRecordingScreen`(录制/拓印主路)改依赖 `CameraSource`。
- 验证:`:core:native-bridge`+`:feature:scan3d` 编译 + 整包 APK + 全模块 Hilt 聚合 + debug/release 单测全过。

#### Berxel native 统一(④,2026-06-01 生产全量迁移,真机复验门控)

- **扩抽象**:`ICameraSession` 加 `snapshot_confidence`/`snapshot_ir`/`extended_stats(16-long)`/`dump_raw_depth`(全默认空,eYs3D 不受影响)。Berxel 的富诊断面(conf/IR/16-long stats/dump)经此统一承载,不丢通道。
- **抽取 + 适配**:`berxel_dual_session_jni.cpp` 抽 `berxel_open_dual` + `berxel_snap_*`/`berxel_get_stats`/`berxel_dump_depth` core(legacy 逐字拷贝)；`BerxelSessionAdapter`(ICameraSession)+ `BerxelDriver`(ICameraDriver,`open_fd` 收 [masterFd,companionFd] + 打包 options_json)+ `MakeBerxelDriver()`(`native/berxel/host/berxel_camera_adapter.h`);`camera_session_jni` 注册 BerxelDriver + 4 新 JNI。
- **Kotlin 迁移**:`BerxelNativeStack` 全量走 `cameraOpenByFds(0x0603:0x001f, [masterFd,companionFd], packed)` + `cameraPoll*`/`cameraExtendedStats`/`cameraStop`。options_json 小端打包 `[xu|init|14-int cfg]`,与 native `unpack_berxel_options` 对称。
- **不退化**:legacy `berxelDual*` JNI + NativeBridge external fun **冻结保留为 device-gated 回退**(生产不再调用,字节不变),真机复验 camera* 路径通过后整段删除。两套逻辑等价(core 从 legacy 逐字拷贝),任一时刻仅一套被生产调用。
- **★ host 统一(2026-06-01,开发服务器真机已验证)**:`berxel_dual_session_jni.cpp` 经 `#ifdef __ANDROID__` 守住 Android 专属区(jni.h/android-log/8 JNIEXPORT)→ **host(Linux 服务器 libusb 枚举)与 Android(wrap_sys_device fd)双目标编译**;`berxel_open_dual` 拆 `set_modes_from_cfg` + `berxel_setup_and_launch`(公共尾)+ `berxel_open_dual_fd`/`_host`;`BerxelDriver::open_host` 接 `UsbContext` 枚举 0x0603+0x3558(`close_session` drain 改无条件,host 用默认 context)。**服务器真机 `tests/harness/berxel_camera_host/` 实测:经统一 `ICameraDriver::open_host` 出 depth valid=1.000、center≈345mm、analyze 判正常**——统一抽象在 host 端真机出 metric depth,与 Android open_fd 同 setup 序列。
- 验证:双 ABI APK + native-host + eys3d-host(含 berxel host probe 编译保护)+ 全模块 Hilt + debug/release 单测全过;**服务器真机 open_host 出帧验证通过**。

剩余(device-gated):⑤暂留 DepthCameraViewModel/Scan3dViewModel/SonixDebug 等 Berxel 专属工具页(待 eYs3D 控件面 device-gated 后中性化)。真机门控:eYs3D mode25 出帧 + 单节点 USB 权限;Berxel camera* 路径真机复验双流 ≥100 帧、conf/IR/stats 与 legacy 一致(通过后删 legacy 回退)。

## 2. Berxel 解耦改造点(风险分级)

🟢 纯重命名/泛化(零行为风险)| 🟡 结构重构(加抽象层,行为不变面广)| 🔴 动协议/数据通路(必须 harness 回归证 Berxel 不退化)

- 🟢:`IUvcDevice`/UVC 组装器/`RgbdFramePairer`/negotiate/replay 迁 `native/camera`(`portable.h:185-371` / `portable.cpp:1831-1985`);`BerxelNativeStack.kt:805-808` 常量→profile;`startKeepalive`→`KeepAliveStrategy`;`BerxelService.kt:2615` VID/PID→`CameraModel`;`SonixDebugScreen/DepthCameraScreen/Scan3dScreen` UI 标题/档位。
- 🟡:`TemporalFilter`/`FlyingPixelConfig` 去前缀参数化(`portable.h:571-614`,`depth_temporal_quality` 回归);`DualSession`→`BerxelSessionAdapter`;`BerxelNativeStack.kt:814-831` VS/EP/format 查表;`NativeBridge` 新增 `cameraOpen`;`startNativeDualDepthInternal` 动态 buffer;`needsBerxelSdkUsbPermission`→`needsAnyRecognizedCameraUsbPermission`。
- 🔴:`process_p100r3_depth_frame`(`portable.cpp:780-843`)**零改动锁死**;marker `0x0600/0x0500` 硬判(`berxel_dual_session_jni.cpp:200-206`)+ `depth_parser_loop`(`:259-276`)下沉 `BerxelDriver` 的 codec——`berxel_depth_parity` + `depth_temporal_quality` harness 是不退化验收闸。

## 3. 里程碑

见 `TODO.md`:**M1.8**(抽象层提取,Berxel 回归闸守护,host-first,hardware-independent)→ **M6**(eYs3D host→portable→Android,一路到含 metric 深度)。排序:M1.8 全程 `berxel_depth_parity`+`depth_temporal_quality` 零退化;M6 严格 host→portable→Android,每级一个独立 harness,M6.4-M6.5(端侧立体深度)是真正自研重头,不可跳。

## 4. 文档 / registry 更新(随实现落地)

- `13-eys3d-driver.md`(eYs3D 自研:`0x3438:0x0206` RS-D550、双 VS、Etron XU、stereo→视差→metric 自研链路、开流原语、带电 hub 供电时序)——**M6.1 验机已建**。
- 更新 `01`(§ 标注 Berxel 硬编码为单设备示范,通用见 12)、`10`(§5 分通用 IUvcDevice + Sonix-specific 两层)、`architecture.md`(加 12/13 链接)。
- registry:`modules.yaml` 加 `native/camera`、`native/eys3d`;`dependencies.yaml` 加 `native/{berxel,eys3d}→native/camera` + forbidden「native/* 不得 import 原厂 SDK」;`capabilities.yaml` 加 `camera_backend`/`depth_capability`(含 eYs3D `stereo_disparity` 维度)。**随 M1.8.1 创建目录时同步,不提前加不存在的模块。**

## 关键边界小结

- **通用 UVC(eYs3D 复用 ≥ Berxel)**:probe/commit、FID/EOF 组装、RGBD 配对、XU replay 编排、TemporalFilter/飞点算法。
- **Berxel 专有(eYs3D 不碰)**:master XU5 keepalive、companion 0x82 BULK 交织 IR+phase、MJPEG-over-BULK、Sonix ASIC 寄存器、双设备编排。
- **eYs3D 专有(Berxel 没有)**:Etron 0xF4xx 寄存器、**端侧立体匹配视差→metric Z**(M6 主工作量)、两阶段供电时序。
