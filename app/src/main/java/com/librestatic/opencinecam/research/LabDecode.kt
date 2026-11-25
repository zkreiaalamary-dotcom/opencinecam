/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import android.media.MediaMetadataRetriever
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal fun inspectDecodedFrames(clip: File, timesUs: List<Long>): JSONObject {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(clip.absolutePath)
        val samples = JSONArray()
        val scores = mutableListOf<LabHalfFrame>()
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
                    }
                } finally {
                    frame.recycle()
                }
            } catch (error: RuntimeException) {
                sample.put("failure", error.toString().take(300))
            }
        }
        (representative ?: json("status" to "UNVERIFIED")).put("geometryScreen", halfFrameScreen(scores))
            .put("geometrySamples", samples).put("scope", "Platform display-decoded RGB8. Chart and independent decoder needed to qualify geometry.")
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
