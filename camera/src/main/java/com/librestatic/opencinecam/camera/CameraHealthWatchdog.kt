/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.content.Context
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.SystemClock
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

enum class CameraHealthIssue {
    DAEMON_BOUNCE,
    OPEN_TIMEOUT,
    SERVICE_ERROR,
    DEVICE_ERROR,
    ACCESS_ERROR,
}

data class CameraHealthEvent(
    val issue: CameraHealthIssue,
    val cameraId: String?,
    val detail: String,
    val timestampMs: Long,
)

/**
 * Pure state machine detecting CameraDaemon restarts / HISP hangs without false-triggering on
 * normal app-initiated single-camera opens. Thread-safe via synchronization.
 */
class CameraHealthTracker(
    private val burstWindowMs: Long = DAEMON_BOUNCE_WINDOW_MS,
    private val burstThreshold: Int = DAEMON_BOUNCE_DISTINCT_CAMERAS,
    private val expectedOpenWindowMs: Long = EXPECTED_OPEN_GRACE_MS,
) {
    private val lock = Any()
    private val expectedUnavailableUntil = LinkedHashMap<String, Long>()
    private val recentUnexpectedUnavailable = ArrayDeque<Pair<String, Long>>()
    private val events = ArrayDeque<CameraHealthEvent>()
    private var lastBounceEmitMs: Long = -10_000L

    fun markExpectedUnavailable(cameraId: String?, nowMs: Long) {
        if (cameraId == null) return
        synchronized(lock) {
            expectedUnavailableUntil[cameraId] = nowMs + expectedOpenWindowMs
        }
    }

    fun clearExpectedUnavailable(cameraId: String?, nowMs: Long) {
        if (cameraId == null) return
        synchronized(lock) {
            val deadline = expectedUnavailableUntil[cameraId]
            if (deadline != null && nowMs >= deadline) expectedUnavailableUntil.remove(cameraId)
            else expectedUnavailableUntil.remove(cameraId)
        }
    }

    fun onCameraUnavailable(cameraId: String, nowMs: Long): CameraHealthEvent? = synchronized(lock) {
        val expectedDeadline = expectedUnavailableUntil[cameraId]
        if (expectedDeadline != null && nowMs <= expectedDeadline) {
            return@synchronized null
        }
        recentUnexpectedUnavailable.addLast(cameraId to nowMs)
        while (recentUnexpectedUnavailable.isNotEmpty() &&
            nowMs - recentUnexpectedUnavailable.first().second > burstWindowMs
        ) {
            recentUnexpectedUnavailable.removeFirst()
        }
        val distinctIds = recentUnexpectedUnavailable.mapTo(LinkedHashSet()) { it.first }
        if (distinctIds.size >= burstThreshold && nowMs - lastBounceEmitMs >= burstWindowMs) {
            lastBounceEmitMs = nowMs
            val ev = CameraHealthEvent(
                issue = CameraHealthIssue.DAEMON_BOUNCE,
                cameraId = cameraId,
                detail = "${distinctIds.size} cameras went unavailable within ${burstWindowMs}ms (${distinctIds.joinToString(",")}); possible CameraDaemon/HAL bounce.",
                timestampMs = nowMs,
            )
            recordEventLocked(ev)
            return@synchronized ev
        }
        null
    }

    fun onCameraAvailable(cameraId: String, nowMs: Long) {
        synchronized(lock) {
            expectedUnavailableUntil.remove(cameraId)
        }
    }

    fun onCameraError(cameraId: String?, errorCode: Int, nowMs: Long): CameraHealthEvent = synchronized(lock) {
        val issue = when (errorCode) {
            CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> CameraHealthIssue.SERVICE_ERROR
            CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> CameraHealthIssue.DEVICE_ERROR
            else -> CameraHealthIssue.ACCESS_ERROR
        }
        val ev = CameraHealthEvent(
            issue = issue,
            cameraId = cameraId,
            detail = "CameraDevice error=$errorCode on camera=${cameraId ?: "unknown"}",
            timestampMs = nowMs,
        )
        recordEventLocked(ev)
        ev
    }

    fun onOpenTimeout(cameraId: String?, elapsedMs: Long, nowMs: Long): CameraHealthEvent = synchronized(lock) {
        val ev = CameraHealthEvent(
            issue = CameraHealthIssue.OPEN_TIMEOUT,
            cameraId = cameraId,
            detail = "openCamera(${cameraId ?: "unknown"}) hung for ${elapsedMs}ms without callback (possible AP_COMMAND_ACQUIRE_CAMERA / HISP stall).",
            timestampMs = nowMs,
        )
        recordEventLocked(ev)
        ev
    }

    fun recordAccessException(cameraId: String?, reason: Int, message: String?, nowMs: Long): CameraHealthEvent = synchronized(lock) {
        val ev = CameraHealthEvent(
            issue = CameraHealthIssue.ACCESS_ERROR,
            cameraId = cameraId,
            detail = "CameraAccessException reason=$reason on camera=${cameraId ?: "unknown"}: ${message ?: ""}",
            timestampMs = nowMs,
        )
        recordEventLocked(ev)
        ev
    }

    fun recentEvents(limit: Int = 16): List<CameraHealthEvent> = synchronized(lock) {
        events.takeLast(limit.coerceAtLeast(0))
    }

    private fun recordEventLocked(event: CameraHealthEvent) {
        events.addLast(event)
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    companion object {
        const val DAEMON_BOUNCE_WINDOW_MS = 1_500L
        const val DAEMON_BOUNCE_DISTINCT_CAMERAS = 2
        const val EXPECTED_OPEN_GRACE_MS = 2_000L
        const val OPEN_TIMEOUT_MS = 6_000L
        private const val MAX_EVENTS = 64
    }
}

class CameraHealthWatchdog(
    private val manager: CameraManager,
    private val executor: Executor,
    private val handler: Handler,
    private val onIssue: (CameraHealthEvent) -> Unit = {},
) : AutoCloseable {
    val tracker = CameraHealthTracker()
    private val closed = AtomicBoolean(false)
    private val callback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            if (closed.get()) return
            tracker.onCameraUnavailable(cameraId, SystemClock.elapsedRealtime())?.let(onIssue)
        }

        override fun onCameraAvailable(cameraId: String) {
            if (closed.get()) return
            tracker.onCameraAvailable(cameraId, SystemClock.elapsedRealtime())
        }
    }

    init {
        runCatching { manager.registerAvailabilityCallback(executor, callback) }
    }

    fun noteAppOpening(cameraId: String) {
        tracker.markExpectedUnavailable(cameraId, SystemClock.elapsedRealtime())
    }

    fun noteCameraError(cameraId: String?, error: Int): CameraHealthEvent {
        val ev = tracker.onCameraError(cameraId, error, SystemClock.elapsedRealtime())
        onIssue(ev)
        return ev
    }

    fun noteAccessException(cameraId: String?, reason: Int, message: String?): CameraHealthEvent {
        val ev = tracker.recordAccessException(cameraId, reason, message, SystemClock.elapsedRealtime())
        onIssue(ev)
        return ev
    }

    fun scheduleOpenTimeout(
        cameraId: String,
        timeoutMs: Long = CameraHealthTracker.OPEN_TIMEOUT_MS,
        onTimeout: (CameraHealthEvent) -> Unit,
    ): Runnable {
        val startedAt = SystemClock.elapsedRealtime()
        val runnable = Runnable {
            if (closed.get()) return@Runnable
            val ev = tracker.onOpenTimeout(cameraId, SystemClock.elapsedRealtime() - startedAt, SystemClock.elapsedRealtime())
            onIssue(ev)
            onTimeout(ev)
        }
        handler.postDelayed(runnable, timeoutMs)
        return runnable
    }

    fun cancelOpenTimeout(runnable: Runnable?) {
        if (runnable != null) handler.removeCallbacks(runnable)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { manager.unregisterAvailabilityCallback(callback) }
    }
}
