# App UI 与设计系统 — 历史上下文

> 最后更新: 2026-07-26 | 截至 commit: a979415 | 维护规则见 AGENTS.md「历史上下文维护」节

## 使命与当前状态

本模块负责 gomob 全部 Compose 界面与 `core/designsystem` 设计系统层: 5 tab 信息架构 (首页/消息/协作/3D/我的)、token 体系 (GomobColors/Type/Shapes/Spacing)、公共组件 (ScreenHeader/BackHeader/HairlineCard/MetricTile/TabBar/SegmentedTabs/Chip)、以及 2026-07 起的毛玻璃体系 (Haze 真模糊 chrome + 拟玻璃面板 + 真 edge-to-edge)。视觉基调经历三段演进: 暗色科技风 (05 月初) → light-first/mint (05-30) → **固定浅色、彻底删除暗色链** (M14.2, 2026-07-12)。

当前状态: M14 毛玻璃主体、M14.2 固定浅色、M14.5 设计稿二轮对齐与登录后 QA 反馈通道已在 a979415 落盘；代码构建与测试门此前全绿，M14.5 真机逐屏走查仍未做（当时无设备在线）。设计真理源在 `.dev/design/` (gitignored): `proto.html` 21 屏交互原型 + `spec.html` token 修订表, 冲突时以 proto 为准。

## 决策时间线

### 2026-05-03~04 M0→M0.5: 从骨架到 5 tab 科技风 (M0/M0.5)
f652518 落 NIA 式多模块骨架, 656c27e 首页跑通; 87117b8 重新规划为 5 tab 科技风 App + Go 服务端骨架; 50a2517 "工业仪表风"设计系统 + 6 屏重写, b581e7b 迭代 + 注册流。架构硬约束定型 (docs/architecture/02): `core:designsystem` 不依赖 `core:ui`、feature 互不依赖、JNI 唯一走 `core:native-bridge`。产品信息架构真理源是 docs/architecture/06 (机动车检测站查验员场景, "装备"tab 改名"3D"; scan/calibration/gallery 合并 `feature:scan3d`, settings 改 `feature:profile`)。

### 2026-05-05 mob3d Claude Design 交付对齐 — 确立"外部设计稿→token 对齐"工作流
用户从 Claude Design 拿到 handoff 包, 同日 36ed636 先做 token 核对, 随后 7 连 commit (3628254→8a37818, Commit 1/7~7/7) 逐屏对齐: 色板回 OLED 黑 + 默认 dark、首页改 AI 助手聊天页、Profile 设置右抽屉诞生 (该抽屉 2026-07-10 M14.5 被删)。关键认知: **现仓不是落后于 handoff 而是业务化升级版**, 对齐的是视觉骨架与 token 而非覆盖业务代码; 命名映射 Mob3d*→Gomob*。证据: docs/agent-memory/finding_mob3d_handoff_realign_2026-05-05.md。此工作流 (用户送设计包出去→带交付稿回来→代码逐屏对齐) 在 07-10 M14.5 复用。

### 2026-05-07 PointCloud3dView 花屏根因 — Filament/Compose 共享 GL 的 buffer 污染
点云预览区整块条纹方格 garbage。订正链: 先疑 setBufferAt race → 再疑 hardware overlay/驱动 bug → **终态: Compose 硬件加速层与 TextureView 共享 GL context, 复用了 SurfaceTexture buffer 池; dirty-flag 模式下 Filament 不持续渲染, buffer 被 Compose 的 RGB 帧上传写脏; 修复 = 去掉 dirty flag、每帧 render 锁住 buffer**。用户"镜头变化大才花"是关键诊断线索。证据: /root/.claude/projects/-root-lilw-gomob/memory/finding_pointcloud3d_corruption_2026-05-07.md (含现场截图)。

### 2026-05-09~13 消息/通话 UI 打磨 (M5 UI 部分)
消息媒体/语音转写/搜索/通话状态等界面随控制面落地 (a6a2e4a 等); 40de696 (05-13) 后进入 11 天 Berxel 逆向攻坚零 commit 期 (至 f7951b9 05-24)。M5 的通话/直播页 UI 验收 (uiautomator 确认控件) 至今未闭环, 主线叙事见 docs/context/infra-server.md。

### 2026-05-30 主题转浅色第一步: light-first/mint 默认 (92d01f0)
从 mob3d 的 OLED 黑默认暗切到默认浅色 mint。这是浅色化三部曲的第一步 (→ 07-10 用户定调"主要做 light" → 07-12 M14.2 删尽暗色)。同期踩到 SystemBarStyle 陷阱, 见禁区第 2 条。

### 2026-06-05~06 M10 激光点云界面重做 (跨模块)
A/B/融合三窗同款轨道 + 第一视角漫游标注车位框, 属激光站主线, 详见 docs/context/laser-station.md; 本模块视角的后续是 07-01 漫游 UI 从 App 端整体撤除。

### 2026-07-01 外廓扫描页瘦身: App 纯操作端
背景: 激光工位管理网页端成熟, App 简化为纯操作端。用户拍板: 结果展示 = 同屏放大结果卡 (LaserResultPanel, 不跳页); 设置/设备控制/标定/车位框/漫游标注入口**全部移除**、配置归网页端。删 LaserDeviceControl/LaserCropBoxEditor/RoamAnnotationScreen/VehicleTypeCatalog 4 文件; 数据层 crop-box repo/api 保留 (服务端仍拥有该配置); PointCloud3dView 内漫游状态机 ~130 行惰性残留, 留作独立清理项。踩坑: 首版只编译 main 源集就宣布通过, 被对抗式审查抓出 2 个孤儿单测会挂 test 源集 → 教训入禁区第 10 条。证据: 会话消化稿 2026-07-01 (6e720e8e)。

### 2026-07-09 M14 全 App 毛玻璃焕新 (07fdf97, 9df21c9) — 用户拍板 Haze 真模糊
用户开题"改成毛玻璃效果", AskUserQuestion 拍板选 chrisbanes Haze 真实背景模糊而非纯拟玻璃。核心设计: **玻璃分两档** — 真模糊 chrome (`glassChrome`, 用于 TabBar/Header/吸底输入条, 玻璃下有滚动内容穿过才值得真模糊) vs 拟玻璃面板 (`GlassPanel`, 用于卡片/Dialog/BottomSheet, Dialog 独立 window 采样不到内容, 真模糊无意义); 真 edge-to-edge (去全局系统栏 padding, 每屏经 GlassHeaderScaffold/TabBar 自理 inset); 并行迁移 (Home 作参考实现 + 5 个并行 agent 迁 6 模块 30+ 屏)。
**本次核心踩坑 = Haze 采样源嵌套导致玻璃全透明**: NavHost 与每屏各挂一层源, 层中录层把采样层录空, 所有 hazeChild 静默不画 — 编译单测全绿, 只有真机肉眼可见; 靠反编译 Haze jar 定位。终态铁律: 全 App 单 HazeState、源只挂当前屏 scaffold 内容层一处、版本钉 1.2.2。首轮真机反馈四处修正 (9df21c9): composer 去边线、**历史会话/联系人侧滑抽屉退场**改全屏玻璃二级路由 (删 ~250 行浮层)、消息页顶部五层堆叠收敛、我的页身份 hero 化。
同日插曲: Codex 用旧上下文把 46 个文件覆写回玻璃改造前快照 (`07fdf97^`), 逐字节比对确认后恢复, 零内容损失 (幸而玻璃工作已提交); 另发现 install -r 因 debug 签名不同静默失败, 靠 APK MD5 比对识破。证据: docs/agent-memory/finding_haze_nested_sources_transparent_2026-07-09.md、docs/architecture/07 §8、会话消化稿 2026-07-09 (3aaf07b3)。

### 2026-07-10 三线并发: API29 兼容 + 设计交接包出站 + M14.5 二轮对齐
- **36653a4**: 主题 API 29 兼容修正 (新增 values-v29/themes.xml)。
- **设计交接包** (会话 49e0f628): 制作 `.dev/design-handoff-m14.zip` 发给 Claude Design, README 写明六条不可破坏硬约束 (玻璃两档依据/单 HazeState 拓扑/主题组合/可读性/性能/Compose 可落地), 强制设计产出落到 GomobSpacing/GomobType 具体字段。用户纠偏定调: "**我们主要是做 light 的页面, dark 不要截**"。同会话把外廓页右上下拉改"3D 工位选择"(静态表只放真实 1 号工位, 终态=服务端工位注册表下发), Berxel 相机入口暂隐但链路保留。
- **M14.5 二轮对齐** (会话 1d543fa2): 用户带回 design.zip, 指出"codex 已做一轮但跟设计差别挺大"。设计真理源固定 `.dev/design/spec.html + proto.html` (21 屏), **spec 与 proto 冲突以 proto 为准**。codex 首轮偏差定性三类: ①卡片用不透明 bg1 丢玻璃质感 ②若干屏结构没按原型 (聊天设置微信硬编码色/设置仍是抽屉/外廓无分镜网格) ③大量字号尺寸细节。执行架构 = 6 只读对照 agent 出差异清单 → 主线改共享组件 → 5 并行修复 agent。关键改动: 我的页路由重构 (设置抽屉→独立子页 `profile/settings`, 删 SettingsDrawer ~260 行)、ChatComposer 折叠态改"pill+外置发送"56dp、聊天设置整屏 token 化、外廓工位分镜 2×2 网格 (未接入镜头显式标注非假数据)。**无后端能力的设计动作一律不放假按钮, 留 `TODO(终态)`** (结束并融合/确认入档/RGB 缩略/在线点/1:1 拉群等)。代码改动已在 a979415 落盘；**真机走查未做**。证据: TODO.md M14.5、/root/.claude/projects/-root-lilw-gomob/memory/project_m14_design_alignment_2026-07-10.md、docs/architecture/07 §8.4。

### 2026-07-12 M14.2 主题固定浅色 (已在 a979415 落盘)
删除外观模式/ThemeMode/明暗持久化链, 原生 Activity 基主题改 Light, 设计系统删五套暗色色板与不可达暗分支, 默认 Mint。TODO M14.2 已标 ✅ (真机验证含冷启保留 Coral)。这是浅色化终局: 用户只在 5 套浅色主题间选色。证据: TODO.md M14.2、docs/architecture/07 §5。

### 2026-07-16 前后 (已在 a979415 落盘) 登录后内部 QA 截图反馈通道
隐藏入口挂 ScreenHeader/BackHeader 等标题栏 (大标题连点 5 次、相邻间隔 ≤700ms 触发, 见 `component/FeedbackTitleTrigger.kt`); `AppFeedbackHost` 只包登录后业务壳; Filament 页截图走 `FeedbackCaptureSurface` (停 Choreographer + flushAndWait + DST_OVER 合成); 标注编辑器 + `POST /v1/feedback` 落 `.dev/app-feedback/`。规范已写入 docs/architecture/07 §8.5; 实施会话无消化稿, 日期依 `.dev/app-feedback/` 产物时间 (07-16) 反推, 细节以 §8.5 与代码为准。

## 禁区与已证伪路线

1. **Haze 采样源嵌套 / 乱升版本**: NavHost 等外层容器挂 `haze()` 源 = 所有 hazeChild 静默全透明 (非版本 bug, 拓扑错误)。全 App 单 HazeState、源只挂当前屏 scaffold 内容层、消费者不得是源后代; Haze 钉 1.2.2, 升 Compose 1.8+ 前不动。证据: docs/agent-memory/finding_haze_nested_sources_transparent_2026-07-09.md、docs/architecture/07 §8.2b。
2. **SystemBarStyle.light()/.dark() 匹配 App 主题**: 其内部 detectDarkMode 读的是**系统** uiMode, App 主题与系统不一致即撞色; 必须 `SystemBarStyle.auto(...) { appDark }` 注入 App 自身判定 (splash 白 icon 场景把 launchBackdropVisible 并进判定)。M14.2 固定浅色后风险降低, 但 API 陷阱本身仍在。证据: memory/finding_systembarstyle_light_system_mode_trap.md。
3. **SideEffect/LaunchedEffect lambda 内读 State 当订阅**: lambda 脱离 snapshot 上下文, 读值不建立订阅, 依赖它重跑的逻辑全部哑火; 先在 composition 作用域读、lambda 只消费 capture 值。证据: 同上 finding 配套节。
4. **Filament + TextureView + Compose 场景用 dirty-flag 省渲染**: Compose 共享 GL context 会复用 SurfaceTexture buffer 池, 不持续 render 即被污染花屏; 必须每帧 render。证据: memory/finding_pointcloud3d_corruption_2026-05-07.md。
5. **暗色主题回潮**: 用户 07-10 定调产品主做 light, M14.2 已删外观模式与全部暗色色板; 不要再加"跟随系统/深色"、不要截 dark 走查图。证据: TODO M14.2、会话 49e0f628 用户原话。
6. **把 docs/architecture/07 §1~7 当现状**: 那是早期暗色科技风设计稿 (深空黑/霓虹青/80dp NavigationBar 等), 现状真理源是 §8 + `core/designsystem` 代码 (TabBar 实际 54dp、5 套浅色 GomobColors)。证据: 07 文档 §8 开头自注。
7. **假按钮/假数据**: 无后端能力的设计动作 (结束并融合/确认入档/RGB 缩略/在线 presence/轴距货箱字段等) 禁止放假入口、禁止伪造数据, 留真实动作 + `TODO(终态)`; 未接入镜头显式标"未接入"。证据: AGENTS.md 宪章、07 §8.4、TODO M14.5。
8. **用设计交付包里的 code/ 旧快照覆盖现仓**: 交付包 code 是发出去时的现状快照; Codex 曾据旧上下文覆写 46 文件酿事故。对齐设计只改视觉/结构, 业务代码以现仓为准。证据: 07 §8.4 首行、会话 3aaf07b3 事故记录。
9. **"编译/单测绿 = UI 对"**: Haze 全透明 bug 全套绿、只有真机肉眼可见; UI 改动必须真机走查"内容滚过 chrome 是否模糊+tint", 空列表分辨不出玻璃死活。装机后核 APK 哈希 (install -r 会因签名不同静默失败)。证据: 禁区 1 同源 finding、会话 3aaf07b3。
10. **删产品代码不动 test 源集**: 孤儿单测会让 `./dev.sh test/ci` 必挂但 `compileDebugKotlin` 不报; 删功能必须同步删/迁单测并编译 `compileDebugUnitTestKotlin`。证据: 会话 6e720e8e。
11. **把本机模拟器当真机用**: 本机无 KVM (`/dev/kvm` 不存在), 默认加速路径起不来 (07-09 七分钟超时); 07-10 起改用 **`-accel off` 纯软件 TCG 模拟**成功启动 (证据: `~/.android/avd/gomob_x86.avd/emu-launch-params.txt` 明确 `-accel off`, `.dev/emulator.log`), 本机 AVD 仅 gomob_x86 (API30 x86) 与 gomob_test (API34 x86_64) — TODO 中 emulator-5556 走查记录即此类实例, 所写 "API35" 与本机仅有的 android-30/34 镜像不符, 疑为 API34 误记。软件模拟只够装包 + uiautomator 结构走查; 玻璃观感/媒体/性能类验证仍必须真机 WiFi adb (测试机池见 memory/project_test_phone.md)。

## 关键资产指针

- `core/designsystem/src/main/kotlin/io/gomob/designsystem/` — 设计系统本体: `theme/` (Color/Type/Shape/Spacing/Theme), `component/` (ScreenHeader/BackHeader/HairlineCard/MetricTile/TabBar/SegmentedTabs/Chip 等), `glass/` (Glass.kt=glassChrome/GlassPanel, GlassHeaderScaffold.kt), `motion/` (PageDrag/按压动效)。
- `app/src/main/kotlin/io/gomob/scan/` — MainActivity (edge-to-edge + SystemBarStyle.auto)、AppRoot、navigation/GomobNavHost (单 HazeState 下发/二级路由)、feedback/AppFeedbackHost (QA 反馈壳)。
- `docs/architecture/07-design-system-tech.md` — §8 毛玻璃体系是代码真理源 (§8.2b HazeState 铁律, §8.4 版式回迁规范, §8.5 QA 反馈); §1~7 为历史设计稿。
- `docs/architecture/06-product-features.md` — 产品信息架构单一真理源 (5 tab/角色/各屏功能)。
- `docs/architecture/02-app-architecture.md` — 模块切分与依赖硬约束。
- `docs/agent-memory/finding_haze_nested_sources_transparent_2026-07-09.md` — Haze 拓扑踩坑权威版。
- `docs/agent-memory/finding_mob3d_handoff_realign_2026-05-05.md` — 设计 handoff 对齐方法论 + token 命名映射。
- `docs/agent-memory/feedback_design_style.md` — 设计决策风格偏好 (激进/长期主义/边界纯粹: 数学走 native, Kotlin 只编排呈现)。
- `/root/.claude/projects/-root-lilw-gomob/memory/` — finding_pointcloud3d_corruption_2026-05-07.md / finding_systembarstyle_light_system_mode_trap.md / project_m14_design_alignment_2026-07-10.md (auto-memory, 不入库)。
- `.dev/design/proto.html` + `spec.html` — M14.5 设计真理源 (gitignored, 21 屏原型逐屏 `<!-- ═══ 屏名 ═══ -->` 锚点可 grep; 若丢失需向用户重新索要 design.zip)。
- `.dev/design-handoff-m14.zip` — 发给 Claude Design 的现状交接包 (含六条硬约束 README 与 30 张浅色截图)。
- `TODO.md` M14 节 — 收尾任务唯一真理源。

## 未竟事项

- **M14.5 真机逐屏走查** (最紧要): 五根页+会话+VIN+外廓+设置/资料/案例/主题/历史对照 proto.html 逐屏过, 键盘/撤回/开关/路由回归; 先 `adb mdns services` 连测试机。外廓专项已在 emulator-5556 过一轮 (TODO M14.5 验收栏; 软件模拟实例, 见禁区 11)。
- M14.1: 5 套浅色主题逐屏走查 (尤其 Gold/Coral 玻璃 tint 可读性) + API<31 降级遮罩验证 (需 Android 10/11 设备)。
- M14.3: 玻璃滚动性能采样 (systrace/JankStats, P95 <16.7ms 或给量化结论; 必要时 HazeInputScale)。
- M5.5~5.7 UI 验收: 通话页/直播观看页/录像回放的 uiautomator+harness 验收未闭环 (主线见 docs/context/infra-server.md)。
- PointCloud3dView 漫游状态机 ~130 行死代码清理 (2026-07-01 留档的独立清理项, 需配独立渲染验证)。
