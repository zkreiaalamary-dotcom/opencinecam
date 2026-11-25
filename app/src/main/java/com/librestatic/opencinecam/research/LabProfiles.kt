/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.MediaRecorder
import android.os.Build
import android.util.Size
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.Camera2LogProfile
import com.librestatic.opencinecam.camera.Camera2VideoProfile
import com.librestatic.opencinecam.camera.OpenCineLogSourcePath
import org.json.JSONArray

internal class LabProfiles(manager: CameraManager, val descriptor: Camera2CameraDescriptor) {
    private val id = descriptor.cameraId
    private val caps = manager.getCameraCharacteristics(id)
    private val map = requireNotNull(caps.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
    private val textureSizes = map.getOutputSizes(SurfaceTexture::class.java).orEmpty().toSet()
    private val recorderSizes = map.getOutputSizes(MediaRecorder::class.java).orEmpty().toSet()
    private val privateSizes = map.getOutputSizes(ImageFormat.PRIVATE).orEmpty().toSet()
    private val rawSizes = runCatching { map.getOutputSizes(ImageFormat.RAW10).orEmpty().toList() }.getOrDefault(emptyList())
    private val hlgAdvertised = Build.VERSION.SDK_INT >= 33 &&
        caps.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles
            ?.contains(DynamicRangeProfiles.HLG10) == true

    fun cases(): List<LabCase> {
        val controls = listOf(Size(1920, 1080), Size(3840, 2160))
        val regular = controls.flatMap { size ->
            listOf(30, 60, 24, 25).flatMap { fps -> regular(size, fps) }
        }
        val hfr = descriptor.logProfiles.filter { it.constrainedHighSpeed && it.fps <= 120 && it.size.width <= 1920 }
            .map { LabCase(id, LabPath.LOG, it.size.width, it.size.height, it.fps, hfr = true) }
        val raw = rawSizes.filter { it.width % 4 == 0 }.maxByOrNull { it.width.toLong() * it.height }
            ?.let { listOf(LabCase(id, LabPath.RAW_CAMERA, it.width, it.height, 30, listed = false)) }.orEmpty()
        // Interleave independent controls before visiting all raster/rate combinations.
        val seeds = listOfNotNull(
            regular.firstOrNull { it.path == LabPath.AVC && it.width == 1920 && it.fps == 30 },
            regular.firstOrNull { it.path == LabPath.LOG && it.width == 3840 && it.fps == 30 },
            hfr.firstOrNull(),
            raw.firstOrNull(),
            regular.firstOrNull { it.path == LabPath.HEVC && it.width == 3840 && it.fps == 30 },
        )
        val all = (seeds + regular + hfr + raw).distinctBy(LabCase::key)
        val probes = buildList {
            val fixed60 = descriptor.targetFpsRanges.any { it.lower == 60 && it.upper == 60 }
            if (Size(3840, 2160) in recorderSizes && fixed60) {
                val direct = LabCase(id, LabPath.DIRECT_HEVC, 3840, 2160, 60, listed = false)
                add(direct)
                if (Size(1280, 720) in privateSizes) add(direct.copy(path = LabPath.DIRECT_HEVC_PREVIEW))
            }
            add(LabCase(id, LabPath.CODEC_LEVEL_PAIR, 3840, 2160, 30, listed = false))
        }
        return probes + all + all.flatMap {
            if (it.path == LabPath.RAW_CAMERA) listOf(it.copy(path = LabPath.RAW_NATIVE))
            else listOf(it.copy(previewOnly = true), it.copy(longer = true))
        }
    }

    private fun regular(size: Size, fps: Int): List<LabCase> {
        val video = descriptor.videoProfiles.any { !it.constrainedHighSpeed && it.size == size && it.fps == fps }
        // Unlisted probes still use a public SurfaceTexture output and a compatible AE range.
        val requestable = size in textureSizes && descriptor.targetFpsRanges.any { fps in it.lower..it.upper }
        val output = mutableListOf<LabCase>()
        if (video || requestable) {
            output += LabCase(id, LabPath.AVC, size.width, size.height, fps, listed = video)
            output += LabCase(id, LabPath.HEVC, size.width, size.height, fps, listed = video)
        }
        val log = descriptor.logProfiles.firstOrNull { !it.constrainedHighSpeed && it.size == size && it.fps == fps }
        if (log != null || (requestable && hlgAdvertised)) {
            output += LabCase(id, LabPath.LOG, size.width, size.height, fps, listed = log != null)
            output += LabCase(id, LabPath.HLG, size.width, size.height, fps, listed = log != null)
        }
        if (log?.hdr10 == true) output += LabCase(id, LabPath.HDR10, size.width, size.height, fps)
        return output
    }

    fun logProfile(attempt: LabCase): Camera2LogProfile? {
        if (attempt.path !in setOf(LabPath.LOG, LabPath.HLG, LabPath.HDR10)) return null
        val size = Size(attempt.width, attempt.height)
        return descriptor.logProfiles.firstOrNull {
            it.size == size && it.fps == attempt.fps && it.constrainedHighSpeed == attempt.hfr
        } ?: Camera2LogProfile(size, attempt.fps, OpenCineLogSourcePath.HLG10_BT2020, false)
    }

    fun videoProfile(attempt: LabCase) = Camera2VideoProfile(Size(attempt.width, attempt.height), attempt.fps, false)

    fun inventory(cameras: List<Camera2CameraDescriptor>) = json(
        "selectedCamera" to id,
        "hlg10Advertised" to hlgAdvertised,
        "mediaRecorderSizes" to JSONArray(recorderSizes.map { it.toString() }.sorted()),
        "privateOutputSizes" to JSONArray(privateSizes.map { it.toString() }.sorted()),
        "raw10Sizes" to JSONArray(rawSizes.map { it.toString() }),
        "surfaceTextureSizes" to JSONArray(textureSizes.map { it.toString() }.sorted()),
        "outputFormats" to JSONArray(map.outputFormats.toList()),
        "cameras" to JSONArray(cameras.map { camera ->
            json("id" to camera.cameraId, "facing" to camera.lensFacing,
                "preview" to camera.previewSize.toString(), "sensorOrientation" to camera.sensorOrientation,
                "aeRanges" to JSONArray(camera.targetFpsRanges.map { it.toString() }),
                "fixedFps" to JSONArray(camera.availableFixedFps),
                "videoProfiles" to JSONArray(camera.videoProfiles.map {
                    json("size" to it.size.toString(), "fps" to it.fps, "hfr" to it.constrainedHighSpeed)
                }),
                "logProfiles" to JSONArray(camera.logProfiles.map {
                    json("size" to it.size.toString(), "fps" to it.fps, "hfr" to it.constrainedHighSpeed,
                        "source" to it.sourcePath.name, "hdr10" to it.hdr10)
                }),
            )
        }),
        "profileGate" to if (!hlgAdvertised) "Public Camera2 HLG10 profile unavailable on this ID/API." else null,
    )
}
