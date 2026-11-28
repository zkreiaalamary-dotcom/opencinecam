/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Direct10BitVideoPolicyTest {
    @Test
    fun enablesOnlyForHuaweiCuvaRealTimeHlgWithoutGpuTransforms() {
        assertTrue(
            Direct10BitVideoPolicy.shouldUseDirect10BitHlg(
                hlgOutput = true,
                hdr10Output = false,
                huaweiCuvaHdr = true,
                constrainedHighSpeed = false,
                hasRecordingLut = false,
                hasLensCorrection = false,
                isTimelapse = false,
                hasProjectRateOverride = false,
                hasDesqueezedAnamorphic = false,
                hasEncoderOverride = false,
                targetFps = 60,
            ),
        )
        assertTrue(
            Direct10BitVideoPolicy.shouldUseDirect10BitHlg(
                hlgOutput = true,
                hdr10Output = false,
                huaweiCuvaHdr = true,
                constrainedHighSpeed = false,
                hasRecordingLut = false,
                hasLensCorrection = false,
                isTimelapse = false,
                hasProjectRateOverride = false,
                targetFps = 30,
            ),
        )
    }

    @Test
    fun fallsBackToGpuForOcLog2Hdr10LutLensCorrectionAndNonHuawei() {
        assertFalse(
            Direct10BitVideoPolicy.shouldUseDirect10BitHlg(
                hlgOutput = false,
                hdr10Output = false,
                huaweiCuvaHdr = true,
                constrainedHighSpeed = false,
                hasRecordingLut = false,
                hasLensCorrection = false,
                isTimelapse = false,
                hasProjectRateOverride = false,
            ),
        )
        assertFalse(
            Direct10BitVideoPolicy.shouldUseDirect10BitHlg(
                hlgOutput = true,
                hdr10Output = true,
                huaweiCuvaHdr = true,
                constrainedHighSpeed = false,
                hasRecordingLut = false,
                hasLensCorrection = false,
                isTimelapse = false,
                hasProjectRateOverride = false,
            ),
        )
        assertFalse(
            Direct10BitVideoPolicy.shouldUseDirect10BitHlg(
                hlgOutput = true,
                hdr10Output = false,
                huaweiCuvaHdr = false,
                constrainedHighSpeed = false,
                hasRecordingLut = false,
                hasLensCorrection = false,
                isTimelapse = false,
                hasProjectRateOverride = false,
            ),
        )
        assertFalse(
            Direct10BitVideoPolicy.shouldUseDirect10BitHlg(
                hlgOutput = true,
                hdr10Output = false,
                huaweiCuvaHdr = true,
                constrainedHighSpeed = false,
                hasRecordingLut = true,
                hasLensCorrection = false,
                isTimelapse = false,
                hasProjectRateOverride = false,
            ),
        )
        assertFalse(
            Direct10BitVideoPolicy.shouldUseDirect10BitHlg(
                hlgOutput = true,
                hdr10Output = false,
                huaweiCuvaHdr = true,
                constrainedHighSpeed = false,
                hasRecordingLut = false,
                hasLensCorrection = true,
                isTimelapse = false,
                hasProjectRateOverride = false,
            ),
        )
    }
}
