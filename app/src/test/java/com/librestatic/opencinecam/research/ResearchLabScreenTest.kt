/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.librestatic.opencinecam.ui.theme.AppTheme
import com.librestatic.opencinecam.ui.theme.OpenCineCamTheme
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
}
