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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import io.gomob.designsystem.component.HairlineCard
import io.gomob.designsystem.component.ScreenHeader
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.theme.Gomob

const val SCAN3D_ROUTE = "scan3d"

/**
 * 3D Tab — 包含「标定」和「扫描」两个入口（按用户产品规格）。
 *
 * 视觉骨架：
 *   1. 顶部 ScreenHeader（设备状态 trailing）
 *   2. 相机/预览主区（预留 cameraSlot）
 *   3. 仪表行：帧率 / 重投影误差
 *   4. 双入口卡：标定 / 扫描
 */
@Composable
fun Scan3dRoute(
    cameraSlot: @Composable () -> Unit = {},
    onOpenCalibration: () -> Unit = {},
    onOpenScan: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().background(Gomob.colors.bg0),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
    ) {
        ScreenHeader(
            title = "3D 扫描",
            eyebrow = "采集 · Berxel iHawk-072",
            trailing = { StatusTag(text = "已连接", tone = StatusTone.Ok, showDot = true) },
        )

        // 预览主区
        Box(
            Modifier
                .padding(horizontal = Gomob.spacing.s16)
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(Gomob.shapes.r3)
                .background(Gomob.colors.bg2)
                .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r3),
        ) {
            cameraSlot()
            // 角落 ID 标签
            Box(Modifier.align(Alignment.TopStart).padding(Gomob.spacing.s8)) {
                StatusTag(text = "ZAA0120230001", tone = StatusTone.Accent)
            }
            // 中心十字提示（待真实预览覆盖）
            if (true) {
                Box(
                    Modifier.align(Alignment.Center),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .padding(Gomob.spacing.s8)
                            .clip(CircleShape)
                            .background(Gomob.colors.bg3)
                            .padding(Gomob.spacing.s12),
                    ) {
                        Text(
                            "USB-C 接 Berxel 深度相机",
                            style = Gomob.type.bodySm,
                            color = Gomob.colors.fg2,
                        )
                    }
                }
            }
        }

        // 仪表行
        Row(
            Modifier.padding(horizontal = Gomob.spacing.s16),
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        ) {
            HairlineCard(modifier = Modifier.weight(1f)) {
                Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                    Text("帧率", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
                    Text("28", style = Gomob.type.metricLg, color = Gomob.colors.accentStrong)
                    Text("Mix HD 1280×800 · 8 fps", style = Gomob.type.caption, color = Gomob.colors.fg3)
                }
            }
            HairlineCard(modifier = Modifier.weight(1f)) {
                Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                    Text("重投影误差", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
                    Text("0.42 px", style = Gomob.type.metricMd, color = Gomob.colors.fg0)
                    Text("上次标定 2024/05/09 18:32", style = Gomob.type.caption, color = Gomob.colors.fg3)
                }
            }
        }

        // 入口双卡 — 用户产品规格：3D = 标定 + 扫描
        Row(
            Modifier.padding(horizontal = Gomob.spacing.s16),
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        ) {
            EntryCard(
                modifier = Modifier.weight(1f),
                title = "标定",
                subtitle = "双摄外参 / 内参",
                description = "Charuco 标定向导 · 3 分钟",
                icon = Icons.Filled.Tune,
                onClick = onOpenCalibration,
            )
            EntryCard(
                modifier = Modifier.weight(1f),
                title = "扫描",
                subtitle = "RGBD 同步采集",
                description = "实时点云 · 智能预审",
                icon = Icons.Filled.ViewInAr,
                onClick = onOpenScan,
            )
        }
    }
}

@Composable
private fun EntryCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    HairlineCard(modifier = modifier, onClick = onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s6)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = Gomob.colors.accent)
                Box(Modifier.padding(start = Gomob.spacing.s8)) {
                    Text(text = title, style = Gomob.type.title, color = Gomob.colors.fg0)
                }
            }
            Text(text = subtitle, style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
            Text(text = description, style = Gomob.type.caption, color = Gomob.colors.fg3)
        }
    }
}
