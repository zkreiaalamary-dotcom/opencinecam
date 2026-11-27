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
 * - Smooth ISP color profile (`colorMode = 2`) reduces harsh edge/contrast processing cleanly.
 */
object HuaweiVendorControls {
    const val SENSOR_WB_RANGE_KEY = "com.huawei.device.capabilities.sensorWbRange"
    const val SENSOR_WB_VALUE_KEY = "com.huawei.capture.metadata.sensorWbValue"
    const val PROFESSIONAL_MODE_KEY = "com.huawei.capture.metadata.professionalMode"
    const val COLOR_MODE_KEY = "com.huawei.capture.metadata.colorMode"
    const val COLOR_MODE_SMOOTH = 2

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

    fun supportsSmoothColorMode(characteristics: CameraCharacteristics): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            characteristics.availableCaptureRequestKeys.any { it.name == COLOR_MODE_KEY }
        }.getOrDefault(false)
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
        if (enabled) {
            setIntOrIntArray(builder, characteristics, cameraId, COLOR_MODE_KEY, COLOR_MODE_SMOOTH)
        } else {
            val key = characteristics?.let { it.availableCaptureRequestKeys.firstOrNull { k -> k.name == COLOR_MODE_KEY } }
            if (key != null) {
                @Suppress("UNCHECKED_CAST")
                runCatching { builder.set(key as CaptureRequest.Key<Any?>, null) }
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
        runCatching { builder.set(key, byteArrayOf(value.toByte())) }
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
