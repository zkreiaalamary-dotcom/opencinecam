/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult

/** Optional observation on the owning Camera2 callback thread. Implementations must return promptly. */
fun interface Camera2ResearchObserver {
    fun onGpuSource(width: Int, height: Int, fps: Int, source: OpenCineLogSourcePath) {}
    fun onEncoderStep(step: String, state: String, codec: String?, format: android.media.MediaFormat?, failure: Throwable?) {}
    fun onResult(request: CaptureRequest, result: TotalCaptureResult, sensorTimestampNs: Long?)
}
