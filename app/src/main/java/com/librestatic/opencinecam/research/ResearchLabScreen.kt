/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import java.util.Locale
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.librestatic.opencinecam.R
import com.librestatic.opencinecam.settings.SettingsTopBar
import java.util.concurrent.Executors
import android.os.Handler
import android.os.Looper
import android.net.Uri

internal data class LabUiProgress(
    val stageLabel: String = "",
    val attemptNumber: Int = 0,
    val maxAttempts: Int = 0,
    val completedAttempts: Int = 0,
    val remainingPlanned: Int = 0,
    val hypothesis: String? = null,
    val confirmedHypotheses: Int = 0,
    val totalHypotheses: Int = 12,
    val batteryTempC: Double? = null,
    val thermalStatus: Int? = null,
    val thermalStatusName: String? = null,
    val inThermalPause: Boolean = false,
    val androidPowerSaveMode: Boolean? = null,
    val manualPowerMode: ManualPowerMode = ManualPowerMode.UNSPECIFIED,
    val socTempC: Double? = null,
)

internal data class LabStorageUiState(
    val usedBytes: Long,
    val mediaBytes: Long,
    val metadataBytes: Long,
    val limitBytes: Long,
    val freeBytes: Long,
    val reserveBytes: Long,
    val verifiedPrunableBytes: Long,
    val verifiedPrunableFiles: Int,
    val hasVerifiedExport: Boolean,
)

@Composable
internal fun ResearchLabScreen(cameraId: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val controls = remember(context, cameraId) { ResearchLabControls(context.applicationContext, cameraId) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let(controls::export)
    }
    DisposableEffect(owner, controls) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) controls.stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            controls.close()
        }
    }
    val active = controls.running
    val window = (context as? ComponentActivity)?.window
    DisposableEffect(window, active) {
        if (active) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { if (active) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val leave = { if (controls.running) controls.stop() else onBack() }
    BackHandler(onBack = leave)
    ResearchLabContent(
        status = controls.status,
        running = controls.running,
        exporting = controls.exporting,
        progress = controls.progress,
        storage = controls.storage,
        powerProfile = controls.powerProfile,
        sessionSummary = controls.sessionSummary,
        onBack = leave,
        onStart = { controls.start(resume = true) },
        onNew = { controls.start(resume = false) },
        onStop = controls::stop,
        onExport = { export.launch("OpenCineCam-research.zip") },
        onSelectLimitBytes = controls::setLimitBytes,
        onSelectManualPowerMode = controls::setManualPowerMode,
        onPruneVerified = controls::pruneVerified,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ResearchLabContent(
    status: String?,
    running: Boolean,
    exporting: Boolean,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onNew: () -> Unit,
    onStop: () -> Unit,
    onExport: () -> Unit,
    progress: LabUiProgress? = null,
    storage: LabStorageUiState? = null,
    powerProfile: PowerProfileSnapshot? = null,
    sessionSummary: LabSessionSummary? = null,
    onSelectLimitBytes: ((Long) -> Unit)? = null,
    onSelectManualPowerMode: ((ManualPowerMode) -> Unit)? = null,
    onPruneVerified: (() -> Unit)? = null,
) {
    var confirmPruneOpen by remember { mutableStateOf(false) }
    var confirmNewSessionOpen by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SettingsTopBar(stringResource(R.string.research_lab_title), onBack, backTag = "lab-back")
        Text(stringResource(R.string.research_lab_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            status ?: stringResource(R.string.research_lab_ready),
            modifier = Modifier.testTag("lab-status").semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (progress != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val countLabel = if (progress.remainingPlanned > 0 || progress.completedAttempts > 0) {
                        "${progress.completedAttempts} done · ${progress.remainingPlanned} remaining"
                    } else if (progress.maxAttempts > 0) {
                        "${progress.attemptNumber}/${progress.maxAttempts}"
                    } else {
                        "Attempt #${progress.attemptNumber}"
                    }
                    Text(
                        "${progress.stageLabel} ($countLabel)",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.testTag("lab-stage"),
                    )
                    val hypText = buildString {
                        if (!progress.hypothesis.isNullOrBlank()) {
                            append(progress.hypothesis)
                            append(" · ")
                        }
                        append("Hypotheses confirmed: ${progress.confirmedHypotheses}/${progress.totalHypotheses}")
                    }
                    Text(
                        hypText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("lab-hypothesis"),
                    )
                    if (progress.batteryTempC != null || progress.thermalStatus != null) {
                        val tempStr = progress.batteryTempC?.let { "%.1f °C".format(Locale.ROOT, it) } ?: "n/a"
                        val thermStr = progress.thermalStatusName ?: progress.thermalStatus?.toString() ?: "n/a"
                        val socStr = progress.socTempC?.let { " · SoC %.1f °C".format(Locale.ROOT, it) }.orEmpty()
                        val pausePolicyNote = if (progress.inThermalPause) " (paused >=40°C, auto-resumes <=35°C)" else ""
                        Text(
                            "Battery: $tempStr · OS thermal: $thermStr$socStr$pausePolicyNote",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("lab-telemetry"),
                        )
                    }
                }
            }
        }
        if (powerProfile != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().testTag("lab-power"),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val osSaveText = if (powerProfile.androidPowerSaveMode) "ON (throttled!)" else "OFF"
                    val smartHint = powerProfile.huaweiSmartModeStatusRaw?.let { " · Huawei hint=$it" }.orEmpty()
                    Text(
                        "Power state · Android Power Save: $osSaveText · Logged mode: ${powerProfile.manualPowerMode.label}$smartHint",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (powerProfile.androidPowerSaveMode || powerProfile.isLikelyThrottled) {
                        Text(
                            "Warning: Power Saving Mode is active. Disable Power Saving and enable Huawei Performance Mode before running 4K60/LOG benchmarks.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("lab-power-warning"),
                        )
                    } else {
                        Text(
                            "Huawei Performance Mode cannot be toggled from public APIs; record the active device mode below for clean comparisons.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (onSelectManualPowerMode != null) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            listOf(
                                ManualPowerMode.PERFORMANCE,
                                ManualPowerMode.NORMAL,
                                ManualPowerMode.POWER_SAVING,
                                ManualPowerMode.UNSPECIFIED,
                            ).forEach { mode ->
                                FilterChip(
                                    selected = powerProfile.manualPowerMode == mode,
                                    onClick = { if (!running && !exporting) onSelectManualPowerMode(mode) },
                                    enabled = !running && !exporting,
                                    label = { Text(mode.label) },
                                    modifier = Modifier.testTag("lab-power-${mode.name}"),
                                )
                            }
                        }
                    }
                }
            }
        }
        if (sessionSummary != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().testTag("lab-session"),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (sessionSummary.hasResumableSession) {
                        Text(
                            "Saved checkpoint (${sessionSummary.state?.name ?: "PAUSED"}): " +
                                "${sessionSummary.completedCount} completed, ${sessionSummary.failedCount} failed/mismatch, " +
                                "${sessionSummary.interruptedCount} interrupted",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        val detail = buildString {
                            sessionSummary.currentStage?.let { append("Stage: $it · ") }
                            append("Total stored runs: ${sessionSummary.pastSessionCount}")
                        }
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        sessionSummary.lastStopReason?.let { reason ->
                            Text("Last pause: $reason", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Text(
                            "No paused run pending (${sessionSummary.pastSessionCount} archived run(s) preserved).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (storage != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().testTag("lab-storage"),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Evidence: ${LabStorageManager.formatBytes(storage.usedBytes)} / ${LabStorageManager.formatBytes(storage.limitBytes)} " +
                            "(media ${LabStorageManager.formatBytes(storage.mediaBytes)}, logs ${LabStorageManager.formatBytes(storage.metadataBytes)})",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.testTag("lab-storage-usage"),
                    )
                    Text(
                        "Device free: ${LabStorageManager.formatBytes(storage.freeBytes)} · Safety reserve: 5.0 GiB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("lab-storage-reserve"),
                    )
                    if (onSelectLimitBytes != null) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            LabStorageManager.LIMIT_PRESETS_GB.forEach { gb ->
                                val targetBytes = gb * 1_000_000_000L
                                FilterChip(
                                    selected = storage.limitBytes == targetBytes,
                                    onClick = { if (!running && !exporting) onSelectLimitBytes(targetBytes) },
                                    enabled = !running && !exporting,
                                    label = { Text("$gb GB") },
                                    modifier = Modifier.testTag("lab-limit-$gb"),
                                )
                            }
                        }
                    }
                    if (onPruneVerified != null) {
                        val canPrune = !running && !exporting && storage.hasVerifiedExport && storage.verifiedPrunableBytes > 0L
                        OutlinedButton(
                            onClick = { confirmPruneOpen = true },
                            enabled = canPrune,
                            modifier = Modifier.testTag("lab-prune-verified"),
                        ) {
                            val label = if (storage.hasVerifiedExport && storage.verifiedPrunableBytes > 0L) {
                                "Clean verified exported media (${LabStorageManager.formatBytes(storage.verifiedPrunableBytes)})"
                            } else {
                                "Export & verify ZIP to enable media cleanup"
                            }
                            Text(label)
                        }
                    }
                }
            }
        }
        if (running) {
            Button(onClick = onStop, modifier = Modifier.testTag("lab-stop")) { Text(stringResource(R.string.research_lab_stop)) }
        } else {
            val primaryLabel = if (sessionSummary?.hasResumableSession == true) {
                "Resume session (${sessionSummary.completedCount} done)"
            } else {
                stringResource(R.string.research_lab_start)
            }
            Button(onClick = onStart, enabled = !exporting, modifier = Modifier.testTag("lab-start")) {
                Text(primaryLabel)
            }
            TextButton(
                onClick = {
                    if (sessionSummary?.hasResumableSession == true) confirmNewSessionOpen = true else onNew()
                },
                enabled = !exporting,
                modifier = Modifier.testTag("lab-new"),
            ) {
                Text(stringResource(R.string.research_lab_new))
            }
            TextButton(onClick = onExport, enabled = !exporting, modifier = Modifier.testTag("lab-export")) {
                Text(stringResource(if (exporting) R.string.research_lab_exporting else R.string.research_lab_export))
            }
        }
    }
    if (confirmNewSessionOpen) {
        AlertDialog(
            onDismissRequest = { confirmNewSessionOpen = false },
            modifier = Modifier.testTag("lab-new-dialog"),
            title = { Text("Start a separate new session?") },
            text = {
                Text(
                    "Your current paused session (${sessionSummary?.completedCount ?: 0} completed attempts) " +
                        "and all prior recordings will be preserved untouched, and a brand-new session folder will be started.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmNewSessionOpen = false
                        onNew()
                    },
                    modifier = Modifier.testTag("lab-new-confirm"),
                ) { Text("Start new session") }
            },
            dismissButton = {
                TextButton(onClick = { confirmNewSessionOpen = false }) { Text("Cancel") }
            },
        )
    }
    if (confirmPruneOpen && storage != null && onPruneVerified != null) {
        AlertDialog(
            onDismissRequest = { confirmPruneOpen = false },
            modifier = Modifier.testTag("lab-prune-dialog"),
            title = { Text("Clean verified exported media?") },
            text = {
                Text(
                    "Delete ${storage.verifiedPrunableFiles} media file(s) (${LabStorageManager.formatBytes(storage.verifiedPrunableBytes)}) " +
                        "that passed SHA-256 readback export verification? All JSONL logs, telemetry, reports, and learned knowledge will remain intact.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmPruneOpen = false
                        onPruneVerified()
                    },
                    modifier = Modifier.testTag("lab-prune-confirm"),
                ) { Text("Delete verified media") }
            },
            dismissButton = {
                TextButton(onClick = { confirmPruneOpen = false }) { Text("Cancel") }
            },
        )
    }
}

private class ResearchLabControls(private val context: android.content.Context, private val cameraId: String?) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var run: ResearchLaboratory? = null
    var status by mutableStateOf<String?>(null)
        private set
    var progress by mutableStateOf<LabUiProgress?>(null)
        private set
    var storage by mutableStateOf<LabStorageUiState?>(null)
        private set
    var powerProfile by mutableStateOf<PowerProfileSnapshot?>(null)
        private set
    var sessionSummary by mutableStateOf<LabSessionSummary?>(null)
        private set
    var running by mutableStateOf(false)
        private set
    var exporting by mutableStateOf(false)
        private set

    init {
        refreshOverview()
    }

    fun refreshOverview() {
        worker.execute {
            val snap = loadStorageSnapshot()
            val pow = LabPowerProfile.capture(context)
            val sess = LabJournal.inspectResumableSummary(ResearchLaboratory.evidenceRoot(context))
            main.post {
                storage = snap
                powerProfile = pow
                sessionSummary = sess
            }
        }
    }

    fun refreshStorage() = refreshOverview()

    fun setLimitBytes(limitBytes: Long) {
        if (running || exporting) return
        val saved = LabStorageManager.saveEvidenceLimitBytes(context, limitBytes)
        status = "Evidence storage limit updated to ${LabStorageManager.formatBytes(saved)}."
        refreshOverview()
    }

    fun setManualPowerMode(mode: ManualPowerMode) {
        if (running || exporting) return
        LabPowerProfile.saveManualPowerMode(context, mode)
        status = "Logged device power mode: ${mode.label}."
        refreshOverview()
    }

    fun start(resume: Boolean) {
        if (running || exporting) return
        val run = ResearchLaboratory(
            context = context,
            cameraId = cameraId,
            status = { message -> main.post { status = message } },
            onProgress = { snapshot -> main.post { progress = snapshot } },
        )
        this.run = run
        running = true
        status = context.getString(R.string.research_lab_preparing)
        worker.execute {
            val message = runCatching { run.run(resume) }.getOrElse { it.message ?: it.javaClass.simpleName }
            val snap = loadStorageSnapshot()
            val pow = LabPowerProfile.capture(context)
            val sess = LabJournal.inspectResumableSummary(ResearchLaboratory.evidenceRoot(context))
            main.post {
                status = message
                storage = snap
                powerProfile = pow
                sessionSummary = sess
                running = false
                this.run = null
            }
        }
    }

    fun export(uri: Uri) {
        if (running || exporting) return
        exporting = true
        worker.execute {
            val message = runCatching {
                val root = ResearchLaboratory.evidenceRoot(context)
                check(LabJournal.latest(root) != null) { context.getString(R.string.research_lab_no_evidence) }
                val verification = LabStorageManager.streamingVerifiedExport(
                    root = root,
                    outputProvider = { requireNotNull(context.contentResolver.openOutputStream(uri)) },
                    readbackProvider = { requireNotNull(context.contentResolver.openInputStream(uri)) },
                )
                verification.message
            }.getOrElse { it.message ?: it.javaClass.simpleName }
            val snap = loadStorageSnapshot()
            val sess = LabJournal.inspectResumableSummary(ResearchLaboratory.evidenceRoot(context))
            main.post { status = message; storage = snap; sessionSummary = sess; exporting = false }
        }
    }

    fun pruneVerified() {
        if (running || exporting) return
        exporting = true
        worker.execute {
            val message = runCatching {
                val root = ResearchLaboratory.evidenceRoot(context)
                LabStorageManager.pruneVerifiedExportedMedia(root).message
            }.getOrElse { it.message ?: it.javaClass.simpleName }
            val snap = loadStorageSnapshot()
            val sess = LabJournal.inspectResumableSummary(ResearchLaboratory.evidenceRoot(context))
            main.post { status = message; storage = snap; sessionSummary = sess; exporting = false }
        }
    }

    private fun loadStorageSnapshot(): LabStorageUiState {
        val root = ResearchLaboratory.evidenceRoot(context)
        val limit = LabStorageManager.loadEvidenceLimitBytes(context)
        val usage = LabStorageManager.measureUsage(root = root, limitBytes = limit)
        return LabStorageUiState(
            usedBytes = usage.totalBytes,
            mediaBytes = usage.mediaBytes,
            metadataBytes = usage.metadataBytes,
            limitBytes = usage.limitBytes,
            freeBytes = usage.freeBytes,
            reserveBytes = usage.reserveBytes,
            verifiedPrunableBytes = usage.verifiedPrunableBytes,
            verifiedPrunableFiles = usage.verifiedPrunableFiles,
            hasVerifiedExport = usage.hasVerifiedExport,
        )
    }

    fun stop() {
        run?.cancel()
        if (running) status = context.getString(R.string.research_lab_stopping)
    }

    fun close() {
        stop()
        worker.shutdown()
    }
}
