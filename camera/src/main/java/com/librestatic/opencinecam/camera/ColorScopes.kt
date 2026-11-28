/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.camera

enum class FalseColorBand { BLACK, SHADOW, MID, HIGHLIGHT, CLIP }

data class ScopeThresholds(
    val black: Double = 0.05,
    val shadow: Double = 0.25,
    val highlight: Double = 0.75,
    val clip: Double = 0.95,
) {
    init { require(black in 0.0..1.0 && shadow in 0.0..1.0 && highlight in 0.0..1.0 && clip in 0.0..1.0 && black <= shadow && shadow <= highlight && highlight <= clip) }
}

data class LumaFrame(
    val width: Int,
    val height: Int,
    val luma: ByteArray,
) {
    init { require(width > 0 && height > 0 && luma.size == width * height) }
}

data class Histogram(
    val bins: IntArray,
    val totalSamples: Int,
) {
    init { require(bins.size == 256 && bins.all { it >= 0 } && totalSamples >= 0) }
}

data class ColorScopeOverlay(
    val zebra: BooleanArray,
    val falseColor: Array<FalseColorBand>,
    val thresholds: ScopeThresholds,
) {
    init { require(zebra.size == falseColor.size) }
}

sealed interface ScopeComputation {
    data class Computed(val histogram: Histogram, val overlay: ColorScopeOverlay) : ScopeComputation
    data class Rejected(val reason: String) : ScopeComputation
}

/** Pure, bounded luma analysis for histogram/zebra/false-color overlays. */
class ColorScopeComputer(
    private val maxPixels: Int = 320 * 180,
    private val thresholds: ScopeThresholds = ScopeThresholds(),
) {
    init { require(maxPixels > 0) }

    fun compute(frame: LumaFrame): ScopeComputation {
        if (frame.luma.size > maxPixels) return ScopeComputation.Rejected("Analysis frame exceeds the scope pixel budget.")
        val bins = IntArray(256)
        val zebra = BooleanArray(frame.luma.size)
        val falseColor = Array(frame.luma.size) { FalseColorBand.MID }
        frame.luma.forEachIndexed { index, valueByte ->
            val value = valueByte.toInt() and 0xff
            bins[value]++
            val normalized = value / 255.0
            zebra[index] = normalized >= thresholds.clip || normalized <= thresholds.black
            falseColor[index] = when {
                normalized <= thresholds.black -> FalseColorBand.BLACK
                normalized <= thresholds.shadow -> FalseColorBand.SHADOW
                normalized < thresholds.highlight -> FalseColorBand.MID
                normalized < thresholds.clip -> FalseColorBand.HIGHLIGHT
                else -> FalseColorBand.CLIP
            }
        }
        return ScopeComputation.Computed(Histogram(bins, frame.luma.size), ColorScopeOverlay(zebra, falseColor, thresholds))
    }
}

/**
 * Calibrated IRE/threshold anchors per signal domain so false color, zebra, and middle-grey
 * guides reflect the active encoding (BT.2020 LOG/HLG vs BT.709 SDR).
 */
object MonitoringDomainReference {
    fun defaultThresholds(domain: MonitoringSignalDomain): ScopeThresholds = when (domain) {
        MonitoringSignalDomain.OCLOG2_CODE -> ScopeThresholds(black = 0.105, shadow = 0.24, highlight = 0.78, clip = 0.895)
        MonitoringSignalDomain.HLG10_BT2020_CODE -> ScopeThresholds(black = 0.065, shadow = 0.22, highlight = 0.75, clip = 0.915)
        MonitoringSignalDomain.SDR_BT709_CODE,
        MonitoringSignalDomain.ISP_YUV_ESTIMATED_SDR -> ScopeThresholds()
    }

    /** Nominal 18% middle-grey code position [0, 1] in the active signal domain. */
    fun middleGreyCode(domain: MonitoringSignalDomain): Double = when (domain) {
        MonitoringSignalDomain.OCLOG2_CODE -> OpenCineLog2Curve.encode(0.18)
        MonitoringSignalDomain.HLG10_BT2020_CODE -> OpenCineLogGreyReference.HLG_REFERENCE_GREY_SIGNAL
        MonitoringSignalDomain.SDR_BT709_CODE,
        MonitoringSignalDomain.ISP_YUV_ESTIMATED_SDR -> 0.4090077288685503
    }

    /** Stops of diffuse highlight headroom above 18% middle grey for full-scale signal 1.0. */
    fun ettrHighlightHeadroomStops(domain: MonitoringSignalDomain): Double = when (domain) {
        MonitoringSignalDomain.OCLOG2_CODE -> 2.473931188332412
        MonitoringSignalDomain.HLG10_BT2020_CODE -> 4.376810755679564
        MonitoringSignalDomain.SDR_BT709_CODE,
        MonitoringSignalDomain.ISP_YUV_ESTIMATED_SDR -> 2.473931188332412
    }
}
