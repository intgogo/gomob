# scan_conf_weighting — 置信加权重建 harness

## 目的

验证「端侧 native 重建按 per-pixel 置信加权」这一落地(04b Stage3 预览 / 多视角融合的端侧形态)
的**行为收益**:density-first 稠密但带噪的深度,经 IR/时域置信软加权后,TSDF/ICP 能否产出更贴真表面的重建。

背景:M1.6.19 证明 IR 散斑置信能识别弱回波/噪声像素(AUC 0.73–0.82),掩码后单帧误差 8%→0.3%。
本 harness 验证把该置信**接进重建**(TSDF voxel 加权 + ICP 加权 Umeyama)是否兑现为重建质量提升。

## 落地点(native,向后兼容可选 conf)

- `TsdfVolume::Integrate(..., const uint8_t* conf=nullptr)`：`w1=min(w0+conf/255, clamp)`,conf=0(无效/飞点)不贡献,conf=255 等同旧均权。
- `IcpRegister(..., const float* src_weights=nullptr)`：加权刚体拟合(Kabsch with weights),低置信点降权防噪声拉偏位姿。
- `SessionIngest(..., const uint8_t* conf=nullptr)`：透传 conf 给 TSDF + 经 `DownsampleCloudConf` 与点云对齐喂 ICP。
- JNI `scanSessionIngest(..., confBuffer)` + Kotlin `scanSessionIngest(..., confidence: ByteBuffer?=null)` + ViewModel 传 `DepthFrame.confidence`。

## 跑法

```bash
OUTPUT_DIR=.dev/scan_conf_weighting bash tests/harness/scan_conf_weighting/run.sh
```

- `[1]` 合成球面基准 `recon_conf_bench`(确定性,有真值)= **硬判定门**。
- `[2]` 真实硬件基准 `real_recon_bench`(探索性)= 用 host_capture 真数据看相对趋势;SDK+相机在则自动现采。

公式正确性(单测)走 `scripts/native-host-test.sh` 的 `conf_weight_test`(TSDF 加权公式 / ICP 加权抗噪 / 向后兼容)。

## 判定门(合成,确定性)

合成已知球面(R=60mm @z=400mm),12 帧,**45% 像素随机弱回波**(σ40mm + 概率粗飞点,conf=40),
其余好像素(σ2mm,conf=255)。加权 vs 均权 TSDF→Marching Cubes 提面,度量顶点到真球面 RMS。

判定门 = 加权 ① RMS 降 ≥30% ② 覆盖真可见球冠 ≥85% ③ 内点(<5mm)占比 +20pt 以上。

### 结果(2026-05-30)

| 重建 | 顶点 | 表面 RMS | 平均\|误差\| | 内点(<5mm) |
|---|---|---|---|---|
| 均权 | 79338 | 14.30mm | 10.37mm | 40% |
| **加权** | 22854 | **0.81mm** | **0.59mm** | **100%** |

**RMS 降 94.4%、内点占比 40%→100%、覆盖真球冠 98%。** 加权把稠密带噪表面拉回真球面,
覆盖完整球冠且不牺牲真实覆盖。(均权顶点更多是噪声把表面打成毛刺微三角的虚高,XY 范围 68mm
也超出真轮廓 59mm = 噪点被甩到球外,非真覆盖。)

**系统性恒弱区子场景**(左 40% 区域每帧恒 conf=40+大噪,无任何好观测):加权仍**覆盖真球冠 98%、不空洞**
(conf=40 × 12 帧累计 weight≈1.88 > min_weight=1.0 仍过门),RMS 9.00→0.97mm。即 min_weight 门只挖
"极少帧+低 conf"的真噪点,连续扫描下恒弱区靠多帧累计照样成面——**用数据回应了"加权挖空洞"的担忧**。

## 真实数据(探索性,host_capture)

真 P100R3 density-first depth(99.8% 稠密)+ 真 light-IR 算 conf(复用 shipping `p100r3_ir_speckle_confidence`,
host 纯 10bit IR 经 `(ir>>2)<<8` 适配进高字节),静态 ~40cm 目标,以 18 帧逐像素时域中值面为稳健参考算单向 chamfer:

| 重建 | 顶点 | chamfer→中值面 |
|---|---|---|
| 均权 | 506484 | 6.99mm |
| **加权** | 290427 | **4.03mm** |

**加权 chamfer 相对均权降 42%**——真硬件数据上,加权重建更贴近抗飞点的稳健中值面。
探索性(内参近似、无 CAD 真值、单视角),只看相对趋势;绝对门交给合成基准。
⚠ 参考面(时域中值)来自同一批帧,非独立真值——中值与加权都抗飞点,故"加权更贴中值"含同源偏好;
但它确实说明**均权被中值/加权共同抵抗的飞点拉偏**,这点成立。绝对证明看合成基准(有真球 CAD 真值)。

## 诚实边界

- 合成弱像素为「逐帧随机」(模拟散斑/噪声逐帧抖动)→ 每体素跨帧既有好观测也有坏观测,加权得以混合取优。
  「系统性恒弱」区域(某表面始终弱回波)则靠 conf 低 + min_weight 门排除(掩码收益,见 M1.6.19 mask_recovery)。
- 多视角(不同位姿)下加权才有跨视角混合的完整收益;本 harness 固定相机+identity 位姿,
  收益来自跨帧而非跨视角。真多视角端云融合按 conf 加权是 04b 主线的云端 worker 工作(M3.14,待建)。
