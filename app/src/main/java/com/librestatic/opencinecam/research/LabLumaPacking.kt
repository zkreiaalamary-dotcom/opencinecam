/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.research

import java.nio.ByteBuffer

/** Selected from the decoder's Image format, independently of the sampled pixel values. */
internal enum class LabLumaPacking(val bits: Int, private val bytes: Int) {
    YUV8(8, 1),
    P010_MSB(10, 2);

    fun read(littleEndian: ByteBuffer, offset: Int): Int? {
        if (offset < 0 || offset > littleEndian.limit() - bytes) return null
        return when (this) {
            YUV8 -> littleEndian.get(offset).toInt() and 0xff
            P010_MSB -> (littleEndian.getShort(offset).toInt() and 0xffff) ushr 6
        }
    }

    fun previewCode(code: Int): Int {
        val maxCode = (1 shl bits) - 1
        return (code * 255 + maxCode / 2) / maxCode
    }
}
