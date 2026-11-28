/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * OpenCineLog3 (`OCLog3` v1.0): scene-referred wide-latitude BT.2020 cinema log transfer curve.
 *
 * Designed to eliminate OCLog2's `0.18 -> 0.349 / 0.568` dual-tier ambiguity and `[0, 1]` clipping
 * under HDR/HLG gain matching:
 * - Domain: scene-linear BT.2020 `[0.0, 16.0]` (6.47 stops above 18% middle grey).
 * - Output range: full `[0.095, 0.920]` with clean C¹ continuity at `CUT = 0.008`:
 *   - `encode(0.0) = 0.095` (shadow pedestal)
 *   - `encode(0.18) = 0.400` (40% IRE middle grey)
 *   - `encode(16.0) = 0.920` (highlight ceiling; HLG peak white `3.7396` lands at `0.7492`)
 * - Strictly monotonic with exact analytical inverse `decode(code)` to `< 1e-12`.
 */
object OpenCineLog3Curve {
    const val VERSION = "1.0.0"
    const val BLACK_CODE = 0.095
    const val GREY_LINEAR = 0.18
    const val GREY_CODE = 0.400
    const val WHITE_CODE = 0.920
    const val MAX_LINEAR = 16.0
    const val CUT = 0.008

    // Analytical C¹ parameters solving:
    //   M * CUT + BLACK_CODE == A_LN * ln(CUT + C) + D
    //   M == A_LN / (CUT + C)
    //   A_LN * ln(0.18 + C) + D == 0.400
    //   A_LN * ln(16.0 + C) + D == 0.920
    const val C_OFFSET = 0.013131196250227292
    const val A_LN = 0.11770578833655286
    const val D_OFFSET = 0.5935536972214088
    const val TOE_SLOPE = 5.570237810615516
    val CUT_CODE: Double = TOE_SLOPE * CUT + BLACK_CODE

    private const val HLG_A = 0.17883277
    private const val HLG_B = 1.0 - 4.0 * HLG_A
    private val HLG_C = 0.5 - HLG_A * ln(4.0 * HLG_A)

    /** Encodes scene-linear BT.2020 `[0.0, 16.0]` into OCLog3 code `[0.095, 0.920]`. */
    fun encode(linear: Double): Double {
        require(linear.isFinite() && linear in 0.0..MAX_LINEAR) {
            "OCLog3 linear input must be finite and in [0.0, $MAX_LINEAR]: $linear"
        }
        return if (linear < CUT) {
            TOE_SLOPE * linear + BLACK_CODE
        } else {
            A_LN * ln(linear + C_OFFSET) + D_OFFSET
        }
    }

    /** Clamped convenience encoder matching GPU/LUT transport boundary semantics. */
    fun encodeClamped(linear: Double): Double =
        encode(if (linear.isNaN()) 0.0 else linear.coerceIn(0.0, MAX_LINEAR))

    /** Exact analytical inverse from OCLog3 code `[0.095, 0.920]` to scene-linear `[0.0, 16.0]`. */
    fun decode(code: Double): Double {
        require(code.isFinite() && code in BLACK_CODE..WHITE_CODE) {
            "OCLog3 code must be finite and in [$BLACK_CODE, $WHITE_CODE]: $code"
        }
        return if (code < CUT_CODE) {
            ((code - BLACK_CODE) / TOE_SLOPE).coerceAtLeast(0.0)
        } else {
            (exp((code - D_OFFSET) / A_LN) - C_OFFSET).coerceIn(0.0, MAX_LINEAR)
        }
    }

    /** Clamped inverse for real encoded containers with compression overshoot/undershoot. */
    fun decodeClamped(code: Double): Double =
        decode(if (code.isNaN()) BLACK_CODE else code.coerceIn(BLACK_CODE, WHITE_CODE))

    /** Converts BT.2100 HLG signal `[0, 1]` directly to scene-referred OCLog3 linear light (18% grey -> 0.18). */
    fun hlgSignalToSceneLinear(hlgCode: Double): Double {
        val e = if (hlgCode.isNaN()) 0.0 else hlgCode.coerceIn(0.0, 1.0)
        val raw = if (e <= 0.5) (e * e) / 3.0 else (exp((e - HLG_C) / HLG_A) + HLG_B) / 12.0
        return raw * OpenCineLogGreyReference.TIER_GREY_RATIO
    }

    /** Converts BT.2100 HLG code `[0, 1]` directly to OCLog3 code (HLG 0.38 -> 0.400, HLG 1.0 -> 0.7492). */
    fun encodeFromHlgSignal(hlgCode: Double): Double = encodeClamped(hlgSignalToSceneLinear(hlgCode))

    /** Converts standard Rec.709 camera code `[0, 1]` to OCLog3 code (18% grey ~0.409 -> 0.400, 1.0 -> 0.653). */
    fun encodeFromRec709Signal(code: Double): Double {
        val e = if (code.isNaN()) 0.0 else code.coerceIn(0.0, 1.0)
        val linear = if (e < 0.081) e / 4.5 else ((e + 0.099) / 1.099).pow(1.0 / 0.45)
        return encodeClamped(linear)
    }
}

/**
 * Policy isolating Android MediaCodec KEY_COLOR_RANGE choices on 8-bit EGL -> Main10 HEVC paths.
 * HiSilicon OMX.hisi.video.encoder.hevc shows a -60 10-bit code downward shift and sub-0.10
 * blacks under COLOR_RANGE_FULL with RGBA8 EGL surfaces in M2; Field Probe 18A compares both.
 */
object OpenCineLogEncoderRangePolicy {
    fun mediaFormatColorRange(
        isHlgOrHdr10Output: Boolean,
        isPassthroughOrBakedLut: Boolean,
        egl8BitActive: Boolean,
        experimentalLimitedForRgba8Log: Boolean,
    ): Int = when {
        isHlgOrHdr10Output || isPassthroughOrBakedLut -> 2 // MediaFormat.COLOR_RANGE_LIMITED
        egl8BitActive && experimentalLimitedForRgba8Log -> 2 // MediaFormat.COLOR_RANGE_LIMITED
        else -> 1 // MediaFormat.COLOR_RANGE_FULL
    }
}
