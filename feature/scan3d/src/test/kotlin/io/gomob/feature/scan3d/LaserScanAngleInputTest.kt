package io.gomob.feature.scan3d

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LaserScanAngleInputTest {

    @Test
    fun oldStartStop_roundTripsToSignedScanAngle() {
        assertEquals(200.0, signedScanAngleDeg(-180.0, 20.0), 0.0001)
        assertEquals(160.0, signedScanAngleDeg(-170.0, -10.0), 0.0001)
        assertEquals(170.0, signedScanAngleDeg(0.0, 170.0), 0.0001)
    }

    @Test
    fun signedScanAngle_convertsToDeviceStopAngle() {
        assertEquals(-10.0, stopAngleFromScan(-170.0, 160.0), 0.0001)
        assertEquals(20.0, stopAngleFromScan(0.0, 20.0), 0.0001)
        assertEquals(150.0, stopAngleFromScan(170.0, -20.0), 0.0001)
    }

    @Test
    fun scanAngleWarning_rejectsTooSmallHalfCircleAndMechanicalOverflow() {
        assertNull(scanAngleWarning(-170.0, 160.0))
        assertNotNull(scanAngleWarning(-180.0, 160.0))
        assertNotNull(scanAngleWarning(-180.0, 2.0))
        assertNotNull(scanAngleWarning(-180.0, 180.0))
        assertNotNull(scanAngleWarning(-180.0, -160.0))
        assertNotNull(scanAngleWarning(20.0, -100.0))
        assertNotNull(scanAngleWarning(170.0, 20.0))
    }

    @Test
    fun accumulator_keepsOneAnglePerPoint() {
        val acc = FloatCloudAccumulator()
        acc.add(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), -180f)
        acc.add(floatArrayOf(7f, 8f, 9f), -120f)

        val cloud = acc.snapshotRender()

        assertEquals(3, cloud.pointCount)
        assertArrayEquals(floatArrayOf(-180f, -180f, -120f), cloud.angles, 0.0001f)
    }
}
