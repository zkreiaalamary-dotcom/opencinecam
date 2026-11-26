/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.driver

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.librestatic.opencinecam.research.LabUiProgress
import com.librestatic.opencinecam.research.ResearchLabContent
import com.librestatic.opencinecam.ui.theme.AppTheme
import com.librestatic.opencinecam.ui.theme.OpenCineCamTheme

@Composable
fun ResearchLab() {
    var running by remember { mutableStateOf(false) }
    OpenCineCamTheme(AppTheme.CINE) {
        ResearchLabContent(
            status = "Stage B · 4K30 LOG & GPU HEVC · LOG_REGULAR_4K30 · 3840×2160 · 30 fps".takeIf { running },
            running = running,
            exporting = false,
            progress = LabUiProgress(
                stageLabel = "Stage B · 4K30 LOG & GPU HEVC",
                attemptNumber = 2,
                maxAttempts = 24,
                hypothesis = "H-04: live 4K30 regular-session OCLog2 Main10 using synthetic recipe.",
                confirmedHypotheses = 2,
                totalHypotheses = 10,
                batteryTempC = 33.4,
                thermalStatus = 0,
            ).takeIf { running },
            onBack = {},
            onStart = { running = true },
            onNew = { running = true },
            onStop = { running = false },
            onExport = {},
        )
    }
}
