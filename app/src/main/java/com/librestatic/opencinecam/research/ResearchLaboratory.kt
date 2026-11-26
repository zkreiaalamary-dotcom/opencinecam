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
    private val sessionBudget = LabSessionBudget()
    private val evidenceLimitBytes = LabStorageManager.loadEvidenceLimitBytes(context)
    private val thermalPolicy = LabThermalPolicy(evidenceLimitBytes = evidenceLimitBytes)
    private var activeJournal: LabJournal? = null
    private var nextEnvironmentalCheck = 0L
    private var lastThermalStatus: Int? = null
    private var lastSample: ThermalSample? = null

    fun cancel() { cancelled.set(true) }

    fun run(resume: Boolean): String {
        check(exclusive.compareAndSet(false, true)) { "A research run is already closing." }
        try {
            val latest = LabJournal.latest(root)
            val revision = context.getString(com.librestatic.opencinecam.R.string.research_lab_revision)
            val build = if (revision == "local-unknown") {
                "local-" + context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            } else revision
            val journal = latest?.takeIf {
                resume && !it.finished && (it.compatibleWith(build) || it.resumableOnDevice())
            } ?: LabJournal.create(root, build)
            activeJournal = journal
            if (resume && !journal.compatibleWith(build) && journal.resumableOnDevice()) {
                journal.recordApkRevisionTransition(build)
            }
            journal.recoverInterrupted()
            checkBudget(forceSample = true)
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
            return explore(journal, build, knowledge, planner, video, regularLog, sourceProbe)
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
        knowledge: LabKnowledgeBase,
        planner: LabAutonomousPlanner,
        video: LabVideo,
        regularLog: LabRegularLogRunner,
        sourceProbe: LabSourceProbe,
    ): String {
        val maxLoopSteps = sessionBudget.maxTotal + 8
        repeat(maxLoopSteps) {
            awaitThermalClearance(journal, knowledge)
            val history = journal.history()
            val counts = sessionBudget.snapshot(history)
            val next = planner.next(history.filter { it.verdict != LabVerdict.STOPPED })
            if (next == null) {
                val reason = "All admissible autonomous experiments completed."
                journal.saveCheckpoint(json("state" to "FINISHED", "reason" to reason, "coreExecuted" to counts.coreExecuted, "adaptiveExecuted" to counts.adaptiveExecuted, "epochMs" to System.currentTimeMillis()))
                journal.finish(reason)
                runCatching { LabReportGenerator.generate(journal.directory, journal.inventoryJson(), history, knowledge, reason) }
                return "Research complete. Evidence is ready to export."
            }
            val budgetBlockReason = sessionBudget.checkNext(history, next.attempt)
            if (budgetBlockReason != null) {
                journal.saveCheckpoint(json("state" to "FINISHED_BUDGET", "reason" to budgetBlockReason, "coreExecuted" to counts.coreExecuted, "adaptiveExecuted" to counts.adaptiveExecuted, "epochMs" to System.currentTimeMillis()))
                journal.finish(budgetBlockReason)
                runCatching { LabReportGenerator.generate(journal.directory, journal.inventoryJson(), history, knowledge, budgetBlockReason) }
                return "$budgetBlockReason Evidence is ready to export."
            }
            val estimateBytes = LabStorageManager.estimateAttemptBytes(next.attempt)
            val spaceDecision = LabStorageManager.checkSpace(
                root = root,
                limitBytes = evidenceLimitBytes,
                upcomingEstimateBytes = estimateBytes,
            )
            if (!spaceDecision.allowed) {
                val reason = spaceDecision.reason ?: "Insufficient storage budget for upcoming experiment."
                val currentSample = sampleThermal(context, root, evidenceLimitBytes).also { lastSample = it }
                journal.recordStop(reason, currentSample.toJson())
                runCatching { LabReportGenerator.generate(journal.directory, journal.inventoryJson(), history, knowledge, reason) }
                return reason
            }
            val stageTitle = planner.stageLabel(next.attempt)
            status("$stageTitle · ${next.attempt.label}")
            val sample = lastSample ?: sampleThermal(context, root, evidenceLimitBytes).also { lastSample = it }
            onProgress?.invoke(
                LabUiProgress(
                    stageLabel = stageTitle,
                    attemptNumber = counts.totalExecuted + 1,
                    maxAttempts = counts.maxTotal,
                    hypothesis = next.hypothesis,
                    confirmedHypotheses = knowledge.confirmedCount(),
                    totalHypotheses = knowledge.totalHypotheses(),
                    batteryTempC = sample.batteryTempTenthsC / 10.0,
                    thermalStatus = sample.thermalStatus,
                ),
            )
            val attempt = journal.begin(next, apkRevision)
            attempt.notice(sample.toJson())
            val stopReason = execute(next.attempt, attempt, knowledge, video, regularLog, sourceProbe)
            val updatedHistory = journal.history()
            val updatedCounts = sessionBudget.snapshot(updatedHistory)
            journal.saveCheckpoint(
                json(
                    "state" to if (stopReason != null) "PAUSED" else "RUNNING",
                    "lastAttemptId" to attempt.directory.name,
                    "lastPath" to next.attempt.path.name,
                    "lastVariant" to next.attempt.variant,
                    "coreExecuted" to updatedCounts.coreExecuted,
                    "adaptiveExecuted" to updatedCounts.adaptiveExecuted,
                    "stopReason" to stopReason,
                    "epochMs" to System.currentTimeMillis(),
                ),
            )
            runCatching {
                LabReportGenerator.generate(journal.directory, journal.inventoryJson(), updatedHistory, knowledge, stopReason)
            }.onFailure { err ->
                journal.appendTelemetry(json("event" to "report_error", "error" to "${err.javaClass.name}: ${err.message}"))
            }
            if (stopReason != null) return stopReason
            val until = SystemClock.elapsedRealtime() + 1_000
            while (SystemClock.elapsedRealtime() < until) {
                checkBudget()
                Thread.sleep(100)
            }
        }
        val reason = "All planned autonomous loop steps completed."
        journal.finish(reason)
        runCatching { LabReportGenerator.generate(journal.directory, journal.inventoryJson(), journal.history(), knowledge, reason) }
        return "$reason Evidence is ready to export."
    }

    private fun execute(
        case: LabCase,
        attempt: LabJournal.Attempt,
        knowledge: LabKnowledgeBase,
        video: LabVideo,
        regularLog: LabRegularLogRunner,
        sourceProbe: LabSourceProbe,
    ): String? = executeIsolatedAttempt(attempt, case, knowledge) {
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
            else -> video.run(case, attempt)
        }
    }

    private fun runRaw(case: LabCase, journal: LabJournal.Attempt): Pair<LabVerdict, String?> {
        val encode = case.path == LabPath.RAW_NATIVE
        var lastPhase: String? = null
        var measured = false
        var stop: LabStop? = null
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
            // A waiting lease still respects its predecessor. A running qualifier closes Camera2 during run().
            lease.releaseAfter(CompletableFuture.completedFuture(Unit))
        }
        journal.notice(json("event" to "raw_qualification_summary", "result" to result.report))
        stop?.let { throw it }
        return if (measured) LabVerdict.OBSERVED to null else LabVerdict.FAILED to result.reasonCode
    }

    private fun awaitThermalClearance(journal: LabJournal, knowledge: LabKnowledgeBase) {
        while (true) {
            checkTimeAndCancel()
            val sample = sampleThermal(context, root, evidenceLimitBytes)
            lastSample = sample
            val statusChanged = lastThermalStatus != null && lastThermalStatus != sample.thermalStatus
            lastThermalStatus = sample.thermalStatus
            journal.appendTelemetry(sample.toJson())
            nextEnvironmentalCheck = SystemClock.elapsedRealtime() + TELEMETRY_INTERVAL_MS
            when (val decision = thermalPolicy.evaluate(sample, betweenAttempts = true)) {
                is ThermalDecision.Continue -> return
                is ThermalDecision.CooldownWait -> {
                    status(decision.reason)
                    val counts = sessionBudget.snapshot(journal.history())
                    onProgress?.invoke(
                        LabUiProgress(
                            stageLabel = "Thermal Cooldown",
                            attemptNumber = counts.totalExecuted,
                            maxAttempts = counts.maxTotal,
                            hypothesis = decision.reason,
                            confirmedHypotheses = knowledge.confirmedCount(),
                            totalHypotheses = knowledge.totalHypotheses(),
                            batteryTempC = sample.batteryTempTenthsC / 10.0,
                            thermalStatus = sample.thermalStatus,
                        ),
                    )
                    if (statusChanged) {
                        journal.appendTelemetry(sample.toJson().put("event", "thermal_cooldown_wait"))
                    }
                    Thread.sleep(COOLDOWN_POLL_MS)
                }
                is ThermalDecision.HardStop -> {
                    journal.recordStop(decision.reason, sample.toJson())
                    throw LabStop(decision.reason)
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
        val sample = sampleThermal(context, root, evidenceLimitBytes)
        lastSample = sample
        val statusChanged = lastThermalStatus != null && lastThermalStatus != sample.thermalStatus
        lastThermalStatus = sample.thermalStatus
        activeJournal?.appendTelemetry(
            sample.toJson().apply { if (statusChanged) put("statusTransition", true) },
        )
        when (val decision = thermalPolicy.evaluate(sample, betweenAttempts = false)) {
            is ThermalDecision.Continue -> Unit
            is ThermalDecision.CooldownWait -> {
                activeJournal?.recordStop(decision.reason, sample.toJson())
                throw LabStop(decision.reason)
            }
            is ThermalDecision.HardStop -> {
                activeJournal?.recordStop(decision.reason, sample.toJson())
                throw LabStop(decision.reason)
            }
        }
    }

    private fun checkTimeAndCancel() {
        if (cancelled.get()) {
            val reason = "Research paused. The saved run can be resumed."
            activeJournal?.recordStop(reason, lastSample?.toJson())
            throw LabStop(reason)
        }
    }

    companion object {
        private const val TELEMETRY_INTERVAL_MS = 2_000L
        private const val COOLDOWN_POLL_MS = 2_000L
        private val exclusive = AtomicBoolean()

        fun evidenceRoot(context: Context) = File(context.filesDir, "research-lab")
    }
}

/**
 * Separates fixed core discovery experiments from bounded adaptive follow-ups and blocks
 * non-productive duplicate scheduling without imposing any elapsed-session time cap.
 */
internal class LabSessionBudget(
    val maxCoreAttempts: Int = DEFAULT_MAX_CORE_ATTEMPTS,
    val maxAdaptiveFollowUps: Int = DEFAULT_MAX_ADAPTIVE_FOLLOWUPS,
) {
    val maxTotal: Int get() = maxCoreAttempts + maxAdaptiveFollowUps

    data class Snapshot(
        val coreExecuted: Int,
        val adaptiveExecuted: Int,
        val totalExecuted: Int,
        val maxTotal: Int,
    )

    fun isAdaptive(case: LabCase): Boolean = case.retryIndex > 0 || case.variant.isNotBlank()

    fun snapshot(history: List<LabResult>): Snapshot {
        val executed = history.filter { it.verdict != LabVerdict.STOPPED }
        val core = executed.count { !isAdaptive(it.attempt) }
        val adaptive = executed.count { isAdaptive(it.attempt) }
        return Snapshot(
            coreExecuted = core,
            adaptiveExecuted = adaptive,
            totalExecuted = core + adaptive,
            maxTotal = maxTotal,
        )
    }

    fun checkNext(history: List<LabResult>, nextCase: LabCase): String? {
        val executed = history.filter { it.verdict != LabVerdict.STOPPED }
        if (executed.any { it.attempt.key == nextCase.key }) {
            return "Non-productive repeat guard triggered for ${nextCase.key}."
        }
        val counts = snapshot(history)
        return when {
            !isAdaptive(nextCase) && counts.coreExecuted >= maxCoreAttempts ->
                "Core experiment budget reached (${counts.coreExecuted}/$maxCoreAttempts)."
            isAdaptive(nextCase) && counts.adaptiveExecuted >= maxAdaptiveFollowUps ->
                "Adaptive follow-up budget reached (${counts.adaptiveExecuted}/$maxAdaptiveFollowUps)."
            else -> null
        }
    }

    companion object {
        const val DEFAULT_MAX_CORE_ATTEMPTS = 36
        const val DEFAULT_MAX_ADAPTIVE_FOLLOWUPS = 18
    }
}

internal class LabStop(message: String) : Exception(message)

internal fun executeIsolatedAttempt(
    attempt: LabJournal.Attempt,
    case: LabCase,
    knowledge: LabKnowledgeBase,
    runner: () -> Pair<LabVerdict, String?>,
): String? = try {
    val (verdict, reason) = runner()
    attempt.result(verdict, reason)
    runCatching { knowledge.ingestAttempt(attempt.directory.name, case, verdict, reason, attempt.readEvents()) }
    null
} catch (stop: LabStop) {
    attempt.result(LabVerdict.STOPPED, stop.message)
    stop.message
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
    error.message
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
    null
}
