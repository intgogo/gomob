package io.gomob.nativebridge.camera

import android.app.PendingIntent
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPermissionPolicyTest {
    @Test
    fun rawGrantIsAccepted() {
        assertTrue(isUsbPermissionGranted(rawGranted = true, managerGranted = false))
    }

    @Test
    fun managerGrantRecoversFalseBroadcast() {
        assertTrue(isUsbPermissionGranted(rawGranted = false, managerGranted = true))
    }

    @Test
    fun bothDeniedIsRejected() {
        assertFalse(isUsbPermissionGranted(rawGranted = false, managerGranted = false))
    }

    @Test
    fun android12AndAboveUseMutablePendingIntent() {
        val flags = usbPermissionPendingIntentFlags(Build.VERSION_CODES.S)

        assertTrue(flags and PendingIntent.FLAG_MUTABLE != 0)
        assertEquals(0, flags and PendingIntent.FLAG_IMMUTABLE)
        assertTrue(flags and PendingIntent.FLAG_CANCEL_CURRENT != 0)
    }

    @Test
    fun preAndroid12DoesNotSetMutabilityFlag() {
        val flags = usbPermissionPendingIntentFlags(Build.VERSION_CODES.R)

        assertEquals(0, flags and PendingIntent.FLAG_MUTABLE)
        assertEquals(0, flags and PendingIntent.FLAG_IMMUTABLE)
        assertTrue(flags and PendingIntent.FLAG_CANCEL_CURRENT != 0)
    }

    @Test
    fun requestCodeChangesAcrossUsbGenerations() {
        val first = usbPermissionRequestCode("/dev/bus/usb/001/003", generation = 1L)
        val second = usbPermissionRequestCode("/dev/bus/usb/001/003", generation = 2L)

        assertTrue(first != second)
    }

    @Test
    fun requestCodeSeparatesUsbNodes() {
        val color = usbPermissionRequestCode("/dev/bus/usb/001/003", generation = 1L)
        val depth = usbPermissionRequestCode("/dev/bus/usb/001/004", generation = 1L)

        assertTrue(color != depth)
    }
}
