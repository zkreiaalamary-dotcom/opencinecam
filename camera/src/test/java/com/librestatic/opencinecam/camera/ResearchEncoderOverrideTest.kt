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

    @Test
    fun huaweiBack4k60SyntheticFpsOnlyAppliesToBackCuvaCamerasWithout60() {
        assertTrue(huaweiBack4k60SyntheticFps(1, hasHuawei4k60 = true, hasHuaweiCuva = true, existingFixedFps = listOf(15, 24, 30)))
        assertFalse(huaweiBack4k60SyntheticFps(0, hasHuawei4k60 = true, hasHuaweiCuva = true, existingFixedFps = listOf(15, 24, 30)))
        assertFalse(huaweiBack4k60SyntheticFps(1, hasHuawei4k60 = true, hasHuaweiCuva = true, existingFixedFps = listOf(15, 24, 30, 60)))
        assertFalse(huaweiBack4k60SyntheticFps(1, hasHuawei4k60 = false, hasHuaweiCuva = true, existingFixedFps = listOf(15, 24, 30)))
        assertFalse(huaweiBack4k60SyntheticFps(1, hasHuawei4k60 = true, hasHuaweiCuva = false, existingFixedFps = listOf(15, 24, 30)))
    }

    @Test
    fun huaweiCuvaUhdStreamDurationOverrideAdmits24To60WithinUhd() {
        assertTrue(huaweiCuvaUhdStreamDurationOverride(3840, 2160, 24, huaweiCuvaSupported = true, huawei4k60Supported = true))
        assertTrue(huaweiCuvaUhdStreamDurationOverride(3840, 2160, 25, huaweiCuvaSupported = true, huawei4k60Supported = true))
        assertTrue(huaweiCuvaUhdStreamDurationOverride(3840, 2160, 30, huaweiCuvaSupported = true, huawei4k60Supported = true))
        assertTrue(huaweiCuvaUhdStreamDurationOverride(3840, 2160, 60, huaweiCuvaSupported = true, huawei4k60Supported = true))
        assertFalse(huaweiCuvaUhdStreamDurationOverride(4096, 3072, 30, huaweiCuvaSupported = true, huawei4k60Supported = true))
        assertFalse(huaweiCuvaUhdStreamDurationOverride(3840, 2160, 20, huaweiCuvaSupported = true, huawei4k60Supported = true))
        assertFalse(huaweiCuvaUhdStreamDurationOverride(3840, 2160, 120, huaweiCuvaSupported = true, huawei4k60Supported = true))
        assertFalse(huaweiCuvaUhdStreamDurationOverride(3840, 2160, 30, huaweiCuvaSupported = false, huawei4k60Supported = true))
        assertFalse(huaweiCuvaUhdStreamDurationOverride(3840, 2160, 30, huaweiCuvaSupported = true, huawei4k60Supported = false))
    }

    @Test
    fun resolveTargetFpsBoundsUsesExactMatchOrHuaweiBack60Fallback() {
        val advertised = listOf(15 to 15, 24 to 24, 30 to 30)
        assertEquals(30 to 30, resolveTargetFpsBounds(advertised, 30, huaweiBack60Fallback = true))
        assertEquals(60 to 60, resolveTargetFpsBounds(advertised, 60, huaweiBack60Fallback = true))
        assertEquals(null, resolveTargetFpsBounds(advertised, 60, huaweiBack60Fallback = false))
        assertEquals(null, resolveTargetFpsBounds(listOf(15 to 15), 30, huaweiBack60Fallback = true))
    }
}
