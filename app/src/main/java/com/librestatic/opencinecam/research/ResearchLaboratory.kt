/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.PowerManager
import android.os.StatFs
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
    private var nextEnvironmentalCheck = 0L

    fun cancel() { cancelled.set(true) }

    fun run(resume: Boolean): String {
        check(exclusive.compareAndSet(false, true)) { "A research run is already closing." }
        try {
            checkBudget()
            val latest = LabJournal.latest(root)
            val journal = latest?.takeIf { resume && !it.finished && it.compatible } ?: LabJournal.create(root)
            journal.recoverInterrupted()
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
            journal.inventory(profiles.inventory(descriptors).put("nativeBlockPackAvailable", NativeRawCodec.available))
            val search = LabSearch(candidates)
            val video = LabVideo(context, profiles, ::checkBudget)
            return explore(journal, search, video)
        } catch (stop: LabStop) {
            return stop.message.orEmpty()
        } finally {
            exclusive.set(false)
        }
    }

    private fun explore(journal: LabJournal, search: LabSearch, video: LabVideo): String {
        repeat(MAX_ATTEMPTS_PER_RUN) {
            checkBudget()
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
            attempt.notice(environment())
            val stopReason = execute(next.attempt, attempt, video)
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

    private fun execute(case: LabCase, attempt: LabJournal.Attempt, video: LabVideo): String? {
        return try {
            val (verdict, reason) = when (case.path) {
                LabPath.RAW_CAMERA, LabPath.RAW_NATIVE -> runRaw(case, attempt)
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

    private fun checkBudget() {
        if (cancelled.get()) throw LabStop("Research paused. The saved run can be resumed.")
        if (SystemClock.elapsedRealtime() - started >= 180_000L) throw LabStop("Time limit reached. The saved run can be resumed.")
        val now = SystemClock.elapsedRealtime()
        if (now < nextEnvironmentalCheck) return
        nextEnvironmentalCheck = now + 1_000
        val snapshot = environment()
        val thermal = snapshot.getInt("thermalStatus")
        if (thermal >= PowerManager.THERMAL_STATUS_MODERATE) throw LabStop("Research paused due to thermal load.")
        val percent = snapshot.getInt("batteryPercent")
        val temperature = snapshot.getInt("batteryTemperatureTenthsC")
        if (percent < 25 || temperature >= 420) {
            throw LabStop("Research paused at the battery limit (25% charge or 42 °C).")
        }
        if (snapshot.getLong("freeBytes") < 1_500_000_000L) {
            throw LabStop("Research needs at least 1.5 GB of free storage.")
        }
        if (snapshot.getLong("evidenceBytes") >= 950_000_000L) {
            throw LabStop("Research evidence is close to the 1 GB local limit.")
        }
    }

    private fun environment(): JSONObject {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val percent = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return json(
            "event" to "environment",
            "batteryPercent" to if (scale > 0 && percent >= 0) 100 * percent / scale else -1,
            "batteryTemperatureTenthsC" to (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1),
            "thermalStatus" to context.getSystemService(PowerManager::class.java).currentThermalStatus,
            "freeBytes" to StatFs(context.filesDir.absolutePath).availableBytes,
            "evidenceBytes" to root.walkTopDown().filter(File::isFile).sumOf(File::length),
        )
    }

    companion object {
        private const val MAX_ATTEMPTS_PER_RUN = 12
        private val exclusive = AtomicBoolean()

        fun evidenceRoot(context: Context) = File(context.filesDir, "research-lab")
    }
}

internal class LabStop(message: String) : Exception(message)
