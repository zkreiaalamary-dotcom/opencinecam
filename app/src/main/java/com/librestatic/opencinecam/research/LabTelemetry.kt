/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import java.io.File
import java.util.Locale
import org.json.JSONObject

internal data class ThermalSample(
    val elapsedMs: Long,
    val epochMs: Long,
    val thermalStatus: Int,
    val headroom0s: Float?,
    val headroom10s: Float?,
    val headroom30s: Float?,
    val batteryPercent: Int,
    val batteryTempTenthsC: Int,
    val batteryVoltageMv: Int,
    val batteryCurrentUa: Int?,
    val plugged: Int,
    val freeBytes: Long,
    val evidenceBytes: Long,
    val sysZones: Map<String, Int> = emptyMap(),
    val evidenceLimitBytes: Long = LabStorageManager.DEFAULT_EVIDENCE_LIMIT_BYTES,
    val batteryTempRaw: Int = batteryTempTenthsC,
    val powerProfile: PowerProfileSnapshot? = null,
) {
    fun toJson(): JSONObject = json(
        "event" to "telemetry",
        "elapsedMs" to elapsedMs,
        "epochMs" to epochMs,
        "thermalStatus" to thermalStatus,
        "thermalStatusName" to thermalStatusLabel(thermalStatus),
        "headroom0s" to headroom0s?.toDouble(),
        "headroom10s" to headroom10s?.toDouble(),
        "headroom30s" to headroom30s?.toDouble(),
        "batteryPercent" to batteryPercent,
        "batteryTempSource" to "BatteryManager.EXTRA_TEMPERATURE",
        "batteryTempRaw" to batteryTempRaw,
        "batteryTempTenthsC" to batteryTempTenthsC,
        "batteryTempC" to if (batteryTempTenthsC >= 0) batteryTempTenthsC / 10.0 else null,
        "batteryVoltageMv" to batteryVoltageMv,
        "batteryCurrentUa" to batteryCurrentUa,
        "plugged" to plugged,
        "freeBytes" to freeBytes,
        "evidenceBytes" to evidenceBytes,
        "evidenceLimitBytes" to evidenceLimitBytes,
        "reserveFreeBytes" to LabStorageManager.DEVICE_FREE_RESERVE_BYTES,
        "sysZones" to JSONObject().apply {
            sysZones.forEach { (k, v) -> put(k, v) }
        },
        "sysZonesC" to JSONObject().apply {
            sysZones.forEach { (k, v) -> put(k, normalizeSysZoneC(v)) }
        },
        "powerProfile" to powerProfile?.toJson(),
    )
}

internal fun normalizeBatteryTempTenthsC(raw: Int): Int = when {
    raw < 0 -> -1
    raw > 1000 -> raw / 100
    else -> raw
}

internal fun normalizeSysZoneC(raw: Int): Double =
    if (kotlin.math.abs(raw) >= 1000) raw / 1000.0 else raw.toDouble()

internal fun thermalStatusLabel(status: Int): String = when (status) {
    PowerManager.THERMAL_STATUS_NONE -> "NONE"
    PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
    PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
    PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
    PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
    PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
    PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
    else -> "UNKNOWN($status)"
}

internal sealed interface ThermalDecision {
    data class Continue(val transientModerate: Boolean = false) : ThermalDecision
    data class CooldownWait(val reason: String) : ThermalDecision
    data class HardStop(val reason: String) : ThermalDecision
}

/**
 * Battery-hysteresis thermal policy aligned with active cooling:
 * - Continues at full performance whenever battery < 40.0 °C and OS thermalStatus < SEVERE (3).
 *   Does NOT pause at 32 °C / MODERATE or apply arbitrary trend-rate aborts.
 * - Enters CooldownWait when battery >= 40.0 °C or OS thermalStatus >= SEVERE (3).
 * - Automatically resumes once battery drops <= 35.0 °C AND OS thermalStatus < SEVERE (3),
 *   without resetting or aborting the session.
 * - Uses HardStop ONLY for unrecoverable resource limits (< 25% battery, < 5 GiB free reserve,
 *   configured evidence cap) or OS EMERGENCY/SHUTDOWN status, preserving full checkpoint state.
 */
internal class LabThermalPolicy(
    @Suppress("unused") private val maxCooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val evidenceLimitBytes: Long = DEFAULT_MAX_EVIDENCE_BYTES,
) {
    var inThermalPause: Boolean = false
        private set

    fun evaluate(sample: ThermalSample, @Suppress("unused") betweenAttempts: Boolean = false): ThermalDecision {
        val effectiveLimit = LabStorageManager.clampLimitBytes(
            evidenceLimitBytes.takeIf { it > 0L } ?: sample.evidenceLimitBytes,
        )
        if (sample.batteryPercent in 0 until MIN_BATTERY_PERCENT) {
            return ThermalDecision.HardStop("Research paused at the battery charge limit (< $MIN_BATTERY_PERCENT%).")
        }
        if (sample.freeBytes < MIN_FREE_BYTES) {
            return ThermalDecision.HardStop("Research paused to protect the 5.0 GiB device free-space safety reserve.")
        }
        if (sample.evidenceBytes >= effectiveLimit) {
            return ThermalDecision.HardStop(
                "Research evidence reached the ${LabStorageManager.formatBytes(effectiveLimit)} configured local limit. Export or prune media to resume.",
            )
        }
        if (sample.thermalStatus >= EMERGENCY_THERMAL_STATUS) {
            return ThermalDecision.HardStop(
                "Research paused due to OS emergency thermal status (${thermalStatusLabel(sample.thermalStatus)}, ${formatC(sample.batteryTempTenthsC)}).",
            )
        }

        val batteryHot = sample.batteryTempTenthsC >= PAUSE_BATTERY_TEMP_TENTHS_C
        val batteryCooled = sample.batteryTempTenthsC < 0 || sample.batteryTempTenthsC <= RESUME_BATTERY_TEMP_TENTHS_C
        val osDangerous = sample.thermalStatus >= DANGEROUS_THERMAL_STATUS

        if (batteryHot || osDangerous) {
            inThermalPause = true
            return ThermalDecision.CooldownWait(
                "Thermal pause (${formatC(sample.batteryTempTenthsC)}, OS=${thermalStatusLabel(sample.thermalStatus)}): " +
                    "waiting for battery <= 35.0 °C and OS status < SEVERE to auto-resume…",
            )
        }

        if (inThermalPause) {
            if (!batteryCooled) {
                return ThermalDecision.CooldownWait(
                    "Thermal cooldown (${formatC(sample.batteryTempTenthsC)}, OS=${thermalStatusLabel(sample.thermalStatus)}): " +
                        "auto-resuming at <= 35.0 °C…",
                )
            }
            inThermalPause = false
        }

        return ThermalDecision.Continue(transientModerate = sample.thermalStatus == THERMAL_STATUS_MODERATE)
    }

    fun resetCooldown() {
        inThermalPause = false
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 45_000L
        const val MIN_BATTERY_PERCENT = 25
        const val PAUSE_BATTERY_TEMP_TENTHS_C = 400
        const val RESUME_BATTERY_TEMP_TENTHS_C = 350
        const val DANGEROUS_THERMAL_STATUS = PowerManager.THERMAL_STATUS_SEVERE
        const val EMERGENCY_THERMAL_STATUS = PowerManager.THERMAL_STATUS_EMERGENCY
        const val MIN_FREE_BYTES = LabStorageManager.DEVICE_FREE_RESERVE_BYTES
        const val DEFAULT_MAX_EVIDENCE_BYTES = LabStorageManager.DEFAULT_EVIDENCE_LIMIT_BYTES
        const val THERMAL_STATUS_MODERATE = PowerManager.THERMAL_STATUS_MODERATE
        const val THERMAL_STATUS_SEVERE = PowerManager.THERMAL_STATUS_SEVERE

        private fun formatC(tenthsC: Int): String =
            if (tenthsC < 0) "unknown °C" else String.format(Locale.ROOT, "%.1f °C", tenthsC / 10.0)
    }
}

internal fun sampleThermal(
    context: Context,
    evidenceRoot: File,
    evidenceLimitBytes: Long = LabStorageManager.loadEvidenceLimitBytes(context),
    includePowerProfile: Boolean = true,
): ThermalSample {
    val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val percent = if (scale > 0 && level >= 0) 100 * level / scale else -1
    val rawTemp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
    val tempTenthsC = normalizeBatteryTempTenthsC(rawTemp)
    val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
    val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
    val batteryManager = context.getSystemService(BatteryManager::class.java)
    val currentUa = runCatching {
        batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?.takeIf { it != Int.MIN_VALUE }
    }.getOrNull()
    val powerManager = context.getSystemService(PowerManager::class.java)
    val thermalStatus = powerManager?.currentThermalStatus ?: 0
    fun headroom(seconds: Int): Float? = LabApiCompat.thermalHeadroom(powerManager, seconds)
    val freeBytes = LabStorageManager.availableDeviceBytes(evidenceRoot)
    val evidenceBytes = LabStorageManager.measureUsage(
        root = evidenceRoot,
        freeBytes = freeBytes,
        limitBytes = evidenceLimitBytes,
    ).totalBytes
    val powerSnapshot = if (includePowerProfile) runCatching { LabPowerProfile.capture(context) }.getOrNull() else null
    return ThermalSample(
        elapsedMs = SystemClock.elapsedRealtime(),
        epochMs = System.currentTimeMillis(),
        thermalStatus = thermalStatus,
        headroom0s = headroom(0),
        headroom10s = headroom(10),
        headroom30s = headroom(30),
        batteryPercent = percent,
        batteryTempTenthsC = tempTenthsC,
        batteryVoltageMv = voltageMv,
        batteryCurrentUa = currentUa,
        plugged = plugged,
        freeBytes = freeBytes,
        evidenceBytes = evidenceBytes,
        sysZones = readSysThermalZones(),
        evidenceLimitBytes = evidenceLimitBytes,
        batteryTempRaw = rawTemp,
        powerProfile = powerSnapshot,
    )
}

internal fun readSysThermalZones(maxZones: Int = 12): Map<String, Int> = runCatching {
    val dir = File("/sys/class/thermal")
    val zones = dir.listFiles { file -> file.name.startsWith("thermal_zone") }?.sortedBy { it.name }.orEmpty()
    buildMap {
        for (zone in zones) {
            if (size >= maxZones) break
            val type = runCatching { File(zone, "type").readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: zone.name
            val temp = runCatching { File(zone, "temp").readText().trim().toIntOrNull() }.getOrNull() ?: continue
            put(type, temp)
        }
    }
}.getOrDefault(emptyMap())
