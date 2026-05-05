package io.gomob.feature.scan3d

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.component.HairlineCard
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.theme.Gomob

/**
 * 扫描页 UI 框架 — 真实 RGBD 同步采集 / 点云预览在 M1-M3 阶段实现。
 *
 * 视觉骨架(沿用 29313af5 设计):
 *   1. 全屏 RGB 预览(占位 cameraSlot — 接 Berxel + CameraX)
 *   2. 顶部蓝色进度卡:今日已扫描 N 辆 / 当日预警 N
 *   3. 底部 CTA:下载查验单 PDF
 *   4. 拍摄按钮(中) + 切换主/深度预览(右)
 */
@Composable
fun ScanCaptureRoute(
    onBack: () -> Unit,
    cameraSlot: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(
            title = "RGBD 扫描",
            onBack = onBack,
            eyebrow = "Berxel iHawk-072 · 主从同步",
            trailing = { StatusTag(text = "采集中", tone = StatusTone.Accent, showDot = true) },
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            // 相机槽位
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Gomob.colors.bg2),
            ) { cameraSlot() }

            // 顶部进度卡 — accent overlay
            HairlineCard(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(Gomob.spacing.s16)
                    .fillMaxWidth(),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2)) {
                        Text("今日扫描", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
                        Text("12 辆", style = Gomob.type.metricMd, color = Gomob.colors.accentStrong)
                    }
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2),
                    ) {
                        Text("当日预警", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
                        Text("3 辆", style = Gomob.type.metricMd, color = Gomob.colors.danger)
                    }
                }
            }

            // 中心定位框
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.7f)
                    .padding(Gomob.spacing.s32)
                    .border(Gomob.spacing.hairline * 2, Gomob.colors.accent, Gomob.shapes.r2)
                    .padding(Gomob.spacing.s32),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "对准 VIN 区域",
                    style = Gomob.type.bodySm,
                    color = Gomob.colors.accent,
                )
            }
        }

        // 底部控制栏
        Box(
            Modifier.fillMaxWidth().height(Gomob.spacing.hairline).background(Gomob.colors.line1),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .background(Gomob.colors.bg0)
                .padding(horizontal = Gomob.spacing.s16, vertical = Gomob.spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // 左:下载 PDF
            Box(
                Modifier
                    .clip(Gomob.shapes.r2)
                    .background(Gomob.colors.bg2)
                    .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r2)
                    .clickable {}
                    .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
                ) {
                    Icon(
                        Icons.Filled.PictureAsPdf,
                        contentDescription = null,
                        tint = Gomob.colors.fg2,
                        modifier = Modifier.size(16.dp),
                    )
                    Text("查验单 PDF", style = Gomob.type.caption, color = Gomob.colors.fg1)
                }
            }

            // 中:大圆拍摄按钮
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Gomob.colors.accentSoft)
                    .border(Gomob.spacing.hairline * 2, Gomob.colors.accentLine, CircleShape)
                    .clickable {},
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Gomob.colors.accent),
                )
            }

            // 右:切主/深度预览
            Box(
                Modifier
                    .clip(Gomob.shapes.r2)
                    .background(Gomob.colors.bg2)
                    .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r2)
                    .clickable {}
                    .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
                ) {
                    Icon(
                        Icons.Filled.SwapHoriz,
                        contentDescription = null,
                        tint = Gomob.colors.fg2,
                        modifier = Modifier.size(16.dp),
                    )
                    Text("RGB / 深度", style = Gomob.type.caption, color = Gomob.colors.fg1)
                }
            }
        }
    }
}
