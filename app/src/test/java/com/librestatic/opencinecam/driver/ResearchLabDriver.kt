/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.driver

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.librestatic.opencinecam.research.ResearchLabContent
import com.librestatic.opencinecam.ui.theme.AppTheme
import com.librestatic.opencinecam.ui.theme.OpenCineCamTheme

@Composable
fun ResearchLab() {
    var running by remember { mutableStateOf(false) }
    OpenCineCamTheme(AppTheme.CINE) {
        ResearchLabContent(
            status = "OCLog2 · 3840×2160 · 30 fps".takeIf { running },
            running = running,
            exporting = false,
            onBack = {},
            onStart = { running = true },
            onNew = { running = true },
            onStop = { running = false },
            onExport = {},
        )
    }
}
