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
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.TonemapCurve
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.util.Size
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.CaptureOwnerAdmission
import com.librestatic.opencinecam.camera.PictureProfile
import com.librestatic.opencinecam.camera.PictureProfileCurves
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

internal data class LumaHistogramMetrics(
    val sampleCount: Int,
    val min: Int,
    val max: Int,
    val mean: Double,
    val p01: Int,
    val p05: Int,
    val p50: Int,
    val p95: Int,
    val p99: Int,
    val shadowClipFraction: Double,
    val highlightClipFraction: Double,
    val dynamicSpread: Int,
    val distinctBins: Int,
) {
    fun toJson(): JSONObject = json(
        "sampleCount" to sampleCount,
        "min" to min,
        "max" to max,
        "mean" to mean,
        "p01" to p01,
        "p05" to p05,
        "p50" to p50,
        "p95" to p95,
        "p99" to p99,
        "shadowClipFraction" to shadowClipFraction,
        "highlightClipFraction" to highlightClipFraction,
        "dynamicSpread" to dynamicSpread,
        "distinctBins" to distinctBins,
    )
}

internal fun computeLumaHistogramMetrics(lumaBytes: IntArray): LumaHistogramMetrics {
    if (lumaBytes.isEmpty()) {
        return LumaHistogramMetrics(0, 0, 0, 0.0, 0, 0, 0, 0, 0, 0.0, 0.0, 0, 0)
    }
    val bins = IntArray(256)
    var minV = 255
    var maxV = 0
    var sum = 0L
    var shadowClip = 0
    var highlightClip = 0
    for (raw in lumaBytes) {
        val v = raw.coerceIn(0, 255)
        bins[v]++
        if (v < minV) minV = v
        if (v > maxV) maxV = v
        sum += v
        if (v <= 4) shadowClip++
        if (v >= 250) highlightClip++
    }
    fun percentile(p: Double): Int {
        val target = ((lumaBytes.size - 1) * p).toInt().coerceIn(0, lumaBytes.size - 1)
        var acc = 0
        for (i in 0..255) {
            acc += bins[i]
            if (acc > target) return i
        }
        return 255
    }
    val p01 = percentile(0.01)
    val p05 = percentile(0.05)
    val p50 = percentile(0.50)
    val p95 = percentile(0.95)
    val p99 = percentile(0.99)
    return LumaHistogramMetrics(
        sampleCount = lumaBytes.size,
        min = minV,
        max = maxV,
        mean = sum.toDouble() / lumaBytes.size,
        p01 = p01,
        p05 = p05,
        p50 = p50,
        p95 = p95,
        p99 = p99,
        shadowClipFraction = shadowClip.toDouble() / lumaBytes.size,
        highlightClipFraction = highlightClip.toDouble() / lumaBytes.size,
        dynamicSpread = (p99 - p01).coerceAtLeast(0),
        distinctBins = bins.count { it > 0 },
    )
}

internal fun classifyToneMapComparison(
    defaultMetrics: LumaHistogramMetrics,
    flatMetrics: LumaHistogramMetrics,
    flatCurveConfirmed: Boolean,
): String = when {
    !flatCurveConfirmed -> "TONEMAP_CONTRAST_CURVE_IGNORED_BY_HAL"
    flatMetrics.highlightClipFraction < defaultMetrics.highlightClipFraction - 0.005 ||
        flatMetrics.shadowClipFraction < defaultMetrics.shadowClipFraction - 0.005 -> "FLAT_REDUCED_CLIPPING"
    kotlin.math.abs(flatMetrics.p50 - defaultMetrics.p50) >= 3 ||
        kotlin.math.abs(flatMetrics.dynamicSpread - defaultMetrics.dynamicSpread) >= 3 -> "FLAT_ALTERED_RESPONSE"
    else -> "NO_MEASURABLE_DIFFERENCE"
}

internal fun filterInterestingVendorKeys(names: Collection<String>): List<String> {
    val pattern = Regex("hw|huawei|hisi|hdr|log|drc|tone|raw|10bit|bit|fps|hfr|movie|cine", RegexOption.IGNORE_CASE)
    return names.filter { !it.startsWith("android.") && pattern.containsMatchIn(it) }.distinct().sorted()
}

/**
 * Hardware probes for source precision & ISP tone mapping:
 *  - [LabPath.ISP_TONEMAP_AB]: A/B YUV_420_888 histogram comparison between default ISP tone map
 *    and explicit TONEMAP_MODE_CONTRAST_CURVE ([PictureProfile.FLAT]).
 *  - [LabPath.SOURCE_FORMAT_PROBE]: logical + physical camera inspection of RAW_SENSOR, RAW10,
 *    YCBCR_P010, YUV_420_888 admission, vendor keys, and Huawei CameraKit presence.
 */
internal class LabSourceProbe(
    private val context: Context,
    private val descriptor: Camera2CameraDescriptor,
    private val checkBudget: () -> Unit,
) {
    fun run(case: LabCase, attempt: LabJournal.Attempt): Pair<LabVerdict, String?> = when (case.path) {
        LabPath.ISP_TONEMAP_AB -> runToneMapAb(case, attempt)
        LabPath.ISP_COLOR_CORRECTION_PROBE -> runColorCorrectionProbe(case, attempt)
        LabPath.SOURCE_FORMAT_PROBE -> runSourceFormatProbe(case, attempt)
        else -> LabVerdict.FAILED to "Unsupported path for LabSourceProbe: ${case.path}"
    }

    @SuppressLint("MissingPermission")
    private fun runColorCorrectionProbe(case: LabCase, attempt: LabJournal.Attempt): Pair<LabVerdict, String?> {
        val manager = context.getSystemService(CameraManager::class.java)
        val caps = manager.getCameraCharacteristics(case.camera)
        val discovery = LabApiCompat.discoverColorCorrection(caps)
        attempt.notice(json("event" to "color_correction_discovery", "discovery" to discovery.toJson()))

        val empiricalColorModes = mutableListOf<Int>()
        val colorArms = JSONArray()
        val ccRequests = listOf(
            "FAST" to LabApiCompat.COLOR_CORRECTION_MODE_FAST,
            "HIGH_QUALITY" to LabApiCompat.COLOR_CORRECTION_MODE_HIGH_QUALITY,
            "TRANSFORM_MATRIX" to LabApiCompat.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
        )
        for ((name, mode) in ccRequests) {
            checkBudget()
            val armObj = try {
                captureConfiguredYuvProbe(
                    cameraId = case.camera,
                    width = case.width,
                    height = case.height,
                    fps = case.fps,
                ) { builder ->
                    builder.set(CaptureRequest.COLOR_CORRECTION_MODE, mode)
                    if (mode == LabApiCompat.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX) {
                        val identity = intArrayOf(1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1)
                        builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, ColorSpaceTransform(identity))
                        builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(1f, 1f, 1f, 1f))
                    }
                }
            } catch (t: Throwable) {
                json("errorClass" to t.javaClass.name, "error" to t.message?.take(300))
            }
            armObj.put("armName", name).put("requestedColorMode", mode)
            if (armObj.optInt("observedColorCorrectionMode", -1) == mode && armObj.optInt("framesCaptured") > 0) {
                empiricalColorModes += mode
            }
            colorArms.put(armObj)
            attempt.notice(json("event" to "isp_color_correction_arm", "details" to armObj))
        }

        val empiricalToneModes = mutableListOf<Int>()
        val toneArms = JSONArray()
        val toneRequests = listOf(
            "GAMMA_2_2" to CaptureRequest.TONEMAP_MODE_GAMMA_VALUE,
            "PRESET_SRGB" to CaptureRequest.TONEMAP_MODE_PRESET_CURVE,
        )
        for ((name, mode) in toneRequests) {
            checkBudget()
            val armObj = try {
                captureConfiguredYuvProbe(
                    cameraId = case.camera,
                    width = case.width,
                    height = case.height,
                    fps = case.fps,
                ) { builder ->
                    builder.set(CaptureRequest.TONEMAP_MODE, mode)
                    if (mode == CaptureRequest.TONEMAP_MODE_GAMMA_VALUE) {
                        builder.set(CaptureRequest.TONEMAP_GAMMA, 2.2f)
                    } else if (mode == CaptureRequest.TONEMAP_MODE_PRESET_CURVE) {
                        builder.set(CaptureRequest.TONEMAP_PRESET_CURVE, CaptureRequest.TONEMAP_PRESET_CURVE_SRGB)
                    }
                }
            } catch (t: Throwable) {
                json("errorClass" to t.javaClass.name, "error" to t.message?.take(300))
            }
            armObj.put("armName", name).put("requestedToneMapMode", mode)
            if (armObj.optInt("observedToneMapMode", -1) == mode && armObj.optInt("framesCaptured") > 0) {
                empiricalToneModes += mode
            }
            toneArms.put(armObj)
            attempt.notice(json("event" to "isp_tonemap_extra_arm", "details" to armObj))
        }

        val summary = json(
            "event" to "isp_color_correction_summary",
            "sdkInt" to Build.VERSION.SDK_INT,
            "discoveryInference" to discovery.inferenceStatus,
            "empiricalSupportedColorModes" to JSONArray(empiricalColorModes),
            "empiricalSupportedToneMapModes" to JSONArray(empiricalToneModes),
            "colorArms" to colorArms,
            "toneArms" to toneArms,
        )
        attempt.event(LabStage.FILE, summary)
        return LabVerdict.OBSERVED to "Empirical colorCorrection modes=$empiricalColorModes, extra toneMap modes=$empiricalToneModes (API ${Build.VERSION.SDK_INT})."
    }

    @SuppressLint("MissingPermission")
    private fun captureConfiguredYuvProbe(
        cameraId: String,
        width: Int,
        height: Int,
        fps: Int,
        configure: (CaptureRequest.Builder) -> Unit,
    ): JSONObject {
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        lease.ready().get(8, TimeUnit.SECONDS)
        val manager = context.getSystemService(CameraManager::class.java)
        val thread = HandlerThread("occ-cc-probe").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3)
        val opened = CompletableFuture<CameraDevice>()
        val closed = CompletableFuture<Unit>()
        val configured = CompletableFuture<CameraCaptureSession>()
        val retiring = AtomicBoolean()
        val samples = ArrayList<IntArray>()
        val observedCc = AtomicReference<Int?>()
        val observedTm = AtomicReference<Int?>()
        val captureDone = CompletableFuture<Unit>()

        reader.setOnImageAvailableListener({ r ->
            runCatching {
                r.acquireLatestImage()?.use { img ->
                    if (samples.size < 6) {
                        samples += downsampleYPlane(img, 160)
                        if (samples.size >= 6) captureDone.complete(Unit)
                    }
                }
            }
        }, handler)

        var session: CameraCaptureSession? = null
        var openSubmitted = false
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
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
            }, handler)
            openSubmitted = true
            val camera = opened.get(8, TimeUnit.SECONDS)
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (retiring.get()) s.close() else configured.complete(s)
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    configured.completeExceptionally(IllegalStateException("Configure failed"))
                    s.close()
                }
            }, handler)
            session = configured.get(8, TimeUnit.SECONDS)
            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                configure(this)
            }.build()
            session.setRepeatingRequest(req, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                    observedCc.compareAndSet(null, res.get(CaptureResult.COLOR_CORRECTION_MODE))
                    observedTm.compareAndSet(null, res.get(CaptureResult.TONEMAP_MODE))
                }
            }, handler)
            runCatching { captureDone.get(5, TimeUnit.SECONDS) }
            session.stopRepeating()
        } catch (t: Throwable) {
            return json("framesCaptured" to samples.size, "errorClass" to t.javaClass.name, "error" to t.toString().take(300))
        } finally {
            retiring.set(true)
            val camClosed = closed.thenApply { Unit }
            lease.releaseAfter(camClosed)
            if (openSubmitted) {
                handler.post {
                    try { session?.close() } finally {
                        if (!opened.isCompletedExceptionally) opened.getNow(null)?.close()
                    }
                }
            } else closed.complete(Unit)
            runCatching { camClosed.get(8, TimeUnit.SECONDS) }
            runCatching { reader.close() }
            thread.quitSafely()
            runCatching { thread.join(8_000) }
        }
        val metrics = computeLumaHistogramMetrics(samples.lastOrNull() ?: IntArray(0))
        return json(
            "framesCaptured" to samples.size,
            "observedColorCorrectionMode" to observedCc.get(),
            "observedToneMapMode" to observedTm.get(),
            "metrics" to metrics.toJson(),
        )
    }

    @SuppressLint("MissingPermission")
    private fun runToneMapAb(case: LabCase, attempt: LabJournal.Attempt): Pair<LabVerdict, String?> {
        val armA = captureYuvBurst(case.camera, case.width, case.height, case.fps, flatToneMap = false)
        attempt.notice(json("event" to "isp_tonemap_arm", "arm" to "DEFAULT", "details" to armA))
        checkBudget()
        val armB = captureYuvBurst(case.camera, case.width, case.height, case.fps, flatToneMap = true)
        attempt.notice(json("event" to "isp_tonemap_arm", "arm" to "FLAT_CONTRAST_CURVE", "details" to armB))

        val aMetrics = armA.opt("metricsObj") as? LumaHistogramMetrics
        val bMetrics = armB.opt("metricsObj") as? LumaHistogramMetrics
        val verdictLabel = if (aMetrics != null && bMetrics != null) {
            classifyToneMapComparison(aMetrics, bMetrics, armB.optBoolean("curveAppliedAndObserved", false))
        } else "CAPTURE_FAILED"
        val summary = json(
            "event" to "isp_tonemap_ab_summary",
            "comparisonVerdict" to verdictLabel,
            "defaultArm" to armA.optJSONObject("metricsJson"),
            "flatArm" to armB.optJSONObject("metricsJson"),
            "flatObservedToneMapMode" to armB.opt("observedToneMapMode"),
        )
        attempt.event(LabStage.FILE, summary)
        return if (aMetrics != null && bMetrics != null) {
            LabVerdict.OBSERVED to "ISP ToneMap A/B completed: $verdictLabel"
        } else {
            LabVerdict.FAILED to "ISP ToneMap A/B failed to decode YUV frames."
        }
    }

    @SuppressLint("MissingPermission")
    private fun captureYuvBurst(cameraId: String, width: Int, height: Int, fps: Int, flatToneMap: Boolean): JSONObject {
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        lease.ready().get(8, TimeUnit.SECONDS)
        val manager = context.getSystemService(CameraManager::class.java)
        val caps = manager.getCameraCharacteristics(cameraId)
        val thread = HandlerThread("occ-tonemap-probe").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 4)
        val opened = CompletableFuture<CameraDevice>()
        val closed = CompletableFuture<Unit>()
        val configured = CompletableFuture<CameraCaptureSession>()
        val retiring = AtomicBoolean()
        val samples = ArrayList<IntArray>()
        val observedToneMap = AtomicReference<Int?>()
        val captureDone = CompletableFuture<Unit>()

        reader.setOnImageAvailableListener({ r ->
            runCatching {
                r.acquireLatestImage()?.use { img ->
                    if (samples.size < 12) {
                        samples += downsampleYPlane(img, 160)
                        if (samples.size >= 12) captureDone.complete(Unit)
                    }
                }
            }
        }, handler)

        var session: CameraCaptureSession? = null
        var openSubmitted = false
        var curveApplied = false
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
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
            }, handler)
            openSubmitted = true
            val camera = opened.get(8, TimeUnit.SECONDS)
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (retiring.get()) s.close() else configured.complete(s)
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    configured.completeExceptionally(IllegalStateException("Configure failed"))
                    s.close()
                }
            }, handler)
            session = configured.get(8, TimeUnit.SECONDS)
            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                if (flatToneMap) {
                    val modes = caps.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.toSet().orEmpty()
                    val maxPts = caps.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0
                    if (CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE in modes && maxPts >= PictureProfileCurves.MIN_POINT_COUNT) {
                        val pts = PictureProfileCurves.points(PictureProfile.FLAT, minOf(PictureProfileCurves.DEFAULT_POINT_COUNT, maxPts))
                        if (pts != null) {
                            set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
                            set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(pts, pts, pts))
                            curveApplied = true
                        }
                    }
                    if (caps.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.contains(CaptureRequest.EDGE_MODE_OFF) == true) {
                        set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                    }
                    if (caps.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.contains(CaptureRequest.NOISE_REDUCTION_MODE_OFF) == true) {
                        set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                    }
                }
            }.build()
            session.setRepeatingRequest(req, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                    observedToneMap.compareAndSet(null, res.get(CaptureResult.TONEMAP_MODE))
                }
            }, handler)
            runCatching { captureDone.get(6, TimeUnit.SECONDS) }
            session.stopRepeating()
        } catch (t: Throwable) {
            return json("error" to t.toString().take(300), "curveApplied" to curveApplied)
        } finally {
            retiring.set(true)
            val camClosed = closed.thenApply { Unit }
            lease.releaseAfter(camClosed)
            if (openSubmitted) {
                handler.post {
                    try { session?.close() } finally {
                        if (!opened.isCompletedExceptionally) opened.getNow(null)?.close()
                    }
                }
            } else closed.complete(Unit)
            runCatching { camClosed.get(8, TimeUnit.SECONDS) }
            runCatching { reader.close() }
            thread.quitSafely()
            runCatching { thread.join(8_000) }
        }
        val merged = samples.lastOrNull() ?: IntArray(0)
        val metrics = computeLumaHistogramMetrics(merged)
        val curveObserved = curveApplied && observedToneMap.get() == CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE
        return JSONObject().apply {
            put("curveRequested", curveApplied)
            put("observedToneMapMode", observedToneMap.get())
            put("curveAppliedAndObserved", curveObserved)
            put("framesCaptured", samples.size)
            put("metricsJson", metrics.toJson())
            put("metricsObj", metrics)
        }
    }

    @SuppressLint("MissingPermission")
    private fun runSourceFormatProbe(case: LabCase, attempt: LabJournal.Attempt): Pair<LabVerdict, String?> {
        val manager = context.getSystemService(CameraManager::class.java)
        val physicalIds = runCatching { manager.getCameraCharacteristics(case.camera).physicalCameraIds.sorted() }
            .getOrDefault(emptyList())
        val allIds = (listOf(case.camera) + physicalIds).distinct()
        val cameraKitClasses = listOf(
            "com.huawei.camera.camerakit.CameraKit",
            "com.huawei.camera.camerakit.Mode",
            "com.huawei.camera.camerakit.ModeCharacteristics",
        ).associateWith { runCatching { Class.forName(it) }.isSuccess }

        val candidateFormats = listOf(
            "RAW_SENSOR" to ImageFormat.RAW_SENSOR,
            "RAW10" to ImageFormat.RAW10,
            "YCBCR_P010" to LabApiCompat.FORMAT_YCBCR_P010,
            "PRIVATE" to ImageFormat.PRIVATE,
            "YUV_420_888" to ImageFormat.YUV_420_888,
        )
        val cameraReports = JSONArray()
        var anyHighPrecision = false

        for (cid in allIds) {
            checkBudget()
            val cCaps = runCatching { manager.getCameraCharacteristics(cid) }.getOrNull() ?: continue
            val map = cCaps.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            val fmtResults = JSONArray()
            for ((fname, fmt) in candidateFormats) {
                val sizes = runCatching { map.getOutputSizes(fmt)?.map { it.toString() } }.getOrNull().orEmpty()
                val rawObj = json(
                    "format" to fname,
                    "code" to fmt,
                    "advertisedInStreamMap" to sizes.isNotEmpty(),
                    "sizes" to JSONArray(sizes.take(12)),
                )
                val shouldProbeEmpirically = (sizes.isNotEmpty() && fmt != ImageFormat.YUV_420_888) ||
                    (cid == case.camera && fmt == LabApiCompat.FORMAT_YCBCR_P010)
                if (shouldProbeEmpirically) {
                    val chosen = map.getOutputSizes(fmt)?.firstOrNull { it.width >= 1920 }
                        ?: map.getOutputSizes(fmt)?.firstOrNull()
                        ?: Size(1920, 1080)
                    val admission = try {
                        probeSingleFormatAdmission(cid, chosen, fmt)
                    } catch (t: Throwable) {
                        json("admitted" to false, "framesReceived" to false, "errorClass" to t.javaClass.name, "error" to t.message?.take(300))
                    }
                    rawObj.put("admission", admission)
                    if (admission.optBoolean("framesReceived", false) && fmt != ImageFormat.PRIVATE) {
                        anyHighPrecision = true
                    }
                }
                fmtResults.put(rawObj)
            }
            val (charKeys, reqKeys, resKeys) = LabApiCompat.safeCharacteristicsKeyNames(cCaps)
            val interestingVendor = filterInterestingVendorKeys(charKeys + reqKeys + resKeys)
            val colorCorrectionDiscovery = LabApiCompat.discoverColorCorrection(cCaps)
            val dynamicRangeDiscovery = LabApiCompat.discoverDynamicRange(cCaps)
            cameraReports.put(json(
                "cameraId" to cid,
                "formats" to fmtResults,
                "interestingVendorKeys" to JSONArray(interestingVendor),
                "colorCorrectionDiscovery" to colorCorrectionDiscovery.toJson(),
                "dynamicRangeDiscovery" to dynamicRangeDiscovery.toJson(),
            ))
        }

        val summary = json(
            "event" to "source_format_probe",
            "sdkInt" to Build.VERSION.SDK_INT,
            "queryApiAbsentNotProofOfAbsence" to (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU),
            "huaweiCameraKitClasses" to JSONObject(cameraKitClasses.mapValues { it.value }),
            "cameras" to cameraReports,
            "anyHighPrecisionSourceAdmitted" to anyHighPrecision,
        )
        attempt.event(LabStage.FILE, summary)
        return LabVerdict.OBSERVED to if (anyHighPrecision) {
            "High-precision non-YUV_420_888 source stream admitted on hardware."
        } else {
            "Source format probe completed: only public YUV_420_888 / PRIVATE admitted."
        }
    }

    @SuppressLint("MissingPermission")
    private fun probeSingleFormatAdmission(cameraId: String, size: Size, format: Int): JSONObject {
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        lease.ready().get(8, TimeUnit.SECONDS)
        val manager = context.getSystemService(CameraManager::class.java)
        val thread = HandlerThread("occ-fmt-probe").apply { start() }
        val handler = Handler(thread.looper)
        val reader = runCatching { ImageReader.newInstance(size.width, size.height, format, 3) }.getOrNull()
            ?: return run {
                lease.releaseAfter(CompletableFuture.completedFuture(Unit))
                thread.quitSafely()
                json("admitted" to false, "step" to "image_reader_create", "error" to "ImageReader refused format=$format $size")
            }
        val opened = CompletableFuture<CameraDevice>()
        val closed = CompletableFuture<Unit>()
        val configured = CompletableFuture<CameraCaptureSession>()
        val retiring = AtomicBoolean()
        val firstFrame = CompletableFuture<JSONObject>()
        reader.setOnImageAvailableListener({ r ->
            runCatching {
                r.acquireLatestImage()?.use { img ->
                    val hb = img.hardwareBuffer
                    val info = json(
                        "width" to img.width,
                        "height" to img.height,
                        "format" to img.format,
                        "hwBufferFormat" to hb?.format,
                        "hwBufferUsage" to hb?.usage,
                        "dataSpace" to LabApiCompat.imageDataSpace(img),
                    )
                    hb?.close()
                    firstFrame.complete(info)
                }
            }
        }, handler)

        var session: CameraCaptureSession? = null
        var openSubmitted = false
        return try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
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
            }, handler)
            openSubmitted = true
            val camera = opened.get(6, TimeUnit.SECONDS)
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (retiring.get()) s.close() else configured.complete(s)
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    configured.completeExceptionally(IllegalStateException("Configure failed"))
                    s.close()
                }
            }, handler)
            session = configured.get(6, TimeUnit.SECONDS)
            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
            }.build()
            session.setRepeatingRequest(req, null, handler)
            val frameInfo = runCatching { firstFrame.get(4, TimeUnit.SECONDS) }.getOrNull()
            session.stopRepeating()
            json(
                "admitted" to true,
                "framesReceived" to (frameInfo != null),
                "frame" to frameInfo,
            )
        } catch (t: Throwable) {
            json("admitted" to false, "framesReceived" to false, "error" to t.toString().take(300))
        } finally {
            retiring.set(true)
            val camClosed = closed.thenApply { Unit }
            lease.releaseAfter(camClosed)
            if (openSubmitted) {
                handler.post {
                    try { session?.close() } finally {
                        if (!opened.isCompletedExceptionally) opened.getNow(null)?.close()
                    }
                }
            } else closed.complete(Unit)
            runCatching { camClosed.get(6, TimeUnit.SECONDS) }
            runCatching { reader.close() }
            thread.quitSafely()
            runCatching { thread.join(6_000) }
        }
    }

    private fun downsampleYPlane(image: Image, targetW: Int): IntArray {
        val w = image.width
        val h = image.height
        val sw = targetW.coerceAtMost(w).coerceAtLeast(8)
        val sh = ((sw.toLong() * h) / w.coerceAtLeast(1)).toInt().coerceAtLeast(8)
        val yPlane = image.planes[0]
        val buf = yPlane.buffer.duplicate()
        val rs = yPlane.rowStride
        val ps = yPlane.pixelStride
        val out = IntArray(sw * sh)
        for (sy in 0 until sh) {
            val y = (sy * h) / sh
            for (sx in 0 until sw) {
                val x = (sx * w) / sw
                val idx = y * rs + x * ps
                out[sy * sw + sx] = if (idx < buf.limit()) buf.get(idx).toInt() and 0xFF else 0
            }
        }
        return out
    }
}
