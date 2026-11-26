/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class LabSessionBudgetTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun noTimeSliceLimitConstantOrCheckExistsOnResearchLaboratory() {
        val fields = ResearchLaboratory.Companion::class.java.declaredFields.map { it.name } +
            ResearchLaboratory::class.java.declaredFields.map { it.name }
        assertFalse("Arbitrary 5-minute time limit must be removed", fields.any { it.contains("TIME_SLICE_LIMIT") })
    }

    @Test
    fun sessionBudgetSeparatesCoreFromAdaptiveFollowUpsAndBlocksDuplicateRepeatLoops() {
        val budget = LabSessionBudget(maxCoreAttempts = 3, maxAdaptiveFollowUps = 2)
        val c1 = LabCase("0", LabPath.CODEC_SYNTHETIC_MATRIX, 3840, 2160, 30, listed = false)
        val c2 = LabCase("0", LabPath.LOG_REGULAR_4K30, 3840, 2160, 30, listed = false)
        val c3 = LabCase("0", LabPath.LOG_REGULAR_4K60, 3840, 2160, 60, listed = false)
        val v1 = c3.copy(variant = "realtime")
        val v2 = c3.copy(variant = "single_ctx")
        val v3 = c3.copy(variant = "prev_30fps")

        val history = mutableListOf(
            LabResult(c1, LabVerdict.OBSERVED, LabStage.FILE, "001"),
            LabResult(c2, LabVerdict.OBSERVED, LabStage.FILE, "002"),
            LabResult(v1, LabVerdict.MISMATCH, LabStage.FILE, "003"),
        )
        val snap = budget.snapshot(history)
        assertEquals(2, snap.coreExecuted)
        assertEquals(1, snap.adaptiveExecuted)

        // Repeating an already-executed non-stopped case triggers repeat guard
        val repeatReason = budget.checkNext(history, v1)
        assertNotNull(repeatReason)
        assertTrue(repeatReason!!.contains("repeat guard"))

        // Stopped attempts do NOT consume budget
        history += LabResult(v2, LabVerdict.STOPPED, LabStage.RECORDER, "004")
        assertNull(budget.checkNext(history, v2))

        // Core budget and adaptive budget trigger independently at their exact caps
        history += LabResult(c3, LabVerdict.OBSERVED, LabStage.FILE, "005")
        history += LabResult(v2, LabVerdict.MISMATCH, LabStage.FILE, "006")
        assertNotNull(budget.checkNext(history, LabCase("0", LabPath.GPU_HEVC_4K30, 3840, 2160, 30)))
        assertNotNull(budget.checkNext(history, v3))
    }

    @Test
    fun journalSavesAndReadsAtomicCheckpointAcrossResumes() {
        val root = folder.newFolder()
        val journal = LabJournal.create(root, "rev-test")
        assertNull(journal.readCheckpoint())
        journal.saveCheckpoint(json("state" to "RUNNING", "coreExecuted" to 5, "adaptiveExecuted" to 2))
        val cp = journal.readCheckpoint()
        assertNotNull(cp)
        assertEquals("RUNNING", cp?.optString("state"))
        assertEquals(5, cp?.optInt("coreExecuted"))
        assertEquals(2, cp?.optInt("adaptiveExecuted"))
    }
}
