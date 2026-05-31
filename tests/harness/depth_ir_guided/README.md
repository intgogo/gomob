# depth_ir_guided — IR 引导深度精修离线原型 harness

## 目的

回答"companion 交织的 0x0500 IR/phase 帧能不能用来增强深度质量"。复刻厂商 SDK 里
那套**导出却零调用者的死 API** `inner_process_with_IR`(`CannyEdge(IR) + region_fit(depth, IR边缘)`，
见 `native/berxel/host/docs/depth-pipeline-reverse.md`「交织 IR 帧不进 SDK 深度链」），
在真实交织 dump 上量化它相对 depth-only 的增益,数据驱动决定是否接入管线。

## 跑法

```bash
OUTPUT_DIR=.dev/depth_ir_guided bash tests/harness/depth_ir_guided/run.sh
# 需带 numpy + cv2 的解释器(默认 /root/lilw/miniconda3/bin/python3)
# 输入:.dev/depth-4b-analysis/dual_raw_NN.bin(DUMP 按钮采,513280B/帧,640x401 u16)
```

`prototype.py` 采样产出 + `analyze.py` 判定 正常/警告/异常。

## 两条可判定指标

数据是稠密深度帧(无真实洞),故:

1. **边缘对真边界 F1**:18 帧近静态深度的**时域中值=低噪真值** → Canny 出真边界;
   比 IR 边缘(原始 + 去散斑变体)vs 单帧深度边缘对真边界的 precision/recall。
2. **留一法补洞 RMS**:挖掉已知有效像素当合成洞,两法**填同一批洞**(只换边缘来源:
   IR vs 单帧深度),以时域中值为真值比 RMS(整体/近边界/远边界)。两法用同一区域平面拟合
   填充机制(无平面性门限)保证覆盖公平。

## 结论(2026-05-30,小米 2510DRK44C dump)

**⛔ 朴素 IR 边缘引导无益,维持 depth-only 精修。**

| 指标 | IR 引导 | 深度自身 |
|---|---|---|
| 边缘对真边界 F1 | 0.25(去散斑最佳仍 0.25) | **0.88** |
| 留一法补洞 RMS | 328mm | **73mm** |
| 近边界 RMS | 314mm | 116mm |

根因:**0x0500 是结构光 IR 帧,表面满是投射散斑点阵**,Canny 检到的是散斑不是物体边界
(样张 `s01_edge_ir.png` vs `s01_edge_true.png`)。去散斑(median/形态学)能提精度但把真边界
一起抹掉(recall 崩),F1 反降。而这批深度本身稠密、单帧边缘 F1 已 0.88,depth-only 边缘感知
处理已够。这也解释了厂商为何把 `inner_process_with_IR` 留作死代码。

## ★ 但 IR 作"置信/有效性"信号成立(2026-05-30,confidence_probe.py)

换个用法测 IR:**散斑局部对比度=深度可信度的物理指标**(散斑清晰=图案被良好接收=深度强约束)。
真值用 18 帧逐像素时域 MAD(>30mm=不可信),评估单帧 IR 特征预测不可信的 AUC:

| IR 单帧特征 | AUC |
|---|---|
| **局部对比度低(散斑弱)** | **0.82** |
| 强度低(暗) | 0.70 |
| 饱和 | 0.50(本场景无饱和) |
| 局部梯度 | 0.22(反:有纹理处反而可信) |

对抗验证均站得住:① 只看亮像素(控强度),对比度低 AUC 仍 0.75 而强度低塌到 0.53 → **独立于明暗**;
② 中心 ROI 去渐晕 AUC 0.85;③ **逐帧单帧** AUC mean 0.76(0.73–0.77,12 帧极稳)→ 单张 IR 即可用,
无需时域窗口。视觉:`conf_mad_mm.png`(红=不可信,在背景/暗区)与 `conf_ir_contrast.png`
(黄=散斑强,在前景物体)空间完全反相关。

**结论:IR 不是废带宽——它给零延迟单帧置信图**,补足时域稳定性置信(M1.6.17,需窗口+静态场景)
的短板(首帧/运动场景)。属 depth-only 精修内的置信增强(IR 只做权重,不碰几何),可接入。

## 置信掩码恢复质量(confidence_probe 之外,mask_recovery.py)

`mask_recovery.py <dump_dir>` 验证策略:density-first 稠密单帧误差(vs 18 帧时域中值真值)
按 IR 散斑置信阈值掩码后能恢复到多少。实测(交织 dump):全体单帧误差中位 0.25%、≤0.5% 达标 57%;
**conf≥80 掩码 → 保留 49% 密度,误差中位降到 0.05%(=官方稀疏模式质量)、≤0.5% 达标 78%**。
即"稠密+置信掩码"在保 ~50% 密度(比 vendor 正常模式 11% 稀疏稠 4-5×)下把保留像素拉回标称精度,
验证 density-first+置信加权 > 稀疏干净。对比官方 0.5% 标称见 M1.6.17。

## ★ host 重验:无需手机,在服务器上直接复现(host_capture.cpp + host_confidence.py)

之前 IR 置信只在 Android DUMP 的交织流上验过。现确认**这台 Linux host 用厂商 SDK 也能拿
depth + light-IR**,在服务器上复跑整条置信链路,无需手机。

**关键事实(现场实测厂商 SDK Linux V2.0.190)**:
- depth / light-IR 是**两条独立 SDK 流**(Linux SDK 只支持 color+depth 的 MIX,depth+light-IR
  并发实测零帧)。但二者是**同一个 640×400 传感器**(depth 由 light-IR 在器件内算出),天然逐像素对齐;
  静态近物场景下**顺序采集**两批即可,置信要的是「散斑对比度 vs 深度时域 MAD」的空间相关。
- `host_capture.cpp`:SINGULAR 模式单会话内 depth(density-first:temporal=0+spatial=0+conf3,
  稠密 99.8%)→ light-IR(pix=3 纯 10bit IR 灰度,比交织流 high-byte 更干净)各抓 N 帧。
- light-IR 单独流要用 `--light-ir`(type 20);纯 IR 流(type 4)host 实测 rc=-11 不出帧。

**结果(host 顺序采集,640×400 density-first,静态 ~38cm 目标)**:
- 稠密度 99.8%,时域稳健 std 中位 ~59mm、61% 像素不可信 → 独立复现 density-first 真实器件噪声。
- ① IR 置信 **AUC 0.72–0.73**,符号校验强成立(不可信像素散斑对比度 ~0.66 vs 可信 ~2.3)。
- ② 掩码恢复:**conf≥160 → 保留 30% 密度,单帧误差中位 7.9%→0.32%**(≤0.5% 达标 29%→54%)。

**诚实差异**:host 全裸 density-first 比 Android DUMP 更噪(单帧中位 ~8% vs 0.25%),
故需更高阈值(≥160 而非 ≥80)。但结论一致:IR 散斑置信识别弱像素 + 掩码拉回标称精度,
保留密度仍是 vendor 稀疏(11%)的 ~3×。两条独立路径(Android 交织 / host 顺序)互证。

跑法:`OUTPUT_DIR=.dev/depth_ir_guided bash run.sh` 的 [4/4] 步自动编译 + 采集 + 分析
(相机需插本机、SDK 在 `.dev/berxel-sdk-extract/`);SDK/相机缺失时该步跳过,不影响前 3 步。
