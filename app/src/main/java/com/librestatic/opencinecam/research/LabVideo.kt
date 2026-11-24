/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import android.content.Context
import android.graphics.ImageFormat
import android.media.ImageReader
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Size
import com.librestatic.opencinecam.camera.Camera2PreviewEngine
import com.librestatic.opencinecam.camera.Main10FileChecks
import com.librestatic.opencinecam.camera.Main10TakeKind
import com.librestatic.opencinecam.camera.OpenCineLogRecordingEvidence
import com.librestatic.opencinecam.camera.QualificationStatus
import com.librestatic.opencinecam.camera.RecordingGeometryCalculator
import com.librestatic.opencinecam.camera.RecordingGeometryMode
import com.librestatic.opencinecam.diagnostics.DeviceQuirksProvider
import com.librestatic.opencinecam.media.video.VideoCodecPreference
import com.librestatic.opencinecam.playback.main10SignalOf
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray

internal class LabVideo(
    private val context: Context,
    private val profiles: LabProfiles,
    private val checkBudget: () -> Unit,
) {
    fun run(case: LabCase, journal: LabJournal.Attempt): Pair<LabVerdict, String?> {
        val signals = LabSignals()
        val engine = Camera2PreviewEngine(context, deviceQuirks = DeviceQuirksProvider.lazy(context), researchObserver = signals)
        val thread = HandlerThread("occ-lab-preview").apply { start() }
        var reader: ImageReader? = null
        try {
            val reportedBuffer = AtomicBoolean()
            val reportedSinkError = AtomicBoolean()
            val size = Size(case.width, case.height)
            val sink = ImageReader.newInstance(640, 360, ImageFormat.PRIVATE, 3)
            reader = sink
            sink.setOnImageAvailableListener({ source ->
                runCatching {
                    source.acquireLatestImage()?.use { image ->
                        if (reportedBuffer.compareAndSet(false, true)) {
                            image.hardwareBuffer?.use { buffer ->
                                signals.notice("preview_sink_hardware_buffer", json(
                                    "width" to buffer.width, "height" to buffer.height,
                                    "format" to buffer.format, "layers" to buffer.layers,
                                    "usage" to buffer.usage, "imageFormat" to image.format,
                                    "scope" to "Preview output sink, after GPU rendering.",
                                ))
                            }
                        }
                    }
                }.onFailure {
                    if (reportedSinkError.compareAndSet(false, true)) signals.notice("preview_sink_error", json("error" to it.toString().take(300)))
                }
            }, Handler(thread.looper))
            val log = profiles.logProfile(case)
            val descriptor = profiles.descriptor
            engine.startPreview(
                descriptor = descriptor, surface = sink.surface, displayRotationDegrees = 0, listener = signals,
                openCineLog = log != null, logProfile = log, targetFps = case.fps, gpuPreview = log == null,
                videoProfile = if (log == null) profiles.videoProfile(case) else null,
            )
            await(signals, journal, START_TIMEOUT_MS, "preview or first Camera2 result") { signals.preview && signals.cameraResult }
            if (case.previewOnly) {
                await(signals, journal, 2_000, "preview observation", finishAtDeadline = true) { false }
                journal.notice(signals.snapshot())
                return LabVerdict.OBSERVED to "Preview and Camera2 results observed with the encoder idle."
            }
            val geometry = RecordingGeometryCalculator.calculate(
                sourceSize = size, sensorOrientationDegrees = descriptor.sensorOrientation,
                deviceOrientationDegrees = 0, lensFacing = descriptor.lensFacing, mode = RecordingGeometryMode.NATIVE_RASTER,
            )
            val bitrate = 20_000_000
            journal.notice(json("event" to "encoder_requested", "bitrate" to bitrate, "audio" to false,
                "geometry" to RecordingGeometryMode.NATIVE_RASTER.name, "sourcePath" to log?.sourcePath?.name,
                "sourceRequestedDynamicRange" to log?.sourcePath?.dynamicRange,
                "sourceRequestedColorSpace" to log?.sourcePath?.colorSpace))
            val mode = ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE
            ParcelFileDescriptor.open(journal.clip, mode).use { output ->
                val accepted = if (log != null) {
                    engine.startOpenCineLogVideo(output, geometry, bitrate,
                        hlgOutput = case.path == LabPath.HLG || case.path == LabPath.HDR10,
                        hdr10Output = case.path == LabPath.HDR10, sampleListener = signals)
                } else {
                    engine.startVideo(output, audio = null, recordingGeometry = geometry, videoBitrate = bitrate,
                        videoCodec = if (case.path == LabPath.HEVC) VideoCodecPreference.HEVC else VideoCodecPreference.H264,
                        sampleListener = signals)
                }
                signals.drain(journal)
                check(accepted) { signals.failure ?: "Production recorder rejected start." }
                await(signals, journal, START_TIMEOUT_MS, "recorder start") { signals.started }
                val duration = if (case.longer) 10_000L else 4_000L
                try {
                    await(signals, journal, duration, "recording", finishAtDeadline = true) { signals.stopped != null }
                } finally {
                    if (signals.stopped == null) engine.stopVideo()
                }
                await(signals, journal, STOP_TIMEOUT_MS, "recording finalization", enforceBudget = false) { signals.stopped != null }
                engine.stoppedRecordingOutputRetirement().get(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                check(signals.stopped == true) { "Recorder finalized with failure." }
                val audit = engine.captureDiagnostics()
                journal.notice(json("event" to "camera_audit",
                    "results" to audit?.results, "droppedFrames" to audit?.droppedFrames,
                    "failedCaptures" to audit?.failedCaptures, "cadenceOutliers" to audit?.cadenceOutliers,
                    "maxFrameDeltaUs" to audit?.maxFrameDeltaUs))
            }
            val evidence = engine.consumeLastOpenCineLogEvidence()
            evidence?.let { journal.notice(it.toResearchJson()) }
        } finally {
            try {
                engine.closeAsync().get(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                signals.drain(journal)
                journal.notice(signals.snapshot().put("retirement", "acknowledged"))
            } catch (error: Exception) {
                throw LabRetirementFailure(error)
            } finally {
                reader?.close()
                thread.quitSafely()
                thread.join(STOP_TIMEOUT_MS)
            }
        }
        return inspectClip(case, journal)
    }

    private fun await(
        signals: LabSignals,
        journal: LabJournal.Attempt,
        timeoutMs: Long,
        phase: String,
        finishAtDeadline: Boolean = false,
        enforceBudget: Boolean = true,
        ready: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            signals.drain(journal)
            signals.failure?.let { error(it) }
            if (enforceBudget) checkBudget()
            if (ready()) return
            if (SystemClock.elapsedRealtime() >= deadline) {
                check(finishAtDeadline) { "Timeout at $phase" }
                return
            }
            Thread.sleep(125)
        }
    }

    private companion object {
        const val START_TIMEOUT_MS = 8_000L
        const val STOP_TIMEOUT_MS = 8_000L
    }
}

internal class LabRetirementFailure(cause: Exception) : Exception("Camera or encoder retirement could not be verified.", cause)

private fun OpenCineLogRecordingEvidence.toResearchJson() = json(
    "event" to "production_gpu_evidence", "sourcePath" to sourcePath.name,
    "sourceSurface" to sourceSurface, "sourceDataSpace" to sourceDataSpace,
    "sourceDataSpaceMismatchedFrames" to sourceDataSpaceMismatchedFrames,
    "unexpectedSourceDataSpace" to unexpectedSourceDataSpace, "sourcePrecisionDescription" to sourcePrecision,
    "effectiveSourcePrecision" to "UNKNOWN", "eglRenderTargetBits" to eglRenderTargetBits,
    "sourceYcbcrConversion" to ycbcrConversion, "curve" to curve, "shaderSha256" to transformSha256,
    "codecName" to codecName, "codecMime" to codecMime, "codecProfile" to codecProfile,
    "encoderVuiTransfer" to encoderVuiTransfer, "containerVuiTransfer" to containerVuiTransfer,
    "codecFallback" to codecFallback, "encodedFrames" to encodedFrames, "firstPtsUs" to firstPtsUs,
    "lastPtsUs" to lastPtsUs, "maxVideoPtsGapUs" to maxVideoPtsGapUs, "targetFps" to targetFps,
    "videoPtsGapsOverThreshold" to videoPtsGapsOverThreshold, "greyReference" to greyReference.name,
    "sceneGain" to sceneGain.toDouble(),
)

private fun inspectClip(case: LabCase, journal: LabJournal.Attempt): Pair<LabVerdict, String?> {
    val probe = readClip(journal.clip)
    val format = probe.format
    val mime = format.getString(MediaFormat.KEY_MIME)
    val signal = main10SignalOf(mime, format.intOrNull(MediaFormat.KEY_PROFILE),
        format.intOrNull(MediaFormat.KEY_COLOR_STANDARD), format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER))
    val main10 = if (case.path == LabPath.LOG || case.path == LabPath.HLG || case.path == LabPath.HDR10) {
        val kind = if (case.path == LabPath.HLG) Main10TakeKind.HLG else Main10TakeKind.LOG
        Main10FileChecks.evaluate(kind, signal, journal.clip.name, System.currentTimeMillis()).fileSignal
    } else null
    val pts = probe.pts.sorted()
    val span = if (pts.size >= 2) pts.last() - pts.first() else 0L
    val fps = if (span > 0) (pts.size - 1) * 1_000_000.0 / span else null
    val issue = when {
        fps == null || pts.size < 2 -> "No usable video cadence in the MP4."
        format.intOrNull(MediaFormat.KEY_WIDTH) != case.width ||
            format.intOrNull(MediaFormat.KEY_HEIGHT) != case.height -> "Encoded raster differs from the request."
        main10 != null && main10.status != QualificationStatus.PASS -> "Main10 file check: ${main10.reasonCode}"
        case.path == LabPath.HDR10 && (signal.colorStandard != "BT.2020" || signal.colorTransfer != "PQ") -> "PQ/BT.2020 tags missing."
        case.path == LabPath.HEVC && mime != MediaFormat.MIMETYPE_VIDEO_HEVC -> "Requested HEVC differs from the MP4 codec."
        case.path == LabPath.AVC && mime != MediaFormat.MIMETYPE_VIDEO_AVC -> "Requested AVC differs from the MP4 codec."
        kotlin.math.abs(fps - case.fps) > case.fps * 0.05 -> "File cadence differs from the target by more than 5%."
        pts.zipWithNext().any { (a, b) -> b <= a || b - a > 1_500_000.0 / case.fps } -> "File contains a repeated PTS or a cadence gap."
        else -> null
    }
    journal.event(LabStage.FILE, json(
        "format" to format.toString(), "mime" to mime, "profile" to signal.profile,
        "colorStandard" to signal.colorStandard, "colorTransfer" to signal.colorTransfer,
        "codedBitDepthFromProfile" to signal.bitDepth, "effectiveSourcePrecision" to "UNKNOWN",
        "fileSignalCheck" to main10?.status?.name, "fileSignalReason" to main10?.reasonCode,
        "samplePtsUsInExtractorOrder" to JSONArray(probe.pts), "observedFileFps" to fps,
        "maxSampleGapUs" to pts.zipWithNext { a, b -> b - a }.maxOrNull(), "durationFromPtsUs" to span,
        "containerBytes" to journal.clip.length(),
    ))
    val decode = pts.getOrNull(pts.size / 2)?.let { inspectDecodedFrame(journal.clip, it) }
    journal.notice(json("event" to "decoded_frame_and_clip_hash", "decoder" to decode,
        "clipSha256" to clipSha256(journal.clip), "effectiveSourcePrecision" to "UNKNOWN"))
    val verdict = issue ?: if (decode?.optString("status") == "DECODED") null else "A decoded frame could not be verified."
    return if (verdict == null) LabVerdict.OBSERVED to null else LabVerdict.MISMATCH to verdict
}

private data class LabClip(val format: MediaFormat, val pts: List<Long>)

private fun readClip(clip: File): LabClip {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(clip.absolutePath)
        val index = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: error("No video track in finalized file")
        val format = extractor.getTrackFormat(index)
        extractor.selectTrack(index)
        val pts = mutableListOf<Long>()
        while (extractor.sampleTime >= 0 && pts.size < 4_096) {
            pts += extractor.sampleTime
            if (!extractor.advance()) break
        }
        LabClip(format, pts)
    } finally {
        extractor.release()
    }
}

private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null
