---
name: 开发闭环 — 自驱动验证
description: 每个阶段性功能完成后, 立即自己跑完采样 → 分析闭环, 不等用户报问题
type: feedback
---

# 开发闭环：自驱动验证

开发完大功能后，必须自己执行完整的验证闭环，不等用户来发现问题。

**闭环流程（铁律）：**
1. **开发功能** → 写代码
2. **开发测试** → 单元测试 + 集成测试
3. **执行采样** → `./dev.sh install` / `./dev.sh run` 推到设备，或 `./dev.sh harness <名称>` 用真实 RGBD 数据跑
4. **分析输出** → 知道日志/数据在哪（`adb logcat -s gomob:*`、`.dev/<名称>/*`、harness `analyze.py` 的判定结论）；用户主动要求截图时再查 `.dev/screenshots/`
5. **发现问题** → 自己对比预期 vs 实际，不依赖用户告知
6. **回到设计** → 问题出在哪一层？是设计缺陷还是实现 bug？从第一性原理重新审视
7. **迭代** → 如果是设计问题就重新设计，不堆补丁；异常时定位根因 → 修复 → 重采样 → 闭环完成

命中 harness 覆盖的模块，闭环必须以 `analyze.py` 出"正常 / 警告 / 异常 + 原因"为终点，
不靠目视"看一眼好像对了"。

**How to apply：**
- 每个阶段性功能完成后，立即写模拟测试、跑 harness 或推到真机验证
- 日志要设计得足够精细，能看到关键路径的每一步（谁触发了什么、参数是什么、结果是什么）
- 知道 logcat tag 约定：`gomob.<module>`，C++ 端用 `gomob_native`
- 发现异常时，先问"设计对不对"，不急着改代码
- 补丁式修复（硬编码阈值、特殊 case 处理）是红线，必须从架构层面解决，详见
  [整体不打补丁](feedback_holistic_not_patching.md)
- harness 何时必须建、怎么建，见 [harness 强制](feedback_harness_mandatory.md)

## 在 gomob 的特化场景

- **RGBD 同步问题**：先跑 `tests/harness/device_sync/` 给出"正常 / 警告 / 异常"判定，
  看时间戳偏差分布，不靠"看一眼帧好像对齐了"
- **重建漂移 / 配准质量**：跑 `scan_quality` / `cv_vin_pipeline` 多视角场景，看点云密度、
  mesh trajectory 是否回环、复现尺度是否稳定，不靠目视"差不多"
- **UI 不对**：先用 uiautomator dump / instrumentation / logcat / 渲染首帧日志定位；
  只有用户主动要求截图时才执行 `./dev.sh shot <screen>`，详见
  [UI 视觉验证](feedback_ui_visual_verification.md)

## 用户报体验 bug 时同样适用

用户报"进入扫描页卡死 / 预览掉帧 / 漫游不流畅 / 点云花屏"这类 perf / 体验 bug 时，
**必须先模拟完整用户流程 + 读端侧日志，再下结论**，不要只看单点指标就推论。

- 端到端流程（USB 连相机 → 授权 → 启流 → 双流同步 → 预览首帧 → 录制 → 3D 回看）
  才能复现并量化痛点，单纯启动 App 截一张图不够。
- native 内部计数（如 depth_seq / pairs / valid 比）是间接指标，真正反映用户体验的是
  Compose 端帧间隔分布、freeze 占比、丢帧触发点、大帧 / 大点云的渲染卡点。
- 量化卡顿的标准方法：从 logcat 抓带时间戳的关键事件，算相邻事件 gap 分布，
  freeze 占比（gap 超阈值累计 / 总时长）是直接可信指标；卡顿源在 native 还是渲染线程，
  靠两侧时间戳对齐判断，不靠猜。
