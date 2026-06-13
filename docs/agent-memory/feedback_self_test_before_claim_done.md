---
name: 声称完成前先自测，不把复现甩给用户
description: 写完代码/功能/harness 必须自己跑通验证，确认无崩溃无空数据行为对再报完成
type: feedback
---

# 声称完成前先自测，不把复现甩给用户

写完代码、加完功能、加完 harness，**必须自己真跑一次验证**，确认无崩溃、无空数据、行为正确，再说"完成"。需要复现问题时**自己制造输入复现，不让用户手动重启 + 操作一遍报结果**。

## Why

- "我加完了" ≠ "我跑通了"。只编译过 / 只写了 harness 没真跑，就报"已覆盖全主链 PASS"，是用暗示性措辞蒙混未验证的工作。
- 很多 P0 不是编译期能 catch 的：`--check-only` 类静态检查只 parse 单文件，不实际初始化运行时；JNI 链路、USB 枚举、CameraX 绑定、Compose 首帧、点云上屏都要真启动才暴露。例如双流并发供电不足导致 0 帧、`DirectByteBuffer` 复用引发 Filament 花屏、权限未授导致空数据——全部只在真跑时出现。
- 改完客户端 / native / 服务端就让用户"手动接相机、点扫描、看效果"，等于把复现成本和验证责任甩回用户。能自己注入输入复现的，就不该让用户重现。

## How to apply

完成功能后，按链路自己跑通再报完成：

- **编译 + 单测**：`./dev.sh build`、`./dev.sh test` 先过；改了模块边界 / 依赖跑 `./dev.sh ci`。
- **harness 必须真跑填结论**：写了 harness 不等于完成，要 `./dev.sh harness <名称>` 真采样，让 `analyze.py` 出"正常 / 警告 / 异常"可判定结论。命中 scan_quality / cv_vin_pipeline / device_sync 等现有 harness 的改动，提交前必须跑确认无退化。详见[harness 是强制能力](feedback_harness_mandatory.md)。
- **真机 / 模拟器自跑**：UI、HUD、点击区域、3D 预览、扫描状态机改动，`./dev.sh install` 或 `run` 推上去，用 logcat、`uiautomator dump`、Compose / instrumentation 测试自动注入点击与状态切换，自验崩溃、空数据、权限、首帧渲染。详见[UI 看实际效果再说做完](feedback_ui_visual_verification.md)、[业务链路验证不止 UI](feedback_business_verification_not_ui_only.md)。
- **自己制造输入复现**：要复现某交互或数据路径，用 instrumentation / `uiautomator` 注入点击、用 harness 喂录制的 RGBD 帧或 usbmon 回放、用 host native test 跑数据流，不要把"请帮我重启测一遍"丢回用户。
- **第一次跑新环境先初始化**：新机 / 新设备第一次跑，先确认依赖到位（SDK 资产、native .so、相机供电 hub），别在缺前置条件时报失败当结论。

确实有无法自跑的边界——例如真实深度相机硬件并发供电、特定手机 OTG 行为、需要人眼判断的视觉质量——就**显式说清"X 我已自测通过，Y 走真机硬件路径需要你验证"**，列出已验证范围和待验证项，而不是默认把整件事丢回用户。这也是[自主执行不频繁打断](feedback_autonomous_execution_no_check_in.md)的前提：自己先验证到位，才有底气连续推进。
