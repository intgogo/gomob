package io.gomob.feature.scan3d

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.gomob.designsystem.component.ScreenHeader
import io.gomob.designsystem.decoration.ticks
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob

const val SCAN3D_ROUTE = "scan3d"

/**
 * 04 三维扫描 — jsx scan3d.jsx。
 *
 * 视觉骨架:
 *   1. ScreenHeader "三维扫描 / Berxel iHawk · 主从合一采集" + Refresh 按钮
 *   2. 设备卡 (USB 图标 + iHawk-072 + 已连接 + FW/SDK 副 + 帧率 8.0 大字)
 *   3. 双 ActionTile (01 三维外廓扫描 primary acc / 02 VIN 数码拓印 normal)
 *   4. 标定状态卡 (Calibrate 圆 + 已校准 + 重投影 0.42 px + 重新标定按钮 + 上次/下次)
 *   5. SectionTitle "最近 3D 资产 / 查看全部 →"
 *   6. 3 列 Asset 网格 (点云 30 圆点 SVG + 点数 tag + VIN 名)
 */
@Composable
fun Scan3dRoute(
    cameraSlot: @Composable () -> Unit = {},
    onOpenCalibration: () -> Unit = {},
    onOpenScan: () -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Gomob.colors.bg0),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            ScreenHeader(
                title = "三维扫描",
                eyebrow = "Berxel iHawk · 主从合一采集",
                trailing = { RefreshIconButton() },
            )
        }
        item { DeviceCard() }
        item { Spacer(Modifier.height(Gomob.spacing.s12)) }
        item { ActionTilePair(onOpenScan, onOpenCalibration) }
        item { Spacer(Modifier.height(Gomob.spacing.s16)) }
        item { CalibrationStatusCard(onOpenCalibration = onOpenCalibration) }
        item { Spacer(Modifier.height(Gomob.spacing.s20)) }
        item { AssetSectionHeader() }
        item { AssetGrid() }
    }
}

@Composable
private fun RefreshIconButton() {
    Box(
        Modifier.size(Gomob.spacing.touchMin).clickable {},
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            GomobIcons.Refresh,
            contentDescription = "刷新设备",
            tint = Gomob.colors.fg2,
            modifier = Modifier.size(Gomob.spacing.icon20),
        )
    }
}

// ─── 设备卡 ──────────────────────────────────────────────────────────────────
@Composable
private fun DeviceCard() {
    Box(Modifier.padding(start = Gomob.spacing.s20, end = Gomob.spacing.s20, bottom = Gomob.spacing.s12)) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(Gomob.shapes.r3)
                .background(Gomob.colors.bg1)
                .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r3)
                .ticks()
                .padding(Gomob.spacing.s14),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12)) {
                // 左：USB 图标 44dp
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(Gomob.shapes.r2)
                        .background(Gomob.colors.bg3)
                        .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r2),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        GomobIcons.USB,
                        contentDescription = null,
                        tint = Gomob.colors.accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
                // 中：型号 + 状态 + 副文
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
                    ) {
                        Text(
                            "iHawk-072",
                            style = Gomob.type.numInline.copy(fontSize = 13.sp, letterSpacing = 0.04.em),
                            color = Gomob.colors.fg0,
                        )
                        OkConnectedTag()
                    }
                    Spacer(Modifier.height(Gomob.spacing.s6))
                    Text(
                        "USB-C OTG · MixHD 1280×800 · 8 fps",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.06.em,
                        lineHeight = 16.sp,
                        color = Gomob.colors.fg2,
                    )
                    Text(
                        "FW 1.2.3 · SDK v2.0.190",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.06.em,
                        lineHeight = 16.sp,
                        color = Gomob.colors.fg2,
                    )
                }
                // 右：帧率
                Column(horizontalAlignment = Alignment.End) {
                    Text("帧率", fontSize = 10.sp, color = Gomob.colors.fg3)
                    Text(
                        "8.0",
                        style = Gomob.type.numInline.copy(
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Normal,
                            letterSpacing = (-0.01).em,
                            lineHeight = 22.sp,
                        ),
                        color = Gomob.colors.accent,
                    )
                }
            }
        }
    }
}

@Composable
private fun OkConnectedTag() {
    Row(
        Modifier
            .height(Gomob.spacing.chipHeight)
            .clip(Gomob.shapes.r1)
            .background(Gomob.colors.okSoft)
            .border(Gomob.spacing.hairline, Gomob.colors.okLine, Gomob.shapes.r1)
            .padding(horizontal = Gomob.spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
    ) {
        Box(
            Modifier
                .size(Gomob.spacing.dot6)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Gomob.colors.ok),
        )
        Text(
            "已连接",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.04.em,
            color = Gomob.colors.ok,
        )
    }
}

// ─── 双 ActionTile ──────────────────────────────────────────────────────────
@Composable
private fun ActionTilePair(onScan: () -> Unit, onCalibration: () -> Unit) {
    Row(
        Modifier.padding(horizontal = Gomob.spacing.s20),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ActionTile(
            modifier = Modifier.weight(1f),
            id = "01",
            icon = GomobIcons.Cube,
            title = "三维外廓扫描",
            desc = "RGBD 同步采集",
            detail = "主从合一 · 实时点云预览",
            primary = true,
            onClick = onScan,
        )
        ActionTile(
            modifier = Modifier.weight(1f),
            id = "02",
            icon = GomobIcons.Stamp,
            title = "VIN 数码拓印",
            desc = "OCR + 拓印图层",
            detail = "自动识别 17 位 · 入档归档",
            primary = false,
            onClick = onCalibration,    // VIN 拓印暂复用 onCalibration 回调（后续接真路由）
        )
    }
}

@Composable
private fun ActionTile(
    modifier: Modifier = Modifier,
    id: String,
    icon: ImageVector,
    title: String,
    desc: String,
    detail: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (primary) Gomob.colors.accentLine else Gomob.colors.line1
    val bgColor = if (primary) Gomob.colors.accentSoft else Gomob.colors.bg1
    val titleColor = if (primary) Gomob.colors.accentStrong else Gomob.colors.fg0
    val descColor = if (primary) Gomob.colors.accent else Gomob.colors.fg2
    val iconBoxBorder = if (primary) Gomob.colors.accentLine else Gomob.colors.line2
    val iconBoxBg = if (primary) Color.Black.copy(alpha = 0.2f) else Gomob.colors.bg3
    val iconColor = if (primary) Gomob.colors.accentStrong else Gomob.colors.fg1
    Column(
        modifier
            .clip(Gomob.shapes.r3)
            .background(bgColor)
            .border(Gomob.spacing.hairline, borderColor, Gomob.shapes.r3)
            .clickable(onClick = onClick)
            .padding(Gomob.spacing.s14),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(Gomob.shapes.r1)
                    .background(iconBoxBg)
                    .border(Gomob.spacing.hairline, iconBoxBorder, Gomob.shapes.r1),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(Gomob.spacing.icon16),
                )
            }
            Text(
                id,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Gomob.colors.fg3,
            )
        }
        Spacer(Modifier.height(22.dp))
        Text(
            title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = titleColor,
        )
        Spacer(Modifier.height(Gomob.spacing.s4))
        Text(desc, fontSize = 11.sp, color = descColor)
        Spacer(Modifier.height(Gomob.spacing.s12))
        Box(
            Modifier
                .fillMaxWidth()
                .height(Gomob.spacing.hairline)
                .background(Gomob.colors.line1),
        )
        Spacer(Modifier.height(Gomob.spacing.s8))
        Text(
            detail,
            fontSize = 10.sp,
            lineHeight = 15.sp,
            color = Gomob.colors.fg3,
        )
    }
}

// ─── 标定状态卡 ─────────────────────────────────────────────────────────────
@Composable
private fun CalibrationStatusCard(onOpenCalibration: () -> Unit) {
    Box(Modifier.padding(horizontal = Gomob.spacing.s20)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(Gomob.shapes.r3)
                .background(Gomob.colors.bg1)
                .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r3)
                .ticks()
                .padding(Gomob.spacing.s14),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(Gomob.shapes.r1)
                        .background(Gomob.colors.okSoft)
                        .border(Gomob.spacing.hairline, Gomob.colors.okLine, Gomob.shapes.r1),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        GomobIcons.Calibrate,
                        contentDescription = null,
                        tint = Gomob.colors.ok,
                        modifier = Modifier.size(Gomob.spacing.icon16),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
                    ) {
                        Text("标定状态", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Gomob.colors.fg0)
                        Row(
                            Modifier
                                .height(Gomob.spacing.chipHeight)
                                .clip(Gomob.shapes.r1)
                                .background(Gomob.colors.okSoft)
                                .border(Gomob.spacing.hairline, Gomob.colors.okLine, Gomob.shapes.r1)
                                .padding(horizontal = Gomob.spacing.s8),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
                        ) {
                            Box(
                                Modifier
                                    .size(Gomob.spacing.dot6)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(Gomob.colors.ok),
                            )
                            Text(
                                "已校准",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 0.04.em,
                                color = Gomob.colors.ok,
                            )
                        }
                    }
                    Spacer(Modifier.height(Gomob.spacing.s6))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "双摄外参 · 内参 · 重投影 ",
                            fontSize = 10.sp,
                            lineHeight = 15.sp,
                            color = Gomob.colors.fg3,
                        )
                        Text(
                            "0.42 px",
                            style = Gomob.type.numInline.copy(fontSize = 10.sp),
                            color = Gomob.colors.fg2,
                        )
                    }
                }
                // 重新标定按钮
                Row(
                    Modifier
                        .height(28.dp)
                        .clip(Gomob.shapes.r1)
                        .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r1)
                        .clickable(onClick = onOpenCalibration)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
                ) {
                    Text("重新标定", fontSize = 11.sp, color = Gomob.colors.fg1)
                    Text(
                        "›",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Gomob.colors.accent,
                    )
                }
            }
            Spacer(Modifier.height(Gomob.spacing.s12))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(Gomob.spacing.hairline)
                    .background(Gomob.colors.line1),
            )
            Spacer(Modifier.height(10.dp))
            // footer 行：上次 / 下次建议
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                FooterText(label = "上次 ", value = "2024/05/09 18:32")
                FooterText(label = "下次建议 ", value = "≤ 7 天")
            }
        }
    }
}

@Composable
private fun FooterText(label: String, value: String) {
    Row {
        Text(
            label,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.04.em,
            color = Gomob.colors.fg3,
        )
        Text(
            value,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.04.em,
            color = Gomob.colors.fg2,
        )
    }
}

// ─── 资产网格 ──────────────────────────────────────────────────────────────
@Composable
private fun AssetSectionHeader() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Gomob.spacing.s20)
            .padding(bottom = Gomob.spacing.s12),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "最近 3D 资产",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Gomob.colors.fg0,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
            modifier = Modifier.clickable {},
        ) {
            Text("查看全部", fontSize = 11.sp, color = Gomob.colors.accent)
            Icon(
                GomobIcons.ArrowRight,
                contentDescription = null,
                tint = Gomob.colors.accent,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}

@Composable
private fun AssetGrid() {
    Row(
        Modifier.padding(horizontal = Gomob.spacing.s20),
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        AssetCell("VIN-1", "0.7M", Modifier.weight(1f))
        AssetCell("VIN-2", "1.2M", Modifier.weight(1f))
        AssetCell("VIN-3", "0.9M", Modifier.weight(1f))
    }
}

@Composable
private fun AssetCell(name: String, pts: String, modifier: Modifier = Modifier) {
    val accentDot = Gomob.colors.accent.copy(alpha = 0.45f)
    Box(
        modifier
            .aspectRatio(1f)
            .clip(Gomob.shapes.r1)
            .background(Gomob.colors.bg1)
            .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r1)
            .clickable {}
            .padding(10.dp),
    ) {
        // 30 个点云圆点（jsx 用 SVG circle）— Compose Canvas 自绘
        androidx.compose.foundation.Canvas(
            modifier = Modifier.fillMaxSize().padding(top = 18.dp, bottom = 26.dp),
        ) {
            for (i in 0 until 30) {
                val xRel = (i * 37) % 100 / 100f
                val yRel = (i * 53) % 100 / 100f
                val r = ((i % 3) * 0.4f + 0.6f) * 1.2f
                drawCircle(
                    color = accentDot,
                    radius = r,
                    center = Offset(xRel * size.width, yRel * size.height),
                )
            }
        }
        // 点数 tag
        Row(
            Modifier
                .align(Alignment.TopStart)
                .height(Gomob.spacing.chipHeight)
                .clip(Gomob.shapes.r1)
                .background(Gomob.colors.accentSoft)
                .border(Gomob.spacing.hairline, Gomob.colors.accentLine, Gomob.shapes.r1)
                .padding(horizontal = Gomob.spacing.s6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$pts 点",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Gomob.colors.accent,
            )
        }
        // VIN 名
        Text(
            name,
            style = Gomob.type.numInline.copy(fontSize = 13.sp, letterSpacing = 0.04.em),
            color = Gomob.colors.fg0,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

