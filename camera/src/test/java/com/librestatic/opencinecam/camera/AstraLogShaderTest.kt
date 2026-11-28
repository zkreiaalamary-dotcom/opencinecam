/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AstraLogShaderTest {
    @Test
    fun cuvaMatchSdrKeepsRecordGainAtUnity() {
        val path = OpenCineLogSourcePath.HLG10_BT2020
        val requested = OpenCineLogGreyReference.MATCH_SDR
        assertEquals(1f, astraRecordingGreyReference(requested, true).sceneGain(path), 0f)
        assertEquals(requested, astraRecordingGreyReference(requested, false))
    }

    @Test
    fun bothSamplersKeepLinearCorrectionBetweenDecodeAndOutputGamut() {
        for (explicitYcbcr in listOf(false, true)) {
            for (primaries in OpenCineLogSourcePrimaries.entries) {
                val shader = OpenCineLogGpuPipeline.transformShader(
                    OpenCineLogSourcePath.HLG10_BT2020,
                    shaderYcbcr = explicitYcbcr, lens = true, dither8Bit = true,
                    sourcePrimaries = primaries, astraLive = true,
                )
                assertTrue(shader.contains("astraLens(inverseHlg("))
                assertTrue(shader.contains("return polarity * mix"))
                assertTrue(shader.contains("ditheredOcLog"))
                assertFalse(shader.contains("clamp(uYcbcrToRgb"))
                assertFalse(shader.contains("lensEncode("))
                if (primaries == OpenCineLogSourcePrimaries.BT709_QUIRK) {
                    assertTrue(shader.contains("bt709ToBt2020(astraLens("))
                }
            }
        }
    }
}
