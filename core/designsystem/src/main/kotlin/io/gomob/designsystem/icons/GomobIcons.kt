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
