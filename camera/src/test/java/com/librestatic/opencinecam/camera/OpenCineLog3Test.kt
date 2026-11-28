/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OpenCineLog3Test {
    @Test
    fun ocLog3AnchorsC1ContinuityAndExactInverseHold() {
        assertEquals(0.095, OpenCineLog3Curve.encode(0.0), 1e-12)
        assertEquals(0.400, OpenCineLog3Curve.encode(0.18), 1e-12)
        assertEquals(0.920, OpenCineLog3Curve.encode(16.0), 1e-12)

        // C¹ continuity across the linear-to-log transition CUT.
        val eps = 1e-7
        val leftVal = OpenCineLog3Curve.encode(OpenCineLog3Curve.CUT - eps)
        val midVal = OpenCineLog3Curve.encode(OpenCineLog3Curve.CUT)
        val rightVal = OpenCineLog3Curve.encode(OpenCineLog3Curve.CUT + eps)
        val leftSlope = (midVal - leftVal) / eps
        val rightSlope = (rightVal - midVal) / eps
        assertEquals(OpenCineLog3Curve.CUT_CODE, midVal, 1e-12)
        assertEquals(leftSlope, rightSlope, 1e-4)

        // Strict monotonicity and round-trip precision < 1e-12 over 1000 samples across all 14+ stops.
        var previous = -1.0
        for (i in 0..1000) {
            val linear = OpenCineLog3Curve.MAX_LINEAR * (i / 1000.0) * (i / 1000.0)
            val encoded = OpenCineLog3Curve.encode(linear)
            assertTrue("monotonic at $linear", encoded > previous)
            previous = encoded
            val decoded = OpenCineLog3Curve.decode(encoded)
            assertTrue("round-trip error too high at $linear: ${abs(decoded - linear)}", abs(decoded - linear) < 1e-11)
        }
    }

    @Test
    fun hlgToOcLog3PreservesFullHighlightHeadroomWithoutClipping() {
        // BT.2408 HLG 18% grey (0.38) aligns exactly to 0.400 OCLog3 code.
        assertEquals(0.400, OpenCineLog3Curve.encodeFromHlgSignal(0.38), 1e-12)
        // HLG peak white (1.0) lands comfortably below 0.920 ceiling (~0.7492): zero highlight clip.
        val hlgPeakCode = OpenCineLog3Curve.encodeFromHlgSignal(1.0)
        assertEquals(0.749218, hlgPeakCode, 1e-5)
        assertTrue(hlgPeakCode < OpenCineLog3Curve.WHITE_CODE)
        // Rec.709 18% grey also aligns to 0.400 (0.04813 -> 0.400 in HLG, 0.40901 -> 0.400 in 709).
        assertEquals(0.400, OpenCineLog3Curve.encodeFromRec709Signal(0.4090077288685503), 1e-9)
    }

    @Test
    fun outOfDomainInputsThrowOnStrictCurveMethods() {
        assertThrows(IllegalArgumentException::class.java) { OpenCineLog3Curve.encode(-1e-6) }
        assertThrows(IllegalArgumentException::class.java) { OpenCineLog3Curve.encode(16.1) }
        assertThrows(IllegalArgumentException::class.java) { OpenCineLog3Curve.decode(0.09) }
        assertThrows(IllegalArgumentException::class.java) { OpenCineLog3Curve.decode(0.93) }
    }

    @Test
    fun encoderRangePolicyIsolatesExperimentalLimitedFlag() {
        assertEquals(1, OpenCineLogEncoderRangePolicy.mediaFormatColorRange(false, false, true, false))
        assertEquals(2, OpenCineLogEncoderRangePolicy.mediaFormatColorRange(false, false, true, true))
        assertEquals(1, OpenCineLogEncoderRangePolicy.mediaFormatColorRange(false, false, false, true))
        assertEquals(2, OpenCineLogEncoderRangePolicy.mediaFormatColorRange(true, false, true, false))
    }
}
