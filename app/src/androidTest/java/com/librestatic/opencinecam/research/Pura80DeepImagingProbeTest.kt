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
 * Isolated Wave A Computational Imaging & Cinema Capability Probes (Probes 9-12)
 * on Huawei Pura 80 Pro (`LMR-LX9`, Kirin 9020, API 31).
 * Targets `com.librestatic.opencinecam.debug` without touching `com.librestatic.opencinecam.researchlab`.
 */
@RunWith(AndroidJUnit4::class)
class Pura80DeepImagingProbeTest {
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
        outDir = requireNotNull(context.getExternalFilesDir("deep-imaging-probes")).apply { mkdirs() }
    }

    @Test
    fun probe9_SourcePrecisionAndCuvaTransfer() {
        val report = LabDeepImagingProbes.runProbe9_SourcePrecisionAndCuvaTransfer(context, outDir)
        assertTrue("Expected non-empty probe9 arms: $report", (report.optJSONArray("arms")?.length() ?: 0) > 0)
    }

    @Test
    fun probe10_True10BitOutputEscape() {
        val report = LabDeepImagingProbes.runProbe10_True10BitOutputEscape(context, outDir)
        assertTrue("Expected non-empty probe10 arms: $report", (report.optJSONArray("arms")?.length() ?: 0) > 0)
    }

    @Test
    fun probe11_RawForensicsAndHeadToHead() {
        val report = LabDeepImagingProbes.runProbe11_RawForensicsAndHeadToHead(context, outDir)
        assertTrue("Expected rawResults in probe11: $report", report.has("rawResults"))
    }

    @Test
    fun probe12_HiddenProControlsAndMultiLens() {
        val report = LabDeepImagingProbes.runProbe12_HiddenProControlsAndMultiLens(context, outDir)
        assertTrue("Expected non-empty probe12 sections: $report", report.has("exposureLinearity") && report.has("multiLens"))
    }

    @Test
    fun probe13_ClosureProbes() {
        val report = LabDeepImagingProbes.runProbe13_ClosureProbes(context, outDir)
        assertTrue("Expected non-empty probe13 sections: $report", report.has("p13a_0x7f11_and_p010") && report.has("p13d_raw_compression"))
    }
}
