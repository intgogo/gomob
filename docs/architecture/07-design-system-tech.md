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
