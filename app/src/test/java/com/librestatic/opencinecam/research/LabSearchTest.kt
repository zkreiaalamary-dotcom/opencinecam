/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LabSearchTest {
    private val baseline = LabCase("0", LabPath.LOG, 3840, 2160, 30)
    private val slower = baseline.copy(fps = 24)
    private val faster = baseline.copy(fps = 60)
    private val preview = baseline.copy(previewOnly = true)

    @Test
    fun recorderFailureIsolatesPreviewBeforeChangingResolutionOrRate() {
        val search = LabSearch(listOf(baseline, slower, faster, preview))
        val failure = LabResult(baseline, LabVerdict.FAILED, LabStage.RECORDER, "a")
        val decision = search.next(listOf(failure))
        assertEquals(preview, decision?.attempt)
        assertEquals("a", decision?.parent)
        assertEquals(slower, search.next(listOf(failure, LabResult(preview, LabVerdict.OBSERVED, LabStage.PREVIEW, "b")))?.attempt)
    }

    @Test
    fun successIncreasesOnlyFrameRate() {
        val search = LabSearch(listOf(baseline, slower, faster, preview))
        assertEquals(faster, search.next(listOf(LabResult(baseline, LabVerdict.OBSERVED, LabStage.FILE, "a")))?.attempt)
    }

    @Test
    fun incompleteAttemptIsBypassedRatherThanAutomaticallyRepeated() {
        val search = LabSearch(listOf(baseline, slower))
        assertEquals(slower, search.next(listOf(LabResult(baseline, LabVerdict.INTERRUPTED, LabStage.RECORDER, "a")))?.attempt)
    }

    @Test
    fun rawNativeBenchmarkRequiresAWorkingRawCameraControl() {
        val raw = LabCase("0", LabPath.RAW_CAMERA, 4080, 3060, 30)
        val native = raw.copy(path = LabPath.RAW_NATIVE)
        val search = LabSearch(listOf(raw, native))
        assertNull(search.next(listOf(LabResult(raw, LabVerdict.FAILED, LabStage.RAW, "a"))))
        assertEquals(native, search.next(listOf(LabResult(raw, LabVerdict.OBSERVED, LabStage.RAW, "a")))?.attempt)
    }
}
