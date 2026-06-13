---
name: 验收不能只看 UI, 必须验扫描业务结果
description: UI 不崩 / 首帧出来不等于扫描对; 退出条件必须验点云密度 / 网格质量 / 外参一致 / 测量值经得起量测复现
type: feedback
---

# 验收不能只看 UI, 必须验扫描业务结果

## 规则

任何采集 / 配准 / 融合 / 重建 / 预览的改动，sprint 退出条件**不允许只是**:

- ✗ "Compose 界面不崩 / 导航通"
- ✗ "扫描预览出了首帧 / 点云渲染非空"
- ✗ "HUD 响应、按钮点击命中"
- ✗ "atomic chain / 帧回调耗时 X ms"

必须包含**扫描业务结果判定**——结果经得起近距离量测和反复复现:

- ✓ 点云密度 / 覆盖率达标，缺洞位置可解释
- ✓ 重建网格质量（无穿插、法线一致、纹理投影对齐）
- ✓ 多视角外参一致（同一物体不同角度配准后尺度 / 姿态对得上）
- ✓ 关键测量值（车辆 LWH / 轴距 / 标定板已知尺寸）误差在规格内，且多次复现稳定
- ✓ 对应 harness PASS（`scan_quality` / `cv_vin_pipeline` / `device_sync` 等）

这与 CLAUDE.md 的 UI 验证规范、扫描真实化标准一致：真实不是"能显示点云"，
而是结果经得起量测和复现。

## Why

UI 链路通和扫描对是两件事。深度相机一帧能反投影出一团点、Filament 把它渲染出来、
界面不崩，这些都不能证明扫描结果正确——尺度可能错、外参可能漂、深度可能是 IR/phase 帧
被当成 metric 帧、点云可能稀疏到量不出尺寸。

容易踩的伪 PASS:

- 看截图里"有点云"就打勾，没去查 metric 距离对不对、密度够不够。
- 单帧 demo / 硬编码姿态 / 离线资产看着像那么回事，把演示当成真实 RGBD 链路。
- 只验了 UI 不崩，没验同一物体多角度采集后外参 / 尺度 / 缺洞是否能解释清楚。

根因是**用户当我眼睛，而不是我自己有眼睛**——reactive 不是 proactive。
项目早有 harness 能出可判定结论，不用就是把判断权丢给用户。

## How to apply

**采集 / 几何 / 配准 / 融合 / 重建改动退出前必做**:

1. 跑对应 harness（`tests/harness/scan_quality/`、`cv_vin_pipeline/`、`device_sync/`、
   `tests/native_host/` 等），读 `.dev/<名称>/` 下的可判定结论。
2. **全 PASS 才允许声明完成**；WARN/FAIL 自己定位根因 → 修 → 重采样，
   不发用户看截图问"是不是这样"。
3. 量测类结果（LWH / 轴距 / 标定板尺寸）必须**多次复现**比对，单次对不算数。
4. commit message 附 verdict（"scan_quality PASS, 见 .dev/scan_quality/REPORT"）。

**先自查再喊 bug——遇到"点云看起来不对 / 量不准"先查 4 件**:

1. depth 帧是不是被 IR/phase 帧污染（按状态行分流是否正确），metric 距离 mm 数对不对。
2. RGB 与 depth 时间戳偏差是否越阈值（同步性是公理，偏差大的帧本该丢）。
3. 外参 / 内参是否来自标定真理源，不是占位或上一台设备的残值。
4. 点云密度 / 覆盖率指标是否在变化（活的链路标志），还是固定一团（可能在回放旧 buffer）。

4 件都正常 → 再判断是不是渲染 / buffer 复用 / 镜头视角问题。

## 相关

- [UI 改动真实运行；默认不截图](feedback_ui_visual_verification.md) — UI 改动要真实运行验证，
  但只验视觉布局 / 渲染非空 / 点击命中，**不验扫描业务正确性**，本文补业务侧。
- [重要不确定模块必须建 harness](feedback_harness_mandatory.md) — 涌现 / 参数敏感 / 长时序模块先建 harness，
  本文的业务判定就靠它出可判定结论。
- [批判性思考不做应声虫](feedback_critical_thinking_not_yes_man.md) — 自己主动 challenge，不等用户当眼睛。
- [Phase 0 是骨架不是真实化](feedback_phase_0_is_skeleton_not_realism.md) — 骨架阶段别拿数字模拟伪装真实 RGBD。
