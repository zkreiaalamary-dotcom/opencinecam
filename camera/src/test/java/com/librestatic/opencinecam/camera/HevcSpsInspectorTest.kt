/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HevcSpsInspectorTest {
    private class BitWriter {
        private val bits = ArrayList<Int>()

        fun u(value: Int, width: Int) {
            for (i in width - 1 downTo 0) bits += (value ushr i) and 1
        }

        fun flag(value: Boolean) = u(if (value) 1 else 0, 1)

        fun ue(value: Int) {
            val codeNum = value + 1
            val len = 32 - Integer.numberOfLeadingZeros(codeNum)
            repeat(len - 1) { bits += 0 }
            u(codeNum, len)
        }

        fun toRbsp(): ByteArray {
            // RBSP trailing bits
            bits += 1
            while (bits.size % 8 != 0) bits += 0
            val out = ByteArray(bits.size / 8)
            for (i in out.indices) {
                var b = 0
                for (j in 0 until 8) b = (b shl 1) or bits[i * 8 + j]
                out[i] = b.toByte()
            }
            return out
        }
    }

    private fun escapeRbsp(rbsp: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(rbsp.size + 4)
        var zeros = 0
        for (b in rbsp) {
            val v = b.toInt() and 0xFF
            if (zeros == 2 && v <= 3) {
                out.write(3)
                zeros = 0
            }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private fun buildSyntheticSpsNal(
        profileIdc: Int,
        levelIdc: Int,
        codedWidth: Int,
        codedHeight: Int,
        cropRight: Int,
        cropBottom: Int,
        bitDepth: Int,
        fullRange: Boolean,
        primaries: Int,
        transfer: Int,
        matrix: Int,
    ): ByteArray {
        val w = BitWriter()
        // NAL header: forbidden=0, type=33 (SPS), layer_id=0, temporal_id_plus1=1
        w.u(0, 1); w.u(33, 6); w.u(0, 6); w.u(1, 3)
        w.u(0, 4) // vps_id
        w.u(0, 3) // max_sub_layers_minus1
        w.flag(true) // temporal_id_nesting_flag
        w.u(0, 2) // general_profile_space
        w.u(0, 1) // general_tier_flag
        w.u(profileIdc, 5)
        w.u(0, 32) // profile compatibility
        w.u(0, 24); w.u(0, 24) // 48 constraint bits
        w.u(levelIdc, 8)
        w.ue(0) // sps_id
        w.ue(1) // chroma_format_idc = 1 (4:2:0)
        w.ue(codedWidth)
        w.ue(codedHeight)
        val hasCrop = cropRight > 0 || cropBottom > 0
        w.flag(hasCrop)
        if (hasCrop) {
            w.ue(0); w.ue(cropRight); w.ue(0); w.ue(cropBottom)
        }
        w.ue(bitDepth - 8)
        w.ue(bitDepth - 8)
        w.ue(4) // log2_max_pic_order_cnt_lsb_minus4 = 4
        w.flag(false) // sub_layer_ordering_info_present_flag
        repeat(3) { w.ue(0) } // max_dec_pic_buffering, max_num_reorder_pics, max_latency_increase
        repeat(6) { w.ue(0) } // log2_min_luma_cb..max_transform_hierarchy
        w.flag(false) // scaling_list_enabled_flag
        w.flag(false) // amp_enabled_flag
        w.flag(true) // sample_adaptive_offset_enabled_flag
        w.flag(false) // pcm_enabled_flag
        w.ue(0) // num_short_term_ref_pic_sets
        w.flag(false) // long_term_ref_pics_present_flag
        w.flag(true) // sps_temporal_mvp_enabled_flag
        w.flag(true) // strong_intra_smoothing_enabled_flag
        w.flag(true) // vui_parameters_present_flag
        w.flag(false) // aspect_ratio_info_present_flag
        w.flag(false) // overscan_info_present_flag
        w.flag(true) // video_signal_type_present_flag
        w.u(5, 3) // video_format = Unspecified
        w.flag(fullRange)
        w.flag(true) // colour_description_present_flag
        w.u(primaries, 8)
        w.u(transfer, 8)
        w.u(matrix, 8)
        return escapeRbsp(w.toRbsp())
    }

    private fun wrapAnnexB(nal: ByteArray): ByteArray = byteArrayOf(0, 0, 0, 1) + nal

    private fun wrapHvcc(spsNal: ByteArray): ByteArray {
        val header = ByteArray(23)
        header[0] = 1 // configurationVersion
        header[22] = 1 // numOfArrays = 1
        val len = spsNal.size
        val arrayHeader = byteArrayOf(
            33.toByte(), // array_completeness=0, nal_unit_type=33
            0, 1, // numNalus = 1
            ((len ushr 8) and 0xFF).toByte(),
            (len and 0xFF).toByte(),
        )
        return header + arrayHeader + spsNal
    }

    @Test
    fun parsesAnnexB8BitMainWithConformanceCrop() {
        val sps = buildSyntheticSpsNal(
            profileIdc = 1,
            levelIdc = 153, // L5.1
            codedWidth = 3840,
            codedHeight = 2176,
            cropRight = 0,
            cropBottom = 8, // 2176 - 2*8 = 2160
            bitDepth = 8,
            fullRange = false,
            primaries = 1,
            transfer = 1,
            matrix = 1,
        )
        val info = HevcSpsInspector.inspect(wrapAnnexB(sps))
        assertNotNull(info)
        requireNotNull(info)
        assertEquals(1, info.profileIdc)
        assertEquals(153, info.levelIdc)
        assertEquals(8, info.bitDepthLuma)
        assertEquals(8, info.bitDepthChroma)
        assertEquals(3840, info.codedWidth)
        assertEquals(2176, info.codedHeight)
        assertEquals(3840, info.displayWidth)
        assertEquals(2160, info.displayHeight)
        assertEquals(false, info.fullRange)
        assertEquals(1, info.colourPrimaries)
        assertEquals(1, info.transferCharacteristics)
        assertEquals(1, info.matrixCoeffs)
    }

    @Test
    fun parsesHvccMain10Bt2020FullRangeUnspecifiedTransfer() {
        val sps = buildSyntheticSpsNal(
            profileIdc = 2,
            levelIdc = 156, // L5.2
            codedWidth = 3840,
            codedHeight = 2160,
            cropRight = 0,
            cropBottom = 0,
            bitDepth = 10,
            fullRange = true,
            primaries = 9,
            transfer = 2,
            matrix = 9,
        )
        val info = HevcSpsInspector.inspect(wrapHvcc(sps))
        assertNotNull(info)
        requireNotNull(info)
        assertEquals(2, info.profileIdc)
        assertEquals(156, info.levelIdc)
        assertEquals(10, info.bitDepthLuma)
        assertEquals(10, info.bitDepthChroma)
        assertEquals(3840, info.codedWidth)
        assertEquals(2160, info.codedHeight)
        assertEquals(true, info.fullRange)
        assertEquals(9, info.colourPrimaries)
        assertEquals(2, info.transferCharacteristics)
        assertEquals(9, info.matrixCoeffs)
    }

    @Test
    fun malformedCsdReturnsNull() {
        assertNull(HevcSpsInspector.inspect(byteArrayOf()))
        assertNull(HevcSpsInspector.inspect(byteArrayOf(0, 0, 0, 1, 64, 1)))
    }
}
