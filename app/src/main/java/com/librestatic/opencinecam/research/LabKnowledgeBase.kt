/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import android.util.AtomicFile
import com.librestatic.opencinecam.camera.ResearchEncoderOverride
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal enum class HypothesisStatus { UNTESTED, CONFIRMED, REFUTED, INCONCLUSIVE, CONTRADICTED }

internal data class HypothesisEntry(
    val id: String,
    val statement: String,
    val status: HypothesisStatus = HypothesisStatus.UNTESTED,
    val evidenceAttempts: List<String> = emptyList(),
    val summary: String? = null,
) {
    fun toJson(): JSONObject = json(
        "id" to id,
        "statement" to statement,
        "status" to status.name,
        "evidenceAttempts" to JSONArray(evidenceAttempts),
        "summary" to summary,
    )
}

internal data class LearnedEncoderRecipe(
    val armId: String,
    val fps: Int,
    val profile: Int,
    val explicitHevcLevel: Int?,
    val colorStandard: Int?,
    val colorRange: Int?,
    val colorTransfer: Int?,
    val forceEgl8BitForMain10: Boolean,
    val omitEglBt2020ColorSpace: Boolean,
    val rampEffectiveBits: Int?,
    val geometryClean: Boolean?,
    val attemptId: String,
) {
    fun toResearchOverride(allowAvcFallback: Boolean = false): ResearchEncoderOverride = ResearchEncoderOverride(
        explicitHevcLevel = explicitHevcLevel,
        colorStandard = colorStandard,
        colorRange = colorRange,
        colorTransfer = colorTransfer,
        forceEgl8BitForMain10 = forceEgl8BitForMain10,
        omitEglBt2020ColorSpace = omitEglBt2020ColorSpace,
        allowAvcFallback = allowAvcFallback,
    )

    fun toJson(): JSONObject = json(
        "armId" to armId,
        "fps" to fps,
        "profile" to profile,
        "explicitHevcLevel" to explicitHevcLevel,
        "colorStandard" to colorStandard,
        "colorRange" to colorRange,
        "colorTransfer" to colorTransfer,
        "forceEgl8BitForMain10" to forceEgl8BitForMain10,
        "omitEglBt2020ColorSpace" to omitEglBt2020ColorSpace,
        "rampEffectiveBits" to rampEffectiveBits,
        "geometryClean" to geometryClean,
        "attemptId" to attemptId,
    )

    companion object {
        fun fromJson(obj: JSONObject): LearnedEncoderRecipe = LearnedEncoderRecipe(
            armId = obj.optString("armId"),
            fps = obj.optInt("fps", 30),
            profile = obj.optInt("profile", CodecProfileLevel.HEVCProfileMain10),
            explicitHevcLevel = obj.opt("explicitHevcLevel").let { it as? Number }?.toInt(),
            colorStandard = obj.opt("colorStandard").let { it as? Number }?.toInt(),
            colorRange = obj.opt("colorRange").let { it as? Number }?.toInt(),
            colorTransfer = obj.opt("colorTransfer").let { it as? Number }?.toInt(),
            forceEgl8BitForMain10 = obj.optBoolean("forceEgl8BitForMain10", false),
            omitEglBt2020ColorSpace = obj.optBoolean("omitEglBt2020ColorSpace", false),
            rampEffectiveBits = obj.opt("rampEffectiveBits").let { it as? Number }?.toInt(),
            geometryClean = if (obj.isNull("geometryClean")) null else obj.optBoolean("geometryClean"),
            attemptId = obj.optString("attemptId"),
        )
    }
}

internal data class ContradictionRecord(
    val topic: String,
    val attemptIds: List<String>,
    val description: String,
) {
    fun toJson(): JSONObject = json(
        "topic" to topic,
        "attemptIds" to JSONArray(attemptIds),
        "description" to description,
    )
}

/**
 * Persistent cumulative knowledge base inside the active run directory (`knowledge.json`).
 * Tracks falsifiable hypotheses (H-01..H-10), learned MediaCodec/EGL recipes, contradictions
 * across retries or conflicting observations, and strict source-precision provenance.
 */
internal class LabKnowledgeBase private constructor(private val file: File) {
    private val hypotheses = LinkedHashMap<String, HypothesisEntry>()
    val recipes = mutableListOf<LearnedEncoderRecipe>()
    val contradictions = mutableListOf<ContradictionRecord>()
    private val caseVerdicts = LinkedHashMap<String, MutableList<Pair<String, LabVerdict>>>()

    var hlg10Advertised: Boolean? = null
        private set
    var cameraKitPresent: Boolean? = null
        private set
    var bestCodedBitDepth: Int? = null
        private set
    var bestSourcePrecision: String = "UNVERIFIED"
        private set
    var trueDynamicRangeVerified: Boolean = false
        private set
    var bestLogRecipeSummary: String? = null
        private set
    var bestDirect4k60Summary: String? = null
        private set
    var toneMapFlatVerdict: String? = null
        private set
    var highPrecisionSourceAdmitted: Boolean? = null
        private set

    init {
        DEFAULT_HYPOTHESES.forEach { hypotheses[it.id] = it }
    }

    fun hypothesesList(): List<HypothesisEntry> = hypotheses.values.toList()
    fun hypothesis(id: String): HypothesisEntry? = hypotheses[id]
    fun confirmedCount(): Int = hypotheses.values.count { it.status == HypothesisStatus.CONFIRMED }
    fun totalHypotheses(): Int = hypotheses.size

    fun bestOverrideFor(width: Int, height: Int, fps: Int, main10: Boolean = true): ResearchEncoderOverride? {
        val targetProfile = if (main10) CodecProfileLevel.HEVCProfileMain10 else CodecProfileLevel.HEVCProfileMain
        val profileMatches = recipes.filter { it.profile == targetProfile && it.geometryClean != false }
        val exact = profileMatches.filter { it.fps == fps }
            .maxByOrNull { (it.rampEffectiveBits ?: 0) * 10 + if (!it.forceEgl8BitForMain10) 2 else 0 }
        val fallback = profileMatches.maxByOrNull { (it.rampEffectiveBits ?: 0) * 10 + if (!it.forceEgl8BitForMain10) 2 else 0 }
        val chosen = exact ?: fallback ?: return null
        val adjusted = chosen.toResearchOverride(allowAvcFallback = false)
        return if (fps >= 60 && width >= 3840) {
            adjusted.copy(explicitHevcLevel = adjusted.explicitHevcLevel ?: CodecProfileLevel.HEVCMainTierLevel52)
        } else {
            adjusted
        }
    }

    fun ingestInventory(inv: JSONObject) {
        if (inv.has("hlg10Advertised")) hlg10Advertised = inv.optBoolean("hlg10Advertised")
        if (inv.has("huaweiCameraKitClassPresent")) {
            val present = inv.optBoolean("huaweiCameraKitClassPresent")
            cameraKitPresent = present
            val vendorKeys = inv.optJSONObject("vendorKeys")
            val reqCount = vendorKeys?.optJSONArray("request")?.length() ?: 0
            val charCount = vendorKeys?.optJSONArray("characteristics")?.length() ?: 0
            updateHypothesis(
                id = "H-09",
                status = if (present || reqCount > 0 || charCount > 0) HypothesisStatus.CONFIRMED else HypothesisStatus.INCONCLUSIVE,
                attemptId = "inventory",
                summary = "CameraKit=$present, vendorCharKeys=$charCount, vendorReqKeys=$reqCount",
            )
        }
        save()
    }

    fun ingestAttempt(
        attemptId: String,
        case: LabCase,
        verdict: LabVerdict,
        reason: String?,
        events: List<JSONObject>,
    ) {
        val baseKey = "${case.camera}/${case.path.name}/${case.width}x${case.height}/${case.fps}/${case.hfr}/${case.longer}/${case.flatToneMap}"
        val prior = caseVerdicts.getOrPut(baseKey) { mutableListOf() }
        if (prior.isNotEmpty()) {
            val prev = prior.first()
            val prevSuccess = prev.second == LabVerdict.OBSERVED
            val curSuccess = verdict == LabVerdict.OBSERVED
            if (prevSuccess != curSuccess && verdict != LabVerdict.INTERRUPTED && verdict != LabVerdict.STOPPED) {
                contradictions += ContradictionRecord(
                    topic = baseKey,
                    attemptIds = listOf(prev.first, attemptId),
                    description = "${prev.first}=${prev.second.name} conflicted with $attemptId=${verdict.name} (${reason ?: "no reason"})",
                )
            }
        }
        prior += attemptId to verdict

        when (case.path) {
            LabPath.CODEC_LEVEL_PAIR -> ingestCodecLevelPair(attemptId, verdict, events)
            LabPath.CODEC_SYNTHETIC_MATRIX -> ingestSyntheticMatrix(attemptId, verdict, events)
            LabPath.LOG_REGULAR_4K30, LabPath.LOG_REGULAR_4K60 -> ingestRegularLog(attemptId, case, verdict, reason, events)
            LabPath.GPU_HEVC_4K30, LabPath.GPU_HEVC_4K60 -> ingestGpuHevc(attemptId, case, verdict, reason, events)
            LabPath.DIRECT_HEVC, LabPath.DIRECT_HEVC_PREVIEW, LabPath.DIRECT_HEVC_SURFACETEXTURE ->
                ingestDirect4k60(attemptId, case, verdict, reason, events)
            LabPath.ISP_TONEMAP_AB -> ingestToneMapAb(attemptId, verdict, events)
            LabPath.SOURCE_FORMAT_PROBE -> ingestSourceProbe(attemptId, verdict, events)
            LabPath.LOG -> if (case.hfr) ingestHfrLog(attemptId, case, verdict, reason)
            else -> Unit
        }
        save()
    }

    private fun ingestCodecLevelPair(attemptId: String, verdict: LabVerdict, events: List<JSONObject>) {
        val pairs = events.filter { it.optString("event") == "codec_level_probe" }
        val withoutLevel = pairs.firstOrNull { it.isNull("level") }?.optBoolean("admitted", false)
        val withLevel = pairs.firstOrNull { !it.isNull("level") }?.optBoolean("admitted", false)
        val status = when {
            withoutLevel == false && withLevel == true -> HypothesisStatus.CONFIRMED
            withoutLevel == true && withLevel == true -> HypothesisStatus.REFUTED
            verdict == LabVerdict.FAILED -> HypothesisStatus.REFUTED
            else -> HypothesisStatus.INCONCLUSIVE
        }
        updateHypothesis("H-01", status, attemptId, "noLevelAdmitted=$withoutLevel, explicitLevelAdmitted=$withLevel")
    }

    private fun ingestSyntheticMatrix(attemptId: String, verdict: LabVerdict, events: List<JSONObject>) {
        val arms = events.filter { it.optString("event") == "synthetic_encoder_arm" }
        val main10Ok = arms.filter {
            it.optBoolean("encodedOk", false) && it.optInt("profile") == CodecProfileLevel.HEVCProfileMain10
        }
        val distortedMain10 = main10Ok.any { it.optString("syntheticLayoutVerdict").startsWith("REPEAT_HALF") }
        val cleanMain10 = main10Ok.filter {
            val v = it.optString("syntheticLayoutVerdict")
            v == "PASS" || v == "NO_REPEAT_FLAG"
        }
        val cleanMain8 = arms.filter { arm ->
            arm.optBoolean("encodedOk", false) &&
                arm.optInt("profile") == CodecProfileLevel.HEVCProfileMain &&
                arm.optString("syntheticLayoutVerdict").let { it == "PASS" || it == "NO_REPEAT_FLAG" }
        }
        (cleanMain10 + cleanMain8).forEach { arm ->
            val recipe = LearnedEncoderRecipe(
                armId = arm.optString("armId"),
                fps = arm.optInt("fps", 30),
                profile = arm.optInt("profile", CodecProfileLevel.HEVCProfileMain10),
                explicitHevcLevel = arm.opt("level").let { it as? Number }?.toInt(),
                colorStandard = arm.opt("colorStandard").let { it as? Number }?.toInt(),
                colorRange = arm.opt("colorRange").let { it as? Number }?.toInt(),
                colorTransfer = arm.opt("colorTransfer").let { it as? Number }?.toInt(),
                forceEgl8BitForMain10 = !arm.optBoolean("egl10Bit", true),
                omitEglBt2020ColorSpace = arm.isNull("eglColorSpace"),
                rampEffectiveBits = arm.opt("rampEffectiveBits").let { it as? Number }?.toInt(),
                geometryClean = true,
                attemptId = attemptId,
            )
            if (recipes.none { it.armId == recipe.armId && it.fps == recipe.fps && it.profile == recipe.profile }) {
                recipes += recipe
            }
        }
        val h2Status = when {
            distortedMain10 && cleanMain10.isEmpty() -> HypothesisStatus.REFUTED
            distortedMain10 && cleanMain10.isNotEmpty() -> HypothesisStatus.CONTRADICTED
            cleanMain10.isNotEmpty() -> HypothesisStatus.CONFIRMED
            verdict == LabVerdict.MISMATCH || verdict == LabVerdict.FAILED -> HypothesisStatus.REFUTED
            else -> HypothesisStatus.INCONCLUSIVE
        }
        updateHypothesis("H-02", h2Status, attemptId, "cleanMain10=${cleanMain10.size}, distortedMain10=$distortedMain10")

        val maxRampBits = main10Ok.mapNotNull { it.opt("rampEffectiveBits").let { v -> v as? Number }?.toInt() }.maxOrNull()
        if (maxRampBits != null) {
            bestCodedBitDepth = maxOf(bestCodedBitDepth ?: 0, 10)
            val h3Status = if (maxRampBits >= 9) HypothesisStatus.CONFIRMED else HypothesisStatus.REFUTED
            updateHypothesis("H-03", h3Status, attemptId, "maxSyntheticRampEffectiveBits=$maxRampBits")
            if (maxRampBits >= 9 && bestSourcePrecision == "UNVERIFIED") {
                bestSourcePrecision = "SYNTHETIC_10BIT_ENCODER_VERIFIED (camera source separate)"
            }
        }
    }

    private fun ingestRegularLog(
        attemptId: String,
        case: LabCase,
        verdict: LabVerdict,
        reason: String?,
        events: List<JSONObject>,
    ) {
        val fileEvent = events.lastOrNull { it.optString("stage") == LabStage.FILE.name }
        val mime = fileEvent?.optString("mime")
        val bitDepth = fileEvent?.opt("codedBitDepthFromProfile")?.let { it as? Number }?.toInt()
            ?: fileEvent?.optJSONObject("sps")?.opt("bitDepthLuma")?.let { it as? Number }?.toInt()
        val observedFps = fileEvent?.opt("observedFileFps")?.let { it as? Number }?.toDouble()
        val hypId = if (case.fps >= 60) "H-05" else "H-04"
        val isObservedHevc = verdict == LabVerdict.OBSERVED && mime == MediaFormat.MIMETYPE_VIDEO_HEVC
        val status = when {
            isObservedHevc -> HypothesisStatus.CONFIRMED
            verdict == LabVerdict.REVIEW -> HypothesisStatus.CONTRADICTED
            verdict == LabVerdict.MISMATCH || verdict == LabVerdict.FAILED -> HypothesisStatus.REFUTED
            else -> HypothesisStatus.INCONCLUSIVE
        }
        val prevStatus = hypotheses[hypId]?.status
        val finalStatus = if (prevStatus == HypothesisStatus.CONFIRMED && status == HypothesisStatus.REFUTED) {
            HypothesisStatus.CONTRADICTED
        } else status

        updateHypothesis(
            hypId,
            finalStatus,
            attemptId,
            "${case.path.name} ${case.width}x${case.height}@${case.fps}: verdict=${verdict.name}, mime=$mime, fps=$observedFps${reason?.let { ", $it" }.orEmpty()}",
        )
        if (isObservedHevc) {
            if (bitDepth != null) bestCodedBitDepth = maxOf(bestCodedBitDepth ?: 0, bitDepth)
            if (bestSourcePrecision == "UNVERIFIED" || bestSourcePrecision.startsWith("SYNTHETIC_")) {
                bestSourcePrecision = "POST_ISP_8BIT_SDR_DERIVED (regular Session SDR_BT709_ISP)"
            }
            bestLogRecipeSummary = "${case.path.name} (${case.width}x${case.height} @ ${"%.2f".format(java.util.Locale.ROOT, observedFps ?: case.fps.toDouble())} fps, flat=${case.flatToneMap}, attempt=$attemptId)"
        }
    }

    private fun ingestGpuHevc(
        attemptId: String,
        case: LabCase,
        verdict: LabVerdict,
        reason: String?,
        events: List<JSONObject>,
    ) {
        if (case.fps >= 60 && verdict == LabVerdict.OBSERVED && hypotheses["H-05"]?.status != HypothesisStatus.CONFIRMED) {
            val fileEvent = events.lastOrNull { it.optString("stage") == LabStage.FILE.name }
            updateHypothesis(
                "H-05",
                HypothesisStatus.INCONCLUSIVE,
                attemptId,
                "GPU_HEVC_4K60 Main8 succeeded (fps=${fileEvent?.opt("observedFileFps")}); Main10 LOG 4K60 still required (${reason ?: "ok"}).",
            )
        }
    }

    private fun ingestDirect4k60(
        attemptId: String,
        case: LabCase,
        verdict: LabVerdict,
        reason: String?,
        events: List<JSONObject>,
    ) {
        if (case.fps < 60) return
        val fileEvent = events.lastOrNull { it.optString("stage") == LabStage.FILE.name }
        val observedFps = fileEvent?.opt("observedFileFps")?.let { it as? Number }?.toDouble()
        val status = when {
            case.longer && verdict == LabVerdict.OBSERVED -> HypothesisStatus.CONFIRMED
            verdict == LabVerdict.OBSERVED -> HypothesisStatus.INCONCLUSIVE
            verdict == LabVerdict.MISMATCH || verdict == LabVerdict.FAILED -> HypothesisStatus.REFUTED
            else -> hypotheses["H-06"]?.status ?: HypothesisStatus.INCONCLUSIVE
        }
        val current = hypotheses["H-06"]?.status
        val combined = if (current == HypothesisStatus.CONFIRMED && status == HypothesisStatus.REFUTED) {
            HypothesisStatus.CONTRADICTED
        } else if (current == HypothesisStatus.CONFIRMED) HypothesisStatus.CONFIRMED else status

        updateHypothesis(
            "H-06",
            combined,
            attemptId,
            "${case.path.name} (longer=${case.longer}): verdict=${verdict.name}, fps=$observedFps${reason?.let { ", $it" }.orEmpty()}",
        )
        if (verdict == LabVerdict.OBSERVED) {
            bestDirect4k60Summary = "${case.path.name} (longer=${case.longer}, ${"%.3f".format(java.util.Locale.ROOT, observedFps ?: 60.0)} fps, attempt=$attemptId)"
        }
    }

    private fun ingestToneMapAb(attemptId: String, verdict: LabVerdict, events: List<JSONObject>) {
        val summary = events.lastOrNull { it.optString("event") == "isp_tonemap_ab_summary" }
        val label = summary?.optString("comparisonVerdict").orEmpty().ifBlank { verdict.name }
        toneMapFlatVerdict = label
        val status = when (label) {
            "FLAT_REDUCED_CLIPPING", "FLAT_ALTERED_RESPONSE" -> HypothesisStatus.CONFIRMED
            "TONEMAP_CONTRAST_CURVE_IGNORED_BY_HAL", "NO_MEASURABLE_DIFFERENCE" -> HypothesisStatus.REFUTED
            else -> HypothesisStatus.INCONCLUSIVE
        }
        updateHypothesis("H-07", status, attemptId, "ISP ToneMap A/B verdict: $label")
    }

    private fun ingestSourceProbe(attemptId: String, verdict: LabVerdict, events: List<JSONObject>) {
        val probe = events.lastOrNull { it.optString("event") == "source_format_probe" }
        val highPrecision = probe?.optBoolean("anyHighPrecisionSourceAdmitted", false) == true
        highPrecisionSourceAdmitted = highPrecision
        if (highPrecision) {
            trueDynamicRangeVerified = false // Admission alone != DR verified until curve measured
            bestSourcePrecision = "HIGH_PRECISION_CAMERA2_ADMITTED (DR curve pending)"
        }
        val status = when {
            highPrecision -> HypothesisStatus.CONFIRMED
            verdict == LabVerdict.OBSERVED -> HypothesisStatus.REFUTED
            else -> HypothesisStatus.INCONCLUSIVE
        }
        updateHypothesis("H-08", status, attemptId, "anyHighPrecisionSourceAdmitted=$highPrecision")
    }

    private fun ingestHfrLog(attemptId: String, case: LabCase, verdict: LabVerdict, reason: String?) {
        val status = when (verdict) {
            LabVerdict.OBSERVED -> HypothesisStatus.CONFIRMED
            LabVerdict.MISMATCH, LabVerdict.FAILED -> HypothesisStatus.REFUTED
            else -> HypothesisStatus.INCONCLUSIVE
        }
        updateHypothesis("H-10", status, attemptId, "1080p${case.fps} HFR: ${verdict.name}${reason?.let { " ($it)" }.orEmpty()}")
    }

    private fun updateHypothesis(id: String, status: HypothesisStatus, attemptId: String, summary: String) {
        val existing = hypotheses[id] ?: return
        val mergedStatus = if (existing.status == HypothesisStatus.CONTRADICTED ||
            (existing.status == HypothesisStatus.CONFIRMED && status == HypothesisStatus.REFUTED) ||
            (existing.status == HypothesisStatus.REFUTED && status == HypothesisStatus.CONFIRMED)
        ) {
            HypothesisStatus.CONTRADICTED
        } else {
            status
        }
        hypotheses[id] = existing.copy(
            status = mergedStatus,
            evidenceAttempts = (existing.evidenceAttempts + attemptId).distinct(),
            summary = summary,
        )
    }

    fun save() {
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(toJson().toString(2).toByteArray(Charsets.UTF_8))
            out.fd.sync()
            atomic.finishWrite(out)
        } catch (t: Throwable) {
            atomic.failWrite(out)
            throw t
        }
    }

    fun toJson(): JSONObject = json(
        "hlg10Advertised" to hlg10Advertised,
        "cameraKitPresent" to cameraKitPresent,
        "bestCodedBitDepth" to bestCodedBitDepth,
        "bestSourcePrecision" to bestSourcePrecision,
        "trueDynamicRangeVerified" to trueDynamicRangeVerified,
        "bestLogRecipeSummary" to bestLogRecipeSummary,
        "bestDirect4k60Summary" to bestDirect4k60Summary,
        "toneMapFlatVerdict" to toneMapFlatVerdict,
        "highPrecisionSourceAdmitted" to highPrecisionSourceAdmitted,
        "hypotheses" to JSONArray(hypotheses.values.map { it.toJson() }),
        "learnedRecipes" to JSONArray(recipes.map { it.toJson() }),
        "contradictions" to JSONArray(contradictions.map { it.toJson() }),
    )

    companion object {
        private val DEFAULT_HYPOTHESES = listOf(
            HypothesisEntry("H-01", "OMX.hisi.video.encoder.hevc requires explicit KEY_LEVEL for 4K/HFR configure"),
            HypothesisEntry("H-02", "Synthetic camera-closed HEVC Main10 4K30/4K60 encodes cleanly without repeat-half geometry"),
            HypothesisEntry("H-03", "HEVC Main10 preserves >8-bit linear ramp precision (effectiveBits >= 9, mod4 != 0)"),
            HypothesisEntry("H-04", "Regular Camera2 + OpenCineLogGpuPipeline records 4K30 OCLog2 HEVC Main10 cleanly"),
            HypothesisEntry("H-05", "Regular Camera2 + OpenCineLogGpuPipeline escalates cleanly to 4K60 OCLog2 HEVC Main10"),
            HypothesisEntry("H-06", "Direct 4K60 HEVC sustains 30 s within 1% cadence with live SurfaceTexture preview"),
            HypothesisEntry("H-07", "CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE (FLAT) measurably alters/flattens ISP output"),
            HypothesisEntry("H-08", "Public RAW_SENSOR / RAW10 / YCBCR_P010 stream is admitted on logical or physical camera"),
            HypothesisEntry("H-09", "Huawei CameraKit SDK or HDR/Log vendor keys are exposed read-only on LMR-LX9"),
            HypothesisEntry("H-10", "Constrained HFR 1080p120 OCLog2 succeeds with explicit HEVC KEY_LEVEL"),
        )

        fun loadOrCreate(directory: File): LabKnowledgeBase {
            val target = File(directory, "knowledge.json")
            val kb = LabKnowledgeBase(target)
            if (target.isFile) {
                runCatching {
                    val obj = AtomicFile(target).openRead().bufferedReader().use { JSONObject(it.readText()) }
                    kb.loadFromJson(obj)
                }
            }
            return kb
        }
    }

    private fun loadFromJson(obj: JSONObject) {
        if (!obj.isNull("hlg10Advertised")) hlg10Advertised = obj.optBoolean("hlg10Advertised")
        if (!obj.isNull("cameraKitPresent")) cameraKitPresent = obj.optBoolean("cameraKitPresent")
        if (!obj.isNull("bestCodedBitDepth")) bestCodedBitDepth = obj.optInt("bestCodedBitDepth")
        obj.optString("bestSourcePrecision").takeIf { it.isNotBlank() }?.let { bestSourcePrecision = it }
        trueDynamicRangeVerified = obj.optBoolean("trueDynamicRangeVerified", false)
        obj.optString("bestLogRecipeSummary").takeIf { it.isNotBlank() && it != "null" }?.let { bestLogRecipeSummary = it }
        obj.optString("bestDirect4k60Summary").takeIf { it.isNotBlank() && it != "null" }?.let { bestDirect4k60Summary = it }
        obj.optString("toneMapFlatVerdict").takeIf { it.isNotBlank() && it != "null" }?.let { toneMapFlatVerdict = it }
        if (!obj.isNull("highPrecisionSourceAdmitted")) highPrecisionSourceAdmitted = obj.optBoolean("highPrecisionSourceAdmitted")

        obj.optJSONArray("hypotheses")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val existing = hypotheses[id] ?: continue
                val status = runCatching { HypothesisStatus.valueOf(item.optString("status")) }
                    .getOrDefault(existing.status)
                val evArr = item.optJSONArray("evidenceAttempts")
                val evList = buildList {
                    if (evArr != null) for (j in 0 until evArr.length()) add(evArr.optString(j))
                }
                hypotheses[id] = existing.copy(
                    status = status,
                    evidenceAttempts = evList,
                    summary = item.optString("summary").takeIf { it.isNotBlank() && it != "null" },
                )
            }
        }
        obj.optJSONArray("learnedRecipes")?.let { arr ->
            recipes.clear()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { recipes += LearnedEncoderRecipe.fromJson(it) }
            }
        }
        obj.optJSONArray("contradictions")?.let { arr ->
            contradictions.clear()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val ids = buildList {
                    item.optJSONArray("attemptIds")?.let { a -> for (j in 0 until a.length()) add(a.optString(j)) }
                }
                contradictions += ContradictionRecord(
                    topic = item.optString("topic"),
                    attemptIds = ids,
                    description = item.optString("description"),
                )
            }
        }
    }
}
