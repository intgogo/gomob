# VIN 去阴影二值化真机订正：极性反转 + 低对比丢字（2026-06-21）

## What

原厂 `GetSignature3G` 的 `adaptiveThreshold(blockSize=131,C=15)` 在另一次真机采集（同钢板、不同补光角度/距离）**两处失效**，端上表现为"还原图有问题"：

1. **极性反转**：刻字在该光照下镜面高光灌进刻槽 → 比底材**亮**。固定 `THRESH_BINARY`+反相把"暗于底=前景"写死，整片翻成**白字黑底**（墨水占比 91%）。同一钢板早先一组（cap_001-006）刻字比底暗 → 正常黑字白底；新一组（cap_016-021）刻字偏亮 → 全翻。
2. **固定边距怕低对比**：字-底灰度差 < C(=15) 时整串丢字成碎片（不是反转，是丢）。

几何正射本身没问题（彩色 `_obb` 里 VIN 清晰可读），坏的只是去阴影那一步。

## Why

钢板刻字的明暗极性**不是固定的**，随补光角度/反光在"比底暗"和"比底亮"间切换；固定极性 + 固定边距的自适应阈值对真实光照变化不鲁棒。逆向出的原厂单算子是"对的逆向"，但原厂这步本身就脆（或原厂有额外极性处理没被逆出来）。真机才暴露——离线只有早先一组同极性数据时 harness 判"正常"，假阳性。

## How to apply

**新去阴影法**（`tests/harness/vin_restore/restore_obb.py::signature_binarize` 与 `server/internal/cvengine/restore/signature.go::signatureBinarize` 两端同步）：

```
bilateral(9,60,60)                              # 保边降噪，除钢板微纹理（CLAHE 会放大微纹理→椒盐，弃）
→ norm = clip(d ÷ (GaussianBlur(d,σ21)+1) × 180)  # 背景除法平照：拉平光照不均，让全局阈值普适
→ threshold(OTSU | BINARY_INV)                  # 全局自动阈值
→ 若前景(白)占比 > 0.5 → bitwise_not             # 极性归一：真实墨水稀疏(~8-12%)，过半=刻字偏亮，反过来
→ 去小斑(minAreaRect 宽高都≤39) → OPEN(2)+CLOSE(3)
```

**质量闸**：归一后墨水占比 > `SigInkMax=0.25` 判废（Go `ErrLowQuality` → handler `ok:false`+`reject_reason="low_quality"`；tilt 门是 `tilt_too_large`）。端侧按 reason 给针对性重拍提示，不显垃圾。harness `restore_obb.py` 主循环同闸跳过坏组，不污染重合分析。

**实测（真机 2510DRK44C 21 组，HTTP 契约 httptest 验证）**：14 组好采集出干净可读签名（墨水 8-12%，含原 91% 垃圾的 6 组 live 转 8-9%），7 组坏采集（框偏/糊/低对比/板2 反光）正确判废。

**通用教训**：刻字/拓印类二值化必须**极性无关**（按"墨水稀疏=少数类"归一）+ **光照无关**（背景除法平照）；离线 harness 数据若极性单一会假阳性，需多光照真机样本才暴露。详见 [[finding_vin_rectify_serverside_calib_2026-06-18]]。
