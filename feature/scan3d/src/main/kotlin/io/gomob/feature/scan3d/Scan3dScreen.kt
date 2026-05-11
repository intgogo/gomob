package io.gomob.feature.scan3d

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.ScreenHeader
import io.gomob.designsystem.decoration.ticks
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob
import io.gomob.nativebridge.berxel.BerxelDeviceState
import io.gomob.nativebridge.berxel.BerxelFrameStat

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
    onOpenContourScan: () -> Unit = {},
    onOpenDepthCamera: () -> Unit = {},
    onOpenVinRectify: () -> Unit = {},
    vm: Scan3dViewModel = hiltViewModel(),
) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        ScreenHeader(
            title = "三维扫描",
            eyebrow = "Berxel iHawk · 主从合一采集",
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            // 设备卡 → 深度相机详情页（看预览 + 调控制 + 标定）
            item { DeviceCard(state = ui, onClick = onOpenDepthCamera) }
            item { Spacer(Modifier.height(Gomob.spacing.s12)) }
            // ActionTile 01 三维外廓扫描 → 直接进 RecordingScreen（自包含预览 + 开始/停止）
            // ActionTile 02 VIN 数码拓印 → ScanCaptureRoute (M4 实施前的 VIN 风格 stub)
            item { ActionTilePair(onOpenContourScan = onOpenContourScan, onOpenVinRectify = onOpenVinRectify) }
            item { Spacer(Modifier.height(Gomob.spacing.s20)) }
            item { AssetSectionHeader() }
            item { AssetGrid() }
        }
    }
}


// ─── 设备卡（点击进详情页才会启动 SDK；本卡不显示实时 fps，只显示已知信息） ─────
@Composable
private fun DeviceCard(state: Scan3dDeviceUiState, onClick: () -> Unit) {
    val view = state.toView()
    Box(Modifier.padding(start = Gomob.spacing.s20, end = Gomob.spacing.s20, bottom = Gomob.spacing.s12)) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(Gomob.shapes.r3)
                .background(Gomob.colors.bg1)
                .ticks()
                .clickable(onClick = onClick)
                .padding(Gomob.spacing.s14),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12)) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(Gomob.shapes.r2)
                        .background(Gomob.colors.bg3),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        GomobIcons.USB,
                        contentDescription = null,
                        tint = view.iconTint,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
                    ) {
                        Text(
                            view.title,
                            style = Gomob.type.numInline.copy(fontSize = 13.sp, letterSpacing = 0.04.em),
                            color = Gomob.colors.fg0,
                        )
                        StatusTagPill(view.statusText, view.statusTone)
                    }
                    Spacer(Modifier.height(Gomob.spacing.s6))
                    Text(
                        view.line1,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.06.em,
                        lineHeight = 16.sp,
                        color = Gomob.colors.fg2,
                    )
                    Text(
                        view.line2,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.06.em,
                        lineHeight = 16.sp,
                        color = Gomob.colors.fg2,
                    )
                }
                Icon(
                    GomobIcons.ArrowRight,
                    contentDescription = null,
                    tint = Gomob.colors.fg3,
                    modifier = Modifier.size(16.dp).align(Alignment.CenterVertically),
                )
            }
        }
    }
}

private data class DeviceCardView(
    val title: String,
    val iconTint: Color,
    val statusText: String,
    val statusTone: StatusTone,
    val line1: String,
    val line2: String,
)

private enum class StatusTone { Ok, Warn, Bad, Neutral }

@Composable
private fun Scan3dDeviceUiState.toView(): DeviceCardView {
    val info = lastKnownInfo
    val title = info?.serialNumber?.ifBlank { null } ?: "iHawk"
    val streamLine = info?.let { i ->
        val color = i.colorMode
        val depth = i.depthMode
        when {
            color != null && depth != null ->
                "Color ${color.width}×${color.height}@${color.fps} · Depth ${depth.width}×${depth.height}@${depth.fps}"
            color != null -> "Color ${color.width}×${color.height}@${color.fps}"
            depth != null -> "Depth ${depth.width}×${depth.height}@${depth.fps}"
            else -> null
        }
    }
    val versionLine = info?.let { i ->
        val sdk = i.sdkVersion.ifBlank { "" }.let { if (it.isNotEmpty()) "SDK $it" else "" }
        val fw = i.firmwareVersion.ifBlank { "" }.let { if (it.isNotEmpty()) "FW $it" else "" }
        listOf(sdk, fw).filter { it.isNotEmpty() }.joinToString(" · ").ifBlank { null }
    }

    return when (val s = state) {
        is BerxelDeviceState.Idle -> DeviceCardView(
            title = title,
            iconTint = if (info != null) Gomob.colors.fg2 else Gomob.colors.fg3,
            statusText = if (info != null) "已停止" else "未连接",
            statusTone = StatusTone.Neutral,
            line1 = streamLine ?: "USB-C OTG 接 iHawk · 点击查看详情",
            line2 = versionLine ?: "进入详情页才会启动 SDK · 节能",
        )
        is BerxelDeviceState.Initializing -> DeviceCardView(
            title = title,
            iconTint = Gomob.colors.accent,
            statusText = "加载 SDK…",
            statusTone = StatusTone.Warn,
            line1 = streamLine ?: "BerxelSDK Context 初始化中",
            line2 = versionLine ?: "约 1-2s",
        )
        is BerxelDeviceState.NoDevice -> DeviceCardView(
            title = title,
            iconTint = Gomob.colors.fg3,
            statusText = "未插入",
            statusTone = StatusTone.Warn,
            line1 = "请用 USB-C OTG 接 iHawk",
            line2 = versionLine ?: "插入后系统弹权限框，允许即可",
        )
        is BerxelDeviceState.WaitingPermission -> DeviceCardView(
            title = title,
            iconTint = Gomob.colors.accent,
            statusText = "等待 USB 授权",
            statusTone = StatusTone.Warn,
            line1 = "请在系统弹窗点 \"始终允许\"",
            line2 = versionLine ?: "授权一次后下次插上自动开",
        )
        is BerxelDeviceState.Opening -> DeviceCardView(
            title = title,
            iconTint = Gomob.colors.accent,
            statusText = "开流中",
            statusTone = StatusTone.Warn,
            line1 = streamLine ?: "Color + Depth MIX 模式",
            line2 = versionLine ?: "首帧到达后切已连接",
        )
        is BerxelDeviceState.Streaming -> DeviceCardView(
            title = s.info.serialNumber.ifBlank { title },
            iconTint = Gomob.colors.accent,
            statusText = "已连接",
            statusTone = StatusTone.Ok,
            line1 = streamLine ?: "Color + Depth MIX",
            line2 = versionLine ?: "—",
        )
        is BerxelDeviceState.Error -> DeviceCardView(
            title = title,
            iconTint = Gomob.colors.danger,
            statusText = "错误",
            statusTone = StatusTone.Bad,
            line1 = s.reason,
            line2 = versionLine ?: "点击进详情页重试",
        )
    }
}

@Composable
private fun StatusTagPill(text: String, tone: StatusTone) {
    val (bg, fg, dot) = when (tone) {
        StatusTone.Ok -> Triple(Gomob.colors.okSoft, Gomob.colors.ok, Gomob.colors.ok)
        StatusTone.Warn -> Triple(Gomob.colors.accentSoft, Gomob.colors.accent, Gomob.colors.accent)
        StatusTone.Bad -> Triple(Gomob.colors.dangerSoft, Gomob.colors.danger, Gomob.colors.danger)
        StatusTone.Neutral -> Triple(Gomob.colors.bg2, Gomob.colors.fg2, Gomob.colors.fg3)
    }
    Row(
        Modifier
            .height(Gomob.spacing.chipHeight)
            .clip(Gomob.shapes.r1)
            .background(bg)
            .padding(horizontal = Gomob.spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
    ) {
        Box(
            Modifier
                .size(Gomob.spacing.dot6)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(dot),
        )
        Text(
            text,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.04.em,
            color = fg,
        )
    }
}

// ─── 双 ActionTile ──────────────────────────────────────────────────────────
@Composable
private fun ActionTilePair(onOpenContourScan: () -> Unit, onOpenVinRectify: () -> Unit) {
    Row(
        Modifier.padding(horizontal = Gomob.spacing.s20),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ActionTile(
            modifier = Modifier.weight(1f),
            id = "01",
            icon = GomobIcons.Cube,
            title = "三维外廓扫描",
            desc = "ICP + TSDF + Mesh",
            detail = "深度相机 → 转一圈 → mesh.obj",
            primary = true,
            onClick = onOpenContourScan,    // 深度相机详情页（含 emphasis "开始扫描" 进 Recording）
        )
        ActionTile(
            modifier = Modifier.weight(1f),
            id = "02",
            icon = GomobIcons.Stamp,
            title = "VIN 数码拓印",
            desc = "OCR + 拓印图层",
            detail = "自动识别 17 位 · 入档归档",
            primary = false,
            onClick = onOpenVinRectify,     // M4 实施前指向 VIN 风格 stub
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
    val bgColor = if (primary) Gomob.colors.accentSoft else Gomob.colors.bg1
    val titleColor = if (primary) Gomob.colors.accentStrong else Gomob.colors.fg0
    val descColor = if (primary) Gomob.colors.accent else Gomob.colors.fg2
    val iconBoxBg = if (primary) Color.Black.copy(alpha = 0.2f) else Gomob.colors.bg3
    val iconColor = if (primary) Gomob.colors.accentStrong else Gomob.colors.fg1
    Column(
        modifier
            .clip(Gomob.shapes.r3)
            .background(bgColor)
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
                    .background(iconBoxBg),
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
        Spacer(Modifier.height(Gomob.spacing.s8))
        Text(
            detail,
            fontSize = 10.sp,
            lineHeight = 15.sp,
            color = Gomob.colors.fg3,
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
