/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.io.File
import org.json.JSONObject

internal enum class ManualPowerMode(val label: String) {
    UNSPECIFIED("Unspecified"),
    PERFORMANCE("Performance"),
    NORMAL("Normal / Balanced"),
    POWER_SAVING("Power Saving"),
    ULTRA_POWER_SAVING("Ultra Power Saving"),
    ;

    companion object {
        fun fromName(raw: String?): ManualPowerMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: UNSPECIFIED
    }
}

internal data class PowerProfileSnapshot(
    val epochMs: Long,
    val androidPowerSaveMode: Boolean,
    val sustainedPerformanceSupported: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val locationPowerSaveMode: Int,
    val manualPowerMode: ManualPowerMode,
    val huaweiSmartModeStatusRaw: Int? = null,
    val androidLowPowerGlobalRaw: Int? = null,
    val vendorPerformanceModeRaw: Int? = null,
    val cpuFreqReadable: Boolean = false,
    val cpuPolicySummary: Map<String, String> = emptyMap(),
) {
    val isLikelyThrottled: Boolean
        get() = androidPowerSaveMode ||
            manualPowerMode == ManualPowerMode.POWER_SAVING ||
            manualPowerMode == ManualPowerMode.ULTRA_POWER_SAVING ||
            androidLowPowerGlobalRaw == 1 ||
            huaweiSmartModeStatusRaw == 4

    val profileTag: String
        get() = "androidPowerSave=$androidPowerSaveMode|manual=${manualPowerMode.name}|smart=${huaweiSmartModeStatusRaw ?: "na"}"

    fun toJson(): JSONObject = json(
        "epochMs" to epochMs,
        "androidPowerSaveMode" to androidPowerSaveMode,
        "sustainedPerformanceSupported" to sustainedPerformanceSupported,
        "ignoringBatteryOptimizations" to ignoringBatteryOptimizations,
        "locationPowerSaveMode" to locationPowerSaveMode,
        "manualPowerMode" to manualPowerMode.name,
        "manualPowerModeLabel" to manualPowerMode.label,
        "huaweiSmartModeStatusRaw" to huaweiSmartModeStatusRaw,
        "androidLowPowerGlobalRaw" to androidLowPowerGlobalRaw,
        "vendorPerformanceModeRaw" to vendorPerformanceModeRaw,
        "cpuFreqReadable" to cpuFreqReadable,
        "cpuPolicySummary" to JSONObject().apply {
            cpuPolicySummary.forEach { (k, v) -> put(k, v) }
        },
        "isLikelyThrottled" to isLikelyThrottled,
        "profileTag" to profileTag,
    )

    companion object {
        fun fromJson(obj: JSONObject?): PowerProfileSnapshot? {
            if (obj == null) return null
            val cpuMap = linkedMapOf<String, String>()
            obj.optJSONObject("cpuPolicySummary")?.let { cpuJson ->
                val keys = cpuJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    cpuMap[key] = cpuJson.optString(key)
                }
            }
            return PowerProfileSnapshot(
                epochMs = obj.optLong("epochMs", 0L),
                androidPowerSaveMode = obj.optBoolean("androidPowerSaveMode", false),
                sustainedPerformanceSupported = obj.optBoolean("sustainedPerformanceSupported", false),
                ignoringBatteryOptimizations = obj.optBoolean("ignoringBatteryOptimizations", false),
                locationPowerSaveMode = obj.optInt("locationPowerSaveMode", 0),
                manualPowerMode = ManualPowerMode.fromName(obj.optString("manualPowerMode")),
                huaweiSmartModeStatusRaw = obj.opt("huaweiSmartModeStatusRaw").let { it as? Number }?.toInt(),
                androidLowPowerGlobalRaw = obj.opt("androidLowPowerGlobalRaw").let { it as? Number }?.toInt(),
                vendorPerformanceModeRaw = obj.opt("vendorPerformanceModeRaw").let { it as? Number }?.toInt(),
                cpuFreqReadable = obj.optBoolean("cpuFreqReadable", false),
                cpuPolicySummary = cpuMap,
            )
        }
    }
}

/**
 * Captures OS power-save telemetry, read-only Huawei OEM hints, CPU governor visibility,
 * and operator-annotated Huawei power/performance mode without guessing proprietary flags.
 */
internal object LabPowerProfile {
    private const val PREFS_NAME = "opencinecam_research_prefs"
    private const val KEY_MANUAL_POWER_MODE = "manual_power_mode"

    fun loadManualPowerMode(context: Context): ManualPowerMode {
        val raw = runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_MANUAL_POWER_MODE, ManualPowerMode.UNSPECIFIED.name)
        }.getOrNull()
        return ManualPowerMode.fromName(raw)
    }

    fun saveManualPowerMode(context: Context, mode: ManualPowerMode): ManualPowerMode {
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MANUAL_POWER_MODE, mode.name)
                .apply()
        }
        return mode
    }

    fun capture(context: Context, manualOverride: ManualPowerMode? = null): PowerProfileSnapshot {
        val pm = runCatching { context.getSystemService(PowerManager::class.java) }.getOrNull()
        val powerSave = runCatching { pm?.isPowerSaveMode == true }.getOrDefault(false)
        val sustainedSupported = runCatching { pm?.isSustainedPerformanceModeSupported == true }.getOrDefault(false)
        val ignoringBatteryOpt = runCatching {
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        }.getOrDefault(false)
        val locationPowerSave = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { pm?.locationPowerSaveMode ?: 0 }.getOrDefault(0)
        } else {
            0
        }
        val manual = manualOverride ?: loadManualPowerMode(context)
        val cr = context.contentResolver
        val smartModeRaw = runCatching { Settings.System.getInt(cr, "SmartModeStatus") }.getOrNull()
        val lowPowerRaw = runCatching { Settings.Global.getInt(cr, "low_power") }.getOrNull()
        val vendorPerfRaw = runCatching { Settings.System.getInt(cr, "performance_mode") }.getOrNull()
            ?: runCatching { Settings.Global.getInt(cr, "performance_mode") }.getOrNull()
            ?: runCatching { Settings.System.getInt(cr, "power_save_mode") }.getOrNull()
            ?: runCatching { Settings.Global.getInt(cr, "power_save_mode") }.getOrNull()
        val cpuSummary = readCpuPolicySummary()

        return PowerProfileSnapshot(
            epochMs = System.currentTimeMillis(),
            androidPowerSaveMode = powerSave,
            sustainedPerformanceSupported = sustainedSupported,
            ignoringBatteryOptimizations = ignoringBatteryOpt,
            locationPowerSaveMode = locationPowerSave,
            manualPowerMode = manual,
            huaweiSmartModeStatusRaw = smartModeRaw,
            androidLowPowerGlobalRaw = lowPowerRaw,
            vendorPerformanceModeRaw = vendorPerfRaw,
            cpuFreqReadable = cpuSummary.isNotEmpty(),
            cpuPolicySummary = cpuSummary,
        )
    }

    internal fun readCpuPolicySummary(sysCpuDir: File = File("/sys/devices/system/cpu")): Map<String, String> =
        runCatching {
            val out = linkedMapOf<String, String>()
            for (core in listOf(0, 4, 6, 7)) {
                val freqDir = File(sysCpuDir, "cpu$core/cpufreq")
                if (!freqDir.isDirectory) continue
                val gov = runCatching { File(freqDir, "scaling_governor").readText().trim() }.getOrNull()
                val cur = runCatching { File(freqDir, "scaling_cur_freq").readText().trim() }.getOrNull()
                val max = runCatching { File(freqDir, "scaling_max_freq").readText().trim() }.getOrNull()
                if (!gov.isNullOrBlank() || !cur.isNullOrBlank() || !max.isNullOrBlank()) {
                    out["cpu$core"] = "gov=${gov ?: "?"} cur=${cur ?: "?"} max=${max ?: "?"}"
                }
            }
            out
        }.getOrDefault(emptyMap())
}
