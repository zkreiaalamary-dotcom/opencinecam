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

    fun next(history: List<LabResult>): LabDecision? {
        val seen = history.map { it.attempt.key }.toSet()

        // 0. Bound to 1 retry on inconclusive/borderline/interrupted prioritized probes to verify reproducibility or catch contradictions.
        findPendingRetry(history, seen)?.let { return it }

        // Stage A: Camera-closed synthetic HEVC Main8/Main10 matrix (isolates configure, geometry, 10-bit ramp).
        candidates.firstOrNull { it.path == LabPath.CODEC_SYNTHETIC_MATRIX && it.key !in seen }?.let {
            return LabDecision(it, null, "Stage A (H-02/H-03): synthetic EGL Main8/Main10 encoder, geometry & 10-bit ramp isolation.")
        }

        // Stage B: 4K30 regular-session OCLog2 (default & FLAT tone map) + GPU HEVC control.
        val log4k30 = candidates.firstOrNull {
            it.path == LabPath.LOG_REGULAR_4K30 && !it.flatToneMap && !it.longer && !it.previewOnly
        }
        if (log4k30 != null && log4k30.key !in seen) {
            return LabDecision(log4k30, null, "Stage B (H-04): live 4K30 regular-session OCLog2 Main10 using synthetic recipe.")
        }
        val log4k30Result = history.lastOrNull { it.attempt.path == LabPath.LOG_REGULAR_4K30 && !it.attempt.flatToneMap && !it.attempt.longer }
        if (log4k30Result?.verdict == LabVerdict.OBSERVED) {
            candidates.firstOrNull {
                it.path == LabPath.LOG_REGULAR_4K30 && it.flatToneMap && !it.longer && !it.previewOnly && it.key !in seen
            }?.let { flatCase ->
                return LabDecision(flatCase, log4k30Result.id, "Stage B (H-04/H-07): 4K30 OCLog2 with TONEMAP_MODE_CONTRAST_CURVE FLAT.")
            }
        }
        candidates.firstOrNull {
            it.path == LabPath.GPU_HEVC_4K30 && !it.longer && !it.previewOnly && it.key !in seen
        }?.let { gpu30 ->
            return LabDecision(gpu30, log4k30Result?.id, "Stage B: regular-session 4K30 GPU HEVC Main8 control.")
        }

        // Stage C: Escalate to 4K60 OCLog2 & 4K60 GPU HEVC if 4K30 or synthetic 4K60 succeeded.
        val log30Ok = history.any { it.attempt.path == LabPath.LOG_REGULAR_4K30 && it.verdict == LabVerdict.OBSERVED }
        val gpu30Ok = history.any { it.attempt.path == LabPath.GPU_HEVC_4K30 && it.verdict == LabVerdict.OBSERVED }
        val synth60Main10Ok = knowledge.recipes.any {
            it.fps >= 60 && it.profile == android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
        }
        val synth60AnyOk = knowledge.recipes.any { it.fps >= 60 }
        if (log30Ok || synth60Main10Ok) {
            candidates.firstOrNull {
                it.path == LabPath.LOG_REGULAR_4K60 && !it.longer && !it.previewOnly && it.key !in seen
            }?.let { log60 ->
                return LabDecision(log60, log4k30Result?.id, "Stage C (H-05): escalate 4K30 success to 4K60 regular-session OCLog2.")
            }
        }
        val log60Result = history.lastOrNull { it.attempt.path == LabPath.LOG_REGULAR_4K60 && !it.attempt.longer }
        if (log60Result?.verdict == LabVerdict.OBSERVED) {
            val longLog60 = log60Result.attempt.copy(longer = true, retryIndex = 0)
            if (longLog60.key !in seen) {
                return LabDecision(longLog60, log60Result.id, "Stage C (H-05): extend verified 4K60 OCLog2 to 10 s window.")
            }
        }
        if (gpu30Ok || log30Ok || synth60AnyOk) {
            candidates.firstOrNull {
                it.path == LabPath.GPU_HEVC_4K60 && !it.longer && !it.previewOnly && it.key !in seen
            }?.let { gpu60 ->
                return LabDecision(gpu60, log60Result?.id, "Stage C: test 4K60 GPU HEVC Main8 on regular session.")
            }
        }

        // Stage D: Sustained direct 4K60 HEVC + live SurfaceTexture preview.
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
        val directFallback = candidates.firstOrNull { it.path == LabPath.DIRECT_HEVC && it.longer && it.key !in seen }
        if (directStShortResult?.verdict != LabVerdict.OBSERVED && directFallback != null) {
            return LabDecision(directFallback, directStShortResult?.id, "Stage D (H-06): 30 s sustained single-target direct 4K60 fallback.")
        }

        // Stage E: ISP ToneMap A/B & High-precision source/vendor-key probe.
        candidates.firstOrNull { it.path == LabPath.ISP_TONEMAP_AB && it.key !in seen }?.let {
            return LabDecision(it, null, "Stage E (H-07): 4K30 YUV ISP ToneMap A/B (default vs FLAT curve).")
        }
        candidates.firstOrNull { it.path == LabPath.SOURCE_FORMAT_PROBE && it.key !in seen }?.let {
            return LabDecision(it, null, "Stage E (H-08/H-09): probe physical/logical RAW_SENSOR, RAW10, P010 & vendor keys.")
        }

        // Stage F: Delegate remaining codec level pair, RAW, HFR 1080p120, and AVC baselines to LabSearch.
        return fallbackSearch.next(history)
    }

    private fun findPendingRetry(history: List<LabResult>, seen: Set<String>): LabDecision? {
        val retryablePaths = setOf(
            LabPath.CODEC_SYNTHETIC_MATRIX,
            LabPath.LOG_REGULAR_4K30,
            LabPath.LOG_REGULAR_4K60,
            LabPath.GPU_HEVC_4K30,
            LabPath.GPU_HEVC_4K60,
            LabPath.DIRECT_HEVC_SURFACETEXTURE,
        )
        val candidate = history.lastOrNull { res ->
            res.attempt.retryIndex == 0 &&
                res.attempt.path in retryablePaths &&
                res.verdict in setOf(LabVerdict.REVIEW, LabVerdict.INTERRUPTED, LabVerdict.MISMATCH) &&
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
        LabPath.DIRECT_HEVC, LabPath.DIRECT_HEVC_PREVIEW, LabPath.DIRECT_HEVC_SURFACETEXTURE ->
            "Stage D · Direct 4K60 Sustained"
        LabPath.ISP_TONEMAP_AB, LabPath.SOURCE_FORMAT_PROBE -> "Stage E · ISP & Source Probe"
        LabPath.CODEC_LEVEL_PAIR -> "Stage F · Codec Level Control"
        else -> if (case.hfr) "Stage F · HFR Control" else "Stage F · Baseline"
    }
}
