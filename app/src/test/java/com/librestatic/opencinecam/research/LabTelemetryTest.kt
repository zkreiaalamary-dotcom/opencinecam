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
    fun thirtyTwoCelsiusWithModerateStatusContinuesWithoutThermalPause() {
        val policy = LabThermalPolicy()
        val s1 = sample(elapsedMs = 1_000L, thermalStatus = 2, batteryTempTenthsC = 320)
        val s2 = sample(elapsedMs = 10_000L, thermalStatus = 2, batteryTempTenthsC = 320)
        val s3 = sample(elapsedMs = 60_000L, thermalStatus = 2, batteryTempTenthsC = 399)
        assertTrue(policy.evaluate(s1, betweenAttempts = false) is ThermalDecision.Continue)
        assertTrue(policy.evaluate(s2, betweenAttempts = true) is ThermalDecision.Continue)
        assertTrue(policy.evaluate(s3, betweenAttempts = true) is ThermalDecision.Continue)
        assertEquals(false, policy.inThermalPause)
    }

    @Test
    fun fortyCelsiusTriggersPauseAndAutoResumesOnlyAtThirtyFiveWithoutTimeoutAbort() {
        val policy = LabThermalPolicy()
        assertTrue(policy.evaluate(sample(elapsedMs = 1_000L, thermalStatus = 0, batteryTempTenthsC = 400), betweenAttempts = false) is ThermalDecision.CooldownWait)
        assertEquals(true, policy.inThermalPause)

        // Even 10 minutes later at 35.1 C, must stay in CooldownWait (never aborting with a 45s timeout)
        assertTrue(policy.evaluate(sample(elapsedMs = 300_000L, thermalStatus = 0, batteryTempTenthsC = 380), betweenAttempts = true) is ThermalDecision.CooldownWait)
        assertTrue(policy.evaluate(sample(elapsedMs = 600_000L, thermalStatus = 0, batteryTempTenthsC = 351), betweenAttempts = true) is ThermalDecision.CooldownWait)

        // Drops to 35.0 C -> auto-resumes!
        val resumed = policy.evaluate(sample(elapsedMs = 610_000L, thermalStatus = 0, batteryTempTenthsC = 350), betweenAttempts = true)
        assertTrue(resumed is ThermalDecision.Continue)
        assertEquals(false, policy.inThermalPause)
    }

    @Test
    fun dangerousOsThermalStatusPausesBelowFortyAndResumesWhenCleared() {
        val policy = LabThermalPolicy()
        // OS SEVERE (3) pauses even if battery is 33.0 C
        assertTrue(policy.evaluate(sample(elapsedMs = 1_000L, thermalStatus = 3, batteryTempTenthsC = 330), betweenAttempts = true) is ThermalDecision.CooldownWait)
        // Stays paused while still SEVERE
        assertTrue(policy.evaluate(sample(elapsedMs = 20_000L, thermalStatus = 3, batteryTempTenthsC = 320), betweenAttempts = true) is ThermalDecision.CooldownWait)
        // Clears once OS status < SEVERE and battery <= 35.0 C
        assertTrue(policy.evaluate(sample(elapsedMs = 30_000L, thermalStatus = 1, batteryTempTenthsC = 320), betweenAttempts = true) is ThermalDecision.Continue)
    }

    @Test
    fun emergencyThermalOrBatteryOrStorageLimitsTriggerResumableHardStop() {
        val policy = LabThermalPolicy()
        assertTrue(policy.evaluate(sample(thermalStatus = 5)) is ThermalDecision.HardStop)
        assertTrue(policy.evaluate(sample(batteryPercent = 24)) is ThermalDecision.HardStop)
        assertTrue(policy.evaluate(sample(freeBytes = LabStorageManager.DEVICE_FREE_RESERVE_BYTES - 1L)) is ThermalDecision.HardStop)
        assertTrue(policy.evaluate(sample(evidenceBytes = LabStorageManager.DEFAULT_EVIDENCE_LIMIT_BYTES)) is ThermalDecision.HardStop)
    }

    @Test
    fun normalizesBatteryUnitsAndSysZonesCleanly() {
        assertEquals(320, normalizeBatteryTempTenthsC(320))
        assertEquals(320, normalizeBatteryTempTenthsC(32_000))
        assertEquals(-1, normalizeBatteryTempTenthsC(-1))
        assertEquals(60.0, normalizeSysZoneC(60_000), 1e-6)
        assertEquals(38.5, normalizeSysZoneC(38_500), 1e-6)
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
