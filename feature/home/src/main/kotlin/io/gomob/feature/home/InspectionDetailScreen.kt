package io.gomob.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.component.HairlineCard
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.theme.Gomob

private data class FieldRow(val label: String, val value: String, val isMono: Boolean = false)

private val DEMO_FIELDS = listOf(
    FieldRow("VIN", "LSVHM133022221761", isMono = true),
    FieldRow("车型号", "SVW7186LJD", isMono = true),
    FieldRow("车辆品牌", "上汽大众"),
    FieldRow("车辆类型", "小型轿车"),
    FieldRow("车辆颜色", "极地白"),
    FieldRow("年份码", "M (2021)", isMono = true),
    FieldRow("出厂日期", "2021/07/18"),
    FieldRow("发动机号", "EA211-CYV-018371", isMono = true),
)

@Composable
fun InspectionDetailRoute(inspectionId: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(
            title = "预审报告",
            onBack = onBack,
            eyebrow = "工单 · $inspectionId",
            trailing = { StatusTag(text = "存在预警", tone = StatusTone.Danger, showDot = true) },
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Gomob.spacing.s16, vertical = Gomob.spacing.s12),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        ) {
            // 车架号图位
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(Gomob.shapes.r3)
                    .background(Gomob.colors.bg2)
                    .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r3),
                contentAlignment = Alignment.Center,
            ) {
                Text("车架号扫描图", style = Gomob.type.bodySm, color = Gomob.colors.fg3)
            }

            HairlineCard {
                Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8)) {
                    Text("车辆基础", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
                    DEMO_FIELDS.forEach { FieldLine(it) }
                }
            }

            HairlineCard {
                Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("不予通过原因", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
                        StatusTag(text = "外廓尺寸异常", tone = StatusTone.Danger)
                    }
                    Text(
                        "实测车长 4870mm,与铭牌登记 4760mm 偏差 +110mm,超出 ±50mm 容差。",
                        style = Gomob.type.bodySm,
                        color = Gomob.colors.fg1,
                    )
                    Text(
                        "建议:重新测量并核对铭牌,如有改装需提交说明。",
                        style = Gomob.type.caption,
                        color = Gomob.colors.fg3,
                    )
                }
            }

            HairlineCard {
                Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                    Text("AI 预审", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
                    Text("置信度 0.92", style = Gomob.type.metricMd, color = Gomob.colors.accentStrong)
                    Text("耗时 1.2s · 模型 gomob-v0.4", style = Gomob.type.caption, color = Gomob.colors.fg3)
                }
            }
        }
    }
}

@Composable
private fun FieldLine(f: FieldRow) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(f.label, style = Gomob.type.caption, color = Gomob.colors.fg3)
        Text(
            f.value,
            style = if (f.isMono) Gomob.type.numInline else Gomob.type.bodySm,
            color = Gomob.colors.fg0,
        )
    }
}
