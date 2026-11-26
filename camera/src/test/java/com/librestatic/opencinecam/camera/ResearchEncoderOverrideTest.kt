/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchEncoderOverrideTest {
    @Test
    fun resolveEffectiveLevelAppliesOnlyToHevcWhenOverrideIsSet() {
        val override = ResearchEncoderOverride(explicitHevcLevel = CodecProfileLevel.HEVCMainTierLevel51)
        assertEquals(
            CodecProfileLevel.HEVCMainTierLevel51,
            OpenCineLogGpuPipeline.resolveEffectiveLevel(MediaFormat.MIMETYPE_VIDEO_HEVC, null, override),
        )
        assertEquals(
            CodecProfileLevel.AVCLevel51,
            OpenCineLogGpuPipeline.resolveEffectiveLevel(MediaFormat.MIMETYPE_VIDEO_AVC, CodecProfileLevel.AVCLevel51, override),
        )
        assertNull(OpenCineLogGpuPipeline.resolveEffectiveLevel(MediaFormat.MIMETYPE_VIDEO_HEVC, null, null))
    }

    @Test
    fun resolveEglSurfacePolicyPreservesProductionDefaultsAndAppliesOverrides() {
        val prodLog = OpenCineLogGpuPipeline.resolveEglSurfacePolicy(
            passthroughSdr = false, hlgOutput = false, hasRecordingLut = false, override = null,
        )
        assertFalse(prodLog.use8BitConfig)
        assertTrue(prodLog.useBt2020Linear)
        assertFalse(prodLog.useHlgSurface)

        val prodSdr = OpenCineLogGpuPipeline.resolveEglSurfacePolicy(
            passthroughSdr = true, hlgOutput = false, hasRecordingLut = false, override = null,
        )
        assertTrue(prodSdr.use8BitConfig)
        assertFalse(prodSdr.useBt2020Linear)

        val researched = OpenCineLogGpuPipeline.resolveEglSurfacePolicy(
            passthroughSdr = false,
            hlgOutput = false,
            hasRecordingLut = false,
            override = ResearchEncoderOverride(forceEgl8BitForMain10 = true, omitEglBt2020ColorSpace = true),
        )
        assertTrue(researched.use8BitConfig)
        assertFalse(researched.useBt2020Linear)
    }

    @Test
    fun resolveBitratePlanPreservesProductionScalingAndSupportsExplicitUnscaledOverride() {
        val prod60 = OpenCineLogGpuPipeline.resolveBitratePlan(40_000_000, 60, 100_000_000, null)
        assertEquals(80_000_000, prod60.sentToCodecBps)
        assertFalse(prod60.clampedByCodecMax)

        val unscaled60 = OpenCineLogGpuPipeline.resolveBitratePlan(
            60_000_000,
            60,
            100_000_000,
            ResearchEncoderOverride(explicitTargetBitrateBps = 60_000_000),
        )
        assertEquals(60_000_000, unscaled60.sentToCodecBps)
        assertFalse(unscaled60.clampedByCodecMax)

        val defaults = ResearchEncoderOverride()
        assertFalse(defaults.enableRealtimeCodecKeys)
        assertFalse(defaults.singleContextWhen8Bit)
        assertEquals(1, defaults.previewFpsDivisor)
    }
}
