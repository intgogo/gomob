package io.gomob.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.HairlineCard
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.theme.Gomob

const val LOGIN_ROUTE = "auth/login"

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
        onSubmit = vm::submit,
        onGoRegister = onGoRegister,
    )
}

@Composable
private fun LoginContent(
    state: LoginUiState,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
    onGoRegister: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Gomob.colors.bg0)
            .padding(horizontal = Gomob.spacing.s24),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s20),
    ) {
        Spacer(Modifier.height(Gomob.spacing.s32))

        Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2)) {
            Text("gomob", style = Gomob.type.display, color = Gomob.colors.fg0)
            Text("v0.1.0 · 机动车检测站查验员工作台", style = Gomob.type.numInline, color = Gomob.colors.fg3)
        }

        HairlineCard(padding = Gomob.spacing.s12) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
            ) {
                Box(Modifier.size(Gomob.spacing.dot6).clip(CircleShape).background(Gomob.colors.ok))
                Text("服务端", style = Gomob.type.bodySm, color = Gomob.colors.fg1)
                Spacer(Modifier.weight(1f))
                StatusTag(text = "DEV · 127.0.0.1:8808", tone = StatusTone.Ok)
            }
        }

        HairlineInput(
            label = "账号",
            value = state.username,
            placeholder = "工号或邮箱",
            onChange = onUsername,
        )
        HairlineInput(
            label = "密码",
            value = state.password,
            placeholder = "请输入密码",
            isPassword = true,
            onChange = onPassword,
        )

        if (state.errorMessage != null) {
            HairlineCard(padding = Gomob.spacing.s12) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(Gomob.spacing.dot6).clip(CircleShape).background(Gomob.colors.danger))
                    Spacer(Modifier.width(Gomob.spacing.s8))
                    Text(state.errorMessage, style = Gomob.type.bodySm, color = Gomob.colors.danger)
                }
            }
        }

        Spacer(Modifier.height(Gomob.spacing.s4))

        Box(
            Modifier
                .fillMaxWidth()
                .height(Gomob.spacing.touchMin)
                .clip(Gomob.shapes.r2)
                .background(Gomob.colors.accentSoft)
                .border(Gomob.spacing.hairline, Gomob.colors.accentLine, Gomob.shapes.r2)
                .clickable(enabled = !state.loading, onClick = onSubmit),
            contentAlignment = Alignment.Center,
        ) {
            if (state.loading) {
                CircularProgressIndicator(
                    color = Gomob.colors.accent,
                    modifier = Modifier.size(Gomob.spacing.icon20),
                    strokeWidth = Gomob.spacing.s2,
                )
            } else {
                Text("登录", style = Gomob.type.body, color = Gomob.colors.accent)
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("记住账号", style = Gomob.type.caption, color = Gomob.colors.fg2)
            Text(
                text = "没账号? 去注册",
                style = Gomob.type.caption,
                color = Gomob.colors.accent,
                modifier = Modifier.clickable(onClick = onGoRegister),
            )
        }

        Spacer(Modifier.height(Gomob.spacing.s32))
    }
}

@Composable
private fun HairlineInput(
    label: String,
    value: String,
    placeholder: String,
    isPassword: Boolean = false,
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
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text,
                ),
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            )
        }
    }
}
