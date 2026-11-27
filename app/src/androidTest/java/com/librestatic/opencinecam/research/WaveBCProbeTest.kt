/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import android.Manifest
import android.os.Build
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Isolated Wave C discovery & Wave B end-to-end production verification suite on Pura 80 Pro.
 * Targets `com.librestatic.opencinecam.debug` without touching `com.librestatic.opencinecam.researchlab`.
 */
@RunWith(AndroidJUnit4::class)
class WaveBCProbeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var outDir: File

    @Before
    fun setUp() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val pkg = context.packageName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).forEach { perm ->
                if (context.checkPermission(perm, Process.myPid(), Process.myUid()) != 0) {
                    runCatching { inst.uiAutomation.grantRuntimePermission(pkg, perm) }
                }
            }
        }
        outDir = requireNotNull(context.getExternalFilesDir("wave-bc-probes")).apply { mkdirs() }
    }

    @Test
    fun probe14A_Crack0x7f11UvLayout() {
        val report = LabWaveBCProbes.runProbe14A_Crack0x7f11UvLayout(outDir)
        assertTrue("Expected non-empty probe14A arms: $report", (report.optJSONArray("arms")?.length() ?: 0) > 0)
    }

    @Test
    fun probe14B_KelvinMatrixAndCuvaOetf() {
        val report = LabWaveBCProbes.runProbe14B_KelvinMatrixAndCuvaOetf(context, outDir)
        assertTrue("Expected non-empty probe14B kelvinMatrix: $report", (report.optJSONArray("kelvinMatrix")?.length() ?: 0) > 0)
    }

    @Test
    fun probe15_ProductionEndToEndVerification() {
        val report = LabWaveBCProbes.runProbe15_ProductionEndToEndVerification(context, outDir)
        assertTrue("Expected non-empty probe15 clips: $report", (report.optJSONArray("clips")?.length() ?: 0) > 0)
    }
}
