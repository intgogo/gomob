# VIN 正交还原：输出彩色图 + 字符端正（真机订正 2026-06-22）

## What

用户两条纠正，推翻之前两处做法：

1. **输出要彩色正交还原图，不是二值签名**。之前 `Restore` 返回 `GetSignature3G` 去阴影二值图当成果；用户要的是 `render` 出的**彩色正射图**（金属底色 + 端正字符）。二值图降级为**只给质量闸(墨水占比)+ 未来 OCR** 用，不返端侧。
2. **字符还是歪的（斜体状）**——真正交后字符应竖直。诊断：原图里 VIN 钢印字本是**正的**，还原后才左右渐斜。

## Why（字斜的两个几何根因）

投影轮廓法测竖笔倾角：**左端≈0°、右端 -13~-15°（左右梯度），且随拍摄倾角增大**（cap_001 近正拍不斜、live 组 tilt~15° 才斜）= **残余透视**，非字体非均匀 shear。两根因：

1. **彩色内参假设错**：之前 `fyc = 2·fyd`。但深度传感器被**重度竖直 binning**（640×128，`fy≈164` anamorphic，`fx≈614`），彩色(HLSD8 1280×256)**不是**——实测彩色**近方形像素 `fyc≈fxc`**。只有水平 2× registration 成立（`fxc=2·fxd`、`cx/cy=2×`），`fyc` 不跟深度走。用 `2·fyd` 留残余竖直透视 → 字随倾角左右渐斜。扫描实证：`fyc=fxc` 时 21 组倾角全 ≤2°（`fyc×4≈fxc`）。
2. **平面拟合污染**：之前深度平面取中心 90% ROI，把钢牌 strip **上下背景**纳入 → 法向被污染（inlier 0.44-0.82）→ 去透视不彻底。改成**先检 OBB → 只在 OBB 区(承印面)拟合平面** → inlier 升到 0.99-1.00、rms 8.4→4.4。

（另加 de-shear：OBB 平面内 4 角重建成真矩形再单应，消单应 shear；实测此项贡献小，主因是上面两条。）

## How to apply

`tests/harness/vin_restore/restore_obb.py` 与 `server/internal/cvengine/restore/{render.go,restore.go}` 两端同步：

- **彩色内参**：`Kc = (2·fxd, 2·fxd, 2·cxd, 2·cyd)` —— `fyc=fxc`，**不要** `2·fyd`。
- **平面拟合 ROI**：用最高分 OBB 的轴对齐 bbox 换算成深度分数 ROI（`÷彩色分辨率`，分数与分辨率无关；高度 ×1.25 夹钳定法向），不要中心固定 ROI。
- **render 输出彩色**：`Restore` 返回 `render()` 的彩色 BGR PNG；`signatureBinarize` 只算墨水占比给质量闸(`SigInkMax=0.25`→`ErrLowQuality`)。
- **顺序**：先 `Detect` OBB → 再拟合平面（buildFrame 里 OBB 选择移到平面拟合前）。

**验收**：真机 21 组，14 组好采集出**彩色端正可读** VIN（cap_021 live 实测 ok/ink8.4%/tilt16.6°/彩色 260×1200×3）、7 组坏采集判废；harness 重合 0.51-1.01%（组3 live 0.67%/NCC0.817）。去阴影极性/质量闸见 [[finding_vin_signature_binarize_realdevice_2026-06-21]]。

**通用教训**：双传感器 registration 别假设各轴同比例——anamorphic binning 下 `fx`/`fy` 缩放可能不同；正交还原残余透视优先怀疑内参 `fy` 与平面拟合 ROI 是否含背景，用"竖笔倾角左右梯度 vs 拍摄倾角"区分内参误差/字体/单应 shear。
