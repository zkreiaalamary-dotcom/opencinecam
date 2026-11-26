/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.Image
import android.media.MediaRecorder
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import org.json.JSONArray
import org.json.JSONObject

internal data class ColorCorrectionDiscovery(
    val queryApiLevelSupported: Boolean,
    val advertisedModes: List<Int>?,
    val dynamicKeyModes: List<Int>?,
    val manualPostProcessingAdvertised: Boolean,
    val requestKeysPresent: List<String>,
    val aberrationModes: List<Int>,
    val hardwareLevel: Int?,
    val inferenceStatus: String,
    val queryError: String?,
) {
    fun toJson(): JSONObject = json(
        "queryApiLevelSupported" to queryApiLevelSupported,
        "advertisedModes" to advertisedModes?.let { JSONArray(it) },
        "dynamicKeyModes" to dynamicKeyModes?.let { JSONArray(it) },
        "manualPostProcessingAdvertised" to manualPostProcessingAdvertised,
        "requestKeysPresent" to JSONArray(requestKeysPresent),
        "aberrationModes" to JSONArray(aberrationModes),
        "hardwareLevel" to hardwareLevel,
        "inferenceStatus" to inferenceStatus,
        "queryError" to queryError,
    )
}

internal data class DynamicRangeDiscovery(
    val queryApiLevelSupported: Boolean,
    val hlg10Advertised: Boolean,
    val note: String,
    val queryError: String?,
) {
    fun toJson(): JSONObject = json(
        "queryApiLevelSupported" to queryApiLevelSupported,
        "hlg10Advertised" to hlg10Advertised,
        "note" to note,
        "queryError" to queryError,
    )
}

/**
 * Isolated API-level compatibility guards and non-fatal empirical/alternative capability discovery
 * so API 30-36 constants and queries never trigger NoSuchFieldError / NewApi on API 31 devices.
 */
internal object LabApiCompat {
    const val FORMAT_YCBCR_P010 = 54
    const val KEY_PIXEL_ASPECT_RATIO_WIDTH = "sar-width"
    const val KEY_PIXEL_ASPECT_RATIO_HEIGHT = "sar-height"
    const val COLOR_CORRECTION_MODE_TRANSFORM_MATRIX = 0
    const val COLOR_CORRECTION_MODE_FAST = 1
    const val COLOR_CORRECTION_MODE_HIGH_QUALITY = 2
    const val CAPABILITY_MANUAL_POST_PROCESSING = 2

    private val COLOR_CORRECTION_REQUEST_KEY_NAMES = setOf(
        "android.colorCorrection.mode",
        "android.colorCorrection.transform",
        "android.colorCorrection.gains",
        "android.colorCorrection.aberrationMode",
    )

    @RequiresApi(30)
    private object Api30 {
        fun thermalHeadroom(powerManager: PowerManager, forecastSeconds: Int): Float? =
            runCatching {
                powerManager.getThermalHeadroom(forecastSeconds).takeIf { it.isFinite() && it >= 0f }
            }.getOrNull()
    }

    @RequiresApi(31)
    private object Api31 {
        fun createMediaRecorder(context: Context): MediaRecorder = MediaRecorder(context)
    }

    @RequiresApi(33)
    private object Api33 {
        fun isHlg10Advertised(caps: CameraCharacteristics): Boolean =
            caps.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                ?.supportedProfiles
                ?.contains(DynamicRangeProfiles.HLG10) == true

        fun imageDataSpace(image: Image): Int? = image.dataSpace
    }

    @RequiresApi(36)
    private object Api36 {
        fun colorCorrectionAvailableModes(caps: CameraCharacteristics): IntArray? =
            caps.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_MODES)
    }

    fun thermalHeadroom(powerManager: PowerManager?, forecastSeconds: Int, sdkInt: Int = Build.VERSION.SDK_INT): Float? {
        if (powerManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R || sdkInt < Build.VERSION_CODES.R) return null
        return try {
            Api30.thermalHeadroom(powerManager, forecastSeconds)
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("DEPRECATION")
    fun createMediaRecorder(context: Context): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Api31.createMediaRecorder(context) else MediaRecorder()

    fun imageDataSpace(image: Image, sdkInt: Int = Build.VERSION.SDK_INT): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || sdkInt < Build.VERSION_CODES.TIRAMISU) return null
        return try {
            Api33.imageDataSpace(image)
        } catch (_: Throwable) {
            null
        }
    }

    fun safeCharacteristicsKeyNames(
        caps: CameraCharacteristics,
    ): Triple<List<String>, List<String>, List<String>> {
        val charKeys = runCatching { caps.keys.map { it.name } }.getOrDefault(emptyList())
        val reqKeys = runCatching { caps.availableCaptureRequestKeys.map { it.name } }.getOrDefault(emptyList())
        val resKeys = runCatching { caps.availableCaptureResultKeys.map { it.name } }.getOrDefault(emptyList())
        return Triple(charKeys, reqKeys, resKeys)
    }

    fun discoverDynamicRange(caps: CameraCharacteristics, sdkInt: Int = Build.VERSION.SDK_INT): DynamicRangeDiscovery {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || sdkInt < Build.VERSION_CODES.TIRAMISU) {
            return DynamicRangeDiscovery(
                queryApiLevelSupported = false,
                hlg10Advertised = false,
                note = "REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES requires API 33; absence on API $sdkInt does NOT prove hardware lacks 10-bit/HDR — verify via empirical format & vendor probes.",
                queryError = null,
            )
        }
        return try {
            val hlg = Api33.isHlg10Advertised(caps)
            DynamicRangeDiscovery(
                queryApiLevelSupported = true,
                hlg10Advertised = hlg,
                note = if (hlg) "DynamicRangeProfiles.HLG10 advertised on API $sdkInt." else "HLG10 not advertised via DynamicRangeProfiles on API $sdkInt.",
                queryError = null,
            )
        } catch (t: Throwable) {
            DynamicRangeDiscovery(
                queryApiLevelSupported = true,
                hlg10Advertised = false,
                note = "DynamicRangeProfiles query threw ${t.javaClass.simpleName}; probe empirically.",
                queryError = "${t.javaClass.simpleName}: ${t.message}",
            )
        }
    }

    fun discoverColorCorrection(caps: CameraCharacteristics, sdkInt: Int = Build.VERSION.SDK_INT): ColorCorrectionDiscovery {
        var queryError: String? = null
        val advertisedModes = if (Build.VERSION.SDK_INT >= 36 && sdkInt >= 36) {
            try {
                Api36.colorCorrectionAvailableModes(caps)?.toList()
            } catch (t: Throwable) {
                queryError = "${t.javaClass.simpleName}: ${t.message}"
                null
            }
        } else {
            null
        }

        // Check dynamically if OEM exposed "android.colorCorrection.availableModes" in CameraCharacteristics.keys even below API 36.
        val dynamicKeyModes = try {
            @Suppress("UNCHECKED_CAST")
            val dynamicKey = caps.keys.firstOrNull { it.name == "android.colorCorrection.availableModes" }
                as? CameraCharacteristics.Key<IntArray>
            dynamicKey?.let { caps.get(it)?.toList() }
        } catch (t: Throwable) {
            if (queryError == null) queryError = "${t.javaClass.simpleName}: ${t.message}"
            null
        }

        val capabilities = runCatching { caps.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty() }
            .getOrDefault(emptyList())
        val manualPostProcessing = CAPABILITY_MANUAL_POST_PROCESSING in capabilities
        val (_, reqKeys, _) = safeCharacteristicsKeyNames(caps)
        val presentColorKeys = reqKeys.filter { it in COLOR_CORRECTION_REQUEST_KEY_NAMES }.sorted()
        val aberrationModes = runCatching {
            caps.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)?.toList().orEmpty()
        }.getOrDefault(emptyList())
        val hardwareLevel = runCatching { caps.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) }.getOrNull()

        val inferenceStatus = when {
            advertisedModes != null -> "DIRECT_API36_ADVERTISED"
            dynamicKeyModes != null -> "DYNAMIC_KEY_ADVERTISED"
            manualPostProcessing || "android.colorCorrection.mode" in presentColorKeys ->
                "INFERRED_SUPPORTED_VIA_MANUAL_POST_PROCESSING_AND_KEYS_NEEDS_EMPIRICAL_TEST"
            else -> "QUERY_KEY_UNAVAILABLE_ON_API_${sdkInt}_NOT_PROOF_OF_ABSENCE"
        }

        return ColorCorrectionDiscovery(
            queryApiLevelSupported = sdkInt >= 36,
            advertisedModes = advertisedModes,
            dynamicKeyModes = dynamicKeyModes,
            manualPostProcessingAdvertised = manualPostProcessing,
            requestKeysPresent = presentColorKeys,
            aberrationModes = aberrationModes,
            hardwareLevel = hardwareLevel,
            inferenceStatus = inferenceStatus,
            queryError = queryError,
        )
    }
}
