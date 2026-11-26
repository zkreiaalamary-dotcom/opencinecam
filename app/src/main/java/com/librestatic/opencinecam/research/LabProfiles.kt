/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.util.Size
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import com.librestatic.opencinecam.camera.Camera2LogProfile
import com.librestatic.opencinecam.camera.Camera2VideoProfile
import com.librestatic.opencinecam.camera.OpenCineLogSourcePath
import org.json.JSONArray
import org.json.JSONObject

internal class LabProfiles(private val manager: CameraManager, val descriptor: Camera2CameraDescriptor) {
    private val id = descriptor.cameraId
    private val caps = manager.getCameraCharacteristics(id)
    private val map = requireNotNull(caps.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
    private val textureSizes = runCatching { map.getOutputSizes(SurfaceTexture::class.java).orEmpty().toSet() }.getOrDefault(emptySet())
    private val recorderSizes = runCatching { map.getOutputSizes(MediaRecorder::class.java).orEmpty().toSet() }.getOrDefault(emptySet())
    private val privateSizes = runCatching { map.getOutputSizes(ImageFormat.PRIVATE).orEmpty().toSet() }.getOrDefault(emptySet())
    private val rawSizes = runCatching { map.getOutputSizes(ImageFormat.RAW10).orEmpty().toList() }.getOrDefault(emptyList())
    private val dynamicRangeDiscovery = LabApiCompat.discoverDynamicRange(caps)
    private val hlgAdvertised = dynamicRangeDiscovery.hlg10Advertised

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
            add(LabCase(id, LabPath.CODEC_SYNTHETIC_MATRIX, 3840, 2160, 30, listed = false))
            val uhdInTexture = Size(3840, 2160) in textureSizes
            val has30 = descriptor.targetFpsRanges.any { 30 in it.lower..it.upper }
            val fixed60 = descriptor.targetFpsRanges.any { it.lower == 60 && it.upper == 60 }
            if (uhdInTexture && has30) {
                add(LabCase(id, LabPath.LOG_REGULAR_4K30, 3840, 2160, 30, listed = false))
                add(LabCase(id, LabPath.LOG_REGULAR_4K30, 3840, 2160, 30, listed = false, flatToneMap = true))
                add(LabCase(id, LabPath.LOG_REGULAR_4K30, 3840, 2160, 30, listed = false, variant = "gamma_1_8"))
                add(LabCase(id, LabPath.GPU_HEVC_4K30, 3840, 2160, 30, listed = false))
                add(LabCase(id, LabPath.ISP_TONEMAP_AB, 3840, 2160, 30, listed = false))
                add(LabCase(id, LabPath.ISP_COLOR_CORRECTION_PROBE, 3840, 2160, 30, listed = false))
                add(LabCase(id, LabPath.ISP_CUVA_HDR_PROBE, 3840, 2160, 30, listed = false))
            } else if (has30 && Size(1920, 1080) in textureSizes) {
                add(LabCase(id, LabPath.ISP_COLOR_CORRECTION_PROBE, 1920, 1080, 30, listed = false))
                add(LabCase(id, LabPath.ISP_CUVA_HDR_PROBE, 1920, 1080, 30, listed = false))
            }
            if (uhdInTexture && (fixed60 || descriptor.targetFpsRanges.any { 60 in it.lower..it.upper })) {
                add(LabCase(id, LabPath.LOG_REGULAR_4K60, 3840, 2160, 60, listed = false))
                add(LabCase(id, LabPath.LOG_REGULAR_4K60, 3840, 2160, 60, listed = false, variant = "realtime"))
                add(LabCase(id, LabPath.LOG_REGULAR_4K60, 3840, 2160, 60, listed = false, variant = "single_ctx"))
                add(LabCase(id, LabPath.LOG_REGULAR_4K60, 3840, 2160, 60, listed = false, variant = "prev_30fps"))
                add(LabCase(id, LabPath.LOG_REGULAR_4K60, 3840, 2160, 60, listed = false, variant = "no_preview"))
                add(LabCase(id, LabPath.LOG_REGULAR_4K60, 3840, 2160, 60, listed = false, variant = "bitrate_45m"))
                add(LabCase(id, LabPath.LOG_REGULAR_4K60, 3840, 2160, 60, listed = false, variant = "egl8"))
                add(LabCase(id, LabPath.LOG_REGULAR_4K60, 3840, 2160, 60, listed = false, variant = "novui"))
                add(LabCase(id, LabPath.LOG_REGULAR_4K60, 3840, 2160, 60, listed = false, variant = "scopes_pbo"))
                add(LabCase(id, LabPath.GPU_HEVC_4K60, 3840, 2160, 60, listed = false))
                add(LabCase(id, LabPath.GPU_HEVC_4K60, 3840, 2160, 60, listed = false, variant = "prev_30fps"))
                add(LabCase(id, LabPath.GPU_HEVC_4K60, 3840, 2160, 60, listed = false, variant = "novui"))
                add(LabCase(id, LabPath.GPU_HEVC_4K60, 3840, 2160, 60, listed = false, variant = "scopes_pbo"))
            }
            add(LabCase(id, LabPath.SOURCE_FORMAT_PROBE, 3840, 2160, 30, listed = false))
            if (Size(3840, 2160) in recorderSizes && fixed60) {
                val direct = LabCase(id, LabPath.DIRECT_HEVC, 3840, 2160, 60, listed = false)
                add(direct)
                if (Size(1280, 720) in privateSizes) add(direct.copy(path = LabPath.DIRECT_HEVC_PREVIEW))
                if (Size(1280, 720) in textureSizes) {
                    add(direct.copy(path = LabPath.DIRECT_HEVC_SURFACETEXTURE))
                    add(direct.copy(path = LabPath.DIRECT_HEVC_SURFACETEXTURE, longer = true))
                    add(direct.copy(path = LabPath.DIRECT_HEVC_SURFACETEXTURE_360P))
                    add(direct.copy(path = LabPath.DIRECT_HEVC_SURFACETEXTURE_360P, longer = true))
                }
                add(direct.copy(longer = true))
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

    fun inventory(
        cameras: List<Camera2CameraDescriptor>,
        sectionInjectorForTest: ((String) -> Unit)? = null,
    ): JSONObject {
        val errors = JSONArray()
        fun safeSection(section: String, block: () -> Any?): Any? = try {
            sectionInjectorForTest?.invoke(section)
            block()
        } catch (e: VirtualMachineError) {
            throw e
        } catch (t: Throwable) {
            errors.put(json(
                "section" to section,
                "errorClass" to t.javaClass.name,
                "message" to t.message?.take(300),
                "sdkInt" to Build.VERSION.SDK_INT,
            ))
            null
        }

        val colorCorrection = safeSection("colorCorrectionDiscovery") {
            LabApiCompat.discoverColorCorrection(caps)
        } as? ColorCorrectionDiscovery

        return json(
            "selectedCamera" to id,
            "sdkInt" to Build.VERSION.SDK_INT,
            "hlg10Advertised" to hlgAdvertised,
            "dynamicRangeDiscovery" to dynamicRangeDiscovery.toJson(),
            "mediaRecorderSizes" to JSONArray(recorderSizes.map { it.toString() }.sorted()),
            "privateOutputSizes" to JSONArray(privateSizes.map { it.toString() }.sorted()),
            "raw10Sizes" to JSONArray(rawSizes.map { it.toString() }),
            "surfaceTextureSizes" to JSONArray(textureSizes.map { it.toString() }.sorted()),
            "outputFormats" to JSONArray(runCatching { map.outputFormats.toList() }.getOrDefault(emptyList())),
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
            "profileGate" to dynamicRangeDiscovery.note,
            "descriptorRejectionDiagnostics" to safeSection("descriptorRejectionDiagnostics") { descriptorRejectionDiagnostics() },
            "videoEncoders" to safeSection("videoEncoders") { videoEncoderInventory() },
            "physicalCameras" to safeSection("physicalCameras") { physicalCameraInventory() },
            "vendorKeys" to safeSection("vendorKeys") { vendorKeyInventory(caps) },
            "toneMapModes" to safeSection("toneMapModes") {
                JSONArray(caps.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.toList().orEmpty())
            },
            "maxToneMapCurvePoints" to safeSection("maxToneMapCurvePoints") {
                caps.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS)
            },
            "colorCorrectionDiscovery" to colorCorrection?.toJson(),
            "colorCorrectionModes" to JSONArray(
                colorCorrection?.advertisedModes ?: colorCorrection?.dynamicKeyModes ?: emptyList<Int>(),
            ),
            "huaweiCameraKitClassPresent" to safeSection("huaweiCameraKitClassPresent") {
                runCatching { Class.forName("com.huawei.camera.camerakit.CameraKit") }.isSuccess
            },
            "inventoryErrors" to errors,
        )
    }

    private fun descriptorRejectionDiagnostics(): JSONArray {
        val probeSizes = listOf(Size(1280, 720), Size(1920, 1080), Size(3840, 2160), Size(4096, 2304))
        return JSONArray(probeSizes.map { size ->
            fun classDuration(klass: Class<*>) = json(
                "listed" to (size in map.getOutputSizes(klass).orEmpty()),
                "minFrameDurationNs" to runCatching { map.getOutputMinFrameDuration(klass, size) }.getOrNull(),
                "stallDurationNs" to runCatching { map.getOutputStallDuration(klass, size) }.getOrNull(),
            )
            fun formatDuration(format: Int) = json(
                "listed" to (size in runCatching { map.getOutputSizes(format).orEmpty() }.getOrDefault(emptyArray())),
                "minFrameDurationNs" to runCatching { map.getOutputMinFrameDuration(format, size) }.getOrNull(),
                "stallDurationNs" to runCatching { map.getOutputStallDuration(format, size) }.getOrNull(),
            )
            json(
                "size" to "${size.width}x${size.height}",
                "surfaceTexture" to classDuration(SurfaceTexture::class.java),
                "mediaRecorder" to classDuration(MediaRecorder::class.java),
                "private" to formatDuration(ImageFormat.PRIVATE),
                "yuv420" to formatDuration(ImageFormat.YUV_420_888),
                "rawSensor" to formatDuration(ImageFormat.RAW_SENSOR),
                "raw10" to formatDuration(ImageFormat.RAW10),
            )
        })
    }

    private fun videoEncoderInventory(): JSONArray {
        val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        val probePoints = listOf(
            Triple(1920, 1080, 30),
            Triple(1920, 1080, 60),
            Triple(1920, 1080, 120),
            Triple(3840, 2160, 30),
            Triple(3840, 2160, 60),
        )
        return JSONArray(codecs.filter { it.isEncoder && !it.isAlias }.flatMap { info ->
            info.supportedTypes.filter {
                it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) || it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true)
            }.mapNotNull { type ->
                val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull() ?: return@mapNotNull null
                val video = caps.videoCapabilities
                json(
                    "name" to info.name,
                    "mime" to type,
                    "hardwareAccelerated" to info.isHardwareAccelerated,
                    "colorFormats" to JSONArray(caps.colorFormats.toList()),
                    "profileLevels" to JSONArray(caps.profileLevels.map { json("profile" to it.profile, "level" to it.level) }),
                    "bitrateRange" to video?.bitrateRange?.toString(),
                    "widths" to video?.supportedWidths?.toString(),
                    "heights" to video?.supportedHeights?.toString(),
                    "sizeAndRate" to JSONObject().apply {
                        probePoints.forEach { (w, h, fps) ->
                            put("${w}x${h}@$fps", video?.areSizeAndRateSupported(w, h, fps.toDouble()) == true)
                        }
                    },
                )
            }
        })
    }

    private fun physicalCameraInventory(): JSONArray {
        val ids = runCatching { caps.physicalCameraIds }.getOrDefault(emptySet()).sorted()
        return JSONArray(ids.map { physicalId ->
            val pCaps = runCatching { manager.getCameraCharacteristics(physicalId) }.getOrNull()
            val pMap = pCaps?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            json(
                "id" to physicalId,
                "focalLengthsMm" to JSONArray(pCaps?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty()),
                "activeArray" to pCaps?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.toString(),
                "outputFormats" to JSONArray(pMap?.outputFormats?.toList().orEmpty()),
                "raw10Sizes" to JSONArray(runCatching { pMap?.getOutputSizes(ImageFormat.RAW10)?.map { it.toString() } }.getOrNull().orEmpty()),
                "rawSensorSizes" to JSONArray(runCatching { pMap?.getOutputSizes(ImageFormat.RAW_SENSOR)?.map { it.toString() } }.getOrNull().orEmpty()),
                "vendorKeyCount" to (pCaps?.let { c -> c.keys.count { !it.name.startsWith("android.") } } ?: 0),
            )
        })
    }

    private fun vendorKeyInventory(characteristics: CameraCharacteristics): JSONObject {
        fun nonAndroid(names: List<String>) = JSONArray(names.filterNot { it.startsWith("android.") }.distinct().sorted())
        val (charKeys, reqKeys, resKeys) = LabApiCompat.safeCharacteristicsKeyNames(characteristics)
        return json(
            "characteristics" to nonAndroid(charKeys),
            "request" to nonAndroid(reqKeys),
            "result" to nonAndroid(resKeys),
            "session" to nonAndroid(runCatching { characteristics.availableSessionKeys.orEmpty().map { it.name } }.getOrDefault(emptyList())),
        )
    }
}
