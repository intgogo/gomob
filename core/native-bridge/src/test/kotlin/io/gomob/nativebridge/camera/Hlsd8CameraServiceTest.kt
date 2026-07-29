package io.gomob.nativebridge.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class Hlsd8CameraServiceTest {
    @Test
    fun fullResolutionPreviewIsSampledWithinMemoryBudget() {
        assertEquals(4, hlsd8PreviewSampleSize(srcWidth = 4160, maxWidth = 1280))
        assertEquals(1040, 4160 / hlsd8PreviewSampleSize(4160, 1280))
    }

    @Test
    fun alreadySmallPreviewIsNotSampled() {
        assertEquals(1, hlsd8PreviewSampleSize(srcWidth = 1280, maxWidth = 1280))
        assertEquals(1, hlsd8PreviewSampleSize(srcWidth = 640, maxWidth = 1280))
    }
}
