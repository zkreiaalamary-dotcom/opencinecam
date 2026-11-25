/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
class LabJournalTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun recoveryPreservesSavedMilestoneAndLeavesCompletedAttemptIntact() {
        val root = folder.newFolder()
        val case = LabCase("0", LabPath.AVC, 1920, 1080, 30)
        val journal = LabJournal.create(root, "build-A")
        journal.begin(LabDecision(case, null, "control")).apply {
            event(LabStage.FILE, json("fps" to 30))
            result(LabVerdict.OBSERVED)
        }
        journal.begin(LabDecision(case.copy(fps = 60), journal.history().single().id, "fps limit")).apply {
            event(LabStage.FIRST_ENCODED, json("ptsUs" to 2_000))
            clip.writeBytes(byteArrayOf(1, 2, 3))
        }

        val reopened = requireNotNull(LabJournal.latest(root))
        assertTrue(reopened.compatibleWith("build-A"))
        assertEquals(false, reopened.compatibleWith("build-B"))
        reopened.recoverInterrupted()
        reopened.recoverInterrupted()

        val records = reopened.history()
        assertEquals(listOf(LabVerdict.OBSERVED, LabVerdict.INTERRUPTED), records.map { it.verdict })
        assertEquals(LabStage.FIRST_ENCODED, records.last().stage)
        val output = ByteArrayOutputStream()
        LabJournal.exportAll(root, output)
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            val entries = generateSequence { zip.nextEntry?.name }.toList()
            assertTrue(entries.any { it.endsWith("/${records.last().id}/result.json") })
            assertTrue(entries.any { it.endsWith("/${records.last().id}/clip.mp4") })
        }
    }
}
