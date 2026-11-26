/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

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
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.media.ImageReader
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Range
import android.util.Size
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.CaptureOwnerAdmission
import com.librestatic.opencinecam.camera.OpenCineLogGpuPipeline
import com.librestatic.opencinecam.camera.OpenCineLogSourcePath
import com.librestatic.opencinecam.camera.PictureProfile
import com.librestatic.opencinecam.camera.PictureProfileCurves
import com.librestatic.opencinecam.camera.RecordingGeometryCalculator
import com.librestatic.opencinecam.camera.RecordingGeometryMode
import com.librestatic.opencinecam.camera.ResearchEncoderOverride
import com.librestatic.opencinecam.media.video.HevcEncoderLevels
import com.librestatic.opencinecam.media.video.VideoCodecPreference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs explicit research-only regular-session 4K30/4K60 OCLog2 and GPU HEVC paths directly over
 * Camera2 TEMPLATE_RECORD + OpenCineLogGpuPipeline(SDR_BT709_ISP), injecting learned encoder
 * level/EGL overrides without altering production Camera2 descriptors or shaders.
 */
internal class LabRegularLogRunner(
    private val context: Context,
    private val descriptor: Camera2CameraDescriptor,
    private val checkBudget: () -> Unit,
) {
    @SuppressLint("MissingPermission")
    fun run(
        case: LabCase,
        attempt: LabJournal.Attempt,
        overrideRecipe: ResearchEncoderOverride? = null,
    ): Pair<LabVerdict, String?> {
        val passthroughSdr = case.path == LabPath.GPU_HEVC_4K30 || case.path == LabPath.GPU_HEVC_4K60
        val effectiveOverride = overrideRecipe ?: defaultOverrideFor(case)
        val provenance = if (passthroughSdr) "POST_ISP_SDR_GPU" else "POST_ISP_SDR_LOG"

        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        try {
            lease.ready().get(8, TimeUnit.SECONDS)
            checkBudget()
        } catch (error: Exception) {
            lease.releaseAfter(CompletableFuture.completedFuture(Unit))
            throw error
        }

        val manager = context.getSystemService(CameraManager::class.java)
        val caps = manager.getCameraCharacteristics(case.camera)
        val thread = HandlerThread("occ-regular-log-lab").apply { start() }
        val handler = Handler(thread.looper)
        val previewSink = ImageReader.newInstance(640, 360, ImageFormat.PRIVATE, 3)
        val signals = LabSignals()
        val opened = CompletableFuture<CameraDevice>()
        val closed = CompletableFuture<Unit>()
        val configured = CompletableFuture<CameraCaptureSession>()
        val firstFrame = AtomicBoolean()
        val firstCameraResult = AtomicBoolean()
        val retiring = AtomicBoolean()
        val previewCount = AtomicInteger()
        val failedCaptures = AtomicInteger()
        val fault = AtomicReference<String?>()
        val observedToneMap = AtomicReference<Int?>()
        val observedEdge = AtomicReference<Int?>()
        val observedNr = AtomicReference<Int?>()

        previewSink.setOnImageAvailableListener({ reader ->
            runCatching {
                reader.acquireLatestImage()?.use {
                    previewCount.incrementAndGet()
                    firstFrame.compareAndSet(false, true)
                }
            }
        }, handler)

        var pipeline: OpenCineLogGpuPipeline? = null
        var activeSession: CameraCaptureSession? = null
        var openSubmitted = false
        var toneMapCurveApplied = false

        try {
            val size = Size(case.width, case.height)
            attempt.notice(json(
                "event" to "regular_log_config",
                "path" to case.path.name,
                "width" to case.width,
                "height" to case.height,
                "fps" to case.fps,
                "passthroughSdr" to passthroughSdr,
                "flatToneMapRequested" to case.flatToneMap,
                "explicitHevcLevel" to effectiveOverride.explicitHevcLevel,
                "hevcLevelName" to HevcEncoderLevels.hevcLevelName(effectiveOverride.explicitHevcLevel),
                "forceEgl8BitForMain10" to effectiveOverride.forceEgl8BitForMain10,
                "omitEglBt2020ColorSpace" to effectiveOverride.omitEglBt2020ColorSpace,
                "allowAvcFallback" to effectiveOverride.allowAvcFallback,
                "sourceProvenance" to provenance,
            ))

            val gpu = OpenCineLogGpuPipeline(
                size = size,
                sourcePath = OpenCineLogSourcePath.SDR_BT709_ISP,
                preview = previewSink.surface,
                sensorOrientationDegrees = descriptor.sensorOrientation,
                displayRotationDegrees = 0,
                frontFacing = descriptor.lensFacing == CameraCharacteristics.LENS_FACING_FRONT,
                viewAssist = !passthroughSdr,
                targetFps = case.fps,
                passthroughSdr = passthroughSdr,
                appContext = context.applicationContext,
                researchEncoderOverride = effectiveOverride,
            ) { code, msg ->
                fault.compareAndSet(null, "$code: $msg")
            }
            gpu.attachResearchObserver(signals)
            pipeline = gpu
            val cameraSurface = gpu.cameraInputSurface

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

            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(cameraSurface)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(case.fps, case.fps))
                if (case.flatToneMap) {
                    val modes = caps.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.toSet().orEmpty()
                    val maxPts = caps.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0
                    if (CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE in modes && maxPts >= PictureProfileCurves.MIN_POINT_COUNT) {
                        val pts = PictureProfileCurves.points(PictureProfile.FLAT, minOf(PictureProfileCurves.DEFAULT_POINT_COUNT, maxPts))
                        if (pts != null) {
                            set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
                            set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(pts, pts, pts))
                            toneMapCurveApplied = true
                        }
                    }
                    if (caps.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.contains(CaptureRequest.EDGE_MODE_OFF) == true) {
                        set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                    }
                    if (caps.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.contains(CaptureRequest.NOISE_REDUCTION_MODE_OFF) == true) {
                        set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                    }
                }
            }
            val initialParams = requestBuilder.build()

            val sessionCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (retiring.get()) session.close() else configured.complete(session)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    configured.completeExceptionally(IllegalStateException("Camera session rejected GPU input surface."))
                    session.close()
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val cfg = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(cameraSurface)),
                    Executor { handler.post(it) },
                    sessionCallback,
                ).apply {
                    sessionParameters = initialParams
                }
                camera.createCaptureSession(cfg)
            } else {
                @Suppress("DEPRECATION")
                camera.createCaptureSession(listOf(cameraSurface), sessionCallback, handler)
            }

            val session = configured.get(8, TimeUnit.SECONDS)
            activeSession = session
            session.setRepeatingRequest(initialParams, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    if (retiring.get()) return
                    firstCameraResult.compareAndSet(false, true)
                    observedToneMap.compareAndSet(null, result.get(CaptureResult.TONEMAP_MODE))
                    observedEdge.compareAndSet(null, result.get(CaptureResult.EDGE_MODE))
                    observedNr.compareAndSet(null, result.get(CaptureResult.NOISE_REDUCTION_MODE))
                    signals.onResult(request, result, result.get(CaptureResult.SENSOR_TIMESTAMP))
                }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    if (!retiring.get()) failedCaptures.incrementAndGet()
                }
            }, handler)

            awaitCondition(8_000, "preview and first Camera2 result") {
                signals.drain(attempt)
                fault.get()?.let { error(it) }
                checkBudget()
                firstFrame.get() && firstCameraResult.get()
            }

            attempt.notice(json(
                "event" to "isp_controls_observed",
                "flatToneMapRequested" to case.flatToneMap,
                "toneMapCurveApplied" to toneMapCurveApplied,
                "observedToneMapMode" to observedToneMap.get(),
                "observedEdgeMode" to observedEdge.get(),
                "observedNoiseReductionMode" to observedNr.get(),
                "previewFrames" to previewCount.get(),
            ))

            if (case.previewOnly) {
                awaitDuration(2_000) {
                    signals.drain(attempt)
                    fault.get()?.let { error(it) }
                    checkBudget()
                }
                attempt.notice(signals.snapshot())
                return LabVerdict.OBSERVED to "Regular-session GPU preview observed without encoder."
            }

            val geometry = RecordingGeometryCalculator.calculate(
                sourceSize = size,
                sensorOrientationDegrees = descriptor.sensorOrientation,
                deviceOrientationDegrees = 0,
                lensFacing = descriptor.lensFacing,
                mode = RecordingGeometryMode.NATIVE_RASTER,
            )
            val bitrate = if (case.fps >= 60) 60_000_000 else 40_000_000
            val pfdMode = ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE
            val stoppedFuture = CompletableFuture<Boolean>()
            val evidenceRef = AtomicReference<com.librestatic.opencinecam.camera.OpenCineLogRecordingEvidence?>()
            val started = AtomicBoolean()

            ParcelFileDescriptor.open(attempt.clip, pfdMode).use { output ->
                val accepted = gpu.startRecording(
                    output = output,
                    bitrate = bitrate,
                    geometry = geometry,
                    onStarted = { started.set(true) },
                    onStopped = { ok, ev ->
                        evidenceRef.set(ev)
                        stoppedFuture.complete(ok)
                    },
                    videoCodec = VideoCodecPreference.HEVC,
                    sampleListener = signals,
                )
                signals.drain(attempt)
                check(accepted) { fault.get() ?: "GPU pipeline refused recording start." }
                awaitCondition(8_000, "encoder start") {
                    signals.drain(attempt)
                    fault.get()?.let { error(it) }
                    checkBudget()
                    started.get()
                }
                val recordMs = if (case.longer) 10_000L else 4_000L
                try {
                    awaitDuration(recordMs) {
                        signals.drain(attempt)
                        fault.get()?.let { error(it) }
                        checkBudget()
                    }
                } finally {
                    gpu.stopRecording()
                }
                val stopOk = stoppedFuture.get(8, TimeUnit.SECONDS)
                gpu.recordingFileRetirement().get(8, TimeUnit.SECONDS)
                check(stopOk) { fault.get() ?: "Regular LOG recording finalized with failure." }
            }

            evidenceRef.get()?.let { ev ->
                attempt.notice(json(
                    "event" to "production_gpu_evidence",
                    "sourcePath" to ev.sourcePath.name,
                    "sourceSurface" to ev.sourceSurface,
                    "sourceDataSpace" to ev.sourceDataSpace,
                    "sourceDataSpaceMismatchedFrames" to ev.sourceDataSpaceMismatchedFrames,
                    "eglRenderTargetBits" to ev.eglRenderTargetBits,
                    "codecName" to ev.codecName,
                    "codecMime" to ev.codecMime,
                    "codecProfile" to ev.codecProfile,
                    "encoderVuiTransfer" to ev.encoderVuiTransfer,
                    "containerVuiTransfer" to ev.containerVuiTransfer,
                    "codecFallback" to ev.codecFallback,
                    "encodedFrames" to ev.encodedFrames,
                    "maxVideoPtsGapUs" to ev.maxVideoPtsGapUs,
                    "sourceProvenance" to provenance,
                ))
            }
        } finally {
            retiring.set(true)
            val cameraClosed = closed.thenApply { Unit }
            lease.releaseAfter(cameraClosed)
            if (openSubmitted) {
                handler.post {
                    try { activeSession?.close() } finally {
                        if (!opened.isCompletedExceptionally) opened.getNow(null)?.close()
                    }
                }
            } else {
                closed.complete(Unit)
            }
            val closeErrors = mutableListOf<Throwable>()
            runCatching { pipeline?.closeAsync()?.get(8, TimeUnit.SECONDS) }.onFailure { closeErrors += it }
            runCatching { cameraClosed.get(8, TimeUnit.SECONDS) }.onFailure { closeErrors += it }
            runCatching { previewSink.close() }
            thread.quitSafely()
            runCatching { thread.join(8_000) }
            signals.drain(attempt)
            attempt.notice(signals.snapshot().put("failedCaptures", failedCaptures.get()).put("retirement", "acknowledged"))
            closeErrors.firstOrNull()?.let { throw LabRetirementFailure(it as? Exception ?: Exception(it)) }
        }
        return inspectClip(case, attempt)
    }

    private fun defaultOverrideFor(case: LabCase): ResearchEncoderOverride {
        val hevcCaps = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { it.isEncoder && !it.isAlias && it.isHardwareAccelerated }
            .firstNotNullOfOrNull { info ->
                val type = info.supportedTypes.firstOrNull { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) }
                    ?: return@firstNotNullOfOrNull null
                val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull() ?: return@firstNotNullOfOrNull null
                if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats) return@firstNotNullOfOrNull null
                caps.profileLevels.map { it.level }
            }.orEmpty()
        val level = HevcEncoderLevels.selectHevcLevel(case.width, case.height, case.fps, hevcCaps)
            ?: HevcEncoderLevels.minimumMainTierLevel(case.width, case.height, case.fps)
        return ResearchEncoderOverride(
            explicitHevcLevel = level,
            allowAvcFallback = false,
        )
    }

    private fun awaitCondition(timeoutMs: Long, phase: String, ready: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (ready()) return
            if (SystemClock.elapsedRealtime() >= deadline) error("Timeout at $phase")
            Thread.sleep(80)
        }
    }

    private fun awaitDuration(durationMs: Long, tick: () -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (SystemClock.elapsedRealtime() < deadline) {
            tick()
            Thread.sleep(80)
        }
    }
}
