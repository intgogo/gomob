package io.gomob.scan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.feature.auth.AuthGateViewModel
import io.gomob.feature.auth.LoginRoute
import io.gomob.feature.auth.RegisterRoute
import io.gomob.scan.R
import io.gomob.scan.feedback.AppFeedbackHost
import io.gomob.scan.navigation.GomobNavHost

/**
 * Auth gate:
 * - 未登录 → 登录页 (可切到注册页)
 * - 已登录 → 5 tab 主 Shell
 */
@Composable
fun AppRoot(
    onContentReadinessChanged: (Boolean) -> Unit = {},
    debugRouteRequest: String? = null,
    onDebugRouteConsumed: () -> Unit = {},
    onSystemBarsPaddingRequiredChanged: (Boolean) -> Unit = {},
    vm: AuthGateViewModel = hiltViewModel(),
) {
    val loggedIn by vm.isLoggedIn.collectAsStateWithLifecycle(initialValue = null)
    val sessionNotice by vm.sessionNotice.collectAsStateWithLifecycle(initialValue = null)
    val warmup: AppWarmupViewModel = hiltViewModel()
    val readyForShell by warmup.readyForShell.collectAsStateWithLifecycle()
    val currentOnContentReadinessChanged by rememberUpdatedState(onContentReadinessChanged)
    val currentOnSystemBarsPaddingRequiredChanged by rememberUpdatedState(onSystemBarsPaddingRequiredChanged)
    var registerMode by rememberSaveable { mutableStateOf(false) }
    val contentReady = loggedIn == false || (loggedIn == true && readyForShell)
    LaunchedEffect(contentReady) {
        currentOnContentReadinessChanged(contentReady)
    }
    LaunchedEffect(loggedIn, readyForShell) {
        if (loggedIn != true || !readyForShell) {
            currentOnSystemBarsPaddingRequiredChanged(true)
        }
    }
    LaunchedEffect(sessionNotice) {
        if (!sessionNotice.isNullOrBlank()) {
            registerMode = false
        }
    }
    LaunchedEffect(loggedIn) {
        if (loggedIn == false) {
            warmup.resetForLoggedOut()
        }
    }
    when (loggedIn) {
        null -> SplashLoading()
        false -> if (registerMode) {
            RegisterRoute(
                onBack = { registerMode = false },
                onRegistered = { registerMode = false },
            )
        } else {
            LoginRoute(
                onLoggedIn = { /* isLoggedIn flow 自动重组 */ },
                onGoRegister = { registerMode = true },
                sessionNotice = sessionNotice,
                onSessionNoticeShown = vm::clearSessionNotice,
            )
        }
        true -> {
            LaunchedEffect(Unit) {
                warmup.prepareForShell()
            }
            if (readyForShell) {
                AppFeedbackHost {
                    GomobNavHost(
                        debugRouteRequest = debugRouteRequest,
                        onDebugRouteConsumed = onDebugRouteConsumed,
                        onSystemBarsPaddingRequiredChanged = onSystemBarsPaddingRequiredChanged,
                    )
                }
            } else {
                SplashLoading()
            }
        }
    }
}

@Composable
internal fun SplashLoading() {
    val appName = stringResource(R.string.app_name)
    val slogan = stringResource(R.string.splash_slogan)
    val capabilities = stringResource(R.string.splash_capabilities)
    val loading = stringResource(R.string.splash_loading)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.splash_background)),
    ) {
        SplashSceneBackground(Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .padding(top = 68.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = appName,
                color = Color(0xFFF8FCFF),
                fontSize = 33.sp,
                lineHeight = 39.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = slogan,
                color = Color(0xCCB8E7F7),
                fontSize = 15.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }
        Image(
            painter = painterResource(R.drawable.ic_splash_mark_system),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-34).dp)
                .size(154.dp),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp)
                .padding(bottom = 46.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = capabilities,
                color = Color(0xB3E0F2FE),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
            Box(Modifier.height(15.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF2DD4BF),
                    trackColor = Color.White.copy(alpha = 0.12f),
                )
                Text(
                    text = loading,
                    color = Color(0x992DD4BF),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SplashSceneBackground(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF04111C),
                    Color(0xFF061927),
                    Color(0xFF04111C),
                ),
            ),
            size = size,
        )
        val upperBeam = Path().apply {
            moveTo(w * 0.58f, 0f)
            lineTo(w, 0f)
            lineTo(w, h * 0.47f)
            lineTo(w * 0.72f, h * 0.52f)
            close()
        }
        drawPath(upperBeam, Color(0x0E38BDF8))
        val lowerBeam = Path().apply {
            moveTo(0f, h * 0.74f)
            lineTo(w * 0.34f, h * 0.66f)
            lineTo(w * 0.56f, h)
            lineTo(0f, h)
            close()
        }
        drawPath(lowerBeam, Color(0x0B2DD4BF))

        val accent = Color(0xFF22D3EE)
        val mutedLine = Color(0x24E0F2FE)
        val bayTop = h * 0.47f
        val bayLeft = w * 0.14f
        val bayWidth = w * 0.72f
        val bayHeight = h * 0.22f
        drawRoundRect(
            color = Color(0x102DD4BF),
            topLeft = Offset(bayLeft, bayTop),
            size = Size(bayWidth, bayHeight),
            cornerRadius = CornerRadius(20.dp.toPx()),
        )
        drawRoundRect(
            color = Color(0x3838BDF8),
            topLeft = Offset(bayLeft, bayTop),
            size = Size(bayWidth, bayHeight),
            cornerRadius = CornerRadius(20.dp.toPx()),
            style = Stroke(width = 1.2.dp.toPx()),
        )
        drawLine(
            color = Color(0x662DD4BF),
            start = Offset(bayLeft + 18.dp.toPx(), bayTop + 26.dp.toPx()),
            end = Offset(bayLeft + bayWidth - 18.dp.toPx(), bayTop + 26.dp.toPx()),
            strokeWidth = 1.8.dp.toPx(),
            cap = StrokeCap.Round,
        )

        val lane = Path().apply {
            moveTo(w * 0.34f, h * 0.58f)
            lineTo(w * 0.66f, h * 0.58f)
            lineTo(w * 0.78f, h * 0.82f)
            lineTo(w * 0.22f, h * 0.82f)
            close()
        }
        drawPath(lane, Color(0x0EE0F2FE))
        drawPath(lane, Color(0x2438BDF8), style = Stroke(width = 1.1.dp.toPx()))

        repeat(5) { index ->
            val y = h * (0.62f + index * 0.04f)
            drawLine(mutedLine, Offset(w * 0.13f, y), Offset(w * 0.87f, y), strokeWidth = 1.dp.toPx())
        }
        listOf(0.36f, 0.45f, 0.55f, 0.64f).forEach { x ->
            drawLine(mutedLine, Offset(w * x, h * 0.58f), Offset(w * 0.5f, h * 0.82f), strokeWidth = 1.dp.toPx())
        }

        val postTop = h * 0.55f
        val postHeight = h * 0.12f
        listOf(w * 0.18f, w * 0.78f).forEach { x ->
            drawRoundRect(
                color = Color(0x1A38BDF8),
                topLeft = Offset(x, postTop),
                size = Size(w * 0.055f, postHeight),
                cornerRadius = CornerRadius(8.dp.toPx()),
            )
            drawRoundRect(
                color = Color(0x8022D3EE),
                topLeft = Offset(x + w * 0.012f, postTop + h * 0.018f),
                size = Size(w * 0.032f, h * 0.009f),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
        }
        drawLine(Color(0x5522D3EE), Offset(w * 0.22f, postTop + h * 0.06f), Offset(w * 0.50f, h * 0.53f), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Color(0x5522D3EE), Offset(w * 0.78f, postTop + h * 0.06f), Offset(w * 0.50f, h * 0.53f), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)

        val frameLeft = w * 0.29f
        val frameRight = w * 0.71f
        val frameTop = h * 0.39f
        val frameBottom = h * 0.56f
        val corner = 22.dp.toPx()
        val frameStroke = 2.dp.toPx()
        drawLine(accent, Offset(frameLeft, frameTop), Offset(frameLeft + corner, frameTop), strokeWidth = frameStroke, cap = StrokeCap.Round)
        drawLine(accent, Offset(frameLeft, frameTop), Offset(frameLeft, frameTop + corner), strokeWidth = frameStroke, cap = StrokeCap.Round)
        drawLine(accent, Offset(frameRight, frameTop), Offset(frameRight - corner, frameTop), strokeWidth = frameStroke, cap = StrokeCap.Round)
        drawLine(accent, Offset(frameRight, frameTop), Offset(frameRight, frameTop + corner), strokeWidth = frameStroke, cap = StrokeCap.Round)
        drawLine(accent, Offset(frameLeft, frameBottom), Offset(frameLeft + corner, frameBottom), strokeWidth = frameStroke, cap = StrokeCap.Round)
        drawLine(accent, Offset(frameLeft, frameBottom), Offset(frameLeft, frameBottom - corner), strokeWidth = frameStroke, cap = StrokeCap.Round)
        drawLine(accent, Offset(frameRight, frameBottom), Offset(frameRight - corner, frameBottom), strokeWidth = frameStroke, cap = StrokeCap.Round)
        drawLine(accent, Offset(frameRight, frameBottom), Offset(frameRight, frameBottom - corner), strokeWidth = frameStroke, cap = StrokeCap.Round)

        repeat(9) { index ->
            val column = index % 3
            val row = index / 3
            drawCircle(
                color = Color(0x552DD4BF),
                radius = (1.4f + row * 0.35f).dp.toPx(),
                center = Offset(w * (0.36f + column * 0.14f), h * (0.70f + row * 0.035f)),
            )
        }
    }
}
