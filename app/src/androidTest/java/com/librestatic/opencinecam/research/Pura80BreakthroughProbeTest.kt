/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import android.Manifest
import android.os.Build
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Isolated Stage G Kirin 9020 / Huawei Pura 80 Pro hardware breakthrough probes.
 * Targets `com.librestatic.opencinecam.debug` without touching `com.librestatic.opencinecam.researchlab`.
 */
@RunWith(AndroidJUnit4::class)
class Pura80BreakthroughProbeTest {
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
        outDir = requireNotNull(context.getExternalFilesDir("breakthrough-probes")).apply { mkdirs() }
    }

    @Test
    fun probe1_4k60CameraInputIsolation() {
        val report = LabBreakthroughProbes.runProbe1_4k60CameraInputIsolation(context, outDir)
        assertTrue("Expected non-empty probe1 arms: $report", (report.optJSONArray("arms")?.length() ?: 0) > 0)
    }

    @Test
    fun probe2_CuvaHdrDirectAndGpuRecording() {
        val report = LabBreakthroughProbes.runProbe2_CuvaHdrDirectAndGpuRecording(context, outDir)
        assertTrue("Expected non-empty probe2 arms: $report", (report.optJSONArray("arms")?.length() ?: 0) > 0)
    }

    @Test
    fun probe3_Synthetic4k60ThroughputAndPriority() {
        val report = LabBreakthroughProbes.runProbe3_Synthetic4k60ThroughputAndPriority(context, outDir)
        assertTrue("Expected non-empty probe3 arms: $report", (report.optJSONArray("arms")?.length() ?: 0) > 0)
    }

    @Test
    fun probe4_HiddenRawSensorAdmission() {
        val report = LabBreakthroughProbes.runProbe4_HiddenRawSensorAdmission(context, outDir)
        assertTrue("Expected probe4 results array: $report", report.has("results"))
    }

    @Test
    fun probe5_VendorByteBuffer10BitClips() {
        val report = LabBreakthroughProbes.runProbe5_VendorByteBuffer10BitClips(context, outDir)
        assertTrue("Expected non-empty probe5 arms: $report", (report.optJSONArray("arms")?.length() ?: 0) > 0)
    }

    @Test
    fun probe6_ZeroCopy4k60GpuOcLog2() {
        val report = LabBreakthroughProbes.runProbe6_ZeroCopy4k60GpuOcLog2(context, outDir)
        assertTrue("Expected non-empty probe6 arms: $report", (report.optJSONArray("arms")?.length() ?: 0) > 0)
    }

    @Test
    fun probe7_RawSensorStreamingRate() {
        val report = LabBreakthroughProbes.runProbe7_RawSensorStreamingRate(context, outDir)
        assertTrue("Expected non-empty probe7 arms: $report", (report.optJSONArray("arms")?.length() ?: 0) > 0)
    }

    @Test
    fun probe8_ProductionPipelineSustained4k60And4k30() {
        val report = LabBreakthroughProbes.runProbe8_ProductionPipelineVerification(context, outDir)
        assertTrue("Expected Camera 0 supportsOpenCineLog: $report", report.optBoolean("supportsOpenCineLog", false))
        assertTrue("Expected 4K60 CUVA profile: $report", report.optBoolean("has4k60CuvaProfile", false))
        assertTrue("Expected 4K30 CUVA profile: $report", report.optBoolean("has4k30CuvaProfile", false))
        assertTrue("Expected 4K60 video profile: $report", report.optBoolean("has4k60VideoProfile", false))
        assertFalse("Unexpected untested >3840px 60fps LOG profile: $report", report.optBoolean("hasUntestedOpenGate4k60", true))
        val arms = report.optJSONArray("arms")
        assertTrue("Expected 2 production arms: $report", arms != null && arms.length() == 2)
        for (i in 0 until arms!!.length()) {
            val arm = arms.getJSONObject(i)
            assertTrue("Production arm failed: $arm", arm.optBoolean("ok", false))
        }
    }
}
