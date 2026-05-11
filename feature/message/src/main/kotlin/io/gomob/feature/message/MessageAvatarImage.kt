package io.gomob.feature.message

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.gomob.designsystem.theme.Gomob

@Composable
internal fun MessageAvatarImage(
    seed: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    shape: Shape = Gomob.shapes.r2,
    online: Boolean? = null,
) {
    val palette = avatarPalette(seed)
    Box(
        modifier
            .size(size)
            .semantics { contentDescription = "头像" },
        contentAlignment = Alignment.BottomEnd,
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(palette.bottom),
        ) {
            val w = this.size.width
            val h = this.size.height
            val m = minOf(w, h)
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(palette.top, palette.bottom),
                    start = Offset(0f, 0f),
                    end = Offset(w, h),
                ),
            )
            drawCircle(
                color = palette.light.copy(alpha = 0.38f),
                radius = m * 0.42f,
                center = Offset(w * 0.78f, h * 0.2f),
            )
            drawCircle(
                color = palette.shade.copy(alpha = 0.28f),
                radius = m * 0.36f,
                center = Offset(w * 0.14f, h * 0.88f),
            )
            drawOval(
                color = palette.cloth,
                topLeft = Offset(w * 0.18f, h * 0.62f),
                size = Size(w * 0.64f, h * 0.48f),
            )
            drawCircle(
                color = palette.face,
                radius = m * 0.19f,
                center = Offset(w * 0.5f, h * 0.42f),
            )
            drawCircle(
                color = palette.hair,
                radius = m * 0.22f,
                center = Offset(w * 0.5f, h * 0.34f),
            )
            drawCircle(
                color = palette.face,
                radius = m * 0.18f,
                center = Offset(w * 0.5f, h * 0.43f),
            )
        }
        online?.let { isOnline ->
            Box(
                Modifier
                    .size(size * 0.24f)
                    .clip(CircleShape)
                    .background(if (isOnline) Gomob.colors.ok else Gomob.colors.fg3.copy(alpha = 0.62f)),
            )
        }
    }
}

private data class AvatarPalette(
    val top: Color,
    val bottom: Color,
    val light: Color,
    val shade: Color,
    val face: Color,
    val hair: Color,
    val cloth: Color,
)

private fun avatarPalette(seed: String): AvatarPalette {
    val index = Math.floorMod(seed.hashCode(), avatarPalettes.size)
    return avatarPalettes[index]
}

private val avatarPalettes = listOf(
    AvatarPalette(Color(0xFF254F6D), Color(0xFF132736), Color(0xFF74D2F2), Color(0xFF071018), Color(0xFFE6B69B), Color(0xFF27364A), Color(0xFF65C6E4)),
    AvatarPalette(Color(0xFF4A394F), Color(0xFF1A1826), Color(0xFFF0A6CA), Color(0xFF0B0B12), Color(0xFFD8A88C), Color(0xFF20151A), Color(0xFFE58FB5)),
    AvatarPalette(Color(0xFF314C3D), Color(0xFF111C18), Color(0xFF9FE0C5), Color(0xFF06100D), Color(0xFFEAC3A4), Color(0xFF2E251C), Color(0xFF6BCCB4)),
    AvatarPalette(Color(0xFF594629), Color(0xFF1E1710), Color(0xFFF6D07C), Color(0xFF0D0905), Color(0xFFCB9276), Color(0xFF19110D), Color(0xFFF2B95A)),
    AvatarPalette(Color(0xFF3D426A), Color(0xFF141725), Color(0xFFAAB8FF), Color(0xFF080A12), Color(0xFFE3B190), Color(0xFF222941), Color(0xFF8EA3FF)),
    AvatarPalette(Color(0xFF653D3D), Color(0xFF211313), Color(0xFFFFAAA4), Color(0xFF0C0707), Color(0xFFB97D65), Color(0xFF281711), Color(0xFFF97676)),
)
