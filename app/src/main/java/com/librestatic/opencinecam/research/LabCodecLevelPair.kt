/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.research

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Size
import android.view.Surface
import com.librestatic.opencinecam.camera.OpenCineLogGpuPipeline
import com.librestatic.opencinecam.camera.RecordingDataRate

internal fun Throwable.toCodecFailure() = json(
    "type" to javaClass.name, "message" to message?.take(400),
    "diagnosticInfo" to (this as? MediaCodec.CodecException)?.diagnosticInfo,
    "errorCode" to (this as? MediaCodec.CodecException)?.errorCode,
    "transient" to (this as? MediaCodec.CodecException)?.isTransient,
    "recoverable" to (this as? MediaCodec.CodecException)?.isRecoverable,
)

internal fun hevcProbeKeys(case: LabCase, profile: Int, level: Int?, sar: Boolean = true): Map<String, Int> = buildMap {
    put(MediaFormat.KEY_PROFILE, profile)
    level?.let { put(MediaFormat.KEY_LEVEL, it) }
    put(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
    put(MediaFormat.KEY_BIT_RATE, RecordingDataRate.gpuVideoBps(20_000_000, case.fps))
    put(MediaFormat.KEY_FRAME_RATE, case.fps)
    put(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    put(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
    put(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
    put(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
    put(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
    if (sar) {
        put(LabApiCompat.KEY_PIXEL_ASPECT_RATIO_WIDTH, 1)
        put(LabApiCompat.KEY_PIXEL_ASPECT_RATIO_HEIGHT, 1)
    }
}

/** Isolates configuration admission. Each arm gets a fresh instance of the same production-selected codec. */
internal fun runCodecLevelPair(
    case: LabCase, attempt: LabJournal.Attempt, checkBudget: () -> Unit,
): Pair<LabVerdict, String?> {
    val size = Size(case.width, case.height)
    val candidate = OpenCineLogGpuPipeline.findHevcSdrEncoder(size, case.fps)
        ?: return LabVerdict.FAILED to "Production selector found no hardware HEVC Main candidate."
    var admitted = 0
    for (level in listOf(null, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51)) {
        checkBudget()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, case.width, case.height)
        hevcProbeKeys(case, candidate.profile, level, android.os.Build.VERSION.SDK_INT >= 30).forEach(format::setInteger)
        attempt.event(LabStage.RECORDER, json("event" to "codec_admission_arm", "codec" to candidate.codecName,
            "level" to level, "format" to format.toString(), "framesSubmitted" to 0))
        var codec: MediaCodec? = null
        var surface: Surface? = null
        var started = false
        var step = "create"
        try {
            val encoder = MediaCodec.createByCodecName(candidate.codecName)
            codec = encoder
            step = "configure"
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            step = "input_surface"
            surface = encoder.createInputSurface()
            step = "start"
            encoder.start()
            started = true
            admitted++
            attempt.notice(json("event" to "codec_arm_result", "level" to level, "admitted" to true, "step" to step))
        } catch (error: Exception) {
            attempt.notice(json("event" to "codec_arm_result", "level" to level, "admitted" to false,
                "step" to step, "failure" to error.toCodecFailure()))
        } finally {
            val stopped = runCatching { if (started) codec?.stop() }
            val released = runCatching { codec?.release() }
            val surfaceReleased = runCatching { surface?.release() }
            listOf(stopped, released, surfaceReleased).firstNotNullOfOrNull { it.exceptionOrNull() }?.let {
                throw LabRetirementFailure(IllegalStateException("Codec probe cleanup failed", it))
            }
        }
    }
    return when (admitted) {
        2 -> LabVerdict.ADMITTED to "Both configurations started. No frames were submitted."
        1 -> LabVerdict.MISMATCH to "Only one configuration started. Compare the arm receipts."
        else -> LabVerdict.FAILED to "Both configurations failed. Compare the failing native steps."
    }
}
