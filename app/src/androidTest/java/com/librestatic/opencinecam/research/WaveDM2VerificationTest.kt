/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import android.Manifest
import android.content.Context
import android.graphics.ImageFormat
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.opencinecam.camera.Camera2Analysis
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.Camera2EmbeddedAudioConfig
import com.librestatic.opencinecam.camera.Camera2LogProfile
import com.librestatic.opencinecam.camera.Camera2PreviewEngine
import com.librestatic.opencinecam.camera.Camera2PreviewListener
import com.librestatic.opencinecam.camera.Camera2PreviewMetadata
import com.librestatic.opencinecam.camera.CaptureEpochReport
import com.librestatic.opencinecam.camera.CapturedStill
import com.librestatic.opencinecam.camera.ExposureMode
import com.librestatic.opencinecam.camera.ExposureSelection
import com.librestatic.opencinecam.camera.ImageProcessingSelection
import com.librestatic.opencinecam.camera.MonitoringOptions
import com.librestatic.opencinecam.camera.OpenCineLogRecordingEvidence
import com.librestatic.opencinecam.camera.OpenCineLogSourcePath
import com.librestatic.opencinecam.camera.RecordingGeometryCalculator
import com.librestatic.opencinecam.camera.RecordingGeometryMode
import com.librestatic.opencinecam.camera.WhiteBalanceSelection
import com.librestatic.opencinecam.camera.descriptor
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WaveDM2VerificationTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun outDir(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "research_lab/wave_d_m2").apply { mkdirs() }

    @Before
    fun grantPermissions() {
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        inst.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
    }

    private fun waitForThermalReady(outDir: File) {
        LabWaveCProbes.waitNaturalThermalCool(context, outDir)
    }

    private fun defaultAudioConfig(): Camera2EmbeddedAudioConfig = Camera2EmbeddedAudioConfig(
        source = MediaRecorder.AudioSource.CAMCORDER,
        sampleRateHz = 48_000,
        channels = 2,
        bitrateBps = 320_000,
        preferredInputDeviceId = null,
    )

    private data class ProductionTakeResult(
        val tag: String,
        val file: File,
        val previewFramesBeforeTake: Int,
        val previewFramesDuringTake: Int,
        val analysisCallbacks: Int,
        val recordingStarted: Boolean,
        val recordingStoppedSuccess: Boolean,
        val evidence: OpenCineLogRecordingEvidence?,
        val avTiming: CaptureEpochReport?,
        val failures: List<String>,
        val lastIso: Int? = null,
        val lastExposureNs: Long? = null,
        val lastExposureMode: String? = null,
        val lastEffectiveFps: Double? = null,
        val rejectedControls: List<String> = emptyList(),
        val adaptedControls: List<String> = emptyList(),
        val pboEnabled: Boolean? = null,
        val pboFramesQueued: Long? = null,
        val pboAsyncReads: Long? = null,
        val pboSyncFallbacks: Long? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("tag", tag)
            put("file", file.name)
            put("fileBytes", if (file.exists()) file.length() else 0L)
            put("previewFramesBeforeTake", previewFramesBeforeTake)
            put("previewFramesDuringTake", previewFramesDuringTake)
            put("analysisCallbacks", analysisCallbacks)
            put("recordingStarted", recordingStarted)
            put("recordingStoppedSuccess", recordingStoppedSuccess)
            put("failures", JSONArray(failures))
            lastIso?.let { put("lastIso", it) }
            lastExposureNs?.let { put("lastExposureNs", it) }
            lastExposureMode?.let { put("lastExposureMode", it) }
            lastEffectiveFps?.let { put("lastEffectiveFps", it) }
            if (rejectedControls.isNotEmpty()) put("rejectedControls", JSONArray(rejectedControls))
            if (adaptedControls.isNotEmpty()) put("adaptedControls", JSONArray(adaptedControls))
            evidence?.let { ev ->
                put("sourceSurface", ev.sourceSurface)
                put("sourcePath", ev.sourcePath.name)
                put("curve", ev.curve)
                put("codecName", ev.codecName)
                put("codecProfile", ev.codecProfile)
                put("encodedWidth", ev.encodedWidth)
                put("encodedHeight", ev.encodedHeight)
                put("targetFps", ev.targetFps)
                put("encodedFrames", ev.encodedFrames)
                put("firstPtsUs", ev.firstPtsUs)
                put("lastPtsUs", ev.lastPtsUs)
                put("maxVideoPtsGapUs", ev.maxVideoPtsGapUs)
                put("videoPtsGapsOverThreshold", ev.videoPtsGapsOverThreshold)
                put("eglRenderTargetBits", ev.eglRenderTargetBits)
            }
            avTiming?.let { av ->
                put("avPolicy", av.policy)
                put("videoFrameZeroNs", av.videoFrameZeroNs)
                put("audioFrameZeroNs", av.audioFrameZeroNs)
                put("audioTimestampBacked", av.audioTimestampBacked)
                put("audioPresentationOffsetUs", av.audioPresentationOffsetUs)
                put("videoMuxOffsetUs", av.videoMuxOffsetUs)
                put("submittedPcmFrames", av.submittedPcmFrames)
                put("encodedAudioPackets", av.encodedAudioPackets)
            }
            if (pboEnabled != null) {
                put("pboEnabled", pboEnabled)
                put("pboFramesQueued", pboFramesQueued)
                put("pboAsyncReads", pboAsyncReads)
                put("pboSyncFallbacks", pboSyncFallbacks)
            }
        }
    }

    private fun runProductionTake(
        tag: String,
        cameraId: String,
        fps: Int,
        durationMs: Long,
        bitrateMbps: Int,
        hlgOutput: Boolean,
        withAudio: Boolean,
        enableScopes: Boolean = false,
        exposure: ExposureSelection = ExposureSelection(),
        whiteBalance: WhiteBalanceSelection = WhiteBalanceSelection.Auto,
        processing: ImageProcessingSelection = ImageProcessingSelection(),
        outDir: File,
        midTakeAction: ((Camera2PreviewEngine) -> Unit)? = null,
    ): ProductionTakeResult {
        val outFile = File(outDir, "$tag.mp4").apply { delete() }
        val texThread = HandlerThread("M2PreviewTex_$tag").apply { start() }
        val texHandler = Handler(texThread.looper)
        val previewFramesBefore = AtomicInteger(0)
        val previewFramesAtStart = java.util.concurrent.atomic.AtomicLong(0L)
        val previewFramesDuring = AtomicInteger(0)
        val analysisCount = AtomicInteger(0)
        val previewLatch = CountDownLatch(1)
        val startedLatch = CountDownLatch(1)
        val stoppedLatch = CountDownLatch(1)
        val stoppedOk = AtomicReference(false)
        val failures = mutableListOf<String>()
        val rejectedControls = mutableListOf<String>()
        val adaptedControls = mutableListOf<String>()
        val lastIso = AtomicReference<Int?>(null)
        val lastExposureNs = AtomicReference<Long?>(null)
        val lastExposureMode = AtomicReference<String?>(null)
        val lastEffectiveFps = AtomicReference<Double?>(null)

        val previewSink = ImageReader.newInstance(1280, 720, ImageFormat.PRIVATE, 4).apply {
            setOnImageAvailableListener({ reader ->
                runCatching { reader.acquireLatestImage()?.close() }
            }, texHandler)
        }
        val surface = previewSink.surface
        val engine = Camera2PreviewEngine(context)
        try {
            if (enableScopes) {
                engine.setMonitoringOptions(
                    MonitoringOptions(
                        waveformEnabled = true,
                        vectorscopeEnabled = true,
                        falseColorEnabled = true,
                        refreshHz = 4,
                    ),
                )
            }
            val desc: Camera2CameraDescriptor = engine.descriptor(cameraId, 3840, 2160)
                ?: error("Camera $cameraId descriptor missing")
            val profile: Camera2LogProfile = desc.logProfiles.firstOrNull {
                !it.constrainedHighSpeed && it.size.width == 3840 && it.size.height == 2160 && it.fps == fps &&
                    it.sourcePath == OpenCineLogSourcePath.HLG10_BT2020
            } ?: error("No 4K$fps HLG10 logProfile on cam $cameraId: ${desc.logProfiles}")

            val listener = object : Camera2PreviewListener {
                override fun onOpening(descriptor: Camera2CameraDescriptor) = Unit
                override fun onPreviewStarted(descriptor: Camera2CameraDescriptor) { previewLatch.countDown() }
                override fun onMetadata(metadata: Camera2PreviewMetadata) {
                    metadata.sensitivityIso?.let { lastIso.set(it) }
                    metadata.exposureTimeNs?.let { lastExposureNs.set(it) }
                    metadata.exposureMode?.let { lastExposureMode.set(it.name) }
                    metadata.effectiveFps?.let { lastEffectiveFps.set(it) }
                }
                override fun onJpegCaptured(bytes: ByteArray, width: Int, height: Int) = Unit
                override fun onDngCaptured(bytes: ByteArray, width: Int, height: Int) = Unit
                override fun onStillCaptured(capture: CapturedStill) = Unit
                override fun onAnalysis(analysis: Camera2Analysis) { analysisCount.incrementAndGet() }
                override fun onRecordingStarted(width: Int, height: Int) {
                    val nowCount = engine.renderedPreviewFrameCount()
                    previewFramesBefore.set(nowCount.toInt())
                    previewFramesAtStart.set(nowCount)
                    startedLatch.countDown()
                }
                override fun onRecordingStopped(success: Boolean) {
                    val delta = (engine.renderedPreviewFrameCount() - previewFramesAtStart.get()).coerceAtLeast(0L)
                    previewFramesDuring.set(delta.toInt())
                    stoppedOk.set(success)
                    stoppedLatch.countDown()
                }
                override fun onControlRejected(code: String, message: String) {
                    rejectedControls += "$code:$message"
                }
                override fun onControlAdapted(code: String, message: String) {
                    adaptedControls += "$code:$message"
                }
                override fun onProfessionalControlsRejected(message: String) {
                    rejectedControls += "pro:$message"
                }
                override fun onFailure(code: String, message: String, recoverable: Boolean) {
                    failures += "$code:$message"
                }
            }

            engine.startPreview(
                descriptor = desc,
                surface = surface,
                displayRotationDegrees = 0,
                listener = listener,
                openCineLog = true,
                viewAssist = false,
                targetFps = fps,
                logProfile = profile,
            )
            assertTrue("Preview failed to start for $tag ($failures)", previewLatch.await(8, TimeUnit.SECONDS))
            engine.setProfessionalControls(exposure, whiteBalance, processing)
            Thread.sleep(700L)

            val geometry = RecordingGeometryCalculator.calculate(
                sourceSize = profile.size,
                sensorOrientationDegrees = desc.sensorOrientation,
                deviceOrientationDegrees = 0,
                lensFacing = desc.lensFacing,
                mode = RecordingGeometryMode.COMPATIBLE,
            )
            val pfd = ParcelFileDescriptor.open(
                outFile,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE,
            )
            val accepted = pfd.use {
                engine.startOpenCineLogVideo(
                    output = it,
                    recordingGeometry = geometry,
                    videoBitrate = bitrateMbps * 1_000_000,
                    audio = if (withAudio) defaultAudioConfig() else null,
                    hlgOutput = hlgOutput,
                )
            }
            assertTrue("startOpenCineLogVideo rejected for $tag ($failures)", accepted)
            assertTrue("Recording failed to start for $tag ($failures)", startedLatch.await(8, TimeUnit.SECONDS))
            if (midTakeAction != null && durationMs > 450L) {
                Thread.sleep(450L)
                midTakeAction.invoke(engine)
                Thread.sleep(durationMs - 450L)
            } else {
                Thread.sleep(durationMs)
            }
            assertTrue("stopVideo failed for $tag", engine.stopVideo())
            assertTrue("Recording failed to stop for $tag ($failures)", stoppedLatch.await(12, TimeUnit.SECONDS))

            val evidence = engine.consumeLastOpenCineLogEvidence()
            val av = engine.consumeCaptureEpochReport() ?: evidence?.avTiming
            val pbo = engine.gpuScopePboStatsSnapshot()
            Thread.sleep(250L)
            return ProductionTakeResult(
                tag = tag,
                file = outFile,
                previewFramesBeforeTake = previewFramesBefore.get(),
                previewFramesDuringTake = previewFramesDuring.get(),
                analysisCallbacks = analysisCount.get(),
                recordingStarted = startedLatch.count == 0L,
                recordingStoppedSuccess = stoppedOk.get(),
                evidence = evidence,
                avTiming = av,
                failures = failures,
                lastIso = lastIso.get(),
                lastExposureNs = lastExposureNs.get(),
                lastExposureMode = lastExposureMode.get(),
                lastEffectiveFps = lastEffectiveFps.get(),
                rejectedControls = rejectedControls,
                adaptedControls = adaptedControls,
                pboEnabled = pbo?.enabled,
                pboFramesQueued = pbo?.framesQueued,
                pboAsyncReads = pbo?.asyncReadsCompleted,
                pboSyncFallbacks = pbo?.syncFallbacks,
            )
        } finally {
            runCatching { engine.close() }
            runCatching { previewSink.close() }
            runCatching { texThread.quitSafely() }
        }
    }

    @Test
    fun test01_Direct10BitDualSurfacePreviewCheck() {
        val dir = outDir()
        LabDiagnosticRecorder(dir).use { diag ->
            diag.start()
            waitForThermalReady(dir)
            val r30 = runProductionTake(
                tag = "m2_dual_4k30",
                cameraId = "0",
                fps = 30,
                durationMs = 3_000L,
                bitrateMbps = 40,
                hlgOutput = true,
                withAudio = false,
                outDir = dir,
            )
            Thread.sleep(500L)
            val r60 = runProductionTake(
                tag = "m2_dual_4k60",
                cameraId = "0",
                fps = 60,
                durationMs = 3_000L,
                bitrateMbps = 80,
                hlgOutput = true,
                withAudio = false,
                outDir = dir,
            )
            val report = JSONObject().apply {
                put("test", "test01_Direct10BitDualSurfacePreviewCheck")
                put("arms", JSONArray().apply { put(r30.toJson()); put(r60.toJson()) })
            }
            File(dir, "m2_test01_dual_surface.json").writeText(report.toString(2))
            assertTrue("4K30 dual-surface failed: ${r30.toJson()}", r30.recordingStoppedSuccess && (r30.evidence?.encodedFrames ?: 0L) > 60L)
            assertTrue("4K30 preview stalled during direct take: ${r30.previewFramesDuringTake}", r30.previewFramesDuringTake > 15)
            assertTrue("4K60 dual-surface failed: ${r60.toJson()}", r60.recordingStoppedSuccess && (r60.evidence?.encodedFrames ?: 0L) > 120L)
            assertTrue("4K60 preview stalled during direct take: ${r60.previewFramesDuringTake}", r60.previewFramesDuringTake > 20)
            assertTrue("Expected DIRECT_SURFACE_0x324 on 4K60, got ${r60.evidence?.sourceSurface}",
                r60.evidence?.sourceSurface == "DIRECT_SURFACE_0x324")
        }
    }

    @Test
    fun test02_ProductionDirect10BitHlg_4k30_4k60_WithAudioAndSmoothAndAperture() {
        val dir = outDir()
        LabDiagnosticRecorder(dir).use { diag ->
            diag.start()
            waitForThermalReady(dir)
            val results = mutableListOf<ProductionTakeResult>()
            results += runProductionTake("m2_prod_cam0_4k30_hlg_aac", "0", 30, 4_000L, 40, hlgOutput = true, withAudio = true, outDir = dir)
            Thread.sleep(400L)
            results += runProductionTake("m2_prod_cam0_4k60_hlg_aac", "0", 60, 5_000L, 80, hlgOutput = true, withAudio = true, outDir = dir)
            waitForThermalReady(dir)
            results += runProductionTake("m2_prod_cam4_4k60_hlg_aac", "4", 60, 4_000L, 80, hlgOutput = true, withAudio = true, outDir = dir)
            Thread.sleep(400L)
            results += runProductionTake("m2_prod_cam5_4k60_hlg_aac", "5", 60, 4_000L, 80, hlgOutput = true, withAudio = true, outDir = dir)
            waitForThermalReady(dir)

            val fixedManual = ExposureSelection(mode = ExposureMode.MANUAL, iso = 400, timeNs = 16_666_667L)
            val fixedWb = WhiteBalanceSelection.Kelvin(5600)
            results += runProductionTake("m2_prod_ap_f16", "0", 30, 2_500L, 60, hlgOutput = true, withAudio = false,
                exposure = fixedManual.copy(apertureFNumber = 1.6f), whiteBalance = fixedWb, outDir = dir)
            results += runProductionTake("m2_prod_ap_f40", "0", 30, 2_500L, 60, hlgOutput = true, withAudio = false,
                exposure = fixedManual.copy(apertureFNumber = 4.0f), whiteBalance = fixedWb, outDir = dir)
            results += runProductionTake("m2_prod_ap_auto", "0", 30, 2_500L, 60, hlgOutput = true, withAudio = false,
                exposure = fixedManual.copy(apertureFNumber = null), whiteBalance = fixedWb, outDir = dir)

            results += runProductionTake("m2_prod_smooth_off", "0", 30, 2_500L, 60, hlgOutput = true, withAudio = false,
                exposure = fixedManual, whiteBalance = fixedWb, processing = ImageProcessingSelection(huaweiSmoothColor = false), outDir = dir)
            results += runProductionTake("m2_prod_smooth_on", "0", 30, 2_500L, 60, hlgOutput = true, withAudio = false,
                exposure = fixedManual, whiteBalance = fixedWb, processing = ImageProcessingSelection(huaweiSmoothColor = true), outDir = dir)

            val report = JSONObject().apply {
                put("test", "test02_ProductionDirect10BitHlg_4k30_4k60_WithAudioAndSmoothAndAperture")
                put("arms", JSONArray().apply { results.forEach { put(it.toJson()) } })
            }
            File(dir, "m2_test02_direct_hlg_matrix.json").writeText(report.toString(2))

            results.forEach { r ->
                assertTrue("Take ${r.tag} failed: ${r.toJson()}", r.recordingStoppedSuccess && r.evidence != null)
                assertTrue("Take ${r.tag} expected DIRECT_SURFACE_0x324: ${r.evidence?.sourceSurface}",
                    r.evidence?.sourceSurface == "DIRECT_SURFACE_0x324")
            }
            results.take(4).forEach { r ->
                assertNotNull("Expected avTiming on ${r.tag}", r.avTiming)
                assertTrue("Expected encodedAudioPackets > 0 on ${r.tag}", (r.avTiming?.encodedAudioPackets ?: 0L) > 0L)
            }
        }
    }

    @Test
    fun test03_ProductionGpuOcLog2_4k30_4k60_WithAudioAndPbo() {
        val dir = outDir()
        LabDiagnosticRecorder(dir).use { diag ->
            diag.start()
            waitForThermalReady(dir)
            val r30 = runProductionTake(
                tag = "m2_prod_cam0_4k30_oclog2_aac",
                cameraId = "0",
                fps = 30,
                durationMs = 4_000L,
                bitrateMbps = 40,
                hlgOutput = false,
                withAudio = true,
                enableScopes = true,
                outDir = dir,
            )
            waitForThermalReady(dir)
            val r60 = runProductionTake(
                tag = "m2_prod_cam0_4k60_oclog2_aac",
                cameraId = "0",
                fps = 60,
                durationMs = 5_000L,
                bitrateMbps = 60,
                hlgOutput = false,
                withAudio = true,
                enableScopes = true,
                outDir = dir,
            )
            val report = JSONObject().apply {
                put("test", "test03_ProductionGpuOcLog2_4k30_4k60_WithAudioAndPbo")
                put("arms", JSONArray().apply { put(r30.toJson()); put(r60.toJson()) })
            }
            File(dir, "m2_test03_gpu_oclog2.json").writeText(report.toString(2))

            assertTrue("OCLog2 4K30 failed: ${r30.toJson()}", r30.recordingStoppedSuccess && (r30.evidence?.encodedFrames ?: 0L) > 80L)
            assertTrue("OCLog2 4K60 failed: ${r60.toJson()}", r60.recordingStoppedSuccess && (r60.evidence?.encodedFrames ?: 0L) > 150L)
            assertTrue("Expected AAC packets on 4K30 OCLog2", (r30.avTiming?.encodedAudioPackets ?: 0L) > 0L)
            assertTrue("Expected AAC packets on 4K60 OCLog2", (r60.avTiming?.encodedAudioPackets ?: 0L) > 0L)
            assertTrue("Expected Async PBO active on 4K60 OCLog2: ${r60.toJson()}", r60.pboEnabled == true)
        }
    }

    @Test
    fun test04_Direct10BitApertureIsolation() {
        val dir = outDir()
        LabDiagnosticRecorder(dir).use { diag ->
            diag.start()
            waitForThermalReady(dir)
            val fixedManual = ExposureSelection(mode = ExposureMode.MANUAL, iso = 400, timeNs = 16_666_667L)
            val fixedWb = WhiteBalanceSelection.Kelvin(5600)
            val arms = mutableListOf<ProductionTakeResult>()
            arms += runProductionTake(
                tag = "m2_diag_ap_f40_gpu_single",
                cameraId = "0",
                fps = 30,
                durationMs = 2_000L,
                bitrateMbps = 40,
                hlgOutput = false,
                withAudio = false,
                exposure = fixedManual.copy(apertureFNumber = 4.0f),
                whiteBalance = fixedWb,
                outDir = dir,
            )
            arms += runProductionTake(
                tag = "m2_diag_ap_f40_delayed_reassert",
                cameraId = "0",
                fps = 30,
                durationMs = 2_050L,
                bitrateMbps = 60,
                hlgOutput = true,
                withAudio = false,
                exposure = fixedManual.copy(apertureFNumber = null),
                whiteBalance = fixedWb,
                outDir = dir,
                midTakeAction = { eng ->
                    eng.setProfessionalControls(fixedManual.copy(apertureFNumber = 4.0f), fixedWb)
                },
            )
            arms += runProductionTake(
                tag = "m2_diag_ap_f40_toggle_reassert",
                cameraId = "0",
                fps = 30,
                durationMs = 2_070L,
                bitrateMbps = 60,
                hlgOutput = true,
                withAudio = false,
                exposure = fixedManual.copy(apertureFNumber = 4.0f),
                whiteBalance = fixedWb,
                outDir = dir,
                midTakeAction = { eng ->
                    eng.setProfessionalControls(fixedManual.copy(apertureFNumber = null), fixedWb)
                    Thread.sleep(120L)
                    eng.setProfessionalControls(fixedManual.copy(apertureFNumber = 4.0f), fixedWb)
                },
            )
            val report = JSONObject().apply {
                put("test", "test04_Direct10BitApertureIsolation")
                put("arms", JSONArray().apply { arms.forEach { put(it.toJson()) } })
            }
            File(dir, "m2_test04_aperture_diag.json").writeText(report.toString(2))
            arms.forEach { r ->
                assertTrue("Diag arm ${r.tag} failed: ${r.toJson()}", r.recordingStoppedSuccess && r.evidence != null)
            }
        }
    }
}
