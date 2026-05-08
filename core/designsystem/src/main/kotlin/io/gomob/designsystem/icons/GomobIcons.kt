package io.gomob.designsystem.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * jsx icons.jsx 的 Compose 翻译。
 *
 * 视觉：1.5 stroke / 24×24 viewBox / 圆头线帽 + 圆头连接 / currentColor。
 * 风格是工业蓝图 hairline，不要换 Material 的实心填充图标 — 体感会糙。
 *
 * 用法：`Icon(GomobIcons.User, contentDescription = "账号", tint = Gomob.colors.fg2)`
 *
 * 不一次性翻译全部 32 个 — 按 commit 推进时增量补；当前覆盖 Commit 2 (Login) 所需。
 */
object GomobIcons {

    /** mob3d 立方体 Logo — 16×16 viewport (jsx Logo 组件内嵌) */
    val Logo: ImageVector = stroked16("Logo") {
        // 立方体外轮廓
        moveTo(8f, 1.5f)
        lineTo(14f, 4.5f)
        verticalLineTo(11.5f)
        lineTo(8f, 14.5f)
        lineTo(2f, 11.5f)
        verticalLineTo(4.5f)
        close()
        // 顶面 Y 形：左侧 → 中心 → 右侧
        moveTo(2f, 4.5f)
        lineTo(8f, 7.5f)
        lineTo(14f, 4.5f)
        // 中央竖线
        moveTo(8f, 7.5f)
        verticalLineTo(14.5f)
    }

    val User: ImageVector = stroked("User") {
        circle(12f, 8.5f, 3.5f)
        // 肩弧：M5 19 c 1.4 -3 4 -4.5 7 -4.5 s 5.6 1.5 7 4.5
        moveTo(5f, 19f)
        curveToRelative(1.4f, -3f, 4f, -4.5f, 7f, -4.5f)
        reflectiveCurveToRelative(5.6f, 1.5f, 7f, 4.5f)
    }

    val Lock: ImageVector = stroked("Lock") {
        roundRect(5f, 10.5f, 14f, 9f, 1.5f)
        // 锁梁
        moveTo(8f, 10.5f)
        verticalLineTo(7f)
        arcToRelative(4f, 4f, 0f, true, true, 8f, 0f)
        verticalLineTo(10.5f)
    }

    val Eye: ImageVector = stroked("Eye") {
        moveTo(2.5f, 12f)
        reflectiveCurveToRelative(3.5f, -6.5f, 9.5f, -6.5f)
        reflectiveCurveTo(21.5f, 12f, 21.5f, 12f)
        reflectiveCurveTo(18f, 18.5f, 12f, 18.5f)
        reflectiveCurveTo(2.5f, 12f, 2.5f, 12f)
        close()
        circle(12f, 12f, 2.8f)
    }

    val Check: ImageVector = stroked("Check") {
        moveTo(4.5f, 12.5f)
        lineTo(10f, 18f)
        lineTo(20f, 7f)
    }

    val ArrowRight: ImageVector = stroked("ArrowRight") {
        moveTo(5f, 12f)
        horizontalLineToRelative(14f)
        moveTo(13f, 6f)
        lineToRelative(6f, 6f)
        lineToRelative(-6f, 6f)
    }

    val ChevronRight: ImageVector = stroked("ChevronRight") {
        moveTo(9f, 5f)
        lineTo(16f, 12f)
        lineTo(9f, 19f)
    }

    val ChevronLeft: ImageVector = stroked("ChevronLeft") {
        moveTo(15f, 5f)
        lineTo(8f, 12f)
        lineTo(15f, 19f)
    }

    val History: ImageVector = stroked("History") {
        // 圆弧 + 缺口（jsx history 图标）
        moveTo(3.5f, 12f)
        arcToRelative(8.5f, 8.5f, 0f, true, false, 2.6f, -6.1f)
        // 时针 hand
        moveTo(3.5f, 3f)
        verticalLineTo(7f)
        horizontalLineTo(7.5f)
        moveTo(12f, 7.5f)
        verticalLineTo(12f)
        lineTo(15.5f, 14f)
    }

    val Plus: ImageVector = stroked("Plus") {
        moveTo(12f, 5f)
        verticalLineTo(19f)
        moveTo(5f, 12f)
        horizontalLineTo(19f)
    }

    val Mic: ImageVector = stroked("Mic") {
        // 麦头矩形圆角
        roundRect(9f, 3f, 6f, 11f, 3f)
        // 弧
        moveTo(5f, 11f)
        arcToRelative(7f, 7f, 0f, false, false, 14f, 0f)
        // 麦杆
        moveTo(12f, 18f)
        verticalLineTo(21f)
    }

    val Send: ImageVector = stroked("Send") {
        // jsx Send 是 paper plane 形：M4 20 l16 -8 L4 4 l3 8 -3 8z + M7 12h13
        moveTo(4f, 20f)
        lineTo(20f, 12f)
        lineTo(4f, 4f)
        lineTo(7f, 12f)
        lineTo(4f, 20f)
        close()
        moveTo(7f, 12f)
        horizontalLineTo(20f)
    }

    val Search: ImageVector = stroked("Search") {
        circle(11f, 11f, 6.5f)
        moveTo(16f, 16f)
        lineTo(20f, 20f)
    }

    val Compose: ImageVector = stroked("Compose") {
        moveTo(4f, 20f)
        horizontalLineTo(8f)
        lineTo(19f, 9f)
        lineTo(15f, 5f)
        lineTo(4f, 16f)
        close()
        moveTo(14f, 6f)
        lineTo(18f, 10f)
    }

    /** 立方体（与 Logo 形状同 — 但 24×24 viewport 用于 ActionTile 等）。 */
    val Cube: ImageVector = stroked("Cube") {
        moveTo(12f, 3f)
        lineTo(20f, 7.5f)
        verticalLineTo(16.5f)
        lineTo(12f, 21f)
        lineTo(4f, 16.5f)
        verticalLineTo(7.5f)
        close()
        moveTo(4f, 7.5f)
        lineTo(12f, 12f)
        lineTo(20f, 7.5f)
        moveTo(12f, 12f)
        verticalLineTo(21f)
    }

    val USB: ImageVector = stroked("USB") {
        // USB 接头形 — 顶部圆点 + 主线 + V 分叉 + 内圈点 / 矩形
        circle(12f, 4f, 1.5f)
        moveTo(12f, 5.5f)
        verticalLineTo(20f)
        moveTo(8f, 11f)
        lineTo(12f, 7f)
        lineTo(16f, 11f)
        circle(9f, 14f, 1f)
        moveTo(9f, 15f)
        verticalLineTo(17f)
        lineTo(12f, 19f)
        roundRect(14f, 11f, 2.5f, 3f, 0.3f)
        moveTo(15.25f, 14f)
        verticalLineTo(16f)
        lineTo(12f, 18f)
    }

    val Stamp: ImageVector = stroked("Stamp") {
        roundRect(3.5f, 6f, 17f, 12f, 1.5f)
        moveTo(3.5f, 10f)
        horizontalLineTo(20.5f)
        moveTo(7f, 14f)
        horizontalLineTo(9.5f)
        moveTo(11.5f, 14f)
        horizontalLineTo(16.5f)
        moveTo(7f, 17f)
        verticalLineTo(18.5f)
        moveTo(9f, 17f)
        verticalLineTo(18.5f)
        moveTo(11f, 17f)
        verticalLineTo(18.5f)
    }

    val Calibrate: ImageVector = stroked("Calibrate") {
        circle(12f, 12f, 8.5f)
        moveTo(12f, 4f)
        verticalLineTo(7f)
        moveTo(12f, 17f)
        verticalLineTo(20f)
        moveTo(4f, 12f)
        horizontalLineTo(7f)
        moveTo(17f, 12f)
        horizontalLineTo(20f)
        circle(12f, 12f, 2.5f)
    }

    val Eyeball: ImageVector = stroked("Eyeball") {
        // 椭圆 9×6 + 内圆 r=2.5 (jsx Eyeball)
        moveTo(3f, 12f)
        arcToRelative(9f, 6f, 0f, true, true, 18f, 0f)
        arcToRelative(9f, 6f, 0f, true, true, -18f, 0f)
        close()
        circle(12f, 12f, 2.5f)
    }

    val Refresh: ImageVector = stroked("Refresh") {
        moveTo(4f, 12f)
        arcToRelative(8f, 8f, 0f, false, true, 14f, -5.3f)
        moveTo(18f, 3f)
        verticalLineTo(7f)
        horizontalLineTo(14f)
        moveTo(20f, 12f)
        arcToRelative(8f, 8f, 0f, false, true, -14f, 5.3f)
        moveTo(6f, 21f)
        verticalLineTo(17f)
        horizontalLineTo(10f)
    }

    val Settings: ImageVector = stroked("Settings") {
        // 齿轮：内圆(轴孔 r=3) + 外圈(r=8) + 8 个外凸齿(r=8 → r=9.7)。
        // 用外圈 + 短齿组合直接表达"齿轮"几何 — 比纯八光线的 Lucide settings 更像传统齿轮。
        circle(12f, 12f, 3f)
        circle(12f, 12f, 8f)
        // 4 个正交齿(上/下/左/右)
        moveTo(12f, 4f); verticalLineTo(2.3f)
        moveTo(12f, 20f); verticalLineTo(21.7f)
        moveTo(4f, 12f); horizontalLineTo(2.3f)
        moveTo(20f, 12f); horizontalLineTo(21.7f)
        // 4 个对角齿(45°)，齿端坐标 = 12 ± 9.7/√2 ≈ 12 ± 6.86 → 5.14 / 18.86
        // 起点(齿根)在外圈上 = 12 ± 8/√2 ≈ 12 ± 5.66 → 6.34 / 17.66
        moveTo(6.34f, 6.34f); lineTo(5.14f, 5.14f)
        moveTo(17.66f, 17.66f); lineTo(18.86f, 18.86f)
        moveTo(6.34f, 17.66f); lineTo(5.14f, 18.86f)
        moveTo(17.66f, 6.34f); lineTo(18.86f, 5.14f)
    }

    val ID: ImageVector = stroked("ID") {
        roundRect(3.5f, 6f, 17f, 12f, 1.5f)
        circle(9f, 11f, 1.8f)
        moveTo(6f, 15.5f); curveToRelative(0.6f, -1.4f, 1.8f, -2f, 3f, -2f)
        reflectiveCurveToRelative(2.4f, 0.6f, 3f, 2f)
        moveTo(14f, 10f); horizontalLineTo(18f)
        moveTo(14f, 13f); horizontalLineTo(17f)
    }

    val Cache: ImageVector = stroked("Cache") {
        // 简化数据库圆柱
        moveTo(5f, 6f); arcToRelative(7f, 2.5f, 0f, true, true, 14f, 0f)
        arcToRelative(7f, 2.5f, 0f, true, true, -14f, 0f)
        moveTo(5f, 6f); verticalLineTo(12f)
        curveToRelative(0f, 1.4f, 3.1f, 2.5f, 7f, 2.5f)
        reflectiveCurveToRelative(7f, -1.1f, 7f, -2.5f)
        verticalLineTo(6f)
        moveTo(5f, 12f); verticalLineTo(18f)
        curveToRelative(0f, 1.4f, 3.1f, 2.5f, 7f, 2.5f)
        reflectiveCurveToRelative(7f, -1.1f, 7f, -2.5f)
        verticalLineTo(12f)
    }

    val Wifi: ImageVector = stroked("Wifi") {
        moveTo(3.5f, 9f); arcToRelative(14f, 14f, 0f, false, true, 17f, 0f)
        moveTo(6.5f, 12.5f); arcToRelative(10f, 10f, 0f, false, true, 11f, 0f)
        moveTo(9.5f, 16f); arcToRelative(6f, 6f, 0f, false, true, 5f, 0f)
        moveTo(12f, 19.5f); arcToRelative(0.5f, 0.5f, 0f, false, true, 0.01f, 0f)
    }

    val Bell: ImageVector = stroked("Bell") {
        moveTo(6f, 9f); arcToRelative(6f, 6f, 0f, false, true, 12f, 0f)
        curveToRelative(0f, 4f, 1.5f, 5.5f, 2f, 6.5f)
        horizontalLineTo(4f)
        curveToRelative(0.5f, -1f, 2f, -2.5f, 2f, -6.5f)
        close()
        moveTo(10f, 18.5f); arcToRelative(2f, 2f, 0f, false, false, 4f, 0f)
    }

    val Info: ImageVector = stroked("Info") {
        circle(12f, 12f, 8.5f)
        moveTo(12f, 11f); verticalLineTo(17f)
        moveTo(12f, 8f); arcToRelative(0.1f, 0.1f, 0f, false, true, 0.01f, 0f)
    }

    val Moon: ImageVector = stroked("Moon") {
        // 弯月 — Lucide moon path。外圆 r=9 + 内圆 r=7 切除右半边，得到月牙轮廓。
        moveTo(21f, 12.79f)
        arcToRelative(9f, 9f, 0f, true, true, -9.79f, -9.79f)
        arcToRelative(7f, 7f, 0f, false, false, 9.79f, 9.79f)
        close()
    }

    val ArrowSwap: ImageVector = stroked("ArrowSwap") {
        moveTo(3f, 8f); horizontalLineTo(17f)
        lineToRelative(-3f, -3f)
        moveTo(21f, 16f); horizontalLineTo(7f)
        lineToRelative(3f, 3f)
    }

    val Filter: ImageVector = stroked("Filter") {
        moveTo(3.5f, 5f); horizontalLineTo(20.5f)
        lineToRelative(-6.5f, 8f)
        verticalLineTo(19f)
        lineToRelative(-4f, -2f)
        verticalLineTo(13f)
        close()
    }
}

// ─── helpers ────────────────────────────────────────────────────────────────

private fun stroked(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = "GomobIcons.$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(
        fill = null,
        stroke = SolidColor(Color.Black),       // tint 覆盖
        strokeLineWidth = 1.5f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    ).build()

private fun stroked16(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = "GomobIcons.$name",
        defaultWidth = 16.dp,
        defaultHeight = 16.dp,
        viewportWidth = 16f,
        viewportHeight = 16f,
    ).path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.5f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    ).build()

// 圆形 path（CircleShape 不能用 stroke，所以自画）
private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcToRelative(r, r, 0f, false, true, 2 * r, 0f)
    arcToRelative(r, r, 0f, false, true, -2 * r, 0f)
    close()
}

private fun PathBuilder.roundRect(x: Float, y: Float, w: Float, h: Float, rx: Float) {
    moveTo(x + rx, y)
    horizontalLineTo(x + w - rx)
    arcToRelative(rx, rx, 0f, false, true, rx, rx)
    verticalLineTo(y + h - rx)
    arcToRelative(rx, rx, 0f, false, true, -rx, rx)
    horizontalLineTo(x + rx)
    arcToRelative(rx, rx, 0f, false, true, -rx, -rx)
    verticalLineTo(y + rx)
    arcToRelative(rx, rx, 0f, false, true, rx, -rx)
    close()
}
