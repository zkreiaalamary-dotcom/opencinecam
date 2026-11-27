/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresApi
import com.librestatic.opencinecam.camera.Camera2PreviewEngine
import com.librestatic.opencinecam.camera.CaptureOwnerAdmission
import com.librestatic.opencinecam.camera.HevcSpsInspector
import com.librestatic.opencinecam.camera.HevcVuiTransfer
import com.librestatic.opencinecam.camera.OpenCineLogGpuPipeline
import com.librestatic.opencinecam.camera.OpenCineLogRecordingEvidence
import com.librestatic.opencinecam.camera.OpenCineLogSourcePath
import com.librestatic.opencinecam.camera.RecordingGeometryCalculator
import com.librestatic.opencinecam.camera.RecordingGeometryMode
import com.librestatic.opencinecam.camera.ResearchEncoderOverride
import com.librestatic.opencinecam.media.video.EglHardwareBufferBridge
import com.librestatic.opencinecam.media.video.HevcEncoderLevels
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Isolated Stage G Kirin 9020 / Huawei Pura 80 Pro hardware probes (Probes 1-5):
 * 1. 4K60 Camera2 input/consumer cadence isolation (SurfaceTexture vs ImageReader vs scene modes)
 * 2. CUVA HDR Vivid Direct 4K30/4K60 & GPU 4K30 recording with real MP4 evidence
 * 3. Camera-closed synthetic 4K60 EGL priority & MediaCodec realtime throughput
 * 4. High-resolution RAW_SENSOR (32) / RAW10 / RAW_PRIVATE admission across cameras 0 & 2
 * 5. Vendor ByteBuffer 10-bit format clips (`0x7F000011` / `0x7F00000F` / `Flexible`)
 */
internal object LabBreakthroughProbes {
    private const val HISI_HEVC = "OMX.hisi.video.encoder.hevc"
    private const val COLOR_FORMAT_HISI_YUV420SP_10BIT = 0x7F000011
    private const val COLOR_FORMAT_HISI_YUV420SP = 0x7F00000F
    private const val EGL_CONTEXT_PRIORITY_LEVEL_IMG = 0x3100
    private const val EGL_CONTEXT_PRIORITY_HIGH_IMG = 0x3101
    private const val EGL_RECORDABLE_ANDROID = 0x3142
    private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
    private const val EGL_GL_COLORSPACE_KHR = 0x309D
    private const val EGL_GL_COLORSPACE_BT2020_LINEAR_EXT = 0x333F
    private const val GAP_THRESHOLD_60FPS_NS = 25_000_000L

    @RequiresApi(Build.VERSION_CODES.Q)
    private object Api29ImageReader {
        fun create(width: Int, height: Int, format: Int, maxImages: Int, usage: Long): ImageReader =
            ImageReader.newInstance(width, height, format, maxImages, usage)
    }

    private fun createUsageImageReader(width: Int, height: Int, format: Int, maxImages: Int, usage: Long?): ImageReader =
        if (usage != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Api29ImageReader.create(width, height, format, maxImages, usage)
        } else {
            ImageReader.newInstance(width, height, format, maxImages)
        }

    fun batteryTempC(context: Context): Double? {
        val intent = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
            ?: return null
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths > -1000) tenths / 10.0 else null
    }

    private fun ensureCoolOrWait(context: Context, checkBudget: () -> Unit, maxWaitMs: Long = 90_000L): Double? {
        val deadline = System.currentTimeMillis() + maxWaitMs
        var temp = batteryTempC(context)
        while (temp != null && temp >= 40.0 && System.currentTimeMillis() < deadline) {
            checkBudget()
            Thread.sleep(5_000L)
            temp = batteryTempC(context)
        }
        checkBudget()
        if (temp != null && temp >= 40.0) {
            throw IllegalStateException("Battery temperature stayed >= 40.0 C ($temp C)")
        }
        return temp
    }

    private fun saveProbeJson(outDir: File, fileName: String, payload: JSONObject): JSONObject {
        runCatching {
            outDir.mkdirs()
            File(outDir, fileName).writeText(payload.toString(2), Charsets.UTF_8)
        }
        return payload
    }

    private fun selectHevcEncoderName(): String {
        val infos = runCatching { MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos }.getOrNull().orEmpty()
        infos.firstOrNull { it.isEncoder && !it.isAlias && it.name == HISI_HEVC }?.let { return it.name }
        return infos.firstOrNull {
            it.isEncoder && !it.isAlias && it.isHardwareAccelerated &&
                it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) }
        }?.name ?: HISI_HEVC
    }

    private fun findVendorRequestKey(caps: CameraCharacteristics, substr: String): CaptureRequest.Key<*>? =
        runCatching { caps.availableCaptureRequestKeys }.getOrNull().orEmpty()
            .firstOrNull { it.name.contains(substr, ignoreCase = true) }

    private fun findVendorResultKey(caps: CameraCharacteristics, substr: String): CaptureResult.Key<*>? =
        runCatching { caps.availableCaptureResultKeys }.getOrNull().orEmpty()
            .firstOrNull { it.name.contains(substr, ignoreCase = true) }

    private fun vendorKeyTypeHint(key: CaptureRequest.Key<*>?): String = runCatching {
        val nativeKey = key?.javaClass?.getMethod("getNativeKey")?.invoke(key)
        nativeKey?.javaClass?.getMethod("getType")?.invoke(nativeKey)?.toString()
    }.getOrNull() ?: "unknown"

    private fun readResultKeyValue(res: TotalCaptureResult, key: CaptureResult.Key<*>?): String? {
        if (key == null) return null
        val value = runCatching {
            @Suppress("UNCHECKED_CAST")
            res.get(key as CaptureResult.Key<Any>)
        }.getOrNull() ?: return null
        return when (value) {
            is ByteArray -> value.contentToString()
            is IntArray -> value.contentToString()
            else -> value.toString()
        }
    }

    private data class TimestampMetrics(
        val frames: Int,
        val fps: Double,
        val gaps: Int,
        val meanDeltaMs: Double,
        val maxDeltaMs: Double,
    )

    private fun computeTimestampMetrics(timestampsNs: List<Long>, gapThresholdNs: Long = GAP_THRESHOLD_60FPS_NS): TimestampMetrics {
        if (timestampsNs.size < 2) {
            return TimestampMetrics(timestampsNs.size, 0.0, 0, 0.0, 0.0)
        }
        val sorted = timestampsNs.sorted()
        val spanNs = (sorted.last() - sorted.first()).coerceAtLeast(1L)
        val fps = ((sorted.size - 1) * 1_000_000_000.0) / spanNs.toDouble()
        var gaps = 0
        var maxDeltaNs = 0L
        var sumDeltaNs = 0L
        for (i in 1 until sorted.size) {
            val d = (sorted[i] - sorted[i - 1]).coerceAtLeast(0L)
            sumDeltaNs += d
            if (d > maxDeltaNs) maxDeltaNs = d
            if (d > gapThresholdNs) gaps++
        }
        return TimestampMetrics(
            frames = sorted.size,
            fps = fps,
            gaps = gaps,
            meanDeltaMs = (sumDeltaNs.toDouble() / (sorted.size - 1)) / 1_000_000.0,
            maxDeltaMs = maxDeltaNs.toDouble() / 1_000_000.0,
        )
    }

    private fun TimestampMetrics.toJson(): JSONObject = json(
        "frames" to frames,
        "fps" to fps,
        "gaps" to gaps,
        "meanDeltaMs" to meanDeltaMs,
        "maxDeltaMs" to maxDeltaMs,
    )

    // -------------------------------------------------------------------------
    // Probe 1: 4K60 Camera2 Input / Consumer Cadence Isolation
    // -------------------------------------------------------------------------
    fun runProbe1_4k60CameraInputIsolation(
        context: Context,
        outDir: File,
        checkBudget: () -> Unit = {},
    ): JSONObject {
        val arms = JSONArray()
        val armSpecs = listOf(
            "1A_surfaceTexture_baseline",
            "1B_imageReader_gpuSampled",
            "1C_imageReader_gpuAndVideoEncode",
            "1D_imageReader_videoEncodeOnly",
            "1E_surfaceTexture_scene28",
            "1F_surfaceTexture_scene31",
            "1G_surfaceTexture_plus360pVideoEncode",
        )
        for (spec in armSpecs) {
            val result = try {
                val temp = ensureCoolOrWait(context, checkBudget)
                runProbe1Arm(context, spec).put("batteryStartC", temp)
            } catch (t: Throwable) {
                json(
                    "arm" to spec,
                    "ok" to false,
                    "errorClass" to t.javaClass.name,
                    "error" to (t.message ?: t.toString()).take(300),
                )
            }
            arms.put(result)
        }
        return saveProbeJson(outDir, "probe1_4k60_input.json", json(
            "probe" to "Probe1_4K60_CameraInputIsolation",
            "batteryEndC" to batteryTempC(context),
            "arms" to arms,
        ))
    }

    @SuppressLint("MissingPermission")
    private fun runProbe1Arm(context: Context, spec: String): JSONObject {
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        lease.ready().get(8, TimeUnit.SECONDS)
        val manager = context.getSystemService(CameraManager::class.java)
        val caps = manager.getCameraCharacteristics("0")
        val sceneReqKey = findVendorRequestKey(caps, "cameraSessionSceneMode")
        val sceneResKey = findVendorResultKey(caps, "cameraSessionSceneMode")
        val sceneTypeHint = vendorKeyTypeHint(sceneReqKey)

        val camThread = HandlerThread("p1-cam").apply { start() }
        val camHandler = Handler(camThread.looper)
        val executor = Executor { r -> camHandler.post(r) }

        val eglThread = HandlerThread("p1-gl").apply { start() }
        val eglHandler = Handler(eglThread.looper)
        var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var texId = 0
        var st: SurfaceTexture? = null
        var stSurface: Surface? = null
        var primaryReader: ImageReader? = null
        var companion360Reader: ImageReader? = null

        val opened = CompletableFuture<CameraDevice>()
        val closed = CompletableFuture<Unit>()
        val configured = CompletableFuture<CameraCaptureSession>()
        val retiring = AtomicBoolean(false)

        val camTimestamps = mutableListOf<Long>()
        val consumerTimestamps = mutableListOf<Long>()
        val companionTimestamps = mutableListOf<Long>()
        val hbFormat = AtomicInteger(-1)
        val hbUsage = AtomicLong(-1L)
        val observedSceneResult = AtomicReference<String?>(null)
        val sceneAppliedFormat = AtomicReference<String?>(null)

        var session: CameraCaptureSession? = null
        var openSubmitted = false
        return try {
            val useSurfaceTexture = spec.contains("surfaceTexture")
            val targets = mutableListOf<Surface>()
            if (useSurfaceTexture) {
                val initFuture = CompletableFuture<Unit>()
                eglHandler.post {
                    try {
                        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                        val ver = IntArray(2)
                        check(EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) { "eglInitialize failed" }
                        val cfgs = arrayOfNulls<EGLConfig>(1)
                        val num = IntArray(1)
                        val attr = intArrayOf(
                            EGL14.EGL_RED_SIZE, 8,
                            EGL14.EGL_GREEN_SIZE, 8,
                            EGL14.EGL_BLUE_SIZE, 8,
                            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                            EGL14.EGL_NONE,
                        )
                        EGL14.eglChooseConfig(eglDisplay, attr, 0, cfgs, 0, 1, num, 0)
                        eglContext = EGL14.eglCreateContext(
                            eglDisplay, requireNotNull(cfgs[0]), EGL14.EGL_NO_CONTEXT,
                            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
                        )
                        eglSurface = EGL14.eglCreatePbufferSurface(
                            eglDisplay, cfgs[0],
                            intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0,
                        )
                        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }
                        val texs = IntArray(1)
                        GLES20.glGenTextures(1, texs, 0)
                        texId = texs[0]
                        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
                        st = SurfaceTexture(texId).apply {
                            setDefaultBufferSize(3840, 2160)
                            setOnFrameAvailableListener({ tex ->
                                runCatching {
                                    tex.updateTexImage()
                                    val ts = tex.timestamp
                                    synchronized(consumerTimestamps) { consumerTimestamps += ts }
                                }
                            }, eglHandler)
                        }
                        stSurface = Surface(st)
                        initFuture.complete(Unit)
                    } catch (t: Throwable) {
                        initFuture.completeExceptionally(t)
                    }
                }
                initFuture.get(5, TimeUnit.SECONDS)
                targets += requireNotNull(stSurface)
            } else {
                val usage = when (spec) {
                    "1B_imageReader_gpuSampled" -> HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                    "1C_imageReader_gpuAndVideoEncode" -> HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_VIDEO_ENCODE
                    else -> HardwareBuffer.USAGE_VIDEO_ENCODE
                }
                primaryReader = createUsageImageReader(3840, 2160, ImageFormat.PRIVATE, 6, usage).apply {
                    setOnImageAvailableListener({ r ->
                        runCatching {
                            r.acquireNextImage()?.use { img ->
                                synchronized(consumerTimestamps) { consumerTimestamps += img.timestamp }
                                if (hbFormat.get() == -1) {
                                    img.hardwareBuffer?.let { hb ->
                                        try {
                                            hbFormat.compareAndSet(-1, hb.format)
                                            hbUsage.compareAndSet(-1L, hb.usage)
                                        } finally {
                                            hb.close()
                                        }
                                    }
                                }
                            }
                        }
                    }, camHandler)
                }
                targets += primaryReader.surface
            }

            if (spec == "1G_surfaceTexture_plus360pVideoEncode") {
                companion360Reader = createUsageImageReader(640, 360, ImageFormat.PRIVATE, 6, HardwareBuffer.USAGE_VIDEO_ENCODE).apply {
                    setOnImageAvailableListener({ r ->
                        runCatching {
                            r.acquireNextImage()?.use { img ->
                                synchronized(companionTimestamps) { companionTimestamps += img.timestamp }
                                if (hbFormat.get() == -1) {
                                    img.hardwareBuffer?.let { hb ->
                                        try {
                                            hbFormat.compareAndSet(-1, hb.format)
                                            hbUsage.compareAndSet(-1L, hb.usage)
                                        } finally {
                                            hb.close()
                                        }
                                    }
                                }
                            }
                        }
                    }, camHandler)
                }
                targets += companion360Reader.surface
            }

            manager.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    opened.complete(camera)
                    if (retiring.get()) camera.close()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    opened.completeExceptionally(IllegalStateException("Disconnected"))
                    camera.close()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    opened.completeExceptionally(IllegalStateException("Error $error"))
                    camera.close()
                }
                override fun onClosed(camera: CameraDevice) { closed.complete(Unit) }
            }, camHandler)
            openSubmitted = true
            val camera = opened.get(6, TimeUnit.SECONDS)

            fun applyArmKeys(builder: CaptureRequest.Builder) {
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(60, 60))
                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                val sceneVal = when (spec) {
                    "1E_surfaceTexture_scene28" -> 28
                    "1F_surfaceTexture_scene31" -> 31
                    else -> null
                }
                if (sceneVal != null && sceneReqKey != null) {
                    sceneAppliedFormat.compareAndSet(null, setVendorByteOrIntKey(builder, sceneReqKey, sceneVal, sceneTypeHint))
                }
            }

            val reqBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                targets.forEach { addTarget(it) }
                applyArmKeys(this)
            }
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                targets.map { OutputConfiguration(it) },
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (retiring.get()) s.close() else configured.complete(s)
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        configured.completeExceptionally(IllegalStateException("Configure failed for $spec"))
                        s.close()
                    }
                },
            ).apply {
                sessionParameters = reqBuilder.build()
            }
            camera.createCaptureSession(sessionConfig)
            session = configured.get(6, TimeUnit.SECONDS)

            val recordReq = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                targets.forEach { addTarget(it) }
                applyArmKeys(this)
            }.build()
            session.setRepeatingRequest(recordReq, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, res: TotalCaptureResult) {
                    res.get(CaptureResult.SENSOR_TIMESTAMP)?.let { ts ->
                        synchronized(camTimestamps) { camTimestamps += ts }
                    }
                    if (observedSceneResult.get() == null) {
                        readResultKeyValue(res, sceneResKey)?.let { observedSceneResult.compareAndSet(null, it) }
                    }
                }
            }, camHandler)

            Thread.sleep(3_000L)
            runCatching { session.stopRepeating() }

            val camMetrics = synchronized(camTimestamps) { computeTimestampMetrics(camTimestamps.toList()) }
            val consMetrics = synchronized(consumerTimestamps) { computeTimestampMetrics(consumerTimestamps.toList()) }
            val compMetrics = synchronized(companionTimestamps) { computeTimestampMetrics(companionTimestamps.toList()) }
            json(
                "arm" to spec,
                "ok" to (camMetrics.frames > 10 && consMetrics.frames > 10),
                "camCb" to camMetrics.toJson(),
                "consumer" to consMetrics.toJson(),
                "companion360" to if (spec == "1G_surfaceTexture_plus360pVideoEncode") compMetrics.toJson() else JSONObject.NULL,
                "hwBufferFormat" to hbFormat.get().takeIf { it != -1 },
                "hwBufferFormatHex" to hbFormat.get().takeIf { it != -1 }?.let { "0x${it.toString(16)}" },
                "hwBufferUsageHex" to hbUsage.get().takeIf { it != -1L }?.let { "0x${it.toString(16)}" },
                "sceneKeyPresent" to (sceneReqKey != null),
                "sceneAppliedFormat" to sceneAppliedFormat.get(),
                "observedSceneResult" to observedSceneResult.get(),
            )
        } finally {
            retiring.set(true)
            val camClosed = closed.thenApply { Unit }
            lease.releaseAfter(camClosed)
            if (openSubmitted) {
                camHandler.post {
                    try { session?.close() } finally {
                        if (!opened.isCompletedExceptionally) opened.getNow(null)?.close()
                    }
                }
            } else {
                closed.complete(Unit)
            }
            runCatching { camClosed.get(6, TimeUnit.SECONDS) }
            runCatching { primaryReader?.close() }
            runCatching { companion360Reader?.close() }
            if (useSurfaceTextureGlResourcesNeeded(stSurface, st, eglDisplay)) {
                val glDone = CompletableFuture<Unit>()
                eglHandler.post {
                    runCatching { stSurface?.release() }
                    runCatching { st?.release() }
                    runCatching {
                        if (texId != 0) GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
                        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                            EGL14.eglTerminate(eglDisplay)
                        }
                    }
                    glDone.complete(Unit)
                }
                runCatching { glDone.get(4, TimeUnit.SECONDS) }
            }
            camThread.quitSafely()
            eglThread.quitSafely()
            runCatching { camThread.join(4_000) }
            runCatching { eglThread.join(4_000) }
        }
    }

    private fun useSurfaceTextureGlResourcesNeeded(s: Surface?, st: SurfaceTexture?, d: EGLDisplay): Boolean =
        s != null || st != null || d != EGL14.EGL_NO_DISPLAY

    // -------------------------------------------------------------------------
    // Probe 2: CUVA HDR Vivid Direct 4K30/4K60 & GPU 4K30 Video Clips
    // -------------------------------------------------------------------------
    fun runProbe2_CuvaHdrDirectAndGpuRecording(
        context: Context,
        outDir: File,
        checkBudget: () -> Unit = {},
    ): JSONObject {
        outDir.mkdirs()
        val arms = JSONArray()
        val lockedAe = runCatching { warmupAndLockExposure(context) }.getOrNull()
        val armSpecs = listOf(
            "2A_direct_4k30_cuva_off" to "cuva_direct_4k30_off.mp4",
            "2B_direct_4k30_cuva_on" to "cuva_direct_4k30_on.mp4",
            "2C1_direct_4k60_cuva_on_2target" to "cuva_direct_4k60_2target.mp4",
            "2C2_direct_4k60_cuva_on_with_priv_vid" to "cuva_direct_4k60_priv_vid.mp4",
            "2D_gpu_4k30_cuva_off" to "cuva_gpu_4k30_off.mp4",
            "2D_gpu_4k30_cuva_on" to "cuva_gpu_4k30_on.mp4",
        )
        for ((armName, fileName) in armSpecs) {
            val res = try {
                val temp = ensureCoolOrWait(context, checkBudget)
                val outFile = File(outDir, fileName)
                if (outFile.exists()) outFile.delete()
                val jsonObj = when {
                    armName.startsWith("2D_gpu") -> {
                        val cuvaOn = armName.endsWith("cuva_on")
                        runProbe2GpuArm(context, armName, outFile, cuvaOn, lockedAe)
                    }
                    armName == "2C1_direct_4k60_cuva_on_2target" -> {
                        runProbe2DirectArm(context, armName, outFile, 60, cuvaOn = true, lockedAe = null, includeCompanionReader = false, companionUsage = null)
                    }
                    armName == "2C2_direct_4k60_cuva_on_with_priv_vid" -> {
                        runProbe2DirectArm(context, armName, outFile, 60, cuvaOn = true, lockedAe = null, includeCompanionReader = true, companionUsage = HardwareBuffer.USAGE_VIDEO_ENCODE)
                    }
                    else -> {
                        val fps = if (armName.contains("4k60")) 60 else 30
                        val cuvaOn = armName.endsWith("cuva_on")
                        runProbe2DirectArm(context, armName, outFile, fps, cuvaOn, lockedAe, includeCompanionReader = true, companionUsage = null)
                    }
                }
                jsonObj.put("batteryStartC", temp)
            } catch (t: Throwable) {
                json(
                    "arm" to armName,
                    "ok" to false,
                    "errorClass" to t.javaClass.name,
                    "error" to (t.message ?: t.toString()).take(300),
                )
            }
            arms.put(res)
        }
        return saveProbeJson(outDir, "probe2_cuva_hdr.json", json(
            "probe" to "Probe2_CuvaHdrDirectAndGpuRecording",
            "lockedAe" to (lockedAe?.let { json("exposureNs" to it.first, "iso" to it.second) } ?: JSONObject.NULL),
            "batteryEndC" to batteryTempC(context),
            "arms" to arms,
        ))
    }

    @SuppressLint("MissingPermission")
    private fun warmupAndLockExposure(context: Context): Pair<Long, Int>? {
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        lease.ready().get(8, TimeUnit.SECONDS)
        val manager = context.getSystemService(CameraManager::class.java)
        val thread = HandlerThread("p2-warmup").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 3)
        val opened = CompletableFuture<CameraDevice>()
        val closed = CompletableFuture<Unit>()
        val configured = CompletableFuture<CameraCaptureSession>()
        val retiring = AtomicBoolean()
        val exp = AtomicLong(0L)
        val iso = AtomicInteger(0)
        val settled = CountDownLatch(12)
        reader.setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage()?.close() } }, handler)
        var session: CameraCaptureSession? = null
        var openSubmitted = false
        return try {
            manager.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { opened.complete(c); if (retiring.get()) c.close() }
                override fun onDisconnected(c: CameraDevice) { opened.completeExceptionally(IllegalStateException("Disconnected")); c.close() }
                override fun onError(c: CameraDevice, e: Int) { opened.completeExceptionally(IllegalStateException("Err $e")); c.close() }
                override fun onClosed(c: CameraDevice) { closed.complete(Unit) }
            }, handler)
            openSubmitted = true
            val cam = opened.get(6, TimeUnit.SECONDS)
            @Suppress("DEPRECATION")
            cam.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { if (retiring.get()) s.close() else configured.complete(s) }
                override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("Configure failed")); s.close() }
            }, handler)
            session = configured.get(6, TimeUnit.SECONDS)
            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(reader.surface) }.build()
            session.setRepeatingRequest(req, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                    res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { if (it > 0) exp.set(it) }
                    res.get(CaptureResult.SENSOR_SENSITIVITY)?.let { if (it > 0) iso.set(it) }
                    settled.countDown()
                }
            }, handler)
            settled.await(3, TimeUnit.SECONDS)
            runCatching { session.stopRepeating() }
            val e = exp.get()
            val s = iso.get()
            if (e > 0L && s > 0) e to s else null
        } finally {
            retiring.set(true)
            val cc = closed.thenApply { Unit }
            lease.releaseAfter(cc)
            if (openSubmitted) {
                handler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } }
            } else closed.complete(Unit)
            runCatching { cc.get(6, TimeUnit.SECONDS) }
            runCatching { reader.close() }
            thread.quitSafely()
            runCatching { thread.join(4_000) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun runProbe2DirectArm(
        context: Context,
        armName: String,
        outFile: File,
        fps: Int,
        cuvaOn: Boolean,
        lockedAe: Pair<Long, Int>?,
        includeCompanionReader: Boolean = true,
        companionUsage: Long? = null,
    ): JSONObject {
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        lease.ready().get(8, TimeUnit.SECONDS)
        val manager = context.getSystemService(CameraManager::class.java)
        val caps = manager.getCameraCharacteristics("0")
        val cuvaReqKey = findVendorRequestKey(caps, "cuvaStateVideoMode")
        val cuvaResKey = findVendorResultKey(caps, "cuvaStateVideoMode")
        val cuvaHint = vendorKeyTypeHint(cuvaReqKey)
        val aeModes = caps.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.toSet().orEmpty()

        val codecName = selectHevcEncoderName()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 3840, 2160).apply {
            setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_LEVEL, CodecProfileLevel.HEVCMainTierLevel62)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, if (fps >= 60) 80_000_000 else 60_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
        }
        val codec = MediaCodec.createByCodecName(codecName)
        var inputSurface: Surface? = null
        var muxer: MediaMuxer? = null
        var codecStarted = false
        var muxerStarted = false
        var drainThread: Thread? = null
        val csd0Ref = AtomicReference<ByteArray?>(null)
        val encodedSamples = AtomicInteger(0)
        val camTimestamps = mutableListOf<Long>()
        val companionHbFormat = AtomicInteger(-1)
        val companionHbUsage = AtomicLong(-1L)
        val cuvaAppliedFmt = AtomicReference<String?>(null)
        val cuvaObservedRes = AtomicReference<String?>(null)

        val camThread = HandlerThread("p2-cam").apply { start() }
        val camHandler = Handler(camThread.looper)
        val executor = Executor { r -> camHandler.post(r) }
        val stThread = HandlerThread("p2-st").apply { start() }
        val stHandler = Handler(stThread.looper)

        val companionReader = if (includeCompanionReader) {
            createUsageImageReader(1280, 720, ImageFormat.PRIVATE, 6, companionUsage).apply {
                setOnImageAvailableListener({ r ->
                    runCatching {
                        r.acquireLatestImage()?.use { img ->
                            if (companionHbFormat.get() == -1) {
                                img.hardwareBuffer?.let { hb ->
                                    try {
                                        companionHbFormat.compareAndSet(-1, hb.format)
                                        companionHbUsage.compareAndSet(-1L, hb.usage)
                                    } finally {
                                        hb.close()
                                    }
                                }
                            }
                        }
                    }
                }, camHandler)
            }
        } else null

        // Companion dummy GL SurfaceTexture when running 4K60 direct CUVA to match production 4K60 dual-stream recipe
        var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var texId = 0
        var previewSt: SurfaceTexture? = null
        var previewStSurface: Surface? = null

        val opened = CompletableFuture<CameraDevice>()
        val closed = CompletableFuture<Unit>()
        val configured = CompletableFuture<CameraCaptureSession>()
        val retiring = AtomicBoolean()
        var session: CameraCaptureSession? = null
        var openSubmitted = false

        return try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.start()
            codecStarted = true
            val drainStop = AtomicBoolean(false)
            drainThread = Thread({
                val info = MediaCodec.BufferInfo()
                var trackIdx = -1
                while (!drainStop.get()) {
                    val outIdx = runCatching { codec.dequeueOutputBuffer(info, 10_000L) }.getOrDefault(-99)
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val of = codec.outputFormat
                        of.getByteBuffer("csd-0")?.let { bb ->
                            val copy = ByteArray(bb.remaining())
                            bb.duplicate().get(copy)
                            csd0Ref.compareAndSet(null, copy)
                        }
                        if (!muxerStarted) {
                            trackIdx = muxer.addTrack(of)
                            muxer.start()
                            muxerStarted = true
                        }
                    } else if (outIdx >= 0) {
                        val buf = codec.getOutputBuffer(outIdx)
                        if (buf != null && info.size > 0 && muxerStarted && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIdx, buf, info)
                            encodedSamples.incrementAndGet()
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    } else if (outIdx == -99) {
                        break
                    }
                }
            }, "p2-drain").apply { start() }

            if (fps >= 60) {
                val initF = CompletableFuture<Unit>()
                stHandler.post {
                    runCatching {
                        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                        val v = IntArray(2)
                        EGL14.eglInitialize(eglDisplay, v, 0, v, 1)
                        val cfgs = arrayOfNulls<EGLConfig>(1)
                        val num = IntArray(1)
                        EGL14.eglChooseConfig(
                            eglDisplay,
                            intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE),
                            0, cfgs, 0, 1, num, 0,
                        )
                        eglContext = EGL14.eglCreateContext(eglDisplay, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
                        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
                        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); texId = t[0]
                        previewSt = SurfaceTexture(texId).apply {
                            setDefaultBufferSize(640, 360)
                            setOnFrameAvailableListener({ st -> runCatching { st.updateTexImage() } }, stHandler)
                        }
                        previewStSurface = Surface(previewSt)
                    }.onSuccess { initF.complete(Unit) }.onFailure { initF.completeExceptionally(it) }
                }
                initF.get(5, TimeUnit.SECONDS)
            }

            val targets = buildList {
                add(requireNotNull(inputSurface))
                companionReader?.surface?.let { add(it) }
                previewStSurface?.let { add(it) }
            }

            manager.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { opened.complete(c); if (retiring.get()) c.close() }
                override fun onDisconnected(c: CameraDevice) { opened.completeExceptionally(IllegalStateException("Disconnected")); c.close() }
                override fun onError(c: CameraDevice, e: Int) { opened.completeExceptionally(IllegalStateException("Error $e")); c.close() }
                override fun onClosed(c: CameraDevice) { closed.complete(Unit) }
            }, camHandler)
            openSubmitted = true
            val cam = opened.get(6, TimeUnit.SECONDS)

            fun applyKeys(builder: CaptureRequest.Builder) {
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                if (lockedAe != null && CaptureRequest.CONTROL_AE_MODE_OFF in aeModes) {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, lockedAe.first)
                    builder.set(CaptureRequest.SENSOR_SENSITIVITY, lockedAe.second)
                } else {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }
                if (cuvaOn && cuvaReqKey != null) {
                    cuvaAppliedFmt.compareAndSet(null, setVendorByteOrIntKey(builder, cuvaReqKey, 1, cuvaHint))
                }
            }

            val sessReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                targets.forEach { addTarget(it) }
                applyKeys(this)
            }.build()
            cam.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    targets.map { OutputConfiguration(it) },
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { if (retiring.get()) s.close() else configured.complete(s) }
                        override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("Configure failed")); s.close() }
                    },
                ).apply { sessionParameters = sessReq },
            )
            session = configured.get(6, TimeUnit.SECONDS)

            val repReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                targets.forEach { addTarget(it) }
                applyKeys(this)
            }.build()
            session.setRepeatingRequest(repReq, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, res: TotalCaptureResult) {
                    res.get(CaptureResult.SENSOR_TIMESTAMP)?.let { synchronized(camTimestamps) { camTimestamps += it } }
                    if (cuvaObservedRes.get() == null) {
                        readResultKeyValue(res, cuvaResKey)?.let { cuvaObservedRes.compareAndSet(null, it) }
                    }
                }
            }, camHandler)

            Thread.sleep(3_000L)
            runCatching { session.stopRepeating() }
            runCatching { codec.signalEndOfInputStream() }
            drainThread.join(4_000L)
            drainStop.set(true)
            drainThread.join(1_000L)
            if (muxerStarted) runCatching { muxer.stop() }
            muxerStarted = false

            val camMetrics = synchronized(camTimestamps) { computeTimestampMetrics(camTimestamps.toList(), if (fps >= 60) 25_000_000L else 45_000_000L) }
            val fileMetrics = inspectWrittenMp4(outFile)
            val sps = csd0Ref.get()?.let { HevcSpsInspector.inspect(it) }
            json(
                "arm" to armName,
                "ok" to (encodedSamples.get() > 10 && outFile.length() > 1024L),
                "clip" to outFile.name,
                "clipBytes" to outFile.length(),
                "codecName" to codecName,
                "cuvaRequested" to cuvaOn,
                "cuvaAppliedFmt" to cuvaAppliedFmt.get(),
                "cuvaObservedRes" to cuvaObservedRes.get(),
                "companionHwBufferFormat" to companionHbFormat.get().takeIf { it != -1 },
                "companionHwBufferFormatHex" to companionHbFormat.get().takeIf { it != -1 }?.let { "0x${it.toString(16)}" },
                "companionHwBufferUsageHex" to companionHbUsage.get().takeIf { it != -1L }?.let { "0x${it.toString(16)}" },
                "camCb" to camMetrics.toJson(),
                "encodedSamples" to encodedSamples.get(),
                "spsBitDepthLuma" to sps?.bitDepthLuma,
                "spsColourPrimaries" to sps?.colourPrimaries,
                "spsTransfer" to sps?.transferCharacteristics,
                "fileStats" to fileMetrics,
            )
        } finally {
            retiring.set(true)
            val cc = closed.thenApply { Unit }
            lease.releaseAfter(cc)
            if (openSubmitted) {
                camHandler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } }
            } else closed.complete(Unit)
            runCatching { cc.get(6, TimeUnit.SECONDS) }
            if (codecStarted) runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { inputSurface?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { companionReader?.close() }
            if (previewSt != null || previewStSurface != null || eglDisplay != EGL14.EGL_NO_DISPLAY) {
                val glDone = CompletableFuture<Unit>()
                stHandler.post {
                    runCatching { previewStSurface?.release() }
                    runCatching { previewSt?.release() }
                    if (texId != 0) runCatching { GLES20.glDeleteTextures(1, intArrayOf(texId), 0) }
                    if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                        runCatching {
                            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                            EGL14.eglTerminate(eglDisplay)
                        }
                    }
                    glDone.complete(Unit)
                }
                runCatching { glDone.get(4, TimeUnit.SECONDS) }
            }
            camThread.quitSafely()
            stThread.quitSafely()
            runCatching { camThread.join(4_000) }
            runCatching { stThread.join(4_000) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun runProbe2GpuArm(
        context: Context,
        armName: String,
        outFile: File,
        cuvaOn: Boolean,
        lockedAe: Pair<Long, Int>?,
    ): JSONObject {
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        lease.ready().get(8, TimeUnit.SECONDS)
        val manager = context.getSystemService(CameraManager::class.java)
        val caps = manager.getCameraCharacteristics("0")
        val cuvaReqKey = findVendorRequestKey(caps, "cuvaStateVideoMode")
        val cuvaResKey = findVendorResultKey(caps, "cuvaStateVideoMode")
        val cuvaHint = vendorKeyTypeHint(cuvaReqKey)
        val aeModes = caps.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.toSet().orEmpty()
        val sensorOrientation = caps.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        val failureMsg = AtomicReference<String?>(null)
        val refusedMsg = AtomicReference<String?>(null)
        val fallbackMsg = AtomicReference<String?>(null)
        val sourcePath = if (cuvaOn) OpenCineLogSourcePath.HLG10_BT2020 else OpenCineLogSourcePath.SDR_BT709_ISP
        val pipeline = OpenCineLogGpuPipeline(
            size = Size(3840, 2160),
            sourcePath = sourcePath,
            preview = null,
            sensorOrientationDegrees = sensorOrientation,
            displayRotationDegrees = 0,
            frontFacing = false,
            viewAssist = false,
            targetFps = 30,
            passthroughSdr = false,
            appContext = context.applicationContext,
            researchEncoderOverride = ResearchEncoderOverride(
                explicitHevcLevel = HevcEncoderLevels.MAIN_TIER_62,
                colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                colorRange = MediaFormat.COLOR_RANGE_FULL,
                allowAvcFallback = false,
                enableFrameDiagnostics = true,
            ),
            onFailure = { code, msg -> failureMsg.compareAndSet(null, "$code: $msg") },
        )

        val camThread = HandlerThread("p2-gpu-cam").apply { start() }
        val camHandler = Handler(camThread.looper)
        val executor = Executor { r -> camHandler.post(r) }
        val companionReader = ImageReader.newInstance(1280, 720, ImageFormat.PRIVATE, 3)
        val companionHbFormat = AtomicInteger(-1)
        companionReader.setOnImageAvailableListener({ r ->
            runCatching {
                r.acquireLatestImage()?.use { img ->
                    if (companionHbFormat.get() == -1) {
                        img.hardwareBuffer?.let { hb ->
                            try { companionHbFormat.compareAndSet(-1, hb.format) } finally { hb.close() }
                        }
                    }
                }
            }
        }, camHandler)

        val opened = CompletableFuture<CameraDevice>()
        val closed = CompletableFuture<Unit>()
        val configured = CompletableFuture<CameraCaptureSession>()
        val retiring = AtomicBoolean()
        val cuvaAppliedFmt = AtomicReference<String?>(null)
        val cuvaObservedRes = AtomicReference<String?>(null)
        val started = CountDownLatch(1)
        val stopped = CompletableFuture<Pair<Boolean, OpenCineLogRecordingEvidence?>>()
        var session: CameraCaptureSession? = null
        var openSubmitted = false
        var pfd: ParcelFileDescriptor? = null

        return try {
            val targets = listOf(pipeline.cameraInputSurface, companionReader.surface)
            manager.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { opened.complete(c); if (retiring.get()) c.close() }
                override fun onDisconnected(c: CameraDevice) { opened.completeExceptionally(IllegalStateException("Disconnected")); c.close() }
                override fun onError(c: CameraDevice, e: Int) { opened.completeExceptionally(IllegalStateException("Error $e")); c.close() }
                override fun onClosed(c: CameraDevice) { closed.complete(Unit) }
            }, camHandler)
            openSubmitted = true
            val cam = opened.get(6, TimeUnit.SECONDS)

            fun applyKeys(b: CaptureRequest.Builder) {
                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                if (lockedAe != null && CaptureRequest.CONTROL_AE_MODE_OFF in aeModes) {
                    b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, lockedAe.first)
                    b.set(CaptureRequest.SENSOR_SENSITIVITY, lockedAe.second)
                } else {
                    b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }
                if (cuvaOn && cuvaReqKey != null) {
                    cuvaAppliedFmt.compareAndSet(null, setVendorByteOrIntKey(b, cuvaReqKey, 1, cuvaHint))
                }
            }
            val sessReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { targets.forEach(::addTarget); applyKeys(this) }.build()
            cam.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    targets.map { OutputConfiguration(it) },
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { if (retiring.get()) s.close() else configured.complete(s) }
                        override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("Configure failed")); s.close() }
                    },
                ).apply { sessionParameters = sessReq },
            )
            session = configured.get(6, TimeUnit.SECONDS)
            val repReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { targets.forEach(::addTarget); applyKeys(this) }.build()
            session.setRepeatingRequest(repReq, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, res: TotalCaptureResult) {
                    if (cuvaObservedRes.get() == null) readResultKeyValue(res, cuvaResKey)?.let { cuvaObservedRes.compareAndSet(null, it) }
                }
            }, camHandler)

            Thread.sleep(600L)
            pfd = ParcelFileDescriptor.open(outFile, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
            val geometry = RecordingGeometryCalculator.calculate(
                sourceSize = Size(3840, 2160),
                sensorOrientationDegrees = sensorOrientation,
                deviceOrientationDegrees = 0,
                lensFacing = CameraCharacteristics.LENS_FACING_BACK,
                mode = RecordingGeometryMode.NATIVE_RASTER,
            )
            val recCalled = pipeline.startRecording(
                output = pfd,
                bitrate = 60_000_000,
                geometry = geometry,
                onStarted = { started.countDown() },
                onStopped = { ok, ev -> stopped.complete(ok to ev) },
                onCodecFallback = { reason -> fallbackMsg.compareAndSet(null, reason) },
                onStartRefused = { code, msg -> refusedMsg.compareAndSet(null, "$code: $msg") },
                hlgOutput = false,
                hdr10Output = false,
            )
            val startedOk = recCalled && started.await(4, TimeUnit.SECONDS)
            if (startedOk) {
                Thread.sleep(2_800L)
                pipeline.stopRecording()
            }
            val stopPair = runCatching { stopped.get(8, TimeUnit.SECONDS) }.getOrNull()
            runCatching { pipeline.recordingFileRetirement().get(6, TimeUnit.SECONDS) }
            runCatching { session.stopRepeating() }

            val ev = stopPair?.second
            val fileMetrics = inspectWrittenMp4(outFile)
            val diag = pipeline.frameDiagnosticsSnapshot()
            json(
                "arm" to armName,
                "ok" to (startedOk && stopPair?.first == true && outFile.length() > 1024L && failureMsg.get() == null && refusedMsg.get() == null),
                "clip" to outFile.name,
                "clipBytes" to outFile.length(),
                "sourcePath" to sourcePath.name,
                "cuvaRequested" to cuvaOn,
                "cuvaAppliedFmt" to cuvaAppliedFmt.get(),
                "cuvaObservedRes" to cuvaObservedRes.get(),
                "companionHwBufferFormat" to companionHbFormat.get().takeIf { it != -1 },
                "companionHwBufferFormatHex" to companionHbFormat.get().takeIf { it != -1 }?.let { "0x${it.toString(16)}" },
                "recCalled" to recCalled,
                "startedOk" to startedOk,
                "stoppedOk" to stopPair?.first,
                "startRefused" to refusedMsg.get(),
                "codecFallback" to fallbackMsg.get(),
                "pipelineFailure" to failureMsg.get(),
                "encodedFrames" to ev?.encodedProgress?.frameCount,
                "sourceDataSpace" to ev?.sourceDataSpace,
                "dataSpaceMismatchedFrames" to ev?.sourceDataSpaceMismatchedFrames,
                "unexpectedSourceDataSpace" to ev?.unexpectedSourceDataSpace,
                "gpuRenderFrames" to diag?.totalFrames,
                "gpuRenderGaps" to diag?.totalGaps,
                "fileStats" to fileMetrics,
            )
        } finally {
            retiring.set(true)
            runCatching { pipeline.stopRecording() }
            val pipeClose = runCatching { pipeline.closeAsync() }.getOrElse { CompletableFuture.completedFuture(Unit) }
            val cc = closed.thenApply { Unit }
            val totalRetire = pipeClose.thenCombine(cc) { _, _ -> Unit }
            lease.releaseAfter(totalRetire)
            if (openSubmitted) {
                camHandler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } }
            } else closed.complete(Unit)
            runCatching { totalRetire.get(8, TimeUnit.SECONDS) }
            runCatching { companionReader.close() }
            runCatching { pfd?.close() }
            camThread.quitSafely()
            runCatching { camThread.join(4_000) }
        }
    }

    private fun inspectWrittenMp4(file: File): JSONObject {
        if (!file.isFile || file.length() < 64L) return json("valid" to false, "bytes" to file.length())
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var videoFormat: MediaFormat? = null
            var trackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    videoFormat = f
                    trackIndex = i
                    break
                }
            }
            if (trackIndex < 0 || videoFormat == null) return json("valid" to false)
            extractor.selectTrack(trackIndex)
            var count = 0
            var firstPts = -1L
            var lastPts = -1L
            while (true) {
                val pts = extractor.sampleTime
                if (pts < 0) break
                if (firstPts < 0) firstPts = pts
                lastPts = pts
                count++
                if (!extractor.advance()) break
            }
            val spanUs = (lastPts - firstPts).coerceAtLeast(1L)
            val obsFps = if (count > 1) ((count - 1) * 1_000_000.0) / spanUs.toDouble() else 0.0
            val csd0 = videoFormat.getByteBuffer("csd-0")?.let { bb ->
                ByteArray(bb.remaining()).also { bb.duplicate().get(it) }
            }
            val sps = csd0?.let { HevcSpsInspector.inspect(it) }
            val decodeStats = runCatching {
                val dUs = (lastPts - firstPts).coerceAtLeast(0L)
                val samples = if (dUs > 0L) listOf(firstPts, firstPts + dUs / 2, lastPts) else listOf(0L)
                inspectDecodedFrames(file, samples, syntheticQuadrants = false)
            }.getOrNull()
            json(
                "valid" to true,
                "mime" to videoFormat.getString(MediaFormat.KEY_MIME),
                "width" to videoFormat.getInteger(MediaFormat.KEY_WIDTH),
                "height" to videoFormat.getInteger(MediaFormat.KEY_HEIGHT),
                "sampleCount" to count,
                "durationSec" to spanUs / 1_000_000.0,
                "observedFps" to obsFps,
                "spsBitDepthLuma" to sps?.bitDepthLuma,
                "spsProfileIdc" to sps?.profileIdc,
                "spsLevelIdc" to sps?.levelIdc,
                "rgb8LumaMin" to decodeStats?.opt("rgb8LumaMin"),
                "rgb8LumaMax" to decodeStats?.opt("rgb8LumaMax"),
                "rgb8LumaMean" to decodeStats?.opt("rgb8LumaMean"),
            )
        } catch (t: Throwable) {
            json("valid" to false, "error" to t.message?.take(240))
        } finally {
            runCatching { extractor.release() }
        }
    }

    // -------------------------------------------------------------------------
    // Probe 3: Camera-Closed Synthetic 4K60 Throughput & EGL Priority
    // -------------------------------------------------------------------------
    fun runProbe3_Synthetic4k60ThroughputAndPriority(
        context: Context,
        outDir: File,
        checkBudget: () -> Unit = {},
    ): JSONObject {
        val arms = JSONArray()
        val configs = listOf(
            Triple("3A_main10_default", CodecProfileLevel.HEVCProfileMain10, false),
            Triple("3B_main10_high_priority", CodecProfileLevel.HEVCProfileMain10, true),
            Triple("3C_main8_high_priority", CodecProfileLevel.HEVCProfileMain, true),
        )
        for ((armName, profile, highPriority) in configs) {
            val res = try {
                val temp = ensureCoolOrWait(context, checkBudget)
                runProbe3Arm(armName, profile, highPriority).put("batteryStartC", temp)
            } catch (t: Throwable) {
                json(
                    "arm" to armName,
                    "ok" to false,
                    "errorClass" to t.javaClass.name,
                    "error" to (t.message ?: t.toString()).take(300),
                )
            }
            arms.put(res)
        }
        return saveProbeJson(outDir, "probe3_synth_4k60_priority.json", json(
            "probe" to "Probe3_Synthetic4K60ThroughputAndPriority",
            "batteryEndC" to batteryTempC(context),
            "arms" to arms,
        ))
    }

    private fun runProbe3Arm(armName: String, profile: Int, highPriority: Boolean): JSONObject {
        val codecName = selectHevcEncoderName()
        val level = if (profile == CodecProfileLevel.HEVCProfileMain10) {
            CodecProfileLevel.HEVCMainTierLevel62
        } else {
            CodecProfileLevel.HEVCMainTierLevel52
        }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 3840, 2160).apply {
            setInteger(MediaFormat.KEY_PROFILE, profile)
            setInteger(MediaFormat.KEY_LEVEL, level)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 80_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            if (highPriority) {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_OPERATING_RATE, 60)
            }
        }
        val codec = MediaCodec.createByCodecName(codecName)
        var inputSurface: Surface? = null
        var codecStarted = false
        var drainThread: Thread? = null
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var eglCtx: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurf: EGLSurface = EGL14.EGL_NO_SURFACE
        val encodedCount = AtomicInteger(0)
        val totalOutputBytes = AtomicLong(0L)
        val totalFrames = 240

        return try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
            codecStarted = true

            val drainDone = AtomicBoolean(false)
            drainThread = Thread({
                val info = MediaCodec.BufferInfo()
                while (!drainDone.get()) {
                    val outIdx = runCatching { codec.dequeueOutputBuffer(info, 10_000L) }.getOrDefault(-99)
                    if (outIdx >= 0) {
                        if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            encodedCount.incrementAndGet()
                            totalOutputBytes.addAndGet(info.size.toLong())
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    } else if (outIdx == -99) {
                        break
                    }
                }
            }, "p3-drain").apply { start() }

            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val v = IntArray(2)
            check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize failed" }
            val cfgAttr = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            )
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            check(EGL14.eglChooseConfig(display, cfgAttr, 0, cfgs, 0, 1, num, 0) && cfgs[0] != null) { "eglChooseConfig failed" }

            var priorityAttrAccepted = false
            if (highPriority) {
                eglCtx = EGL14.eglCreateContext(
                    display, cfgs[0], EGL14.EGL_NO_CONTEXT,
                    intArrayOf(
                        EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                        EGL_CONTEXT_PRIORITY_LEVEL_IMG, EGL_CONTEXT_PRIORITY_HIGH_IMG,
                        EGL14.EGL_NONE,
                    ),
                    0,
                )
                priorityAttrAccepted = eglCtx != EGL14.EGL_NO_CONTEXT
            }
            if (eglCtx == EGL14.EGL_NO_CONTEXT) {
                eglCtx = EGL14.eglCreateContext(
                    display, cfgs[0], EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
                )
            }
            check(eglCtx != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
            val queriedPrio = IntArray(1)
            val queryOk = EGL14.eglQueryContext(display, eglCtx, EGL_CONTEXT_PRIORITY_LEVEL_IMG, queriedPrio, 0)

            eglSurf = EGL14.eglCreateWindowSurface(display, cfgs[0], inputSurface, intArrayOf(EGL14.EGL_NONE), 0)
            check(eglSurf != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
            check(EGL14.eglMakeCurrent(display, eglSurf, eglSurf, eglCtx)) { "eglMakeCurrent failed" }

            val frameDurationsNs = LongArray(totalFrames)
            val stepNs = 16_666_667L
            val t0 = System.nanoTime()
            for (i in 0 until totalFrames) {
                val fs = System.nanoTime()
                val frac = (i % 60) / 60.0f
                GLES20.glViewport(0, 0, 3840, 2160)
                GLES20.glClearColor(frac, 0.25f, 1.0f - frac, 1.0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                EGLExt.eglPresentationTimeANDROID(display, eglSurf, i * stepNs)
                EGL14.eglSwapBuffers(display, eglSurf)
                frameDurationsNs[i] = System.nanoTime() - fs
            }
            val wallNs = System.nanoTime() - t0
            runCatching { codec.signalEndOfInputStream() }
            drainThread.join(4_000L)
            drainDone.set(true)
            drainThread.join(1_000L)

            val sortedMs = frameDurationsNs.map { it / 1_000_000.0 }.sorted()
            val p50 = sortedMs[sortedMs.size / 2]
            val p95 = sortedMs[(sortedMs.size * 95 / 100).coerceAtMost(sortedMs.lastIndex)]
            val maxMs = sortedMs.lastOrNull() ?: 0.0
            val wallMs = wallNs / 1_000_000.0
            val effectiveFps = (totalFrames * 1_000.0) / wallMs.coerceAtLeast(1.0)
            json(
                "arm" to armName,
                "ok" to (encodedCount.get() >= totalFrames - 5),
                "codecName" to codecName,
                "profile" to profile,
                "level" to level,
                "highPriorityRequested" to highPriority,
                "priorityAttrAccepted" to priorityAttrAccepted,
                "eglQueryContextOk" to queryOk,
                "eglQueryPriorityHex" to if (queryOk) "0x${queriedPrio[0].toString(16)}" else null,
                "submittedFrames" to totalFrames,
                "encodedFrames" to encodedCount.get(),
                "totalOutputBytes" to totalOutputBytes.get(),
                "wallMs" to wallMs,
                "effectiveFps" to effectiveFps,
                "p50FrameMs" to p50,
                "p95FrameMs" to p95,
                "maxFrameMs" to maxMs,
            )
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                runCatching {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglSurf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurf)
                    if (eglCtx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglCtx)
                    EGL14.eglTerminate(display)
                }
            }
            if (codecStarted) runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { inputSurface?.release() }
        }
    }

    // -------------------------------------------------------------------------
    // Probe 4: Hidden High-Res RAW_SENSOR (32) / RAW10 / RAW_PRIVATE Admission
    // -------------------------------------------------------------------------
    fun runProbe4_HiddenRawSensorAdmission(
        context: Context,
        outDir: File,
        checkBudget: () -> Unit = {},
    ): JSONObject {
        val manager = context.getSystemService(CameraManager::class.java)
        val candidateCamIds = listOf("0", "2").filter { cid -> runCatching { manager.cameraIdList.contains(cid) }.getOrDefault(false) }
        val armsSpec = listOf(
            Triple("RAW_SENSOR_8192x6144", ImageFormat.RAW_SENSOR, Size(8192, 6144)),
            Triple("RAW_SENSOR_8000x6016", ImageFormat.RAW_SENSOR, Size(8000, 6016)),
            Triple("RAW_SENSOR_4096x3072", ImageFormat.RAW_SENSOR, Size(4096, 3072)),
            Triple("RAW_SENSOR_4000x3000", ImageFormat.RAW_SENSOR, Size(4000, 3000)),
            Triple("RAW10_4096x3072", ImageFormat.RAW10, Size(4096, 3072)),
            Triple("RAW10_8192x6144", ImageFormat.RAW10, Size(8192, 6144)),
            Triple("RAW_PRIVATE_4096x3072", ImageFormat.RAW_PRIVATE, Size(4096, 3072)),
        )
        val results = JSONArray()
        for (cid in candidateCamIds) {
            val caps = runCatching { manager.getCameraCharacteristics(cid) }.getOrNull() ?: continue
            val inconsistentKey = findVendorRequestKey(caps, "hwInconsistentRawStream")
            for ((label, fmt, sz) in armsSpec) {
                for (pairedWithPreview in listOf(false, true)) {
                    ensureCoolOrWait(context, checkBudget)
                    val res = try {
                        probeSingleRawAttempt(context, cid, label, fmt, sz, pairedWithPreview, inconsistentKey, false)
                    } catch (t: Throwable) {
                        json("cameraId" to cid, "label" to label, "pairedWithPreview" to pairedWithPreview, "admitted" to false, "error" to t.toString().take(300))
                    }
                    results.put(res)
                }
                if (inconsistentKey != null) {
                    ensureCoolOrWait(context, checkBudget)
                    val incRes = try {
                        probeSingleRawAttempt(context, cid, "${label}_inconsistentKey", fmt, sz, true, inconsistentKey, true)
                    } catch (t: Throwable) {
                        json("cameraId" to cid, "label" to "${label}_inconsistentKey", "admitted" to false, "error" to t.toString().take(300))
                    }
                    results.put(incRes)
                }
            }
        }
        return saveProbeJson(outDir, "probe4_hidden_raw.json", json(
            "probe" to "Probe4_HiddenRawSensorAdmission",
            "batteryEndC" to batteryTempC(context),
            "results" to results,
        ))
    }

    @SuppressLint("MissingPermission")
    private fun probeSingleRawAttempt(
        context: Context,
        cameraId: String,
        label: String,
        format: Int,
        size: Size,
        pairedWithPreview: Boolean,
        inconsistentKey: CaptureRequest.Key<*>?,
        setInconsistentKey: Boolean,
    ): JSONObject {
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        lease.ready().get(8, TimeUnit.SECONDS)
        val manager = context.getSystemService(CameraManager::class.java)
        val thread = HandlerThread("p4-raw").apply { start() }
        val handler = Handler(thread.looper)

        val rawReader = runCatching { ImageReader.newInstance(size.width, size.height, format, 3) }.getOrElse { t ->
            lease.releaseAfter(CompletableFuture.completedFuture(Unit))
            thread.quitSafely()
            return json(
                "cameraId" to cameraId,
                "label" to label,
                "format" to format,
                "size" to size.toString(),
                "pairedWithPreview" to pairedWithPreview,
                "admitted" to false,
                "step" to "image_reader_create",
                "error" to t.toString().take(240),
            )
        }
        val previewReader = if (pairedWithPreview) ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 3) else null
        previewReader?.setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage()?.close() } }, handler)

        val firstFrame = CompletableFuture<JSONObject>()
        rawReader.setOnImageAvailableListener({ r ->
            runCatching {
                r.acquireLatestImage()?.use { img ->
                    val hb = img.hardwareBuffer
                    val hbFmt = hb?.format
                    val hbUsage = hb?.usage
                    hb?.close()
                    val plane = img.planes.firstOrNull()
                    var nonZero = 0
                    var maxSample = 0
                    val cap = plane?.buffer?.remaining() ?: 0
                    if (plane != null && cap > 0) {
                        val dup = plane.buffer.duplicate()
                        val step = (cap / 64).coerceAtLeast(1)
                        var idx = 0
                        while (idx < cap) {
                            val v = dup.get(idx).toInt() and 0xFF
                            if (v != 0) nonZero++
                            if (v > maxSample) maxSample = v
                            idx += step
                        }
                    }
                    firstFrame.complete(json(
                        "width" to img.width,
                        "height" to img.height,
                        "format" to img.format,
                        "planeCount" to img.planes.size,
                        "rowStride" to plane?.rowStride,
                        "pixelStride" to plane?.pixelStride,
                        "planeBytes" to cap,
                        "sampledNonZero" to nonZero,
                        "sampledMaxByte" to maxSample,
                        "hwBufferFormat" to hbFmt,
                        "hwBufferFormatHex" to hbFmt?.let { "0x${it.toString(16)}" },
                        "hwBufferUsageHex" to hbUsage?.let { "0x${it.toString(16)}" },
                    ))
                }
            }
        }, handler)

        val opened = CompletableFuture<CameraDevice>()
        val closed = CompletableFuture<Unit>()
        val configured = CompletableFuture<CameraCaptureSession>()
        val retiring = AtomicBoolean()
        var session: CameraCaptureSession? = null
        var openSubmitted = false

        return try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { opened.complete(c); if (retiring.get()) c.close() }
                override fun onDisconnected(c: CameraDevice) { opened.completeExceptionally(IllegalStateException("Disconnected")); c.close() }
                override fun onError(c: CameraDevice, e: Int) { opened.completeExceptionally(IllegalStateException("Error $e")); c.close() }
                override fun onClosed(c: CameraDevice) { closed.complete(Unit) }
            }, handler)
            openSubmitted = true
            val cam = opened.get(6, TimeUnit.SECONDS)
            val targets = buildList {
                add(rawReader.surface)
                previewReader?.surface?.let { add(it) }
            }
            @Suppress("DEPRECATION")
            cam.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { if (retiring.get()) s.close() else configured.complete(s) }
                override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("Configure failed")); s.close() }
            }, handler)
            session = configured.get(6, TimeUnit.SECONDS)
            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                targets.forEach(::addTarget)
                if (setInconsistentKey && inconsistentKey != null) {
                    setVendorByteOrIntKey(this, inconsistentKey, 1, vendorKeyTypeHint(inconsistentKey))
                }
            }.build()
            session.capture(req, null, handler)
            val frameObj = runCatching { firstFrame.get(3_500L, TimeUnit.MILLISECONDS) }.getOrNull()
            json(
                "cameraId" to cameraId,
                "label" to label,
                "format" to format,
                "size" to size.toString(),
                "pairedWithPreview" to pairedWithPreview,
                "inconsistentKeyApplied" to setInconsistentKey,
                "admitted" to true,
                "framesReceived" to (frameObj != null),
                "frame" to frameObj,
            )
        } catch (t: Throwable) {
            json(
                "cameraId" to cameraId,
                "label" to label,
                "format" to format,
                "size" to size.toString(),
                "pairedWithPreview" to pairedWithPreview,
                "inconsistentKeyApplied" to setInconsistentKey,
                "admitted" to false,
                "framesReceived" to false,
                "errorClass" to t.javaClass.name,
                "error" to (t.message ?: t.toString()).take(240),
            )
        } finally {
            retiring.set(true)
            val cc = closed.thenApply { Unit }
            lease.releaseAfter(cc)
            if (openSubmitted) {
                handler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } }
            } else closed.complete(Unit)
            runCatching { cc.get(6, TimeUnit.SECONDS) }
            runCatching { rawReader.close() }
            runCatching { previewReader?.close() }
            thread.quitSafely()
            runCatching { thread.join(4_000) }
        }
    }

    // -------------------------------------------------------------------------
    // Probe 5: Vendor ByteBuffer 10-bit Encoder Format Clips (1920x1080@30 Main10)
    // -------------------------------------------------------------------------
    private enum class BbPackMode { NV12_8BIT, P010_MSB_16LE, RAW10_LSB_16LE }

    fun runProbe5_VendorByteBuffer10BitClips(
        context: Context,
        outDir: File,
        checkBudget: () -> Unit = {},
    ): JSONObject {
        outDir.mkdirs()
        val arms = JSONArray()
        val specs = listOf(
            Triple("bb_flex.mp4", MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible, BbPackMode.NV12_8BIT),
            Triple("bb_7f0f.mp4", COLOR_FORMAT_HISI_YUV420SP, BbPackMode.NV12_8BIT),
            Triple("bb_7f11_nv12.mp4", COLOR_FORMAT_HISI_YUV420SP_10BIT, BbPackMode.NV12_8BIT),
            Triple("bb_7f11_p010_msb.mp4", COLOR_FORMAT_HISI_YUV420SP_10BIT, BbPackMode.P010_MSB_16LE),
            Triple("bb_7f11_10bit_lsb.mp4", COLOR_FORMAT_HISI_YUV420SP_10BIT, BbPackMode.RAW10_LSB_16LE),
        )
        for ((fileName, colorFmt, packMode) in specs) {
            val res = try {
                ensureCoolOrWait(context, checkBudget)
                val outFile = File(outDir, fileName)
                if (outFile.exists()) outFile.delete()
                encodeByteBufferArm(outFile, colorFmt, packMode)
            } catch (t: Throwable) {
                json(
                    "clip" to fileName,
                    "colorFormat" to colorFmt,
                    "colorFormatHex" to "0x${colorFmt.toString(16)}",
                    "packMode" to packMode.name,
                    "encodedOk" to false,
                    "error" to (t.message ?: t.toString()).take(280),
                )
            }
            arms.put(res)
        }
        return saveProbeJson(outDir, "probe5_bb10.json", json(
            "probe" to "Probe5_VendorByteBuffer10BitClips",
            "batteryEndC" to batteryTempC(context),
            "arms" to arms,
        ))
    }

    private fun encodeByteBufferArm(outFile: File, colorFormat: Int, packMode: BbPackMode): JSONObject {
        val w = 1920
        val h = 1080
        val fps = 30
        val frames = 15
        val codecName = selectHevcEncoderName()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h).apply {
            setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_LEVEL, CodecProfileLevel.HEVCMainTierLevel62)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, 20_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        val codec = MediaCodec.createByCodecName(codecName)
        var muxer: MediaMuxer? = null
        var codecStarted = false
        var muxerStarted = false
        val csd0Ref = AtomicReference<ByteArray?>(null)
        val inputCapObserved = AtomicInteger(-1)
        val inputImgFormatObserved = AtomicInteger(-1)
        val inputBufferTooSmall = AtomicBoolean(false)
        val outputSamples = AtomicInteger(0)

        return try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.start()
            codecStarted = true

            val expectedNv12 = w * h * 3 / 2
            val expectedP010 = w * h * 3
            val frameData = buildRampFrameBytes(w, h, packMode)
            var submitted = 0
            var eosQueued = false
            var eosReceived = false
            var trackIndex = -1
            val info = MediaCodec.BufferInfo()
            val deadline = System.currentTimeMillis() + 12_000L

            while (!eosReceived && System.currentTimeMillis() < deadline) {
                if (!eosQueued) {
                    val inIdx = codec.dequeueInputBuffer(5_000L)
                    if (inIdx >= 0) {
                        if (submitted >= frames) {
                            codec.queueInputBuffer(inIdx, 0, 0, submitted * 33_333L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosQueued = true
                        } else {
                            val buf = codec.getInputBuffer(inIdx)
                            val cap = buf?.capacity() ?: 0
                            inputCapObserved.compareAndSet(-1, cap)
                            if (buf == null || cap == 0) {
                                throw IllegalStateException("Null/empty encoder input buffer")
                            }
                            if (cap < frameData.size) {
                                inputBufferTooSmall.set(true)
                            }
                            val writeLen = frameData.size.coerceAtMost(cap)
                            buf.clear()
                            buf.put(frameData, 0, writeLen)
                            codec.queueInputBuffer(inIdx, 0, writeLen, submitted * 33_333L, 0)
                            submitted++
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 5_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        of.getByteBuffer("csd-0")?.let { bb ->
                            csd0Ref.compareAndSet(null, ByteArray(bb.remaining()).also { bb.duplicate().get(it) })
                        }
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(of)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0 && muxerStarted && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, outBuf, info)
                            outputSamples.incrementAndGet()
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eosReceived = true
                    }
                }
            }
            if (muxerStarted) runCatching { muxer.stop() }
            muxerStarted = false

            val sps = csd0Ref.get()?.let { HevcSpsInspector.inspect(it) }
            val inputFormat = runCatching { codec.inputFormat }.getOrNull()
            json(
                "clip" to outFile.name,
                "codecName" to codecName,
                "colorFormat" to colorFormat,
                "colorFormatHex" to "0x${colorFormat.toString(16)}",
                "packMode" to packMode.name,
                "inputBufferCapacity" to inputCapObserved.get(),
                "inputImageFormat" to inputImgFormatObserved.get().takeIf { it != -1 },
                "inputFormatStride" to runCatching { inputFormat?.getInteger(MediaFormat.KEY_STRIDE) }.getOrNull(),
                "expectedNv12Bytes" to expectedNv12,
                "expectedP010Bytes" to expectedP010,
                "inputBufferTooSmall" to inputBufferTooSmall.get(),
                "submittedFrames" to submitted,
                "outputSamples" to outputSamples.get(),
                "outputBytes" to outFile.length(),
                "spsBitDepthLuma" to sps?.bitDepthLuma,
                "encodedOk" to (eosReceived && outputSamples.get() >= frames && outFile.length() > 1024L),
            )
        } finally {
            if (codecStarted) runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun buildRampFrameBytes(w: Int, h: Int, mode: BbPackMode): ByteArray {
        val pixels = w * h
        return when (mode) {
            BbPackMode.NV12_8BIT -> {
                val out = ByteArray(pixels + pixels / 2)
                for (x in 0 until w) {
                    val v8 = ((x * 255) / (w - 1).coerceAtLeast(1)).toByte()
                    for (y in 0 until h) out[y * w + x] = v8
                }
                for (i in pixels until out.size) out[i] = 128.toByte()
                out
            }
            BbPackMode.P010_MSB_16LE, BbPackMode.RAW10_LSB_16LE -> {
                val out = ByteArray(pixels * 3)
                val shift = if (mode == BbPackMode.P010_MSB_16LE) 6 else 0
                val neutralUv = if (mode == BbPackMode.P010_MSB_16LE) 0x8000 else 512
                val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val val10 = (x * 1023) / (w - 1).coerceAtLeast(1)
                        bb.putShort(((val10 and 0x3FF) shl shift).toShort())
                    }
                }
                val uvShorts = pixels / 2
                for (i in 0 until uvShorts) {
                    bb.putShort(neutralUv.toShort())
                }
                out
            }
        }
    }

    // -------------------------------------------------------------------------
    // Probe 6: Zero-Copy ImageReader(GPU|VIDEO_ENCODE) -> HardwareBuffer -> EGLImage -> OCLog2
    // -------------------------------------------------------------------------
    fun runProbe6_ZeroCopy4k60GpuOcLog2(
        context: Context,
        outDir: File,
        checkBudget: () -> Unit = {},
    ): JSONObject {
        outDir.mkdirs()
        val bridgeLoaded = EglHardwareBufferBridge.loaded
        val armSpecs = listOf(
            "6A_zerocopy_4k60_oclog2_sdr" to "zc_4k60_oclog2_sdr.mp4",
            "6B_zerocopy_4k60_oclog2_gamma20" to "zc_4k60_oclog2_gamma20.mp4",
            "6C_zerocopy_4k60_oclog2_cuva" to "zc_4k60_oclog2_cuva.mp4",
            "6D_zerocopy_4k30_oclog2_cuva" to "zc_4k30_oclog2_cuva.mp4",
        )
        val arms = JSONArray()
        for ((armName, fileName) in armSpecs) {
            val res = try {
                val temp = ensureCoolOrWait(context, checkBudget)
                val outFile = File(outDir, fileName)
                if (outFile.exists()) outFile.delete()
                val fps = if (armName.contains("4k30")) 30 else 60
                val cuvaOn = armName.endsWith("_cuva")
                val gamma20 = armName.contains("gamma20")
                runProbe6Arm(context, armName, outFile, fps, cuvaOn, gamma20).put("batteryStartC", temp)
            } catch (t: Throwable) {
                json(
                    "arm" to armName,
                    "ok" to false,
                    "errorClass" to t.javaClass.name,
                    "error" to (t.message ?: t.toString()).take(300),
                )
            }
            arms.put(res)
        }
        return saveProbeJson(outDir, "probe6_zerocopy_4k60_oclog2.json", json(
            "probe" to "Probe6_ZeroCopy4k60GpuOcLog2",
            "bridgeLoaded" to bridgeLoaded,
            "batteryEndC" to batteryTempC(context),
            "arms" to arms,
        ))
    }

    @SuppressLint("MissingPermission")
    private fun runProbe6Arm(
        context: Context,
        armName: String,
        outFile: File,
        fps: Int,
        cuvaOn: Boolean,
        gamma20: Boolean,
    ): JSONObject {
        check(EglHardwareBufferBridge.loaded) { "libocc_egl_hwbuffer.so failed to load" }
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        lease.ready().get(8, TimeUnit.SECONDS)
        val manager = context.getSystemService(CameraManager::class.java)
        val caps = manager.getCameraCharacteristics("0")
        val cuvaReqKey = findVendorRequestKey(caps, "cuvaStateVideoMode")
        val cuvaResKey = findVendorResultKey(caps, "cuvaStateVideoMode")
        val cuvaHint = vendorKeyTypeHint(cuvaReqKey)
        val toneMapModes = caps.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.toSet().orEmpty()

        val sourcePath = if (cuvaOn) OpenCineLogSourcePath.HLG10_BT2020 else OpenCineLogSourcePath.SDR_BT709_ISP
        val w = 3840
        val h = 2160
        val codecName = selectHevcEncoderName()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h).apply {
            setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_LEVEL, CodecProfileLevel.HEVCMainTierLevel62)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, if (fps >= 60) 80_000_000 else 60_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
        }
        val codec = MediaCodec.createByCodecName(codecName)
        var inputSurface: Surface? = null
        var muxer: MediaMuxer? = null
        var codecStarted = false
        var muxerStarted = false
        var drainThread: Thread? = null
        val csd0Ref = AtomicReference<ByteArray?>(null)
        val encodedSamples = AtomicInteger(0)

        val camThread = HandlerThread("p6-cam").apply { start() }
        val camHandler = Handler(camThread.looper)
        val executor = Executor { r -> camHandler.post(r) }
        val glThread = HandlerThread("p6-gl").apply { start() }
        val glHandler = Handler(glThread.looper)

        var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var texId = 0
        var program = 0
        var extMask = 0
        var eglColorBits = 8
        var bt2020SurfaceAccepted = false
        var prioHex: String? = null

        val closing = AtomicBoolean(false)
        val camTimestamps = mutableListOf<Long>()
        val glTimestamps = mutableListOf<Long>()
        val renderTimesMs = mutableListOf<Double>()
        val eglCreateOk = AtomicInteger(0)
        val eglCreateFail = AtomicInteger(0)
        val glErrors = AtomicInteger(0)
        val lastEglErr = AtomicInteger(0)
        val hbFormat = AtomicInteger(-1)
        val hbUsage = AtomicLong(-1L)
        val cuvaAppliedFmt = AtomicReference<String?>(null)
        val cuvaObservedRes = AtomicReference<String?>(null)
        val toneMapApplied = AtomicBoolean(false)

        val reader = createUsageImageReader(
            w, h, ImageFormat.PRIVATE, 6,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_VIDEO_ENCODE,
        )

        val opened = CompletableFuture<CameraDevice>()
        val closed = CompletableFuture<Unit>()
        val configured = CompletableFuture<CameraCaptureSession>()
        val retiring = AtomicBoolean(false)
        var session: CameraCaptureSession? = null
        var openSubmitted = false

        return try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.start()
            codecStarted = true

            val drainStop = AtomicBoolean(false)
            drainThread = Thread({
                val info = MediaCodec.BufferInfo()
                var trackIdx = -1
                while (!drainStop.get()) {
                    val outIdx = runCatching { codec.dequeueOutputBuffer(info, 10_000L) }.getOrDefault(-99)
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val of = codec.outputFormat
                        of.getByteBuffer("csd-0")?.let { bb ->
                            val raw = ByteArray(bb.remaining()).also { bb.duplicate().get(it) }
                            val fixed = runCatching { HevcVuiTransfer.rewrite(raw, HevcVuiTransfer.UNSPECIFIED).bytes }.getOrDefault(raw)
                            csd0Ref.compareAndSet(null, fixed)
                            of.setByteBuffer("csd-0", ByteBuffer.wrap(fixed))
                        }
                        if (!muxerStarted) {
                            trackIdx = muxer.addTrack(of)
                            muxer.start()
                            muxerStarted = true
                        }
                    } else if (outIdx >= 0) {
                        val buf = codec.getOutputBuffer(outIdx)
                        if (buf != null && info.size > 0 && muxerStarted && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val payload = if ((info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                                val bytes = ByteArray(info.size)
                                buf.get(bytes)
                                val rw = runCatching { HevcVuiTransfer.rewrite(bytes, HevcVuiTransfer.UNSPECIFIED).bytes }.getOrDefault(bytes)
                                info.offset = 0
                                info.size = rw.size
                                ByteBuffer.wrap(rw)
                            } else buf
                            muxer.writeSampleData(trackIdx, payload, info)
                            encodedSamples.incrementAndGet()
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    } else if (outIdx == -99) {
                        break
                    }
                }
            }, "p6-drain").apply { start() }

            var quadBuffer: FloatBuffer? = null
            var uTexLoc = -1
            var uOutModeLoc = -1
            var uGainLoc = -1

            val glInitFuture = CompletableFuture<Unit>()
            glHandler.post {
                runCatching {
                    eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                    val ver = IntArray(2)
                    check(EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) { "eglInitialize failed" }

                    val cfg10Attr = intArrayOf(
                        EGL14.EGL_RED_SIZE, 10,
                        EGL14.EGL_GREEN_SIZE, 10,
                        EGL14.EGL_BLUE_SIZE, 10,
                        EGL14.EGL_ALPHA_SIZE, 2,
                        EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                        EGL_RECORDABLE_ANDROID, 1,
                        EGL14.EGL_NONE,
                    )
                    val cfg8Attr = intArrayOf(
                        EGL14.EGL_RED_SIZE, 8,
                        EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_ALPHA_SIZE, 8,
                        EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                        EGL_RECORDABLE_ANDROID, 1,
                        EGL14.EGL_NONE,
                    )
                    val cfgs = arrayOfNulls<EGLConfig>(1)
                    val num = IntArray(1)
                    var chosenCfg: EGLConfig? = null
                    if (EGL14.eglChooseConfig(eglDisplay, cfg10Attr, 0, cfgs, 0, 1, num, 0) && num[0] > 0 && cfgs[0] != null) {
                        val s10 = runCatching {
                            EGL14.eglCreateWindowSurface(
                                eglDisplay, cfgs[0], inputSurface,
                                intArrayOf(EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_LINEAR_EXT, EGL14.EGL_NONE), 0,
                            )
                        }.getOrDefault(EGL14.EGL_NO_SURFACE)
                        if (s10 != EGL14.EGL_NO_SURFACE) {
                            chosenCfg = cfgs[0]
                            eglSurface = s10
                            eglColorBits = 10
                            bt2020SurfaceAccepted = true
                        } else {
                            val s10plain = EGL14.eglCreateWindowSurface(eglDisplay, cfgs[0], inputSurface, intArrayOf(EGL14.EGL_NONE), 0)
                            if (s10plain != EGL14.EGL_NO_SURFACE) {
                                chosenCfg = cfgs[0]
                                eglSurface = s10plain
                                eglColorBits = 10
                            }
                        }
                    }
                    if (chosenCfg == null) {
                        check(EGL14.eglChooseConfig(eglDisplay, cfg8Attr, 0, cfgs, 0, 1, num, 0) && num[0] > 0 && cfgs[0] != null) {
                            "eglChooseConfig 8-bit failed"
                        }
                        chosenCfg = cfgs[0]
                        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, chosenCfg, inputSurface, intArrayOf(EGL14.EGL_NONE), 0)
                        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface 8-bit failed" }
                        eglColorBits = 8
                    }
                    eglContext = EGL14.eglCreateContext(
                        eglDisplay, chosenCfg, EGL14.EGL_NO_CONTEXT,
                        intArrayOf(
                            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                            EGL_CONTEXT_PRIORITY_LEVEL_IMG, EGL_CONTEXT_PRIORITY_HIGH_IMG,
                            EGL14.EGL_NONE,
                        ),
                        0,
                    )
                    if (eglContext == EGL14.EGL_NO_CONTEXT) {
                        eglContext = EGL14.eglCreateContext(
                            eglDisplay, chosenCfg, EGL14.EGL_NO_CONTEXT,
                            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
                        )
                    }
                    check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
                    check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }
                    val pq = IntArray(1)
                    if (EGL14.eglQueryContext(eglDisplay, eglContext, EGL_CONTEXT_PRIORITY_LEVEL_IMG, pq, 0)) {
                        prioHex = "0x${pq[0].toString(16)}"
                    }
                    extMask = EglHardwareBufferBridge.probeExtensions()
                    check(extMask == 15) { "Missing EGLImage extensions mask=$extMask" }

                    val texs = IntArray(1)
                    GLES20.glGenTextures(1, texs, 0)
                    texId = texs[0]

                    val vsh = """
                        #version 300 es
                        layout(location = 0) in vec2 aPosition;
                        out vec2 vTexCoord;
                        void main() {
                            gl_Position = vec4(aPosition, 0.0, 1.0);
                            vTexCoord = (aPosition + 1.0) * 0.5;
                        }
                    """.trimIndent()
                    val fsh = OpenCineLogGpuPipeline.transformShader(sourcePath, shaderYcbcr = false)
                    program = compileGlProgram(vsh, fsh)
                    GLES20.glUseProgram(program)
                    uTexLoc = GLES20.glGetUniformLocation(program, "uTexture")
                    uOutModeLoc = GLES20.glGetUniformLocation(program, "uOutputMode")
                    uGainLoc = GLES20.glGetUniformLocation(program, "uSceneGain")

                    val coords = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
                    quadBuffer = ByteBuffer.allocateDirect(coords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                        put(coords)
                        position(0)
                    }
                }.onSuccess { glInitFuture.complete(Unit) }.onFailure { glInitFuture.completeExceptionally(it) }
            }
            glInitFuture.get(6, TimeUnit.SECONDS)

            reader.setOnImageAvailableListener({ r ->
                val img = runCatching { r.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
                if (closing.get()) {
                    runCatching { img.close() }
                    return@setOnImageAvailableListener
                }
                val ts = img.timestamp
                var hb: android.hardware.HardwareBuffer? = null
                var eglImg = 0L
                val t0 = System.nanoTime()
                try {
                    hb = img.hardwareBuffer
                    if (hb != null) {
                        if (hbFormat.get() == -1) {
                            hbFormat.compareAndSet(-1, hb.format)
                            hbUsage.compareAndSet(-1L, hb.usage)
                        }
                        eglImg = EglHardwareBufferBridge.createEglImage(hb)
                        if (eglImg == 0L) {
                            eglCreateFail.incrementAndGet()
                            lastEglErr.compareAndSet(0, EglHardwareBufferBridge.lastEglError())
                        } else {
                            eglCreateOk.incrementAndGet()
                            GLES20.glViewport(0, 0, w, h)
                            GLES20.glUseProgram(program)
                            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
                            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                            val bindErr = EglHardwareBufferBridge.bindExternalTexture(eglImg)
                            if (bindErr != 0) glErrors.incrementAndGet()
                            GLES20.glUniform1i(uTexLoc, 0)
                            GLES20.glUniform1i(uOutModeLoc, 0)
                            GLES20.glUniform1f(uGainLoc, 1.0f)
                            GLES20.glEnableVertexAttribArray(0)
                            quadBuffer?.position(0)
                            GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quadBuffer)
                            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                            GLES20.glDisableVertexAttribArray(0)
                            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ts)
                            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
                            val dtMs = (System.nanoTime() - t0) / 1_000_000.0
                            synchronized(glTimestamps) { glTimestamps += ts }
                            synchronized(renderTimesMs) { renderTimesMs += dtMs }
                        }
                    }
                } finally {
                    if (eglImg != 0L) EglHardwareBufferBridge.destroyEglImage(eglImg)
                    runCatching { hb?.close() }
                    runCatching { img.close() }
                }
            }, glHandler)

            manager.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { opened.complete(c); if (retiring.get()) c.close() }
                override fun onDisconnected(c: CameraDevice) { opened.completeExceptionally(IllegalStateException("Disconnected")); c.close() }
                override fun onError(c: CameraDevice, e: Int) { opened.completeExceptionally(IllegalStateException("Error $e")); c.close() }
                override fun onClosed(c: CameraDevice) { closed.complete(Unit) }
            }, camHandler)
            openSubmitted = true
            val cam = opened.get(6, TimeUnit.SECONDS)

            fun applyKeys(b: CaptureRequest.Builder) {
                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                if (gamma20 && CaptureRequest.TONEMAP_MODE_GAMMA_VALUE in toneMapModes) {
                    b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_GAMMA_VALUE)
                    b.set(CaptureRequest.TONEMAP_GAMMA, 2.0f)
                    toneMapApplied.set(true)
                }
                if (cuvaOn && cuvaReqKey != null) {
                    cuvaAppliedFmt.compareAndSet(null, setVendorByteOrIntKey(b, cuvaReqKey, 1, cuvaHint))
                }
            }

            val targets = listOf(reader.surface)
            val sessReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { targets.forEach(::addTarget); applyKeys(this) }.build()
            cam.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    targets.map { OutputConfiguration(it) },
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { if (retiring.get()) s.close() else configured.complete(s) }
                        override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("Configure failed")); s.close() }
                    },
                ).apply { sessionParameters = sessReq },
            )
            session = configured.get(6, TimeUnit.SECONDS)
            val repReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { targets.forEach(::addTarget); applyKeys(this) }.build()
            session.setRepeatingRequest(repReq, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, res: TotalCaptureResult) {
                    res.get(CaptureResult.SENSOR_TIMESTAMP)?.let { synchronized(camTimestamps) { camTimestamps += it } }
                    if (cuvaObservedRes.get() == null) readResultKeyValue(res, cuvaResKey)?.let { cuvaObservedRes.compareAndSet(null, it) }
                }
            }, camHandler)

            Thread.sleep(3_500L)
            closing.set(true)
            runCatching { session.stopRepeating() }
            runCatching { codec.signalEndOfInputStream() }
            drainThread.join(4_000L)
            drainStop.set(true)
            drainThread.join(1_000L)
            if (muxerStarted) runCatching { muxer.stop() }
            muxerStarted = false

            val gapThresh = if (fps >= 60) 25_000_000L else 45_000_000L
            val camMetrics = synchronized(camTimestamps) { computeTimestampMetrics(camTimestamps.toList(), gapThresh) }
            val glMetrics = synchronized(glTimestamps) { computeTimestampMetrics(glTimestamps.toList(), gapThresh) }
            val rTimes = synchronized(renderTimesMs) { renderTimesMs.sorted() }
            val fileMetrics = inspectWrittenMp4(outFile)
            val sps = csd0Ref.get()?.let { HevcSpsInspector.inspect(it) }
            json(
                "arm" to armName,
                "ok" to (encodedSamples.get() > 20 && glMetrics.frames > 20 && outFile.length() > 1024L && eglCreateOk.get() > 0),
                "clip" to outFile.name,
                "clipBytes" to outFile.length(),
                "sourcePath" to sourcePath.name,
                "eglColorBits" to eglColorBits,
                "bt2020LinearSurface" to bt2020SurfaceAccepted,
                "eglPriorityHex" to prioHex,
                "extMask" to extMask,
                "hwBufferFormat" to hbFormat.get().takeIf { it != -1 },
                "hwBufferFormatHex" to hbFormat.get().takeIf { it != -1 }?.let { "0x${it.toString(16)}" },
                "hwBufferUsageHex" to hbUsage.get().takeIf { it != -1L }?.let { "0x${it.toString(16)}" },
                "cuvaRequested" to cuvaOn,
                "cuvaAppliedFmt" to cuvaAppliedFmt.get(),
                "cuvaObservedRes" to cuvaObservedRes.get(),
                "gamma20Applied" to toneMapApplied.get(),
                "eglCreateOk" to eglCreateOk.get(),
                "eglCreateFail" to eglCreateFail.get(),
                "lastEglErrHex" to lastEglErr.get().takeIf { it != 0 }?.let { "0x${it.toString(16)}" },
                "glErrors" to glErrors.get(),
                "renderMsP50" to rTimes.getOrNull(rTimes.size / 2),
                "renderMsP95" to rTimes.getOrNull((rTimes.size * 95 / 100).coerceAtMost((rTimes.size - 1).coerceAtLeast(0))),
                "renderMsMax" to rTimes.lastOrNull(),
                "camCb" to camMetrics.toJson(),
                "glRendered" to glMetrics.toJson(),
                "encodedSamples" to encodedSamples.get(),
                "spsBitDepthLuma" to sps?.bitDepthLuma,
                "fileStats" to fileMetrics,
            )
        } finally {
            closing.set(true)
            retiring.set(true)
            val cc = closed.thenApply { Unit }
            lease.releaseAfter(cc)
            if (openSubmitted) {
                camHandler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } }
            } else closed.complete(Unit)
            runCatching { cc.get(6, TimeUnit.SECONDS) }
            runCatching { reader.close() }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                val glDone = CompletableFuture<Unit>()
                glHandler.post {
                    if (program != 0) runCatching { GLES20.glDeleteProgram(program) }
                    if (texId != 0) runCatching { GLES20.glDeleteTextures(1, intArrayOf(texId), 0) }
                    runCatching {
                        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                        EGL14.eglTerminate(eglDisplay)
                    }
                    glDone.complete(Unit)
                }
                runCatching { glDone.get(4, TimeUnit.SECONDS) }
            }
            if (codecStarted) runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { inputSurface?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            camThread.quitSafely()
            glThread.quitSafely()
            runCatching { camThread.join(4_000) }
            runCatching { glThread.join(4_000) }
        }
    }

    private fun compileGlProgram(vsh: String, fsh: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val status = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "Shader compile failed: ${GLES20.glGetShaderInfoLog(s)}" }
            return s
        }
        val vs = compile(GLES20.GL_VERTEX_SHADER, vsh)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fsh)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Program link failed: ${GLES20.glGetProgramInfoLog(p)}" }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return p
    }

    // -------------------------------------------------------------------------
    // Probe 7: Continuous 4000x3000 RAW_SENSOR (32) Repeating-Request Rate
    // -------------------------------------------------------------------------
    fun runProbe7_RawSensorStreamingRate(
        context: Context,
        outDir: File,
        checkBudget: () -> Unit = {},
    ): JSONObject {
        val arms = JSONArray()
        for (fps in listOf(30, 24)) {
            val res = try {
                val temp = ensureCoolOrWait(context, checkBudget)
                runProbe7Arm(context, fps).put("batteryStartC", temp)
            } catch (t: Throwable) {
                json(
                    "fps" to fps,
                    "ok" to false,
                    "errorClass" to t.javaClass.name,
                    "error" to (t.message ?: t.toString()).take(300),
                )
            }
            arms.put(res)
        }
        return saveProbeJson(outDir, "probe7_raw_streaming.json", json(
            "probe" to "Probe7_RawSensorStreamingRate",
            "batteryEndC" to batteryTempC(context),
            "arms" to arms,
        ))
    }

    @SuppressLint("MissingPermission")
    private fun runProbe7Arm(context: Context, fps: Int): JSONObject {
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        lease.ready().get(8, TimeUnit.SECONDS)
        val manager = context.getSystemService(CameraManager::class.java)
        val caps = manager.getCameraCharacteristics("0")
        val whiteLevel = caps.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
        val blackPattern = caps.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { p ->
            val arr = IntArray(4)
            p.copyTo(arr, 0)
            arr.toList()
        }

        val thread = HandlerThread("p7-raw").apply { start() }
        val handler = Handler(thread.looper)
        val executor = Executor { r -> handler.post(r) }
        val rawReader = ImageReader.newInstance(4000, 3000, ImageFormat.RAW_SENSOR, 6)
        val previewReader = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 3)

        val camTimestamps = mutableListOf<Long>()
        val rawTimestamps = mutableListOf<Long>()
        val pixelStats = AtomicReference<JSONObject?>(null)

        rawReader.setOnImageAvailableListener({ r ->
            runCatching {
                r.acquireNextImage()?.use { img ->
                    synchronized(rawTimestamps) { rawTimestamps += img.timestamp }
                    if (pixelStats.get() == null && rawTimestamps.size >= 5) {
                        val plane = img.planes.firstOrNull()
                        val buf = plane?.buffer?.duplicate()?.order(ByteOrder.LITTLE_ENDIAN)
                        if (buf != null && buf.remaining() >= 4096) {
                            val samples = IntArray(2048)
                            val cap = buf.remaining() / 2
                            val step = (cap / 2048).coerceAtLeast(1)
                            var minV = 65535
                            var maxV = 0
                            var nonZero = 0
                            for (i in samples.indices) {
                                val v = buf.getShort((i * step) * 2).toInt() and 0xFFFF
                                samples[i] = v
                                if (v < minV) minV = v
                                if (v > maxV) maxV = v
                                if (v != 0) nonZero++
                            }
                            samples.sort()
                            val inferredAdcBits = when {
                                maxV <= 1023 -> 10
                                maxV <= 4095 -> 12
                                maxV <= 16383 -> 14
                                else -> 16
                            }
                            pixelStats.set(json(
                                "rowStride" to plane.rowStride,
                                "pixelStride" to plane.pixelStride,
                                "min" to minV,
                                "p01" to samples[(samples.size * 1 / 100)],
                                "p50" to samples[samples.size / 2],
                                "p99" to samples[(samples.size * 99 / 100)],
                                "max" to maxV,
                                "nonZeroSamples" to nonZero,
                                "inferredAdcBits" to inferredAdcBits,
                            ))
                        }
                    }
                }
            }
        }, handler)
        previewReader.setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage()?.close() } }, handler)

        val opened = CompletableFuture<CameraDevice>()
        val closed = CompletableFuture<Unit>()
        val configured = CompletableFuture<CameraCaptureSession>()
        val retiring = AtomicBoolean()
        var session: CameraCaptureSession? = null
        var openSubmitted = false

        return try {
            manager.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { opened.complete(c); if (retiring.get()) c.close() }
                override fun onDisconnected(c: CameraDevice) { opened.completeExceptionally(IllegalStateException("Disconnected")); c.close() }
                override fun onError(c: CameraDevice, e: Int) { opened.completeExceptionally(IllegalStateException("Error $e")); c.close() }
                override fun onClosed(c: CameraDevice) { closed.complete(Unit) }
            }, handler)
            openSubmitted = true
            val cam = opened.get(6, TimeUnit.SECONDS)
            val targets = listOf(rawReader.surface, previewReader.surface)
            val sessReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                targets.forEach(::addTarget)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
            }.build()
            cam.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    targets.map { OutputConfiguration(it) },
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { if (retiring.get()) s.close() else configured.complete(s) }
                        override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("Configure failed")); s.close() }
                    },
                ).apply { sessionParameters = sessReq },
            )
            session = configured.get(6, TimeUnit.SECONDS)
            val repReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                targets.forEach(::addTarget)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
            }.build()
            session.setRepeatingRequest(repReq, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, res: TotalCaptureResult) {
                    res.get(CaptureResult.SENSOR_TIMESTAMP)?.let { synchronized(camTimestamps) { camTimestamps += it } }
                }
            }, handler)

            Thread.sleep(3_000L)
            runCatching { session.stopRepeating() }

            val gapThresh = (1_500_000_000L / fps.coerceAtLeast(1))
            val camM = synchronized(camTimestamps) { computeTimestampMetrics(camTimestamps.toList(), gapThresh) }
            val rawM = synchronized(rawTimestamps) { computeTimestampMetrics(rawTimestamps.toList(), gapThresh) }
            json(
                "fpsTarget" to fps,
                "ok" to (rawM.frames > 10),
                "whiteLevel" to whiteLevel,
                "blackLevelPattern" to blackPattern?.toString(),
                "camCb" to camM.toJson(),
                "rawReader" to rawM.toJson(),
                "pixelStats" to pixelStats.get(),
            )
        } finally {
            retiring.set(true)
            val cc = closed.thenApply { Unit }
            lease.releaseAfter(cc)
            if (openSubmitted) {
                handler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } }
            } else closed.complete(Unit)
            runCatching { cc.get(6, TimeUnit.SECONDS) }
            runCatching { rawReader.close() }
            runCatching { previewReader.close() }
            thread.quitSafely()
            runCatching { thread.join(4_000) }
        }
    }

    // -------------------------------------------------------------------------
    // Probe 8: Production OpenCineLogGpuPipeline 15s 4K60 & 5s 4K30 + Descriptor Check
    // -------------------------------------------------------------------------
    fun runProbe8_ProductionPipelineVerification(
        context: Context,
        outDir: File,
        checkBudget: () -> Unit = {},
    ): JSONObject {
        outDir.mkdirs()
        val engine = Camera2PreviewEngine(context.applicationContext)
        val cam0 = engine.descriptors(1920, 1080).firstOrNull { it.cameraId == "0" }
        val logProfilesJson = JSONArray(cam0?.logProfiles.orEmpty().map { p ->
            json(
                "size" to p.size.toString(),
                "fps" to p.fps,
                "sourcePath" to p.sourcePath.name,
                "hfr" to p.constrainedHighSpeed,
                "huaweiCuvaHdr" to p.huaweiCuvaHdr,
            )
        })
        val has4k60Cuva = cam0?.logProfiles?.any { it.size == Size(3840, 2160) && it.fps == 60 && it.huaweiCuvaHdr } == true
        val has4k30Cuva = cam0?.logProfiles?.any { it.size == Size(3840, 2160) && it.fps == 30 && it.huaweiCuvaHdr } == true
        val has4k60VideoProfile = cam0?.videoProfiles?.any { it.size == Size(3840, 2160) && it.fps == 60 } == true
        val hasUntestedOpenGate4k60 = cam0?.logProfiles?.any { it.size.width > 3840 && it.fps >= 60 } == true

        val arms = JSONArray()
        val configs = listOf(
            Triple("8B_prod_4k60_oclog2_15s", 60, 15_000L) to "prod_4k60_oclog2_15s.mp4",
            Triple("8C_prod_4k30_oclog2_5s", 30, 5_000L) to "prod_4k30_oclog2_5s.mp4",
        )
        for ((spec, fileName) in configs) {
            val (armName, fps, durationMs) = spec
            val res = try {
                val temp = ensureCoolOrWait(context, checkBudget)
                val outFile = File(outDir, fileName)
                if (outFile.exists()) outFile.delete()
                runProbe8RecordingArm(context, armName, outFile, fps, durationMs).put("batteryStartC", temp)
            } catch (t: Throwable) {
                json(
                    "arm" to armName,
                    "ok" to false,
                    "errorClass" to t.javaClass.name,
                    "error" to (t.message ?: t.toString()).take(300),
                )
            }
            arms.put(res)
        }
        return saveProbeJson(outDir, "probe8_production_verification.json", json(
            "probe" to "Probe8_ProductionPipelineVerification",
            "supportsOpenCineLog" to (cam0?.supportsOpenCineLog == true),
            "has4k60CuvaProfile" to has4k60Cuva,
            "has4k30CuvaProfile" to has4k30Cuva,
            "has4k60VideoProfile" to has4k60VideoProfile,
            "hasUntestedOpenGate4k60" to hasUntestedOpenGate4k60,
            "logProfilesCount" to logProfilesJson.length(),
            "logProfiles" to logProfilesJson,
            "batteryEndC" to batteryTempC(context),
            "arms" to arms,
        ))
    }

    @SuppressLint("MissingPermission")
    private fun runProbe8RecordingArm(
        context: Context,
        armName: String,
        outFile: File,
        fps: Int,
        durationMs: Long,
    ): JSONObject {
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        lease.ready().get(8, TimeUnit.SECONDS)
        val manager = context.getSystemService(CameraManager::class.java)
        val caps = manager.getCameraCharacteristics("0")
        val cuvaReqKey = findVendorRequestKey(caps, "cuvaStateVideoMode")
        val cuvaResKey = findVendorResultKey(caps, "cuvaStateVideoMode")
        val cuvaHint = vendorKeyTypeHint(cuvaReqKey)
        val sensorOrientation = caps.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        val camThread = HandlerThread("p8-cam").apply { start() }
        val camHandler = Handler(camThread.looper)
        val executor = Executor { r -> camHandler.post(r) }
        val previewDrainReader = ImageReader.newInstance(1280, 720, ImageFormat.PRIVATE, 4).apply {
            setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage()?.close() } }, camHandler)
        }

        val failureMsg = AtomicReference<String?>(null)
        val refusedMsg = AtomicReference<String?>(null)
        val pipeline = OpenCineLogGpuPipeline(
            size = Size(3840, 2160),
            sourcePath = OpenCineLogSourcePath.HLG10_BT2020,
            preview = previewDrainReader.surface,
            sensorOrientationDegrees = sensorOrientation,
            displayRotationDegrees = 0,
            frontFacing = false,
            viewAssist = true,
            targetFps = fps,
            passthroughSdr = false,
            appContext = context.applicationContext,
            researchEncoderOverride = null,
            useZeroCopyVideoImageReader = true,
            onFailure = { code, msg -> failureMsg.compareAndSet(null, "$code: $msg") },
        )

        val opened = CompletableFuture<CameraDevice>()
        val closed = CompletableFuture<Unit>()
        val configured = CompletableFuture<CameraCaptureSession>()
        val retiring = AtomicBoolean()
        val cuvaAppliedFmt = AtomicReference<String?>(null)
        val cuvaObservedRes = AtomicReference<String?>(null)
        val camTimestamps = mutableListOf<Long>()
        val started = CountDownLatch(1)
        val stopped = CompletableFuture<Pair<Boolean, OpenCineLogRecordingEvidence?>>()
        var session: CameraCaptureSession? = null
        var openSubmitted = false
        var pfd: ParcelFileDescriptor? = null

        return try {
            val input = pipeline.cameraInputSurface
            manager.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { opened.complete(c); if (retiring.get()) c.close() }
                override fun onDisconnected(c: CameraDevice) { opened.completeExceptionally(IllegalStateException("Disconnected")); c.close() }
                override fun onError(c: CameraDevice, e: Int) { opened.completeExceptionally(IllegalStateException("Error $e")); c.close() }
                override fun onClosed(c: CameraDevice) { closed.complete(Unit) }
            }, camHandler)
            openSubmitted = true
            val cam = opened.get(6, TimeUnit.SECONDS)

            fun applyKeys(b: CaptureRequest.Builder) {
                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                if (cuvaReqKey != null) {
                    cuvaAppliedFmt.compareAndSet(null, setVendorByteOrIntKey(b, cuvaReqKey, 1, cuvaHint))
                }
            }

            val targets = listOf(input)
            val sessReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { targets.forEach(::addTarget); applyKeys(this) }.build()
            cam.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    targets.map { OutputConfiguration(it) },
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { if (retiring.get()) s.close() else configured.complete(s) }
                        override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("Configure failed")); s.close() }
                    },
                ).apply { sessionParameters = sessReq },
            )
            session = configured.get(6, TimeUnit.SECONDS)
            val repReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { targets.forEach(::addTarget); applyKeys(this) }.build()
            session.setRepeatingRequest(repReq, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, res: TotalCaptureResult) {
                    res.get(CaptureResult.SENSOR_TIMESTAMP)?.let { synchronized(camTimestamps) { camTimestamps += it } }
                    if (cuvaObservedRes.get() == null) readResultKeyValue(res, cuvaResKey)?.let { cuvaObservedRes.compareAndSet(null, it) }
                }
            }, camHandler)

            Thread.sleep(500L)
            pfd = ParcelFileDescriptor.open(outFile, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
            val geometry = RecordingGeometryCalculator.calculate(
                sourceSize = Size(3840, 2160),
                sensorOrientationDegrees = sensorOrientation,
                deviceOrientationDegrees = 0,
                lensFacing = CameraCharacteristics.LENS_FACING_BACK,
                mode = RecordingGeometryMode.NATIVE_RASTER,
            )
            val recCalled = pipeline.startRecording(
                output = pfd,
                bitrate = if (fps >= 60) 80_000_000 else 60_000_000,
                geometry = geometry,
                onStarted = { started.countDown() },
                onStopped = { ok, ev -> stopped.complete(ok to ev) },
                onStartRefused = { code, msg -> refusedMsg.compareAndSet(null, "$code: $msg") },
                hlgOutput = false,
                hdr10Output = false,
            )
            val startedOk = recCalled && started.await(5, TimeUnit.SECONDS)
            if (startedOk) {
                Thread.sleep(durationMs)
                pipeline.stopRecording()
            }
            val stopPair = runCatching { stopped.get(10, TimeUnit.SECONDS) }.getOrNull()
            runCatching { pipeline.recordingFileRetirement().get(6, TimeUnit.SECONDS) }
            runCatching { session.stopRepeating() }

            val ev = stopPair?.second
            val camMetrics = synchronized(camTimestamps) {
                computeTimestampMetrics(camTimestamps.toList(), if (fps >= 60) 25_000_000L else 45_000_000L)
            }
            val fileMetrics = inspectWrittenMp4(outFile)
            json(
                "arm" to armName,
                "ok" to (startedOk && stopPair?.first == true && outFile.length() > 1024L && failureMsg.get() == null && refusedMsg.get() == null),
                "clip" to outFile.name,
                "clipBytes" to outFile.length(),
                "cuvaAppliedFmt" to cuvaAppliedFmt.get(),
                "cuvaObservedRes" to cuvaObservedRes.get(),
                "recCalled" to recCalled,
                "startedOk" to startedOk,
                "stoppedOk" to stopPair?.first,
                "startRefused" to refusedMsg.get(),
                "pipelineFailure" to failureMsg.get(),
                "camCb" to camMetrics.toJson(),
                "encodedFrames" to ev?.encodedFrames,
                "maxVideoPtsGapUs" to ev?.maxVideoPtsGapUs,
                "videoPtsGapsOverThreshold" to ev?.videoPtsGapsOverThreshold,
                "eglRenderTargetBits" to ev?.eglRenderTargetBits,
                "encoderVuiTransfer" to ev?.encoderVuiTransfer,
                "containerVuiTransfer" to ev?.containerVuiTransfer,
                "codecName" to ev?.codecName,
                "codecProfile" to ev?.codecProfile,
                "fileStats" to fileMetrics,
            )
        } finally {
            retiring.set(true)
            runCatching { pipeline.stopRecording() }
            val pipeClose = runCatching { pipeline.closeAsync() }.getOrElse { CompletableFuture.completedFuture(Unit) }
            val cc = closed.thenApply { Unit }
            val totalRetire = pipeClose.thenCombine(cc) { _, _ -> Unit }
            lease.releaseAfter(totalRetire)
            if (openSubmitted) {
                camHandler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } }
            } else closed.complete(Unit)
            runCatching { totalRetire.get(8, TimeUnit.SECONDS) }
            runCatching { previewDrainReader.close() }
            runCatching { pfd?.close() }
            camThread.quitSafely()
            runCatching { camThread.join(4_000) }
        }
    }
}
