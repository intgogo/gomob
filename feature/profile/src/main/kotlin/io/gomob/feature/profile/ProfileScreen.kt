package io.gomob.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.HairlineCard
import io.gomob.designsystem.component.ScreenHeader
import io.gomob.designsystem.component.SettingRow
import io.gomob.designsystem.component.SettingRowDivider
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.theme.Gomob

const val PROFILE_ROUTE = "profile"

@Composable
fun ProfileRoute(
    onOpenPersonal: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onOpenNetwork: () -> Unit = {},
    onOpenNotification: () -> Unit = {},
    onOpenAppearance: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    vm: ProfileViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().background(Gomob.colors.bg0),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
    ) {
        ScreenHeader(
            title = "我的",
            eyebrow = "个人 · 当班",
            trailing = { StatusTag(text = "在岗", tone = StatusTone.Ok, showDot = true) },
        )

        Column(
            Modifier.padding(horizontal = Gomob.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        ) {
            HairlineCard {
                Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                    Text(
                        text = "工号 " + (state.profile?.employeeId ?: "—"),
                        style = Gomob.type.numInline,
                        color = Gomob.colors.fg2,
                    )
                    Text(
                        text = (state.profile?.realName ?: "加载中…") + " · " +
                            (state.profile?.roleLabel ?: ""),
                        style = Gomob.type.title,
                        color = Gomob.colors.fg0,
                    )
                    Text(
                        text = state.profile?.stationName ?: "未绑定检测站",
                        style = Gomob.type.caption,
                        color = Gomob.colors.fg3,
                    )
                }
            }

            HairlineCard {
                Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                    Text("当前班次", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
                    Text("已工作 5h35m / 9h00m", style = Gomob.type.metricMd, color = Gomob.colors.fg0)
                    Text(
                        text = "08:30 - 17:30 · 杭州市西湖区车管所检测站",
                        style = Gomob.type.caption,
                        color = Gomob.colors.fg3,
                    )
                }
            }

            HairlineCard(padding = 0.dp) {
                Column {
                    SettingRow(
                        title = "个人信息",
                        subtitle = "用户名 / 工号 / 检测站",
                        onClick = onOpenPersonal,
                    )
                    SettingRowDivider()
                    SettingRow(
                        title = "账号与安全",
                        subtitle = "修改密码 / 设备管理",
                        onClick = onOpenAccount,
                    )
                    SettingRowDivider()
                    SettingRow(
                        title = "网络设置",
                        subtitle = "网关 127.0.0.1:8808 (DEV)",
                        onClick = onOpenNetwork,
                    )
                    SettingRowDivider()
                    SettingRow(title = "通知", onClick = onOpenNotification)
                    SettingRowDivider()
                    SettingRow(
                        title = "外观",
                        subtitle = "深色 / 浅色 / 跟随系统",
                        onClick = onOpenAppearance,
                    )
                    SettingRowDivider()
                    SettingRow(title = "缓存清理", subtitle = "31.2 MB", onClick = {})
                    SettingRowDivider()
                    SettingRow(title = "关于 gomob", subtitle = "v0.1.0", onClick = onOpenAbout)
                    SettingRowDivider()
                    SettingRow(title = "退出登录", onClick = vm::logout)
                }
            }
        }
    }
}
