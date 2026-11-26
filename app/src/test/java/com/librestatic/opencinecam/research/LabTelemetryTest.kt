/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class LabTelemetryTest {
    @get:Rule val folder = TemporaryFolder()

    private fun sample(
        elapsedMs: Long = 10_000L,
        thermalStatus: Int = 1,
        batteryPercent: Int = 50,
        batteryTempTenthsC: Int = 340,
        freeBytes: Long = 20_000_000_000L,
        evidenceBytes: Long = 10_000_000L,
    ) = ThermalSample(
        elapsedMs = elapsedMs,
        epochMs = 1_700_000_000_000L + elapsedMs,
        thermalStatus = thermalStatus,
        headroom0s = 0.7f,
        headroom10s = 0.75f,
        headroom30s = 0.8f,
        batteryPercent = batteryPercent,
        batteryTempTenthsC = batteryTempTenthsC,
        batteryVoltageMv = 3900,
        batteryCurrentUa = -450_000,
        plugged = 0,
        freeBytes = freeBytes,
        evidenceBytes = evidenceBytes,
        sysZones = mapOf("soc" to 38000),
    )

    @Test
    fun singleTransientModerateDuringAttemptContinuesThenCooldownWaitsAndResumes() {
        val policy = LabThermalPolicy(maxCooldownMs = 10_000L)
        assertEquals(ThermalDecision.Continue(transientModerate = true), policy.evaluate(sample(elapsedMs = 1_000L, thermalStatus = 2), betweenAttempts = false))
        assertTrue(policy.evaluate(sample(elapsedMs = 2_000L, thermalStatus = 2), betweenAttempts = false) is ThermalDecision.CooldownWait)
        assertEquals(ThermalDecision.Continue(transientModerate = false), policy.evaluate(sample(elapsedMs = 4_000L, thermalStatus = 1), betweenAttempts = true))
        assertTrue(policy.evaluate(sample(elapsedMs = 5_000L, thermalStatus = 2), betweenAttempts = true) is ThermalDecision.CooldownWait)
        assertTrue(policy.evaluate(sample(elapsedMs = 15_000L, thermalStatus = 2), betweenAttempts = true) is ThermalDecision.HardStop)
    }

    @Test
    fun severeOrBatteryOrStorageLimitsTriggerImmediateHardStop() {
        val policy = LabThermalPolicy()
        assertTrue(policy.evaluate(sample(thermalStatus = 3)) is ThermalDecision.HardStop)
        assertTrue(policy.evaluate(sample(batteryTempTenthsC = 420)) is ThermalDecision.HardStop)
        assertTrue(policy.evaluate(sample(batteryPercent = 24)) is ThermalDecision.HardStop)
        assertTrue(policy.evaluate(sample(freeBytes = 1_000_000_000L)) is ThermalDecision.HardStop)
        assertTrue(policy.evaluate(sample(evidenceBytes = 960_000_000L)) is ThermalDecision.HardStop)
        assertTrue(policy.evaluate(sample(thermalStatus = 2, batteryTempTenthsC = 400)) is ThermalDecision.HardStop)
    }

    @Test
    fun journalAppendsTelemetryAndPauseReceiptIntoExportedZip() {
        val root = folder.newFolder()
        val journal = LabJournal.create(root, "rev-1")
        journal.appendTelemetry(sample(elapsedMs = 1_000L, thermalStatus = 1).toJson())
        journal.recordStop("Paused due to thermal load", sample(elapsedMs = 3_000L, thermalStatus = 2).toJson())

        val out = ByteArrayOutputStream()
        LabJournal.exportAll(root, out)
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        val telemetryEntry = entries.entries.first { it.key.endsWith("/telemetry.jsonl") }.value
        val pauseEntry = entries.entries.first { it.key.endsWith("/pause.json") }.value
        val lines = telemetryEntry.trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"thermalStatus\":1"))
        assertTrue(lines[1].contains("\"event\":\"run_paused\""))
        assertTrue(pauseEntry.contains("Paused due to thermal load"))
    }
}
