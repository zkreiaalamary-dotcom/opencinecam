/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import android.util.Size
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.pow

class OpenCineLogGpuPipelineContractTest {
    @Test
    fun productionShaderIsPinnedToTheSidecarIdentity() {
        assertEquals(
            "66ba3a4d5c432c935110f4639e64eb894b3a334a7397ce80fe139f9b787233aa",
            OpenCineLogGpuPipeline.TRANSFORM_SHA256,
        )
        assertEquals(
            "25db8970358056bc48d4aa3ed1540979af82764c58bae9a76d56a250cce9a0f8",
            OpenCineLogGpuPipeline.SDR_TRANSFORM_SHA256,
        )
        assertNotEquals(OpenCineLogGpuPipeline.TRANSFORM_SHA256, OpenCineLogGpuPipeline.SDR_TRANSFORM_SHA256)
        // Driver-sampler fallback identities, recorded so qualification can name and reject them.
        assertEquals(
            "41e87f366a5e89a0e78e5775147d080a0d82e54ec6b2c4afbb46a86e49107ffa",
            OpenCineLogGpuPipeline.transformSha256(OpenCineLogSourcePath.HLG10_BT2020, shaderYcbcr = false),
        )
        assertEquals(
            "bdb5d9f3db71d3d6d37a065e9d1a4d7f660e4c8ce98ceb2fa701dc71e10f997b",
            OpenCineLogGpuPipeline.transformSha256(OpenCineLogSourcePath.SDR_BT709_ISP, shaderYcbcr = false),
        )
    }

    @Test
    fun dithered8BitShaderAddsTpdfDitherWithoutMutatingPinnedShaders() {
        val base = OpenCineLogGpuPipeline.transformShader(OpenCineLogSourcePath.HLG10_BT2020, shaderYcbcr = true, lens = false, dither8Bit = false)
        val dithered = OpenCineLogGpuPipeline.transformShader(OpenCineLogSourcePath.HLG10_BT2020, shaderYcbcr = true, lens = false, dither8Bit = true)
        assertTrue(!base.contains("occTpdfDither8"))
        assertTrue(dithered.contains("uniform float uDitherFrame;"))
        assertTrue(dithered.contains("occTpdfDither8(gl_FragCoord.xy, uDitherFrame)"))
        assertNotEquals(
            OpenCineLogGpuPipeline.TRANSFORM_SHA256,
            OpenCineLogGpuPipeline.transformSha256(OpenCineLogSourcePath.HLG10_BT2020, shaderYcbcr = true, dither8Bit = true),
        )

        val lensedDithered = OpenCineLogGpuPipeline.transformShader(OpenCineLogSourcePath.HLG10_BT2020, shaderYcbcr = true, lens = true, dither8Bit = true)
        assertTrue(lensedDithered.contains("lensCorrect(") && lensedDithered.contains("occTpdfDither8("))

        val hlgDithered = OpenCineLogGpuPipeline.hlgSignalShader(shaderYcbcr = true, lens = true, dither8Bit = true)
        assertTrue(hlgDithered.contains("occTpdfDither8("))
    }

    @Test
    fun shaderYcbcrVariantOnlySwapsTheSampler() {
        for (path in OpenCineLogSourcePath.entries) {
            val driver = OpenCineLogGpuPipeline.transformShader(path, shaderYcbcr = false)
            val shader = OpenCineLogGpuPipeline.transformShader(path, shaderYcbcr = true)
            assertTrue(shader.contains("#extension GL_EXT_YUV_target : require"))
            assertTrue(shader.contains("uniform __samplerExternal2DY2YEXT uTexture;"))
            assertFalse(shader.contains("samplerExternalOES"))
            assertEquals(1, shader.split("uYcbcrToRgb * (texture(uTexture, vTexCoord).rgb - uYcbcrOffset)").size - 1)
            // Everything after the sampled value is the same transform body.
            assertEquals(driver.substringAfter("texture(uTexture, vTexCoord).rgb"), shader.substringAfter("uYcbcrOffset), 0.0, 1.0)"))
        }
    }

    @Test
    fun hlgRecordingShaderSharesTheYcbcrDecodeAndStaysSeparateFromLog() {
        val driver = OpenCineLogGpuPipeline.hlgSignalShader(shaderYcbcr = false)
        val shader = OpenCineLogGpuPipeline.hlgSignalShader(shaderYcbcr = true)
        assertTrue(shader.contains("#extension GL_EXT_YUV_target : require"))
        assertTrue(shader.contains("uniform __samplerExternal2DY2YEXT uTexture;"))
        assertFalse(shader.contains("samplerExternalOES"))
        assertEquals(driver.substringAfter("texture(uTexture, vTexCoord).rgb"), shader.substringAfter("uYcbcrOffset), 0.0, 1.0)"))
        assertTrue(shader.contains("bt709ToBt2020(inverseHlg("))
        assertNotEquals(OpenCineLogGpuPipeline.TRANSFORM_SHA256, OpenCineLogGpuPipeline.hlgSignalSha256())
    }

    @Test
    fun hlgRecordingSignalKeepsNeutralsAndRoundTripsTheCurve() {
        // The shader's math, mirrored: HLG10 code -> scene linear BT.709 -> BT.2020 -> HLG code.
        for (code in listOf(0.0, 0.1, 0.25, 0.5, 0.75, 0.9, 1.0)) {
            assertEquals(code, hlgOetf(inverseHlg(code)), 1e-6)
            // Rows of the 709 -> 2020 matrix sum to one, so greys are untouched.
            val grey = DoubleArray(3) { inverseHlg(code) }
            val converted = bt709ToBt2020(grey)
            converted.forEach { assertEquals(code, hlgOetf(it), 1e-5) }
        }
        // A saturated BT.709 red lands inside BT.2020, i.e. less saturated codes.
        val red = bt709ToBt2020(doubleArrayOf(inverseHlg(0.75), 0.0, 0.0)).map(::hlgOetf)
        assertTrue(red[0] < 0.75 && red[1] > 0.0 && red[2] > 0.0)
    }

    @Test
    fun sourceDataSpaceMustMatchTheTierShader() {
        val bt2020HlgFull = 168165376 // DataSpace.DATASPACE_BT2020_HLG
        val bt2020HlgLimited = 302383104 // DataSpace.DATASPACE_BT2020_ITU_HLG
        val bt2020PqFull = 163971072 // DataSpace.DATASPACE_BT2020_PQ
        val bt709Limited = 281083904 // DataSpace.DATASPACE_BT709
        val jfif = 146931712 // DataSpace.DATASPACE_JFIF
        assertTrue(OpenCineLogSourcePath.HLG10_BT2020.acceptsDataSpace(bt2020HlgFull))
        assertTrue(OpenCineLogSourcePath.HLG10_BT2020.acceptsDataSpace(bt2020HlgLimited))
        assertFalse(OpenCineLogSourcePath.HLG10_BT2020.acceptsDataSpace(bt2020PqFull))
        assertFalse(OpenCineLogSourcePath.HLG10_BT2020.acceptsDataSpace(bt709Limited))
        assertFalse(OpenCineLogSourcePath.HLG10_BT2020.acceptsDataSpace(0))
        assertTrue(OpenCineLogSourcePath.SDR_BT709_ISP.acceptsDataSpace(bt709Limited))
        assertTrue(OpenCineLogSourcePath.SDR_BT709_ISP.acceptsDataSpace(jfif))
        assertTrue(OpenCineLogSourcePath.SDR_BT709_ISP.acceptsDataSpace(0))
        assertFalse(OpenCineLogSourcePath.SDR_BT709_ISP.acceptsDataSpace(bt2020HlgFull))
        assertFalse(OpenCineLogSourcePath.SDR_BT709_ISP.acceptsDataSpace(bt2020PqFull))
    }

    @Test
    fun thermalSuspensionSkipsScopeReadbackAndResumesOnCadence() {
        assertTrue(scopeAnalysisDue(suspended = false, nowMs = 1_100, lastAnalysisAtMs = 1_000, periodMs = 100))
        assertEquals(false, scopeAnalysisDue(suspended = false, nowMs = 1_099, lastAnalysisAtMs = 1_000, periodMs = 100))
        assertEquals(false, scopeAnalysisDue(suspended = true, nowMs = 9_000, lastAnalysisAtMs = 1_000, periodMs = 100))
        assertTrue(scopeAnalysisDue(suspended = false, nowMs = 9_000, lastAnalysisAtMs = 1_000, periodMs = 100))
    }

    @Test
    fun hlgDecodeThenOcLog2MatchesReferenceCurve() {
        val hlgCodes = listOf(0.0, 0.25, 0.5, 0.75, 1.0)
        val results = hlgCodes.map { OpenCineLog2Curve.encode(inverseHlg(it)) }
        assertEquals(OpenCineLog2Curve.BLACK_CODE, results.first(), 1e-9)
        assertEquals(OpenCineLog2Curve.WHITE_CODE, results.last(), 1e-9)
        assertTrue(results.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun monitoringAssistIsNumericallySeparateFromFlatLog() {
        val linear = inverseHlg(0.75)
        val flat = OpenCineLog2Curve.encode(linear)
        val assisted = rec709Oetf(OpenCineLog2Curve.decode(flat))
        assertNotEquals(flat, assisted, 1e-3)
        assertEquals(linear, OpenCineLog2Curve.decode(flat), 1e-9)
    }

    @Test
    fun ocLog2ReservesBlackPedestalAndHighlightHeadroom() {
        assertEquals(0.10, OpenCineLog2Curve.encode(0.0), 1e-12)
        assertEquals(0.90, OpenCineLog2Curve.encode(1.0), 1e-12)
        listOf(0.0, 0.01, 0.18, 0.5, 1.0).forEach { linear ->
            assertEquals(linear, OpenCineLog2Curve.decode(OpenCineLog2Curve.encode(linear)), 1e-9)
        }
    }

    @Test
    fun ispHfrBranchDisclosesStandardRangeAndNeverClaimsTenBitSource() {
        val source = OpenCineLogSourcePath.SDR_BT709_ISP
        assertTrue(source.highSpeedDerived)
        assertEquals("STANDARD", source.dynamicRange)
        assertEquals("BT709_ASSUMED", source.colorSpace)
        assertTrue(source.sourcePrecision.contains("not claimed"))
        assertTrue(!OpenCineLogSourcePath.HLG10_BT2020.highSpeedDerived)
        assertThrows(IllegalArgumentException::class.java) {
            Camera2LogProfile(Size(1920, 1080), 120, OpenCineLogSourcePath.HLG10_BT2020, true)
        }
        Camera2LogProfile(Size(1920, 1080), 120, OpenCineLogSourcePath.SDR_BT709_ISP, true)
    }

    @Test
    fun runtimeCapabilityDoesNotImplyQualification() {
        val experimental = Camera2LogProfile(
            Size(1920, 1080),
            30,
            OpenCineLogSourcePath.HLG10_BT2020,
            false,
        )
        val descriptor = descriptorWith(listOf(experimental))

        assertTrue(descriptor.supportsOpenCineLog)
        assertTrue(!descriptor.hasVerifiedOpenCineLog)
        assertTrue(!descriptor.allOpenCineLogProfilesVerified)
        assertEquals(OpenCineLogQualificationStage.EXPERIMENTAL, experimental.qualificationStage)
        assertEquals(OpenCineLogQualificationPolicy.NOT_RUN_REASON, experimental.qualificationReason)
    }

    @Test
    fun verifiedProfileRequiresExactEvidenceIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            Camera2LogProfile(
                Size(1920, 1080),
                30,
                OpenCineLogSourcePath.HLG10_BT2020,
                false,
                qualificationStage = OpenCineLogQualificationStage.VERIFIED,
            )
        }
        val verified = Camera2LogProfile(
            Size(1920, 1080),
            30,
            OpenCineLogSourcePath.HLG10_BT2020,
            false,
            qualificationStage = OpenCineLogQualificationStage.VERIFIED,
            qualificationReason = "qualified-exact-tuple",
            qualificationEvidenceId = "plan-061/device-0/1920x1080-30-hlg10",
        )
        assertTrue(verified.isVerified)
        val verifiedDescriptor = descriptorWith(listOf(verified))
        assertTrue(verifiedDescriptor.hasVerifiedOpenCineLog)
        assertTrue(verifiedDescriptor.allOpenCineLogProfilesVerified)
        assertTrue(!descriptorWith(listOf(verified, Camera2LogProfile(
            Size(1280, 720),
            30,
            OpenCineLogSourcePath.HLG10_BT2020,
            false,
        ))).allOpenCineLogProfilesVerified)
    }

    @Test
    fun inverseRec709IsMonotonicBeforeOcLogEncoding() {
        val codes = listOf(0.0, 0.04, 0.081, 0.5, 1.0)
        val linear = codes.map(::inverseRec709)
        assertEquals(0.0, linear.first(), 1e-12)
        assertEquals(1.0, linear.last(), 1e-12)
        assertTrue(linear.zipWithNext().all { (a, b) -> b > a })
        assertTrue(linear.map(OpenCineLog2Curve::encode).zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun imageReaderTextureMatrixMatchesSurfaceTextureDetAndRotatesAsymmetricCorners() {
        fun sampleUv(m: FloatArray, u0: Float, v0: Float): Pair<Float, Float> {
            val s = m[0] * u0 + m[4] * v0 + m[12]
            val t = m[1] * u0 + m[5] * v0 + m[13]
            return s to t
        }
        for (sensor in listOf(0, 90, 180, 270)) {
            val m = imageReaderTextureMatrix(sensor)
            assertEquals(-1f, m[0] * m[5] - m[4] * m[1], 1e-6f)
            // unmirrorSurfaceTextureMatrix must be a no-op when det is already -1
            val copy = m.copyOf()
            unmirrorSurfaceTextureMatrix(copy)
            assertTrue(m.contentEquals(copy))
        }
        // 0 deg: Y-flip only -> TL(0,1) reads raw (0,0), TR(1,1)->(1,0), BL(0,0)->(0,1), BR(1,0)->(1,1)
        val m0 = imageReaderTextureMatrix(0)
        assertEquals(0f to 0f, sampleUv(m0, 0f, 1f))
        assertEquals(1f to 0f, sampleUv(m0, 1f, 1f))
        assertEquals(0f to 1f, sampleUv(m0, 0f, 0f))
        assertEquals(1f to 1f, sampleUv(m0, 1f, 0f))

        // 90 deg CW sensor: rotating raw 90 CW puts raw TL(0,0) at TR(1,1), BL(0,1) at TL(0,1), TR(1,0) at BR(1,0)
        val m90 = imageReaderTextureMatrix(90)
        assertEquals(0f to 1f, sampleUv(m90, 0f, 1f))
        assertEquals(0f to 0f, sampleUv(m90, 1f, 1f))
        assertEquals(1f to 1f, sampleUv(m90, 0f, 0f))
        assertEquals(1f to 0f, sampleUv(m90, 1f, 0f))

        // 270 deg CW sensor (front camera): rotating raw 270 CW puts raw TL(0,0) at BL(0,0), TR(1,0) at TL(0,1), BL(0,1) at BR(1,0)
        val m270 = imageReaderTextureMatrix(270)
        assertEquals(1f to 0f, sampleUv(m270, 0f, 1f))
        assertEquals(1f to 1f, sampleUv(m270, 1f, 1f))
        assertEquals(0f to 0f, sampleUv(m270, 0f, 0f))
        assertEquals(0f to 1f, sampleUv(m270, 1f, 0f))

        assertThrows(IllegalArgumentException::class.java) { imageReaderTextureMatrix(45) }
    }

    @Test
    fun vertexQuadCompositionPreservesUprightAxesAcrossPreviewAndRecordingRotations() {
        // Simulates VERTEX_SHADER position rotation + mirror and texture UV lookup against
        // a 16:9 horizontal camera sensor (natural top=(0..1, t=0), bottom=(0..1, t=1), left=s=0, right=s=1).
        fun sampleAtScreenCorner(
            sensor: Int,
            positionRotationDeg: Int,
            mirrorHoriz: Boolean,
            targetX: Float,
            targetY: Float,
        ): Pair<Float, Float> {
            val m = imageReaderTextureMatrix(sensor)
            val rotStep = positionRotationDeg / 90
            // Solve backwards which input vertex aPosition lands at (targetX, targetY)
            for (ax in listOf(-1f, 1f)) {
                for (ay in listOf(-1f, 1f)) {
                    var px = ax
                    var py = ay
                    when (rotStep) {
                        1 -> { val nx = py; val ny = -px; px = nx; py = ny }
                        2 -> { px = -px; py = -py }
                        3 -> { val nx = -py; val ny = px; px = nx; py = ny }
                    }
                    if (mirrorHoriz) px = -px
                    if (px == targetX && py == targetY) {
                        val u0 = (ax + 1f) * 0.5f
                        val v0 = (ay + 1f) * 0.5f
                        return (m[0] * u0 + m[4] * v0 + m[12]) to (m[1] * u0 + m[5] * v0 + m[13])
                    }
                }
            }
            error("Corner not found")
        }

        // Held portrait (display=0): back sensor 90 -> upright physical world has sky at raw s=0, floor at s=1.
        //Wait: for 90 deg CW raw buffer, raw LEFT (s=0) is sky/top of room when portrait!
        //Wait: check both RecordingGeometry (COMPATIBLE & NATIVE_RASTER) and PreviewGeometry agree 100%!
        for (sensor in listOf(90, 270)) {
            val frontFacing = sensor == 270
            val lensFacing = if (frontFacing) 0 else 1
            for (orient in listOf(0, 90, 180, 270)) {
                // Note: Display rotation and deviceOrientation have opposite signs for 90 vs 270, so use matching display rotation:
                // deviceOrientation = (360 - displayRotation) % 360 -> test RecordingGeometry rendererRotation matches PreviewGeometry when display == (360 - device)%360
                val displayRot = (360 - orient) % 360
                val previewGeom = OpenCineLogPreviewGeometryCalculator.calculate(
                    sourceWidth = 1920, sourceHeight = 1080,
                    targetWidth = 1080, targetHeight = 1920,
                    sensorOrientationDegrees = sensor, displayRotationDegrees = displayRot,
                    frontFacing = frontFacing,
                )
                val recGeom = RecordingGeometryCalculator.calculate(
                    sourceWidth = 1920, sourceHeight = 1080,
                    sensorOrientationDegrees = sensor, deviceOrientationDegrees = orient,
                    lensFacing = lensFacing, mode = RecordingGeometryMode.COMPATIBLE,
                )
                // Recording rendererRotation equals preview positionRotation (only unmirrored)!
                assertEquals(previewGeom.positionRotationDegrees, recGeom.rendererRotationDegrees)
                // Portrait (display=0, orient=0): TL/TR share top-edge buffer axis, BL/BR share bottom-edge buffer axis
                if (orient == 0) {
                    val tl = sampleAtScreenCorner(sensor, previewGeom.positionRotationDegrees, false, -1f, 1f)
                    val tr = sampleAtScreenCorner(sensor, previewGeom.positionRotationDegrees, false, 1f, 1f)
                    val bl = sampleAtScreenCorner(sensor, previewGeom.positionRotationDegrees, false, -1f, -1f)
                    // TL & TR must lie along the SAME sensor row/col edge (for 90: s=0, for 270: s=1), orthogonal to BL
                    assertEquals(tl.first, tr.first, 1e-6f)
                    assertNotEquals(tl.first, bl.first, 1e-6f)
                }
            }
        }
    }

    private fun inverseHlg(code: Double): Double = if (code <= 0.5) {
        code * code / 3.0
    } else {
        (exp((code - 0.55991073) / 0.17883277) + 0.28466892) / 12.0
    }.coerceIn(0.0, 1.0)

    private fun hlgOetf(linear: Double): Double {
        val x = linear.coerceIn(0.0, 1.0)
        return if (x <= 1.0 / 12.0) sqrt(3.0 * x) else 0.17883277 * ln(max(12.0 * x - 0.28466892, 1e-6)) + 0.55991073
    }

    private fun bt709ToBt2020(c: DoubleArray) = doubleArrayOf(
        0.627404 * c[0] + 0.329283 * c[1] + 0.043313 * c[2],
        0.069097 * c[0] + 0.919540 * c[1] + 0.011362 * c[2],
        0.016391 * c[0] + 0.088013 * c[1] + 0.895595 * c[2],
    )

    private fun rec709Oetf(linear: Double): Double = if (linear < 0.018) {
        4.5 * linear
    } else {
        1.099 * linear.pow(0.45) - 0.099
    }

    private fun inverseRec709(code: Double): Double = if (code < 0.081) {
        code / 4.5
    } else {
        ((code + 0.099) / 1.099).pow(1.0 / 0.45)
    }

    private fun descriptorWith(logProfiles: List<Camera2LogProfile>) = Camera2CameraDescriptor(
        cameraId = "0",
        lensFacing = 1,
        focalLengthsMm = emptyList(),
        previewSize = Size(1920, 1080),
        jpegSize = null,
        rawSize = null,
        analysisSize = null,
        sensorOrientation = 90,
        sensitivityRange = null,
        exposureTimeRangeNs = null,
        aeCompensationRange = null,
        aeCompensationStep = 0f,
        minimumFocusDistance = null,
        supportsRaw = false,
        flashAvailable = false,
        targetFpsRanges = emptyList(),
        availableFixedFps = emptyList(),
        videoProfiles = emptyList(),
        logProfiles = logProfiles,
    )
}
