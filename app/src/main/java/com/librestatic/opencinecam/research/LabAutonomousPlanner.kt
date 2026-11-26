/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

/**
 * Staged autonomous planner for cinematic 4K30/4K60 LOG discovery and sustained direct 4K60 checks.
 * Delegates fallback baselines to [LabSearch] once prioritized stages finish.
 */
internal class LabAutonomousPlanner(
    private val candidates: List<LabCase>,
    private val knowledge: LabKnowledgeBase,
) {
    private val fallbackSearch = LabSearch(candidates)

    fun remainingPlannedCount(history: List<LabResult>, skippedKeys: Set<String> = emptySet()): Int {
        val simHistory = history.toMutableList()
        val localSkipped = skippedKeys.toMutableSet()
        var count = 0
        val guard = candidates.size + 64
        while (count < guard) {
            val step = next(simHistory, localSkipped) ?: break
            count++
            simHistory += LabResult(step.attempt, LabVerdict.MISMATCH, LabStage.FILE, "plan_sim_$count")
            localSkipped += step.attempt.key
        }
        return count
    }

    fun next(history: List<LabResult>, skippedKeys: Set<String> = emptySet()): LabDecision? {
        val seen = history.map { it.attempt.key }.toSet() + skippedKeys

        // 0. Bound to 1 retry on inconclusive/borderline/interrupted prioritized probes to verify reproducibility or catch contradictions.
        findPendingRetry(history, seen)?.let { return it }

        // Stage A: Camera-closed synthetic HEVC Main8/Main10 + ByteBuffer 10-bit probe.
        candidates.firstOrNull { it.path == LabPath.CODEC_SYNTHETIC_MATRIX && it.key !in seen }?.let {
            return LabDecision(it, null, "Stage A (H-02/H-03/H-12): synthetic EGL Main8/Main10 encoder, geometry & 10-bit ramp/ByteBuffer isolation.")
        }

        // Stage B: 4K30 regular-session OCLog2 (default, calibrated gamma 2.0/2.2/1.8, FLAT) + GPU HEVC control.
        val log4k30 = candidates.firstOrNull {
            it.path == LabPath.LOG_REGULAR_4K30 && !it.flatToneMap && !it.longer && !it.previewOnly && it.variant.isBlank()
        }
        if (log4k30 != null && log4k30.key !in seen) {
            return LabDecision(log4k30, null, "Stage B (H-04): live 4K30 regular-session OCLog2 Main10 using synthetic recipe.")
        }
        val log4k30Result = history.lastOrNull { it.attempt.path == LabPath.LOG_REGULAR_4K30 && !it.attempt.flatToneMap && !it.attempt.longer && it.attempt.variant.isBlank() }
        if (log4k30Result?.verdict == LabVerdict.OBSERVED) {
            for ((variantKey, hyp) in listOf(
                "gamma_2_0" to "Stage B (H-04/H-07): 4K30 OCLog2 with TONEMAP_MODE_GAMMA_VALUE=2.0 unclipped headroom.",
                "gamma_2_2" to "Stage B (H-04/H-07): 4K30 OCLog2 with TONEMAP_MODE_GAMMA_VALUE=2.2 highlight reference.",
                "gamma_1_8" to "Stage B (H-04/H-07): 4K30 OCLog2 with TONEMAP_MODE_GAMMA_VALUE=1.8 midtone test.",
            )) {
                candidates.firstOrNull {
                    it.path == LabPath.LOG_REGULAR_4K30 && !it.flatToneMap && !it.longer && !it.previewOnly && it.variant == variantKey && it.key !in seen
                }?.let { gammaCase ->
                    return LabDecision(gammaCase, log4k30Result.id, hyp)
                }
            }
            candidates.firstOrNull {
                it.path == LabPath.LOG_REGULAR_4K30 && it.flatToneMap && !it.longer && !it.previewOnly && it.variant.isBlank() && it.key !in seen
            }?.let { flatCase ->
                return LabDecision(flatCase, log4k30Result.id, "Stage B (H-04/H-07): 4K30 OCLog2 with TONEMAP_MODE_CONTRAST_CURVE FLAT.")
            }
        }
        candidates.firstOrNull {
            it.path == LabPath.GPU_HEVC_4K30 && !it.longer && !it.previewOnly && it.variant.isBlank() && it.key !in seen
        }?.let { gpu30 ->
            return LabDecision(gpu30, log4k30Result?.id, "Stage B: regular-session 4K30 GPU HEVC Main8 control.")
        }

        // Stage C: Escalate to 4K60 SurfaceTexture drain-only check, 4K60 OCLog2 & 4K60 GPU HEVC.
        val log30Ok = history.any { it.attempt.path == LabPath.LOG_REGULAR_4K30 && it.verdict == LabVerdict.OBSERVED }
        val gpu30Ok = history.any { it.attempt.path == LabPath.GPU_HEVC_4K30 && it.verdict == LabVerdict.OBSERVED }
        val synth60Main10Ok = knowledge.recipes.any {
            it.fps >= 60 && it.profile == android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
        }
        val synth60AnyOk = knowledge.recipes.any { it.fps >= 60 }
        if (log30Ok || synth60Main10Ok) {
            candidates.firstOrNull {
                it.path == LabPath.LOG_REGULAR_4K60 && it.previewOnly && !it.longer && it.variant.isBlank() && it.key !in seen
            }?.let { previewOnly60 ->
                return LabDecision(previewOnly60, log4k30Result?.id, "Stage C (H-11): 4K60 Camera2->SurfaceTexture drain-only control (encoder idle) to isolate 48 fps metronome.")
            }
            candidates.firstOrNull {
                it.path == LabPath.LOG_REGULAR_4K60 && !it.longer && !it.previewOnly && it.variant.isBlank() && it.key !in seen
            }?.let { log60 ->
                return LabDecision(log60, log4k30Result?.id, "Stage C (H-05): escalate 4K30 success to 4K60 regular-session OCLog2.")
            }
        }
        val log60BaseResult = history.lastOrNull {
            it.attempt.path == LabPath.LOG_REGULAR_4K60 && !it.attempt.longer && !it.attempt.previewOnly && it.attempt.variant.isBlank()
        }
        val log60AnyObserved = history.firstOrNull {
            it.attempt.path == LabPath.LOG_REGULAR_4K60 && !it.attempt.longer && !it.attempt.previewOnly && it.verdict == LabVerdict.OBSERVED
        }
        if (log60BaseResult != null && log60BaseResult.verdict != LabVerdict.OBSERVED && log60AnyObserved == null) {
            for ((variantKey, hypDesc) in listOf(
                "realtime" to "Stage C (H-05): enable KEY_PRIORITY=0 & KEY_OPERATING_RATE=60 on 4K60 OCLog2.",
                "single_ctx" to "Stage C (H-05): eliminate dual-EGLContext flush via single 8-bit context on 4K60 OCLog2.",
                "prev_30fps" to "Stage C (H-05): decimate preview draw/swap to 30 fps while encoding 4K60 OCLog2.",
                "no_preview" to "Stage C (H-05): suspend preview swap during recording to isolate 4K60 encoder headroom.",
                "bitrate_45m" to "Stage C (H-05): lower target bitrate to 45 Mbps with single-ctx & 30 fps preview.",
                "egl8" to "Stage C (H-05): isolate 8-bit EGL target vs Main10 codec on 4K60 OCLog2.",
                "novui" to "Stage C (H-05): isolate H.265 SPS VUI rewriting on 4K60 OCLog2.",
            )) {
                candidates.firstOrNull {
                    it.path == LabPath.LOG_REGULAR_4K60 && !it.longer && !it.previewOnly && it.variant == variantKey && it.key !in seen
                }?.let { variantCase ->
                    return LabDecision(variantCase, log60BaseResult.id, hypDesc)
                }
            }
        }
        val log60Result = history.lastOrNull { it.attempt.path == LabPath.LOG_REGULAR_4K60 && !it.attempt.longer && !it.attempt.previewOnly }
        if (log60AnyObserved != null) {
            val longLog60 = log60AnyObserved.attempt.copy(longer = true, retryIndex = 0)
            if (longLog60.key !in seen) {
                return LabDecision(longLog60, log60AnyObserved.id, "Stage C (H-05): extend verified 4K60 OCLog2 (${log60AnyObserved.attempt.variant.ifBlank { "default" }}) to 10 s window.")
            }
        }
        if (gpu30Ok || log30Ok || synth60AnyOk) {
            candidates.firstOrNull {
                it.path == LabPath.GPU_HEVC_4K60 && !it.longer && !it.previewOnly && it.variant.isBlank() && it.key !in seen
            }?.let { gpu60 ->
                return LabDecision(gpu60, log60Result?.id, "Stage C: test 4K60 GPU HEVC Main8 on regular session.")
            }
            val gpu60Base = history.lastOrNull { it.attempt.path == LabPath.GPU_HEVC_4K60 && !it.attempt.longer && it.attempt.variant.isBlank() }
            if (gpu60Base != null && gpu60Base.verdict != LabVerdict.OBSERVED) {
                candidates.firstOrNull {
                    it.path == LabPath.GPU_HEVC_4K60 && !it.longer && !it.previewOnly && it.variant == "prev_30fps" && it.key !in seen
                }?.let { gpu60Opt ->
                    return LabDecision(gpu60Opt, gpu60Base.id, "Stage C: apply single-ctx + realtime codec + 30 fps preview on 4K60 GPU HEVC.")
                }
            }
        }
        if (log60AnyObserved != null || history.any { it.attempt.path == LabPath.GPU_HEVC_4K60 && it.verdict == LabVerdict.OBSERVED }) {
            candidates.firstOrNull {
                (it.path == LabPath.LOG_REGULAR_4K60 || it.path == LabPath.GPU_HEVC_4K60) &&
                    !it.longer && !it.previewOnly && it.variant == "scopes_pbo" && it.key !in seen
            }?.let { pboCase ->
                return LabDecision(pboCase, log60Result?.id, "Stage C: verify async PBO scope readback at 4K60 without GPU stall.")
            }
        }

        // Stage D: Sustained direct 4K60 HEVC + live SurfaceTexture preview (720p & 360p reference + single-target control).
        val directStShort = candidates.firstOrNull {
            it.path == LabPath.DIRECT_HEVC_SURFACETEXTURE && !it.longer && !it.previewOnly
        }
        if (directStShort != null && directStShort.key !in seen) {
            return LabDecision(directStShort, null, "Stage D (H-06): 4K60 direct HEVC with live GL SurfaceTexture preview.")
        }
        val directStShortResult = history.lastOrNull { it.attempt.path == LabPath.DIRECT_HEVC_SURFACETEXTURE && !it.attempt.longer }
        if (directStShortResult?.verdict == LabVerdict.OBSERVED) {
            candidates.firstOrNull {
                it.path == LabPath.DIRECT_HEVC_SURFACETEXTURE && it.longer && it.key !in seen
            }?.let { sustained ->
                return LabDecision(sustained, directStShortResult.id, "Stage D (H-06): escalate SurfaceTexture direct 4K60 to 30 s sustained.")
            }
        }
        if (directStShortResult != null && directStShortResult.verdict != LabVerdict.OBSERVED) {
            candidates.firstOrNull {
                it.path == LabPath.DIRECT_HEVC_SURFACETEXTURE_360P && !it.longer && !it.previewOnly && it.key !in seen
            }?.let { st360 ->
                return LabDecision(st360, directStShortResult.id, "Stage D (H-06): drop SurfaceTexture preview to 640x360 to test 4K60 HAL drop relief.")
            }
        }
        val st360Result = history.lastOrNull { it.attempt.path == LabPath.DIRECT_HEVC_SURFACETEXTURE_360P && !it.attempt.longer }
        if (st360Result?.verdict == LabVerdict.OBSERVED) {
            candidates.firstOrNull {
                it.path == LabPath.DIRECT_HEVC_SURFACETEXTURE_360P && it.longer && it.key !in seen
            }?.let { sustained360 ->
                return LabDecision(sustained360, st360Result.id, "Stage D (H-06): escalate 360p SurfaceTexture direct 4K60 to 30 s sustained.")
            }
        }
        val directFallback = candidates.firstOrNull { it.path == LabPath.DIRECT_HEVC && it.longer && it.key !in seen }
        if (directStShortResult?.verdict != LabVerdict.OBSERVED && directFallback != null) {
            return LabDecision(directFallback, directStShortResult?.id, "Stage D (H-06): 30 s sustained single-target direct 4K60 fallback.")
        }

        // Stage E: ISP ToneMap A..E, ColorCorrection/Gamma sweep, Source Format Probe, and CUVA HDR Vivid Probe.
        candidates.firstOrNull { it.path == LabPath.ISP_TONEMAP_AB && it.key !in seen }?.let {
            return LabDecision(it, null, "Stage E (H-07): 4K30 YUV ISP ToneMap A..E (default, FLAT, rolloffs, -1 EV).")
        }
        candidates.firstOrNull { it.path == LabPath.ISP_COLOR_CORRECTION_PROBE && it.key !in seen }?.let {
            return LabDecision(it, null, "Stage E (H-07): empirical ISP colorCorrection & gamma 2.6..1.0 sweep on API ${android.os.Build.VERSION.SDK_INT}.")
        }
        candidates.firstOrNull { it.path == LabPath.SOURCE_FORMAT_PROBE && it.key !in seen }?.let {
            return LabDecision(it, null, "Stage E (H-08/H-09): probe physical/logical RAW_SENSOR, RAW10, P010 & vendor keys.")
        }
        candidates.firstOrNull { it.path == LabPath.ISP_CUVA_HDR_PROBE && it.key !in seen }?.let {
            return LabDecision(it, null, "Stage E (H-09): probe com.huawei.capture.metadata.cuvaStateVideoMode ByteArray states 0/1/2 & HDR buffers.")
        }

        // Stage F: Tiered historical audit — run codec level pair, HFR, RAW, primary AVC/HEVC controls without redundant HEVC missing-level spam.
        val redundantPathsToPrune = buildSet<String> {
            candidates.forEach { candidate ->
                val isRedundantMissingLevelHevc = candidate.path == LabPath.HEVC &&
                    (candidate.previewOnly || candidate.longer || candidate.width < 3840 || candidate.fps != 30)
                val isRedundantPreviewOnlyDuplicate = candidate.path == LabPath.AVC && candidate.previewOnly && candidate.fps < 60
                val isRedundantLongerSmall = candidate.longer && candidate.path == LabPath.AVC && candidate.width < 3840 && candidate.fps != 60
                if (isRedundantMissingLevelHevc || isRedundantPreviewOnlyDuplicate || isRedundantLongerSmall) add(candidate.key)
            }
        }
        val filteredHistory = history + (skippedKeys + redundantPathsToPrune).mapNotNull { key ->
            candidates.firstOrNull { it.key == key }?.let { LabResult(it, LabVerdict.STOPPED, LabStage.REQUESTED, "pruned") }
        }
        return fallbackSearch.next(filteredHistory)
    }

    private fun findPendingRetry(history: List<LabResult>, seen: Set<String>): LabDecision? {
        val retryablePaths = setOf(
            LabPath.CODEC_SYNTHETIC_MATRIX,
            LabPath.LOG_REGULAR_4K30,
            LabPath.LOG_REGULAR_4K60,
            LabPath.GPU_HEVC_4K30,
            LabPath.GPU_HEVC_4K60,
            LabPath.DIRECT_HEVC_SURFACETEXTURE,
            LabPath.DIRECT_HEVC_SURFACETEXTURE_360P,
        )
        val candidate = history.lastOrNull { res ->
            val retryWarranted = res.verdict in setOf(LabVerdict.REVIEW, LabVerdict.INTERRUPTED) ||
                (res.verdict == LabVerdict.MISMATCH && res.attempt.variant.isBlank() &&
                    res.attempt.path in setOf(
                        LabPath.LOG_REGULAR_4K30,
                        LabPath.DIRECT_HEVC_SURFACETEXTURE,
                        LabPath.DIRECT_HEVC_SURFACETEXTURE_360P,
                    ) && !res.attempt.longer)
            res.attempt.retryIndex == 0 &&
                res.attempt.path in retryablePaths &&
                retryWarranted &&
                res.attempt.copy(retryIndex = 1).key !in seen
        } ?: return null
        val retryAttempt = candidate.attempt.copy(retryIndex = 1)
        if (retryAttempt.key in seen) return null
        return LabDecision(
            retryAttempt,
            candidate.id,
            "Retry (inconclusive/contradiction check after ${candidate.verdict.name}) on ${candidate.attempt.path.name}.",
        )
    }

    fun stageLabel(case: LabCase): String = when (case.path) {
        LabPath.CODEC_SYNTHETIC_MATRIX -> "Stage A · Synthetic Encoder Matrix"
        LabPath.LOG_REGULAR_4K30, LabPath.GPU_HEVC_4K30 -> "Stage B · 4K30 LOG & GPU HEVC"
        LabPath.LOG_REGULAR_4K60, LabPath.GPU_HEVC_4K60 -> "Stage C · 4K60 LOG Escalation"
        LabPath.DIRECT_HEVC, LabPath.DIRECT_HEVC_PREVIEW, LabPath.DIRECT_HEVC_SURFACETEXTURE, LabPath.DIRECT_HEVC_SURFACETEXTURE_360P ->
            "Stage D · Direct 4K60 Sustained"
        LabPath.ISP_TONEMAP_AB, LabPath.ISP_COLOR_CORRECTION_PROBE, LabPath.ISP_CUVA_HDR_PROBE, LabPath.SOURCE_FORMAT_PROBE ->
            "Stage E · ISP & Source Probe"
        LabPath.CODEC_LEVEL_PAIR -> "Stage F · Codec Level Control"
        else -> if (case.hfr) "Stage F · HFR Control" else "Stage F · Baseline"
    }
}
