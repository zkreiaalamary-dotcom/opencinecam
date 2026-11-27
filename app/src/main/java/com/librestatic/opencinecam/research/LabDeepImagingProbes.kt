/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.media.ImageWriter
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
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
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Half
import android.util.Range
import android.util.Size
import android.view.Surface
import com.librestatic.opencinecam.camera.CaptureOwnerAdmission
import com.librestatic.opencinecam.camera.HevcSpsInspector
import com.librestatic.opencinecam.camera.OpenCineLogGpuPipeline
import com.librestatic.opencinecam.media.raw.NativeRawCodec
import com.librestatic.opencinecam.media.video.EglHardwareBufferBridge
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/**
 * Isolated Wave A Computational Imaging & Cinema Capability Probes (Probes 9-12)
 * on Huawei Pura 80 Pro (`LMR-LX9`, Kirin 9020, Maleoon 920, API 31).
 */
internal object LabDeepImagingProbes {
    private const val EGL_CONTEXT_PRIORITY_LEVEL_IMG = 0x3100
    private const val EGL_CONTEXT_PRIORITY_HIGH_IMG = 0x3101
    private const val EGL_GL_COLORSPACE_KHR = 0x309D
    private const val EGL_GL_COLORSPACE_BT2020_LINEAR_EXT = 0x333F
    private const val EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3340
    private const val EGL_GL_COLORSPACE_BT2020_HLG_EXT = 0x3540
    private const val EGL_RECORDABLE_ANDROID = 0x3142
    private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
    private const val COLOR_FORMAT_HISI_YUV420SP_10BIT = 0x7F000011
    private const val COLOR_FORMAT_HISI_YUV420SP = 0x7F00000F
    private const val FORMAT_CUVA_0x324 = 0x324
    private const val FORMAT_YCBCR_P010 = 54
    private const val FORMAT_RGBA_1010102 = 43
    private const val FORMAT_RGBA_FP16 = 22
    // Android ADataSpace bitfields safe on API 31 without android.hardware.DataSpace (API 33).
    private const val DS_BT2020_LINEAR_FULL = (6 shl 16) or (1 shl 22) or (1 shl 27)
    private const val DS_BT2020_HLG_LIMITED = (6 shl 16) or (7 shl 22) or (2 shl 27)
    private const val DS_BT2020_HLG_FULL = (6 shl 16) or (7 shl 22) or (1 shl 27)

    private val FULLSCREEN_QUAD = floatArrayOf(
        -1f, -1f,
         1f, -1f,
        -1f,  1f,
         1f,  1f,
    )

    private const val VERTEX_QUAD = """#version 300 es
layout(location = 0) in vec2 aPosition;
out vec2 vTexCoord;
void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
    vTexCoord = vec2((aPosition.x + 1.0) * 0.5, 1.0 - (aPosition.y + 1.0) * 0.5);
}"""

    private const val VERTEX_QUAD_UPRIGHT = """#version 300 es
layout(location = 0) in vec2 aPosition;
out vec2 vTexCoord;
void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
    vTexCoord = (aPosition + 1.0) * 0.5;
}"""

    // =========================================================================
    // Probe 9: True Source Precision (RGBA16F / RGBA10_A2 Readback) & CUVA 0x324 Transfer
    // =========================================================================
    fun runProbe9_SourcePrecisionAndCuvaTransfer(
        context: Context,
        outDir: File,
        checkBudget: () -> Unit = {},
    ): JSONObject {
        outDir.mkdirs()
        val lockedAe = runCatching { LabBreakthroughProbes.warmupAndLockExposure(context) }.getOrNull()
        val baseExp = lockedAe?.first ?: 10_000_000L
        val baseIso = lockedAe?.second ?: 200
        val specs = listOf(
            QuadSpec("9A_sdr_0x22_oes_base", cuva = false, yuvRawTarget = false, expNs = baseExp, iso = baseIso),
            QuadSpec("9B_sdr_0x22_yuv_raw_base", cuva = false, yuvRawTarget = true, expNs = baseExp, iso = baseIso),
            QuadSpec("9C_cuva_0x324_oes_base", cuva = true, yuvRawTarget = false, expNs = baseExp, iso = baseIso),
            QuadSpec("9D_cuva_0x324_yuv_raw_base", cuva = true, yuvRawTarget = true, expNs = baseExp, iso = baseIso),
            QuadSpec("9E_cuva_0x324_oes_minus2ev", cuva = true, yuvRawTarget = false, expNs = (baseExp / 4L).coerceAtLeast(100_000L), iso = baseIso),
            QuadSpec("9F_cuva_0x324_oes_plus2ev", cuva = true, yuvRawTarget = false, expNs = (baseExp * 4L).coerceAtMost(32_000_000L), iso = baseIso),
        )
        val arms = JSONArray()
        for (s in specs) {
            val res = try {
                val temp = LabBreakthroughProbes.ensureCoolOrWait(context, checkBudget)
                runProbe9Arm(context, s).put("batteryStartC", temp)
            } catch (t: Throwable) {
                json("arm" to s.arm, "ok" to false, "errorClass" to t.javaClass.name, "error" to (t.message ?: t.toString()).take(300))
            }
            arms.put(res)
        }
        return LabBreakthroughProbes.saveProbeJson(outDir, "probe9_source_precision.json", json(
            "probe" to "Probe9_SourcePrecisionAndCuvaTransfer",
            "lockedBaseAe" to json("expNs" to baseExp, "iso" to baseIso),
            "batteryEndC" to LabBreakthroughProbes.batteryTempC(context),
            "arms" to arms,
        ))
    }

    private data class QuadSpec(val arm: String, val cuva: Boolean, val yuvRawTarget: Boolean, val expNs: Long, val iso: Int)

    @SuppressLint("MissingPermission")
    private fun runProbe9Arm(context: Context, spec: QuadSpec): JSONObject {
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        lease.ready().get(8, TimeUnit.SECONDS)
        val manager = context.getSystemService(CameraManager::class.java)
        val caps = manager.getCameraCharacteristics("0")
        val cuvaReqKey = LabBreakthroughProbes.findVendorRequestKey(caps, "cuvaStateVideoMode")
        val cuvaHint = LabBreakthroughProbes.vendorKeyTypeHint(cuvaReqKey)

        val camThread = HandlerThread("p9-cam").apply { start() }
        val camHandler = Handler(camThread.looper)
        val glThread = HandlerThread("p9-gl").apply { start() }
        val glHandler = Handler(glThread.looper)
        val executor = Executor { r -> camHandler.post(r) }

        val w = 1920
        val h = 1080
        val outW = 480
        val outH = 270
        val reader = LabBreakthroughProbes.createUsageImageReader(
            w, h, ImageFormat.PRIVATE, 4,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_VIDEO_ENCODE,
        )

        val frameCount = AtomicInteger(0)
        val analysisFuture = CompletableFuture<JSONObject>()
        val opened = CompletableFuture<CameraDevice>()
        val closed = CompletableFuture<Unit>()
        val configured = CompletableFuture<CameraCaptureSession>()
        val retiring = AtomicBoolean(false)
        val observedExp = AtomicLong(-1L)
        val observedIso = AtomicInteger(-1)

        var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglPbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
        var texExt = 0
        var texFbo = 0
        var fbo = 0
        var prog = 0
        var isRgba16f = false
        var quadBuf: FloatBuffer? = null

        val fragOes = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTexture;
in vec2 vTexCoord;
out vec4 outColor;
void main() { outColor = texture(uTexture, vTexCoord); }"""

        val fragYuv = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
#extension GL_EXT_YUV_target : require
precision highp float;
uniform __samplerExternal2DY2YEXT uTexture;
in vec2 vTexCoord;
out vec4 outColor;
void main() { outColor = texture(uTexture, vTexCoord); }"""

        val glReady = CompletableFuture<Unit>()
        glHandler.post {
            try {
                eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                val ver = IntArray(2)
                check(EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1))
                val cfgs = arrayOfNulls<EGLConfig>(1)
                val cnt = IntArray(1)
                EGL14.eglChooseConfig(
                    eglDisplay,
                    intArrayOf(
                        EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                        EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE,
                    ),
                    0, cfgs, 0, 1, cnt, 0,
                )
                eglContext = EGL14.eglCreateContext(
                    eglDisplay, requireNotNull(cfgs[0]), EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
                )
                eglPbuffer = EGL14.eglCreatePbufferSurface(
                    eglDisplay, cfgs[0],
                    intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0,
                )
                check(EGL14.eglMakeCurrent(eglDisplay, eglPbuffer, eglPbuffer, eglContext))
                val glExts = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
                if (spec.yuvRawTarget && !glExts.contains("GL_EXT_YUV_target")) {
                    throw IllegalStateException("GL_EXT_YUV_target unsupported")
                }
                prog = LabBreakthroughProbes.compileGlProgram(VERTEX_QUAD_UPRIGHT, if (spec.yuvRawTarget) fragYuv else fragOes)
                val t = IntArray(2)
                GLES30.glGenTextures(2, t, 0)
                texExt = t[0]
                texFbo = t[1]
                GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texExt)
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
                val fb = IntArray(1)
                GLES30.glGenFramebuffers(1, fb, 0)
                fbo = fb[0]
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texFbo)
                // Prefer RGBA16F floating-point FBO when EXT_color_buffer_float is supported; otherwise RGBA10_A2.
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, outW, outH, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texFbo, 0)
                if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                    isRgba16f = true
                } else {
                    while (GLES30.glGetError() != GLES30.GL_NO_ERROR) {}
                    GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGB10_A2, outW, outH, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_INT_2_10_10_10_REV, null)
                    check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "Neither RGBA16F nor RGBA10_A2 FBO complete" }
                    isRgba16f = false
                }
                quadBuf = ByteBuffer.allocateDirect(FULLSCREEN_QUAD.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                    put(FULLSCREEN_QUAD).position(0)
                }
                glReady.complete(Unit)
            } catch (t: Throwable) {
                glReady.completeExceptionally(t)
            }
        }
        reader.setOnImageAvailableListener({ r ->
            val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val n = frameCount.incrementAndGet()
                if (n != 15 || analysisFuture.isDone) return@setOnImageAvailableListener
                val hb = img.hardwareBuffer ?: return@setOnImageAvailableListener
                val desc = EglHardwareBufferBridge.describeAhb(hb)
                val eglImg = EglHardwareBufferBridge.createEglImage(hb)
                try {
                    check(eglImg != 0L) { "createEglImage failed" }
                    EGL14.eglMakeCurrent(eglDisplay, eglPbuffer, eglPbuffer, eglContext)
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
                    GLES30.glViewport(0, 0, outW, outH)
                    GLES30.glUseProgram(prog)
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                    GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texExt)
                    val bindRc = EglHardwareBufferBridge.bindExternalTexture(eglImg)
                    check(bindRc == 0) { "bindExternalTexture err=0x${bindRc.toString(16)}" }
                    val qb = requireNotNull(quadBuf)
                    qb.position(0)
                    GLES30.glEnableVertexAttribArray(0)
                    GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, qb)
                    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                    GLES30.glFinish()

                    val analysis = analyzeReadbackPrecise(outW, outH, isRgba16f, desc?.first, desc?.second)
                    analysisFuture.complete(analysis)
                } finally {
                    if (eglImg != 0L) EglHardwareBufferBridge.destroyEglImage(eglImg)
                    hb.close()
                }
            } catch (t: Throwable) {
                analysisFuture.completeExceptionally(t)
            } finally {
                img.close()
            }
        }, glHandler)

        var session: CameraCaptureSession? = null
        var openSubmitted = false
        return try {
            glReady.get(5, TimeUnit.SECONDS)
            manager.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { opened.complete(c); if (retiring.get()) c.close() }
                override fun onDisconnected(c: CameraDevice) { opened.completeExceptionally(IllegalStateException("Disconnected")); c.close() }
                override fun onError(c: CameraDevice, e: Int) { opened.completeExceptionally(IllegalStateException("Err $e")); c.close() }
                override fun onClosed(c: CameraDevice) { closed.complete(Unit) }
            }, camHandler)
            openSubmitted = true
            val cam = opened.get(6, TimeUnit.SECONDS)
            fun applyKeys(b: CaptureRequest.Builder) {
                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, spec.expNs)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, spec.iso)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, 33_333_333L)
                b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                if (spec.cuva && cuvaReqKey != null) {
                    setVendorByteOrIntKey(b, cuvaReqKey, 1, cuvaHint)
                }
            }
            val sessReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(reader.surface); applyKeys(this)
            }.build()
            cam.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(reader.surface)),
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { if (retiring.get()) s.close() else configured.complete(s) }
                        override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("Configure failed")); s.close() }
                    },
                ).apply { sessionParameters = sessReq },
            )
            session = configured.get(6, TimeUnit.SECONDS)
            val repReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(reader.surface); applyKeys(this)
            }.build()
            session.setRepeatingRequest(repReq, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                    res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { observedExp.compareAndSet(-1L, it) }
                    res.get(CaptureResult.SENSOR_SENSITIVITY)?.let { observedIso.compareAndSet(-1, it) }
                }
            }, camHandler)
            val stats = analysisFuture.get(6, TimeUnit.SECONDS)
            runCatching { session.stopRepeating() }
            json(
                "arm" to spec.arm,
                "ok" to true,
                "cuva" to spec.cuva,
                "yuvRawTarget" to spec.yuvRawTarget,
                "reqExpNs" to spec.expNs,
                "reqIso" to spec.iso,
                "obsExpNs" to observedExp.get(),
                "obsIso" to observedIso.get(),
                "precision" to stats,
            )
        } finally {
            retiring.set(true)
            val cc = closed.thenApply { Unit }
            lease.releaseAfter(cc)
            if (openSubmitted) camHandler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } } else closed.complete(Unit)
            runCatching { cc.get(6, TimeUnit.SECONDS) }
            runCatching { reader.close() }
            val glDone = CompletableFuture<Unit>()
            glHandler.post {
                runCatching {
                    if (prog != 0) GLES30.glDeleteProgram(prog)
                    if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
                    if (texExt != 0 || texFbo != 0) GLES30.glDeleteTextures(2, intArrayOf(texExt, texFbo), 0)
                    if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                        if (eglPbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglPbuffer)
                        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                        EGL14.eglTerminate(eglDisplay)
                    }
                }
                glDone.complete(Unit)
            }
            runCatching { glDone.get(4, TimeUnit.SECONDS) }
            camThread.quitSafely(); glThread.quitSafely()
            runCatching { camThread.join(4_000) }; runCatching { glThread.join(4_000) }
        }
    }

    private fun analyzeReadbackPrecise(w: Int, h: Int, isRgba16f: Boolean, hwFmt: Int?, hwUsage: Long?): JSONObject {
        val pixels = w * h
        val ch0 = FloatArray(pixels)
        val ch1 = FloatArray(pixels)
        val ch2 = FloatArray(pixels)
        val fmtLabel: String
        if (isRgba16f) {
            val buf = ByteBuffer.allocateDirect(pixels * 4 * 4).order(ByteOrder.nativeOrder())
            while (GLES30.glGetError() != GLES30.GL_NO_ERROR) {}
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_FLOAT, buf)
            val err = GLES30.glGetError()
            if (err == GLES30.GL_NO_ERROR) {
                fmtLabel = "RGBA16F_GL_FLOAT"
                val fb = buf.asFloatBuffer()
                for (i in 0 until pixels) {
                    val base = i * 4
                    ch0[i] = fb.get(base)
                    ch1[i] = fb.get(base + 1)
                    ch2[i] = fb.get(base + 2)
                }
            } else {
                // Fallback to HALF_FLOAT readback
                val hbuf = ByteBuffer.allocateDirect(pixels * 4 * 2).order(ByteOrder.nativeOrder())
                GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, hbuf)
                fmtLabel = "RGBA16F_GL_HALF_FLOAT"
                val sb = hbuf.asShortBuffer()
                for (i in 0 until pixels) {
                    val base = i * 4
                    ch0[i] = Half.toFloat(sb.get(base))
                    ch1[i] = Half.toFloat(sb.get(base + 1))
                    ch2[i] = Half.toFloat(sb.get(base + 2))
                }
            }
        } else {
            val ibuf = ByteBuffer.allocateDirect(pixels * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_INT_2_10_10_10_REV, ibuf)
            fmtLabel = "RGBA10_A2"
            val ib = ibuf.asIntBuffer()
            for (i in 0 until pixels) {
                val p = ib.get(i)
                ch0[i] = (p and 0x3FF) / 1023f
                ch1[i] = ((p ushr 10) and 0x3FF) / 1023f
                ch2[i] = ((p ushr 20) and 0x3FF) / 1023f
            }
        }
        val ch1Sorted = ch1.copyOf().apply { sort() }
        val seen10 = BooleanArray(1024)
        val seen8 = BooleanArray(256)
        val mod4 = IntArray(4)
        var non8BitCount = 0
        var sum0 = 0.0; var sum1 = 0.0; var sum2 = 0.0
        var clipHigh = 0; var clipLow = 0
        val distinctFloats = HashSet<Int>(4096)
        for (i in 0 until pixels) {
            val r = ch0[i]; val g = ch1[i]; val b = ch2[i]
            sum0 += r; sum1 += g; sum2 += b
            if (g >= 0.995f) clipHigh++
            if (g <= 0.005f) clipLow++
            val c10 = (g * 1023f).roundToInt().coerceIn(0, 1023)
            val c8 = (g * 255f).roundToInt().coerceIn(0, 255)
            seen10[c10] = true
            seen8[c8] = true
            mod4[c10 and 3]++
            if (abs(g * 255f - c8) > 0.08f) non8BitCount++
            if (distinctFloats.size < 4096) distinctFloats.add(g.toRawBits())
        }
        return json(
            "fboFormat" to fmtLabel,
            "hwBufferFormat" to hwFmt,
            "hwBufferFormatHex" to hwFmt?.let { "0x${it.toString(16)}" },
            "hwBufferUsageHex" to hwUsage?.let { "0x${it.toString(16)}" },
            "pixels" to pixels,
            "ch0Mean" to sum0 / pixels,
            "ch1Mean" to sum1 / pixels,
            "ch2Mean" to sum2 / pixels,
            "ch1Min" to ch1Sorted.first(),
            "ch1P01" to ch1Sorted[pixels / 100],
            "ch1P50" to ch1Sorted[pixels / 2],
            "ch1P99" to ch1Sorted[pixels * 99 / 100],
            "ch1Max" to ch1Sorted.last(),
            "clipHighFrac" to clipHigh.toDouble() / pixels,
            "clipLowFrac" to clipLow.toDouble() / pixels,
            "distinct10BitCodesG" to seen10.count { it },
            "distinct8BitCodesG" to seen8.count { it },
            "distinctFloatBitsSampleCapped4096" to distinctFloats.size,
            "non8BitGridFractionG" to non8BitCount.toDouble() / pixels,
            "mod4Distribution10Bit" to JSONArray(mod4.map { it.toDouble() / pixels }),
        )
    }

    // =========================================================================
    // Probe 10: Escaping egl8 -> End-to-End 10-Bit Output -> OMX.hisi Main10 L6.2
    // =========================================================================
    fun runProbe10_True10BitOutputEscape(
        context: Context,
        outDir: File,
        checkBudget: () -> Unit = {},
    ): JSONObject {
        outDir.mkdirs()
        val arms = JSONArray()

        // 10A: AHardwareBuffer capability & GL_EXT_YUV_target renderbuffer probe
        arms.put(try {
            LabBreakthroughProbes.ensureCoolOrWait(context, checkBudget)
            runProbe10A_AhbAndYuvRenderbuffer()
        } catch (t: Throwable) {
            json("arm" to "10A_ahb_and_yuv_rbo", "ok" to false, "error" to (t.message ?: t.toString()).take(300))
        })

        // 10B: config10 + ANativeWindow_setBuffersDataSpace & setBuffersGeometry sweep
        val cfg10Specs = listOf(
            Triple("10B_cfg10_hlg_ds", EGL_GL_COLORSPACE_BT2020_HLG_EXT, DS_BT2020_HLG_LIMITED),
            Triple("10B_cfg10_bt2020lin_full", EGL_GL_COLORSPACE_BT2020_LINEAR_EXT, DS_BT2020_LINEAR_FULL),
            Triple("10B_cfg10_none_bt2020_ds", 0, DS_BT2020_HLG_FULL),
        )
        for ((name, eglCs, nativeDs) in cfg10Specs) {
            arms.put(try {
                LabBreakthroughProbes.ensureCoolOrWait(context, checkBudget)
                val out = File(outDir, "${name}.mp4").apply { delete() }
                runProbe10B_Config10WithDataSpace(name, out, eglCs, nativeDs)
            } catch (t: Throwable) {
                json("arm" to name, "ok" to false, "error" to (t.message ?: t.toString()).take(300))
            })
        }

        // 10C: ImageWriter P010 / 0x324 directly into OMX.hisi Main10 Input Surface
        for ((name, fmt, fileName) in listOf(
            Triple("10C_imagewriter_p010", FORMAT_YCBCR_P010, "p10_iw_p010.mp4"),
            Triple("10C_imagewriter_0x324", FORMAT_CUVA_0x324, "p10_iw_0x324.mp4"),
        )) {
            arms.put(try {
                LabBreakthroughProbes.ensureCoolOrWait(context, checkBudget)
                val out = File(outDir, fileName).apply { delete() }
                runProbe10C_ImageWriterMain10(name, fmt, out)
            } catch (t: Throwable) {
                json("arm" to name, "ok" to false, "error" to (t.message ?: t.toString()).take(300))
            })
        }

        // 10D: ByteBuffer 0x7F000011 with explicit KEY_MAX_INPUT_SIZE at 1080p & 4K
        for ((name, sz, fileName) in listOf(
            Triple("10D_bb_0x7f11_1080p_maxinput", Size(1920, 1080), "p10_bb_0x7f11_1080p.mp4"),
            Triple("10D_bb_0x7f11_4k_maxinput", Size(3840, 2160), "p10_bb_0x7f11_4k.mp4"),
        )) {
            arms.put(try {
                LabBreakthroughProbes.ensureCoolOrWait(context, checkBudget)
                val out = File(outDir, fileName).apply { delete() }
                runProbe10D_ByteBufferFullFrame(name, sz, out)
            } catch (t: Throwable) {
                json("arm" to name, "ok" to false, "error" to (t.message ?: t.toString()).take(300))
            })
        }

        // 10E: Synthetic 10-bit ramp via egl8 nodither vs TPDF dither
        for ((name, tpdf, fileName) in listOf(
            Triple("10E_egl8_nodither_ramp", false, "p10_egl8_nodither.mp4"),
            Triple("10E_egl8_tpdf_dither_ramp", true, "p10_egl8_tpdf.mp4"),
        )) {
            arms.put(try {
                LabBreakthroughProbes.ensureCoolOrWait(context, checkBudget)
                val out = File(outDir, fileName).apply { delete() }
                runProbe10E_Egl8DitherRamp(name, out, tpdf)
            } catch (t: Throwable) {
                json("arm" to name, "ok" to false, "error" to (t.message ?: t.toString()).take(300))
            })
        }

        return LabBreakthroughProbes.saveProbeJson(outDir, "probe10_true10bit_output.json", json(
            "probe" to "Probe10_True10BitOutputEscape",
            "bridgeExtMask" to EglHardwareBufferBridge.probeExtensions(),
            "batteryEndC" to LabBreakthroughProbes.batteryTempC(context),
            "arms" to arms,
        ))
    }

    private fun runProbe10A_AhbAndYuvRenderbuffer(): JSONObject {
        val formats = listOf(1, 22, 43, 54, 34, 35, 0x324, COLOR_FORMAT_HISI_YUV420SP, COLOR_FORMAT_HISI_YUV420SP_10BIT)
        val usages = listOf(
            "GPU_OUT_AND_VIDEO" to (HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_VIDEO_ENCODE),
            "GPU_OUT_AND_SAMPLED" to (HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE),
            "CPU_WRITE_AND_VIDEO" to (HardwareBuffer.USAGE_CPU_WRITE_OFTEN or HardwareBuffer.USAGE_VIDEO_ENCODE),
        )
        val matrix = JSONArray()
        for (fmt in formats) {
            for ((uLabel, usage) in usages) {
                val sup = EglHardwareBufferBridge.isAhbSupported(1920, 1080, fmt, usage)
                matrix.put(json("format" to fmt, "formatHex" to "0x${fmt.toString(16)}", "usage" to uLabel, "supported" to sup))
            }
        }
        // Test GL_EXT_YUV_target renderbuffer attachment on YUV AHardwareBuffer formats (54, 35, 34, 0x324)
        var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
        var pb: EGLSurface = EGL14.EGL_NO_SURFACE
        val yuvRboTests = JSONArray()
        try {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val v = IntArray(2)
            check(EGL14.eglInitialize(dpy, v, 0, v, 1))
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val c = IntArray(1)
            EGL14.eglChooseConfig(dpy, intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE,
            ), 0, cfgs, 0, 1, c, 0)
            ctx = EGL14.eglCreateContext(dpy, requireNotNull(cfgs[0]), EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            pb = EGL14.eglCreatePbufferSurface(dpy, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(dpy, pb, pb, ctx))
            val rboShaderCompiles = runCatching {
                val p = LabBreakthroughProbes.compileGlProgram(VERTEX_QUAD_UPRIGHT, """#version 300 es
#extension GL_EXT_YUV_target : require
precision highp float;
in vec2 vTexCoord;
layout(yuv) out vec4 outColor;
void main() { outColor = vec4(vTexCoord.x, 0.5, 0.5, 1.0); }""")
                GLES30.glDeleteProgram(p)
                true
            }.getOrDefault(false)

            for (fmt in listOf(FORMAT_YCBCR_P010, 0x324, 35, 34)) {
                val usage = HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_VIDEO_ENCODE
                val hb = EglHardwareBufferBridge.allocateAhb(256, 256, fmt, usage)
                    ?: EglHardwareBufferBridge.allocateAhb(256, 256, fmt, HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
                if (hb == null) {
                    yuvRboTests.put(json("format" to fmt, "formatHex" to "0x${fmt.toString(16)}", "allocated" to false))
                    continue
                }
                val img = EglHardwareBufferBridge.createEglImage(hb)
                val rboArr = IntArray(1)
                val fboArr = IntArray(1)
                var rboBindRc = -99
                var fboStatus = -1
                if (img != 0L) {
                    GLES30.glGenRenderbuffers(1, rboArr, 0)
                    GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rboArr[0])
                    rboBindRc = EglHardwareBufferBridge.bindRenderbuffer(img)
                    if (rboBindRc == 0) {
                        GLES30.glGenFramebuffers(1, fboArr, 0)
                        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboArr[0])
                        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, rboArr[0])
                        fboStatus = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
                    }
                }
                val ahbDesc = EglHardwareBufferBridge.describeAhb(hb)
                yuvRboTests.put(json(
                    "format" to fmt, "formatHex" to "0x${fmt.toString(16)}", "allocated" to true,
                    "actualFmt" to ahbDesc?.first, "actualFmtHex" to ahbDesc?.first?.let { "0x${it.toString(16)}" },
                    "eglImageOk" to (img != 0L), "rboBindErr" to rboBindRc,
                    "fboStatusHex" to "0x${fboStatus.toString(16)}",
                    "fboComplete" to (fboStatus == GLES30.GL_FRAMEBUFFER_COMPLETE),
                    "yuvOutShaderCompiles" to rboShaderCompiles,
                ))
                if (fboArr[0] != 0) GLES30.glDeleteFramebuffers(1, fboArr, 0)
                if (rboArr[0] != 0) GLES30.glDeleteRenderbuffers(1, rboArr, 0)
                if (img != 0L) EglHardwareBufferBridge.destroyEglImage(img)
                hb.close()
            }
        } finally {
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (pb != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, pb)
                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
                EGL14.eglTerminate(dpy)
            }
        }
        return json("arm" to "10A_ahb_and_yuv_rbo", "ok" to true, "ahbSupportMatrix" to matrix, "yuvRenderbufferTests" to yuvRboTests)
    }

    private fun runProbe10B_Config10WithDataSpace(armName: String, outFile: File, eglColorSpace: Int, nativeDataSpace: Int): JSONObject {
        val w = 1920; val h = 1080; val frames = 30
        val codecName = LabBreakthroughProbes.selectHevcEncoderName()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h).apply {
            setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_LEVEL, CodecProfileLevel.HEVCMainTierLevel62)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 40_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
        }
        val codec = MediaCodec.createByCodecName(codecName)
        var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
        var win: EGLSurface = EGL14.EGL_NO_SURFACE
        var surface: Surface? = null
        var muxer: MediaMuxer? = null
        var started = false
        var muxerStarted = false
        val encodedSamples = AtomicInteger(0)
        return try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            val dsRc = if (nativeDataSpace != 0) EglHardwareBufferBridge.setSurfaceGeometryAndDataSpace(surface, 0, 0, 0, nativeDataSpace) else 0
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val v = IntArray(2)
            check(EGL14.eglInitialize(dpy, v, 0, v, 1))
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val cnt = IntArray(1)
            val okCfg = EGL14.eglChooseConfig(dpy, intArrayOf(
                EGL14.EGL_RED_SIZE, 10, EGL14.EGL_GREEN_SIZE, 10, EGL14.EGL_BLUE_SIZE, 10, EGL14.EGL_ALPHA_SIZE, 2,
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
            ), 0, cfgs, 0, 1, cnt, 0)
            check(okCfg && cnt[0] > 0) { "No config10" }
            ctx = EGL14.eglCreateContext(dpy, requireNotNull(cfgs[0]), EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            val winAttrs = if (eglColorSpace != 0) intArrayOf(EGL_GL_COLORSPACE_KHR, eglColorSpace, EGL14.EGL_NONE) else intArrayOf(EGL14.EGL_NONE)
            win = EGL14.eglCreateWindowSurface(dpy, cfgs[0], surface, winAttrs, 0)
            check(win != EGL14.EGL_NO_SURFACE) { "WindowSurface config10 failed eglErr=0x${EGL14.eglGetError().toString(16)}" }
            check(EGL14.eglMakeCurrent(dpy, win, win, ctx))
            val prog = LabBreakthroughProbes.compileGlProgram(VERTEX_QUAD_UPRIGHT, """#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 outColor;
void main() { outColor = vec4(vTexCoord.x, vTexCoord.x, vTexCoord.x, 1.0); }""")
            val qb = ByteBuffer.allocateDirect(FULLSCREEN_QUAD.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(FULLSCREEN_QUAD).position(0) }
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.start(); started = true
            val info = MediaCodec.BufferInfo()
            var track = -1
            for (f in 0 until frames) {
                GLES30.glViewport(0, 0, w, h)
                GLES30.glUseProgram(prog)
                qb.position(0)
                GLES30.glEnableVertexAttribArray(0)
                GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, qb)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                EGLExt.eglPresentationTimeANDROID(dpy, win, f * 33_333_333L)
                EGL14.eglSwapBuffers(dpy, win)
                while (true) {
                    val idx = codec.dequeueOutputBuffer(info, 0)
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true }
                    } else if (idx >= 0) {
                        val b = codec.getOutputBuffer(idx)
                        if (b != null && info.size > 0 && muxerStarted && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            b.position(info.offset); b.limit(info.offset + info.size)
                            muxer.writeSampleData(track, b, info); encodedSamples.incrementAndGet()
                        }
                        codec.releaseOutputBuffer(idx, false)
                    } else break
                }
            }
            codec.signalEndOfInputStream()
            val dl = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < dl) {
                val idx = codec.dequeueOutputBuffer(info, 10_000L)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true }
                } else if (idx >= 0) {
                    val b = codec.getOutputBuffer(idx)
                    if (b != null && info.size > 0 && muxerStarted && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        b.position(info.offset); b.limit(info.offset + info.size)
                        muxer.writeSampleData(track, b, info); encodedSamples.incrementAndGet()
                    }
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(idx, false)
                    if (eos) break
                }
            }
            GLES30.glDeleteProgram(prog)
            val mp4 = LabBreakthroughProbes.inspectWrittenMp4(outFile)
            json("arm" to armName, "ok" to (encodedSamples.get() > 0), "nativeDsRc" to dsRc, "encodedSamples" to encodedSamples.get(), "clipBytes" to outFile.length(), "fileStats" to mp4)
        } finally {
            if (started) runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (win != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, win)
                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
                EGL14.eglTerminate(dpy)
            }
            runCatching { surface?.release() }
        }
    }

    private fun runProbe10C_ImageWriterMain10(armName: String, imageFormat: Int, outFile: File): JSONObject {
        val w = 1920; val h = 1080; val frames = 30
        val codecName = LabBreakthroughProbes.selectHevcEncoderName()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h).apply {
            setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_LEVEL, CodecProfileLevel.HEVCMainTierLevel62)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 40_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
        }
        val codec = MediaCodec.createByCodecName(codecName)
        var surface: Surface? = null
        var writer: ImageWriter? = null
        var muxer: MediaMuxer? = null
        var started = false; var muxerStarted = false
        val samples = AtomicInteger(0)
        return try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            val geomRc = EglHardwareBufferBridge.setSurfaceGeometryAndDataSpace(
                surface, w, h, imageFormat,
                DS_BT2020_HLG_FULL,
            )
            writer = ImageWriter.newInstance(surface, 4, imageFormat)
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.start(); started = true
            var filledPlanes = false
            var ahbFmtHex: String? = null
            val info = MediaCodec.BufferInfo(); var track = -1
            for (f in 0 until frames) {
                val img = writer.dequeueInputImage()
                val planes = runCatching { img.planes }.getOrNull()
                if (planes != null && planes.isNotEmpty() && planes.size >= 3) {
                    filledPlanes = true
                    val yBuf = planes[0].buffer; val yStride = planes[0].rowStride; val yPix = planes[0].pixelStride
                    val uBuf = planes[1].buffer; val vBuf = planes[2].buffer
                    for (yy in 0 until h) {
                        for (xx in 0 until w) {
                            val v10 = (xx * 1023) / (w - 1)
                            val pos = yy * yStride + xx * yPix
                            val msb16 = (v10 shl 6) and 0xFFFF
                            yBuf.put(pos, (msb16 and 0xFF).toByte())
                            yBuf.put(pos + 1, ((msb16 ushr 8) and 0xFF).toByte())
                        }
                    }
                    val uvStride = planes[1].rowStride; val uvPix = planes[1].pixelStride
                    for (yy in 0 until h / 2) {
                        for (xx in 0 until w / 2) {
                            val pos = yy * uvStride + xx * uvPix
                            uBuf.put(pos, 0); uBuf.put(pos + 1, 0x80.toByte())
                            vBuf.put(pos, 0); vBuf.put(pos + 1, 0x80.toByte())
                        }
                    }
                }
                if (ahbFmtHex == null) {
                    img.hardwareBuffer?.use { hb -> ahbFmtHex = EglHardwareBufferBridge.describeAhb(hb)?.first?.let { "0x${it.toString(16)}" } }
                }
                img.timestamp = f * 33_333_333L
                writer.queueInputImage(img)
                while (true) {
                    val idx = codec.dequeueOutputBuffer(info, 0)
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true }
                    } else if (idx >= 0) {
                        val b = codec.getOutputBuffer(idx)
                        if (b != null && info.size > 0 && muxerStarted && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            b.position(info.offset); b.limit(info.offset + info.size)
                            muxer.writeSampleData(track, b, info); samples.incrementAndGet()
                        }
                        codec.releaseOutputBuffer(idx, false)
                    } else break
                }
            }
            codec.signalEndOfInputStream()
            val dl = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < dl) {
                val idx = codec.dequeueOutputBuffer(info, 10_000L)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true }
                } else if (idx >= 0) {
                    val b = codec.getOutputBuffer(idx)
                    if (b != null && info.size > 0 && muxerStarted && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        b.position(info.offset); b.limit(info.offset + info.size)
                        muxer.writeSampleData(track, b, info); samples.incrementAndGet()
                    }
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(idx, false)
                    if (eos) break
                }
            }
            json(
                "arm" to armName, "ok" to (samples.get() > 0 && filledPlanes),
                "geomRc" to geomRc, "filledPlanes" to filledPlanes, "ahbFmtHex" to ahbFmtHex,
                "encodedSamples" to samples.get(), "clipBytes" to outFile.length(),
                "fileStats" to LabBreakthroughProbes.inspectWrittenMp4(outFile),
            )
        } finally {
            runCatching { writer?.close() }
            if (started) runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { surface?.release() }
        }
    }

    private fun runProbe10D_ByteBufferFullFrame(armName: String, size: Size, outFile: File): JSONObject {
        val w = size.width; val h = size.height; val frames = 30
        val expectedP010 = w * h * 3
        val codecName = LabBreakthroughProbes.selectHevcEncoderName()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h).apply {
            setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_LEVEL, CodecProfileLevel.HEVCMainTierLevel62)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FORMAT_HISI_YUV420SP_10BIT)
            setInteger(MediaFormat.KEY_BIT_RATE, if (w >= 3840) 60_000_000 else 30_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, expectedP010)
        }
        val codec = MediaCodec.createByCodecName(codecName)
        var muxer: MediaMuxer? = null
        var started = false; var muxerStarted = false
        val samples = AtomicInteger(0)
        var capObserved = -1
        return try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.start(); started = true
            val p010 = ByteArray(expectedP010)
            val bb = ByteBuffer.wrap(p010).order(ByteOrder.LITTLE_ENDIAN)
            for (yy in 0 until h) {
                for (xx in 0 until w) {
                    val v10 = (xx * 1023) / (w - 1)
                    bb.putShort(((v10 shl 6) and 0xFFFF).toShort())
                }
            }
            for (i in 0 until (w * h / 2)) bb.putShort(0x8000.toShort())

            val info = MediaCodec.BufferInfo()
            var submitted = 0; var eosQueued = false; var eosRecv = false; var track = -1
            val t0 = System.nanoTime()
            val dl = System.currentTimeMillis() + 15_000L
            while (!eosRecv && System.currentTimeMillis() < dl) {
                if (!eosQueued) {
                    val inIdx = codec.dequeueInputBuffer(5_000L)
                    if (inIdx >= 0) {
                        if (submitted >= frames) {
                            codec.queueInputBuffer(inIdx, 0, 0, submitted * 33_333L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosQueued = true
                        } else {
                            val buf = codec.getInputBuffer(inIdx)!!
                            if (capObserved < 0) capObserved = buf.capacity()
                            val n = p010.size.coerceAtMost(buf.capacity())
                            buf.clear(); buf.put(p010, 0, n)
                            codec.queueInputBuffer(inIdx, 0, n, submitted * 33_333L, 0)
                            submitted++
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 5_000L)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true }
                } else if (outIdx >= 0) {
                    val b = codec.getOutputBuffer(outIdx)
                    if (b != null && info.size > 0 && muxerStarted && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        b.position(info.offset); b.limit(info.offset + info.size)
                        muxer.writeSampleData(track, b, info); samples.incrementAndGet()
                    }
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eosRecv = true
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }
            val wallMs = (System.nanoTime() - t0) / 1e6
            json(
                "arm" to armName, "ok" to (samples.get() >= frames),
                "expectedP010Bytes" to expectedP010, "inputBufferCapacity" to capObserved,
                "fullFrameFits" to (capObserved >= expectedP010),
                "wallMs" to wallMs, "encodeFps" to (samples.get() * 1000.0 / wallMs.coerceAtLeast(1.0)),
                "encodedSamples" to samples.get(), "clipBytes" to outFile.length(),
                "fileStats" to LabBreakthroughProbes.inspectWrittenMp4(outFile),
            )
        } finally {
            if (started) runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun runProbe10E_Egl8DitherRamp(armName: String, outFile: File, useTpdfDither: Boolean): JSONObject {
        val w = 1920; val h = 1080; val frames = 30
        val codecName = LabBreakthroughProbes.selectHevcEncoderName()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h).apply {
            setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_LEVEL, CodecProfileLevel.HEVCMainTierLevel62)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 30_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
        }
        val codec = MediaCodec.createByCodecName(codecName)
        var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
        var win: EGLSurface = EGL14.EGL_NO_SURFACE
        var surface: Surface? = null
        var muxer: MediaMuxer? = null
        var started = false; var muxerStarted = false
        val samples = AtomicInteger(0)
        val fragDither = if (useTpdfDither) """#version 300 es
precision highp float;
uniform float uFrame;
in vec2 vTexCoord;
out vec4 outColor;
float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
void main() {
    float r1 = hash12(gl_FragCoord.xy + vec2(uFrame * 17.0, uFrame * 31.0));
    float r2 = hash12(gl_FragCoord.xy + vec2(uFrame * 59.0 + 7.3, uFrame * 11.0 + 3.1));
    float tpdf = (r1 + r2 - 1.0) / 255.0;
    float v = clamp(vTexCoord.x + tpdf, 0.0, 1.0);
    outColor = vec4(v, v, v, 1.0);
}""" else """#version 300 es
precision highp float;
uniform float uFrame;
in vec2 vTexCoord;
out vec4 outColor;
void main() { outColor = vec4(vTexCoord.x, vTexCoord.x, vTexCoord.x, 1.0); }"""

        return try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val v = IntArray(2); check(EGL14.eglInitialize(dpy, v, 0, v, 1))
            val cfgs = arrayOfNulls<EGLConfig>(1); val cnt = IntArray(1)
            EGL14.eglChooseConfig(dpy, intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
            ), 0, cfgs, 0, 1, cnt, 0)
            ctx = EGL14.eglCreateContext(dpy, requireNotNull(cfgs[0]), EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            win = EGL14.eglCreateWindowSurface(dpy, cfgs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(dpy, win, win, ctx))
            val prog = LabBreakthroughProbes.compileGlProgram(VERTEX_QUAD_UPRIGHT, fragDither)
            val frameLoc = GLES30.glGetUniformLocation(prog, "uFrame")
            val qb = ByteBuffer.allocateDirect(FULLSCREEN_QUAD.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(FULLSCREEN_QUAD).position(0) }
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.start(); started = true
            val info = MediaCodec.BufferInfo(); var track = -1
            for (f in 0 until frames) {
                GLES30.glViewport(0, 0, w, h)
                GLES30.glUseProgram(prog)
                if (frameLoc >= 0) GLES30.glUniform1f(frameLoc, f.toFloat())
                qb.position(0)
                GLES30.glEnableVertexAttribArray(0)
                GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, qb)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                EGLExt.eglPresentationTimeANDROID(dpy, win, f * 33_333_333L)
                EGL14.eglSwapBuffers(dpy, win)
                while (true) {
                    val idx = codec.dequeueOutputBuffer(info, 0)
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true }
                    } else if (idx >= 0) {
                        val b = codec.getOutputBuffer(idx)
                        if (b != null && info.size > 0 && muxerStarted && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            b.position(info.offset); b.limit(info.offset + info.size)
                            muxer.writeSampleData(track, b, info); samples.incrementAndGet()
                        }
                        codec.releaseOutputBuffer(idx, false)
                    } else break
                }
            }
            codec.signalEndOfInputStream()
            val dl = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < dl) {
                val idx = codec.dequeueOutputBuffer(info, 10_000L)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true }
                } else if (idx >= 0) {
                    val b = codec.getOutputBuffer(idx)
                    if (b != null && info.size > 0 && muxerStarted && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        b.position(info.offset); b.limit(info.offset + info.size)
                        muxer.writeSampleData(track, b, info); samples.incrementAndGet()
                    }
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(idx, false)
                    if (eos) break
                }
            }
            GLES30.glDeleteProgram(prog)
            json("arm" to armName, "ok" to (samples.get() >= frames), "encodedSamples" to samples.get(), "clipBytes" to outFile.length(), "fileStats" to LabBreakthroughProbes.inspectWrittenMp4(outFile))
        } finally {
            if (started) runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (win != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, win)
                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
                EGL14.eglTerminate(dpy)
            }
            runCatching { surface?.release() }
        }
    }

    // =========================================================================
    // Probe 11: RAW_SENSOR Forensics + RAW->GPU Demosaic->OCLog2 vs ISP Head-to-Head
    // =========================================================================
    fun runProbe11_RawForensicsAndHeadToHead(
        context: Context,
        outDir: File,
        checkBudget: () -> Unit = {},
    ): JSONObject {
        outDir.mkdirs()
        val rawResults = JSONArray()
        val rawTargets = listOf(
            Triple("0", Size(4000, 3000), 30),
            Triple("0", Size(4000, 3000), 60),
            Triple("4", Size(3648, 2736), 30),
            Triple("5", Size(4000, 3000), 30),
        )
        for ((camId, sz, fps) in rawTargets) {
            rawResults.put(try {
                LabBreakthroughProbes.ensureCoolOrWait(context, checkBudget)
                val dumpFile = if (camId == "0" && fps == 30) File(outDir, "cam0_4000x3000.raw16").apply { delete() } else null
                runProbe11A_RawStream(context, camId, sz, fps, dumpFile)
            } catch (t: Throwable) {
                json("cameraId" to camId, "size" to sz.toString(), "fps" to fps, "ok" to false, "error" to (t.message ?: t.toString()).take(280))
            })
        }

        // Head-to-Head 5 locked-exposure 4K30 Main10 clips on the exact same scene
        val lockedAe = runCatching { LabBreakthroughProbes.warmupAndLockExposure(context) }.getOrNull()
        val h2hArms = JSONArray()
        val ispArms = listOf(
            Triple("h2h_isp_default_oclog2", false, false),
            Triple("h2h_isp_clean_off_oclog2", false, true),
            Triple("h2h_cuva_default_oclog2", true, false),
            Triple("h2h_cuva_clean_off_oclog2", true, true),
        )
        for ((armName, cuvaOn, edgeNrOff) in ispArms) {
            h2hArms.put(try {
                val temp = LabBreakthroughProbes.ensureCoolOrWait(context, checkBudget)
                val outFile = File(outDir, "${armName}.mp4").apply { delete() }
                runProbe11B_IspOcLog2Arm(context, armName, outFile, cuvaOn, edgeNrOff, lockedAe).put("batteryStartC", temp)
            } catch (t: Throwable) {
                json("arm" to armName, "ok" to false, "error" to (t.message ?: t.toString()).take(280))
            })
        }
        // 5th arm: Real-time RAW_SENSOR 4000x3000 -> GL_R16UI -> Malvar 5x5 Demosaic -> OCLog2 -> HEVC Main 10 (3840x2160@30)
        h2hArms.put(try {
            val temp = LabBreakthroughProbes.ensureCoolOrWait(context, checkBudget)
            val outFile = File(outDir, "h2h_raw_gpu_demosaic_oclog2.mp4").apply { delete() }
            runProbe11B_RawGpuDemosaicOcLog2(context, outFile, lockedAe).put("batteryStartC", temp)
        } catch (t: Throwable) {
            json("arm" to "h2h_raw_gpu_demosaic_oclog2", "ok" to false, "errorClass" to t.javaClass.name, "error" to (t.message ?: t.toString()).take(300))
        })

        return LabBreakthroughProbes.saveProbeJson(outDir, "probe11_raw_and_h2h.json", json(
            "probe" to "Probe11_RawForensicsAndHeadToHead",
            "lockedAe" to (lockedAe?.let { json("expNs" to it.first, "iso" to it.second) } ?: JSONObject.NULL),
            "batteryEndC" to LabBreakthroughProbes.batteryTempC(context),
            "rawResults" to rawResults,
            "headToHead" to h2hArms,
        ))
    }

    @SuppressLint("MissingPermission")
    private fun runProbe11A_RawStream(
        context: Context,
        camId: String,
        size: Size,
        fps: Int,
        dumpRawFile: File?,
    ): JSONObject {
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        lease.ready().get(8, TimeUnit.SECONDS)
        val manager = context.getSystemService(CameraManager::class.java)
        val caps = manager.getCameraCharacteristics(camId)
        val cfa = caps.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
        val white = caps.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
        val black = caps.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { p -> IntArray(4).also { p.copyTo(it, 0) }.toList() }
        val fm1 = caps.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)?.let(::colorTransformToFloatArray)
        val cm1 = caps.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)?.let(::colorTransformToFloatArray)
        val cal1 = caps.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)?.let(::colorTransformToFloatArray)

        val thread = HandlerThread("p11a-$camId").apply { start() }
        val handler = Handler(thread.looper)
        val executor = Executor { r -> handler.post(r) }
        val rawReader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 6)
        val prevReader = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 3)

        val camTs = mutableListOf<Long>()
        val rawTs = mutableListOf<Long>()
        val dumped = AtomicBoolean(false)
        val lowNibbleZeroFrac = AtomicReference<Double?>(null)
        val rowStrideRef = AtomicInteger(-1)
        val codecBenchRef = AtomicReference<JSONObject?>(null)
        val neutralRef = AtomicReference<FloatArray?>(null)
        val expRef = AtomicLong(-1L)
        val isoRef = AtomicInteger(-1)

        rawReader.setOnImageAvailableListener({ r ->
            runCatching {
                r.acquireNextImage()?.use { img ->
                    synchronized(rawTs) { rawTs += img.timestamp }
                    val plane = img.planes.firstOrNull() ?: return@use
                    rowStrideRef.compareAndSet(-1, plane.rowStride)
                    if (rawTs.size == 10 && dumped.compareAndSet(false, true)) {
                        val buf = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                        val rowStride = plane.rowStride
                        val w = img.width; val h = img.height
                        if (dumpRawFile != null) {
                            val bytes = ByteArray(buf.remaining())
                            buf.duplicate().get(bytes)
                            dumpRawFile.writeBytes(bytes)
                        }
                        // Check bit-shift alignment on center region + benchmark RAW16->RAW10->BLOCK_PACK
                        var total = 0; var lowZero = 0
                        val yStart = h / 4; val yEnd = (h * 3) / 4
                        val xStart = w / 4; val xEnd = (w * 3) / 4
                        for (yy in yStart until yEnd step 2) {
                            val rowOff = yy * rowStride
                            for (xx in xStart until xEnd step 2) {
                                val v = buf.getShort(rowOff + xx * 2).toInt() and 0xFFFF
                                total++
                                if ((v and 0xF) == 0) lowZero++
                            }
                        }
                        lowNibbleZeroFrac.set(if (total > 0) lowZero.toDouble() / total else null)

                        if (dumpRawFile != null && NativeRawCodec.available) {
                            codecBenchRef.set(benchmarkRawPackingAndBlockPack(buf, w, h, rowStride, (lowNibbleZeroFrac.get() ?: 0.0) > 0.99))
                        }
                    }
                }
            }
        }, handler)
        prevReader.setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage()?.close() } }, handler)

        val opened = CompletableFuture<CameraDevice>()
        val closed = CompletableFuture<Unit>()
        val configured = CompletableFuture<CameraCaptureSession>()
        val retiring = AtomicBoolean()
        var session: CameraCaptureSession? = null
        var openSubmitted = false
        return try {
            manager.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { opened.complete(c); if (retiring.get()) c.close() }
                override fun onDisconnected(c: CameraDevice) { opened.completeExceptionally(IllegalStateException("Disconnected")); c.close() }
                override fun onError(c: CameraDevice, e: Int) { opened.completeExceptionally(IllegalStateException("Err $e")); c.close() }
                override fun onClosed(c: CameraDevice) { closed.complete(Unit) }
            }, handler)
            openSubmitted = true
            val cam = opened.get(6, TimeUnit.SECONDS)
            val targets = listOf(rawReader.surface, prevReader.surface)
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
                    res.get(CaptureResult.SENSOR_TIMESTAMP)?.let { synchronized(camTs) { camTs += it } }
                    res.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.let { ncp ->
                        neutralRef.compareAndSet(null, floatArrayOf(ncp[0].toFloat(), ncp[1].toFloat(), ncp[2].toFloat()))
                    }
                    res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { expRef.compareAndSet(-1L, it) }
                    res.get(CaptureResult.SENSOR_SENSITIVITY)?.let { isoRef.compareAndSet(-1, it) }
                }
            }, handler)
            Thread.sleep(2_200L)
            runCatching { session.stopRepeating() }
            val thresh = 1_500_000_000L / fps.coerceAtLeast(1)
            val camM = synchronized(camTs) { LabBreakthroughProbes.computeTimestampMetrics(camTs.toList(), thresh) }
            val rawM = synchronized(rawTs) { LabBreakthroughProbes.computeTimestampMetrics(rawTs.toList(), thresh) }
            json(
                "cameraId" to camId, "size" to size.toString(), "fpsTarget" to fps,
                "ok" to (rawM.frames > 5),
                "cfa" to cfa, "whiteLevel" to white, "blackLevel" to black?.toString(),
                "rowStride" to rowStrideRef.get(),
                "lowNibbleZeroFrac" to lowNibbleZeroFrac.get(),
                "forwardMatrix1" to fm1?.joinToString(",", "[", "]"),
                "colorTransform1" to cm1?.joinToString(",", "[", "]"),
                "calibrationTransform1" to cal1?.joinToString(",", "[", "]"),
                "neutralColorPoint" to neutralRef.get()?.joinToString(",", "[", "]"),
                "expNs" to expRef.get(), "iso" to isoRef.get(),
                "dumpedRawFile" to dumpRawFile?.name?.takeIf { dumpRawFile.isFile },
                "raw10BlockPackBench" to codecBenchRef.get(),
                "camCb" to with(LabBreakthroughProbes) { camM.toJson() }, "rawReader" to with(LabBreakthroughProbes) { rawM.toJson() },
            )
        } finally {
            retiring.set(true)
            val cc = closed.thenApply { Unit }
            lease.releaseAfter(cc)
            if (openSubmitted) handler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } } else closed.complete(Unit)
            runCatching { cc.get(6, TimeUnit.SECONDS) }
            runCatching { rawReader.close() }; runCatching { prevReader.close() }
            thread.quitSafely(); runCatching { thread.join(4_000) }
        }
    }

    private fun benchmarkRawPackingAndBlockPack(raw16Buf: ByteBuffer, w: Int, h: Int, rowStride: Int, shift4: Boolean): JSONObject {
        val raw10RowBytes = w * 5 / 4
        val raw10Buf = ByteBuffer.allocateDirect(raw10RowBytes * h)
        val rawBytes = ByteArray(rowStride * h)
        raw16Buf.duplicate().get(rawBytes, 0, rawBytes.size.coerceAtMost(raw16Buf.remaining()))
        val outBytes = ByteArray(raw10RowBytes * h)
        val rightShift = if (shift4) 6 else 2 // 16-bit shifted 12-bit -> 10-bit via >>6; unshifted 12-bit -> >>2

        val tPack0 = System.nanoTime()
        for (yy in 0 until h) {
            val srcRow = yy * rowStride
            val dstRow = yy * raw10RowBytes
            var x = 0; var dg = dstRow
            while (x < w) {
                val p0 = ((rawBytes[srcRow + x * 2].toInt() and 0xFF) or ((rawBytes[srcRow + x * 2 + 1].toInt() and 0xFF) shl 8)) ushr rightShift
                val p1 = ((rawBytes[srcRow + (x + 1) * 2].toInt() and 0xFF) or ((rawBytes[srcRow + (x + 1) * 2 + 1].toInt() and 0xFF) shl 8)) ushr rightShift
                val p2 = ((rawBytes[srcRow + (x + 2) * 2].toInt() and 0xFF) or ((rawBytes[srcRow + (x + 2) * 2 + 1].toInt() and 0xFF) shl 8)) ushr rightShift
                val p3 = ((rawBytes[srcRow + (x + 3) * 2].toInt() and 0xFF) or ((rawBytes[srcRow + (x + 3) * 2 + 1].toInt() and 0xFF) shl 8)) ushr rightShift
                outBytes[dg] = (p0 ushr 2).toByte()
                outBytes[dg + 1] = (p1 ushr 2).toByte()
                outBytes[dg + 2] = (p2 ushr 2).toByte()
                outBytes[dg + 3] = (p3 ushr 2).toByte()
                outBytes[dg + 4] = ((p0 and 3) or ((p1 and 3) shl 2) or ((p2 and 3) shl 4) or ((p3 and 3) shl 6)).toByte()
                x += 4; dg += 5
            }
        }
        val packMs = (System.nanoTime() - tPack0) / 1e6
        raw10Buf.clear(); raw10Buf.put(outBytes); raw10Buf.position(0)

        val enc = NativeRawCodec.newEncoder(NativeRawCodec.Codec.BLOCK_PACK, threads = 4, strips = 8)
        return try {
            val dstCap = enc.maxEncodedSize(w, h).toInt()
            val dst = ByteBuffer.allocateDirect(dstCap)
            var encodedSize = 0
            val tEnc0 = System.nanoTime()
            val iters = 5
            for (i in 0 until iters) {
                encodedSize = enc.encode(raw10Buf, w, h, dst, raw10RowBytes)
            }
            val encMs = ((System.nanoTime() - tEnc0) / 1e6) / iters
            val ratio = (raw10RowBytes.toDouble() * h) / encodedSize.coerceAtLeast(1)
            json(
                "pack16to10Ms" to packMs,
                "blockPackEncodeMsMean5" to encMs,
                "raw10Bytes" to raw10RowBytes * h,
                "encodedBytes" to encodedSize,
                "compressionRatio" to ratio,
                "mbPerSecAt24Fps" to (encodedSize * 24.0) / (1024.0 * 1024.0),
                "mbPerSecAt30Fps" to (encodedSize * 30.0) / (1024.0 * 1024.0),
            )
        } finally {
            enc.close()
        }
    }

    @SuppressLint("MissingPermission")
    private fun runProbe11B_IspOcLog2Arm(
        context: Context,
        armName: String,
        outFile: File,
        cuvaOn: Boolean,
        edgeNrOff: Boolean,
        lockedAe: Pair<Long, Int>?,
    ): JSONObject {
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        lease.ready().get(8, TimeUnit.SECONDS)
        val manager = context.getSystemService(CameraManager::class.java)
        val caps = manager.getCameraCharacteristics("0")
        val cuvaReqKey = LabBreakthroughProbes.findVendorRequestKey(caps, "cuvaStateVideoMode")
        val cuvaHint = LabBreakthroughProbes.vendorKeyTypeHint(cuvaReqKey)
        val w = 3840; val h = 2160; val fps = 30
        val camThread = HandlerThread("p11-isp-cam").apply { start() }
        val camHandler = Handler(camThread.looper)
        val glThread = HandlerThread("p11-isp-gl").apply { start() }
        val glHandler = Handler(glThread.looper)
        val executor = Executor { r -> camHandler.post(r) }

        val reader = LabBreakthroughProbes.createUsageImageReader(
            w, h, ImageFormat.PRIVATE, 6,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_VIDEO_ENCODE,
        )
        val codecName = LabBreakthroughProbes.selectHevcEncoderName()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h).apply {
            setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_LEVEL, CodecProfileLevel.HEVCMainTierLevel62)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 60_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
        }
        val codec = MediaCodec.createByCodecName(codecName)
        var muxer: MediaMuxer? = null; var codecSurface: Surface? = null
        var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY; var ctx: EGLContext = EGL14.EGL_NO_CONTEXT; var win: EGLSurface = EGL14.EGL_NO_SURFACE
        var texExt = 0; var prog = 0
        var codecStarted = false; var muxerStarted = false
        val recordingOn = AtomicBoolean(false); val acceptLock = Any()
        val hwFmt = AtomicInteger(-1); val samples = AtomicInteger(0)
        val camTs = mutableListOf<Long>()
        val opened = CompletableFuture<CameraDevice>(); val closed = CompletableFuture<Unit>(); val configured = CompletableFuture<CameraCaptureSession>()
        val retiring = AtomicBoolean(); var session: CameraCaptureSession? = null; var openSubmitted = false

        val fragOcLog2 = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTexture;
in vec2 vTexCoord;
out vec4 outColor;
const float HLG_A = 0.17883277;
const float HLG_B = 0.28466892;
const float HLG_C = 0.55991073;
vec3 inverseHlg(vec3 e) {
    e = clamp(e, 0.0, 1.0);
    bvec3 low = lessThanEqual(e, vec3(0.5));
    return mix((exp((e - HLG_C) / HLG_A) + HLG_B) / 12.0, (e * e) / 3.0, low);
}
vec3 inverseRec709(vec3 e) {
    bvec3 low = lessThan(e, vec3(0.081));
    return mix(pow((e + 0.099) / 1.099, vec3(1.0 / 0.45)), e / 4.5, low);
}
vec3 bt709ToBt2020(vec3 c) {
    return mat3(0.627404, 0.069097, 0.016391, 0.329283, 0.919540, 0.088013, 0.043313, 0.011362, 0.895595) * c;
}
void main() {
    vec3 rgb = texture(uTexture, vTexCoord).rgb;
    vec3 lin = ${if (cuvaOn) "bt709ToBt2020(inverseHlg(rgb))" else "bt709ToBt2020(inverseRec709(rgb))"};
    vec3 oclog = vec3(0.10) + vec3(0.80) * log(vec3(1.0) + 50.0 * clamp(lin, 0.0, 1.0)) / log(vec3(51.0));
    outColor = vec4(oclog, 1.0);
}"""

        return try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codecSurface = codec.createInputSurface()
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply { setOrientationHint(90) }
            val glInit = CompletableFuture<Unit>()
            lateinit var qb: FloatBuffer
            glHandler.post {
                try {
                    dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                    val v = IntArray(2); check(EGL14.eglInitialize(dpy, v, 0, v, 1))
                    val cfgs = arrayOfNulls<EGLConfig>(1); val cnt = IntArray(1)
                    EGL14.eglChooseConfig(dpy, intArrayOf(
                        EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                        EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
                    ), 0, cfgs, 0, 1, cnt, 0)
                    ctx = EGL14.eglCreateContext(dpy, requireNotNull(cfgs[0]), EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL_CONTEXT_PRIORITY_LEVEL_IMG, EGL_CONTEXT_PRIORITY_HIGH_IMG, EGL14.EGL_NONE), 0)
                    win = EGL14.eglCreateWindowSurface(dpy, cfgs[0], codecSurface, intArrayOf(EGL14.EGL_NONE), 0)
                    check(EGL14.eglMakeCurrent(dpy, win, win, ctx))
                    val t = IntArray(1); GLES30.glGenTextures(1, t, 0); texExt = t[0]
                    GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texExt)
                    GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                    prog = LabBreakthroughProbes.compileGlProgram(VERTEX_QUAD_UPRIGHT, fragOcLog2)
                    qb = ByteBuffer.allocateDirect(FULLSCREEN_QUAD.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(FULLSCREEN_QUAD).position(0) }
                    glInit.complete(Unit)
                } catch (t: Throwable) { glInit.completeExceptionally(t) }
            }
            glInit.get(5, TimeUnit.SECONDS)
            codec.start(); codecStarted = true
            val drainDone = CountDownLatch(1)
            val drainThread = Thread {
                val info = MediaCodec.BufferInfo(); var track = -1
                try {
                    while (true) {
                        val idx = codec.dequeueOutputBuffer(info, 20_000L)
                        if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (!muxerStarted) { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true }
                        } else if (idx >= 0) {
                            val b = codec.getOutputBuffer(idx)
                            if (b != null && info.size > 0 && muxerStarted && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                b.position(info.offset); b.limit(info.offset + info.size)
                                muxer.writeSampleData(track, b, info); samples.incrementAndGet()
                            }
                            val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            codec.releaseOutputBuffer(idx, false)
                            if (eos) break
                        }
                    }
                } finally { drainDone.countDown() }
            }.apply { start() }

            reader.setOnImageAvailableListener({ r ->
                val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                val hb = runCatching { img.hardwareBuffer }.getOrNull()
                if (hb != null && hwFmt.get() == -1) hwFmt.compareAndSet(-1, EglHardwareBufferBridge.describeAhb(hb)?.first ?: hb.format)
                var eglImg = 0L
                try {
                    if (hb == null || !recordingOn.get()) return@setOnImageAvailableListener
                    synchronized(acceptLock) {
                        if (!recordingOn.get()) return@synchronized
                        eglImg = EglHardwareBufferBridge.createEglImage(hb)
                        if (eglImg == 0L) return@synchronized
                        EGL14.eglMakeCurrent(dpy, win, win, ctx)
                        GLES30.glViewport(0, 0, w, h)
                        GLES30.glUseProgram(prog)
                        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texExt)
                        if (EglHardwareBufferBridge.bindExternalTexture(eglImg) == 0) {
                            qb.position(0)
                            GLES30.glEnableVertexAttribArray(0)
                            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, qb)
                            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                            EGLExt.eglPresentationTimeANDROID(dpy, win, img.timestamp)
                            EGL14.eglSwapBuffers(dpy, win)
                        }
                    }
                } finally {
                    if (eglImg != 0L) EglHardwareBufferBridge.destroyEglImage(eglImg)
                    hb?.close(); img.close()
                }
            }, glHandler)

            manager.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { opened.complete(c); if (retiring.get()) c.close() }
                override fun onDisconnected(c: CameraDevice) { opened.completeExceptionally(IllegalStateException("Disconnected")); c.close() }
                override fun onError(c: CameraDevice, e: Int) { opened.completeExceptionally(IllegalStateException("Err $e")); c.close() }
                override fun onClosed(c: CameraDevice) { closed.complete(Unit) }
            }, camHandler)
            openSubmitted = true
            val cam = opened.get(6, TimeUnit.SECONDS)
            fun applyKeys(b: CaptureRequest.Builder) {
                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                if (lockedAe != null) {
                    b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, lockedAe.first)
                    b.set(CaptureRequest.SENSOR_SENSITIVITY, lockedAe.second)
                    b.set(CaptureRequest.SENSOR_FRAME_DURATION, 33_333_333L)
                }
                if (edgeNrOff) {
                    b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                    b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                } else {
                    b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                    b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                }
                if (cuvaOn && cuvaReqKey != null) setVendorByteOrIntKey(b, cuvaReqKey, 1, cuvaHint)
            }
            val sessReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(reader.surface); applyKeys(this) }.build()
            cam.createCaptureSession(
                SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(reader.surface)), executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { if (retiring.get()) s.close() else configured.complete(s) }
                        override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("Configure failed")); s.close() }
                    },
                ).apply { sessionParameters = sessReq },
            )
            session = configured.get(6, TimeUnit.SECONDS)
            val repReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(reader.surface); applyKeys(this) }.build()
            session.setRepeatingRequest(repReq, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                    if (recordingOn.get()) res.get(CaptureResult.SENSOR_TIMESTAMP)?.let { synchronized(camTs) { camTs += it } }
                }
            }, camHandler)
            Thread.sleep(500L)
            recordingOn.set(true)
            Thread.sleep(3_000L)
            synchronized(acceptLock) { recordingOn.set(false) }
            runCatching { session.stopRepeating() }
            glHandler.post { runCatching { codec.signalEndOfInputStream() } }
            drainDone.await(6, TimeUnit.SECONDS)
            val camM = synchronized(camTs) { LabBreakthroughProbes.computeTimestampMetrics(camTs.toList(), 45_000_000L) }
            json(
                "arm" to armName, "ok" to (samples.get() > 30), "clip" to outFile.name, "clipBytes" to outFile.length(),
                "hwFmtHex" to hwFmt.get().takeIf { it != -1 }?.let { "0x${it.toString(16)}" },
                "camCb" to with(LabBreakthroughProbes) { camM.toJson() }, "encodedSamples" to samples.get(),
                "fileStats" to LabBreakthroughProbes.inspectWrittenMp4(outFile),
            )
        } finally {
            retiring.set(true); synchronized(acceptLock) { recordingOn.set(false) }
            val cc = closed.thenApply { Unit }; lease.releaseAfter(cc)
            if (openSubmitted) camHandler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } } else closed.complete(Unit)
            runCatching { cc.get(6, TimeUnit.SECONDS) }
            runCatching { reader.close() }
            if (codecStarted) runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            val glDone = CompletableFuture<Unit>()
            glHandler.post {
                runCatching {
                    if (prog != 0) GLES30.glDeleteProgram(prog)
                    if (texExt != 0) GLES30.glDeleteTextures(1, intArrayOf(texExt), 0)
                    if (dpy != EGL14.EGL_NO_DISPLAY) {
                        EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                        if (win != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, win)
                        if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
                        EGL14.eglTerminate(dpy)
                    }
                }
                glDone.complete(Unit)
            }
            runCatching { glDone.get(4, TimeUnit.SECONDS) }
            runCatching { codecSurface?.release() }
            camThread.quitSafely(); glThread.quitSafely()
            runCatching { camThread.join(4_000) }; runCatching { glThread.join(4_000) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun runProbe11B_RawGpuDemosaicOcLog2(
        context: Context,
        outFile: File,
        lockedAe: Pair<Long, Int>?,
    ): JSONObject {
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        lease.ready().get(8, TimeUnit.SECONDS)
        val manager = context.getSystemService(CameraManager::class.java)
        val caps = manager.getCameraCharacteristics("0")
        val whiteLevel = (caps.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 4095).toFloat()
        val blackArr = caps.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { p -> IntArray(4).also { p.copyTo(it, 0) } } ?: intArrayOf(256, 256, 256, 256)
        val rawW = 4000; val rawH = 3000; val outW = 3840; val outH = 2160; val fps = 30
        val camThread = HandlerThread("p11-raw-cam").apply { start() }
        val camHandler = Handler(camThread.looper)
        val glThread = HandlerThread("p11-raw-gl").apply { start() }
        val glHandler = Handler(glThread.looper)
        val executor = Executor { r -> camHandler.post(r) }

        val rawReader = ImageReader.newInstance(rawW, rawH, ImageFormat.RAW_SENSOR, 6)
        val prevReader = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 3)
        prevReader.setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage()?.close() } }, camHandler)

        val codecName = LabBreakthroughProbes.selectHevcEncoderName()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, outW, outH).apply {
            setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_LEVEL, CodecProfileLevel.HEVCMainTierLevel62)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 60_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
        }
        val codec = MediaCodec.createByCodecName(codecName)
        var muxer: MediaMuxer? = null; var codecSurface: Surface? = null
        var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY; var ctx: EGLContext = EGL14.EGL_NO_CONTEXT; var win: EGLSurface = EGL14.EGL_NO_SURFACE
        var texRaw = 0; var prog = 0
        var codecStarted = false; var muxerStarted = false
        val recordingOn = AtomicBoolean(false); val acceptLock = Any()
        val samples = AtomicInteger(0)
        val camTs = mutableListOf<Long>()
        val glTs = mutableListOf<Long>()
        val uploadMsList = mutableListOf<Double>()
        val drawMsList = mutableListOf<Double>()
        val shift16Ref = AtomicReference<Boolean?>(null)
        val neutralRef = AtomicReference<FloatArray?>(null)
        val opened = CompletableFuture<CameraDevice>(); val closed = CompletableFuture<Unit>(); val configured = CompletableFuture<CameraCaptureSession>()
        val retiring = AtomicBoolean(); var session: CameraCaptureSession? = null; var openSubmitted = false

        // Compute Camera RGB -> XYZ_D50 -> Bradford D65 -> Linear BT.2020 matrix (row-major 3x3 -> transpose for glUniformMatrix3fv)
        val fm1 = caps.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)?.let(::colorTransformToFloatArray)
        val xyzD50ToD65 = doubleArrayOf( // Bradford D50 -> D65
            0.9555766, -0.0230393, 0.0631636,
            -0.0282895, 1.0099416, 0.0210077,
            0.0122982, -0.0204830, 1.3299098,
        )
        val xyzD65ToBt2020 = doubleArrayOf(
            1.7166512, -0.3556708, -0.2533663,
            -0.6666844, 1.6164812, 0.0157685,
            0.0176399, -0.0427706, 0.9421031,
        )
        val xyzD50ToBt2020 = mul3x3(xyzD65ToBt2020, xyzD50ToD65)
        val camToBt2020Row = if (fm1 != null) mul3x3(xyzD50ToBt2020, DoubleArray(9) { fm1[it].toDouble() }) else xyzD50ToBt2020
        val camToBt2020ColMajor = FloatArray(9) { camToBt2020Row[(it % 3) * 3 + it / 3].toFloat() }

        // Malvar-He-Cutler 5x5 Bayer Demosaic (RGGB at 0,0) on GL_R16UI texture -> Linear BT.2020 -> OCLog2!
        // Crops centered 3840x2160 window out of 4000x3000 RAW_SENSOR to avoid aspect distortion!
        val fragMalvarOcLog2 = """#version 300 es
precision highp float;
precision highp usampler2D;
uniform usampler2D uRawTex;
uniform float uRightShiftDiv;
uniform vec4 uBlack;
uniform float uWhite;
uniform vec3 uWbGain;
uniform mat3 uCamToBt2020;
out vec4 outColor;

float rawSample(ivec2 p) {
    ivec2 c = clamp(p, ivec2(0), ivec2(3999, 2999));
    uint raw = texelFetch(uRawTex, c, 0).r;
    float dn = float(raw) / uRightShiftDiv;
    int site = (c.y & 1) * 2 + (c.x & 1);
    float blk = site == 0 ? uBlack.x : (site == 1 ? uBlack.y : (site == 2 ? uBlack.z : uBlack.w));
    return clamp((dn - blk) / max(uWhite - blk, 1.0), 0.0, 1.0);
}

void main() {
    // gl_FragCoord in [0..3840) x [0..2160): map 1:1 to even-aligned center window (x0=80, y0=420)
    ivec2 p = ivec2(int(gl_FragCoord.x) + 80, int(gl_FragCoord.y) + 420);
    float c = rawSample(p);
    float l1 = rawSample(p + ivec2(-1, 0)); float r1 = rawSample(p + ivec2(1, 0));
    float u1 = rawSample(p + ivec2(0, -1)); float d1 = rawSample(p + ivec2(0, 1));
    float l2 = rawSample(p + ivec2(-2, 0)); float r2 = rawSample(p + ivec2(2, 0));
    float u2 = rawSample(p + ivec2(0, -2)); float d2 = rawSample(p + ivec2(0, 2));
    float tl = rawSample(p + ivec2(-1, -1)); float tr = rawSample(p + ivec2(1, -1));
    float bl = rawSample(p + ivec2(-1, 1)); float br = rawSample(p + ivec2(1, 1));

    int px = p.x & 1;
    int py = p.y & 1;
    vec3 rgb;
    if (py == 0 && px == 0) {
        // R location (RGGB)
        float g = (4.0 * c + 2.0 * (l1 + r1 + u1 + d1) - (l2 + r2 + u2 + d2)) / 8.0;
        float b = (6.0 * c + 2.0 * (tl + tr + bl + br) - 1.5 * (l2 + r2 + u2 + d2)) / 8.0;
        rgb = vec3(c, g, b);
    } else if (py == 1 && px == 1) {
        // B location (RGGB)
        float g = (4.0 * c + 2.0 * (l1 + r1 + u1 + d1) - (l2 + r2 + u2 + d2)) / 8.0;
        float r = (6.0 * c + 2.0 * (tl + tr + bl + br) - 1.5 * (l2 + r2 + u2 + d2)) / 8.0;
        rgb = vec3(r, g, c);
    } else if (py == 0 && px == 1) {
        // G on R row
        float r = (5.0 * c + 4.0 * (l1 + r1) - (tl + tr + bl + br + l2 + r2) + 0.5 * (u2 + d2)) / 8.0;
        float b = (5.0 * c + 4.0 * (u1 + d1) - (tl + tr + bl + br + u2 + d2) + 0.5 * (l2 + r2)) / 8.0;
        rgb = vec3(r, c, b);
    } else {
        // G on B row
        float b = (5.0 * c + 4.0 * (l1 + r1) - (tl + tr + bl + br + l2 + r2) + 0.5 * (u2 + d2)) / 8.0;
        float r = (5.0 * c + 4.0 * (u1 + d1) - (tl + tr + bl + br + u2 + d2) + 0.5 * (l2 + r2)) / 8.0;
        rgb = vec3(r, c, b);
    }
    rgb = max(rgb, 0.0);
    // White-balance camera RGB, keeping highlight headroom without hard per-channel clip mismatch before matrix
    vec3 bal = clamp(rgb * uWbGain, 0.0, 1.0);
    vec3 bt2020Lin = max(uCamToBt2020 * bal, 0.0);
    vec3 oclog = vec3(0.10) + vec3(0.80) * log(vec3(1.0) + 50.0 * clamp(bt2020Lin, 0.0, 1.0)) / log(vec3(51.0));
    outColor = vec4(oclog, 1.0);
}"""

        return try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codecSurface = codec.createInputSurface()
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply { setOrientationHint(90) }
            val glInit = CompletableFuture<Unit>()
            lateinit var qb: FloatBuffer
            glHandler.post {
                try {
                    dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                    val v = IntArray(2); check(EGL14.eglInitialize(dpy, v, 0, v, 1))
                    val cfgs = arrayOfNulls<EGLConfig>(1); val cnt = IntArray(1)
                    EGL14.eglChooseConfig(dpy, intArrayOf(
                        EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                        EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
                    ), 0, cfgs, 0, 1, cnt, 0)
                    ctx = EGL14.eglCreateContext(dpy, requireNotNull(cfgs[0]), EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL_CONTEXT_PRIORITY_LEVEL_IMG, EGL_CONTEXT_PRIORITY_HIGH_IMG, EGL14.EGL_NONE), 0)
                    win = EGL14.eglCreateWindowSurface(dpy, cfgs[0], codecSurface, intArrayOf(EGL14.EGL_NONE), 0)
                    check(EGL14.eglMakeCurrent(dpy, win, win, ctx))
                    val t = IntArray(1); GLES30.glGenTextures(1, t, 0); texRaw = t[0]
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texRaw)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                    GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_R16UI, rawW, rawH)
                    prog = LabBreakthroughProbes.compileGlProgram(VERTEX_QUAD, fragMalvarOcLog2)
                    qb = ByteBuffer.allocateDirect(FULLSCREEN_QUAD.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(FULLSCREEN_QUAD).position(0) }
                    glInit.complete(Unit)
                } catch (t: Throwable) { glInit.completeExceptionally(t) }
            }
            glInit.get(5, TimeUnit.SECONDS)
            codec.start(); codecStarted = true
            val drainDone = CountDownLatch(1)
            Thread {
                val info = MediaCodec.BufferInfo(); var track = -1
                try {
                    while (true) {
                        val idx = codec.dequeueOutputBuffer(info, 20_000L)
                        if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (!muxerStarted) { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true }
                        } else if (idx >= 0) {
                            val b = codec.getOutputBuffer(idx)
                            if (b != null && info.size > 0 && muxerStarted && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                b.position(info.offset); b.limit(info.offset + info.size)
                                muxer.writeSampleData(track, b, info); samples.incrementAndGet()
                            }
                            val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            codec.releaseOutputBuffer(idx, false)
                            if (eos) break
                        }
                    }
                } finally { drainDone.countDown() }
            }.apply { start() }

            rawReader.setOnImageAvailableListener({ r ->
                val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    if (!recordingOn.get()) return@setOnImageAvailableListener
                    val plane = img.planes.firstOrNull() ?: return@setOnImageAvailableListener
                    val buf = plane.buffer
                    if (shift16Ref.get() == null) {
                        val le = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                        var lowZ = 0; var tot = 0
                        for (yy in 1000 until 2000 step 16) {
                            val ro = yy * plane.rowStride
                            for (xx in 1000 until 3000 step 16) {
                                val v = le.getShort(ro + xx * 2).toInt() and 0xFFFF
                                tot++; if ((v and 0xF) == 0) lowZ++
                            }
                        }
                        shift16Ref.compareAndSet(null, tot > 0 && lowZ.toDouble() / tot > 0.99)
                    }
                    synchronized(acceptLock) {
                        if (!recordingOn.get()) return@synchronized
                        val t0 = System.nanoTime()
                        EGL14.eglMakeCurrent(dpy, win, win, ctx)
                        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 2)
                        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, plane.rowStride / 2)
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texRaw)
                        buf.position(0)
                        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, rawW, rawH, GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, buf)
                        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
                        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
                        val tUp = System.nanoTime()

                        GLES30.glViewport(0, 0, outW, outH)
                        GLES30.glUseProgram(prog)
                        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texRaw)
                        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uRawTex"), 0)
                        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uRightShiftDiv"), if (shift16Ref.get() == true) 16.0f else 1.0f)
                        GLES30.glUniform4f(GLES30.glGetUniformLocation(prog, "uBlack"), blackArr[0].toFloat(), blackArr[1].toFloat(), blackArr[2].toFloat(), blackArr[3].toFloat())
                        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uWhite"), whiteLevel)
                        val ncp = neutralRef.get()
                        val wb = if (ncp != null && ncp[0] > 0f && ncp[1] > 0f && ncp[2] > 0f) {
                            floatArrayOf(ncp[1] / ncp[0], 1f, ncp[1] / ncp[2])
                        } else floatArrayOf(1.9f, 1.0f, 1.6f)
                        GLES30.glUniform3f(GLES30.glGetUniformLocation(prog, "uWbGain"), wb[0], wb[1], wb[2])
                        GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(prog, "uCamToBt2020"), 1, false, camToBt2020ColMajor, 0)

                        qb.position(0)
                        GLES30.glEnableVertexAttribArray(0)
                        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, qb)
                        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                        EGLExt.eglPresentationTimeANDROID(dpy, win, img.timestamp)
                        EGL14.eglSwapBuffers(dpy, win)
                        val tEnd = System.nanoTime()
                        synchronized(uploadMsList) { uploadMsList += (tUp - t0) / 1e6; drawMsList += (tEnd - tUp) / 1e6 }
                        synchronized(glTs) { glTs += img.timestamp }
                    }
                } finally { img.close() }
            }, glHandler)

            manager.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { opened.complete(c); if (retiring.get()) c.close() }
                override fun onDisconnected(c: CameraDevice) { opened.completeExceptionally(IllegalStateException("Disconnected")); c.close() }
                override fun onError(c: CameraDevice, e: Int) { opened.completeExceptionally(IllegalStateException("Err $e")); c.close() }
                override fun onClosed(c: CameraDevice) { closed.complete(Unit) }
            }, camHandler)
            openSubmitted = true
            val cam = opened.get(6, TimeUnit.SECONDS)
            fun applyKeys(b: CaptureRequest.Builder) {
                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                if (lockedAe != null) {
                    b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, lockedAe.first)
                    b.set(CaptureRequest.SENSOR_SENSITIVITY, lockedAe.second)
                    b.set(CaptureRequest.SENSOR_FRAME_DURATION, 33_333_333L)
                }
            }
            val targets = listOf(rawReader.surface, prevReader.surface)
            val sessReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { targets.forEach(::addTarget); applyKeys(this) }.build()
            cam.createCaptureSession(
                SessionConfiguration(SessionConfiguration.SESSION_REGULAR, targets.map { OutputConfiguration(it) }, executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { if (retiring.get()) s.close() else configured.complete(s) }
                        override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("Configure failed")); s.close() }
                    },
                ).apply { sessionParameters = sessReq },
            )
            session = configured.get(6, TimeUnit.SECONDS)
            val repReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { targets.forEach(::addTarget); applyKeys(this) }.build()
            session.setRepeatingRequest(repReq, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                    res.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.let { ncp ->
                        neutralRef.compareAndSet(null, floatArrayOf(ncp[0].toFloat(), ncp[1].toFloat(), ncp[2].toFloat()))
                    }
                    if (recordingOn.get()) res.get(CaptureResult.SENSOR_TIMESTAMP)?.let { synchronized(camTs) { camTs += it } }
                }
            }, camHandler)
            Thread.sleep(500L)
            recordingOn.set(true)
            Thread.sleep(3_000L)
            synchronized(acceptLock) { recordingOn.set(false) }
            runCatching { session.stopRepeating() }
            glHandler.post { runCatching { codec.signalEndOfInputStream() } }
            drainDone.await(6, TimeUnit.SECONDS)
            val camM = synchronized(camTs) { LabBreakthroughProbes.computeTimestampMetrics(camTs.toList(), 45_000_000L) }
            val glM = synchronized(glTs) { LabBreakthroughProbes.computeTimestampMetrics(glTs.toList(), 45_000_000L) }
            val upSorted = synchronized(uploadMsList) { uploadMsList.sorted() }
            val drSorted = synchronized(drawMsList) { drawMsList.sorted() }
            json(
                "arm" to "h2h_raw_gpu_demosaic_oclog2", "ok" to (samples.get() > 20), "clip" to outFile.name, "clipBytes" to outFile.length(),
                "shift16Detected" to shift16Ref.get(), "neutralPoint" to neutralRef.get()?.joinToString(",", "[", "]"),
                "uploadMsP50" to upSorted.getOrNull(upSorted.size / 2), "uploadMsP95" to upSorted.getOrNull((upSorted.size * 95) / 100),
                "drawSwapMsP50" to drSorted.getOrNull(drSorted.size / 2), "drawSwapMsP95" to drSorted.getOrNull((drSorted.size * 95) / 100),
                "camCb" to with(LabBreakthroughProbes) { camM.toJson() }, "glProcessed" to with(LabBreakthroughProbes) { glM.toJson() }, "encodedSamples" to samples.get(),
                "fileStats" to LabBreakthroughProbes.inspectWrittenMp4(outFile),
            )
        } finally {
            retiring.set(true); synchronized(acceptLock) { recordingOn.set(false) }
            val cc = closed.thenApply { Unit }; lease.releaseAfter(cc)
            if (openSubmitted) camHandler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } } else closed.complete(Unit)
            runCatching { cc.get(6, TimeUnit.SECONDS) }
            runCatching { rawReader.close() }; runCatching { prevReader.close() }
            if (codecStarted) runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            val glDone = CompletableFuture<Unit>()
            glHandler.post {
                runCatching {
                    if (prog != 0) GLES30.glDeleteProgram(prog)
                    if (texRaw != 0) GLES30.glDeleteTextures(1, intArrayOf(texRaw), 0)
                    if (dpy != EGL14.EGL_NO_DISPLAY) {
                        EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                        if (win != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, win)
                        if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
                        EGL14.eglTerminate(dpy)
                    }
                }
                glDone.complete(Unit)
            }
            runCatching { glDone.get(4, TimeUnit.SECONDS) }
            runCatching { codecSurface?.release() }
            camThread.quitSafely(); glThread.quitSafely()
            runCatching { camThread.join(4_000) }; runCatching { glThread.join(4_000) }
        }
    }

    // =========================================================================
    // Probe 12: Hidden Pro Controls & Multi-Lens Matrix (Real Pixel/Result Verification)
    // =========================================================================
    fun runProbe12_HiddenProControlsAndMultiLens(
        context: Context,
        outDir: File,
        checkBudget: () -> Unit = {},
    ): JSONObject {
        outDir.mkdirs()

        // 12A: Manual Exposure Linearity (ISO & Shutter without MANUAL_SENSOR flag)
        val expLinearity = JSONArray()
        for ((label, expNs, iso) in listOf(
            Triple("manual_iso100_10ms", 10_000_000L, 100),
            Triple("manual_iso400_10ms", 10_000_000L, 400),
            Triple("manual_iso1600_10ms", 10_000_000L, 1600),
            Triple("manual_iso200_5ms", 5_000_000L, 200),
            Triple("manual_iso200_20ms", 20_000_000L, 200),
        )) {
            expLinearity.put(runYuvSingleCaptureArm(context, "0", label) { b, _ ->
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, 33_333_333L)
            })
        }

        // 12B: Variable Aperture (f/1.6..f/4.0) under locked 10ms, ISO 400
        val apertureArr = JSONArray()
        for (ap in listOf(1.6f, 2.0f, 2.8f, 4.0f)) {
            apertureArr.put(runYuvSingleCaptureArm(context, "0", "std_lens_aperture_$ap") { b, _ ->
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 10_000_000L)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, 400)
                b.set(CaptureRequest.LENS_APERTURE, ap)
            })
            val apCode = (ap * 100f).roundToInt()
            apertureArr.put(runYuvSingleCaptureArm(context, "0", "vendor_aperture_${apCode}") { b, _ ->
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 10_000_000L)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, 400)
                setByNameByte(b, "com.huawei.capture.metadata.professionalMode", byteArrayOf(1))
                setByNameByte(b, "com.huawei.capture.metadata.apertureMode", byteArrayOf(1))
                setByNameInt(b, "com.huawei.capture.metadata.apertureValue", intArrayOf(apCode))
            })
        }

        // 12C: API 31 Kelvin WB via COLOR_CORRECTION_GAINS + TRANSFORM_MATRIX and vendor sensorWbValue
        val wbArr = JSONArray()
        val wbSpecs = listOf(
            Triple("gains_warm_2800K", 2800, RggbChannelVector(2.4f, 1.0f, 1.0f, 1.15f)),
            Triple("gains_daylight_5600K", 5600, RggbChannelVector(1.55f, 1.0f, 1.0f, 1.65f)),
            Triple("gains_cool_7500K", 7500, RggbChannelVector(1.25f, 1.0f, 1.0f, 2.15f)),
        )
        for ((label, kelvin, vec) in wbSpecs) {
            wbArr.put(runYuvSingleCaptureArm(context, "0", label) { b, _ ->
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 10_000_000L)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, 400)
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                b.set(CaptureRequest.COLOR_CORRECTION_GAINS, vec)
            })
            wbArr.put(runYuvSingleCaptureArm(context, "0", "vendor_sensorWb_${kelvin}K") { b, _ ->
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 10_000_000L)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, 400)
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                setByNameByte(b, "com.huawei.capture.metadata.professionalMode", byteArrayOf(1))
                setByNameInt(b, "com.huawei.capture.metadata.sensorWbValue", intArrayOf(kelvin))
            })
        }

        // 12D: EDGE / NR / Vendor colorMode / sensorHdrMode / OIS & EIS under locked exposure
        val ispCtrlArr = JSONArray()
        for ((label, cfgFn) in listOf<Pair<String, (CaptureRequest.Builder) -> Unit>>(
            "edge0_nr0_off" to { b ->
                b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            },
            "edge1_nr1_fast" to { b ->
                b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
            },
            "edge2_nr2_hq" to { b ->
                b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            },
            "vendor_colorMode_0" to { b -> setByNameByte(b, "com.huawei.capture.metadata.colorMode", byteArrayOf(0)) },
            "vendor_colorMode_1_vivid" to { b -> setByNameByte(b, "com.huawei.capture.metadata.colorMode", byteArrayOf(1)) },
            "vendor_colorMode_2_smooth" to { b -> setByNameByte(b, "com.huawei.capture.metadata.colorMode", byteArrayOf(2)) },
            "vendor_sensorHdrMode_1" to { b -> setByNameByte(b, "com.huawei.capture.metadata.sensorHdrMode", byteArrayOf(1)) },
            "ois_off_eis_off" to { b ->
                b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                setByNameInt(b, "com.huawei.capture.metadata.oisControlForApk", intArrayOf(0))
            },
            "ois_on_eis_on" to { b ->
                b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                setByNameInt(b, "com.huawei.capture.metadata.oisControlForApk", intArrayOf(1))
            },
        )) {
            ispCtrlArr.put(runYuvSingleCaptureArm(context, "0", label) { b, _ ->
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 10_000_000L)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, 400)
                cfgFn(b)
            })
        }

        // 12E: Multi-Lens CUVA 0x324 + 4K30 / 4K60 / OpenGate 4096x3072@30 across Cameras 0, 1, 2, 4, 5
        val multiLens = JSONArray()
        for (camId in listOf("0", "1", "2", "4", "5")) {
            val targets = if (camId == "1") listOf(Size(3840, 2160) to 30, Size(3840, 2160) to 60)
            else listOf(Size(3840, 2160) to 30, Size(3840, 2160) to 60, Size(4096, 3072) to 30)
            for ((sz, fps) in targets) {
                multiLens.put(try {
                    LabBreakthroughProbes.ensureCoolOrWait(context, checkBudget)
                    runMultiLensCuvaProbe(context, camId, sz, fps)
                } catch (t: Throwable) {
                    json("cameraId" to camId, "size" to sz.toString(), "fps" to fps, "ok" to false, "error" to (t.message ?: t.toString()).take(240))
                })
            }
        }

        return LabBreakthroughProbes.saveProbeJson(outDir, "probe12_pro_controls_multilens.json", json(
            "probe" to "Probe12_HiddenProControlsAndMultiLens",
            "batteryEndC" to LabBreakthroughProbes.batteryTempC(context),
            "exposureLinearity" to expLinearity,
            "variableAperture" to apertureArr,
            "manualKelvinWb" to wbArr,
            "ispAndStabilization" to ispCtrlArr,
            "multiLens" to multiLens,
        ))
    }

    @SuppressLint("MissingPermission")
    private fun runYuvSingleCaptureArm(
        context: Context,
        camId: String,
        armName: String,
        configureRequest: (CaptureRequest.Builder, CameraCharacteristics) -> Unit,
    ): JSONObject = try {
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        lease.ready().get(8, TimeUnit.SECONDS)
        val manager = context.getSystemService(CameraManager::class.java)
        val caps = manager.getCameraCharacteristics(camId)
        val thread = HandlerThread("p12-$armName").apply { start() }
        val handler = Handler(thread.looper)
        val executor = Executor { r -> handler.post(r) }
        val reader = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 3)

        val statsFuture = CompletableFuture<JSONObject>()
        val resultRef = AtomicReference<JSONObject?>(null)
        val frameIdx = AtomicInteger(0)
        reader.setOnImageAvailableListener({ r ->
            val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                if (frameIdx.incrementAndGet() != 12 || statsFuture.isDone) return@setOnImageAvailableListener
                statsFuture.complete(computeYuvQualityStats(img))
            } catch (t: Throwable) {
                statsFuture.completeExceptionally(t)
            } finally { img.close() }
        }, handler)

        val opened = CompletableFuture<CameraDevice>(); val closed = CompletableFuture<Unit>(); val configured = CompletableFuture<CameraCaptureSession>()
        val retiring = AtomicBoolean(); var session: CameraCaptureSession? = null; var openSubmitted = false
        try {
            manager.openCamera(camId, object : CameraDevice.StateCallback() {
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
            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(reader.surface)
                configureRequest(this, caps)
            }.build()
            session.setRepeatingRequest(req, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                    if (resultRef.get() == null && frameIdx.get() >= 10) {
                        val gains = res.get(CaptureResult.COLOR_CORRECTION_GAINS)
                        resultRef.compareAndSet(null, json(
                            "expNs" to res.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                            "iso" to res.get(CaptureResult.SENSOR_SENSITIVITY),
                            "aperture" to res.get(CaptureResult.LENS_APERTURE),
                            "awbMode" to res.get(CaptureResult.CONTROL_AWB_MODE),
                            "ccMode" to res.get(CaptureResult.COLOR_CORRECTION_MODE),
                            "ccGains" to gains?.let { floatArrayOf(it.red, it.greenEven, it.greenOdd, it.blue).joinToString(",", "[", "]") },
                            "edgeMode" to res.get(CaptureResult.EDGE_MODE),
                            "nrMode" to res.get(CaptureResult.NOISE_REDUCTION_MODE),
                            "oisMode" to res.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE),
                            "vsMode" to res.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE),
                        ))
                    }
                }
            }, handler)
            val yuv = statsFuture.get(5, TimeUnit.SECONDS)
            runCatching { session.stopRepeating() }
            json("arm" to armName, "ok" to true, "result" to resultRef.get(), "yuv" to yuv)
        } finally {
            retiring.set(true)
            val cc = closed.thenApply { Unit }; lease.releaseAfter(cc)
            if (openSubmitted) handler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } } else closed.complete(Unit)
            runCatching { cc.get(6, TimeUnit.SECONDS) }
            runCatching { reader.close() }
            thread.quitSafely(); runCatching { thread.join(4_000) }
        }
    } catch (t: Throwable) {
        json("arm" to armName, "ok" to false, "errorClass" to t.javaClass.name, "error" to (t.message ?: t.toString()).take(280))
    }

    @SuppressLint("MissingPermission")
    private fun runMultiLensCuvaProbe(context: Context, camId: String, size: Size, fps: Int): JSONObject {
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        lease.ready().get(8, TimeUnit.SECONDS)
        val manager = context.getSystemService(CameraManager::class.java)
        val caps = manager.getCameraCharacteristics(camId)
        val cuvaReqKey = LabBreakthroughProbes.findVendorRequestKey(caps, "cuvaStateVideoMode")
        val cuvaHint = LabBreakthroughProbes.vendorKeyTypeHint(cuvaReqKey)
        val thread = HandlerThread("p12-ml-$camId").apply { start() }
        val handler = Handler(thread.looper)
        val executor = Executor { r -> handler.post(r) }
        val reader = LabBreakthroughProbes.createUsageImageReader(
            size.width, size.height, ImageFormat.PRIVATE, 6,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_VIDEO_ENCODE,
        )
        val camTs = mutableListOf<Long>()
        val consTs = mutableListOf<Long>()
        val hwFmt = AtomicInteger(-1)
        reader.setOnImageAvailableListener({ r ->
            runCatching {
                r.acquireNextImage()?.use { img ->
                    synchronized(consTs) { consTs += img.timestamp }
                    if (hwFmt.get() == -1) img.hardwareBuffer?.use { hb ->
                        hwFmt.compareAndSet(-1, EglHardwareBufferBridge.describeAhb(hb)?.first ?: hb.format)
                    }
                }
            }
        }, handler)
        val opened = CompletableFuture<CameraDevice>(); val closed = CompletableFuture<Unit>(); val configured = CompletableFuture<CameraCaptureSession>()
        val retiring = AtomicBoolean(); var session: CameraCaptureSession? = null; var openSubmitted = false
        return try {
            manager.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { opened.complete(c); if (retiring.get()) c.close() }
                override fun onDisconnected(c: CameraDevice) { opened.completeExceptionally(IllegalStateException("Disconnected")); c.close() }
                override fun onError(c: CameraDevice, e: Int) { opened.completeExceptionally(IllegalStateException("Err $e")); c.close() }
                override fun onClosed(c: CameraDevice) { closed.complete(Unit) }
            }, handler)
            openSubmitted = true
            val cam = opened.get(6, TimeUnit.SECONDS)
            fun applyKeys(b: CaptureRequest.Builder) {
                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                if (cuvaReqKey != null) setVendorByteOrIntKey(b, cuvaReqKey, 1, cuvaHint)
            }
            val sessReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(reader.surface); applyKeys(this) }.build()
            cam.createCaptureSession(
                SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(reader.surface)), executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { if (retiring.get()) s.close() else configured.complete(s) }
                        override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("Configure failed")); s.close() }
                    },
                ).apply { sessionParameters = sessReq },
            )
            session = configured.get(6, TimeUnit.SECONDS)
            val repReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(reader.surface); applyKeys(this) }.build()
            session.setRepeatingRequest(repReq, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                    res.get(CaptureResult.SENSOR_TIMESTAMP)?.let { synchronized(camTs) { camTs += it } }
                }
            }, handler)
            Thread.sleep(2_000L)
            runCatching { session.stopRepeating() }
            val thresh = 1_500_000_000L / fps.coerceAtLeast(1)
            val camM = synchronized(camTs) { LabBreakthroughProbes.computeTimestampMetrics(camTs.toList(), thresh) }
            val consM = synchronized(consTs) { LabBreakthroughProbes.computeTimestampMetrics(consTs.toList(), thresh) }
            json(
                "cameraId" to camId, "size" to size.toString(), "fpsTarget" to fps,
                "ok" to (consM.frames > 10),
                "cuvaKeyPresent" to (cuvaReqKey != null),
                "hwFmtHex" to hwFmt.get().takeIf { it != -1 }?.let { "0x${it.toString(16)}" },
                "camCb" to with(LabBreakthroughProbes) { camM.toJson() }, "consumer" to with(LabBreakthroughProbes) { consM.toJson() },
            )
        } finally {
            retiring.set(true)
            val cc = closed.thenApply { Unit }; lease.releaseAfter(cc)
            if (openSubmitted) handler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } } else closed.complete(Unit)
            runCatching { cc.get(6, TimeUnit.SECONDS) }
            runCatching { reader.close() }
            thread.quitSafely(); runCatching { thread.join(4_000) }
        }
    }

    private fun computeYuvQualityStats(img: android.media.Image): JSONObject {
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val w = img.width; val h = img.height
        val yB = yP.buffer; val uB = uP.buffer; val vB = vP.buffer
        var sumY = 0.0; var sumY2 = 0.0; var sumU = 0.0; var sumV = 0.0
        var nY = 0; var nUv = 0; var clipHi = 0; var clipLo = 0
        var lapSum2 = 0.0; var nLap = 0
        for (yy in 2 until h - 2 step 2) {
            val rOff = yy * yP.rowStride
            val uOff = (yy - 1) * yP.rowStride
            val dOff = (yy + 1) * yP.rowStride
            for (xx in 2 until w - 2 step 2) {
                val c = yB.get(rOff + xx * yP.pixelStride).toInt() and 0xFF
                sumY += c; sumY2 += c * c; nY++
                if (c >= 253) clipHi++
                if (c <= 2) clipLo++
                val lap = 4 * c -
                    (yB.get(rOff + (xx - 1) * yP.pixelStride).toInt() and 0xFF) -
                    (yB.get(rOff + (xx + 1) * yP.pixelStride).toInt() and 0xFF) -
                    (yB.get(uOff + xx * yP.pixelStride).toInt() and 0xFF) -
                    (yB.get(dOff + xx * yP.pixelStride).toInt() and 0xFF)
                lapSum2 += lap.toDouble() * lap.toDouble(); nLap++
            }
        }
        for (yy in 0 until h / 2 step 2) {
            val uR = yy * uP.rowStride; val vR = yy * vP.rowStride
            for (xx in 0 until w / 2 step 2) {
                sumU += (uB.get(uR + xx * uP.pixelStride).toInt() and 0xFF)
                sumV += (vB.get(vR + xx * vP.pixelStride).toInt() and 0xFF)
                nUv++
            }
        }
        val meanY = sumY / nY.coerceAtLeast(1)
        val stdY = sqrt((sumY2 / nY.coerceAtLeast(1) - meanY * meanY).coerceAtLeast(0.0))
        return json(
            "lumaMean" to meanY,
            "lumaStdDev" to stdY,
            "laplacianRms" to sqrt(lapSum2 / nLap.coerceAtLeast(1)),
            "cbMean" to (sumU / nUv.coerceAtLeast(1)),
            "crMean" to (sumV / nUv.coerceAtLeast(1)),
            "clipHiFrac" to clipHi.toDouble() / nY.coerceAtLeast(1),
            "clipLoFrac" to clipLo.toDouble() / nY.coerceAtLeast(1),
        )
    }

    private fun setByNameByte(b: CaptureRequest.Builder, name: String, value: ByteArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val key = CaptureRequest.Key(name, ByteArray::class.java)
            b.set(key, value)
        }
    }

    private fun setByNameInt(b: CaptureRequest.Builder, name: String, value: IntArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val key = CaptureRequest.Key(name, IntArray::class.java)
            b.set(key, value)
        }
    }

    private fun colorTransformToFloatArray(t: ColorSpaceTransform): FloatArray = FloatArray(9) { i ->
        t.getElement(i % 3, i / 3).toFloat()
    }

    private fun mul3x3(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(9)
        for (r in 0 until 3) for (c in 0 until 3) {
            out[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
        }
        return out
    }

    private fun setByNameFloat(b: CaptureRequest.Builder, caps: CameraCharacteristics, name: String, value: Float) {
        val key = LabBreakthroughProbes.findVendorRequestKey(caps, name)
        runCatching {
            when (LabBreakthroughProbes.vendorKeyTypeHint(key)) {
                "float" -> {
                    @Suppress("UNCHECKED_CAST")
                    b.set(key as CaptureRequest.Key<Float>, value)
                }
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    b.set(key as CaptureRequest.Key<FloatArray>, floatArrayOf(value))
                }
            }
        }.onFailure {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { b.set(CaptureRequest.Key(name, FloatArray::class.java), floatArrayOf(value)) }
                    .onFailure { runCatching { b.set(CaptureRequest.Key(name, Float::class.javaObjectType), value) } }
            }
        }
    }

    // =========================================================================
    // Probe 13 (Wave A.5): Precision Closure Probes (13A-13D)
    // =========================================================================
    fun runProbe13_ClosureProbes(
        context: Context,
        outDir: File,
        checkBudget: () -> Unit = {},
    ): JSONObject {
        outDir.mkdirs()

        // 13A: 0x7F000011 UV Layout + Live GPU-Packed P010 PBO + egl8 +-0.5/255 TPDF
        val p13a = JSONArray()
        for ((name, cfgFn) in listOf<Pair<String, () -> JSONObject>>(
            "13A_0x7f11_1080p_default" to {
                runProbe13_0x7f11Pattern(
                    "13A_0x7f11_1080p_default", Size(1920, 1080),
                    File(outDir, "p13_0x7f11_1080p_default.mp4"),
                    explicitStride = false, rowProbe = false, bt2020Tags = false,
                )
            },
            "13A_0x7f11_1080p_stride2w" to {
                runProbe13_0x7f11Pattern(
                    "13A_0x7f11_1080p_stride2w", Size(1920, 1080),
                    File(outDir, "p13_0x7f11_1080p_stride2w.mp4"),
                    explicitStride = true, rowProbe = false, bt2020Tags = true,
                )
            },
            "13A_0x7f11_4k_stride2w" to {
                runProbe13_0x7f11Pattern(
                    "13A_0x7f11_4k_stride2w", Size(3840, 2160),
                    File(outDir, "p13_0x7f11_4k_stride2w.mp4"),
                    explicitStride = true, rowProbe = false, bt2020Tags = true,
                )
            },
            "13A_0x7f11_1080p_rowprobe" to {
                runProbe13_0x7f11Pattern(
                    "13A_0x7f11_1080p_rowprobe", Size(1920, 1080),
                    File(outDir, "p13_0x7f11_1080p_rowprobe.mp4"),
                    explicitStride = false, rowProbe = true, bt2020Tags = false,
                )
            },
            "13A_0x7f11_4k_rowprobe" to {
                runProbe13_0x7f11Pattern(
                    "13A_0x7f11_4k_rowprobe", Size(3840, 2160),
                    File(outDir, "p13_0x7f11_4k_rowprobe.mp4"),
                    explicitStride = false, rowProbe = true, bt2020Tags = false,
                )
            },
            "13A_egl8_tpdf05_ramp" to {
                runProbe13_Egl8TpdfHalfRamp("13A_egl8_tpdf05_ramp", File(outDir, "p13_egl8_tpdf05_ramp.mp4"))
            },
            "13A_live_p010_pbo_4k30" to {
                runProbe13_LiveP010Pbo(context, "13A_live_p010_pbo_4k30", 30, File(outDir, "p13_live_p010_pbo_4k30.mp4"))
            },
            "13A_live_p010_pbo_4k60" to {
                runProbe13_LiveP010Pbo(context, "13A_live_p010_pbo_4k60", 60, File(outDir, "p13_live_p010_pbo_4k60.mp4"))
            },
            "13A_live_egl8_tpdf05_4k60" to {
                runProbe13_LiveEgl8Tpdf4k60(context, File(outDir, "p13_live_egl8_tpdf05_4k60.mp4"))
            },
        )) {
            val res = try {
                val t = LabBreakthroughProbes.ensureCoolOrWait(context, checkBudget)
                cfgFn().put("batteryStartC", t)
            } catch (t: Throwable) {
                json("arm" to name, "ok" to false, "errorClass" to t.javaClass.name, "error" to (t.message ?: t.toString()).take(300))
            }
            p13a.put(res)
        }

        // 13B: Physical Variable Aperture (vaFNumber on cam0 & cam2, settle 20 frames / 350ms)
        val p13b = JSONArray()
        for (camId in listOf("0", "2")) {
            for (ap in listOf(1.6f, 2.0f, 2.8f, 4.0f)) {
                val arms = listOf(
                    "cam${camId}_va_only_$ap" to { b: CaptureRequest.Builder, c: CameraCharacteristics ->
                        setByNameFloat(b, c, "com.huawei.capture.metadata.vaFNumber", ap)
                    },
                    "cam${camId}_pro_va_$ap" to { b: CaptureRequest.Builder, c: CameraCharacteristics ->
                        setByNameByte(b, "com.huawei.capture.metadata.professionalMode", byteArrayOf(1))
                        setByNameFloat(b, c, "com.huawei.capture.metadata.vaFNumber", ap)
                    },
                    "cam${camId}_pro_apMode_va_$ap" to { b: CaptureRequest.Builder, c: CameraCharacteristics ->
                        setByNameByte(b, "com.huawei.capture.metadata.professionalMode", byteArrayOf(1))
                        setByNameByte(b, "com.huawei.capture.metadata.apertureMode", byteArrayOf(1))
                        setByNameFloat(b, c, "com.huawei.capture.metadata.vaFNumber", ap)
                    },
                )
                for ((label, fn) in arms) {
                    p13b.put(runProbe13_YuvSettledArm(context, camId, label, settleFrames = 20) { b, c ->
                        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 10_000_000L)
                        b.set(CaptureRequest.SENSOR_SENSITIVITY, 400)
                        b.set(CaptureRequest.SENSOR_FRAME_DURATION, 33_333_333L)
                        fn(b, c)
                    })
                }
            }
        }

        // 13C: API 31 Manual Kelvin White Balance strategies (locked 10ms ISO 400 on cam0)
        val p13c = JSONArray()
        val identityMatrix = ColorSpaceTransform(intArrayOf(1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1))
        val wbSpecs = listOf(
            Triple("2800K", 2800, RggbChannelVector(2.4f, 1.0f, 1.0f, 1.15f)),
            Triple("5600K", 5600, RggbChannelVector(1.55f, 1.0f, 1.0f, 1.65f)),
            Triple("7500K", 7500, RggbChannelVector(1.25f, 1.0f, 1.0f, 2.15f)),
        )
        for ((kLabel, kVal, vec) in wbSpecs) {
            p13c.put(runProbe13_YuvSettledArm(context, "0", "cc_gains_identity_$kLabel", settleFrames = 12) { b, _ ->
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 10_000_000L)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, 400)
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, identityMatrix)
                b.set(CaptureRequest.COLOR_CORRECTION_GAINS, vec)
            })
            p13c.put(runProbe13_YuvSettledArm(context, "0", "sensorWb_awbAuto_$kLabel", settleFrames = 12) { b, _ ->
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 10_000_000L)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, 400)
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                setByNameByte(b, "com.huawei.capture.metadata.professionalMode", byteArrayOf(1))
                setByNameInt(b, "com.huawei.capture.metadata.sensorWbValue", intArrayOf(kVal))
            })
            p13c.put(runProbe13_YuvSettledArm(context, "0", "sensorWb_awbOff_ccFast_$kLabel", settleFrames = 12) { b, _ ->
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 10_000_000L)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, 400)
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
                setByNameByte(b, "com.huawei.capture.metadata.professionalMode", byteArrayOf(1))
                setByNameInt(b, "com.huawei.capture.metadata.sensorWbValue", intArrayOf(kVal))
            })
        }
        for ((pName, awbMode) in listOf(
            "preset_incandescent" to CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
            "preset_daylight" to CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
            "preset_shade" to CaptureRequest.CONTROL_AWB_MODE_SHADE,
        )) {
            p13c.put(runProbe13_YuvSettledArm(context, "0", pName, settleFrames = 12) { b, _ ->
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 10_000_000L)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, 400)
                b.set(CaptureRequest.CONTROL_AWB_MODE, awbMode)
                b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
            })
        }

        // 13D: RAW_SENSOR Compression Forensics & Decompression Unlock Test
        val p13d = JSONArray()
        for ((armName, modeCfg) in listOf(
            "13D_raw_still_capture" to Raw13Mode.STILL_CAPTURE,
            "13D_raw_manual_template" to Raw13Mode.MANUAL_TEMPLATE,
            "13D_raw_inconsistent_0" to Raw13Mode.INCONSISTENT_0,
            "13D_raw_inconsistent_1" to Raw13Mode.INCONSISTENT_1,
            "13D_raw10_format" to Raw13Mode.RAW10_FORMAT,
        )) {
            val res = try {
                val t = LabBreakthroughProbes.ensureCoolOrWait(context, checkBudget)
                runProbe13D_RawCompressionArm(context, outDir, armName, modeCfg).put("batteryStartC", t)
            } catch (t: Throwable) {
                json("arm" to armName, "ok" to false, "errorClass" to t.javaClass.name, "error" to (t.message ?: t.toString()).take(300))
            }
            p13d.put(res)
        }

        return LabBreakthroughProbes.saveProbeJson(outDir, "probe13_closure.json", json(
            "probe" to "Probe13_ClosureProbes",
            "batteryEndC" to LabBreakthroughProbes.batteryTempC(context),
            "p13a_0x7f11_and_p010" to p13a,
            "p13b_variable_aperture" to p13b,
            "p13c_manual_kelvin_wb" to p13c,
            "p13d_raw_compression" to p13d,
        ))
    }

    private fun runProbe13_0x7f11Pattern(
        armName: String,
        size: Size,
        outFile: File,
        explicitStride: Boolean,
        rowProbe: Boolean,
        bt2020Tags: Boolean,
    ): JSONObject {
        val w = size.width; val h = size.height; val frames = if (rowProbe) 10 else 15
        val expectedP010 = w * h * 3
        val codecName = LabBreakthroughProbes.selectHevcEncoderName()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h).apply {
            setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_LEVEL, CodecProfileLevel.HEVCMainTierLevel62)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FORMAT_HISI_YUV420SP_10BIT)
            setInteger(MediaFormat.KEY_BIT_RATE, if (w >= 3840) 60_000_000 else 30_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, expectedP010)
            if (explicitStride) {
                setInteger(MediaFormat.KEY_STRIDE, w * 2)
                setInteger(MediaFormat.KEY_SLICE_HEIGHT, h)
            }
            if (bt2020Tags) {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
            }
        }
        val codec = MediaCodec.createByCodecName(codecName)
        var muxer: MediaMuxer? = null
        var started = false; var muxerStarted = false
        val samples = AtomicInteger(0)
        var capObserved = -1
        var inputFmtConfigure = ""; var inputFmtStarted = ""
        return try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputFmtConfigure = codec.inputFormat.toString()
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.start(); started = true
            inputFmtStarted = codec.inputFormat.toString()

            val pattern = ByteArray(expectedP010)
            val bb = ByteBuffer.wrap(pattern).order(ByteOrder.LITTLE_ENDIAN)
            if (rowProbe) {
                val totalRows = expectedP010 / (w * 2)
                for (r in 0 until totalRows) {
                    val code = ((r / 4).coerceAtMost(1023) shl 6) and 0xFFFF
                    val sh = code.toShort()
                    for (xx in 0 until w) bb.putShort(sh)
                }
            } else {
                for (yy in 0 until h) {
                    val yBase = (yy * 511) / (h - 1)
                    for (xx in 0 until w) {
                        val v10 = (yBase + (xx * 512) / (w - 1)).coerceIn(0, 1023)
                        bb.putShort(((v10 shl 6) and 0xFFFF).toShort())
                    }
                }
                val uCode = ((256 shl 6) and 0xFFFF).toShort()
                val vCode = ((768 shl 6) and 0xFFFF).toShort()
                val uvPairs = (w * h) / 4
                for (i in 0 until uvPairs) {
                    bb.putShort(uCode)
                    bb.putShort(vCode)
                }
            }

            val info = MediaCodec.BufferInfo()
            var submitted = 0; var eosQueued = false; var eosRecv = false; var track = -1
            val t0 = System.nanoTime()
            val dl = System.currentTimeMillis() + 15_000L
            while (!eosRecv && System.currentTimeMillis() < dl) {
                if (!eosQueued) {
                    val inIdx = codec.dequeueInputBuffer(5_000L)
                    if (inIdx >= 0) {
                        if (submitted >= frames) {
                            codec.queueInputBuffer(inIdx, 0, 0, submitted * 33_333L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosQueued = true
                        } else {
                            val buf = codec.getInputBuffer(inIdx)!!
                            if (capObserved < 0) capObserved = buf.capacity()
                            val n = pattern.size.coerceAtMost(buf.capacity())
                            buf.clear(); buf.put(pattern, 0, n)
                            codec.queueInputBuffer(inIdx, 0, n, submitted * 33_333L, 0)
                            submitted++
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 5_000L)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true }
                } else if (outIdx >= 0) {
                    val b = codec.getOutputBuffer(outIdx)
                    if (b != null && info.size > 0 && muxerStarted && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        b.position(info.offset); b.limit(info.offset + info.size)
                        muxer.writeSampleData(track, b, info); samples.incrementAndGet()
                    }
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eosRecv = true
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }
            val wallMs = (System.nanoTime() - t0) / 1e6
            json(
                "arm" to armName, "ok" to (samples.get() >= frames),
                "clip" to outFile.name, "clipBytes" to outFile.length(),
                "inputFmtConfigure" to inputFmtConfigure, "inputFmtStarted" to inputFmtStarted,
                "expectedP010Bytes" to expectedP010, "inputBufferCapacity" to capObserved,
                "wallMs" to wallMs, "encodeFps" to (samples.get() * 1000.0 / wallMs.coerceAtLeast(1.0)),
                "encodedSamples" to samples.get(),
            )
        } finally {
            if (started) runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun runProbe13_Egl8TpdfHalfRamp(armName: String, outFile: File): JSONObject {
        val w = 1920; val h = 1080; val frames = 30
        val codecName = LabBreakthroughProbes.selectHevcEncoderName()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h).apply {
            setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_LEVEL, CodecProfileLevel.HEVCMainTierLevel62)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 30_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
        }
        val codec = MediaCodec.createByCodecName(codecName)
        var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY; var ctx: EGLContext = EGL14.EGL_NO_CONTEXT; var win: EGLSurface = EGL14.EGL_NO_SURFACE
        var surface: Surface? = null; var muxer: MediaMuxer? = null
        var started = false; var muxerStarted = false
        val samples = AtomicInteger(0)
        // Standard +-0.5 LSB TPDF dither: ((r1 - r2) * 0.5 / 255.0)
        val fragTpdf05 = """#version 300 es
precision highp float;
uniform float uFrame;
in vec2 vTexCoord;
out vec4 outColor;
float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
void main() {
    float r1 = hash12(gl_FragCoord.xy + vec2(uFrame * 17.0, uFrame * 31.0));
    float r2 = hash12(gl_FragCoord.xy + vec2(uFrame * 59.0 + 7.3, uFrame * 11.0 + 3.1));
    float tpdf = (r1 - r2) / 255.0;
    float v = clamp(vTexCoord.x + tpdf, 0.0, 1.0);
    outColor = vec4(v, v, v, 1.0);
}"""
        return try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val v = IntArray(2); check(EGL14.eglInitialize(dpy, v, 0, v, 1))
            val cfgs = arrayOfNulls<EGLConfig>(1); val cnt = IntArray(1)
            EGL14.eglChooseConfig(dpy, intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
            ), 0, cfgs, 0, 1, cnt, 0)
            ctx = EGL14.eglCreateContext(dpy, requireNotNull(cfgs[0]), EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            win = EGL14.eglCreateWindowSurface(dpy, cfgs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(dpy, win, win, ctx))
            val prog = LabBreakthroughProbes.compileGlProgram(VERTEX_QUAD_UPRIGHT, fragTpdf05)
            val frameLoc = GLES30.glGetUniformLocation(prog, "uFrame")
            val qb = ByteBuffer.allocateDirect(FULLSCREEN_QUAD.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(FULLSCREEN_QUAD).position(0) }
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.start(); started = true
            val info = MediaCodec.BufferInfo(); var track = -1
            for (f in 0 until frames) {
                GLES30.glViewport(0, 0, w, h); GLES30.glUseProgram(prog)
                if (frameLoc >= 0) GLES30.glUniform1f(frameLoc, f.toFloat())
                qb.position(0); GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, qb)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                EGLExt.eglPresentationTimeANDROID(dpy, win, f * 33_333_333L); EGL14.eglSwapBuffers(dpy, win)
                while (true) {
                    val idx = codec.dequeueOutputBuffer(info, 0)
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true }
                    } else if (idx >= 0) {
                        val b = codec.getOutputBuffer(idx)
                        if (b != null && info.size > 0 && muxerStarted && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            b.position(info.offset); b.limit(info.offset + info.size)
                            muxer.writeSampleData(track, b, info); samples.incrementAndGet()
                        }
                        codec.releaseOutputBuffer(idx, false)
                    } else break
                }
            }
            codec.signalEndOfInputStream()
            val dl = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < dl) {
                val idx = codec.dequeueOutputBuffer(info, 10_000L)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true }
                } else if (idx >= 0) {
                    val b = codec.getOutputBuffer(idx)
                    if (b != null && info.size > 0 && muxerStarted && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        b.position(info.offset); b.limit(info.offset + info.size)
                        muxer.writeSampleData(track, b, info); samples.incrementAndGet()
                    }
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(idx, false)
                    if (eos) break
                }
            }
            GLES30.glDeleteProgram(prog)
            json("arm" to armName, "ok" to (samples.get() >= frames), "clip" to outFile.name, "clipBytes" to outFile.length(), "encodedSamples" to samples.get())
        } finally {
            if (started) runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (win != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, win)
                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
                EGL14.eglTerminate(dpy)
            }
            runCatching { surface?.release() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun runProbe13_LiveP010Pbo(context: Context, armName: String, fps: Int, outFile: File): JSONObject {
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        lease.ready().get(8, TimeUnit.SECONDS)
        val manager = context.getSystemService(CameraManager::class.java)
        val caps = manager.getCameraCharacteristics("0")
        val cuvaReqKey = LabBreakthroughProbes.findVendorRequestKey(caps, "cuvaStateVideoMode")
        val cuvaHint = LabBreakthroughProbes.vendorKeyTypeHint(cuvaReqKey)
        val outW = 3840; val outH = 2160
        val fboW = outW / 2; val fboH = (outH * 3) / 2 // 1920 x 3240 RGBA8 = 24,883,200 bytes = exact P010
        val frameBytes = fboW * fboH * 4
        val camThread = HandlerThread("p13-pbo-cam").apply { start() }
        val camHandler = Handler(camThread.looper)
        val glThread = HandlerThread("p13-pbo-gl").apply { start() }
        val glHandler = Handler(glThread.looper)
        val executor = Executor { r -> camHandler.post(r) }
        val reader = LabBreakthroughProbes.createUsageImageReader(
            outW, outH, ImageFormat.PRIVATE, 6,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_VIDEO_ENCODE,
        )
        val codecName = LabBreakthroughProbes.selectHevcEncoderName()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, outW, outH).apply {
            setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_LEVEL, CodecProfileLevel.HEVCMainTierLevel62)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FORMAT_HISI_YUV420SP_10BIT)
            setInteger(MediaFormat.KEY_BIT_RATE, if (fps >= 50) 100_000_000 else 60_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frameBytes)
            setInteger(MediaFormat.KEY_STRIDE, outW * 2)
            setInteger(MediaFormat.KEY_SLICE_HEIGHT, outH)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
        }
        val codec = MediaCodec.createByCodecName(codecName)
        var muxer: MediaMuxer? = null
        var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY; var ctx: EGLContext = EGL14.EGL_NO_CONTEXT; var pbuf: EGLSurface = EGL14.EGL_NO_SURFACE
        var texExt = 0; var texFbo = 0; var fbo = 0; var prog = 0
        val pbos = IntArray(2)
        var codecStarted = false; var muxerStarted = false
        val recordingOn = AtomicBoolean(false); val acceptLock = Any()
        val samples = AtomicInteger(0); val hwFmt = AtomicInteger(-1)
        val camTs = mutableListOf<Long>(); val glTs = mutableListOf<Long>()
        val drawMs = mutableListOf<Double>(); val mapCopyMs = mutableListOf<Double>()
        val opened = CompletableFuture<CameraDevice>(); val closed = CompletableFuture<Unit>(); val configured = CompletableFuture<CameraCaptureSession>()
        val retiring = AtomicBoolean(); var session: CameraCaptureSession? = null; var openSubmitted = false

        val fragPackP010 = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTexture;
uniform int uOutW;
uniform int uOutH;
out vec4 outColor;
const float HLG_A = 0.17883277;
const float HLG_B = 0.28466892;
const float HLG_C = 0.55991073;
vec3 sampleOcLog2(vec2 uv) {
    vec3 e = clamp(texture(uTexture, uv).rgb, 0.0, 1.0);
    bvec3 low = lessThanEqual(e, vec3(0.5));
    vec3 hlgLin = mix((exp((e - HLG_C) / HLG_A) + HLG_B) / 12.0, (e * e) / 3.0, low);
    vec3 bt2020Lin = mat3(0.627404, 0.069097, 0.016391, 0.329283, 0.919540, 0.088013, 0.043313, 0.011362, 0.895595) * hlgLin;
    return clamp(vec3(0.10) + vec3(0.80) * log(vec3(1.0) + 50.0 * clamp(bt2020Lin, 0.0, 1.0)) / log(vec3(51.0)), 0.0, 1.0);
}
vec2 pack10Le(int v10) {
    int q16 = clamp(v10, 0, 1023) << 6;
    return vec2(float(q16 & 255), float((q16 >> 8) & 255)) / 255.0;
}
void main() {
    float fx = floor(gl_FragCoord.x);
    float fy = floor(gl_FragCoord.y);
    float invW = 1.0 / float(uOutW);
    float invH = 1.0 / float(uOutH);
    if (fy < float(uOutH)) {
        float v = 1.0 - ((fy + 0.5) * invH);
        vec3 c0 = sampleOcLog2(vec2((2.0 * fx + 0.5) * invW, v));
        vec3 c1 = sampleOcLog2(vec2((2.0 * fx + 1.5) * invW, v));
        // Full-range BT.2020 luma coeffs
        int y0 = int(round(clamp(dot(c0, vec3(0.2627, 0.6780, 0.0593)), 0.0, 1.0) * 1023.0));
        int y1 = int(round(clamp(dot(c1, vec3(0.2627, 0.6780, 0.0593)), 0.0, 1.0) * 1023.0));
        outColor = vec4(pack10Le(y0), pack10Le(y1));
    } else {
        float cy = fy - float(uOutH);
        float u = (2.0 * fx + 1.0) * invW;
        float v = 1.0 - ((2.0 * cy + 1.0) * invH);
        vec3 c = sampleOcLog2(vec2(u, v));
        float y = dot(c, vec3(0.2627, 0.6780, 0.0593));
        float cb = (c.b - y) / 1.8814 + 0.5;
        float cr = (c.r - y) / 1.4746 + 0.5;
        int cbi = int(round(clamp(cb, 0.0, 1.0) * 1023.0));
        int cri = int(round(clamp(cr, 0.0, 1.0) * 1023.0));
        outColor = vec4(pack10Le(cbi), pack10Le(cri));
    }
}"""

        return try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply { setOrientationHint(90) }
            val glInit = CompletableFuture<Unit>()
            lateinit var qb: FloatBuffer
            glHandler.post {
                try {
                    dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                    val ver = IntArray(2); check(EGL14.eglInitialize(dpy, ver, 0, ver, 1))
                    val cfgs = arrayOfNulls<EGLConfig>(1); val cnt = IntArray(1)
                    EGL14.eglChooseConfig(dpy, intArrayOf(
                        EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_NONE,
                    ), 0, cfgs, 0, 1, cnt, 0)
                    ctx = EGL14.eglCreateContext(dpy, requireNotNull(cfgs[0]), EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL_CONTEXT_PRIORITY_LEVEL_IMG, EGL_CONTEXT_PRIORITY_HIGH_IMG, EGL14.EGL_NONE), 0)
                    pbuf = EGL14.eglCreatePbufferSurface(dpy, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
                    check(EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx))
                    val t = IntArray(2); GLES30.glGenTextures(2, t, 0); texExt = t[0]; texFbo = t[1]
                    GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texExt)
                    GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texFbo)
                    GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, fboW, fboH)
                    val fbs = IntArray(1); GLES30.glGenFramebuffers(1, fbs, 0); fbo = fbs[0]
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
                    GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texFbo, 0)
                    check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "RGBA8 packed P010 FBO incomplete" }
                    GLES30.glGenBuffers(2, pbos, 0)
                    for (p in pbos) {
                        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, p)
                        GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, frameBytes, null, GLES30.GL_STREAM_READ)
                    }
                    GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
                    prog = LabBreakthroughProbes.compileGlProgram(VERTEX_QUAD_UPRIGHT, fragPackP010)
                    qb = ByteBuffer.allocateDirect(FULLSCREEN_QUAD.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(FULLSCREEN_QUAD).position(0) }
                    glInit.complete(Unit)
                } catch (t: Throwable) { glInit.completeExceptionally(t) }
            }
            glInit.get(5, TimeUnit.SECONDS)
            codec.start(); codecStarted = true
            val drainDone = CountDownLatch(1)
            Thread {
                val info = MediaCodec.BufferInfo(); var track = -1
                try {
                    while (true) {
                        val idx = codec.dequeueOutputBuffer(info, 20_000L)
                        if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (!muxerStarted) { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true }
                        } else if (idx >= 0) {
                            val b = codec.getOutputBuffer(idx)
                            if (b != null && info.size > 0 && muxerStarted && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                b.position(info.offset); b.limit(info.offset + info.size)
                                muxer.writeSampleData(track, b, info); samples.incrementAndGet()
                            }
                            val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            codec.releaseOutputBuffer(idx, false)
                            if (eos) break
                        }
                    }
                } finally { drainDone.countDown() }
            }.apply { start() }

            var pboIdx = 0; var prevReady = false; var prevPtsUs = 0L; var baseTs = -1L
            reader.setOnImageAvailableListener({ r ->
                val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                val hb = runCatching { img.hardwareBuffer }.getOrNull()
                if (hb != null && hwFmt.get() == -1) hwFmt.compareAndSet(-1, EglHardwareBufferBridge.describeAhb(hb)?.first ?: hb.format)
                var eglImg = 0L
                try {
                    if (hb == null || !recordingOn.get()) return@setOnImageAvailableListener
                    synchronized(acceptLock) {
                        if (!recordingOn.get()) return@synchronized
                        eglImg = EglHardwareBufferBridge.createEglImage(hb)
                        if (eglImg == 0L) return@synchronized
                        val t0 = System.nanoTime()
                        EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx)
                        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
                        GLES30.glViewport(0, 0, fboW, fboH)
                        GLES30.glUseProgram(prog)
                        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texExt)
                        check(EglHardwareBufferBridge.bindExternalTexture(eglImg) == 0)
                        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uTexture"), 0)
                        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uOutW"), outW)
                        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uOutH"), outH)
                        qb.position(0); GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, qb)
                        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

                        val curPbo = pbos[pboIdx]
                        val prevPbo = pbos[1 - pboIdx]
                        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
                        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, curPbo)
                        GLES30.glReadPixels(0, 0, fboW, fboH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 0)
                        GLES30.glFlush()
                        val tDraw = System.nanoTime()
                        drawMs += (tDraw - t0) / 1e6

                        if (baseTs < 0L) baseTs = img.timestamp
                        val ptsUs = ((img.timestamp - baseTs) / 1_000L).coerceAtLeast(0L)
                        if (prevReady) {
                            val inIdx = codec.dequeueInputBuffer(5_000L)
                            if (inIdx >= 0) {
                                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, prevPbo)
                                val mapped = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, frameBytes, GLES30.GL_MAP_READ_BIT) as? ByteBuffer
                                val inBuf = codec.getInputBuffer(inIdx)!!
                                val n = if (mapped != null) {
                                    mapped.position(0); val len = frameBytes.coerceAtMost(inBuf.capacity()); mapped.limit(len)
                                    inBuf.clear(); inBuf.put(mapped); len
                                } else 0
                                GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
                                codec.queueInputBuffer(inIdx, 0, n, prevPtsUs, 0)
                            }
                        }
                        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
                        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                        mapCopyMs += (System.nanoTime() - tDraw) / 1e6
                        prevReady = true; prevPtsUs = ptsUs; pboIdx = 1 - pboIdx
                        glTs += img.timestamp
                    }
                } finally {
                    GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
                    if (eglImg != 0L) EglHardwareBufferBridge.destroyEglImage(eglImg)
                    runCatching { hb?.close() }; img.close()
                }
            }, glHandler)

            manager.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { opened.complete(c); if (retiring.get()) c.close() }
                override fun onDisconnected(c: CameraDevice) { opened.completeExceptionally(IllegalStateException("Disconnected")); c.close() }
                override fun onError(c: CameraDevice, e: Int) { opened.completeExceptionally(IllegalStateException("Err $e")); c.close() }
                override fun onClosed(c: CameraDevice) { closed.complete(Unit) }
            }, camHandler)
            openSubmitted = true
            val cam = opened.get(6, TimeUnit.SECONDS)
            fun applyKeys(b: CaptureRequest.Builder) {
                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                if (cuvaReqKey != null) setVendorByteOrIntKey(b, cuvaReqKey, 1, cuvaHint)
            }
            val sessReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(reader.surface); applyKeys(this) }.build()
            cam.createCaptureSession(
                SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(reader.surface)), executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { if (retiring.get()) s.close() else configured.complete(s) }
                        override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("Configure failed")); s.close() }
                    },
                ).apply { sessionParameters = sessReq },
            )
            session = configured.get(6, TimeUnit.SECONDS)
            val repReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(reader.surface); applyKeys(this) }.build()
            session.setRepeatingRequest(repReq, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                    if (recordingOn.get()) res.get(CaptureResult.SENSOR_TIMESTAMP)?.let { synchronized(camTs) { camTs += it } }
                }
            }, camHandler)
            Thread.sleep(500L)
            recordingOn.set(true)
            Thread.sleep(3_000L)
            synchronized(acceptLock) { recordingOn.set(false) }
            runCatching { session.stopRepeating() }
            val eosFut = CompletableFuture<Unit>()
            glHandler.post {
                val inIdx = codec.dequeueInputBuffer(50_000L)
                if (inIdx >= 0) codec.queueInputBuffer(inIdx, 0, 0, prevPtsUs + (1_000_000L / fps), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                eosFut.complete(Unit)
            }
            eosFut.get(4, TimeUnit.SECONDS)
            drainDone.await(6, TimeUnit.SECONDS)
            val thresh = 1_500_000_000L / fps.coerceAtLeast(1)
            val camM = synchronized(camTs) { LabBreakthroughProbes.computeTimestampMetrics(camTs.toList(), thresh) }
            val glM = LabBreakthroughProbes.computeTimestampMetrics(glTs.toList(), thresh)
            val ds = drawMs.sorted(); val ms = mapCopyMs.sorted()
            json(
                "arm" to armName, "ok" to (samples.get() > 30), "clip" to outFile.name, "clipBytes" to outFile.length(),
                "hwFmtHex" to hwFmt.get().takeIf { it != -1 }?.let { "0x${it.toString(16)}" },
                "drawMsP50" to ds.getOrNull(ds.size / 2), "drawMsP95" to ds.getOrNull((ds.size * 95) / 100),
                "mapCopyMsP50" to ms.getOrNull(ms.size / 2), "mapCopyMsP95" to ms.getOrNull((ms.size * 95) / 100),
                "camCb" to with(LabBreakthroughProbes) { camM.toJson() },
                "glReadback" to with(LabBreakthroughProbes) { glM.toJson() },
                "encodedSamples" to samples.get(),
            )
        } finally {
            retiring.set(true); synchronized(acceptLock) { recordingOn.set(false) }
            val cc = closed.thenApply { Unit }; lease.releaseAfter(cc)
            if (openSubmitted) camHandler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } } else closed.complete(Unit)
            runCatching { cc.get(6, TimeUnit.SECONDS) }
            runCatching { reader.close() }
            if (codecStarted) runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            val glDone = CompletableFuture<Unit>()
            glHandler.post {
                runCatching {
                    GLES30.glDeleteBuffers(2, pbos, 0)
                    if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
                    if (texFbo != 0) GLES30.glDeleteTextures(1, intArrayOf(texFbo), 0)
                    if (texExt != 0) GLES30.glDeleteTextures(1, intArrayOf(texExt), 0)
                    if (prog != 0) GLES30.glDeleteProgram(prog)
                    if (dpy != EGL14.EGL_NO_DISPLAY) {
                        EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                        if (pbuf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, pbuf)
                        if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
                        EGL14.eglTerminate(dpy)
                    }
                }
                glDone.complete(Unit)
            }
            runCatching { glDone.get(4, TimeUnit.SECONDS) }
            camThread.quitSafely(); glThread.quitSafely()
            runCatching { camThread.join(4_000) }; runCatching { glThread.join(4_000) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun runProbe13_LiveEgl8Tpdf4k60(context: Context, outFile: File): JSONObject {
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        lease.ready().get(8, TimeUnit.SECONDS)
        val manager = context.getSystemService(CameraManager::class.java)
        val caps = manager.getCameraCharacteristics("0")
        val cuvaReqKey = LabBreakthroughProbes.findVendorRequestKey(caps, "cuvaStateVideoMode")
        val cuvaHint = LabBreakthroughProbes.vendorKeyTypeHint(cuvaReqKey)
        val w = 3840; val h = 2160; val fps = 60
        val camThread = HandlerThread("p13-tpdf-cam").apply { start() }
        val camHandler = Handler(camThread.looper)
        val glThread = HandlerThread("p13-tpdf-gl").apply { start() }
        val glHandler = Handler(glThread.looper)
        val executor = Executor { r -> camHandler.post(r) }
        val reader = LabBreakthroughProbes.createUsageImageReader(
            w, h, ImageFormat.PRIVATE, 6,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_VIDEO_ENCODE,
        )
        val codecName = LabBreakthroughProbes.selectHevcEncoderName()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h).apply {
            setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_LEVEL, CodecProfileLevel.HEVCMainTierLevel62)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 100_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
        }
        val codec = MediaCodec.createByCodecName(codecName)
        var muxer: MediaMuxer? = null; var codecSurface: Surface? = null
        var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY; var ctx: EGLContext = EGL14.EGL_NO_CONTEXT; var win: EGLSurface = EGL14.EGL_NO_SURFACE
        var texExt = 0; var prog = 0
        var codecStarted = false; var muxerStarted = false
        val recordingOn = AtomicBoolean(false); val acceptLock = Any()
        val hwFmt = AtomicInteger(-1); val samples = AtomicInteger(0)
        val camTs = mutableListOf<Long>(); val glTs = mutableListOf<Long>()
        val opened = CompletableFuture<CameraDevice>(); val closed = CompletableFuture<Unit>(); val configured = CompletableFuture<CameraCaptureSession>()
        val retiring = AtomicBoolean(); var session: CameraCaptureSession? = null; var openSubmitted = false

        val fragOcLog2Tpdf05 = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTexture;
uniform float uFrame;
in vec2 vTexCoord;
out vec4 outColor;
const float HLG_A = 0.17883277;
const float HLG_B = 0.28466892;
const float HLG_C = 0.55991073;
float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
void main() {
    vec3 e = clamp(texture(uTexture, vTexCoord).rgb, 0.0, 1.0);
    bvec3 low = lessThanEqual(e, vec3(0.5));
    vec3 hlgLin = mix((exp((e - HLG_C) / HLG_A) + HLG_B) / 12.0, (e * e) / 3.0, low);
    vec3 lin = mat3(0.627404, 0.069097, 0.016391, 0.329283, 0.919540, 0.088013, 0.043313, 0.011362, 0.895595) * hlgLin;
    vec3 oclog = vec3(0.10) + vec3(0.80) * log(vec3(1.0) + 50.0 * clamp(lin, 0.0, 1.0)) / log(vec3(51.0));
    float r1 = hash12(gl_FragCoord.xy + vec2(uFrame * 17.0, uFrame * 31.0));
    float r2 = hash12(gl_FragCoord.xy + vec2(uFrame * 59.0 + 7.3, uFrame * 11.0 + 3.1));
    float d = (r1 - r2) / 255.0;
    outColor = vec4(clamp(oclog + vec3(d), 0.0, 1.0), 1.0);
}"""

        return try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codecSurface = codec.createInputSurface()
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply { setOrientationHint(90) }
            val glInit = CompletableFuture<Unit>()
            lateinit var qb: FloatBuffer
            glHandler.post {
                try {
                    dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                    val v = IntArray(2); check(EGL14.eglInitialize(dpy, v, 0, v, 1))
                    val cfgs = arrayOfNulls<EGLConfig>(1); val cnt = IntArray(1)
                    EGL14.eglChooseConfig(dpy, intArrayOf(
                        EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                        EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
                    ), 0, cfgs, 0, 1, cnt, 0)
                    ctx = EGL14.eglCreateContext(dpy, requireNotNull(cfgs[0]), EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL_CONTEXT_PRIORITY_LEVEL_IMG, EGL_CONTEXT_PRIORITY_HIGH_IMG, EGL14.EGL_NONE), 0)
                    win = EGL14.eglCreateWindowSurface(dpy, cfgs[0], codecSurface, intArrayOf(EGL14.EGL_NONE), 0)
                    check(EGL14.eglMakeCurrent(dpy, win, win, ctx))
                    val t = IntArray(1); GLES30.glGenTextures(1, t, 0); texExt = t[0]
                    GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texExt)
                    GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                    prog = LabBreakthroughProbes.compileGlProgram(VERTEX_QUAD_UPRIGHT, fragOcLog2Tpdf05)
                    qb = ByteBuffer.allocateDirect(FULLSCREEN_QUAD.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(FULLSCREEN_QUAD).position(0) }
                    glInit.complete(Unit)
                } catch (t: Throwable) { glInit.completeExceptionally(t) }
            }
            glInit.get(5, TimeUnit.SECONDS)
            codec.start(); codecStarted = true
            val drainDone = CountDownLatch(1)
            Thread {
                val info = MediaCodec.BufferInfo(); var track = -1
                try {
                    while (true) {
                        val idx = codec.dequeueOutputBuffer(info, 20_000L)
                        if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (!muxerStarted) { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true }
                        } else if (idx >= 0) {
                            val b = codec.getOutputBuffer(idx)
                            if (b != null && info.size > 0 && muxerStarted && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                b.position(info.offset); b.limit(info.offset + info.size)
                                muxer.writeSampleData(track, b, info); samples.incrementAndGet()
                            }
                            val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            codec.releaseOutputBuffer(idx, false)
                            if (eos) break
                        }
                    }
                } finally { drainDone.countDown() }
            }.apply { start() }

            var frameSeq = 0
            reader.setOnImageAvailableListener({ r ->
                val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                val hb = runCatching { img.hardwareBuffer }.getOrNull()
                if (hb != null && hwFmt.get() == -1) hwFmt.compareAndSet(-1, EglHardwareBufferBridge.describeAhb(hb)?.first ?: hb.format)
                var eglImg = 0L
                try {
                    if (hb == null || !recordingOn.get()) return@setOnImageAvailableListener
                    synchronized(acceptLock) {
                        if (!recordingOn.get()) return@synchronized
                        eglImg = EglHardwareBufferBridge.createEglImage(hb)
                        if (eglImg == 0L) return@synchronized
                        EGL14.eglMakeCurrent(dpy, win, win, ctx)
                        GLES30.glViewport(0, 0, w, h); GLES30.glUseProgram(prog)
                        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texExt)
                        check(EglHardwareBufferBridge.bindExternalTexture(eglImg) == 0)
                        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uTexture"), 0)
                        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uFrame"), (frameSeq++).toFloat())
                        qb.position(0); GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, qb)
                        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                        EGLExt.eglPresentationTimeANDROID(dpy, win, img.timestamp); EGL14.eglSwapBuffers(dpy, win)
                        glTs += img.timestamp
                    }
                } finally {
                    GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
                    if (eglImg != 0L) EglHardwareBufferBridge.destroyEglImage(eglImg)
                    runCatching { hb?.close() }; img.close()
                }
            }, glHandler)

            manager.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { opened.complete(c); if (retiring.get()) c.close() }
                override fun onDisconnected(c: CameraDevice) { opened.completeExceptionally(IllegalStateException("Disconnected")); c.close() }
                override fun onError(c: CameraDevice, e: Int) { opened.completeExceptionally(IllegalStateException("Err $e")); c.close() }
                override fun onClosed(c: CameraDevice) { closed.complete(Unit) }
            }, camHandler)
            openSubmitted = true
            val cam = opened.get(6, TimeUnit.SECONDS)
            fun applyKeys(b: CaptureRequest.Builder) {
                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                if (cuvaReqKey != null) setVendorByteOrIntKey(b, cuvaReqKey, 1, cuvaHint)
            }
            val sessReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(reader.surface); applyKeys(this) }.build()
            cam.createCaptureSession(
                SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(reader.surface)), executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { if (retiring.get()) s.close() else configured.complete(s) }
                        override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("Configure failed")); s.close() }
                    },
                ).apply { sessionParameters = sessReq },
            )
            session = configured.get(6, TimeUnit.SECONDS)
            val repReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(reader.surface); applyKeys(this) }.build()
            session.setRepeatingRequest(repReq, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                    if (recordingOn.get()) res.get(CaptureResult.SENSOR_TIMESTAMP)?.let { synchronized(camTs) { camTs += it } }
                }
            }, camHandler)
            Thread.sleep(500L)
            recordingOn.set(true)
            Thread.sleep(3_000L)
            synchronized(acceptLock) { recordingOn.set(false) }
            runCatching { session.stopRepeating() }
            glHandler.post { runCatching { codec.signalEndOfInputStream() } }
            drainDone.await(6, TimeUnit.SECONDS)
            val camM = synchronized(camTs) { LabBreakthroughProbes.computeTimestampMetrics(camTs.toList(), 25_000_000L) }
            val glM = LabBreakthroughProbes.computeTimestampMetrics(glTs.toList(), 25_000_000L)
            json(
                "arm" to "13A_live_egl8_tpdf05_4k60", "ok" to (samples.get() > 60),
                "clip" to outFile.name, "clipBytes" to outFile.length(),
                "hwFmtHex" to hwFmt.get().takeIf { it != -1 }?.let { "0x${it.toString(16)}" },
                "camCb" to with(LabBreakthroughProbes) { camM.toJson() },
                "glCb" to with(LabBreakthroughProbes) { glM.toJson() },
                "encodedSamples" to samples.get(),
            )
        } finally {
            retiring.set(true); synchronized(acceptLock) { recordingOn.set(false) }
            val cc = closed.thenApply { Unit }; lease.releaseAfter(cc)
            if (openSubmitted) camHandler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } } else closed.complete(Unit)
            runCatching { cc.get(6, TimeUnit.SECONDS) }
            runCatching { reader.close() }
            if (codecStarted) runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            val glDone = CompletableFuture<Unit>()
            glHandler.post {
                runCatching {
                    if (prog != 0) GLES30.glDeleteProgram(prog)
                    if (texExt != 0) GLES30.glDeleteTextures(1, intArrayOf(texExt), 0)
                    if (dpy != EGL14.EGL_NO_DISPLAY) {
                        EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                        if (win != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, win)
                        if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
                        EGL14.eglTerminate(dpy)
                    }
                }
                glDone.complete(Unit)
            }
            runCatching { glDone.get(4, TimeUnit.SECONDS) }
            runCatching { codecSurface?.release() }
            camThread.quitSafely(); glThread.quitSafely()
            runCatching { camThread.join(4_000) }; runCatching { glThread.join(4_000) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun runProbe13_YuvSettledArm(
        context: Context,
        camId: String,
        armName: String,
        settleFrames: Int,
        configureRequest: (CaptureRequest.Builder, CameraCharacteristics) -> Unit,
    ): JSONObject = try {
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        lease.ready().get(8, TimeUnit.SECONDS)
        val manager = context.getSystemService(CameraManager::class.java)
        val caps = manager.getCameraCharacteristics(camId)
        val vaResKey = LabBreakthroughProbes.findVendorResultKey(caps, "vaFNumber")
        val apResKey = LabBreakthroughProbes.findVendorResultKey(caps, "apertureValue")
        val wbResKey = LabBreakthroughProbes.findVendorResultKey(caps, "sensorWbValue")
        val thread = HandlerThread("p13-$armName").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 3)

        val statsFuture = CompletableFuture<JSONObject>()
        val resultRef = AtomicReference<JSONObject?>(null)
        val frameIdx = AtomicInteger(0)
        reader.setOnImageAvailableListener({ r ->
            val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                if (frameIdx.incrementAndGet() != settleFrames || statsFuture.isDone) return@setOnImageAvailableListener
                statsFuture.complete(computeYuvQualityStats(img))
            } catch (t: Throwable) {
                statsFuture.completeExceptionally(t)
            } finally { img.close() }
        }, handler)

        val opened = CompletableFuture<CameraDevice>(); val closed = CompletableFuture<Unit>(); val configured = CompletableFuture<CameraCaptureSession>()
        val retiring = AtomicBoolean(); var session: CameraCaptureSession? = null; var openSubmitted = false
        try {
            manager.openCamera(camId, object : CameraDevice.StateCallback() {
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
            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(reader.surface)
                configureRequest(this, caps)
            }.build()
            session.setRepeatingRequest(req, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                    if (resultRef.get() == null && frameIdx.get() >= (settleFrames - 2)) {
                        val gains = res.get(CaptureResult.COLOR_CORRECTION_GAINS)
                        val xform = res.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
                        resultRef.compareAndSet(null, json(
                            "expNs" to res.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                            "iso" to res.get(CaptureResult.SENSOR_SENSITIVITY),
                            "aperture" to res.get(CaptureResult.LENS_APERTURE),
                            "vaFNumber" to vaResKey?.let { LabBreakthroughProbes.readResultKeyValue(res, it) },
                            "apertureValue" to apResKey?.let { LabBreakthroughProbes.readResultKeyValue(res, it) },
                            "sensorWbValue" to wbResKey?.let { LabBreakthroughProbes.readResultKeyValue(res, it) },
                            "awbMode" to res.get(CaptureResult.CONTROL_AWB_MODE),
                            "ccMode" to res.get(CaptureResult.COLOR_CORRECTION_MODE),
                            "ccGains" to gains?.let { floatArrayOf(it.red, it.greenEven, it.greenOdd, it.blue).joinToString(",", "[", "]") },
                            "ccTransform" to xform?.let { colorTransformToFloatArray(it).joinToString(",", "[", "]") },
                        ))
                    }
                }
            }, handler)
            val yuv = statsFuture.get(6, TimeUnit.SECONDS)
            runCatching { session.stopRepeating() }
            json("arm" to armName, "ok" to true, "result" to resultRef.get(), "yuv" to yuv)
        } finally {
            retiring.set(true)
            val cc = closed.thenApply { Unit }; lease.releaseAfter(cc)
            if (openSubmitted) handler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } } else closed.complete(Unit)
            runCatching { cc.get(6, TimeUnit.SECONDS) }
            runCatching { reader.close() }
            thread.quitSafely(); runCatching { thread.join(4_000) }
        }
    } catch (t: Throwable) {
        json("arm" to armName, "ok" to false, "errorClass" to t.javaClass.name, "error" to (t.message ?: t.toString()).take(280))
    }

    private enum class Raw13Mode { STILL_CAPTURE, MANUAL_TEMPLATE, INCONSISTENT_0, INCONSISTENT_1, RAW10_FORMAT }

    @SuppressLint("MissingPermission")
    private fun runProbe13D_RawCompressionArm(
        context: Context,
        outDir: File,
        armName: String,
        mode: Raw13Mode,
    ): JSONObject {
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        lease.ready().get(8, TimeUnit.SECONDS)
        val manager = context.getSystemService(CameraManager::class.java)
        val caps = manager.getCameraCharacteristics("0")
        val w = 4000; val h = 3000
        val imgFmt = if (mode == Raw13Mode.RAW10_FORMAT) ImageFormat.RAW10 else ImageFormat.RAW_SENSOR
        val thread = HandlerThread("p13-raw-$armName").apply { start() }
        val handler = Handler(thread.looper)
        val rawReader = ImageReader.newInstance(w, h, imgFmt, 4)
        val prevReader = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 3)
        prevReader.setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage()?.close() } }, handler)

        val statsFuture = CompletableFuture<JSONObject>()
        val resultFuture = CompletableFuture<TotalCaptureResult>()
        val capturedImage = AtomicReference<android.media.Image?>(null)
        val count = AtomicInteger(0)
        rawReader.setOnImageAvailableListener({ r ->
            val img = runCatching { r.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
            val idx = count.incrementAndGet()
            val targetIdx = if (mode == Raw13Mode.STILL_CAPTURE) 1 else 3
            if (idx != targetIdx || statsFuture.isDone) {
                img.close(); return@setOnImageAvailableListener
            }
            if (mode == Raw13Mode.STILL_CAPTURE) {
                capturedImage.set(img) // closed in caller after DngCreator check
                statsFuture.complete(analyzeRawPlaneStats(img, w, h, imgFmt))
            } else {
                try {
                    val st = analyzeRawPlaneStats(img, w, h, imgFmt)
                    if (!st.optBoolean("hfbcDetected", true) && st.optDouble("secondHalfNonZeroFrac", 0.0) > 0.8) {
                        val plane = img.planes[0].buffer.duplicate().apply { position(0) }
                        val outBytes = ByteArray(plane.remaining())
                        plane.get(outBytes)
                        File(outDir, "p13_uncompressed_raw16.raw").writeBytes(outBytes)
                        st.put("dumpedUncompressedFile", "p13_uncompressed_raw16.raw")
                    }
                    statsFuture.complete(st)
                } finally { img.close() }
            }
        }, handler)

        val opened = CompletableFuture<CameraDevice>(); val closed = CompletableFuture<Unit>(); val configured = CompletableFuture<CameraCaptureSession>()
        val retiring = AtomicBoolean(); var session: CameraCaptureSession? = null; var openSubmitted = false
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
            cam.createCaptureSession(listOf(rawReader.surface, prevReader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { if (retiring.get()) s.close() else configured.complete(s) }
                override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("Configure failed")); s.close() }
            }, handler)
            session = configured.get(6, TimeUnit.SECONDS)

            if (mode == Raw13Mode.STILL_CAPTURE) {
                val prevReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(prevReader.surface) }.build()
                session.setRepeatingRequest(prevReq, null, handler)
                Thread.sleep(350L)
                val stillReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(prevReader.surface); addTarget(rawReader.surface)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, 20_000_000L)
                    set(CaptureRequest.SENSOR_SENSITIVITY, 400)
                }.build()
                session.capture(stillReq, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                        resultFuture.complete(res)
                    }
                }, handler)
            } else {
                val tpl = if (mode == Raw13Mode.MANUAL_TEMPLATE) CameraDevice.TEMPLATE_MANUAL else CameraDevice.TEMPLATE_RECORD
                val req = cam.createCaptureRequest(tpl).apply {
                    addTarget(prevReader.surface); addTarget(rawReader.surface)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                    if (mode == Raw13Mode.INCONSISTENT_0) setByNameByte(this, "com.huawei.capture.metadata.hwInconsisitentRawStream", byteArrayOf(0))
                    if (mode == Raw13Mode.INCONSISTENT_1) setByNameByte(this, "com.huawei.capture.metadata.hwInconsisitentRawStream", byteArrayOf(1))
                }.build()
                session.setRepeatingRequest(req, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                        if (count.get() >= 3 && !resultFuture.isDone) resultFuture.complete(res)
                    }
                }, handler)
            }

            val st = statsFuture.get(6, TimeUnit.SECONDS)
            if (mode == Raw13Mode.STILL_CAPTURE) {
                val img = capturedImage.getAndSet(null)
                if (img != null) {
                    try {
                        val res = resultFuture.get(3, TimeUnit.SECONDS)
                        val dngRes = runCatching {
                            val baos = ByteArrayOutputStream()
                            DngCreator(caps, res).use { it.writeImage(baos, img) }
                            baos.size()
                        }
                        st.put("dngCreatorOk", dngRes.isSuccess)
                        st.put("dngBytes", dngRes.getOrNull())
                        dngRes.exceptionOrNull()?.let { st.put("dngError", it.message ?: it.javaClass.name) }
                    } finally { img.close() }
                }
            }
            runCatching { session.stopRepeating() }
            st.put("arm", armName).put("ok", true)
        } finally {
            retiring.set(true)
            capturedImage.getAndSet(null)?.close()
            val cc = closed.thenApply { Unit }; lease.releaseAfter(cc)
            if (openSubmitted) handler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } } else closed.complete(Unit)
            runCatching { cc.get(6, TimeUnit.SECONDS) }
            runCatching { rawReader.close() }; runCatching { prevReader.close() }
            thread.quitSafely(); runCatching { thread.join(4_000) }
        }
    }

    private fun analyzeRawPlaneStats(img: android.media.Image, w: Int, h: Int, fmt: Int): JSONObject {
        val plane = img.planes[0]
        val buf = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        buf.position(0)
        val totalBytes = buf.remaining()
        val first16 = buildString {
            for (i in 0 until minOf(16, totalBytes)) append(String.format("%02x", buf.get(i).toInt() and 0xFF)).append(' ')
        }.trim()
        var faHits = 0
        for (i in 0 until minOf(totalBytes - 4, 262_144)) {
            if ((buf.get(i).toInt() and 0xFF) == 0xFA && (buf.get(i + 1).toInt() and 0xFF) == 0x01 &&
                (buf.get(i + 2).toInt() and 0xFF) == 0x60 && (buf.get(i + 3).toInt() and 0xFF) == 0x30
            ) faHits++
        }
        var nzSecondHalf = 0; var secTotal = 0
        val half = totalBytes / 2
        var i = half
        while (i < totalBytes) {
            if (buf.get(i).toInt() != 0) nzSecondHalf++
            secTotal++; i += 16
        }
        var u16Min = 65535; var u16Max = 0; var above4095 = 0; var nU16 = 0
        val histSamples = IntArray(4096)
        if (fmt == ImageFormat.RAW_SENSOR) {
            for (yy in 0 until h step 8) {
                val rOff = yy * plane.rowStride
                for (xx in 0 until w step 8) {
                    if (rOff + xx * 2 + 1 < totalBytes) {
                        val v = buf.getShort(rOff + xx * 2).toInt() and 0xFFFF
                        if (v < u16Min) u16Min = v
                        if (v > u16Max) u16Max = v
                        if (v > 4095) above4095++ else histSamples[v]++
                        nU16++
                    }
                }
            }
        }
        var acc = 0; var p50 = 0
        val target = nU16 / 2
        for (k in 0 until 4096) {
            acc += histSamples[k]
            if (acc >= target) { p50 = k; break }
        }
        return json(
            "format" to fmt,
            "rowStride" to plane.rowStride,
            "totalBytes" to totalBytes,
            "first16Hex" to first16,
            "fa016030HitsInFirst256K" to faHits,
            "hfbcDetected" to (faHits > 0),
            "secondHalfNonZeroFrac" to nzSecondHalf.toDouble() / secTotal.coerceAtLeast(1),
            "u16Min" to u16Min,
            "u16P50" to p50,
            "u16Max" to u16Max,
            "above4095Frac" to above4095.toDouble() / nU16.coerceAtLeast(1),
        )
    }
}
