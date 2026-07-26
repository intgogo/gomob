package io.gomob.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import io.gomob.designsystem.glass.LocalGlassHeader
import io.gomob.designsystem.motion.fixedDuringPageDrag
import io.gomob.designsystem.theme.Gomob

/**
 * root 页顶部条：固定 52dp，左侧 18sp/600 单行标题，右侧 trailing 槽位。
 *
 * 显式组件而非隐式 padding — 每屏从这里挂能保证横向对齐和留白一致。
 *
 * 排版次序按最新设计：title 在上、eyebrow 在下（参数名 eyebrow 出于历史命名保留，
 * 语义现为「副标题」）。
 */
@Composable
fun ScreenHeader(
    title: String,
    eyebrow: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val feedbackTrigger = LocalFeedbackTitleTrigger.current
    // 在 GlassHeaderScaffold 内由玻璃层画底；独立使用时保持 bg0 实底
    val inGlass = LocalGlassHeader.current
    Row(
        modifier
            .then(if (inGlass) Modifier else Modifier.fixedDuringPageDrag())
            .fillMaxWidth()
            .height(Gomob.spacing.headerHeight)
            .then(if (inGlass) Modifier else Modifier.background(Gomob.colors.bg0))
            .padding(horizontal = Gomob.spacing.pageGutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .then(
                    if (feedbackTrigger != null) {
                        Modifier.feedbackTitleFiveTap(title, feedbackTrigger)
                    } else {
                        Modifier
                    },
                )
                .padding(end = Gomob.spacing.s12),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2),
        ) {
            Text(
                text = title,
                style = Gomob.type.screenTitle,
                color = Gomob.colors.fg0,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (eyebrow != null) {
                Text(
                    text = eyebrow,
                    style = Gomob.type.micro,
                    color = Gomob.colors.fg2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) trailing()
    }
}

@Composable
internal fun HeaderTitleStack(
    title: String,
    eyebrow: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2)) {
        Text(
            text = title,
            style = Gomob.type.title,
            color = Gomob.colors.fg0,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (eyebrow != null) {
            Text(
                text = eyebrow,
                style = Gomob.type.micro,
                color = Gomob.colors.fg3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
