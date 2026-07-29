package io.gomob.feature.scan3d

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.gomob.designsystem.theme.GomobTheme

/** 仅供 debug APK 用 uiautomator 验证 VIN 质量门 UI，不进入生产导航。 */
class VinCaptureQualityDebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ready = intent.getBooleanExtra(EXTRA_READY, false)
        val distanceMm = intent.getFloatExtra(EXTRA_DISTANCE_MM, 335f).toDouble()
        val metrics = VinDepthRoiMetrics(
            totalPixels = 100_000,
            validPixels = if (ready) 98_000 else 40_000,
            coverageRatio = if (ready) 0.98 else 0.40,
            projectedPoints = if (ready) 18_000 else 6_000,
            projectedPointRatio = if (ready) 0.18 else 0.06,
            distanceP10Mm = if (ready) distanceMm - 15.0 else 260.0,
            distanceMedianMm = if (ready) distanceMm else 275.0,
            farEnoughRatio = if (ready) 0.95 else 0.20,
        )
        val quality = vinCaptureQuality(metrics)
        setContent {
            GomobTheme {
                VinCaptureQualityTestSurface(quality)
            }
        }
    }

    private companion object {
        const val EXTRA_READY = "ready"
        const val EXTRA_DISTANCE_MM = "distance_mm"
    }
}
