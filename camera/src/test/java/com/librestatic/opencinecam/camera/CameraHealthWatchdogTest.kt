/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.hardware.camera2.CameraDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CameraHealthWatchdogTest {
    @Test
    fun ignoresSingleExpectedAppOpenAndDetectsDaemonBounceAcrossMultipleCameras() {
        val tracker = CameraHealthTracker(burstWindowMs = 1_500L, burstThreshold = 2, expectedOpenWindowMs = 2_000L)

        // Expected single-camera open does not trigger daemon bounce.
        tracker.markExpectedUnavailable("0", 1_000L)
        assertNull(tracker.onCameraUnavailable("0", 1_100L))
        tracker.onCameraAvailable("0", 1_500L)

        // Two distinct cameras dropping unexpectedly within 1.5s triggers DAEMON_BOUNCE once.
        assertNull(tracker.onCameraUnavailable("0", 5_000L))
        val bounce = tracker.onCameraUnavailable("4", 5_300L)
        assertNotNull(bounce)
        assertEquals(CameraHealthIssue.DAEMON_BOUNCE, bounce!!.issue)

        // Suppressed inside duplicate burst window, then resets later.
        assertNull(tracker.onCameraUnavailable("5", 5_400L))
        assertNull(tracker.onCameraUnavailable("0", 8_000L))
        val bounce2 = tracker.onCameraUnavailable("2", 8_200L)
        assertNotNull(bounce2)
    }

    @Test
    fun classifiesDeviceServiceAndOpenTimeoutEvents() {
        val tracker = CameraHealthTracker()
        val dev = tracker.onCameraError("0", CameraDevice.StateCallback.ERROR_CAMERA_DEVICE, 100L)
        val svc = tracker.onCameraError("2", CameraDevice.StateCallback.ERROR_CAMERA_SERVICE, 200L)
        val timeout = tracker.onOpenTimeout("5", 6_010L, 300L)

        assertEquals(CameraHealthIssue.DEVICE_ERROR, dev.issue)
        assertEquals(CameraHealthIssue.SERVICE_ERROR, svc.issue)
        assertEquals(CameraHealthIssue.OPEN_TIMEOUT, timeout.issue)
        assertEquals(3, tracker.recentEvents().size)
    }
}
