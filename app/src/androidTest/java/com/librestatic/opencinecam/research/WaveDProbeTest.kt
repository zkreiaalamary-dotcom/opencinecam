/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WaveDProbeTest {
    private fun outDir(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "research_lab/wave_d_m1").apply { mkdirs() }
    }

    @Test
    fun testProbe17A_NativeLogAndVendorTags() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val report = LabWaveDProbes.runProbe17A_NativeLogAndVendorTags(ctx, outDir())
        assertTrue("Expected vendorSweeps and cuvaModeSweep: $report",
            report.has("vendorSweeps") && report.has("cuvaModeSweep"))
    }

    @Test
    fun testProbe17B_True10BitGpuAndNativePaths() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val report = LabWaveDProbes.runProbe17B_True10BitGpuAndNativePaths(ctx, outDir())
        assertTrue("Expected ahbInspect and imageWriterArms: $report",
            report.has("ahbInspect") && report.has("imageWriterArms"))
    }

    @Test
    fun testProbe17C_4k60BottleneckAndFpsEscape() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val report = LabWaveDProbes.runProbe17C_4k60BottleneckAndFpsEscape(ctx, outDir())
        assertTrue("Expected arms in Probe 17C report: $report", report.has("arms"))
    }

    @Test
    fun testProbe17D_Direct10BitMatrixAndHiddenRaw() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val report = LabWaveDProbes.runProbe17D_Direct10BitMatrixAndHiddenRaw(ctx, outDir())
        assertTrue("Expected directMatrix and hiddenRawAudit: $report",
            report.has("directMatrix") && report.has("hiddenRawAudit"))
    }
}
