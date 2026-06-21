package io.gomob.feature.collaboration

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.component.HairlineCard
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.theme.Gomob

// TODO(demo-data R1): 整个复核详情为静态占位假数据，未按 reviewId 拉取真实工单，也未接裁决 API。
// 终态应按 reviewId 调用复核工单详情接口（周历 / 异常清单 / 审核结果 / 车架号图均来自后端），
// 并把下方“结果正确 / 结果错误”按钮接裁决提交接口。见 docs/architecture 协作复核章节。
private val WEEK_DAYS = listOf(23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33)
private val DAY_DOTS = listOf(
    DayDot(1, false), DayDot(2, false), DayDot(3, true), DayDot(4, false),
    DayDot(5, false), DayDot(6, true), DayDot(7, false), DayDot(8, false),
    DayDot(9, false), DayDot(10, true), DayDot(11, false), DayDot(12, false),
    DayDot(13, false), DayDot(14, false),
)

private data class DayDot(val day: Int, val warn: Boolean)
private data class Anomaly(val title: String, val detail: String, val tone: StatusTone)

private val ANOMALIES = listOf(
    Anomaly("合规性异常", "年份代码异常 — S/T  出厂日期 2021.07  车辆年份代码 F", StatusTone.Danger),
    Anomaly("外观异常", "前保险杠右下方有未登记加装件", StatusTone.Warn),
)

@Composable
fun ReviewDetailRoute(reviewId: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(
            title = "抽查复核",
            onBack = onBack,
            eyebrow = "工单 · $reviewId",
            trailing = { StatusTag(text = "待复核", tone = StatusTone.Warn, showDot = true) },
        )

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Gomob.spacing.s16, vertical = Gomob.spacing.s12),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        ) {
            // 周日历
            HairlineCard {
                Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8)) {
                    Text("周历 · 第 23-33 周", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
                    Row(horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                        WEEK_DAYS.forEach { w ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(Gomob.spacing.cellH28)
                                    .clip(Gomob.shapes.r1)
                                    .background(if (w == 28) Gomob.colors.accentSoft else Gomob.colors.bg2),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    w.toString(),
                                    style = Gomob.type.numInline,
                                    color = if (w == 28) Gomob.colors.accent else Gomob.colors.fg2,
                                )
                            }
                        }
                    }
                    Text("日历 · 01-14 日", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
                    Row(horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                        DAY_DOTS.forEach { d ->
                            Column(
                                Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2),
                            ) {
                                Text(
                                    d.day.toString().padStart(2, '0'),
                                    style = Gomob.type.numInline,
                                    color = Gomob.colors.fg2,
                                )
                                Box(
                                    Modifier
                                        .size(Gomob.spacing.dot4)
                                        .clip(CircleShape)
                                        .background(if (d.warn) Gomob.colors.danger else Gomob.colors.ok),
                                )
                            }
                        }
                    }
                }
            }

            // 异常清单
            HairlineCard {
                Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12)) {
                    Text("异常清单", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
                    ANOMALIES.forEach { AnomalyRow(it) }
                }
            }

            // 审核结果
            HairlineCard {
                Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                    Text("审核结果", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
                    Text(
                        "车架号年份代码验证异常,核验未通过。",
                        style = Gomob.type.body,
                        color = Gomob.colors.fg0,
                    )
                }
            }

            // 车架号图位
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(Gomob.shapes.r3)
                    .background(Gomob.colors.bg2),
                contentAlignment = Alignment.Center,
            ) {
                Text("车架号图 · 多张辅助图", style = Gomob.type.bodySm, color = Gomob.colors.fg3)
            }
        }

        // 三按钮 — 结果正确 / 结果错误 / 跳过
        Row(
            Modifier
                .fillMaxWidth()
                .background(Gomob.colors.bg0)
                .padding(Gomob.spacing.s16),
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
        ) {
            // TODO(demo-data R1): 三个裁决按钮尚未接复核裁决提交接口；当前点击仅提示“未实现”，
            // 不做静默无效操作。终态应提交 (reviewId, 裁决结果) 到后端并返回上一页。
            val context = LocalContext.current
            val notImplemented = {
                Toast.makeText(context, "复核裁决暂未实现", Toast.LENGTH_SHORT).show()
            }
            DecisionButton(
                modifier = Modifier.weight(1f),
                text = "结果正确",
                fill = Gomob.colors.okSoft,
                fg = Gomob.colors.ok,
                onClick = notImplemented,
            )
            DecisionButton(
                modifier = Modifier.weight(1f),
                text = "结果错误",
                fill = Gomob.colors.dangerSoft,
                fg = Gomob.colors.danger,
                onClick = notImplemented,
            )
            DecisionButton(
                modifier = Modifier.weight(1f),
                text = "跳过",
                fill = Gomob.colors.bg2,
                fg = Gomob.colors.fg2,
                onClick = notImplemented,
            )
        }
    }
}

@Composable
private fun AnomalyRow(a: Anomaly) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
        verticalAlignment = Alignment.Top,
    ) {
        StatusTag(text = a.title, tone = a.tone)
        Text(a.detail, style = Gomob.type.bodySm, color = Gomob.colors.fg1)
    }
}

@Composable
private fun DecisionButton(
    modifier: Modifier = Modifier,
    text: String,
    fill: Color,
    fg: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(Gomob.spacing.touchMin)
            .clip(Gomob.shapes.r2)
            .background(fill)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Gomob.type.body, color = fg)
    }
}
