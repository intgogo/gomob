# Berxel P100R3 Linux Host SDK

这是 P100R3 自研替代 SDK 的 Linux 开发入口，只依赖系统 `libusb-1.0`，不链接 Berxel
厂商 `.so`。Android 迁移前，设备枚举、私有 XU 控制、UVC 协商、bulk 拉流都先在这里闭环。

> **2026-05-29 分层**：不依赖 libusb 的纯逻辑（帧组装 / RGBD 配对 / depth&light-ir 解码 /
> XU payload 生成 / 协议编排）已抽到 `native/berxel/portable/`，host 层只保留 libusb IO +
> 文件落盘 + `P100R3DualSession`。协议编排函数改吃 `IUvcDevice&` 接口，`UsbDevice` 实现它。
> 编译 host SDK / probe / demo 都需要同时编译 `native/berxel/portable/gomob_berxel_portable.cpp`
> 并加 `-Inative/berxel/portable`。详见 `native/berxel/portable/README.md`。

## 目录

- `../portable/gomob_berxel_portable.{h,cpp}`：可移植层（libusb-free），Android native 直接复用。
- `include/gomob_berxel_host_sdk.h`：host 层接口（`UsbDevice : public IUvcDevice` / `UsbContext` /
  `load_xu_payloads` / `P100R3DualSession` / `pull_*`），`#include` portable 头。
- `src/gomob_berxel_host_sdk.cpp`：libusb RAII、设备枚举、bulk 拉流线程、`P100R3DualSession`、文件落盘。
- `assets/iHawkP100R3_color_master_xu5_init.json`：彩色 master XU5 初始化序列。
- `docs/xu-command-map.md`：原厂 XU 命令反编译结果与 COLOR/DEPTH 私有模式映射。
- `tools/decode_xu_commands.py`：解析原厂抓包和 JSON，输出 XU 命令表到 `.dev/`。
- `tools/analyze_frame_csv.py`：分析 probe 生成的 `frames.csv` / `pairs.csv`，输出双流帧计数和 host timestamp 同步摘要。
- `tests/native_host/berxel_host_probe.cpp`：命令行探针。
- `scripts/berxel-host-probe.sh`：构建并运行探针，产物写到 `.dev/berxel-host-sdk/`。
- `scripts/berxel-host-test.sh`：构建并运行 Berxel host SDK 纯逻辑单测。
- `scripts/berxel-host-mode-sweep.sh`：逐档测试 COLOR / DEPTH 支持模式。
- `demo/vin_rectify_gui/`：从 VinRectifyGui 迁移来的 Linux Qt6 双流预览 demo。
- `scripts/berxel-host-gui.sh`：构建并运行 Qt6 demo。

## 常用命令

```bash
scripts/berxel-host-probe.sh --list
scripts/berxel-host-probe.sh --reset
scripts/berxel-host-probe.sh --stop-only
scripts/berxel-host-probe.sh --depth --dur-ms 3000
scripts/berxel-host-probe.sh --color --dur-ms 3000 --ka-ms 0
scripts/berxel-host-probe.sh --session-api --dual --master-all --dur-ms 3000
scripts/berxel-host-probe.sh --session-api --dual --master-all --dur-ms 3000 --depth-controls-vendor
scripts/berxel-host-probe.sh --session-api --light-ir --master-all --ka-ms 0 --dur-ms 1500
scripts/berxel-host-test.sh
scripts/berxel-host-gui.sh --auto-start
scripts/berxel-host-gui.sh --auto-start --light-ir
scripts/berxel-host-gui.sh --detach --auto-start
python3 native/berxel/host/tools/decode_xu_commands.py
python3 native/berxel/host/tools/analyze_frame_csv.py .dev/berxel-host-sdk/<case>/frames.csv
python3 native/berxel/host/tools/analyze_depth_raw.py .dev/berxel-host-sdk/<case>/depth-best.raw \
  --out-dir .dev/berxel-host-sdk/depth-raw-analysis
```

彩色流依赖 master `42580a0005...` time-sync 命令里的当前 epoch 秒和微秒。
probe 默认会刷新该 payload；需要复现实验旧包时可加 `--no-fresh-time-sync`。

## 当前实测

- Color：`640x400` MJPEG 已闭环，首帧保存为 `.dev/berxel-host-sdk/color-first.jpg`。
- Depth：`640x401x16` raw payload 已闭环，首帧保存为 `.dev/berxel-host-sdk/depth-first.raw`。
- Dual：物理断电重插后，默认顺序和 `--color-first` 顺序都能同时拿到 color/depth 数据；
  样本保存在 `.dev/berxel-host-sdk/dual-default-after-powercycle/` 和
  `.dev/berxel-host-sdk/dual-color-first-after-powercycle/`。

### Mode sweep

```bash
scripts/berxel-host-mode-sweep.sh
```

2026-05-28 SDK API 收口后实测输出
`.dev/berxel-host-sdk/mode-sweep-sdk-api-20260528-112622/summary.csv`：

| 流 | mode | 结果 |
|----|------|------|
| COLOR | 1920x1080@30 MJPEG | 成功，首帧 JPEG 为 1920x1080 |
| COLOR | 1280x800@30 MJPEG | 成功，首帧 JPEG 为 1280x800 |
| COLOR | 640x400@30 MJPEG | 成功，首帧 JPEG 为 640x400 |
| DEPTH | 1280x801@45 RAW16 | 成功，首帧 raw 为 2,050,560 字节 |
| DEPTH | 640x401@45 RAW16 | 成功，首帧 raw 为 513,280 字节 |
| DEPTH | 320x201@45 RAW16 | 成功，首帧 raw 为 128,640 字节 |
| DEPTH | 1280x800@5 RAW16 | 成功，首帧 raw 为 2,048,000 字节 |

COLOR 高分辨率只切 UVC frame index 不够，原厂 SDK 还会通过 master XU5 下发私有 mode 命令。
该命令已在 `docs/xu-command-map.md` 反编译为 `BX OpenStream(cmd=0x0006)`。
Qt6 demo 已在启动时按所选 COLOR 档位重写 `OpenStream` payload，2026-05-28 实测
`1920x1080@30`、`1280x800@30`、`640x400@30` 均能解码出对应尺寸首帧。
DEPTH 也已在 demo 中按所选档位重写 companion XU3 selector 25：
`1280` 档发 `010203`、`640` 档发 `010208`、`320` 档发 `01020c`。
2026-05-28 实测 `1280x801@45`、`640x401@45`、`320x201@45`、`1280x800@5`
均能在双流 demo 中出首帧。
上述 mode patch 和 master CloseStream 已收口到 `include/gomob_berxel_host_sdk.h`，
Qt6 demo 与 `berxel_host_probe` 共用同一套 SDK API。

### Frame metadata

`pull_raw_frames` / `pull_mjpeg_frames` 每完成一帧都会回调 `UvcFrameInfo`，包含 host
start/end timestamp、UVC PTS/SCR、FID、payload bytes、transport bytes、完成边界来源。
`berxel_host_probe` 会把 dual/single 拉流的帧元数据写到输出目录的 `frames.csv`。
`RgbdFramePairer` 使用双队列最近邻策略，把 COLOR 与 DEPTH 按 host midpoint 配成
`RgbdFramePairInfo`；probe 会同步输出 `pairs.csv`。`P100R3DualSessionStats.rgbd_pairing`
会直接暴露 pair 数、丢弃/排队帧数、last/mean/max host delta 和最后一对的 color/depth
frame number，Android 迁移时不用再解析 CSV 才能拿同步健康度。

2026-05-28 起，MJPEG 帧边界收口到纯 C++ `UvcMjpegFrameAssembler`：同一套逻辑同时支持
UVC EOF、JPEG EOI 和 FID 翻转重同步，且不依赖 libusb，后续 Android 侧可直接复用。
真实双流复测 `.dev/berxel-host-sdk/session-api-dual-mjpeg-assembler-v2-20260528-204904/`：
COLOR 30 帧、`frame_drops=0`、`fid_toggles=29`、`completed_by_jpeg_eoi=30`；
DEPTH 65 帧、`completed_by_size=65`。`frames.csv` 新增 `completed_by_fid` /
`completed_by_jpeg_eoi` 两列，便于定位边界异常。

2026-05-28 追加 `RgbdPairingStats` 和扩展版 `pairs.csv`：每对 pair 会写 color/depth
start/end/midpoint、UVC PTS/SCR 是否存在、FID、payload bytes 和 `within_tolerance`。
`analyze_frame_csv.py` 现在同时报告 stream frame number 是否严格递增、完成边界来源计数、
pair number 是否连续、配对中跳过的 depth frame 数和 pair delta P50/P95/max。
实机短测 `.dev/berxel-host-sdk/session-api-dual-sync-stats-20260528-232236/`：
COLOR 39 帧全部 `completed_by_jpeg_eoi`，DEPTH 82 帧全部 `completed_by_size`；
pair 39 对，pair/color 序号连续，depth 因 45fps 对 30fps 正常跳过 36 帧；
pair delta P50=8.76ms、P95=9.24ms、max=13.48ms，session 退出为
`stopped / duration_reached`。

fixed-size RAW16 边界也已收口到纯 C++ `UvcRawFrameAssembler`，用于 DEPTH / LIGHT_IR。
它会剥 UVC payload header、按固定 frame size 出帧，并在新 header 到来时丢弃残留半帧，
避免预览图像按行平移/滚动。Qt6 demo 的 DEPTH/LIGHT_IR 右侧预览已改为复用该 assembler。
真实双流复测 `.dev/berxel-host-sdk/session-api-dual-raw-assembler-20260528-213513/`：
DEPTH 42 帧、`completed_by_size=42`，COLOR 19 帧、`frame_drops=0`。

`P100R3DualSession` 是当前正式 SDK 会话 API：统一负责 master/companion open、interface claim、
XU replay、UVC commit、可中止 bulk 拉流线程、RGBD pairer、keepalive、CloseStream 和设备释放。
probe 加 `--session-api` 会走该路径。
退出状态机已约束为 idle/failed/stopped 下 `stop()` no-op，只有 opening/streaming/stopping 会写
`stop_reason`；`tests/native_host/berxel_session_state_test.cpp` 覆盖 idle stop 和无 stream setup fail。

2026-05-28 实测：

```bash
scripts/berxel-host-probe.sh --dual --ka-ms 0 --master-all \
  --color-frame 2 --color-interval 333333 \
  --depth-frame 2 --depth-interval 222222 \
  --dur-ms 3000 --out-dir .dev/berxel-host-sdk/rgbd-pair-dual-c1280-d640-v2

python3 native/berxel/host/tools/analyze_frame_csv.py \
  .dev/berxel-host-sdk/rgbd-pair-dual-c1280-d640-v2/frames.csv
```

结果：COLOR 62 帧、DEPTH 132 帧；COLOR 62/62 帧带 UVC PTS/SCR，DEPTH 132/132 帧带
UVC PTS/SCR。实时 `RgbdFramePairer` 输出 61 对，最后 1 帧 COLOR 因测试结束仍在等待下一帧
DEPTH 留在队列里；pair delta 绝对值 P50=6.72ms、P95=7.00ms、max=15.93ms。
首帧产物：COLOR JPEG 为 `1280x800`，DEPTH raw 为 `640*401*2` 字节。

2026-05-28 `P100R3DualSession` 复测：

```bash
scripts/berxel-host-probe.sh --session-api --dual --ka-ms 0 --master-all \
  --color-frame 2 --color-interval 333333 \
  --depth-frame 2 --depth-interval 222222 \
  --dur-ms 3000 --out-dir .dev/berxel-host-sdk/session-api-dual-c1280-d640-20260528
```

结果：session state=`stopped`、stop_reason=`duration_reached`，自动发送 3 条 master
CloseStream；COLOR 62 帧、DEPTH 133 帧、RGBD 61 对。分析脚本输出 pair delta
P50=6.66ms、P95=6.94ms、max=7.02ms。另用默认 keepalive 跑
`.dev/berxel-host-sdk/session-api-dual-keepalive-20260528`，keepalive 44 次、无错误，退出同样为
`stopped / duration_reached`。

`--stop-only` 是实验性清理入口，会向 master XU5 发送 stop stream payload，用于验证
退出路径，不应理解成完整 power-cycle。曾观察到设备进入 companion bulk 空读状态时，
`libusb_reset_device`、sysfs deauthorize/authorize、厂商 SDK stop 都无法恢复，必须物理断电重插。

### Depth raw 语义

P100R3 的 depth payload 不是 RGB/IR 散斑图，而是 raw16 fixed-point depth。2026-05-28
同场采样中，原厂 Linux SDK 对外报告 `pixelType=2`
(`BERXEL_HAWK_PIXEL_TYPE_DEP_16BIT_13I_3D`)；当前设备应按 `raw / 8.0` 转毫米。
早期按 `12I_4D` / `raw / 16.0` 解释会把距离压成一半，这是 depth 图“看起来不对”的明确根因之一。

companion UVC 高帧率三档传输尺寸带一行设备私有/状态行：`1280x801`、`640x401`、
`320x201`；原厂 SDK 对外暴露的 active depth 是 `1280x800`、`640x400`、`320x200`。
host SDK 已提供 `p100r3_depth_active_mode()` / `p100r3_depth_raw_to_mm()` helper，Qt6 demo
现在按 active 高度裁掉状态行，并按 13I_3D 转毫米后再做伪彩。

对照样本：

- 原厂 `controls-demo` 与 host 默认 dense controls 同场采样：
  `.dev/berxel-host-sdk/depth-compare-dense-20260528-223032/`。
- host active raw 平均有效率 `1.0000`，原厂 SDK 平均有效率 `0.9984`；
  median depth 分别约 `384.25mm` / `383.44mm`。
- host-vendor 逐像素 median abs diff 约 `37.81mm`，原厂自身相邻帧约 `36.62mm`，
  host 自身相邻帧约 `36.88mm`；差异已经接近相机自身帧间噪声。
- 用错误的 `/16` 解读 depth 会把距离压成一半；当前统一按 `/8`。

结论：host 已拿到和原厂 SDK 等价的 fixed-point depth，而不是纯散斑；parity 主线从
“用 CPU 补洞伪造 dense 图”调整为“默认下发正确设备侧 dense controls，并保留 raw validity / 噪声基线”。

可重复验收入口：

```bash
./dev.sh harness berxel_depth_parity
```

2026-05-28 最新 harness：`.dev/berxel_depth_parity/`，状态 `OK`；sparse reset
约 `0.129..0.130`，host default dense `1.0000`，vendor dense `0.9984`，
host `--no-depth-controls` `0.1559`，median depth delta `1.19mm`，
host-vendor median abs diff `37.125mm`，相机自身噪声底 `36.625..37.125mm`。

补充线索：该相机据称使用偏振技术。本地 SDK 公开头文件/样例没有暴露 `polarization` 命名的开关，
但存在 `IR` / `LIGHT_IR` intrinsic、NCC confidence、`BerxelDepthProcessor` 等链路。后续 depth parity
不能只按普通结构光补洞理解，需要把材质反射、入射角、金属/高光表面对有效点密度的影响纳入 harness；
processed depth 可以用于拓印/分割实验，量测/点云仍要保留 raw validity mask。
反编译证据链见 `docs/depth-pipeline-reverse.md`。

### Light IR / 散斑图

2026-05-28 已确认原厂 `BERXEL_HAWK_LIGHT_IR_STREAM` 对外输出的是灰度散斑图，
不是 depth fixed-point。自研 SDK 现在支持：

```bash
scripts/berxel-host-probe.sh --session-api --light-ir --ka-ms 0 --master-all \
  --depth-frame 1 --depth-interval 222222 --dur-ms 1500 \
  --out-dir .dev/berxel-host-sdk/session-api-light-ir-fastsave-20260528-194758
```

关键 XU 序列：

- master XU5 `SetProperty(0x0030)`：开 `30 00 01 2d`，关 `30 00 00 00`。
- companion XU3 selector 25：Light IR 模式前缀 `010202`。

UVC transport 是 `1280x801x16`，active 图像是 `1280x800`；像素强度为 10bit 值左移
6 bit。`process_p100r3_light_ir_frame()` 已裁掉状态行并右移回原厂 SDK 语义的
`0..1023` raw16。最新 host 输出：

| 样本 | raw 字节 | 非零比例 | max | mean | P50 | P95 | P99 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 原厂 `readLightIrFrame()` | 2,048,000 | 100% | 1023 | 69.22 | 57 | 104 | 400 |
| host `--light-ir` | 2,048,000 | 100% | 1023 | 69.36 | 57 | 104 | 400 |

预览文件：`.dev/berxel-host-sdk/session-api-light-ir-fastsave-20260528-194758/light-ir-first.pgm`。
UVC 原始包保存在同目录 `light-ir-first-transport.raw`。

### Depth controls

`P100R3DepthControls` 是当前已反编译并落地的 companion HV3 设备侧控制子集：

| 原厂接口 | property | HV3 payload 前缀 | 当前状态 |
| --- | --- | --- | --- |
| `setDepthAEStatus(bool)` | `0x0d` | `0102cb` / `0102c8` | 已实现 |
| `setDepthConfidence(n)` | `0x16` | `0c0201nn` | 已实现，n clamp 到 1..5 |
| `setDepthGain(n)` | `0x0b` | `0611c0013509(n<<4)` | 已实现，手动测试入口 |
| `setTemporalDenoiseStatus(bool)` | `0x1e` | `0c0601nn` | 已实现 |
| `setSpatialDenoiseStatus(bool)` | `0x1f` | `0c0801nn` | 已实现 |

`P100R3DepthControls` 默认就是原厂 dense 组合：
`AE=1, confidence=3, temporal_denoise=0, spatial_denoise=0`。需要故意复现 sparse
设备状态时，probe 可加 `--no-depth-controls`。

probe 参数：

```bash
scripts/berxel-host-probe.sh --session-api --dual --master-all \
  --color-frame 3 --color-interval 333333 \
  --depth-frame 2 --depth-interval 222222 \
  --dur-ms 3000 \
  --out-dir .dev/berxel-host-sdk/default-depth-controls-check-20260528-223715/default
```

2026-05-28 实测：

| 控制 | active 非零比例 | 备注 |
| --- | ---: | --- |
| 原厂复位 `temporal=1, spatial=1` | 12.96% | sparse 状态 |
| host 默认 dense controls | 100.00% | `.dev/berxel-host-sdk/default-depth-controls-check-20260528-223715/default` |
| host `--no-depth-controls` | 15.62% | 故意不下发 dense controls |
| 原厂 `temporal=0` | 85.06% | 单独关闭 temporal 已显著变 dense |
| 原厂 `spatial=0` | 99.84% | 关闭 spatial 后达到原厂 dense |

因此 dense depth 的关键是设备侧 `temporal_denoise=0` / `spatial_denoise=0`，
不是 SDK 侧 `fillHole` 把 sparse raw 补出来。`fillHole` / `maxDepth` 仍保留为
processed API 的可选后处理，不再作为主 parity 路线。

### Processed depth

`process_p100r3_depth_frame()` 是当前自研 SDK 的 processed depth API：

- 输入 transport RAW16，输出 active `uint16_t` depth 和同尺寸 confidence mask。
- raw 有效点原样保留，confidence=`255`；越界或无效点仍为 `0`。
- processed 补洞只写入 processed 层，confidence 随补洞距离从 `180` 衰减到 `32`。
- 默认 `target_valid_ratio=0.99`，`max_fill_distance_px=512` 只是 host 侧实验性补洞；
  dense parity 已由设备侧 controls 解决，量测/点云默认直接使用 active raw depth。
- 2026-05-28 已加入 edge-aware fill：候选补洞点会检查邻域已有深度，超过
  `max_fill_depth_delta_mm=80` 的深度边缘不跨越；被拦截的候选计入 `edge_blocked_pixels`。
- 默认有效距离窗口按 P100R3 规格和 live capture 对齐为 `200..2000mm`，raw fixed-point
  格式为 `13I_3D`。

2026-05-28 历史实机验证（raw frame assembler 修正前，保留作后处理密度参考）：

```bash
scripts/berxel-host-probe.sh --session-api --dual --ka-ms 0 --master-all \
  --color-frame 3 --color-interval 333333 \
  --depth-frame 2 --depth-interval 222222 \
  --dur-ms 3000 --depth-controls-vendor \
  --out-dir .dev/berxel-host-sdk/session-api-depth-processed-target66-r88-20260528
```

结果（基础 target-density 版）：

| 样本 | active 非零比例 | 说明 |
| --- | ---: | --- |
| host raw `depth-best.raw` | 16.1% | transport `640x401`，active `640x400` |
| host processed `depth-best-processed.raw` | 66.0% | 输出 active `640x400` |
| 原厂 `VinRectifyDepthSettingsSmoke/depth.raw` | 65.4% | SDK 输出 |

edge-aware 版历史复测：

```bash
scripts/berxel-host-probe.sh --session-api --dual --ka-ms 0 --master-all \
  --color-frame 3 --color-interval 333333 \
  --depth-frame 2 --depth-interval 222222 \
  --dur-ms 2000 --depth-controls-vendor \
  --out-dir .dev/berxel-host-sdk/session-api-depth-edge-aware-v2-20260528
```

| 样本 | active 非零比例 | 说明 |
| --- | ---: | --- |
| host raw `depth-best.raw` | 16.1% | `raw_valid=41222` |
| host processed `depth-best-processed.raw` | 66.0% | `filled=127738`，`edge_blocked=1069` |
| 原厂 `VinRectifyDepthSettingsSmoke/depth.raw` | 65.4% | SDK 输出 |

同目录还会写 `depth-best-confidence.raw`。Android 迁移时必须把该 mask 一起带上；点云/量测默认只信
active raw depth，VIN 拓印/分割实验才可以显式选择 processed depth。

## Demo 界面

Qt6 demo 保留 VinRectifyGui 的预览面板思路，展示 COLOR + DEPTH 或 COLOR + LIGHT_IR 两路视频和基础帧率/吞吐。
数据源是本目录的自研 host SDK，不链接 Berxel 厂商 `.so`。
DEPTH / LIGHT_IR bulk 会统一剥掉 UVC payload header，再按所选 frame size 对齐成帧。
DEPTH 显示时裁掉 transport 里的额外状态行，直接按 active raw depth 的 13I_3D 转毫米；
LIGHT_IR 则走 `010202` 散斑流，裁掉状态行并右移为 `0..1023` IR10 后做灰度预览。

```bash
scripts/berxel-host-gui.sh --auto-start
```

需要从当前终端脱离时：

```bash
scripts/berxel-host-gui.sh --detach --auto-start
```

无界面自测可用：

```bash
QT_QPA_PLATFORM=offscreen .dev/berxel-host-sdk/vin-rectify-gui-build/gomob_berxel_vin_rectify_gui \
  --auto-start --exit-after-ms 6000 --color-frame 1 --depth-frame 2
```

`--color-frame` 对应 master MJPEG frame index：1=`1920x1080@30`、2=`1280x800@30`、
3=`640x400@30`。`--depth-frame` 对应 companion RAW16 frame index：1=`1280x801@45`、
2=`640x401@45`、3=`320x201@45`、4=`1280x800@5`。加 `--light-ir` 或在界面勾选
`LIGHT_IR 散斑` 时，右侧预览切到原厂 `BERXEL_HAWK_LIGHT_IR_STREAM` 等价的散斑图。

2026-05-28 实测：

```bash
DISPLAY=:1 scripts/berxel-host-gui.sh --auto-start --exit-after-ms 3500 \
  --light-ir --color-frame 3 --depth-frame 2
DISPLAY=:1 scripts/berxel-host-gui.sh --auto-start --exit-after-ms 3000 \
  --color-frame 3 --depth-frame 2
```

LIGHT_IR 路径日志确认 master PWM on、companion `010202`、`companion-light-ir` UVC commit、
首帧 `640x401 -> 640x400`，退出发送 PWM off + companion `010200`；普通 DEPTH 路径同样
可出首帧并发送 3 条 master CloseStream。
