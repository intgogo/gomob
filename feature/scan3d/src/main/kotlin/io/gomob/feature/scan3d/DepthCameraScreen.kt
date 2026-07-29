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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import io.gomob.designsystem.glass.GlassHeaderScaffold
import io.gomob.designsystem.theme.Gomob
import io.gomob.nativebridge.berxel.BerxelFrameStat
import io.gomob.nativebridge.berxel.BerxelStackBackend
import io.gomob.nativebridge.berxel.BerxelStreamProfile
import io.gomob.nativebridge.berxel.BerxelStreamProfiles
import io.gomob.nativebridge.berxel.BerxelStreamTarget
import io.gomob.nativebridge.camera.CameraSourceState

const val DEPTH_CAMERA_ROUTE = "scan3d/depth-camera"

/**
 * 深度相机只读状态页 — 大画面竖排预览 + 设备信息入口。
 *
 * 设计:
 *  - COLOR / DEPTH 各占满宽度独立成行（横排会让画面太小）
 *  - 手机端不承担标定、参数管理或厂商调试，只提供使用态监看
 */
@Composable
fun DepthCameraRoute(
    onBack: () -> Unit,
    onOpenInfo: () -> Unit = {},
    vm: DepthCameraViewModel = hiltViewModel(),
) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val colorBmp by vm.colorPreview.collectAsStateWithLifecycle()
    val depthBmp by vm.depthPreview.collectAsStateWithLifecycle()
    val rgbBmp by vm.rgbPreview.collectAsStateWithLifecycle()
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
    LaunchedEffect(hasCameraPermission) {
        vm.setCameraPermissionGranted(hasCameraPermission)
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val listState = rememberLazyListState()
    GlassHeaderScaffold(
        listState = listState,
        header = {
            BackHeader(
                title = "深度相机",
                eyebrow = ui.label.ifBlank { "深度相机" } + " · 状态监看",
                onBack = onBack,
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + Gomob.spacing.s12,
                bottom = padding.calculateBottomPadding() + Gomob.spacing.s28,
            ),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        ) {
            // HLSD8 真彩 RGB（独立第二颗 USB 相机，正射图高分辨率源）—— 仅插着时显示。
            if (ui.hasRgb) {
                item {
                    LargePreview(
                        label = "RGB · ${ui.rgbLabel.ifBlank { "HLSD8" }}${ui.rgbStat.fpsSuffix()}",
                        bitmap = rgbBmp,
                        placeholder = "等待 HLSD8 RGB 帧…",
                    )
                }
            }
            item {
                LargePreview(
                    // eYs3D 这路是 L'(左矫正/IR 参考)，非真彩；Berxel 才是真彩。标签随相机区分。
                    label = (if (ui.isBerxel) "COLOR" else "L' (左矫正)") + ui.colorStat.fpsSuffix(),
                    bitmap = colorBmp,
                    placeholder = ui.sourceState.colorPlaceholder(),
                )
            }
            item {
                LargePreview(
                    label = buildString {
                        append(if (ui.isBerxel && irRenderMode) "DEPTH · IR-GREY" else "DEPTH · TURBO")
                        append(ui.depthStat.fpsSuffix())
                        if (ui.isBerxel) {
                            append(" · ")
                            append(if (strictFrameSize) "STRICT 401" else "RAW")
                        }
                    },
                    bitmap = depthBmp,
                    placeholder = ui.sourceState.depthPlaceholder(),
                )
            }
            item { LiveStatusStrip(ui = ui) }
            item { Spacer(Modifier.height(Gomob.spacing.s4)) }
            // 只读设备信息允许手机查看；标定、参数管理和厂商调试统一留给网页管理台。
            if (ui.isBerxel) {
                item {
                    SectionList {
                        NavRow(title = "设备详情", subtitle = "序列号 / 流模式 / 内参 / 帧统计", onClick = onOpenInfo)
                    }
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
    // 画面框宽高比跟随真实帧：Berxel 640×400≈1.6；eYs3D mode25 1280×256 / 640×128≈5:1。
    // 无帧时用 16:10 占位，避免写死 16:10 把 eYs3D 宽幅画面压成一条窄缝。
    val aspect = bitmap?.let { if (it.height > 0) it.width.toFloat() / it.height else null }
        ?.coerceIn(0.6f, 6f) ?: (16f / 10f)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Gomob.spacing.s16)
            .aspectRatio(aspect)
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
    val streaming = ui.sourceState is CameraSourceState.Streaming
    val color = ui.colorStat
    val depth = ui.depthStat
    val measuredFps = depth?.measuredFps ?: color?.measuredFps
    val fpsText = measuredFps?.let { if (it > 0) "$it fps" else "测量中" } ?: "—"
    val frameText = (depth?.frameIndex ?: color?.frameIndex)?.let { "frame#$it" } ?: "等待首帧"
    val statusText = when (val s = ui.sourceState) {
        CameraSourceState.Idle -> "未启动"
        CameraSourceState.NoDevice -> "未检测到相机"
        CameraSourceState.WaitingPermission -> "等待 USB 权限"
        CameraSourceState.Opening -> "正在打开相机流"
        is CameraSourceState.Error -> s.message
        is CameraSourceState.Streaming -> when {
            color != null && depth != null ->
                "COLOR ${color.width}×${color.height} · DEPTH ${depth.width}×${depth.height}"
            color != null -> "彩色单流 · ${color.width}×${color.height}"
            depth != null -> "深度单流 · ${depth.width}×${depth.height}"
            else -> "等待首帧"
        }
    }
    val tone = if (streaming) Gomob.colors.accent else Gomob.colors.fg3
    val statusColor = if (ui.sourceState is CameraSourceState.Error) Gomob.colors.danger else Gomob.colors.fg3
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
    else "frame#$frameIndex · ${if (visualStale) "冻结" else "$measuredFps fps"} · t=${timestampUs}μs · ${width}×${height}"

private fun PreviewStat?.fpsSuffix(): String = when {
    this == null -> ""
    stale -> " · 冻结"
    measuredFps > 0 -> " · ${measuredFps}fps"
    else -> " · 测量中"
}

// ─── 中性取流状态 → 占位文案（双相机通用） ──────────────────────────────────
private fun CameraSourceState.colorPlaceholder(): String = when (this) {
    CameraSourceState.Idle -> "未启动"
    CameraSourceState.NoDevice -> "未检测到相机"
    CameraSourceState.WaitingPermission -> "等待 USB 权限"
    CameraSourceState.Opening -> "正在打开相机"
    is CameraSourceState.Streaming -> "等待彩色帧"
    is CameraSourceState.Error -> "彩色流异常"
}

private fun CameraSourceState.depthPlaceholder(): String = when (this) {
    CameraSourceState.Idle -> "未启动"
    CameraSourceState.NoDevice -> "未检测到相机"
    CameraSourceState.WaitingPermission -> "等待 USB 权限"
    CameraSourceState.Opening -> "正在打开深度流"
    is CameraSourceState.Streaming -> "等待深度帧"
    is CameraSourceState.Error -> message
}
