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
    fun noTimeSliceLimitOrFixed36AttemptCapExists() {
        val labFields = ResearchLaboratory.Companion::class.java.declaredFields.map { it.name } +
            ResearchLaboratory::class.java.declaredFields.map { it.name }
        assertFalse("Arbitrary 5-minute time limit must stay removed", labFields.any { it.contains("TIME_SLICE_LIMIT") })
        assertFalse("Arbitrary 36-attempt budget must be removed", labFields.any { it.contains("MAX_CORE_ATTEMPTS") })
        val guardFields = LabExplorationGuard::class.java.declaredFields.map { it.name } +
            LabExplorationGuard.Companion::class.java.declaredFields.map { it.name }
        assertFalse("No fixed 36-experiment limit in LabExplorationGuard", guardFields.any { it.contains("MAX_CORE_ATTEMPTS") })
    }

    @Test
    fun explorationGuardAllowsOverSixtyFiveUniqueExperimentsAndBlocksOnlyTrueRepeatsOrCycles() {
        val guard = LabExplorationGuard()
        val history = mutableListOf<LabResult>()
        for (i in 1..65) {
            val c = LabCase("0", LabPath.AVC, 1920, 1080, 30, variant = "unique_$i")
            assertNull("Attempt #$i > 36 must NOT be blocked", guard.checkNext(history, c))
            history += LabResult(c, LabVerdict.OBSERVED, LabStage.FILE, "%03d-id".format(i))
        }
        assertEquals(65, guard.snapshot(history).completedCount)

        // Exact already-completed key is blocked as a duplicate
        val dupReason = guard.checkNext(history, LabCase("0", LabPath.AVC, 1920, 1080, 30, variant = "unique_1"))
        assertNotNull(dupReason)
        assertTrue(dupReason!!.contains("Duplicate-execution guard"))

        // Interrupted key can retry up to MAX_INTERRUPT_RETRIES_PER_KEY, then gets skipped safely
        val intCase = LabCase("0", LabPath.LOG_REGULAR_4K60, 3840, 2160, 60)
        assertNull(guard.checkNext(history, intCase, mapOf(intCase.key to 1)))
        assertNull(guard.checkNext(history, intCase, mapOf(intCase.key to 2)))
        assertNotNull(guard.checkNext(history, intCase, mapOf(intCase.key to LabExplorationGuard.MAX_INTERRUPT_RETRIES_PER_KEY)))
    }

    @Test
    fun checkpointRoundTripAndCrossRevisionResumeSkipEmptyAbortedRunsAndPreserveHistory() {
        val root = folder.newFolder()
        val run1 = LabJournal.create(root, "rev-1", LabPowerProfile.capture(org.robolectric.RuntimeEnvironment.getApplication()))
        val case1 = LabCase("0", LabPath.CODEC_SYNTHETIC_MATRIX, 3840, 2160, 30, listed = false)
        val a1 = run1.begin(LabDecision(case1, null, "Stage A"))
        a1.event(LabStage.FILE, json("fps" to 30))
        a1.result(LabVerdict.OBSERVED)

        val case2 = LabCase("0", LabPath.LOG_REGULAR_4K30, 3840, 2160, 30, listed = false)
        val a2 = run1.begin(LabDecision(case2, a1.directory.name, "Stage B"))
        a2.event(LabStage.CAMERA, json("id" to "0"))
        // Simulate abrupt app crash/reboot while a2 was inFlight!

        val cp = LabCheckpointState(
            sessionId = run1.sessionId,
            createdEpochMs = 1_000L,
            updatedEpochMs = 2_000L,
            initialApkRevision = "rev-1",
            latestApkRevision = "rev-1",
            revisionHistory = listOf("rev-1"),
            deviceFingerprint = android.os.Build.FINGERPRINT,
            selectedCameraId = "0",
            evidenceLimitBytes = 20_000_000_000L,
            manualPowerMode = ManualPowerMode.PERFORMANCE,
            lastAndroidPowerSaveMode = false,
            state = LabSessionState.PAUSED_THERMAL,
            stopReason = "Thermal pause",
            currentStage = "Stage B",
            inFlightAttempt = InFlightAttemptRecord(a2.directory.name, case2.toJson(), 1_500L),
            completedKeys = listOf(case1.key),
            failedKeys = emptyList(),
            skippedKeys = emptyList(),
            interruptedCounts = mapOf(case2.key to 1),
            scheduledQueuePreview = listOf("remaining_planned=12"),
            explorationDecisions = listOf(
                ExplorationDecisionRecord(a1.directory.name, case1.key, null, "Stage A", "OBSERVED", "FILE", 1_200L),
            ),
            hypothesisStatuses = mapOf("H-02" to "CONFIRMED"),
            powerModeHistory = emptyList(),
        )
        run1.saveCheckpointState(cp)

        // Simulate a 0-attempt aborted run created afterward (like f8d3e998); latestResumable must STILL pick run1!
        val emptyAbortedRun = LabJournal.create(root, "rev-1")
        // Clear pointer to test fallback heuristic too, or point back via inspection
        assertFalse(emptyAbortedRun.finished)
        // latestResumable must prefer run1 (has attempts) over emptyAbortedRun (0 attempts)
        val resumed = LabJournal.latestResumable(root)
        assertNotNull(resumed)
        assertEquals(run1.sessionId, resumed!!.sessionId)

        // Cross-APK-revision resume works and recovers ONLY a2 as INTERRUPTED, keeping a1 OBSERVED untouched
        assertTrue(resumed.resumableOnDevice())
        resumed.recordApkRevisionTransition("rev-2")
        resumed.recoverInterrupted()
        val hist = resumed.history()
        assertEquals(2, hist.size)
        assertEquals(LabVerdict.OBSERVED, hist[0].verdict)
        assertEquals(LabVerdict.INTERRUPTED, hist[1].verdict)

        val reloadedCp = resumed.readCheckpointState()
        assertNotNull(reloadedCp)
        assertEquals(ManualPowerMode.PERFORMANCE, reloadedCp!!.manualPowerMode)
        assertEquals(LabSessionState.PAUSED_THERMAL, reloadedCp.state)
        assertEquals(listOf(case1.key), reloadedCp.completedKeys)
        assertEquals(1, reloadedCp.interruptedCounts[case2.key])

        // Summary reflects resumable run cleanly and explicit new run preserves old directory
        val summary = LabJournal.inspectResumableSummary(root)
        assertTrue(summary.hasResumableSession)
        assertEquals(1, summary.completedCount)
        assertEquals(1, summary.interruptedCount)
        assertEquals(2, summary.pastSessionCount)

        val run3 = LabJournal.create(root, "rev-2")
        assertTrue(run1.directory.isDirectory)
        assertTrue(run3.directory.isDirectory)
        assertEquals(3, LabJournal.allRunDirectories(root).size)
    }

    @Test
    fun completedActiveSessionNeverResurrectsStaleOrStoppedOnlyGhostRuns() {
        val root = folder.newFolder()
        // 1. Old legacy STOPPED-only ghost run (like c8959e8a)
        val ghost = LabJournal.create(root, "rev-1")
        val gCase = LabCase("0", LabPath.CODEC_SYNTHETIC_MATRIX, 3840, 2160, 30, listed = false)
        val gAttempt = ghost.begin(LabDecision(gCase, null, "ghost"))
        gAttempt.result(LabVerdict.STOPPED, "User stopped before any result")
        assertFalse(ghost.hasResumableProgress())

        // Without any newer completed session, STOPPED-only ghost run is STILL not resumable
        assertNull(LabJournal.latestResumable(root))
        assertFalse(LabJournal.inspectResumableSummary(root).hasResumableSession)

        // 2. Older interrupted run with real progress IS resumable before completion
        val olderPaused = LabJournal.create(root, "rev-1")
        val pAttempt = olderPaused.begin(LabDecision(gCase, null, "Stage A"))
        pAttempt.result(LabVerdict.OBSERVED, null)
        assertTrue(olderPaused.hasResumableProgress())
        assertEquals(olderPaused.sessionId, LabJournal.latestResumable(root)?.sessionId)

        // 3. Newest session completes cleanly
        val completedRun = LabJournal.create(root, "rev-2")
        val c1 = completedRun.begin(LabDecision(gCase, null, "Stage A"))
        c1.result(LabVerdict.OBSERVED, null)
        val c2 = completedRun.begin(LabDecision(LabCase("0", LabPath.GPU_HEVC_4K60, 3840, 2160, 60, listed = false), null, "Stage C"))
        c2.result(LabVerdict.MISMATCH, "48 fps")
        completedRun.saveCheckpointState(
            LabCheckpointState(
                sessionId = completedRun.sessionId,
                createdEpochMs = 10_000L,
                updatedEpochMs = 20_000L,
                initialApkRevision = "rev-2",
                latestApkRevision = "rev-2",
                revisionHistory = listOf("rev-2"),
                deviceFingerprint = android.os.Build.FINGERPRINT,
                selectedCameraId = "0",
                evidenceLimitBytes = 20_000_000_000L,
                manualPowerMode = ManualPowerMode.PERFORMANCE,
                lastAndroidPowerSaveMode = false,
                state = LabSessionState.COMPLETED,
                stopReason = "All autonomous research objectives completed.",
                currentStage = "Complete",
                inFlightAttempt = null,
                completedKeys = listOf(gCase.key),
                failedKeys = listOf("0/GPU_HEVC_4K60/3840x2160/60/false/false/false/false/0/"),
                skippedKeys = listOf("skipped-1"),
                interruptedCounts = emptyMap(),
                scheduledQueuePreview = emptyList(),
                explorationDecisions = emptyList(),
                hypothesisStatuses = emptyMap(),
                powerModeHistory = emptyList(),
            ),
        )
        completedRun.finish("All autonomous research objectives completed.")
        LabJournal.setActiveSession(root, completedRun.sessionId, LabSessionState.COMPLETED)

        // Completed active session MUST NOT fall back to older paused/ghost runs!
        assertNull(LabJournal.latestResumable(root))
        val summary = LabJournal.inspectResumableSummary(root)
        assertFalse(summary.hasResumableSession)
        assertEquals(completedRun.sessionId, summary.lastCompletedSessionId)
        assertEquals(2, summary.lastCompletedCount)
        assertEquals(1, summary.lastObservedCount)
        assertEquals(1, summary.lastFailedCount)
        assertEquals(1, summary.lastSkippedCount)
        assertEquals(3, summary.pastSessionCount)
    }

    @Test
    fun manualPowerModePersistsInPreferences() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        assertEquals(ManualPowerMode.UNSPECIFIED, LabPowerProfile.loadManualPowerMode(ctx))
        LabPowerProfile.saveManualPowerMode(ctx, ManualPowerMode.PERFORMANCE)
        assertEquals(ManualPowerMode.PERFORMANCE, LabPowerProfile.loadManualPowerMode(ctx))
        val snap = LabPowerProfile.capture(ctx)
        assertEquals(ManualPowerMode.PERFORMANCE, snap.manualPowerMode)
        assertFalse(snap.isLikelyThrottled)
    }
}
