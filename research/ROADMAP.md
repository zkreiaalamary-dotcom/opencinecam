# Research roadmap

Updated after deep forensic integration of `تجربه1` & `تجربه2` (`Runs 0–3`, Pura 80 Pro `LMR-LX9`, API 31). See full architectural & storage details in [docs/research/engineering-roadmap.md](../docs/research/engineering-roadmap.md) and exact experiment configs in [docs/research/next-experiments.md](../docs/research/next-experiments.md).

| Priority | Track / Experiment | Decision / Unlock | Current Status |
|---:|---|---|---|
| **1** | **Fix 6 Lab Bugs + Dynamic Storage Manager** (`.trimIndent()` in `001`, `Main10 L6.2`, `<=100M` 4K60 bitrate clamp, remove crashing `PRESET_SRGB` before `010`, fix `H-01`/`baseKey` KB bugs, wire `onRecordedFrame`, dynamic storage budget + export verification) | Eliminates false negatives & `950 MB` stop at `21/24` | Diagnosed in full; ready for next Lab commit |
| **2** | **`X-02` / `X-03` Fixed `CODEC_SYNTHETIC_MATRIX` (`001` + `L6.2`)** | Resolves `Main10` EGL10 frame drop (`002` `0` samples), repeat-half geometry, and `effectiveBits >= 9` precision | Blocked solely by missing `.trimIndent()` (`P0005`) |
| **3** | **Production `GPU_HEVC_4K30` + Fixed-Bitrate `GPU_HEVC_4K60` + Direct `4K60`** | Ships verified `4K30` GPU HEVC (`39.8 Mbps, 0 drops`) and `30 s` Direct `4K60` HEVC (`59.92 fps, 0 drops`); tests `4K60` GPU at `60–80 Mbps` | `GPU_HEVC_4K30` & `DIRECT_HEVC 4K60 30s` **CONFIRMED**; `GPU_HEVC_4K60` awaiting bitrate fix |
| **4** | **Locked-AE Chart Test: `GAMMA_VALUE (3)` + `TRANSFORM_MATRIX (0)` vs Linear Curve** | Delivers unclipped flat/Log pre-conditioning on API 31 (`0%` clip in `009` vs `44–69%` in `008 FLAT`) | API 31 modes empirically confirmed (`009`); chart calibration next |
| **5** | **Clean `SOURCE_FORMAT_PROBE` (`"0","2","4","5"`) + `cuvaStateVideoMode` Vendor HDR Vivid** | Checks physical `RAW`/`P010`/`PRIVATE` `HardwareBuffer` & Huawei CUVA HDR Vivid 10-bit path | `010` blinded by `009` crash; CUVA key exposed in `inventory.json` |
| **6** | **`1080p60` GPU/Encoder Drop Elimination & Explicit-Level `1080p120` HFR** | Fixes `cam=0 -> gpu=10-13` drops in `1080p60` (`glReadPixels` / thread contention) and tests `1080p120` with `KEY_LEVEL` | Drop boundary localized (`019/021`); `015` omitted `KEY_LEVEL` |
