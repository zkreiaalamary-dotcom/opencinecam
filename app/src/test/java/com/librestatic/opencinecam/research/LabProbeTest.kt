/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.research

import android.media.MediaCodecInfo
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LabProbeTest {
    private val direct = LabCase("0", LabPath.DIRECT_HEVC, 3840, 2160, 60, listed = false)
    private val paired = direct.copy(path = LabPath.DIRECT_HEVC_PREVIEW)
    private val codec = direct.copy(path = LabPath.CODEC_LEVEL_PAIR, fps = 30)
    private val stable = (0..240).map { it * 1_001_000L / 60 }

    @Test fun directPairRunsBeforeAdaptiveCasesAndLinksFailedControl() {
        val search = LabSearch(listOf(direct.copy(path = LabPath.AVC), codec, paired, direct))
        assertEquals(direct, search.next(emptyList())?.attempt)
        val control = LabResult(direct, LabVerdict.FAILED, LabStage.CAMERA, "control-id")
        val decision = search.next(listOf(control))
        assertEquals(paired, decision?.attempt)
        assertEquals("control-id", decision?.parent)
        assertEquals(codec, search.next(listOf(control, LabResult(paired, LabVerdict.OBSERVED, LabStage.FILE, "pair")))?.attempt)
    }

    @Test fun fractionalRateAndDecodeOrderAreAccepted() {
        val series = stable.toMutableList().apply { java.util.Collections.swap(this, 2, 3) }
        val cadence = labCadence(series, 60)
        assertNull(cadence.shortDirectIssue(60))
        assertEquals(59.940, cadence.fps!!, 0.001)
        assertEquals(16_684L, cadence.p99Us)
    }

    @Test fun rejectsShortDuplicateSlowAndMissingSamplesIndependently() {
        assertNotNull(labCadence(emptyList(), 60).shortDirectIssue(60))
        assertNotNull(labCadence(stable.take(4), 60).shortDirectIssue(60))
        assertEquals(1, labCadence(stable + stable.first(), 60).repeatedPts)
        assertEquals(1, labCadence(stable.filterIndexed { i, _ -> i != 20 }, 60).longIntervals)
        assertNotNull(labCadence((0L..240L).map { it * 17_000 }, 60).shortDirectIssue(60))
    }

    @Test fun codecArmsDifferOnlyInExplicitLevel() {
        val plain = hevcProbeKeys(codec, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain, null)
        val explicit = hevcProbeKeys(codec, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51)
        assertEquals(plain, explicit - MediaFormat.KEY_LEVEL)
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51, explicit[MediaFormat.KEY_LEVEL])
        assertEquals(20_000_000, plain[MediaFormat.KEY_BIT_RATE])
    }
}
