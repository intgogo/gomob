# Berxel iHawk P100R3 原厂 XU 命令图谱

本文件记录 Linux host 自研 SDK 迁移前已经反编译出的原厂 XU 命令。
完整逐条输出由脚本生成到 `.dev/berxel-host-sdk/xu-decode/`：

- `xu_commands.csv`：每一次 XU payload。
- `xu_unique_commands.csv`：按语义归并后的唯一命令。
- `xu_opcode_summary.md`：统计、模式 payload 与结论。

生成命令：

```bash
python3 native/berxel/host/tools/decode_xu_commands.py
```

## XU 端点

| 设备 | XU unit | selector | wIndex | 协议 |
| --- | --- | --- | --- | --- |
| master `0603:001f` | 5 | 1 | `0x0500` | 64B `BX` |
| companion `3558:1012` | 3 | 25 | `0x0300` | 512B Sonix/HV3 raw |
| companion `3558:1012` | 3 | 30 | `0x0300` | 4096B 配置页 |

## BX 包头

`libBerxelUvcDriver.so` 的 `berxel::BerxelHostProtocol::berxelFillupCmd` 反汇编确认格式：

```text
uint16 magic = 0x5842      # bytes: 42 58, ASCII "BX"
uint16 payload_len
uint16 cmd
uint16 subcmd
uint8  payload[payload_len]
```

原厂 XU SET_CUR 固定发 64 字节，`payload_len` 只描述有效载荷长度，其余补零。

## BX 命令号

| cmd | 名称 | payload |
| --- | --- | --- |
| `0x0000` | OpenDevice | 空 |
| `0x0001` | CloseDevice | 空 |
| `0x0002` | ResetComponent | 空 |
| `0x0003` | KeepAlive | `uint16 value` |
| `0x0004` | GetProperty | `uint16 property_id` |
| `0x0005` | SetProperty | `uint16 property_id + value` |
| `0x0006` | OpenStream | `uint16 stream,width,height,fps,aux0,aux1` |
| `0x0007` | CloseStream | `uint16 stream` |
| `0x000a` | InitUploadFile | 文件上传初始化 |
| `0x000b` | WriteUploadFile | 文件上传块 |
| `0x000c` | FinishUploadFile | 文件上传结束 |
| `0x000d` | DownloadFileChunk | `file_type,offset,size`，offset/size 可能是 16 或 32 bit |
| `0x000e` | StartUsbStreamOrFilePull | `uint16 arg0,arg1` |
| `0x000f` | StopFilePull | `uint16 file_type` |
| `0x0010` | InitDownloadFile | `uint16 file_type` |
| `0x0011` | FinishDownloadFile | `uint16 file_type` |

## 已观察属性号

| property | 名称 | 方向 |
| --- | --- | --- |
| `0x0000` | 设备状态探测 | GET |
| `0x0006` | HostTime / 系统时间同步 | SET |
| `0x0015` | StreamStatus / 设备流状态 | SET |
| `0x0017` | LogMode | SET |
| `0x0028` | 原厂初始化探测 | GET |
| `0x002a` | 原厂初始化探测 | GET |
| `0x0030` | StreamFlagMode / 设备流标志 | SET |

## COLOR 模式命令

COLOR 不只靠 UVC probe/commit 切分辨率；原厂还会给 master XU5 发
`BX OpenStream(cmd=0x0006)`。已抓到的原厂样本是：

```text
42580c00060000000100800290010f00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
```

解码结果：

```text
stream=1 width=640 height=400 fps=15 aux0=0 aux1=0
```

按同一结构合成并已在 Qt6 demo 实机验证的 payload：

| 模式 | 64B payload |
| --- | --- |
| `640x400@30` | `42580c00060000000100800290011e00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000` |
| `1280x800@30` | `42580c00060000000100000520031e00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000` |
| `1920x1080@30` | `42580c00060000000100800738041e00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000` |

这些 payload 是根据反编译结构合成的，不是原厂高分辨率抓包原文。
2026-05-28 在 Linux host Qt6 demo 中分别选择三档 COLOR，首帧 JPEG 解码尺寸均与目标一致。

## DEPTH / companion 模式命令

companion XU3 selector 25 使用 512B raw 命令。当前抓包里有：

| payload 前缀 | 推断 |
| --- | --- |
| `011500...` | 初始化/状态命令 `0x15` |
| `010a00...` | 初始化/状态命令 `0x0a` |
| `011901...` | 初始化/状态命令 `0x19` |
| `010200...` | 停止或默认流模式 |
| `010208...` | depth 640 档 |

`BerxelHostProtocolHV3::berxelProtocolOpenStream` 反汇编给出的 depth 档位映射：

| depth width | selector 25 payload 前缀 |
| --- | --- |
| 1280 | `010203` |
| 640 | `010208` |
| 320 | `01020c` |

host SDK 已按该映射在 companion init replay 前重写 selector 25 的 OpenStream payload；
Qt6 demo 与 `berxel_host_probe` 共用该 API。2026-05-28 实机验证：

| DEPTH 模式 | selector 25 payload 前缀 | demo 首帧 |
| --- | --- | --- |
| `1280x801@45` | `010203` | 通过 |
| `640x401@45` | `010208` | 通过 |
| `320x201@45` | `01020c` | 通过 |
| `1280x800@5` | `010203` | 通过 |

## LIGHT_IR / 散斑图命令

2026-05-28 通过原厂 `readLightIrFrame()` 抓包确认，`BERXEL_HAWK_LIGHT_IR_STREAM`
不是普通 DEPTH raw，而是可直接显示为灰底密集点的散斑/Light IR 图。

原厂启动序列的关键差异：

| 设备 | 命令 | payload |
| --- | --- | --- |
| master XU5 | `BX SetProperty(0x0030)` | 开：`42580400050000003000012d...`，关：`425804000500000030000000...` |
| companion XU3 selector 25 | Light IR 模式 | `011901...`、`010200...`、`010202...` |
| companion UVC | VS probe/commit | `frame=1 interval=222222`，transport `1280x801x16` |

其中 master `0x0030` payload 是 `{property=0x0030, enabled, fps}`；`0x2d`
即 45fps。之前只从反汇编猜成 `cmd=0x0008` 不正确，抓包实锤为 `cmd=0x0005 SetProperty`。

host SDK 已按抓包落地：

- `make_p100r3_master_force_internal_pwm_trigger_payload(true, 45)` 生成
  `42580400050000003000012d...`。
- `patch_p100r3_companion_light_ir_open_stream_payloads()` 把 companion OpenStream
  前缀改成 `010202`。
- `process_p100r3_light_ir_frame()` 裁掉状态行，并把 UVC transport 中左移 6 bit
  的 10bit 强度值恢复成原厂 SDK 对外的 `0..1023` raw16。

实测对照：

| 样本 | raw 字节 | 非零比例 | max | mean | P50 | P95 | P99 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 原厂 `readLightIrFrame()` | 2,048,000 | 100% | 1023 | 69.22 | 57 | 104 | 400 |
| host SDK `--light-ir` normalized | 2,048,000 | 100% | 1023 | 69.36 | 57 | 104 | 400 |

最新产物：
`.dev/berxel-host-sdk/session-api-light-ir-fastsave-20260528-194758/light-ir-first.pgm`。

## DEPTH 设备侧控制命令

`libBerxelInterface.so` 的 wrapper 先把原厂 API 映射成 property，再由
`BerxelDeviceHV3::setProperty` 分发到 `BerxelHostProtocolHV3`。当前已确认并落地到
host SDK 的设备侧子集如下，均走 companion XU3 selector 25、`wValue=0x1900`、
`wIndex=0x0300`、512 字节 payload，SET 后读回一次以贴近原厂 `ExecuteCMD2`：

| 原厂 API | property | HV3 方法 | payload 前缀 | host SDK |
| --- | --- | --- | --- | --- |
| `setDepthAEStatus(true)` | `0x0d` | `berxelSetAEStatus` | `0102cb` | `make_p100r3_depth_auto_exposure_payload(true)` |
| `setDepthAEStatus(false)` | `0x0d` | `berxelSetAEStatus` | `0102c8` | `make_p100r3_depth_auto_exposure_payload(false)` |
| `setDepthConfidence(n)` | `0x16` | `berxelSetNCCThreshold` | `0c0201nn` | `make_p100r3_depth_confidence_payload(n)` |
| `setDepthGain(n)` | `0x0b` | `berxelSetDepthGain` | `0611c0013509(n<<4)` | `make_p100r3_depth_gain_payload(n)` |
| `setTemporalDenoiseStatus(n)` | `0x1e` | `berxelSetQutlierRemoval` | `0c0601nn` | `make_p100r3_depth_temporal_denoise_payload(n)` |
| `setSpatialDenoiseStatus(n)` | `0x1f` | `berxelSetDisplayDenoise` | `0c0801nn` | `make_p100r3_depth_spatial_denoise_payload(n)` |

2026-05-28 重新按设备状态切换验证后，结论修正为：

- dense/sparse 是设备侧状态，会跨进程粘住。
- 从 sparse 状态开始，原厂 `setTemporalDenoiseStatus(false)` 可把有效率从约 13% 提到约 85%；
  再执行 `setSpatialDenoiseStatus(false)` 可到约 99.84%。
- 执行 `setTemporalDenoiseStatus(true)` / `setSpatialDenoiseStatus(true)` 会回到 sparse。
- host SDK 同样 payload 经复位验证可生效：默认 dense controls 后 active raw 有效率为 100%，
  `--no-depth-controls` 为约 15.62%。

因此当前 SDK 默认启用 `AE=1, confidence=3, temporal=0, spatial=0`，
这才是原厂 dense depth 的关键路径；`fillHole` / `maxDepth` 不再被视为 dense parity 主因。

selector 30 的 4096B 命令当前只看到配置页写入：

| payload 前缀 | 解码 |
| --- | --- |
| `1c0002000040000001...` | page `0x00004000` |
| `1c0002000041000001...` | page `0x00004100` |

## 关键结论

- COLOR 高分辨率不显示的根因不是 JPEG 解码，而是 master XU5 没有随 UVC frame index 同步下发对应 `OpenStream` 私有模式。
- 原厂 `640x400@15` payload 已完整反编译，`640x400@30`、`1280x800@30`、`1920x1080@30` 已按结构合成并通过 demo 首帧实机验证。
- master 初始化里大量 `cmd=0x000d` 不是重复控制命令，而是原厂按块读取参数/文件数据。
- companion depth 三档模式可以从 HV3/Sonix 反汇编直接映射，host SDK 已完成 XU 模式切换 + UVC commit + 首帧验证。
- `P100R3DualSession` 已把 dual 同步、MJPEG 帧边界、可中止拉流、keepalive、CloseStream 和释放顺序收进正式 SDK API；`berxel_host_probe --session-api` 已完成实机验证。
