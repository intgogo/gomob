package io.gomob.feature.profile

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.component.HairlineCard
import io.gomob.designsystem.component.SettingRow
import io.gomob.designsystem.component.SettingRowDivider
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.theme.Gomob

// ============================================================================
// 个人信息
// ============================================================================
@Composable
fun ProfilePersonalRoute(onBack: () -> Unit, vm: ProfileViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val p = state.profile
    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(title = "个人信息", onBack = onBack, eyebrow = "查验员档案")
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = Gomob.spacing.s16, vertical = Gomob.spacing.s12),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        ) {
            HairlineCard(padding = 0.dp) {
                Column {
                    InfoRow("登录用户名", p?.username ?: "—", mono = true)
                    SettingRowDivider()
                    InfoRow("真实姓名", p?.realName ?: "—")
                    SettingRowDivider()
                    InfoRow("查验员工号", p?.employeeId ?: "—", mono = true)
                    SettingRowDivider()
                    InfoRow("角色", p?.roleLabel ?: "—")
                    SettingRowDivider()
                    InfoRow("所属检测站", p?.stationName ?: "未绑定")
                }
            }
            HairlineCard {
                Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                    Text("说明", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
                    Text(
                        "档案信息由管理员维护,如需变更请联系所在检测站管理员或在 Web 后台申请。",
                        style = Gomob.type.caption,
                        color = Gomob.colors.fg3,
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(Gomob.spacing.rowSetting)
            .padding(horizontal = Gomob.spacing.s16),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = Gomob.type.bodySm, color = Gomob.colors.fg2)
        Text(
            value,
            style = if (mono) Gomob.type.numInline else Gomob.type.body,
            color = Gomob.colors.fg0,
        )
    }
}

// ============================================================================
// 账号与安全(改密码)
// ============================================================================
@Composable
fun ProfileAccountRoute(onBack: () -> Unit) {
    var oldPwd by remember { mutableStateOf("") }
    var newPwd by remember { mutableStateOf("") }
    var confirmPwd by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(title = "账号与安全", onBack = onBack, eyebrow = "修改密码 / 设备管理")
        Column(
            Modifier.padding(horizontal = Gomob.spacing.s16, vertical = Gomob.spacing.s12),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        ) {
            PasswordInput("当前密码", oldPwd, "请输入当前密码") { oldPwd = it }
            PasswordInput("新密码", newPwd, "至少 8 位,含字母和数字") { newPwd = it }
            PasswordInput("确认新密码", confirmPwd, "再次输入新密码") { confirmPwd = it }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(Gomob.spacing.touchMin)
                    .clip(Gomob.shapes.r2)
                    .background(Gomob.colors.accentSoft)
                    .border(Gomob.spacing.hairline, Gomob.colors.accentLine, Gomob.shapes.r2)
                    .clickable {},
                contentAlignment = Alignment.Center,
            ) {
                Text("提交修改", style = Gomob.type.body, color = Gomob.colors.accent)
            }

            HairlineCard(padding = 0.dp) {
                Column {
                    SettingRow(
                        title = "已登录设备",
                        subtitle = "当前 1 台 · Pixel 8 (本机)",
                        onClick = {},
                    )
                    SettingRowDivider()
                    SettingRow(
                        title = "登录历史",
                        subtitle = "最近 30 天 5 次",
                        onClick = {},
                    )
                }
            }
        }
    }
}

@Composable
private fun PasswordInput(
    label: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s6)) {
        Text(label, style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
        Box(
            Modifier
                .fillMaxWidth()
                .height(Gomob.spacing.touchMin)
                .clip(Gomob.shapes.r2)
                .background(Gomob.colors.bg2)
                .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r2)
                .padding(horizontal = Gomob.spacing.s12),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                Text(placeholder, style = Gomob.type.body, color = Gomob.colors.fg3)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = Gomob.type.body.copy(color = Gomob.colors.fg0),
                cursorBrush = SolidColor(Gomob.colors.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
            )
        }
    }
}

// ============================================================================
// 网络设置
// ============================================================================
@Composable
fun ProfileNetworkRoute(
    onBack: () -> Unit,
    vm: ProfileNetworkViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(
            title = "网络设置",
            onBack = onBack,
            eyebrow = "网关 IP + 端口",
            trailing = { StatusTag(text = "DEV", tone = StatusTone.Accent) },
        )
        Column(
            Modifier.padding(horizontal = Gomob.spacing.s16, vertical = Gomob.spacing.s12),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        ) {
            TextInputCard(
                label = "网关 IP",
                value = state.draftIp,
                placeholder = "192.168.x.x 或 10.x.x.x",
                keyboardType = KeyboardType.Uri,
                onChange = vm::setDraftIp,
            )
            TextInputCard(
                label = "端口",
                value = state.draftPort,
                placeholder = "8808",
                keyboardType = KeyboardType.Number,
                onChange = vm::setDraftPort,
            )
            HairlineCard {
                Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                    Text("当前已保存", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
                    Text(state.savedEndpoint.display(), style = Gomob.type.numInline, color = Gomob.colors.fg0)
                    val testLine: String = when (val r = state.testResult) {
                        is ProbeStatus.Unknown -> "App 仅配置单一网关地址,反向代理到内部各服务。"
                        is ProbeStatus.Probing -> "测试中…"
                        is ProbeStatus.Ok -> "测试通过 · ${r.latencyMs}ms"
                        is ProbeStatus.Failed -> "测试失败: ${r.reason}"
                    }
                    Text(testLine, style = Gomob.type.caption, color = Gomob.colors.fg3)
                    if (state.savedToast != null) {
                        Text(state.savedToast!!, style = Gomob.type.caption, color = Gomob.colors.ok)
                    }
                    if (state.validationError != null) {
                        Text(state.validationError!!, style = Gomob.type.caption, color = Gomob.colors.danger)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
            ) {
                NetworkActionButton(
                    label = if (state.testing) "测试中…" else "仅测试",
                    enabled = !state.testing && !state.saving,
                    onClick = vm::test,
                    primary = false,
                    modifier = Modifier.weight(1f),
                )
                NetworkActionButton(
                    label = if (state.saving) "保存中…" else "测试 + 保存",
                    enabled = !state.testing && !state.saving,
                    onClick = vm::testAndSave,
                    primary = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NetworkActionButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    primary: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .height(Gomob.spacing.touchMin)
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
            style = Gomob.type.body,
            color = if (primary) Gomob.colors.accent else Gomob.colors.fg1,
        )
    }
}

@Composable
private fun TextInputCard(
    label: String,
    value: String,
    placeholder: String,
    keyboardType: KeyboardType,
    onChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s6)) {
        Text(label, style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
        Box(
            Modifier
                .fillMaxWidth()
                .height(Gomob.spacing.touchMin)
                .clip(Gomob.shapes.r2)
                .background(Gomob.colors.bg2)
                .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r2)
                .padding(horizontal = Gomob.spacing.s12),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                Text(placeholder, style = Gomob.type.body, color = Gomob.colors.fg3)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = Gomob.type.numInline.copy(color = Gomob.colors.fg0),
                cursorBrush = SolidColor(Gomob.colors.accent),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            )
        }
    }
}

// ============================================================================
// 通知设置
// ============================================================================
@Composable
fun ProfileNotificationRoute(onBack: () -> Unit) {
    var globalOn by remember { mutableStateOf(true) }
    var inAppOn by remember { mutableStateOf(true) }
    var sysBarOn by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(title = "通知", onBack = onBack, eyebrow = "推送 + 系统通知栏")
        Column(
            Modifier.padding(horizontal = Gomob.spacing.s16, vertical = Gomob.spacing.s12),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        ) {
            HairlineCard(padding = 0.dp) {
                Column {
                    ToggleRow("通知总开关", "关闭后所有通知静默", globalOn) { globalOn = it }
                    SettingRowDivider()
                    ToggleRow("APP 内通知", "前台横幅 + 红点", inAppOn) { inAppOn = it }
                    SettingRowDivider()
                    ToggleRow("系统通知栏", "锁屏 / 通知栏 / 抬腕", sysBarOn) { sysBarOn = it }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    value: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(Gomob.spacing.rowSetting)
            .clickable { onChange(!value) }
            .padding(horizontal = Gomob.spacing.s16),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2)) {
            Text(title, style = Gomob.type.body, color = Gomob.colors.fg0)
            Text(subtitle, style = Gomob.type.caption, color = Gomob.colors.fg3)
        }
        // 开关 — 极简 hairline 样式
        Box(
            Modifier
                .padding(start = Gomob.spacing.s12)
                .width(Gomob.spacing.switchW)
                .height(Gomob.spacing.switchH)
                .clip(Gomob.shapes.pill)
                .background(if (value) Gomob.colors.accentSoft else Gomob.colors.bg2)
                .border(
                    Gomob.spacing.hairline,
                    if (value) Gomob.colors.accentLine else Gomob.colors.line2,
                    Gomob.shapes.pill,
                ),
            contentAlignment = if (value) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .padding(Gomob.spacing.switchPad)
                    .height(Gomob.spacing.switchThumb)
                    .width(Gomob.spacing.switchThumb)
                    .clip(Gomob.shapes.pill)
                    .background(if (value) Gomob.colors.accent else Gomob.colors.fg3),
            )
        }
    }
}

// ============================================================================
// 关于 gomob
// ============================================================================
@Composable
fun ProfileAboutRoute(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(title = "关于 gomob", onBack = onBack, eyebrow = "版本 + 法务")
        Column(
            Modifier.padding(horizontal = Gomob.spacing.s16, vertical = Gomob.spacing.s12),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        ) {
            HairlineCard {
                Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                    Text("gomob", style = Gomob.type.display, color = Gomob.colors.fg0)
                    Text("机动车检测站查验员工作台", style = Gomob.type.body, color = Gomob.colors.fg2)
                    Text("v0.1.0 · Build 2026.05.04", style = Gomob.type.numInline, color = Gomob.colors.fg3)
                }
            }
            HairlineCard(padding = 0.dp) {
                Column {
                    SettingRow(title = "用户协议", onClick = {})
                    SettingRowDivider()
                    SettingRow(title = "隐私政策", onClick = {})
                    SettingRowDivider()
                    SettingRow(title = "开源许可", onClick = {})
                    SettingRowDivider()
                    SettingRow(
                        title = "技术支持",
                        subtitle = "support@gomob.io",
                        onClick = {},
                    )
                }
            }
            HairlineCard {
                Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                    Text("硬件配套", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
                    Text("Berxel iHawk 系列深度相机 · USB-C OTG 接入", style = Gomob.type.bodySm, color = Gomob.colors.fg1)
                    Text(
                        "© 2026 gomob.io",
                        style = Gomob.type.caption,
                        color = Gomob.colors.fg3,
                    )
                }
            }
        }
    }
}
