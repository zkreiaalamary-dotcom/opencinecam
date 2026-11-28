/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import com.librestatic.opencinecam.camera.CameraHealthEvent
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rolling ring-buffered logcat + CameraDaemon/HISP event capture for Research Lab sessions.
 * Captures relevant HAL/camera/codec/thermal log lines continuously because EMUI 15's main logcat
 * ring wraps in ~10 seconds. Always terminates its subprocess cleanly on close().
 */
internal class LabDiagnosticRecorder(
    private val directory: File,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()
    private val activeLogFile = File(directory, "camera_hal_logcat.txt")
    private val rotatedLogFile = File(directory, "camera_hal_logcat.prev.txt")
    private val recordedHealthEvents = CopyOnWriteArrayList<CameraHealthEvent>()
    @Volatile private var process: Process? = null
    @Volatile private var readerThread: Thread? = null

    fun start() {
        if (closed.get() || readerThread != null) return
        directory.mkdirs()
        val proc = runCatching {
            ProcessBuilder(
                "logcat",
                "-v", "threadtime",
                "-b", "main,system,events,crash",
            ).redirectErrorStream(true).start()
        }.getOrNull() ?: return
        process = proc
        readerThread = Thread({
            var stream: FileOutputStream? = null
            var bytesWritten = activeLogFile.length()
            try {
                stream = FileOutputStream(activeLogFile, true)
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    while (!closed.get()) {
                        val line = reader.readLine() ?: break
                        if (!isRelevantLine(line)) continue
                        val payload = (line + "\n").toByteArray(Charsets.UTF_8)
                        synchronized(writeLock) {
                            if (bytesWritten + payload.size > maxFileBytes) {
                                runCatching { stream?.close() }
                                rotatedLogFile.delete()
                                activeLogFile.renameTo(rotatedLogFile)
                                stream = FileOutputStream(activeLogFile, false)
                                bytesWritten = 0L
                            }
                            stream?.write(payload)
                            bytesWritten += payload.size
                        }
                    }
                }
            } catch (_: Throwable) {
                // Closing or subprocess EOF.
            } finally {
                runCatching { stream?.close() }
            }
        }, "LabDiagnosticLogcat").apply {
            isDaemon = true
            start()
        }
    }

    fun noteHealthEvent(event: CameraHealthEvent) {
        recordedHealthEvents.add(event)
        while (recordedHealthEvents.size > 64) {
            runCatching { recordedHealthEvents.removeAt(0) }
        }
        synchronized(writeLock) {
            runCatching {
                FileOutputStream(activeLogFile, true).use {
                    val marker = ">>> HEALTH_EVENT [${event.timestampMs}] issue=${event.issue} cam=${event.cameraId} detail=${event.detail}\n"
                    it.write(marker.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    fun recentHealthEvents(): List<CameraHealthEvent> = recordedHealthEvents.toList()

    fun snapshotAnomaly(
        reason: String,
        attemptDir: File?,
        latestSample: ThermalSample?,
        extra: JSONObject? = null,
    ): File {
        val targetDir = attemptDir ?: directory
        targetDir.mkdirs()
        val outFile = File(targetDir, "anomaly_snapshot.json")
        val tailLines = ArrayList<String>(120)
        synchronized(writeLock) {
            if (activeLogFile.isFile) {
                runCatching {
                    val all = activeLogFile.readLines()
                    tailLines.addAll(all.takeLast(120))
                }
            }
        }
        val crashTail = runCatching {
            val p = ProcessBuilder("logcat", "-b", "crash,events", "-t", "80", "-v", "threadtime")
                .redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            p.destroyForcibly()
            text.lines().takeLast(60)
        }.getOrDefault(emptyList())
        val doc = JSONObject().apply {
            put("epochMs", System.currentTimeMillis())
            put("reason", reason)
            latestSample?.let { put("thermal", it.toJson()) }
            put("healthEvents", JSONArray().apply {
                recordedHealthEvents.forEach { ev ->
                    put(JSONObject().apply {
                        put("issue", ev.issue.name)
                        put("cameraId", ev.cameraId)
                        put("detail", ev.detail)
                        put("timestampMs", ev.timestampMs)
                    })
                }
            })
            extra?.let { put("extra", it) }
            put("recentFilteredLogcat", JSONArray(tailLines))
            put("recentCrashAndEventsTail", JSONArray(crashTail))
        }
        runCatching { outFile.writeText(doc.toString(2)) }
        return outFile
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val proc = process
        process = null
        runCatching { proc?.destroy() }
        runCatching { readerThread?.join(500L) }
        runCatching { proc?.destroyForcibly() }
        readerThread = null
    }

    companion object {
        const val DEFAULT_MAX_FILE_BYTES = 2L * 1024L * 1024L
        private val RELEVANT_REGEX = Regex(
            "CameraDaemon|CameraService|HISP|CamX|OpenCineCam|MediaCodec|OMX|hw_thermal|libc|DEBUG|AndroidRuntime|TONEMAP_PRESET|AP_COMMAND_ACQUIRE|VENC|OMXComponentEncoder|BufferQueue|CaptureEpoch|Direct10Bit",
            RegexOption.IGNORE_CASE,
        )

        internal fun isRelevantLine(line: String): Boolean = RELEVANT_REGEX.containsMatchIn(line)
    }
}
