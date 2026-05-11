package io.gomob.feature.scan3d

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

/**
 * 标定页 UI 框架 — 真实 Charuco 求解 / Berxel SDK 接入在 M1-M2 阶段实现。
 * 当前显示固定 demo 状态(8/12 帧 / 0.42 px / 已采集列表)以便走通设计验收。
 */
@Composable
fun CalibrationRoute(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(
            title = "双摄标定",
            onBack = onBack,
            eyebrow = "Charuco · 主从外参 + 内参",
            trailing = { StatusTag(text = "采集中", tone = StatusTone.Accent, showDot = true) },
        )

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = Gomob.spacing.s16, vertical = Gomob.spacing.s12),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        ) {
            // 引导预览框 — 「将标定板对齐方框」
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(Gomob.shapes.r3)
                    .background(Gomob.colors.bg2),
            ) {
                // 取景框
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.7f)
                        .aspectRatio(1f)
                        .border(Gomob.spacing.hairline * 2, Gomob.colors.accent, Gomob.shapes.r2),
                )
                Box(Modifier.align(Alignment.BottomCenter).padding(Gomob.spacing.s12)) {
                    Text(
                        "将 Charuco 板对齐取景框,保持 ≥30cm 距离",
                        style = Gomob.type.bodySm,
                        color = Gomob.colors.fg2,
                    )
                }
            }

            // 仪表行
            Row(horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12)) {
                HairlineCard(modifier = Modifier.weight(1f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                        Text("已采集", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
                        Text("8 / 12", style = Gomob.type.metricLg, color = Gomob.colors.accentStrong)
                        Text("剩 4 帧到求解阈值", style = Gomob.type.caption, color = Gomob.colors.fg3)
                    }
                }
                HairlineCard(modifier = Modifier.weight(1f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                        Text("当前误差", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
                        Text("0.42 px", style = Gomob.type.metricMd, color = Gomob.colors.fg0)
                        Text("目标 < 0.5 px", style = Gomob.type.caption, color = Gomob.colors.fg3)
                    }
                }
            }

            // 帧列表
            HairlineCard {
                Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8)) {
                    Text("最近帧", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
                    repeat(4) { i ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "帧 ${(8 - i).toString().padStart(2, '0')}",
                                style = Gomob.type.numInline,
                                color = Gomob.colors.fg1,
                            )
                            Text(
                                "${(0.38f + i * 0.04f).format2()} px",
                                style = Gomob.type.numInline,
                                color = Gomob.colors.fg2,
                            )
                            StatusTag(text = "OK", tone = StatusTone.Ok)
                        }
                    }
                }
            }
        }

        // 底部 CTA
        Row(
            Modifier.fillMaxWidth().background(Gomob.colors.bg0).padding(Gomob.spacing.s16),
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
        ) {
            CalibButton(
                modifier = Modifier.weight(1f),
                text = "重新采集",
                fill = Gomob.colors.bg2,
                fg = Gomob.colors.fg1,
            )
            CalibButton(
                modifier = Modifier.weight(2f),
                text = "求解并保存 (4 帧后)",
                fill = Gomob.colors.accentSoft,
                fg = Gomob.colors.accent,
            )
        }
    }
}

@Composable
private fun CalibButton(
    modifier: Modifier = Modifier,
    text: String,
    fill: androidx.compose.ui.graphics.Color,
    fg: androidx.compose.ui.graphics.Color,
) {
    Box(
        modifier
            .height(Gomob.spacing.touchMin)
            .clip(Gomob.shapes.r2)
            .background(fill)
            .clickable {},
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Gomob.type.body, color = fg)
    }
}

private fun Float.format2(): String = "%.2f".format(this)
