/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraTimestampBootMapperTest {
    @Test
    fun genuineBootTimeSourceLeavesTimestampsUntouched() {
        val mapper = CameraTimestampBootMapper(cameraTimestampRealtime = true)
        val bootNow = 50_835_118_360_937L
        val monoNow = 27_123_407_379_695L
        val frameNs = bootNow - 33_000_000L

        assertEquals(frameNs, mapper.mapToBoottimeNs(frameNs, bootNow, monoNow))
        assertEquals(0L, mapper.appliedOffsetNs())
    }

    @Test
    fun kirinMonotonicImageTimestampNormalizesToBoottime() {
        val mapper = CameraTimestampBootMapper(cameraTimestampRealtime = true)
        val bootNow = 50_835_118_360_937L
        val monoNow = 27_123_407_379_695L
        val expectedOffset = bootNow - monoNow
        val imageNs = 27_123_319_372_476L

        val normalized1 = mapper.mapToBoottimeNs(imageNs, bootNow, monoNow)
        assertEquals(imageNs + expectedOffset, normalized1)
        assertEquals(expectedOffset, mapper.appliedOffsetNs())

        // Even if later boot-mono gap shifts (e.g. device sleep or clock jitter), locked offset stays constant.
        val nextImageNs = imageNs + 33_333_333L
        val normalized2 = mapper.mapToBoottimeNs(nextImageNs, bootNow + 10_000_000_000L, monoNow + 50_000_000L)
        assertEquals(33_333_333L, normalized2 - normalized1)

        val epoch = CaptureEpochClock(cameraRealtime = true, sampleRateHz = 48_000)
        epoch.videoInput(normalized1)
        epoch.audioInput(AudioCaptureEpoch(frameZeroNs = bootNow - 50_000_000L, timestampBacked = true))
        val offsetUs = epoch.offsetUs(video = false)
        assertTrue("Expected < 100ms offset, got ${offsetUs}us", offsetUs in 0L..100_000L)
    }

    @Test
    fun nonRealtimeCameraDoesNotShiftTimestamp() {
        val mapper = CameraTimestampBootMapper(cameraTimestampRealtime = false)
        val bootNow = 50_835_118_360_937L
        val monoNow = 27_123_407_379_695L
        val frameNs = monoNow - 10_000_000L

        assertEquals(frameNs, mapper.mapToBoottimeNs(frameNs, bootNow, monoNow))
        assertEquals(0L, mapper.appliedOffsetNs())
    }
}
