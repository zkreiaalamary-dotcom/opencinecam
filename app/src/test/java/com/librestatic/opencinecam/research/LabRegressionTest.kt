/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import com.librestatic.opencinecam.camera.OpenCineLogGpuPipeline
import com.librestatic.opencinecam.camera.ResearchEncoderOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class LabRegressionTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun bug1_syntheticGlslShadersBeginWithVersionDirectiveWithoutLeadingIndentation() {
        val fieldVert = LabSyntheticEncoderProbe::class.java.getDeclaredField("SYNTHETIC_VERTEX_SHADER").apply { isAccessible = true }
        val fieldFrag = LabSyntheticEncoderProbe::class.java.getDeclaredField("SYNTHETIC_FRAGMENT_SHADER").apply { isAccessible = true }
        val vert = fieldVert.get(null) as String
        val frag = fieldFrag.get(null) as String
        assertTrue("Vertex shader must start with #version 300 es", vert.startsWith("#version 300 es\n"))
        assertTrue("Fragment shader must start with #version 300 es", frag.startsWith("#version 300 es\n"))
    }

    @Test
    fun bug2_explicitTargetBitratePreventsDoubleScalingAt60Fps() {
        val plan = OpenCineLogGpuPipeline.resolveBitratePlan(
            requestedTargetBps = 60_000_000,
            encoderRate = 60,
            codecMaxBps = 100_000_000,
            override = ResearchEncoderOverride(explicitTargetBitrateBps = 60_000_000),
        )
        assertEquals(60_000_000, plan.requestedTargetBps)
        assertEquals(60_000_000, plan.computedScaledBps)
        assertEquals(60_000_000, plan.sentToCodecBps)
        assertEquals(100_000_000, plan.codecMaxBps)
        assertFalse(plan.clampedByCodecMax)

        // Codec-max clamping is still explicitly disclosed when computed exceeds hardware limit
        val clamped = OpenCineLogGpuPipeline.resolveBitratePlan(
            requestedTargetBps = 120_000_000,
            encoderRate = 60,
            codecMaxBps = 100_000_000,
            override = ResearchEncoderOverride(explicitTargetBitrateBps = 120_000_000),
        )
        assertEquals(100_000_000, clamped.sentToCodecBps)
        assertTrue(clamped.clampedByCodecMax)
    }

    @Test
    fun bug4_codecLevelPairIngestsActualCodecArmResultEventsForH01() {
        val dir = folder.newFolder()
        val kb = LabKnowledgeBase.loadOrCreate(dir)
        val levelCase = LabCase("0", LabPath.CODEC_LEVEL_PAIR, 3840, 2160, 30, listed = false)
        val events = listOf(
            json("event" to "codec_arm_result", "arm" to "without_level", "level" to null, "admitted" to false),
            json("event" to "codec_arm_result", "arm" to "with_explicit_level", "level" to CodecProfileLevel.HEVCMainTierLevel51, "admitted" to true),
        )
        kb.ingestAttempt("013-pair", levelCase, LabVerdict.OBSERVED, null, events)
        assertEquals(HypothesisStatus.CONFIRMED, kb.hypothesis("H-01")?.status)
    }

    @Test
    fun bug5_flatToneMapAndVariantsDoNotCauseFalseContradictionInKnowledgeBase() {
        val dir = folder.newFolder()
        val kb = LabKnowledgeBase.loadOrCreate(dir)
        val base = LabCase("0", LabPath.LOG_REGULAR_4K30, 3840, 2160, 30, listed = false, flatToneMap = false, variant = "")
        val flat = base.copy(flatToneMap = true)
        val pbo = base.copy(variant = "scopes_pbo")
        val fileEvent = json("stage" to LabStage.FILE.name, "mime" to MediaFormat.MIMETYPE_VIDEO_HEVC, "observedFileFps" to 30.0)

        kb.ingestAttempt("004-base", base, LabVerdict.OBSERVED, null, listOf(fileEvent))
        kb.ingestAttempt("005-flat", flat, LabVerdict.FAILED, "curve failure", emptyList())
        kb.ingestAttempt("006-pbo", pbo, LabVerdict.MISMATCH, "mismatch", emptyList())

        assertEquals(0, kb.contradictions.size)
    }

    @Test
    fun zeroEncodedSamplesInSyntheticProbeMarksH02InconclusiveNotRefuted() {
        val dir = folder.newFolder()
        val kb = LabKnowledgeBase.loadOrCreate(dir)
        val synth = LabCase("0", LabPath.CODEC_SYNTHETIC_MATRIX, 3840, 2160, 30, listed = false)
        val events = listOf(
            json("event" to "synthetic_encoder_arm", "armId" to "main10_4k30", "encodedOk" to false, "encodedSamples" to 0, "step" to "egl_shader"),
        )
        kb.ingestAttempt("001-glsl", synth, LabVerdict.FAILED, "shader error", events)
        assertEquals(HypothesisStatus.INCONCLUSIVE, kb.hypothesis("H-02")?.status)
    }

    @Test
    fun safeLinearRolloffCurveIsMonotonicAndBounded() {
        val pts = requireNotNull(safeLinearRolloffCurve(32))
        assertEquals(64, pts.size)
        assertEquals(0f, pts[0], 1e-5f)
        assertEquals(0f, pts[1], 1e-5f)
        assertEquals(1f, pts[62], 1e-5f)
        assertTrue(pts[63] <= 0.96f)
        for (i in 1 until 32) {
            assertTrue(pts[i * 2] > pts[(i - 1) * 2])
            assertTrue(pts[i * 2 + 1] >= pts[(i - 1) * 2 + 1])
        }
    }

    @Test
    fun fileEventWithMimeBeatsHashEventAndBt2020FullBeatsBt709InKnowledgeBase() {
        val dir = folder.newFolder()
        val kb = LabKnowledgeBase.loadOrCreate(dir)

        // Synthetic matrix has BT.709 Limited first and BT.2020 Full OCLog2 second: must pick BT.2020 Full!
        val synth = LabCase("0", LabPath.CODEC_SYNTHETIC_MATRIX, 3840, 2160, 30, listed = false)
        kb.ingestAttempt(
            "001-synth",
            synth,
            LabVerdict.OBSERVED,
            null,
            listOf(
                json(
                    "event" to "synthetic_encoder_arm",
                    "armId" to "main10_bt709_egl8",
                    "fps" to 30,
                    "profile" to CodecProfileLevel.HEVCProfileMain10,
                    "level" to CodecProfileLevel.HEVCMainTierLevel62,
                    "colorStandard" to MediaFormat.COLOR_STANDARD_BT709,
                    "colorRange" to MediaFormat.COLOR_RANGE_LIMITED,
                    "egl10Bit" to false,
                    "encodedOk" to true,
                    "syntheticLayoutVerdict" to "PASS",
                    "rampEffectiveBits" to 8,
                ),
                json(
                    "event" to "synthetic_encoder_arm",
                    "armId" to "main10_oclog2_full_egl8",
                    "fps" to 30,
                    "profile" to CodecProfileLevel.HEVCProfileMain10,
                    "level" to CodecProfileLevel.HEVCMainTierLevel62,
                    "colorStandard" to MediaFormat.COLOR_STANDARD_BT2020,
                    "colorRange" to MediaFormat.COLOR_RANGE_FULL,
                    "egl10Bit" to false,
                    "encodedOk" to true,
                    "syntheticLayoutVerdict" to "PASS",
                    "rampEffectiveBits" to 8,
                ),
            ),
        )
        val picked = kb.bestOverrideFor(3840, 2160, 30, main10 = true)
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, picked?.colorStandard)
        assertEquals(MediaFormat.COLOR_RANGE_FULL, picked?.colorRange)

        // When decoded_frame_and_clip_hash (no mime) follows FILE metadata event, H-04 still gets mime + observedFileFps
        val log30 = LabCase("0", LabPath.LOG_REGULAR_4K30, 3840, 2160, 30, listed = false)
        kb.ingestAttempt(
            "003-log",
            log30,
            LabVerdict.OBSERVED,
            null,
            listOf(
                json("stage" to LabStage.FILE.name, "mime" to MediaFormat.MIMETYPE_VIDEO_HEVC, "codedBitDepthFromProfile" to 10, "observedFileFps" to 29.99),
                json("stage" to LabStage.FILE.name, "event" to "decoded_frame_and_clip_hash", "clipSha256" to "abc"),
            ),
        )
        assertEquals(HypothesisStatus.CONFIRMED, kb.hypothesis("H-04")?.status)
        assertTrue(kb.hypothesis("H-04")?.summary.orEmpty().contains("29.99"))
    }

    @Test
    fun kirinGammaAttenuatedRolloffCurveSuppressesMidtonesAndCapsHighlights() {
        val pts = requireNotNull(kirinGammaAttenuatedRolloffCurve(32))
        assertEquals(64, pts.size)
        assertEquals(0f, pts[0], 1e-5f)
        assertEquals(1f, pts[62], 1e-5f)
        assertTrue(pts[33] < 0.35f) // attenuated around mid-input (~0.5) matching Kirin post-gamma space
        assertTrue(pts[63] <= 0.90f) // smooth highlight shoulder ceiling
        for (i in 1 until 32) {
            assertTrue(pts[i * 2] > pts[(i - 1) * 2])
            assertTrue(pts[i * 2 + 1] >= pts[(i - 1) * 2 + 1])
        }
    }
}
