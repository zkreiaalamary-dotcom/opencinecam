/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.research

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun contentUniquenessDistinguishesStaticFromDynamicFrames() {
        val identical = IntArray(width * height) { 0x4080c0 }
        val shifted = IntArray(width * height) { i ->
            val xPos = i % width
            if (xPos in 10..25) 0xe02020 else 0x4080c0
        }
        assertEquals(0.0, frameContentMad(identical, identical.copyOf()), 1e-9)
        assertTrue(frameContentMad(identical, shifted) > 10.0)
        assertEquals("STATIC_OR_DUPLICATE", contentUniquenessVerdict(listOf(0.0, 0.0), listOf(0.0)))
        assertEquals("DYNAMIC_CONFIRMED", contentUniquenessVerdict(listOf(1.2, 0.9), listOf(4.5)))
        assertEquals("INCONCLUSIVE", contentUniquenessVerdict(emptyList(), emptyList()))
    }

    @Test fun verifySyntheticQuadrantsDetectsValidRepeatedAndCorruptLayouts() {
        val w = 64
        val h = 64
        fun rgb(r: Int, g: Int, b: Int): Int = ((r and 255) shl 16) or ((g and 255) shl 8) or (b and 255)
        val valid = IntArray(w * h) { i ->
            val col = i % w
            val row = i / w
            when {
                col < w / 2 && row < h / 2 -> {
                    val checker = ((col / 3) + (row / 3)) and 1
                    if (checker == 0) rgb(230, 30, 15) else rgb(90, 55, 30)
                }
                col >= w / 2 && row < h / 2 -> {
                    val v = ((col - w / 2) * 255) / (w / 2 - 1)
                    rgb(v, v, v)
                }
                col < w / 2 && row >= h / 2 -> {
                    val bar = ((col / 3) + (row / 5)) and 1
                    if (bar == 0) rgb(20, 220, 110) else rgb(50, 95, 190)
                }
                else -> {
                    val stripe = ((col * 3 + row * 5) / 7) and 1
                    if (stripe == 0) rgb(35, 55, 225) else rgb(235, 195, 25)
                }
            }
        }
        assertEquals("PASS", verifySyntheticQuadrants(valid, w, h).verdict)

        val repeatX = IntArray(w * h) { i ->
            val col = i % w
            val row = i / w
            valid[row * w + (col % (w / 2))]
        }
        assertEquals("REPEAT_HALF_X", verifySyntheticQuadrants(repeatX, w, h).verdict)

        val repeatY = IntArray(w * h) { i ->
            val col = i % w
            val row = i / w
            valid[(row % (h / 2)) * w + col]
        }
        assertEquals("REPEAT_HALF_Y", verifySyntheticQuadrants(repeatY, w, h).verdict)

        val invertedColors = IntArray(w * h) { rgb(10, 10, 10) }
        assertEquals("CORRUPT_LAYOUT", verifySyntheticQuadrants(invertedColors, w, h).verdict)
    }

    @Test fun rampPrecisionDistinguishes8BitPaddedFrom10BitLinearRamp() {
        val eightBitShifted = IntArray(1024) { i -> (i ushr 2) shl 2 }
        val m8 = analyzeRampPrecision(eightBitShifted, declaredBitDepth = 10)
        assertEquals(256, m8.distinctValues)
        assertEquals(4, m8.minPositiveStep)
        assertEquals(1.0, m8.mod4ZeroFraction, 1e-6)
        assertEquals(8, m8.effectiveBitsEstimate)

        val tenBitLinear = IntArray(1024) { it }
        val m10 = analyzeRampPrecision(tenBitLinear, declaredBitDepth = 10)
        assertEquals(1024, m10.distinctValues)
        assertEquals(1, m10.minPositiveStep)
        assertEquals(0.25, m10.mod4ZeroFraction, 1e-6)
        assertEquals(10, m10.effectiveBitsEstimate)
    }
}
