package io.gomob.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.gomob.designsystem.glass.LocalGlassHeader
import io.gomob.designsystem.motion.fixedDuringPageDrag
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob

/**
 * 二级页顶栏:返回箭头 + 当前页大标题 + 来源页/辅助语境小标题 + trailing 槽位。
 *
 * 与 [ScreenHeader] 区分:
 * - ScreenHeader 是 root tab 的大字号 display 标题(无返回)
 * - BackHeader 是二级页面的返回标题(有返回)
 */
@Composable
fun BackHeader(
    title: String,
    onBack: () -> Unit,
    eyebrow: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val feedbackTrigger = LocalFeedbackTitleLongPress.current
    // 在 GlassHeaderScaffold 内由玻璃层画底；独立使用时保持 bg0 实底
    val inGlass = LocalGlassHeader.current
    Column(
        modifier
            .fixedDuringPageDrag()
            .fillMaxWidth()
            .then(if (inGlass) Modifier else Modifier.background(Gomob.colors.bg0)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 0.dp, top = Gomob.spacing.s12, end = Gomob.spacing.s12, bottom = Gomob.spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(Gomob.spacing.touchMin)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = GomobIcons.ChevronLeft,
                    contentDescription = "返回",
                    modifier = Modifier.size(26.dp),
                    tint = Gomob.colors.accent,
                )
            }
            HeaderTitleStack(
                title = title,
                eyebrow = eyebrow,
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (feedbackTrigger != null) {
                            Modifier.feedbackTitleLongPress(title, feedbackTrigger)
                        } else {
                            Modifier
                        },
                    )
                    .padding(start = Gomob.spacing.s4, end = Gomob.spacing.s12),
            )
            if (trailing != null) trailing()
        }
    }
}
