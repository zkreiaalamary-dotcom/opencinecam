/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.media.video

import android.hardware.HardwareBuffer

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

    @JvmStatic private external fun nativeProbeExtensions(): Int

    @JvmStatic private external fun nativeCreateEglImage(hardwareBuffer: HardwareBuffer): Long

    @JvmStatic private external fun nativeBindExternalTexture(imageHandle: Long): Int

    @JvmStatic private external fun nativeDestroyEglImage(imageHandle: Long): Boolean

    @JvmStatic private external fun nativeLastEglError(): Int
}
