/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.SystemClock
import com.librestatic.opencinecam.camera.Camera2PreviewEngine
import com.librestatic.opencinecam.camera.CaptureOwnerAdmission
import com.librestatic.opencinecam.diagnostics.DeviceQuirksProvider
import com.librestatic.opencinecam.media.raw.NativeRawCodec
import com.librestatic.opencinecam.rawvideo.RawQualificationPlan
import com.librestatic.opencinecam.rawvideo.RawQualificationProgress
import com.librestatic.opencinecam.rawvideo.RawQualificationStage
import com.librestatic.opencinecam.rawvideo.RawQualificationTiming
import com.librestatic.opencinecam.rawvideo.RawVideoQualifier
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

internal class ResearchLaboratory(
    private val context: Context,
    private val cameraId: String?,
    private val status: (String) -> Unit,
    private val onProgress: ((LabUiProgress) -> Unit)? = null,
) {
    private val cancelled = AtomicBoolean()
    private val root = evidenceRoot(context)
    private val explorationGuard = LabExplorationGuard()
    private val evidenceLimitBytes = LabStorageManager.loadEvidenceLimitBytes(context)
    private val thermalPolicy = LabThermalPolicy(evidenceLimitBytes = evidenceLimitBytes)
    private var activeJournal: LabJournal? = null
    private var nextEnvironmentalCheck = 0L
    private var lastThermalStatus: Int? = null
    private var lastSample: ThermalSample? = null
    private var lastPowerSnapshot: PowerProfileSnapshot? = null

    fun cancel() { cancelled.set(true) }

    fun run(resume: Boolean): String {
        check(exclusive.compareAndSet(false, true)) { "A research run is already closing." }
        try {
            val initialPower = LabPowerProfile.capture(context)
            lastPowerSnapshot = initialPower
            val revision = context.getString(com.librestatic.opencinecam.R.string.research_lab_revision)
            val build = if (revision == "local-unknown") {
                "local-" + context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            } else revision
            val resumable = if (resume) LabJournal.latestResumable(root) else null
            val journal = resumable ?: LabJournal.create(root, build, initialPower)
            LabJournal.setActiveSession(root, journal.sessionId)
            activeJournal = journal
            if (resume && resumable != null && !journal.compatibleWith(build) && journal.resumableOnDevice()) {
                journal.recordApkRevisionTransition(build)
            }
            journal.recoverInterrupted()
            val startSample = sampleThermal(context, root, evidenceLimitBytes).also { lastSample = it }
            journal.appendTelemetry(startSample.toJson())
            when (val decision = thermalPolicy.evaluate(startSample, betweenAttempts = true)) {
                is ThermalDecision.HardStop -> {
                    savePausedCheckpoint(journal, build, initialPower, LabSessionState.PAUSED_STORAGE, decision.reason, "Startup")
                    journal.recordStop(decision.reason, startSample.toJson())
                    throw LabStop(decision.reason, stateFromStopReason(decision.reason))
                }
                else -> Unit
            }
            val manager = context.getSystemService(CameraManager::class.java)
            val scanner = Camera2PreviewEngine(context, deviceQuirks = DeviceQuirksProvider.lazy(context))
            val descriptors = try { scanner.descriptors(1920, 1080) } finally {
                scanner.closeAsync().get(8, TimeUnit.SECONDS)
            }
            val cameraKey = journal.selectedCamera() ?: cameraId
            val descriptor = descriptors.firstOrNull { it.cameraId == cameraKey }
                ?: descriptors.firstOrNull { it.lensFacing == CameraCharacteristics.LENS_FACING_BACK }
                ?: descriptors.firstOrNull() ?: error("No public Camera2 camera.")
            check(cameraKey == null || cameraKey == descriptor.cameraId || journal.history().isEmpty()) {
                "The previous run's camera is unavailable."
            }
            val profiles = LabProfiles(manager, descriptor)
            val candidates = profiles.cases()
            val knowledge = LabKnowledgeBase.loadOrCreate(journal.directory)
            val inv = try {
                profiles.inventory(descriptors)
                    .put("nativeBlockPackAvailable", NativeRawCodec.available)
                    .put("apkSourceRevision", build)
                    .put("powerProfile", initialPower.toJson())
            } catch (e: VirtualMachineError) {
                throw e
            } catch (t: Throwable) {
                val errMsg = "${t.javaClass.name}: ${t.message}"
                knowledge.recordError("inventory", null, t.javaClass.name, t.message)
                journal.appendTelemetry(json(
                    "event" to "inventory_error",
                    "errorClass" to t.javaClass.name,
                    "message" to t.message?.take(400),
                    "sdkInt" to Build.VERSION.SDK_INT,
                ))
                json(
                    "selectedCamera" to descriptor.cameraId,
                    "sdkInt" to Build.VERSION.SDK_INT,
                    "apkSourceRevision" to build,
                    "powerProfile" to initialPower.toJson(),
                    "inventoryFatalError" to errMsg,
                )
            }
            journal.inventory(inv)
            knowledge.ingestInventory(inv)
            knowledge.syncFromPriorAttempts(journal)
            val planner = LabAutonomousPlanner(candidates, knowledge)
            val video = LabVideo(context, profiles, ::checkBudget)
            val regularLog = LabRegularLogRunner(context, descriptor, ::checkBudget)
            val sourceProbe = LabSourceProbe(context, descriptor, ::checkBudget)
            return explore(journal, build, descriptor.cameraId, knowledge, planner, video, regularLog, sourceProbe)
        } catch (stop: LabStop) {
            activeJournal?.let { j ->
                runCatching {
                    val kb = LabKnowledgeBase.loadOrCreate(j.directory)
                    LabReportGenerator.generate(j.directory, j.inventoryJson(), j.history(), kb, stop.message)
                }
            }
            return stop.message.orEmpty()
        } catch (e: VirtualMachineError) {
            throw e
        } catch (t: Throwable) {
            val reason = "${t.javaClass.simpleName}: ${t.message ?: "Unexpected failure"}"
            activeJournal?.let { j ->
                runCatching {
                    j.recordStop(reason, lastSample?.toJson())
                    val kb = LabKnowledgeBase.loadOrCreate(j.directory)
                    kb.recordError("run_setup", null, t.javaClass.name, t.message)
                    kb.save()
                    val buildRev = context.getString(com.librestatic.opencinecam.R.string.research_lab_revision)
                    savePausedCheckpoint(
                        j,
                        buildRev,
                        lastPowerSnapshot ?: LabPowerProfile.capture(context),
                        LabSessionState.PAUSED_ERROR,
                        reason,
                        "Setup",
                    )
                    LabReportGenerator.generate(j.directory, j.inventoryJson(), j.history(), kb, reason)
                }
            }
            return reason
        } finally {
            activeJournal = null
            exclusive.set(false)
        }
    }

    private fun explore(
        journal: LabJournal,
        apkRevision: String,
        selectedCameraId: String,
        knowledge: LabKnowledgeBase,
        planner: LabAutonomousPlanner,
        video: LabVideo,
        regularLog: LabRegularLogRunner,
        sourceProbe: LabSourceProbe,
    ): String {
        val priorCheckpoint = journal.readCheckpointState()
        val interruptedCounts = priorCheckpoint?.interruptedCounts?.toMutableMap() ?: linkedMapOf()
        val skippedKeys = priorCheckpoint?.skippedKeys?.toMutableSet() ?: linkedSetOf()
        val decisions = priorCheckpoint?.explorationDecisions?.toMutableList() ?: mutableListOf()
        val powerHistory = priorCheckpoint?.powerModeHistory?.toMutableList() ?: mutableListOf()
        val revHistory = (priorCheckpoint?.revisionHistory.orEmpty() + apkRevision).filter { it.isNotBlank() }.distinct()
        val createdEpoch = priorCheckpoint?.createdEpochMs?.takeIf { it > 0L }
            ?: journal.headerJson()?.optLong("createdEpochMs", System.currentTimeMillis())
            ?: System.currentTimeMillis()
        var lastRecordedPowerTag = powerHistory.lastOrNull()?.optString("profileTag")

        fun buildCheckpoint(
            state: LabSessionState,
            stopReason: String?,
            currentStage: String,
            inFlight: InFlightAttemptRecord?,
            history: List<LabResult>,
            power: PowerProfileSnapshot,
            remainingPlanned: Int,
        ): LabCheckpointState {
            val completedKeys = history
                .filter { it.verdict != LabVerdict.STOPPED && it.verdict != LabVerdict.INTERRUPTED }
                .map { it.attempt.key }
                .distinct()
            val failedKeys = history
                .filter { it.verdict == LabVerdict.FAILED || it.verdict == LabVerdict.MISMATCH }
                .map { it.attempt.key }
                .distinct()
            val hypMap = knowledge.hypothesesList().associate { it.id to it.status.name }
            return LabCheckpointState(
                sessionId = journal.sessionId,
                createdEpochMs = createdEpoch,
                updatedEpochMs = System.currentTimeMillis(),
                initialApkRevision = priorCheckpoint?.initialApkRevision?.takeIf { it.isNotBlank() } ?: apkRevision,
                latestApkRevision = apkRevision,
                revisionHistory = revHistory,
                deviceFingerprint = Build.FINGERPRINT,
                selectedCameraId = selectedCameraId,
                evidenceLimitBytes = evidenceLimitBytes,
                manualPowerMode = power.manualPowerMode,
                lastAndroidPowerSaveMode = power.androidPowerSaveMode,
                state = state,
                stopReason = stopReason,
                currentStage = currentStage,
                inFlightAttempt = inFlight,
                completedKeys = completedKeys,
                failedKeys = failedKeys,
                skippedKeys = skippedKeys.toList(),
                interruptedCounts = interruptedCounts.toMap(),
                scheduledQueuePreview = listOf("remaining_planned=$remainingPlanned"),
                explorationDecisions = decisions.toList(),
                hypothesisStatuses = hypMap,
                powerModeHistory = powerHistory.toList(),
            )
        }

        fun publishProgress(
            stageLabel: String,
            historySnapshot: List<LabResult>,
            remainingPlanned: Int,
            hypothesis: String?,
            sample: ThermalSample?,
            power: PowerProfileSnapshot,
            inThermalPause: Boolean = false,
            completed: Boolean = false,
        ) {
            val completedCount = historySnapshot.count { it.verdict != LabVerdict.STOPPED && it.verdict != LabVerdict.INTERRUPTED }
            val skippedCount = skippedKeys.size
            val attemptNum = if (completed || remainingPlanned == 0) completedCount else completedCount + 1
            val socTempC = sample?.sysZones?.get("soc_thermal")?.let(::normalizeSysZoneC)
            onProgress?.invoke(
                LabUiProgress(
                    stageLabel = stageLabel,
                    attemptNumber = attemptNum,
                    maxAttempts = (completedCount + remainingPlanned).coerceAtLeast(1),
                    completedAttempts = completedCount,
                    remainingPlanned = remainingPlanned,
                    skippedAttempts = skippedCount,
                    completed = completed,
                    hypothesis = hypothesis,
                    confirmedHypotheses = knowledge.confirmedCount(),
                    totalHypotheses = knowledge.totalHypotheses(),
                    batteryTempC = if (sample != null && sample.batteryTempTenthsC >= 0) sample.batteryTempTenthsC / 10.0 else null,
                    thermalStatus = sample?.thermalStatus,
                    thermalStatusName = sample?.thermalStatus?.let(::thermalStatusLabel),
                    inThermalPause = inThermalPause,
                    androidPowerSaveMode = power.androidPowerSaveMode,
                    manualPowerMode = power.manualPowerMode,
                    socTempC = socTempC,
                ),
            )
        }

        while (!cancelled.get()) {
            val historyBeforeWait = journal.history()
            val clearanceSample = awaitThermalClearance(
                journal = journal,
                knowledge = knowledge,
                planner = planner,
                history = historyBeforeWait,
                buildCheckpoint = { reason, sample ->
                    val pow = sample.powerProfile ?: lastPowerSnapshot ?: LabPowerProfile.capture(context)
                    buildCheckpoint(
                        state = LabSessionState.PAUSED_THERMAL,
                        stopReason = reason,
                        currentStage = "Thermal Cooldown",
                        inFlight = null,
                        history = historyBeforeWait,
                        power = pow,
                        remainingPlanned = planner.remainingPlannedCount(
                            effectiveHistoryFor(historyBeforeWait, interruptedCounts, skippedKeys),
                            skippedKeys,
                        ),
                    )
                },
            )
            lastSample = clearanceSample
            val history = journal.history()
            val effectiveHistory = effectiveHistoryFor(history, interruptedCounts, skippedKeys)
            val next = planner.next(effectiveHistory, skippedKeys)
            if (next == null) {
                val reason = "All autonomous research objectives completed."
                val pow = LabPowerProfile.capture(context).also { lastPowerSnapshot = it }
                journal.saveCheckpointState(
                    buildCheckpoint(LabSessionState.COMPLETED, reason, "Complete", null, history, pow, 0),
                )
                journal.finish(reason)
                LabJournal.setActiveSession(root, journal.sessionId, LabSessionState.COMPLETED)
                publishProgress(
                    stageLabel = "Complete",
                    historySnapshot = history,
                    remainingPlanned = 0,
                    hypothesis = reason,
                    sample = lastSample,
                    power = pow,
                    completed = true,
                )
                runCatching { LabReportGenerator.generate(journal.directory, journal.inventoryJson(), history, knowledge, reason) }
                return "Research complete. Evidence is ready to export."
            }

            val guardReason = explorationGuard.checkNext(effectiveHistory, next.attempt, interruptedCounts)
            if (guardReason != null) {
                skippedKeys += next.attempt.key
                journal.appendTelemetry(json("event" to "exploration_guard_skip", "key" to next.attempt.key, "reason" to guardReason))
                val afterSkipEffective = effectiveHistoryFor(history, interruptedCounts, skippedKeys)
                publishProgress(
                    stageLabel = planner.stageLabel(next.attempt),
                    historySnapshot = history,
                    remainingPlanned = planner.remainingPlannedCount(afterSkipEffective, skippedKeys),
                    hypothesis = next.hypothesis,
                    sample = lastSample,
                    power = lastPowerSnapshot ?: LabPowerProfile.capture(context),
                )
                continue
            }

            val estimateBytes = LabStorageManager.estimateAttemptBytes(next.attempt)
            val spaceDecision = LabStorageManager.checkSpace(
                root = root,
                limitBytes = evidenceLimitBytes,
                upcomingEstimateBytes = estimateBytes,
            )
            if (!spaceDecision.allowed) {
                val reason = "${spaceDecision.reason ?: "Storage limit reached."} Progress saved; export or free space then tap Resume."
                val currentSample = sampleThermal(context, root, evidenceLimitBytes).also { lastSample = it }
                val pow = currentSample.powerProfile ?: LabPowerProfile.capture(context)
                val rem = planner.remainingPlannedCount(effectiveHistory, skippedKeys)
                journal.saveCheckpointState(
                    buildCheckpoint(
                        state = LabSessionState.PAUSED_STORAGE,
                        stopReason = reason,
                        currentStage = planner.stageLabel(next.attempt),
                        inFlight = null,
                        history = history,
                        power = pow,
                        remainingPlanned = rem,
                    ),
                )
                LabJournal.setActiveSession(root, journal.sessionId, LabSessionState.PAUSED_STORAGE)
                publishProgress(planner.stageLabel(next.attempt), history, rem, reason, currentSample, pow)
                journal.recordStop(reason, currentSample.toJson())
                runCatching { LabReportGenerator.generate(journal.directory, journal.inventoryJson(), history, knowledge, reason) }
                return reason
            }

            val powerSnapshot = LabPowerProfile.capture(context).also { lastPowerSnapshot = it }
            if (powerSnapshot.profileTag != lastRecordedPowerTag) {
                powerHistory += powerSnapshot.toJson()
                lastRecordedPowerTag = powerSnapshot.profileTag
            }

            val stageTitle = planner.stageLabel(next.attempt)
            status("$stageTitle · ${next.attempt.label}")
            val sample = lastSample ?: sampleThermal(context, root, evidenceLimitBytes).also { lastSample = it }
            val remainingPlanned = planner.remainingPlannedCount(effectiveHistory, skippedKeys)
            publishProgress(stageTitle, history, remainingPlanned, next.hypothesis, sample, powerSnapshot)

            val attempt = journal.begin(next, apkRevision, powerSnapshot)
            attempt.notice(sample.toJson())
            val inFlight = InFlightAttemptRecord(
                id = attempt.directory.name,
                caseJson = next.attempt.toJson(),
                startedEpochMs = System.currentTimeMillis(),
            )
            journal.saveCheckpointState(
                buildCheckpoint(LabSessionState.RUNNING, null, stageTitle, inFlight, history, powerSnapshot, remainingPlanned),
            )

            val outcome = execute(next.attempt, attempt, knowledge, video, regularLog, sourceProbe)
            val updatedHistory = journal.history()
            val updatedEffective = effectiveHistoryFor(updatedHistory, interruptedCounts, skippedKeys)
            val updatedRemaining = planner.remainingPlannedCount(updatedEffective, skippedKeys)
            val postPower = LabPowerProfile.capture(context).also { lastPowerSnapshot = it }

            when (outcome) {
                is AttemptOutcome.Completed -> {
                    val lastRes = updatedHistory.lastOrNull { it.id == attempt.directory.name }
                    decisions += ExplorationDecisionRecord(
                        attemptId = attempt.directory.name,
                        key = next.attempt.key,
                        parent = next.parent,
                        hypothesis = next.hypothesis,
                        verdict = lastRes?.verdict?.name ?: LabVerdict.FAILED.name,
                        stage = lastRes?.stage?.name ?: LabStage.REQUESTED.name,
                        epochMs = System.currentTimeMillis(),
                    )
                    journal.saveCheckpointState(
                        buildCheckpoint(LabSessionState.RUNNING, null, stageTitle, null, updatedHistory, postPower, updatedRemaining),
                    )
                    publishProgress(stageTitle, updatedHistory, updatedRemaining, next.hypothesis, lastSample, postPower)
                    runCatching {
                        LabReportGenerator.generate(journal.directory, journal.inventoryJson(), updatedHistory, knowledge, null)
                    }.onFailure { err ->
                        journal.appendTelemetry(json("event" to "report_error", "error" to "${err.javaClass.name}: ${err.message}"))
                    }
                }
                is AttemptOutcome.ThermalSuspended -> {
                    interruptedCounts[next.attempt.key] = (interruptedCounts[next.attempt.key] ?: 0) + 1
                    journal.saveCheckpointState(
                        buildCheckpoint(
                            LabSessionState.PAUSED_THERMAL,
                            outcome.reason,
                            stageTitle,
                            null,
                            updatedHistory,
                            postPower,
                            updatedRemaining,
                        ),
                    )
                    runCatching {
                        LabReportGenerator.generate(journal.directory, journal.inventoryJson(), updatedHistory, knowledge, outcome.reason)
                    }
                    // Loop immediately enters awaitThermalClearance() and auto-resumes when battery <= 35 °C.
                    continue
                }
                is AttemptOutcome.Paused -> {
                    journal.saveCheckpointState(
                        buildCheckpoint(
                            outcome.state,
                            outcome.reason,
                            stageTitle,
                            null,
                            updatedHistory,
                            postPower,
                            updatedRemaining,
                        ),
                    )
                    LabJournal.setActiveSession(root, journal.sessionId, outcome.state)
                    publishProgress(stageTitle, updatedHistory, updatedRemaining, outcome.reason, lastSample, postPower)
                    runCatching {
                        LabReportGenerator.generate(journal.directory, journal.inventoryJson(), updatedHistory, knowledge, outcome.reason)
                    }
                    return outcome.reason
                }
            }

            val until = SystemClock.elapsedRealtime() + 1_000
            while (SystemClock.elapsedRealtime() < until) {
                try {
                    checkBudget()
                } catch (pause: ThermalPauseInterrupt) {
                    status(pause.reason)
                    break
                }
                Thread.sleep(100)
            }
        }

        val cancelReason = "Research paused. The saved run can be resumed."
        val hist = journal.history()
        val cancelPower = lastPowerSnapshot ?: LabPowerProfile.capture(context)
        val cancelRemaining = planner.remainingPlannedCount(effectiveHistoryFor(hist, interruptedCounts, skippedKeys), skippedKeys)
        journal.saveCheckpointState(
            buildCheckpoint(
                LabSessionState.PAUSED_USER,
                cancelReason,
                "Paused",
                null,
                hist,
                cancelPower,
                cancelRemaining,
            ),
        )
        LabJournal.setActiveSession(root, journal.sessionId, LabSessionState.PAUSED_USER)
        publishProgress("Paused", hist, cancelRemaining, cancelReason, lastSample, cancelPower)
        journal.recordStop(cancelReason, lastSample?.toJson())
        runCatching { LabReportGenerator.generate(journal.directory, journal.inventoryJson(), hist, knowledge, cancelReason) }
        return cancelReason
    }

    private fun effectiveHistoryFor(
        history: List<LabResult>,
        interruptedCounts: Map<String, Int>,
        skippedKeys: Set<String>,
    ): List<LabResult> = history.filter { res ->
        res.verdict != LabVerdict.STOPPED &&
            (res.verdict != LabVerdict.INTERRUPTED ||
                (interruptedCounts[res.attempt.key] ?: 0) >= LabExplorationGuard.MAX_INTERRUPT_RETRIES_PER_KEY ||
                res.attempt.key in skippedKeys)
    }

    private fun savePausedCheckpoint(
        journal: LabJournal,
        apkRevision: String,
        power: PowerProfileSnapshot,
        state: LabSessionState,
        reason: String,
        stage: String,
    ) {
        val hist = journal.history()
        journal.saveCheckpointState(
            LabCheckpointState(
                sessionId = journal.sessionId,
                createdEpochMs = journal.headerJson()?.optLong("createdEpochMs", System.currentTimeMillis()) ?: System.currentTimeMillis(),
                updatedEpochMs = System.currentTimeMillis(),
                initialApkRevision = apkRevision,
                latestApkRevision = apkRevision,
                revisionHistory = listOf(apkRevision),
                deviceFingerprint = Build.FINGERPRINT,
                selectedCameraId = journal.selectedCamera(),
                evidenceLimitBytes = evidenceLimitBytes,
                manualPowerMode = power.manualPowerMode,
                lastAndroidPowerSaveMode = power.androidPowerSaveMode,
                state = state,
                stopReason = reason,
                currentStage = stage,
                inFlightAttempt = null,
                completedKeys = hist.filter { it.verdict != LabVerdict.STOPPED && it.verdict != LabVerdict.INTERRUPTED }.map { it.attempt.key }.distinct(),
                failedKeys = hist.filter { it.verdict == LabVerdict.FAILED || it.verdict == LabVerdict.MISMATCH }.map { it.attempt.key }.distinct(),
                skippedKeys = emptyList(),
                interruptedCounts = emptyMap(),
                scheduledQueuePreview = emptyList(),
                explorationDecisions = emptyList(),
                hypothesisStatuses = emptyMap(),
                powerModeHistory = listOf(power.toJson()),
            ),
        )
    }

    private fun execute(
        case: LabCase,
        attempt: LabJournal.Attempt,
        knowledge: LabKnowledgeBase,
        video: LabVideo,
        regularLog: LabRegularLogRunner,
        sourceProbe: LabSourceProbe,
    ): AttemptOutcome = executeIsolatedAttempt(attempt, case, knowledge) {
        when (case.path) {
            LabPath.RAW_CAMERA, LabPath.RAW_NATIVE -> runRaw(case, attempt)
            LabPath.DIRECT_HEVC, LabPath.DIRECT_HEVC_PREVIEW, LabPath.DIRECT_HEVC_SURFACETEXTURE, LabPath.DIRECT_HEVC_SURFACETEXTURE_360P ->
                LabDirectRecorder(context, ::checkBudget).run(case, attempt)
            LabPath.CODEC_LEVEL_PAIR -> runCodecLevelPair(case, attempt, ::checkBudget)
            LabPath.CODEC_SYNTHETIC_MATRIX -> LabSyntheticEncoderProbe.run(attempt, ::checkBudget)
            LabPath.LOG_REGULAR_4K30, LabPath.LOG_REGULAR_4K60, LabPath.GPU_HEVC_4K30, LabPath.GPU_HEVC_4K60 -> {
                val main10 = case.path == LabPath.LOG_REGULAR_4K30 || case.path == LabPath.LOG_REGULAR_4K60
                val override = knowledge.bestOverrideFor(case.width, case.height, case.fps, main10 = main10)
                regularLog.run(case, attempt, override)
            }
            LabPath.ISP_TONEMAP_AB, LabPath.ISP_COLOR_CORRECTION_PROBE, LabPath.SOURCE_FORMAT_PROBE, LabPath.ISP_CUVA_HDR_PROBE ->
                sourceProbe.run(case, attempt)
            LabPath.BREAKTHROUGH_4K60_INPUT, LabPath.BREAKTHROUGH_CUVA_VIDEO, LabPath.BREAKTHROUGH_SYNTH_4K60_PRIORITY,
            LabPath.BREAKTHROUGH_HIDDEN_RAW, LabPath.BREAKTHROUGH_BB10_CLIPS ->
                runBreakthroughProbe(case, attempt)
            else -> video.run(case, attempt)
        }
    }

    private fun runBreakthroughProbe(case: LabCase, attempt: LabJournal.Attempt): Pair<LabVerdict, String?> {
        val summary = when (case.path) {
            LabPath.BREAKTHROUGH_4K60_INPUT -> LabBreakthroughProbes.runProbe1_4k60CameraInputIsolation(context, attempt.directory, ::checkBudget)
            LabPath.BREAKTHROUGH_CUVA_VIDEO -> LabBreakthroughProbes.runProbe2_CuvaHdrDirectAndGpuRecording(context, attempt.directory, ::checkBudget)
            LabPath.BREAKTHROUGH_SYNTH_4K60_PRIORITY -> LabBreakthroughProbes.runProbe3_Synthetic4k60ThroughputAndPriority(context, attempt.directory, ::checkBudget)
            LabPath.BREAKTHROUGH_HIDDEN_RAW -> LabBreakthroughProbes.runProbe4_HiddenRawSensorAdmission(context, attempt.directory, ::checkBudget)
            LabPath.BREAKTHROUGH_BB10_CLIPS -> LabBreakthroughProbes.runProbe5_VendorByteBuffer10BitClips(context, attempt.directory, ::checkBudget)
            else -> error("Unsupported breakthrough probe: ${case.path}")
        }
        java.io.File(attempt.directory, "${case.path.name.lowercase(java.util.Locale.ROOT)}.json")
            .writeText(summary.toString(2), Charsets.UTF_8)
        attempt.event(LabStage.FILE, summary)
        val count = summary.optJSONArray("arms")?.length()
            ?: summary.optJSONArray("results")?.length()
            ?: 0
        return if (count > 0) {
            LabVerdict.OBSERVED to "${case.path.name} completed ($count entries)"
        } else {
            LabVerdict.FAILED to "${case.path.name} produced no entries"
        }
    }

    private fun runRaw(case: LabCase, journal: LabJournal.Attempt): Pair<LabVerdict, String?> {
        val encode = case.path == LabPath.RAW_NATIVE
        var lastPhase: String? = null
        var measured = false
        var stop: Exception? = null
        lateinit var qualifier: RawVideoQualifier
        qualifier = RawVideoQualifier(
            context = context,
            cameraIds = listOf(case.camera),
            plan = RawQualificationPlan(
                specs = listOf(if (encode) "full@30+bp" else "full@30"),
                compressedTwins = false,
                stages = setOf(if (encode) RawQualificationStage.ENCODE else RawQualificationStage.CAMERA),
            ),
            timing = RawQualificationTiming(cameraCheckSeconds = 3, cameraCheckWarmupSeconds = 1, encodeBenchSeconds = 3),
            listener = object : RawVideoQualifier.Listener {
                override fun onProgress(progress: RawQualificationProgress) {
                    try {
                        checkBudget()
                    } catch (reason: ThermalPauseInterrupt) {
                        stop = reason
                        qualifier.cancel()
                    } catch (reason: LabStop) {
                        stop = reason
                        qualifier.cancel()
                    }
                    if (lastPhase != progress.phase.name) {
                        lastPhase = progress.phase.name
                        journal.event(LabStage.RAW, json("phase" to lastPhase, "spec" to progress.spec))
                    }
                }

                override fun onResult(result: JSONObject) {
                    if (result.optString("status") == "PASS") measured = true
                    journal.event(LabStage.RAW, result)
                }
            },
            ringTargetBytes = 128L * 1024 * 1024,
        )
        val lease = CaptureOwnerAdmission.processGlobal.acquire()
        val result = try {
            lease.ready().get(8, TimeUnit.SECONDS)
            checkBudget()
            qualifier.run()
        } finally {
            lease.releaseAfter(CompletableFuture.completedFuture(Unit))
        }
        journal.notice(json("event" to "raw_qualification_summary", "result" to result.report))
        stop?.let { throw it }
        return if (measured) LabVerdict.OBSERVED to null else LabVerdict.FAILED to result.reasonCode
    }

    private fun awaitThermalClearance(
        journal: LabJournal,
        knowledge: LabKnowledgeBase,
        planner: LabAutonomousPlanner,
        history: List<LabResult>,
        buildCheckpoint: (String, ThermalSample) -> LabCheckpointState,
    ): ThermalSample {
        var announcedWait = false
        while (true) {
            checkTimeAndCancel()
            val sample = sampleThermal(context, root, evidenceLimitBytes)
            lastSample = sample
            val statusChanged = lastThermalStatus != null && lastThermalStatus != sample.thermalStatus
            lastThermalStatus = sample.thermalStatus
            journal.appendTelemetry(sample.toJson())
            nextEnvironmentalCheck = SystemClock.elapsedRealtime() + TELEMETRY_INTERVAL_MS
            when (val decision = thermalPolicy.evaluate(sample, betweenAttempts = true)) {
                is ThermalDecision.Continue -> {
                    if (announcedWait) {
                        journal.appendTelemetry(sample.toJson().put("event", "thermal_auto_resumed"))
                        status("Thermal cleared (${"%.1f".format(java.util.Locale.ROOT, sample.batteryTempTenthsC / 10.0)} °C) — auto-resuming research…")
                    }
                    return sample
                }
                is ThermalDecision.CooldownWait -> {
                    status(decision.reason)
                    val completed = history.count { it.verdict != LabVerdict.STOPPED && it.verdict != LabVerdict.INTERRUPTED }
                    val remaining = planner.remainingPlannedCount(history)
                    val pow = sample.powerProfile ?: LabPowerProfile.capture(context)
                    onProgress?.invoke(
                        LabUiProgress(
                            stageLabel = "Thermal Pause (Auto-Resumes <= 35 °C)",
                            attemptNumber = completed,
                            maxAttempts = (completed + remaining).coerceAtLeast(1),
                            completedAttempts = completed,
                            remainingPlanned = remaining,
                            hypothesis = decision.reason,
                            confirmedHypotheses = knowledge.confirmedCount(),
                            totalHypotheses = knowledge.totalHypotheses(),
                            batteryTempC = if (sample.batteryTempTenthsC >= 0) sample.batteryTempTenthsC / 10.0 else null,
                            thermalStatus = sample.thermalStatus,
                            thermalStatusName = thermalStatusLabel(sample.thermalStatus),
                            inThermalPause = true,
                            androidPowerSaveMode = pow.androidPowerSaveMode,
                            manualPowerMode = pow.manualPowerMode,
                            socTempC = sample.sysZones["soc_thermal"]?.let(::normalizeSysZoneC),
                        ),
                    )
                    if (!announcedWait || statusChanged) {
                        announcedWait = true
                        journal.appendTelemetry(sample.toJson().put("event", "thermal_cooldown_wait").put("reason", decision.reason))
                        journal.saveCheckpointState(buildCheckpoint(decision.reason, sample))
                    }
                    Thread.sleep(COOLDOWN_POLL_MS)
                }
                is ThermalDecision.HardStop -> {
                    val stopState = stateFromStopReason(decision.reason)
                    journal.saveCheckpointState(buildCheckpoint(decision.reason, sample).copy(state = stopState))
                    journal.recordStop(decision.reason, sample.toJson())
                    throw LabStop(decision.reason, stopState)
                }
            }
        }
    }

    private fun checkBudget() = checkBudget(forceSample = false)

    private fun checkBudget(forceSample: Boolean) {
        checkTimeAndCancel()
        val now = SystemClock.elapsedRealtime()
        if (!forceSample && now < nextEnvironmentalCheck) return
        nextEnvironmentalCheck = now + TELEMETRY_INTERVAL_MS
        val sample = sampleThermal(context, root, evidenceLimitBytes, includePowerProfile = false)
        lastSample = sample
        val statusChanged = lastThermalStatus != null && lastThermalStatus != sample.thermalStatus
        lastThermalStatus = sample.thermalStatus
        activeJournal?.appendTelemetry(
            sample.toJson().apply { if (statusChanged) put("statusTransition", true) },
        )
        when (val decision = thermalPolicy.evaluate(sample, betweenAttempts = false)) {
            is ThermalDecision.Continue -> Unit
            is ThermalDecision.CooldownWait -> {
                throw ThermalPauseInterrupt(decision.reason, sample)
            }
            is ThermalDecision.HardStop -> {
                activeJournal?.recordStop(decision.reason, sample.toJson())
                throw LabStop(decision.reason, stateFromStopReason(decision.reason))
            }
        }
    }

    private fun checkTimeAndCancel() {
        if (cancelled.get()) {
            val reason = "Research paused. The saved run can be resumed."
            activeJournal?.recordStop(reason, lastSample?.toJson())
            throw LabStop(reason, LabSessionState.PAUSED_USER)
        }
    }

    companion object {
        private const val TELEMETRY_INTERVAL_MS = 2_000L
        private const val COOLDOWN_POLL_MS = 2_000L
        private val exclusive = AtomicBoolean()

        fun evidenceRoot(context: Context) = File(context.filesDir, "research-lab")

        internal fun stateFromStopReason(reason: String): LabSessionState = when {
            reason.contains("battery", ignoreCase = true) -> LabSessionState.PAUSED_BATTERY
            reason.contains("limit", ignoreCase = true) || reason.contains("reserve", ignoreCase = true) -> LabSessionState.PAUSED_STORAGE
            reason.contains("thermal", ignoreCase = true) -> LabSessionState.PAUSED_THERMAL
            else -> LabSessionState.PAUSED_USER
        }
    }
}

/**
 * Prevents non-productive duplicate/infinite exploration loops using research state progression
 * instead of arbitrary attempt count or time caps.
 */
internal class LabExplorationGuard {
    private val visitedStates = LinkedHashSet<Triple<String, Int, Int>>()

    data class Snapshot(
        val completedCount: Int,
        val failedCount: Int,
        val interruptedCount: Int,
        val remainingPlanned: Int,
        val totalObserved: Int,
    )

    fun snapshot(history: List<LabResult>, remainingPlanned: Int = 0): Snapshot {
        val completed = history.count { it.verdict != LabVerdict.STOPPED && it.verdict != LabVerdict.INTERRUPTED }
        val failed = history.count { it.verdict == LabVerdict.FAILED || it.verdict == LabVerdict.MISMATCH }
        val interrupted = history.count { it.verdict == LabVerdict.INTERRUPTED }
        val observed = history.count { it.verdict == LabVerdict.OBSERVED }
        return Snapshot(
            completedCount = completed,
            failedCount = failed,
            interruptedCount = interrupted,
            remainingPlanned = remainingPlanned,
            totalObserved = observed,
        )
    }

    fun checkNext(
        effectiveHistory: List<LabResult>,
        nextCase: LabCase,
        interruptedCounts: Map<String, Int> = emptyMap(),
    ): String? {
        if (effectiveHistory.any { it.attempt.key == nextCase.key }) {
            return "Duplicate-execution guard: ${nextCase.key} already resolved."
        }
        val interruptCount = interruptedCounts[nextCase.key] ?: 0
        if (interruptCount >= MAX_INTERRUPT_RETRIES_PER_KEY) {
            return "Repeated-interruption guard: ${nextCase.key} interrupted $interruptCount times."
        }
        val stateSignature = Triple(nextCase.key, effectiveHistory.size, interruptCount)
        if (!visitedStates.add(stateSignature)) {
            return "Cycle guard: state $stateSignature already visited."
        }
        return null
    }

    companion object {
        const val MAX_INTERRUPT_RETRIES_PER_KEY = 3
    }
}

internal class ThermalPauseInterrupt(val reason: String, val sample: ThermalSample) : Exception(reason)

internal class LabStop(
    message: String,
    val sessionState: LabSessionState = LabSessionState.PAUSED_USER,
) : Exception(message)

internal sealed interface AttemptOutcome {
    object Completed : AttemptOutcome
    data class ThermalSuspended(val reason: String) : AttemptOutcome
    data class Paused(val reason: String, val state: LabSessionState) : AttemptOutcome
}

internal fun executeIsolatedAttempt(
    attempt: LabJournal.Attempt,
    case: LabCase,
    knowledge: LabKnowledgeBase,
    runner: () -> Pair<LabVerdict, String?>,
): AttemptOutcome = try {
    val (verdict, reason) = runner()
    attempt.result(verdict, reason)
    runCatching { knowledge.ingestAttempt(attempt.directory.name, case, verdict, reason, attempt.readEvents()) }
    AttemptOutcome.Completed
} catch (pause: ThermalPauseInterrupt) {
    attempt.notice(pause.sample.toJson().put("event", "thermal_pause_interrupt").put("reason", pause.reason))
    attempt.result(LabVerdict.INTERRUPTED, pause.reason, thermalSuspended = true)
    AttemptOutcome.ThermalSuspended(pause.reason)
} catch (stop: LabStop) {
    attempt.result(LabVerdict.STOPPED, stop.message)
    AttemptOutcome.Paused(stop.message.orEmpty(), stop.sessionState)
} catch (error: LabRetirementFailure) {
    attempt.notice(json(
        "event" to "attempt_error",
        "errorClass" to error.javaClass.name,
        "errorMessage" to error.message?.take(400),
        "sdkInt" to Build.VERSION.SDK_INT,
        "stackTrace" to error.stackTraceToString().take(4096),
    ))
    attempt.result(LabVerdict.INTERRUPTED, error.message)
    runCatching { knowledge.ingestAttempt(attempt.directory.name, case, LabVerdict.INTERRUPTED, error.message, attempt.readEvents()) }
    AttemptOutcome.Paused(error.message.orEmpty(), LabSessionState.PAUSED_ERROR)
} catch (e: VirtualMachineError) {
    throw e
} catch (error: Throwable) {
    val msg = "${error.javaClass.simpleName}: ${error.message?.take(350) ?: "no message"}"
    attempt.notice(json(
        "event" to "attempt_error",
        "errorClass" to error.javaClass.name,
        "errorMessage" to error.message?.take(400),
        "sdkInt" to Build.VERSION.SDK_INT,
        "stackTrace" to error.stackTraceToString().take(4096),
    ))
    attempt.result(LabVerdict.FAILED, msg)
    runCatching { knowledge.ingestAttempt(attempt.directory.name, case, LabVerdict.FAILED, msg, attempt.readEvents()) }
    AttemptOutcome.Completed
}
