/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Generates self-contained `report.md` and `report.json` inside the run directory so exported
 * evidence bundles contain a verified, provenance-accurate engineering summary.
 */
internal object LabReportGenerator {
    fun generate(
        journalDir: File,
        inventory: JSONObject?,
        history: List<LabResult>,
        knowledge: LabKnowledgeBase,
        stopReason: String? = null,
    ): File {
        val telemetryStats = summarizeTelemetry(File(journalDir, "telemetry.jsonl"))
        val storageUsage = LabStorageManager.measureUsage(journalDir)
        val jsonReport = json(
            "schema" to "opencinecam.research.report/v2",
            "selectedCamera" to inventory?.optString("selectedCamera"),
            "hlg10Advertised" to knowledge.hlg10Advertised,
            "cameraKitPresent" to knowledge.cameraKitPresent,
            "totalAttempts" to history.size,
            "observedAttempts" to history.count { it.verdict == LabVerdict.OBSERVED },
            "bestCodedBitDepth" to knowledge.bestCodedBitDepth,
            "bestSourcePrecision" to knowledge.bestSourcePrecision,
            "trueDynamicRangeVerified" to knowledge.trueDynamicRangeVerified,
            "bestLogRecipeSummary" to knowledge.bestLogRecipeSummary,
            "bestDirect4k60Summary" to knowledge.bestDirect4k60Summary,
            "toneMapFlatVerdict" to knowledge.toneMapFlatVerdict,
            "colorCorrectionEmpiricalVerdict" to knowledge.colorCorrectionEmpiricalVerdict,
            "highPrecisionSourceAdmitted" to knowledge.highPrecisionSourceAdmitted,
            "stopReason" to stopReason,
            "storage" to json(
                "totalBytes" to storageUsage.totalBytes,
                "mediaBytes" to storageUsage.mediaBytes,
                "metadataBytes" to storageUsage.metadataBytes,
                "fileCount" to storageUsage.fileCount,
                "freeBytes" to storageUsage.freeBytes,
                "reserveBytes" to storageUsage.reserveBytes,
                "hasVerifiedExport" to storageUsage.hasVerifiedExport,
            ),
            "telemetry" to telemetryStats,
            "knowledge" to knowledge.toJson(),
            "attempts" to JSONArray(history.map {
                json("id" to it.id, "key" to it.attempt.key, "verdict" to it.verdict.name, "stage" to it.stage.name)
            }),
        )
        File(journalDir, "report.json").writeText(jsonReport.toString(2), Charsets.UTF_8)

        val md = buildString {
            appendLine("# OpenCineCam Autonomous Research Lab Report")
            appendLine()
            appendLine("- **Camera:** `${inventory?.optString("selectedCamera") ?: "unknown"}`")
            appendLine("- **Public HLG10 advertised:** `${knowledge.hlg10Advertised}`")
            appendLine("- **Huawei CameraKit present (read-only check):** `${knowledge.cameraKitPresent}`")
            appendLine("- **Attempts recorded:** `${history.size}` (`OBSERVED`: ${history.count { it.verdict == LabVerdict.OBSERVED }})")
            appendLine("- **Evidence storage:** `${LabStorageManager.formatBytes(storageUsage.totalBytes)}` (media `${LabStorageManager.formatBytes(storageUsage.mediaBytes)}`, logs `${LabStorageManager.formatBytes(storageUsage.metadataBytes)}`, free `${LabStorageManager.formatBytes(storageUsage.freeBytes)}`)")
            if (!stopReason.isNullOrBlank()) appendLine("- **Run state:** $stopReason")
            appendLine()
            appendLine("## Provenance & Best Verified Recipes")
            appendLine()
            appendLine("- **Best verified 4K LOG recipe:** ${knowledge.bestLogRecipeSummary ?: "None verified yet"}")
            appendLine("- **Best verified direct 4K60 recipe:** ${knowledge.bestDirect4k60Summary ?: "None verified yet"}")
            appendLine("- **Best coded bit depth:** ${knowledge.bestCodedBitDepth?.let { "$it-bit" } ?: "UNVERIFIED"}")
            appendLine("- **Effective source precision:** `${knowledge.bestSourcePrecision}`")
            appendLine("- **True scene dynamic range verified:** `${knowledge.trueDynamicRangeVerified}`")
            appendLine("- **ISP ToneMap FLAT A/B verdict:** `${knowledge.toneMapFlatVerdict ?: "UNTESTED"}`")
            appendLine("- **ISP Empirical ColorCorrection / ToneMap probe:** `${knowledge.colorCorrectionEmpiricalVerdict ?: "UNTESTED"}`")
            appendLine("- **High-precision non-YUV_420_888 source admitted:** `${knowledge.highPrecisionSourceAdmitted}`")
            inventory?.optJSONObject("colorCorrectionDiscovery")?.let { cc ->
                appendLine("- **ColorCorrection API discovery (`SDK ${inventory.opt("sdkInt")}`):** `${cc.optString("inferenceStatus")}`")
            }
            appendLine()
            appendLine("## Hypothesis Ledger (H-01 .. H-10)")
            appendLine()
            appendLine("| ID | Status | Summary | Attempts |")
            appendLine("|---|---|---|---|")
            knowledge.hypothesesList().forEach { h ->
                val attempts = h.evidenceAttempts.joinToString(", ").ifBlank { "-" }
                val sum = (h.summary ?: h.statement).replace("|", "/")
                appendLine("| ${h.id} | **${h.status.name}** | $sum | $attempts |")
            }
            if (knowledge.recipes.isNotEmpty()) {
                appendLine()
                appendLine("## Learned Synthetic Main10 Recipes")
                appendLine()
                knowledge.recipes.forEach { r ->
                    appendLine("- `${r.armId}` (${r.fps} fps, level=${r.explicitHevcLevel}, egl8=${r.forceEgl8BitForMain10}, rampBits=${r.rampEffectiveBits})")
                }
            }
            if (knowledge.contradictions.isNotEmpty()) {
                appendLine()
                appendLine("## Contradictions Detected")
                appendLine()
                knowledge.contradictions.forEach { c ->
                    appendLine("- **${c.topic}**: ${c.description}")
                }
            }
            if (knowledge.recordedErrors.isNotEmpty()) {
                appendLine()
                appendLine("## Recorded Isolated Errors & Compatibility Notes")
                appendLine()
                knowledge.recordedErrors.forEach { e ->
                    val att = e.attemptId?.let { " (attempt `$it`)" }.orEmpty()
                    appendLine("- **${e.scope}**$att on SDK ${e.sdkInt}: `${e.errorClass}` — ${e.message ?: "no message"}")
                }
            }
            appendLine()
            appendLine("## Thermal Telemetry Summary")
            appendLine()
            appendLine("- **Samples:** `${telemetryStats.optInt("samples")}`")
            appendLine("- **Max battery temp (°C):** `${telemetryStats.opt("maxBatteryTempC")}`")
            appendLine("- **Max thermal status:** `${telemetryStats.opt("maxThermalStatus")}`")
            appendLine()
            appendLine("## Attempt History")
            appendLine()
            appendLine("| Attempt | Case | Verdict | Stage |")
            appendLine("|---|---|---|---|")
            history.forEach { r ->
                appendLine("| `${r.id}` | `${r.attempt.label}` | **${r.verdict.name}** | `${r.stage.name}` |")
            }
        }
        val mdFile = File(journalDir, "report.md")
        mdFile.writeText(md, Charsets.UTF_8)
        return mdFile
    }

    private fun summarizeTelemetry(file: File): JSONObject {
        if (!file.isFile) return json("samples" to 0)
        var samples = 0
        var maxTempTenths = Int.MIN_VALUE
        var maxThermalStatus = Int.MIN_VALUE
        var lastBatteryPct: Int? = null
        file.forEachLine(Charsets.UTF_8) { line ->
            if (line.isBlank()) return@forEachLine
            runCatching {
                val obj = JSONObject(line)
                samples++
                val t = obj.optInt("batteryTempTenthsC", -1)
                if (t > maxTempTenths) maxTempTenths = t
                val s = obj.optInt("thermalStatus", -1)
                if (s > maxThermalStatus) maxThermalStatus = s
                if (!obj.isNull("batteryPct")) lastBatteryPct = obj.optInt("batteryPct")
            }
        }
        return json(
            "samples" to samples,
            "maxBatteryTempC" to if (maxTempTenths >= 0) String.format(Locale.ROOT, "%.1f", maxTempTenths / 10.0) else null,
            "maxThermalStatus" to if (maxThermalStatus >= 0) maxThermalStatus else null,
            "lastBatteryPct" to lastBatteryPct,
        )
    }
}
