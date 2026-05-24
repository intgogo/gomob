package io.gomob.feature.scan3d

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.component.HairlineCard
import io.gomob.designsystem.component.SegmentedTabItem
import io.gomob.designsystem.component.SegmentedTabs
import io.gomob.designsystem.component.SettingRow
import io.gomob.designsystem.component.SettingRowDivider
import io.gomob.designsystem.theme.Gomob
import io.gomob.nativebridge.berxel.BerxelDeviceState
import io.gomob.nativebridge.berxel.BerxelFrameStat
import io.gomob.nativebridge.berxel.BerxelStreamProfile
import io.gomob.nativebridge.berxel.BerxelStreamProfiles
import io.gomob.nativebridge.berxel.BerxelStreamTarget

const val DEPTH_CAMERA_ROUTE = "scan3d/depth-camera"

/**
 * 深度相机详情子页 v2 — 大画面竖排预览 + 导航列表。
 *
 * 设计:
 *  - COLOR / DEPTH 各占满宽度独立成行（横排会让画面太小）
 *  - 详细信息 / 控制 / 标定 都收成 SettingRow → 三级页，避免主页过载
 */
@Composable
fun DepthCameraRoute(
    onBack: () -> Unit,
    onOpenInfo: () -> Unit = {},
    onOpenControls: () -> Unit = {},
    onOpenCalibration: () -> Unit = {},
    vm: DepthCameraViewModel = hiltViewModel(),
) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val colorBmp by vm.colorPreview.collectAsStateWithLifecycle()
    val depthBmp by vm.depthPreview.collectAsStateWithLifecycle()

    // Camera 运行时权限 —— HyperOS / 部分 OEM 把 USB UVC 设备访问跟 CAMERA 权限挂钩，
    // 没拿到 CAMERA 时静默 deny USB_PERMISSION 广播。这里在进入页面时主动申请。
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
    }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(title = "深度相机", eyebrow = "Berxel iHawk · 详情与控制", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = Gomob.spacing.s12,
                bottom = Gomob.spacing.s28,
            ),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        ) {
            item {
                StreamProfileSelector(
                    current = ui.streamProfile,
                    onChange = { vm.setStreamProfile(it) },
                )
            }
            item { LargePreview(label = "COLOR", bitmap = colorBmp) }
            item { LargePreview(label = "DEPTH", bitmap = depthBmp) }
            item { LiveStatusStrip(ui = ui) }
            item { Spacer(Modifier.height(Gomob.spacing.s4)) }
            item {
                SectionList {
                    // 「开始三维外廓扫描」入口已上移到 3D 主页 ActionTile 01；本页只关心设备本身
                    NavRow(title = "设备详情", subtitle = "序列号 / 流模式 / 内参 / 帧统计", onClick = onOpenInfo)
                    SettingRowDivider()
                    NavRow(title = "成像控制", subtitle = "Color / Depth 曝光 · 去噪 · 配准", onClick = onOpenControls)
                    SettingRowDivider()
                    NavRow(title = "Color ↔ Depth 标定", subtitle = "外参微调 / Charuco 标定向导", onClick = onOpenCalibration)
                }
            }
        }
    }
}

// ─── Berxel flag 模式 + 帧规格切换器 ─────────────────────────────────────────
@Composable
private fun StreamProfileSelector(
    current: BerxelStreamProfile,
    onChange: (BerxelStreamProfile) -> Unit,
) {
    val families = listOf(
        StreamProfileFamily("QVGA", BerxelStreamProfiles.QVGA),
        StreamProfileFamily("MIX", BerxelStreamProfiles.STANDARD),
        StreamProfileFamily("HD", BerxelStreamProfiles.HD),
    )
    val currentFamily = families.firstOrNull { family ->
        family.profiles.any { it.id == current.id }
    } ?: families[1]
    val currentFamilyIndex = families.indexOf(currentFamily).coerceAtLeast(0)
    val currentFps = current.primaryFps() ?: currentFamily.profiles.first().primaryFps() ?: 15
    var fpsMenuOpen by remember { mutableStateOf(false) }

    Box(Modifier.padding(horizontal = Gomob.spacing.s16)) {
        HairlineCard(padding = 0.dp) {
            Column(
                Modifier.fillMaxWidth().padding(Gomob.spacing.s8),
                verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SegmentedTabs(
                        items = families.map { SegmentedTabItem(it.title) },
                        selectedIndex = currentFamilyIndex,
                        onSelect = { idx ->
                            val nextFamily = families[idx]
                            onChange(nextFamily.profileForFps(currentFps))
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        Box(
                            modifier = Modifier
                                .width(76.dp)
                                .height(36.dp)
                                .clip(Gomob.shapes.r2)
                                .background(if (fpsMenuOpen) Gomob.colors.accentSoft else Gomob.colors.bg2)
                                .clickable { fpsMenuOpen = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${currentFps}fps",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (fpsMenuOpen) Gomob.colors.accent else Gomob.colors.fg1,
                            )
                        }
                        DropdownMenu(
                            expanded = fpsMenuOpen,
                            onDismissRequest = { fpsMenuOpen = false },
                            modifier = Modifier
                                .width(112.dp)
                                .clip(Gomob.shapes.r2)
                                .background(Gomob.colors.bg1),
                        ) {
                            currentFamily.profiles.forEach { profile ->
                                val selected = profile.primaryFps() == currentFps
                                DropdownMenuItem(
                                    modifier = Modifier.background(
                                        if (selected) Gomob.colors.accentSoft else Color.Transparent,
                                    ),
                                    text = {
                                        Text(
                                            "${profile.primaryFps()} fps",
                                            fontSize = 13.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (selected) Gomob.colors.accent else Gomob.colors.fg1,
                                        )
                                    },
                                    trailingIcon = {
                                        if (selected) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = Gomob.colors.accent,
                                            )
                                        }
                                    },
                                    colors = MenuDefaults.itemColors(
                                        textColor = Gomob.colors.fg1,
                                        trailingIconColor = Gomob.colors.accent,
                                    ),
                                    onClick = {
                                        fpsMenuOpen = false
                                        onChange(profile)
                                    },
                                )
                            }
                        }
                    }
                }
                Text(current.summaryLine(), fontSize = 10.sp, color = Gomob.colors.fg3)
            }
        }
    }
}

private data class StreamProfileFamily(
    val title: String,
    val profiles: List<BerxelStreamProfile>,
) {
    fun profileForFps(fps: Int): BerxelStreamProfile {
        return profiles.firstOrNull { it.primaryFps() == fps }
            ?: profiles
                .filter { (it.primaryFps() ?: 0) <= fps }
                .maxByOrNull { it.primaryFps() ?: 0 }
            ?: profiles.first()
    }
}

private fun BerxelStreamProfile.primaryFps(): Int? = depth?.fps ?: color?.fps

private fun BerxelStreamProfile.summaryLine(): String {
    return "color ${color?.formatTarget() ?: "off"} + depth ${depth?.formatTarget() ?: "off"}"
}

private fun BerxelStreamTarget.formatTarget(): String = "${width}×${height}@${fps}"

// ─── 大画面单流预览（占满宽度） ──────────────────────────────────────────────
@Composable
private fun LargePreview(label: String, bitmap: Bitmap?) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Gomob.spacing.s16)
            .aspectRatio(16f / 10f)  // 640×400 = 1.6 ≈ 16:10
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg2),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "$label preview",
                contentScale = ContentScale.Fit,  // 不裁切，保留全画面
                filterQuality = FilterQuality.Low,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .padding(Gomob.spacing.s8)
                .clip(Gomob.shapes.r1)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = Gomob.spacing.s8, vertical = 2.dp),
        ) {
            Text(
                label,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
            )
        }
    }
}

// ─── 实时状态条（fps + frame 序号 + 状态点） ─────────────────────────────────
@Composable
private fun LiveStatusStrip(ui: DepthCameraUiState) {
    val streaming = ui.device is BerxelDeviceState.Streaming
    val errorReason = (ui.device as? BerxelDeviceState.Error)?.reason
    val color = ui.color
    val depth = ui.depth
    val fpsText = (depth?.measuredFps ?: color?.measuredFps)?.let { "$it fps" } ?: "—"
    val frameText = (depth?.frameIndex ?: color?.frameIndex)?.let { "frame#$it" } ?: "等待首帧"
    val syncText = if (color != null && depth != null && color.timestampUs == depth.timestampUs) {
        "RGBD 同步 ✓"
    } else {
        "未配对"
    }
    val tone = if (streaming) Gomob.colors.accent else Gomob.colors.fg3
    Box(Modifier.padding(horizontal = Gomob.spacing.s16)) {
        HairlineCard(padding = 0.dp) {
            Box(Modifier.fillMaxWidth().padding(Gomob.spacing.s12)) {
                Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                    androidx.compose.foundation.layout.Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(fpsText, style = Gomob.type.numInline.copy(fontSize = 16.sp), color = tone)
                        Text(frameText, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Gomob.colors.fg2)
                    }
                    Text(syncText, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Gomob.colors.fg3)
                    if (errorReason != null) {
                        Text(
                            errorReason,
                            fontSize = 11.sp,
                            color = Gomob.colors.danger,
                        )
                    }
                }
            }
        }
    }
}

// ─── 导航列表组件（导出给三级页复用） ─────────────────────────────────────────
@Composable
internal fun SectionList(content: @Composable () -> Unit) {
    Box(Modifier.padding(horizontal = Gomob.spacing.s16)) {
        HairlineCard(padding = 0.dp) {
            Column { content() }
        }
    }
}

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        trailing = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Gomob.colors.fg3,
            )
        },
    )
}

// ─── 工具：BerxelFrameStat 短文本（三级页复用） ──────────────────────────────
internal fun BerxelFrameStat?.shortLine(): String =
    if (this == null) "等待首帧"
    else "frame#$frameIndex · $measuredFps fps · t=${timestampUs}μs · ${width}×${height}"
