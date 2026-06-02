# 14. RGBD 实时流 → gorob 边缘 (机器人视觉源)

> 状态 (2026-06-02): **代码已写, 未在安卓设备实测**。本机无安卓 SDK/设备, 由 Claude 在 gorob 仓库
> 同步实现边缘侧后, 按线缆契约写出本端客户端。**验证需 `./dev.sh install` 推真机 + 连 gorob 边缘**。

## 目标

把 gomob 手机 + Berxel iHawk 深度相机当作**机器人的眼睛**: 实时 RGBD 帧流给 `gorob` 边缘做权威
TSDF 融合, 用户戴 Pico4 在 `gorob` 的 `/vr` 看实时重建的三维世界。这是 gorob 阶段一(超低延迟 360°
世界复刻 + VR 头控)的真数据源——替换掉 gorob 此前的合成/sim 深度。

```
gomob 手机(Berxel RGBD + VIO) --WS 二进制 protobuf--> gorob cmd/robot-edge /rgbd
   → rgbdwire 解码(mm→m, BGR→RGB, 畸变重映射) → frame-to-model ICP 精修位姿 → TSDF 融合(权威)
   → worldsync 增量/keyframe → /world → Pico4 WebXR 持久网格 + 本地 Tier A 重投影
```

## 决策 (用户拍板 2026-06-02)

1. **传输**: 先 **WebSocket 二进制**(gomob 已有 okhttp; 落地最快), 契约/边缘传输无关, 后续可切 WebRTC。
2. **位姿**: **手机 ARCore VIO 出 6DoF 作先验 + gorob frame-to-model ICP 精修**。起步可用单位位姿
   (`IdentityPoseProvider`, 全靠 gorob ICP); 漂移大时接 `ArCorePoseProvider`。

## 线缆契约

权威契约 = gorob `proto/rgbd.proto`(已复制到本仓 `server/proto/rgbd.proto`)。本端**手写 protobuf 编码**
(`stream/Proto.kt`, 不引 protobuf gradle 插件), 字段号/线型严格对齐, 由 gorob `proto.Unmarshal` 解码。

| 数据 | 本端 (core:model) | 线缆 | gorob 侧 |
|---|---|---|---|
| 深度 | `DepthFrame.data` uint16 小端 mm, 0=无效 | `DEPTH_U16_MM` | ×0.001 转米, 0/NaN→无效 |
| 彩色 | `ColorFrame.data` BGR888 | `COLOR_BGR8` | 翻回 RGB; 尺寸不符则不发 |
| 置信 | `DepthFrame.confidence` uint8, 0=无效 | bytes | 归一 [0,1] |
| 内参 | `DepthFrame.intrinsics` (含畸变[5]) | `CameraIntrinsics` | Brown-Conrady 重映射成针孔 |
| 位姿 | `PoseProvider` (ARCore/单位) | `Pose6DoF` 四元数+米 | ICP 初值, 精修后融合 |

## 已交付代码

- `feature/scan3d/.../stream/Proto.kt` —— 极简 protobuf 写入器。
- `feature/scan3d/.../stream/PoseProvider.kt` —— `Pose6` + `PoseProvider` (默认 `IdentityPoseProvider`)。
- `feature/scan3d/.../stream/RgbdStreamClient.kt` —— okhttp WebSocket; `start(source)` 收 `CameraSource`
  的 color/depth 流逐帧编码上行, `stop(source)` 释放。
- `feature/scan3d/build.gradle.kts` —— 加 `implementation(libs.okhttp)`。
- `server/proto/rgbd.proto` —— 共享契约 (来自 gorob)。

## 还需接的线 (集成步骤)

1. **UI 开关**: 在 `DepthCameraScreen` / `Scan3dRecordingViewModel` 加一个"推流到 gorob"开关 + 边缘
   地址输入框 (默认 `ws://<edge-ip>:8111/rgbd`)。开 → `RgbdStreamClient(url, viewModelScope).start(source)`;
   关 → `stop(source)`。`source` 即页面已有的 `CameraSource` (Berxel/eYs3D 路由)。
2. **ARCore 位姿** (可选, 漂移大时上): 实现 `ArCorePoseProvider`: 起 ARCore session, 把
   `Frame.camera.pose` 换算到 CV 约定(ARCore 是 OpenGL 看 -Z/+Y上, 需绕 X 轴 180° 翻, 与
   gorob `sim/dump_rgbd.py` 的 `CAM_FLIP` 同源), 按 timestamp 取最近位姿。传给 `RgbdStreamClient`。
3. **网络权限**: 已有 (`INTERNET`); 局域网明文 ws:// 需 `usesCleartextTraffic` 或 network-security-config
   允许边缘 IP。

## 验证 (上真机后)

1. gorob 侧起边缘: `cd ~/lilw/gorob && go run ./cmd/robot-edge --addr :8111 --static client/webxr`。
2. 手机与边缘同网; `./dev.sh install` 推 gomob; 进扫描页, 开"推流到 gorob", 填边缘地址。
3. 看 gorob `robot-edge` 日志 `RGBD 融合 frames=.. valid=.. icp=.. bricks=..` 帧数增长。
4. Pico4 浏览器开 `http://<edge-ip>:8111/vr` (无头显可 `?fallback=1`), 看实时点云随手机移动生长。
5. `curl http://<edge-ip>:8111/api/edge/stats` 看 `frames/bricks/surface_points/last_icp_*`。

> 无硬件回归: gorob `tests/harness/rgbdlink` 用合成手机(注入 VIO 漂移)走【真 WS/protobuf】打通整条链路,
> 已 PASS, 含诚实负对照: 关 ICP 直喂漂移 67mm vs 开 ICP 22mm(3× 胜); 全丢后先确陈旧→keyframe 从零自愈。
> 本端真机实测前, 那条 harness 是契约正确性的护栏。

## 评审修正 (2026-06-02)

gorob 侧对抗评审逮到本端一处真 bug 并已修: `RgbdStreamClient.encode` 曾把【帧分辨率】写进
`CameraIntrinsics.width/height`(应为**标定分辨率** `intr.width/intr.height`)。已修。约定: 若标定分辨率
≠ 帧分辨率, fx/fy/cx/cy 须按比例缩放(Berxel 每帧内参通常即帧分辨率, 相等; 真接 ARCore/裁剪/降采样时注意)。
