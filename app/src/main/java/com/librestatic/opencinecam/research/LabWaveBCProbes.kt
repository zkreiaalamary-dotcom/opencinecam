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
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.Range
import android.util.Size
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
import com.librestatic.opencinecam.camera.OpenCineLogRecordingEvidence
import com.librestatic.opencinecam.camera.RecordingGeometryCalculator
import com.librestatic.opencinecam.camera.RecordingGeometryMode
import com.librestatic.opencinecam.camera.RecordingWhiteBalanceResult
import com.librestatic.opencinecam.camera.RecordingWhiteBalanceStatus
import com.librestatic.opencinecam.camera.WhiteBalanceSelection
import com.librestatic.opencinecam.camera.descriptor
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/**
 * Isolated Wave C / Wave B empirical probes (Probes 14A, 14B, and 15 end-to-end production test)
 * guarded by CameraHealthWatchdog, LabDiagnosticRecorder, and full-zone thermal telemetry.
 */
internal object LabWaveBCProbes {
    private const val COLOR_FORMAT_HISI_YUV420SP_10BIT = 0x7F000011

    private fun <T> withWatchdogAndLogs(
        context: Context,
        outDir: File,
        block: (LabDiagnosticRecorder, CameraHealthWatchdog, () -> JSONObject) -> T,
    ): T {
        outDir.mkdirs()
        val root = ResearchLaboratory.evidenceRoot(context)
        val rateTracker = LabThermalRateTracker()
        val recorder = LabDiagnosticRecorder(outDir).apply { start() }
        val wdExec = Executors.newSingleThreadExecutor { r -> Thread(r, "ProbeWatchdog").apply { isDaemon = true } }
        val wdThread = HandlerThread("ProbeWatchdogHandler").apply { start() }
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
        fun sampleNow(): JSONObject {
            val s = sampleThermal(context, root, includePowerProfile = false, rateTracker = rateTracker)
            recorder.recentHealthEvents().lastOrNull()?.let { /* recorded */ }
            return s.toJson()
        }
        try {
            return block(recorder, watchdog, ::sampleNow).also {
                aborted.get()?.let { ev ->
                    recorder.snapshotAnomaly("Probe health event: ${ev.issue} ${ev.detail}", outDir, null)
                }
            }
        } catch (t: Throwable) {
            val s = runCatching { sampleThermal(context, root, includePowerProfile = false, rateTracker = rateTracker) }.getOrNull()
            recorder.snapshotAnomaly("Probe exception: ${t.javaClass.simpleName}: ${t.message}", outDir, s)
            throw t
        } finally {
            runCatching { watchdog.close() }
            runCatching { wdThread.quitSafely(); wdThread.join(1_000L) }
            runCatching { wdExec.shutdownNow() }
            runCatching { recorder.close() }
        }
    }

    private fun waitNaturalThermalIfWarm(context: Context, outDir: File, maxShellC: Double = 39.5) {
        val root = ResearchLaboratory.evidenceRoot(context)
        for (i in 0 until 20) {
            val s = sampleThermal(context, root, includePowerProfile = false)
            val shell = s.shellFrameC ?: (s.batteryTempTenthsC / 10.0)
            if (shell < maxShellC && s.thermalStatus < 3) return
            if (i == 0) {
                File(outDir, "thermal_pause_log.jsonl").appendText(s.toJson().toString() + "\n")
            }
            Thread.sleep(3_000L)
        }
    }

    // =========================================================================
    // Probe 14A: Crack 0x7F000011 (COLOR_FORMAT_HISI_YUV420SP_10BIT) UV Offset / Packing
    // =========================================================================
    fun runProbe14A_Crack0x7f11UvLayout(outDir: File): JSONObject {
        outDir.mkdirs()
        val arms = JSONArray()
        arms.put(run14AEnhancedPattern("14A_offset_blocks", 1920, 1080, File(outDir, "p14a_offset_blocks.mp4"), Mode14A.OFFSET_BLOCKS))
        arms.put(run14AEnhancedPattern("14A_align16_1088_p010", 1920, 1080, File(outDir, "p14a_align16_1088.mp4"), Mode14A.ALIGN_1088))
        arms.put(run14AEnhancedPattern("14A_align64_1152_p010", 1920, 1080, File(outDir, "p14a_align64_1152.mp4"), Mode14A.ALIGN_1152))
        arms.put(run14AEnhancedPattern("14A_lsb10_p010", 1920, 1080, File(outDir, "p14a_lsb10.mp4"), Mode14A.LSB10))
        arms.put(run14AEnhancedPattern("14A_packed10_5div4", 1920, 1080, File(outDir, "p14a_packed10_5div4.mp4"), Mode14A.PACKED10_5DIV4))
        arms.put(run14AEnhancedPattern("14A_y_rowtag_uv_wh2", 1920, 1080, File(outDir, "p14a_y_rowtag_uv_wh2.mp4"), Mode14A.Y_ROWTAG_UV_WH2))

        val report = json("probe" to "14A_0x7f11_layout", "arms" to arms)
        File(outDir, "probe14a_0x7f11.json").writeText(report.toString(2))
        return report
    }

    private enum class Mode14A {
        OFFSET_BLOCKS,
        ALIGN_1088,
        ALIGN_1152,
        LSB10,
        PACKED10_5DIV4,
        Y_ROWTAG_UV_WH2,
    }

    private fun run14AEnhancedPattern(
        armName: String,
        w: Int,
        h: Int,
        outFile: File,
        mode: Mode14A,
    ): JSONObject {
        val frames = 15
        val sliceH = when (mode) {
            Mode14A.ALIGN_1088 -> 1088
            Mode14A.ALIGN_1152 -> 1152
            else -> h
        }
        val expectedBytes = when (mode) {
            Mode14A.ALIGN_1088 -> w * 1088 * 3
            Mode14A.ALIGN_1152 -> w * 1152 * 3
            Mode14A.PACKED10_5DIV4 -> (w * h * 15) / 8
            else -> w * h * 3
        }
        val codecName = LabBreakthroughProbes.selectHevcEncoderName()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h).apply {
            setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_LEVEL, CodecProfileLevel.HEVCMainTierLevel62)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FORMAT_HISI_YUV420SP_10BIT)
            setInteger(MediaFormat.KEY_BIT_RATE, 30_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, expectedBytes.coerceAtLeast(w * h * 3))
            if (mode == Mode14A.ALIGN_1088 || mode == Mode14A.ALIGN_1152) {
                setInteger(MediaFormat.KEY_STRIDE, w * 2)
                setInteger(MediaFormat.KEY_SLICE_HEIGHT, sliceH)
            }
        }
        val codec = MediaCodec.createByCodecName(codecName)
        var muxer: MediaMuxer? = null
        var started = false
        var muxerStarted = false
        val samples = AtomicInteger(0)
        var capObserved = -1
        var inputFmtCfg = ""
        var inputFmtStarted = ""
        return try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputFmtCfg = codec.inputFormat.toString()
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.start(); started = true
            inputFmtStarted = codec.inputFormat.toString()

            fun buildPayload(capacity: Int): ByteArray {
                val arr = ByteArray(maxOf(expectedBytes, capacity))
                val bb = ByteBuffer.wrap(arr).order(ByteOrder.LITTLE_ENDIAN)
                when (mode) {
                    Mode14A.OFFSET_BLOCKS -> {
                        val yBytes = w * h * 2
                        val yVal = (512 shl 6).toShort()
                        for (i in 0 until yBytes / 2) bb.putShort(i * 2, yVal)
                        val rem = (arr.size - yBytes).coerceAtLeast(0)
                        val sliceBytes = (rem / 16) and -4
                        for (k in 0 until 16) {
                            val start = yBytes + k * sliceBytes
                            val end = if (k == 15) arr.size and -4 else start + sliceBytes
                            val uShort = ((64 + k * 56).coerceIn(0, 1023) shl 6).toShort()
                            val vShort = ((960 - k * 56).coerceIn(0, 1023) shl 6).toShort()
                            var p = start
                            while (p + 3 < end) {
                                bb.putShort(p, uShort)
                                bb.putShort(p + 2, vShort)
                                p += 4
                            }
                        }
                    }
                    Mode14A.ALIGN_1088, Mode14A.ALIGN_1152 -> {
                        val yBytes = w * sliceH * 2
                        val yVal = (512 shl 6).toShort()
                        for (i in 0 until (yBytes / 2)) bb.putShort(i * 2, yVal)
                        val uVal = (256 shl 6).toShort()
                        val vVal = (768 shl 6).toShort()
                        var p = yBytes
                        while (p + 3 < arr.size) {
                            bb.putShort(p, uVal)
                            bb.putShort(p + 2, vVal)
                            p += 4
                        }
                    }
                    Mode14A.LSB10 -> {
                        val yBytes = w * h * 2
                        val yVal = 512.toShort()
                        for (i in 0 until yBytes / 2) bb.putShort(i * 2, yVal)
                        var p = yBytes
                        while (p + 3 < arr.size) {
                            bb.putShort(p, 256.toShort())
                            bb.putShort(p + 2, 768.toShort())
                            p += 4
                        }
                    }
                    Mode14A.PACKED10_5DIV4 -> {
                        // 4 x 10-bit samples into 5 bytes (40 bits, little-endian bit stream).
                        fun pack4(s0: Int, s1: Int, s2: Int, s3: Int, offset: Int) {
                            val v = (s0.toLong() and 0x3FFL) or
                                ((s1.toLong() and 0x3FFL) shl 10) or
                                ((s2.toLong() and 0x3FFL) shl 20) or
                                ((s3.toLong() and 0x3FFL) shl 30)
                            for (b in 0 until 5) {
                                if (offset + b < arr.size) arr[offset + b] = ((v ushr (b * 8)) and 0xFFL).toByte()
                            }
                        }
                        val yBytes = (w * h * 5) / 4
                        var p = 0
                        while (p + 4 < yBytes) {
                            pack4(512, 512, 512, 512, p)
                            p += 5
                        }
                        while (p + 4 < arr.size) {
                            pack4(256, 768, 256, 768, p)
                            p += 5
                        }
                    }
                    Mode14A.Y_ROWTAG_UV_WH2 -> {
                        for (yy in 0 until h) {
                            val code = (((yy * 1023) / (h - 1)) shl 6).toShort()
                            val rowBase = yy * w * 2
                            for (xx in 0 until w) bb.putShort(rowBase + xx * 2, code)
                        }
                        val uVal = (256 shl 6).toShort()
                        val vVal = (768 shl 6).toShort()
                        var p = w * h * 2
                        while (p + 3 < arr.size) {
                            bb.putShort(p, uVal)
                            bb.putShort(p + 2, vVal)
                            p += 4
                        }
                    }
                }
                return arr
            }

            var cachedPayload: ByteArray? = null
            val info = MediaCodec.BufferInfo()
            var submitted = 0; var eosQueued = false; var eosRecv = false; var track = -1
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
                            val payload = cachedPayload ?: buildPayload(buf.capacity()).also { cachedPayload = it }
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
                    val b = codec.getOutputBuffer(outIdx)
                    if (b != null && info.size > 0 && muxerStarted && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        b.position(info.offset); b.limit(info.offset + info.size)
                        muxer.writeSampleData(track, b, info); samples.incrementAndGet()
                    }
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eosRecv = true
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }
            json(
                "arm" to armName, "ok" to (samples.get() >= frames),
                "clip" to outFile.name, "clipBytes" to outFile.length(),
                "inputFmtConfigure" to inputFmtCfg, "inputFmtStarted" to inputFmtStarted,
                "expectedBytes" to expectedBytes, "inputBufferCapacity" to capObserved,
                "encodedSamples" to samples.get(),
            )
        } catch (t: Throwable) {
            json("arm" to armName, "ok" to false, "errorClass" to t.javaClass.name, "error" to (t.message ?: t.toString()).take(280))
        } finally {
            if (started) runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    // =========================================================================
    // Probe 14B: Kelvin WB Matrix (pro vs no-pro, clear-to-Auto, CUVA 0x324) + OETF EV Sweep
    // =========================================================================
    @SuppressLint("MissingPermission")
    fun runProbe14B_KelvinMatrixAndCuvaOetf(context: Context, outDir: File): JSONObject =
        withWatchdogAndLogs(context, outDir) { recorder, watchdog, sampleNow ->
            val kelvinArms = JSONArray()
            val wbSpecs = listOf(
                Triple("auto_baseline", null, false),
                Triple("kelvin_2800_no_pro", 2800, false),
                Triple("kelvin_2800_with_pro", 2800, true),
                Triple("kelvin_5600_no_pro", 5600, false),
                Triple("kelvin_5600_with_pro", 5600, true),
                Triple("kelvin_7500_no_pro", 7500, false),
                Triple("kelvin_7500_with_pro", 7500, true),
            )
            // Single persistent Camera "0" session across Kelvin arms to accurately test transitions & restore!
            kelvinArms.putAll(run14BKelvinPersistentSession(context, watchdog, wbSpecs))

            waitNaturalThermalIfWarm(context, outDir)

            // EV sweep on CUVA 0x324 via EGLImage GL readback vs SDR 0x22
            val evSweep = run14BCuvaVsSdrEvSweep(context, watchdog)

            val report = json(
                "probe" to "14B_kelvin_and_cuva_oetf",
                "thermalEnd" to sampleNow(),
                "healthEvents" to recorder.recentHealthEvents().size,
                "kelvinMatrix" to kelvinArms,
                "evSweep" to evSweep,
            )
            File(outDir, "probe14b_kelvin_cuva.json").writeText(report.toString(2))
            report
        }

    private fun JSONArray.putAll(items: List<JSONObject>) {
        items.forEach(::put)
    }

    @SuppressLint("MissingPermission")
    private fun run14BKelvinPersistentSession(
        context: Context,
        watchdog: CameraHealthWatchdog,
        wbSpecs: List<Triple<String, Int?, Boolean>>,
    ): List<JSONObject> {
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        lease.ready().get(8, TimeUnit.SECONDS)
        val manager = context.getSystemService(CameraManager::class.java)
        val caps = manager.getCameraCharacteristics("0")
        val cuvaReqKey = LabBreakthroughProbes.findVendorRequestKey(caps, "cuvaStateVideoMode")
        val cuvaHint = LabBreakthroughProbes.vendorKeyTypeHint(cuvaReqKey)
        val wbResKey = LabBreakthroughProbes.findVendorResultKey(caps, "sensorWbValue")
        val thread = HandlerThread("p14b-wb").apply { start() }
        val handler = Handler(thread.looper)
        val exec = Executor { r -> handler.post(r) }
        val yuvReader = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 3)
        val cuvaReader = LabBreakthroughProbes.createUsageImageReader(
            3840, 2160, ImageFormat.PRIVATE, 4,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_VIDEO_ENCODE,
        )
        val hwFmt = AtomicInteger(-1)
        cuvaReader.setOnImageAvailableListener({ r ->
            val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                if (hwFmt.get() == -1) {
                    img.hardwareBuffer?.use { hb ->
                        hwFmt.compareAndSet(-1, EglHardwareBufferBridge.describeAhb(hb)?.first ?: hb.format)
                    }
                }
            } finally { img.close() }
        }, handler)

        val currentArmIdx = AtomicInteger(-1)
        val armFrameCounter = AtomicInteger(0)
        val activeFuture = AtomicReference<CompletableFuture<JSONObject>?>(null)
        val lastResultJson = AtomicReference<JSONObject?>(null)
        yuvReader.setOnImageAvailableListener({ r ->
            val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val fut = activeFuture.get() ?: return@setOnImageAvailableListener
                if (fut.isDone) return@setOnImageAvailableListener
                if (armFrameCounter.incrementAndGet() >= 14) {
                    fut.complete(computeYuvStats(img))
                }
            } catch (t: Throwable) {
                activeFuture.get()?.completeExceptionally(t)
            } finally { img.close() }
        }, handler)

        val opened = CompletableFuture<CameraDevice>(); val closed = CompletableFuture<Unit>(); val configured = CompletableFuture<CameraCaptureSession>()
        val retiring = AtomicBoolean(); var session: CameraCaptureSession? = null; var openSubmitted = false
        val out = mutableListOf<JSONObject>()
        try {
            watchdog.noteAppOpening("0")
            val openTimeout = watchdog.scheduleOpenTimeout("0") { ev ->
                opened.completeExceptionally(IllegalStateException(ev.detail))
            }
            manager.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { watchdog.cancelOpenTimeout(openTimeout); opened.complete(c); if (retiring.get()) c.close() }
                override fun onDisconnected(c: CameraDevice) { watchdog.cancelOpenTimeout(openTimeout); opened.completeExceptionally(IllegalStateException("Disconnected")); c.close() }
                override fun onError(c: CameraDevice, e: Int) { watchdog.cancelOpenTimeout(openTimeout); watchdog.noteCameraError("0", e); opened.completeExceptionally(IllegalStateException("Err $e")); c.close() }
                override fun onClosed(c: CameraDevice) { closed.complete(Unit) }
            }, handler)
            openSubmitted = true
            val cam = opened.get(6, TimeUnit.SECONDS)
            val sessReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(yuvReader.surface); addTarget(cuvaReader.surface)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                if (cuvaReqKey != null) setVendorByteOrInt(this, cuvaReqKey, 1, cuvaHint)
            }.build()
            cam.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(yuvReader.surface), OutputConfiguration(cuvaReader.surface)),
                    exec,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { if (retiring.get()) s.close() else configured.complete(s) }
                        override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("Configure failed")); s.close() }
                    },
                ).apply { sessionParameters = sessReq },
            )
            session = configured.get(6, TimeUnit.SECONDS)

            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                    if (armFrameCounter.get() >= 10) {
                        val gains = res.get(CaptureResult.COLOR_CORRECTION_GAINS)
                        lastResultJson.set(json(
                            "awbMode" to res.get(CaptureResult.CONTROL_AWB_MODE),
                            "ccMode" to res.get(CaptureResult.COLOR_CORRECTION_MODE),
                            "sensorWbValue" to wbResKey?.let { LabBreakthroughProbes.readResultKeyValue(res, it) },
                            "ccGains" to gains?.let { floatArrayOf(it.red, it.greenEven, it.greenOdd, it.blue).joinToString(",", "[", "]") },
                            "expNs" to res.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                            "iso" to res.get(CaptureResult.SENSOR_SENSITIVITY),
                        ))
                    }
                }
            }

            fun runPhase(name: String, configureFn: (CaptureRequest.Builder) -> Unit): JSONObject {
                val fut = CompletableFuture<JSONObject>()
                hwFmt.set(-1)
                lastResultJson.set(null)
                armFrameCounter.set(0)
                activeFuture.set(fut)
                currentArmIdx.incrementAndGet()
                val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(yuvReader.surface); addTarget(cuvaReader.surface)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                    if (cuvaReqKey != null) setVendorByteOrInt(this, cuvaReqKey, 1, cuvaHint)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, 10_000_000L)
                    set(CaptureRequest.SENSOR_SENSITIVITY, 400)
                    configureFn(this)
                }
                session.setRepeatingRequest(b.build(), callback, handler)
                val yuv = fut.get(6, TimeUnit.SECONDS)
                activeFuture.set(null)
                return json(
                    "arm" to name,
                    "ok" to true,
                    "hwFmtHex" to hwFmt.get().takeIf { it != -1 }?.let { "0x${it.toString(16)}" },
                    "yuv" to yuv,
                    "result" to lastResultJson.get(),
                )
            }

            for ((label, kelvin, pro) in wbSpecs) {
                out += runPhase(label) { b ->
                    b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    if (kelvin == null) {
                        HuaweiVendorControls.clearKelvin(b, caps, "0")
                    } else {
                        HuaweiVendorControls.applyKelvin(b, caps, "0", kelvin, enableProfessionalMode = pro)
                    }
                }
            }
            out += runPhase("restore_auto_null_after_7500") { b ->
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                HuaweiVendorControls.clearKelvin(b, caps, "0")
            }
            out += runPhase("restore_auto_zero_after_7500") { b ->
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                HuaweiVendorControls.applyKelvin(b, caps, "0", 0, enableProfessionalMode = false)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    runCatching { b.set(CaptureRequest.Key(HuaweiVendorControls.PROFESSIONAL_MODE_KEY, ByteArray::class.java), byteArrayOf(0)) }
                }
            }
            out += runPhase("cuva_plus_colorMode2_smooth") { b ->
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                HuaweiVendorControls.clearKelvin(b, caps, "0")
                HuaweiVendorControls.applySmoothColorMode(b, caps, "0", enabled = true)
            }
            runCatching { session.stopRepeating() }
        } finally {
            retiring.set(true)
            val cc = closed.thenApply { Unit }; lease.releaseAfter(cc)
            if (openSubmitted) handler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } } else closed.complete(Unit)
            runCatching { cc.get(6, TimeUnit.SECONDS) }
            runCatching { yuvReader.close() }; runCatching { cuvaReader.close() }
            thread.quitSafely(); runCatching { thread.join(4_000L) }
        }
        return out
    }

    @SuppressLint("MissingPermission")
    private fun run14BCuvaVsSdrEvSweep(context: Context, watchdog: CameraHealthWatchdog): JSONObject {
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        lease.ready().get(8, TimeUnit.SECONDS)
        val manager = context.getSystemService(CameraManager::class.java)
        val caps = manager.getCameraCharacteristics("0")
        val cuvaReqKey = LabBreakthroughProbes.findVendorRequestKey(caps, "cuvaStateVideoMode")
        val cuvaHint = LabBreakthroughProbes.vendorKeyTypeHint(cuvaReqKey)
        val glThread = HandlerThread("p14b-ev-gl").apply { start() }
        val glHandler = Handler(glThread.looper)
        val camThread = HandlerThread("p14b-ev-cam").apply { start() }
        val camHandler = Handler(camThread.looper)
        val exec = Executor { r -> camHandler.post(r) }
        val reader = LabBreakthroughProbes.createUsageImageReader(
            3840, 2160, ImageFormat.PRIVATE, 4,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_VIDEO_ENCODE,
        )
        var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY; var ctx: EGLContext = EGL14.EGL_NO_CONTEXT; var pbuf: EGLSurface = EGL14.EGL_NO_SURFACE
        var tex = 0; var prog = 0
        val out = JSONArray()
        val hwFmt = AtomicInteger(-1)
        val targetFrameCount = AtomicInteger(0)
        val sampleFut = AtomicReference<CompletableFuture<JSONObject>?>(null)

        val fragCopy = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTexture;
in vec2 vTexCoord;
out vec4 outColor;
void main() { outColor = texture(uTexture, vTexCoord); }"""

        try {
            val initFut = CompletableFuture<Unit>()
            lateinit var qb: java.nio.FloatBuffer
            glHandler.post {
                try {
                    dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                    val v = IntArray(2); check(EGL14.eglInitialize(dpy, v, 0, v, 1))
                    val cfgs = arrayOfNulls<EGLConfig>(1); val cnt = IntArray(1)
                    EGL14.eglChooseConfig(dpy, intArrayOf(
                        EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                        EGL14.EGL_RENDERABLE_TYPE, 0x40, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE,
                    ), 0, cfgs, 0, 1, cnt, 0)
                    ctx = EGL14.eglCreateContext(dpy, requireNotNull(cfgs[0]), EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
                    pbuf = EGL14.eglCreatePbufferSurface(dpy, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 160, EGL14.EGL_HEIGHT, 90, EGL14.EGL_NONE), 0)
                    check(EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx))
                    val t = IntArray(1); GLES30.glGenTextures(1, t, 0); tex = t[0]
                    GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
                    GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                    val vs = LabBreakthroughProbes.compileGlProgram(
                        """#version 300 es
layout(location=0) in vec2 aPosition; out vec2 vTexCoord;
void main(){ gl_Position=vec4(aPosition,0.0,1.0); vTexCoord=vec2((aPosition.x+1.0)*0.5, 1.0-(aPosition.y+1.0)*0.5); }""",
                        fragCopy,
                    )
                    prog = vs
                    val qData = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
                    qb = ByteBuffer.allocateDirect(qData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(qData).position(0) }
                    initFut.complete(Unit)
                } catch (t: Throwable) { initFut.completeExceptionally(t) }
            }
            initFut.get(5, TimeUnit.SECONDS)

            val rgbaBuf = ByteBuffer.allocateDirect(160 * 90 * 4).order(ByteOrder.nativeOrder())
            reader.setOnImageAvailableListener({ r ->
                val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                val hb = runCatching { img.hardwareBuffer }.getOrNull()
                if (hb != null && hwFmt.get() == -1) hwFmt.compareAndSet(-1, EglHardwareBufferBridge.describeAhb(hb)?.first ?: hb.format)
                var eglImg = 0L
                try {
                    val fut = sampleFut.get() ?: return@setOnImageAvailableListener
                    if (hb == null || fut.isDone || targetFrameCount.incrementAndGet() < 12) return@setOnImageAvailableListener
                    eglImg = EglHardwareBufferBridge.createEglImage(hb)
                    if (eglImg == 0L) return@setOnImageAvailableListener
                    EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx)
                    GLES30.glViewport(0, 0, 160, 90); GLES30.glUseProgram(prog)
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                    GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
                    check(EglHardwareBufferBridge.bindExternalTexture(eglImg) == 0)
                    GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uTexture"), 0)
                    qb.position(0); GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, qb)
                    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                    rgbaBuf.clear()
                    GLES30.glReadPixels(0, 0, 160, 90, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rgbaBuf)
                    // Average center 40x30 patch (cols 60..100, rows 30..60)
                    var rS = 0.0; var gS = 0.0; var bS = 0.0; var cntPx = 0
                    for (yy in 30 until 60) for (xx in 60 until 100) {
                        val off = (yy * 160 + xx) * 4
                        rS += (rgbaBuf.get(off).toInt() and 0xFF) / 255.0
                        gS += (rgbaBuf.get(off + 1).toInt() and 0xFF) / 255.0
                        bS += (rgbaBuf.get(off + 2).toInt() and 0xFF) / 255.0
                        cntPx++
                    }
                    fut.complete(json(
                        "centerR" to rS / cntPx,
                        "centerG" to gS / cntPx,
                        "centerB" to bS / cntPx,
                        "centerLumaRec709" to (0.2126 * rS + 0.7152 * gS + 0.0722 * bS) / cntPx,
                    ))
                } catch (t: Throwable) {
                    sampleFut.get()?.completeExceptionally(t)
                } finally {
                    GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
                    if (eglImg != 0L) EglHardwareBufferBridge.destroyEglImage(eglImg)
                    runCatching { hb?.close() }
                    img.close()
                }
            }, glHandler)

            for (cuvaOn in listOf(false, true)) {
                val opened = CompletableFuture<CameraDevice>(); val closed = CompletableFuture<Unit>(); val configured = CompletableFuture<CameraCaptureSession>()
                val retiring = AtomicBoolean(); var session: CameraCaptureSession? = null; var openSubmitted = false
                try {
                    watchdog.noteAppOpening("0")
                    val ot = watchdog.scheduleOpenTimeout("0") { ev -> opened.completeExceptionally(IllegalStateException(ev.detail)) }
                    manager.openCamera("0", object : CameraDevice.StateCallback() {
                        override fun onOpened(c: CameraDevice) { watchdog.cancelOpenTimeout(ot); opened.complete(c); if (retiring.get()) c.close() }
                        override fun onDisconnected(c: CameraDevice) { watchdog.cancelOpenTimeout(ot); opened.completeExceptionally(IllegalStateException("Disconnected")); c.close() }
                        override fun onError(c: CameraDevice, e: Int) { watchdog.cancelOpenTimeout(ot); watchdog.noteCameraError("0", e); opened.completeExceptionally(IllegalStateException("Err $e")); c.close() }
                        override fun onClosed(c: CameraDevice) { closed.complete(Unit) }
                    }, camHandler)
                    openSubmitted = true
                    val cam = opened.get(6, TimeUnit.SECONDS)
                    val sessReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(reader.surface)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                        if (cuvaOn && cuvaReqKey != null) setVendorByteOrInt(this, cuvaReqKey, 1, cuvaHint)
                    }.build()
                    cam.createCaptureSession(
                        SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR,
                            listOf(OutputConfiguration(reader.surface)),
                            exec,
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) { if (retiring.get()) s.close() else configured.complete(s) }
                                override fun onConfigureFailed(s: CameraCaptureSession) { configured.completeExceptionally(IllegalStateException("Cfg fail")); s.close() }
                            },
                        ).apply { if (cuvaOn) sessionParameters = sessReq },
                    )
                    session = configured.get(6, TimeUnit.SECONDS)
                    for ((evIdx, expNs) in listOf(
                        -3 to 1_250_000L,
                        -2 to 2_500_000L,
                        -1 to 5_000_000L,
                        0 to 10_000_000L,
                        1 to 20_000_000L,
                        2 to 33_000_000L,
                    )) {
                        val fut = CompletableFuture<JSONObject>()
                        hwFmt.set(-1)
                        targetFrameCount.set(0)
                        sampleFut.set(fut)
                        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(reader.surface)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                            if (cuvaOn && cuvaReqKey != null) setVendorByteOrInt(this, cuvaReqKey, 1, cuvaHint)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                            set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
                            set(CaptureRequest.SENSOR_SENSITIVITY, 200)
                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                            HuaweiVendorControls.applyKelvin(this, caps, "0", 5600, enableProfessionalMode = false)
                        }.build()
                        session.setRepeatingRequest(req, null, camHandler)
                        val st = fut.get(6, TimeUnit.SECONDS)
                        sampleFut.set(null)
                        out.put(json(
                            "cuvaOn" to cuvaOn,
                            "ev" to evIdx,
                            "expNs" to expNs,
                            "hwFmtHex" to hwFmt.get().takeIf { it != -1 }?.let { "0x${it.toString(16)}" },
                            "rgb" to st,
                        ))
                    }
                    runCatching { session.stopRepeating() }
                } finally {
                    retiring.set(true)
                    if (openSubmitted) camHandler.post { try { session?.close() } finally { if (!opened.isCompletedExceptionally) opened.getNow(null)?.close() } } else closed.complete(Unit)
                    runCatching { closed.get(6, TimeUnit.SECONDS) }
                }
            }
        } finally {
            lease.releaseAfter(CompletableFuture.completedFuture(Unit))
            runCatching { reader.close() }
            val glDone = CompletableFuture<Unit>()
            glHandler.post {
                runCatching {
                    if (prog != 0) GLES30.glDeleteProgram(prog)
                    if (tex != 0) GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
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
            runCatching { camThread.join(3_000L) }; runCatching { glThread.join(3_000L) }
        }
        return json("sweep" to out)
    }

    // =========================================================================
    // Probe 15: End-to-End Production Engine Verification (Camera2PreviewEngine)
    // =========================================================================
    @SuppressLint("MissingPermission")
    fun runProbe15_ProductionEndToEndVerification(context: Context, outDir: File): JSONObject =
        withWatchdogAndLogs(context, outDir) { recorder, watchdog, sampleNow ->
            waitNaturalThermalIfWarm(context, outDir)
            val engine = Camera2PreviewEngine(context, deviceQuirks = DeviceQuirksProvider.lazy(context))
            val auditReport = JSONObject()
            val clips = JSONArray()
            val wbClips = JSONArray()

            // Offscreen ImageReader consuming preview swaps at zero cost
            val drainThread = HandlerThread("p15-preview-drain").apply { start() }
            val drainHandler = Handler(drainThread.looper)
            val previewSink = ImageReader.newInstance(1920, 1080, ImageFormat.PRIVATE, 4).apply {
                setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage()?.close() } }, drainHandler)
            }

            try {
                val descriptors = engine.descriptors(1920, 1080)
                val descSummary = JSONArray()
                for (d in descriptors) {
                    val lockCandidates = engine.lensLockCandidates(d.cameraId)
                    val logSummaries = d.logProfiles.map { "${it.size.width}x${it.size.height}@${it.fps}${if (it.huaweiCuvaHdr) "[CUVA]" else ""}" }
                    descSummary.put(json(
                        "cameraId" to d.cameraId,
                        "kelvinRange" to d.kelvinRange?.toString(),
                        "huaweiVendorKelvinWb" to d.huaweiVendorKelvinWb,
                        "huaweiSmoothColorModeSupported" to d.huaweiSmoothColorModeSupported,
                        "logProfiles" to JSONArray(logSummaries),
                        "lensLockCandidates" to JSONArray(lockCandidates.map { "id=${it.physicalId}@${it.anchorRatio}x" }),
                    ))
                }
                auditReport.put("logicalDescriptors", descSummary)
                val physSummary = JSONArray()
                for (pid in listOf("2", "4", "5")) {
                    val pd = engine.descriptor(pid, 1920, 1080)
                    physSummary.put(json(
                        "physicalId" to pid,
                        "present" to (pd != null),
                        "kelvinRange" to pd?.kelvinRange?.toString(),
                        "huaweiVendorKelvinWb" to (pd?.huaweiVendorKelvinWb == true),
                        "logProfiles" to JSONArray(pd?.logProfiles.orEmpty().map { "${it.size.width}x${it.size.height}@${it.fps}${if (it.huaweiCuvaHdr) "[CUVA]" else ""}" }),
                    ))
                }
                auditReport.put("physicalDescriptors", physSummary)

                val back = descriptors.first { it.cameraId == "0" }
                val lockByPhys = engine.lensLockCandidates("0").associateBy { it.physicalId }

                // Helper to run one real production OCLog2 / HLG take through Camera2PreviewEngine
                fun runEngineTake(
                    tag: String,
                    logical: Camera2CameraDescriptor,
                    targetSize: Size,
                    targetFps: Int,
                    physicalId: String? = null,
                    hlgOutput: Boolean = false,
                    durationMs: Long = 4_000L,
                    wb: WhiteBalanceSelection = WhiteBalanceSelection.Auto,
                    useWbPrepare: Boolean = false,
                    bitrate: Int? = null,
                ): JSONObject {
                    waitNaturalThermalIfWarm(context, outDir)
                    val lockBinding = physicalId?.let { pid ->
                        lockByPhys[pid]?.let { LensLockBinding(logical.cameraId, pid, it.anchorRatio) }
                            ?: error("No lensLockCandidate for physicalId=$pid")
                    }
                    val targetDesc = if (physicalId != null) {
                        engine.descriptor(physicalId, 1920, 1080)
                            ?: error("No descriptor for physical $physicalId")
                    } else logical
                    val profile: Camera2LogProfile = targetDesc.logProfiles.firstOrNull {
                        !it.constrainedHighSpeed && it.size == targetSize && it.fps == targetFps
                    } ?: return json("tag" to tag, "ok" to false, "error" to "Profile ${targetSize}@$targetFps missing on cam=${targetDesc.cameraId}")

                    val previewReady = CompletableFuture<Unit>()
                    val firstRecFrame = CompletableFuture<Long>()
                    val stoppedFut = CompletableFuture<Boolean>()
                    val failureRef = AtomicReference<String?>(null)

                    val listener = object : Camera2PreviewListener {
                        override fun onOpening(descriptor: Camera2CameraDescriptor) = Unit
                        override fun onPreviewStarted(descriptor: Camera2CameraDescriptor) { previewReady.complete(Unit) }
                        override fun onMetadata(metadata: Camera2PreviewMetadata) = Unit
                        override fun onJpegCaptured(bytes: ByteArray, width: Int, height: Int) = Unit
                        override fun onDngCaptured(bytes: ByteArray, width: Int, height: Int) = Unit
                        override fun onAnalysis(analysis: Camera2Analysis) = Unit
                        override fun onRecordingStarted(width: Int, height: Int) = Unit
                        override fun onRecordingFirstFrame(sensorTimestampNs: Long) { firstRecFrame.complete(sensorTimestampNs) }
                        override fun onRecordingStopped(saved: Boolean) { stoppedFut.complete(saved) }
                        override fun onFailure(code: String, message: String, recoverable: Boolean) {
                            failureRef.compareAndSet(null, "$code: $message")
                            previewReady.completeExceptionally(IllegalStateException("$code: $message"))
                            stoppedFut.complete(false)
                        }
                    }

                    engine.startPreview(
                        descriptor = logical,
                        surface = previewSink.surface,
                        displayRotationDegrees = 0,
                        listener = listener,
                        openCineLog = true,
                        viewAssist = false,
                        targetFps = targetFps,
                        logProfile = profile,
                        lensLock = lockBinding,
                    )
                    previewReady.get(8, TimeUnit.SECONDS)
                    engine.setWhiteBalance(wb)
                    Thread.sleep(400L)

                    var wbStatus: String? = null
                    if (useWbPrepare && wb != WhiteBalanceSelection.Auto) {
                        val wbFut = CompletableFuture<RecordingWhiteBalanceResult>()
                        engine.prepareRecordingWhiteBalance { wbFut.complete(it) }
                        val res = wbFut.get(5, TimeUnit.SECONDS)
                        wbStatus = "${res.status}:${res.failure ?: "ok"}"
                        val expectedOk = if (wb is WhiteBalanceSelection.Kelvin) {
                            res.status == RecordingWhiteBalanceStatus.FIXED
                        } else {
                            res.status == RecordingWhiteBalanceStatus.FIXED || res.status == RecordingWhiteBalanceStatus.LOCKED
                        }
                        check(expectedOk) { "WB prepare failed for $wb: $res" }
                    }

                    val outFile = File(outDir, "$tag.mp4")
                    val pfd = ParcelFileDescriptor.open(
                        outFile,
                        ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE,
                    )
                    val geom = RecordingGeometryCalculator.calculate(
                        sourceSize = profile.size,
                        sensorOrientationDegrees = targetDesc.sensorOrientation,
                        deviceOrientationDegrees = 0,
                        lensFacing = targetDesc.lensFacing,
                        mode = RecordingGeometryMode.COMPATIBLE,
                    )
                    val effBitrate = bitrate ?: if (targetFps >= 60 || profile.size.width > 3840) 90_000_000 else 50_000_000
                    val accepted = try {
                        engine.startOpenCineLogVideo(
                            output = pfd,
                            recordingGeometry = geom,
                            videoBitrate = effBitrate,
                            hlgOutput = hlgOutput,
                        )
                    } finally {
                        runCatching { pfd.close() }
                    }
                    check(accepted) { "startOpenCineLogVideo returned false for $tag (${failureRef.get()})" }
                    firstRecFrame.get(6, TimeUnit.SECONDS)
                    Thread.sleep(durationMs)
                    check(engine.stopVideo()) { "stopVideo returned false for $tag" }
                    val saved = stoppedFut.get(8, TimeUnit.SECONDS)
                    engine.recordingOutputRetirement().get(8, TimeUnit.SECONDS)
                    val ev: OpenCineLogRecordingEvidence? = engine.consumeLastOpenCineLogEvidence()
                    val hwFmtHex = engine.activeZeroCopyHardwareFormat()?.let { "0x${it.toString(16)}" }
                    if (useWbPrepare) engine.releaseRecordingWhiteBalance()
                    engine.stopPreview()
                    Thread.sleep(250L)

                    return json(
                        "tag" to tag,
                        "ok" to (saved && ev != null && failureRef.get() == null),
                        "clip" to outFile.name,
                        "clipBytes" to outFile.length(),
                        "hwFmtHex" to hwFmtHex,
                        "wbStatus" to wbStatus,
                        "failure" to failureRef.get(),
                        "evidence" to ev?.let {
                            json(
                                "curve" to it.curve,
                                "codecMime" to it.codecMime,
                                "codecProfile" to it.codecProfile,
                                "codecName" to it.codecName,
                                "eglRenderTargetBits" to it.eglRenderTargetBits,
                                "targetFps" to it.targetFps,
                                "encodedFrames" to it.encodedFrames,
                                "firstPtsUs" to it.firstPtsUs,
                                "lastPtsUs" to it.lastPtsUs,
                                "maxVideoPtsGapUs" to it.maxVideoPtsGapUs,
                                "videoPtsGapsOverThreshold" to it.videoPtsGapsOverThreshold,
                                "encodedWidth" to it.encodedWidth,
                                "encodedHeight" to it.encodedHeight,
                                "containerRotationDegrees" to it.containerRotationDegrees,
                                "sourceDataSpace" to it.sourceDataSpace,
                                "sourceDataSpaceMismatchedFrames" to it.sourceDataSpaceMismatchedFrames,
                                "encoderVuiTransfer" to it.encoderVuiTransfer,
                                "containerVuiTransfer" to it.containerVuiTransfer,
                                "transformSha256" to it.transformSha256,
                            )
                        },
                    )
                }

                // Core multi-mode & multi-lens Wave B production verification
                clips.put(runEngineTake("p15_main_4k60_oclog2", back, Size(3840, 2160), 60))
                clips.put(runEngineTake("p15_main_4k30_oclog2", back, Size(3840, 2160), 30))
                clips.put(runEngineTake("p15_main_4k24_oclog2", back, Size(3840, 2160), 24))
                clips.put(runEngineTake("p15_uw_4k60_oclog2", back, Size(3840, 2160), 60, physicalId = "4"))
                clips.put(runEngineTake("p15_tele_4k60_oclog2", back, Size(3840, 2160), 60, physicalId = "5"))
                clips.put(runEngineTake("p15_tele_4k30_oclog2", back, Size(3840, 2160), 30, physicalId = "5", durationMs = 3_000L))
                clips.put(runEngineTake("p15_opengate_4096x3072_30_oclog2", back, Size(4096, 3072), 30))
                clips.put(runEngineTake("p15_main_4k30_hlg10", back, Size(3840, 2160), 30, hlgOutput = true))

                // Production manual Kelvin WB verification through real engine gate + recording
                wbClips.put(runEngineTake("p15_wb_2800k", back, Size(3840, 2160), 30, durationMs = 2_000L, wb = WhiteBalanceSelection.Kelvin(2800), useWbPrepare = true))
                wbClips.put(runEngineTake("p15_wb_7500k", back, Size(3840, 2160), 30, durationMs = 2_000L, wb = WhiteBalanceSelection.Kelvin(7500), useWbPrepare = true))
                wbClips.put(runEngineTake("p15_wb_auto", back, Size(3840, 2160), 30, durationMs = 2_000L, wb = WhiteBalanceSelection.Auto, useWbPrepare = false))
            } finally {
                runCatching { engine.closeAsync().get(8, TimeUnit.SECONDS) }
                runCatching { previewSink.close() }
                runCatching { drainThread.quitSafely(); drainThread.join(2_000L) }
            }

            val report = json(
                "probe" to "15_production_e2e",
                "audit" to auditReport,
                "clips" to clips,
                "wbClips" to wbClips,
                "thermalEnd" to sampleNow(),
                "watchdogHealthEvents" to JSONArray(recorder.recentHealthEvents().map { "${it.issue}:${it.cameraId}:${it.detail}" }),
                "engineHealthEvents" to engine.recentCameraHealthEvents().size,
            )
            File(outDir, "probe15_production.json").writeText(report.toString(2))
            report
        }

    @Suppress("UNUSED_PARAMETER")
    private fun setVendorByteOrInt(builder: CaptureRequest.Builder, key: CaptureRequest.Key<*>, value: Int, hint: String) {
        @Suppress("UNCHECKED_CAST")
        val asByteArr = runCatching { builder.set(key as CaptureRequest.Key<ByteArray>, byteArrayOf(value.toByte())) }
        if (asByteArr.isSuccess) return
        @Suppress("UNCHECKED_CAST")
        val asByte = runCatching { builder.set(key as CaptureRequest.Key<Byte>, value.toByte()) }
        if (asByte.isSuccess) return
        @Suppress("UNCHECKED_CAST")
        val asIntArr = runCatching { builder.set(key as CaptureRequest.Key<IntArray>, intArrayOf(value)) }
        if (asIntArr.isSuccess) return
        @Suppress("UNCHECKED_CAST")
        runCatching { builder.set(key as CaptureRequest.Key<Int>, value) }
    }

    private fun computeYuvStats(img: android.media.Image): JSONObject {
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
}
