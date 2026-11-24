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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
        onBack = leave,
        onStart = { controls.start(resume = true) },
        onNew = { controls.start(resume = false) },
        onStop = controls::stop,
        onExport = { export.launch("OpenCineCam-research.zip") },
    )
}

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
) {
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
        if (running) {
            Button(onClick = onStop, modifier = Modifier.testTag("lab-stop")) { Text(stringResource(R.string.research_lab_stop)) }
        } else {
            Button(onClick = onStart, enabled = !exporting, modifier = Modifier.testTag("lab-start")) {
                Text(stringResource(R.string.research_lab_start))
            }
            TextButton(onClick = onNew, enabled = !exporting) { Text(stringResource(R.string.research_lab_new)) }
            TextButton(onClick = onExport, enabled = !exporting, modifier = Modifier.testTag("lab-export")) {
                Text(stringResource(if (exporting) R.string.research_lab_exporting else R.string.research_lab_export))
            }
        }
    }
}

private class ResearchLabControls(private val context: android.content.Context, private val cameraId: String?) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var run: ResearchLaboratory? = null
    var status by mutableStateOf<String?>(null)
        private set
    var running by mutableStateOf(false)
        private set
    var exporting by mutableStateOf(false)
        private set

    fun start(resume: Boolean) {
        if (running || exporting) return
        val run = ResearchLaboratory(context, cameraId) { message -> main.post { status = message } }
        this.run = run
        running = true
        status = context.getString(R.string.research_lab_preparing)
        worker.execute {
            val message = runCatching { run.run(resume) }.getOrElse { it.message ?: it.javaClass.simpleName }
            main.post { status = message; running = false; this.run = null }
        }
    }

    fun export(uri: Uri) {
        if (running || exporting) return
        exporting = true
        worker.execute {
            val message = runCatching {
                val root = ResearchLaboratory.evidenceRoot(context)
                check(LabJournal.latest(root) != null) { context.getString(R.string.research_lab_no_evidence) }
                val output = requireNotNull(context.contentResolver.openOutputStream(uri))
                LabJournal.exportAll(root, output)
                context.getString(R.string.research_lab_exported)
            }.getOrElse { it.message ?: it.javaClass.simpleName }
            main.post { status = message; exporting = false }
        }
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
