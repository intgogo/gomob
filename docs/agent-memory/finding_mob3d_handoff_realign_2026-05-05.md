# mob3d 设计最新交付（Claude Design）核对结论 — 2026-05-05

## 背景

用户从 Claude Design 下载最新一版设计交付包到 `/root/Downloads/3dmob-handoff/3dmob/`，
要求"按这个最新设计来改造"。完整内容：
- `project/gomob redesign.html`（HTML 原型）
- `project/android-handoff/`（**Compose 移交**：5 token + 7 component + 6 screen + HANDOFF.md）
- `project/screens/*.jsx` / `project/uploads/*.png` 等

## 关键认知 — 现仓 ≠ 落后于 handoff，而是**业务化升级版**

精确 diff 后发现：现仓基于早期 handoff 已搭好骨架，且**远超 handoff**。
具体来看：

| 维度 | 状态 |
|---|---|
| **5 个 token** | Type/Shape/Spacing/Theme 与 handoff 100% 对齐；只有 Color.kt 中深色 surfaces (Ink0-3) 与 hairline 抬亮一档（用户已批，"工业仪表风金属感来自深石板蓝灰，而非纯黑"） |
| **7 个组件** | 签名 / 布局 / props 对齐；现仓多 `HairlineCard.onClick`、`TabBarVector` 重载、`BackHeader`（handoff 无二级页支持） |
| **6 个主屏** | 视觉骨架完整对齐 handoff（ScreenHeader / HairlineCard / MetricTile / 4:3 预览框 / SettingRow + Divider）；全部接入 ViewModel + 真实业务字段 |
| **超出 handoff 的部分** | Home ChipRow（handoff 只留 TODO）；Collab 三 sub-tab（第一视角直播 / 抽查复核含柱图 / 案例公开）；Scan 拆 Scan3dRoute 总览 + ScanCaptureRoute 实采；Profile 设置项 6→8；Login 错误/loading/记住账号/去注册；Message 未读 trailing |
| **handoff 写而现仓真实遗漏** | 仅微观差异：Login 主按钮 `"登 录"` vs `"登录"`、Message badge 最小 16dp vs 18dp |

## 用户意图（已澄清）

- "主页面和功能都按新设计重做" — 指的是 6 主屏的**定位框架** + **视觉骨架**，不是占位文案。现仓已对齐。
- "已实现功能都可以跟新的设计和功能框架融合" — Collab 三 sub-tab / Scan 双层 / Profile 8 项 / Login 错误loading / 全部二级子屏（Calibration / FirstPersonViewer / Conversation / Register / InspectionDetail / ReviewDetail）**全部保留**。
- 微观文案 / 数值 — 严格对齐 handoff。

## 落地的具体动作

1. **Spacing token 扩展**（让裸 dp 有归处）：
   - `dot4/6/8`、`icon16/20/24`、`avatar28/40/48`、`btnCircle72`
   - `switchW/H/Thumb/Pad`、`radioOuter/Inner`
   - `cellH28`、`barChartH80`
   - `overlayCardWSm/Md`（200/220）
   - `rowSettingTall`（64）
2. **Color token 扩展**：补 `dangerLine / warnLine / okLine`（与 accentLine 对称，alpha 0x52 = 32%）。同步把 `StatusTag` 内 `.copy(alpha = 0.32f)` 派生改用新 line。
3. **微观对齐 handoff**：Login 主按钮 `"登 录"` → `"登录"`；Message badge `defaultMinSize` 18dp → 16dp。
4. **5 个子屏裸 dp 批量替换**：ScanCapture / Register / ProfileSubscreens / Conversation / ReviewDetail。
5. **FirstPersonViewerScreen 重写**：手写 header → BackHeader；裸 dp 全部走 token；`.copy(alpha = 0.32f)` → `dangerLine`；4 阶信号条数值改为本地 const（不上升为 token，因仅本地一处用）。
6. **PlaceholderScreen 删除**：core:ui 模块保留作公共占位位（feature plugin 自动依赖未撤）。

## 不动的部分（用户明确批准）

- 深色 surfaces 抬亮（Ink0=#11141B 而非 handoff #07080B）
- HairlineCard.onClick 扩展
- Collab 三 sub-tab、ScanCaptureRoute、Profile 8 项设置、Login 错误loading
- 全部二级子屏

## 验证

`./dev.sh build` 32s BUILD SUCCESSFUL。UI 真机截图复核留作下一步任务（dev.sh shot
配合模拟器，参见 finding_emulator_setup_2026-05-04.md）。

## 下次再核对最新 handoff 时的起点

- handoff 路径：`/root/Downloads/3dmob-handoff/3dmob/project/android-handoff/`
- 现仓对位：`core/designsystem/{theme,component}/` + 6 feature 模块
- 包名映射：handoff `com.mob3d.designsystem.*` ↔ 现仓 `io.gomob.designsystem.*`；
  handoff `com.mob3d.app.screens.*` ↔ 现仓 `io.gomob.feature.<name>.*`
- 命名映射：handoff `Mob3d` 对象 / `Mob3dColors/Type/Shapes/Spacing` ↔ 现仓 `Gomob` / `GomobColors/Type/Shapes/Spacing`
