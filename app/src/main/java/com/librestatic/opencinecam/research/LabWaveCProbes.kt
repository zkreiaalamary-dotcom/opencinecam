/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.Range
import android.util.Size
import android.view.Surface
import com.librestatic.opencinecam.camera.Camera2Analysis
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.Camera2LogProfile
import com.librestatic.opencinecam.camera.Camera2PreviewEngine
import com.librestatic.opencinecam.camera.Camera2PreviewListener
import com.librestatic.opencinecam.camera.Camera2PreviewMetadata
import com.librestatic.opencinecam.camera.CameraHealthEvent
import com.librestatic.opencinecam.camera.CameraHealthIssue
import com.librestatic.opencinecam.camera.CameraHealthWatchdog
import com.librestatic.opencinecam.camera.CaptureOwnerAdmission
import com.librestatic.opencinecam.camera.HuaweiVendorControls
import com.librestatic.opencinecam.camera.LensLockBinding
import com.librestatic.opencinecam.camera.OpenCineLogGpuPipeline
import com.librestatic.opencinecam.camera.OpenCineLogRecordingEvidence
import com.librestatic.opencinecam.camera.OpenCineLogSourcePath
import com.librestatic.opencinecam.camera.RecordingGeometryCalculator
import com.librestatic.opencinecam.camera.RecordingGeometryMode
import com.librestatic.opencinecam.camera.descriptor
import com.librestatic.opencinecam.camera.imageReaderTextureMatrix
import com.librestatic.opencinecam.camera.unmirrorSurfaceTextureMatrix
import com.librestatic.opencinecam.diagnostics.DeviceQuirksProvider
import com.librestatic.opencinecam.media.video.EglHardwareBufferBridge
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wave C Milestone 1 Truth Probes:
 * - Probe 16A: Exact orientation, SurfaceTexture transform matrix audit, and ImageReader comparison
 *   across cameras 0, 1, 2, 4, 5 and device orientations 0, 90, 180, 270.
 * - Probe 16B: Ground-truth OMX.hisi HEVC Main10 RGB->YUV matrix/range calibration + live CUVA 0x324
 *   vs SDR vs RAW_SENSOR comparison under locked exposure and manual 5600K WB.
 */
internal object LabWaveCProbes {
    private const val HUAWEI_CUVA_VIDEO_MODE_KEY = "com.huawei.capture.metadata.cuvaStateVideoMode"

    internal fun <T> withWatchdogAndLogs(
        context: Context,
        outDir: File,
        block: (LabDiagnosticRecorder, CameraHealthWatchdog, () -> JSONObject) -> T,
    ): T {
        outDir.mkdirs()
        val root = ResearchLaboratory.evidenceRoot(context)
        val rateTracker = LabThermalRateTracker()
        val recorder = LabDiagnosticRecorder(outDir).apply { start() }
        val wdExec = Executors.newSingleThreadExecutor { r -> Thread(r, "WaveCWd").apply { isDaemon = true } }
        val wdThread = HandlerThread("WaveCWdHandler").apply { start() }
        val manager = context.getSystemService(CameraManager::class.java)
        val aborted = AtomicReference<CameraHealthEvent?>(null)
        val watchdog = CameraHealthWatchdog(manager, wdExec, Handler(wdThread.looper)) { ev ->
            recorder.noteHealthEvent(ev)
            if (ev.issue == CameraHealthIssue.DAEMON_BOUNCE ||
                ev.issue == CameraHealthIssue.SERVICE_ERROR ||
                ev.issue == CameraHealthIssue.DEVICE_ERROR ||
                ev.issue == CameraHealthIssue.OPEN_TIMEOUT
            ) {
                aborted.compareAndSet(null, ev)
            }
        }
        fun sampleNow(): JSONObject = sampleThermal(context, root, includePowerProfile = false, rateTracker = rateTracker).toJson()
        return try {
            block(recorder, watchdog, ::sampleNow).also {
                aborted.get()?.let { ev ->
                    recorder.snapshotAnomaly("WaveC probe health event: ${ev.issue} ${ev.detail}", outDir, null)
                }
            }
        } catch (t: Throwable) {
            val s = runCatching { sampleThermal(context, root, includePowerProfile = false, rateTracker = rateTracker) }.getOrNull()
            recorder.snapshotAnomaly("WaveC probe exception: ${t.javaClass.simpleName}: ${t.message}", outDir, s)
            throw t
        } finally {
            runCatching { watchdog.close() }
            runCatching { wdThread.quitSafely(); wdThread.join(1_000L) }
            runCatching { wdExec.shutdownNow() }
            runCatching { recorder.close() }
        }
    }

    internal fun waitNaturalThermalCool(context: Context, outDir: File, maxShellC: Double = 36.5) {
        val root = ResearchLaboratory.evidenceRoot(context)
        for (i in 0 until 20) {
            val s = sampleThermal(context, root, includePowerProfile = false)
            val shell = s.shellFrameC ?: (s.batteryTempTenthsC / 10.0)
            if (shell < maxShellC && s.thermalStatus < 2) return
            if (i == 0) File(outDir, "thermal_pause_log.jsonl").appendText(s.toJson().toString() + "\n")
            Thread.sleep(3_000L)
        }
    }

    internal fun chooseConfig8(dpy: EGLDisplay, surfaceType: Int): EGLConfig {
        val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        check(EGL14.eglChooseConfig(dpy, intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, surfaceType, EGL14.EGL_NONE,
        ), 0, cfgs, 0, 1, n, 0) && n[0] > 0)
        return requireNotNull(cfgs[0])
    }

    internal fun linkProgram(vertSrc: String, fragSrc: String): Int {
        fun comp(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
            val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] == GLES30.GL_TRUE) { GLES30.glGetShaderInfoLog(s) }
            return s
        }
        val v = comp(GLES30.GL_VERTEX_SHADER, vertSrc); val f = comp(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f)
        GLES30.glBindAttribLocation(p, 0, "aPos")
        GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] == GLES30.GL_TRUE) { GLES30.glGetProgramInfoLog(p) }
        GLES30.glDeleteShader(v); GLES30.glDeleteShader(f)
        return p
    }

    // =========================================================================
    // Probe 16A: SurfaceTexture vs ImageReader Transform Audit & Orientation Matrix
    // =========================================================================
    @SuppressLint("MissingPermission")
    fun runProbe16A_OrientationTruth(context: Context, outDir: File): JSONObject =
        withWatchdogAndLogs(context, outDir) { _, _, sampleNow ->
            waitNaturalThermalCool(context, outDir)
            val manager = context.getSystemService(CameraManager::class.java)
            val stAudit = JSONArray()

            // 1. Inspect actual SurfaceTexture.getTransformMatrix across cameras 0, 1, 2, 4, 5
            for (camId in listOf("0", "1", "2", "4", "5")) {
                stAudit.put(runCatching {
                    val caps = manager.getCameraCharacteristics(camId)
                    val sensor = caps.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                    val facing = caps.get(CameraCharacteristics.LENS_FACING) ?: -1
                    val cuvaKey = LabBreakthroughProbes.findVendorRequestKey(caps, "cuvaStateVideoMode")
                    val cuvaHint = LabBreakthroughProbes.vendorKeyTypeHint(cuvaKey)
                    val lease = CaptureOwnerAdmission.processGlobal.acquire()
                    lease.ready().get(8, TimeUnit.SECONDS)
                    val camThread = HandlerThread("p16a-st-$camId").apply { start() }
                    val camHandler = Handler(camThread.looper)
                    val glThread = HandlerThread("p16a-gl-$camId").apply { start() }
                    val glHandler = Handler(glThread.looper)
                    val opened = CompletableFuture<CameraDevice>()
                    val closed = CompletableFuture<Unit>()
                    val configured = CompletableFuture<CameraCaptureSession>()
                    val matFut = CompletableFuture<JSONObject>()
                    var dpy = EGL14.EGL_NO_DISPLAY; var ctx = EGL14.EGL_NO_CONTEXT; var pbuf = EGL14.EGL_NO_SURFACE
                    var tex = 0; var st: SurfaceTexture? = null; var surf: Surface? = null
                    var session: CameraCaptureSession? = null
                    val retiring = AtomicBoolean(); var openSubmitted = false
                    try {
                        val initFut = CompletableFuture<Unit>()
                        glHandler.post {
                            runCatching {
                                dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                                val ver = IntArray(2); check(EGL14.eglInitialize(dpy, ver, 0, ver, 1))
                                val cfg = chooseConfig8(dpy, EGL14.EGL_PBUFFER_BIT)
                                ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
                                pbuf = EGL14.eglCreatePbufferSurface(dpy, cfg, intArrayOf(EGL14.EGL_WIDTH, 64, EGL14.EGL_HEIGHT, 64, EGL14.EGL_NONE), 0)
                                check(EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx))
                                val t = IntArray(1); GLES30.glGenTextures(1, t, 0); tex = t[0]
                                st = SurfaceTexture(tex).apply {
                                    setDefaultBufferSize(1920, 1080)
                                    setOnFrameAvailableListener({ texture ->
                                        if (retiring.get() || matFut.isDone) return@setOnFrameAvailableListener
                                        runCatching {
                                            EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx)
                                            texture.updateTexImage()
                                            val raw = FloatArray(16)
                                            texture.getTransformMatrix(raw)
                                            val unmirrored = raw.copyOf().also {
                                                if (facing == CameraCharacteristics.LENS_FACING_FRONT) unmirrorSurfaceTextureMatrix(it)
                                            }
                                            val expected = imageReaderTextureMatrix(sensor)
                                            val detRaw = raw[0] * raw[5] - raw[4] * raw[1]
                                            val detUnm = unmirrored[0] * unmirrored[5] - unmirrored[4] * unmirrored[1]
                                            var maxDiff = 0f
                                            for (i in 0 until 16) maxDiff = maxOf(maxDiff, kotlin.math.abs(unmirrored[i] - expected[i]))
                                            matFut.complete(json(
                                                "rawMatrix" to JSONArray(raw.map { it.toDouble() }),
                                                "unmirroredMatrix" to JSONArray(unmirrored.map { it.toDouble() }),
                                                "expectedMatrix" to JSONArray(expected.map { it.toDouble() }),
                                                "detRaw" to detRaw, "detUnmirrored" to detUnm,
                                                "maxDiffVsExpected" to maxDiff,
                                            ))
                                        }.onFailure { matFut.completeExceptionally(it) }
                                    }, glHandler)
                                }
                                surf = Surface(st)
                            }.onSuccess { initFut.complete(Unit) }.onFailure { initFut.completeExceptionally(it) }
                        }
                        initFut.get(5, TimeUnit.SECONDS)
                        val exec = Executor { r -> camHandler.post(r) }
                        manager.openCamera(camId, object : CameraDevice.StateCallback() {
                            override fun onOpened(c: CameraDevice) { opened.complete(c); if (retiring.get()) c.close() }
                            override fun onDisconnected(c: CameraDevice) { opened.completeExceptionally(IllegalStateException("disc")); c.close() }
                            override fun onError(c: CameraDevice, e: Int) { opened.completeExceptionally(IllegalStateException("err $e")); c.close() }
                            override fun onClosed(c: CameraDevice) { closed.complete(Unit) }
                        }, camHandler)
                        openSubmitted = true
                        val cam = opened.get(6, TimeUnit.SECONDS)
                        val outSurf = requireNotNull(surf)
                        val sessReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(outSurf)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                            if (cuvaKey != null) setVendorByteOrIntKey(this, cuvaKey, 1, cuvaHint)
                        }.build()
                        cam.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(outSurf)), exec,
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) { if (retiring.get()) s.close() else configured.complete(s) }
                                override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("cfg fail")); s.close() }
                            }).apply { sessionParameters = sessReq })
                        session = configured.get(6, TimeUnit.SECONDS)
                        val rep = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(outSurf)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                            if (cuvaKey != null) setVendorByteOrIntKey(this, cuvaKey, 1, cuvaHint)
                        }.build()
                        session.setRepeatingRequest(rep, null, camHandler)
                        val matObj = matFut.get(6, TimeUnit.SECONDS)
                        runCatching { session.stopRepeating() }
                        json("cameraId" to camId, "sensorOrientation" to sensor, "lensFacing" to facing, "ok" to true).apply {
                            for (k in matObj.keys()) put(k, matObj.get(k))
                        }
                    } finally {
                        retiring.set(true)
                        val cc = closed.thenApply { Unit }; lease.releaseAfter(cc)
                        if (openSubmitted) camHandler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } } else closed.complete(Unit)
                        runCatching { cc.get(6, TimeUnit.SECONDS) }
                        val gd = CompletableFuture<Unit>()
                        glHandler.post {
                            runCatching {
                                runCatching { surf?.release() }; runCatching { st?.release() }
                                if (tex != 0) GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
                                if (dpy != EGL14.EGL_NO_DISPLAY) {
                                    EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                                    if (pbuf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, pbuf)
                                    if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
                                    EGL14.eglTerminate(dpy)
                                }
                            }
                            gd.complete(Unit)
                        }
                        runCatching { gd.get(4, TimeUnit.SECONDS) }
                        camThread.quitSafely(); glThread.quitSafely()
                        runCatching { camThread.join(2_000L) }; runCatching { glThread.join(2_000L) }
                    }
                }.getOrElse { json("cameraId" to camId, "ok" to false, "error" to (it.message ?: it.toString())) })
                Thread.sleep(250L)
            }

            // 2. Record actual Camera2PreviewEngine clips across device orientations (0, 90, 180, 270) + UW & Tele
            val engine = Camera2PreviewEngine(context, deviceQuirks = DeviceQuirksProvider.lazy(context))
            val drainThread = HandlerThread("p16a-drain").apply { start() }
            val drainHandler = Handler(drainThread.looper)
            val previewSink = ImageReader.newInstance(1920, 1080, ImageFormat.PRIVATE, 4).apply {
                setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage()?.close() } }, drainHandler)
            }
            val clips = JSONArray()
            try {
                val descs = engine.descriptors(1920, 1080)
                val back = descs.first { it.cameraId == "0" }
                val lockByPhys = engine.lensLockCandidates("0").associateBy { it.physicalId }

                fun runOrientClip(tag: String, devOrient: Int, displayRot: Int, mode: RecordingGeometryMode, physicalId: String? = null): JSONObject {
                    waitNaturalThermalCool(context, outDir)
                    val targetDesc = if (physicalId != null) engine.descriptor(physicalId, 1920, 1080)!! else back
                    val profile = targetDesc.logProfiles.first { !it.constrainedHighSpeed && it.size == Size(1920, 1080) && it.fps == 30 }
                    val previewReady = CompletableFuture<Unit>()
                    val firstFrame = CompletableFuture<Long>()
                    val stopped = CompletableFuture<Boolean>()
                    val failRef = AtomicReference<String?>(null)
                    engine.startPreview(
                        descriptor = back, surface = previewSink.surface, displayRotationDegrees = displayRot,
                        listener = object : Camera2PreviewListener {
                            override fun onOpening(descriptor: Camera2CameraDescriptor) = Unit
                            override fun onPreviewStarted(descriptor: Camera2CameraDescriptor) { previewReady.complete(Unit) }
                            override fun onMetadata(metadata: Camera2PreviewMetadata) = Unit
                            override fun onJpegCaptured(bytes: ByteArray, width: Int, height: Int) = Unit
                            override fun onDngCaptured(bytes: ByteArray, width: Int, height: Int) = Unit
                            override fun onAnalysis(analysis: Camera2Analysis) = Unit
                            override fun onRecordingStarted(width: Int, height: Int) = Unit
                            override fun onRecordingFirstFrame(sensorTimestampNs: Long) { firstFrame.complete(sensorTimestampNs) }
                            override fun onRecordingStopped(saved: Boolean) { stopped.complete(saved) }
                            override fun onFailure(code: String, message: String, recoverable: Boolean) {
                                failRef.compareAndSet(null, "$code: $message")
                                previewReady.completeExceptionally(IllegalStateException("$code: $message"))
                                stopped.complete(false)
                            }
                        },
                        openCineLog = true, viewAssist = true, targetFps = 30, logProfile = profile,
                        lensLock = physicalId?.let { LensLockBinding("0", it, lockByPhys.getValue(it).anchorRatio) },
                    )
                    previewReady.get(8, TimeUnit.SECONDS)
                    Thread.sleep(250L)
                    val outFile = File(outDir, "$tag.mp4")
                    val pfd = ParcelFileDescriptor.open(outFile, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
                    val geom = RecordingGeometryCalculator.calculate(profile.size, targetDesc.sensorOrientation, devOrient, targetDesc.lensFacing, mode)
                    val okStart = try {
                        engine.startOpenCineLogVideo(pfd, geom, videoBitrate = 25_000_000)
                    } finally { runCatching { pfd.close() } }
                    check(okStart) { "startOpenCineLogVideo failed for $tag" }
                    firstFrame.get(6, TimeUnit.SECONDS)
                    Thread.sleep(1_200L)
                    check(engine.stopVideo())
                    val saved = stopped.get(8, TimeUnit.SECONDS)
                    engine.recordingOutputRetirement().get(8, TimeUnit.SECONDS)
                    val ev = engine.consumeLastOpenCineLogEvidence()
                    engine.stopPreview()
                    Thread.sleep(200L)
                    return json(
                        "tag" to tag, "ok" to (saved && ev != null), "clip" to outFile.name,
                        "deviceOrientation" to devOrient, "displayRotation" to displayRot,
                        "mode" to mode.name, "physicalId" to physicalId,
                        "sensorOrientation" to targetDesc.sensorOrientation,
                        "geomPixelRot" to geom.pixelRotationDegrees, "geomRendererRot" to geom.rendererRotationDegrees,
                        "geomContainerRot" to geom.containerRotationDegrees,
                        "encodedWidth" to ev?.encodedWidth, "encodedHeight" to ev?.encodedHeight,
                        "evContainerRot" to ev?.containerRotationDegrees,
                    )
                }

                clips.put(runOrientClip("p16a_back_port_0_compat", 0, 0, RecordingGeometryMode.COMPATIBLE))
                clips.put(runOrientClip("p16a_back_land_90_compat", 90, 270, RecordingGeometryMode.COMPATIBLE))
                clips.put(runOrientClip("p16a_back_revport_180_compat", 180, 180, RecordingGeometryMode.COMPATIBLE))
                clips.put(runOrientClip("p16a_back_revland_270_compat", 270, 90, RecordingGeometryMode.COMPATIBLE))
                clips.put(runOrientClip("p16a_back_port_0_native", 0, 0, RecordingGeometryMode.NATIVE_RASTER))
                clips.put(runOrientClip("p16a_uw_port_0_compat", 0, 0, RecordingGeometryMode.COMPATIBLE, physicalId = "4"))
                clips.put(runOrientClip("p16a_tele_port_0_compat", 0, 0, RecordingGeometryMode.COMPATIBLE, physicalId = "5"))
            } finally {
                runCatching { engine.closeAsync().get(8, TimeUnit.SECONDS) }
                runCatching { previewSink.close() }
                runCatching { drainThread.quitSafely(); drainThread.join(2_000L) }
            }

            val report = json("probe" to "16A_orientation_truth", "surfaceTextureAudit" to stAudit, "clips" to clips, "thermalEnd" to sampleNow())
            File(outDir, "probe16a_orientation.json").writeText(report.toString(2))
            report
        }

    // =========================================================================
    // Probe 16B: Ground-Truth OMX.hisi Encoder Matrix/Range + CUVA 0x324 vs SDR vs RAW
    // =========================================================================
    @SuppressLint("MissingPermission")
    fun runProbe16B_ColorAndEncoderTruth(context: Context, outDir: File): JSONObject =
        withWatchdogAndLogs(context, outDir) { _, _, sampleNow ->
            waitNaturalThermalCool(context, outDir)

            // Part 1: Pure GL -> OMX.hisi.video.encoder.hevc Main10 known-patch calibration (no camera)
            val encoderArms = listOf(
                Triple("enc_bt2020_full", MediaFormat.COLOR_STANDARD_BT2020, MediaFormat.COLOR_RANGE_FULL),
                Triple("enc_bt2020_limited", MediaFormat.COLOR_STANDARD_BT2020, MediaFormat.COLOR_RANGE_LIMITED),
                Triple("enc_bt709_limited", MediaFormat.COLOR_STANDARD_BT709, MediaFormat.COLOR_RANGE_LIMITED),
            )
            // 12 vertical stripes of known normalized RGB sent from GL fragment shader
            val patchRgb = listOf(
                floatArrayOf(0.00f, 0.00f, 0.00f),
                floatArrayOf(0.05f, 0.05f, 0.05f),
                floatArrayOf(0.10f, 0.10f, 0.10f),
                floatArrayOf(0.18f, 0.18f, 0.18f),
                floatArrayOf(0.50f, 0.50f, 0.50f),
                floatArrayOf(0.90f, 0.90f, 0.90f),
                floatArrayOf(1.00f, 1.00f, 1.00f),
                floatArrayOf(0.80f, 0.10f, 0.10f),
                floatArrayOf(0.10f, 0.80f, 0.10f),
                floatArrayOf(0.10f, 0.10f, 0.80f),
                floatArrayOf(0.80f, 0.80f, 0.10f),
                floatArrayOf(0.10f, 0.80f, 0.80f),
            )
            val encoderResults = JSONArray()
            val codecName = OpenCineLogGpuPipeline.findEncoder(Size(1920, 1080), 30)?.codecName
                ?: "OMX.hisi.video.encoder.hevc"
            val encThread = HandlerThread("p16b-enc").apply { start() }
            val encHandler = Handler(encThread.looper)
            try {
                for ((armTag, std, rng) in encoderArms) {
                    val file = File(outDir, "$armTag.mp4")
                    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
                    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1080).apply {
                        setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
                        setInteger(MediaFormat.KEY_LEVEL, CodecProfileLevel.HEVCMainTierLevel62)
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(MediaFormat.KEY_BIT_RATE, 40_000_000)
                        setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                        setInteger(MediaFormat.KEY_COLOR_STANDARD, std)
                        setInteger(MediaFormat.KEY_COLOR_RANGE, rng)
                    }
                    val codec = MediaCodec.createByCodecName(codecName)
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    val inputSurf = codec.createInputSurface()
                    val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    codec.start()
                    val drainDone = CompletableFuture<Int>()
                    val drainThread = Thread {
                        var track = -1; var started = false; var frames = 0
                        val info = MediaCodec.BufferInfo()
                        try {
                            while (true) {
                                val out = codec.dequeueOutputBuffer(info, 20_000)
                                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                    track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true
                                } else if (out >= 0) {
                                    if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && started) {
                                        muxer.writeSampleData(track, codec.getOutputBuffer(out)!!, info); frames++
                                    }
                                    codec.releaseOutputBuffer(out, false)
                                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                                }
                            }
                            drainDone.complete(frames)
                        } catch (t: Throwable) { drainDone.completeExceptionally(t) }
                    }.apply { start() }

                    val glFut = CompletableFuture<Unit>()
                    encHandler.post {
                        var dpy = EGL14.EGL_NO_DISPLAY; var ctx = EGL14.EGL_NO_CONTEXT; var win = EGL14.EGL_NO_SURFACE
                        var prog = 0
                        try {
                            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                            val ver = IntArray(2); check(EGL14.eglInitialize(dpy, ver, 0, ver, 1))
                            val cfg = chooseConfig8(dpy, EGL14.EGL_WINDOW_BIT)
                            ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
                            win = EGL14.eglCreateWindowSurface(dpy, cfg, inputSurf, intArrayOf(EGL14.EGL_NONE), 0)
                            check(EGL14.eglMakeCurrent(dpy, win, win, ctx))
                            val vert = "#version 300 es\nlayout(location=0) in vec2 aPos;\nout vec2 vUv;\nvoid main(){ gl_Position=vec4(aPos,0.0,1.0); vUv=(aPos+1.0)*0.5; }"
                            val colorsGlsl = patchRgb.joinToString(", ") { "vec3(${it[0]}, ${it[1]}, ${it[2]})" }
                            val frag = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 outColor;
void main() {
    vec3 cols[12] = vec3[12]($colorsGlsl);
    int idx = clamp(int(floor(vUv.x * 12.0)), 0, 11);
    outColor = vec4(cols[idx], 1.0);
}"""
                            prog = linkProgram(vert, frag)
                            val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
                                .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).apply { position(0) }
                            GLES30.glViewport(0, 0, 1920, 1080)
                            GLES30.glUseProgram(prog)
                            GLES30.glEnableVertexAttribArray(0)
                            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad)
                            for (fIdx in 0 until 8) {
                                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                                EGLExt.eglPresentationTimeANDROID(dpy, win, fIdx * 33_333_333L)
                                EGL14.eglSwapBuffers(dpy, win)
                            }
                            glFut.complete(Unit)
                        } catch (t: Throwable) { glFut.completeExceptionally(t) }
                        finally {
                            if (prog != 0) GLES30.glDeleteProgram(prog)
                            if (dpy != EGL14.EGL_NO_DISPLAY) {
                                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                                if (win != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, win)
                                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
                                EGL14.eglTerminate(dpy)
                            }
                        }
                    }
                    glFut.get(8, TimeUnit.SECONDS)
                    codec.signalEndOfInputStream()
                    val encFrames = drainDone.get(8, TimeUnit.SECONDS)
                    drainThread.join(2_000L)
                    runCatching { codec.stop() }; runCatching { codec.release() }
                    runCatching { muxer.stop() }; runCatching { muxer.release() }
                    runCatching { inputSurf.release() }; runCatching { pfd.close() }
                    encoderResults.put(json("tag" to armTag, "clip" to file.name, "frames" to encFrames, "colorStandard" to std, "colorRange" to rng))
                }
            } finally {
                encThread.quitSafely(); runCatching { encThread.join(2_000L) }
            }

            // Part 2: Live Camera 0 locked 1/60s ISO400 5600K WB: SDR vs CUVA(driver) vs CUVA(ycbcr?) vs RAW_SENSOR
            val manager = context.getSystemService(CameraManager::class.java)
            val caps = manager.getCameraCharacteristics("0")
            val cuvaKey = LabBreakthroughProbes.findVendorRequestKey(caps, "cuvaStateVideoMode")
            val cuvaHint = LabBreakthroughProbes.vendorKeyTypeHint(cuvaKey)
            val sceneArms = JSONArray()

            // Helper: sample center + 3x3 grid RGB statistics from GL external texture (shaderYcbcr or driver)
            fun captureGlStatsArm(tag: String, useCuva: Boolean, useYcbcrShader: Boolean, evExpNs: Long): JSONObject = runCatching {
                val lease = CaptureOwnerAdmission.processGlobal.acquire()
                lease.ready().get(8, TimeUnit.SECONDS)
                val camThread = HandlerThread("p16b-$tag-c").apply { start() }
                val camHandler = Handler(camThread.looper)
                val glThread = HandlerThread("p16b-$tag-g").apply { start() }
                val glHandler = Handler(glThread.looper)
                val opened = CompletableFuture<CameraDevice>(); val closed = CompletableFuture<Unit>()
                val configured = CompletableFuture<CameraCaptureSession>(); val statsFut = CompletableFuture<JSONObject>()
                val w = 1920; val h = 1080
                val reader = LabBreakthroughProbes.createUsageImageReader(w, h, ImageFormat.PRIVATE, 4,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_VIDEO_ENCODE)
                var dpy = EGL14.EGL_NO_DISPLAY; var ctx = EGL14.EGL_NO_CONTEXT; var pbuf = EGL14.EGL_NO_SURFACE
                var tex = 0; var prog = 0; var fbo = 0; var fboTex = 0; var session: CameraCaptureSession? = null
                val retiring = AtomicBoolean(); var openSubmitted = false; val sampleCount = AtomicInteger(0)
                val hwFmt = AtomicInteger(-1); val dsAtomic = AtomicInteger(-1)
                try {
                    val glInit = CompletableFuture<Unit>()
                    glHandler.post {
                        runCatching {
                            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                            val v = IntArray(2); check(EGL14.eglInitialize(dpy, v, 0, v, 1))
                            val cfg = chooseConfig8(dpy, EGL14.EGL_PBUFFER_BIT)
                            ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
                            pbuf = EGL14.eglCreatePbufferSurface(dpy, cfg, intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
                            check(EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx))
                            val t = IntArray(2); GLES30.glGenTextures(2, t, 0); tex = t[0]; fboTex = t[1]
                            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
                            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTex)
                            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w / 4, h / 4, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
                            val fb = IntArray(1); GLES30.glGenFramebuffers(1, fb, 0); fbo = fb[0]
                            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
                            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fboTex, 0)
                            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                            val vert = "#version 300 es\nlayout(location=0) in vec2 aPos;\nout vec2 vTexCoord;\nvoid main(){ gl_Position=vec4(aPos,0.0,1.0); vTexCoord=(aPos+1.0)*0.5; }"
                            val frag = if (useYcbcrShader) {
                                """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
#extension GL_EXT_YUV_target : require
precision highp float;
uniform __samplerExternal2DY2YEXT uTexture;
in vec2 vTexCoord;
out vec4 outColor;
void main(){ outColor = vec4(texture(uTexture, vec2(vTexCoord.x, 1.0 - vTexCoord.y)).rgb, 1.0); }"""
                            } else {
                                """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTexture;
in vec2 vTexCoord;
out vec4 outColor;
void main(){ outColor = vec4(texture(uTexture, vec2(vTexCoord.x, 1.0 - vTexCoord.y)).rgb, 1.0); }"""
                            }
                            prog = linkProgram(vert, frag)
                        }.onSuccess { glInit.complete(Unit) }.onFailure { glInit.completeExceptionally(it) }
                    }
                    glInit.get(6, TimeUnit.SECONDS)

                    reader.setOnImageAvailableListener({ r ->
                        val img = runCatching { r.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
                        var hb: HardwareBuffer? = null; var eglImg = 0L
                        try {
                            if (retiring.get() || statsFut.isDone) return@setOnImageAvailableListener
                            val c = sampleCount.incrementAndGet()
                            hb = img.hardwareBuffer
                            if (hb != null && hwFmt.get() == -1) {
                                hwFmt.compareAndSet(-1, EglHardwareBufferBridge.describeAhb(hb)?.first ?: hb.format)
                            }
                            if (c < 10) return@setOnImageAvailableListener // settle AE/WB
                            val nonNullHb = hb ?: return@setOnImageAvailableListener
                            val done = CompletableFuture<Unit>()
                            glHandler.post {
                                runCatching {
                                    EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx)
                                    eglImg = EglHardwareBufferBridge.createEglImage(nonNullHb)
                                    check(eglImg != 0L) { "eglCreateImageKHR failed" }
                                    GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
                                    check(EglHardwareBufferBridge.bindExternalTexture(eglImg) == 0)
                                    val rw = w / 4; val rh = h / 4
                                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
                                    GLES30.glViewport(0, 0, rw, rh)
                                    GLES30.glUseProgram(prog)
                                    GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uTexture"), 0)
                                    val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
                                        .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).apply { position(0) }
                                    GLES30.glEnableVertexAttribArray(0)
                                    GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad)
                                    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                                    val rgba = ByteBuffer.allocateDirect(rw * rh * 4)
                                    GLES30.glReadPixels(0, 0, rw, rh, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rgba)
                                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                                    val bytes = ByteArray(rw * rh * 4); rgba.position(0); rgba.get(bytes)
                                    var sum0 = 0.0; var sum1 = 0.0; var sum2 = 0.0; val nPix = rw * rh
                                    var cSum0 = 0.0; var cSum1 = 0.0; var cSum2 = 0.0; var cCnt = 0
                                    var min0 = 255; var max0 = 0; var satHi = 0; var crushLo = 0
                                    for (y in 0 until rh) for (x in 0 until rw) {
                                        val i = (y * rw + x) * 4
                                        val b0 = bytes[i].toInt() and 0xFF
                                        val b1 = bytes[i + 1].toInt() and 0xFF
                                        val b2 = bytes[i + 2].toInt() and 0xFF
                                        sum0 += b0; sum1 += b1; sum2 += b2
                                        if (b0 < min0) min0 = b0; if (b0 > max0) max0 = b0
                                        if (b0 >= 253 || b1 >= 253 || b2 >= 253) satHi++
                                        if (b0 <= 2 && b1 <= 2 && b2 <= 2) crushLo++
                                        if (x in rw / 3..(2 * rw / 3) && y in rh / 3..(2 * rh / 3)) {
                                            cSum0 += b0; cSum1 += b1; cSum2 += b2; cCnt++
                                        }
                                    }
                                    statsFut.complete(json(
                                        "tag" to tag, "ok" to true, "cuvaOn" to useCuva, "ycbcrShader" to useYcbcrShader,
                                        "expNs" to evExpNs,
                                        "hwFmtHex" to hwFmt.get().takeIf { it != -1 }?.let { "0x${it.toString(16)}" },
                                        "meanCh" to JSONArray(listOf(sum0 / nPix / 255.0, sum1 / nPix / 255.0, sum2 / nPix / 255.0)),
                                        "centerCh" to JSONArray(listOf(cSum0 / cCnt / 255.0, cSum1 / cCnt / 255.0, cSum2 / cCnt / 255.0)),
                                        "minCh0" to min0, "maxCh0" to max0,
                                        "satHiFrac" to satHi.toDouble() / nPix, "crushLoFrac" to crushLo.toDouble() / nPix,
                                    ))
                                }.onFailure { statsFut.completeExceptionally(it) }
                                    .also {
                                        if (eglImg != 0L) EglHardwareBufferBridge.destroyEglImage(eglImg)
                                        done.complete(Unit)
                                    }
                            }
                            done.get(4, TimeUnit.SECONDS)
                        } catch (t: Throwable) {
                            statsFut.completeExceptionally(t)
                        } finally {
                            runCatching { hb?.close() }; runCatching { img.close() }
                        }
                    }, camHandler)

                    val exec = Executor { r -> camHandler.post(r) }
                    manager.openCamera("0", object : CameraDevice.StateCallback() {
                        override fun onOpened(c: CameraDevice) { opened.complete(c); if (retiring.get()) c.close() }
                        override fun onDisconnected(c: CameraDevice) { opened.completeExceptionally(IllegalStateException("disc")); c.close() }
                        override fun onError(c: CameraDevice, e: Int) { opened.completeExceptionally(IllegalStateException("err $e")); c.close() }
                        override fun onClosed(c: CameraDevice) { closed.complete(Unit) }
                    }, camHandler)
                    openSubmitted = true
                    val cam = opened.get(6, TimeUnit.SECONDS)
                    fun applyReq(b: CaptureRequest.Builder) {
                        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, evExpNs)
                        b.set(CaptureRequest.SENSOR_SENSITIVITY, 400)
                        b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        HuaweiVendorControls.applyKelvin(b, caps, "0", 5600)
                        if (cuvaKey != null) setVendorByteOrIntKey(b, cuvaKey, if (useCuva) 1 else 0, cuvaHint)
                    }
                    val sessReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(reader.surface); applyReq(this) }.build()
                    cam.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(reader.surface)), exec,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) { if (retiring.get()) s.close() else configured.complete(s) }
                            override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("cfg fail")); s.close() }
                        }).apply { sessionParameters = sessReq })
                    session = configured.get(6, TimeUnit.SECONDS)
                    session.setRepeatingRequest(cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(reader.surface); applyReq(this) }.build(), null, camHandler)
                    val res = statsFut.get(8, TimeUnit.SECONDS)
                    runCatching { session.stopRepeating() }
                    res
                } finally {
                    retiring.set(true)
                    val cc = closed.thenApply { Unit }; lease.releaseAfter(cc)
                    if (openSubmitted) camHandler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } } else closed.complete(Unit)
                    runCatching { cc.get(6, TimeUnit.SECONDS) }
                    runCatching { reader.close() }
                    val gd = CompletableFuture<Unit>()
                    glHandler.post {
                        runCatching {
                            if (prog != 0) GLES30.glDeleteProgram(prog)
                            if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
                            if (tex != 0) GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
                            if (fboTex != 0) GLES30.glDeleteTextures(1, intArrayOf(fboTex), 0)
                            if (dpy != EGL14.EGL_NO_DISPLAY) {
                                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                                if (pbuf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, pbuf)
                                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
                                EGL14.eglTerminate(dpy)
                            }
                        }
                        gd.complete(Unit)
                    }
                    runCatching { gd.get(4, TimeUnit.SECONDS) }
                    camThread.quitSafely(); glThread.quitSafely()
                    runCatching { camThread.join(2_000L) }; runCatching { glThread.join(2_000L) }
                }
            }.getOrElse { json("tag" to tag, "ok" to false, "error" to (it.message ?: it.toString())) }

            // Capture SDR (0x22) & CUVA (0x324) across 5 EV stops + CUVA YCbCr shader probe
            for ((evName, expNs) in listOf("m2ev" to 4_166_667L, "m1ev" to 8_333_333L, "0ev" to 16_666_667L, "p1ev" to 33_333_333L, "p2ev" to 66_666_667L)) {
                sceneArms.put(captureGlStatsArm("sdr_$evName", useCuva = false, useYcbcrShader = false, evExpNs = expNs))
                sceneArms.put(captureGlStatsArm("cuva_drv_$evName", useCuva = true, useYcbcrShader = false, evExpNs = expNs))
            }
            sceneArms.put(captureGlStatsArm("cuva_ycbcr_0ev", useCuva = true, useYcbcrShader = true, evExpNs = 16_666_667L))
            sceneArms.put(captureGlStatsArm("sdr_ycbcr_0ev", useCuva = false, useYcbcrShader = true, evExpNs = 16_666_667L))

            val report = json(
                "probe" to "16B_color_and_encoder_truth",
                "encoderArms" to encoderResults,
                "sceneArms" to sceneArms,
                "thermalEnd" to sampleNow(),
            )
            File(outDir, "probe16b_color.json").writeText(report.toString(2))
            report
        }

    // =========================================================================
    // Probe 16C: 10-bit EGLConfig -> OMX.hisi Main10 Precision & 9-Stop CUVA Curve
    // =========================================================================
    internal fun chooseEglConfigExact(
        dpy: EGLDisplay,
        red: Int,
        green: Int,
        blue: Int,
        alpha: Int,
        surfaceType: Int,
        floatComponent: Boolean = false,
    ): EGLConfig? {
        val attrs = mutableListOf(
            EGL14.EGL_RED_SIZE, red,
            EGL14.EGL_GREEN_SIZE, green,
            EGL14.EGL_BLUE_SIZE, blue,
            EGL14.EGL_ALPHA_SIZE, alpha,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, surfaceType,
        )
        if (floatComponent) {
            attrs += 0x3339 // EGL_COLOR_COMPONENT_TYPE_EXT
            attrs += 0x333B // EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT
        }
        attrs += EGL14.EGL_NONE
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        val ok = EGL14.eglChooseConfig(dpy, attrs.toIntArray(), 0, cfgs, 0, 1, n, 0)
        return if (ok && n[0] > 0) cfgs[0] else null
    }

    private fun waitNaturalStats(samples: IntArray, count: Int): Triple<Double, Double, Double> {
        val copy = samples.copyOf(count).also { it.sort() }
        val p01 = copy[(0.01 * (count - 1)).toInt()].toDouble()
        val p50 = copy[count / 2].toDouble()
        val p99 = copy[(0.99 * (count - 1)).toInt()].toDouble()
        return Triple(p01, p50, p99)
    }

    @SuppressLint("MissingPermission")
    fun runProbe16C_True10BitAndCuvaCurve(context: Context, outDir: File): JSONObject =
        withWatchdogAndLogs(context, outDir) { _, _, sampleNow ->
            waitNaturalThermalCool(context, outDir)

            // Part A: Encoder capabilities + 10-bit step precision across EGL configs
            val codecName = OpenCineLogGpuPipeline.findEncoder(Size(1920, 1080), 30)?.codecName
                ?: "OMX.hisi.video.encoder.hevc"
            val codecFormats = runCatching {
                MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                    .firstOrNull { it.isEncoder && it.name == codecName }
                    ?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                    ?.colorFormats
                    ?.map { "0x${it.toString(16)}" }
                    .orEmpty()
            }.getOrDefault(emptyList())

            val eglProbe = JSONObject()
            val encArmsOut = JSONArray()
            val patchSteps10 = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 510, 511, 512, 513, 1020, 1021, 1022, 1023)
            val encThread = HandlerThread("p16c-enc").apply { start() }
            val encHandler = Handler(encThread.looper)

            fun queryEglInfo(): Pair<Set<String>, JSONObject> {
                val fut = CompletableFuture<Pair<Set<String>, JSONObject>>()
                encHandler.post {
                    runCatching {
                        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                        val ver = IntArray(2); check(EGL14.eglInitialize(dpy, ver, 0, ver, 1))
                        val exts = EGL14.eglQueryString(dpy, EGL14.EGL_EXTENSIONS).orEmpty().split(' ').filter { it.isNotBlank() }.toSet()
                        val hasRgb10 = chooseEglConfigExact(dpy, 10, 10, 10, 2, EGL14.EGL_WINDOW_BIT) != null
                        val hasFloat16 = "EGL_EXT_pixel_format_float" in exts &&
                            chooseEglConfigExact(dpy, 16, 16, 16, 16, EGL14.EGL_WINDOW_BIT, floatComponent = true) != null
                        val info = json(
                            "rgb10a2Supported" to hasRgb10,
                            "rgba16fSupported" to hasFloat16,
                            "bt2020HlgExt" to ("EGL_EXT_gl_colorspace_bt2020_hlg" in exts),
                            "bt2020LinearExt" to ("EGL_EXT_gl_colorspace_bt2020_linear" in exts),
                            "pixelFormatFloatExt" to ("EGL_EXT_pixel_format_float" in exts),
                        )
                        EGL14.eglTerminate(dpy)
                        exts to info
                    }.onSuccess { fut.complete(it) }.onFailure { fut.completeExceptionally(it) }
                }
                return fut.get(5, TimeUnit.SECONDS)
            }

            val (eglExts, eglCapabilitiesJson) = queryEglInfo()
            for (k in eglCapabilitiesJson.keys()) eglProbe.put(k, eglCapabilitiesJson.get(k))
            eglProbe.put("codecName", codecName)
            eglProbe.put("codecColorFormats", JSONArray(codecFormats))

            data class EncArm(val tag: String, val bits: Int, val isFloat: Boolean, val csAttr: Int?)
            val arms = listOf(
                EncArm("p16c_rgb10a2_none", 10, false, null),
                EncArm("p16c_rgb10a2_hlg", 10, false, 0x3540 /* EGL_GL_COLORSPACE_BT2020_HLG_EXT */),
                EncArm("p16c_rgba16f_none", 16, true, null),
                EncArm("p16c_rgba8_none", 8, false, null),
            )
            try {
                for (arm in arms) {
                    if (arm.csAttr != null && "EGL_EXT_gl_colorspace_bt2020_hlg" !in eglExts) {
                        encArmsOut.put(json("tag" to arm.tag, "ok" to false, "skipped" to "EGL_EXT_gl_colorspace_bt2020_hlg unsupported"))
                        continue
                    }
                    if (arm.isFloat && "EGL_EXT_pixel_format_float" !in eglExts) {
                        encArmsOut.put(json("tag" to arm.tag, "ok" to false, "skipped" to "EGL_EXT_pixel_format_float unsupported"))
                        continue
                    }
                    encArmsOut.put(runCatching {
                        val file = File(outDir, "${arm.tag}.mp4")
                        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
                        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1080).apply {
                            setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
                            setInteger(MediaFormat.KEY_LEVEL, CodecProfileLevel.HEVCMainTierLevel62)
                            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                            setInteger(MediaFormat.KEY_BIT_RATE, 80_000_000)
                            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
                        }
                        val codec = MediaCodec.createByCodecName(codecName)
                        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        val inputSurf = codec.createInputSurface()
                        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        codec.start()
                        val drainDone = CompletableFuture<Int>()
                        val drainThread = Thread {
                            var track = -1; var started = false; var frames = 0
                            val info = MediaCodec.BufferInfo()
                            try {
                                while (true) {
                                    val out = codec.dequeueOutputBuffer(info, 20_000)
                                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                        track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true
                                    } else if (out >= 0) {
                                        if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && started) {
                                            muxer.writeSampleData(track, codec.getOutputBuffer(out)!!, info); frames++
                                        }
                                        codec.releaseOutputBuffer(out, false)
                                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                                    }
                                }
                                drainDone.complete(frames)
                            } catch (t: Throwable) { drainDone.completeExceptionally(t) }
                        }.apply { start() }

                        val glFut = CompletableFuture<Unit>()
                        encHandler.post {
                            var dpy = EGL14.EGL_NO_DISPLAY; var ctx = EGL14.EGL_NO_CONTEXT; var win = EGL14.EGL_NO_SURFACE
                            var prog = 0
                            try {
                                dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                                val ver = IntArray(2); check(EGL14.eglInitialize(dpy, ver, 0, ver, 1))
                                val cfg = when {
                                    arm.isFloat -> chooseEglConfigExact(dpy, 16, 16, 16, 16, EGL14.EGL_WINDOW_BIT, floatComponent = true)
                                    arm.bits == 10 -> chooseEglConfigExact(dpy, 10, 10, 10, 2, EGL14.EGL_WINDOW_BIT)
                                    else -> chooseConfig8(dpy, EGL14.EGL_WINDOW_BIT)
                                } ?: error("EGLConfig unavailable for ${arm.tag}")
                                val actualR = IntArray(1); val actualG = IntArray(1); val actualB = IntArray(1)
                                EGL14.eglGetConfigAttrib(dpy, cfg, EGL14.EGL_RED_SIZE, actualR, 0)
                                EGL14.eglGetConfigAttrib(dpy, cfg, EGL14.EGL_GREEN_SIZE, actualG, 0)
                                EGL14.eglGetConfigAttrib(dpy, cfg, EGL14.EGL_BLUE_SIZE, actualB, 0)
                                ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
                                check(ctx != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed (err=${EGL14.eglGetError()})" }
                                val winAttrs = if (arm.csAttr != null) intArrayOf(0x309D /* EGL_GL_COLORSPACE_KHR */, arm.csAttr, EGL14.EGL_NONE) else intArrayOf(EGL14.EGL_NONE)
                                win = EGL14.eglCreateWindowSurface(dpy, cfg, inputSurf, winAttrs, 0)
                                check(win != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed (err=${EGL14.eglGetError()})" }
                                check(EGL14.eglMakeCurrent(dpy, win, win, ctx)) { "eglMakeCurrent failed (err=${EGL14.eglGetError()})" }
                                val vert = "#version 300 es\nlayout(location=0) in vec2 aPos;\nout vec2 vUv;\nvoid main(){ gl_Position=vec4(aPos,0.0,1.0); vUv=(aPos+1.0)*0.5; }"
                                val valsGlsl = patchSteps10.joinToString(", ") { "${it.toFloat() / 1023.0f}f" }
                                val frag = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 outColor;
void main() {
    float vals[16] = float[16]($valsGlsl);
    int idx = clamp(int(floor(vUv.x * 16.0)), 0, 15);
    outColor = vec4(vec3(vals[idx]), 1.0);
}"""
                                prog = linkProgram(vert, frag)
                                val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
                                    .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).apply { position(0) }
                                GLES30.glViewport(0, 0, 1920, 1080)
                                GLES30.glUseProgram(prog)
                                GLES30.glEnableVertexAttribArray(0)
                                GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad)
                                for (fIdx in 0 until 8) {
                                    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                                    EGLExt.eglPresentationTimeANDROID(dpy, win, fIdx * 33_333_333L)
                                    EGL14.eglSwapBuffers(dpy, win)
                                }
                                glFut.complete(Unit)
                            } catch (t: Throwable) { glFut.completeExceptionally(t) }
                            finally {
                                if (prog != 0) GLES30.glDeleteProgram(prog)
                                if (dpy != EGL14.EGL_NO_DISPLAY) {
                                    EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                                    if (win != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, win)
                                    if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
                                    EGL14.eglTerminate(dpy)
                                }
                            }
                        }
                        try {
                            glFut.get(8, TimeUnit.SECONDS)
                            codec.signalEndOfInputStream()
                            val encFrames = drainDone.get(8, TimeUnit.SECONDS)
                            drainThread.join(2_000L)
                            json("tag" to arm.tag, "ok" to true, "clip" to file.name, "frames" to encFrames, "targetBits" to arm.bits)
                        } finally {
                            runCatching { codec.stop() }; runCatching { codec.release() }
                            runCatching { muxer.stop() }; runCatching { muxer.release() }
                            runCatching { inputSurf.release() }; runCatching { pfd.close() }
                        }
                    }.getOrElse { json("tag" to arm.tag, "ok" to false, "error" to (it.message ?: it.toString())) })
                }
            } finally {
                encThread.quitSafely(); runCatching { encThread.join(2_000L) }
            }

            // Part B: 9-Stop (-4EV..+4EV) single-session CUVA (DRV + YCBCR) vs SDR (DRV + YCBCR) + RAW_SENSOR 0EV anchor
            val manager = context.getSystemService(CameraManager::class.java)
            val caps = manager.getCameraCharacteristics("0")
            val cuvaKey = LabBreakthroughProbes.findVendorRequestKey(caps, "cuvaStateVideoMode")
            val cuvaHint = LabBreakthroughProbes.vendorKeyTypeHint(cuvaKey)
            val expRange = caps.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val isoRange = caps.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val fixedIso = 200.coerceIn(isoRange?.lower ?: 200, isoRange?.upper ?: 200)
            val evStops = listOf(
                -4 to 1_041_667L,
                -3 to 2_083_333L,
                -2 to 4_166_667L,
                -1 to 8_333_333L,
                0 to 16_666_667L,
                1 to 33_333_333L,
                2 to 66_666_667L,
                3 to 133_333_333L,
                4 to 266_666_667L,
            ).map { (ev, ns) -> ev to ns.coerceIn(expRange?.lower ?: ns, expRange?.upper ?: ns) }

            fun runSweep(modeName: String, useCuva: Boolean): JSONArray = runCatching {
                val results = JSONArray()
                val lease = CaptureOwnerAdmission.processGlobal.acquire()
                lease.ready().get(8, TimeUnit.SECONDS)
                val camThread = HandlerThread("p16c-$modeName-c").apply { start() }
                val camHandler = Handler(camThread.looper)
                val glThread = HandlerThread("p16c-$modeName-g").apply { start() }
                val glHandler = Handler(glThread.looper)
                val opened = CompletableFuture<CameraDevice>(); val closed = CompletableFuture<Unit>()
                val configured = CompletableFuture<CameraCaptureSession>()
                val w = 1920; val h = 1080; val rw = w / 4; val rh = h / 4
                val reader = LabBreakthroughProbes.createUsageImageReader(w, h, ImageFormat.PRIVATE, 4,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_VIDEO_ENCODE)
                var dpy = EGL14.EGL_NO_DISPLAY; var ctx = EGL14.EGL_NO_CONTEXT; var pbuf = EGL14.EGL_NO_SURFACE
                var tex = 0; var progDrv = 0; var progYcbcr = 0; var fbo = 0; var fboTex = 0
                var session: CameraCaptureSession? = null
                val retiring = AtomicBoolean(); var openSubmitted = false
                val targetCaptureExpNs = AtomicLong(-1L)
                val settleCount = AtomicInteger(0)
                val evReadyFut = AtomicReference<CompletableFuture<JSONObject>?>(null)
                val lastMetaExpNs = AtomicLong(-1L); val lastMetaIso = AtomicInteger(-1)
                val hwFmt = AtomicInteger(-1)
                try {
                    val glInit = CompletableFuture<Unit>()
                    glHandler.post {
                        runCatching {
                            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                            val v = IntArray(2); check(EGL14.eglInitialize(dpy, v, 0, v, 1))
                            val cfg = chooseConfig8(dpy, EGL14.EGL_PBUFFER_BIT)
                            ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
                            pbuf = EGL14.eglCreatePbufferSurface(dpy, cfg, intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
                            check(EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx))
                            val t = IntArray(2); GLES30.glGenTextures(2, t, 0); tex = t[0]; fboTex = t[1]
                            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
                            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTex)
                            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGB10_A2, rw, rh, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_INT_2_10_10_10_REV, null)
                            val fb = IntArray(1); GLES30.glGenFramebuffers(1, fb, 0); fbo = fb[0]
                            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
                            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fboTex, 0)
                            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "RGB10_A2 FBO incomplete" }
                            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                            val vert = "#version 300 es\nlayout(location=0) in vec2 aPos;\nout vec2 vTexCoord;\nvoid main(){ gl_Position=vec4(aPos,0.0,1.0); vTexCoord=(aPos+1.0)*0.5; }"
                            progDrv = linkProgram(vert, """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTexture;
in vec2 vTexCoord;
out vec4 outColor;
void main(){ outColor = vec4(texture(uTexture, vec2(vTexCoord.x, 1.0 - vTexCoord.y)).rgb, 1.0); }""")
                            progYcbcr = linkProgram(vert, """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
#extension GL_EXT_YUV_target : require
precision highp float;
uniform __samplerExternal2DY2YEXT uTexture;
in vec2 vTexCoord;
out vec4 outColor;
void main(){ outColor = vec4(texture(uTexture, vec2(vTexCoord.x, 1.0 - vTexCoord.y)).rgb, 1.0); }""")
                        }.onSuccess { glInit.complete(Unit) }.onFailure { glInit.completeExceptionally(it) }
                    }
                    glInit.get(6, TimeUnit.SECONDS)

                    fun sampleCurrent(prog: Int, pixBuf: ByteBuffer): JSONObject {
                        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
                        GLES30.glViewport(0, 0, rw, rh)
                        GLES30.glUseProgram(prog)
                        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uTexture"), 0)
                        val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
                            .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).apply { position(0) }
                        GLES30.glEnableVertexAttribArray(0)
                        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad)
                        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                        pixBuf.clear()
                        GLES30.glReadPixels(0, 0, rw, rh, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_INT_2_10_10_10_REV, pixBuf)
                        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                        pixBuf.position(0)
                        val ints = pixBuf.order(ByteOrder.nativeOrder()).asIntBuffer()
                        val n = rw * rh
                        val ch0All = IntArray(n)
                        var sum0 = 0.0; var sum1 = 0.0; var sum2 = 0.0
                        var cSum0 = 0.0; var cSum1 = 0.0; var cSum2 = 0.0; var cCnt = 0
                        for (y in 0 until rh) for (x in 0 until rw) {
                            val idx = y * rw + x
                            val p = ints.get(idx)
                            val r10 = p and 0x3FF
                            val g10 = (p ushr 10) and 0x3FF
                            val b10 = (p ushr 20) and 0x3FF
                            ch0All[idx] = r10
                            sum0 += r10; sum1 += g10; sum2 += b10
                            if (x in rw / 3..(2 * rw / 3) && y in rh / 3..(2 * rh / 3)) {
                                cSum0 += r10; cSum1 += g10; cSum2 += b10; cCnt++
                            }
                        }
                        val (p01, p50, p99) = waitNaturalStats(ch0All, n)
                        return json(
                            "mean10" to JSONArray(listOf(sum0 / n, sum1 / n, sum2 / n)),
                            "centerMean10" to JSONArray(listOf(cSum0 / cCnt, cSum1 / cCnt, cSum2 / cCnt)),
                            "p01_ch0" to p01, "p50_ch0" to p50, "p99_ch0" to p99,
                        )
                    }

                    val pixBuf = ByteBuffer.allocateDirect(rw * rh * 4)
                    reader.setOnImageAvailableListener({ r ->
                        val img = runCatching { r.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
                        var hb: HardwareBuffer? = null; var eglImg = 0L
                        try {
                            val fut = evReadyFut.get()
                            if (retiring.get() || fut == null || fut.isDone) return@setOnImageAvailableListener
                            hb = img.hardwareBuffer
                            if (hb != null && hwFmt.get() == -1) {
                                hwFmt.compareAndSet(-1, EglHardwareBufferBridge.describeAhb(hb)?.first ?: hb.format)
                            }
                            if (settleCount.incrementAndGet() < 4) return@setOnImageAvailableListener
                            val nonNullHb = hb ?: return@setOnImageAvailableListener
                            val done = CompletableFuture<Unit>()
                            glHandler.post {
                                runCatching {
                                    EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx)
                                    eglImg = EglHardwareBufferBridge.createEglImage(nonNullHb)
                                    check(eglImg != 0L)
                                    GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
                                    check(EglHardwareBufferBridge.bindExternalTexture(eglImg) == 0)
                                    val drvStats = sampleCurrent(progDrv, pixBuf)
                                    val ycbStats = sampleCurrent(progYcbcr, pixBuf)
                                    fut.complete(json(
                                        "drv" to drvStats,
                                        "ycbcr" to ycbStats,
                                        "hwFmtHex" to hwFmt.get().takeIf { it != -1 }?.let { "0x${it.toString(16)}" },
                                        "metaExpNs" to lastMetaExpNs.get(),
                                        "metaIso" to lastMetaIso.get(),
                                    ))
                                }.onFailure { fut.completeExceptionally(it) }
                                    .also {
                                        if (eglImg != 0L) EglHardwareBufferBridge.destroyEglImage(eglImg)
                                        done.complete(Unit)
                                    }
                            }
                            done.get(4, TimeUnit.SECONDS)
                        } catch (t: Throwable) {
                            evReadyFut.get()?.completeExceptionally(t)
                        } finally {
                            runCatching { hb?.close() }; runCatching { img.close() }
                        }
                    }, camHandler)

                    val exec = Executor { r -> camHandler.post(r) }
                    manager.openCamera("0", object : CameraDevice.StateCallback() {
                        override fun onOpened(c: CameraDevice) { opened.complete(c); if (retiring.get()) c.close() }
                        override fun onDisconnected(c: CameraDevice) { opened.completeExceptionally(IllegalStateException("disc")); c.close() }
                        override fun onError(c: CameraDevice, e: Int) { opened.completeExceptionally(IllegalStateException("err $e")); c.close() }
                        override fun onClosed(c: CameraDevice) { closed.complete(Unit) }
                    }, camHandler)
                    openSubmitted = true
                    val cam = opened.get(6, TimeUnit.SECONDS)
                    fun buildReq(expNs: Long): CaptureRequest = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(reader.surface)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
                        set(CaptureRequest.SENSOR_SENSITIVITY, fixedIso)
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        HuaweiVendorControls.applyKelvin(this, caps, "0", 5600)
                        if (cuvaKey != null) setVendorByteOrIntKey(this, cuvaKey, if (useCuva) 1 else 0, cuvaHint)
                    }.build()
                    cam.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(reader.surface)), exec,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) { if (retiring.get()) s.close() else configured.complete(s) }
                            override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("cfg fail")); s.close() }
                        }).apply { sessionParameters = buildReq(16_666_667L) })
                    session = configured.get(6, TimeUnit.SECONDS)

                    val cb = object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastMetaExpNs.set(it) }
                            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastMetaIso.set(it) }
                        }
                    }
                    for ((ev, expNs) in evStops) {
                        targetCaptureExpNs.set(expNs)
                        settleCount.set(0)
                        val fut = CompletableFuture<JSONObject>()
                        evReadyFut.set(fut)
                        session.setRepeatingRequest(buildReq(expNs), cb, camHandler)
                        val obj = runCatching { fut.get(6, TimeUnit.SECONDS) }.getOrElse { json("error" to (it.message ?: it.toString())) }
                        obj.put("ev", ev); obj.put("reqExpNs", expNs); obj.put("reqIso", fixedIso)
                        results.put(obj)
                    }
                    runCatching { session.stopRepeating() }
                    results
                } finally {
                    retiring.set(true)
                    val cc = closed.thenApply { Unit }; lease.releaseAfter(cc)
                    if (openSubmitted) camHandler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } } else closed.complete(Unit)
                    runCatching { cc.get(6, TimeUnit.SECONDS) }
                    runCatching { reader.close() }
                    val gd = CompletableFuture<Unit>()
                    glHandler.post {
                        runCatching {
                            if (progDrv != 0) GLES30.glDeleteProgram(progDrv)
                            if (progYcbcr != 0) GLES30.glDeleteProgram(progYcbcr)
                            if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
                            if (tex != 0) GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
                            if (fboTex != 0) GLES30.glDeleteTextures(1, intArrayOf(fboTex), 0)
                            if (dpy != EGL14.EGL_NO_DISPLAY) {
                                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                                if (pbuf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, pbuf)
                                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
                                EGL14.eglTerminate(dpy)
                            }
                        }
                        gd.complete(Unit)
                    }
                    runCatching { gd.get(4, TimeUnit.SECONDS) }
                    camThread.quitSafely(); glThread.quitSafely()
                    runCatching { camThread.join(2_000L) }; runCatching { glThread.join(2_000L) }
                }
            }.getOrElse { JSONArray().put(json("ok" to false, "error" to (it.message ?: it.toString()))) }

            val cuvaSweep = runSweep("cuva", useCuva = true)
            val sdrSweep = runSweep("sdr", useCuva = false)

            // RAW_SENSOR reference anchor at -2, 0, +2 EV
            val rawAnchor = runCatching {
                val rawOut = JSONArray()
                val streamMap = caps.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val rawSize = streamMap?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
                    ?: error("RAW_SENSOR unavailable")
                val whiteLevel = (caps.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toDouble()
                val blPattern = caps.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
                val blMean = if (blPattern != null) {
                    (0..3).sumOf { blPattern.getOffsetForIndex(it % 2, it / 2) } / 4.0
                } else 64.0
                val lease = CaptureOwnerAdmission.processGlobal.acquire()
                lease.ready().get(8, TimeUnit.SECONDS)
                val camThread = HandlerThread("p16c-raw-c").apply { start() }
                val camHandler = Handler(camThread.looper)
                val opened = CompletableFuture<CameraDevice>(); val closed = CompletableFuture<Unit>()
                val configured = CompletableFuture<CameraCaptureSession>()
                val rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2)
                var session: CameraCaptureSession? = null; val retiring = AtomicBoolean(); var openSubmitted = false
                val rawFut = AtomicReference<CompletableFuture<JSONObject>?>(null)
                try {
                    rawReader.setOnImageAvailableListener({ r ->
                        val img = runCatching { r.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
                        try {
                            val fut = rawFut.get()
                            if (fut == null || fut.isDone) return@setOnImageAvailableListener
                            val plane = img.planes[0]
                            val rowStride = plane.rowStride
                            val buf = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
                            val cw0 = rawSize.width * 45 / 100; val cw1 = rawSize.width * 55 / 100
                            val ch0 = rawSize.height * 45 / 100; val ch1 = rawSize.height * 55 / 100
                            var sumNorm = 0.0; var cnt = 0
                            for (y in ch0 until ch1 step 2) {
                                val base = y * rowStride
                                for (x in cw0 until cw1 step 2) {
                                    val raw16 = buf.getShort(base + x * 2).toInt() and 0xFFFF
                                    sumNorm += ((raw16 - blMean) / (whiteLevel - blMean)).coerceIn(0.0, 1.0)
                                    cnt++
                                }
                            }
                            fut.complete(json("centerLinearMean" to sumNorm / cnt.coerceAtLeast(1), "whiteLevel" to whiteLevel, "blackLevel" to blMean))
                        } catch (t: Throwable) {
                            rawFut.get()?.completeExceptionally(t)
                        } finally { img.close() }
                    }, camHandler)
                    manager.openCamera("0", object : CameraDevice.StateCallback() {
                        override fun onOpened(c: CameraDevice) { opened.complete(c); if (retiring.get()) c.close() }
                        override fun onDisconnected(c: CameraDevice) { opened.completeExceptionally(IllegalStateException("disc")); c.close() }
                        override fun onError(c: CameraDevice, e: Int) { opened.completeExceptionally(IllegalStateException("err $e")); c.close() }
                        override fun onClosed(c: CameraDevice) { closed.complete(Unit) }
                    }, camHandler)
                    openSubmitted = true
                    val cam = opened.get(6, TimeUnit.SECONDS)
                    cam.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                        listOf(OutputConfiguration(rawReader.surface)), Executor { r -> camHandler.post(r) },
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) { if (retiring.get()) s.close() else configured.complete(s) }
                            override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("cfg fail")); s.close() }
                        }))
                    session = configured.get(6, TimeUnit.SECONDS)
                    for ((ev, expNs) in listOf(-2 to 4_166_667L, 0 to 16_666_667L, 2 to 66_666_667L)) {
                        val f = CompletableFuture<JSONObject>()
                        rawFut.set(f)
                        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(rawReader.surface)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                            set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
                            set(CaptureRequest.SENSOR_SENSITIVITY, fixedIso)
                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                        }.build()
                        session.capture(req, null, camHandler)
                        val o = runCatching { f.get(6, TimeUnit.SECONDS) }.getOrElse { json("error" to (it.message ?: it.toString())) }
                        o.put("ev", ev); o.put("expNs", expNs); rawOut.put(o)
                    }
                    rawOut
                } finally {
                    retiring.set(true)
                    val cc = closed.thenApply { Unit }; lease.releaseAfter(cc)
                    if (openSubmitted) camHandler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } } else closed.complete(Unit)
                    runCatching { cc.get(6, TimeUnit.SECONDS) }
                    runCatching { rawReader.close() }
                    camThread.quitSafely(); runCatching { camThread.join(2_000L) }
                }
            }.getOrElse { JSONArray().put(json("ok" to false, "error" to (it.message ?: it.toString()))) }

            val report = json(
                "probe" to "16C_true10bit_and_cuva_curve",
                "eglInfo" to eglProbe,
                "encoderPrecisionArms" to encArmsOut,
                "cuva9EvSweep" to cuvaSweep,
                "sdr9EvSweep" to sdrSweep,
                "rawSensorAnchor" to rawAnchor,
                "thermalEnd" to sampleNow(),
            )
            File(outDir, "probe16c_10bit_cuva.json").writeText(report.toString(2))
            report
        }

    // =========================================================================
    // Probe 16D: Non-RGB-Surface True 10-bit Paths & Multi-Camera RAW Audit
    // =========================================================================
    @SuppressLint("MissingPermission")
    fun runProbe16D_True10BitPaths(context: Context, outDir: File): JSONObject =
        withWatchdogAndLogs(context, outDir) { _, _, sampleNow ->
            waitNaturalThermalCool(context, outDir)
            val codecName = OpenCineLogGpuPipeline.findEncoder(Size(1920, 1080), 30)?.codecName
                ?: "OMX.hisi.video.encoder.hevc"
            val manager = context.getSystemService(CameraManager::class.java)
            val caps0 = manager.getCameraCharacteristics("0")
            val cuvaKey0 = LabBreakthroughProbes.findVendorRequestKey(caps0, "cuvaStateVideoMode")
            val cuvaHint0 = LabBreakthroughProbes.vendorKeyTypeHint(cuvaKey0)

            // Arm 1: Direct Camera2 0x324 -> OMX.hisi.video.encoder.hevc Main10 Input Surface (bypass GL)
            val directCuvaArm = runCatching {
                val outFile = File(outDir, "p16d_direct_cuva.mp4")
                val pfd = ParcelFileDescriptor.open(outFile, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
                val encFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1080).apply {
                    setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
                    setInteger(MediaFormat.KEY_LEVEL, CodecProfileLevel.HEVCMainTierLevel62)
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, 80_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                }
                val codec = MediaCodec.createByCodecName(codecName)
                codec.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val encSurface = codec.createInputSurface()
                val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                codec.start()
                var outFormatStr = ""
                val drainFut = CompletableFuture<Int>()
                val drainThread = Thread {
                    var track = -1; var started = false; var frames = 0
                    val info = MediaCodec.BufferInfo()
                    try {
                        while (true) {
                            val out = codec.dequeueOutputBuffer(info, 20_000)
                            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                outFormatStr = codec.outputFormat.toString()
                                track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true
                            } else if (out >= 0) {
                                if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && started) {
                                    muxer.writeSampleData(track, codec.getOutputBuffer(out)!!, info); frames++
                                }
                                codec.releaseOutputBuffer(out, false)
                                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                            }
                        }
                        drainFut.complete(frames)
                    } catch (t: Throwable) { drainFut.completeExceptionally(t) }
                }.apply { start() }

                val lease = CaptureOwnerAdmission.processGlobal.acquire()
                lease.ready().get(8, TimeUnit.SECONDS)
                val camThread = HandlerThread("p16d-direct").apply { start() }
                val camHandler = Handler(camThread.looper)
                val previewReader = LabBreakthroughProbes.createUsageImageReader(1280, 720, ImageFormat.PRIVATE, 3,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_VIDEO_ENCODE)
                previewReader.setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage()?.close() } }, camHandler)
                val opened = CompletableFuture<CameraDevice>(); val closed = CompletableFuture<Unit>()
                val configured = CompletableFuture<CameraCaptureSession>()
                var session: CameraCaptureSession? = null; val retiring = AtomicBoolean(); var openSubmitted = false
                val countTarget = CountDownLatch(45)
                try {
                    manager.openCamera("0", object : CameraDevice.StateCallback() {
                        override fun onOpened(c: CameraDevice) { opened.complete(c); if (retiring.get()) c.close() }
                        override fun onDisconnected(c: CameraDevice) { opened.completeExceptionally(IllegalStateException("disc")); c.close() }
                        override fun onError(c: CameraDevice, e: Int) { opened.completeExceptionally(IllegalStateException("err $e")); c.close() }
                        override fun onClosed(c: CameraDevice) { closed.complete(Unit) }
                    }, camHandler)
                    openSubmitted = true
                    val cam = opened.get(6, TimeUnit.SECONDS)
                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(encSurface)
                        addTarget(previewReader.surface)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        if (cuvaKey0 != null) setVendorByteOrIntKey(this, cuvaKey0, 1, cuvaHint0)
                    }.build()
                    cam.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                        listOf(OutputConfiguration(encSurface), OutputConfiguration(previewReader.surface)),
                        Executor { r -> camHandler.post(r) },
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) { if (retiring.get()) s.close() else configured.complete(s) }
                            override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("cfg fail")); s.close() }
                        }).apply { sessionParameters = req })
                    session = configured.get(6, TimeUnit.SECONDS)
                    session.setRepeatingRequest(req, object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                            countTarget.countDown()
                        }
                    }, camHandler)
                    countTarget.await(4, TimeUnit.SECONDS)
                    runCatching { session.stopRepeating() }
                    codec.signalEndOfInputStream()
                    val encFrames = drainFut.get(8, TimeUnit.SECONDS)
                    drainThread.join(2_000L)
                    json("ok" to true, "clip" to outFile.name, "frames" to encFrames, "outputFormat" to outFormatStr)
                } finally {
                    retiring.set(true)
                    val cc = closed.thenApply { Unit }; lease.releaseAfter(cc)
                    if (openSubmitted) camHandler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } } else closed.complete(Unit)
                    runCatching { cc.get(6, TimeUnit.SECONDS) }
                    runCatching { previewReader.close() }
                    runCatching { codec.stop() }; runCatching { codec.release() }
                    runCatching { muxer.stop() }; runCatching { muxer.release() }
                    runCatching { encSurface.release() }; runCatching { pfd.close() }
                    camThread.quitSafely(); runCatching { camThread.join(2_000L) }
                    Thread.sleep(500L)
                }
            }.getOrElse { json("ok" to false, "error" to (it.message ?: it.toString())) }

            // Arm 2a: ByteBuffer color-format probe (0x7f000011, 0x7f00000f, 0x7f000001, 0x7f420888)
            val byteBufferArms = JSONArray()
            val patchSteps10 = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 510, 511, 512, 513, 1020, 1021, 1022, 1023)
            for (fmtInt in listOf(0x7f000011, 0x7f00000f, 0x7f000001, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)) {
                val hex = "0x${fmtInt.toString(16)}"
                byteBufferArms.put(runCatching {
                    val encFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1080).apply {
                        setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
                        setInteger(MediaFormat.KEY_LEVEL, CodecProfileLevel.HEVCMainTierLevel62)
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, fmtInt)
                        setInteger(MediaFormat.KEY_BIT_RATE, 80_000_000)
                        setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
                    }
                    val codec = MediaCodec.createByCodecName(codecName)
                    val outFile = File(outDir, "p16d_buf_${fmtInt.toString(16)}.mp4")
                    var muxer: MediaMuxer? = null; var pfd: ParcelFileDescriptor? = null
                    try {
                        codec.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        val inputFmtStr = runCatching { codec.inputFormat.toString() }.getOrDefault("unavailable")
                        codec.start()
                        val idx = codec.dequeueInputBuffer(500_000)
                        check(idx >= 0) { "dequeueInputBuffer returned $idx" }
                        val firstBuf = codec.getInputBuffer(idx) ?: error("null inputBuffer")
                        val cap = firstBuf.capacity()
                        val is16BitSize = cap >= 1920 * 1080 * 3
                        if (!is16BitSize) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            json("colorFormat" to hex, "ok" to true, "inputFormat" to inputFmtStr,
                                "firstBufferCapacity" to cap, "bitDepthClass" to "8bit_size")
                        } else {
                            pfd = ParcelFileDescriptor.open(outFile, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
                            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                            val info = MediaCodec.BufferInfo()
                            var track = -1; var started = false; var frames = 0
                            // Build 1 frame P010 pattern (1920*1080 Y uint16 little endian << 6 + 1920*540 UV 0x8000)
                            val w = 1920; val h = 1080
                            val p010Template = ByteArray(w * h * 3).also { arr ->
                                val bb = ByteBuffer.wrap(arr).order(ByteOrder.LITTLE_ENDIAN)
                                val rowShorts = ShortArray(w) { x ->
                                    val bar = (x * 16 / w).coerceIn(0, 15)
                                    ((patchSteps10[bar] and 0x3FF) shl 6).toShort()
                                }
                                val sb = bb.asShortBuffer()
                                for (y in 0 until h) sb.put(rowShorts)
                                val uvShorts = ShortArray(w) { 0x8000.toShort() }
                                for (y in 0 until h / 2) sb.put(uvShorts)
                            }
                            for (fIdx in 0 until 8) {
                                val bufIdx = if (fIdx == 0) idx else codec.dequeueInputBuffer(500_000).also { check(it >= 0) }
                                val b = codec.getInputBuffer(bufIdx)!!
                                b.clear()
                                b.put(p010Template, 0, minOf(p010Template.size, b.capacity()))
                                codec.queueInputBuffer(bufIdx, 0, p010Template.size, fIdx * 33_333L, 0)
                            }
                            val eosIdx = codec.dequeueInputBuffer(500_000)
                            if (eosIdx >= 0) codec.queueInputBuffer(eosIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            val deadlineMs = System.currentTimeMillis() + 6_000L
                            while (System.currentTimeMillis() < deadlineMs) {
                                val out = codec.dequeueOutputBuffer(info, 20_000)
                                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                    track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true
                                } else if (out >= 0) {
                                    if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && started) {
                                        muxer.writeSampleData(track, codec.getOutputBuffer(out)!!, info); frames++
                                    }
                                    codec.releaseOutputBuffer(out, false)
                                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                                }
                            }
                            json("colorFormat" to hex, "ok" to true, "inputFormat" to inputFmtStr,
                                "firstBufferCapacity" to cap, "bitDepthClass" to "p010_or_16bit", "clip" to outFile.name, "frames" to frames)
                        }
                    } finally {
                        runCatching { codec.stop() }; runCatching { codec.release() }
                        runCatching { muxer?.stop() }; runCatching { muxer?.release() }
                        runCatching { pfd?.close() }
                    }
                }.getOrElse { json("colorFormat" to hex, "ok" to false, "error" to (it.message ?: it.toString())) })
            }

            // Arm 2b: HardwareBuffer (0x324, 0x36, 0x23) & GL_EXT_YUV_target layout(yuv) shader compile probe
            val hbArms = JSONArray()
            val usageModes = listOf(
                "gpu_color+video_enc" to (HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_VIDEO_ENCODE),
                "gpu_sampled+video_enc" to (HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_VIDEO_ENCODE),
                "gpu_color_only" to HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
            )
            for ((fmtName, fmtCode) in listOf("cuva_0x324" to 0x324, "p010_0x36" to 0x36, "yuv420_0x23" to 0x23)) {
                for ((uName, uFlags) in usageModes) {
                    hbArms.put(runCatching {
                        val hb = HardwareBuffer.create(1920, 1080, fmtCode, 1, uFlags)
                        hb.close()
                        json("format" to fmtName, "usage" to uName, "created" to true)
                    }.getOrElse { json("format" to fmtName, "usage" to uName, "created" to false, "error" to (it.message ?: it.toString())) })
                }
            }
            val glYuvOutProbe = runCatching {
                val t = HandlerThread("p16d-gl-yuv").apply { start() }
                val fut = CompletableFuture<JSONObject>()
                Handler(t.looper).post {
                    var dpy = EGL14.EGL_NO_DISPLAY; var ctx = EGL14.EGL_NO_CONTEXT; var pbuf = EGL14.EGL_NO_SURFACE
                    var prog = 0
                    try {
                        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                        val v = IntArray(2); check(EGL14.eglInitialize(dpy, v, 0, v, 1))
                        val cfg = chooseConfig8(dpy, EGL14.EGL_PBUFFER_BIT)
                        ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
                        pbuf = EGL14.eglCreatePbufferSurface(dpy, cfg, intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
                        check(EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx))
                        val exts = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
                        val hasYuvTarget = "GL_EXT_YUV_target" in exts
                        val vert = "#version 300 es\nlayout(location=0) in vec2 aPos;\nvoid main(){ gl_Position=vec4(aPos,0.0,1.0); }"
                        val frag = """#version 300 es
#extension GL_EXT_YUV_target : require
precision highp float;
layout(yuv) out vec4 outColor;
void main(){ outColor = vec4(0.5, 0.5, 0.5, 1.0); }"""
                        val shaderCompile = runCatching {
                            prog = linkProgram(vert, frag)
                            true
                        }.getOrElse { false }
                        val compileErr = if (!shaderCompile) {
                            runCatching { linkProgram(vert, frag) }.exceptionOrNull()?.message
                        } else null
                        fut.complete(json("hasGlExtYuvTarget" to hasYuvTarget, "layoutYuvShaderLinked" to shaderCompile, "error" to compileErr))
                    } catch (e: Throwable) {
                        fut.completeExceptionally(e)
                    } finally {
                        if (prog != 0) GLES30.glDeleteProgram(prog)
                        if (dpy != EGL14.EGL_NO_DISPLAY) {
                            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                            if (pbuf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, pbuf)
                            if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
                            EGL14.eglTerminate(dpy)
                        }
                    }
                }
                val res = fut.get(6, TimeUnit.SECONDS)
                t.quitSafely(); runCatching { t.join(1_000L) }
                res
            }.getOrElse { json("error" to (it.message ?: it.toString())) }

            // Arm 3: Multi-camera RAW_SENSOR / RAW10 audit (+ paired 0 EV RAW anchor if any back camera exposes RAW_SENSOR)
            val rawAudit = JSONArray()
            var pairedRawCamId: String? = null
            for (camId in listOf("0", "1", "2", "4", "5")) {
                rawAudit.put(runCatching {
                    val c = manager.getCameraCharacteristics(camId)
                    val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val capArr = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
                    val hasRawCap = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in capArr
                    val rawSensorSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.map { "${it.width}x${it.height}" }.orEmpty()
                    val raw10Sizes = map?.getOutputSizes(ImageFormat.RAW10)?.map { "${it.width}x${it.height}" }.orEmpty()
                    val formats = map?.outputFormats?.map { "0x${it.toString(16)}" }.orEmpty()
                    val facing = c.get(CameraCharacteristics.LENS_FACING) ?: -1
                    if (pairedRawCamId == null && facing == CameraCharacteristics.LENS_FACING_BACK && rawSensorSizes.isNotEmpty()) {
                        pairedRawCamId = camId
                    }
                    json("cameraId" to camId, "facing" to facing, "hasRawCapability" to hasRawCap,
                        "rawSensorSizes" to JSONArray(rawSensorSizes), "raw10Sizes" to JSONArray(raw10Sizes),
                        "outputFormats" to JSONArray(formats))
                }.getOrElse { json("cameraId" to camId, "error" to (it.message ?: it.toString())) })
            }

            val pairedAnchorResult: JSONObject = pairedRawCamId?.let { camId ->
                runCatching {
                    val c = manager.getCameraCharacteristics(camId)
                    val rawSize = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                        .getOutputSizes(ImageFormat.RAW_SENSOR).maxByOrNull { it.width.toLong() * it.height }!!
                    val whiteLevel = (c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toDouble()
                    val blPattern = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
                    val blMean = if (blPattern != null) (0..3).sumOf { blPattern.getOffsetForIndex(it % 2, it / 2) } / 4.0 else 64.0
                    val lease = CaptureOwnerAdmission.processGlobal.acquire()
                    lease.ready().get(8, TimeUnit.SECONDS)
                    val ct = HandlerThread("p16d-raw-$camId").apply { start() }
                    val ch = Handler(ct.looper)
                    val rdr = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2)
                    val op = CompletableFuture<CameraDevice>(); val cl = CompletableFuture<Unit>()
                    val cfg = CompletableFuture<CameraCaptureSession>(); val rFut = CompletableFuture<JSONObject>()
                    var ses: CameraCaptureSession? = null; val ret = AtomicBoolean(); var sub = false
                    try {
                        rdr.setOnImageAvailableListener({ r ->
                            val img = runCatching { r.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
                            try {
                                if (!rFut.isDone) {
                                    val plane = img.planes[0]; val rs = plane.rowStride
                                    val buf = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
                                    val x0 = rawSize.width * 45 / 100; val x1 = rawSize.width * 55 / 100
                                    val y0 = rawSize.height * 45 / 100; val y1 = rawSize.height * 55 / 100
                                    var sum = 0.0; var cnt = 0
                                    for (yy in y0 until y1 step 2) {
                                        val base = yy * rs
                                        for (xx in x0 until x1 step 2) {
                                            val raw16 = buf.getShort(base + xx * 2).toInt() and 0xFFFF
                                            sum += ((raw16 - blMean) / (whiteLevel - blMean)).coerceIn(0.0, 1.0)
                                            cnt++
                                        }
                                    }
                                    rFut.complete(json("cameraId" to camId, "rawSize" to "${rawSize.width}x${rawSize.height}",
                                        "centerLinearMean" to sum / cnt.coerceAtLeast(1), "whiteLevel" to whiteLevel, "blackLevel" to blMean))
                                }
                            } catch (e: Throwable) { rFut.completeExceptionally(e) } finally { img.close() }
                        }, ch)
                        manager.openCamera(camId, object : CameraDevice.StateCallback() {
                            override fun onOpened(d: CameraDevice) { op.complete(d); if (ret.get()) d.close() }
                            override fun onDisconnected(d: CameraDevice) { op.completeExceptionally(IllegalStateException("disc")); d.close() }
                            override fun onError(d: CameraDevice, e: Int) { op.completeExceptionally(IllegalStateException("err $e")); d.close() }
                            override fun onClosed(d: CameraDevice) { cl.complete(Unit) }
                        }, ch)
                        sub = true
                        val cam = op.get(6, TimeUnit.SECONDS)
                        cam.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                            listOf(OutputConfiguration(rdr.surface)), Executor { r -> ch.post(r) },
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) { if (ret.get()) s.close() else cfg.complete(s) }
                                override fun onConfigureFailed(s: CameraCaptureSession) { cfg.completeExceptionally(IllegalStateException("cfg")); s.close() }
                            }))
                        ses = cfg.get(6, TimeUnit.SECONDS)
                        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(rdr.surface)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                            set(CaptureRequest.SENSOR_EXPOSURE_TIME, 16_666_667L)
                            set(CaptureRequest.SENSOR_SENSITIVITY, 200)
                        }.build()
                        ses.capture(req, null, ch)
                        rFut.get(6, TimeUnit.SECONDS)
                    } finally {
                        ret.set(true)
                        val cc = cl.thenApply { Unit }; lease.releaseAfter(cc)
                        if (sub) ch.post { try { ses?.close() } finally { if (!op.isCompletedExceptionally) op.getNow(null)?.close() } } else cl.complete(Unit)
                        runCatching { cc.get(6, TimeUnit.SECONDS) }
                        runCatching { rdr.close() }; ct.quitSafely(); runCatching { ct.join(2_000L) }
                    }
                }.getOrElse { json("cameraId" to camId, "error" to (it.message ?: it.toString())) }
            } ?: json("available" to false, "reason" to "No public back camera exposes ImageFormat.RAW_SENSOR")

            val report = json(
                "probe" to "16D_true10bit_paths",
                "codecName" to codecName,
                "directCuvaToEncoder" to directCuvaArm,
                "byteBufferColorFormats" to byteBufferArms,
                "hardwareBufferProbes" to hbArms,
                "glExtYuvTargetOut" to glYuvOutProbe,
                "multiCameraRawAudit" to rawAudit,
                "pairedRawAnchor" to pairedAnchorResult,
                "thermalEnd" to sampleNow(),
            )
            File(outDir, "probe16d_report.json").writeText(report.toString(2))
            report
        }
}
