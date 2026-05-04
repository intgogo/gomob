package io.gomob.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.GlassCard
import io.gomob.designsystem.component.PrimaryButton
import io.gomob.designsystem.component.TechTextField
import io.gomob.designsystem.theme.Accent
import io.gomob.designsystem.theme.AccentDim
import io.gomob.designsystem.theme.Primary
import io.gomob.designsystem.theme.PrimaryDim
import io.gomob.designsystem.theme.StateDanger
import io.gomob.designsystem.theme.SurfaceCard
import io.gomob.designsystem.theme.SurfaceDeep
import io.gomob.designsystem.theme.TextPrimary
import io.gomob.designsystem.theme.TextSecondary
import io.gomob.designsystem.theme.TextTertiary

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
        onRemember = vm::setRemember,
        onSubmit = vm::submit,
        onGoRegister = onGoRegister,
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
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDeep)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // 顶部装饰
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .background(
                    Brush.linearGradient(
                        listOf(
                            PrimaryDim.copy(alpha = 0.4f),
                            AccentDim.copy(alpha = 0.4f),
                            SurfaceDeep,
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(64.dp))
            Text(text = "你好！", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(4.dp))
            Text(text = "欢迎登录 gomob", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "机动车检测站查验员工作台",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )

            Spacer(Modifier.height(48.dp))

            TechTextField(
                value = state.username,
                onValueChange = onUsername,
                placeholder = "请输入账号",
                leading = { Icon(Icons.Filled.Person, null, tint = TextTertiary) },
            )
            Spacer(Modifier.height(12.dp))
            TechTextField(
                value = state.password,
                onValueChange = onPassword,
                placeholder = "请输入密码",
                leading = { Icon(Icons.Filled.Lock, null, tint = TextTertiary) },
                isPassword = true,
                isError = state.errorMessage != null,
            )

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(if (state.rememberMe) Primary else SurfaceCard)
                        .clickable { onRemember(!state.rememberMe) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.rememberMe) {
                        Text("✓", color = SurfaceDeep, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "记住账号",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.clickable { onRemember(!state.rememberMe) },
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "没账号? 去注册",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Primary,
                    modifier = Modifier.clickable { onGoRegister() },
                )
            }

            if (state.errorMessage != null) {
                Spacer(Modifier.height(12.dp))
                GlassCard(
                    borderColor = StateDanger.copy(alpha = 0.5f),
                    background = Brush.horizontalGradient(
                        listOf(
                            StateDanger.copy(alpha = 0.18f),
                            SurfaceCard,
                        ),
                    ),
                ) {
                    Text(
                        text = state.errorMessage,
                        modifier = Modifier.padding(14.dp),
                        color = StateDanger,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
            if (state.loading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brush.horizontalGradient(listOf(Primary, Accent))),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = TextPrimary,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                }
            } else {
                PrimaryButton(text = "登 录", onClick = onSubmit)
            }

            Spacer(Modifier.height(48.dp))
            Text(
                text = "服务端 10.0.2.2:8808 (DEV)",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
