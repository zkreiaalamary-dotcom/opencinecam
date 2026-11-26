/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabSourceProbeTest {
    @Test
    fun lumaHistogramMetricsComputesPercentilesAndClipFractions() {
        val clipped = IntArray(100) { i ->
            when {
                i < 10 -> 2
                i >= 90 -> 253
                else -> 128
            }
        }
        val m = computeLumaHistogramMetrics(clipped)
        assertEquals(100, m.sampleCount)
        assertEquals(2, m.min)
        assertEquals(253, m.max)
        assertEquals(0.10, m.shadowClipFraction, 1e-6)
        assertEquals(0.10, m.highlightClipFraction, 1e-6)
        assertEquals(3, m.distinctBins)
        assertEquals(251, m.dynamicSpread)
    }

    @Test
    fun toneMapComparisonDistinguishesFlatClipReductionFromIgnoredCurve() {
        val clippedDefault = computeLumaHistogramMetrics(IntArray(100) { if (it < 20) 254 else 120 })
        val unclippedFlat = computeLumaHistogramMetrics(IntArray(100) { if (it < 20) 232 else 128 })

        assertEquals(
            "FLAT_REDUCED_CLIPPING",
            classifyToneMapComparison(clippedDefault, unclippedFlat, flatCurveConfirmed = true),
        )
        assertEquals(
            "TONEMAP_CONTRAST_CURVE_IGNORED_BY_HAL",
            classifyToneMapComparison(clippedDefault, clippedDefault, flatCurveConfirmed = false),
        )
        assertEquals(
            "NO_MEASURABLE_DIFFERENCE",
            classifyToneMapComparison(unclippedFlat, unclippedFlat, flatCurveConfirmed = true),
        )
    }

    @Test
    fun filterInterestingVendorKeysSelectsRelevantNonAndroidTags() {
        val filtered = filterInterestingVendorKeys(
            listOf(
                "android.control.aeMode",
                "com.huawei.device.capabilities.hdrMode",
                "hw.video.logMode",
                "vendor.unrelated.gyroBias",
                "com.huawei.camera.raw10Support",
            ),
        )
        assertEquals(
            listOf(
                "com.huawei.camera.raw10Support",
                "com.huawei.device.capabilities.hdrMode",
                "hw.video.logMode",
            ),
            filtered,
        )
        assertTrue(filtered.none { it.startsWith("android.") })
    }
}
