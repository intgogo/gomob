---
name: UI 改动真实运行；默认不截图
description: 涉及 Compose 界面 / HUD / 布局 / 点击区域改动, 先用日志 / harness / uiautomator 判断；只有用户主动要求时才截图
type: feedback
---

# UI 改动真实运行；默认不截图

涉及 Compose 界面、HUD、扫描预览覆盖、点云/网格渲染、点击区域、显示/隐藏逻辑的改动，
不能只靠编译或单元测试结束。

验证优先级：

1. 更新 APK 后默认同时推到真机和模拟器；多 adb 设备时显式指定 `ADB_DEVICE` / `adb -s` / `adb -t`，避免装错目标
2. 优先看 harness 结果、服务端日志、logcat、API 返回、`uiautomator dump` 文本树，先判断是否仍有服务异常、崩溃、权限错误、404/403/500、空数据等问题
3. 默认不执行 `./dev.sh shot`；只有用户主动要求截图时，才截图到 `.dev/screenshots/<screen-name>.png` 并做复核
4. 对视觉布局、遮挡、渲染非空、点击命中等风险，优先用 uiautomator / instrumentation / 渲染日志 / harness 指标验证
5. 发现视觉问题要继续修，不要把"测试通过"当成 UI 完成

如果用户指出"界面不对""HUD 不响应""看起来怪""文字没居中"等问题，先做真实运行与日志 / uiautomator / instrumentation 复核；只有用户明确要求截图时才进入截图闭环。

## 手动截图入口

仅当用户主动要求截图时使用：

- 主页：`./dev.sh shot home`
- 扫描预览：`./dev.sh shot scan-preview`
- 标定向导每一步：`./dev.sh shot calib-step{1..N}`
- 历史详情（Filament 渲染）：`./dev.sh shot gallery-detail`

每个截图 entry 必须在 `feature:<module>` 内提供 `*ScreenForTest()` 或 deeplink，让脚本
能确定性进入到目标 state，不依赖用户操作。
