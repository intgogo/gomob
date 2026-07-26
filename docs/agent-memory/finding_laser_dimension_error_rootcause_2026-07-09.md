# 激光外廓"多次扫描误差大"根因四连 + 修法（M13，2026-07-09）

## Why

真机 job183/184/185（同一静止物体=JCHY 100742，真值 L1777/W533/H759）诊断出旧链路误差全景：
融合点云逐扫描重复性只有 **1mm**（185→184 最近邻中位差），但测量输出 L/W/H 波动 9/20/11mm、
且 L 系统性 +3.5%。误差不在采集，全在管线：

1. **地面逐扫描 RANSAC 重拟合 = 主方差源**。整房间云内点率仅 ~34-40%，法向漂 ~2°、d 漂 36mm；
   测量在地面正交基里做 → W ±20/H ±11mm。交叉实验一锤定音：拿 185 的地面测 184 的云，
   读数完全变成 185 的（互换地面即复现对方全部差异）。
2. **宽度 trimEnds 10mm bin 量化**：`trimEnds` 返回 bin 边界 → 宽只能出 10mm 倍数（520/530/540）。
   1mm bin 下同数据出 522/526/527。
3. **两单元只见对立面 → native 点到点 ICP 有对立面偏置**。A/B 各见一侧翻边（~60mm 厚幕帘，
   车表面零重叠），点到点 ICP 把对立面往一起拉 → B→A 沿车长轴错 ~67mm → 并集 L 被"抹长"+3.5%；
   围栏裁 B 用请求外参、融合摆 B 用精修外参，还差 44mm 不一致。
4. **悬空车体被背景相减吃底**：物体架在台面上，贴台面 tol(40mm) 内的车底点被当背景删 →
   zSpan 车高偏短 −13~−24mm。

## How to apply

- 修法全在 `server/internal/laser`（M13，历史真机复算 L −0.3%/W −0.7%/H +0.5%，互差≤5mm）：
  ① 地面优先从当前 A/B raw background revision 经当前 region/最终 B→A 重建，live 重拟合只做漂移比对，
  >1.5°/50mm 禁止 measured；② `WidthBinMM=1 / SpanTrimPct=0.5`，鲁棒分位跨度替代极值；
  ③ `RefineBToA` 使用点到面 ICP + 法向相容性，生产要求 applied、pairs≥1000、RMS≤15mm、
  修正≤50mm/1°，否则只保留诊断云；④ `SupportBG` 使用支撑面相对车高。
- 验收/监控：`laser_repeatability` 只统计同一 inspection、mode、site、region、background revision；
  不同 revision 和旧链不得混算。单扫描用 `server/cmd/laserreplay`，客户端契约用 `laser_app_web_parity`。
- 铁律：**极值统计(max−min/单 bin 边界)不要直接当测量输出**；固定安装的基准量（地面/外参）
  标定一次持久化，不要逐扫描重估；跨单元配准在"对立面可见"场景必须点到面+法向相容。
- site 保存要求 RMS≤5mm、公共标记≥4；refine 超 50mm/1° 已直接拒绝生产。现场重标后必须重采 raw A/B 背景。
- 2026-07-11 起，`align=site` 的 native 层只应用权威外参，不再做点到点 ICP；生产精修唯一入口为上述
  Go 点到面算法。正式 ArUco + scan208 全量 A/B PCD 离线验证：直接从 site 初值收敛到线上终态仅差
  0.010mm/0.067°，证明删除 native 精修不损失结果，并消除其“只比较幸存近邻均值、无覆盖率/位姿守卫”的误采纳风险。
