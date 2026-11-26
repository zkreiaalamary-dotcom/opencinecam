/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.research

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.media.MediaRecorder
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.view.Surface
import com.librestatic.opencinecam.camera.CaptureOwnerAdmission
import com.librestatic.opencinecam.camera.GpuEglDisplayLease
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Camera2 feeds MediaRecorder directly. Optional second target: drained PRIVATE ImageReader or live GL SurfaceTexture. */
internal class LabDirectRecorder(private val context: Context, private val checkBudget: () -> Unit) {
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun run(case: LabCase, attempt: LabJournal.Attempt): Pair<LabVerdict, String?> {
        val manager = context.getSystemService(CameraManager::class.java)
        val caps = manager.getCameraCharacteristics(case.camera)
        val useImageReaderPreview = case.path == LabPath.DIRECT_HEVC_PREVIEW
        val useSurfaceTexturePreview = case.path == LabPath.DIRECT_HEVC_SURFACETEXTURE
        val twoOutputs = useImageReaderPreview || useSurfaceTexturePreview
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
        val glThread = if (useSurfaceTexturePreview) HandlerThread("occ-direct-st-gl").apply { start() } else null
        val glHandler = glThread?.let { Handler(it.looper) }
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
        var previewSurfaceTexture: SurfaceTexture? = null
        val previewTextureSurface = AtomicReference<Surface?>()
        var stEglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        val stEglAcquired = AtomicBoolean()
        var stEglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        var stEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var stTexId = 0
        var encoderSurface: Surface? = null
        var activeSession: CameraCaptureSession? = null
        var openSubmitted = false
        val durationMs = if (case.longer) 30_000L else 4_000L
        fun stage(name: String) = attempt.notice(json("event" to "direct_step", "step" to name))
        try {
            attempt.notice(json("event" to "direct_graph", "camera" to case.camera, "cameraTargetCount" to if (twoOutputs) 2 else 1,
                "encoder" to "MediaRecorder/HEVC", "width" to case.width, "height" to case.height,
                "fps" to case.fps, "bitrate" to 60_000_000, "audio" to false,
                "sessionParameters" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P),
                "durationMs" to durationMs,
                "preview" to when {
                    useImageReaderPreview -> "1280x720 PRIVATE ImageReader"
                    useSurfaceTexturePreview -> "1280x720 live SurfaceTexture"
                    else -> null
                }))
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
            if (useImageReaderPreview) {
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
            } else if (useSurfaceTexturePreview) {
                val stReady = CompletableFuture<Surface>()
                requireNotNull(glHandler).post {
                    try {
                        val disp = GpuEglDisplayLease.acquire()
                        stEglDisplay = disp
                        stEglAcquired.set(true)
                        val cfg = choose8BitPbufferConfig(disp) ?: error("EGL 8-bit config unavailable")
                        val ctx = EGL14.eglCreateContext(
                            disp, cfg, EGL14.EGL_NO_CONTEXT,
                            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
                        )
                        check(ctx != EGL14.EGL_NO_CONTEXT) { "EGLContext unavailable" }
                        stEglContext = ctx
                        val pbuf = EGL14.eglCreatePbufferSurface(
                            disp, cfg, intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0,
                        )
                        check(pbuf != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(disp, pbuf, pbuf, ctx)) { "Pbuffer failed" }
                        stEglSurface = pbuf
                        val ids = IntArray(1)
                        GLES30.glGenTextures(1, ids, 0)
                        stTexId = ids[0]
                        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, stTexId)
                        val st = SurfaceTexture(stTexId).apply { setDefaultBufferSize(1280, 720) }
                        st.setOnFrameAvailableListener({ tex ->
                            if (!retiring.get()) {
                                runCatching {
                                    tex.updateTexImage()
                                    if (recording.get()) previewFrames.incrementAndGet()
                                }.onFailure { err -> fault.compareAndSet(null, "SurfaceTexture drain failed: ${err.message}") }
                            }
                        }, glHandler)
                        previewSurfaceTexture = st
                        val surf = Surface(st)
                        previewTextureSurface.set(surf)
                        stReady.complete(surf)
                    } catch (t: Throwable) {
                        stReady.completeExceptionally(t)
                    }
                }
                targets += stReady.get(6, TimeUnit.SECONDS)
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
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                targets.forEach { addTarget(it) }
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(case.fps, case.fps))
                if (caps.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.contains(CaptureRequest.EDGE_MODE_OFF) == true)
                    set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                if (caps.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.contains(CaptureRequest.NOISE_REDUCTION_MODE_OFF) == true)
                    set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            }.build()
            val sessionCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (retiring.get()) session.close() else configured.complete(session)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    configured.completeExceptionally(IllegalStateException("Regular session rejected ${targets.size} targets"))
                    session.close()
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val cfg = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    targets.map(::OutputConfiguration),
                    Executor { handler.post(it) },
                    sessionCallback,
                ).apply {
                    sessionParameters = request
                }
                camera.createCaptureSession(cfg)
            } else {
                camera.createCaptureSession(targets, sessionCallback, handler)
            }
            val session = configured.get(8, TimeUnit.SECONDS)
            activeSession = session
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
            val end = SystemClock.elapsedRealtime() + durationMs
            try {
                attempt.event(LabStage.RECORDER, json("event" to "recording", "edge" to request.get(CaptureRequest.EDGE_MODE),
                    "noise" to request.get(CaptureRequest.NOISE_REDUCTION_MODE), "durationMs" to durationMs))
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
                    runCatching { sink?.close() }, runCatching { previewTextureSurface.get()?.release() })
                if (glHandler != null && stEglAcquired.get()) {
                    val glDone = CompletableFuture<Unit>()
                    glHandler.post {
                        runCatching {
                            previewSurfaceTexture?.release()
                            if (stTexId != 0) GLES30.glDeleteTextures(1, intArrayOf(stTexId), 0)
                            EGL14.eglMakeCurrent(stEglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                            if (stEglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(stEglDisplay, stEglSurface)
                            if (stEglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(stEglDisplay, stEglContext)
                            GpuEglDisplayLease.release(stEglDisplay)
                        }
                        glDone.complete(Unit)
                    }
                    runCatching { glDone.get(4, TimeUnit.SECONDS) }
                }
                glThread?.quitSafely()
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
                glThread?.join(8_000)
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
        val sensorIssue = signals.sensorCadence(case.fps).let { c ->
            if (case.longer) c.sustainedDirectIssue(case.fps) else c.shortDirectIssue(case.fps)
        }
        val mismatch = when {
            fault.get() != null -> fault.get()
            results.get() == 0 -> "No Camera2 results during the recording."
            twoOutputs && previewFrames.get() == 0 -> "The second camera output delivered no observed images."
            failedCaptures.get() + lostBuffers.get() > 0 -> "Camera2 reported failed captures or lost output buffers."
            else -> sensorIssue?.let { "Camera2 sensor timing: $it" }
        }
        return if (file.first == LabVerdict.MISMATCH || mismatch == null) file else LabVerdict.MISMATCH to mismatch
    }

    private fun choose8BitPbufferConfig(display: EGLDisplay): EGLConfig? {
        val configs = arrayOfNulls<EGLConfig>(8)
        val count = IntArray(1)
        val ok = EGL14.eglChooseConfig(
            display,
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            ),
            0, configs, 0, configs.size, count, 0,
        )
        return if (ok && count[0] > 0) configs[0] else null
    }
}
