/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.research

import kotlin.math.abs

internal data class AxisDistances(val half: Double, val quarter: Double) {
    val textured get() = quarter > 15.0
    val suspicious get() = textured && half < 4.0
}

internal data class LabHalfFrame(val horizontal: AxisDistances, val vertical: AxisDistances)

/** Display-decoded RGB8 distances with the Pura Cinema repeated-half screening thresholds. */
internal fun halfFrame(pixels: IntArray, width: Int, height: Int): LabHalfFrame {
    require(width >= 4 && height >= 4 && pixels.size == width * height)
    fun distance(dx: Int, dy: Int): Double {
        var total = 0L
        for (y in 0 until height - dy) for (x in 0 until width - dx) {
            val a = pixels[y * width + x]
            val b = pixels[(y + dy) * width + x + dx]
            for (shift in 0..16 step 8) total += abs(((a ushr shift) and 255) - ((b ushr shift) and 255))
        }
        return total.toDouble() / (3L * (width - dx) * (height - dy))
    }
    return LabHalfFrame(AxisDistances(distance(width / 2, 0), distance(width / 4, 0)),
        AxisDistances(distance(0, height / 2), distance(0, height / 4)))
}

internal fun halfFrameScreen(frames: List<LabHalfFrame>): String = when {
    frames.count { it.horizontal.suspicious } >= 2 -> "REVIEW_LEFT_RIGHT"
    frames.count { it.vertical.suspicious } >= 2 -> "REVIEW_TOP_BOTTOM"
    frames.any { it.horizontal.suspicious || it.vertical.suspicious } -> "INCONCLUSIVE"
    frames.count { it.horizontal.textured && it.vertical.textured } >= 2 -> "NO_REPEAT_FLAG"
    else -> "INCONCLUSIVE"
}

/** Mean absolute RGB8 channel difference between two same-sized frames, to verify unique picture content. */
internal fun frameContentMad(frameA: IntArray, frameB: IntArray): Double {
    require(frameA.size == frameB.size && frameA.isNotEmpty())
    var total = 0L
    for (i in frameA.indices) {
        val a = frameA[i]
        val b = frameB[i]
        for (shift in 0..16 step 8) {
            total += abs(((a ushr shift) and 255) - ((b ushr shift) and 255))
        }
    }
    return total.toDouble() / (3L * frameA.size)
}

internal fun contentUniquenessVerdict(consecutiveMads: List<Double>, spacedMads: List<Double>): String {
    val all = consecutiveMads + spacedMads
    if (all.isEmpty()) return "INCONCLUSIVE"
    val maxAll = all.max()
    if (maxAll <= 0.02) return "STATIC_OR_DUPLICATE"
    val consecOk = consecutiveMads.isEmpty() || consecutiveMads.any { it > 0.05 }
    val spacedOk = spacedMads.isEmpty() || spacedMads.any { it > 0.15 }
    return if (consecOk && spacedOk && maxAll > 0.10) "DYNAMIC_CONFIRMED" else "INCONCLUSIVE"
}

internal data class SyntheticLayoutCheck(
    val verdict: String,
    val halfX: Double,
    val quarterX: Double,
    val halfY: Double,
    val quarterY: Double,
    val q0RedMinusBlue: Double,
    val q1RampDelta: Double,
    val q2GreenMinusRed: Double,
)

/**
 * Verifies the deterministic 4-quadrant synthetic test pattern in coded image coordinates
 * (row 0 = top, row height-1 = bottom):
 *  - Q0 (top-left): red-dominant checkerboard + L-fiducial
 *  - Q1 (top-right): smooth horizontal gradient ramp (right brighter than left)
 *  - Q2 (bottom-left): green/cyan bars (G > R)
 *  - Q3 (bottom-right): moving bar + binary frame stripes
 */
internal fun verifySyntheticQuadrants(pixels: IntArray, width: Int, height: Int): SyntheticLayoutCheck {
    val half = halfFrame(pixels, width, height)
    val hw = width / 2
    val hh = height / 2
    fun quadrantStats(x0: Int, x1: Int, y0: Int, y1: Int): DoubleArray {
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for (y in y0 until y1) for (x in x0 until x1) {
            val p = pixels[y * width + x]
            r += (p ushr 16) and 255
            g += (p ushr 8) and 255
            b += p and 255
            n++
        }
        return if (n == 0L) doubleArrayOf(0.0, 0.0, 0.0) else doubleArrayOf(r.toDouble() / n, g.toDouble() / n, b.toDouble() / n)
    }
    fun meanLuma(x0: Int, x1: Int, y0: Int, y1: Int): Double {
        var sum = 0.0; var n = 0L
        for (y in y0 until y1) for (x in x0 until x1) {
            val p = pixels[y * width + x]
            sum += 0.2126 * ((p ushr 16) and 255) + 0.7152 * ((p ushr 8) and 255) + 0.0722 * (p and 255)
            n++
        }
        return if (n == 0L) 0.0 else sum / n
    }
    val q0 = quadrantStats(0, hw, 0, hh)
    val q1Mid = hw + (width - hw) / 2
    val q1Left = meanLuma(hw, q1Mid, 0, hh)
    val q1Right = meanLuma(q1Mid, width, 0, hh)
    val q2 = quadrantStats(0, hw, hh, height)
    val q0Rb = q0[0] - q0[2]
    val q1Delta = q1Right - q1Left
    val q2Gr = q2[1] - q2[0]
    val verdict = when {
        half.horizontal.suspicious -> "REPEAT_HALF_X"
        half.vertical.suspicious -> "REPEAT_HALF_Y"
        q0Rb > 20.0 && q1Delta > 30.0 && q2Gr > 15.0 -> "PASS"
        else -> "CORRUPT_LAYOUT"
    }
    return SyntheticLayoutCheck(
        verdict = verdict,
        halfX = half.horizontal.half,
        quarterX = half.horizontal.quarter,
        halfY = half.vertical.half,
        quarterY = half.vertical.quarter,
        q0RedMinusBlue = q0Rb,
        q1RampDelta = q1Delta,
        q2GreenMinusRed = q2Gr,
    )
}

internal data class RampPrecisionMetrics(
    val declaredBitDepth: Int,
    val sampleCount: Int,
    val distinctValues: Int,
    val minPositiveStep: Int?,
    val mod4ZeroFraction: Double,
    val effectiveBitsEstimate: Int,
)

/**
 * Measures step quantization on decoded integer ramp samples.
 * For a 10-bit signal, an 8-bit source shifted left by 2 bits has <= 256 distinct values and
 * mod4ZeroFraction ~= 1.0, whereas a true 10-bit ramp has > 256 values and mod4ZeroFraction ~= 0.25.
 */
internal fun analyzeRampPrecision(values: IntArray, declaredBitDepth: Int): RampPrecisionMetrics {
    if (values.isEmpty()) {
        return RampPrecisionMetrics(declaredBitDepth, 0, 0, null, 0.0, 0)
    }
    val sorted = values.distinct().sorted()
    val distinct = sorted.size
    val minStep = sorted.zipWithNext { a, b -> b - a }.filter { it > 0 }.minOrNull()
    val mod4Zero = if (declaredBitDepth >= 10) values.count { (it and 3) == 0 }.toDouble() / values.size else 1.0
    val bits = if (distinct <= 1) 0 else 32 - Integer.numberOfLeadingZeros(distinct - 1)
    return RampPrecisionMetrics(
        declaredBitDepth = declaredBitDepth,
        sampleCount = values.size,
        distinctValues = distinct,
        minPositiveStep = minStep,
        mod4ZeroFraction = mod4Zero,
        effectiveBitsEstimate = bits,
    )
}
