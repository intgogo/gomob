---
name: UI 改动真实运行；日志优先，必要时截图自查
description: 涉及 Compose 界面 / HUD / 布局 / 点击区域改动, 先用日志 / harness / uiautomator 判断；必要时截图复核, 不靠编译/单测过就算完
type: feedback
---

# UI 改动真实运行；日志优先，必要时截图自查

涉及 Compose 界面、HUD、扫描预览覆盖、点云/网格渲染、点击区域、显示/隐藏逻辑的改动，
不能只靠编译或单元测试结束。

验证优先级：

1. 更新 APK 后默认同时推到真机和模拟器；多 adb 设备时显式指定 `ADB_DEVICE` / `adb -s` / `adb -t`，避免装错目标
2. 优先看 harness 结果、服务端日志、logcat、API 返回、`uiautomator dump` 文本树，先判断是否仍有服务异常、崩溃、权限错误、404/403/500、空数据等问题
3. 只有涉及视觉布局、遮挡、渲染非空、点击命中、用户明确说界面不对，或 TODO / 验收明确要求截图时，才执行 `./dev.sh shot <screen-name>` 到 `.dev/screenshots/<screen-name>.png`
4. 截图分析要聚焦具体风险点，不做无必要的长篇视觉复盘
5. 发现视觉问题要继续修，不要把"测试通过"当成 UI 完成

如果用户指出"界面不对""HUD 不响应""看起来怪""文字没居中"等问题，**优先**进入这个截图闭环。

## 默认截图入口（M0 之后逐步建立）

- 主页：`./dev.sh shot home`
- 扫描预览：`./dev.sh shot scan-preview`
- 标定向导每一步：`./dev.sh shot calib-step{1..N}`
- 历史详情（Filament 渲染）：`./dev.sh shot gallery-detail`

每个截图 entry 必须在 `feature:<module>` 内提供 `*ScreenForTest()` 或 deeplink，让脚本
能确定性进入到目标 state，不依赖用户操作。
