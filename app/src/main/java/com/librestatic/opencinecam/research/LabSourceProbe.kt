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
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
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
import java.util.concurrent.Executor
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

internal fun isHuaweiKirinDevice(
    manufacturer: String = Build.MANUFACTURER,
    hardware: String = Build.HARDWARE,
): Boolean {
    val m = manufacturer.lowercase(Locale.ROOT)
    val h = hardware.lowercase(Locale.ROOT)
    return "huawei" in m || "honor" in m || "kirin" in h || "hi" in h && h.startsWith("kirin")
}

internal fun tonemapPresetCurveQuarantineReason(
    mode: Int,
    advertisedModes: Set<Int>,
    manufacturer: String = Build.MANUFACTURER,
    hardware: String = Build.HARDWARE,
): String? {
    if (mode != CaptureRequest.TONEMAP_MODE_PRESET_CURVE) return null
    if (isHuaweiKirinDevice(manufacturer, hardware)) {
        return "huawei-hal-tonemap-preset-curve-elemSize-abort-0x45f"
    }
    if (mode !in advertisedModes) {
        return "TONEMAP_MODE_PRESET_CURVE not advertised in TONEMAP_AVAILABLE_TONE_MAP_MODES; quarantined to prevent cameraserver crash."
    }
    return null
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
        LabPath.ISP_CUVA_HDR_PROBE -> runCuvaHdrProbe(case, attempt)
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
        val advertisedToneModes = caps.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.toSet().orEmpty()
        val baselineArm = colorArms.optJSONObject(0)
        val lockedExp = baselineArm?.optLong("observedExposureTimeNs", 0L)?.takeIf { it > 0L }
        val lockedIso = baselineArm?.optInt("observedIso", 0)?.takeIf { it > 0 }
        val aeModes = caps.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.toSet().orEmpty()
        val toneRequests = listOf(
            Triple("GAMMA_2_6", CaptureRequest.TONEMAP_MODE_GAMMA_VALUE, 2.6f),
            Triple("GAMMA_2_4", CaptureRequest.TONEMAP_MODE_GAMMA_VALUE, 2.4f),
            Triple("GAMMA_2_2", CaptureRequest.TONEMAP_MODE_GAMMA_VALUE, 2.2f),
            Triple("GAMMA_2_0", CaptureRequest.TONEMAP_MODE_GAMMA_VALUE, 2.0f),
            Triple("GAMMA_1_8", CaptureRequest.TONEMAP_MODE_GAMMA_VALUE, 1.8f),
            Triple("GAMMA_1_0_LINEAR_PROBE", CaptureRequest.TONEMAP_MODE_GAMMA_VALUE, 1.0f),
            Triple("PRESET_SRGB", CaptureRequest.TONEMAP_MODE_PRESET_CURVE, 0f),
        )
        for ((name, mode, gamma) in toneRequests) {
            checkBudget()
            val quarantineReason = tonemapPresetCurveQuarantineReason(mode, advertisedToneModes)
            if (quarantineReason != null) {
                val skippedObj = json(
                    "armName" to name,
                    "requestedToneMapMode" to mode,
                    "skipped" to true,
                    "quarantineReason" to quarantineReason,
                    "skipReason" to quarantineReason,
                )
                toneArms.put(skippedObj)
                attempt.notice(json("event" to "isp_tonemap_extra_arm", "details" to skippedObj))
                continue
            }
            val armObj = try {
                captureConfiguredYuvProbe(
                    cameraId = case.camera,
                    width = case.width,
                    height = case.height,
                    fps = case.fps,
                ) { builder ->
                    if (lockedExp != null && lockedIso != null && CaptureRequest.CONTROL_AE_MODE_OFF in aeModes) {
                        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, lockedExp)
                        builder.set(CaptureRequest.SENSOR_SENSITIVITY, lockedIso)
                    }
                    builder.set(CaptureRequest.TONEMAP_MODE, mode)
                    if (mode == CaptureRequest.TONEMAP_MODE_GAMMA_VALUE) {
                        builder.set(CaptureRequest.TONEMAP_GAMMA, gamma)
                    }
                }
            } catch (t: Throwable) {
                json("errorClass" to t.javaClass.name, "error" to t.message?.take(300))
            }
            armObj.put("armName", name).put("requestedToneMapMode", mode)
            if (mode == CaptureRequest.TONEMAP_MODE_GAMMA_VALUE) {
                armObj.put("requestedGamma", gamma.toDouble())
            }
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
        useSessionParameters: Boolean = false,
        companionPrivateReader: Boolean = false,
        extraResultReader: ((TotalCaptureResult) -> Pair<String, String>?)? = null,
        configure: (CaptureRequest.Builder) -> Unit,
    ): JSONObject {
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        lease.ready().get(8, TimeUnit.SECONDS)
        val manager = context.getSystemService(CameraManager::class.java)
        val caps = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull()
        val thread = HandlerThread("occ-cc-probe").apply { start() }
        val handler = Handler(thread.looper)
        awaitCameraAvailable(manager, cameraId, handler)
        val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3)
        val privateReader = if (companionPrivateReader) ImageReader.newInstance(width, height, ImageFormat.PRIVATE, 3) else null
        val opened = CompletableFuture<CameraDevice>()
        val closed = CompletableFuture<Unit>()
        val configured = CompletableFuture<CameraCaptureSession>()
        val retiring = AtomicBoolean()
        val samples = ArrayList<IntArray>()
        val observedCc = AtomicReference<Int?>()
        val observedTm = AtomicReference<Int?>()
        val observedAeLock = AtomicReference<Boolean?>()
        val observedAwbLock = AtomicReference<Boolean?>()
        val observedExpNs = AtomicReference<Long?>()
        val observedIso = AtomicReference<Int?>()
        val extraResultVal = AtomicReference<Pair<String, String>?>()
        val privateBufInfo = AtomicReference<JSONObject?>()
        val aeLockSupported = caps?.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
        val awbLockSupported = caps?.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
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
        privateReader?.setOnImageAvailableListener({ r ->
            runCatching {
                r.acquireLatestImage()?.use { img ->
                    if (privateBufInfo.get() == null) {
                        val hb = img.hardwareBuffer
                        privateBufInfo.compareAndSet(
                            null,
                            json(
                                "imageFormat" to img.format,
                                "hwBufferFormat" to hb?.format,
                                "hwBufferUsage" to hb?.usage,
                                "dataSpace" to LabApiCompat.imageDataSpace(img),
                            ),
                        )
                        hb?.close()
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
            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(reader.surface)
                privateReader?.let { addTarget(it.surface) }
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                if (aeLockSupported) set(CaptureRequest.CONTROL_AE_LOCK, true)
                if (awbLockSupported) set(CaptureRequest.CONTROL_AWB_LOCK, true)
                configure(this)
            }.build()
            val outputs = buildList {
                add(reader.surface)
                privateReader?.let { add(it.surface) }
            }
            val sessionCb = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (retiring.get()) s.close() else configured.complete(s)
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    configured.completeExceptionally(IllegalStateException("Configure failed"))
                    s.close()
                }
            }
            if (useSessionParameters) {
                val cfg = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs.map(::OutputConfiguration),
                    Executor { handler.post(it) },
                    sessionCb,
                )
                try {
                    cfg.sessionParameters = req
                    camera.createCaptureSession(cfg)
                } catch (_: Throwable) {
                    val plainCfg = SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        outputs.map(::OutputConfiguration),
                        Executor { handler.post(it) },
                        sessionCb,
                    )
                    camera.createCaptureSession(plainCfg)
                }
            } else {
                @Suppress("DEPRECATION")
                camera.createCaptureSession(outputs, sessionCb, handler)
            }
            session = configured.get(8, TimeUnit.SECONDS)
            session.setRepeatingRequest(req, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                    observedCc.compareAndSet(null, res.get(CaptureResult.COLOR_CORRECTION_MODE))
                    observedTm.compareAndSet(null, res.get(CaptureResult.TONEMAP_MODE))
                    observedAeLock.compareAndSet(null, res.get(CaptureResult.CONTROL_AE_LOCK))
                    observedAwbLock.compareAndSet(null, res.get(CaptureResult.CONTROL_AWB_LOCK))
                    observedExpNs.compareAndSet(null, res.get(CaptureResult.SENSOR_EXPOSURE_TIME))
                    observedIso.compareAndSet(null, res.get(CaptureResult.SENSOR_SENSITIVITY))
                    extraResultReader?.invoke(res)?.let { extraResultVal.compareAndSet(null, it) }
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
            runCatching { privateReader?.close() }
            thread.quitSafely()
            runCatching { thread.join(8_000) }
        }
        val metrics = computeLumaHistogramMetrics(samples.lastOrNull() ?: IntArray(0))
        return json(
            "framesCaptured" to samples.size,
            "observedColorCorrectionMode" to observedCc.get(),
            "observedToneMapMode" to observedTm.get(),
            "aeLockSupported" to aeLockSupported,
            "awbLockSupported" to awbLockSupported,
            "observedAeLocked" to observedAeLock.get(),
            "observedAwbLocked" to observedAwbLock.get(),
            "observedExposureTimeNs" to observedExpNs.get(),
            "observedIso" to observedIso.get(),
            "privateCompanionBuffer" to privateBufInfo.get(),
            "extraResult" to extraResultVal.get()?.let { "${it.first}:${it.second}" },
            "metrics" to metrics.toJson(),
        )
    }

    @SuppressLint("MissingPermission")
    private fun runToneMapAb(case: LabCase, attempt: LabJournal.Attempt): Pair<LabVerdict, String?> {
        val armA = captureYuvBurst(case.camera, case.width, case.height, case.fps, flatToneMap = false, rolloffCurve = false)
        attempt.notice(json("event" to "isp_tonemap_arm", "arm" to "DEFAULT", "details" to armA))
        checkBudget()
        val lockedExp = armA.optLong("observedExposureTimeNs", 0L).takeIf { it > 0L }
        val lockedIso = armA.optInt("observedIso", 0).takeIf { it > 0 }
        val armB = captureYuvBurst(
            case.camera, case.width, case.height, case.fps,
            flatToneMap = true, rolloffCurve = false, lockedExpNs = lockedExp, lockedIso = lockedIso,
        )
        attempt.notice(json("event" to "isp_tonemap_arm", "arm" to "FLAT_CONTRAST_CURVE", "details" to armB))
        checkBudget()
        val armC = captureYuvBurst(
            case.camera, case.width, case.height, case.fps,
            flatToneMap = false, rolloffCurve = true, lockedExpNs = lockedExp, lockedIso = lockedIso,
        )
        attempt.notice(json("event" to "isp_tonemap_arm", "arm" to "SAFE_LINEAR_ROLLOFF", "details" to armC))
        checkBudget()
        val armD = captureYuvBurst(
            case.camera, case.width, case.height, case.fps,
            flatToneMap = false, kirinAttenuatedCurve = true, lockedExpNs = lockedExp, lockedIso = lockedIso,
        )
        attempt.notice(json("event" to "isp_tonemap_arm", "arm" to "KIRIN_ATTENUATED_ROLLOFF", "details" to armD))
        checkBudget()
        val halfExp = lockedExp?.let { (it / 2L).coerceAtLeast(100_000L) }
        val armE = captureYuvBurst(
            case.camera, case.width, case.height, case.fps,
            flatToneMap = false, rolloffCurve = true, lockedExpNs = halfExp, lockedIso = lockedIso,
        )
        attempt.notice(json("event" to "isp_tonemap_arm", "arm" to "CONTRAST_CURVE_MINUS_1EV", "details" to armE))

        val aMetrics = armA.opt("metricsObj") as? LumaHistogramMetrics
        val bMetrics = armB.opt("metricsObj") as? LumaHistogramMetrics
        val cMetrics = armC.opt("metricsObj") as? LumaHistogramMetrics
        val dMetrics = armD.opt("metricsObj") as? LumaHistogramMetrics
        val eMetrics = armE.opt("metricsObj") as? LumaHistogramMetrics
        val verdictLabel = if (aMetrics != null && bMetrics != null) {
            classifyToneMapComparison(aMetrics, bMetrics, armB.optBoolean("curveAppliedAndObserved", false))
        } else "CAPTURE_FAILED"
        val rolloffLabel = if (aMetrics != null && cMetrics != null) {
            classifyToneMapComparison(aMetrics, cMetrics, armC.optBoolean("curveAppliedAndObserved", false))
        } else "CAPTURE_FAILED"
        val kirinLabel = if (aMetrics != null && dMetrics != null) {
            classifyToneMapComparison(aMetrics, dMetrics, armD.optBoolean("curveAppliedAndObserved", false))
        } else "CAPTURE_FAILED"
        val minus1EvLabel = if (aMetrics != null && eMetrics != null) {
            classifyToneMapComparison(aMetrics, eMetrics, armE.optBoolean("curveAppliedAndObserved", false))
        } else "CAPTURE_FAILED"
        val summary = json(
            "event" to "isp_tonemap_ab_summary",
            "comparisonVerdict" to verdictLabel,
            "rolloffVerdict" to rolloffLabel,
            "kirinAttenuatedVerdict" to kirinLabel,
            "minus1EvVerdict" to minus1EvLabel,
            "exposureLockedFromArmA" to (lockedExp != null && lockedIso != null),
            "defaultArm" to armA.optJSONObject("metricsJson"),
            "flatArm" to armB.optJSONObject("metricsJson"),
            "rolloffArm" to armC.optJSONObject("metricsJson"),
            "kirinAttenuatedArm" to armD.optJSONObject("metricsJson"),
            "minus1EvArm" to armE.optJSONObject("metricsJson"),
            "flatObservedToneMapMode" to armB.opt("observedToneMapMode"),
        )
        attempt.event(LabStage.FILE, summary)
        return if (aMetrics != null && bMetrics != null) {
            LabVerdict.OBSERVED to "ISP ToneMap A..E completed: flat=$verdictLabel, minus1EV=$minus1EvLabel"
        } else {
            LabVerdict.FAILED to "ISP ToneMap A/B failed to decode YUV frames."
        }
    }

    @SuppressLint("MissingPermission")
    private fun captureYuvBurst(
        cameraId: String,
        width: Int,
        height: Int,
        fps: Int,
        flatToneMap: Boolean,
        rolloffCurve: Boolean = false,
        kirinAttenuatedCurve: Boolean = false,
        lockedExpNs: Long? = null,
        lockedIso: Int? = null,
    ): JSONObject {
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        lease.ready().get(8, TimeUnit.SECONDS)
        val manager = context.getSystemService(CameraManager::class.java)
        val caps = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull()
        val thread = HandlerThread("occ-tonemap-probe").apply { start() }
        val handler = Handler(thread.looper)
        awaitCameraAvailable(manager, cameraId, handler)
        val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 4)
        val opened = CompletableFuture<CameraDevice>()
        val closed = CompletableFuture<Unit>()
        val configured = CompletableFuture<CameraCaptureSession>()
        val retiring = AtomicBoolean()
        val samples = ArrayList<IntArray>()
        val observedToneMap = AtomicReference<Int?>()
        val observedExpNs = AtomicReference<Long?>()
        val observedIso = AtomicReference<Int?>()
        val observedAeLock = AtomicReference<Boolean?>()
        val observedAwbLock = AtomicReference<Boolean?>()
        val aeLockSupported = caps?.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
        val awbLockSupported = caps?.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
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
        var manualExposureApplied = false
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
                if (lockedExpNs != null && lockedIso != null) {
                    val aeModes = caps?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.toSet().orEmpty()
                    if (CaptureRequest.CONTROL_AE_MODE_OFF in aeModes) {
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, lockedExpNs)
                        set(CaptureRequest.SENSOR_SENSITIVITY, lockedIso)
                        manualExposureApplied = true
                    } else if (aeLockSupported) {
                        set(CaptureRequest.CONTROL_AE_LOCK, true)
                    }
                } else if (aeLockSupported) {
                    set(CaptureRequest.CONTROL_AE_LOCK, true)
                }
                if (awbLockSupported) set(CaptureRequest.CONTROL_AWB_LOCK, true)
                if (flatToneMap || rolloffCurve || kirinAttenuatedCurve) {
                    val modes = caps?.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.toSet().orEmpty()
                    val maxPts = caps?.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0
                    if (CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE in modes && maxPts >= PictureProfileCurves.MIN_POINT_COUNT) {
                        val pts = when {
                            kirinAttenuatedCurve -> kirinGammaAttenuatedRolloffCurve(minOf(32, maxPts))
                            rolloffCurve -> safeLinearRolloffCurve(minOf(32, maxPts))
                            else -> PictureProfileCurves.points(PictureProfile.FLAT, minOf(PictureProfileCurves.DEFAULT_POINT_COUNT, maxPts))
                        }
                        if (pts != null) {
                            set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
                            set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(pts, pts, pts))
                            curveApplied = true
                        }
                    }
                    if (caps?.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.contains(CaptureRequest.EDGE_MODE_OFF) == true) {
                        set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                    }
                    if (caps?.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.contains(CaptureRequest.NOISE_REDUCTION_MODE_OFF) == true) {
                        set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                    }
                }
            }.build()
            session.setRepeatingRequest(req, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                    observedToneMap.compareAndSet(null, res.get(CaptureResult.TONEMAP_MODE))
                    observedExpNs.compareAndSet(null, res.get(CaptureResult.SENSOR_EXPOSURE_TIME))
                    observedIso.compareAndSet(null, res.get(CaptureResult.SENSOR_SENSITIVITY))
                    observedAeLock.compareAndSet(null, res.get(CaptureResult.CONTROL_AE_LOCK))
                    observedAwbLock.compareAndSet(null, res.get(CaptureResult.CONTROL_AWB_LOCK))
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
            put("manualExposureApplied", manualExposureApplied)
            put("aeLockSupported", aeLockSupported)
            put("awbLockSupported", awbLockSupported)
            put("observedAeLocked", observedAeLock.get())
            put("observedAwbLocked", observedAwbLock.get())
            put("observedExposureTimeNs", observedExpNs.get())
            put("observedIso", observedIso.get())
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
        if (cameraReports.length() == 0) {
            return LabVerdict.FAILED to "No accessible cameras responded during source format probe."
        }
        return LabVerdict.OBSERVED to if (anyHighPrecision) {
            "High-precision non-YUV_420_888 source stream admitted on hardware."
        } else {
            "Source format probe completed: only public YUV_420_888 / PRIVATE admitted."
        }
    }

    @SuppressLint("MissingPermission")
    private fun runCuvaHdrProbe(case: LabCase, attempt: LabJournal.Attempt): Pair<LabVerdict, String?> {
        val manager = context.getSystemService(CameraManager::class.java)
        val caps = runCatching { manager.getCameraCharacteristics(case.camera) }.getOrNull()
            ?: return LabVerdict.FAILED to "Camera characteristics unavailable for ${case.camera}."
        val cuvaReqKey = caps.availableCaptureRequestKeys.firstOrNull {
            it.name == "com.huawei.capture.metadata.cuvaStateVideoMode"
        }
        val cuvaResKey = caps.availableCaptureResultKeys.firstOrNull {
            it.name == "com.huawei.capture.metadata.cuvaStateVideoMode"
        }
        val keyTypeName = runCatching {
            val nativeKey = cuvaReqKey?.javaClass?.getMethod("getNativeKey")?.invoke(cuvaReqKey)
            nativeKey?.javaClass?.getMethod("getType")?.invoke(nativeKey)?.toString()
        }.getOrNull() ?: "unknown"

        val baseArm = captureConfiguredYuvProbe(
            cameraId = case.camera,
            width = case.width,
            height = case.height,
            fps = case.fps,
            useSessionParameters = true,
            companionPrivateReader = true,
            extraResultReader = { res -> readVendorKeyResult(res, cuvaResKey) },
        ) { /* baseline */ }
        attempt.notice(json("event" to "cuva_hdr_arm", "arm" to "DEFAULT", "details" to baseArm))

        val lockedExp = baseArm.optLong("observedExposureTimeNs", 0L).takeIf { it > 0L }
        val lockedIso = baseArm.optInt("observedIso", 0).takeIf { it > 0 }
        val aeModes = caps.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.toSet().orEmpty()

        val stateResults = linkedMapOf<String, JSONObject>()
        var chosenKeyFormat: String? = null
        var anyAccepted = false
        for ((armName, stateVal) in listOf("CUVA_STATE_0" to 0, "CUVA_STATE_1" to 1, "CUVA_STATE_2" to 2)) {
            if (cuvaReqKey == null) break
            checkBudget()
            val captured = try {
                captureConfiguredYuvProbe(
                    cameraId = case.camera,
                    width = case.width,
                    height = case.height,
                    fps = case.fps,
                    useSessionParameters = true,
                    companionPrivateReader = (stateVal == 1),
                    extraResultReader = { res -> readVendorKeyResult(res, cuvaResKey) },
                ) { builder ->
                    if (lockedExp != null && lockedIso != null && CaptureRequest.CONTROL_AE_MODE_OFF in aeModes) {
                        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, lockedExp)
                        builder.set(CaptureRequest.SENSOR_SENSITIVITY, lockedIso)
                    }
                    chosenKeyFormat = setVendorByteOrIntKey(builder, cuvaReqKey, stateVal, keyTypeName)
                }
            } catch (t: Throwable) {
                json("framesCaptured" to 0, "errorClass" to t.javaClass.name, "error" to t.message?.take(240))
            }
            captured.put("requestedCuvaValue", stateVal)
            captured.put("kotlinKeyFormatUsed", chosenKeyFormat)
            stateResults[armName] = captured
            if (captured.optInt("framesCaptured", 0) > 0 && captured.optString("error").isBlank()) {
                anyAccepted = true
            }
            attempt.notice(json("event" to "cuva_hdr_arm", "arm" to armName, "details" to captured))
        }

        val state1Arm = stateResults["CUVA_STATE_1"] ?: stateResults.values.firstOrNull()
        val baseMean = baseArm.optJSONObject("metrics")?.optDouble("mean")
        val cuvaMean = state1Arm?.optJSONObject("metrics")?.optDouble("mean")
        val baseClip = baseArm.optJSONObject("metrics")?.optDouble("highlightClipFraction")
        val cuvaClip = state1Arm?.optJSONObject("metrics")?.optDouble("highlightClipFraction")
        val basePriv = baseArm.optJSONObject("privateCompanionBuffer")
        val cuvaPriv = state1Arm?.optJSONObject("privateCompanionBuffer")
        val dataSpaceChanged = basePriv != null && cuvaPriv != null && (
            basePriv.opt("hwBufferFormat") != cuvaPriv.opt("hwBufferFormat") ||
                basePriv.opt("dataSpace") != cuvaPriv.opt("dataSpace")
            )
        val firstErr = state1Arm?.optString("error")?.takeIf { it.isNotBlank() }

        val summary = json(
            "event" to "cuva_hdr_probe_summary",
            "cuvaKeyPresent" to (cuvaReqKey != null),
            "cuvaKeyType" to (chosenKeyFormat ?: keyTypeName),
            "cuvaAccepted" to anyAccepted,
            "dataSpaceChanged" to dataSpaceChanged,
            "baselinePrivateBuffer" to basePriv,
            "cuva1PrivateBuffer" to cuvaPriv,
            "baselineMeanLuma" to baseMean,
            "cuvaMeanLuma" to cuvaMean,
            "baselineHighlightClip" to baseClip,
            "cuvaHighlightClip" to cuvaClip,
            "error" to firstErr,
        )
        attempt.event(LabStage.FILE, summary)
        return when {
            cuvaReqKey == null -> LabVerdict.REVIEW to "cuvaStateVideoMode key not listed on camera ${case.camera}."
            anyAccepted -> LabVerdict.OBSERVED to "CUVA HDR probe ($chosenKeyFormat): accepted=true, dataSpaceChanged=$dataSpaceChanged, mean=$baseMean->$cuvaMean"
            else -> LabVerdict.MISMATCH to "CUVA HDR probe failed across states 0/1/2: $firstErr"
        }
    }

    private fun readVendorKeyResult(res: TotalCaptureResult, key: CaptureResult.Key<*>?): Pair<String, String>? {
        if (key == null) return null
        val value = runCatching {
            @Suppress("UNCHECKED_CAST")
            res.get(key as CaptureResult.Key<Any>)
        }.getOrNull() ?: return null
        val rendered = when (value) {
            is ByteArray -> value.contentToString()
            is IntArray -> value.contentToString()
            else -> value.toString()
        }
        return key.name to rendered
    }

    private fun awaitCameraAvailable(manager: CameraManager, cameraId: String, handler: Handler, timeoutMs: Long = 6_000L) {
        if (runCatching { manager.cameraIdList }.getOrNull()?.contains(cameraId) == true) return
        val ready = CompletableFuture<Unit>()
        val cb = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(id: String) {
                if (id == cameraId) ready.complete(Unit)
            }
        }
        runCatching { manager.registerAvailabilityCallback(cb, handler) }
        try {
            runCatching { ready.get(timeoutMs, TimeUnit.MILLISECONDS) }
        } finally {
            runCatching { manager.unregisterAvailabilityCallback(cb) }
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

/**
 * Generates an alternating monotonic (x, y) tone-curve with a near-linear midtone slope
 * and a logarithmic highlight shoulder rolloff (preserving highlights up to ~0.92 instead of clipping).
 */
internal fun safeLinearRolloffCurve(pointsCount: Int): FloatArray? {
    if (pointsCount < PictureProfileCurves.MIN_POINT_COUNT) return null
    val out = FloatArray(pointsCount * 2)
    for (i in 0 until pointsCount) {
        val x = i.toFloat() / (pointsCount - 1).toFloat()
        val y = if (x <= 0.45f) {
            (x * 0.90f).coerceIn(0f, 1f)
        } else {
            val t = ((x - 0.45f) / 0.55f).coerceIn(0f, 1f)
            val shoulder = kotlin.math.ln(1.0 + 4.0 * t) / kotlin.math.ln(5.0)
            (0.405f + 0.545f * shoulder.toFloat()).coerceIn(0f, 1f)
        }
        out[i * 2] = x
        out[i * 2 + 1] = y
    }
    return out
}

/**
 * Inverse-gamma attenuated monotonic curve calibrated for Kirin 9020 Camera2 HAL, whose
 * TONEMAP_CURVE input acts in post-gamma space (`y=x^1.0` clips ~75% while `gamma=2.2` preserves 0%).
 * Follows `0.88 * x^2.1` up to midtones and smoothly softens the shoulder to avoid highlight clipping.
 */
internal fun kirinGammaAttenuatedRolloffCurve(pointsCount: Int): FloatArray? {
    if (pointsCount < PictureProfileCurves.MIN_POINT_COUNT) return null
    val out = FloatArray(pointsCount * 2)
    for (i in 0 until pointsCount) {
        val x = i.toFloat() / (pointsCount - 1).toFloat()
        val curved = java.lang.Math.pow(x.toDouble(), 2.1).toFloat()
        val y = if (x <= 0.70f) {
            (0.92f * curved).coerceIn(0f, 1f)
        } else {
            val base70 = 0.92f * java.lang.Math.pow(0.70, 2.1).toFloat()
            val t = ((x - 0.70f) / 0.30f).coerceIn(0f, 1f)
            (base70 + (0.88f - base70) * (1f - (1f - t) * (1f - t))).coerceIn(0f, 1f)
        }
        out[i * 2] = x
        out[i * 2 + 1] = y
    }
    return out
}

/**
 * Sets a Huawei/vendor CaptureRequest.Key whose native array/scalar type is often ByteArray (`[B`)
 * on Kirin Camera2 HAL (`Not an array: class java.lang.Byte` observed when passing scalar Byte).
 * Tries ByteArray first unless [keyTypeHint] explicitly requests IntArray or scalar Int.
 */
internal fun setVendorByteOrIntKey(
    builder: CaptureRequest.Builder,
    key: CaptureRequest.Key<*>,
    value: Int,
    keyTypeHint: String = "unknown",
): String {
    @Suppress("UNCHECKED_CAST")
    val attempts: List<Pair<String, () -> Unit>> = when {
        keyTypeHint.contains("IntArray", ignoreCase = true) || keyTypeHint.contains("[I") -> listOf(
            "IntArray" to { builder.set(key as CaptureRequest.Key<IntArray>, intArrayOf(value)) },
            "ByteArray" to { builder.set(key as CaptureRequest.Key<ByteArray>, byteArrayOf(value.toByte())) },
            "Int" to { builder.set(key as CaptureRequest.Key<Int>, value) },
            "Byte" to { builder.set(key as CaptureRequest.Key<Byte>, value.toByte()) },
        )
        else -> listOf(
            "ByteArray" to { builder.set(key as CaptureRequest.Key<ByteArray>, byteArrayOf(value.toByte())) },
            "IntArray" to { builder.set(key as CaptureRequest.Key<IntArray>, intArrayOf(value)) },
            "Byte" to { builder.set(key as CaptureRequest.Key<Byte>, value.toByte()) },
            "Int" to { builder.set(key as CaptureRequest.Key<Int>, value) },
        )
    }
    var lastError: Throwable? = null
    for ((fmt, setter) in attempts) {
        try {
            setter()
            return fmt
        } catch (t: Throwable) {
            lastError = t
        }
    }
    throw lastError ?: IllegalArgumentException("Unable to set vendor key ${key.name}")
}
