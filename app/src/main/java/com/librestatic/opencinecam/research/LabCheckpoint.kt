/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import org.json.JSONArray
import org.json.JSONObject

internal enum class LabSessionState {
    RUNNING,
    PAUSED_USER,
    PAUSED_THERMAL,
    PAUSED_STORAGE,
    PAUSED_BATTERY,
    PAUSED_ERROR,
    COMPLETED,
    ;

    companion object {
        fun fromName(raw: String?): LabSessionState =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: PAUSED_USER
    }
}

internal data class InFlightAttemptRecord(
    val id: String,
    val caseJson: JSONObject,
    val startedEpochMs: Long,
) {
    fun toJson(): JSONObject = json(
        "id" to id,
        "case" to caseJson,
        "startedEpochMs" to startedEpochMs,
    )

    companion object {
        fun fromJson(obj: JSONObject?): InFlightAttemptRecord? {
            if (obj == null) return null
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
            val caseObj = obj.optJSONObject("case") ?: return null
            return InFlightAttemptRecord(
                id = id,
                caseJson = caseObj,
                startedEpochMs = obj.optLong("startedEpochMs", 0L),
            )
        }
    }
}

internal data class ExplorationDecisionRecord(
    val attemptId: String,
    val key: String,
    val parent: String?,
    val hypothesis: String,
    val verdict: String,
    val stage: String,
    val epochMs: Long,
) {
    fun toJson(): JSONObject = json(
        "attemptId" to attemptId,
        "key" to key,
        "parent" to parent,
        "hypothesis" to hypothesis,
        "verdict" to verdict,
        "stage" to stage,
        "epochMs" to epochMs,
    )

    companion object {
        fun fromJson(obj: JSONObject?): ExplorationDecisionRecord? {
            if (obj == null) return null
            val attemptId = obj.optString("attemptId").takeIf { it.isNotBlank() } ?: return null
            val key = obj.optString("key").takeIf { it.isNotBlank() } ?: return null
            return ExplorationDecisionRecord(
                attemptId = attemptId,
                key = key,
                parent = obj.optString("parent").takeIf { it.isNotBlank() && it != "null" },
                hypothesis = obj.optString("hypothesis"),
                verdict = obj.optString("verdict"),
                stage = obj.optString("stage"),
                epochMs = obj.optLong("epochMs", 0L),
            )
        }
    }
}

internal data class LabCheckpointState(
    val schemaVersion: Int = SCHEMA_VERSION,
    val sessionId: String,
    val createdEpochMs: Long,
    val updatedEpochMs: Long,
    val initialApkRevision: String,
    val latestApkRevision: String,
    val revisionHistory: List<String>,
    val deviceFingerprint: String,
    val selectedCameraId: String?,
    val evidenceLimitBytes: Long,
    val manualPowerMode: ManualPowerMode,
    val lastAndroidPowerSaveMode: Boolean?,
    val state: LabSessionState,
    val stopReason: String?,
    val currentStage: String,
    val inFlightAttempt: InFlightAttemptRecord?,
    val completedKeys: List<String>,
    val failedKeys: List<String>,
    val skippedKeys: List<String>,
    val interruptedCounts: Map<String, Int>,
    val scheduledQueuePreview: List<String>,
    val explorationDecisions: List<ExplorationDecisionRecord>,
    val hypothesisStatuses: Map<String, String>,
    val powerModeHistory: List<JSONObject>,
) {
    fun toJson(): JSONObject = json(
        "schemaVersion" to schemaVersion,
        "sessionId" to sessionId,
        "createdEpochMs" to createdEpochMs,
        "updatedEpochMs" to updatedEpochMs,
        "initialApkRevision" to initialApkRevision,
        "latestApkRevision" to latestApkRevision,
        "revisionHistory" to JSONArray(revisionHistory),
        "deviceFingerprint" to deviceFingerprint,
        "selectedCameraId" to selectedCameraId,
        "evidenceLimitBytes" to evidenceLimitBytes,
        "manualPowerMode" to manualPowerMode.name,
        "lastAndroidPowerSaveMode" to lastAndroidPowerSaveMode,
        "state" to state.name,
        "stopReason" to stopReason,
        "currentStage" to currentStage,
        "inFlightAttempt" to inFlightAttempt?.toJson(),
        "completedKeys" to JSONArray(completedKeys),
        "failedKeys" to JSONArray(failedKeys),
        "skippedKeys" to JSONArray(skippedKeys),
        "interruptedCounts" to JSONObject().apply {
            interruptedCounts.forEach { (k, v) -> put(k, v) }
        },
        "scheduledQueuePreview" to JSONArray(scheduledQueuePreview),
        "explorationDecisions" to JSONArray(explorationDecisions.map { it.toJson() }),
        "hypothesisStatuses" to JSONObject().apply {
            hypothesisStatuses.forEach { (k, v) -> put(k, v) }
        },
        "powerModeHistory" to JSONArray(powerModeHistory),
    )

    companion object {
        const val SCHEMA_VERSION = 3

        fun fromJson(obj: JSONObject?): LabCheckpointState? {
            if (obj == null) return null
            val sessionId = obj.optString("sessionId").takeIf { it.isNotBlank() } ?: return null
            fun stringList(name: String): List<String> = buildList {
                obj.optJSONArray(name)?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }
            val interrupted = linkedMapOf<String, Int>().apply {
                obj.optJSONObject("interruptedCounts")?.let { o ->
                    val keys = o.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        put(k, o.optInt(k, 0))
                    }
                }
            }
            val hypotheses = linkedMapOf<String, String>().apply {
                obj.optJSONObject("hypothesisStatuses")?.let { o ->
                    val keys = o.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        put(k, o.optString(k))
                    }
                }
            }
            val decisions = buildList {
                obj.optJSONArray("explorationDecisions")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        ExplorationDecisionRecord.fromJson(arr.optJSONObject(i))?.let(::add)
                    }
                }
            }
            val powerHistory = buildList {
                obj.optJSONArray("powerModeHistory")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let(::add)
                    }
                }
            }
            return LabCheckpointState(
                schemaVersion = obj.optInt("schemaVersion", SCHEMA_VERSION),
                sessionId = sessionId,
                createdEpochMs = obj.optLong("createdEpochMs", 0L),
                updatedEpochMs = obj.optLong("updatedEpochMs", 0L),
                initialApkRevision = obj.optString("initialApkRevision"),
                latestApkRevision = obj.optString("latestApkRevision"),
                revisionHistory = stringList("revisionHistory"),
                deviceFingerprint = obj.optString("deviceFingerprint"),
                selectedCameraId = obj.optString("selectedCameraId").takeIf { it.isNotBlank() && it != "null" },
                evidenceLimitBytes = obj.optLong("evidenceLimitBytes", LabStorageManager.DEFAULT_EVIDENCE_LIMIT_BYTES),
                manualPowerMode = ManualPowerMode.fromName(obj.optString("manualPowerMode")),
                lastAndroidPowerSaveMode = if (obj.isNull("lastAndroidPowerSaveMode")) null else obj.optBoolean("lastAndroidPowerSaveMode"),
                state = LabSessionState.fromName(obj.optString("state")),
                stopReason = obj.optString("stopReason").takeIf { it.isNotBlank() && it != "null" },
                currentStage = obj.optString("currentStage"),
                inFlightAttempt = InFlightAttemptRecord.fromJson(obj.optJSONObject("inFlightAttempt")),
                completedKeys = stringList("completedKeys"),
                failedKeys = stringList("failedKeys"),
                skippedKeys = stringList("skippedKeys"),
                interruptedCounts = interrupted,
                scheduledQueuePreview = stringList("scheduledQueuePreview"),
                explorationDecisions = decisions,
                hypothesisStatuses = hypotheses,
                powerModeHistory = powerHistory,
            )
        }
    }
}

internal data class LabSessionSummary(
    val hasResumableSession: Boolean,
    val sessionId: String? = null,
    val state: LabSessionState? = null,
    val completedCount: Int = 0,
    val failedCount: Int = 0,
    val interruptedCount: Int = 0,
    val skippedCount: Int = 0,
    val currentStage: String? = null,
    val lastStopReason: String? = null,
    val apkRevision: String? = null,
    val pastSessionCount: Int = 0,
    val lastPowerProfileTag: String? = null,
    val lastCompletedSessionId: String? = null,
    val lastCompletedCount: Int = 0,
    val lastObservedCount: Int = 0,
    val lastFailedCount: Int = 0,
    val lastSkippedCount: Int = 0,
    val lastCompletionReason: String? = null,
)
