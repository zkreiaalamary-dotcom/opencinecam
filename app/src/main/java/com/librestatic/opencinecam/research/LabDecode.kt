/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import android.media.MediaMetadataRetriever
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** A small decoded picture for smoke checking. Android may tone-map or convert it to eight-bit RGB. */
internal fun inspectDecodedFrame(clip: File, timestampUs: Long): JSONObject {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(clip.absolutePath)
        val frame = retriever.getScaledFrameAtTime(timestampUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 96, 54)
            ?: error("Android returned no decoded frame")
        try {
            val pixels = IntArray(frame.width * frame.height)
            frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
            var minimum = 255
            var maximum = 0
            var sum = 0L
            for (pixel in pixels) {
                val red = (pixel shr 16) and 255
                val green = (pixel shr 8) and 255
                val blue = pixel and 255
                val luma = (54 * red + 183 * green + 19 * blue) shr 8
                minimum = minOf(minimum, luma)
                maximum = maxOf(maximum, luma)
                sum += luma
            }
            json("status" to "DECODED", "requestedTimestampUs" to timestampUs, "width" to frame.width,
                "height" to frame.height, "rgb8LumaMin" to minimum, "rgb8LumaMax" to maximum,
                "rgb8LumaMean" to sum.toDouble() / pixels.size, "scope" to "Platform-decoded preview; display transform may apply.")
        } finally {
            frame.recycle()
        }
    } catch (error: Exception) {
        json("status" to "UNVERIFIED", "reason" to error.toString().take(300))
    } finally {
        runCatching { retriever.release() }
    }
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
    return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}
