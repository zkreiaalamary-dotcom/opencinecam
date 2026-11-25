/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.research

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.view.Surface
import com.librestatic.opencinecam.camera.CaptureOwnerAdmission
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Camera2 feeds MediaRecorder directly. The optional second target is a drained PRIVATE camera stream. */
internal class LabDirectRecorder(private val context: Context, private val checkBudget: () -> Unit) {
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun run(case: LabCase, attempt: LabJournal.Attempt): Pair<LabVerdict, String?> {
        val manager = context.getSystemService(CameraManager::class.java)
        val caps = manager.getCameraCharacteristics(case.camera)
        val twoOutputs = case.path == LabPath.DIRECT_HEVC_PREVIEW
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        try {
            lease.ready().get(8, TimeUnit.SECONDS)
            checkBudget()
        } catch (failure: Exception) {
            lease.releaseAfter(CompletableFuture.completedFuture(Unit))
            throw failure
        }
        val thread = HandlerThread("occ-direct-camera").apply { start() }
        val handler = Handler(thread.looper)
        val opened = CompletableFuture<CameraDevice>()
        val closed = CompletableFuture<Unit>()
        val configured = CompletableFuture<CameraCaptureSession>()
        val retiring = AtomicBoolean()
        val recording = AtomicBoolean()
        val fault = AtomicReference<String?>()
        val previewFrames = AtomicInteger()
        val failedCaptures = AtomicInteger()
        val lostBuffers = AtomicInteger()
        val results = AtomicInteger()
        val activeLens = AtomicReference<String?>()
        val signals = LabSignals()
        var recorder: MediaRecorder? = null
        var sink: ImageReader? = null
        var encoderSurface: Surface? = null
        var activeSession: CameraCaptureSession? = null
        var openSubmitted = false
        fun stage(name: String) = attempt.notice(json("event" to "direct_step", "step" to name))
        try {
            attempt.notice(json("event" to "direct_graph", "camera" to case.camera, "cameraTargetCount" to if (twoOutputs) 2 else 1,
                "encoder" to "MediaRecorder/HEVC", "width" to case.width, "height" to case.height,
                "fps" to case.fps, "bitrate" to 60_000_000, "audio" to false, "sessionParameters" to false,
                "preview" to if (twoOutputs) "1280x720 PRIVATE ImageReader" else null))
            stage("recorder_prepare")
            val output = MediaRecorder()
            recorder = output
            output.setOnErrorListener { _, what, extra -> fault.compareAndSet(null, "MediaRecorder error $what/$extra") }
            output.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            output.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            output.setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
            output.setVideoSize(case.width, case.height)
            output.setVideoFrameRate(case.fps)
            output.setVideoEncodingBitRate(60_000_000)
            output.setOrientationHint(caps.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0)
            output.setOutputFile(attempt.clip.absolutePath)
            output.prepare()
            val targets = mutableListOf(output.surface.also { encoderSurface = it })
            if (twoOutputs) {
                val reader = ImageReader.newInstance(1280, 720, ImageFormat.PRIVATE, 3)
                sink = reader
                reader.setOnImageAvailableListener({ source ->
                    try {
                        source.acquireLatestImage()?.use { if (recording.get()) previewFrames.incrementAndGet() }
                    } catch (e: IllegalStateException) {
                        if (!retiring.get()) fault.compareAndSet(null, "Preview drain failed: ${e.message}")
                    }
                }, handler)
                targets += reader.surface
            }
            stage("camera_open")
            manager.openCamera(case.camera, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    opened.complete(camera)
                    if (retiring.get()) camera.close()
                }
                private fun fail(camera: CameraDevice, reason: String) {
                    fault.compareAndSet(null, reason)
                    opened.completeExceptionally(IllegalStateException(reason))
                    configured.completeExceptionally(IllegalStateException(reason))
                    camera.close()
                }
                override fun onDisconnected(camera: CameraDevice) = fail(camera, "Camera disconnected")
                override fun onError(camera: CameraDevice, error: Int) = fail(camera, "CameraDevice error $error")
                override fun onClosed(camera: CameraDevice) { closed.complete(Unit) }
            }, handler)
            openSubmitted = true
            val camera = opened.get(8, TimeUnit.SECONDS)
            attempt.event(LabStage.CAMERA, json("event" to "camera_opened", "id" to camera.id))
            stage("session_configure")
            camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (retiring.get()) session.close() else configured.complete(session)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    configured.completeExceptionally(IllegalStateException("Regular session rejected ${targets.size} targets"))
                    session.close()
                }
            }, handler)
            val session = configured.get(8, TimeUnit.SECONDS)
            activeSession = session
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                targets.forEach { addTarget(it) }
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(case.fps, case.fps))
                if (caps.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.contains(CaptureRequest.EDGE_MODE_OFF) == true)
                    set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                if (caps.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.contains(CaptureRequest.NOISE_REDUCTION_MODE_OFF) == true)
                    set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            }.build()
            stage("repeat_request")
            session.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    if (!recording.get()) return
                    results.incrementAndGet()
                    result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)?.let(activeLens::set)
                    signals.onResult(request, result, result.get(CaptureResult.SENSOR_TIMESTAMP))
                }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    if (recording.get()) failedCaptures.incrementAndGet()
                }
                override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
                    if (recording.get()) lostBuffers.incrementAndGet()
                }
            }, handler)
            stage("recorder_start")
            output.start()
            recording.set(true)
            val end = SystemClock.elapsedRealtime() + 4_000
            try {
                attempt.event(LabStage.RECORDER, json("event" to "recording", "edge" to request.get(CaptureRequest.EDGE_MODE),
                    "noise" to request.get(CaptureRequest.NOISE_REDUCTION_MODE)))
                while (SystemClock.elapsedRealtime() < end) {
                    checkBudget()
                    fault.get()?.let { error(it) }
                    Thread.sleep(80)
                }
            } finally {
                recording.set(false)
            }
            stage("recorder_stop")
            session.stopRepeating()
            output.stop()
            attempt.event(LabStage.STOPPED, json("event" to "recorder_finalized"))
        } finally {
            recording.set(false)
            retiring.set(true)
            val retired = closed.thenApply {
                // Try every release even if one vendor call throws. An unverified retirement blocks the next owner.
                val outcomes = listOf(runCatching { recorder?.release() }, runCatching { encoderSurface?.release() },
                    runCatching { sink?.close() })
                thread.quitSafely()
                outcomes.forEach { it.getOrThrow() }
                Unit
            }
            lease.releaseAfter(retired)
            if (openSubmitted) {
                handler.post {
                    try {
                        activeSession?.close()
                    } finally {
                        if (!opened.isCompletedExceptionally) opened.getNow(null)?.close()
                    }
                }
            } else closed.complete(Unit)
            try {
                retired.get(8, TimeUnit.SECONDS)
                thread.join(8_000)
                check(!thread.isAlive) { "Camera callback thread still active" }
            } catch (error: Exception) {
                throw LabRetirementFailure(error)
            } finally {
                signals.drain(attempt)
                val sensor = signals.sensorCadence(case.fps)
                attempt.notice(signals.snapshot().put("event", "direct_capture_receipt")
                    .put("sensorCadence", json("samples" to sensor.samples, "fps" to sensor.fps,
                        "p95Us" to sensor.p95Us, "p99Us" to sensor.p99Us, "maxUs" to sensor.maxUs,
                        "duplicateTimestamps" to sensor.repeatedPts, "longIntervals" to sensor.longIntervals))
                    .put("resultCount", results.get()).put("previewImagesDrained", previewFrames.get())
                    .put("failedCaptures", failedCaptures.get()).put("lostBuffers", lostBuffers.get())
                    .put("lastReportedPhysicalId", activeLens.get()).put("asyncError", fault.get()))
            }
        }
        val file = inspectClip(case, attempt)
        val mismatch = when {
            fault.get() != null -> fault.get()
            results.get() == 0 -> "No Camera2 results during the recording."
            twoOutputs && previewFrames.get() == 0 -> "The second camera output delivered no observed images."
            failedCaptures.get() + lostBuffers.get() > 0 -> "Camera2 reported failed captures or lost output buffers."
            else -> signals.sensorCadence(case.fps).shortDirectIssue(case.fps)?.let { "Camera2 sensor timing: $it" }
        }
        return if (file.first == LabVerdict.MISMATCH || mismatch == null) file else LabVerdict.MISMATCH to mismatch
    }
}
