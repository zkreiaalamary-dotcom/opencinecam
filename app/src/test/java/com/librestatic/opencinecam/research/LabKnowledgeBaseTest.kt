/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class LabKnowledgeBaseTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun ingestsSyntheticMatrixSeparatesMain8AndMain10AndPersists() {
        val dir = folder.newFolder()
        val kb = LabKnowledgeBase.loadOrCreate(dir)
        val synthCase = LabCase("0", LabPath.CODEC_SYNTHETIC_MATRIX, 3840, 2160, 30, listed = false)
        val events = listOf(
            json(
                "event" to "synthetic_encoder_arm",
                "armId" to "main10_l51_oclog2_full_4k30_egl10_linear",
                "fps" to 30,
                "profile" to CodecProfileLevel.HEVCProfileMain10,
                "level" to CodecProfileLevel.HEVCMainTierLevel51,
                "colorStandard" to MediaFormat.COLOR_STANDARD_BT2020,
                "colorRange" to MediaFormat.COLOR_RANGE_FULL,
                "egl10Bit" to true,
                "eglColorSpace" to 0x333F,
                "encodedOk" to true,
                "syntheticLayoutVerdict" to "PASS",
                "rampEffectiveBits" to 10,
            ),
            json(
                "event" to "synthetic_encoder_arm",
                "armId" to "main8_l51_4k30_egl8",
                "fps" to 30,
                "profile" to CodecProfileLevel.HEVCProfileMain,
                "level" to CodecProfileLevel.HEVCMainTierLevel51,
                "colorStandard" to MediaFormat.COLOR_STANDARD_BT709,
                "colorRange" to MediaFormat.COLOR_RANGE_LIMITED,
                "colorTransfer" to MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                "egl10Bit" to false,
                "encodedOk" to true,
                "syntheticLayoutVerdict" to "PASS",
                "rampEffectiveBits" to 8,
            ),
        )

        kb.ingestAttempt("001-synth", synthCase, LabVerdict.OBSERVED, null, events)
        assertEquals(HypothesisStatus.CONFIRMED, kb.hypothesis("H-02")?.status)
        assertEquals(HypothesisStatus.CONFIRMED, kb.hypothesis("H-03")?.status)
        assertEquals(10, kb.bestCodedBitDepth)

        val main10Override = kb.bestOverrideFor(3840, 2160, 30, main10 = true)
        assertNotNull(main10Override)
        assertEquals(CodecProfileLevel.HEVCMainTierLevel51, main10Override?.explicitHevcLevel)
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, main10Override?.colorStandard)
        assertFalse(main10Override?.forceEgl8BitForMain10 ?: true)

        val main8Override = kb.bestOverrideFor(3840, 2160, 30, main10 = false)
        assertNotNull(main8Override)
        assertEquals(MediaFormat.COLOR_STANDARD_BT709, main8Override?.colorStandard)
        assertTrue(main8Override?.forceEgl8BitForMain10 ?: false)

        // Reload from disk and verify state persists
        val reloaded = LabKnowledgeBase.loadOrCreate(dir)
        assertEquals(2, reloaded.recipes.size)
        assertEquals(HypothesisStatus.CONFIRMED, reloaded.hypothesis("H-02")?.status)
    }

    @Test
    fun detectsContradictionAcrossRepeatedAttempts() {
        val dir = folder.newFolder()
        val kb = LabKnowledgeBase.loadOrCreate(dir)
        val c1 = LabCase("0", LabPath.LOG_REGULAR_4K30, 3840, 2160, 30, listed = false)
        val fileEvent = json(
            "stage" to LabStage.FILE.name,
            "mime" to MediaFormat.MIMETYPE_VIDEO_HEVC,
            "codedBitDepthFromProfile" to 10,
            "observedFileFps" to 29.97,
        )
        kb.ingestAttempt("002-log", c1, LabVerdict.OBSERVED, null, listOf(fileEvent))
        kb.ingestAttempt("003-retry", c1.copy(retryIndex = 1), LabVerdict.MISMATCH, "cadence drift", listOf(fileEvent))

        assertEquals(HypothesisStatus.CONTRADICTED, kb.hypothesis("H-04")?.status)
        assertEquals(1, kb.contradictions.size)
        assertTrue(kb.contradictions.single().attemptIds.containsAll(listOf("002-log", "003-retry")))
    }
}
