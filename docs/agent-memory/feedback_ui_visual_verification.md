---
name: UI 改动必须真实运行并截图自查
description: 涉及 Compose 界面 / HUD / 布局 / 点击区域改动, 默认同时 install 真机和模拟器；必要时截图复核, 不靠编译/单测过就算完
type: feedback
---

# UI 改动必须真实运行并截图自查

涉及 Compose 界面、HUD、扫描预览覆盖、点云/网格渲染、点击区域、显示/隐藏逻辑的改动，
不能只靠编译或单元测试结束。

必须执行：

1. 更新 APK 后默认同时推到真机和模拟器；多 adb 设备时显式指定 `ADB_DEVICE` / `adb -s` / `adb -t`，避免装错目标
2. 必要时执行 `./dev.sh shot <screen-name>` 自动跳到指定 screen 并截图到 `.dev/screenshots/<screen-name>.png`
3. 打开截图检查布局、遮挡、比例、信息密度、文字居中、点击目标与视觉目标是否一致
4. 发现视觉问题要继续修，不要把"测试通过"当成 UI 完成

如果用户指出"界面不对""HUD 不响应""看起来怪""文字没居中"等问题，**优先**进入这个截图闭环。

## 默认截图入口（M0 之后逐步建立）

- 主页：`./dev.sh shot home`
- 扫描预览：`./dev.sh shot scan-preview`
- 标定向导每一步：`./dev.sh shot calib-step{1..N}`
- 历史详情（Filament 渲染）：`./dev.sh shot gallery-detail`

每个截图 entry 必须在 `feature:<module>` 内提供 `*ScreenForTest()` 或 deeplink，让脚本
能确定性进入到目标 state，不依赖用户操作。
