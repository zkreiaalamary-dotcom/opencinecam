/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.driver

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.librestatic.opencinecam.research.LabStorageUiState
import com.librestatic.opencinecam.research.LabUiProgress
import com.librestatic.opencinecam.research.ResearchLabContent
import com.librestatic.opencinecam.ui.theme.AppTheme
import com.librestatic.opencinecam.ui.theme.OpenCineCamTheme

@Composable
fun ResearchLab() {
    var running by remember { mutableStateOf(false) }
    var limitBytes by remember { mutableStateOf(20_000_000_000L) }
    var prunableBytes by remember { mutableStateOf(2_390_000_000L) }
    var prunableFiles by remember { mutableStateOf(4) }
    var status by remember { mutableStateOf<String?>(null) }
    OpenCineCamTheme(AppTheme.CINE) {
        ResearchLabContent(
            status = if (running) "Stage B · 4K30 LOG & GPU HEVC · LOG_REGULAR_4K30 · 3840×2160 · 30 fps" else status,
            running = running,
            exporting = false,
            progress = LabUiProgress(
                stageLabel = "Stage B · 4K30 LOG & GPU HEVC",
                attemptNumber = 2,
                maxAttempts = 54,
                hypothesis = "H-04: live 4K30 regular-session OCLog2 Main10 using synthetic recipe.",
                confirmedHypotheses = 2,
                totalHypotheses = 10,
                batteryTempC = 33.4,
                thermalStatus = 0,
            ).takeIf { running },
            storage = LabStorageUiState(
                usedBytes = prunableBytes + 12_000_000L,
                mediaBytes = prunableBytes,
                metadataBytes = 12_000_000L,
                limitBytes = limitBytes,
                freeBytes = 32_000_000_000L,
                reserveBytes = 5_368_709_120L,
                verifiedPrunableBytes = prunableBytes,
                verifiedPrunableFiles = prunableFiles,
                hasVerifiedExport = true,
            ),
            onBack = {},
            onStart = { running = true },
            onNew = { running = true },
            onStop = { running = false },
            onExport = {},
            onSelectLimitBytes = { limitBytes = it },
            onPruneVerified = {
                prunableBytes = 0L
                prunableFiles = 0
                status = "Pruned verified exported media. Logs & knowledge preserved."
            },
        )
    }
}
