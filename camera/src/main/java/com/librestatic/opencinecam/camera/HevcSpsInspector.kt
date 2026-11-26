/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

/**
 * Independent parser for HEVC Sequence Parameter Sets (SPS, NAL unit type 33) from either Annex-B
 * byte streams (`csd-0` from MediaCodec) or ISO-BMFF `hvcC` configuration records.
 * Reuses [HevcVuiTransfer.nalUnits] and [HevcVuiTransfer.unescape].
 */
data class HevcSpsInfo(
    val profileSpace: Int,
    val tierFlag: Int,
    val profileIdc: Int,
    val levelIdc: Int,
    val chromaFormatIdc: Int,
    val codedWidth: Int,
    val codedHeight: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    val bitDepthLuma: Int,
    val bitDepthChroma: Int,
    val vuiPresent: Boolean,
    val fullRange: Boolean?,
    val colourPrimaries: Int?,
    val transferCharacteristics: Int?,
    val matrixCoeffs: Int?,
)

object HevcSpsInspector {
    private const val SPS_NAL_TYPE = 33

    /** Inspects either an Annex-B stream or an `hvcC` configuration box and returns the first SPS. */
    fun inspect(csd: ByteArray): HevcSpsInfo? = runCatching {
        val spsPayloads = extractSpsPayloads(csd)
        spsPayloads.firstNotNullOfOrNull { payload -> parseSpsRbsp(HevcVuiTransfer.unescape(payload)) }
    }.getOrNull()

    private fun extractSpsPayloads(data: ByteArray): List<ByteArray> {
        if (data.size >= 4 && data[0].toInt() == 0 && data[1].toInt() == 0 &&
            (data[2].toInt() == 1 || (data[2].toInt() == 0 && data[3].toInt() == 1))
        ) {
            return HevcVuiTransfer.nalUnits(data).mapNotNull { (start, end) ->
                if (end - start >= 2 && ((data[start].toInt() shr 1) and 0x3F) == SPS_NAL_TYPE) {
                    data.copyOfRange(start, end)
                } else null
            }
        }
        // ISO-BMFF HEVCDecoderConfigurationRecord (configurationVersion == 1, min 23 bytes).
        if (data.size >= 23 && (data[0].toInt() and 0xFF) == 1) {
            val out = ArrayList<ByteArray>()
            val numArrays = data[22].toInt() and 0xFF
            var offset = 23
            for (a in 0 until numArrays) {
                if (offset + 3 > data.size) break
                val nalType = data[offset].toInt() and 0x3F
                val numNalus = ((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset + 2].toInt() and 0xFF)
                offset += 3
                for (n in 0 until numNalus) {
                    if (offset + 2 > data.size) break
                    val len = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
                    offset += 2
                    if (offset + len > data.size) break
                    if (nalType == SPS_NAL_TYPE && len >= 2) {
                        out += data.copyOfRange(offset, offset + len)
                    }
                    offset += len
                }
            }
            return out
        }
        return emptyList()
    }

    private fun parseSpsRbsp(rbsp: ByteArray): HevcSpsInfo {
        val r = BitReader(rbsp)
        r.skip(16) // NAL header
        r.skip(4) // sps_video_parameter_set_id
        val maxSubLayersMinus1 = r.u(3)
        r.skip(1) // sps_temporal_id_nesting_flag

        val profileSpace = r.u(2)
        val tierFlag = r.u(1)
        val profileIdc = r.u(5)
        r.skip(32) // general_profile_compatibility_flag[32]
        r.skip(48) // constraint flags
        val levelIdc = r.u(8)
        val profilePresent = BooleanArray(maxSubLayersMinus1)
        val levelPresent = BooleanArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) {
            profilePresent[i] = r.flag()
            levelPresent[i] = r.flag()
        }
        if (maxSubLayersMinus1 > 0) r.skip(2 * (8 - maxSubLayersMinus1))
        for (i in 0 until maxSubLayersMinus1) {
            if (profilePresent[i]) r.skip(88)
            if (levelPresent[i]) r.skip(8)
        }

        r.ue() // sps_seq_parameter_set_id
        val chromaFormatIdc = r.ue()
        if (chromaFormatIdc == 3) r.skip(1) // separate_colour_plane_flag
        val codedWidth = r.ue()
        val codedHeight = r.ue()
        val confWin = r.flag()
        var displayWidth = codedWidth
        var displayHeight = codedHeight
        if (confWin) {
            val left = r.ue()
            val right = r.ue()
            val top = r.ue()
            val bottom = r.ue()
            val subWidthC = if (chromaFormatIdc == 1 || chromaFormatIdc == 2) 2 else 1
            val subHeightC = if (chromaFormatIdc == 1) 2 else 1
            displayWidth = (codedWidth - subWidthC * (left + right)).coerceAtLeast(1)
            displayHeight = (codedHeight - subHeightC * (top + bottom)).coerceAtLeast(1)
        }
        val bitDepthLuma = r.ue() + 8
        val bitDepthChroma = r.ue() + 8
        val log2MaxPocLsb = r.ue() + 4
        val orderingForAll = r.flag()
        repeat(if (orderingForAll) maxSubLayersMinus1 + 1 else 1) { repeat(3) { r.ue() } }
        repeat(6) { r.ue() }
        if (r.flag() && r.flag()) scalingListData(r)
        r.skip(2)
        if (r.flag()) {
            r.skip(8)
            r.ue(); r.ue()
            r.skip(1)
        }
        shortTermRefPicSets(r)
        if (r.flag()) repeat(r.ue()) { r.skip(log2MaxPocLsb + 1) }
        r.skip(2)

        val vuiPresent = r.flag()
        var fullRange: Boolean? = null
        var colourPrimaries: Int? = null
        var transferCharacteristics: Int? = null
        var matrixCoeffs: Int? = null
        if (vuiPresent) {
            if (r.flag() && r.u(8) == 255) r.skip(32)
            if (r.flag()) r.skip(1)
            if (r.flag()) { // video_signal_type_present_flag
                r.skip(3) // video_format
                fullRange = r.flag()
                if (r.flag()) { // colour_description_present_flag
                    colourPrimaries = r.u(8)
                    transferCharacteristics = r.u(8)
                    matrixCoeffs = r.u(8)
                }
            }
        }
        return HevcSpsInfo(
            profileSpace = profileSpace,
            tierFlag = tierFlag,
            profileIdc = profileIdc,
            levelIdc = levelIdc,
            chromaFormatIdc = chromaFormatIdc,
            codedWidth = codedWidth,
            codedHeight = codedHeight,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            bitDepthLuma = bitDepthLuma,
            bitDepthChroma = bitDepthChroma,
            vuiPresent = vuiPresent,
            fullRange = fullRange,
            colourPrimaries = colourPrimaries,
            transferCharacteristics = transferCharacteristics,
            matrixCoeffs = matrixCoeffs,
        )
    }

    private fun scalingListData(r: BitReader) {
        for (sizeId in 0..3) {
            for (matrixId in 0 until 6 step if (sizeId == 3) 3 else 1) {
                if (!r.flag()) r.ue()
                else {
                    if (sizeId > 1) r.se()
                    repeat(minOf(64, 1 shl (4 + (sizeId shl 1)))) { r.se() }
                }
            }
        }
    }

    private fun shortTermRefPicSets(r: BitReader) {
        val count = r.ue()
        require(count <= 64) { "num_short_term_ref_pic_sets out of range: $count" }
        val negatives = ArrayList<IntArray>(count)
        val positives = ArrayList<IntArray>(count)
        for (index in 0 until count) {
            if (index != 0 && r.flag()) {
                val refNegative = negatives[index - 1]
                val refPositive = positives[index - 1]
                val sign = r.u(1)
                val deltaRps = (1 - 2 * sign) * (r.ue() + 1)
                val refCount = refNegative.size + refPositive.size
                val useDelta = BooleanArray(refCount + 1) { r.flag() || r.flag() }
                val s0 = ArrayList<Int>()
                for (j in refPositive.indices.reversed()) {
                    val dPoc = refPositive[j] + deltaRps
                    if (dPoc < 0 && useDelta[refNegative.size + j]) s0 += dPoc
                }
                if (deltaRps < 0 && useDelta[refCount]) s0 += deltaRps
                for (j in refNegative.indices) {
                    val dPoc = refNegative[j] + deltaRps
                    if (dPoc < 0 && useDelta[j]) s0 += dPoc
                }
                val s1 = ArrayList<Int>()
                for (j in refNegative.indices.reversed()) {
                    val dPoc = refNegative[j] + deltaRps
                    if (dPoc > 0 && useDelta[j]) s1 += dPoc
                }
                if (deltaRps > 0 && useDelta[refCount]) s1 += deltaRps
                for (j in refPositive.indices) {
                    val dPoc = refPositive[j] + deltaRps
                    if (dPoc > 0 && useDelta[refNegative.size + j]) s1 += dPoc
                }
                negatives += s0.toIntArray()
                positives += s1.toIntArray()
            } else {
                val negativeCount = r.ue()
                val positiveCount = r.ue()
                require(negativeCount <= 16 && positiveCount <= 16)
                var poc = 0
                negatives += IntArray(negativeCount) { poc -= r.ue() + 1; r.skip(1); poc }
                poc = 0
                positives += IntArray(positiveCount) { poc += r.ue() + 1; r.skip(1); poc }
            }
        }
    }

    private class BitReader(private val data: ByteArray) {
        var position = 0
            private set

        fun skip(bits: Int) {
            position += bits
            require(position <= data.size * 8) { "SPS ended early" }
        }

        fun u(bits: Int): Int {
            require(position + bits <= data.size * 8) { "SPS ended early" }
            var value = 0
            for (bit in position until position + bits) {
                value = (value shl 1) or ((data[bit ushr 3].toInt() shr (7 - (bit and 7))) and 1)
            }
            position += bits
            return value
        }

        fun flag(): Boolean = u(1) == 1

        fun ue(): Int {
            var zeros = 0
            while (u(1) == 0) require(++zeros <= 31) { "Exp-Golomb code too long" }
            return ((1L shl zeros) - 1 + u(zeros).toLong()).toInt()
        }

        fun se(): Int {
            val code = ue()
            return if (code and 1 == 1) (code + 1) / 2 else -(code / 2)
        }
    }
}
