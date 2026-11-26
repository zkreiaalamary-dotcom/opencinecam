/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

internal class LabJournal private constructor(val directory: File, revision: String? = null) {
    private val header = File(directory, "run.json")
    private val complete = File(directory, "summary.json")
    private val telemetryFile = File(directory, "telemetry.jsonl")
    private val pauseFile = File(directory, "pause.json")
    private val telemetryLock = Any()
    val finished: Boolean get() = complete.isFile
    fun compatibleWith(revision: String): Boolean = readJson(header)?.let {
            it.optInt("schemaVersion") == VERSION && it.optString("apkSourceRevision") == revision &&
            it.optString("fingerprint") == Build.FINGERPRINT } == true

    init {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create research directory" }
        if (!header.exists()) writeJson(header, json(
            "schemaVersion" to VERSION, "apkSourceRevision" to revision, "model" to Build.MODEL, "manufacturer" to Build.MANUFACTURER,
            "sdk" to Build.VERSION.SDK_INT, "fingerprint" to Build.FINGERPRINT,
            "createdEpochMs" to System.currentTimeMillis(),
        ))
    }

    fun selectedCamera(): String? = readJson(File(directory, "inventory.json"))?.optString("selectedCamera")

    fun inventory(report: JSONObject) = writeJson(File(directory, "inventory.json"), report)

    fun inventoryJson(): JSONObject? = readJson(File(directory, "inventory.json"))

    fun attemptDirectory(id: String): File = File(directory, id)

    fun telemetryFile(): File = telemetryFile

    fun history(): List<LabResult> = attempts().mapNotNull { folder ->
        val start = readJson(File(folder, "000-requested.json")) ?: return@mapNotNull null
        val saved = readJson(File(folder, "result.json"))
        val stage = saved?.optString("stage") ?: folder.listFiles().orEmpty().sortedBy { it.name }
            .lastOrNull { it.extension == "json" && it.name.firstOrNull()?.isDigit() == true }?.let(::readJson)?.optString("stage")
        LabResult(
            attempt = start.getJSONObject("case").toCase(),
            verdict = saved?.getString("verdict")?.let(LabVerdict::valueOf) ?: LabVerdict.INTERRUPTED,
            stage = stage?.let(LabStage::valueOf) ?: LabStage.REQUESTED,
            id = folder.name,
        )
    }

    fun recoverInterrupted() {
        history().filter { it.verdict == LabVerdict.INTERRUPTED }.forEach { result ->
            val file = File(File(directory, result.id), "result.json")
            if (!file.exists()) writeJson(file, json(
                "verdict" to result.verdict.name, "stage" to result.stage.name,
                "reason" to "Process ended before the attempt committed a result.",
            ))
        }
    }

    fun begin(decision: LabDecision): Attempt {
        val folder = File(directory, "%03d-%s".format(java.util.Locale.ROOT, attempts().size + 1, UUID.randomUUID()))
        check(folder.mkdir()) { "Cannot create attempt directory" }
        return Attempt(folder).apply {
            event(LabStage.REQUESTED, json(
                "case" to decision.attempt.toJson(), "parent" to decision.parent, "hypothesis" to decision.hypothesis,
            ))
        }
    }

    fun appendTelemetry(sample: JSONObject) {
        synchronized(telemetryLock) {
            FileOutputStream(telemetryFile, true).use { stream ->
                stream.write((sample.toString() + "\n").toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
        }
    }

    fun recordStop(reason: String, snapshot: JSONObject?) {
        val stopRecord = json(
            "event" to "run_paused",
            "reason" to reason,
            "epochMs" to System.currentTimeMillis(),
            "elapsedMs" to SystemClock.elapsedRealtime(),
            "snapshot" to snapshot,
        )
        writeJson(pauseFile, stopRecord)
        appendTelemetry(stopRecord)
    }

    fun finish(reason: String) {
        val results = history()
        writeJson(complete, json(
            "reason" to reason,
            "model" to Build.MODEL,
            "completedEpochMs" to System.currentTimeMillis(),
            "attempts" to JSONArray(results.map {
                json("directory" to it.id, "case" to it.attempt.toJson(), "verdict" to it.verdict.name, "stage" to it.stage.name)
            }),
            "sourcePrecision" to "Effective source precision requires a separate controlled measurement.",
            "scope" to "Short tests on this exact device and recording graph. File verification means container and sample inspection.",
        ))
    }

    private fun attempts(): List<File> = directory.listFiles().orEmpty().filter(File::isDirectory).sortedBy(File::getName)

    class Attempt(val directory: File) {
        private var sequence = 0
        var stage = LabStage.REQUESTED
            private set
        val clip = File(directory, "clip.mp4")

        fun readEvents(): List<JSONObject> = directory.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" && it.name.firstOrNull()?.isDigit() == true }
            .sortedBy { it.name }
            .mapNotNull(::readJson)

        fun event(stage: LabStage, data: JSONObject) {
            val name = "%03d-%s.json".format(java.util.Locale.ROOT, sequence++, stage.name.lowercase(java.util.Locale.ROOT))
            writeJson(File(directory, name), data
                .put("stage", stage.name)
                .put("epochMs", System.currentTimeMillis())
                .put("elapsedMs", SystemClock.elapsedRealtime()))
            if (stage > this.stage) this.stage = stage
        }

        fun notice(data: JSONObject) = event(stage, data)

        fun result(verdict: LabVerdict, reason: String? = null) = writeJson(
            File(directory, "result.json"),
            json("verdict" to verdict.name, "stage" to stage.name, "reason" to reason,
                "clip" to clip.takeIf(File::isFile)?.name, "clipBytes" to clip.length()),
        )
    }

    companion object {
        private const val VERSION = 2

        fun latest(root: File): LabJournal? {
            val latest = root.listFiles().orEmpty().filter { File(it, "run.json").isFile }.maxByOrNull(File::getName)
            return latest?.let { LabJournal(it) }
        }

        fun exportAll(root: File, output: OutputStream) {
            ZipOutputStream(output).use { zip ->
                root.walkTopDown().filter { it.isFile && !it.name.endsWith(".new") && !it.name.endsWith(".bak") }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }

        fun create(root: File, revision: String) = LabJournal(File(root, "${System.currentTimeMillis()}-${UUID.randomUUID()}"), revision)
    }
}

private fun LabCase.toJson() = json(
    "camera" to camera, "path" to path.name, "width" to width, "height" to height, "fps" to fps,
    "hfr" to hfr, "listedByApp" to listed, "longer" to longer, "previewOnly" to previewOnly,
    "flatToneMap" to flatToneMap, "retryIndex" to retryIndex,
)

private fun JSONObject.toCase() = LabCase(
    getString("camera"), LabPath.valueOf(getString("path")), getInt("width"), getInt("height"),
    getInt("fps"), getBoolean("hfr"), getBoolean("listedByApp"), getBoolean("longer"), getBoolean("previewOnly"),
    optBoolean("flatToneMap", false), optInt("retryIndex", 0),
)

internal fun json(vararg values: Pair<String, Any?>) = JSONObject().apply {
    values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
}

private fun readJson(file: File): JSONObject? = try {
    AtomicFile(file).openRead().bufferedReader().use { JSONObject(it.readText()) }
} catch (_: java.io.FileNotFoundException) {
    null
}

private fun writeJson(file: File, value: JSONObject) {
    val atomic = AtomicFile(file)
    val stream = atomic.startWrite()
    try {
        stream.write(value.toString(2).toByteArray(Charsets.UTF_8))
        stream.fd.sync()
        atomic.finishWrite(stream)
    } catch (error: Exception) {
        atomic.failWrite(stream)
        throw error
    }
}
