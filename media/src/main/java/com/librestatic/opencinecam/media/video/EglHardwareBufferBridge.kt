/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.video

import android.hardware.HardwareBuffer
import android.view.Surface

/**
 * Zero-copy bridge from Android [HardwareBuffer] (e.g. `ImageReader(PRIVATE, USAGE_GPU_SAMPLED_IMAGE | USAGE_VIDEO_ENCODE)`)
 * to an `EGLImageKHR` bound to `GL_TEXTURE_EXTERNAL_OES`.
 * Safe on host JVMs where native shared libraries are absent ([loaded] is false).
 */
object EglHardwareBufferBridge {
    val loaded: Boolean by lazy {
        try {
            System.loadLibrary("occ_egl_hwbuffer")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun probeExtensions(): Int = if (loaded) nativeProbeExtensions() else 0

    fun createEglImage(buffer: HardwareBuffer): Long = if (loaded) nativeCreateEglImage(buffer) else 0L

    fun bindExternalTexture(imageHandle: Long): Int =
        if (loaded && imageHandle != 0L) nativeBindExternalTexture(imageHandle) else -1

    fun destroyEglImage(imageHandle: Long): Boolean =
        loaded && imageHandle != 0L && nativeDestroyEglImage(imageHandle)

    fun lastEglError(): Int = if (loaded) nativeLastEglError() else -1

    fun bindRenderbuffer(imageHandle: Long): Int =
        if (loaded && imageHandle != 0L) nativeBindRenderbuffer(imageHandle) else -1

    fun isAhbSupported(width: Int, height: Int, format: Int, usage: Long): Boolean =
        loaded && width > 0 && height > 0 && nativeIsAhbSupported(width, height, format, usage)

    fun allocateAhb(width: Int, height: Int, format: Int, usage: Long): HardwareBuffer? =
        if (loaded && width > 0 && height > 0) nativeAllocateAhb(width, height, format, usage) else null

    fun setSurfaceGeometryAndDataSpace(
        surface: Surface,
        width: Int,
        height: Int,
        format: Int,
        dataSpace: Int,
    ): Int = if (loaded) nativeSetSurfaceGeometryAndDataSpace(surface, width, height, format, dataSpace) else -1

    fun describeAhb(buffer: HardwareBuffer): Pair<Int, Long>? {
        if (!loaded) return null
        val out = LongArray(2)
        return if (nativeReadAhbFormatAndUsage(buffer, out)) out[0].toInt() to out[1] else null
    }

    fun bindTexture2D(imageHandle: Long): Int =
        if (loaded && imageHandle != 0L) nativeBindTexture2D(imageHandle) else -1

    fun inspectSurfaceWindow(surface: Surface): IntArray? {
        if (!loaded) return null
        val out = IntArray(3)
        return if (nativeInspectSurfaceWindow(surface, out)) out else null
    }

    fun probeAnwFormatLockAndPost(
        surface: Surface,
        width: Int,
        height: Int,
        format: Int,
        dataSpace: Int,
        patternMode: Int,
    ): Pair<Int, IntArray>? {
        if (!loaded) return null
        val stats = IntArray(6)
        val rc = nativeProbeAnwFormatLockAndPost(surface, width, height, format, dataSpace, patternMode, stats)
        return rc to stats
    }

    fun inspectAhbLockPlanes(buffer: HardwareBuffer, sampleBytes: Int = 64): Triple<Int, LongArray, Pair<IntArray, ByteArray>>? {
        if (!loaded) return null
        val meta = LongArray(6)
        val planes = IntArray(12)
        val sample = ByteArray(sampleBytes.coerceAtLeast(1))
        val rc = nativeInspectAhbLockPlanes(buffer, meta, planes, sample)
        return Triple(rc, meta, planes to sample)
    }

    fun probeVulkanAhb(ahbIn: HardwareBuffer?, ahbOut: HardwareBuffer? = null): String =
        if (loaded) nativeProbeVulkanAhb(ahbIn, ahbOut) else "{\"ok\":false,\"error\":\"jni_not_loaded\"}"

    @JvmStatic private external fun nativeProbeExtensions(): Int

    @JvmStatic private external fun nativeCreateEglImage(hardwareBuffer: HardwareBuffer): Long

    @JvmStatic private external fun nativeBindExternalTexture(imageHandle: Long): Int

    @JvmStatic private external fun nativeDestroyEglImage(imageHandle: Long): Boolean

    @JvmStatic private external fun nativeLastEglError(): Int

    @JvmStatic private external fun nativeBindRenderbuffer(imageHandle: Long): Int

    @JvmStatic private external fun nativeIsAhbSupported(width: Int, height: Int, format: Int, usage: Long): Boolean

    @JvmStatic private external fun nativeAllocateAhb(width: Int, height: Int, format: Int, usage: Long): HardwareBuffer?

    @JvmStatic private external fun nativeSetSurfaceGeometryAndDataSpace(
        surface: Surface,
        width: Int,
        height: Int,
        format: Int,
        dataSpace: Int,
    ): Int

    @JvmStatic private external fun nativeReadAhbFormatAndUsage(hardwareBuffer: HardwareBuffer, out2: LongArray): Boolean

    @JvmStatic private external fun nativeBindTexture2D(imageHandle: Long): Int

    @JvmStatic private external fun nativeInspectSurfaceWindow(surface: Surface, out3: IntArray): Boolean

    @JvmStatic private external fun nativeProbeAnwFormatLockAndPost(
        surface: Surface,
        width: Int,
        height: Int,
        format: Int,
        dataSpace: Int,
        patternMode: Int,
        outStats6: IntArray,
    ): Int

    @JvmStatic private external fun nativeInspectAhbLockPlanes(
        hardwareBuffer: HardwareBuffer,
        outMeta6: LongArray,
        outPlanes12: IntArray,
        sampleOut: ByteArray,
    ): Int

    @JvmStatic private external fun nativeProbeVulkanAhb(ahbIn: HardwareBuffer?, ahbOut: HardwareBuffer?): String
}
