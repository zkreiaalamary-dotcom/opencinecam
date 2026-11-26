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

internal class LabJournal private constructor(
    val directory: File,
    revision: String? = null,
    initialPowerProfile: PowerProfileSnapshot? = null,
) {
    private val header = File(directory, "run.json")
    private val complete = File(directory, "summary.json")
    private val telemetryFile = File(directory, "telemetry.jsonl")
    private val pauseFile = File(directory, "pause.json")
    private val checkpointFile = File(directory, "checkpoint.json")
    private val telemetryLock = Any()
    val sessionId: String get() = directory.name
    val finished: Boolean get() = complete.isFile

    fun compatibleWith(revision: String): Boolean = readJson(header)?.let {
        val ver = it.optInt("schemaVersion")
        (ver == VERSION || ver == LEGACY_VERSION_2) &&
            it.optString("apkSourceRevision") == revision &&
            it.optString("fingerprint") == Build.FINGERPRINT
    } == true

    /** Cross-revision resumability on the exact same device build fingerprint for unfinished runs. */
    fun resumableOnDevice(): Boolean = !finished && readJson(header)?.let {
        val ver = it.optInt("schemaVersion")
        (ver == VERSION || ver == LEGACY_VERSION_2) &&
            it.optString("fingerprint") == Build.FINGERPRINT
    } == true

    /** True only if this unfinished run recorded actual progress beyond STOPPED-only ghost attempts. */
    fun hasResumableProgress(): Boolean {
        if (!resumableOnDevice()) return false
        val cp = readCheckpointState()
        if (cp != null && cp.state == LabSessionState.COMPLETED) return false
        val hist = history()
        val nonStopped = hist.count { it.verdict != LabVerdict.STOPPED }
        if (nonStopped > 0) return true
        return cp != null && cp.schemaVersion >= VERSION && (
            cp.completedKeys.isNotEmpty() ||
                cp.failedKeys.isNotEmpty() ||
                cp.interruptedCounts.isNotEmpty() ||
                cp.inFlightAttempt != null
            )
    }

    fun headerJson(): JSONObject? = readJson(header)

    fun recordApkRevisionTransition(newRevision: String) {
        val current = readJson(header) ?: return
        if (current.optString("apkSourceRevision") == newRevision &&
            current.optString("latestApkSourceRevision") == newRevision
        ) return
        val history = current.optJSONArray("revisionHistory") ?: JSONArray().apply {
            current.optString("apkSourceRevision").takeIf { it.isNotBlank() }?.let(::put)
        }
        if ((0 until history.length()).none { history.optString(it) == newRevision }) {
            history.put(newRevision)
        }
        current.put("latestApkSourceRevision", newRevision)
        current.put("revisionHistory", history)
        current.put("resumedAtEpochMs", System.currentTimeMillis())
        writeJson(header, current)
    }

    init {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create research directory" }
        if (!header.exists()) writeJson(header, json(
            "schemaVersion" to VERSION,
            "sessionId" to directory.name,
            "apkSourceRevision" to revision,
            "latestApkSourceRevision" to revision,
            "model" to Build.MODEL,
            "manufacturer" to Build.MANUFACTURER,
            "sdk" to Build.VERSION.SDK_INT,
            "fingerprint" to Build.FINGERPRINT,
            "createdEpochMs" to System.currentTimeMillis(),
            "initialPowerProfile" to initialPowerProfile?.toJson(),
        ))
    }

    fun selectedCamera(): String? = readJson(File(directory, "inventory.json"))?.optString("selectedCamera")

    fun inventory(report: JSONObject) = writeJson(File(directory, "inventory.json"), report)

    fun inventoryJson(): JSONObject? = readJson(File(directory, "inventory.json"))

    fun attemptDirectory(id: String): File = File(directory, id)

    fun readAttemptEvents(id: String): List<JSONObject> = attemptDirectory(id).listFiles().orEmpty()
        .filter { it.isFile && it.extension == "json" && it.name.firstOrNull()?.isDigit() == true }
        .sortedBy { it.name }
        .mapNotNull(::readJson)

    fun readResultJson(id: String): JSONObject? = readJson(File(attemptDirectory(id), "result.json"))

    fun isThermalSuspendedAttempt(id: String): Boolean = readResultJson(id)?.optBoolean("thermalSuspended", false) == true

    fun telemetryFile(): File = telemetryFile

    fun history(): List<LabResult> = attempts().mapNotNull { folder ->
        runCatching {
            val start = readJson(File(folder, "000-requested.json")) ?: return@mapNotNull null
            val saved = readJson(File(folder, "result.json"))
            val stage = saved?.optString("stage") ?: folder.listFiles().orEmpty().sortedBy { it.name }
                .lastOrNull { it.extension == "json" && it.name.firstOrNull()?.isDigit() == true }?.let(::readJson)?.optString("stage")
            val verdict = saved?.optString("verdict")?.takeIf { it.isNotBlank() }?.let { raw ->
                runCatching { LabVerdict.valueOf(raw) }.getOrNull()
            } ?: LabVerdict.INTERRUPTED
            LabResult(
                attempt = start.getJSONObject("case").toCase(),
                verdict = verdict,
                stage = stage?.let { runCatching { LabStage.valueOf(it) }.getOrNull() } ?: LabStage.REQUESTED,
                id = folder.name,
            )
        }.getOrNull()
    }

    fun recoverInterrupted() {
        val cp = readCheckpointState()
        val inFlightId = cp?.inFlightAttempt?.id
        val interruptedIds = buildSet {
            history().filter { it.verdict == LabVerdict.INTERRUPTED }.forEach { add(it.id) }
            if (inFlightId != null) {
                val resFile = File(File(directory, inFlightId), "result.json")
                if (!resFile.exists()) add(inFlightId)
            }
        }
        interruptedIds.forEach { id ->
            val attemptDir = File(directory, id)
            val file = File(attemptDir, "result.json")
            if (!file.exists()) {
                val lastStage = attemptDir.listFiles().orEmpty().sortedBy { it.name }
                    .lastOrNull { it.extension == "json" && it.name.firstOrNull()?.isDigit() == true }
                    ?.let(::readJson)?.optString("stage") ?: LabStage.REQUESTED.name
                writeJson(file, json(
                    "verdict" to LabVerdict.INTERRUPTED.name,
                    "stage" to lastStage,
                    "reason" to "Process ended before the attempt committed a result.",
                ))
            }
        }
    }

    fun begin(
        decision: LabDecision,
        apkSourceRevision: String? = null,
        powerProfile: PowerProfileSnapshot? = null,
    ): Attempt {
        val folder = File(directory, "%03d-%s".format(java.util.Locale.ROOT, attempts().size + 1, UUID.randomUUID()))
        check(folder.mkdir()) { "Cannot create attempt directory" }
        return Attempt(folder).apply {
            val req = json(
                "case" to decision.attempt.toJson(),
                "parent" to decision.parent,
                "hypothesis" to decision.hypothesis,
            )
            apkSourceRevision?.let { req.put("apkSourceRevision", it) }
            powerProfile?.let { req.put("powerProfile", it.toJson()) }
            event(LabStage.REQUESTED, req)
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

    fun readPauseReason(): String? = readJson(pauseFile)?.optString("reason")?.takeIf { it.isNotBlank() }

    fun saveCheckpoint(checkpoint: JSONObject) {
        writeJson(checkpointFile, checkpoint)
    }

    fun saveCheckpointState(state: LabCheckpointState) {
        saveCheckpoint(state.toJson())
    }

    fun readCheckpoint(): JSONObject? = readJson(checkpointFile)

    fun readCheckpointState(): LabCheckpointState? = LabCheckpointState.fromJson(readCheckpoint())

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

        fun result(verdict: LabVerdict, reason: String? = null, thermalSuspended: Boolean = false) = writeJson(
            File(directory, "result.json"),
            json(
                "verdict" to verdict.name,
                "stage" to stage.name,
                "reason" to reason,
                "thermalSuspended" to thermalSuspended,
                "clip" to clip.takeIf(File::isFile)?.name,
                "clipBytes" to clip.length(),
            ),
        )
    }

    companion object {
        private const val LEGACY_VERSION_2 = 2
        private const val VERSION = 3
        private const val ACTIVE_SESSION_FILE = "active_session.json"

        fun allRunDirectories(root: File): List<File> =
            root.listFiles().orEmpty()
                .filter { it.isDirectory && File(it, "run.json").isFile }
                .sortedByDescending(File::getName)

        fun latest(root: File): LabJournal? {
            val latest = allRunDirectories(root).firstOrNull()
            return latest?.let { LabJournal(it) }
        }

        fun setActiveSession(root: File, sessionId: String, state: LabSessionState = LabSessionState.RUNNING) {
            if (!root.exists()) root.mkdirs()
            writeJson(
                File(root, ACTIVE_SESSION_FILE),
                json(
                    "sessionId" to sessionId,
                    "state" to state.name,
                    "updatedEpochMs" to System.currentTimeMillis(),
                ),
            )
        }

        fun readActiveSessionPointer(root: File): Pair<String, LabSessionState?>? {
            val obj = readJson(File(root, ACTIVE_SESSION_FILE)) ?: return null
            val id = obj.optString("sessionId").takeIf { it.isNotBlank() } ?: return null
            val state = obj.optString("state").takeIf { it.isNotBlank() }?.let { raw ->
                runCatching { LabSessionState.valueOf(raw) }.getOrNull()
            }
            return id to state
        }

        fun readActiveSessionId(root: File): String? = readActiveSessionPointer(root)?.first

        private fun isSessionCompleted(journal: LabJournal): Boolean =
            journal.finished || journal.readCheckpointState()?.state == LabSessionState.COMPLETED

        /**
         * Returns the active resumable session on this device. Once the active/newest session completes,
         * never falls back to resurrecting older abandoned/stopped legacy runs. Skips 0-attempt aborts
         * and STOPPED-only ghost runs without real progress.
         */
        fun latestResumable(root: File): LabJournal? {
            val pointer = readActiveSessionPointer(root)
            if (pointer != null) {
                val (pointerId, pointerState) = pointer
                val pointedDir = File(root, pointerId)
                if (File(pointedDir, "run.json").isFile) {
                    val pointed = LabJournal(pointedDir)
                    if (isSessionCompleted(pointed) || pointerState == LabSessionState.COMPLETED) {
                        return null
                    }
                    if (pointed.hasResumableProgress()) {
                        return pointed
                    }
                }
            }
            for (dir in allRunDirectories(root)) {
                val journal = LabJournal(dir)
                if (isSessionCompleted(journal)) {
                    // A newer session completed cleanly; older unfinished dirs are historical archives.
                    return null
                }
                if (journal.hasResumableProgress()) {
                    return journal
                }
            }
            return null
        }

        fun inspectResumableSummary(root: File): LabSessionSummary {
            val allRuns = allRunDirectories(root)
            val resumable = latestResumable(root)
            if (resumable == null) {
                val latestCompleted = readActiveSessionId(root)?.let { id ->
                    val d = File(root, id)
                    if (File(d, "run.json").isFile) LabJournal(d).takeIf(::isSessionCompleted) else null
                } ?: allRuns.asSequence().map(::LabJournal).firstOrNull(::isSessionCompleted)
                if (latestCompleted != null) {
                    val hist = latestCompleted.history()
                    val cp = latestCompleted.readCheckpointState()
                    val completed = hist.count { it.verdict != LabVerdict.STOPPED && it.verdict != LabVerdict.INTERRUPTED }
                    val observed = hist.count { it.verdict == LabVerdict.OBSERVED }
                    val failed = hist.count { it.verdict == LabVerdict.FAILED || it.verdict == LabVerdict.MISMATCH }
                    val skipped = cp?.skippedKeys?.size ?: 0
                    val compReason = readJson(File(latestCompleted.directory, "summary.json"))?.optString("reason")
                        ?.takeIf { it.isNotBlank() } ?: cp?.stopReason
                    return LabSessionSummary(
                        hasResumableSession = false,
                        pastSessionCount = allRuns.size,
                        lastCompletedSessionId = latestCompleted.sessionId,
                        lastCompletedCount = completed,
                        lastObservedCount = observed,
                        lastFailedCount = failed,
                        lastSkippedCount = skipped,
                        lastCompletionReason = compReason,
                    )
                }
                return LabSessionSummary(
                    hasResumableSession = false,
                    pastSessionCount = allRuns.size,
                )
            }
            val cp = resumable.readCheckpointState()
            val hist = resumable.history()
            val completed = hist.count { it.verdict != LabVerdict.STOPPED && it.verdict != LabVerdict.INTERRUPTED }
            val failed = hist.count { it.verdict == LabVerdict.FAILED || it.verdict == LabVerdict.MISMATCH }
            val interrupted = hist.count { it.verdict == LabVerdict.INTERRUPTED }
            val header = resumable.headerJson()
            val powerTag = cp?.let { "manual=${it.manualPowerMode.name}, save=${it.lastAndroidPowerSaveMode}" }
            return LabSessionSummary(
                hasResumableSession = true,
                sessionId = resumable.sessionId,
                state = cp?.state ?: LabSessionState.PAUSED_USER,
                completedCount = completed,
                failedCount = failed,
                interruptedCount = interrupted,
                skippedCount = cp?.skippedKeys?.size ?: 0,
                currentStage = cp?.currentStage,
                lastStopReason = cp?.stopReason ?: resumable.readPauseReason(),
                apkRevision = cp?.latestApkRevision?.takeIf { it.isNotBlank() }
                    ?: header?.optString("latestApkSourceRevision")?.takeIf { it.isNotBlank() }
                    ?: header?.optString("apkSourceRevision"),
                pastSessionCount = allRuns.size,
                lastPowerProfileTag = powerTag,
            )
        }

        fun exportAll(root: File, output: OutputStream, additionalDirs: List<File> = emptyList()) {
            val files = LabStorageManager.collectUniqueFiles(root, additionalDirs)
            ZipOutputStream(output).use { zip ->
                files.forEach { (relPath, file) ->
                    zip.putNextEntry(ZipEntry(relPath))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }

        fun create(
            root: File,
            revision: String,
            initialPowerProfile: PowerProfileSnapshot? = null,
        ): LabJournal {
            val id = "${System.currentTimeMillis()}-${UUID.randomUUID()}"
            val journal = LabJournal(File(root, id), revision, initialPowerProfile)
            setActiveSession(root, journal.sessionId)
            return journal
        }
    }
}

internal fun LabCase.toJson() = json(
    "camera" to camera, "path" to path.name, "width" to width, "height" to height, "fps" to fps,
    "hfr" to hfr, "listedByApp" to listed, "longer" to longer, "previewOnly" to previewOnly,
    "flatToneMap" to flatToneMap, "retryIndex" to retryIndex, "variant" to variant,
)

private fun JSONObject.toCase() = LabCase(
    getString("camera"), LabPath.valueOf(getString("path")), getInt("width"), getInt("height"),
    getInt("fps"), getBoolean("hfr"), getBoolean("listedByApp"), getBoolean("longer"), getBoolean("previewOnly"),
    optBoolean("flatToneMap", false), optInt("retryIndex", 0), optString("variant", ""),
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
        // Android's AtomicFile.renameTo cannot replace an existing file on Windows host JVM tests.
        val pendingNew = File(file.parentFile, "${file.name}.new")
        if (pendingNew.isFile && System.getProperty("os.name").orEmpty().startsWith("Windows")) {
            java.nio.file.Files.move(
                pendingNew.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    } catch (error: Exception) {
        atomic.failWrite(stream)
        throw error
    }
}
