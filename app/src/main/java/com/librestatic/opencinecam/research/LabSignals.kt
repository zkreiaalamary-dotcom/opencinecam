/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaFormat
import com.librestatic.opencinecam.camera.Camera2Analysis
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.Camera2PreviewListener
import com.librestatic.opencinecam.camera.Camera2PreviewMetadata
import com.librestatic.opencinecam.camera.Camera2ResearchObserver
import com.librestatic.opencinecam.camera.CaptureAuditAction
import com.librestatic.opencinecam.camera.EncodedSampleListener
import com.librestatic.opencinecam.camera.PreviewGraphLoss
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/** A run's private callback receiver. Camera, GL and encoder threads never write to disk. */
internal class LabSignals : Camera2PreviewListener, EncodedSampleListener, Camera2ResearchObserver {
    private val events = ArrayBlockingQueue<Pair<LabStage, JSONObject>>(96)
    private val dropped = AtomicInteger()
    private val gpuFrames = LabTimestamps()
    private val encoded = LabTimestamps()
    private val cameraFrames = LabTimestamps()
    private var lastRangeAt = 0L

    @Volatile var gpuSource: Triple<Int, Int, Int>? = null
        private set

    override fun onGpuSource(width: Int, height: Int, fps: Int, source: com.librestatic.opencinecam.camera.OpenCineLogSourcePath) {
        gpuSource = Triple(width, height, fps)
        notice("configured_gpu_source", json("width" to width, "height" to height, "targetFps" to fps,
            "sourcePath" to source.name, "scope" to "Pipeline input configuration before the camera session"))
    }

    override fun onEncoderStep(step: String, state: String, codec: String?, format: MediaFormat?, failure: Throwable?) {
        notice("gpu_encoder_step", json("step" to step, "state" to state, "codec" to codec,
            "elapsedRealtimeNs" to android.os.SystemClock.elapsedRealtimeNanos(),
            "requestedFormat" to format?.toString(), "failure" to failure?.toCodecFailure()))
    }

    override fun onResult(request: CaptureRequest, result: TotalCaptureResult, sensorTimestampNs: Long?) {
        val frame = result.frameNumber
        if (sensorTimestampNs != null) cameraFrames.append(frame, sensorTimestampNs)
        if (frame < lastRangeAt) return
        lastRangeAt = frame + 60
        notice("camera_request_result", json(
            "frame" to frame, "sensorTimestampNs" to sensorTimestampNs,
            "submittedAeFpsRange" to request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)?.toString(),
            "reportedAeFpsRange" to result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)?.toString(),
            "reportedFrameDurationNs" to result.get(CaptureResult.SENSOR_FRAME_DURATION),
        ))
    }
    @Volatile var preview = false
        private set
    @Volatile var cameraResult = false
        private set
    @Volatile var started = false
        private set
    @Volatile var stopped: Boolean? = null
        private set
    @Volatile var failure: String? = null
        private set
    @Volatile private var latestMetadata: Camera2PreviewMetadata? = null

    override fun onOpening(descriptor: Camera2CameraDescriptor) = offer(LabStage.CAMERA,
        json("camera" to descriptor.cameraId, "event" to "opening"))
    override fun onPreviewStarted(descriptor: Camera2CameraDescriptor) {
        offer(LabStage.PREVIEW, json("camera" to descriptor.cameraId, "event" to "preview_ready"))
        preview = true
    }

    override fun onMetadata(metadata: Camera2PreviewMetadata) {
        latestMetadata = metadata
        if (!cameraResult) {
            offer(LabStage.FIRST_CAMERA_RESULT, metadata.toResearchJson())
            cameraResult = true
        }
    }

    override fun onJpegCaptured(bytes: ByteArray, width: Int, height: Int) = Unit
    override fun onDngCaptured(bytes: ByteArray, width: Int, height: Int) = Unit
    override fun onAnalysis(analysis: Camera2Analysis) = Unit

    override fun onRecordingStarted(width: Int, height: Int) {
        offer(LabStage.RECORDER, json("event" to "recorder_started", "width" to width, "height" to height))
        started = true
    }

    override fun onRecordingFirstFrame(sensorTimestampNs: Long) =
        offer(LabStage.FIRST_GPU_FRAME, json("sensorTimestampNs" to sensorTimestampNs))

    override fun onRecordingFrame(sensorTimestampNs: Long, presentationTimeNs: Long) {
        gpuFrames.append(sensorTimestampNs, presentationTimeNs)
    }

    override fun onTrack(video: Boolean, format: MediaFormat, rotationDegrees: Int) {
        if (video) offer(LabStage.RECORDER, json("event" to "muxer_track", "format" to format.toString(),
            "containerRotationDegrees" to rotationDegrees))
    }

    override fun onSample(video: Boolean, size: Int, presentationTimeUs: Long, flags: Int) {
        if (video && encoded.append(presentationTimeUs, size.toLong())) {
            offer(LabStage.FIRST_ENCODED, json("ptsUs" to presentationTimeUs, "bytes" to size, "flags" to flags))
        }
    }

    override fun onRecordingStopped(success: Boolean) {
        offer(LabStage.STOPPED, json("muxerFinalizationReported" to success))
        stopped = success
    }

    override fun onFailure(code: String, message: String, recoverable: Boolean) {
        notice("engine_failure", json("code" to code, "message" to message, "recoverable" to recoverable))
        failure = "$code — $message"
    }

    override fun onPreviewGraphLoss(loss: PreviewGraphLoss) =
        notice("preview_graph_loss", json("raw" to loss.raw, "analysis" to loss.analysis, "physicalStill" to loss.physicalStill))

    override fun onVideoCodecFallback(reason: String) = notice("codec_fallback", json("reason" to reason))
    override fun onSoftwareVideoEncoder(codecName: String) = notice("software_encoder", json("codec" to codecName))
    override fun onActiveLens(physicalId: String?) = notice("active_lens", json("physicalId" to physicalId))
    override fun onSustainedCadenceLoss(action: CaptureAuditAction, detail: String) =
        notice("sustained_cadence_loss", json("action" to action.toString(), "detail" to detail))

    fun drain(attempt: LabJournal.Attempt) {
        while (true) {
            val (stage, data) = events.poll() ?: return
            if (data.optBoolean("notice")) attempt.notice(data) else attempt.event(stage, data)
        }
    }

    fun snapshot() = json(
        "cameraResult" to latestMetadata?.toResearchJson(),
        "cameraFrameNumberAndSensorNs" to cameraFrames.snapshot(),
        "gpuSensorNsAndInputPtsNs" to gpuFrames.snapshot(),
        "muxerInputPtsUsAndBytes" to encoded.snapshot(),
        "droppedObservationEvents" to dropped.get(),
    )

    fun notice(kind: String, data: JSONObject) = offer(LabStage.CAMERA, data.put("notice", true).put("kind", kind))

    private fun offer(stage: LabStage, fields: JSONObject) {
        if (!events.offer(stage to fields)) dropped.incrementAndGet()
    }
}

private fun Camera2PreviewMetadata.toResearchJson() = json(
    "frameNumber" to frameNumber, "sensorTimestampNs" to sensorTimestampNs,
    "iso" to sensitivityIso, "exposureTimeNs" to exposureTimeNs, "effectiveFps" to effectiveFps,
    "awbGains" to awbGains?.let { JSONArray(it) }, "reportedStabilization" to videoStabilization,
    "reportedToneMap" to toneMapMode, "submittedToneMap" to submittedToneMapMode, "focusDiopters" to focusDistanceDiopters,
)

private class LabTimestamps {
    private val first = LongArray(2_048)
    private val second = LongArray(first.size)
    private var count = 0

    @Synchronized
    fun append(a: Long, b: Long): Boolean {
        if (count >= first.size) return false
        first[count] = a
        second[count] = b
        return count++ == 0
    }

    fun snapshot(): JSONArray {
        val rows = synchronized(this) { first.copyOf(count) to second.copyOf(count) }
        return JSONArray(rows.first.indices.map { JSONArray(listOf(rows.first[it], rows.second[it])) })
    }
}
