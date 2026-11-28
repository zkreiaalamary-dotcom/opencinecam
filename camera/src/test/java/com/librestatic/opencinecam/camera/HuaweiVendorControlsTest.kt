/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun supportsSmoothColorModesChecksStaticCapabilityBytes() {
        assertTrue(HuaweiVendorControls.supportsSmoothColorModesRaw(byteArrayOf(0, 1, 2, 100), emptySet()))
        assertFalse(HuaweiVendorControls.supportsSmoothColorModesRaw(byteArrayOf(0, 1), emptySet()))
        assertTrue(HuaweiVendorControls.supportsSmoothColorModesRaw(null, setOf(HuaweiVendorControls.COLOR_MODE_KEY)))
        assertFalse(HuaweiVendorControls.supportsSmoothColorModesRaw(null, emptySet()))
    }

    @Test
    fun parsesAndSortsValidVariableApertures() {
        assertEquals(
            listOf(1.6f, 2.0f, 2.8f, 4.0f),
            HuaweiVendorControls.parseVariableApertures(floatArrayOf(4.0f, 1.6f, 2.0f, 2.0f, 2.8f, -1f, Float.NaN)),
        )
        assertEquals(emptyList<Float>(), HuaweiVendorControls.parseVariableApertures(null))
    }

    @Test
    fun supportsHuaweiManualExposureOnlyWithAeOffAndRanges() {
        assertTrue(
            HuaweiVendorControls.supportsHuaweiManualExposureRaw(
                isHuawei = true,
                hasManualSensorCapability = false,
                aeAvailableModes = intArrayOf(CaptureRequest.CONTROL_AE_MODE_OFF, CaptureRequest.CONTROL_AE_MODE_ON),
                isoRange = 50..6400,
                timeRangeNs = 10_000L..1_000_000_000L,
            ),
        )
        assertFalse(
            HuaweiVendorControls.supportsHuaweiManualExposureRaw(
                isHuawei = false,
                hasManualSensorCapability = false,
                aeAvailableModes = intArrayOf(CaptureRequest.CONTROL_AE_MODE_OFF, CaptureRequest.CONTROL_AE_MODE_ON),
                isoRange = 50..6400,
                timeRangeNs = 10_000L..1_000_000_000L,
            ),
        )
        assertFalse(
            HuaweiVendorControls.supportsHuaweiManualExposureRaw(
                isHuawei = true,
                hasManualSensorCapability = false,
                aeAvailableModes = intArrayOf(CaptureRequest.CONTROL_AE_MODE_ON),
                isoRange = 50..6400,
                timeRangeNs = 10_000L..1_000_000_000L,
            ),
        )
        assertTrue(
            HuaweiVendorControls.supportsHuaweiManualExposureRaw(
                isHuawei = false,
                hasManualSensorCapability = true,
                aeAvailableModes = null,
                isoRange = null,
                timeRangeNs = null,
            ),
        )
    }
}
