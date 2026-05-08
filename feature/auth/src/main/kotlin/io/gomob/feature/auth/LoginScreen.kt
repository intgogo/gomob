package io.gomob.feature.auth

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.common.net.Ipv4AddressDraft
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob
import io.gomob.network.DiscoveredGateway
import io.gomob.ui.component.Ipv4AddressField

const val LOGIN_ROUTE = "auth/login"

/**
 * 01 登录屏 — 严格对齐 jsx login.jsx 排版。
 *
 * 视觉骨架（自上而下）：
 *   1. 顶部 Brand 行（padding 14/20/0）：左 Logo 28dp + "mob3d / v0.1.0" 横排 / 右 DEV tag
 *   2. 欢迎区（padding 60/24/0）："你好" / "登录工作台" 28sp / 副标题
 *   3. 输入区（padding 36/24/0 gap 14）：Field × 2 + 记住账号 / 去注册同行
 *   4. 主按钮（padding 24/24/0）：48dp 高 + accentSoft + "登 录" 字距 0.3em + ArrowRight
 *   5. flex:1 spacer
 *   6. 底部诊断条（margin 0/20/24/20）：mono 10sp "服务端 10.0.2.2:8808 · 已连接 · 28ms"
 */
@Composable
fun LoginRoute(
    onLoggedIn: () -> Unit,
    onGoRegister: () -> Unit = {},
    vm: LoginViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.loggedIn) {
        if (state.loggedIn) onLoggedIn()
    }
    LoginContent(
        state = state,
        onUsername = vm::setUsername,
        onPassword = vm::setPassword,
        onRemember = vm::setRemember,
        onSubmit = vm::submit,
        onGoRegister = onGoRegister,
        onOpenEndpointEditor = vm::openEndpointEditor,
        onCloseEndpointEditor = vm::closeEndpointEditor,
        onDraftIp = vm::setDraftIp,
        onDraftPort = vm::setDraftPort,
        onDiscoverGateways = { vm.discoverGateways() },
        onUseDiscoveredGateway = vm::useDiscoveredGateway,
        onTestDraft = vm::testDraft,
        onSaveDraft = vm::saveDraft,
        onDevBypass = vm::devBypassLogin,
    )
}

@Composable
private fun LoginContent(
    state: LoginUiState,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onRemember: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onGoRegister: () -> Unit,
    onOpenEndpointEditor: () -> Unit,
    onCloseEndpointEditor: () -> Unit,
    onDraftIp: (Ipv4AddressDraft) -> Unit,
    onDraftPort: (String) -> Unit,
    onDiscoverGateways: () -> Unit,
    onUseDiscoveredGateway: (DiscoveredGateway) -> Unit,
    onTestDraft: () -> Unit,
    onSaveDraft: () -> Unit,
    onDevBypass: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Gomob.colors.bg0)
            .verticalScroll(rememberScrollState()),
    ) {
        BrandRow(onDevBypass = onDevBypass)
        WelcomeBlock()
        InputBlock(
            state = state,
            onUsername = onUsername,
            onPassword = onPassword,
            onRemember = onRemember,
            onGoRegister = onGoRegister,
        )
        if (state.errorMessage != null) {
            ErrorBanner(message = state.errorMessage)
        }
        PrimaryButton(loading = state.loading, onClick = onSubmit)
        Spacer(Modifier.weight(1f, fill = true).height(Gomob.spacing.s24))
        DiagnosticStrip(
            endpoint = state.endpoint,
            connectivity = state.connectivity,
            onClick = onOpenEndpointEditor,
        )
    }
    if (state.editor != null) {
        EndpointEditorSheet(
            editor = state.editor,
            currentEndpoint = state.endpoint,
            discoveringGateways = state.discoveringGateways,
            discoveredGateways = state.discoveredGateways,
            discoveryMessage = state.discoveryMessage,
            onDismiss = onCloseEndpointEditor,
            onDraftIp = onDraftIp,
            onDraftPort = onDraftPort,
            onDiscover = onDiscoverGateways,
            onUseDiscovered = onUseDiscoveredGateway,
            onTest = onTestDraft,
            onSave = onSaveDraft,
        )
    }
}

// ─── 1. Brand 行 ────────────────────────────────────────────────────────────
@Composable
private fun BrandRow(onDevBypass: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Gomob.spacing.s20, end = Gomob.spacing.s20, top = Gomob.spacing.s14),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Logo 容器 28dp：accentSoft 底 + accentLine 边 + 内嵌 Cube SVG
            Box(
                Modifier
                    .size(Gomob.spacing.avatar28)
                    .clip(Gomob.shapes.r2)
                    .background(Gomob.colors.accentSoft)
                    .border(Gomob.spacing.hairline, Gomob.colors.accentLine, Gomob.shapes.r2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = GomobIcons.Logo,
                    contentDescription = null,
                    tint = Gomob.colors.accent,
                )
            }
            Column {
                Text(
                    "mob3d",
                    fontSize = 14.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    letterSpacing = 0.04.em,
                    color = Gomob.colors.fg0,
                )
                Spacer(Modifier.height(Gomob.spacing.s2))
                Text(
                    "v0.1.0",
                    style = Gomob.type.numInline.copy(
                        fontSize = 10.sp,
                        letterSpacing = 0.08.em,
                    ),
                    color = Gomob.colors.fg3,
                )
            }
        }
        // DEV tag — mono 11sp + line2 边 + 透明底
        DevTag(onDevBypass = onDevBypass)
    }
}

@Composable
private fun DevTag(onDevBypass: () -> Unit) {
    Box(
        Modifier
            .height(Gomob.spacing.chipHeight)
            .clip(Gomob.shapes.r1)
            .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r1)
            // 长按 DEV badge —— 写假 token 跳过登录鉴权，给硬件功能调试用。
            // 普通点击不响应，避免误触；只在 dev 调试场景的 long-press 才生效。
            .combinedClickableForDev(onLongPress = onDevBypass)
            .padding(horizontal = Gomob.spacing.s8),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "DEV",
            style = Gomob.type.caption.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                letterSpacing = 0.04.em,
            ),
            color = Gomob.colors.fg1,
        )
    }
}

// ─── 2. 欢迎区 ──────────────────────────────────────────────────────────────
@Composable
private fun WelcomeBlock() {
    Spacer(Modifier.height(60.dp - Gomob.spacing.s14))   // jsx padding "60px 24px 0" 减去 BrandRow 已用的 14
    Column(
        Modifier.padding(horizontal = Gomob.spacing.s24),
    ) {
        Text(
            "你好",
            fontSize = 14.sp,
            color = Gomob.colors.fg2,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "登录工作台",
            fontSize = 28.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            lineHeight = 32.sp,                               // 28 × 1.15
            letterSpacing = (-0.01).em,
            color = Gomob.colors.fg0,
        )
        Spacer(Modifier.height(Gomob.spacing.s8))
        Text(
            "机动车检测站 · 查验员协同工作台",
            fontSize = 13.sp,
            lineHeight = 19.sp,                               // 13 × 1.5 ≈ 19
            color = Gomob.colors.fg2,
        )
    }
}

// ─── 3. 输入区 ──────────────────────────────────────────────────────────────
@Composable
private fun InputBlock(
    state: LoginUiState,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onRemember: (Boolean) -> Unit,
    onGoRegister: () -> Unit,
) {
    Spacer(Modifier.height(36.dp))
    Column(
        Modifier.padding(horizontal = Gomob.spacing.s24),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        InlineField(
            icon = GomobIcons.User,
            label = "账号",
            value = state.username,
            placeholder = "工号或邮箱",
            onChange = onUsername,
        )
        InlineField(
            icon = GomobIcons.Lock,
            label = "密码",
            value = state.password,
            placeholder = "请输入密码",
            onChange = onPassword,
            isPassword = true,
            trailing = {
                Icon(
                    GomobIcons.Eye,
                    contentDescription = null,
                    tint = Gomob.colors.fg2,
                    modifier = Modifier.size(Gomob.spacing.icon16),
                )
            },
        )

        // 记住账号 / 去注册 同行
        Spacer(Modifier.height(Gomob.spacing.s6 - Gomob.spacing.s2))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            RememberCheckbox(
                checked = state.rememberMe,
                onToggle = { onRemember(!state.rememberMe) },
            )
            Text(
                "没账号 ? 去注册",
                fontSize = 12.sp,
                color = Gomob.colors.accent,
                modifier = Modifier.clickable(onClick = onGoRegister),
            )
        }
    }
}

@Composable
private fun InlineField(
    icon: ImageVector,
    label: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg1)
            .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r2)
            .padding(horizontal = Gomob.spacing.s14, vertical = Gomob.spacing.s8),
    ) {
        Column {
            // label 内嵌顶行：11sp + fg3
            Text(
                label,
                fontSize = 11.sp,
                color = Gomob.colors.fg3,
            )
            Spacer(Modifier.height(Gomob.spacing.s2))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Gomob.colors.fg2,
                    modifier = Modifier.size(Gomob.spacing.icon16),
                )
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            fontSize = 15.sp,
                            color = Gomob.colors.fg3,
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onChange,
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 15.sp,
                            color = Gomob.colors.fg0,
                            // 有值时数字/字母用 mono + 0.04em，与 jsx 一致
                            fontFamily = if (value.isNotEmpty() && !isPassword)
                                androidx.compose.ui.text.font.FontFamily.Monospace
                            else
                                androidx.compose.ui.text.font.FontFamily.Default,
                            letterSpacing = if (value.isNotEmpty() && !isPassword) 0.04.em else 0.em,
                        ),
                        cursorBrush = SolidColor(Gomob.colors.accent),
                        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                        visualTransformation = if (isPassword)
                            PasswordVisualTransformation()
                        else
                            VisualTransformation.None,
                    )
                }
                if (trailing != null) trailing()
            }
        }
    }
}

@Composable
private fun RememberCheckbox(checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        // 14×14 勾选框 — accentSoft 底 + accentLine 边 + 选中显 Check
        Box(
            Modifier
                .size(14.dp)
                .clip(Gomob.shapes.r1)
                .background(if (checked) Gomob.colors.accentSoft else Gomob.colors.bg2)
                .border(
                    Gomob.spacing.hairline,
                    if (checked) Gomob.colors.accentLine else Gomob.colors.line2,
                    Gomob.shapes.r1,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    GomobIcons.Check,
                    contentDescription = null,
                    tint = Gomob.colors.accent,
                    modifier = Modifier.size(10.dp),
                )
            }
        }
        Text(
            "记住账号",
            fontSize = 12.sp,
            color = Gomob.colors.fg1,
        )
    }
}

// ─── 错误条（jsx 没画但保留 — 业务真实需要） ────────────────────────────────
@Composable
private fun ErrorBanner(message: String) {
    Spacer(Modifier.height(Gomob.spacing.s12))
    Row(
        Modifier
            .padding(horizontal = Gomob.spacing.s24)
            .fillMaxWidth()
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.dangerSoft)
            .border(Gomob.spacing.hairline, Gomob.colors.dangerLine, Gomob.shapes.r2)
            .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        Box(
            Modifier
                .size(Gomob.spacing.dot6)
                .clip(CircleShape)
                .background(Gomob.colors.danger),
        )
        Text(
            message,
            fontSize = 12.sp,
            color = Gomob.colors.danger,
        )
    }
}

// ─── 4. 主按钮 ──────────────────────────────────────────────────────────────
@Composable
private fun PrimaryButton(loading: Boolean, onClick: () -> Unit) {
    Spacer(Modifier.height(Gomob.spacing.s24))
    Box(
        Modifier
            .padding(horizontal = Gomob.spacing.s24)
            .fillMaxWidth()
            .height(Gomob.spacing.avatar48)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.accentSoft)
            .border(Gomob.spacing.hairline, Gomob.colors.accentLine, Gomob.shapes.r2)
            .clickable(enabled = !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = Gomob.colors.accent,
                modifier = Modifier.size(Gomob.spacing.icon20),
                strokeWidth = Gomob.spacing.s2,
            )
        } else {
            // 文字 "登 录" + 字距 0.3em — Box 居中 Text + 右上角箭头叠加
            Text(
                "登 录",
                fontSize = 14.sp,
                letterSpacing = 0.3.em,
                color = Gomob.colors.accent,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(end = 18.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    GomobIcons.ArrowRight,
                    contentDescription = null,
                    tint = Gomob.colors.accent,
                    modifier = Modifier.size(Gomob.spacing.icon16),
                )
            }
        }
    }
}

// ─── 6. 底部诊断条（可点击 → 弹出编辑面板） ─────────────────────────────────
@Composable
private fun DiagnosticStrip(
    endpoint: io.gomob.network.ServerEndpoint,
    connectivity: ConnectivityStatus,
    onClick: () -> Unit,
) {
    val (dotColor, statusText) = connectivity.toLabel()
    Row(
        Modifier
            .padding(start = Gomob.spacing.s20, end = Gomob.spacing.s20, bottom = Gomob.spacing.s24)
            .fillMaxWidth()
            .clip(Gomob.shapes.r2)
            .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r2)
            .clickable(onClick = onClick)
            .padding(horizontal = Gomob.spacing.s12, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "服务端  ${endpoint.display()}",
            style = Gomob.type.numInline.copy(
                fontSize = 10.sp,
                letterSpacing = 0.06.em,
            ),
            color = Gomob.colors.fg3,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
        ) {
            Box(
                Modifier
                    .size(Gomob.spacing.dot6)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Text(
                statusText,
                style = Gomob.type.numInline.copy(
                    fontSize = 10.sp,
                    letterSpacing = 0.06.em,
                ),
                color = Gomob.colors.fg3,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ConnectivityStatus.toLabel(): Pair<androidx.compose.ui.graphics.Color, String> = when (this) {
    is ConnectivityStatus.Unknown -> Gomob.colors.fg3 to "未测"
    is ConnectivityStatus.Probing -> Gomob.colors.fg3 to "测试中…"
    is ConnectivityStatus.Ok -> Gomob.colors.ok to "已连接 · ${latencyMs}ms"
    is ConnectivityStatus.Failed -> Gomob.colors.danger to reason
}

// ─── 7. 端点编辑面板（ModalBottomSheet） ─────────────────────────────────────
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EndpointEditorSheet(
    editor: EndpointEditorState,
    currentEndpoint: io.gomob.network.ServerEndpoint,
    discoveringGateways: Boolean,
    discoveredGateways: List<DiscoveredGateway>,
    discoveryMessage: String?,
    onDismiss: () -> Unit,
    onDraftIp: (Ipv4AddressDraft) -> Unit,
    onDraftPort: (String) -> Unit,
    onDiscover: () -> Unit,
    onUseDiscovered: (DiscoveredGateway) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Gomob.colors.bg1,
        contentColor = Gomob.colors.fg0,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Gomob.spacing.s24, vertical = Gomob.spacing.s12)
                .padding(bottom = Gomob.spacing.s24),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s14),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                Text(
                    "服务端网关",
                    fontSize = 16.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    color = Gomob.colors.fg0,
                )
                Text(
                    "当前已保存: ${currentEndpoint.display()}",
                    fontSize = 12.sp,
                    color = Gomob.colors.fg3,
                )
            }
            DiscoverySection(
                currentEndpoint = currentEndpoint,
                discovering = discoveringGateways,
                gateways = discoveredGateways,
                message = discoveryMessage,
                onDiscover = onDiscover,
                onUse = onUseDiscovered,
            )
            Ipv4AddressField(
                label = "网关 IP",
                value = editor.draftIp,
                onValueChange = onDraftIp,
                isError = editor.validationError?.contains("IP") == true,
            )
            InlineField(
                icon = GomobIcons.Settings,
                label = "端口",
                value = editor.draftPort,
                placeholder = "8808",
                onChange = onDraftPort,
                keyboardType = KeyboardType.Number,
            )
            if (editor.validationError != null) {
                Text(
                    editor.validationError,
                    fontSize = 12.sp,
                    color = Gomob.colors.danger,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
            ) {
                val (testDot, testText) = editor.testResult.toLabel()
                Box(
                    Modifier
                        .size(Gomob.spacing.dot6)
                        .clip(CircleShape)
                        .background(testDot),
                )
                Text(
                    testText,
                    fontSize = 12.sp,
                    color = Gomob.colors.fg2,
                    modifier = Modifier.weight(1f),
                )
                SheetButton(
                    label = if (editor.testing) "测试中…" else "测试连接",
                    enabled = !editor.testing && !editor.saving,
                    onClick = onTest,
                    primary = false,
                )
            }
            SheetButton(
                label = if (editor.saving) "保存中…" else "保存",
                enabled = !editor.saving && !editor.testing,
                onClick = onSave,
                primary = true,
            )
        }
    }
}

@Composable
private fun DiscoverySection(
    currentEndpoint: io.gomob.network.ServerEndpoint,
    discovering: Boolean,
    gateways: List<DiscoveredGateway>,
    message: String?,
    onDiscover: () -> Unit,
    onUse: (DiscoveredGateway) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "同网段服务器",
                fontSize = 13.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                color = Gomob.colors.fg1,
            )
            SmallIconButton(
                icon = GomobIcons.Refresh,
                loading = discovering,
                enabled = !discovering,
                onClick = onDiscover,
            )
        }
        when {
            gateways.isNotEmpty() -> gateways.forEach { gateway ->
                DiscoveredGatewayRow(
                    gateway = gateway,
                    selected = gateway.endpoint == currentEndpoint,
                    enabled = !discovering,
                    onUse = { onUse(gateway) },
                )
            }
            else -> DiscoveryStatusRow(text = if (discovering) "发现中…" else message ?: "暂无发现")
        }
    }
}

@Composable
private fun DiscoveryStatusRow(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(Gomob.spacing.touchMin)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg0)
            .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r2)
            .padding(horizontal = Gomob.spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        Icon(
            GomobIcons.Search,
            contentDescription = null,
            tint = Gomob.colors.fg3,
            modifier = Modifier.size(Gomob.spacing.icon16),
        )
        Text(
            text,
            fontSize = 12.sp,
            color = Gomob.colors.fg3,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DiscoveredGatewayRow(
    gateway: DiscoveredGateway,
    selected: Boolean,
    enabled: Boolean,
    onUse: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(Gomob.spacing.rowSetting)
            .clip(Gomob.shapes.r2)
            .background(if (selected) Gomob.colors.accentSoft else Gomob.colors.bg0)
            .border(
                Gomob.spacing.hairline,
                if (selected) Gomob.colors.accentLine else Gomob.colors.line2,
                Gomob.shapes.r2,
            )
            .clickable(enabled = enabled && !selected, onClick = onUse)
            .padding(horizontal = Gomob.spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        Icon(
            GomobIcons.Wifi,
            contentDescription = null,
            tint = if (selected) Gomob.colors.accent else Gomob.colors.fg2,
            modifier = Modifier.size(Gomob.spacing.icon16),
        )
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2),
        ) {
            Text(
                gateway.name,
                fontSize = 13.sp,
                color = Gomob.colors.fg0,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                "${gateway.endpoint.display()} · ${gateway.latencyMs}ms",
                style = Gomob.type.numInline.copy(fontSize = 10.sp, letterSpacing = 0.04.em),
                color = Gomob.colors.fg3,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(
                GomobIcons.Check,
                contentDescription = null,
                tint = Gomob.colors.accent,
                modifier = Modifier.size(Gomob.spacing.icon16),
            )
        } else {
            Text(
                "使用",
                fontSize = 12.sp,
                color = if (enabled) Gomob.colors.accent else Gomob.colors.fg3,
            )
        }
    }
}

@Composable
private fun SmallIconButton(
    icon: ImageVector,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(Gomob.spacing.s32)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg0)
            .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r2)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = Gomob.colors.accent,
                modifier = Modifier.size(Gomob.spacing.icon16),
                strokeWidth = Gomob.spacing.s2,
            )
        } else {
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) Gomob.colors.fg1 else Gomob.colors.fg3,
                modifier = Modifier.size(Gomob.spacing.icon16),
            )
        }
    }
}

@Composable
private fun SheetButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    primary: Boolean,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(Gomob.spacing.avatar48)
            .clip(Gomob.shapes.r2)
            .background(if (primary) Gomob.colors.accentSoft else Gomob.colors.bg2)
            .border(
                Gomob.spacing.hairline,
                if (primary) Gomob.colors.accentLine else Gomob.colors.line2,
                Gomob.shapes.r2,
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 14.sp,
            color = if (primary) Gomob.colors.accent else Gomob.colors.fg1,
            letterSpacing = 0.1.em,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableForDev(onLongPress: () -> Unit): Modifier =
    this.combinedClickable(onClick = {}, onLongClick = onLongPress)
