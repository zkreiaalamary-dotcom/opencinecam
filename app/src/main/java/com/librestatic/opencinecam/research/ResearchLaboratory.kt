/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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
) {
    private val cancelled = AtomicBoolean()
    private val root = evidenceRoot(context)
    private val started = SystemClock.elapsedRealtime()
    private val thermalPolicy = LabThermalPolicy()
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
            val journal = latest?.takeIf { resume && !it.finished && it.compatibleWith(build) } ?: LabJournal.create(root, build)
            activeJournal = journal
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
            journal.inventory(profiles.inventory(descriptors).put("nativeBlockPackAvailable", NativeRawCodec.available)
                .put("apkSourceRevision", build))
            val search = LabSearch(candidates)
            val video = LabVideo(context, profiles, ::checkBudget)
            val regularLog = LabRegularLogRunner(context, descriptor, ::checkBudget)
            val sourceProbe = LabSourceProbe(context, descriptor, ::checkBudget)
            return explore(journal, search, video, regularLog, sourceProbe)
        } catch (stop: LabStop) {
            return stop.message.orEmpty()
        } finally {
            activeJournal = null
            exclusive.set(false)
        }
    }

    private fun explore(
        journal: LabJournal,
        search: LabSearch,
        video: LabVideo,
        regularLog: LabRegularLogRunner,
        sourceProbe: LabSourceProbe,
    ): String {
        repeat(MAX_ATTEMPTS_PER_RUN) {
            awaitThermalClearance(journal)
            val history = journal.history()
            if (history.count { it.verdict != LabVerdict.STOPPED } >= MAX_ATTEMPTS_PER_RUN) {
                journal.finish("Experiment budget reached")
                return "Experiment budget reached. Evidence is ready to export."
            }
            val next = search.next(history.filter { it.verdict != LabVerdict.STOPPED })
            if (next == null) {
                journal.finish("All admissible comparisons completed")
                return "Research complete. Evidence is ready to export."
            }
            status(next.attempt.label)
            val attempt = journal.begin(next)
            val sample = lastSample ?: sampleThermal(context, root).also { lastSample = it }
            attempt.notice(sample.toJson())
            val stopReason = execute(next.attempt, attempt, video, regularLog, sourceProbe)
            if (stopReason != null) return stopReason
            val until = SystemClock.elapsedRealtime() + 1_000
            while (SystemClock.elapsedRealtime() < until) {
                checkBudget()
                Thread.sleep(100)
            }
        }
        journal.finish("Experiment budget reached")
        return "Experiment budget reached. Evidence is ready to export."
    }

    private fun execute(
        case: LabCase,
        attempt: LabJournal.Attempt,
        video: LabVideo,
        regularLog: LabRegularLogRunner,
        sourceProbe: LabSourceProbe,
    ): String? {
        return try {
            val (verdict, reason) = when (case.path) {
                LabPath.RAW_CAMERA, LabPath.RAW_NATIVE -> runRaw(case, attempt)
                LabPath.DIRECT_HEVC, LabPath.DIRECT_HEVC_PREVIEW, LabPath.DIRECT_HEVC_SURFACETEXTURE ->
                    LabDirectRecorder(context, ::checkBudget).run(case, attempt)
                LabPath.CODEC_LEVEL_PAIR -> runCodecLevelPair(case, attempt, ::checkBudget)
                LabPath.CODEC_SYNTHETIC_MATRIX -> LabSyntheticEncoderProbe.run(attempt, ::checkBudget)
                LabPath.LOG_REGULAR_4K30, LabPath.LOG_REGULAR_4K60, LabPath.GPU_HEVC_4K30, LabPath.GPU_HEVC_4K60 ->
                    regularLog.run(case, attempt)
                LabPath.ISP_TONEMAP_AB, LabPath.SOURCE_FORMAT_PROBE -> sourceProbe.run(case, attempt)
                else -> video.run(case, attempt)
            }
            attempt.result(verdict, reason)
            null
        } catch (stop: LabStop) {
            attempt.result(LabVerdict.STOPPED, stop.message)
            stop.message
        } catch (error: LabRetirementFailure) {
            attempt.notice(json("error" to error.stackTraceToString().take(4096)))
            attempt.result(LabVerdict.INTERRUPTED, error.message)
            error.message
        } catch (error: Exception) {
            attempt.notice(json("error" to error.stackTraceToString().take(4096)))
            attempt.result(LabVerdict.FAILED, error.message?.take(400))
            null
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

    private fun awaitThermalClearance(journal: LabJournal) {
        while (true) {
            checkTimeAndCancel()
            val sample = sampleThermal(context, root)
            lastSample = sample
            val statusChanged = lastThermalStatus != null && lastThermalStatus != sample.thermalStatus
            lastThermalStatus = sample.thermalStatus
            journal.appendTelemetry(sample.toJson())
            nextEnvironmentalCheck = SystemClock.elapsedRealtime() + TELEMETRY_INTERVAL_MS
            when (val decision = thermalPolicy.evaluate(sample, betweenAttempts = true)) {
                is ThermalDecision.Continue -> return
                is ThermalDecision.CooldownWait -> {
                    status(decision.reason)
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
        val sample = sampleThermal(context, root)
        lastSample = sample
        val statusChanged = lastThermalStatus != null && lastThermalStatus != sample.thermalStatus
        lastThermalStatus = sample.thermalStatus
        activeJournal?.appendTelemetry(
            sample.toJson().apply { if (statusChanged) put("statusTransition", true) },
        )
        when (val decision = thermalPolicy.evaluate(sample, betweenAttempts = false)) {
            is ThermalDecision.Continue -> Unit
            is ThermalDecision.CooldownWait -> {
                val reason = "Research paused due to thermal load (status=MODERATE, ${sample.batteryTempTenthsC / 10.0} °C)."
                activeJournal?.recordStop(reason, sample.toJson())
                throw LabStop(reason)
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
        if (SystemClock.elapsedRealtime() - started >= 180_000L) {
            val reason = "Time limit reached. The saved run can be resumed."
            activeJournal?.recordStop(reason, lastSample?.toJson())
            throw LabStop(reason)
        }
    }

    companion object {
        private const val MAX_ATTEMPTS_PER_RUN = 12
        private const val TELEMETRY_INTERVAL_MS = 2_000L
        private const val COOLDOWN_POLL_MS = 2_000L
        private val exclusive = AtomicBoolean()

        fun evidenceRoot(context: Context) = File(context.filesDir, "research-lab")
    }
}

internal class LabStop(message: String) : Exception(message)
