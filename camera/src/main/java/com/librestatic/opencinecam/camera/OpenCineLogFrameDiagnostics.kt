/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.os.SystemClock
import android.util.Log

data class GpuFrameDiagSummary(
    val totalFrames: Long,
    val totalGaps: Long,
    val totalMissingFrames: Long,
    val maxTotalUs: Long,
    val maxLatchUs: Long,
    val maxEncoderDrawUs: Long,
    val maxEncoderSwapUs: Long,
    val maxScopesUs: Long,
    val maxSubjectUs: Long,
    val maxPreviewDrawUs: Long,
    val maxPreviewSwapUs: Long,
    val maxDrainWriteUs: Long,
    val maxDrainDequeueGapUs: Long,
)

/**
 * Opt-in frame-drop diagnostics for the OCLog GL thread and codec drain.
 *
 * Off unless `adb shell setprop log.tag.OCLogFrameDiag DEBUG` or research-mode diagnostics are
 * enabled before the pipeline is created. Each rendered frame records how long every phase of
 * [OpenCineLogGpuPipeline]'s render loop took. When the camera timestamp jumps by more than 1.5
 * frame intervals, the jump and the phase timings of the frames before it are logged, so a
 * camera-side gap (the loop was fast) can be told apart from back-pressure (the loop was slow
 * before the gap). A summary is logged every second and also available via [snapshotSummary].
 */
internal class OpenCineLogFrameDiagnostics private constructor() {
    enum class Phase { LATCH, ENCODER_DRAW, ENCODER_SWAP, SCOPES, SUBJECT, PREVIEW_DRAW, PREVIEW_SWAP }

    private class Frame(val sensorNs: Long, val callbackLagUs: Long, val phasesUs: LongArray, val totalUs: Long)

    private val history = ArrayDeque<Frame>()
    private var phasesUs = LongArray(Phase.entries.size)
    private var phaseStartNs = 0L
    private var frameStartNs = 0L
    private var previousSensorNs = 0L
    private var previousCallbackNs = 0L
    private var intervalNs = 0L

    // Per-second summary, GL thread.
    private var windowStartNs = 0L
    private var windowFrames = 0
    private var windowGaps = 0
    private var windowMissing = 0
    private val windowMaxUs = LongArray(Phase.entries.size)
    private var windowMaxTotalUs = 0L

    // Cumulative session counters for Research Lab diagnostics.
    @Volatile private var totalFrames = 0L
    @Volatile private var totalGaps = 0L
    @Volatile private var totalMissingFrames = 0L
    private val sessionMaxUs = LongArray(Phase.entries.size)
    @Volatile private var sessionMaxTotalUs = 0L
    @Volatile private var sessionMaxDrainWriteUs = 0L
    @Volatile private var sessionMaxDrainDequeueGapUs = 0L

    // Codec drain thread.
    @Volatile private var drainMaxWriteUs = 0L
    @Volatile private var drainMaxDequeueGapUs = 0L
    private var lastVideoOutputNs = 0L

    fun frameStart() {
        frameStartNs = SystemClock.elapsedRealtimeNanos()
        phaseStartNs = frameStartNs
        phasesUs = LongArray(Phase.entries.size)
    }

    fun mark(phase: Phase) {
        val now = SystemClock.elapsedRealtimeNanos()
        phasesUs[phase.ordinal] += (now - phaseStartNs) / 1_000
        phaseStartNs = now
    }

    /** Restarts the phase clock without charging the elapsed time to any phase. */
    fun skip() { phaseStartNs = SystemClock.elapsedRealtimeNanos() }

    fun frameEnd(sensorNs: Long, targetFps: Int) {
        val end = SystemClock.elapsedRealtimeNanos()
        val total = (end - frameStartNs) / 1_000
        // The camera timestamp shares the elapsedRealtime base on this pipeline (REALTIME source).
        val callbackLagUs = ((frameStartNs - sensorNs) / 1_000).coerceAtLeast(-1)
        val frame = Frame(sensorNs, callbackLagUs, phasesUs, total)
        if (targetFps > 0) intervalNs = 1_000_000_000L / targetFps
        if (previousSensorNs != 0L && intervalNs > 0) {
            val delta = sensorNs - previousSensorNs
            if (delta * 2 > intervalNs * 3) {
                val missing = ((delta + intervalNs / 2) / intervalNs - 1).toInt()
                windowGaps++
                windowMissing += missing
                totalGaps++
                totalMissingFrames += missing.toLong()
                Log.w(TAG, "GAP sensorDelta=${delta / 1_000}us missing=$missing " +
                    "callbackGap=${(frameStartNs - previousCallbackNs) / 1_000}us lastFrames=" +
                    history.joinToString(" | ") { describe(it) } + " | now: " + describe(frame))
            }
        }
        previousSensorNs = sensorNs
        previousCallbackNs = frameStartNs
        history.addLast(frame)
        while (history.size > HISTORY) history.removeFirst()
        windowFrames++
        totalFrames++
        for (i in phasesUs.indices) {
            windowMaxUs[i] = maxOf(windowMaxUs[i], phasesUs[i])
            sessionMaxUs[i] = maxOf(sessionMaxUs[i], phasesUs[i])
        }
        windowMaxTotalUs = maxOf(windowMaxTotalUs, total)
        sessionMaxTotalUs = maxOf(sessionMaxTotalUs, total)
        if (windowStartNs == 0L) windowStartNs = end
        if (end - windowStartNs >= 1_000_000_000L) {
            Log.d(TAG, "1s frames=$windowFrames gaps=$windowGaps missing=$windowMissing maxTotal=${windowMaxTotalUs}us max{" +
                Phase.entries.joinToString(" ") { "${it.name}=${windowMaxUs[it.ordinal]}" } +
                "} drain{maxWrite=${drainMaxWriteUs}us maxVideoOutputGap=${drainMaxDequeueGapUs}us}")
            windowStartNs = end
            windowFrames = 0
            windowGaps = 0
            windowMissing = 0
            windowMaxUs.fill(0)
            windowMaxTotalUs = 0
            drainMaxWriteUs = 0
            drainMaxDequeueGapUs = 0
        }
    }

    /** Called on the drain thread for each muxed video sample. */
    fun videoSampleWritten(writeUs: Long) {
        val now = SystemClock.elapsedRealtimeNanos()
        if (lastVideoOutputNs != 0L) {
            val gap = (now - lastVideoOutputNs) / 1_000
            drainMaxDequeueGapUs = maxOf(drainMaxDequeueGapUs, gap)
            sessionMaxDrainDequeueGapUs = maxOf(sessionMaxDrainDequeueGapUs, gap)
        }
        lastVideoOutputNs = now
        drainMaxWriteUs = maxOf(drainMaxWriteUs, writeUs)
        sessionMaxDrainWriteUs = maxOf(sessionMaxDrainWriteUs, writeUs)
        if (writeUs > SLOW_WRITE_US) Log.w(TAG, "SLOW muxer write ${writeUs}us")
    }

    fun snapshotSummary(): GpuFrameDiagSummary = GpuFrameDiagSummary(
        totalFrames = totalFrames,
        totalGaps = totalGaps,
        totalMissingFrames = totalMissingFrames,
        maxTotalUs = sessionMaxTotalUs,
        maxLatchUs = sessionMaxUs[Phase.LATCH.ordinal],
        maxEncoderDrawUs = sessionMaxUs[Phase.ENCODER_DRAW.ordinal],
        maxEncoderSwapUs = sessionMaxUs[Phase.ENCODER_SWAP.ordinal],
        maxScopesUs = sessionMaxUs[Phase.SCOPES.ordinal],
        maxSubjectUs = sessionMaxUs[Phase.SUBJECT.ordinal],
        maxPreviewDrawUs = sessionMaxUs[Phase.PREVIEW_DRAW.ordinal],
        maxPreviewSwapUs = sessionMaxUs[Phase.PREVIEW_SWAP.ordinal],
        maxDrainWriteUs = sessionMaxDrainWriteUs,
        maxDrainDequeueGapUs = sessionMaxDrainDequeueGapUs,
    )

    private fun describe(frame: Frame): String =
        "t=${frame.sensorNs / 1_000}us lag=${frame.callbackLagUs} total=${frame.totalUs} " +
            Phase.entries.filter { frame.phasesUs[it.ordinal] >= 500 }
                .joinToString(",") { "${it.name}=${frame.phasesUs[it.ordinal]}" }

    companion object {
        const val TAG = "OCLogFrameDiag"
        private const val HISTORY = 8
        private const val SLOW_WRITE_US = 8_000L

        /** Returns a recorder when the log tag is enabled or forced for research, otherwise null. */
        fun create(forceEnable: Boolean = false): OpenCineLogFrameDiagnostics? =
            if (forceEnable || Log.isLoggable(TAG, Log.DEBUG)) OpenCineLogFrameDiagnostics() else null

        /** Returns a recorder when the log tag is enabled, otherwise null (zero per-frame cost). */
        fun createIfEnabled(): OpenCineLogFrameDiagnostics? = create(forceEnable = false)
    }
}
