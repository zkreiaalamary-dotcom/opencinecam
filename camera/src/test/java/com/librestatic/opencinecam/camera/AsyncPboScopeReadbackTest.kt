/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsyncPboScopeReadbackTest {
    @Test
    fun doubleBufferedPingPongAlternatesSlotsAndTracksMapTimings() {
        val planner = PboReadbackPlanner()
        assertTrue(planner.needsReallocation(320, 180))
        planner.resetForSize(320, 180)
        assertFalse(planner.needsReallocation(320, 180))
        assertEquals(0, planner.writeSlot)
        assertFalse(planner.hasReadyReadSlot())

        // Frame 0 queues async write into slot 0 -> writeSlot moves to 1, readSlot=0 now ready
        planner.recordAsyncWriteQueued()
        assertEquals(1, planner.writeSlot)
        assertEquals(0, planner.readSlot)
        assertTrue(planner.hasReadyReadSlot())

        // Frame 1 drains readSlot (0), then queues async write into slot 1 -> writeSlot moves to 0
        planner.recordAsyncReadCompleted(420L)
        assertFalse(planner.hasReadyReadSlot())
        planner.recordAsyncWriteQueued()
        assertEquals(0, planner.writeSlot)
        assertEquals(1, planner.readSlot)
        assertTrue(planner.hasReadyReadSlot())

        planner.recordAsyncReadCompleted(580L)
        val snap = planner.snapshot(enabled = true)
        assertEquals(2L, snap.framesQueued)
        assertEquals(2L, snap.asyncReadsCompleted)
        assertEquals(500L, snap.avgMapUs)
        assertEquals(580L, snap.maxMapUs)
        assertFalse(snap.retired)

        // Resize clears primed state safely
        assertTrue(planner.needsReallocation(640, 360))
        planner.resetForSize(640, 360)
        assertFalse(planner.hasReadyReadSlot())

        // Retirement clears pending reads
        planner.recordAsyncWriteQueued()
        planner.markRetired()
        assertFalse(planner.hasReadyReadSlot())
        assertTrue(planner.snapshot(enabled = true).retired)
    }

    @Test
    fun syncFallbackRecordsReasonAndClearsPrimedState() {
        val planner = PboReadbackPlanner()
        planner.resetForSize(320, 180)
        planner.recordAsyncWriteQueued()
        planner.recordSyncFallback("glMapBufferRange unsupported")
        assertFalse(planner.hasReadyReadSlot())
        val snap = planner.snapshot(enabled = false)
        assertFalse(snap.enabled)
        assertEquals(1L, snap.syncFallbacks)
        assertEquals("glMapBufferRange unsupported", snap.lastFallbackReason)
    }
}
