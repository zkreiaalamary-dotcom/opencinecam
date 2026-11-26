/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

internal enum class LabPath {
    AVC,
    HEVC,
    LOG,
    HLG,
    HDR10,
    RAW_CAMERA,
    RAW_NATIVE,
    DIRECT_HEVC,
    DIRECT_HEVC_PREVIEW,
    DIRECT_HEVC_SURFACETEXTURE,
    DIRECT_HEVC_SURFACETEXTURE_360P,
    CODEC_LEVEL_PAIR,
    CODEC_SYNTHETIC_MATRIX,
    LOG_REGULAR_4K30,
    LOG_REGULAR_4K60,
    GPU_HEVC_4K30,
    GPU_HEVC_4K60,
    ISP_TONEMAP_AB,
    ISP_COLOR_CORRECTION_PROBE,
    ISP_CUVA_HDR_PROBE,
    SOURCE_FORMAT_PROBE,
    BREAKTHROUGH_4K60_INPUT,
    BREAKTHROUGH_CUVA_VIDEO,
    BREAKTHROUGH_SYNTH_4K60_PRIORITY,
    BREAKTHROUGH_HIDDEN_RAW,
    BREAKTHROUGH_BB10_CLIPS,
}
internal enum class LabStage { REQUESTED, CAMERA, PREVIEW, FIRST_CAMERA_RESULT, RECORDER, FIRST_GPU_FRAME, FIRST_ENCODED, STOPPED, RELEASED, FILE, RAW }
internal enum class LabVerdict { OBSERVED, MISMATCH, FAILED, INTERRUPTED, STOPPED, ADMITTED, REVIEW }

internal data class LabCase(
    val camera: String,
    val path: LabPath,
    val width: Int,
    val height: Int,
    val fps: Int,
    val hfr: Boolean = false,
    val listed: Boolean = true,
    val longer: Boolean = false,
    val previewOnly: Boolean = false,
    val flatToneMap: Boolean = false,
    val retryIndex: Int = 0,
    val variant: String = "",
) {
    val key get() = "$camera/${path.name}/${width}x$height/$fps/$hfr/$longer/$previewOnly" +
        (if (flatToneMap) "/flat" else "") +
        (if (variant.isNotBlank()) "/$variant" else "") +
        (if (retryIndex > 0) "/retry$retryIndex" else "")
    val label get() = "${path.name} · ${width}×$height · $fps fps" +
        (if (hfr) " · HFR" else "") +
        (if (previewOnly) " · preview only" else "") +
        (if (flatToneMap) " · FLAT" else "") +
        (if (variant.isNotBlank()) " · $variant" else "") +
        (if (longer) " · sustained" else "") +
        (if (retryIndex > 0) " · retry #$retryIndex" else "")
}

internal data class LabResult(val attempt: LabCase, val verdict: LabVerdict, val stage: LabStage, val id: String)
internal data class LabDecision(val attempt: LabCase, val parent: String?, val hypothesis: String)

/** A bounded search over admissible profiles and controlled, single-variable neighbours. */
internal class LabSearch(private val candidates: List<LabCase>) {
    fun next(history: List<LabResult>): LabDecision? {
        val seen = history.map { it.attempt.key }.toSet()
        val pending = candidates.filterNot { it.key in seen }
        // Reserve the causal controls before adaptive neighbours consume the run budget.
        pending.firstOrNull { it.path == LabPath.CODEC_SYNTHETIC_MATRIX }?.let {
            return LabDecision(it, null, "X-02/X-03: run camera-closed synthetic Main8/Main10 encoder & geometry isolation matrix.")
        }
        val direct = pending.firstOrNull { it.path == LabPath.DIRECT_HEVC }
        if (direct != null) return LabDecision(direct, null, "X-01: measure the regular recorder-only UHD60 graph.")
        val paired = pending.firstOrNull { it.path == LabPath.DIRECT_HEVC_PREVIEW }
        if (paired != null) {
            val control = history.lastOrNull { it.attempt == paired.copy(path = LabPath.DIRECT_HEVC) }
            return LabDecision(paired, control?.id, "X-01: add one drained 720p camera output to the same recorder configuration.")
        }
        pending.firstOrNull { it.path == LabPath.CODEC_LEVEL_PAIR }?.let {
            return LabDecision(it, null, "X-02: toggle only the explicit HEVC level on one named hardware encoder.")
        }
        val hasProbes = candidates.any { it.path == LabPath.CODEC_LEVEL_PAIR }
        if (hasProbes) {
            pending.firstOrNull { it.path == LabPath.HEVC && it.width == 3840 && it.fps == 30 && !it.longer && !it.previewOnly }
                ?.let { return LabDecision(it, null, "Trace the production HEVC configure call and verify the resulting codec.") }
            pending.firstOrNull { it.path == LabPath.LOG && it.hfr && !it.longer && !it.previewOnly }
                ?.let { return LabDecision(it, null, "Localize the SDR-ISP HFR recording preparation failure.") }
        }
        val last = history.lastOrNull {
            it.verdict != LabVerdict.INTERRUPTED && it.verdict != LabVerdict.STOPPED && !it.attempt.longer && !it.attempt.previewOnly
        }
        if (last != null) {
            neighbour(last, pending)?.let { return it }
        }
        return pending.firstOrNull { !it.longer && !it.previewOnly && it.path != LabPath.RAW_NATIVE }?.let {
            LabDecision(it, null, "Establish a baseline for this path and operating point.")
        }
    }

    private fun neighbour(last: LabResult, pending: List<LabCase>): LabDecision? {
        val a = last.attempt
        if (a.path == LabPath.RAW_CAMERA || a.path == LabPath.RAW_NATIVE) {
            if (a.path == LabPath.RAW_CAMERA && last.verdict == LabVerdict.OBSERVED) {
                return pending.firstOrNull { it == a.copy(path = LabPath.RAW_NATIVE) }?.let {
                    LabDecision(it, last.id, "Measure native block packing for the same RAW10 stream.")
                }
            }
            return null
        }
        if (last.verdict != LabVerdict.OBSERVED && a.hfr && last.stage <= LabStage.FIRST_CAMERA_RESULT) {
            // A constrained session and a regular session are different graphs.
            return null
        }
        val same = pending.filter { it.camera == a.camera && it.hfr == a.hfr && !it.longer && !it.previewOnly }
        val sameGraph = same.filter { it.path == a.path }
        val choices = buildList {
            if (last.verdict == LabVerdict.OBSERVED) {
                add(sameGraph.filter { it.width == a.width && it.height == a.height && it.fps > a.fps }
                    .minByOrNull { it.fps } to "Raise only capture FPS to find a cadence limit.")
                add(sameGraph.filter { it.fps == a.fps && it.width > a.width }
                    .minByOrNull { it.width } to "Raise only raster size at the observed cadence.")
                add(same.firstOrNull { a.path == LabPath.LOG && it.path == LabPath.HLG &&
                    it.width == a.width && it.height == a.height && it.fps == a.fps }
                    to "Keep the HLG camera graph and compare the OCLog2 and HLG output transforms.")
                add(pending.firstOrNull { !a.longer && !a.previewOnly && it == a.copy(longer = true) }
                    to "Repeat the same graph with a longer recording window.")
            } else {
                add(pending.firstOrNull { !a.previewOnly && it == a.copy(previewOnly = true) && last.stage >= LabStage.PREVIEW }
                    to "Repeat the same camera and GPU preview graph without arming the encoder.")
                add(sameGraph.filter { it.width == a.width && it.height == a.height && it.fps < a.fps }
                    .maxByOrNull { it.fps } to "Lower only capture FPS to test whether cadence caused the failure.")
                add(sameGraph.filter { it.fps == a.fps && it.width < a.width }
                    .maxByOrNull { it.width } to "Lower only raster size to test whether graph bandwidth caused the failure.")
                add(same.firstOrNull { a.path == LabPath.HEVC && it.path == LabPath.AVC &&
                    it.width == a.width && it.height == a.height && it.fps == a.fps }
                    to "Switch only the requested SDR codec from HEVC to AVC.")
            }
        }
        return choices.firstNotNullOfOrNull { (candidate, hypothesis) ->
            candidate?.let { LabDecision(it, last.id, hypothesis) }
        }
    }
}
