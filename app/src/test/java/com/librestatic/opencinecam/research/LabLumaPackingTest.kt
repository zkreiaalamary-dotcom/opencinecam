/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.research

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LabLumaPackingTest {
    @Test
    fun p010UsesMsbEvenWhenEveryWordFitsInTenBits() {
        val samples = listOf(0, 8, 15, 64, 940, 1023)
        val data = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEachIndexed { index, value -> data.putShort(index * 4, (value shl 6).toShort()) }
        assertEquals(samples, samples.indices.map { LabLumaPacking.P010_MSB.read(data, it * 4) })
        assertEquals(2, LabLumaPacking.P010_MSB.previewCode(8))
        assertNull(LabLumaPacking.P010_MSB.read(data, data.limit() - 1))
    }
}
