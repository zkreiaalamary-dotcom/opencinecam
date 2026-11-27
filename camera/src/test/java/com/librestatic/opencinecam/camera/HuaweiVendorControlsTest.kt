/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HuaweiVendorControlsTest {
    @Test
    fun parsesValidHuaweiKelvinRangeAndRejectsGarbage() {
        assertEquals(2800..10000, HuaweiVendorControls.parseKelvinRange(intArrayOf(2800, 10000)))
        assertNull(HuaweiVendorControls.parseKelvinRange(null))
        assertNull(HuaweiVendorControls.parseKelvinRange(intArrayOf(2800)))
        assertNull(HuaweiVendorControls.parseKelvinRange(intArrayOf(8000, 3000)))
        assertNull(HuaweiVendorControls.parseKelvinRange(intArrayOf(0, 0)))
    }

    @Test
    fun whiteBalanceKelvinAdaptsToHuaweiRangeWithoutTint() {
        val adapted = WhiteBalanceSelection.Kelvin(4340, tint = 12).adaptTo(2800..10000, tintSupported = false)
        assertEquals(WhiteBalanceSelection.Kelvin(4300, tint = 0), adapted)
        assertEquals(WhiteBalanceSelection.Auto, WhiteBalanceSelection.Kelvin(5600).adaptTo(null))
    }

    @Test
    fun sanitizeRestoredToneMapModeNeverEmitsPresetCurve() {
        assertEquals(CaptureRequest.TONEMAP_MODE_FAST, sanitizeRestoredToneMapMode(CaptureRequest.TONEMAP_MODE_PRESET_CURVE))
        assertEquals(CaptureRequest.TONEMAP_MODE_FAST, sanitizeRestoredToneMapMode(CaptureRequest.TONEMAP_MODE_FAST))
        assertEquals(CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE, sanitizeRestoredToneMapMode(CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE))
        assertNull(sanitizeRestoredToneMapMode(null))
    }
}
