# Haze 采样源嵌套 = 玻璃全透明 (2026-07-09)

## Why

毛玻璃改造首版在 NavHost 挂 `Modifier.haze(shellState)` 全局源, 每屏 GlassHeaderScaffold
内容层又挂自己的源 —— 真机 (2510DRK44C, Android 16) 上**所有 hazeChild 静默不画**:
不模糊、不 tint, 内容原样从 TabBar/Header 底下透出, 看起来像 z 序反了, 其实是
`HazeEffectNode.isValid()` 的源区域匹配失败。层中录层 (GraphicsLayer 录制里再录制)
把外层采样层录空。换 Haze 1.3.1 / 1.2.2 都一样 → 不是版本 bug, 是拓扑错误。
编译、单测全绿, 只有真机肉眼能发现 —— UI 双重验证规范又一次救场。

## How to apply

1. 全 App 只建一个 `HazeState`(Shell), 经 `LocalHazeState` 下发。
2. 采样源只挂一处: 当前屏 GlassHeaderScaffold 的内容层。NavHost 等外层容器不挂源。
3. 消费者 (TabBar/Header/吸底栏/来电浮窗) 全部 hazeChild 同一 state, 且不得是源节点后代。
4. Haze 钉 1.2.2 (Compose 1.7 世代, API=haze/hazeChild); 升 Compose 1.8+ 后再评估 1.5+。
5. 验收必须真机看"内容滚过 chrome 时是否模糊+tint", 空列表/不滚动的屏分辨不出玻璃死活。

相关: docs/architecture/07-design-system-tech.md §8。
