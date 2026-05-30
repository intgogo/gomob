# Berxel P100R3 自研 SDK 交接

更新时间：2026-05-29  
交接范围：M1.6.11 Linux host 自研 P100R3 SDK、depth parity、dual 同步 API，以及下一步 Android 迁移。

## 先读顺序

Claude 接手时先按这个顺序读，不要重新从 UI 或旧 Android SDK 现象开始猜：

1. `AGENTS.md` -> `CLAUDE.md` -> `docs/agent-memory/AGENTS_MEMORY.md`
2. 本文件
3. `TODO.md` 的 M1.6.6 到 M1.6.11
4. `native/berxel/host/README.md`
5. `native/berxel/host/docs/depth-pipeline-reverse.md`
6. `native/berxel/host/docs/xu-command-map.md`
7. `tests/harness/berxel_depth_parity/README.md`

当前工作树里 `native/berxel/host/`、`native/berxel/portable/`、`tests/native_host/berxel_*`、`tests/harness/berxel_depth_parity/` 等仍可能显示为 untracked，这是当前 Berxel host SDK 主线成果，不要清理或回滚。

## 2026-05-29 进展：portable 层抽离 + host bug 修复（Android 迁移 Step 1，已实机验证）

接手后先复跑了三条验证全绿（host 单测 7/7、Qt demo build exit 0、depth parity status=OK），
随后做了一轮多 agent 迁移就绪度审核（报告 `.dev/berxel-host-sdk/migration-audit-2026-05-29.md`），
据此推进了迁移第一步。**用户拍板：编排函数收口走 A 方案（抽 `IUvcDevice` 接口），IR 预览保留为独立 LIGHT_IR 流。**

已完成并实机验证：

- 新增 `native/berxel/portable/{gomob_berxel_portable.h,.cpp}`：把不依赖 libusb 的纯逻辑全部抽出——
  数据契约、`UvcRawFrameAssembler` / `UvcMjpegFrameAssembler`、`RgbdFramePairer`、`p100r3_depth_*`、
  `process_p100r3_depth_frame` / `process_p100r3_light_ir_frame`、`make_p100r3_*` / `patch_p100r3_*`、
  `parse_xu_payloads`、`refresh_master_time_sync_payloads`、`usb_error_name`(libusb-free)，
  以及协议编排 `replay_xu_payloads` / `apply_p100r3_depth_controls` / `negotiate_uvc_stream` /
  `master_keepalive_loop`。
- 新增 `IUvcDevice` 抽象接口（control_transfer / uvc_set_cur / uvc_get_cur / uvc_get_def / bulk_in）；
  编排函数从吃 `UsbDevice&` 改为吃 `IUvcDevice&`；host 层 `UsbDevice : public IUvcDevice`。
  Android 端只需各自实现 `IUvcDevice`（`libusb_wrap_sys_device` 或纯 Java 后端）即可复用全部编排逻辑。
- host 层 `gomob_berxel_host_sdk.{h,cpp}` 只保留 libusb IO + 文件落盘 + `load_xu_payloads`(委托
  `parse_xu_payloads`) + `pull_*` + `P100R3DualSession`。共享底层 helper 放 `namespace detail`。
- **内部 helper `emit` 改名 `log_line`**：避免与 Qt `emit` 宏冲突（portable 头被 Qt demo include）。
- 修两个 host bug：
  1. `UvcRawFrameAssembler` oversized 恢复路径不再用 stale `current_`，出帧元数据来自当前包
     （补 `tests/native_host/berxel_raw_assembler_test.cpp::oversized_recovery_resets_frame_info`）。
  2. `P100R3DualSession::start()` opening 期被并发 `stop()` 后，不再把 `kStopping` 覆盖回 `kStreaming`，
     仅 `kOpening` 时才 CAS 升 streaming 并启动拉流线程。
- 构建点同步：`scripts/berxel-host-test.sh`（纯逻辑 5 个单测改为 **只链 portable.cpp、不链 libusb**，
  编译期硬证明零依赖）、`scripts/berxel-host-probe.sh`、`demo/vin_rectify_gui/CMakeLists.txt` 都加了
  portable.cpp + `-Inative/berxel/portable`。

验收（2026-05-29 实机）：host 单测 7/7（含新增 oversized 回归）、Qt demo build exit 0、
depth parity status=OK（host dense 1.0000 / vendor 0.9983 / median depth delta 0.56mm /
median over noise 0.00mm），无退化。

**Step 2-3 已完成并实机验证（2026-05-29 当天续）**：
- Step 2：portable.cpp 加进 `native/CMakeLists.txt`，NDK clang 跨 arm64/armv7 零警告编进 `gomob_native.so`。
- Step 3：新增 `native/jni/berxel_dual_session_jni.cpp`——`AndroidUvcDevice : IUvcDevice`（libusb_wrap_sys_device 包 Android fd）+ 双会话驱动原样 mirror host 12 步序列（全走 portable 编排 + assembler + RgbdFramePairer）+ Android bulk pump；JNI `berxelDualStart/Stop/Stats/PollDepthMm` + Kotlin `BerxelNativeStack.startDualNative` + `SonixDebugScreen` 按钮/`HARNESS_DUAL` broadcast。
- **实机（小米 2510DRK44C）depth-only 跑通**：完整序列执行，**valid=1.000 稠密 depth / center_median≈423mm(raw/8.0) / depth_err=0 / ~26-40fps**（对准 ~42cm 目标）。**P0「depth 当 IR」实机修复确认**，portable 链路 1:1 复现 host dense 行为。证据 `.dev/berxel-split/xiaomi-dual-success-2026-05-29.md`。
- 踩坑：CMake `file(GLOB)` 不重 glob → 新 .cpp 漏编 UnsatisfiedLinkError；已给所有 GLOB 加 `CONFIGURE_DEPENDS`。HyperOS 锁 `INJECT_EVENTS` → `adb input tap` 失效，触发改用 `am broadcast`（不受限）。

**下一步（Step 3.5 / 4，未开始）**：① DUAL(depth+color) 实机验证（小米双流稳；旧路 master color 出全 0，待 portable MJPEG 路径验证）；② 可加 manifest 常驻 `HARNESS_DUAL` 接收器实现零点屏 adb 驱动；③ Step 4：Kotlin 正式切到 `startDualNative` host 验证链路，删 `BerxelFrameAssembler.kt`/`BerxelMjpegAssembler.kt`/native-bridge 包内 `DepthFrame.kt` 重复实现，修 `BerxelService.kt:507-566` depth-as-IR + 契约 `DepthFrame.data` 真为 16bit mm。审核报告第 2/4/5 节有逐字段 gap 与可验收步骤。

## 当前结论

Linux host 自研 SDK 已经不是“demo 验证”阶段，而是 Android 迁移前的事实来源：

- 不链接 Berxel `.so`，只依赖系统 `libusb-1.0`。
- 已复现 P100R3 master `0603:001f` 与 companion `3558:1012` 的枚举、XU replay、UVC probe/commit、bulk 拉流、CloseStream。
- COLOR 支持 `1920x1080@30`、`1280x800@30`、`640x400@30` MJPEG。
- DEPTH 支持 `1280x801@45`、`640x401@45`、`320x201@45`、`1280x800@5` RAW16。
- LIGHT_IR 已确认是散斑图，active `1280x800`，raw10 灰度 `0..1023`。
- Qt6 demo 可展示 COLOR + DEPTH 或 COLOR + LIGHT_IR，数据源是自研 host SDK。

最重要的 depth 语义已经定案：

- 普通 `DEPTH` 不是散斑图，而是设备侧已算好的 RAW16 fixed-point depth。
- 当前设备原厂 SDK 报 `pixelType=2`，即 `13I_3D`，应按 `raw / 8.0` 转毫米。
- companion transport 多一行设备状态行：`1280x801`、`640x401`、`320x201`；业务 active depth 是 `1280x800`、`640x400`、`320x200`。
- dense depth 不是 host CPU `fillHole` 补出来的，而是设备侧 `temporal_denoise=0` 和 `spatial_denoise=0` 触发。
- host SDK 默认 `P100R3DepthControls` 已是原厂 dense 组合：`AE=1, confidence=3, temporal_denoise=0, spatial_denoise=0`。
- `process_p100r3_depth_frame()` 只作为 VIN/分割实验后处理，量测和点云默认必须使用 active raw depth + validity/confidence mask。

偏振信息的当前判断：

- 用户补充该相机使用偏振技术。
- 反编译和字符串检索没有发现 SDK 侧明确的 `polarization` / DoLP / AoLP / Stokes 处理入口。
- 偏振更可能在光学/传感器/固件侧影响匹配质量；SDK 侧目前证据是 IR/LIGHT_IR、NCC confidence、曝光/增益/电流、温补和后处理。
- 后续 parity 要用材质/角度 harness 覆盖高光、金属、黑色、玻璃/半透明、斜入射，不要在 CPU 里盲目补成“好看”的深度图。

## 最近验证结果

基础逻辑单测：

```bash
scripts/berxel-host-test.sh
```

覆盖：

- `berxel_sonix_protocol_test`
- `berxel_host_payload_test`
- `berxel_depth_processing_test`
- `berxel_mjpeg_assembler_test`
- `berxel_raw_assembler_test`
- `berxel_session_state_test`
- `berxel_rgbd_pairer_test`

Qt demo 构建：

```bash
scripts/berxel-host-gui.sh --build-only
```

最新 depth parity：

```bash
./dev.sh harness berxel_depth_parity
```

最新实测摘要，输出目录为 `.dev/berxel_depth_parity/`：

- 状态：`OK`
- sparse reset：约 `0.129..0.130`
- host default dense：`1.0000`
- vendor dense：`0.9984`
- host `--no-depth-controls`：`0.1559`
- median depth delta：`1.19mm`
- host-vendor median abs diff：`37.125mm`
- 相机自身噪声底：`36.625..37.125mm`

dual 同步短测：

```bash
OUT=.dev/berxel-host-sdk/session-api-dual-sync-stats-20260528-232236
scripts/berxel-host-probe.sh --session-api --dual --ka-ms 0 --master-all \
  --color-frame 2 --color-interval 333333 \
  --depth-frame 2 --depth-interval 222222 \
  --dur-ms 2000 --save-depth-frames 3 --save-depth-skip 2 \
  --out-dir "$OUT"
python3 native/berxel/host/tools/analyze_frame_csv.py "$OUT/frames.csv" \
  --out "$OUT/frame-sync-analysis-v2.json"
```

结果：

- session：`stopped / duration_reached`
- COLOR：39 帧，全部 `completed_by_jpeg_eoi`
- DEPTH：82 帧，全部 `completed_by_size`
- RGBD pair：39 对
- pair/color 序号连续
- depth 因 45fps 对 30fps 正常跳过 36 帧
- pair delta：P50 `8.76ms`，P95 `9.24ms`，max `13.48ms`

## 关键文件

Host SDK：

- `native/berxel/host/include/gomob_berxel_host_sdk.h`
- `native/berxel/host/src/gomob_berxel_host_sdk.cpp`
- `native/berxel/host/README.md`
- `native/berxel/host/docs/xu-command-map.md`
- `native/berxel/host/docs/depth-pipeline-reverse.md`

Host tools / demo：

- `scripts/berxel-host-probe.sh`
- `scripts/berxel-host-gui.sh`
- `scripts/berxel-host-mode-sweep.sh`
- `scripts/berxel-host-test.sh`
- `native/berxel/host/tools/analyze_frame_csv.py`
- `native/berxel/host/tools/compare_depth_frames.py`
- `native/berxel/host/demo/vin_rectify_gui/`

Harness：

- `tests/harness/berxel_depth_parity/run.sh`
- `tests/harness/berxel_depth_parity/analyze.py`
- `tests/harness/berxel_depth_parity/sweep.sh`
- `tests/harness/berxel_depth_parity/vendor_hawk_depth_read.cpp`
- `tests/harness/berxel_depth_parity/scenes.example.tsv`

Native host tests：

- `tests/native_host/berxel_sonix_protocol_test.cpp`
- `tests/native_host/berxel_host_payload_test.cpp`
- `tests/native_host/berxel_mjpeg_assembler_test.cpp`
- `tests/native_host/berxel_raw_assembler_test.cpp`
- `tests/native_host/berxel_depth_processing_test.cpp`
- `tests/native_host/berxel_session_state_test.cpp`
- `tests/native_host/berxel_rgbd_pairer_test.cpp`
- `tests/native_host/berxel_host_probe.cpp`

Android 相关现状：

- `core/native-bridge/src/main/kotlin/io/gomob/nativebridge/berxel/BerxelService.kt`
- `core/native-bridge/src/main/kotlin/io/gomob/nativebridge/berxel/BerxelNativeStack.kt`
- `core/native-bridge/src/main/kotlin/io/gomob/nativebridge/berxel/BerxelStackBackend.kt`
- `core/native-bridge/src/main/kotlin/io/gomob/nativebridge/NativeBridge.kt`
- `feature/scan3d/src/main/kotlin/io/gomob/feature/scan3d/DepthCameraScreen.kt`
- `feature/scan3d/src/main/kotlin/io/gomob/feature/scan3d/SonixDebugScreen.kt`
- `native/jni/jni_bridge.cpp`

厂商资源位置：

- Linux SDK：`.dev/berxel-sdk-extract/BerxelSDK-Linux-2.0.190`
- Windows SDK：`/root/WindowsR/berxel/sdk/`
- VinRectifyGui 原界面：`/root/WindowsR/berxel/sdk/Tools/VinRectifyGui`
- Berxel 资源总索引：`docs/agent-memory/reference_berxel_sdk_locations.md`

## 当前 API 形状

host SDK 已经有 Android 迁移要复用的最小稳定形状：

- `UvcFrameInfo`
  - endpoint
  - mode
  - frame_number
  - host_start_ns / host_end_ns
  - transport_bytes / payload_bytes
  - UVC PTS / SCR / FID
  - completed_by_eof / size / fid / jpeg_eoi
- `RgbdFramePairInfo`
  - pair_number
  - color `UvcFrameInfo`
  - depth `UvcFrameInfo`
  - host_delta_ns，语义是 depth midpoint 减 color midpoint
  - within_tolerance
- `RgbdPairingStats`
  - pairs
  - dropped_color_frames / dropped_depth_frames
  - queued_color_frames / queued_depth_frames
  - last_host_delta_ns
  - mean_abs_host_delta_ns
  - max_abs_host_delta_ns
  - last_color_frame_number / last_depth_frame_number

probe 已把 `frames.csv` 和 `pairs.csv` 扩展到可分析帧边界、帧计数、UVC timestamp、pair delta。Android 迁移时不要再发明一套弱元数据结构，直接照这个 shape 落到 Kotlin / JNI。

## 不要踩的坑

- 不要把 `DEPTH` 当散斑图。散斑图走 `LIGHT_IR`。
- 不要再按 `raw / 16.0` 解释 depth；当前 P100R3 是 `13I_3D`，按 `raw / 8.0` 毫米。
- 不要用 CPU fill-hole 的 processed depth 替代 raw 量测；processed 只能作为 VIN/分割实验。
- 不要忘记 depth controls 的设备状态是粘性的。测试 sparse/dense 前要显式复位。
- 不要把 `--no-depth-controls` 下的 sparse 图当作 SDK 正常输出。
- 不要把 depth pair 中跳过帧当异常。当前 depth 45fps、color 30fps，pairer 最近邻会自然跳过部分 depth 帧。
- 不要看到 pairer `push_color()` 没立刻吐 pair 就误判失败。pairer 会等 depth queue 覆盖 color midpoint 后再配。
- 不要只看 Android `startStreams rc=0` 判断成功；旧 Berxel SDK 有“假成功”历史。
- 不要在工作树里清理 untracked 的 Berxel host SDK 文件；这些是当前主线成果。

## 下一步开发建议

### 1. 先做 Android 可移植 API，不先碰 UI

目标：把 host SDK 的 frame metadata 和 pair stats 原样映射到 Android。

建议落点：

- Kotlin 数据类放 `core/native-bridge/src/main/kotlin/io/gomob/nativebridge/berxel/`
- JNI C++ 放 `native/jni/jni_bridge.cpp` 或先拆到 `native/berxel/` 复用层
- 高吞吐数据用 `DirectByteBuffer` 或 native-owned buffer handle，不要用 JNI byte array 长期搬运

需要包含：

- color/depth/light-ir frame format
- frame_number
- timestamp start/end/midpoint
- UVC PTS/SCR/FID
- payload/transport bytes
- completed_by
- RGBD pair delta
- `RgbdPairingStats`

验收：

- Android 日志/状态流能持续输出 color/depth frame number、fps、timestamp、pair delta。
- 60s dual stream 中 pair stats 能持续更新，且异常时可解释 drop/queue。

### 2. 把纯逻辑从 host libusb 层拆得更清楚

当前 `gomob_berxel_host_sdk.cpp` 同时包含 libusb IO、UVC assembler、pairer、depth processing。Android 迁移前建议抽公共层：

- UVC payload parser
- `UvcRawFrameAssembler`
- `UvcMjpegFrameAssembler`
- `RgbdFramePairer`
- P100R3 depth active/raw/mm helper
- depth controls payload 生成

这些不应该依赖 Linux `libusb`，Android 可直接编译复用。

每次拆分后跑：

```bash
scripts/berxel-host-test.sh
./dev.sh harness berxel_depth_parity
```

### 3. Android native stack 走 host 已证实的启动顺序

不要从旧 Berxel Android SDK 的行为倒推。按 host 成功路径迁移：

1. open master `0603:001f`
2. claim master interface
3. replay master XU5 init，刷新 time-sync payload
4. patch COLOR OpenStream payload
5. open companion `3558:1012`
6. claim companion interface 0/1
7. replay companion XU3 init，patch DEPTH/LIGHT_IR mode
8. apply dense depth controls
9. UVC commit depth
10. UVC commit color
11. start bulk loops
12. stop 时发 master CloseStream；LIGHT_IR 还要 PWM off + companion stop

### 4. 多场景 parity sweep

当前 default 桌面场景已经过关，下一步要把用户提到的偏振影响纳入真实采样：

- 白纸/漫反射平面
- 黑色塑料
- 金属高光
- 玻璃或半透明材料
- 斜入射角 30/45/60 度
- 30cm、50cm、100cm 距离

入口：

```bash
cp tests/harness/berxel_depth_parity/scenes.example.tsv .dev/berxel_depth_parity/scenes.tsv
tests/harness/berxel_depth_parity/sweep.sh .dev/berxel_depth_parity/scenes.tsv
```

验收要看：

- host/vendor valid ratio delta
- median depth delta
- host-vendor median abs diff 是否接近相机自身噪声底
- center ROI jitter
- 高光/黑色/玻璃是否有系统性偏差

### 5. 出厂参数和注册参数继续反编译

M1.3 / M1.6.7 仍需要：

- `getCameraIntriscParams`
- `getDeviceIntriscParams`
- `setRegistrationEnable`
- params.bin / params100Q.bin / params137.bin 解码

不要先写假参数或固定内参。优先用厂商 Linux SDK 读回和二进制反编译对照，再落自研读取。

## 继续前的最短恢复命令

Claude 接手后建议先跑：

```bash
scripts/berxel-host-test.sh
./dev.sh harness berxel_depth_parity
scripts/berxel-host-gui.sh --build-only
```

如果要实机看 dual 同步：

```bash
OUT=.dev/berxel-host-sdk/session-api-dual-sync-$(date +%Y%m%d-%H%M%S)
scripts/berxel-host-probe.sh --session-api --dual --ka-ms 0 --master-all \
  --color-frame 2 --color-interval 333333 \
  --depth-frame 2 --depth-interval 222222 \
  --dur-ms 3000 --save-depth-frames 5 --save-depth-skip 2 \
  --out-dir "$OUT"
python3 native/berxel/host/tools/analyze_frame_csv.py "$OUT/frames.csv" \
  --out "$OUT/frame-sync-analysis.json"
```

如果设备进入 bulk 空读或枚举异常，先物理断电重插相机。历史上有状态是 `libusb_reset_device`、sysfs deauthorize/authorize、厂商 SDK stop 都恢复不了的。
