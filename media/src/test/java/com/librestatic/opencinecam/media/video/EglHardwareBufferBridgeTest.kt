/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class EglHardwareBufferBridgeTest {
    @Test
    fun hostJvmReturnsLoadedFalseSafelyWithoutCrashing() {
        assertFalse(EglHardwareBufferBridge.loaded)
        assertEquals(0, EglHardwareBufferBridge.probeExtensions())
        assertEquals(-1, EglHardwareBufferBridge.bindExternalTexture(0L))
        assertEquals(-1, EglHardwareBufferBridge.bindRenderbuffer(0L))
        assertFalse(EglHardwareBufferBridge.isAhbSupported(1920, 1080, 54, 0L))
        assertNull(EglHardwareBufferBridge.allocateAhb(1920, 1080, 54, 0L))
        assertFalse(EglHardwareBufferBridge.destroyEglImage(0L))
        assertEquals(-1, EglHardwareBufferBridge.lastEglError())
    }
}
