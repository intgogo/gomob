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
import io.gomob.nativebridge.berxel.BerxelStackBackend
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
    onOpenSonixDebug: () -> Unit = {},
    vm: DepthCameraViewModel = hiltViewModel(),
) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val colorBmp by vm.colorPreview.collectAsStateWithLifecycle()
    val depthBmp by vm.depthPreview.collectAsStateWithLifecycle()
    val strictFrameSize by vm.strictFrameSize.collectAsStateWithLifecycle()
    val irRenderMode by vm.irRenderMode.collectAsStateWithLifecycle()

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
                BackendSwitcher(
                    current = ui.backend,
                    onChange = { vm.setBackendMode(it) },
                )
            }
            item {
                StreamProfileSelector(
                    current = ui.streamProfile,
                    backend = ui.backend,
                    onChange = { vm.setStreamProfile(it) },
                )
            }
            item {
                LargePreview(
                    label = "COLOR${ui.color.fpsSuffix()}",
                    bitmap = colorBmp,
                    placeholder = "等待彩色帧",
                )
            }
            item {
                LargePreview(
                    label = buildString {
                        append(if (irRenderMode) "DEPTH · IR-GREY" else "DEPTH · TURBO")
                        append(ui.depth.fpsSuffix())
                        append(" · ")
                        append(if (strictFrameSize) "STRICT 401" else "RAW")
                    },
                    bitmap = depthBmp,
                    placeholder = when (ui.device) {
                        BerxelDeviceState.NoDevice -> "未检测到相机"
                        BerxelDeviceState.Initializing -> "正在枚举 USB"
                        BerxelDeviceState.Opening -> "正在打开深度流"
                        BerxelDeviceState.WaitingPermission -> "等待 USB 权限"
                        is BerxelDeviceState.Error -> "深度流异常"
                        else -> "等待深度帧"
                    },
                    actionLabel = if (strictFrameSize) "切 RAW" else "切 STRICT",
                    onAction = { vm.toggleStrictFrameSize() },
                    action2Label = if (irRenderMode) "切 TURBO" else "切 IR",
                    onAction2 = { vm.toggleIrRenderMode() },
                    action3Label = "DUMP",
                    onAction3 = { vm.triggerFrameDump() },
                )
            }
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
                    SettingRowDivider()
                    NavRow(
                        title = "Sonix ASIC 调试",
                        subtitle = "M1.6.5/6 · 直发 XU vendor cmd 读寄存器",
                        onClick = onOpenSonixDebug,
                    )
                }
            }
        }
    }
}

// ─── Native / 厂商 SDK 后端切换 ─────────────────────────────────────────────
@Composable
private fun BackendSwitcher(
    current: BerxelStackBackend,
    onChange: (BerxelStackBackend) -> Unit,
) {
    val selected = if (current == BerxelStackBackend.NATIVE_REWRITE) 0 else 1
    Box(Modifier.padding(horizontal = Gomob.spacing.s16)) {
        HairlineCard(padding = 0.dp) {
            Column(
                Modifier.fillMaxWidth().padding(Gomob.spacing.s12),
                verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("采集后端", fontSize = 13.sp, color = Gomob.colors.fg2)
                    Text(
                        if (current == BerxelStackBackend.NATIVE_REWRITE) "NATIVE" else "SDK",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Gomob.colors.accent,
                    )
                }
                SegmentedTabs(
                    items = listOf(
                        SegmentedTabItem("NATIVE"),
                        SegmentedTabItem("官方 SDK"),
                    ),
                    selectedIndex = selected,
                    onSelect = { index ->
                        onChange(if (index == 0) BerxelStackBackend.NATIVE_REWRITE else BerxelStackBackend.SDK)
                    },
                )
            }
        }
    }
}

// ─── Berxel flag 模式 + 帧规格切换器 ─────────────────────────────────────────
@Composable
private fun StreamProfileSelector(
    current: BerxelStreamProfile,
    backend: BerxelStackBackend,
    onChange: (BerxelStreamProfile) -> Unit,
) {
    if (backend == BerxelStackBackend.NATIVE_REWRITE) {
        NativeProfileCard(current = current)
        return
    }
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
                Text(current.summaryLine(), fontSize = 11.sp, color = Gomob.colors.fg2)
            }
        }
    }
}

@Composable
private fun NativeProfileCard(current: BerxelStreamProfile) {
    Box(Modifier.padding(horizontal = Gomob.spacing.s16)) {
        HairlineCard(padding = 0.dp) {
            Column(
                Modifier.fillMaxWidth().padding(Gomob.spacing.s12),
                verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "NATIVE",
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Gomob.colors.accent,
                    )
                    Text(
                        "${current.primaryFps() ?: 0}fps",
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Gomob.colors.fg2,
                    )
                }
                Text(current.summaryLine(), fontSize = 11.sp, color = Gomob.colors.fg2)
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
private fun LargePreview(
    label: String,
    bitmap: Bitmap?,
    placeholder: String = "等待首帧",
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    action2Label: String? = null,
    onAction2: (() -> Unit)? = null,
    action3Label: String? = null,
    onAction3: (() -> Unit)? = null,
) {
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
                filterQuality = FilterQuality.Medium,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                placeholder,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = Gomob.colors.fg2,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(Gomob.spacing.s8)
                .clip(Gomob.shapes.r1)
                .background(Color.Black.copy(alpha = 0.78f))
                .padding(horizontal = Gomob.spacing.s8, vertical = 3.dp),
        ) {
            Text(
                label,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Gomob.spacing.s8),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (action2Label != null && onAction2 != null) {
                Box(
                    Modifier
                        .clip(Gomob.shapes.r1)
                        .background(Color.Black.copy(alpha = 0.78f))
                        .clickable { onAction2() }
                        .padding(horizontal = Gomob.spacing.s12, vertical = 5.dp),
                ) {
                    Text(action2Label, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color.White)
                }
            }
            if (actionLabel != null && onAction != null) {
                Box(
                    Modifier
                        .clip(Gomob.shapes.r1)
                        .background(Color.Black.copy(alpha = 0.78f))
                        .clickable { onAction() }
                        .padding(horizontal = Gomob.spacing.s12, vertical = 5.dp),
                ) {
                    Text(actionLabel, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color.White)
                }
            }
            if (action3Label != null && onAction3 != null) {
                Box(
                    Modifier
                        .clip(Gomob.shapes.r1)
                        .background(Color(0xFF8B0000).copy(alpha = 0.85f))
                        .clickable { onAction3() }
                        .padding(horizontal = Gomob.spacing.s12, vertical = 5.dp),
                ) {
                    Text(action3Label, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color.White)
                }
            }
        }
    }
}

// ─── 实时状态条（fps + frame 序号 + 状态点） ─────────────────────────────────
@Composable
private fun LiveStatusStrip(ui: DepthCameraUiState) {
    val streaming = ui.device is BerxelDeviceState.Streaming
    val color = ui.color
    val depth = ui.depth
    val measuredFps = depth?.measuredFps ?: color?.measuredFps
    val fpsText = measuredFps?.let { if (it > 0) "$it fps" else "测量中" } ?: "—"
    val frameText = (depth?.frameIndex ?: color?.frameIndex)?.let { "frame#$it" } ?: "等待首帧"
    val statusText = when (val device = ui.device) {
        BerxelDeviceState.Idle -> "未启动"
        BerxelDeviceState.Initializing -> "正在枚举 USB"
        BerxelDeviceState.NoDevice -> "未检测到相机"
        BerxelDeviceState.WaitingPermission -> "等待 USB 权限"
        BerxelDeviceState.Opening -> "正在打开深度流"
        is BerxelDeviceState.Error -> device.reason
        is BerxelDeviceState.Streaming -> when {
            depth != null && ui.streamProfile.color == null -> "深度单流 · ${depth.width}×${depth.height}"
            color != null && depth != null && color.timestampUs == depth.timestampUs -> "RGBD 同步 ✓"
            color != null && depth != null -> "RGBD 未同步"
            color != null -> "彩色单流 · ${color.width}×${color.height}"
            depth != null -> "深度单流 · ${depth.width}×${depth.height}"
            else -> "等待首帧"
        }
    }
    val tone = if (streaming) Gomob.colors.accent else Gomob.colors.fg3
    val statusColor = if (ui.device is BerxelDeviceState.Error) Gomob.colors.danger else Gomob.colors.fg3
    Box(Modifier.padding(horizontal = Gomob.spacing.s16)) {
        HairlineCard(padding = 0.dp) {
            Box(Modifier.fillMaxWidth().padding(Gomob.spacing.s12)) {
                Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                    androidx.compose.foundation.layout.Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(fpsText, style = Gomob.type.numInline.copy(fontSize = 16.sp), color = tone)
                        Text(frameText, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Gomob.colors.fg1)
                    }
                    Text(statusText, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = statusColor)
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

private fun BerxelFrameStat?.fpsSuffix(): String = when {
    this == null -> ""
    measuredFps > 0 -> " · ${measuredFps}fps"
    else -> " · 测量中"
}
