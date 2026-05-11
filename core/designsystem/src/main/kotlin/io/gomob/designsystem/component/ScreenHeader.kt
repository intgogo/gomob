package io.gomob.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import io.gomob.designsystem.motion.fixedDuringPageDrag
import io.gomob.designsystem.theme.Gomob

/**
 * 屏幕顶部条：左 大标题（title）+ 下方副标题（eyebrow），右 trailing 槽位。
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
    Row(
        modifier
            .fixedDuringPageDrag()
            .fillMaxWidth()
            .background(Gomob.colors.bg0)
            .padding(horizontal = Gomob.spacing.s16, vertical = Gomob.spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderTitleStack(
            title = title,
            eyebrow = eyebrow,
            modifier = Modifier
                .weight(1f)
                .padding(end = Gomob.spacing.s12),
        )
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
            style = Gomob.type.display,
            color = Gomob.colors.fg0,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (eyebrow != null) {
            Text(
                text = eyebrow,
                style = Gomob.type.eyebrow,
                color = Gomob.colors.fg2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
