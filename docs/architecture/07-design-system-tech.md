# 07 — 设计系统：科技风（Tech Style）

> 主题：**浅色科技 + 克制主题色 + 玻璃拟态**。
> 目标：在工位 / 户外 / 弱光环境中清晰可读，体现"专业检测设备"质感，
> 区别于"通用聊天 / 社交"App 的扁平 Material You 视觉。

## 1. 核心色板

### 1.1 背景层（Surface）

| Token | HEX | 用途 |
|-------|-----|------|
| `surface_deep` | `#070B17` | App 最底层（深空黑 + 一点蓝） |
| `surface_card` | `#0F172A` | 卡片底色（深空蓝） |
| `surface_card_high` | `#1E293B` | 浮起卡片 / Sheet |
| `surface_overlay` | `#0F172A 80%` | 半透明蒙层（玻璃拟态底色） |

### 1.2 主色 / 强调色

| Token | HEX | 用途 |
|-------|-----|------|
| `primary` | `#22D3EE` | 主 CTA / 关键图标（霓虹青） |
| `primary_dim` | `#0E7490` | 主色禁用 / 边框 |
| `accent` | `#A78BFA` | 强调（紫罗兰，用于 3D / AI 相关功能） |
| `accent_dim` | `#5B21B6` | 强调色边框 / 描边 |

### 1.3 状态色

| Token | HEX | 用途 |
|-------|-----|------|
| `success` | `#10B981` | 通过 / 正常 |
| `warning` | `#F59E0B` | 预警 |
| `danger` | `#EF4444` | 错误 / 不通过 / 退出登录 |
| `info` | `#38BDF8` | 提示 |

### 1.4 文字 / 描边

| Token | HEX | 用途 |
|-------|-----|------|
| `text_primary` | `#F1F5F9` | 主文字 |
| `text_secondary` | `#94A3B8` | 次文字 |
| `text_tertiary` | `#64748B` | 三级 / placeholder |
| `border_subtle` | `#FFFFFF 0A%` (≈10/255) | 卡片描边 |
| `border_glow` | `#22D3EE 33%` | 焦点 / 选中描边 |

## 2. 字体阶梯

中文使用系统默认（思源 / 苹方 / 微软雅黑），数字/英文使用更几何的等宽：
- Display：自带 Roboto / 后续可换 `Sora` / `Orbitron`（待评估字体许可）
- Body：系统默认

| Style | Size / Weight | 用途 |
|-------|---------------|------|
| `Display L` | 48sp / Bold | 闪屏 LOGO |
| `Headline L` | 32sp / SemiBold | 主页大标题 / 数据卡数字 |
| `Headline M` | 24sp / SemiBold | 二级页标题 |
| `Title L` | 20sp / Medium | 卡片标题 |
| `Title M` | 16sp / Medium | 列表项主文字 |
| `Body L` | 14sp / Regular | 正文 |
| `Body M` | 12sp / Regular | 次文字 / 时间戳 |
| `Label` | 11sp / Medium / Tracking 1.5sp | tab / chip / 大写英文 |

## 3. 形状 / 圆角

| Token | dp | 用途 |
|-------|----|----|
| `shape_xs` | 4 | chip / pill |
| `shape_sm` | 8 | 输入框 / 按钮 |
| `shape_md` | 12 | 列表项 |
| `shape_lg` | 16 | 卡片 |
| `shape_xl` | 24 | 主 CTA / 顶层卡片 |
| `shape_full` | 999 | 圆形按钮 / FAB |

## 4. 关键组件视觉规则

### 4.1 GlassCard（玻璃拟态卡片）

- 底：`surface_card` + 4dp 内 padding
- 边：1px `border_subtle` (普通) / `border_glow` (focus)
- 阴影：x=0 y=4 blur=24 spread=0 color=`#000000 40%`
- 圆角：`shape_lg` (16dp)
- 可选：右上 / 右下 角落 16x16dp 渐变描边亮线（科技感装饰）

### 4.2 PrimaryButton

- 渐变填充：`linear(to right, #22D3EE → #A78BFA)`
- 文字：`text_primary` Bold
- 圆角：`shape_lg`
- pressed：透明度 80% + 内发光
- 动画：长按时呼吸光（scale 0.98 ~ 1.02 1s 循环）

### 4.3 GhostButton

- 背景：透明
- 描边：1px `border_glow`
- 文字：`primary`
- 圆角：`shape_md`

### 4.4 数据卡（StatCard）

- 数字：`Headline L` 颜色 = 状态色（红预警 / 绿正常）
- 标签：`Body M` `text_secondary`
- 趋势小图：sparkline，颜色 = 数字色
- 卡内右上角：sparkline 或趋势 chip

### 4.5 NavigationBar（5 tab）

- 高度：80dp + 底部安全区
- 容器：`surface_card` + 顶部 1px `border_subtle`
- icon size：24dp，未选中 `text_tertiary`，选中 `primary`（带轻微发光）
- 中间「3D」tab 凸起（FAB 风格）：
  - 直径 56dp 圆形
  - 渐变填充 `primary → accent`
  - 比其它 tab 高 8dp
  - 中心 AR 立方体图标（白色描边）

### 4.6 输入框（TechTextField）

- 背景：`surface_card_high`
- 圆角：`shape_md` (12dp)
- focus：底边 2px `primary` 渐入 + 文字阴影
- 错误：底边 2px `danger`
- 左右图标：`text_secondary` 24dp

### 4.7 Chip / Filter

- 未选中：透明背景 + 1px `border_subtle` + `text_secondary`
- 选中：`primary 16%` 背景 + 1px `border_glow` + `primary` 文字

### 4.8 视频通话 / 实时画面

- 背景：纯黑 `#000000`
- 控件浮于画面：`surface_overlay` + blur 16dp（如果设备支持）+ `border_subtle`

## 5. 浅色主题

应用固定使用浅色，不再暴露“跟随系统 / 浅色 / 深色”外观模式。用户只选择色彩主题，
所有预览与运行态均使用对应色板的 light 变体；首装和恢复默认均为 Mint。

## 6. 动画原则

- 页面切换：滑动 + 淡入 250ms `easeOutCubic`
- 卡片入场：从下方 16dp 淡入 + 上移
- 加载骨架：流光（左→右 1.5s）
- 状态变更：红/绿 / 警告色入场带 200ms tween
- 不滥用动画 — **不要旋转/弹跳类活泼动效**，专业工具 App 节制

## 7. 实现路径

`core:designsystem` 暴露：
```kotlin
GomobTheme(colorScheme = ColorScheme.Mint) {
    // 固定浅色，只切换五套色彩主题
}
```

包含：
- `theme/Color.kt` — 色 token
- `theme/Type.kt` — Typography
- `theme/Shape.kt` — Shapes
- `theme/Theme.kt` — `GomobTheme` Composable
- `component/GlassCard.kt`
- `component/PrimaryButton.kt`
- `component/GhostButton.kt`
- `component/StatCard.kt`
- `component/TechTextField.kt`
- `component/TopAppBar.kt`（沉浸式 / 透明 / 高斯模糊背景）

实现详见后续 commit。

## 8. 毛玻璃体系（2026-07 落地, 代码真理源）

> 本节对应 `core/designsystem/glass/` 实际实现; 上文 1-7 节为早期设计稿,
> 色板/组件命名以 `theme/Color.kt`（5 套浅色 GomobColors）与 `component/` 现码为准。

### 8.1 分层原则 — 按"玻璃下有没有高频内容穿过"选实现

| 档位 | 实现 | 适用 | 成本 |
|------|------|------|------|
| 真模糊 chrome | `Modifier.glassChrome()` (Haze backdrop blur) | TabBar / Header / 吸底输入条 / 来电浮窗 | API 31+ GPU 模糊; API 26-30 自动降级 0.94 遮罩 |
| 拟玻璃面板 | `GlassPanel` / `Modifier.glassPanelBg()` | 卡片(HairlineCard) / Dialog / BottomSheet | 零模糊: 半透明 bg1 + 顶缘高光 + line1 细边 |

依据: Dialog/Sheet 是独立 window 采样不到 Activity 内容; 卡片下面只有低频
AmbientGlow 光晕(模糊结果 ≈ 原样), 真模糊纯浪费。滚动内容会穿过的悬浮条才配真模糊。

### 8.2 关键件

- `GlassHeaderScaffold(header, overlay, content)` — 屏骨架: bg0 + AmbientGlow 氛围光晕
  + 内容层(haze 采样源, 全屏) + 玻璃 header(自动吃状态栏, 滚动后渐显分隔线) + overlay 槽
  (吸底输入条/侧滑面板, 可取 LocalHazeState 做真模糊)。content 经 PaddingValues 拿避让区。
- 页面弹性拖动只允许移动内容平面；`GlassHeaderScaffold` 的完整 header 玻璃和 overlay、Shell 的
  完整底部玻璃统一挂 `fixedDuringPageDrag`。禁止只在标题文字、Tab 图标或输入控件内层抵消，
  否则会造成玻璃表面随内容移动、控件留在原位的错层。
- `LocalContentBottomInset` — Shell 在 root tab 屏下发 TabBar 总高(54dp+导航栏, ime 时动画归 0)。
- 真 edge-to-edge: MainActivity 不再全局吃 systemBars, 每屏经 scaffold/TabBar 自理 inset;
  `systemBarsPaddingRequired` 仅余"视频沉浸页"语义(驱动状态栏图标配色)。
- 玻璃样式全部从语义 token 派生(`glassChromeStyle(colors)`), 不引入新原色;
  固定使用 0.72 tint，保证 fg0 在花内容上可读。

### 8.2b ★ HazeState 拓扑铁律 — 全 App 单 state, 采样源绝不嵌套

**真机踩坑(2026-07-09, 2510DRK44C)**: 最初 NavHost 挂一层 `haze()` 源、每屏 scaffold
内容层再挂一层 → 层中录层把采样层录空, **所有 hazeChild 全透明**(不模糊不 tint,
内容原样从 chrome 底下透出), 1.3.1/1.2.2 两版本一致 → 拓扑错误而非版本 bug。正确拓扑:

- Shell 建唯一 `HazeState`, 经 `LocalHazeState` 下发整棵树;
- **采样源只有一处**: 当前屏 GlassHeaderScaffold 的内容层(`Modifier.haze(state)`);
  NavHost/其它容器一律不挂源。转场瞬间两屏源共存属多 area, 正常;
- 消费者(TabBar / Header / 吸底栏 / 来电浮窗)全部 `hazeChild` 同一个 state,
  且必须不是源节点的后代(scaffold 里 header/overlay 与内容层是兄弟, 满足);
- Haze 版本钉 **1.2.2**(Compose 1.7 世代), API 名为 `haze`/`hazeChild`。
- 详见 docs/agent-memory/finding_haze_nested_sources_transparent_2026-07-09.md。

### 8.3 动效补充(2026-07)

- TabBar: 选中图标弹性放大 1.08 + 按压回缩 0.88(spring), 颜色 200ms tween, 无方块 ripple。
- SegmentedTabs: accentSoft 滑动指示块 220ms 滑移。
- 可点卡片: `Modifier.pressScale()` 按压 0.985 弹性回缩。

### 8.4 M14 版式优化回迁（2026-07-10）

设计真理源分两层：`gomob 交互原型.dc.html` 决定页面结构与状态，
`毛玻璃版式优化.dc.html` 决定 token、密度和表面规范；交付包里的 `code/` 是旧快照，禁止覆盖现仓业务代码。

- 全局版式：页面边距 `pageGutter=16dp`、卡间距 `cardGap=12dp`、分组间距
  `sectionGap=20dp`；图标列表行 64dp、会话行 68dp、会话输入栏默认 64dp。
- 字阶：根页 hero、二级页标题、分组标题分别使用 `heroTitle`、`screenTitle`、
  `sectionTitle`；时间、计数、VIN、尺寸一律使用 mono 数字样式。
- 根页：Header 固定 52dp；标签页使用文字下划线 `HeaderTabs`；首页输入条与 TabBar
  共用单块 `glassChrome`，IME 弹起时只隐藏 TabBar，输入条继续避让键盘。
- 会话：Header 副标题只显示会话模型可证明的员工号/会话类型，不伪造角色或在线状态；
  撤回消息是居中系统文字；己方气泡使用 `accentSoft`；业务流水卡使用 `bg1 + accentLine + StatusTag`；
  基础输入行 64dp，业务流水、图库、视频等附加动作收进“+”展开区。
- VIN：真实深度/彩色帧合并为一张暗色双面板；端侧估算正射必须标“估算预览”，
  只有服务端权威还原成功才开放外部 OCR；逐字符分数使用中性 accent，缺分数显示“—”，
  不用人为阈值染成通过/警告。仅合法 17 位显示“识别完成”，其他显示“需复核”；禁止展示
  外部算法没有提供的“通过/未通过/厂家字形比对”结论。还原提示使用
  `warnSoft/okSoft/dangerSoft`，不画纸纹假拓印。
- 3D 根页：最近区只查询默认工位 `GET /v1/scans/laser/latest` 的单条 `done`；加载、
  无数据、失败重试分态。仅展示真实 `scan_id`、`points` 与空工位背景标记，不伪造 VIN、
  缩略图、时间、耗时、“查看全部”或可跳转能力。
- 外廓工位：扫描态仅投影真实 A/B 点云，完成态只渲染真实融合点云；通道只保留融合/A/B。
  测量浮层只消费真实 L/W/H、`compliant`、`violations`，不制造 C/D、RGB 缩略、耗时、
  轴距/货箱、尺寸几何投影或归档动作。
- 暗色媒体视口：点云/相机面板固定深色表面，内部文字使用白色透明度层级；不能直接使用
  浅色主题的 `fg2/fg3` 压在黑底上。面板外的状态和操作仍全部走主题语义 token。

### 8.5 登录后内部 QA 截图反馈

- 隐藏入口统一挂在 `ScreenHeader`、`BackHeader` 及会话/通话/第一视角自定义标题栏：
  同一页面大标题相邻点击间隔不超过 700ms，连续 5 次后触发；标题切换、超时或时钟回退重置。
- `AppFeedbackHost` 只包登录后的业务壳。该能力是受鉴权的内部 QA 通道，不覆盖登录/注册页，
  也不替代面向生产用户的工单、对象存储和集中检索系统。
- 普通 Compose/TextureView 页面直接用 Window PixelCopy。遇 Filament `SurfaceView` 时，渲染器必须
  实现 `FeedbackCaptureSurface`：停止 Choreographer、`Engine.flushAndWait` 等待 GPU 稳定帧；随后
  `View.draw()` 取得带透明 Surface 孔洞的 UI 层，Surface 单独 PixelCopy，再用 `DST_OVER` 铺到
  UI 后面。未知 Surface 明确失败，不用颜色阈值猜测或跨帧拼接。
- 编辑器在截图坐标系内保存 0..1 归一化自由路径，单路径最多 512 点；过短/过小/纯直线手势
  不生成标注。每条路径自动编号并要求填写非空说明，支持修改、删除、撤销；提交中和提交成功后
  进入只读态，保证界面内容与已上传报告一致。
- `POST /v1/feedback` 记录认证用户 ID、原图、带编号标注图、路径/包围框及说明。开发环境经
  `dev.sh` 固定落仓库根 `.dev/app-feedback/<fb_id>/`；目录/文件权限为 0700/0600，四个文件先写
  同根临时目录并同步，再原子重命名发布。PNG 做完整解码、像素上限和双图尺寸一致性校验，内部
  存储错误只写服务端日志，不向端侧泄露路径。

验证必须同时覆盖 Compose 布局与真实业务状态：会话撤回/附件、VIN 估算与权威还原切换、
激光 A/B 实时点流、融合完成及合规/超限/不可判定三态。编译通过不能替代设备链路验证。
