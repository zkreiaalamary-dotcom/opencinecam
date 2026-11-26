/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Build
import android.view.Surface
import com.librestatic.opencinecam.camera.GpuEglDisplayLease
import com.librestatic.opencinecam.camera.HevcSpsInspector
import com.librestatic.opencinecam.camera.HevcVuiTransfer
import com.librestatic.opencinecam.media.video.HevcEncoderLevels
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import org.json.JSONArray
import org.json.JSONObject

internal data class LabSyntheticArm(
    val id: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val profile: Int,
    val level: Int?,
    val bitrateBps: Int,
    val colorStandard: Int,
    val colorRange: Int,
    val colorTransfer: Int?,
    val egl10Bit: Boolean,
    val eglColorSpace: Int? = null,
    val applyOcLog2Shader: Boolean = false,
    val rewriteVuiUnspecified: Boolean = false,
    val frameCount: Int = 30,
)

/**
 * Deterministic camera-closed EGL frame producer and HEVC Main/Main10 isolation matrix.
 * Tests explicit level admission, color/range keys, EGL 8-bit vs 10-bit surface import,
 * 4-quadrant coded geometry, frame-to-frame pixel uniqueness, and 10-bit ramp precision.
 */
internal object LabSyntheticEncoderProbe {
    private const val EGL_GL_COLORSPACE_KHR = 0x309D
    private const val EGL_GL_COLORSPACE_BT2020_LINEAR_EXT = 0x333F

    fun run(attempt: LabJournal.Attempt, checkBudget: () -> Unit): Pair<LabVerdict, String?> {
        val hevcEncoder = findBestHardwareHevcEncoder()
            ?: return LabVerdict.FAILED to "No hardware HEVC Surface encoder advertised."
        val advertisedLevels = hevcEncoder.second.filter {
            it.profile == CodecProfileLevel.HEVCProfileMain || it.profile == CodecProfileLevel.HEVCProfileMain10
        }.map { it.level }.toSet()
        val level4k30 = HevcEncoderLevels.selectHevcLevel(3840, 2160, 30, advertisedLevels)
            ?: CodecProfileLevel.HEVCMainTierLevel51
        val level4k60 = HevcEncoderLevels.selectHevcLevel(3840, 2160, 60, advertisedLevels)
            ?: CodecProfileLevel.HEVCMainTierLevel52

        val arms = mutableListOf(
            LabSyntheticArm("main8_no_level_4k30", 3840, 2160, 30, CodecProfileLevel.HEVCProfileMain, null, 20_000_000, MediaFormat.COLOR_STANDARD_BT709, MediaFormat.COLOR_RANGE_LIMITED, MediaFormat.COLOR_TRANSFER_SDR_VIDEO, egl10Bit = false, frameCount = 15),
            LabSyntheticArm("main8_l51_4k30_egl8", 3840, 2160, 30, CodecProfileLevel.HEVCProfileMain, level4k30, 20_000_000, MediaFormat.COLOR_STANDARD_BT709, MediaFormat.COLOR_RANGE_LIMITED, MediaFormat.COLOR_TRANSFER_SDR_VIDEO, egl10Bit = false, frameCount = 30),
            LabSyntheticArm("main10_no_level_4k30_egl10", 3840, 2160, 30, CodecProfileLevel.HEVCProfileMain10, null, 40_000_000, MediaFormat.COLOR_STANDARD_BT709, MediaFormat.COLOR_RANGE_LIMITED, MediaFormat.COLOR_TRANSFER_SDR_VIDEO, egl10Bit = true, frameCount = 15),
            LabSyntheticArm("main10_l51_bt709_lim_4k30_egl10", 3840, 2160, 30, CodecProfileLevel.HEVCProfileMain10, level4k30, 40_000_000, MediaFormat.COLOR_STANDARD_BT709, MediaFormat.COLOR_RANGE_LIMITED, MediaFormat.COLOR_TRANSFER_SDR_VIDEO, egl10Bit = true, frameCount = 30),
            LabSyntheticArm("main10_l51_oclog2_full_4k30_egl10_linear", 3840, 2160, 30, CodecProfileLevel.HEVCProfileMain10, level4k30, 40_000_000, MediaFormat.COLOR_STANDARD_BT2020, MediaFormat.COLOR_RANGE_FULL, null, egl10Bit = true, eglColorSpace = EGL_GL_COLORSPACE_BT2020_LINEAR_EXT, applyOcLog2Shader = true, rewriteVuiUnspecified = true, frameCount = 30),
            LabSyntheticArm("main10_l51_oclog2_full_4k30_egl10_default", 3840, 2160, 30, CodecProfileLevel.HEVCProfileMain10, level4k30, 40_000_000, MediaFormat.COLOR_STANDARD_BT2020, MediaFormat.COLOR_RANGE_FULL, null, egl10Bit = true, eglColorSpace = null, applyOcLog2Shader = true, rewriteVuiUnspecified = true, frameCount = 30),
            LabSyntheticArm("main10_l51_oclog2_lim_4k30_egl10_default", 3840, 2160, 30, CodecProfileLevel.HEVCProfileMain10, level4k30, 40_000_000, MediaFormat.COLOR_STANDARD_BT2020, MediaFormat.COLOR_RANGE_LIMITED, null, egl10Bit = true, eglColorSpace = null, applyOcLog2Shader = true, rewriteVuiUnspecified = true, frameCount = 30),
            LabSyntheticArm("main10_l51_hlg_lim_4k30_egl10", 3840, 2160, 30, CodecProfileLevel.HEVCProfileMain10, level4k30, 40_000_000, MediaFormat.COLOR_STANDARD_BT2020, MediaFormat.COLOR_RANGE_LIMITED, MediaFormat.COLOR_TRANSFER_HLG, egl10Bit = true, eglColorSpace = null, applyOcLog2Shader = true, rewriteVuiUnspecified = true, frameCount = 30),
            LabSyntheticArm("main10_l51_bt709_lim_4k30_egl8", 3840, 2160, 30, CodecProfileLevel.HEVCProfileMain10, level4k30, 40_000_000, MediaFormat.COLOR_STANDARD_BT709, MediaFormat.COLOR_RANGE_LIMITED, MediaFormat.COLOR_TRANSFER_SDR_VIDEO, egl10Bit = false, frameCount = 30),
        )

        val armResults = mutableListOf<JSONObject>()
        val scratchFiles = mutableListOf<Pair<String, File>>()
        var bestMain10Arm4k30: LabSyntheticArm? = null
        var bestMain8Arm4k30: LabSyntheticArm? = null
        var bestClipToKeep: File? = null
        var firstDistortedMain10Id: String? = null
        var main10GeometryClean: Boolean? = null
        var main10RampBits: Int? = null

        fun runArmAndRecord(arm: LabSyntheticArm): JSONObject {
            checkBudget()
            val outFile = File(attempt.clip.parentFile, "synthetic_${arm.id}.mp4")
            val result = executeArm(hevcEncoder.first, arm, outFile)
            armResults += result
            attempt.event(LabStage.RECORDER, result)
            if (outFile.isFile && outFile.length() > 0L) scratchFiles += arm.id to outFile
            return result
        }

        try {
            for (arm in arms) {
                val res = runArmAndRecord(arm)
                val ok = res.optBoolean("encodedOk", false)
                val layout = res.optString("syntheticLayoutVerdict")
                if (ok && arm.profile == CodecProfileLevel.HEVCProfileMain && bestMain8Arm4k30 == null) {
                    bestMain8Arm4k30 = arm
                }
                if (ok && arm.profile == CodecProfileLevel.HEVCProfileMain10) {
                    if (bestMain10Arm4k30 == null || (layout == "PASS" && arm.applyOcLog2Shader)) {
                        bestMain10Arm4k30 = arm
                    }
                    if (layout == "PASS") main10GeometryClean = true
                    else if (layout.startsWith("REPEAT_HALF")) {
                        if (main10GeometryClean == null) main10GeometryClean = false
                        if (firstDistortedMain10Id == null) firstDistortedMain10Id = arm.id
                    }
                    val bits = res.optInt("rampEffectiveBits", 0)
                    if (bits > (main10RampBits ?: 0)) main10RampBits = bits
                }
            }

            // Automatically test 4K60 Main10 and Main8 using the winning 4K30 recipes.
            val template4k60Main10 = (bestMain10Arm4k30 ?: arms[4]).copy(
                id = "main10_4k60_best",
                fps = 60,
                level = level4k60,
                bitrateBps = 60_000_000,
                frameCount = 30,
            )
            val res4k60Main10 = runArmAndRecord(template4k60Main10)
            if (!res4k60Main10.optBoolean("encodedOk", false) && level4k60 != CodecProfileLevel.HEVCMainTierLevel52) {
                runArmAndRecord(template4k60Main10.copy(id = "main10_l52_4k60_fallback", level = CodecProfileLevel.HEVCMainTierLevel52))
            }
            val main84k60 = LabSyntheticArm(
                id = "main8_l51_4k60_egl8",
                width = 3840, height = 2160, fps = 60,
                profile = CodecProfileLevel.HEVCProfileMain,
                level = level4k60,
                bitrateBps = 60_000_000,
                colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                colorRange = MediaFormat.COLOR_RANGE_LIMITED,
                colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                egl10Bit = false,
                frameCount = 30,
            )
            runArmAndRecord(main84k60)

            // Keep the most informative Main10 (or Main8) synthetic clip as attempt.clip, remove redundant scratch clips.
            val preferredId = bestMain10Arm4k30?.id ?: bestMain8Arm4k30?.id
            bestClipToKeep = scratchFiles.firstOrNull { it.first == preferredId }?.second
                ?: scratchFiles.lastOrNull()?.second
            if (bestClipToKeep != null && bestClipToKeep.isFile) {
                bestClipToKeep.copyTo(attempt.clip, overwrite = true)
            }
            firstDistortedMain10Id?.let { distortedId ->
                scratchFiles.firstOrNull { it.first == distortedId }?.second?.takeIf { it.isFile }?.copyTo(
                    File(attempt.clip.parentFile, "synthetic_distorted_main10.mp4"),
                    overwrite = true,
                )
            }
        } finally {
            scratchFiles.forEach { (_, f) -> runCatching { f.delete() } }
        }

        val passingMain10 = armResults.filter {
            it.optBoolean("encodedOk", false) && it.optInt("profile") == CodecProfileLevel.HEVCProfileMain10
        }
        val passingMain8 = armResults.filter {
            it.optBoolean("encodedOk", false) && it.optInt("profile") == CodecProfileLevel.HEVCProfileMain
        }
        val summary = json(
            "event" to "synthetic_matrix_summary",
            "codec" to hevcEncoder.first,
            "totalArms" to armResults.size,
            "main8Passed" to passingMain8.size,
            "main10Passed" to passingMain10.size,
            "bestMain8Recipe" to passingMain8.firstOrNull()?.optString("armId"),
            "bestMain10Recipe4k30" to bestMain10Arm4k30?.id,
            "bestMain10Recipe4k60" to armResults.firstOrNull {
                it.optBoolean("encodedOk", false) && it.optInt("fps") == 60 && it.optInt("profile") == CodecProfileLevel.HEVCProfileMain10
            }?.optString("armId"),
            "main10GeometryClean" to main10GeometryClean,
            "main10RampBits" to main10RampBits,
            "arms" to JSONArray(armResults.map {
                json(
                    "armId" to it.optString("armId"),
                    "encodedOk" to it.optBoolean("encodedOk"),
                    "step" to it.optString("step"),
                    "layout" to it.optString("syntheticLayoutVerdict"),
                    "content" to it.optString("contentUniqueness"),
                    "rampBits" to it.opt("rampEffectiveBits"),
                )
            }),
        )
        attempt.event(LabStage.FILE, summary)

        return when {
            passingMain10.isNotEmpty() && main10GeometryClean == false ->
                LabVerdict.REVIEW to "Synthetic Main10 encoded but exhibited repeated-half geometry."
            passingMain10.isNotEmpty() ->
                LabVerdict.OBSERVED to "Synthetic Main10 & Main8 matrix completed (${passingMain10.size} Main10 / ${passingMain8.size} Main8 passed)."
            passingMain8.isNotEmpty() ->
                LabVerdict.MISMATCH to "Synthetic Main8 passed (${passingMain8.size}) but all Main10 arms failed."
            else ->
                LabVerdict.FAILED to "All synthetic HEVC arms failed."
        }
    }

    private fun executeArm(codecName: String, arm: LabSyntheticArm, outputFile: File): JSONObject {
        val result = json(
            "event" to "synthetic_encoder_arm",
            "armId" to arm.id,
            "codec" to codecName,
            "width" to arm.width,
            "height" to arm.height,
            "fps" to arm.fps,
            "profile" to arm.profile,
            "level" to arm.level,
            "levelName" to HevcEncoderLevels.hevcLevelName(arm.level),
            "bitrateBps" to arm.bitrateBps,
            "colorStandard" to arm.colorStandard,
            "colorRange" to arm.colorRange,
            "colorTransfer" to arm.colorTransfer,
            "egl10Bit" to arm.egl10Bit,
            "eglColorSpace" to arm.eglColorSpace,
            "applyOcLog2Shader" to arm.applyOcLog2Shader,
        )
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, arm.width, arm.height).apply {
            setInteger(MediaFormat.KEY_PROFILE, arm.profile)
            arm.level?.let { setInteger(MediaFormat.KEY_LEVEL, it) }
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, arm.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, arm.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, arm.colorStandard)
            setInteger(MediaFormat.KEY_COLOR_RANGE, arm.colorRange)
            arm.colorTransfer?.let { setInteger(MediaFormat.KEY_COLOR_TRANSFER, it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH, 1)
                setInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT, 1)
            }
        }
        result.put("requestedFormat", format.toString())

        var codec: MediaCodec? = null
        var surface: Surface? = null
        var muxer: MediaMuxer? = null
        var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var displayAcquired = false
        var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var step = "create"
        var started = false
        var muxerStarted = false
        var encodedSamples = 0
        var csd0Bytes: ByteArray? = null
        var encoderVuiPrev: Int? = null
        var containerVuiAfter: Int? = null

        try {
            val encoder = MediaCodec.createByCodecName(codecName)
            codec = encoder
            step = "configure"
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            step = "input_surface"
            surface = encoder.createInputSurface()

            step = "egl_init"
            eglDisplay = GpuEglDisplayLease.acquire()
            displayAcquired = true
            val eglConfig = if (arm.egl10Bit) chooseConfig(eglDisplay, 10, 10, 10, 2) else chooseConfig(eglDisplay, 8, 8, 8, 8)
            check(eglConfig != null) { "EGLConfig (${if (arm.egl10Bit) "10-bit" else "8-bit"}) unavailable" }
            eglContext = EGL14.eglCreateContext(
                eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
            )
            check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
            val surfAttrs = arm.eglColorSpace?.let { intArrayOf(EGL_GL_COLORSPACE_KHR, it, EGL14.EGL_NONE) }
                ?: intArrayOf(EGL14.EGL_NONE)
            step = "egl_window"
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfAttrs, 0)
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed (0x${EGL14.eglGetError().toString(16)})" }
            check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }

            step = "muxer_create"
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            step = "start"
            encoder.start()
            started = true

            step = "render_and_drain"
            val program = linkProgram(SYNTHETIC_VERTEX_SHADER, SYNTHETIC_FRAGMENT_SHADER)
            val quadBuf = createQuadBuffer()
            var trackIndex = -1
            var drainFailure: Throwable? = null
            val drainThread = Thread({
                val info = MediaCodec.BufferInfo()
                var eos = false
                try {
                    while (!eos) {
                        val idx = encoder.dequeueOutputBuffer(info, 20_000)
                        when {
                            idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val outFmt = encoder.outputFormat
                                if (arm.rewriteVuiUnspecified) {
                                    outFmt.getByteBuffer("csd-0")?.duplicate()?.let { buf ->
                                        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                                        val rw = runCatching { HevcVuiTransfer.rewrite(bytes, HevcVuiTransfer.UNSPECIFIED) }.getOrNull()
                                        if (rw != null) {
                                            encoderVuiPrev = rw.previousTransfer
                                            containerVuiAfter = HevcVuiTransfer.transferOf(rw.bytes)
                                            outFmt.setByteBuffer("csd-0", ByteBuffer.wrap(rw.bytes))
                                            outFmt.removeKey(MediaFormat.KEY_COLOR_TRANSFER)
                                        }
                                    }
                                }
                                csd0Bytes = outFmt.getByteBuffer("csd-0")?.duplicate()?.let { b ->
                                    ByteArray(b.remaining()).also { b.get(it) }
                                }
                                trackIndex = muxer.addTrack(outFmt)
                                muxer.start()
                                muxerStarted = true
                            }
                            idx >= 0 -> {
                                val outBuf = encoder.getOutputBuffer(idx)
                                val isCfg = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                if (info.size > 0 && outBuf != null && !isCfg && muxerStarted) {
                                    outBuf.position(info.offset)
                                    outBuf.limit(info.offset + info.size)
                                    if (arm.rewriteVuiUnspecified && (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                                        val raw = ByteArray(info.size).also { outBuf.get(it) }
                                        val rw = runCatching { HevcVuiTransfer.rewrite(raw, HevcVuiTransfer.UNSPECIFIED).bytes }.getOrDefault(raw)
                                        val ni = MediaCodec.BufferInfo().apply { set(0, rw.size, info.presentationTimeUs, info.flags) }
                                        muxer.writeSampleData(trackIndex, ByteBuffer.wrap(rw), ni)
                                    } else {
                                        muxer.writeSampleData(trackIndex, outBuf, info)
                                    }
                                    encodedSamples++
                                }
                                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true
                                encoder.releaseOutputBuffer(idx, false)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    drainFailure = t
                }
            }, "occ-synth-drain").apply { start() }

            val frameStepNs = 1_000_000_000L / arm.fps
            val uFrameLoc = GLES30.glGetUniformLocation(program, "uFrameIndex")
            val uLogLoc = GLES30.glGetUniformLocation(program, "uApplyOcLog2")
            GLES30.glViewport(0, 0, arm.width, arm.height)
            GLES30.glUseProgram(program)
            GLES30.glUniform1i(uLogLoc, if (arm.applyOcLog2Shader) 1 else 0)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quadBuf)
            for (i in 0 until arm.frameCount) {
                GLES30.glUniform1f(uFrameLoc, i.toFloat())
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, i * frameStepNs)
                check(EGL14.eglSwapBuffers(eglDisplay, eglSurface)) { "eglSwapBuffers failed at frame $i" }
            }
            GLES30.glDisableVertexAttribArray(0)
            GLES30.glDeleteProgram(program)
            encoder.signalEndOfInputStream()
            drainThread.join(10_000)
            check(!drainThread.isAlive) { "Encoder drain thread timed out" }
            drainFailure?.let { throw it }

            step = "muxer_stop"
            if (muxerStarted) muxer.stop()
            muxerStarted = false

            step = "inspect"
            val sps = csd0Bytes?.let { HevcSpsInspector.inspect(it) }
            val decode = inspectDecodedFrames(
                outputFile,
                listOf(arm.frameCount / 4, arm.frameCount / 2, (3 * arm.frameCount) / 4).map { it * 1_000_000L / arm.fps },
                syntheticQuadrants = true,
            )
            result.put("encodedOk", encodedSamples >= 2)
            result.put("step", "done")
            result.put("encodedSamples", encodedSamples)
            result.put("clipBytes", outputFile.length())
            result.put("encoderVuiTransferPrev", encoderVuiPrev)
            result.put("containerVuiTransferAfter", containerVuiAfter)
            result.put("sps", sps?.let {
                json(
                    "profileIdc" to it.profileIdc, "levelIdc" to it.levelIdc,
                    "codedWidth" to it.codedWidth, "codedHeight" to it.codedHeight,
                    "bitDepthLuma" to it.bitDepthLuma, "bitDepthChroma" to it.bitDepthChroma,
                    "fullRange" to it.fullRange, "primaries" to it.colourPrimaries,
                    "transfer" to it.transferCharacteristics, "matrix" to it.matrixCoeffs,
                )
            })
            val coded = decode.optJSONObject("codedDecode")
            val layoutVerdict = decode.optJSONObject("syntheticLayout")?.optString("verdict")
                ?: decode.optString("geometryScreen")
            result.put("syntheticLayoutVerdict", layoutVerdict)
            result.put("geometryScreen", decode.optString("geometryScreen"))
            result.put("codedGeometryScreen", coded?.optString("geometryScreen"))
            result.put("contentUniqueness", decode.optString("contentUniqueness"))
            result.put("rampEffectiveBits", coded?.optJSONObject("rampPrecision")?.opt("effectiveBitsEstimate"))
            result.put("decoder", decode)
        } catch (error: Throwable) {
            result.put("encodedOk", false)
            result.put("step", step)
            result.put("failure", error.toCodecFailure())
        } finally {
            runCatching {
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                    if (displayAcquired) GpuEglDisplayLease.release(eglDisplay)
                }
            }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { surface?.release() }
            if (started) runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
        return result
    }

    private fun findBestHardwareHevcEncoder(): Pair<String, List<CodecProfileLevel>>? {
        val infos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { it.isEncoder && !it.isAlias && it.isHardwareAccelerated }
            .sortedBy { it.name }
        for (info in infos) {
            val type = info.supportedTypes.firstOrNull { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) } ?: continue
            val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull() ?: continue
            if (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface !in caps.colorFormats) continue
            return info.name to caps.profileLevels.toList()
        }
        return null
    }

    private fun chooseConfig(display: EGLDisplay, r: Int, g: Int, b: Int, a: Int): EGLConfig? {
        val configs = arrayOfNulls<EGLConfig>(8)
        val count = IntArray(1)
        val ok = EGL14.eglChooseConfig(
            display,
            intArrayOf(
                EGL14.EGL_RED_SIZE, r, EGL14.EGL_GREEN_SIZE, g, EGL14.EGL_BLUE_SIZE, b, EGL14.EGL_ALPHA_SIZE, a,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE,
            ),
            0, configs, 0, configs.size, count, 0,
        )
        return if (ok && count[0] > 0) configs[0] else null
    }

    private fun createQuadBuffer(): FloatBuffer =
        ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

    private fun linkProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val status = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES30.GL_TRUE) { GLES30.glGetShaderInfoLog(s) }
            return s
        }
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
        val status = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) { GLES30.glGetProgramInfoLog(p) }
        return p
    }

    private const val SYNTHETIC_VERTEX_SHADER = """
        #version 300 es
        layout(location = 0) in vec2 aPos;
        out vec2 vUv;
        void main() {
            gl_Position = vec4(aPos, 0.0, 1.0);
            vUv = (aPos + 1.0) * 0.5;
        }
    """

    // Generates a 4-quadrant asymmetric pattern with a smooth ramp (Q1 top-right) and
    // per-frame moving bar + binary frame stripes (Q3 bottom-right).
    private const val SYNTHETIC_FRAGMENT_SHADER = """
        #version 300 es
        precision highp float;
        uniform float uFrameIndex;
        uniform int uApplyOcLog2;
        in vec2 vUv;
        out vec4 outColor;
        // Matches com.librestatic.opencinecam.camera.OpenCineLog2Curve (BLACK_CODE=0.10, WHITE_CODE=0.90, BASE=50.0).
        vec3 encodeOcLog2(vec3 x) {
            return vec3(0.10) + vec3(0.80) * log(vec3(1.0) + 50.0 * clamp(x, 0.0, 1.0)) / log(vec3(51.0));
        }
        void main() {
            // Note: GL UV y=1 is top of image; top-left is vUv.x < 0.5 && vUv.y >= 0.5.
            vec3 rgb = vec3(0.0);
            if (vUv.x < 0.5 && vUv.y >= 0.5) {
                // Q0 top-left: odd-frequency red/amber checkerboard + L-fiducial so quarter shifts stay textured
                float cx = floor(vUv.x * 23.0);
                float cy = floor(vUv.y * 23.0);
                float chk = mod(cx + cy, 2.0);
                rgb = chk > 0.5 ? vec3(0.90, 0.16, 0.06) : vec3(0.28, 0.06, 0.04);
                if (vUv.x < 0.08 && vUv.y > 0.72) rgb = vec3(0.95, 0.90, 0.20);
            } else if (vUv.x >= 0.5 && vUv.y >= 0.5) {
                // Q1 top-right: smooth horizontal ramp 0..1 for 10-bit quantization testing
                float t = clamp((vUv.x - 0.5) * 2.0, 0.0, 1.0);
                rgb = vec3(t);
            } else if (vUv.x < 0.5 && vUv.y < 0.5) {
                // Q2 bottom-left: odd-frequency green/cyan blocks + shadow steps
                float bx = floor(vUv.x * 13.0);
                float by = floor(vUv.y * 11.0);
                rgb = mod(bx + by, 2.0) > 0.5 ? vec3(0.08, 0.85, 0.30) : vec3(0.04, 0.32, 0.72);
                if (vUv.y < 0.12) rgb = vec3(0.02, 0.15, 0.08);
            } else {
                // Q3 bottom-right: moving vertical bar + frame-index stripe
                float lx = (vUv.x - 0.5) * 2.0;
                float barPos = fract(uFrameIndex / 30.0);
                float dist = abs(lx - barPos);
                rgb = dist < 0.06 ? vec3(0.95, 0.95, 0.95) : vec3(0.12, 0.14, 0.25);
                float band = floor(vUv.y * 16.0);
                if (band < 4.0) {
                    float bit = mod(floor(uFrameIndex / pow(2.0, band)), 2.0);
                    rgb = mix(rgb, vec3(0.9, 0.4, 0.9), bit * 0.7);
                }
            }
            // Asymmetric diagonal line from top-left to bottom-right
            if (abs((1.0 - vUv.y) - vUv.x) < 0.012) {
                rgb = vec3(0.92, 0.25, 0.85);
            }
            outColor = vec4(uApplyOcLog2 == 1 ? encodeOcLog2(rgb) : rgb, 1.0);
        }
    """
}
