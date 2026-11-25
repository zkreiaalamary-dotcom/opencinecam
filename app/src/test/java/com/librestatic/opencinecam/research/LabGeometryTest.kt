/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.research

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class LabGeometryTest {
    private val width = 64
    private val height = 32
    private val chart = Random(906).let { rng -> IntArray(width * height) { rng.nextInt() } }
    private fun score(sample: (Int, Int) -> Int) =
        halfFrame(IntArray(chart.size) { sample(it % width, it / width) }, width, height)
    private val x = score { col, row -> chart[row * width + col % (width / 2)] }
    private val y = score { col, row -> chart[row % (height / 2) * width + col] }

    @Test fun repeatedHalvesMustAppearTwiceOnTheSameAxis() {
        assertEquals("REVIEW_LEFT_RIGHT", halfFrameScreen(listOf(x, x)))
        assertEquals("REVIEW_TOP_BOTTOM", halfFrameScreen(listOf(y, y)))
        assertEquals("INCONCLUSIVE", halfFrameScreen(listOf(x, y)))
    }

    @Test fun flatOrSingleSampleDoesNotClearGeometry() {
        val flat = score { _, _ -> 0xaaaaaa }
        assertEquals("INCONCLUSIVE", halfFrameScreen(listOf(flat, flat)))
        assertEquals("INCONCLUSIVE", halfFrameScreen(listOf(x)))
        val good = score { col, row -> chart[row * width + col] }
        assertEquals("NO_REPEAT_FLAG", halfFrameScreen(listOf(good, good)))
    }
}
