package io.gomob.feature.profile

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.component.Chip
import io.gomob.designsystem.component.HairlineCard
import io.gomob.designsystem.component.SegmentedTabItem
import io.gomob.designsystem.component.SegmentedTabs
import io.gomob.designsystem.component.SettingRow
import io.gomob.designsystem.component.SettingRowDivider
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob
import io.gomob.model.user.UserProfile

// ============================================================================
// 个人信息
// ============================================================================
@Composable
fun ProfilePersonalRoute(
    onBack: () -> Unit,
    initialTab: Int = 0,
    vm: ProfileViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val p = state.profile
    var tab by rememberSaveable(initialTab) { mutableStateOf(initialTab.coerceIn(0, 1)) }
    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(title = "我的资料", onBack = onBack, eyebrow = "PROFILE · EDIT")
        SegmentedTabs(
            items = listOf(SegmentedTabItem("基本资料"), SegmentedTabItem("我的案例")),
            selectedIndex = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = Gomob.spacing.s20, vertical = Gomob.spacing.s6),
        )
        when (tab) {
            0 -> ProfileBasicPane(profile = p)
            else -> ProfileCasePane()
        }
    }
}

@Composable
private fun ProfileBasicPane(profile: UserProfile?) {
    var name by rememberSaveable(profile?.realName) { mutableStateOf(profile?.realName ?: "沈海明") }
    var role by rememberSaveable(profile?.roleLabel) { mutableStateOf(profile?.roleLabel ?: "查验员") }
    var contact by rememberSaveable(profile?.id) { mutableStateOf("13586**4421") }
    var bio by rememberSaveable(profile?.id) {
        mutableStateOf("专注于二手车残值核定与 VIN 拓印复检。\n累计预审 1.2 万台，复核异常案例 320+ 份。")
    }
    val employeeId = profile?.employeeId ?: "ZAA0120230001"
    val station = profile?.stationName ?: "杭州市西湖区 · 车管所检测站"

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Gomob.spacing.s20),
        contentPadding = PaddingValues(top = Gomob.spacing.s8, bottom = Gomob.spacing.s24),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
    ) {
        item { ProfileAvatarCard(name = name, onChange = {}) }
        item { ProfileInputField(label = "姓名", value = name, onChange = { name = it }) }
        item { ProfileInputField(label = "职务", value = role, onChange = { role = it }) }
        item {
            ProfileInputField(
                label = "工号",
                value = employeeId,
                onChange = {},
                readOnly = true,
                mono = true,
                trailing = "READ ONLY",
                trailingIcon = GomobIcons.Lock,
            )
        }
        item {
            ProfileSelectField(
                label = "所属检测站",
                value = station,
                onClick = {},
            )
        }
        item { ProfileInputField(label = "联系方式", value = contact, onChange = { contact = it }, mono = true) }
        item {
            ProfileInputField(
                label = "个人简介 · 专长",
                value = bio,
                onChange = { bio = it },
                singleLine = false,
                minHeight = 72.dp,
            )
        }
    }
}

@Composable
private fun ProfileAvatarCard(name: String, onChange: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
            .padding(Gomob.spacing.s16),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s14),
    ) {
        Box(
            Modifier
                .size(60.dp)
                .clip(Gomob.shapes.r2)
                .background(Gomob.colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.take(1).ifBlank { "?" },
                fontSize = 28.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.04.em,
                color = Gomob.colors.accentStrong,
            )
        }
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
        ) {
            Text("头像", style = Gomob.type.body, color = Gomob.colors.fg0)
            Text("JPG / PNG · <= 2 MB", style = Gomob.type.eyebrow, color = Gomob.colors.fg3)
        }
        Box(
            Modifier
                .height(32.dp)
                .clip(Gomob.shapes.r1)
                .background(Gomob.colors.bg2)
                .clickable(onClick = onChange)
                .padding(horizontal = Gomob.spacing.s14),
            contentAlignment = Alignment.Center,
        ) {
            Text("更换", style = Gomob.type.bodySm, color = Gomob.colors.fg1)
        }
    }
}

@Composable
private fun ProfileInputField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    readOnly: Boolean = false,
    mono: Boolean = false,
    singleLine: Boolean = true,
    minHeight: androidx.compose.ui.unit.Dp = Gomob.spacing.touchMin,
    trailing: String? = null,
    trailingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s6)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = Gomob.type.bodySm, color = Gomob.colors.fg2)
            if (trailing != null) {
                Text(trailing, style = Gomob.type.eyebrow, color = Gomob.colors.fg3)
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(minHeight)
                .clip(Gomob.shapes.r2)
                .background(Gomob.colors.bg2)
                .padding(horizontal = Gomob.spacing.s12, vertical = if (singleLine) 0.dp else Gomob.spacing.s8),
            contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    readOnly = readOnly,
                    singleLine = singleLine,
                    textStyle = (if (mono) Gomob.type.numInline else Gomob.type.body).copy(
                        color = if (readOnly) Gomob.colors.fg2 else Gomob.colors.fg0,
                    ),
                    cursorBrush = SolidColor(Gomob.colors.accent),
                    modifier = Modifier.weight(1f),
                )
                if (trailingIcon != null) {
                    Icon(
                        imageVector = trailingIcon,
                        contentDescription = null,
                        tint = Gomob.colors.fg3,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileSelectField(label: String, value: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s6)) {
        Text(label, style = Gomob.type.bodySm, color = Gomob.colors.fg2)
        Row(
            Modifier
                .fillMaxWidth()
                .height(Gomob.spacing.touchMin)
                .clip(Gomob.shapes.r2)
                .background(Gomob.colors.bg2)
                .clickable(onClick = onClick)
                .padding(horizontal = Gomob.spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
        ) {
            Text(
                value,
                style = Gomob.type.body,
                color = Gomob.colors.fg0,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = GomobIcons.ChevronRight,
                contentDescription = null,
                tint = Gomob.colors.fg3,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

private enum class ProfileCaseStatus { Published, Reviewing, Draft }

private data class ProfileCase(
    val id: String,
    val status: ProfileCaseStatus,
    val thumb: String,
    val title: String,
    val vin: String,
    val vehicle: String,
    val tag: String,
    val views: Int,
    val shares: Int,
    val pendingText: String? = null,
)

private val PROFILE_CASES = listOf(
    ProfileCase(
        id = "C-0247",
        status = ProfileCaseStatus.Published,
        thumb = "VIN",
        title = "VIN 字符压印模糊 · 同字符垂压复核",
        vin = "LSVHM41182123456",
        vehicle = "大众桑塔纳 · 沪A57Y0",
        tag = "异常案例",
        views = 1284,
        shares = 96,
    ),
    ProfileCase(
        id = "C-0245",
        status = ProfileCaseStatus.Published,
        thumb = "FRAME",
        title = "外廓尺寸偏差 23mm · 改装车判定流程",
        vin = "LSVHM133022221761",
        vehicle = "大众系列 · 沪A12345",
        tag = "疑难复核",
        views = 842,
        shares = 51,
    ),
    ProfileCase(
        id = "C-0241",
        status = ProfileCaseStatus.Reviewing,
        thumb = "OBD",
        title = "OBD 接口非原厂 · 二次焊接识别",
        vin = "LSVHM77129003344",
        vehicle = "比亚迪 · 浙B22T01",
        tag = "经验分享",
        views = 0,
        shares = 0,
        pendingText = "提交 03/09 · 待审",
    ),
    ProfileCase(
        id = "C-0238",
        status = ProfileCaseStatus.Published,
        thumb = "PLATE",
        title = "新能源车铭牌缺失 · 应急核验流程",
        vin = "LSVHM52017788321",
        vehicle = "本田系列 · 浙A91K20",
        tag = "异常案例",
        views = 693,
        shares = 44,
    ),
)

@Composable
private fun ProfileCasePane() {
    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Gomob.spacing.s20),
        contentPadding = PaddingValues(top = Gomob.spacing.s8, bottom = Gomob.spacing.s24),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        item { ProfileCaseStats() }
        item { ProfileCaseFilters() }
        items(PROFILE_CASES, key = { it.id }) { item ->
            ProfileCaseCard(item)
        }
    }
}

@Composable
private fun ProfileCaseStats() {
    Row(horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8)) {
        ProfileCaseStatTile("已公开", "04", Gomob.colors.accent, Modifier.weight(1f))
        ProfileCaseStatTile("审核中", "01", Gomob.colors.warn, Modifier.weight(1f))
        ProfileCaseStatTile("草稿", "01", Gomob.colors.fg1, Modifier.weight(1f))
    }
}

@Composable
private fun ProfileCaseStatTile(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg1)
            .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
    ) {
        Text(label, style = Gomob.type.caption, color = Gomob.colors.fg2)
        Text(
            value,
            style = Gomob.type.metricMd.copy(fontSize = 22.sp, lineHeight = 24.sp),
            color = color,
        )
    }
}

@Composable
private fun ProfileCaseFilters() {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
    ) {
        listOf("全部", "异常案例", "疑难复核", "经验分享").forEachIndexed { index, label ->
            Chip(label, selected = index == 0, onClick = {})
        }
        Spacer(Modifier.weight(1f))
        Text("最新⌄", style = Gomob.type.caption, color = Gomob.colors.fg3)
    }
}

@Composable
private fun ProfileCaseCard(item: ProfileCase) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
            .clickable {}
            .padding(Gomob.spacing.s12),
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
    ) {
        CaseThumb(label = item.thumb, modifier = Modifier.width(68.dp).aspectRatio(0.78f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s6)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfileCaseStatusPill(item.status)
                Text(item.id, style = Gomob.type.eyebrow, color = Gomob.colors.fg3)
            }
            Text(
                item.title,
                style = Gomob.type.bodySm.copy(fontWeight = FontWeight.Medium),
                color = Gomob.colors.fg0,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(item.vin, style = Gomob.type.numInline.copy(fontSize = 11.sp), color = Gomob.colors.fg2)
            Text(item.vehicle, style = Gomob.type.caption, color = Gomob.colors.fg3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.fillMaxWidth().height(Gomob.spacing.hairline).background(Gomob.colors.line1.copy(alpha = 0.5f)))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
            ) {
                ProfileCaseTag(item.tag)
                if (item.pendingText != null) {
                    Text(item.pendingText, style = Gomob.type.numInline.copy(fontSize = 10.sp), color = Gomob.colors.warn)
                } else {
                    CaseMeta(icon = GomobIcons.Eyeball, text = item.views.toString())
                    CaseMeta(icon = GomobIcons.LinkShare, text = item.shares.toString())
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(Gomob.shapes.r1)
                        .background(Gomob.colors.bg2)
                        .clickable {},
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(GomobIcons.Edit, contentDescription = "编辑案例", tint = Gomob.colors.fg2, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun CaseThumb(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(Gomob.shapes.r1)
            .background(Gomob.colors.bg2),
        contentAlignment = Alignment.Center,
    ) {
        val line = Gomob.colors.line2
        Canvas(Modifier.fillMaxSize()) {
            val step = 10.dp.toPx()
            var x = -size.height
            while (x < size.width) {
                drawLine(
                    color = line.copy(alpha = 0.45f),
                    start = Offset(x, size.height),
                    end = Offset(x + size.height, 0f),
                    strokeWidth = 1.dp.toPx(),
                )
                x += step
            }
        }
        Box(
            Modifier
                .clip(Gomob.shapes.r1)
                .background(Gomob.colors.bg0.copy(alpha = 0.76f))
                .padding(horizontal = Gomob.spacing.s8, vertical = Gomob.spacing.s4),
        ) {
            Text(label, style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
        }
    }
}

@Composable
private fun ProfileCaseStatusPill(status: ProfileCaseStatus) {
    val (text, tone) = when (status) {
        ProfileCaseStatus.Published -> "已公开" to StatusTone.Ok
        ProfileCaseStatus.Reviewing -> "审核中" to StatusTone.Warn
        ProfileCaseStatus.Draft -> "草稿" to StatusTone.Neutral
    }
    StatusTag(text = text, tone = tone, showDot = true)
}

@Composable
private fun ProfileCaseTag(text: String) {
    Box(
        Modifier
            .height(Gomob.spacing.chipHeight)
            .clip(Gomob.shapes.r1)
            .background(Gomob.colors.bg2)
            .padding(horizontal = Gomob.spacing.s8),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Gomob.type.caption, color = Gomob.colors.fg2)
    }
}

@Composable
private fun CaseMeta(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
        Icon(icon, contentDescription = null, tint = Gomob.colors.fg3, modifier = Modifier.size(12.dp))
        Text(text, style = Gomob.type.numInline.copy(fontSize = 10.sp), color = Gomob.colors.fg3)
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
        Box(
            Modifier
                .padding(start = Gomob.spacing.s12)
                .width(Gomob.spacing.switchW)
                .height(Gomob.spacing.switchH)
                .clip(Gomob.shapes.pill)
                .background(if (value) Gomob.colors.accentSoft else Gomob.colors.bg2),
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
