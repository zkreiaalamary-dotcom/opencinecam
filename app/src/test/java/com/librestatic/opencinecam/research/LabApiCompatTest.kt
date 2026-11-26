/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Range
import android.util.Size
import androidx.test.core.app.ApplicationProvider
import com.librestatic.opencinecam.camera.Camera2CameraDescriptor
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCameraCharacteristics

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class LabApiCompatTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun api31ColorCorrectionAndDynamicRangeDiscoveryNeverThrowAndNeverTreatMissingQueryKeyAsAbsence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(CameraManager::class.java)
        val chars = ShadowCameraCharacteristics.newCameraCharacteristics()
        val shadowChars = shadowOf(chars)
        shadowChars.set(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
            intArrayOf(LabApiCompat.CAPABILITY_MANUAL_POST_PROCESSING),
        )
        shadowChars.set(
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL,
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3,
        )
        shadowOf(manager as CameraManager).addCamera("0", chars)

        val cc = LabApiCompat.discoverColorCorrection(chars, sdkInt = 31)
        assertFalse(cc.queryApiLevelSupported)
        assertNull(cc.advertisedModes)
        assertTrue(cc.manualPostProcessingAdvertised)
        assertEquals(
            "INFERRED_SUPPORTED_VIA_MANUAL_POST_PROCESSING_AND_KEYS_NEEDS_EMPIRICAL_TEST",
            cc.inferenceStatus,
        )

        // When no capabilities or request keys are mocked either, must STILL explicitly say NOT_PROOF_OF_ABSENCE.
        val bareChars = ShadowCameraCharacteristics.newCameraCharacteristics()
        val bareCc = LabApiCompat.discoverColorCorrection(bareChars, sdkInt = 31)
        assertEquals("QUERY_KEY_UNAVAILABLE_ON_API_31_NOT_PROOF_OF_ABSENCE", bareCc.inferenceStatus)

        val dr = LabApiCompat.discoverDynamicRange(chars, sdkInt = 31)
        assertFalse(dr.queryApiLevelSupported)
        assertFalse(dr.hlg10Advertised)
        assertTrue(dr.note.contains("does NOT prove hardware lacks"))
    }

    @Test
    fun crossRevisionResumabilityPreservesPriorAttemptsWithoutBreakingStrictCompatibilityCheck() {
        val root = folder.newFolder()
        val c1 = LabCase("0", LabPath.CODEC_SYNTHETIC_MATRIX, 3840, 2160, 30, listed = false)
        val c2 = LabCase("0", LabPath.LOG_REGULAR_4K30, 3840, 2160, 30, listed = false)
        val journal = LabJournal.create(root, "rev-old")
        journal.begin(LabDecision(c1, null, "old apk attempt"), "rev-old").apply {
            event(LabStage.FILE, json("mime" to "video/hevc"))
            result(LabVerdict.OBSERVED, "ok")
        }

        val reopened = requireNotNull(LabJournal.latest(root))
        assertTrue(reopened.compatibleWith("rev-old"))
        assertFalse(reopened.compatibleWith("rev-new"))
        assertTrue(reopened.resumableOnDevice())

        reopened.recordApkRevisionTransition("rev-new")
        reopened.begin(LabDecision(c2, null, "new apk resumed attempt"), "rev-new").apply {
            result(LabVerdict.OBSERVED, "ok")
        }

        val history = reopened.history()
        assertEquals(2, history.size)
        assertEquals(listOf(LabVerdict.OBSERVED, LabVerdict.OBSERVED), history.map { it.verdict })
        val runJson = org.json.JSONObject(File(reopened.directory, "run.json").readText())
        assertEquals("rev-new", runJson.getString("latestApkSourceRevision"))
        assertEquals(2, runJson.getJSONArray("revisionHistory").length())
    }

    @Test
    fun faultIsolationCatchesLinkageErrorRecordsEvidenceAndAllowsSubsequentExperiments() {
        val root = folder.newFolder()
        val journal = LabJournal.create(root, "rev-test")
        val kb = LabKnowledgeBase.loadOrCreate(journal.directory)

        val failingCase = LabCase("0", LabPath.SOURCE_FORMAT_PROBE, 3840, 2160, 30, listed = false)
        val attempt1 = journal.begin(LabDecision(failingCase, null, "probe broken field"), "rev-test")
        val outcome1 = executeIsolatedAttempt(attempt1, failingCase, kb) {
            throw NoSuchFieldError("COLOR_CORRECTION_AVAILABLE_MODES")
        }
        assertEquals(AttemptOutcome.Completed, outcome1) // Lab must continue to independent experiments!

        val independentCase = LabCase("0", LabPath.CODEC_SYNTHETIC_MATRIX, 3840, 2160, 30, listed = false)
        val attempt2 = journal.begin(LabDecision(independentCase, null, "independent codec probe"), "rev-test")
        val outcome2 = executeIsolatedAttempt(attempt2, independentCase, kb) {
            LabVerdict.OBSERVED to "Independent probe passed"
        }
        assertEquals(AttemptOutcome.Completed, outcome2)

        val history = journal.history()
        assertEquals(listOf(LabVerdict.FAILED, LabVerdict.OBSERVED), history.map { it.verdict })
        assertEquals(1, kb.recordedErrors.size)
        assertEquals("java.lang.NoSuchFieldError", kb.recordedErrors.single().errorClass)
        assertEquals("SOURCE_FORMAT_PROBE", kb.recordedErrors.single().scope)
    }

    @Test
    fun inventoryRunsSafelyOnSdk31AndIsolatesSectionFailures() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(CameraManager::class.java)
        val chars = ShadowCameraCharacteristics.newCameraCharacteristics()
        val shadowChars = shadowOf(chars)
        val streamMap = org.robolectric.shadow.api.Shadow.newInstanceOf(android.hardware.camera2.params.StreamConfigurationMap::class.java)
        shadowChars.set(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP, streamMap)
        shadowOf(manager as CameraManager).addCamera("0", chars)

        val descriptor = Camera2CameraDescriptor(
            cameraId = "0",
            lensFacing = CameraCharacteristics.LENS_FACING_BACK,
            focalLengthsMm = listOf(4.2f),
            previewSize = Size(1920, 1080),
            jpegSize = Size(4080, 3060),
            rawSize = null,
            analysisSize = Size(640, 360),
            sensorOrientation = 90,
            sensitivityRange = Range(100, 3200),
            exposureTimeRangeNs = Range(100_000L, 1_000_000_000L),
            aeCompensationRange = Range(0, 0),
            aeCompensationStep = 0.33f,
            minimumFocusDistance = 0f,
            supportsRaw = false,
            flashAvailable = true,
            targetFpsRanges = emptyList(),
            availableFixedFps = emptyList(),
            videoProfiles = emptyList(),
            logProfiles = emptyList(),
        )
        val profiles = LabProfiles(manager, descriptor)
        val inv = profiles.inventory(listOf(descriptor)) { section ->
            if (section == "colorCorrectionDiscovery") throw NoSuchFieldError("COLOR_CORRECTION_AVAILABLE_MODES")
        }
        assertEquals(31, inv.getInt("sdkInt"))
        assertFalse(inv.getBoolean("hlg10Advertised"))
        val errorsArr = inv.getJSONArray("inventoryErrors")
        val sections = (0 until errorsArr.length()).map { errorsArr.getJSONObject(it).getString("section") }
        assertTrue("colorCorrectionDiscovery" in sections)
        assertNotNull(inv.getJSONObject("dynamicRangeDiscovery"))
    }
}
