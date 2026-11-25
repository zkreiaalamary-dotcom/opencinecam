/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.research

import kotlin.math.abs
import kotlin.math.ceil

internal data class LabCadence(
    val samples: Int, val spanUs: Long, val fps: Double?, val repeatedPts: Int,
    val longIntervals: Int, val p95Us: Long?, val p99Us: Long?, val maxUs: Long?,
) {
    fun shortDirectIssue(targetFps: Int): String? = when {
        fps == null || spanUs < 3_500_000 -> "Direct recorder did not deliver the measurement window."
        repeatedPts > 0 || longIntervals > 0 -> "Repeated PTS or intervals above 1.5 frame periods."
        abs(fps - targetFps) > targetFps * 0.005 -> "Direct recorder cadence differs by more than 0.5%."
        else -> null
    }
}

internal fun labCadence(ptsUs: List<Long>, targetFps: Int): LabCadence {
    val sorted = ptsUs.sorted()
    val gaps = sorted.zipWithNext { a, b -> b - a }.sorted()
    fun percentile(p: Double) = gaps.getOrNull(ceil(p * gaps.size).toInt() - 1)
    val span = if (gaps.isEmpty()) 0 else sorted.last() - sorted.first()
    return LabCadence(sorted.size, span, if (span > 0) gaps.size * 1_000_000.0 / span else null,
        gaps.count { it == 0L }, gaps.count { it > 1_500_000.0 / targetFps },
        percentile(0.95), percentile(0.99), gaps.lastOrNull())
}
