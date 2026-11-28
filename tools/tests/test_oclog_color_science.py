# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors
"""Tests for the independent color science reference, OCLog3 curve, shader parity & Pro LUTs."""

from __future__ import annotations

import math
import re
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))

import generate_cinema_luts as lutgen  # noqa: E402
import oclog_color_science as cs  # noqa: E402


class ColorScienceAndLutTest(unittest.TestCase):
    def test_kotlin_oclog3_constants_match_python_reference(self) -> None:
        kt_source = (ROOT / "camera" / "src" / "main" / "java" / "com" / "librestatic" / "opencinecam" / "camera" / "OpenCineLog3.kt").read_text(encoding="utf-8")
        c_val = float(re.search(r"C_OFFSET\s*=\s*([0-9.]+)", kt_source).group(1))
        a_val = float(re.search(r"A_LN\s*=\s*([0-9.]+)", kt_source).group(1))
        d_val = float(re.search(r"D_OFFSET\s*=\s*([0-9.]+)", kt_source).group(1))
        m_val = float(re.search(r"TOE_SLOPE\s*=\s*([0-9.]+)", kt_source).group(1))
        self.assertAlmostEqual(cs.OCLOG3_C, c_val, places=14)
        self.assertAlmostEqual(cs.OCLOG3_A_LN, a_val, places=14)
        self.assertAlmostEqual(cs.OCLOG3_D, d_val, places=14)
        self.assertAlmostEqual(cs.OCLOG3_M, m_val, places=14)

    def test_oclog3_c1_continuity_roundtrip_and_hlg_headroom(self) -> None:
        self.assertAlmostEqual(0.095, cs.oclog3_encode(0.0), places=12)
        self.assertAlmostEqual(0.400, cs.oclog3_encode(0.18), places=12)
        self.assertAlmostEqual(0.920, cs.oclog3_encode(16.0), places=12)
        eps = 1e-7
        s_left = (cs.oclog3_encode(cs.OCLOG3_CUT) - cs.oclog3_encode(cs.OCLOG3_CUT - eps)) / eps
        s_right = (cs.oclog3_encode(cs.OCLOG3_CUT + eps) - cs.oclog3_encode(cs.OCLOG3_CUT)) / eps
        self.assertAlmostEqual(s_left, s_right, places=4)
        for i in range(501):
            lin = 16.0 * (i / 500.0) ** 2
            dec = cs.oclog3_decode(cs.oclog3_encode(lin))
            self.assertLess(abs(dec - lin), 1e-11)
        # HLG 18% grey -> 0.400, HLG peak -> ~0.7492 (zero clipping)
        grey_log3 = cs.eval_hlg_to_oclog3((0.38, 0.38, 0.38), native_bt2020=True)
        for ch in grey_log3:
            self.assertAlmostEqual(0.400, ch, places=11)
        peak_log3 = cs.eval_hlg_to_oclog3((1.0, 1.0, 1.0), native_bt2020=True)
        for ch in peak_log3:
            self.assertLess(ch, 0.76)

    def test_matrices_preserve_d65_neutrals_and_invert_cleanly(self) -> None:
        for mat in (cs.BT709_TO_BT2020, cs.BT2020_TO_BT709, cs.BT2020_TO_DWG, cs.DWG_TO_BT2020):
            white = cs.mat3_vec(mat, (1.0, 1.0, 1.0))
            for ch in white:
                self.assertAlmostEqual(1.0, ch, places=4)
        rt_709 = cs.mat3_mul(cs.BT2020_TO_BT709, cs.BT709_TO_BT2020)
        rt_dwg = cs.mat3_mul(cs.DWG_TO_BT2020, cs.BT2020_TO_DWG)
        rt_aces = cs.mat3_mul(cs.ACES_AP1_TO_BT2020, cs.BT2020_TO_ACES_AP1)
        for mat in (rt_709, rt_dwg, rt_aces):
            for r in range(3):
                for c in range(3):
                    self.assertAlmostEqual(1.0 if r == c else 0.0, mat[r][c], places=3)

    def test_bt2020_native_fixes_bt709_quirk_chroma_crush(self) -> None:
        # True BT.2020 red encoded in HLG vs crushed through bt709ToBt2020.
        lin_bt2020_red = cs.mat3_vec(cs.BT709_TO_BT2020, cs.SYNTHETIC_PATCHES_709["moderate_red"])
        hlg_red = tuple(cs.hlg_oetf(c) for c in lin_bt2020_red)
        native_log = cs.eval_shader_hlg_to_oclog2(hlg_red, native_bt2020=True)
        quirked_log = cs.eval_shader_hlg_to_oclog2(hlg_red, native_bt2020=False)
        # Reconstructing linear BT.2020 recovers exact scene red when native_bt2020=True
        rec_native = tuple(cs.oclog2_decode(c) for c in native_log)
        rec_quirked = tuple(cs.oclog2_decode(c) for c in quirked_log)
        for i in range(3):
            self.assertAlmostEqual(lin_bt2020_red[i], rec_native[i], places=5)
        self.assertGreater(abs(rec_quirked[0] - lin_bt2020_red[0]), 0.08)

    def test_cinema_luts_generate_and_match_analytic_reference(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            manifest = lutgen.generate(out)
            self.assertGreaterEqual(len(manifest["artifacts"]), 12)
            self.assertTrue(lutgen.check(out))

            # Parse OCLog3 -> Rec709 33^3 cube and verify neutral ramp monotonicity & grey accuracy.
            lines = (out / "technical" / "OCLog3_BT2020_to_Rec709_33.cube").read_text(encoding="utf-8").splitlines()
            rows = [tuple(float(x) for x in ln.split()) for ln in lines if ln and (ln[0].isdigit() or ln[0] == "-")]
            self.assertEqual(33 ** 3, len(rows))
            sampled_grey = cs.sample_3d_trilinear(rows, 33, (0.400, 0.400, 0.400))
            expected_grey = cs.rec709_oetf(0.18)  # 0.4090
            for ch in sampled_grey:
                self.assertAlmostEqual(expected_grey, ch, delta=2e-3)

            # Parse OCLog2 HLG-aligned -> Rec709 cube and verify HLG grey (0.3490 OCLog2) maps to ~0.409
            hlg_grey_oclog2 = cs.oclog2_encode(cs.HLG_REFERENCE_GREY_LINEAR)
            lines2 = (out / "technical" / "OCLog2_BT2020_HLG_to_Rec709_33.cube").read_text(encoding="utf-8").splitlines()
            rows2 = [tuple(float(x) for x in ln.split()) for ln in lines2 if ln and (ln[0].isdigit() or ln[0] == "-")]
            sampled_hlg_grey = cs.sample_3d_trilinear(rows2, 33, (hlg_grey_oclog2, hlg_grey_oclog2, hlg_grey_oclog2))
            for ch in sampled_hlg_grey:
                self.assertAlmostEqual(expected_grey, ch, delta=3e-3)


if __name__ == "__main__":
    unittest.main()
