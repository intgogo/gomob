# HLSD8 = 独立第二颗 RGB 相机（非深度模组）；gomob 双相机接入 + 正射图几何 2026-06-10

## 结论（headline）

扫描机上是**两颗物理独立的 USB 相机**，不是一颗：

| 角色 | 型号 | VID:PID | 厂商 | 输出 |
|------|------|---------|------|------|
| **深度** | Etron **RS-D550** | 0x3438:0x0206 (13368:518) | Etron Technology | L'(1280×256 MJPG) + depth(640×128) = mode25 |
| **RGB** | Image+ **HLSD8** | 0x0C45:0x6366 (3141:25446) | Image+（0x0C45=Sonix） | 13MP 真彩，约 **4160×832 MJPEG** |

`adb shell dumpsys usb` 实证两条独立 `UsbDevice`。**此前 gomob 只接了深度模组**（USB filter / 权限 / 枚举全是单节点 0x3438:0x0206），13MP RGB 从未触及。VINCreator「用深度把 RGB 几何校正成正射图（约 4000 宽）」的高分辨率 RGB 来源就是 HLSD8。

**★ 订正旧记忆**：本仓 `finding_eys3d_android_bringup_0bytes` 续28 把 4160×832 MJPEG 当成 eYs3D 的 COLOR 流是**错的** —— 4160×832 是 HLSD8（独立相机），eYs3D 的 color/L' 是 1280×256。HLSD8≠深度模组。

## Why（为什么重要）

项目魂是「真实可量测 RGBD 3D 扫描」。RGB 这一路缺失=正射图/真彩纹理/VIN 拓印全没真彩源。补齐 HLSD8 是把 gomob 从「只有深度」拉到「深度+真彩双相机」的关键缺口。HLSD8 是**标准 Sonix UVC MJPEG** 摄像头（无 eYs3D 那套 XU arming），取流比深度模组容易得多。

## How to apply（已落地 + 怎么用）

**native**（`native/hlsd8/hlsd8_uvc_session.{h,cpp}`）：
- `Hlsd8Driver : ICameraDriver`（color-only：has_depth=false），认领 0x0C45:0x6366，注册进 `camera_session_jni.cpp` 的 `Registry()`，与 eYs3D/Berxel 同一 `cameraOpenByFds` 分发。
- `Hlsd8UvcSession`：dlopen `libuvc_lusb100.so`（与 eYs3D 同后端，pupil 解析 MJPEG + libusb100 能出流），`uvc_get_format_descs` 枚举设备真实格式**自动选最大 MJPEG 帧**（不猜分辨率），`uvc_start_streaming` 起 libuvc 自有阻塞 handler 线程，最新帧 `snapshot_color`（consume-once）。无 XU、无手动 bulk。

**Kotlin**（`core/native-bridge/.../camera/`）：
- `CameraModel.Hlsd8`（`isDepthSource=false`）+ `CameraDetection.detect`（只选深度源）/ `detectAuxRgb`（HLSD8）分流——两颗可并存。
- `Hlsd8CameraService : CameraSource`（color-only，无 armViaJava；13MP 预览 BitmapFactory `inSampleSize` 降采样到 ≤1280 宽省内存）。
- `CameraSourceProvider.active()`=深度主相机、`auxRgb()`=HLSD8，可并行 acquire。
- USB filter 加 `vendor-id=3141 product-id=25446`。

**UI**（`feature/scan3d/DepthCamera*`）：深度页插着 HLSD8 时多一栏「RGB · HLSD8」预览（VM 并行 acquire/release rgbSource）。eYs3D 那栏标签改「L'(左矫正)」（非真彩）。

**正射图几何**（`native/vin/ortho_rectify.{h,cpp}`，替代旧 `vin_rectify.cpp` NOT_IMPLEMENTED 桩）：
- `OrthoRectify(depth,K_depth, rgb,K_rgb, R|t_rgb_from_depth, cfg)`：depth 反投影→RANSAC 主平面→平面内正交基→逐正射像素映射平面点 Q→`R*Q+t` 变到 RGB 系→投影双线性采样。
- **R|t 是 HLSD8↔RS-D550 双相机外参，来自双相机标定（device-gated，见 docs/architecture/05）**；缺标定时退化 R=I,t=0=同相机假设（仅调试，真机有 ~baseline 视差会偏，不能当真结果）。
- harness `tests/native_host/ortho_rectify_test.cpp`（合成倾斜平面 RGBD + 坐标编码纹理）PASS：法向|n·n0|=1.0、inlier 1.0、rms0.29mm、覆盖91%、中心解码-质心 0.55mm（验外参投影+采样）、尺度比 1.008（验 metric scale）。`scripts/native-host-test.sh` 已挂。

## 仍 device-gated（用户回来测）

1. HLSD8 真机出流验证（标准 UVC，预期顺）。
2. HLSD8↔RS-D550 双相机标定（R|t）→ 正射图真彩才对。
3. mode25 深度真机连续出帧（见 [[finding_eys3d_android_bringup_0bytes_2026-06-09]] 续30：config 已逐字节对齐 VINCreator gold，剩运行态）。

相关：[[finding_vincreator_eys3d_uvc_blueprint_2026-06-01]]、[[finding_multiview_rgbd_pivot_2026-05-07]]。
