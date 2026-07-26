package io.gomob.nativebridge.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class Eys3dMode25IntrinsicsTest {
    @Test
    fun colorProfileUsesVerticalCropWithoutAnisotropicFocalScaling() {
        val k = rsd550Mode25Intrinsics(1280, 256)
        assertEquals(1229.205, k.fx, 1e-6)
        assertEquals(1229.205, k.fy, 1e-6)
        assertEquals(648.0, k.cx, 1e-6)
        assertEquals(130.865, k.cy, 1e-6)
    }

    @Test
    fun depthProfileUniformlyScalesTheCroppedImage() {
        val k = rsd550Mode25Intrinsics(640, 128)
        assertEquals(614.6025, k.fx, 1e-6)
        assertEquals(614.6025, k.fy, 1e-6)
        assertEquals(324.0, k.cx, 1e-6)
        assertEquals(65.4325, k.cy, 1e-6)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonMode25AspectIsRejected() {
        rsd550Mode25Intrinsics(640, 480)
    }
}
