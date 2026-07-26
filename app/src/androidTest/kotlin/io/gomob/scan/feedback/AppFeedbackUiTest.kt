package io.gomob.scan.feedback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.gomob.designsystem.component.feedbackTitleFiveTap
import io.gomob.designsystem.theme.GomobTheme
import io.gomob.ui.feedback.FeedbackCaptureSurface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test

class AppFeedbackUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var bitmap: Bitmap? = null

    @After
    fun tearDown() {
        bitmap?.recycle()
        bitmap = null
    }

    @Test
    fun titleFiveTapsTriggerExactlyOnce() {
        var triggers by mutableIntStateOf(0)
        composeRule.setContent {
            Text(
                "首页",
                modifier = Modifier
                    .testTag("page_title")
                    .feedbackTitleFiveTap("首页") { triggers++ },
            )
        }

        repeat(4) { composeRule.onNodeWithTag("page_title").performTouchInput { click() } }
        composeRule.runOnIdle { check(triggers == 0) }
        composeRule.onNodeWithTag("page_title").performTouchInput { click() }
        composeRule.runOnIdle { check(triggers == 1) }
        composeRule.onNodeWithTag("page_title").performTouchInput { click() }
        composeRule.runOnIdle { check(triggers == 1) }
    }

    @Test
    fun circleAnnotationCanAddEditAndDeleteFeedback() {
        val shot = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(android.graphics.Color.rgb(24, 32, 44))
            bitmap = it
        }
        var state by mutableStateOf(FeedbackEditorState(pageTitle = "首页", screenshot = shot))
        composeRule.setContent {
            GomobTheme {
                FeedbackEditorOverlay(
                    state = state,
                    submitState = FeedbackSubmitState.Idle,
                    onStateChange = { state = it },
                    onSubmit = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithTag("feedback_submit").assertIsNotEnabled()
        composeRule.onNodeWithTag("feedback_shot_stage").performTouchInput {
            val center = this.center
            val radius = min(width, height).toFloat() * 0.22f
            val start = Offset(center.x + radius, center.y)
            down(start)
            for (step in 1..24) {
                val angle = 2.0 * PI * step / 24.0
                moveTo(
                    Offset(
                        x = center.x + radius * cos(angle).toFloat(),
                        y = center.y + radius * sin(angle).toFloat(),
                    ),
                    delayMillis = 8,
                )
            }
            up()
        }

        composeRule.onNodeWithText("标注 1 的反馈内容").assertIsDisplayed()
        composeRule.onNodeWithTag("feedback_note_input").performTextInput("按钮被遮挡")
        composeRule.onNodeWithTag("feedback_note_confirm").performClick()

        composeRule.onNodeWithTag("feedback_marker_1").assertIsDisplayed()
        composeRule.onNodeWithText("按钮被遮挡").assertIsDisplayed()
        composeRule.onNodeWithTag("feedback_submit").assertIsEnabled()

        composeRule.onNodeWithText("修改").performClick()
        composeRule.onNodeWithTag("feedback_note_input").performTextClearance()
        composeRule.onNodeWithTag("feedback_note_input").performTextInput("标题区域被遮挡")
        composeRule.onNodeWithTag("feedback_note_confirm").performClick()
        composeRule.onNodeWithText("标题区域被遮挡").assertIsDisplayed()

        composeRule.onNodeWithText("删除").performClick()
        composeRule.onNodeWithTag("feedback_submit").assertIsNotEnabled()
    }

    @Test
    fun submittedFeedbackIsReadOnly() {
        val shot = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(android.graphics.Color.rgb(24, 32, 44))
            bitmap = it
        }
        val marker = AppFeedbackMarker(
            id = 1L,
            points = listOf(
                FeedbackPoint(0.2f, 0.2f),
                FeedbackPoint(0.4f, 0.2f),
                FeedbackPoint(0.4f, 0.4f),
                FeedbackPoint(0.2f, 0.4f),
            ),
            note = "按钮被遮挡",
        )
        composeRule.setContent {
            GomobTheme {
                FeedbackEditorOverlay(
                    state = FeedbackEditorState(
                        pageTitle = "首页",
                        screenshot = shot,
                        markers = listOf(marker),
                    ),
                    submitState = FeedbackSubmitState.Submitted("feedback-id"),
                    onStateChange = {},
                    onSubmit = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("修改").assertIsNotEnabled()
        composeRule.onNodeWithText("删除").assertIsNotEnabled()
        composeRule.onNodeWithTag("feedback_submit").assertIsNotEnabled()
    }

    @Test
    fun captureIncludesSurfaceViewAndComposeOverlay() {
        val surfaceReady = AtomicBoolean(false)
        val surfacePaused = AtomicBoolean(false)
        val surfaceResumed = AtomicBoolean(false)
        lateinit var surfaceView: SurfaceView
        composeRule.setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFF301010))) {
                AndroidView(
                    factory = { context ->
                        object : SurfaceView(context), FeedbackCaptureSurface {
                            override fun pauseForFeedbackCapture() {
                                surfacePaused.set(true)
                            }

                            override fun resumeAfterFeedbackCapture() {
                                surfaceResumed.set(true)
                            }
                        }.also { view ->
                            surfaceView = view
                            view.holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    drawGreen(holder)
                                    surfaceReady.set(true)
                                }

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int,
                                ) {
                                    drawGreen(holder)
                                    surfaceReady.set(true)
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                            })
                        }
                    },
                    modifier = Modifier.align(Alignment.Center).size(240.dp),
                )
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(40.dp)
                        .background(Color.Blue),
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { surfaceReady.get() }

        val location = IntArray(2)
        var width = 0
        var height = 0
        composeRule.runOnIdle {
            surfaceView.getLocationInWindow(location)
            width = surfaceView.width
            height = surfaceView.height
        }

        val captured = runBlocking(Dispatchers.Main) { captureWindowBitmap(composeRule.activity) }
        try {
            val greenPixel = captured.getPixel(location[0] + width / 4, location[1] + height / 4)
            assertTrue(android.graphics.Color.green(greenPixel) > 180)
            assertTrue(android.graphics.Color.red(greenPixel) < 100)

            val overlayPixel = captured.getPixel(location[0] + width / 2, location[1] + height / 2)
            assertTrue(android.graphics.Color.blue(overlayPixel) > 180)
            assertTrue(android.graphics.Color.red(overlayPixel) < 100)
            assertTrue(surfacePaused.get())
            assertTrue(surfaceResumed.get())
        } finally {
            captured.recycle()
        }
    }

    @Test
    fun dynamicSurfaceCaptureUsesOneStableFrameAndResumes() {
        lateinit var surfaceView: AlternatingSurfaceView
        composeRule.setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFF301010))) {
                AndroidView(
                    factory = { context -> AlternatingSurfaceView(context).also { surfaceView = it } },
                    modifier = Modifier.align(Alignment.Center).size(240.dp),
                )
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(40.dp)
                        .background(Color.Blue.copy(alpha = 0.5f)),
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { surfaceView.frameCount >= 3 }

        val location = IntArray(2)
        var width = 0
        var height = 0
        composeRule.runOnIdle {
            surfaceView.getLocationInWindow(location)
            width = surfaceView.width
            height = surfaceView.height
        }

        repeat(10) {
            val captured = runBlocking(Dispatchers.Main) { captureWindowBitmap(composeRule.activity) }
            try {
                val left = captured.getPixel(location[0] + width / 4, location[1] + height / 4)
                val right = captured.getPixel(location[0] + width * 3 / 4, location[1] + height / 4)
                val leftRed = android.graphics.Color.red(left) > 180 && android.graphics.Color.green(left) < 90
                val leftGreen = android.graphics.Color.green(left) > 180 && android.graphics.Color.red(left) < 90
                val rightRed = android.graphics.Color.red(right) > 180 && android.graphics.Color.green(right) < 90
                val rightGreen = android.graphics.Color.green(right) > 180 && android.graphics.Color.red(right) < 90
                assertTrue((leftRed && rightGreen) || (leftGreen && rightRed))

                val overlay = captured.getPixel(location[0] + width / 2, location[1] + height / 2)
                assertTrue(android.graphics.Color.blue(overlay) > 100)
                assertTrue(
                    android.graphics.Color.red(overlay) > 50 ||
                        android.graphics.Color.green(overlay) > 50,
                )
            } finally {
                captured.recycle()
            }
        }

        val frameBeforeResumeCheck = surfaceView.frameCount
        composeRule.waitUntil(timeoutMillis = 5_000) { surfaceView.frameCount > frameBeforeResumeCheck }
        assertTrue(surfaceView.pauseCount >= 10)
        assertTrue(surfaceView.resumeCount >= 10)
    }

    @Test
    fun viewDrawKeepsTransparentSurfaceHoleAndComposeOverlay() {
        val surfaceReady = AtomicBoolean(false)
        lateinit var surfaceView: SurfaceView
        composeRule.setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFF301010))) {
                AndroidView(
                    factory = { context ->
                        SurfaceView(context).also { view ->
                            surfaceView = view
                            view.holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    drawGreen(holder)
                                    surfaceReady.set(true)
                                }

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int,
                                ) {
                                    drawGreen(holder)
                                    surfaceReady.set(true)
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                            })
                        }
                    },
                    modifier = Modifier.align(Alignment.Center).size(240.dp),
                )
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(40.dp)
                        .background(Color.Blue),
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { surfaceReady.get() }

        val location = IntArray(2)
        var width = 0
        var height = 0
        lateinit var drawn: Bitmap
        composeRule.runOnIdle {
            surfaceView.getLocationInWindow(location)
            width = surfaceView.width
            height = surfaceView.height
            val root = composeRule.activity.window.decorView.rootView
            drawn = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(drawn))
        }
        try {
            val holePixel = drawn.getPixel(location[0] + width / 4, location[1] + height / 4)
            assertTrue(android.graphics.Color.alpha(holePixel) < 16)

            val overlayPixel = drawn.getPixel(location[0] + width / 2, location[1] + height / 2)
            assertTrue(android.graphics.Color.blue(overlayPixel) > 180)
            assertTrue(android.graphics.Color.alpha(overlayPixel) > 240)
        } finally {
            drawn.recycle()
        }
    }

    private fun drawGreen(holder: SurfaceHolder) {
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawColor(android.graphics.Color.rgb(20, 220, 60))
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private class AlternatingSurfaceView(context: Context) :
        SurfaceView(context),
        FeedbackCaptureSurface,
        SurfaceHolder.Callback,
        Choreographer.FrameCallback {
        private val choreographer = Choreographer.getInstance()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var running = false

        @Volatile
        var frameCount: Int = 0
            private set

        @Volatile
        var pauseCount: Int = 0
            private set

        @Volatile
        var resumeCount: Int = 0
            private set

        init {
            holder.addCallback(this)
        }

        override fun surfaceCreated(holder: SurfaceHolder) {
            start()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            start()
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            stop()
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            drawAlternatingFrame()
            choreographer.postFrameCallback(this)
        }

        override fun pauseForFeedbackCapture() {
            pauseCount++
            stop()
        }

        override fun resumeAfterFeedbackCapture() {
            resumeCount++
            start()
        }

        private fun start() {
            if (running || !holder.surface.isValid) return
            running = true
            drawAlternatingFrame()
            choreographer.postFrameCallback(this)
        }

        private fun stop() {
            running = false
            choreographer.removeFrameCallback(this)
        }

        private fun drawAlternatingFrame() {
            val canvas = holder.lockCanvas() ?: return
            try {
                val even = frameCount % 2 == 0
                paint.color = if (even) {
                    android.graphics.Color.rgb(220, 30, 30)
                } else {
                    android.graphics.Color.rgb(30, 220, 60)
                }
                canvas.drawRect(0f, 0f, canvas.width / 2f, canvas.height.toFloat(), paint)
                paint.color = if (even) {
                    android.graphics.Color.rgb(30, 220, 60)
                } else {
                    android.graphics.Color.rgb(220, 30, 30)
                }
                canvas.drawRect(canvas.width / 2f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), paint)
                frameCount++
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }
}
