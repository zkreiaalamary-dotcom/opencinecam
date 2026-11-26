/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class LabStorageManagerTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun defaultLimitIs20GbReserveIs5GiBAndPersistsInPrefs() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals(20_000_000_000L, LabStorageManager.loadEvidenceLimitBytes(ctx))
        assertEquals(5L * 1024L * 1024L * 1024L, LabStorageManager.DEVICE_FREE_RESERVE_BYTES)

        val saved = LabStorageManager.saveEvidenceLimitBytes(ctx, 30_000_000_000L)
        assertEquals(30_000_000_000L, saved)
        assertEquals(30_000_000_000L, LabStorageManager.loadEvidenceLimitBytes(ctx))

        // Clamp check
        assertEquals(LabStorageManager.MAX_EVIDENCE_LIMIT_BYTES, LabStorageManager.saveEvidenceLimitBytes(ctx, 999_000_000_000L))
    }

    @Test
    fun measureUsageAvoidsDoubleCountingOverlappingDirectories() {
        val root = folder.newFolder("research")
        val attemptDir = File(root, "001-test").apply { mkdirs() }
        val clip = File(attemptDir, "clip.mp4").apply { writeBytes(ByteArray(4096) { 0x11 }) }
        val meta = File(attemptDir, "result.json").apply { writeText("{\"verdict\":\"OBSERVED\"}") }
        File(attemptDir, "stale.tmp").apply { writeBytes(ByteArray(5000)) }

        val usage = LabStorageManager.measureUsage(
            root = root,
            additionalDirs = listOf(attemptDir), // overlapping child dir must not double-count
            freeBytes = 30_000_000_000L,
            limitBytes = 20_000_000_000L,
        )
        assertEquals(clip.length(), usage.mediaBytes)
        assertEquals(meta.length(), usage.metadataBytes)
        assertEquals(clip.length() + meta.length(), usage.totalBytes)
        assertEquals(2, usage.fileCount)
    }

    @Test
    fun checkSpaceEnforcesBoth20GbCapAnd5GiBDeviceReserve() {
        val root = folder.newFolder("cap-test")
        File(root, "run.json").writeText("{}")

        val ok = LabStorageManager.checkSpace(
            root = root,
            freeBytes = 12_000_000_000L,
            limitBytes = 20_000_000_000L,
            upcomingEstimateBytes = 500_000_000L,
        )
        assertTrue(ok.allowed)

        // Protecting 5 GiB reserve blocks attempt even if evidence < 20 GB
        val reserveBlocked = LabStorageManager.checkSpace(
            root = root,
            freeBytes = LabStorageManager.DEVICE_FREE_RESERVE_BYTES + 100_000_000L,
            limitBytes = 20_000_000_000L,
            upcomingEstimateBytes = 300_000_000L,
        )
        assertFalse(reserveBlocked.allowed)
        assertTrue(reserveBlocked.reason.orEmpty().contains("5.0 GiB"))
    }

    @Test
    fun streamingVerifiedExportAndManualPrunePreserveLogsAndKnowledge() {
        val root = folder.newFolder("evidence")
        val exportDir = folder.newFolder("exports")
        val runDir = File(root, "run-1").apply { mkdirs() }
        val knowledge = File(runDir, "knowledge.json").apply { writeText("{\"hypotheses\":[]}") }
        val telemetry = File(runDir, "telemetry.jsonl").apply { writeText("{\"event\":\"telemetry\"}\n") }
        val report = File(runDir, "report.md").apply { writeText("# Report\n") }
        val attemptDir = File(runDir, "001-attempt").apply { mkdirs() }
        val clip = File(attemptDir, "clip.mp4").apply { writeBytes(ByteArray(8192) { 0x42 }) }
        val resultJson = File(attemptDir, "result.json").apply { writeText("{\"verdict\":\"OBSERVED\"}") }

        // Cannot prune before verified export
        val prematurePrune = LabStorageManager.pruneVerifiedExportedMedia(root)
        assertFalse(prematurePrune.executed)
        assertTrue(clip.isFile)

        // Mismatched readback fails verification cleanly and leaves originals untouched
        val badZip = File(exportDir, "bad.zip")
        val mismatch = LabStorageManager.streamingVerifiedExport(
            root = root,
            outputProvider = { FileOutputStream(badZip) },
            readbackProvider = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
        )
        assertFalse(mismatch.verified)
        assertFalse(LabStorageManager.pruneVerifiedExportedMedia(root).executed)
        assertTrue(clip.isFile)

        // Full streaming export + readback verification succeeds without temp ZIP inside root
        val goodZip = File(exportDir, "OpenCineCam-research.zip")
        val verification = LabStorageManager.streamingVerifiedExport(
            root = root,
            outputProvider = { FileOutputStream(goodZip) },
            readbackProvider = { FileInputStream(goodZip) },
        )
        assertTrue(verification.verified)
        assertEquals(5, verification.receipt.entryCount) // 5 files + ? wait: 5 files on disk

        val zipNames = mutableSetOf<String>()
        ZipInputStream(FileInputStream(goodZip)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                zipNames += e.name
            }
        }
        assertTrue("run-1/001-attempt/clip.mp4" in zipNames)
        assertTrue("run-1/knowledge.json" in zipNames)
        assertTrue("export-manifest.json" in zipNames)

        // Manual pruning removes ONLY verified media, preserving ALL logs/knowledge/reports
        val prune = LabStorageManager.pruneVerifiedExportedMedia(root)
        assertTrue(prune.executed)
        assertEquals(1, prune.deletedFiles)
        assertEquals(8192L, prune.freedBytes)
        assertFalse(clip.exists())
        assertTrue(knowledge.isFile)
        assertTrue(telemetry.isFile)
        assertTrue(report.isFile)
        assertTrue(resultJson.isFile)
    }
}
