/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.gallery

import android.os.Build
import com.librestatic.opencinecam.storage.SurfaceFrameRenderConfirmation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogPlaybackApi31DefaultTest {
    @Test
    fun defaultsToDirectGlSurfaceReviewForOcLogOnApiBelow33() {
        assertTrue(shouldDefaultNativeSurfaceFrames(settingNative = false, isOcLogClip = true, sdkInt = Build.VERSION_CODES.S))
        assertTrue(shouldDefaultNativeSurfaceFrames(settingNative = false, isOcLogClip = true, sdkInt = Build.VERSION_CODES.S_V2))
        assertTrue(shouldDefaultNativeSurfaceFrames(settingNative = false, isOcLogClip = false, sdkInt = Build.VERSION_CODES.S, isHdrClip = true))
        assertFalse(shouldDefaultNativeSurfaceFrames(settingNative = false, isOcLogClip = true, sdkInt = Build.VERSION_CODES.TIRAMISU))
        assertFalse(shouldDefaultNativeSurfaceFrames(settingNative = false, isOcLogClip = false, sdkInt = Build.VERSION_CODES.TIRAMISU, isHdrClip = true))
        assertFalse(shouldDefaultNativeSurfaceFrames(settingNative = false, isOcLogClip = false, sdkInt = Build.VERSION_CODES.S))
        assertTrue(shouldDefaultNativeSurfaceFrames(settingNative = true, isOcLogClip = false, sdkInt = Build.VERSION_CODES.TIRAMISU))
    }

    @Test
    fun surfaceRenderConfirmationContractDeliversTimestampAndClosesCleanly() {
        var closed = false
        var observedNs = -1L
        val hook = SurfaceFrameRenderConfirmation { ptsUs, onRendered ->
            if (ptsUs == 16_667L) onRendered(987_654_321L)
            AutoCloseable { closed = true }
        }
        hook.awaitFrame(16_667L) { observedNs = it }.use { }
        assertEquals(987_654_321L, observedNs)
        assertTrue(closed)
    }
}
