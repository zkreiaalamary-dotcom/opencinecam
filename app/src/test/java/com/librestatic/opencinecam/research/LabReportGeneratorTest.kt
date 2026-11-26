/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import org.json.JSONObject
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
class LabReportGeneratorTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun generatesMarkdownAndJsonReportPackagedIntoExportZip() {
        val root = folder.newFolder()
        val journal = LabJournal.create(root, "rev-test")
        journal.inventory(json("selectedCamera" to "0", "hlg10Advertised" to false, "huaweiCameraKitClassPresent" to false))
        journal.appendTelemetry(json("batteryTempTenthsC" to 325, "thermalStatus" to 0, "batteryPct" to 85))
        journal.appendTelemetry(json("batteryTempTenthsC" to 362, "thermalStatus" to 1, "batteryPct" to 82))

        val kb = LabKnowledgeBase.loadOrCreate(journal.directory)
        kb.ingestInventory(requireNotNull(journal.inventoryJson()))
        val logCase = LabCase("0", LabPath.LOG_REGULAR_4K30, 3840, 2160, 30, listed = false)
        val attempt = journal.begin(LabDecision(logCase, null, "4K30 test"))
        attempt.event(LabStage.FILE, json("mime" to "video/hevc", "codedBitDepthFromProfile" to 10, "observedFileFps" to 29.97))
        attempt.result(LabVerdict.OBSERVED, null)
        kb.ingestAttempt(attempt.directory.name, logCase, LabVerdict.OBSERVED, null, attempt.readEvents())

        val mdFile = LabReportGenerator.generate(
            journalDir = journal.directory,
            inventory = journal.inventoryJson(),
            history = journal.history(),
            knowledge = kb,
            stopReason = "Done",
        )
        assertTrue(mdFile.isFile)
        val mdText = mdFile.readText(Charsets.UTF_8)
        assertTrue(mdText.contains("H-04"))
        assertTrue(mdText.contains("CONFIRMED"))
        assertTrue(mdText.contains("36.2"))
        assertTrue(mdText.contains("POST_ISP_8BIT_SDR_DERIVED"))

        val jsonReport = JSONObject(File(journal.directory, "report.json").readText(Charsets.UTF_8))
        assertEquals(1, jsonReport.getInt("observedAttempts"))
        assertEquals("36.2", jsonReport.getJSONObject("telemetry").getString("maxBatteryTempC"))

        val zipBytes = ByteArrayOutputStream()
        LabJournal.exportAll(root, zipBytes)
        ZipInputStream(ByteArrayInputStream(zipBytes.toByteArray())).use { zip ->
            val names = generateSequence { zip.nextEntry?.name }.toList()
            assertTrue(names.any { it.endsWith("/report.md") })
            assertTrue(names.any { it.endsWith("/report.json") })
            assertTrue(names.any { it.endsWith("/knowledge.json") })
            assertTrue(names.any { it.endsWith("/telemetry.jsonl") })
        }
    }
}
