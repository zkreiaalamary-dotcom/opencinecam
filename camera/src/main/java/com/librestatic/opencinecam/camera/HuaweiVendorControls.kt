/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.os.Build

/**
 * Huawei / Kirin Camera2 vendor controls verified on Pura 80 Pro (LMR-LX9, API 31):
 * - Manual Kelvin WB uses `com.huawei.device.capabilities.sensorWbRange` + request key
 *   `com.huawei.capture.metadata.sensorWbValue` while keeping `CONTROL_AWB_MODE = AUTO`
 *   (standard `AWB_MODE_OFF` outputs solid black frames on API 31 Kirin HAL).
 * - Smooth ISP color profile (`colorMode = 2`) softens SDR tone mapping and adds punchy contrast
 *   in 10-bit CUVA HLG (`0x324`).
 */
object HuaweiVendorControls {
    const val SENSOR_WB_RANGE_KEY = "com.huawei.device.capabilities.sensorWbRange"
    const val SENSOR_WB_VALUE_KEY = "com.huawei.capture.metadata.sensorWbValue"
    const val PROFESSIONAL_MODE_KEY = "com.huawei.capture.metadata.professionalMode"
    const val COLOR_MODE_KEY = "com.huawei.capture.metadata.colorMode"
    const val SUPPORTED_COLOR_MODES_KEY = "com.huawei.device.capabilities.supportedColorModes"
    const val COLOR_MODE_SMOOTH = 2
    const val VA_MANUAL_FNUM_KEY = "com.huawei.capture.metadata.vaManualFNum"
    const val VA_INIT_FNUM_KEY = "com.huawei.capture.metadata.vaInitFNum"
    const val VA_PERFRAME_FNUM_KEY = "com.huawei.capture.metadata.vaPerframeFNum"
    const val APERTURE_VALUE_KEY = "com.huawei.capture.metadata.apertureValue"
    const val APERTURE_MODE_KEY = "com.huawei.capture.metadata.apertureMode"
    const val VA_FNUMBER_CAPABILITY_KEY = "com.huawei.device.capabilities.actuatorVAFNumberCapability"
    const val HW_VIDEO_DYNAMIC_FPS_MODE_KEY = "com.huawei.capture.metadata.hw-video-dynamic-fps-mode"
    const val HW_VIDEO_FPS_KEY = "com.huawei.capture.metadata.hw-video-fps"
    const val HW_VIDEO_REDUCTION_FPS_ESCAPE_KEY = "com.huawei.capture.metadata.hwVideoReductionFpsEscape"
    const val HW_VIDEO_AI_DENOISE_ESCAPE_KEY = "com.huawei.capture.metadata.hwVideoAiDenoiseEscape"

    fun isHuaweiDevice(): Boolean =
        Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true) ||
            Build.BRAND.equals("HUAWEI", ignoreCase = true)

    fun parseKelvinRange(raw: IntArray?): IntRange? {
        if (raw == null || raw.size < 2) return null
        val minK = raw[0]
        val maxK = raw[1]
        if (minK < 1500 || maxK <= minK || maxK > 20_000) return null
        return minK..maxK
    }

    fun kelvinRange(characteristics: CameraCharacteristics): IntRange? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val raw = VendorKeys.staticInts(characteristics, SENSOR_WB_RANGE_KEY)
        val parsed = parseKelvinRange(raw) ?: return null
        val reqKey = requestKey(characteristics, null, SENSOR_WB_VALUE_KEY, gatePresent = true, IntArray::class.java)
            ?: requestKey(characteristics, null, SENSOR_WB_VALUE_KEY, gatePresent = true, Int::class.javaObjectType)
        return if (reqKey != null) parsed else null
    }

    fun supportsSmoothColorModesRaw(supportedColorModes: ByteArray?, advertisedRequestKeys: Set<String>): Boolean =
        COLOR_MODE_KEY in advertisedRequestKeys ||
            supportedColorModes?.any { it.toInt() == COLOR_MODE_SMOOTH } == true

    fun supportsHuaweiManualExposureRaw(
        isHuawei: Boolean,
        hasManualSensorCapability: Boolean,
        aeAvailableModes: IntArray?,
        isoRange: IntRange?,
        timeRangeNs: LongRange?,
    ): Boolean {
        if (hasManualSensorCapability) return true
        if (!isHuawei) return false
        val hasAeOff = aeAvailableModes?.any { it == CaptureRequest.CONTROL_AE_MODE_OFF } == true
        val validIso = isoRange != null && isoRange.first > 0 && isoRange.last >= isoRange.first
        val validTime = timeRangeNs != null && timeRangeNs.first > 0L && timeRangeNs.last >= timeRangeNs.first
        return hasAeOff && validIso && validTime
    }

    fun supportsSmoothColorMode(characteristics: CameraCharacteristics): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val advertised = runCatching {
            characteristics.availableCaptureRequestKeys.mapTo(HashSet()) { it.name }
        }.getOrDefault(emptySet())
        val staticModes = VendorKeys.staticBytes(characteristics, SUPPORTED_COLOR_MODES_KEY)
        return supportsSmoothColorModesRaw(staticModes, advertised)
    }

    fun parseVariableApertures(raw: FloatArray?): List<Float> {
        if (raw == null) return emptyList()
        return raw.asSequence()
            .filter { it.isFinite() && it in 0.5f..32f }
            .distinct()
            .sorted()
            .toList()
    }

    fun variableApertures(characteristics: CameraCharacteristics): List<Float> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val parsed = parseVariableApertures(VendorKeys.staticFloats(characteristics, VA_FNUMBER_CAPABILITY_KEY))
        return if (parsed.size > 1) parsed else emptyList()
    }

    fun applyVariableAperture(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics?,
        cameraId: String?,
        apertureFNumber: Float?,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (apertureFNumber != null) {
            runCatching { builder.set(CaptureRequest.LENS_APERTURE, apertureFNumber) }
            setFloatOrFloatArray(builder, characteristics, cameraId, VA_MANUAL_FNUM_KEY, apertureFNumber)
            setFloatOrFloatArray(builder, characteristics, cameraId, VA_INIT_FNUM_KEY, apertureFNumber)
            setFloatOrFloatArray(builder, characteristics, cameraId, VA_PERFRAME_FNUM_KEY, apertureFNumber)
        } else if (characteristics == null || variableApertures(characteristics).isNotEmpty()) {
            setFloatOrFloatArray(builder, characteristics, cameraId, VA_MANUAL_FNUM_KEY, 0f)
            setFloatOrFloatArray(builder, characteristics, cameraId, VA_PERFRAME_FNUM_KEY, 0f)
        }
    }

    /**
     * Locks Kirin 9020 HAL cadence at 60 fps (`hw-video-dynamic-fps-mode=0`, `hw-video-fps=targetFps`,
     * `hwVideoReductionFpsEscape=1`, `hwVideoAiDenoiseEscape=1`) so CUVA HLG does not throttle frame rate.
     */
    fun applyHighFpsCadenceLock(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics?,
        cameraId: String?,
        targetFps: Int,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !isHuaweiDevice() || targetFps < 60) return
        setIntOrIntArray(builder, characteristics, cameraId, HW_VIDEO_DYNAMIC_FPS_MODE_KEY, 0)
        setIntOrIntArray(builder, characteristics, cameraId, HW_VIDEO_FPS_KEY, targetFps)
        setByteFlag(builder, characteristics, cameraId, HW_VIDEO_REDUCTION_FPS_ESCAPE_KEY, 1)
        setByteFlag(builder, characteristics, cameraId, HW_VIDEO_AI_DENOISE_ESCAPE_KEY, 1)
    }

    fun applyKelvin(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics?,
        cameraId: String?,
        kelvin: Int,
        enableProfessionalMode: Boolean = false,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (enableProfessionalMode) {
            setByteFlag(builder, characteristics, cameraId, PROFESSIONAL_MODE_KEY, 1)
        }
        setIntOrIntArray(builder, characteristics, cameraId, SENSOR_WB_VALUE_KEY, kelvin)
    }

    fun clearKelvin(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics?,
        cameraId: String?,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        // Empirically verified on Pura 80 Pro (Probe 14B): null leaves sensorWbValue latched at
        // the prior Kelvin; writing 0 immediately restores AWB gains to baseline AUTO.
        setIntOrIntArray(builder, characteristics, cameraId, SENSOR_WB_VALUE_KEY, 0)
    }

    fun requestHasKelvin(request: CaptureRequest, expectedKelvin: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val intArrMatch = runCatching {
            request.get(CaptureRequest.Key(SENSOR_WB_VALUE_KEY, IntArray::class.java))?.firstOrNull() == expectedKelvin
        }.getOrDefault(false)
        if (intArrMatch) return true
        return runCatching {
            request.get(CaptureRequest.Key(SENSOR_WB_VALUE_KEY, Int::class.javaObjectType)) == expectedKelvin
        }.getOrDefault(false)
    }

    fun resultHasKelvinOrUnreported(result: CaptureResult, expectedKelvin: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val advertised = runCatching { result.keys.firstOrNull { it.name == SENSOR_WB_VALUE_KEY } }.getOrNull() ?: return true
        val intArr = runCatching {
            @Suppress("UNCHECKED_CAST")
            result.get(advertised as CaptureResult.Key<IntArray>)?.firstOrNull()
        }.getOrNull()
        if (intArr != null) return intArr == expectedKelvin
        val singleInt = runCatching {
            @Suppress("UNCHECKED_CAST")
            result.get(advertised as CaptureResult.Key<Int>)
        }.getOrNull()
        return singleInt == null || singleInt == expectedKelvin
    }

    fun applySmoothColorMode(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics?,
        cameraId: String?,
        enabled: Boolean,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (characteristics != null && !supportsSmoothColorMode(characteristics)) return
        if (enabled) {
            setByteFlag(builder, characteristics, cameraId, COLOR_MODE_KEY, COLOR_MODE_SMOOTH)
        } else {
            val key = characteristics?.let { it.availableCaptureRequestKeys.firstOrNull { k -> k.name == COLOR_MODE_KEY } }
            if (key != null) {
                @Suppress("UNCHECKED_CAST")
                runCatching { builder.set(key as CaptureRequest.Key<Any?>, null) }
            } else {
                setByteFlag(builder, characteristics, cameraId, COLOR_MODE_KEY, 0)
            }
        }
    }

    private fun setByteFlag(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics?,
        cameraId: String?,
        name: String,
        value: Int,
    ) {
        val advertised = characteristics?.availableCaptureRequestKeys?.firstOrNull { it.name == name }
        if (advertised != null) {
            val asByteArr = runCatching {
                @Suppress("UNCHECKED_CAST")
                builder.set(advertised as CaptureRequest.Key<ByteArray>, byteArrayOf(value.toByte()))
            }
            if (asByteArr.isSuccess) return
            val asByte = runCatching {
                @Suppress("UNCHECKED_CAST")
                builder.set(advertised as CaptureRequest.Key<Byte>, value.toByte())
            }
            if (asByte.isSuccess) return
        }
        val key = characteristics?.let {
            VendorKeys.requestGated(it, cameraId, name, gatePresent = true, ByteArray::class.java)
        } ?: CaptureRequest.Key(name, ByteArray::class.java)
        val res = runCatching { builder.set(key, byteArrayOf(value.toByte())) }
        if (res.isFailure) {
            runCatching { builder.set(CaptureRequest.Key(name, Byte::class.javaObjectType), value.toByte()) }
        }
    }

    private fun setFloatOrFloatArray(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics?,
        cameraId: String?,
        name: String,
        value: Float,
    ) {
        val advertised = characteristics?.availableCaptureRequestKeys?.firstOrNull { it.name == name }
        if (advertised != null) {
            val asFloatArr = runCatching {
                @Suppress("UNCHECKED_CAST")
                builder.set(advertised as CaptureRequest.Key<FloatArray>, floatArrayOf(value))
            }
            if (asFloatArr.isSuccess) return
            val asFloat = runCatching {
                @Suppress("UNCHECKED_CAST")
                builder.set(advertised as CaptureRequest.Key<Float>, value)
            }
            if (asFloat.isSuccess) return
        }
        val keyArr = characteristics?.let {
            VendorKeys.requestGated(it, cameraId, name, gatePresent = true, FloatArray::class.java)
        } ?: CaptureRequest.Key(name, FloatArray::class.java)
        val res = runCatching { builder.set(keyArr, floatArrayOf(value)) }
        if (res.isFailure) {
            runCatching { builder.set(CaptureRequest.Key(name, Float::class.javaObjectType), value) }
        }
    }

    private fun setIntOrIntArray(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics?,
        cameraId: String?,
        name: String,
        value: Int,
    ) {
        val advertised = characteristics?.availableCaptureRequestKeys?.firstOrNull { it.name == name }
        if (advertised != null) {
            val asIntArr = runCatching {
                @Suppress("UNCHECKED_CAST")
                builder.set(advertised as CaptureRequest.Key<IntArray>, intArrayOf(value))
            }
            if (asIntArr.isSuccess) return
            val asInt = runCatching {
                @Suppress("UNCHECKED_CAST")
                builder.set(advertised as CaptureRequest.Key<Int>, value)
            }
            if (asInt.isSuccess) return
        }
        val keyArr = characteristics?.let {
            VendorKeys.requestGated(it, cameraId, name, gatePresent = true, IntArray::class.java)
        } ?: CaptureRequest.Key(name, IntArray::class.java)
        val res = runCatching { builder.set(keyArr, intArrayOf(value)) }
        if (res.isFailure) {
            runCatching { builder.set(CaptureRequest.Key(name, Int::class.javaObjectType), value) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> requestKey(
        characteristics: CameraCharacteristics,
        cameraId: String?,
        name: String,
        gatePresent: Boolean,
        type: Class<T>,
    ): CaptureRequest.Key<T>? = VendorKeys.requestGated(characteristics, cameraId, name, gatePresent, type)
}
