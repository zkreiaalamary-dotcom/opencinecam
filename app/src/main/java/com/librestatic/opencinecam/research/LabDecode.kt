/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

internal fun inspectDecodedFrames(
    clip: File,
    timesUs: List<Long>,
    syntheticQuadrants: Boolean = false,
): JSONObject {
    val coded = runCatching { decodeCodedLumaFrames(clip, timesUs, syntheticQuadrants) }.getOrNull()
    val retriever = MediaMetadataRetriever()
    val displayResult = try {
        retriever.setDataSource(clip.absolutePath)
        val samples = JSONArray()
        val scores = mutableListOf<LabHalfFrame>()
        val rgbFrames = mutableListOf<IntArray>()
        var frameW = 0
        var frameH = 0
        var representative: JSONObject? = null
        for (timeUs in timesUs.distinct()) {
            val sample = json("requestedTimestampUs" to timeUs)
            samples.put(sample)
            try {
                val frame = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, 192, 192)
                    ?: continue
                try {
                    val pixels = IntArray(frame.width * frame.height)
                    frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
                    if (frameW == 0) {
                        frameW = frame.width
                        frameH = frame.height
                    }
                    if (frame.width == frameW && frame.height == frameH) rgbFrames += pixels
                    val d = halfFrame(pixels, frame.width, frame.height)
                    scores += d
                    sample.put("halfX", d.horizontal.half).put("quarterX", d.horizontal.quarter)
                        .put("halfY", d.vertical.half).put("quarterY", d.vertical.quarter)
                        .put("width", frame.width).put("height", frame.height)
                    if (representative == null) {
                        val luma = pixels.map { p ->
                            (54 * ((p ushr 16) and 255) + 183 * ((p ushr 8) and 255) + 19 * (p and 255)) ushr 8
                        }
                        representative = json("status" to "DECODED", "requestedTimestampUs" to timeUs,
                            "width" to frame.width, "height" to frame.height, "rgb8LumaMin" to luma.minOrNull(),
                            "rgb8LumaMax" to luma.maxOrNull(), "rgb8LumaMean" to luma.average())
                        if (syntheticQuadrants) {
                            val quad = verifySyntheticQuadrants(pixels, frame.width, frame.height)
                            representative.put("syntheticLayout", json(
                                "verdict" to quad.verdict,
                                "q0RedMinusBlue" to quad.q0RedMinusBlue,
                                "q1RampDelta" to quad.q1RampDelta,
                                "q2GreenMinusRed" to quad.q2GreenMinusRed,
                            ))
                        }
                    }
                } finally {
                    frame.recycle()
                }
            } catch (error: RuntimeException) {
                sample.put("failure", error.toString().take(300))
            }
        }
        val spacedMads = rgbFrames.zipWithNext { a, b -> frameContentMad(a, b) }
        val base = (representative ?: json("status" to "UNVERIFIED"))
            .put("geometryScreen", halfFrameScreen(scores))
            .put("geometrySamples", samples)
            .put("displaySpacedFrameMads", JSONArray(spacedMads))
            .put("scope", "Platform display-decoded RGB8 plus coded-plane MediaCodec decode when available.")
        base
    } catch (error: Exception) {
        json("status" to "UNVERIFIED", "reason" to error.toString().take(300))
    } finally {
        runCatching { retriever.release() }
    }
    if (coded != null) {
        displayResult.put("codedDecode", coded)
        val codedContent = coded.optString("contentUniqueness")
        if (codedContent.isNotEmpty()) displayResult.put("contentUniqueness", codedContent)
        val codedScreen = coded.optString("geometryScreen")
        if (codedScreen.startsWith("REVIEW_")) {
            displayResult.put("codedGeometryAlert", codedScreen)
        }
    } else {
        val displayMads = mutableListOf<Double>()
        displayResult.optJSONArray("displaySpacedFrameMads")?.let { arr ->
            for (i in 0 until arr.length()) displayMads += arr.optDouble(i)
        }
        displayResult.put("contentUniqueness", contentUniquenessVerdict(emptyList(), displayMads))
    }
    return displayResult
}

private fun decodeCodedLumaFrames(
    clip: File,
    timesUs: List<Long>,
    measureRamp: Boolean,
): JSONObject? {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    try {
        extractor.setDataSource(clip.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: return null
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        extractor.selectTrack(track)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        val decoder = MediaCodec.createDecoderByType(mime)
        codec = decoder
        decoder.configure(format, null, null, 0)
        decoder.start()

        val sortedTargets = timesUs.distinct().sorted()
        val matchedTargets = BooleanArray(sortedTargets.size)
        val targetCount = sortedTargets.size.coerceAtLeast(1)
        // Also capture consecutive frames around frame index 10..11 for strict inter-frame diff.
        var totalDecoded = 0
        val codedSamples = JSONArray()
        val codedHalfScores = mutableListOf<LabHalfFrame>()
        val sampledLumaGreyFrames = mutableListOf<IntArray>()
        var consecFrameA: IntArray? = null
        var consecFrameB: IntArray? = null
        var rampMetrics: RampPrecisionMetrics? = null
        var reportedFormat = 0
        var codedW = 0
        var codedH = 0
        var pixelStride = 1
        var rowStride = 0

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val deadlineMs = android.os.SystemClock.elapsedRealtime() + 8_000L

        while (!outputDone && android.os.SystemClock.elapsedRealtime() < deadlineMs) {
            if (!inputDone) {
                val inIdx = decoder.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = decoder.getInputBuffer(inIdx)
                    val sampleSize = if (buf != null) extractor.readSampleData(buf, 0) else -1
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = decoder.dequeueOutputBuffer(info, 10_000)
            if (outIdx >= 0) {
                val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                if (info.size > 0) {
                    val frameIndex = totalDecoded++
                    val wantConsecutive = frameIndex == 10 || frameIndex == 11
                    val targetSlot = sortedTargets.indexOfFirst { targetUs ->
                        kotlin.math.abs(info.presentationTimeUs - targetUs) <= 25_000L
                    }
                    val wantTarget = targetSlot >= 0 && !matchedTargets[targetSlot]
                    if (wantTarget) matchedTargets[targetSlot] = true
                    if (wantConsecutive || wantTarget || (frameIndex == 5 && codedSamples.length() == 0)) {
                        runCatching { decoder.getOutputImage(outIdx) }.getOrNull()?.use { image ->
                            if (reportedFormat == 0) {
                                reportedFormat = image.format
                                codedW = image.width
                                codedH = image.height
                                pixelStride = image.planes[0].pixelStride
                                rowStride = image.planes[0].rowStride
                            }
                            val downsampled = downsampleCodedLuma(image, 192)
                            if (frameIndex == 10) consecFrameA = downsampled
                            if (frameIndex == 11) consecFrameB = downsampled
                            if (wantTarget || (frameIndex == 5 && codedSamples.length() == 0)) {
                                sampledLumaGreyFrames += downsampled
                                val sw = 192.coerceAtMost(image.width)
                                val sh = ((sw.toLong() * image.height) / image.width.coerceAtLeast(1)).toInt().coerceAtLeast(4)
                                if (downsampled.size == sw * sh) {
                                    val hf = halfFrame(downsampled, sw, sh)
                                    codedHalfScores += hf
                                    codedSamples.put(json(
                                        "ptsUs" to info.presentationTimeUs,
                                        "halfX" to hf.horizontal.half, "quarterX" to hf.horizontal.quarter,
                                        "halfY" to hf.vertical.half, "quarterY" to hf.vertical.quarter,
                                    ))
                                }
                                if (measureRamp && rampMetrics == null) {
                                    rampMetrics = extractTopRightRampMetrics(image)
                                }
                            }
                        }
                    }
                    if (sampledLumaGreyFrames.size >= targetCount && consecFrameA != null && consecFrameB != null) {
                        outputDone = true
                    }
                }
                decoder.releaseOutputBuffer(outIdx, false)
                if (isEos) outputDone = true
            }
        }
        if (reportedFormat == 0) return null
        val consecMads = if (consecFrameA != null && consecFrameB != null && consecFrameA!!.size == consecFrameB!!.size) {
            listOf(frameContentMad(consecFrameA!!, consecFrameB!!))
        } else emptyList()
        val spacedMads = sampledLumaGreyFrames.zipWithNext { a, b ->
            if (a.size == b.size) frameContentMad(a, b) else 0.0
        }
        return json(
            "status" to "DECODED",
            "codedWidth" to codedW,
            "codedHeight" to codedH,
            "imageFormat" to reportedFormat,
            "plane0PixelStride" to pixelStride,
            "plane0RowStride" to rowStride,
            "geometryScreen" to halfFrameScreen(codedHalfScores),
            "geometrySamples" to codedSamples,
            "consecutiveFrameMads" to JSONArray(consecMads),
            "spacedFrameMads" to JSONArray(spacedMads),
            "contentUniqueness" to contentUniquenessVerdict(consecMads, spacedMads),
            "rampPrecision" to rampMetrics?.let {
                json(
                    "declaredBitDepth" to it.declaredBitDepth,
                    "sampleCount" to it.sampleCount,
                    "distinctValues" to it.distinctValues,
                    "minPositiveStep" to it.minPositiveStep,
                    "mod4ZeroFraction" to it.mod4ZeroFraction,
                    "effectiveBitsEstimate" to it.effectiveBitsEstimate,
                )
            },
        )
    } finally {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor.release() }
    }
}

private fun downsampleCodedLuma(image: Image, targetMaxDim: Int): IntArray {
    val w = image.width
    val h = image.height
    val sw = targetMaxDim.coerceAtMost(w).coerceAtLeast(4)
    val sh = ((sw.toLong() * h) / w.coerceAtLeast(1)).toInt().coerceAtLeast(4)
    val plane = image.planes[0]
    val buf = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val is16Bit = pixelStride >= 2 || image.format == LabApiCompat.FORMAT_YCBCR_P010
    val out = IntArray(sw * sh)
    for (sy in 0 until sh) {
        val y = (sy * h) / sh
        for (sx in 0 until sw) {
            val x = (sx * w) / sw
            val offset = y * rowStride + x * pixelStride
            val luma8 = if (is16Bit && offset + 1 < buf.limit()) {
                val raw = buf.getShort(offset).toInt() and 0xFFFF
                // P010 puts 10-bit code in top 10 bits; 10-bit LSB puts code in bottom 10 bits.
                if (raw > 1023) (raw ushr 8) and 255 else ((raw * 255.0) / 1023.0).roundToInt().coerceIn(0, 255)
            } else if (offset < buf.limit()) {
                buf.get(offset).toInt() and 0xFF
            } else 0
            out[sy * sw + sx] = (luma8 shl 16) or (luma8 shl 8) or luma8
        }
    }
    return out
}

private fun extractTopRightRampMetrics(image: Image): RampPrecisionMetrics {
    val plane = image.planes[0]
    val buf = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val is16Bit = pixelStride >= 2 || image.format == LabApiCompat.FORMAT_YCBCR_P010
    val yRow = (image.height / 4).coerceIn(0, image.height - 1)
    val xStart = image.width / 2 + 8
    val xEnd = (image.width - 8).coerceAtLeast(xStart + 1)
    val values = IntArray(xEnd - xStart)
    var maxSeen = 0
    for (i in values.indices) {
        val x = xStart + i
        val off = yRow * rowStride + x * pixelStride
        val v = if (is16Bit && off + 1 < buf.limit()) {
            buf.getShort(off).toInt() and 0xFFFF
        } else if (off < buf.limit()) {
            buf.get(off).toInt() and 0xFF
        } else 0
        if (v > maxSeen) maxSeen = v
        values[i] = v
    }
    val declared = if (is16Bit) 10 else 8
    val normalized = if (is16Bit && maxSeen > 1023) IntArray(values.size) { values[it] ushr 6 } else values
    return analyzeRampPrecision(normalized, declared)
}

internal fun clipSha256(file: File): String {
    val hash = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val bytes = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(bytes)
            if (n < 0) break
            hash.update(bytes, 0, n)
        }
    }
    return hash.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
}
