/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.video

import android.media.MediaCodecInfo.CodecProfileLevel

/**
 * Pure HEVC Main-tier level selection based on H.265 Annex A luma picture size and sample-rate
 * bounds. HiSilicon MediaCodec configure requires an explicit KEY_LEVEL matching the target raster
 * and frame rate.
 */
object HevcEncoderLevels {
    // Numeric CodecProfileLevel constants kept explicit so pure-JVM logic never drifts.
    const val MAIN_TIER_31 = 256
    const val MAIN_TIER_40 = 1024
    const val MAIN_TIER_41 = 4096
    const val MAIN_TIER_50 = 16384
    const val MAIN_TIER_51 = 65536
    const val MAIN_TIER_52 = 262144
    const val MAIN_TIER_60 = 1048576
    const val MAIN_TIER_61 = 4194304
    const val MAIN_TIER_62 = 16777216

    private data class LevelBound(
        val level: Int,
        val maxLumaPictureSize: Long,
        val maxLumaSamplesPerSecond: Long,
        val label: String,
    )

    // H.265 Annex A Table A.1 / A.2 Main tier bounds.
    private val ORDERED_MAIN_TIER = listOf(
        LevelBound(MAIN_TIER_31, 983_040L, 33_177_600L, "MainTierLevel31"),
        LevelBound(MAIN_TIER_40, 2_228_224L, 66_846_720L, "MainTierLevel4"),
        LevelBound(MAIN_TIER_41, 2_228_224L, 133_693_440L, "MainTierLevel41"),
        LevelBound(MAIN_TIER_50, 8_912_896L, 267_386_880L, "MainTierLevel5"),
        LevelBound(MAIN_TIER_51, 8_912_896L, 534_773_760L, "MainTierLevel51"),
        LevelBound(MAIN_TIER_52, 8_912_896L, 1_069_547_520L, "MainTierLevel52"),
        LevelBound(MAIN_TIER_60, 35_651_584L, 1_069_547_520L, "MainTierLevel6"),
        LevelBound(MAIN_TIER_61, 35_651_584L, 2_139_095_040L, "MainTierLevel61"),
        LevelBound(MAIN_TIER_62, 35_651_584L, 4_278_190_080L, "MainTierLevel62"),
    )

    fun minimumMainTierLevel(width: Int, height: Int, fps: Int): Int {
        require(width > 0 && height > 0 && fps > 0) { "Dimensions and frame rate must be positive" }
        val picSize = width.toLong() * height.toLong()
        val samplesPerSecond = picSize * fps.toLong()
        return ORDERED_MAIN_TIER.firstOrNull {
            picSize <= it.maxLumaPictureSize && samplesPerSecond <= it.maxLumaSamplesPerSecond
        }?.level ?: MAIN_TIER_62
    }

    /**
     * Picks the lowest advertised Main-tier level >= [minimumMainTierLevel], falling back to the
     * highest advertised Main-tier level or highest advertised level if none meet the floor.
     */
    fun selectHevcLevel(width: Int, height: Int, fps: Int, advertisedLevels: Collection<Int>): Int? {
        if (advertisedLevels.isEmpty()) return null
        val minimum = minimumMainTierLevel(width, height, fps)
        val mainTierLevels = ORDERED_MAIN_TIER.map { it.level }.toSet()
        val advertisedMain = advertisedLevels.filter { it in mainTierLevels }.sorted()
        return advertisedMain.firstOrNull { it >= minimum }
            ?: advertisedMain.lastOrNull()
            ?: advertisedLevels.maxOrNull()
    }

    fun hevcLevelName(level: Int?): String = when (level) {
        null -> "NONE"
        CodecProfileLevel.HEVCMainTierLevel31 -> "MainTierLevel31"
        CodecProfileLevel.HEVCMainTierLevel4 -> "MainTierLevel4"
        CodecProfileLevel.HEVCMainTierLevel41 -> "MainTierLevel41"
        CodecProfileLevel.HEVCMainTierLevel5 -> "MainTierLevel5"
        CodecProfileLevel.HEVCMainTierLevel51 -> "MainTierLevel51"
        CodecProfileLevel.HEVCMainTierLevel52 -> "MainTierLevel52"
        CodecProfileLevel.HEVCMainTierLevel6 -> "MainTierLevel6"
        CodecProfileLevel.HEVCMainTierLevel61 -> "MainTierLevel61"
        CodecProfileLevel.HEVCMainTierLevel62 -> "MainTierLevel62"
        else -> "Level($level)"
    }
}
