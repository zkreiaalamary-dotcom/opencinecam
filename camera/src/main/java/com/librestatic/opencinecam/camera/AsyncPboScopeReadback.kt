/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.opengl.GLES30
import android.os.SystemClock
import java.nio.ByteBuffer
import kotlin.math.max

data class AsyncPboStats(
    val enabled: Boolean,
    val framesQueued: Long,
    val asyncReadsCompleted: Long,
    val syncFallbacks: Long,
    val avgMapUs: Long,
    val maxMapUs: Long,
    val activeWidth: Int,
    val activeHeight: Int,
    val retired: Boolean,
    val lastFallbackReason: String? = null,
)

/**
 * Pure JVM ping-pong ring state machine for two GL_PIXEL_PACK_BUFFER slots.
 * Keeps allocation, resize invalidation, latency priming, and retirement testable without EGL.
 */
internal class PboReadbackPlanner {
    var width: Int = 0
        private set
    var height: Int = 0
        private set
    var writeSlot: Int = 0
        private set
    val readSlot: Int get() = 1 - writeSlot
    private val slotPrimed = booleanArrayOf(false, false)
    var framesQueued: Long = 0L
        private set
    var asyncReadsCompleted: Long = 0L
        private set
    var syncFallbacks: Long = 0L
        private set
    var totalMapUs: Long = 0L
        private set
    var maxMapUs: Long = 0L
        private set
    var retired: Boolean = false
        private set
    var lastFallbackReason: String? = null
        private set

    fun needsReallocation(targetWidth: Int, targetHeight: Int): Boolean =
        targetWidth != width || targetHeight != height

    fun resetForSize(targetWidth: Int, targetHeight: Int) {
        width = targetWidth
        height = targetHeight
        writeSlot = 0
        slotPrimed[0] = false
        slotPrimed[1] = false
    }

    fun hasReadyReadSlot(): Boolean = !retired && slotPrimed[readSlot]

    fun recordAsyncReadCompleted(mapDurationUs: Long) {
        asyncReadsCompleted++
        totalMapUs += mapDurationUs.coerceAtLeast(0L)
        maxMapUs = max(maxMapUs, mapDurationUs.coerceAtLeast(0L))
        slotPrimed[readSlot] = false
    }

    fun recordAsyncWriteQueued() {
        slotPrimed[writeSlot] = true
        framesQueued++
        writeSlot = 1 - writeSlot
    }

    fun recordSyncFallback(reason: String) {
        syncFallbacks++
        lastFallbackReason = reason
        slotPrimed[0] = false
        slotPrimed[1] = false
    }

    fun markRetired() {
        retired = true
        slotPrimed[0] = false
        slotPrimed[1] = false
    }

    fun snapshot(enabled: Boolean): AsyncPboStats = AsyncPboStats(
        enabled = enabled,
        framesQueued = framesQueued,
        asyncReadsCompleted = asyncReadsCompleted,
        syncFallbacks = syncFallbacks,
        avgMapUs = if (asyncReadsCompleted > 0L) totalMapUs / asyncReadsCompleted else 0L,
        maxMapUs = maxMapUs,
        activeWidth = width,
        activeHeight = height,
        retired = retired,
        lastFallbackReason = lastFallbackReason,
    )
}

/**
 * Double-buffered asynchronous GLES 3.0 GL_PIXEL_PACK_BUFFER reader for scope analysis.
 * Eliminates blocking glReadPixels GPU pipeline flushes by overlapping DMA readback of the
 * previous scope sample with encoding/preview rendering, while falling back cleanly if the
 * driver refuses PBO mapping.
 */
internal class AsyncPboScopeReader {
    private val planner = PboReadbackPlanner()
    private val pboIds = intArrayOf(0, 0)
    private var allocatedByteSize = 0
    private var reusableOutBytes: ByteArray? = null
    private var pboDisabledForSession = false

    fun readRgba(width: Int, height: Int): ByteArray {
        val byteSize = width * height * 4
        if (pboDisabledForSession) {
            return readSyncBytes(width, height, byteSize)
        }
        return try {
            ensurePboCapacity(width, height, byteSize)
            var resultBytes: ByteArray? = null

            // Step 1: Drain the completed asynchronous readback from the previous sample (readSlot).
            if (planner.hasReadyReadSlot()) {
                val readId = pboIds[planner.readSlot]
                val t0 = SystemClock.elapsedRealtimeNanos()
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, readId)
                val mapped = GLES30.glMapBufferRange(
                    GLES30.GL_PIXEL_PACK_BUFFER,
                    0,
                    byteSize,
                    GLES30.GL_MAP_READ_BIT,
                ) as? ByteBuffer
                if (mapped == null) {
                    GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
                    val err = GLES30.glGetError()
                    throw IllegalStateException("glMapBufferRange returned null (glError=0x${err.toString(16)})")
                }
                val out = ensureOutArray(byteSize)
                mapped.position(0)
                mapped.get(out, 0, byteSize)
                val unmapped = GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
                val mapUs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000L
                if (!unmapped) {
                    throw IllegalStateException("glUnmapBuffer indicated lost buffer contents")
                }
                planner.recordAsyncReadCompleted(mapUs)
                resultBytes = out.copyOf()
            }

            // Step 2: Issue non-blocking async glReadPixels into writeSlot for the next sample.
            val writeId = pboIds[planner.writeSlot]
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, writeId)
            GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 0)
            val err = GLES30.glGetError()
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            if (err != GLES30.GL_NO_ERROR) {
                throw IllegalStateException("Async glReadPixels failed with GL error 0x${err.toString(16)}")
            }
            planner.recordAsyncWriteQueued()

            // First sample before readSlot is primed: avoid delaying initial UI scopes if caller expects pixels.
            resultBytes ?: readSyncBytes(width, height, byteSize)
        } catch (t: Throwable) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            while (GLES30.glGetError() != GLES30.GL_NO_ERROR) { /* drain stale error */ }
            planner.recordSyncFallback(t.message ?: t.javaClass.simpleName)
            pboDisabledForSession = true
            releaseGlResourcesOnly()
            readSyncBytes(width, height, byteSize)
        }
    }

    fun release() {
        releaseGlResourcesOnly()
        planner.markRetired()
    }

    fun stats(): AsyncPboStats = planner.snapshot(enabled = !pboDisabledForSession)

    private fun ensurePboCapacity(width: Int, height: Int, byteSize: Int) {
        if (!planner.needsReallocation(width, height) && pboIds[0] != 0 && allocatedByteSize == byteSize) {
            return
        }
        releaseGlResourcesOnly()
        GLES30.glGenBuffers(2, pboIds, 0)
        check(pboIds[0] != 0 && pboIds[1] != 0) { "Failed to allocate PBO buffers" }
        for (id in pboIds) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, id)
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, byteSize, null, GLES30.GL_STREAM_READ)
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        val err = GLES30.glGetError()
        check(err == GLES30.GL_NO_ERROR) { "PBO buffer storage allocation failed (0x${err.toString(16)})" }
        allocatedByteSize = byteSize
        planner.resetForSize(width, height)
    }

    private fun releaseGlResourcesOnly() {
        if (pboIds[0] != 0 || pboIds[1] != 0) {
            runCatching {
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
                GLES30.glDeleteBuffers(2, pboIds, 0)
            }
            pboIds[0] = 0
            pboIds[1] = 0
            allocatedByteSize = 0
        }
    }

    private fun readSyncBytes(width: Int, height: Int, byteSize: Int): ByteArray {
        val rgba = ByteBuffer.allocateDirect(byteSize)
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rgba)
        val err = GLES30.glGetError()
        check(err == GLES30.GL_NO_ERROR) { "Scope readback failed with GL error 0x${err.toString(16)}." }
        val bytes = ByteArray(byteSize)
        rgba.position(0)
        rgba.get(bytes)
        return bytes
    }

    private fun ensureOutArray(byteSize: Int): ByteArray {
        val current = reusableOutBytes
        if (current != null && current.size == byteSize) return current
        return ByteArray(byteSize).also { reusableOutBytes = it }
    }
}
