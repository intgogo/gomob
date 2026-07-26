package io.gomob.feature.scan3d

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.gomob.data.scan.VinRecognitionResult
import io.gomob.designsystem.theme.Gomob
import io.gomob.designsystem.theme.GomobTheme

/** 仅供 debug APK 用 uiautomator 验证紧凑结果与单字符横滑，不进入生产导航。 */
class VinCharacterCropDebugActivity : ComponentActivity() {
    private val cropsDelegate = lazy {
        "LA99FRP32G0LTH013".mapIndexed { index, character ->
            VinCharacterCropPreview(
                position = index + 1,
                character = character.toString(),
                confidence = 0.9,
                bitmap = Bitmap.createBitmap(64, 128, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.rgb(24 + index, 48 + index, 72 + index))
                },
            )
        }
    }
    private val crops by cropsDelegate
    private val rubbingDelegate = lazy {
        Bitmap.createBitmap(885, 120, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(Color.rgb(64, 61, 58))
            canvas.drawText(
                "LA99FRP32G0LTH013",
                36f,
                82f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(218, 214, 207)
                    textSize = 54f
                },
            )
        }
    }
    private val rubbing by rubbingDelegate
    private val resultDelegate = lazy {
        VinCaptureState.Result(
            result = VinRecognitionResult(
                provider = "gosmart",
                vin = "LA99FRP32G0LTH013",
                confidence = 0.918,
                characterScores = List(17) { 0.9 },
                characterCount = 17,
                logId = "debug-ui",
                inferMs = 930,
                characterCrops = emptyList(),
            ),
            crops = crops,
        )
    }
    private val result by resultDelegate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GomobTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Gomob.colors.bg0)
                        .padding(vertical = 48.dp),
                ) {
                    VinOutcomePanel(rubbing = rubbing, recognition = result)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (cropsDelegate.isInitialized()) crops.forEach { it.bitmap.recycle() }
        if (rubbingDelegate.isInitialized()) rubbing.recycle()
    }
}
