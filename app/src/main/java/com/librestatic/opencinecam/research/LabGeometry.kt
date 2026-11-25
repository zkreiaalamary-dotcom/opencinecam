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
