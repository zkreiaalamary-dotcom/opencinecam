/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CinemaLutCompatibilityTest {
    private fun repoFile(relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        error("Could not locate $relative from ${File(".").absolutePath}")
    }

    @Test
    fun generated33CubeTechnicalLutsLoadInCubeLutAndAlignGrey() {
        val hlgRec709 = CubeLut.parse(repoFile("docs/color/cinema-luts/technical/OCLog2_BT2020_HLG_to_Rec709_33.cube").readBytes())
        assertEquals(33, hlgRec709.size)
        val monitor = MonitorLut(hlgRec709, LutTransformKind.TECHNICAL, LutSignalDomain.OCLOG2_CODE)
        assertTrue(monitorLutCompatible(monitor, passthroughSdr = false))

        // HLG 18% grey sits at ~0.3490 in NATIVE OCLog2; the technical LUT lifts it cleanly to ~0.409 Rec.709.
        val hlgGreyCode = OpenCineLog2Curve.encode(OpenCineLogGreyReference.HLG_REFERENCE_GREY_LINEAR).toFloat()
        val rec709Grey = hlgRec709.sample(hlgGreyCode, hlgGreyCode, hlgGreyCode)
        for (ch in 0..2) {
            assertEquals(0.409f, rec709Grey[ch], 0.004f)
        }

        val ocl3Rec709 = CubeLut.parse(repoFile("docs/color/cinema-luts/technical/OCLog3_BT2020_to_Rec709_33.cube").readBytes())
        val ocl3Grey = OpenCineLog3Curve.encode(0.18).toFloat()
        val mapped3 = ocl3Rec709.sample(ocl3Grey, ocl3Grey, ocl3Grey)
        for (ch in 0..2) {
            assertEquals(0.409f, mapped3[ch], 0.003f)
        }
    }
}
