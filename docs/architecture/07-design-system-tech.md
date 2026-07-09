# 07 — 设计系统：科技风（Tech Style）

> 主题：**深空 + 霓虹蓝青 + 玻璃拟态**。
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

## 5. 暗色 / 浅色

**默认深色，不做浅色版本**（场景多为工位 + 户外，深色更耐眼且科技感更强；
后续如果用户反馈强光下读不清屏，再追加浅色变体）。

## 6. 动画原则

- 页面切换：滑动 + 淡入 250ms `easeOutCubic`
- 卡片入场：从下方 16dp 淡入 + 上移
- 加载骨架：流光（左→右 1.5s）
- 状态变更：红/绿 / 警告色入场带 200ms tween
- 不滥用动画 — **不要旋转/弹跳类活泼动效**，专业工具 App 节制

## 7. 实现路径

`core:designsystem` 暴露：
```kotlin
GomobTheme(useDynamicColor = false) {
    // 强制使用本科技风主题，不被系统 Material You 覆盖
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
> 色板/组件命名以 `theme/Color.kt`(5 套 GomobColors × 明暗)与 `component/` 现码为准。

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
- `LocalContentBottomInset` — Shell 在 root tab 屏下发 TabBar 总高(56dp+导航栏, ime 时动画归 0)。
- 真 edge-to-edge: MainActivity 不再全局吃 systemBars, 每屏经 scaffold/TabBar 自理 inset;
  `systemBarsPaddingRequired` 仅余"视频沉浸页"语义(驱动状态栏图标配色)。
- 玻璃样式全部从语义 token 派生(`glassChromeStyle(colors)`), 不引入新原色;
  浅色 tint 0.72 / 深色 0.64, 保证 fg0 在花内容上可读。

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
