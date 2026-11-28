/* SPDX-License-Identifier: Apache-2.0 */

package com.librestatic.opencinecam.camera

/** New CUVA takes keep the native headroom with the existing OCLog2 transfer. */
internal fun astraRecordingGreyReference(
    requested: OpenCineLogGreyReference,
    huaweiCuvaLog: Boolean,
): OpenCineLogGreyReference =
    if (huaweiCuvaLog && requested == OpenCineLogGreyReference.MATCH_SDR) OpenCineLogGreyReference.NATIVE else requested

/** CUVA preprocessing keeps intermediate values extended until the OCLog2 transport or display transform. */
internal object AstraLogShader {
    fun hlg(shader: String, lens: Boolean, shaderYcbcr: Boolean): String {
        val signedHlg = shader
            .replaceOnce("e = clamp(e, 0.0, 1.0);", "vec3 polarity = sign(e); e = abs(e);")
            .replaceOnce("return mix(highPart, lowPart, low);", "return polarity * mix(highPart, lowPart, low);")
        if (!lens) return signedHlg

        val sample = if (shaderYcbcr) "(uYcbcrToRgb * (texture(uTexture, vTexCoord).rgb - uYcbcrOffset))"
        else "texture(uTexture, vTexCoord).rgb"
        // The lens table acts in its decoded source RGB basis, before any change of primaries.
        return signedHlg
            .replaceOnce("void main() {", LINEAR_LENS + "\nvoid main() {")
            .replaceOnce("inverseHlg($sample)", "astraLens(inverseHlg($sample))")
    }

    private fun String.replaceOnce(anchor: String, replacement: String): String {
        check(indexOf(anchor) >= 0 && indexOf(anchor) == lastIndexOf(anchor)) { "Astra GLSL anchor drifted: $anchor" }
        return replace(anchor, replacement)
    }

    private val LINEAR_LENS = """
        uniform float uLensEv;
        uniform mat3 uLensMatrix;
        uniform vec3 uLensGains;
        vec3 astraLens(vec3 lin) {
            return exp2(uLensEv) * (uLensMatrix * (uLensGains * lin));
        }
    """.trimIndent()
}
