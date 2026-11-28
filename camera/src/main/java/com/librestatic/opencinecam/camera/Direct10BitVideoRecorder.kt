/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Admission policy for bypassing the GLES render stage and feeding Camera2 HAL's
 * native 10-bit CUVA HLG (`0x324` / `HAL_PIXEL_FORMAT_YCBCR_P010`) directly into
 * the hardware HEVC Main10 encoder `MediaCodec.createInputSurface()`.
 */
internal object Direct10BitVideoPolicy {
    fun shouldUseDirect10BitHlg(
        hlgOutput: Boolean,
        hdr10Output: Boolean,
        huaweiCuvaHdr: Boolean,
        constrainedHighSpeed: Boolean,
        hasRecordingLut: Boolean,
        hasLensCorrection: Boolean,
        isTimelapse: Boolean,
        hasProjectRateOverride: Boolean,
        hasDesqueezedAnamorphic: Boolean = false,
        hasEncoderOverride: Boolean = false,
        targetFps: Int = 30,
    ): Boolean =
        hlgOutput &&
            !hdr10Output &&
            huaweiCuvaHdr &&
            !constrainedHighSpeed &&
            !hasRecordingLut &&
            !hasLensCorrection &&
            !isTimelapse &&
            !hasProjectRateOverride &&
            !hasDesqueezedAnamorphic &&
            !hasEncoderOverride &&
            targetFps in setOf(24, 30, 60)
}

/**
 * Direct Camera2 -> HEVC Main10 surface recorder for Huawei Kirin CUVA HDR (`0x324`),
 * eliminating GLES P010->RGBA1010102->P010 round-trips while keeping viewfinder preview
 * & scopes running via dual-surface session sharing and sharing [OpenCineLogGpuPipeline.EmbeddedAac]
 * + [CaptureEpochClock] audio/video sync.
 */
internal class Direct10BitVideoRecorder private constructor(
    val codecSurface: Surface,
    private val codec: MediaCodec,
    private val muxer: MediaMuxer,
    private val outputDescriptor: ParcelFileDescriptor,
    private val candidate: OpenCineLogEncoderCandidate,
    private val geometry: RecordingGeometry,
    private val sourcePath: OpenCineLogSourcePath,
    private val targetFps: Int,
    private val audio: OpenCineLogGpuPipeline.EmbeddedAac?,
    private val captureEpoch: CaptureEpochClock?,
    private val onEncodedProgress: ((EncodedRecordingProgress) -> Unit)?,
    private val onFirstRecordedFrame: ((Long) -> Unit)?,
    private val onRecordedFrame: ((Long, Long) -> Unit)?,
    private val onStopped: (Boolean, OpenCineLogRecordingEvidence?) -> Unit,
    val fileRetirement: OpenCineLogGpuPipeline.RecordingFileRetirement,
    private val sampleListener: EncodedSampleListener?,
) {
    private val takeId = takeIds.incrementAndGet()
    private val started = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val cleanedUp = AtomicBoolean(false)
    private val codecReleased = AtomicBoolean(false)
    private val timestampBootMapper = CameraTimestampBootMapper(captureEpoch?.cameraRealtime ?: false)

    @Volatile private var drainThread: Thread? = null
    @Volatile var failure: Throwable? = null
        private set
    @Volatile private var frames = 0L
    @Volatile private var audioPackets = 0L
    @Volatile private var firstPtsUs: Long? = null
    @Volatile private var lastPtsUs: Long? = null
    @Volatile private var maxVideoPtsGapUs: Long? = null
    @Volatile private var videoPtsGapsOverThreshold = 0L
    @Volatile private var encoderVuiTransfer: Int? = null
    @Volatile private var containerVuiTransfer: Int? = null
    private var vuiRewriteLogged = false
    private var firstFrameReported = false
    private var recordedFrameListenerFailed = false

    @Synchronized
    private fun noteFailure(problem: Throwable) {
        val first = failure
        if (first == null) failure = problem else if (first !== problem) first.addSuppressed(problem)
    }

    /** Call only after the CameraCaptureSession is configured and repeating is ready to run. */
    fun startAfterSessionConfigured() {
        check(started.compareAndSet(false, true)) { "Direct10BitVideoRecorder already started" }
        audio?.start { problem ->
            noteFailure(problem)
            stopRecording()
        }
        check(failure == null) { "Embedded AAC start failed" }
        onEncodedProgress?.invoke(EncodedRecordingProgress(takeId, 0L, null, null))
        drainThread = Thread({ drain() }, "Direct10BitCodecDrain").apply { start() }
    }

    /**
     * Releases MediaCodec and its input Surface. Must only be called AFTER Camera2 has
     * closed or replaced the CameraCaptureSession targeting [codecSurface], so Kirin HAL
     * does not return -16 EBUSY on deleteStream.
     */
    fun releaseCodecAndSurface() {
        if (!codecReleased.compareAndSet(false, true)) return
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { codecSurface.release() }
    }

    /** Clean up resources if Camera2 session configuration fails before recording begins. */
    fun abortBeforeStart(reason: Throwable? = null) {
        if (!cleanedUp.compareAndSet(false, true)) return
        if (reason != null) noteFailure(reason)
        fileRetirement.retire { audio?.close() }
        fileRetirement.retire { muxer.release() }
        fileRetirement.retire { releaseCodecAndSurface() }
        fileRetirement.retire { outputDescriptor.close() }
        fileRetirement.finish()
    }

    fun stopRecording(): Boolean {
        if (!stopRequested.compareAndSet(false, true)) return false
        audio?.requestStop()
        runCatching { codec.signalEndOfInputStream() }.onFailure(::noteFailure)
        return true
    }

    fun retirementFuture(): CompletableFuture<Unit> = fileRetirement.completion.thenApply { it }

    private fun drain() {
        val videoInfo = MediaCodec.BufferInfo()
        val audioInfo = MediaCodec.BufferInfo()
        var videoTrack = -1
        var audioTrack = -1
        var muxerStarted = false
        var videoEos = false
        var audioEos = audio == null
        data class PendingDirectSample(val sample: OpenCineLogGpuPipeline.PendingMuxSample, val sourceNs: Long)
        val pending = ArrayDeque<PendingDirectSample>()
        val timestampNormalizer = MuxTimestampNormalizer(captureEpoch.takeIf { audio != null })
        val maxPendingSamples = OpenCineLogGpuPipeline.maxPendingMuxSamples(
            targetFps,
            audio?.calibration?.config?.sampleRateHz,
            audio?.calibration?.config?.let { it.maxInputBytes / (2 * it.channels) },
        )
        var audioEncoderDelayFrames: Int? = null
        var stopDeadlineNs: Long? = null
        val forcedTransfer = HevcVuiTransfer.ARIB_STD_B67

        fun noteSample(video: Boolean, sourceNs: Long, normalizedPtsUs: Long) {
            if (!video) {
                audioPackets++
                return
            }
            frames++
            if (!firstFrameReported) {
                firstFrameReported = true
                runCatching { onFirstRecordedFrame?.invoke(sourceNs) }
            }
            if (!recordedFrameListenerFailed && onRecordedFrame != null) {
                runCatching { onRecordedFrame?.invoke(sourceNs, normalizedPtsUs * 1_000L) }
                    .onFailure { recordedFrameListenerFailed = true }
            }
            lastPtsUs?.let { previousPtsUs ->
                val gapUs = (normalizedPtsUs - previousPtsUs).coerceAtLeast(0L)
                maxVideoPtsGapUs = maxOf(maxVideoPtsGapUs ?: 0L, gapUs)
                if (gapUs > 1_500_000L / targetFps) videoPtsGapsOverThreshold++
            }
            firstPtsUs = firstPtsUs ?: normalizedPtsUs
            lastPtsUs = normalizedPtsUs
            onEncodedProgress?.invoke(EncodedRecordingProgress(takeId, frames, firstPtsUs, lastPtsUs))
        }

        fun rewriteHlgTransfer(annexB: ByteArray): ByteArray = try {
            HevcVuiTransfer.rewrite(annexB, forcedTransfer).let { result ->
                result.previousTransfer?.let { if (encoderVuiTransfer == null) encoderVuiTransfer = it }
                result.bytes
            }
        } catch (problem: IllegalArgumentException) {
            if (!vuiRewriteLogged) {
                vuiRewriteLogged = true
                Log.w(LOG_TAG, "HEVC SPS could not be parsed; keeping encoder transfer.", problem)
            }
            annexB
        }

        fun normalizeVideoFormat(format: MediaFormat) {
            val csd = format.getByteBuffer("csd-0")?.duplicate()?.let { value ->
                ByteArray(value.remaining()).also { value.get(it) }
            } ?: return
            val rewritten = rewriteHlgTransfer(csd)
            containerVuiTransfer = runCatching { HevcVuiTransfer.transferOf(rewritten) }.getOrNull()
            if (containerVuiTransfer == forcedTransfer) {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(rewritten))
                format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
            }
        }

        fun startMuxerIfReady() {
            if (muxerStarted || videoTrack < 0 || (audio != null && audioTrack < 0) ||
                (audio != null && captureEpoch?.ready() == false)
            ) return
            muxer.start()
            muxerStarted = true
            pending
                .map { entry ->
                    entry.copy(
                        sample = entry.sample.copy(
                            presentationTimeUs = timestampNormalizer.normalize(entry.sample.video, entry.sample.presentationTimeUs),
                        ),
                    )
                }
                .sortedBy { it.sample.presentationTimeUs }
                .forEach { entry ->
                    val sample = entry.sample
                    val track = if (sample.video) videoTrack else audioTrack
                    muxer.writeSampleData(
                        track,
                        ByteBuffer.wrap(sample.data),
                        MediaCodec.BufferInfo().apply {
                            offset = 0
                            size = sample.data.size
                            presentationTimeUs = sample.presentationTimeUs
                            flags = sample.flags
                        },
                    )
                    runCatching { sampleListener?.onSample(sample.video, sample.data.size, sample.presentationTimeUs, sample.flags) }
                    noteSample(sample.video, entry.sourceNs, sample.presentationTimeUs)
                }
            pending.clear()
        }

        fun drainOne(targetCodec: MediaCodec, info: MediaCodec.BufferInfo, video: Boolean, timeoutUs: Long): Boolean {
            when (val index = targetCodec.dequeueOutputBuffer(info, timeoutUs)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = targetCodec.outputFormat
                    if (video) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            format.setInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH, geometry.pixelAspectRatioWidth)
                            format.setInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT, geometry.pixelAspectRatioHeight)
                        }
                        check(videoTrack < 0) { "Video encoder emitted its format twice." }
                        normalizeVideoFormat(format)
                        videoTrack = muxer.addTrack(format)
                        runCatching { sampleListener?.onTrack(true, format, geometry.contentRotationDegrees) }
                    } else {
                        check(audioTrack < 0) { "AAC encoder emitted its format twice." }
                        val csd = requireNotNull(format.getByteBuffer("csd-0")).duplicate().let { value ->
                            ByteArray(value.remaining()).also { value.get(it) }
                        }
                        AacCodecCalibrator.requireCalibratedCodecConfig(requireNotNull(audio).calibration, csd)
                        audioEncoderDelayFrames = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && format.containsKey(MediaFormat.KEY_ENCODER_DELAY)) {
                            format.getInteger(MediaFormat.KEY_ENCODER_DELAY)
                        } else null
                        audioTrack = muxer.addTrack(format)
                        runCatching { sampleListener?.onTrack(false, format, 0) }
                    }
                    startMuxerIfReady()
                    return true
                }
                else -> if (index >= 0) {
                    val buffer = targetCodec.getOutputBuffer(index)
                    val isCodecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (info.size > 0 && buffer != null && !isCodecConfig) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val mappedNs: Long
                        val ptsUs: Long
                        if (video) {
                            mappedNs = timestampBootMapper.mapToBoottimeNs(
                                info.presentationTimeUs * 1_000L,
                                android.os.SystemClock.elapsedRealtimeNanos(),
                                System.nanoTime(),
                            )
                            captureEpoch?.videoInput(mappedNs)
                            ptsUs = mappedNs / 1_000L
                        } else {
                            mappedNs = info.presentationTimeUs * 1_000L
                            ptsUs = info.presentationTimeUs
                        }
                        val inBandIdr = video && (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                        if (muxerStarted) {
                            val normalizedPtsUs = timestampNormalizer.normalize(video, ptsUs)
                            val normalizedInfo = MediaCodec.BufferInfo().apply { set(info.offset, info.size, normalizedPtsUs, info.flags) }
                            val sample = if (!inBandIdr) buffer else {
                                val bytes = ByteArray(info.size).also { buffer.get(it) }
                                rewriteHlgTransfer(bytes).let { rewritten ->
                                    normalizedInfo.set(0, rewritten.size, normalizedPtsUs, info.flags)
                                    ByteBuffer.wrap(rewritten)
                                }
                            }
                            muxer.writeSampleData(if (video) videoTrack else audioTrack, sample, normalizedInfo)
                            runCatching { sampleListener?.onSample(video, normalizedInfo.size, normalizedPtsUs, normalizedInfo.flags) }
                            noteSample(video, mappedNs, normalizedPtsUs)
                        } else {
                            check(pending.size < maxPendingSamples) { "Muxer format/epoch negotiation buffer overflowed." }
                            val bytes = ByteArray(info.size)
                            buffer.get(bytes)
                            pending.add(
                                PendingDirectSample(
                                    OpenCineLogGpuPipeline.PendingMuxSample(
                                        video,
                                        if (inBandIdr) rewriteHlgTransfer(bytes) else bytes,
                                        ptsUs,
                                        info.flags,
                                    ),
                                    mappedNs,
                                ),
                            )
                        }
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        if (video) videoEos = true else audioEos = true
                    }
                    targetCodec.releaseOutputBuffer(index, false)
                    return true
                }
            }
            return false
        }

        try {
            while (!videoEos || !audioEos) {
                val audioProgress = audio?.takeUnless { audioEos }?.let {
                    drainOne(it.codec, audioInfo, video = false, timeoutUs = 0)
                } ?: false
                val videoProgress = if (!videoEos) {
                    drainOne(codec, videoInfo, video = true, timeoutUs = if (audioProgress) 0 else OpenCineLogGpuPipeline.CODEC_TIMEOUT_US)
                } else false
                startMuxerIfReady()
                if (stopRequested.get()) {
                    val deadline = stopDeadlineNs ?: (System.nanoTime() + OpenCineLogGpuPipeline.CODEC_STOP_TIMEOUT_NS).also { stopDeadlineNs = it }
                    if (System.nanoTime() > deadline) error("Direct 10-bit codec EOS timed out while finalizing.")
                    if (!audioProgress && !videoProgress && failure != null) break
                }
            }
        } catch (problem: Throwable) {
            noteFailure(problem)
        } finally {
            fileRetirement.retire { audio?.close() }?.let(::noteFailure)
            val success = failure == null && muxerStarted && videoEos && audioEos &&
                hasRequiredEncodedSamples(frames, audio?.submittedPcmFrames, audioPackets)
            var audioSourceWindow: AacSourceWindowResult? = null
            val timelineFinalizer: (() -> Unit)? = if (audio != null) {
                {
                    requireNotNull(captureEpoch).requireRetainedAudioFrames(audio.submittedPcmFrames)
                    check(audio.drainPaddingFrames == audio.calibration.drainPaddingFrames.toLong()) { "AAC drain padding did not complete" }
                    audioSourceWindow = finalizeAacSourceWindow(
                        ProjectMp4Descriptor(outputDescriptor.fileDescriptor),
                        AacSourceWindow(
                            audio.calibration.config.sampleRateHz,
                            audio.submittedPcmFrames,
                            audio.calibration.primingFrames.toLong(),
                            audioPackets,
                            requireNotNull(captureEpoch).offsetUs(false),
                        ),
                    )
                }
            } else null

            failure = finalizeEncodedRecording(
                ready = success,
                initialFailure = failure,
                muxerStarted = muxerStarted,
                stopMuxer = { fileRetirement.retire(muxer::stop)?.let { throw it } },
                releaseMuxer = { fileRetirement.retire(muxer::release)?.let { throw it } },
                finalizeTimeline = timelineFinalizer,
            )
            fileRetirement.retire { outputDescriptor.close() }?.let(::noteFailure)
            fileRetirement.finish()

            val evidence = if (success && failure == null) {
                val containerRot = geometry.contentRotationDegrees
                val display = if (containerRot % 180 == 90) geometry.sourceSize.swapped() else geometry.sourceSize
                OpenCineLogRecordingEvidence(
                    encodedProgress = EncodedRecordingProgress(takeId, frames, firstPtsUs, lastPtsUs),
                    avTiming = captureEpoch?.report(timestampNormalizer.videoOriginUs, timestampNormalizer.audioOriginUs, audioEncoderDelayFrames)
                        ?.copy(videoMuxOffsetUs = timestampNormalizer.videoOffsetUs)
                        ?.let { report ->
                            audio?.let { a ->
                                report.copy(
                                    submittedPcmFrames = a.submittedPcmFrames,
                                    encodedAudioPackets = audioPackets,
                                    aacCalibration = a.calibration,
                                    audioDrainPaddingFrames = a.drainPaddingFrames,
                                    audioSourceWindow = audioSourceWindow,
                                    audioPresentationOffsetUs = captureEpoch.offsetUs(false),
                                )
                            } ?: report.copy(audioStorage = "SEPARATE_LOSSLESS")
                        },
                    sourcePath = sourcePath,
                    sourceDynamicRange = sourcePath.dynamicRange,
                    sourceColorSpace = sourcePath.colorSpace,
                    sourceTransfer = sourcePath.transfer,
                    sourcePrecision = sourcePath.sourcePrecision,
                    sourceSurface = "DIRECT_SURFACE_0x324",
                    sourcePrimariesApplied = "BT.2020",
                    sourcePrimariesBasis = "direct-cuva-output-contract",
                    sourceDataSpace = null,
                    curve = HdrVideoFormat.HLG.curve,
                    curveVersion = HdrVideoFormat.HLG.curveVersion,
                    gamut = "BT.2020",
                    range = "limited",
                    codecMime = candidate.mime,
                    codecProfile = "Main10",
                    eglRenderTargetBits = 10,
                    codecName = candidate.codecName,
                    targetFps = targetFps,
                    transformSha256 = "direct-surface-bt2020-hlg",
                    encodedFrames = frames,
                    firstPtsUs = firstPtsUs,
                    lastPtsUs = lastPtsUs,
                    maxVideoPtsGapUs = maxVideoPtsGapUs,
                    videoPtsGapsOverThreshold = videoPtsGapsOverThreshold,
                    geometryMode = RecordingGeometryMode.NATIVE_RASTER,
                    deviceOrientationDegrees = geometry.deviceOrientationDegrees,
                    pixelRotationDegrees = 0,
                    rendererRotationDegrees = 0,
                    containerRotationDegrees = containerRot,
                    encodedWidth = geometry.sourceSize.width,
                    encodedHeight = geometry.sourceSize.height,
                    displayWidth = display.width,
                    displayHeight = display.height,
                    pixelAspectRatioWidth = geometry.pixelAspectRatioWidth,
                    pixelAspectRatioHeight = geometry.pixelAspectRatioHeight,
                    greyReference = OpenCineLogGreyReference.NATIVE,
                    sceneGain = 1.0f,
                    encoderVuiTransfer = encoderVuiTransfer,
                    containerVuiTransfer = containerVuiTransfer,
                )
            } else null

            onStopped(success && failure == null, evidence)
        }
    }

    companion object {
        private const val LOG_TAG = "Direct10BitRecorder"
        private val takeIds = AtomicLong()

        fun prepare(
            context: Context,
            output: ParcelFileDescriptor,
            geometry: RecordingGeometry,
            sourcePath: OpenCineLogSourcePath,
            targetFps: Int,
            bitrate: Int,
            cameraTimestampRealtime: Boolean,
            audio: Camera2EmbeddedAudioConfig?,
            separateAudioClock: CaptureEpochClock?,
            onEncodedProgress: ((EncodedRecordingProgress) -> Unit)?,
            onFirstRecordedFrame: ((Long) -> Unit)?,
            onRecordedFrame: ((Long, Long) -> Unit)?,
            onStopped: (Boolean, OpenCineLogRecordingEvidence?) -> Unit,
            sampleListener: EncodedSampleListener?,
            isCancelled: () -> Boolean,
        ): Direct10BitVideoRecorder {
            val encodedSize = Size(geometry.sourceSize.width, geometry.sourceSize.height)
            val candidate = OpenCineLogGpuPipeline.findEncoder(encodedSize, targetFps)
                ?: error("No hardware HEVC Main10 Surface encoder accepts ${encodedSize.width}x${encodedSize.height} at $targetFps fps.")
            val fileRetirement = OpenCineLogGpuPipeline.RecordingFileRetirement()
            val ownedOutput = ParcelFileDescriptor.dup(output.fileDescriptor)
            val calibration = audio?.let {
                OpenCineLogGpuPipeline.EmbeddedAac.calibrate(context.applicationContext, it, isCancelled)
            }
            val bitratePlan = OpenCineLogGpuPipeline.resolveBitratePlan(bitrate, targetFps, candidate.maxBitrateBps, null)
            val format = MediaFormat.createVideoFormat(candidate.mime, encodedSize.width, encodedSize.height).apply {
                setInteger(MediaFormat.KEY_PROFILE, candidate.profile)
                OpenCineLogGpuPipeline.resolveEffectiveLevel(candidate.mime, candidate.level, null)?.let {
                    setInteger(MediaFormat.KEY_LEVEL, it)
                }
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitratePlan.sentToCodecBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                if (targetFps >= 60) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    setInteger(MediaFormat.KEY_OPERATING_RATE, targetFps)
                }
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH, geometry.pixelAspectRatioWidth)
                    setInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT, geometry.pixelAspectRatioHeight)
                }
            }
            var codec: MediaCodec? = null
            var codecSurface: Surface? = null
            var muxer: MediaMuxer? = null
            var embeddedAac: OpenCineLogGpuPipeline.EmbeddedAac? = null
            var codecStarted = false
            return try {
                codec = MediaCodec.createByCodecName(candidate.codecName)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codecSurface = codec.createInputSurface()
                muxer = MediaMuxer(ownedOutput.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
                    setOrientationHint(geometry.contentRotationDegrees)
                }
                val captureEpoch = separateAudioClock ?: audio?.let { CaptureEpochClock(cameraTimestampRealtime, it.sampleRateHz, it.syncOffsetNs) }
                embeddedAac = audio?.let {
                    OpenCineLogGpuPipeline.EmbeddedAac.create(
                        context.applicationContext,
                        it,
                        requireNotNull(calibration),
                        requireNotNull(captureEpoch),
                        fileRetirement::noteFailure,
                        isCancelled,
                    )
                }
                codec.start()
                codecStarted = true
                Log.i(
                    LOG_TAG,
                    "Prepared Direct 10-bit HLG recorder: ${encodedSize.width}x${encodedSize.height}@${targetFps}fps " +
                        "codec=${candidate.codecName} bitrate=${bitratePlan.sentToCodecBps} bps",
                )
                Direct10BitVideoRecorder(
                    codecSurface = codecSurface,
                    codec = codec,
                    muxer = muxer,
                    outputDescriptor = ownedOutput,
                    candidate = candidate,
                    geometry = geometry,
                    sourcePath = sourcePath,
                    targetFps = targetFps,
                    audio = embeddedAac,
                    captureEpoch = captureEpoch,
                    onEncodedProgress = onEncodedProgress,
                    onFirstRecordedFrame = onFirstRecordedFrame,
                    onRecordedFrame = onRecordedFrame,
                    onStopped = onStopped,
                    fileRetirement = fileRetirement,
                    sampleListener = sampleListener,
                )
            } catch (failure: Throwable) {
                fileRetirement.retire { embeddedAac?.close() }
                fileRetirement.retire { muxer?.release() }
                fileRetirement.retire { codecSurface?.release() }
                if (codecStarted) fileRetirement.retire { codec?.stop() }
                fileRetirement.retire { codec?.release() }
                fileRetirement.retire { ownedOutput.close() }
                fileRetirement.finish()
                throw failure
            }
        }
    }
}
