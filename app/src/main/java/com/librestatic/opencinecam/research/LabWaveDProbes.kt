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
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
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
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresApi
import com.librestatic.opencinecam.camera.Camera2Analysis
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.Camera2PreviewEngine
import com.librestatic.opencinecam.camera.CaptureOwnerAdmission
import com.librestatic.opencinecam.camera.HevcSpsInspector
import com.librestatic.opencinecam.camera.HuaweiVendorControls
import com.librestatic.opencinecam.camera.OpenCineLogGpuPipeline
import com.librestatic.opencinecam.camera.OpenCineLogRecordingEvidence
import com.librestatic.opencinecam.camera.OpenCineLogSourcePath
import com.librestatic.opencinecam.camera.RecordingGeometryCalculator
import com.librestatic.opencinecam.camera.RecordingGeometryMode
import com.librestatic.opencinecam.camera.ResearchEncoderOverride
import com.librestatic.opencinecam.media.video.EglHardwareBufferBridge
import com.librestatic.opencinecam.research.LabBreakthroughProbes.toJson
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
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wave D Milestone 1 Empirical Discovery Probes (Probes 17A-17D):
 * - 17A: Native H_LOG / CameraKit check, timestamp clock audit (CLOCK_BOOTTIME vs sensor),
 *        vendor tag sweeps (colorMode, sharpness, contrast, saturation, denoise, lossless, aperture f/1.6..f/4.0, CUVA list).
 * - 17B: True >8-bit GPU / Vulkan / AHB / ANativeWindow / ImageWriter / 0x7F000011 ByteBuffer / RGB10_A2 root-cause probes.
 * - 17C: 4K60 bottleneck isolation across Camera HAL AE/FPS-escape vs GPU scopes vs Direct Surface vs bitrate/All-Intra.
 * - 17D: Production matrix of Direct 10-bit 0x324 -> OMX.hisi Main10 across lenses/rates + hidden RAW_SENSOR (32) verification.
 */
internal object LabWaveDProbes {
    private const val HUAWEI_CUVA_VIDEO_MODE_KEY = "com.huawei.capture.metadata.cuvaStateVideoMode"
    private const val COLOR_FORMAT_HISI_YUV420SP_10BIT = 0x7F000011
    private const val FMT_CUVA_10BIT = 0x324
    private const val FMT_P010 = 0x36
    private const val FMT_YUV420 = 0x23
    private const val FMT_RGBA1010102 = 0x2B
    private const val EGL_RECORDABLE_ANDROID = 0x3142
    private const val EGL_GL_COLORSPACE_KHR = 0x309D
    private const val EGL_GL_COLORSPACE_BT2020_HLG_EXT = 0x3540
    private const val EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3340
    private const val DS_BT2020_HLG_FULL = (6 shl 16) or (8 shl 22) or (1 shl 27)
    private const val DS_BT2020_HLG_LIMITED = (6 shl 16) or (8 shl 22) or (2 shl 27)

    @RequiresApi(Build.VERSION_CODES.Q)
    private object Api29Writer {
        fun open(surface: Surface, maxImages: Int, format: Int): ImageWriter =
            ImageWriter.newInstance(surface, maxImages, format)
    }

    private fun setIntVendorKey(builder: CaptureRequest.Builder, keyName: String, value: Int): String {
        val byteArr = runCatching {
            builder.set(CaptureRequest.Key(keyName, ByteArray::class.java), byteArrayOf(value.toByte()))
            "ByteArray"
        }.getOrNull()
        if (byteArr != null) return byteArr
        val intArr = runCatching {
            builder.set(CaptureRequest.Key(keyName, IntArray::class.java), intArrayOf(value))
            "IntArray"
        }.getOrNull()
        if (intArr != null) return intArr
        val bVal = runCatching {
            builder.set(CaptureRequest.Key(keyName, Byte::class.javaObjectType), value.toByte())
            "Byte"
        }.getOrNull()
        if (bVal != null) return bVal
        return runCatching {
            builder.set(CaptureRequest.Key(keyName, Int::class.javaObjectType), value)
            "Int"
        }.getOrElse { "fail:${it.message?.take(80)}" }
    }

    private fun setFloatVendorKey(builder: CaptureRequest.Builder, keyName: String, value: Float): String {
        val fArr = runCatching {
            builder.set(CaptureRequest.Key(keyName, FloatArray::class.java), floatArrayOf(value))
            "FloatArray"
        }.getOrNull()
        if (fArr != null) return fArr
        return runCatching {
            builder.set(CaptureRequest.Key(keyName, Float::class.javaObjectType), value)
            "Float"
        }.getOrElse { "fail:${it.message?.take(80)}" }
    }

    private fun computeYuvDetailedStats(img: android.media.Image): JSONObject {
        val yP = img.planes[0]
        val uP = img.planes[1]
        val vP = img.planes[2]
        val w = img.width
        val h = img.height
        val yB = yP.buffer
        val uB = uP.buffer
        val vB = vP.buffer
        val x0 = w / 4
        val x1 = (3 * w) / 4
        val y0 = h / 4
        val y1 = (3 * h) / 4

        val ySamples = IntArray(((y1 - y0 + 1) / 2) * ((x1 - x0 + 1) / 2))
        var sIdx = 0
        var sumY = 0.0
        var sumY2 = 0.0
        var lapSum2 = 0.0
        var nLap = 0
        for (yy in y0 until y1 step 2) {
            val rOff = yy * yP.rowStride
            val uOff = (yy - 1).coerceAtLeast(0) * yP.rowStride
            val dOff = (yy + 1).coerceAtMost(h - 1) * yP.rowStride
            for (xx in x0 until x1 step 2) {
                val c = yB.get(rOff + xx * yP.pixelStride).toInt() and 0xFF
                if (sIdx < ySamples.size) ySamples[sIdx++] = c
                sumY += c
                sumY2 += c * c
                val l = yB.get(rOff + (xx - 1).coerceAtLeast(0) * yP.pixelStride).toInt() and 0xFF
                val r = yB.get(rOff + (xx + 1).coerceAtMost(w - 1) * yP.pixelStride).toInt() and 0xFF
                val u = yB.get(uOff + xx * yP.pixelStride).toInt() and 0xFF
                val d = yB.get(dOff + xx * yP.pixelStride).toInt() and 0xFF
                val lap = 4 * c - l - r - u - d
                lapSum2 += lap.toDouble() * lap.toDouble()
                nLap++
            }
        }
        val sortedY = ySamples.copyOf(sIdx).also { it.sort() }
        val p01 = if (sIdx > 0) sortedY[(sIdx - 1) / 100] else 0
        val p50 = if (sIdx > 0) sortedY[sIdx / 2] else 0
        val p99 = if (sIdx > 0) sortedY[((sIdx - 1) * 99) / 100] else 0
        val meanY = sumY / sIdx.coerceAtLeast(1)
        val stdY = sqrt((sumY2 / sIdx.coerceAtLeast(1) - meanY * meanY).coerceAtLeast(0.0))

        // Local 8x8 flat-patch noise (minimum non-saturated block std)
        var minBlockStd = Double.MAX_VALUE
        for (by in y0 until y1 - 8 step 16) {
            for (bx in x0 until x1 - 8 step 16) {
                var bSum = 0.0
                var bSum2 = 0.0
                for (dy in 0 until 8) {
                    val row = (by + dy) * yP.rowStride
                    for (dx in 0 until 8) {
                        val v = yB.get(row + (bx + dx) * yP.pixelStride).toInt() and 0xFF
                        bSum += v
                        bSum2 += v * v
                    }
                }
                val bm = bSum / 64.0
                if (bm in 8.0..245.0) {
                    val bStd = sqrt((bSum2 / 64.0 - bm * bm).coerceAtLeast(0.0))
                    if (bStd < minBlockStd) minBlockStd = bStd
                }
            }
        }

        var sumU = 0.0
        var sumV = 0.0
        var sumChromaMag = 0.0
        var nUv = 0
        for (yy in y0 / 2 until y1 / 2 step 2) {
            val uR = yy * uP.rowStride
            val vR = yy * vP.rowStride
            for (xx in x0 / 2 until x1 / 2 step 2) {
                val u = uB.get(uR + xx * uP.pixelStride).toInt() and 0xFF
                val v = vB.get(vR + xx * vP.pixelStride).toInt() and 0xFF
                sumU += u
                sumV += v
                sumChromaMag += (abs(u - 128) + abs(v - 128)).toDouble()
                nUv++
            }
        }
        return json(
            "yMean" to meanY,
            "yStd" to stdY,
            "yP01" to p01,
            "yP50" to p50,
            "yP99" to p99,
            "uMean" to (sumU / nUv.coerceAtLeast(1)),
            "vMean" to (sumV / nUv.coerceAtLeast(1)),
            "chromaMagMean" to (sumChromaMag / nUv.coerceAtLeast(1)),
            "laplacianEnergy" to sqrt(lapSum2 / nLap.coerceAtLeast(1)),
            "flatPatchNoiseStd" to (if (minBlockStd < Double.MAX_VALUE) minBlockStd else null),
        )
    }

    // =========================================================================
    // Probe 17A: Native H_LOG / CameraKit Check, Clock Audit, & EMUI Vendor Sweeps
    // =========================================================================
    @SuppressLint("MissingPermission")
    fun runProbe17A_NativeLogAndVendorTags(context: Context, outDir: File): JSONObject =
        LabWaveCProbes.withWatchdogAndLogs(context, outDir) { recorder, watchdog, sampleNow ->
            LabWaveCProbes.waitNaturalThermalCool(context, outDir)
            val manager = context.getSystemService(CameraManager::class.java)
            val caps = manager.getCameraCharacteristics("0")
            val reqKeyNames = runCatching { caps.availableCaptureRequestKeys.map { it.name } }.getOrDefault(emptyList())
            val resKeyNames = runCatching { caps.availableCaptureResultKeys.map { it.name } }.getOrDefault(emptyList())
            val interestingReqKeys = reqKeyNames.filter {
                it.contains("huawei", ignoreCase = true) ||
                    it.contains("edge", ignoreCase = true) ||
                    it.contains("noise", ignoreCase = true) ||
                    it.contains("aperture", ignoreCase = true)
            }
            val interestingResKeys = resKeyNames.filter {
                it.contains("huawei", ignoreCase = true)
            }

            // CameraKit Java API availability check (read-only reflection)
            val cameraKitProbe = runCatching {
                val ckCtx = runCatching { context.createPackageContext("com.huawei.camerakit.impl", 0) }.getOrNull()
                val cls = runCatching { Class.forName("com.huawei.camera.camerakit.CameraKit") }.getOrNull()
                    ?: runCatching { ckCtx?.classLoader?.loadClass("com.huawei.camerakit.api.ModeManagerInterface") }.getOrNull()
                json("packagePresent" to (ckCtx != null), "classLoaded" to (cls != null), "className" to cls?.name)
            }.getOrElse { json("error" to (it.message ?: it.toString())) }

            // Compute sensible manual locked exposure for current room lighting
            val lockedAe = LabBreakthroughProbes.warmupAndLockExposure(context) ?: (16_666_667L to 200)
            val fixedExpNs = lockedAe.first.coerceIn(1_000_000L, 66_666_667L)
            val fixedIso = lockedAe.second.coerceIn(50, 3200)
            Thread.sleep(250L)

            val clockDiag = AtomicReference<JSONObject?>(null)
            val vendorSweeps = JSONArray()

            // Persistent Camera "0" session for all single-parameter vendor tag sweeps under locked exposure + 5600K
            run {
                val lease = CaptureOwnerAdmission.processGlobal.acquire()
                lease.ready().get(8, TimeUnit.SECONDS)
                val camThread = HandlerThread("p17a-sweep").apply { start() }
                val camHandler = Handler(camThread.looper)
                val exec = Executor { r -> camHandler.post(r) }
                val yuvReader = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 4)
                val privReader = LabBreakthroughProbes.createUsageImageReader(
                    3840, 2160, ImageFormat.PRIVATE, 4,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_VIDEO_ENCODE,
                )
                val privFmt = AtomicInteger(-1)
                privReader.setOnImageAvailableListener({ r ->
                    runCatching {
                        r.acquireLatestImage()?.use { img ->
                            if (privFmt.get() == -1) {
                                img.hardwareBuffer?.use { hb ->
                                    privFmt.compareAndSet(-1, EglHardwareBufferBridge.describeAhb(hb)?.first ?: hb.format)
                                }
                            }
                            if (clockDiag.get() == null) {
                                val bootNs = SystemClock.elapsedRealtimeNanos()
                                val monoNs = System.nanoTime()
                                val imgTs = img.timestamp
                                clockDiag.compareAndSet(null, json(
                                    "timestampSourceChar" to caps.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE),
                                    "elapsedRealtimeNanos" to bootNs,
                                    "systemNanoTime" to monoNs,
                                    "imageTimestampNs" to imgTs,
                                    "bootMinusImageSec" to (bootNs - imgTs) / 1e9,
                                    "monoMinusImageSec" to (monoNs - imgTs) / 1e9,
                                ))
                            }
                        }
                    }
                }, camHandler)

                val settleCounter = AtomicInteger(0)
                val yuvFut = AtomicReference<CompletableFuture<JSONObject>?>(null)
                val lastMetaJson = AtomicReference<JSONObject?>(null)
                yuvReader.setOnImageAvailableListener({ r ->
                    val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                    try {
                        val fut = yuvFut.get() ?: return@setOnImageAvailableListener
                        if (fut.isDone) return@setOnImageAvailableListener
                        if (settleCounter.incrementAndGet() >= 12) {
                            fut.complete(computeYuvDetailedStats(img))
                        }
                    } catch (t: Throwable) {
                        yuvFut.get()?.completeExceptionally(t)
                    } finally {
                        img.close()
                    }
                }, camHandler)

                val opened = CompletableFuture<CameraDevice>()
                val closed = CompletableFuture<Unit>()
                val configured = CompletableFuture<CameraCaptureSession>()
                val retiring = AtomicBoolean(false)
                var session: CameraCaptureSession? = null
                var openSubmitted = false
                try {
                    watchdog.noteAppOpening("0")
                    val ot = watchdog.scheduleOpenTimeout("0") { ev -> opened.completeExceptionally(IllegalStateException(ev.detail)) }
                    manager.openCamera("0", object : CameraDevice.StateCallback() {
                        override fun onOpened(c: CameraDevice) { watchdog.cancelOpenTimeout(ot); opened.complete(c); if (retiring.get()) c.close() }
                        override fun onDisconnected(c: CameraDevice) { watchdog.cancelOpenTimeout(ot); opened.completeExceptionally(IllegalStateException("disc")); c.close() }
                        override fun onError(c: CameraDevice, e: Int) { watchdog.cancelOpenTimeout(ot); watchdog.noteCameraError("0", e); opened.completeExceptionally(IllegalStateException("err $e")); c.close() }
                        override fun onClosed(c: CameraDevice) { closed.complete(Unit) }
                    }, camHandler)
                    openSubmitted = true
                    val cam = opened.get(6, TimeUnit.SECONDS)

                    fun baseBuilder(): CaptureRequest.Builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(yuvReader.surface)
                        addTarget(privReader.surface)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, fixedExpNs)
                        set(CaptureRequest.SENSOR_SENSITIVITY, fixedIso)
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        HuaweiVendorControls.applyKelvin(this, caps, "0", 5600)
                    }

                    val sessReq = baseBuilder().build()
                    cam.createCaptureSession(SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        listOf(OutputConfiguration(yuvReader.surface), OutputConfiguration(privReader.surface)),
                        exec,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) { if (retiring.get()) s.close() else configured.complete(s) }
                            override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("cfg fail")); s.close() }
                        },
                    ).apply { sessionParameters = sessReq })
                    session = configured.get(6, TimeUnit.SECONDS)

                    val cb = object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                            if (settleCounter.get() >= 10) {
                                val bootNs = SystemClock.elapsedRealtimeNanos()
                                val sensNs = res.get(CaptureResult.SENSOR_TIMESTAMP)
                                if (sensNs != null) {
                                    clockDiag.get()?.let {
                                        if (!it.has("sensorTimestampNs")) {
                                            it.put("sensorTimestampNs", sensNs)
                                            it.put("bootMinusSensorSec", (bootNs - sensNs) / 1e9)
                                        }
                                    }
                                }
                                lastMetaJson.set(json(
                                    "expNs" to res.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                                    "iso" to res.get(CaptureResult.SENSOR_SENSITIVITY),
                                    "lensAperture" to res.get(CaptureResult.LENS_APERTURE),
                                    "edgeMode" to res.get(CaptureResult.EDGE_MODE),
                                    "nrMode" to res.get(CaptureResult.NOISE_REDUCTION_MODE),
                                    "vaFNumber" to LabBreakthroughProbes.readResultKeyValue(res, LabBreakthroughProbes.findVendorResultKey(caps, "vaFNumber")),
                                    "colorModeRes" to LabBreakthroughProbes.readResultKeyValue(res, LabBreakthroughProbes.findVendorResultKey(caps, "colorMode")),
                                ))
                            }
                        }
                    }

                    fun runArm(armName: String, applyFn: (CaptureRequest.Builder) -> String): JSONObject {
                        return runCatching {
                            val fut = CompletableFuture<JSONObject>()
                            lastMetaJson.set(null)
                            settleCounter.set(0)
                            yuvFut.set(fut)
                            val b = baseBuilder()
                            val appliedInfo = applyFn(b)
                            session.setRepeatingRequest(b.build(), cb, camHandler)
                            val stats = fut.get(6, TimeUnit.SECONDS)
                            json("arm" to armName, "ok" to true, "applied" to appliedInfo, "stats" to stats, "meta" to lastMetaJson.get(),
                                "privFmtHex" to privFmt.get().takeIf { it != -1 }?.let { "0x${it.toString(16)}" })
                        }.getOrElse { json("arm" to armName, "ok" to false, "error" to (it.message ?: it.toString())) }
                    }

                    vendorSweeps.put(runArm("baseline_locked") { "default" })
                    for (cm in listOf(0, 1, 2, 100)) {
                        vendorSweeps.put(runArm("colorMode_$cm") { b -> setIntVendorKey(b, "com.huawei.capture.metadata.colorMode", cm) })
                    }
                    for (scm in listOf(0, 1, 2)) {
                        vendorSweeps.put(runArm("hwSensorColorMode_$scm") { b -> setIntVendorKey(b, "com.huawei.capture.metadata.hwSensorColorMode", scm) })
                    }
                    for ((eName, eVal) in listOf("OFF" to CaptureRequest.EDGE_MODE_OFF, "FAST" to CaptureRequest.EDGE_MODE_FAST, "HQ" to CaptureRequest.EDGE_MODE_HIGH_QUALITY)) {
                        vendorSweeps.put(runArm("edgeMode_$eName") { b -> b.set(CaptureRequest.EDGE_MODE, eVal); eName })
                    }
                    for ((nName, nVal) in listOf("OFF" to CaptureRequest.NOISE_REDUCTION_MODE_OFF, "FAST" to CaptureRequest.NOISE_REDUCTION_MODE_FAST, "HQ" to CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, "MINIMAL" to CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL)) {
                        vendorSweeps.put(runArm("nrMode_$nName") { b -> b.set(CaptureRequest.NOISE_REDUCTION_MODE, nVal); nName })
                    }
                    for (cv in listOf(0, 2, 4)) {
                        vendorSweeps.put(runArm("contrast_$cv") { b -> setIntVendorKey(b, "com.huawei.capture.metadata.constrastValue", cv) })
                    }
                    for (sv in listOf(0, 2, 4)) {
                        vendorSweeps.put(runArm("saturation_$sv") { b -> setIntVendorKey(b, "com.huawei.capture.metadata.saturationValue", sv) })
                    }
                    for (bv in listOf(0, 2, 4)) {
                        vendorSweeps.put(runArm("brightness_$bv") { b -> setIntVendorKey(b, "com.huawei.capture.metadata.brightnessValue", bv) })
                    }
                    for (sh in listOf(0, 2, 5)) {
                        vendorSweeps.put(runArm("sharpness_$sh") { b -> setIntVendorKey(b, "com.huawei.capture.metadata.sharpness", sh) })
                    }
                    for (flag in listOf(0, 1)) {
                        vendorSweeps.put(runArm("aiDenoiseEscape_$flag") { b -> setIntVendorKey(b, "com.huawei.capture.metadata.hwVideoAiDenoiseEscape", flag) })
                    }
                    for (flag in listOf(0, 1)) {
                        vendorSweeps.put(runArm("frameLossless_$flag") { b -> setIntVendorKey(b, "com.huawei.capture.metadata.hw-frame-lossless", flag) })
                    }
                    for (fNum in listOf(1.6f, 2.0f, 2.8f, 4.0f)) {
                        vendorSweeps.put(runArm("vaManual_$fNum") { b ->
                            setFloatVendorKey(b, "com.huawei.capture.metadata.vaManualFNum", fNum)
                        })
                    }
                    for ((fNum, apInt) in listOf(1.6f to 16, 2.0f to 20, 2.8f to 28, 4.0f to 40)) {
                        vendorSweeps.put(runArm("vaCombo_$fNum") { b ->
                            b.set(CaptureRequest.LENS_APERTURE, fNum)
                            val r1 = setFloatVendorKey(b, "com.huawei.capture.metadata.vaManualFNum", fNum)
                            val r2 = setFloatVendorKey(b, "com.huawei.capture.metadata.vaInitFNum", fNum)
                            val r3 = setIntVendorKey(b, "com.huawei.capture.metadata.apertureValue", apInt)
                            val r4 = setIntVendorKey(b, "com.huawei.capture.metadata.apertureMode", 1)
                            "manual=$r1,init=$r2,ap=$r3,mode=$r4"
                        })
                    }
                    runCatching { session.stopRepeating() }
                } finally {
                    retiring.set(true)
                    val cc = closed.thenApply { Unit }
                    lease.releaseAfter(cc)
                    if (openSubmitted) camHandler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } }
                    else closed.complete(Unit)
                    runCatching { cc.get(6, TimeUnit.SECONDS) }
                    runCatching { yuvReader.close() }
                    runCatching { privReader.close() }
                    camThread.quitSafely()
                    runCatching { camThread.join(2_000L) }
                }
            }

            // Session-key CUVA sweep (0..12 from cuvaHdrSupportedList)
            val cuvaModeSweep = JSONArray()
            val cuvaReqKey = LabBreakthroughProbes.findVendorRequestKey(caps, "cuvaStateVideoMode")
            val cuvaHint = LabBreakthroughProbes.vendorKeyTypeHint(cuvaReqKey)
            for (cuvaVal in listOf(0, 1, 2, 3, 5, 6, 8, 9, 10, 11, 12)) {
                Thread.sleep(200L)
                cuvaModeSweep.put(runCatching {
                    val lease = CaptureOwnerAdmission.processGlobal.acquire()
                    lease.ready().get(8, TimeUnit.SECONDS)
                    val camThread = HandlerThread("p17a-cuva-$cuvaVal").apply { start() }
                    val camHandler = Handler(camThread.looper)
                    val privReader = LabBreakthroughProbes.createUsageImageReader(
                        1920, 1080, ImageFormat.PRIVATE, 3,
                        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_VIDEO_ENCODE,
                    )
                    val yuvReader = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 3)
                    val hwFmt = AtomicInteger(-1)
                    val dataSpaceVal = AtomicInteger(-1)
                    val privFrames = AtomicInteger(0)
                    privReader.setOnImageAvailableListener({ r ->
                        runCatching {
                            r.acquireLatestImage()?.use { img ->
                                privFrames.incrementAndGet()
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && dataSpaceVal.get() == -1) {
                                    dataSpaceVal.set(img.dataSpace)
                                }
                                if (hwFmt.get() == -1) {
                                    img.hardwareBuffer?.use { hb ->
                                        hwFmt.compareAndSet(-1, EglHardwareBufferBridge.describeAhb(hb)?.first ?: hb.format)
                                    }
                                }
                            }
                        }
                    }, camHandler)
                    val settle = AtomicInteger(0)
                    val yuvFut = CompletableFuture<JSONObject>()
                    yuvReader.setOnImageAvailableListener({ r ->
                        val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                        try {
                            if (!yuvFut.isDone && settle.incrementAndGet() >= 10) {
                                yuvFut.complete(computeYuvDetailedStats(img))
                            }
                        } catch (t: Throwable) {
                            yuvFut.completeExceptionally(t)
                        } finally {
                            img.close()
                        }
                    }, camHandler)

                    val opened = CompletableFuture<CameraDevice>()
                    val closed = CompletableFuture<Unit>()
                    val configured = CompletableFuture<CameraCaptureSession>()
                    val retiring = AtomicBoolean(false)
                    var session: CameraCaptureSession? = null
                    var openSubmitted = false
                    try {
                        manager.openCamera("0", object : CameraDevice.StateCallback() {
                            override fun onOpened(c: CameraDevice) { opened.complete(c); if (retiring.get()) c.close() }
                            override fun onDisconnected(c: CameraDevice) { opened.completeExceptionally(IllegalStateException("disc")); c.close() }
                            override fun onError(c: CameraDevice, e: Int) { opened.completeExceptionally(IllegalStateException("err $e")); c.close() }
                            override fun onClosed(c: CameraDevice) { closed.complete(Unit) }
                        }, camHandler)
                        openSubmitted = true
                        val cam = opened.get(6, TimeUnit.SECONDS)
                        fun buildReq(): CaptureRequest = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(privReader.surface)
                            addTarget(yuvReader.surface)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                            set(CaptureRequest.SENSOR_EXPOSURE_TIME, fixedExpNs)
                            set(CaptureRequest.SENSOR_SENSITIVITY, fixedIso)
                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                            HuaweiVendorControls.applyKelvin(this, caps, "0", 5600)
                            if (cuvaReqKey != null) setVendorByteOrIntKey(this, cuvaReqKey, cuvaVal, cuvaHint)
                            else setIntVendorKey(this, HUAWEI_CUVA_VIDEO_MODE_KEY, cuvaVal)
                        }.build()
                        val req = buildReq()
                        cam.createCaptureSession(SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR,
                            listOf(OutputConfiguration(privReader.surface), OutputConfiguration(yuvReader.surface)),
                            Executor { r -> camHandler.post(r) },
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) { if (retiring.get()) s.close() else configured.complete(s) }
                                override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("cfg fail")); s.close() }
                            },
                        ).apply { sessionParameters = req })
                        session = configured.get(6, TimeUnit.SECONDS)
                        session.setRepeatingRequest(req, null, camHandler)
                        val stats = yuvFut.get(5, TimeUnit.SECONDS)
                        runCatching { session.stopRepeating() }
                        json(
                            "cuvaMode" to cuvaVal,
                            "ok" to true,
                            "privFrames" to privFrames.get(),
                            "hwFmtHex" to hwFmt.get().takeIf { it != -1 }?.let { "0x${it.toString(16)}" },
                            "stats" to stats,
                        )
                    } finally {
                        retiring.set(true)
                        val cc = closed.thenApply { Unit }
                        lease.releaseAfter(cc)
                        if (openSubmitted) camHandler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } }
                        else closed.complete(Unit)
                        runCatching { cc.get(6, TimeUnit.SECONDS) }
                        runCatching { privReader.close() }
                        runCatching { yuvReader.close() }
                        camThread.quitSafely()
                        runCatching { camThread.join(2_000L) }
                    }
                }.getOrElse { json("cuvaMode" to cuvaVal, "ok" to false, "error" to (it.message ?: it.toString())) })
            }

            val report = json(
                "probe" to "17A_native_log_and_vendor_tags",
                "lockedExpNs" to fixedExpNs,
                "lockedIso" to fixedIso,
                "clockDiagnostic" to clockDiag.get(),
                "cameraKitProbe" to cameraKitProbe,
                "advertisedRequestKeys" to JSONArray(interestingReqKeys),
                "advertisedResultKeys" to JSONArray(interestingResKeys),
                "vendorSweeps" to vendorSweeps,
                "cuvaModeSweep" to cuvaModeSweep,
                "healthEvents" to recorder.recentHealthEvents().size,
                "thermalEnd" to sampleNow(),
            )
            LabBreakthroughProbes.saveProbeJson(outDir, "probe17a_vendor_hlog.json", report)
        }

    // =========================================================================
    // Probe 17B: True >8-Bit GPU / Native / Vulkan / ImageWriter / ByteBuffer / EGL
    // =========================================================================
    @SuppressLint("MissingPermission")
    fun runProbe17B_True10BitGpuAndNativePaths(context: Context, outDir: File): JSONObject =
        LabWaveCProbes.withWatchdogAndLogs(context, outDir) { _, _, sampleNow ->
            LabWaveCProbes.waitNaturalThermalCool(context, outDir)
            val codecName = LabBreakthroughProbes.selectHevcEncoderName()

            // Arm 17B-1: Capture live Camera2 0x324 AHB & inspect CPU lockPlanes + Vulkan properties
            val camAhbHolder = AtomicReference<HardwareBuffer?>(null)
            val ahbInspect = captureAndInspectCuvaAhb(context, camAhbHolder)

            // Arm 17B-2 & 17B-3: ImageWriter + ANativeWindow + Vulkan + GL_EXT_YUV_target + Direct relay
            val imageWriterArms = JSONArray()
            val anwArms = JSONArray()
            val camAhb = camAhbHolder.get()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                for (fmt in listOf(FMT_CUVA_10BIT, FMT_P010, FMT_YUV420, FMT_RGBA1010102)) {
                    for (useTextureTarget in listOf(false, true)) {
                        imageWriterArms.put(testImageWriterGlYuvArm(
                            outDir = outDir,
                            codecName = codecName,
                            format = fmt,
                            useTexture2DTarget = useTextureTarget,
                            camAhb = if (!useTextureTarget) camAhb else null,
                        ))
                    }
                }
            }
            for ((fmt, pat) in listOf(FMT_CUVA_10BIT to 1, FMT_P010 to 1, FMT_RGBA1010102 to 1)) {
                anwArms.put(testAnwLockAndPostArm(outDir, codecName, fmt, pat))
            }
            val directRelayResult = testCameraToImageWriterRelay(context, outDir, codecName)
            runCatching { camAhb?.close() }

            // Arm 17B-4: Crack 0x7F000011 ByteBuffer UV offset & layout
            val byteBufferCrackArms = runByteBufferCrackMatrix(outDir, codecName)

            // Arm 17B-5: RGB10_A2 / RGBA16F EGL root-cause diagnostic + OMX logcat capture
            val eglDiagArms = runEglRootCauseDiagnostic(outDir, codecName)

            val report = json(
                "probe" to "17B_true10bit_gpu_and_native_paths",
                "codecName" to codecName,
                "ahbInspect" to ahbInspect,
                "imageWriterArms" to imageWriterArms,
                "anwLockPostArms" to anwArms,
                "cameraToImageWriterRelay" to directRelayResult,
                "byteBufferCrackArms" to byteBufferCrackArms,
                "eglRootCauseArms" to eglDiagArms,
                "thermalEnd" to sampleNow(),
            )
            LabBreakthroughProbes.saveProbeJson(outDir, "probe17b_10bit_paths.json", report)
        }

    @SuppressLint("MissingPermission")
    private fun captureAndInspectCuvaAhb(context: Context, outHolder: AtomicReference<HardwareBuffer?>): JSONObject {
        val manager = context.getSystemService(CameraManager::class.java)
        val caps = manager.getCameraCharacteristics("0")
        val cuvaReqKey = LabBreakthroughProbes.findVendorRequestKey(caps, "cuvaStateVideoMode")
        val cuvaHint = LabBreakthroughProbes.vendorKeyTypeHint(cuvaReqKey)
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        lease.ready().get(8, TimeUnit.SECONDS)
        val camThread = HandlerThread("p17b-ahb").apply { start() }
        val camHandler = Handler(camThread.looper)

        fun tryWithUsage(usage: Long, tag: String): JSONObject = runCatching {
            val reader = LabBreakthroughProbes.createUsageImageReader(1920, 1080, ImageFormat.PRIVATE, 3, usage)
            val fut = CompletableFuture<JSONObject>()
            val cnt = AtomicInteger(0)
            reader.setOnImageAvailableListener({ r ->
                val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    if (!fut.isDone && cnt.incrementAndGet() >= 3) {
                        val hb = img.hardwareBuffer
                        if (hb != null) {
                            val desc = EglHardwareBufferBridge.describeAhb(hb)
                            val lockRes = EglHardwareBufferBridge.inspectAhbLockPlanes(hb, 64)
                            val vkJson = EglHardwareBufferBridge.probeVulkanAhb(hb, null)
                            val stored = outHolder.compareAndSet(null, hb)
                            if (!stored) hb.close()
                            val meta = lockRes?.second
                            val pInfo = lockRes?.third?.first
                            val sampleHex = lockRes?.third?.second?.take(16)?.joinToString("") { "%02x".format(it) }
                            fut.complete(json(
                                "tag" to tag,
                                "ok" to true,
                                "formatHex" to desc?.first?.let { "0x${it.toString(16)}" },
                                "usageHex" to desc?.second?.let { "0x${it.toString(16)}" },
                                "lockRc" to lockRes?.first,
                                "metaWidth" to meta?.getOrNull(0),
                                "metaHeight" to meta?.getOrNull(1),
                                "metaStride" to meta?.getOrNull(3),
                                "planeCount" to pInfo?.getOrNull(0),
                                "plane0PixelStride" to pInfo?.getOrNull(1),
                                "plane0RowStride" to pInfo?.getOrNull(2),
                                "plane1Offset" to pInfo?.getOrNull(7),
                                "plane2Offset" to pInfo?.getOrNull(8),
                                "sampleFirst16Hex" to sampleHex,
                                "vulkan" to runCatching { JSONObject(vkJson) }.getOrElse { vkJson },
                            ))
                        }
                    }
                } catch (t: Throwable) {
                    fut.completeExceptionally(t)
                } finally {
                    img.close()
                }
            }, camHandler)

            val opened = CompletableFuture<CameraDevice>()
            val closed = CompletableFuture<Unit>()
            val configured = CompletableFuture<CameraCaptureSession>()
            val retiring = AtomicBoolean(false)
            var session: CameraCaptureSession? = null
            var openSubmitted = false
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
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                    if (cuvaReqKey != null) setVendorByteOrIntKey(this, cuvaReqKey, 1, cuvaHint)
                }.build()
                cam.createCaptureSession(SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(reader.surface)),
                    Executor { r -> camHandler.post(r) },
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { if (retiring.get()) s.close() else configured.complete(s) }
                        override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("cfg fail")); s.close() }
                    },
                ).apply { sessionParameters = req })
                session = configured.get(6, TimeUnit.SECONDS)
                session.setRepeatingRequest(req, null, camHandler)
                val res = fut.get(5, TimeUnit.SECONDS)
                runCatching { session.stopRepeating() }
                res
            } finally {
                retiring.set(true)
                val cc = closed.thenApply { Unit }
                lease.releaseAfter(cc)
                if (openSubmitted) camHandler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } }
                else closed.complete(Unit)
                runCatching { cc.get(6, TimeUnit.SECONDS) }
                runCatching { reader.close() }
            }
        }.getOrElse { json("tag" to tag, "ok" to false, "error" to (it.message ?: it.toString())) }

        return try {
            val gpuVid = tryWithUsage(HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_VIDEO_ENCODE, "gpu+vidEnc")
            camThread.quitSafely()
            runCatching { camThread.join(2_000L) }
            Thread.sleep(200L)
            val lease2 = CaptureOwnerAdmission.processGlobal.acquire()
            lease2.ready().get(8, TimeUnit.SECONDS)
            val ct2 = HandlerThread("p17b-ahb-cpu").apply { start() }
            val ch2 = Handler(ct2.looper)
            val cpuRes = runCatching {
                val r2 = LabBreakthroughProbes.createUsageImageReader(
                    1920, 1080, ImageFormat.PRIVATE, 3,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_VIDEO_ENCODE or HardwareBuffer.USAGE_CPU_READ_OFTEN,
                )
                val f2 = CompletableFuture<JSONObject>()
                val c2 = AtomicInteger(0)
                r2.setOnImageAvailableListener({ rd ->
                    val img = runCatching { rd.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                    try {
                        if (!f2.isDone && c2.incrementAndGet() >= 2) {
                            val hb = img.hardwareBuffer
                            val lockRes = hb?.use { EglHardwareBufferBridge.inspectAhbLockPlanes(it, 64) }
                            f2.complete(json(
                                "tag" to "gpu+vidEnc+cpuRead",
                                "ok" to true,
                                "lockRc" to lockRes?.first,
                                "planeCount" to lockRes?.third?.first?.getOrNull(0),
                                "plane0RowStride" to lockRes?.third?.first?.getOrNull(2),
                                "sampleFirst16Hex" to lockRes?.third?.second?.take(16)?.joinToString("") { "%02x".format(it) },
                            ))
                        }
                    } catch (t: Throwable) {
                        f2.completeExceptionally(t)
                    } finally {
                        img.close()
                    }
                }, ch2)
                val op = CompletableFuture<CameraDevice>(); val cl = CompletableFuture<Unit>()
                val cfg = CompletableFuture<CameraCaptureSession>(); val ret = AtomicBoolean(false); var ses: CameraCaptureSession? = null; var sub = false
                try {
                    manager.openCamera("0", object : CameraDevice.StateCallback() {
                        override fun onOpened(c: CameraDevice) { op.complete(c); if (ret.get()) c.close() }
                        override fun onDisconnected(c: CameraDevice) { op.completeExceptionally(IllegalStateException("disc")); c.close() }
                        override fun onError(c: CameraDevice, e: Int) { op.completeExceptionally(IllegalStateException("err $e")); c.close() }
                        override fun onClosed(c: CameraDevice) { cl.complete(Unit) }
                    }, ch2)
                    sub = true
                    val cam = op.get(6, TimeUnit.SECONDS)
                    val rq = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(r2.surface)
                        if (cuvaReqKey != null) setVendorByteOrIntKey(this, cuvaReqKey, 1, cuvaHint)
                    }.build()
                    cam.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(r2.surface)),
                        Executor { x -> ch2.post(x) }, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) { if (ret.get()) s.close() else cfg.complete(s) }
                            override fun onConfigureFailed(s: CameraCaptureSession) { cfg.completeExceptionally(IllegalStateException("cfg fail")); s.close() }
                        }).apply { sessionParameters = rq })
                    ses = cfg.get(6, TimeUnit.SECONDS)
                    ses.setRepeatingRequest(rq, null, ch2)
                    val res = f2.get(4, TimeUnit.SECONDS)
                    runCatching { ses.stopRepeating() }
                    res
                } finally {
                    ret.set(true)
                    val cc = cl.thenApply { Unit }; lease2.releaseAfter(cc)
                    if (sub) ch2.post { try { ses?.close() } finally { if (!op.isCompletedExceptionally) op.getNow(null)?.close() } } else cl.complete(Unit)
                    runCatching { cc.get(6, TimeUnit.SECONDS) }
                    runCatching { r2.close() }
                }
            }.getOrElse { json("tag" to "gpu+vidEnc+cpuRead", "ok" to false, "error" to (it.message ?: it.toString())) }
            ct2.quitSafely(); runCatching { ct2.join(2_000L) }
            json("gpuVideoEnc" to gpuVid, "gpuVideoEncCpuRead" to cpuRes)
        } catch (t: Throwable) {
            json("gpuVideoEnc" to json("ok" to false, "error" to (t.message ?: t.toString())))
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun testImageWriterGlYuvArm(
        outDir: File,
        codecName: String,
        format: Int,
        useTexture2DTarget: Boolean,
        camAhb: HardwareBuffer?,
    ): JSONObject {
        val w = 1920
        val h = 1080
        val tag = "iw_0x${format.toString(16)}_${if (useTexture2DTarget) "tex2d" else "rbo"}"
        val outFile = File(outDir, "p17b_$tag.mp4")
        val encFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h).apply {
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
        var writer: ImageWriter? = null
        var surf: Surface? = null
        var muxer: MediaMuxer? = null
        var pfd: ParcelFileDescriptor? = null
        var codecStarted = false
        var muxerStarted = false
        val glThread = HandlerThread("p17b-$tag").apply { start() }
        val glHandler = Handler(glThread.looper)

        return try {
            codec.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surf = codec.createInputSurface()
            val winBefore = EglHardwareBufferBridge.inspectSurfaceWindow(surf)?.toList()
            pfd = ParcelFileDescriptor.open(outFile, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.start()
            codecStarted = true
            val writerOpened = runCatching { Api29Writer.open(surf, 4, format) }
            writer = writerOpened.getOrNull()
            if (writer == null) {
                return json("arm" to tag, "ok" to false, "stage" to "ImageWriter.newInstance",
                    "winBefore" to winBefore?.toString(),
                    "error" to (writerOpened.exceptionOrNull()?.message ?: "null"))
            }
            val drainDone = CompletableFuture<Int>()
            val drainThread = Thread {
                val info = MediaCodec.BufferInfo()
                var track = -1
                var frames = 0
                try {
                    while (true) {
                        val out = codec.dequeueOutputBuffer(info, 20_000L)
                        if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        } else if (out >= 0) {
                            if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && muxerStarted) {
                                muxer.writeSampleData(track, codec.getOutputBuffer(out)!!, info)
                                frames++
                            }
                            codec.releaseOutputBuffer(out, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                        }
                    }
                    drainDone.complete(frames)
                } catch (t: Throwable) {
                    drainDone.completeExceptionally(t)
                }
            }.apply { start() }

            val glFut = CompletableFuture<JSONObject>()
            glHandler.post {
                var dpy = EGL14.EGL_NO_DISPLAY
                var ctx = EGL14.EGL_NO_CONTEXT
                var pbuf = EGL14.EGL_NO_SURFACE
                var prog = 0
                var fbo = 0
                var targetObj = 0
                try {
                    dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                    val ver = IntArray(2)
                    check(EGL14.eglInitialize(dpy, ver, 0, ver, 1))
                    val cfg = LabWaveCProbes.chooseConfig8(dpy, EGL14.EGL_PBUFFER_BIT)
                    ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
                    pbuf = EGL14.eglCreatePbufferSurface(dpy, cfg, intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
                    check(EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx))

                    val firstImg = writer.dequeueInputImage()
                    val firstHb = firstImg.hardwareBuffer
                    val ahbDesc = firstHb?.let { EglHardwareBufferBridge.describeAhb(it) }
                    val vkJson = if (!useTexture2DTarget && firstHb != null) EglHardwareBufferBridge.probeVulkanAhb(camAhb, firstHb) else null

                    if (firstHb == null) {
                        firstImg.close()
                        glFut.complete(json("arm" to tag, "ok" to false, "stage" to "no_hb_on_dequeued_image"))
                        return@post
                    }

                    val eglImgTest = EglHardwareBufferBridge.createEglImage(firstHb)
                    val eglErr = if (eglImgTest == 0L) EglHardwareBufferBridge.lastEglError() else 0
                    if (eglImgTest == 0L) {
                        firstHb.close()
                        firstImg.close()
                        glFut.complete(json(
                            "arm" to tag, "ok" to false, "stage" to "eglCreateImage",
                            "eglErrHex" to "0x${eglErr.toString(16)}",
                            "ahbFmtHex" to ahbDesc?.first?.let { "0x${it.toString(16)}" },
                            "ahbUsageHex" to ahbDesc?.second?.let { "0x${it.toString(16)}" },
                            "vulkan" to vkJson?.let { runCatching { JSONObject(it) }.getOrElse { vkJson } },
                        ))
                        return@post
                    }

                    val fbs = IntArray(1); GLES30.glGenFramebuffers(1, fbs, 0); fbo = fbs[0]
                    val objs = IntArray(1)
                    if (useTexture2DTarget) {
                        GLES30.glGenTextures(1, objs, 0); targetObj = objs[0]
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, targetObj)
                        val bErr = EglHardwareBufferBridge.bindTexture2D(eglImgTest)
                        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
                        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, targetObj, 0)
                        val fbStatus = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
                        if (bErr != 0 || fbStatus != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                            EglHardwareBufferBridge.destroyEglImage(eglImgTest)
                            firstHb.close(); firstImg.close()
                            glFut.complete(json(
                                "arm" to tag, "ok" to false, "stage" to "fbo_tex2d",
                                "bindErrHex" to "0x${bErr.toString(16)}",
                                "fbStatusHex" to "0x${fbStatus.toString(16)}",
                                "ahbFmtHex" to ahbDesc?.first?.let { "0x${it.toString(16)}" },
                            ))
                            return@post
                        }
                    } else {
                        GLES30.glGenRenderbuffers(1, objs, 0); targetObj = objs[0]
                        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, targetObj)
                        val bErr = EglHardwareBufferBridge.bindRenderbuffer(eglImgTest)
                        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
                        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, targetObj)
                        val fbStatus = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
                        if (bErr != 0 || fbStatus != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                            EglHardwareBufferBridge.destroyEglImage(eglImgTest)
                            firstHb.close(); firstImg.close()
                            glFut.complete(json(
                                "arm" to tag, "ok" to false, "stage" to "fbo_rbo",
                                "bindErrHex" to "0x${bErr.toString(16)}",
                                "fbStatusHex" to "0x${fbStatus.toString(16)}",
                                "ahbFmtHex" to ahbDesc?.first?.let { "0x${it.toString(16)}" },
                                "vulkan" to vkJson?.let { runCatching { JSONObject(it) }.getOrElse { vkJson } },
                            ))
                            return@post
                        }
                    }

                    // Render smooth 10-bit gradient via layout(yuv) for YUV formats or RGBA for RGBA1010102
                    val vert = "#version 300 es\nlayout(location=0) in vec2 aPos;\nout vec2 vUv;\nvoid main(){ gl_Position=vec4(aPos,0.0,1.0); vUv=(aPos+1.0)*0.5; }"
                    val frag = if (format == FMT_RGBA1010102) {
                        "#version 300 es\nprecision highp float;\nin vec2 vUv;\nout vec4 outColor;\nvoid main(){ outColor = vec4(vUv.x, vUv.x, vUv.x, 1.0); }"
                    } else {
                        """#version 300 es
#extension GL_EXT_YUV_target : require
precision highp float;
in vec2 vUv;
layout(yuv) out vec4 outColor;
void main() {
    float uVal = vUv.y < 0.5 ? 0.25 : 0.75;
    float vVal = vUv.y < 0.5 ? 0.75 : 0.25;
    outColor = vec4(vUv.x, uVal, vVal, 1.0);
}"""
                    }
                    prog = LabWaveCProbes.linkProgram(vert, frag)
                    val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
                        .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).apply { position(0) }

                    fun drawAndQueue(img: android.media.Image, hb: HardwareBuffer, existingEglImg: Long, ptsNs: Long) {
                        val eImg = if (existingEglImg != 0L) existingEglImg else EglHardwareBufferBridge.createEglImage(hb)
                        check(eImg != 0L) { "eglCreateImage failed frame" }
                        try {
                            if (useTexture2DTarget) {
                                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, targetObj)
                                check(EglHardwareBufferBridge.bindTexture2D(eImg) == 0)
                            } else {
                                GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, targetObj)
                                check(EglHardwareBufferBridge.bindRenderbuffer(eImg) == 0)
                            }
                            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
                            GLES30.glViewport(0, 0, w, h)
                            GLES30.glUseProgram(prog)
                            GLES30.glEnableVertexAttribArray(0)
                            quad.position(0)
                            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad)
                            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                            GLES30.glFinish()
                            check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "GL draw error" }
                            img.timestamp = ptsNs
                            writer.queueInputImage(img)
                        } finally {
                            EglHardwareBufferBridge.destroyEglImage(eImg)
                            hb.close()
                        }
                    }

                    drawAndQueue(firstImg, firstHb, eglImgTest, 0L)
                    for (fIdx in 1 until 15) {
                        val img = writer.dequeueInputImage()
                        val hb = img.hardwareBuffer ?: error("null hb frame $fIdx")
                        drawAndQueue(img, hb, 0L, fIdx * 33_333_333L)
                    }
                    glFut.complete(json(
                        "arm" to tag,
                        "ok" to true,
                        "ahbFmtHex" to ahbDesc?.first?.let { "0x${it.toString(16)}" },
                        "ahbUsageHex" to ahbDesc?.second?.let { "0x${it.toString(16)}" },
                        "vulkan" to vkJson?.let { runCatching { JSONObject(it) }.getOrElse { vkJson } },
                    ))
                } catch (t: Throwable) {
                    glFut.complete(json("arm" to tag, "ok" to false, "stage" to "draw", "error" to (t.message ?: t.toString())))
                } finally {
                    if (prog != 0) GLES30.glDeleteProgram(prog)
                    if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
                    if (targetObj != 0) {
                        if (useTexture2DTarget) GLES30.glDeleteTextures(1, intArrayOf(targetObj), 0)
                        else GLES30.glDeleteRenderbuffers(1, intArrayOf(targetObj), 0)
                    }
                    if (dpy != EGL14.EGL_NO_DISPLAY) {
                        EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                        if (pbuf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, pbuf)
                        if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
                        EGL14.eglTerminate(dpy)
                    }
                }
            }

            val glRes = glFut.get(8, TimeUnit.SECONDS)
            runCatching { codec.signalEndOfInputStream() }
            val frames = runCatching { drainDone.get(6, TimeUnit.SECONDS) }.getOrDefault(0)
            glRes.put("winBefore", winBefore?.toString())
            glRes.put("encodedFrames", frames)
            glRes.put("clip", outFile.name)
            glRes.put("clipBytes", outFile.length())
            glRes
        } catch (t: Throwable) {
            json("arm" to tag, "ok" to false, "error" to (t.message ?: t.toString()))
        } finally {
            runCatching { writer?.close() }
            if (codecStarted) runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { surf?.release() }
            runCatching { pfd?.close() }
            glThread.quitSafely(); runCatching { glThread.join(2_000L) }
        }
    }

    private fun testAnwLockAndPostArm(outDir: File, codecName: String, format: Int, patternMode: Int): JSONObject {
        val tag = "anw_0x${format.toString(16)}"
        val outFile = File(outDir, "p17b_$tag.mp4")
        val encFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1080).apply {
            setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_LEVEL, CodecProfileLevel.HEVCMainTierLevel62)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 60_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
        }
        val codec = MediaCodec.createByCodecName(codecName)
        var surf: Surface? = null; var muxer: MediaMuxer? = null; var pfd: ParcelFileDescriptor? = null
        var codecStarted = false; var muxerStarted = false
        return try {
            codec.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surf = codec.createInputSurface()
            pfd = ParcelFileDescriptor.open(outFile, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.start(); codecStarted = true
            val drainDone = CompletableFuture<Int>()
            Thread {
                val info = MediaCodec.BufferInfo(); var track = -1; var f = 0
                try {
                    while (true) {
                        val out = codec.dequeueOutputBuffer(info, 20_000L)
                        if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true }
                        else if (out >= 0) {
                            if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && muxerStarted) {
                                muxer.writeSampleData(track, codec.getOutputBuffer(out)!!, info); f++
                            }
                            codec.releaseOutputBuffer(out, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                        }
                    }
                    drainDone.complete(f)
                } catch (t: Throwable) { drainDone.completeExceptionally(t) }
            }.start()

            var lastPair: Pair<Int, IntArray>? = null
            for (i in 0 until 8) {
                val pair = EglHardwareBufferBridge.probeAnwFormatLockAndPost(surf, 1920, 1080, format, DS_BT2020_HLG_FULL, patternMode)
                if (i == 0) lastPair = pair
                if (pair == null || pair.second[2] != 0) break
            }
            codec.signalEndOfInputStream()
            val frames = runCatching { drainDone.get(5, TimeUnit.SECONDS) }.getOrDefault(0)
            val stats = lastPair?.second
            json(
                "arm" to tag,
                "postRc" to lastPair?.first,
                "geomRc" to stats?.getOrNull(0),
                "dsRc" to stats?.getOrNull(1),
                "lockRc" to stats?.getOrNull(2),
                "lockedW" to stats?.getOrNull(3),
                "lockedStride" to stats?.getOrNull(4),
                "lockedFmtHex" to stats?.getOrNull(5)?.let { "0x${it.toString(16)}" },
                "encodedFrames" to frames,
                "clipBytes" to outFile.length(),
            )
        } catch (t: Throwable) {
            json("arm" to tag, "ok" to false, "error" to (t.message ?: t.toString()))
        } finally {
            if (codecStarted) runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { surf?.release() }
            runCatching { pfd?.close() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun testCameraToImageWriterRelay(context: Context, outDir: File, codecName: String): JSONObject {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return json("ok" to false, "error" to "API<29")
        val outFile = File(outDir, "p17b_cam_iw_relay.mp4")
        val encFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1080).apply {
            setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_LEVEL, CodecProfileLevel.HEVCMainTierLevel62)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 60_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
        }
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        lease.ready().get(8, TimeUnit.SECONDS)
        val manager = context.getSystemService(CameraManager::class.java)
        val caps = manager.getCameraCharacteristics("0")
        val cuvaReqKey = LabBreakthroughProbes.findVendorRequestKey(caps, "cuvaStateVideoMode")
        val cuvaHint = LabBreakthroughProbes.vendorKeyTypeHint(cuvaReqKey)
        val camThread = HandlerThread("p17b-relay").apply { start() }
        val camHandler = Handler(camThread.looper)
        val codec = MediaCodec.createByCodecName(codecName)
        val reader = LabBreakthroughProbes.createUsageImageReader(
            1920, 1080, ImageFormat.PRIVATE, 6,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_VIDEO_ENCODE,
        )
        var encSurf: Surface? = null; var writer: ImageWriter? = null; var muxer: MediaMuxer? = null; var pfd: ParcelFileDescriptor? = null
        var codecStarted = false; var muxerStarted = false
        val opened = CompletableFuture<CameraDevice>(); val closed = CompletableFuture<Unit>()
        val configured = CompletableFuture<CameraCaptureSession>(); val retiring = AtomicBoolean(false)
        var session: CameraCaptureSession? = null; var openSubmitted = false
        val queued = AtomicInteger(0); val queueFail = AtomicReference<String?>(null)
        return try {
            codec.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encSurf = codec.createInputSurface()
            pfd = ParcelFileDescriptor.open(outFile, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.start(); codecStarted = true
            writer = Api29Writer.open(encSurf, 4, FMT_CUVA_10BIT)
            val drainFut = CompletableFuture<Int>()
            Thread {
                val info = MediaCodec.BufferInfo(); var track = -1; var f = 0
                try {
                    while (true) {
                        val out = codec.dequeueOutputBuffer(info, 20_000L)
                        if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true }
                        else if (out >= 0) {
                            if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && muxerStarted) {
                                muxer.writeSampleData(track, codec.getOutputBuffer(out)!!, info); f++
                            }
                            codec.releaseOutputBuffer(out, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                        }
                    }
                    drainFut.complete(f)
                } catch (t: Throwable) { drainFut.completeExceptionally(t) }
            }.start()

            reader.setOnImageAvailableListener({ r ->
                val img = runCatching { r.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
                if (retiring.get() || queued.get() >= 20) {
                    img.close(); return@setOnImageAvailableListener
                }
                runCatching {
                    writer.queueInputImage(img)
                    queued.incrementAndGet()
                }.onFailure {
                    queueFail.compareAndSet(null, it.message ?: it.toString())
                    runCatching { img.close() }
                }
            }, camHandler)

            manager.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { opened.complete(c); if (retiring.get()) c.close() }
                override fun onDisconnected(c: CameraDevice) { opened.completeExceptionally(IllegalStateException("disc")); c.close() }
                override fun onError(c: CameraDevice, e: Int) { opened.completeExceptionally(IllegalStateException("err $e")); c.close() }
                override fun onClosed(c: CameraDevice) { closed.complete(Unit) }
            }, camHandler)
            openSubmitted = true
            val cam = opened.get(6, TimeUnit.SECONDS)
            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                if (cuvaReqKey != null) setVendorByteOrIntKey(this, cuvaReqKey, 1, cuvaHint)
            }.build()
            cam.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(reader.surface)),
                Executor { x -> camHandler.post(x) }, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { if (retiring.get()) s.close() else configured.complete(s) }
                    override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("cfg")); s.close() }
                }).apply { sessionParameters = req })
            session = configured.get(6, TimeUnit.SECONDS)
            session.setRepeatingRequest(req, null, camHandler)
            Thread.sleep(1_200L)
            retiring.set(true)
            runCatching { session.stopRepeating() }
            codec.signalEndOfInputStream()
            val encFrames = runCatching { drainFut.get(5, TimeUnit.SECONDS) }.getOrDefault(0)
            json("ok" to (encFrames > 5), "queued" to queued.get(), "queueFail" to queueFail.get(),
                "encodedFrames" to encFrames, "clip" to outFile.name, "clipBytes" to outFile.length())
        } catch (t: Throwable) {
            json("ok" to false, "error" to (t.message ?: t.toString()), "queueFail" to queueFail.get())
        } finally {
            retiring.set(true)
            val cc = closed.thenApply { Unit }; lease.releaseAfter(cc)
            if (openSubmitted) camHandler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } }
            else closed.complete(Unit)
            runCatching { cc.get(6, TimeUnit.SECONDS) }
            runCatching { writer?.close() }
            runCatching { reader.close() }
            if (codecStarted) runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { encSurf?.release() }
            runCatching { pfd?.close() }
            camThread.quitSafely(); runCatching { camThread.join(2_000L) }
        }
    }

    private fun runByteBufferCrackMatrix(outDir: File, codecName: String): JSONArray {
        val arms = JSONArray()
        val w = 1920
        val h = 1080
        data class BbSpec(
            val tag: String,
            val colorFormat: Int,
            val stride: Int?,
            val sliceH: Int?,
            val fillMode: Int,
        )
        val specs = listOf(
            BbSpec("bb_s3840_h1080_p010", COLOR_FORMAT_HISI_YUV420SP_10BIT, w * 2, h, 0),
            BbSpec("bb_s3840_h1088_p010", COLOR_FORMAT_HISI_YUV420SP_10BIT, w * 2, 1088, 1),
            BbSpec("bb_s1920_h1080_p010", COLOR_FORMAT_HISI_YUV420SP_10BIT, w, h, 0),
            BbSpec("bb_offset_probe_wh2_p010", COLOR_FORMAT_HISI_YUV420SP_10BIT, w * 2, h, 2),
            BbSpec("bb_planar_16bit_wh2", COLOR_FORMAT_HISI_YUV420SP_10BIT, w * 2, h, 3),
            BbSpec("bb_flexible_main10", MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible, null, null, 0),
        )
        for (spec in specs) {
            arms.put(runCatching {
                val outFile = File(outDir, "p17b_${spec.tag}.mp4")
                val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h).apply {
                    setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
                    setInteger(MediaFormat.KEY_LEVEL, CodecProfileLevel.HEVCMainTierLevel62)
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, spec.colorFormat)
                    setInteger(MediaFormat.KEY_BIT_RATE, 60_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, w * 1152 * 3)
                    spec.stride?.let { setInteger(MediaFormat.KEY_STRIDE, it) }
                    spec.sliceH?.let { setInteger(MediaFormat.KEY_SLICE_HEIGHT, it) }
                }
                val codec = MediaCodec.createByCodecName(codecName)
                var muxer: MediaMuxer? = null; var pfd: ParcelFileDescriptor? = null
                var started = false; var muxerStarted = false
                try {
                    codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    pfd = ParcelFileDescriptor.open(outFile, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
                    muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    codec.start(); started = true
                    val inputFmtStr = codec.inputFormat.toString()
                    var inputImageDesc = "none"
                    var firstCap = -1

                    val yBytesBase = w * (spec.sliceH ?: h) * 2
                    val payload = ByteArray(w * 1152 * 3)
                    val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                    when (spec.fillMode) {
                        0, 1 -> {
                            // Horizontal smooth 10-bit ramp (0..1023 << 6) + constant chroma U=256<<6, V=768<<6 after sliceH
                            for (yy in 0 until h) {
                                val rBase = yy * w * 2
                                for (xx in 0 until w) {
                                    val c10 = (xx * 1023) / (w - 1)
                                    bb.putShort(rBase + xx * 2, (c10 shl 6).toShort())
                                }
                            }
                            val u16 = (256 shl 6).toShort()
                            val v16 = (768 shl 6).toShort()
                            var p = yBytesBase
                            while (p + 3 < payload.size) {
                                bb.putShort(p, u16); bb.putShort(p + 2, v16); p += 4
                            }
                        }
                        2 -> {
                            // Offset finder: Y = constant 512<<6 in [0..w*h*2), 16-bit UV (320<<6, 700<<6) at [w*h*2..end)
                            val yC = (512 shl 6).toShort()
                            for (i in 0 until (w * h)) bb.putShort(i * 2, yC)
                            // Stamp distinct top/bottom chroma regions across candidate UV starts (w*h, w*h*2)
                            var p = w * h * 2
                            while (p + 3 < payload.size) {
                                bb.putShort(p, (300 shl 6).toShort()); bb.putShort(p + 2, (720 shl 6).toShort()); p += 4
                            }
                        }
                        3 -> {
                            // Planar 16-bit YUV420P10LE: Y [0..w*h*2), U [w*h*2..w*h*2.5), V [w*h*2.5..w*h*3)
                            for (yy in 0 until h) {
                                val rBase = yy * w * 2
                                for (xx in 0 until w) {
                                    bb.putShort(rBase + xx * 2, (((xx * 1023) / (w - 1)) shl 6).toShort())
                                }
                            }
                            val uStart = w * h * 2
                            val planeUvBytes = (w * h) / 2
                            for (i in 0 until planeUvBytes / 2) bb.putShort(uStart + i * 2, (256 shl 6).toShort())
                            val vStart = uStart + planeUvBytes
                            for (i in 0 until planeUvBytes / 2) bb.putShort(vStart + i * 2, (768 shl 6).toShort())
                        }
                    }

                    var track = -1; var encFrames = 0; var submitted = 0; var eosSent = false; var eosDone = false
                    val info = MediaCodec.BufferInfo()
                    val dl = System.currentTimeMillis() + 10_000L
                    while (!eosDone && System.currentTimeMillis() < dl) {
                        if (!eosSent) {
                            val inIdx = codec.dequeueInputBuffer(5_000L)
                            if (inIdx >= 0) {
                                if (submitted == 0) {
                                    inputImageDesc = runCatching {
                                        val img = codec.getInputImage(inIdx)
                                        if (img == null) "null"
                                        else "fmt=0x${img.format.toString(16)} planes=${img.planes.size} " +
                                            img.planes.mapIndexed { idx, pl ->
                                                "p$idx(px=${pl.pixelStride},row=${pl.rowStride},cap=${pl.buffer.capacity()})"
                                            }.joinToString(",")
                                    }.getOrElse { "ex:${it.message}" }
                                }
                                if (submitted >= 10) {
                                    codec.queueInputBuffer(inIdx, 0, 0, submitted * 33_333L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    eosSent = true
                                } else {
                                    val buf = codec.getInputBuffer(inIdx)!!
                                    if (firstCap < 0) firstCap = buf.capacity()
                                    val n = minOf(payload.size, buf.capacity())
                                    buf.clear(); buf.put(payload, 0, n)
                                    codec.queueInputBuffer(inIdx, 0, n, submitted * 33_333L, 0)
                                    submitted++
                                }
                            }
                        }
                        val outIdx = codec.dequeueOutputBuffer(info, 5_000L)
                        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (!muxerStarted) { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true }
                        } else if (outIdx >= 0) {
                            val ob = codec.getOutputBuffer(outIdx)
                            if (ob != null && info.size > 0 && muxerStarted && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                ob.position(info.offset); ob.limit(info.offset + info.size)
                                muxer.writeSampleData(track, ob, info); encFrames++
                            }
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eosDone = true
                            codec.releaseOutputBuffer(outIdx, false)
                        }
                    }
                    json(
                        "arm" to spec.tag, "ok" to (encFrames >= 8),
                        "inputFormat" to inputFmtStr,
                        "inputImage" to inputImageDesc,
                        "firstBufCap" to firstCap,
                        "encodedFrames" to encFrames,
                        "clip" to outFile.name,
                        "clipBytes" to outFile.length(),
                    )
                } finally {
                    if (started) runCatching { codec.stop() }
                    runCatching { codec.release() }
                    if (muxerStarted) runCatching { muxer?.stop() }
                    runCatching { muxer?.release() }
                    runCatching { pfd?.close() }
                }
            }.getOrElse { json("arm" to spec.tag, "ok" to false, "error" to (it.message ?: it.toString())) })
        }
        return arms
    }

    private fun runEglRootCauseDiagnostic(outDir: File, codecName: String): JSONArray {
        val arms = JSONArray()
        data class EglDiagSpec(
            val tag: String,
            val r: Int, val g: Int, val b: Int, val a: Int,
            val isFloat: Boolean,
            val useRecordable: Boolean,
            val anwDataSpace: Int,
            val eglColorspaceAttr: Int?,
        )
        val specs = listOf(
            EglDiagSpec("rgb10a2_no_recordable", 10, 10, 10, 2, false, false, 0, null),
            EglDiagSpec("rgb10a2_anw_hlg_ds", 10, 10, 10, 2, false, true, DS_BT2020_HLG_FULL, null),
            EglDiagSpec("rgb10a2_pq_ext", 10, 10, 10, 2, false, true, 0, EGL_GL_COLORSPACE_BT2020_PQ_EXT),
            EglDiagSpec("rgba16f_anw_hlg_ds", 16, 16, 16, 16, true, true, DS_BT2020_HLG_FULL, null),
        )
        val t = HandlerThread("p17b-egl-diag").apply { start() }
        val h = Handler(t.looper)
        try {
            for (s in specs) {
                arms.put(runCatching {
                    val outFile = File(outDir, "p17b_${s.tag}.mp4")
                    val encFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1080).apply {
                        setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
                        setInteger(MediaFormat.KEY_LEVEL, CodecProfileLevel.HEVCMainTierLevel62)
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(MediaFormat.KEY_BIT_RATE, 60_000_000)
                        setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
                        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                    }
                    val codec = MediaCodec.createByCodecName(codecName)
                    var surf: Surface? = null; var muxer: MediaMuxer? = null; var pfd: ParcelFileDescriptor? = null
                    var codecStarted = false; var muxerStarted = false
                    try {
                        codec.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        surf = codec.createInputSurface()
                        if (s.anwDataSpace != 0) {
                            EglHardwareBufferBridge.setSurfaceGeometryAndDataSpace(surf, 0, 0, 0, s.anwDataSpace)
                        }
                        pfd = ParcelFileDescriptor.open(outFile, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
                        muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        codec.start(); codecStarted = true
                        val drainDone = CompletableFuture<Int>()
                        Thread {
                            val info = MediaCodec.BufferInfo(); var track = -1; var f = 0
                            try {
                                while (true) {
                                    val out = codec.dequeueOutputBuffer(info, 20_000L)
                                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true }
                                    else if (out >= 0) {
                                        if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && muxerStarted) {
                                            muxer.writeSampleData(track, codec.getOutputBuffer(out)!!, info); f++
                                        }
                                        codec.releaseOutputBuffer(out, false)
                                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                                    }
                                }
                                drainDone.complete(f)
                            } catch (e: Throwable) { drainDone.completeExceptionally(e) }
                        }.start()

                        val glFut = CompletableFuture<String>()
                        h.post {
                            var dpy = EGL14.EGL_NO_DISPLAY; var ctx = EGL14.EGL_NO_CONTEXT; var win = EGL14.EGL_NO_SURFACE
                            var prog = 0
                            try {
                                dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                                val ver = IntArray(2); check(EGL14.eglInitialize(dpy, ver, 0, ver, 1))
                                val attrs = mutableListOf(
                                    EGL14.EGL_RED_SIZE, s.r, EGL14.EGL_GREEN_SIZE, s.g, EGL14.EGL_BLUE_SIZE, s.b, EGL14.EGL_ALPHA_SIZE, s.a,
                                    EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                                )
                                if (s.useRecordable) { attrs += EGL_RECORDABLE_ANDROID; attrs += 1 }
                                if (s.isFloat) { attrs += 0x3339; attrs += 0x333B }
                                attrs += EGL14.EGL_NONE
                                val cfgs = arrayOfNulls<EGLConfig>(1); val cnt = IntArray(1)
                                check(EGL14.eglChooseConfig(dpy, attrs.toIntArray(), 0, cfgs, 0, 1, cnt, 0) && cnt[0] > 0) {
                                    "no EGLConfig (err=0x${EGL14.eglGetError().toString(16)})"
                                }
                                ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
                                check(ctx != EGL14.EGL_NO_CONTEXT) { "eglCreateContext err=0x${EGL14.eglGetError().toString(16)}" }
                                val wAttrs = s.eglColorspaceAttr?.let { intArrayOf(EGL_GL_COLORSPACE_KHR, it, EGL14.EGL_NONE) } ?: intArrayOf(EGL14.EGL_NONE)
                                win = EGL14.eglCreateWindowSurface(dpy, cfgs[0], surf, wAttrs, 0)
                                check(win != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface err=0x${EGL14.eglGetError().toString(16)}" }
                                check(EGL14.eglMakeCurrent(dpy, win, win, ctx)) { "eglMakeCurrent err=0x${EGL14.eglGetError().toString(16)}" }
                                val vert = "#version 300 es\nlayout(location=0) in vec2 aPos;\nout vec2 vUv;\nvoid main(){ gl_Position=vec4(aPos,0.0,1.0); vUv=(aPos+1.0)*0.5; }"
                                val frag = "#version 300 es\nprecision highp float;\nin vec2 vUv;\nout vec4 outColor;\nvoid main(){ outColor=vec4(vec3(vUv.x),1.0); }"
                                prog = LabWaveCProbes.linkProgram(vert, frag)
                                val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
                                    .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).apply { position(0) }
                                GLES30.glViewport(0, 0, 1920, 1080)
                                GLES30.glUseProgram(prog)
                                GLES30.glEnableVertexAttribArray(0)
                                GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad)
                                for (i in 0 until 8) {
                                    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                                    EGLExt.eglPresentationTimeANDROID(dpy, win, i * 33_333_333L)
                                    val sw = EGL14.eglSwapBuffers(dpy, win)
                                    if (!sw) error("eglSwapBuffers=false err=0x${EGL14.eglGetError().toString(16)}")
                                }
                                glFut.complete("ok")
                            } catch (e: Throwable) {
                                glFut.complete("glErr:${e.message}")
                            } finally {
                                if (prog != 0) GLES30.glDeleteProgram(prog)
                                if (dpy != EGL14.EGL_NO_DISPLAY) {
                                    EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                                    if (win != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, win)
                                    if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
                                    EGL14.eglTerminate(dpy)
                                }
                            }
                        }
                        val glStatus = glFut.get(5, TimeUnit.SECONDS)
                        codec.signalEndOfInputStream()
                        val frames = runCatching { drainDone.get(4, TimeUnit.SECONDS) }.getOrDefault(0)
                        json("arm" to s.tag, "ok" to (frames > 0), "glStatus" to glStatus,
                            "encodedFrames" to frames, "clipBytes" to outFile.length())
                    } finally {
                        if (codecStarted) runCatching { codec.stop() }
                        runCatching { codec.release() }
                        if (muxerStarted) runCatching { muxer?.stop() }
                        runCatching { muxer?.release() }
                        runCatching { surf?.release() }
                        runCatching { pfd?.close() }
                    }
                }.getOrElse { json("arm" to s.tag, "ok" to false, "error" to (it.message ?: it.toString())) })
            }
        } finally {
            t.quitSafely(); runCatching { t.join(2_000L) }
        }
        return arms
    }

    // =========================================================================
    // Probe 17C: 4K60 Bottleneck Stage-by-Stage Telemetry & FPS-Escape Diagnosis
    // =========================================================================
    @SuppressLint("MissingPermission")
    fun runProbe17C_4k60BottleneckAndFpsEscape(context: Context, outDir: File): JSONObject =
        LabWaveCProbes.withWatchdogAndLogs(context, outDir) { _, _, sampleNow ->
            val manager = context.getSystemService(CameraManager::class.java)
            val caps = manager.getCameraCharacteristics("0")
            val cuvaReqKey = LabBreakthroughProbes.findVendorRequestKey(caps, "cuvaStateVideoMode")
            val cuvaHint = LabBreakthroughProbes.vendorKeyTypeHint(cuvaReqKey)
            val hwAlgoFpsKey = LabBreakthroughProbes.findVendorResultKey(caps, "hw-algo-fps")

            data class Arm17C(
                val name: String,
                val directMode: Boolean,
                val manualExposureNs: Long?,
                val manualIso: Int?,
                val fpsEscapeTags: Boolean,
                val aiDenoiseEscape: Boolean,
                val scopesEnabled: Boolean,
                val bitrate: Int = 80_000_000,
                val iFrameInterval: Int = 1,
            )

            // Probe room ISO at 1/60s from warmup, keep stationary across all arms
            val warmup = LabBreakthroughProbes.warmupAndLockExposure(context) ?: (16_666_667L to 400)
            val isoFor60 = ((warmup.second.toDouble() * (warmup.first.toDouble() / 16_000_000.0)).toInt())
                .coerceIn(50, caps.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.upper ?: 6400)
            Thread.sleep(250L)

            val specs = listOf(
                Arm17C("17C_1_gpu_auto_scopes_on", directMode = false, manualExposureNs = null, manualIso = null, fpsEscapeTags = false, aiDenoiseEscape = false, scopesEnabled = true),
                Arm17C("17C_2_gpu_auto_fps_escape", directMode = false, manualExposureNs = null, manualIso = null, fpsEscapeTags = true, aiDenoiseEscape = false, scopesEnabled = true),
                Arm17C("17C_3_gpu_auto_ai_denoise_escape", directMode = false, manualExposureNs = null, manualIso = null, fpsEscapeTags = true, aiDenoiseEscape = true, scopesEnabled = true),
                Arm17C("17C_4a_gpu_manual_1_60_scopes_on", directMode = false, manualExposureNs = 16_000_000L, manualIso = isoFor60, fpsEscapeTags = false, aiDenoiseEscape = false, scopesEnabled = true),
                Arm17C("17C_4b_gpu_manual_1_60_scopes_off", directMode = false, manualExposureNs = 16_000_000L, manualIso = isoFor60, fpsEscapeTags = false, aiDenoiseEscape = false, scopesEnabled = false),
                Arm17C("17C_5a_direct_auto", directMode = true, manualExposureNs = null, manualIso = null, fpsEscapeTags = false, aiDenoiseEscape = false, scopesEnabled = false),
                Arm17C("17C_5b_direct_auto_escape", directMode = true, manualExposureNs = null, manualIso = null, fpsEscapeTags = true, aiDenoiseEscape = true, scopesEnabled = false),
                Arm17C("17C_5c_direct_manual_1_60", directMode = true, manualExposureNs = 16_000_000L, manualIso = isoFor60, fpsEscapeTags = false, aiDenoiseEscape = false, scopesEnabled = false),
                Arm17C("17C_6a_direct_manual_100m", directMode = true, manualExposureNs = 16_000_000L, manualIso = isoFor60, fpsEscapeTags = false, aiDenoiseEscape = false, scopesEnabled = false, bitrate = 100_000_000),
                Arm17C("17C_6b_direct_manual_all_intra_100m", directMode = true, manualExposureNs = 16_000_000L, manualIso = isoFor60, fpsEscapeTags = false, aiDenoiseEscape = false, scopesEnabled = false, bitrate = 100_000_000, iFrameInterval = 0),
            )

            val arms = JSONArray()
            for (spec in specs) {
                LabWaveCProbes.waitNaturalThermalCool(context, outDir)
                Thread.sleep(250L)
                arms.put(runCatching {
                    val outFile = File(outDir, "${spec.name}.mp4")
                    val lease = CaptureOwnerAdmission.processGlobal.acquire()
                    lease.ready().get(8, TimeUnit.SECONDS)
                    val camThread = HandlerThread("p17c-cam").apply { start() }
                    val camHandler = Handler(camThread.looper)
                    val previewDrain = ImageReader.newInstance(1280, 720, ImageFormat.PRIVATE, 4).apply {
                        setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage()?.close() } }, camHandler)
                    }

                    val camTimestamps = mutableListOf<Long>()
                    val encSubmitPtsNs = mutableListOf<Long>()
                    val expTimesNs = mutableListOf<Long>()
                    val isos = mutableListOf<Int>()
                    val hwAlgoFpsVals = mutableListOf<String>()

                    val opened = CompletableFuture<CameraDevice>()
                    val closed = CompletableFuture<Unit>()
                    val configured = CompletableFuture<CameraCaptureSession>()
                    val retiring = AtomicBoolean(false)
                    var session: CameraCaptureSession? = null
                    var openSubmitted = false

                    fun configureRequest(b: CaptureRequest.Builder) {
                        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(60, 60))
                        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        if (spec.manualExposureNs != null && spec.manualIso != null) {
                            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, spec.manualExposureNs)
                            b.set(CaptureRequest.SENSOR_SENSITIVITY, spec.manualIso)
                        } else {
                            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        }
                        b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        if (cuvaReqKey != null) setVendorByteOrIntKey(b, cuvaReqKey, 1, cuvaHint)
                        if (spec.fpsEscapeTags) {
                            setIntVendorKey(b, "com.huawei.capture.metadata.hwVideoReductionFpsEscape", 1)
                            setIntVendorKey(b, "com.huawei.capture.metadata.hw-video-dynamic-fps-mode", 0)
                            setIntVendorKey(b, "com.huawei.capture.metadata.hw-video-fps", 60)
                            setFloatVendorKey(b, "com.huawei.capture.metadata.vaManualFNum", 1.6f)
                        }
                        if (spec.aiDenoiseEscape) {
                            setIntVendorKey(b, "com.huawei.capture.metadata.hwVideoAiDenoiseEscape", 1)
                        }
                    }

                    val cb = object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                            res.get(CaptureResult.SENSOR_TIMESTAMP)?.let { synchronized(camTimestamps) { camTimestamps += it } }
                            res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { synchronized(expTimesNs) { expTimesNs += it } }
                            res.get(CaptureResult.SENSOR_SENSITIVITY)?.let { synchronized(isos) { isos += it } }
                            if (hwAlgoFpsVals.size < 8) {
                                LabBreakthroughProbes.readResultKeyValue(res, hwAlgoFpsKey)?.let { hwAlgoFpsVals += it }
                            }
                        }
                    }

                    if (!spec.directMode) {
                        val scopeCount = AtomicInteger(0)
                        val pipeline = OpenCineLogGpuPipeline(
                            size = Size(3840, 2160),
                            sourcePath = OpenCineLogSourcePath.HLG10_BT2020,
                            preview = previewDrain.surface,
                            sensorOrientationDegrees = caps.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                            displayRotationDegrees = 0,
                            frontFacing = false,
                            viewAssist = true,
                            targetFps = 60,
                            passthroughSdr = false,
                            appContext = context.applicationContext,
                            onAnalysis = if (spec.scopesEnabled) { _ -> scopeCount.incrementAndGet() } else null,
                            researchEncoderOverride = ResearchEncoderOverride(enableFrameDiagnostics = true),
                            useZeroCopyVideoImageReader = true,
                            onFailure = { _, _ -> },
                        )
                        pipeline.onRecordedFrame = { _, ptsNs -> synchronized(encSubmitPtsNs) { encSubmitPtsNs += ptsNs } }
                        pipeline.setAnalysisSuspended(!spec.scopesEnabled)

                        var pfd: ParcelFileDescriptor? = null
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
                                addTarget(pipeline.cameraInputSurface)
                                configureRequest(this)
                            }.build()
                            cam.createCaptureSession(SessionConfiguration(
                                SessionConfiguration.SESSION_REGULAR,
                                listOf(OutputConfiguration(pipeline.cameraInputSurface)),
                                Executor { r -> camHandler.post(r) },
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(s: CameraCaptureSession) { if (retiring.get()) s.close() else configured.complete(s) }
                                    override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("cfg fail")); s.close() }
                                },
                            ).apply { sessionParameters = req })
                            session = configured.get(6, TimeUnit.SECONDS)
                            session.setRepeatingRequest(req, cb, camHandler)
                            Thread.sleep(400L)

                            pfd = ParcelFileDescriptor.open(outFile, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
                            val geom = RecordingGeometryCalculator.calculate(
                                Size(3840, 2160), caps.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90, 0,
                                CameraCharacteristics.LENS_FACING_BACK, RecordingGeometryMode.NATIVE_RASTER,
                            )
                            val started = CountDownLatch(1)
                            val stopped = CompletableFuture<OpenCineLogRecordingEvidence?>()
                            check(pipeline.startRecording(
                                output = pfd, bitrate = spec.bitrate, geometry = geom,
                                onStarted = { started.countDown() },
                                onStopped = { _, ev -> stopped.complete(ev) },
                                hlgOutput = false,
                            ))
                            check(started.await(5, TimeUnit.SECONDS))
                            camTimestamps.clear(); expTimesNs.clear(); isos.clear()
                            Thread.sleep(5_000L)
                            pipeline.stopRecording()
                            val ev = stopped.get(8, TimeUnit.SECONDS)
                            runCatching { pipeline.recordingFileRetirement().get(6, TimeUnit.SECONDS) }
                            runCatching { session.stopRepeating() }

                            val camM = synchronized(camTimestamps) { LabBreakthroughProbes.computeTimestampMetrics(camTimestamps.toList(), 25_000_000L) }
                            val subM = synchronized(encSubmitPtsNs) { LabBreakthroughProbes.computeTimestampMetrics(encSubmitPtsNs.toList(), 25_000_000L) }
                            val diag = pipeline.frameDiagnosticsSnapshot()
                            val fileM = LabBreakthroughProbes.inspectWrittenMp4(outFile)
                            json(
                                "arm" to spec.name, "ok" to ((ev?.encodedFrames ?: 0L) > 20L),
                                "mode" to "gpu_oclog2",
                                "camCb" to camM.toJson(),
                                "encSubmit" to subM.toJson(),
                                "encodedFrames" to ev?.encodedFrames,
                                "maxPtsGapUs" to ev?.maxVideoPtsGapUs,
                                "gapsOverThresh" to ev?.videoPtsGapsOverThreshold,
                                "avgExpMs" to expTimesNs.takeIf { it.isNotEmpty() }?.let { it.average() / 1e6 },
                                "maxExpMs" to expTimesNs.maxOrNull()?.let { it / 1e6 },
                                "avgIso" to isos.takeIf { it.isNotEmpty() }?.average(),
                                "hwAlgoFpsSample" to hwAlgoFpsVals.distinct().toString(),
                                "scopeCallbacks" to scopeCount.get(),
                                "diagTotalGaps" to diag?.totalGaps,
                                "diagMissingFrames" to diag?.totalMissingFrames,
                                "diagMaxTotalMs" to diag?.maxTotalUs?.let { it / 1000.0 },
                                "diagMaxEncoderSwapMs" to diag?.maxEncoderSwapUs?.let { it / 1000.0 },
                                "diagMaxScopesMs" to diag?.maxScopesUs?.let { it / 1000.0 },
                                "diagMaxPreviewSwapMs" to diag?.maxPreviewSwapUs?.let { it / 1000.0 },
                                "fileStats" to fileM,
                                "clip" to outFile.name,
                            )
                        } finally {
                            retiring.set(true)
                            val cc = closed.thenApply { Unit }; lease.releaseAfter(cc)
                            if (openSubmitted) camHandler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } }
                            else closed.complete(Unit)
                            runCatching { cc.get(6, TimeUnit.SECONDS) }
                            runCatching { pipeline.closeAsync().get(6, TimeUnit.SECONDS) }
                            runCatching { previewDrain.close() }
                            runCatching { pfd?.close() }
                            camThread.quitSafely(); runCatching { camThread.join(2_000L) }
                        }
                    } else {
                        val res = runDirect10BitCapture(
                            context = context, manager = manager, cameraId = "0", size = Size(3840, 2160),
                            fps = 60, durationMs = 5_000L, bitrate = spec.bitrate, iFrameInterval = spec.iFrameInterval,
                            outFile = outFile,
                            customBuilderFn = { b -> configureRequest(b) },
                            releaseLease = false, existingLease = lease,
                        )
                        res.put("arm", spec.name).put("mode", "direct_0x324")
                    }
                }.getOrElse { json("arm" to spec.name, "ok" to false, "error" to (it.message ?: it.toString())) })
            }

            val report = json(
                "probe" to "17C_4k60_bottleneck_and_fps_escape",
                "matchedIsoFor60" to isoFor60,
                "arms" to arms,
                "thermalEnd" to sampleNow(),
            )
            LabBreakthroughProbes.saveProbeJson(outDir, "probe17c_4k60_bottleneck.json", report)
        }

    // =========================================================================
    // Probe 17D: Direct 10-Bit Surface Matrix + Hidden RAW_SENSOR (32) Verification
    // =========================================================================
    @SuppressLint("MissingPermission")
    fun runProbe17D_Direct10BitMatrixAndHiddenRaw(context: Context, outDir: File): JSONObject =
        LabWaveCProbes.withWatchdogAndLogs(context, outDir) { _, _, sampleNow ->
            val manager = context.getSystemService(CameraManager::class.java)
            val directMatrix = JSONArray()

            data class DirectSpec(
                val tag: String,
                val cameraId: String,
                val size: Size,
                val fps: Int,
                val smoothColor: Boolean = false,
                val wb5600: Boolean = false,
                val manualShutterIso: Boolean = false,
                val apertureF16: Boolean = false,
                val oisOff: Boolean = false,
            )

            val matrixSpecs = listOf(
                DirectSpec("17d_cam0_4k24", "0", Size(3840, 2160), 24),
                DirectSpec("17d_cam0_4k30", "0", Size(3840, 2160), 30),
                DirectSpec("17d_cam0_4k60", "0", Size(3840, 2160), 60),
                DirectSpec("17d_cam0_1080p60", "0", Size(1920, 1080), 60),
                DirectSpec("17d_cam0_4k30_manual_smooth_oisoff", "0", Size(3840, 2160), 30,
                    smoothColor = true, wb5600 = true, manualShutterIso = true, apertureF16 = true, oisOff = true),
                DirectSpec("17d_cam4_uw_4k30", "4", Size(3840, 2160), 30),
                DirectSpec("17d_cam4_uw_4k60", "4", Size(3840, 2160), 60),
                DirectSpec("17d_cam5_tele_4k30", "5", Size(3840, 2160), 30),
                DirectSpec("17d_cam5_tele_4k60", "5", Size(3840, 2160), 60),
                DirectSpec("17d_cam2_wide_4k30", "2", Size(3840, 2160), 30),
            )

            for (spec in matrixSpecs) {
                LabWaveCProbes.waitNaturalThermalCool(context, outDir)
                Thread.sleep(250L)
                val outFile = File(outDir, "${spec.tag}.mp4")
                val caps = manager.getCameraCharacteristics(spec.cameraId)
                directMatrix.put(runCatching {
                    runDirect10BitCapture(
                        context = context, manager = manager, cameraId = spec.cameraId,
                        size = spec.size, fps = spec.fps, durationMs = 3_000L,
                        bitrate = if (spec.fps >= 60) 80_000_000 else 60_000_000,
                        iFrameInterval = 1, outFile = outFile,
                        customBuilderFn = { b ->
                            if (spec.smoothColor) HuaweiVendorControls.applySmoothColorMode(b, caps, spec.cameraId, true)
                            if (spec.wb5600) HuaweiVendorControls.applyKelvin(b, caps, spec.cameraId, 5600)
                            if (spec.manualShutterIso) {
                                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 16_666_667L)
                                b.set(CaptureRequest.SENSOR_SENSITIVITY, 200)
                            }
                            if (spec.apertureF16) setFloatVendorKey(b, "com.huawei.capture.metadata.vaManualFNum", 1.6f)
                            if (spec.oisOff) b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                        },
                    ).put("tag", spec.tag)
                }.getOrElse { json("tag" to spec.tag, "ok" to false, "error" to (it.message ?: it.toString())) })
            }

            // Hidden RAW_SENSOR (32) streaming check across cameras 0, 4, 5
            val hiddenRawAudit = JSONArray()
            val rawConfigs = listOf(
                Triple("0", Size(4000, 3000), 30),
                Triple("0", Size(4000, 3000), 24),
                Triple("4", Size(3648, 2736), 30),
                Triple("5", Size(4000, 3000), 30),
            )
            for ((camId, rawSz, fps) in rawConfigs) {
                Thread.sleep(250L)
                hiddenRawAudit.put(probeHiddenRawStreaming(context, manager, camId, rawSz, fps))
            }

            // 3-EV comparison: Camera 0 RAW_SENSOR (4000x3000) vs CUVA 0x324 @ -2, 0, +2 EV (locked ISO 200, 5600K)
            val rawVsCuva3Ev = runCatching {
                Thread.sleep(250L)
                runHiddenRaw3EvCheck(manager)
            }.getOrElse { json("ok" to false, "error" to (it.message ?: it.toString())) }

            val report = json(
                "probe" to "17D_direct10bit_matrix_and_hidden_raw",
                "directMatrix" to directMatrix,
                "hiddenRawAudit" to hiddenRawAudit,
                "hiddenRaw3Ev" to rawVsCuva3Ev,
                "thermalEnd" to sampleNow(),
            )
            LabBreakthroughProbes.saveProbeJson(outDir, "probe17d_direct_and_raw.json", report)
        }

    @SuppressLint("MissingPermission")
    private fun runDirect10BitCapture(
        context: Context,
        manager: CameraManager,
        cameraId: String,
        size: Size,
        fps: Int,
        durationMs: Long,
        bitrate: Int,
        iFrameInterval: Int,
        outFile: File,
        customBuilderFn: (CaptureRequest.Builder) -> Unit,
        releaseLease: Boolean = true,
        existingLease: CaptureOwnerAdmission.Lease? = null,
    ): JSONObject {
        val lease = existingLease ?: CaptureOwnerAdmission.processGlobal.acquire().also { it.ready().get(8, TimeUnit.SECONDS) }
        val caps = manager.getCameraCharacteristics(cameraId)
        val cuvaReqKey = LabBreakthroughProbes.findVendorRequestKey(caps, "cuvaStateVideoMode")
        val cuvaHint = LabBreakthroughProbes.vendorKeyTypeHint(cuvaReqKey)
        val hwAlgoFpsKey = LabBreakthroughProbes.findVendorResultKey(caps, "hw-algo-fps")
        val codecName = LabBreakthroughProbes.selectHevcEncoderName()
        val encFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_LEVEL, CodecProfileLevel.HEVCMainTierLevel62)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
            if (fps >= 60) {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
            }
        }
        val camThread = HandlerThread("p17d-dir-$cameraId").apply { start() }
        val camHandler = Handler(camThread.looper)
        val codec = MediaCodec.createByCodecName(codecName)
        var encSurf: Surface? = null; var muxer: MediaMuxer? = null; var pfd: ParcelFileDescriptor? = null
        var codecStarted = false; var muxerStarted = false
        val previewReader = LabBreakthroughProbes.createUsageImageReader(
            1280, 720, ImageFormat.PRIVATE, 4,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_VIDEO_ENCODE,
        )
        val previewFmt = AtomicInteger(-1)
        val previewTimestamps = mutableListOf<Long>()
        previewReader.setOnImageAvailableListener({ r ->
            runCatching {
                r.acquireLatestImage()?.use { img ->
                    synchronized(previewTimestamps) { previewTimestamps += img.timestamp }
                    if (previewFmt.get() == -1) {
                        img.hardwareBuffer?.use { hb ->
                            previewFmt.compareAndSet(-1, EglHardwareBufferBridge.describeAhb(hb)?.first ?: hb.format)
                        }
                    }
                }
            }
        }, camHandler)

        val camTimestamps = mutableListOf<Long>()
        val encOutPtsNs = mutableListOf<Long>()
        val expTimesNs = mutableListOf<Long>()
        val isos = mutableListOf<Int>()
        val hwAlgoFpsVals = mutableListOf<String>()
        val opened = CompletableFuture<CameraDevice>(); val closed = CompletableFuture<Unit>()
        val configured = CompletableFuture<CameraCaptureSession>(); val retiring = AtomicBoolean(false)
        var session: CameraCaptureSession? = null; var openSubmitted = false

        return try {
            codec.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encSurf = codec.createInputSurface()
            pfd = ParcelFileDescriptor.open(outFile, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply { setOrientationHint(90) }
            codec.start(); codecStarted = true

            val csd0Ref = AtomicReference<ByteArray?>(null)
            val outFmtStr = AtomicReference<String>("")
            val drainDone = CompletableFuture<Int>()
            Thread {
                val info = MediaCodec.BufferInfo(); var track = -1; var f = 0
                try {
                    while (true) {
                        val out = codec.dequeueOutputBuffer(info, 20_000L)
                        if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val of = codec.outputFormat
                            outFmtStr.set(of.toString())
                            of.getByteBuffer("csd-0")?.let { bb -> csd0Ref.set(ByteArray(bb.remaining()).also { bb.duplicate().get(it) }) }
                            track = muxer.addTrack(of); muxer.start(); muxerStarted = true
                        } else if (out >= 0) {
                            val ob = codec.getOutputBuffer(out)
                            if (ob != null && info.size > 0 && muxerStarted && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                ob.position(info.offset); ob.limit(info.offset + info.size)
                                synchronized(encOutPtsNs) { encOutPtsNs += info.presentationTimeUs * 1_000L }
                                muxer.writeSampleData(track, ob, info); f++
                            }
                            codec.releaseOutputBuffer(out, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                        }
                    }
                    drainDone.complete(f)
                } catch (t: Throwable) { drainDone.completeExceptionally(t) }
            }.start()

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { opened.complete(c); if (retiring.get()) c.close() }
                override fun onDisconnected(c: CameraDevice) { opened.completeExceptionally(IllegalStateException("disc")); c.close() }
                override fun onError(c: CameraDevice, e: Int) { opened.completeExceptionally(IllegalStateException("err $e")); c.close() }
                override fun onClosed(c: CameraDevice) { closed.complete(Unit) }
            }, camHandler)
            openSubmitted = true
            val cam = opened.get(6, TimeUnit.SECONDS)

            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(encSurf)
                addTarget(previewReader.surface)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                if (cuvaReqKey != null) setVendorByteOrIntKey(this, cuvaReqKey, 1, cuvaHint)
                customBuilderFn(this)
            }.build()

            cam.createCaptureSession(SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(encSurf), OutputConfiguration(previewReader.surface)),
                Executor { r -> camHandler.post(r) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { if (retiring.get()) s.close() else configured.complete(s) }
                    override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("cfg fail")); s.close() }
                },
            ).apply { sessionParameters = req })
            session = configured.get(6, TimeUnit.SECONDS)
            session.setRepeatingRequest(req, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                    res.get(CaptureResult.SENSOR_TIMESTAMP)?.let { synchronized(camTimestamps) { camTimestamps += it } }
                    res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { synchronized(expTimesNs) { expTimesNs += it } }
                    res.get(CaptureResult.SENSOR_SENSITIVITY)?.let { synchronized(isos) { isos += it } }
                    if (hwAlgoFpsVals.size < 8) LabBreakthroughProbes.readResultKeyValue(res, hwAlgoFpsKey)?.let { hwAlgoFpsVals += it }
                }
            }, camHandler)

            Thread.sleep(durationMs)
            runCatching { session.stopRepeating() }
            codec.signalEndOfInputStream()
            val encFrames = drainDone.get(8, TimeUnit.SECONDS)

            val gapThresh = (1_500_000_000L / fps.coerceAtLeast(1))
            val camM = synchronized(camTimestamps) { LabBreakthroughProbes.computeTimestampMetrics(camTimestamps.toList(), gapThresh) }
            val prvM = synchronized(previewTimestamps) { LabBreakthroughProbes.computeTimestampMetrics(previewTimestamps.toList(), gapThresh) }
            val encM = synchronized(encOutPtsNs) { LabBreakthroughProbes.computeTimestampMetrics(encOutPtsNs.toList(), gapThresh) }
            val sps = csd0Ref.get()?.let { HevcSpsInspector.inspect(it) }
            if (muxerStarted) { runCatching { muxer.stop() }; muxerStarted = false }
            val fileM = LabBreakthroughProbes.inspectWrittenMp4(outFile)
            json(
                "ok" to (encFrames > 20),
                "cameraId" to cameraId,
                "size" to size.toString(),
                "fps" to fps,
                "previewFmtHex" to previewFmt.get().takeIf { it != -1 }?.let { "0x${it.toString(16)}" },
                "camCb" to camM.toJson(),
                "previewReader" to prvM.toJson(),
                "encOut" to encM.toJson(),
                "encodedFrames" to encFrames,
                "spsBitDepth" to sps?.bitDepthLuma,
                "avgExpMs" to expTimesNs.takeIf { it.isNotEmpty() }?.let { it.average() / 1e6 },
                "maxExpMs" to expTimesNs.maxOrNull()?.let { it / 1e6 },
                "avgIso" to isos.takeIf { it.isNotEmpty() }?.average(),
                "hwAlgoFps" to hwAlgoFpsVals.distinct().toString(),
                "fileStats" to fileM,
                "clip" to outFile.name,
                "clipBytes" to outFile.length(),
            )
        } finally {
            retiring.set(true)
            val cc = closed.thenApply { Unit }
            if (releaseLease || existingLease == null) lease.releaseAfter(cc) else lease.releaseAfter(cc)
            if (openSubmitted) camHandler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } }
            else closed.complete(Unit)
            runCatching { cc.get(6, TimeUnit.SECONDS) }
            runCatching { previewReader.close() }
            if (codecStarted) runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { encSurf?.release() }
            runCatching { pfd?.close() }
            camThread.quitSafely(); runCatching { camThread.join(2_000L) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun probeHiddenRawStreaming(
        context: Context,
        manager: CameraManager,
        camId: String,
        rawSize: Size,
        fps: Int,
    ): JSONObject = runCatching {
        val caps = manager.getCameraCharacteristics(camId)
        val whiteLevel = caps.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
        val blPattern = caps.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { p ->
            IntArray(4).also { p.copyTo(it, 0) }.toList()
        }
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        lease.ready().get(8, TimeUnit.SECONDS)
        val thread = HandlerThread("p17d-raw-$camId").apply { start() }
        val handler = Handler(thread.looper)
        val rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 6)
        val previewReader = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 3)
        val camTimestamps = mutableListOf<Long>()
        val rawTimestamps = mutableListOf<Long>()
        val pixStats = AtomicReference<JSONObject?>(null)

        rawReader.setOnImageAvailableListener({ r ->
            runCatching {
                r.acquireNextImage()?.use { img ->
                    synchronized(rawTimestamps) { rawTimestamps += img.timestamp }
                    if (pixStats.get() == null && rawTimestamps.size >= 4) {
                        val pl = img.planes[0]
                        val buf = pl.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                        val cap = buf.remaining() / 2
                        val step = (cap / 2048).coerceAtLeast(1)
                        var minV = 65535; var maxV = 0; var nonZero = 0
                        val sArr = IntArray(2048)
                        for (i in 0 until 2048) {
                            val v = buf.getShort((i * step).coerceAtMost(cap - 1) * 2).toInt() and 0xFFFF
                            sArr[i] = v
                            if (v < minV) minV = v; if (v > maxV) maxV = v; if (v != 0) nonZero++
                        }
                        sArr.sort()
                        pixStats.set(json("rowStride" to pl.rowStride, "min" to minV, "p01" to sArr[20],
                            "p50" to sArr[1024], "p99" to sArr[2027], "max" to maxV, "nonZero" to nonZero))
                    }
                }
            }
        }, handler)
        previewReader.setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage()?.close() } }, handler)

        val opened = CompletableFuture<CameraDevice>(); val closed = CompletableFuture<Unit>()
        val configured = CompletableFuture<CameraCaptureSession>(); val retiring = AtomicBoolean(false)
        var session: CameraCaptureSession? = null; var openSubmitted = false
        try {
            manager.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { opened.complete(c); if (retiring.get()) c.close() }
                override fun onDisconnected(c: CameraDevice) { opened.completeExceptionally(IllegalStateException("disc")); c.close() }
                override fun onError(c: CameraDevice, e: Int) { opened.completeExceptionally(IllegalStateException("err $e")); c.close() }
                override fun onClosed(c: CameraDevice) { closed.complete(Unit) }
            }, handler)
            openSubmitted = true
            val cam = opened.get(6, TimeUnit.SECONDS)
            val targets = listOf(rawReader.surface, previewReader.surface)
            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                targets.forEach(::addTarget)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
            }.build()
            cam.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                targets.map(::OutputConfiguration), Executor { r -> handler.post(r) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { if (retiring.get()) s.close() else configured.complete(s) }
                    override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("cfg fail")); s.close() }
                }).apply { sessionParameters = req })
            session = configured.get(6, TimeUnit.SECONDS)
            session.setRepeatingRequest(req, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                    res.get(CaptureResult.SENSOR_TIMESTAMP)?.let { synchronized(camTimestamps) { camTimestamps += it } }
                }
            }, handler)
            Thread.sleep(2_500L)
            runCatching { session.stopRepeating() }
            val thresh = 1_500_000_000L / fps.coerceAtLeast(1)
            val camM = synchronized(camTimestamps) { LabBreakthroughProbes.computeTimestampMetrics(camTimestamps.toList(), thresh) }
            val rawM = synchronized(rawTimestamps) { LabBreakthroughProbes.computeTimestampMetrics(rawTimestamps.toList(), thresh) }
            json("cameraId" to camId, "rawSize" to rawSize.toString(), "targetFps" to fps,
                "ok" to (rawM.frames > 10), "whiteLevel" to whiteLevel, "blackPattern" to blPattern?.toString(),
                "camCb" to camM.toJson(), "rawReader" to rawM.toJson(), "pixelStats" to pixStats.get())
        } finally {
            retiring.set(true)
            val cc = closed.thenApply { Unit }; lease.releaseAfter(cc)
            if (openSubmitted) handler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } }
            else closed.complete(Unit)
            runCatching { cc.get(6, TimeUnit.SECONDS) }
            runCatching { rawReader.close() }; runCatching { previewReader.close() }
            thread.quitSafely(); runCatching { thread.join(2_000L) }
        }
    }.getOrElse { json("cameraId" to camId, "rawSize" to rawSize.toString(), "targetFps" to fps, "ok" to false, "error" to (it.message ?: it.toString())) }

    @SuppressLint("MissingPermission")
    private fun runHiddenRaw3EvCheck(manager: CameraManager): JSONObject {
        val caps = manager.getCameraCharacteristics("0")
        val whiteLevel = (caps.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toDouble()
        val blPattern = caps.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val blMean = if (blPattern != null) (0..3).sumOf { blPattern.getOffsetForIndex(it % 2, it / 2) } / 4.0 else 64.0
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        lease.ready().get(8, TimeUnit.SECONDS)
        val thread = HandlerThread("p17d-raw-3ev").apply { start() }
        val handler = Handler(thread.looper)
        val rawReader = ImageReader.newInstance(4000, 3000, ImageFormat.RAW_SENSOR, 2)
        val previewReader = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 2)
        previewReader.setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage()?.close() } }, handler)
        val rawFut = AtomicReference<CompletableFuture<JSONObject>?>(null)
        rawReader.setOnImageAvailableListener({ r ->
            val img = runCatching { r.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val fut = rawFut.get()
                if (fut != null && !fut.isDone) {
                    val pl = img.planes[0]
                    val buf = pl.buffer.order(ByteOrder.LITTLE_ENDIAN)
                    val cw0 = 4000 * 45 / 100; val cw1 = 4000 * 55 / 100
                    val ch0 = 3000 * 45 / 100; val ch1 = 3000 * 55 / 100
                    var sum = 0.0; var maxRaw = 0; var minRaw = 65535; var cnt = 0
                    for (yy in ch0 until ch1 step 4) {
                        val base = yy * pl.rowStride
                        for (xx in cw0 until cw1 step 4) {
                            val raw16 = buf.getShort(base + xx * 2).toInt() and 0xFFFF
                            if (raw16 > maxRaw) maxRaw = raw16
                            if (raw16 < minRaw) minRaw = raw16
                            sum += ((raw16 - blMean) / (whiteLevel - blMean).coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
                            cnt++
                        }
                    }
                    fut.complete(json("centerLinearMean" to sum / cnt.coerceAtLeast(1), "minRaw" to minRaw, "maxRaw" to maxRaw,
                        "whiteLevel" to whiteLevel, "blackLevel" to blMean))
                }
            } catch (t: Throwable) { rawFut.get()?.completeExceptionally(t) } finally { img.close() }
        }, handler)

        val opened = CompletableFuture<CameraDevice>(); val closed = CompletableFuture<Unit>()
        val configured = CompletableFuture<CameraCaptureSession>(); val retiring = AtomicBoolean(false)
        var session: CameraCaptureSession? = null; var openSubmitted = false
        return try {
            manager.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { opened.complete(c); if (retiring.get()) c.close() }
                override fun onDisconnected(c: CameraDevice) { opened.completeExceptionally(IllegalStateException("disc")); c.close() }
                override fun onError(c: CameraDevice, e: Int) { opened.completeExceptionally(IllegalStateException("err $e")); c.close() }
                override fun onClosed(c: CameraDevice) { closed.complete(Unit) }
            }, handler)
            openSubmitted = true
            val cam = opened.get(6, TimeUnit.SECONDS)
            val targets = listOf(rawReader.surface, previewReader.surface)
            val initReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { targets.forEach(::addTarget) }.build()
            cam.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                targets.map(::OutputConfiguration), Executor { r -> handler.post(r) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { if (retiring.get()) s.close() else configured.complete(s) }
                    override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("cfg fail")); s.close() }
                }).apply { sessionParameters = initReq })
            session = configured.get(6, TimeUnit.SECONDS)
            val out = JSONArray()
            for ((ev, expNs) in listOf(-2 to 4_166_667L, 0 to 16_666_667L, 2 to 66_666_667L)) {
                val f = CompletableFuture<JSONObject>()
                rawFut.set(f)
                val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(rawReader.surface); addTarget(previewReader.surface)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
                    set(CaptureRequest.SENSOR_SENSITIVITY, 200)
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    HuaweiVendorControls.applyKelvin(this, caps, "0", 5600)
                }.build()
                session.capture(req, null, handler)
                val r = runCatching { f.get(6, TimeUnit.SECONDS) }.getOrElse { json("error" to (it.message ?: it.toString())) }
                out.put(r.put("ev", ev).put("expNs", expNs))
            }
            json("ok" to true, "stops" to out)
        } finally {
            retiring.set(true)
            val cc = closed.thenApply { Unit }; lease.releaseAfter(cc)
            if (openSubmitted) handler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } }
            else closed.complete(Unit)
            runCatching { cc.get(6, TimeUnit.SECONDS) }
            runCatching { rawReader.close() }; runCatching { previewReader.close() }
            thread.quitSafely(); runCatching { thread.join(2_000L) }
        }
    }
}
