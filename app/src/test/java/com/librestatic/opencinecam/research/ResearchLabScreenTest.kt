/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.librestatic.opencinecam.ui.theme.AppTheme
import com.librestatic.opencinecam.ui.theme.OpenCineCamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResearchLabScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun idleStateShowsStartAndExportButtons() {
        var started = false
        composeRule.setContent {
            OpenCineCamTheme(AppTheme.CINE) {
                ResearchLabContent(
                    status = null,
                    running = false,
                    exporting = false,
                    onBack = {},
                    onStart = { started = true },
                    onNew = {},
                    onStop = {},
                    onExport = {},
                )
            }
        }
        composeRule.onNodeWithTag("lab-back").assertIsDisplayed()
        composeRule.onNodeWithTag("lab-status").assertIsDisplayed()
        composeRule.onNodeWithTag("lab-start").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("lab-export").assertIsDisplayed()
        assertTrue(started)
    }

    @Test
    fun runningStateShowsAutonomousProgressAndTelemetryCard() {
        composeRule.setContent {
            OpenCineCamTheme(AppTheme.CINE) {
                ResearchLabContent(
                    status = "Stage B · LOG_REGULAR_4K30",
                    running = true,
                    exporting = false,
                    progress = LabUiProgress(
                        stageLabel = "Stage B · 4K30 LOG & GPU HEVC",
                        attemptNumber = 3,
                        maxAttempts = 24,
                        hypothesis = "H-04: regular 4K30 OCLog2 Main10",
                        confirmedHypotheses = 2,
                        totalHypotheses = 10,
                        batteryTempC = 34.5,
                        thermalStatus = 0,
                    ),
                    onBack = {},
                    onStart = {},
                    onNew = {},
                    onStop = {},
                    onExport = {},
                )
            }
        }
        composeRule.onNodeWithTag("lab-stage").assertIsDisplayed()
        composeRule.onNodeWithTag("lab-hypothesis").assertIsDisplayed()
        composeRule.onNodeWithTag("lab-telemetry").assertIsDisplayed()
        composeRule.onNodeWithTag("lab-stop").assertIsDisplayed()
    }

    @Test
    fun storageCardSupportsLimitSelectionAndManualVerifiedPruneConfirmation() {
        var selectedLimit = 0L
        var pruned = false
        composeRule.setContent {
            OpenCineCamTheme(AppTheme.CINE) {
                ResearchLabContent(
                    status = "Ready",
                    running = false,
                    exporting = false,
                    storage = LabStorageUiState(
                        usedBytes = 2_400_000_000L,
                        mediaBytes = 2_390_000_000L,
                        metadataBytes = 10_000_000L,
                        limitBytes = 20_000_000_000L,
                        freeBytes = 32_000_000_000L,
                        reserveBytes = 5_368_709_120L,
                        verifiedPrunableBytes = 2_390_000_000L,
                        verifiedPrunableFiles = 4,
                        hasVerifiedExport = true,
                    ),
                    onBack = {},
                    onStart = {},
                    onNew = {},
                    onStop = {},
                    onExport = {},
                    onSelectLimitBytes = { selectedLimit = it },
                    onPruneVerified = { pruned = true },
                )
            }
        }
        composeRule.onNodeWithTag("lab-storage").assertIsDisplayed()
        composeRule.onNodeWithTag("lab-limit-50").performClick()
        assertEquals(50_000_000_000L, selectedLimit)

        composeRule.onNodeWithTag("lab-prune-verified").performClick()
        composeRule.onNodeWithTag("lab-prune-confirm").performClick()
        assertTrue(pruned)
    }

    @Test
    fun powerWarningManualChipsAndResumeNewSessionConfirmationWork() {
        var selectedMode: ManualPowerMode? = null
        var startedNew = false
        var resumed = false
        composeRule.setContent {
            OpenCineCamTheme(AppTheme.CINE) {
                ResearchLabContent(
                    status = "Thermal pause",
                    running = false,
                    exporting = false,
                    powerProfile = PowerProfileSnapshot(
                        epochMs = 100L,
                        androidPowerSaveMode = true,
                        sustainedPerformanceSupported = false,
                        ignoringBatteryOptimizations = false,
                        locationPowerSaveMode = 0,
                        manualPowerMode = ManualPowerMode.POWER_SAVING,
                    ),
                    sessionSummary = LabSessionSummary(
                        hasResumableSession = true,
                        sessionId = "123-abc",
                        state = LabSessionState.PAUSED_THERMAL,
                        completedCount = 12,
                        failedCount = 2,
                        interruptedCount = 1,
                        currentStage = "Stage D",
                        lastStopReason = "Thermal pause",
                        pastSessionCount = 4,
                    ),
                    onBack = {},
                    onStart = { resumed = true },
                    onNew = { startedNew = true },
                    onStop = {},
                    onExport = {},
                    onSelectManualPowerMode = { selectedMode = it },
                )
            }
        }
        composeRule.onNodeWithTag("lab-power").assertIsDisplayed()
        composeRule.onNodeWithTag("lab-power-warning").assertIsDisplayed()
        composeRule.onNodeWithTag("lab-power-PERFORMANCE").performScrollTo().performClick()
        assertEquals(ManualPowerMode.PERFORMANCE, selectedMode)

        composeRule.onNodeWithTag("lab-session").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("lab-start").performScrollTo().performClick()
        assertTrue(resumed)

        composeRule.onNodeWithTag("lab-new").performScrollTo().performClick()
        composeRule.onNodeWithTag("lab-new-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("lab-new-confirm").performClick()
        assertTrue(startedNew)
    }

    @Test
    fun completedSessionShowsZeroRemainingAndNoFalsePausedCheckpoint() {
        composeRule.setContent {
            OpenCineCamTheme(AppTheme.CINE) {
                ResearchLabContent(
                    status = "Research complete. Evidence is ready to export.",
                    running = false,
                    exporting = false,
                    progress = LabUiProgress(
                        stageLabel = "Complete",
                        attemptNumber = 47,
                        maxAttempts = 47,
                        completedAttempts = 47,
                        remainingPlanned = 0,
                        skippedAttempts = 0,
                        completed = true,
                        hypothesis = "All autonomous research objectives completed.",
                        confirmedHypotheses = 6,
                        totalHypotheses = 12,
                    ),
                    sessionSummary = LabSessionSummary(
                        hasResumableSession = false,
                        pastSessionCount = 17,
                        lastCompletedSessionId = "1795725149085-082c0332",
                        lastCompletedCount = 47,
                        lastObservedCount = 26,
                        lastFailedCount = 21,
                        lastSkippedCount = 0,
                        lastCompletionReason = "All autonomous research objectives completed.",
                    ),
                    onBack = {},
                    onStart = {},
                    onNew = {},
                    onStop = {},
                    onExport = {},
                )
            }
        }
        composeRule.onNodeWithText("Complete (47 done · 0 remaining)").assertIsDisplayed()
        composeRule.onNodeWithText("Last session completed: 47 completed (26 observed, 21 failed/mismatch)").assertIsDisplayed()
        composeRule.onNodeWithText("Total stored runs: 17").assertIsDisplayed()
        composeRule.onNodeWithText("Saved checkpoint", substring = true).assertDoesNotExist()
    }
}
