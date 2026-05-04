package io.gomob.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.gomob.designsystem.theme.Gomob

enum class MetricTrend { Up, Down, Flat }

/**
 * 仪表卡：顶部 eyebrow / 中部大数字 / 右侧 delta / 底部说明。
 *
 * - 大数字走 Mob3d.type.metricLg（mono + tabular），多行同栏不抖。
 * - 颜色全部从语义 token 取。要让某张图变红 → trend = Down。
 */
@Composable
fun MetricTile(
    label: String,
    value: String,
    delta: String? = null,
    trend: MetricTrend = MetricTrend.Flat,
    caption: String? = null,
    modifier: Modifier = Modifier,
) {
    val deltaColor: Color = when (trend) {
        MetricTrend.Up -> Gomob.colors.ok
        MetricTrend.Down -> Gomob.colors.danger
        MetricTrend.Flat -> Gomob.colors.fg2
    }

    HairlineCard(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Gomob.spacing.metricTileMinH),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8)) {
            Text(text = label, style = Gomob.type.eyebrow, color = Gomob.colors.fg2)

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = Gomob.type.metricLg,
                    color = Gomob.colors.accentStrong,
                )
                if (delta != null) {
                    Spacer(Modifier.width(Gomob.spacing.s8))
                    Text(text = delta, style = Gomob.type.numInline, color = deltaColor)
                }
            }

            if (caption != null) {
                Text(text = caption, style = Gomob.type.caption, color = Gomob.colors.fg3)
            }
        }
    }
}
