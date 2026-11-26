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
) {
    fun toJson(): JSONObject = json(
        "event" to "telemetry",
        "elapsedMs" to elapsedMs,
        "epochMs" to epochMs,
        "thermalStatus" to thermalStatus,
        "headroom0s" to headroom0s?.toDouble(),
        "headroom10s" to headroom10s?.toDouble(),
        "headroom30s" to headroom30s?.toDouble(),
        "batteryPercent" to batteryPercent,
        "batteryTempTenthsC" to batteryTempTenthsC,
        "batteryVoltageMv" to batteryVoltageMv,
        "batteryCurrentUa" to batteryCurrentUa,
        "plugged" to plugged,
        "freeBytes" to freeBytes,
        "evidenceBytes" to evidenceBytes,
        "sysZones" to JSONObject().apply {
            sysZones.forEach { (k, v) -> put(k, v) }
        },
    )
}

internal sealed interface ThermalDecision {
    data class Continue(val transientModerate: Boolean = false) : ThermalDecision
    data class CooldownWait(val reason: String) : ThermalDecision
    data class HardStop(val reason: String) : ThermalDecision
}

/**
 * Hysteresis-aware thermal and resource safety policy.
 * Allows a single transient MODERATE reading during active work to prevent unexplained silent stops,
 * enters a bounded cooldown wait between attempts when MODERATE persists, and halts immediately on
 * SEVERE thermal status, >= 42.0 °C battery, < 25% battery, or storage exhaustion.
 */
internal class LabThermalPolicy(
    private val maxCooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    private var consecutiveModerate = 0
    private var cooldownStartedMs: Long? = null

    fun evaluate(sample: ThermalSample, betweenAttempts: Boolean = false): ThermalDecision {
        if (sample.batteryPercent in 0 until MIN_BATTERY_PERCENT || sample.batteryTempTenthsC >= MAX_BATTERY_TEMP_TENTHS_C) {
            return ThermalDecision.HardStop("Research paused at the battery limit (25% charge or 42 °C).")
        }
        if (sample.freeBytes < MIN_FREE_BYTES) {
            return ThermalDecision.HardStop("Research needs at least 1.5 GB of free storage.")
        }
        if (sample.evidenceBytes >= MAX_EVIDENCE_BYTES) {
            return ThermalDecision.HardStop("Research evidence is close to the 1 GB local limit.")
        }
        if (sample.thermalStatus >= THERMAL_STATUS_SEVERE) {
            return ThermalDecision.HardStop(
                "Research paused due to severe thermal load (status=${sample.thermalStatus}, ${formatC(sample.batteryTempTenthsC)}).",
            )
        }
        if (sample.thermalStatus == THERMAL_STATUS_MODERATE) {
            if (sample.batteryTempTenthsC >= COOLDOWN_BATTERY_CEILING_TENTHS_C) {
                return ThermalDecision.HardStop(
                    "Research paused due to thermal load (status=MODERATE, ${formatC(sample.batteryTempTenthsC)}).",
                )
            }
            consecutiveModerate++
            if (!betweenAttempts && consecutiveModerate == 1) {
                return ThermalDecision.Continue(transientModerate = true)
            }
            val started = cooldownStartedMs ?: sample.elapsedMs.also { cooldownStartedMs = it }
            if (sample.elapsedMs - started >= maxCooldownMs) {
                return ThermalDecision.HardStop(
                    "Research paused after thermal cooldown (${formatC(sample.batteryTempTenthsC)}, status=MODERATE).",
                )
            }
            return ThermalDecision.CooldownWait(
                "Cooling down (thermal=MODERATE, ${formatC(sample.batteryTempTenthsC)})…",
            )
        }
        consecutiveModerate = 0
        cooldownStartedMs = null
        return ThermalDecision.Continue(transientModerate = false)
    }

    fun resetCooldown() {
        consecutiveModerate = 0
        cooldownStartedMs = null
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 45_000L
        const val MIN_BATTERY_PERCENT = 25
        const val MAX_BATTERY_TEMP_TENTHS_C = 420
        const val COOLDOWN_BATTERY_CEILING_TENTHS_C = 400
        const val MIN_FREE_BYTES = 1_500_000_000L
        const val MAX_EVIDENCE_BYTES = 950_000_000L
        const val THERMAL_STATUS_MODERATE = 2
        const val THERMAL_STATUS_SEVERE = 3

        private fun formatC(tenthsC: Int): String =
            if (tenthsC < 0) "unknown °C" else String.format(Locale.ROOT, "%.1f °C", tenthsC / 10.0)
    }
}

internal fun sampleThermal(context: Context, evidenceRoot: File): ThermalSample {
    val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val percent = if (scale > 0 && level >= 0) 100 * level / scale else -1
    val tempTenthsC = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
    val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
    val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
    val batteryManager = context.getSystemService(BatteryManager::class.java)
    val currentUa = runCatching {
        batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?.takeIf { it != Int.MIN_VALUE }
    }.getOrNull()
    val powerManager = context.getSystemService(PowerManager::class.java)
    val thermalStatus = powerManager?.currentThermalStatus ?: 0
    fun headroom(seconds: Int): Float? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && powerManager != null) {
        runCatching {
            powerManager.getThermalHeadroom(seconds).takeIf { it.isFinite() && it >= 0f }
        }.getOrNull()
    } else null
    val freeBytes = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(0L)
    val evidenceBytes = runCatching {
        if (evidenceRoot.exists()) evidenceRoot.walkTopDown().filter(File::isFile).sumOf(File::length) else 0L
    }.getOrDefault(0L)
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
