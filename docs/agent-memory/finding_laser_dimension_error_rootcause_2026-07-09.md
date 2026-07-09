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

- 修法全在 `server/internal/laser`（M13，真机复算验收 L −0.3%/W −0.7%/H +0.5%，互差 ≤5mm）：
  ① 持久化地面：背景采集时拟合入库（migration 0021 `laser_ground_plane` + `Runner.Grounds`），
  扫描复用，重拟合仅漂移告警；② `MeasureParams.WidthBinMM=1 / SpanTrimPct=0.5`（bg_subtract 路径），
  鲁棒分位跨度替代极值；③ `RefineBToA`（refine_btoa.go）点到面 ICP + 法向相容性拒绝，
  从任意初值收敛同一解，守卫 150mm/5°，围栏与融合同用精修后 B→A；④ `SupportBG` 支撑面相对车高。
- 验收/监控：`./dev.sh harness laser_repeatability`（σ≤5mm 门 + 真值门 + ground_source/refine 卫生检查）；
  深挖单扫描用 `server/cmd/laserreplay` 对落盘 PCD 复算。
- 铁律：**极值统计(max−min/单 bin 边界)不要直接当测量输出**；固定安装的基准量（地面/外参）
  标定一次持久化，不要逐扫描重估；跨单元配准在"对立面可见"场景必须点到面+法向相容。
- refine delta 持续 >100mm = site 标记外参偏差大，去现场按 4 角点版重标（§docs/architecture/17 §9.5）。
