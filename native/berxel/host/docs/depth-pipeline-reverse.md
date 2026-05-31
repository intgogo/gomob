# P100R3 Depth Pipeline 反编译记录

更新时间：2026-05-28

## 结论

P100R3 正常 `DEPTH` 流里的核心“散斑/结构光匹配到深度值”已经在相机设备侧完成，host SDK 收到的是带深度语义的 RAW16 fixed-point depth，不是 RGB/IR 散斑图。

如果需要直接看散斑图，应走原厂 `LIGHT_IR` stream。2026-05-28 已抓包并在 host SDK 复现：
companion 模式前缀为 `010202`，master 通过 `SetProperty(0x0030)` 打开 45fps PWM；
UVC transport 是 `1280x801x16`，active 图像裁成 `1280x800` 后右移 6 bit，得到和原厂
`readLightIrFrame()` 一致的 `0..1023` raw16 灰度散斑。

原厂 SDK 仍然做了大量 CPU 后处理：非法值剔除、温度补偿、denoise、hole fill、edge/ground/plane/curve filter、registration、depth optimizer 等。也就是说我们要平替原厂 SDK，重点不是在 host 端重新做从散斑到深度的匹配，而是复现设备初始化、模式切换、raw depth 语义、SDK 后处理和 raw validity/confidence mask。

2026-05-28 追加实测修正：原厂 dense depth 的关键不是 `fillHole` 在 CPU 侧把 sparse raw 补满，而是设备侧 `setTemporalDenoiseStatus(false)` / `setSpatialDenoiseStatus(false)`。从 sparse 状态开始，temporal 关闭可把有效率提高到约 85%，spatial 关闭后到约 99.84%；重新打开二者会回到 sparse。host SDK 相同 XU payload 已复现这个状态切换，默认 dense controls 后 active raw 有效率为 100%。

关于“偏振技术”：当前本地头文件、样例、动态符号和字符串里没有发现 `polarization` / DoLP / AoLP 这类明确的 SDK 侧偏振处理入口。库内出现的 `logPolar` / `linearPolar` / `cartToPolar` 是 OpenCV 极坐标函数名，不能作为偏振算法证据。偏振更可能在光学/传感器/固件侧参与成像和匹配质量，SDK 侧目前只看到 IR 辅助处理、NCC confidence、曝光/增益/电流、温补和后处理。

## 证据链

### UVC 输出已经是深度

公开头文件定义：

- `BERXEL_HAWK_PIXEL_TYPE_DEP_16BIT_12I_4D = 0x01`
- `BERXEL_HAWK_PIXEL_TYPE_DEP_16BIT_13I_3D = 0x02`
- `BERXEL_HAWK_PIXEL_TYPE_DEP_16BIT_14I_2D = 0x04`
- `BERXEL_HAWK_PIXEL_TYPE_IR_16BIT = 0x03`
- `BERXEL_HAWK_DEPTH_STREAM = 0x02`
- `BERXEL_HAWK_IR_STREAM = 0x04`
- `BERXEL_HAWK_LIGHT_IR_STREAM = 0x20`

这说明原厂 API 把 depth 和 IR/LIGHT_IR 明确分成不同 stream。我们在不链接 Berxel `.so` 的 host SDK 里直接从 companion UVC 读到的 depth payload 也是 `1280x801x16` / `640x401x16` / `320x201x16`，裁掉状态行后对应 active `1280x800` / `640x400` / `320x200`。2026-05-28 live capture 中原厂 SDK 报告 `pixelType=2`，即 `13I_3D`，当前设备按 `raw / 8.0` 毫米解释；早期按 `12I_4D` / `raw / 16.0` 会把距离压成一半。

如果 SDK 端负责“散斑匹配到深度”，DEPTH 流通常需要向 host 传 IR 图、左右/多相图或相关原始观测；但当前普通 DEPTH UVC payload 已经是 fixed-point depth。

### `BerxelStreamImplDepth::processFrame()` 入口

`libBerxelUvcDriver.so` 反汇编：

```text
BerxelStreamImplDepth::processFrame()
  if current_frame != null:
    BerxelDepthProcessor::addFrame(current_frame)
    CountFrame::record/show(current_frame)
    release current_frame
```

这说明深度流入口拿到的是已经封装好的 `BerxelFrameRef`。入口处没有看到从原始散斑图生成深度图的步骤。

### `BerxelDepthProcessor` 是后处理链

动态符号和 `processDepth()` 反汇编能看到以下 SDK 侧处理：

- `BerxelDepthAlgorithm::rotateImage`
- `removeIllegalData`
- `BerxelDepthProcessor::cropEdgeForRemoveNosie`
- `BerxelDepthProcessor::removeNoise`
- `BerxelDepthProcessor::clearGround`
- `BerxelDepthAlgorithm::onNosieFilter`
- `BerxelDepthAlgorithm::onDenoise`
- `BerxelDepthAlgorithm::onGetHoleData`
- `BerxelDepthAlgorithm::onFillHoleInpaintColor`
- `BerxelDepthAlgorithm::onTemperaTureCompensation*`
- `BerxelRegistration::translateDepthImage`
- `BerxelDepthOptimizer::precisionOptimizeLinear`
- `BerxelDepthOptimizer::flatOptimizeLinear`
- `BerxelDepthOptimizer::rotateOptimizeLinear`
- `BerxelDepthOptimizer::convertPixelToWorld`

这些命名和调用形态都指向“已有深度图上的校正/补洞/滤波/配准/点云几何优化”，不是原始散斑相关、视差搜索或相位解算。

库内还有 `inner_process_with_IR(cv::Mat, cv::Mat&, cv::Mat, cv::Mat, int)` 和 `BerxelIrProcessor::processIrStream()`。2026-05-30 决定性反汇编已证：这套 IR 引导深度精修是**导出但运行时从不调用的死 API**，普通 DEPTH 流不经它。详见下节「交织 IR 帧不进 SDK 深度链」。

### 设备侧控制更像深度引擎参数

HV3/Sonix host protocol 里能看到设备侧命令：

- `setDepthAEStatus`
- `setDepthConfidence` / `setNCCThreshold`
- `setDepthGain`
- `setDepthExposureTime`
- `setDeptheCurrent`
- `setDisplayDenoise`
- `setDepthDefaultGainAndExposure`
- `get2D/3DGainRange`
- `get2D/3DExposureTime`
- `getDeviceTemperature`

其中 `NCCThreshold` 是相关匹配/置信度阈值的强线索。阈值在设备侧下发，而不是 SDK 侧函数参数，也支持“设备侧先做深度候选/置信度，SDK 再做显示级后处理”的判断。

控制项实测：

- `AE=1`、`confidence=3` 不是 dense/sparse 的主开关。
- `temporal_denoise=0` 是第一段 dense 触发，从约 13% 到约 85%。
- `spatial_denoise=0` 是最终 dense 触发，到约 99.84%。
- dense/sparse 是设备侧粘性状态，跨进程保留；测试必须先显式复位。

## 6MB `<SN>_params.bin` 是温度补偿表（2026-05-29 三平台反编译订正）

之前把这 6MB 文件泛称"离线 blob / 散斑参考 + 标定"是**错的**。三平台（Linux/Android/Windows）`libBerxelUvcDriver` 反汇编逐字节一致，实证：

- 加载者 = `BerxelDepthProcessor::setTemperaTureCompensationStatus`（Linux VA≈0xf3270 / Android≈0xac984 / Win≈0x180017e40）。
- 校验文件 `== 0x5DC000`（6,144,000 = 1280×800×6），不符打印 `fileSize is not correct!` 直接放弃。
- 一次性 `fread` 整入缓冲，再两次 `memcpy` 各 `0x2EE000`（3,072,000 = 1280×800×3）切成**两张逐像素系数表**（两个温度态，82.8% 字节相异、非镜像），喂给 `onTemperaTureCompensation640_137(表A, 表B)`，按当前 `getDeviceTemperature` 插值修正**已重建好的深度图**。
- 设备类型分流：默认 `<SN>_params.bin`；type 0x19/0xe → `params137_0.bin`+`params137_1.bin` 双文件；type 0xa/0xc/0x11/0x14 → `params100Q.bin`。SDK 自带的 `params*.bin` 是**产品级静态资产**（三平台同 sha256 `f330227…`），非逐机标定。
- 关键字符串 `Device is enable hw temperature compensation, can not use soft temperature compensation`：设备开了硬件温补时，SDK **跳过**这张软件表 → 此时设备直出的 `0x0600` 深度已是温补过的。
- 里面**没有**散斑参考图 / pattern / 视差重建 LUT。自研结构光所需的参考散斑、投影器基线、参考距离在任何可导出数据里都拿不到（烘进 firmware DSP）。

**散斑→深度确在设备 ASIC**：全库唯一带 disparity/NCC 的 Berxel 符号是 `Get/SetNCCThreshold`，反汇编只调 `ExecuteCMD2` 经 USB 把阈值下发给设备引擎；host 无任何块匹配/三角化代码；`processDepth` 每帧零文件 IO，全是对 16bit 深度图的后处理。

**可导出的标定**（真机 `berxelGetProperty(propID=0x4a, 156B)` 或 adb pull `<SN>_params.bin` 解析）：156B = `_BerxelHawkIntrinsicInfo`，5 个连续块各 36B（末块 12B）：`[0:36]` color 内参、`[36:72]` ir 内参、`[72:108]` **liteIr 内参（深度流运行期实际用这块）**、`[108:144]` depth→color 旋转 R(3×3)、`[144:156]` 平移 T(tx,ty,tz mm，是配准平移**不是结构光基线**）；每块 = fx,fy,cx,cy,k1,k2,p1,p2,k3。分辨率缩放常量 0.5/0.25/0.125（全分辨率内参除 2/4/8）。

## 交织 IR 帧不进 SDK 深度链（2026-05-30 决定性反汇编）

回答"companion 交织的 0x0500 IR/phase 帧(占 ~40% 带宽)是否被 SDK 用来增强深度"。
**结论：不进。** SDK 里**存在**一整套 IR 引导深度精修算法，但它是**导出却零调用者的死 API**，运行时深度管线完全不碰它。

### SDK 里确实有 IR→深度精修算法（扁平 C 函数模块）

`libBerxelUvcDriver.so`(及静态复制进 `libBerxelNetDriver.so`)含一组**非 `berxel::` 命名空间的扁平导出函数**，自包含调用图：

```
EdgeEnhance(depth)                       → inner_process        → region_fit
EdgeEnhanceInfraRed*(depth, ir)          → inner_process        → region_fit
inner_porcess_thread(...)                → inner_process        → region_fit
EdgeEnhance_Anti_Alising(depth, ir)      → EdgeEnhance_inpaint
inner_porcess_with_IR_thread(...)        → inner_process_with_IR → region_fit
```

算法语义(寄存器级追踪)：`inner_process_with_IR` 对 **IR 强度图**跑 `CannyEdge`(阈值 3/20/3)，把边缘作为约束掩码喂 `region_fit(depth, &out, ir_edges, mode)`——`region_fit @0x15e230` 在 Canny 边缘约束内对深度做**加权最小二乘平面拟合 / 补洞**。对照的无 IR 版 `inner_process` 则对 **depth 自身**跑 Canny。**IR 的增量 = 用 IR 的清晰边缘约束深度区域拟合，不依赖噪声大的深度梯度**。`_test` 变体(`EdgeEnhance_inpaint_Color_test`)暴露这是实验性模块。

### 三重证明：这套模块运行时零调用

1. **无 PLT JUMP_SLOT**：库内经 PLT 被调的算法函数只有 `inner_process` / `inner_process_with_IR` / `region_fit` / `EdgeEnhance_inpaint`(均为模块**内部**互调)；顶层入口 `EdgeEnhance*` / `EdgeEnhanceInfraRed*` / `inner_porcess_*thread` **无任何 JUMP_SLOT**(对照 `BerxelDepthAlgorithm::onNosieFilter`/`onTemperaTureCompensation*`/`BerxelDepthOptimizer::*` 都有 → PLT 假设成立)。
2. **无 lea 引用**：全库无 `lea` 装载 `inner_porcess_with_IR_thread`(0x1799b0)/`inner_porcess_thread`(0x17ac90)地址 → **没人 `std::thread` 启动这些 worker**。
3. **无跨库 import**：`libBerxelHawk` / `libBerxelCommonDriver` / `libBerxelInterface` / `libBerxelNetDriver` 的动态符号表里**没有一个 `U`(undefined)指向这套函数**；`libBerxelHawk` 虽 import `dlsym`，但其 `.rodata` 无这些 mangled 名字符串 → 无运行时按名解析路径。

### 活的深度链路（不含 IR）

`BerxelStreamImpl::newFrame → getFrameType() → 虚 processFrame()` 按帧类型**流级分流**：
- 0x0600 → `BerxelStreamImplDepth::processFrame → BerxelDepthProcessor::addFrame → processDepth* → removeNoise/onNosieFilter/onDenoise/onFillHoleInpaintColor/onTemperaTureCompensation* + BerxelDepthOptimizer::*`（**全程不引用 IR 帧**）。
- 0x0500 → `BerxelStreamImplIR::processFrame → BerxelIrProcessor::addFrame → processIrStream`(memcpy+rotate)→ `setNewFrameCallback` 注册的**上层 App 回调**；`setNewFrameCallback` 唯一调用者是 `BerxelStreamImplIR::startImpl`，即**只有 App 显式开 IR/LIGHT_IR 流时才有人消费**，否则 IR 帧丢弃。

### 含义

- 设备 ASIC 直出的 0x0600 metric 深度，SDK 只做**无 IR 的**温补/去噪/补洞/几何优化。交织 IR 对"原厂深度质量"零贡献。
- IR 帧是**独立的一等输出流**(IR/LIGHT_IR，用于预览/瞄准/弱光/曝光检查)，被多路复用在同一 ep 上；纯 depth 消费方眼里它是额外带宽。
- **复刻这套 `inner_process_with_IR`(CannyEdge(IR)+region_fit)做离线原型量化后，已证伪"IR 边缘引导有益"**(2026-05-30，harness `tests/harness/depth_ir_guided/`)：**0x0500 是结构光散斑帧**，Canny 检到的是投射散斑不是物体边界 → IR 边缘对真边界 F1 仅 0.25（去散斑后 recall 崩、F1 更低），远低于单帧深度边缘的 0.88；留一法补洞 RMS IR 引导 328mm vs depth-only 73mm。**结论：维持 depth-only 精修，不接 IR 边缘引导**，这也解释厂商为何把它留作死代码。IR 的潜在价值不在边缘而可能在**置信/有效性**(无回波/强光饱和=深度不可信)，属未验证的另一实验。见 [[finding_p100r3_depth_ir_interleaved_2026-05-29]]。

## 偏振判断

当前能确认的是：

- SDK 公开 API 没有偏振专用开关。
- `nm` / `strings` 没有发现 `polarization`、DoLP、AoLP、Stokes 这类偏振处理符号或字符串。
- `polar` 相关字符串来自 OpenCV 极坐标变换，不等价于光学偏振处理。
- SDK 有 IR/LIGHT_IR stream、IR processor、NCC confidence 和 `inner_process_with_IR`，这些可能和偏振硬件的输出质量相关，但名字和调用证据不足以说 SDK 做了偏振解算。

因此当前工作假设是：偏振主要属于硬件/固件成像链路。自研 SDK parity 需要在 harness 里覆盖偏振最影响的场景：高光、金属、玻璃/半透明、黑色材质、斜入射角，而不是在 CPU 后处理里盲目补成一张“好看但不可信”的稠密图。

## 为什么相机能算深度

“相机里没有强 CPU”这个直觉是对通用 CPU 而言成立，但深度相机通常不是靠一个通用 CPU 做全部计算。它可以用传感器 ISP、专用 depth ASIC、DSP、FPGA 或 MCU+硬件加速模块完成相关匹配、置信度和初步滤波，再通过 USB 输出 16-bit depth。

对 P100R3 来说，`640x400@45` 的 RAW16 depth 输出带宽约 23 MB/s，USB2 足够承载；设备侧专用硬件做结构光/相关匹配也合理。SDK 侧再用 x86/ARM CPU 做温补、补洞、配准、点云转换，符合当前反编译证据和实测数据。

## 对自研 SDK 的要求

- DEPTH 输入层继续按 RAW16 fixed-point depth 实现，保留状态行裁剪和 `13I_3D` 转毫米。
- 默认启动时下发 `AE=1, confidence=3, temporal=0, spatial=0`，直接取得 dense active raw depth。
- processed depth 只能作为 VIN 拓印、分割和弱置信填补；量测/点云默认必须带 raw validity/confidence mask。
- parity 目标应拆成两层：raw parity 对齐设备输出语义，processed parity 对齐原厂 `BerxelDepthProcessor` 后处理效果。
- raw parity 判定必须带相机自身帧间噪声基线；当前 host-vendor median abs diff 约 37.81mm，
  原厂自身相邻帧约 36.62mm，已经接近噪声底。
- 当前 host SDK 已把 processed depth 从纯 target-density BFS 推进到 edge-aware fill：补洞候选会检查邻域深度一致性，避免跨明显深度边缘扩散。
- 后续继续反编译 `processDepth2/3`、`onFillHoleInpaintColor`、`onDenoise`、`onTemperaTureCompensation*` 和 `inner_process_with_IR`，并做同场景 vendor SDK vs host SDK 捕获。
