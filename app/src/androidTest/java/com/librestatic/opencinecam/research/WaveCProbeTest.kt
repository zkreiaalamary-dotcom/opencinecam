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
class WaveCProbeTest {
    private fun outDir(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "wave-c-probes").apply { mkdirs() }
    }

    @Test
    fun probe16A_OrientationTruth() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val report = LabWaveCProbes.runProbe16A_OrientationTruth(ctx, outDir())
        assertTrue("Expected surfaceTextureAudit and clips: $report", report.has("surfaceTextureAudit") && report.has("clips"))
    }

    @Test
    fun probe16B_ColorAndEncoderTruth() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val report = LabWaveCProbes.runProbe16B_ColorAndEncoderTruth(ctx, outDir())
        assertTrue("Expected encoderArms and sceneArms: $report", report.has("encoderArms") && report.has("sceneArms"))
    }

    @Test
    fun probe16C_True10BitAndCuvaCurve() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val report = LabWaveCProbes.runProbe16C_True10BitAndCuvaCurve(ctx, outDir())
        assertTrue("Expected encoderPrecisionArms and cuva9EvSweep: $report",
            report.has("encoderPrecisionArms") && report.has("cuva9EvSweep"))
    }

    @Test
    fun probe16D_True10BitPaths() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val report = LabWaveCProbes.runProbe16D_True10BitPaths(ctx, outDir())
        assertTrue("Expected directCuvaToEncoder and byteBufferColorFormats: $report",
            report.has("directCuvaToEncoder") && report.has("byteBufferColorFormats"))
    }
}
