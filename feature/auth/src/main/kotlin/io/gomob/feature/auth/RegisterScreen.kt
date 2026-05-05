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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.component.HairlineCard
import io.gomob.designsystem.theme.Gomob

const val REGISTER_ROUTE = "auth/register"

@Composable
fun RegisterRoute(
    onBack: () -> Unit,
    onRegistered: () -> Unit,
    vm: RegisterViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(title = "注册账号", onBack = onBack, eyebrow = "查验员入驻")
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Gomob.spacing.s24, vertical = Gomob.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s16),
        ) {
            HairlineInput(
                label = "真实姓名",
                value = state.realName,
                placeholder = "如:沈海明",
                onChange = vm::setRealName,
            )
            HairlineInput(
                label = "查验员工号",
                value = state.employeeId,
                placeholder = "如:ZAA0120230001",
                onChange = vm::setEmployeeId,
            )
            HairlineInput(
                label = "所属检测站(说明)",
                value = state.stationHint,
                placeholder = "如:杭州市西湖区车管所检测站",
                onChange = vm::setStationHint,
            )
            HairlineInput(
                label = "登录用户名",
                value = state.username,
                placeholder = "字母/数字/下划线",
                onChange = vm::setUsername,
            )
            HairlineInput(
                label = "登录密码",
                value = state.password,
                placeholder = "至少 6 位",
                isPassword = true,
                onChange = vm::setPassword,
            )
            HairlineInput(
                label = "确认密码",
                value = state.confirmPassword,
                placeholder = "再次输入密码",
                isPassword = true,
                onChange = vm::setConfirmPassword,
            )

            if (state.errorMessage != null) {
                HairlineCard(padding = Gomob.spacing.s12) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(Gomob.spacing.dot6)
                                .clip(CircleShape)
                                .background(Gomob.colors.danger),
                        )
                        Spacer(Modifier.padding(start = Gomob.spacing.s8))
                        Text(state.errorMessage!!, style = Gomob.type.bodySm, color = Gomob.colors.danger)
                    }
                }
            }
            if (state.successMessage != null) {
                HairlineCard(padding = Gomob.spacing.s12, onClick = onRegistered) {
                    Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(Gomob.spacing.dot6).clip(CircleShape).background(Gomob.colors.ok),
                            )
                            Spacer(Modifier.padding(start = Gomob.spacing.s8))
                            Text(state.successMessage!!, style = Gomob.type.body, color = Gomob.colors.ok)
                        }
                        Text("点此返回登录", style = Gomob.type.caption, color = Gomob.colors.fg3)
                    }
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(Gomob.spacing.touchMin)
                    .clip(Gomob.shapes.r2)
                    .background(Gomob.colors.accentSoft)
                    .border(Gomob.spacing.hairline, Gomob.colors.accentLine, Gomob.shapes.r2)
                    .clickable(enabled = !state.loading, onClick = vm::submit),
                contentAlignment = Alignment.Center,
            ) {
                if (state.loading) {
                    CircularProgressIndicator(
                        color = Gomob.colors.accent,
                        modifier = Modifier.size(Gomob.spacing.icon20),
                        strokeWidth = Gomob.spacing.s2,
                    )
                } else {
                    Text("提交注册", style = Gomob.type.body, color = Gomob.colors.accent)
                }
            }

            Text(
                "提交后由检测站管理员后台审核通过后方可登录;DEV 环境下自动激活。",
                style = Gomob.type.caption,
                color = Gomob.colors.fg3,
            )
        }
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
