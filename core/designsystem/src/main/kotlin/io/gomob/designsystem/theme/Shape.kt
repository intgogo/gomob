package io.gomob.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp

/**
 * gomob · Shape tokens
 *
 * 圆角刻意保持小（4 / 6 / 8）— 工业仪表风。
 * 想让某个组件感觉更"硬"用 r1；标准卡片 r3；按钮 / 输入 r2。
 * 完全圆胶囊只用在状态点 / 头像 / 小指示器。
 */
@Immutable
data class GomobShapes(
    val r1: RoundedCornerShape = RoundedCornerShape(4.dp),
    val r2: RoundedCornerShape = RoundedCornerShape(6.dp),
    val r3: RoundedCornerShape = RoundedCornerShape(8.dp),
    val pill: RoundedCornerShape = RoundedCornerShape(999.dp),
)

internal val DefaultShapes = GomobShapes()
