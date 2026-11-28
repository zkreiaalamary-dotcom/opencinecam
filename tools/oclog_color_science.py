# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors
"""Independent high-precision Python reference model for OpenCineCam color science.

Implements exact IEEE-754 double-precision (and float32 GLSL parity) math for:
- ITU-R BT.2100 HLG OETF, inverse OETF, and 1000 cd/m^2 display OOTF
- ITU-R BT.709 / sRGB OETF and inverse OETF
- YCbCr <-> R'G'B' (BT.2020, BT.709, BT.601; full & limited 8/10/12/16-bit)
- Color gamut 3x3 transforms derived from chromaticities & Bradford adaptation:
  BT.709 <-> BT.2020 <-> XYZ D65 <-> DaVinci Wide Gamut (DWG) <-> ACES AP1 (D60)
- Log transfer curves: OCLog2, OCLog3 (scene-referred, 0.18->0.400), DaVinci Intermediate, ACEScct
- View-Assist Rec.709 hue-preserving shoulder + gamut-fit LUT evaluation
"""

from __future__ import annotations

import math
from typing import Callable, Iterable

Vec3 = tuple[float, float, float]
Mat3 = tuple[Vec3, Vec3, Vec3]

# BT.2100 HLG constants
HLG_A = 0.17883277
HLG_B = 1.0 - 4.0 * HLG_A  # 0.28466892
HLG_C = 0.5 - HLG_A * math.log(4.0 * HLG_A)  # 0.55991073
HLG_REFERENCE_GREY_LINEAR = (0.38 * 0.38) / 3.0  # 0.04813333333333334
TIER_GREY_RATIO = 0.18 / HLG_REFERENCE_GREY_LINEAR  # 3.739612188365651

# OCLog2 constants (spec.json v2.0.0)
OCLOG2_BASE = 50.0
OCLOG2_BLACK = 0.10
OCLOG2_WHITE = 0.90
OCLOG2_LN51 = math.log(51.0)

# OCLog3 constants (OpenCineLog3.kt v1.0.0, exact C1 join at CUT=0.008)
OCLOG3_BLACK = 0.095
OCLOG3_WHITE = 0.920
OCLOG3_MAX_LINEAR = 16.0
OCLOG3_CUT = 0.008
OCLOG3_C = 0.013131196250227292
OCLOG3_A_LN = 0.11770578833655286
OCLOG3_D = 0.5935536972214088
OCLOG3_M = 5.570237810615516
OCLOG3_CUT_CODE = OCLOG3_M * OCLOG3_CUT + OCLOG3_BLACK

# YCbCr luma weights
YCBCR_WEIGHTS = {
    "BT601": (0.299, 0.114),
    "BT709": (0.2126, 0.0722),
    "BT2020": (0.2627, 0.0593),
}

# Exact primary-derived 3x3 conversion matrices (linear RGB)
BT709_TO_BT2020: Mat3 = (
    (0.6274038959, 0.3292830384, 0.0433130657),
    (0.0690972894, 0.9195403951, 0.0113623155),
    (0.0163914389, 0.0880133079, 0.8955952532),
)

BT2020_TO_BT709: Mat3 = (
    (1.6604910021, -0.5876411388, -0.0728498633),
    (-0.1245504745, 1.1328998971, -0.0083494226),
    (-0.0181507634, -0.1005788980, 1.1187296614),
)

# Shader literals in OpenCineLogGpuPipeline.kt (float32 6-digit rounded)
GLSL_BT709_TO_BT2020: Mat3 = (
    (0.627404, 0.329283, 0.043313),
    (0.069097, 0.919540, 0.011362),
    (0.016391, 0.088013, 0.895595),
)

GLSL_BT2020_TO_BT709: Mat3 = (
    (1.660491, -0.587641, -0.072850),
    (-0.124550, 1.132900, -0.008349),
    (-0.018151, -0.100579, 1.118730),
)

# CIE XYZ D65 matrices from SMPTE RP 177 / ITU-R BT.2020 / Blackmagic DWG / ACES AP1 (Bradford D65<->D60)
BT2020_TO_XYZ_D65: Mat3 = (
    (0.6369580483, 0.1446169036, 0.1688809752),
    (0.2627002120, 0.6779980715, 0.0593017165),
    (0.0000000000, 0.0280726930, 1.0609850577),
)

XYZ_D65_TO_DWG: Mat3 = (
    (1.51667, -0.28147, -0.14696),
    (-0.46491, 1.25142, 0.17488),
    (0.06484, 0.10913, 0.76141),
)

DWG_TO_XYZ_D65: Mat3 = (
    (0.70062239, 0.14877482, 0.10105872),
    (0.27411851, 0.87363190, -0.14775041),
    (-0.09896291, -0.13789533, 1.32591599),
)

# Bradford-adapted linear BT.2020 (D65) -> linear ACEScg / AP1 (D60) and inverse
BT2020_TO_ACES_AP1: Mat3 = (
    (0.97489498, 0.01959911, 0.00550591),
    (0.00217956, 0.99553547, 0.00228497),
    (0.00479724, 0.02453202, 0.97067075),
)

ACES_AP1_TO_BT2020: Mat3 = (
    (1.02582475, -0.02005319, -0.00577156),
    (-0.00223437, 1.00458650, -0.00235213),
    (-0.00501335, -0.02529007, 1.03030342),
)


def mat3_mul(a: Mat3, b: Mat3) -> Mat3:
    return tuple(
        tuple(sum(a[r][k] * b[k][c] for k in range(3)) for c in range(3))
        for r in range(3)
    )  # type: ignore[return-value]


def mat3_vec(m: Mat3, v: Vec3) -> Vec3:
    return (
        m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
        m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
        m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2],
    )


BT2020_TO_DWG: Mat3 = mat3_mul(XYZ_D65_TO_DWG, BT2020_TO_XYZ_D65)
DWG_TO_BT2020: Mat3 = mat3_mul(
    (
        (1.7166511880, -0.3556707838, -0.2533662814),
        (-0.6666843518, 1.6164812366, 0.0157685458),
        (0.0176398574, -0.0427706133, 0.9421031212),
    ),
    DWG_TO_XYZ_D65,
)


def clamp01(x: float) -> float:
    return 0.0 if x < 0.0 else (1.0 if x > 1.0 else x)


def clamp_vec3(v: Vec3, lo: float = 0.0, hi: float = 1.0) -> Vec3:
    return (
        lo if v[0] < lo else (hi if v[0] > hi else v[0]),
        lo if v[1] < lo else (hi if v[1] > hi else v[1]),
        lo if v[2] < lo else (hi if v[2] > hi else v[2]),
    )


def hlg_oetf(linear: float) -> float:
    x = clamp01(linear)
    if x <= 1.0 / 12.0:
        return math.sqrt(3.0 * x)
    return HLG_A * math.log(max(12.0 * x - HLG_B, 1e-12)) + HLG_C


def hlg_inverse_oetf(code: float) -> float:
    e = clamp01(code)
    if e <= 0.5:
        return (e * e) / 3.0
    return (math.exp((e - HLG_C) / HLG_A) + HLG_B) / 12.0


def rec709_oetf(linear: float) -> float:
    x = clamp01(linear)
    return 4.5 * x if x < 0.018 else 1.099 * (x ** 0.45) - 0.099


def rec709_inverse_oetf(code: float) -> float:
    e = clamp01(code)
    return e / 4.5 if e < 0.081 else ((e + 0.099) / 1.099) ** (1.0 / 0.45)


def oclog2_encode(linear: float) -> float:
    x = clamp01(linear)
    return OCLOG2_BLACK + (OCLOG2_WHITE - OCLOG2_BLACK) * math.log1p(OCLOG2_BASE * x) / OCLOG2_LN51


def oclog2_decode(code: float) -> float:
    y = max(OCLOG2_BLACK, min(OCLOG2_WHITE, code))
    norm = (y - OCLOG2_BLACK) / (OCLOG2_WHITE - OCLOG2_BLACK)
    return (math.pow(1.0 + OCLOG2_BASE, norm) - 1.0) / OCLOG2_BASE


def oclog3_encode(linear: float) -> float:
    x = max(0.0, min(OCLOG3_MAX_LINEAR, linear))
    if x < OCLOG3_CUT:
        return OCLOG3_M * x + OCLOG3_BLACK
    return OCLOG3_A_LN * math.log(x + OCLOG3_C) + OCLOG3_D


def oclog3_decode(code: float) -> float:
    y = max(OCLOG3_BLACK, min(OCLOG3_WHITE, code))
    if y < OCLOG3_CUT_CODE:
        return max(0.0, (y - OCLOG3_BLACK) / OCLOG3_M)
    return max(0.0, min(OCLOG3_MAX_LINEAR, math.exp((y - OCLOG3_D) / OCLOG3_A_LN) - OCLOG3_C))


def davinci_intermediate_encode(linear: float) -> float:
    # Official Blackmagic Design DaVinci Intermediate OETF
    di_a = 0.0075
    di_b = 7.0
    di_c = 0.07329248
    di_m = 10.44426855
    di_lin_cut = 0.00262409
    x = max(0.0, linear)
    if x <= di_lin_cut:
        return x * di_m
    return (math.log2(x + di_a) + di_b) * di_c


def davinci_intermediate_decode(code: float) -> float:
    di_a = 0.0075
    di_b = 7.0
    di_c = 0.07329248
    di_m = 10.44426855
    di_log_cut = 0.02740668
    y = max(0.0, code)
    if y <= di_log_cut:
        return y / di_m
    return math.pow(2.0, (y / di_c) - di_b) - di_a


def acescct_encode(linear: float) -> float:
    # Academy S-2016-001 ACEScct
    x = max(0.0, linear)
    if x <= 0.0078125:
        return 10.5402377416545 * x + 0.0729055341958355
    return (math.log2(x) + 9.72) / 17.52


def acescct_decode(code: float) -> float:
    if code <= 0.155251141552511:
        return max(0.0, (code - 0.0729055341958355) / 10.5402377416545)
    return math.pow(2.0, code * 17.52 - 9.72)


def ycbcr_to_rgb(y: float, cb: float, cr: float, standard: str = "BT2020", full_range: bool = True, bit_depth: int = 10) -> Vec3:
    kr, kb = YCBCR_WEIGHTS[standard]
    kg = 1.0 - kr - kb
    max_code = float((1 << bit_depth) - 1)
    step = float(1 << (bit_depth - 8))
    if full_range:
        yy = y / max_code
        ccb = cb / max_code - (128.0 * step / max_code)
        ccr = cr / max_code - (128.0 * step / max_code)
    else:
        yy = (y / max_code - (16.0 * step / max_code)) * (max_code / (219.0 * step))
        ccb = (cb / max_code - (128.0 * step / max_code)) * (max_code / (224.0 * step))
        ccr = (cr / max_code - (128.0 * step / max_code)) * (max_code / (224.0 * step))
    r = yy + 2.0 * (1.0 - kr) * ccr
    g = yy - (2.0 * kb * (1.0 - kb) / kg) * ccb - (2.0 * kr * (1.0 - kr) / kg) * ccr
    b = yy + 2.0 * (1.0 - kb) * ccb
    return clamp_vec3((r, g, b), 0.0, 1.0)


def rgb_to_ycbcr(rgb: Vec3, standard: str = "BT2020", full_range: bool = True, bit_depth: int = 10) -> Vec3:
    r, g, b = clamp_vec3(rgb, 0.0, 1.0)
    kr, kb = YCBCR_WEIGHTS[standard]
    kg = 1.0 - kr - kb
    y = kr * r + kg * g + kb * b
    cb = (b - y) / (2.0 * (1.0 - kb))
    cr = (r - y) / (2.0 * (1.0 - kr))
    max_code = float((1 << bit_depth) - 1)
    step = float(1 << (bit_depth - 8))
    if full_range:
        return (
            clamp01(y) * max_code,
            clamp01(cb + (128.0 * step / max_code)) * max_code,
            clamp01(cr + (128.0 * step / max_code)) * max_code,
        )
    return (
        ((16.0 + 219.0 * clamp01(y)) * step),
        ((128.0 + 224.0 * max(-0.5, min(0.5, cb))) * step),
        ((128.0 + 224.0 * max(-0.5, min(0.5, cr))) * step),
    )


def soft_tonemap_rec709(linear_709: Vec3, gain: float = 1.0, knee: float = 0.5) -> Vec3:
    """Hue-preserving luminance shoulder + soft gamut fit matching OpenCineLogViewAssist."""
    c = [v * gain for v in linear_709]
    y = max(0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2], 1.0e-6)
    toned = y if y <= knee else knee + (1.0 - knee) * math.tanh((y - knee) / (1.0 - knee))
    scale = toned / y
    c = [v * scale for v in c]
    hi = max(c)
    lo = min(c)
    fit = 1.0
    if hi > 1.0:
        fit = min(fit, (1.0 - toned) / (hi - toned))
    if lo < 0.0:
        fit = min(fit, toned / (toned - lo))
    fitted = (toned + fit * (c[0] - toned), toned + fit * (c[1] - toned), toned + fit * (c[2] - toned))
    return (rec709_oetf(fitted[0]), rec709_oetf(fitted[1]), rec709_oetf(fitted[2]))


def eval_shader_hlg_to_oclog2(
    rgb_hlg: Vec3,
    native_bt2020: bool = True,
    scene_gain: float = 1.0,
) -> Vec3:
    """Exact mirror of HLG_FRAGMENT_SHADER (both BT709_QUIRK and BT2020_NATIVE modes)."""
    lin = (hlg_inverse_oetf(rgb_hlg[0]), hlg_inverse_oetf(rgb_hlg[1]), hlg_inverse_oetf(rgb_hlg[2]))
    if not native_bt2020:
        lin = mat3_vec(GLSL_BT709_TO_BT2020, lin)
    lin = (lin[0] * scene_gain, lin[1] * scene_gain, lin[2] * scene_gain)
    return (oclog2_encode(lin[0]), oclog2_encode(lin[1]), oclog2_encode(lin[2]))


def eval_hlg_to_oclog3(rgb_hlg: Vec3, native_bt2020: bool = True) -> Vec3:
    """High-precision BT.2100 HLG -> scene-referred BT.2020 OCLog3 (0.38 HLG -> 0.400)."""
    lin = (
        hlg_inverse_oetf(rgb_hlg[0]) * TIER_GREY_RATIO,
        hlg_inverse_oetf(rgb_hlg[1]) * TIER_GREY_RATIO,
        hlg_inverse_oetf(rgb_hlg[2]) * TIER_GREY_RATIO,
    )
    if not native_bt2020:
        lin = mat3_vec(BT709_TO_BT2020, lin)
    return (oclog3_encode(lin[0]), oclog3_encode(lin[1]), oclog3_encode(lin[2]))


# 24 canonical ColorChecker-like sRGB / scene-referred patches in linear BT.709 (0..1+)
SYNTHETIC_PATCHES_709: dict[str, Vec3] = {
    "dark_skin": (0.115, 0.056, 0.038),
    "light_skin": (0.490, 0.275, 0.200),
    "blue_sky": (0.115, 0.215, 0.375),
    "foliage": (0.095, 0.145, 0.048),
    "blue_flower": (0.210, 0.205, 0.425),
    "bluish_green": (0.140, 0.460, 0.410),
    "orange": (0.640, 0.195, 0.025),
    "purplish_blue": (0.075, 0.095, 0.365),
    "moderate_red": (0.500, 0.085, 0.095),
    "purple": (0.105, 0.042, 0.145),
    "yellow_green": (0.310, 0.475, 0.045),
    "orange_yellow": (0.680, 0.365, 0.028),
    "blue": (0.032, 0.050, 0.295),
    "green": (0.068, 0.295, 0.065),
    "red": (0.435, 0.038, 0.032),
    "yellow": (0.760, 0.590, 0.032),
    "magenta": (0.460, 0.085, 0.285),
    "cyan": (0.025, 0.240, 0.390),
    "white_95": (0.900, 0.900, 0.900),
    "neutral_65": (0.362, 0.362, 0.362),
    "neutral_50": (0.180, 0.180, 0.180),
    "neutral_35": (0.090, 0.090, 0.090),
    "neutral_20": (0.031, 0.031, 0.031),
    "black_05": (0.005, 0.005, 0.005),
}


def sample_1d_trilinear_safe(table: list[Vec3], x: float) -> Vec3:
    n = len(table)
    scaled = clamp01(x) * (n - 1)
    lo = min(n - 2, int(math.floor(scaled)))
    frac = scaled - lo
    a = table[lo]
    b = table[lo + 1]
    return (
        a[0] + (b[0] - a[0]) * frac,
        a[1] + (b[1] - a[1]) * frac,
        a[2] + (b[2] - a[2]) * frac,
    )


def sample_3d_trilinear(table: list[Vec3], size: int, rgb: Vec3) -> Vec3:
    coords = [clamp01(c) * (size - 1) for c in rgb]
    lo = [min(size - 2, int(math.floor(c))) for c in coords]
    hi = [min(size - 1, l + 1) for l in lo]
    fr = [coords[i] - lo[i] for i in range(3)]
    out = [0.0, 0.0, 0.0]
    for bz in (0, 1):
        wz = (1.0 - fr[2]) if bz == 0 else fr[2]
        iz = lo[2] if bz == 0 else hi[2]
        for gy in (0, 1):
            wy = wz * ((1.0 - fr[1]) if gy == 0 else fr[1])
            iy = lo[1] if gy == 0 else hi[1]
            for rx in (0, 1):
                w = wy * ((1.0 - fr[0]) if rx == 0 else fr[0])
                ix = lo[0] if rx == 0 else hi[0]
                v = table[ix + size * (iy + size * iz)]
                out[0] += v[0] * w
                out[1] += v[1] * w
                out[2] += v[2] * w
    return (out[0], out[1], out[2])
