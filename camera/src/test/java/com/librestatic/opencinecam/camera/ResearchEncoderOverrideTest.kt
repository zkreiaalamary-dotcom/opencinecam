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

        val hisiWorkaround = OpenCineLogGpuPipeline.resolveEglSurfacePolicy(
            passthroughSdr = false,
            hlgOutput = false,
            hasRecordingLut = false,
            override = null,
            hisiliconMain10Workaround = true,
        )
        assertTrue(hisiWorkaround.use8BitConfig)
        assertFalse(hisiWorkaround.useBt2020Linear)
        assertFalse(hisiWorkaround.useHlgSurface)
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

    @Test
    fun huawei4k60StreamDurationOverrideOnlyAdmitsUhdUpTo60Fps() {
        assertTrue(huawei4k60StreamDurationOverride(3840, 2160, 60, huawei4k60Supported = true))
        assertTrue(huawei4k60StreamDurationOverride(2720, 1536, 60, huawei4k60Supported = true))
        assertFalse(huawei4k60StreamDurationOverride(3840, 2160, 60, huawei4k60Supported = false))
        assertFalse(huawei4k60StreamDurationOverride(4096, 3072, 60, huawei4k60Supported = true))
        assertFalse(huawei4k60StreamDurationOverride(4096, 2304, 60, huawei4k60Supported = true))
        assertFalse(huawei4k60StreamDurationOverride(3840, 2160, 30, huawei4k60Supported = true))
        assertFalse(huawei4k60StreamDurationOverride(3840, 2160, 120, huawei4k60Supported = true))
    }
}
