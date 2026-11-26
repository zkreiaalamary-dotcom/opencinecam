/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class LabAutonomousPlannerTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun escalatesCleanlyAcrossStagesAndBoundsRetries() {
        val kb = LabKnowledgeBase.loadOrCreate(folder.newFolder())
        val synth = LabCase("0", LabPath.CODEC_SYNTHETIC_MATRIX, 3840, 2160, 30, listed = false)
        val log30 = LabCase("0", LabPath.LOG_REGULAR_4K30, 3840, 2160, 30, listed = false)
        val log30Flat = log30.copy(flatToneMap = true)
        val gpu30 = LabCase("0", LabPath.GPU_HEVC_4K30, 3840, 2160, 30, listed = false)
        val log60 = LabCase("0", LabPath.LOG_REGULAR_4K60, 3840, 2160, 60, listed = false)
        val log60Long = log60.copy(longer = true)
        val gpu60 = LabCase("0", LabPath.GPU_HEVC_4K60, 3840, 2160, 60, listed = false)
        val directSt = LabCase("0", LabPath.DIRECT_HEVC_SURFACETEXTURE, 3840, 2160, 60, listed = false)
        val directStLong = directSt.copy(longer = true)
        val toneAb = LabCase("0", LabPath.ISP_TONEMAP_AB, 3840, 2160, 30, listed = false)
        val ccProbe = LabCase("0", LabPath.ISP_COLOR_CORRECTION_PROBE, 3840, 2160, 30, listed = false)
        val fmtProbe = LabCase("0", LabPath.SOURCE_FORMAT_PROBE, 3840, 2160, 30, listed = false)

        val candidates = listOf(synth, log30, log30Flat, gpu30, log60, gpu60, directSt, directStLong, toneAb, ccProbe, fmtProbe)
        val planner = LabAutonomousPlanner(candidates, kb)

        val history = mutableListOf<LabResult>()
        assertEquals(synth, planner.next(history)?.attempt)
        history += LabResult(synth, LabVerdict.OBSERVED, LabStage.FILE, "a1")

        // Stage B: 4K30 LOG -> if MISMATCH, retries once, then if retry OBSERVED proceeds to FLAT & GPU_HEVC_4K30
        assertEquals(log30, planner.next(history)?.attempt)
        history += LabResult(log30, LabVerdict.MISMATCH, LabStage.FILE, "a2")
        val retry = planner.next(history)
        assertEquals(log30.copy(retryIndex = 1), retry?.attempt)
        history += LabResult(retry!!.attempt, LabVerdict.OBSERVED, LabStage.FILE, "a3")

        // Next: 4K30 FLAT tone map (since retry succeeded with OBSERVED), then GPU_HEVC_4K30
        assertEquals(log30Flat, planner.next(history)?.attempt)
        history += LabResult(log30Flat, LabVerdict.OBSERVED, LabStage.FILE, "a3b")
        assertEquals(gpu30, planner.next(history)?.attempt)
        history += LabResult(gpu30, LabVerdict.OBSERVED, LabStage.FILE, "a4")

        // Stage C: 4K30 passed -> escalate to 4K60 LOG -> 10 s 4K60 LOG -> GPU_HEVC_4K60
        assertEquals(log60, planner.next(history)?.attempt)
        history += LabResult(log60, LabVerdict.OBSERVED, LabStage.FILE, "a5")
        assertEquals(log60Long, planner.next(history)?.attempt)
        history += LabResult(log60Long, LabVerdict.OBSERVED, LabStage.FILE, "a6")
        assertEquals(gpu60, planner.next(history)?.attempt)
        history += LabResult(gpu60, LabVerdict.OBSERVED, LabStage.FILE, "a7")

        // Stage D: direct SurfaceTexture 4s -> 30s sustained
        assertEquals(directSt, planner.next(history)?.attempt)
        history += LabResult(directSt, LabVerdict.OBSERVED, LabStage.FILE, "a8")
        assertEquals(directStLong, planner.next(history)?.attempt)
        history += LabResult(directStLong, LabVerdict.OBSERVED, LabStage.FILE, "a9")

        // Stage E: ISP ToneMap A/B -> ISP ColorCorrection Probe -> Source Format Probe
        assertEquals(toneAb, planner.next(history)?.attempt)
        history += LabResult(toneAb, LabVerdict.OBSERVED, LabStage.FILE, "a10")
        assertEquals(ccProbe, planner.next(history)?.attempt)
        history += LabResult(ccProbe, LabVerdict.OBSERVED, LabStage.FILE, "a10b")
        assertEquals(fmtProbe, planner.next(history)?.attempt)
        history += LabResult(fmtProbe, LabVerdict.OBSERVED, LabStage.FILE, "a11")

        // No infinite loop on retry: even if failed twice, planner moves on
        assertNotNull(planner.stageLabel(log60))
        assertNull(planner.next(history))
    }

    @Test
    fun walks4k60OptimizationLadderWithoutWastingRetriesAndStopsEarlyOnSuccess() {
        val kb = LabKnowledgeBase.loadOrCreate(folder.newFolder())
        val synth = LabCase("0", LabPath.CODEC_SYNTHETIC_MATRIX, 3840, 2160, 30, listed = false)
        val log30 = LabCase("0", LabPath.LOG_REGULAR_4K30, 3840, 2160, 30, listed = false)
        val log60 = LabCase("0", LabPath.LOG_REGULAR_4K60, 3840, 2160, 60, listed = false)
        val log60Rt = log60.copy(variant = "realtime")
        val log60Ctx = log60.copy(variant = "single_ctx")
        val log60Prev30 = log60.copy(variant = "prev_30fps")
        val log60NoPrev = log60.copy(variant = "no_preview")
        val cuva = LabCase("0", LabPath.ISP_CUVA_HDR_PROBE, 3840, 2160, 30, listed = false)

        val candidates = listOf(synth, log30, log60, log60Rt, log60Ctx, log60Prev30, log60NoPrev, cuva)
        val planner = LabAutonomousPlanner(candidates, kb)
        val history = mutableListOf(
            LabResult(synth, LabVerdict.OBSERVED, LabStage.FILE, "a1"),
            LabResult(log30, LabVerdict.OBSERVED, LabStage.FILE, "a2"),
            LabResult(log60, LabVerdict.MISMATCH, LabStage.FILE, "a3"),
        )

        // Immediately tries realtime without wasting retry on 4K60 MISMATCH
        assertEquals(log60Rt, planner.next(history)?.attempt)
        history += LabResult(log60Rt, LabVerdict.MISMATCH, LabStage.FILE, "a4")

        // Moves directly to single_ctx (no retry on deterministic MISMATCH variant)
        assertEquals(log60Ctx, planner.next(history)?.attempt)
        history += LabResult(log60Ctx, LabVerdict.MISMATCH, LabStage.FILE, "a5")

        // Succeeds on prev_30fps -> stops ladder and runs 10s sustained take on winning variant
        assertEquals(log60Prev30, planner.next(history)?.attempt)
        history += LabResult(log60Prev30, LabVerdict.OBSERVED, LabStage.FILE, "a6")
        assertEquals(log60Prev30.copy(longer = true), planner.next(history)?.attempt)
        history += LabResult(log60Prev30.copy(longer = true), LabVerdict.OBSERVED, LabStage.FILE, "a7")

        // Should NOT schedule no_preview once prev_30fps succeeded; moves to Stage E CUVA probe
        assertEquals(cuva, planner.next(history)?.attempt)
        assertNotNull(planner.stageLabel(cuva))
    }
}
