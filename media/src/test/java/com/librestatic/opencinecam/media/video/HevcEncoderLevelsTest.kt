/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.video

import android.media.MediaCodecInfo.CodecProfileLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HevcEncoderLevelsTest {
    @Test
    fun minimumMainTierLevelMatchesAnnexABounds() {
        assertEquals(HevcEncoderLevels.MAIN_TIER_40, HevcEncoderLevels.minimumMainTierLevel(1920, 1080, 30))
        assertEquals(HevcEncoderLevels.MAIN_TIER_41, HevcEncoderLevels.minimumMainTierLevel(1920, 1080, 60))
        assertEquals(HevcEncoderLevels.MAIN_TIER_50, HevcEncoderLevels.minimumMainTierLevel(1920, 1080, 120))
        assertEquals(HevcEncoderLevels.MAIN_TIER_50, HevcEncoderLevels.minimumMainTierLevel(3840, 2160, 30))
        assertEquals(HevcEncoderLevels.MAIN_TIER_51, HevcEncoderLevels.minimumMainTierLevel(3840, 2160, 60))
        assertEquals(HevcEncoderLevels.MAIN_TIER_52, HevcEncoderLevels.minimumMainTierLevel(3840, 2160, 120))
    }

    @Test
    fun selectHevcLevelPicksLowestMainTierAtOrAboveMinimum() {
        val puraAdvertised = listOf(
            CodecProfileLevel.HEVCMainTierLevel51,
            CodecProfileLevel.HEVCMainTierLevel62,
        )
        assertEquals(
            HevcEncoderLevels.MAIN_TIER_51,
            HevcEncoderLevels.selectHevcLevel(3840, 2160, 30, puraAdvertised),
        )
        assertEquals(
            HevcEncoderLevels.MAIN_TIER_51,
            HevcEncoderLevels.selectHevcLevel(3840, 2160, 60, puraAdvertised),
        )
        assertEquals(
            HevcEncoderLevels.MAIN_TIER_62,
            HevcEncoderLevels.selectHevcLevel(3840, 2160, 120, puraAdvertised),
        )
    }

    @Test
    fun selectHevcLevelFallsBackToHighestAdvertisedWhenBelowFloor() {
        val capped = listOf(HevcEncoderLevels.MAIN_TIER_41, HevcEncoderLevels.MAIN_TIER_50)
        assertEquals(HevcEncoderLevels.MAIN_TIER_50, HevcEncoderLevels.selectHevcLevel(3840, 2160, 60, capped))
        assertNull(HevcEncoderLevels.selectHevcLevel(3840, 2160, 30, emptyList()))
    }

    @Test
    fun levelNamesMatchExpectedStrings() {
        assertEquals("NONE", HevcEncoderLevels.hevcLevelName(null))
        assertEquals("MainTierLevel41", HevcEncoderLevels.hevcLevelName(CodecProfileLevel.HEVCMainTierLevel41))
        assertEquals("MainTierLevel5", HevcEncoderLevels.hevcLevelName(CodecProfileLevel.HEVCMainTierLevel5))
        assertEquals("MainTierLevel51", HevcEncoderLevels.hevcLevelName(CodecProfileLevel.HEVCMainTierLevel51))
        assertEquals("MainTierLevel52", HevcEncoderLevels.hevcLevelName(CodecProfileLevel.HEVCMainTierLevel52))
    }

    @Test
    fun selectHevcLevelForProfileSeparatesMain8AndMain10Levels() {
        // Mirrors Huawei Pura 80 Pro OMX.hisi.video.encoder.hevc where Main10 advertises only Level 6.2.
        val hisiProfileLevels = listOf(
            EncoderProfileLevel(CodecProfileLevel.HEVCProfileMain, CodecProfileLevel.HEVCMainTierLevel5),
            EncoderProfileLevel(CodecProfileLevel.HEVCProfileMain, CodecProfileLevel.HEVCMainTierLevel51),
            EncoderProfileLevel(CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCMainTierLevel62),
        )
        assertEquals(
            CodecProfileLevel.HEVCMainTierLevel51,
            HevcEncoderLevels.selectHevcLevelForProfile(CodecProfileLevel.HEVCProfileMain, 3840, 2160, 60, hisiProfileLevels),
        )
        assertEquals(
            CodecProfileLevel.HEVCMainTierLevel62,
            HevcEncoderLevels.selectHevcLevelForProfile(CodecProfileLevel.HEVCProfileMain10, 3840, 2160, 30, hisiProfileLevels),
        )
    }
}
