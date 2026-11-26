# Pura 80 Pro evidence and root causes

[State](STATE.md) · [Evidence index](EVIDENCE.jsonl) · [Experiments](ROADMAP.md) · [Handoff](HANDOFF.md)

This review integrates the new field export `OpenCineCam-research (1).zip` (`E-OCC2-*`, built from `d0ffb123`) alongside the prior `E-OCC-*` and `E-PURA-*` baselines. All 9 non-empty clips in `E-OCC2-ZIP` were independently parsed with `ffprobe` and decoded in coded orientation with `ffmpeg` (`E-HOST-GEOM-02`).

## P-UHD60-GRAPH · Direct 4K60 with 720p PRIVATE drain works; GPU path substitutes 1080p

- **Direct Camera2 -> MediaRecorder 4K60 (`E-OCC2-R2-001`, `002`)**: Single-target 4K60 reached `59.893 fps` file / `59.898 fps` sensor (`43.30 Mbps`, `maxGapMs=18.344`). Adding a drained `1280x720 ImageFormat.PRIVATE` `ImageReader` reached `59.920 fps` file / `59.925 fps` sensor (`214` preview frames drained, `31.54 Mbps`, `maxGapMs=16.689`). Run 1 (`E-OCC2-R1-001`) delivered `59.291 fps` (`MISMATCH` on the 0.5% cadence gate). All three clips decoded cleanly with `NO_REPEAT_FLAG` and non-zero consecutive frame MAD (`0.293..0.685`).
- **GPU 4K60 substitution (`E-OCC2-R2-008`, `009`)**: `configured_gpu_source` confirmed `(1920, 1080, 60)` instead of `(3840, 2160, 60)` because `Camera2PreviewEngine.startPreview` drops unlisted `videoProfile` and falls back to `baseViewfinderSize` (`1920x1080`).
- **Next discriminator**: Test 30 s sustained direct 4K60 (`X-04`), live `SurfaceTexture` preview as second target (`DIRECT_HEVC_SURFACETEXTURE`), initial fixed `[60,60]` session parameters, and explicit 4K60 GPU source admission in the lab runner.

## P-HEVC-MAIN and P-LOG-HFR · Missing `KEY_LEVEL` is a confirmed configure killer on HiSilicon

- **Isolated `CODEC_LEVEL_PAIR` (`E-OCC2-R2-003`)**: On `OMX.hisi.video.encoder.hevc` (4K30, Main profile, 20 Mbps VBR, BT.709 limited SDR, SAR 1:1, `framesSubmitted=0`), omitting `KEY_LEVEL` failed `MediaCodec.configure` with `Error 0x80001001` (`-2147479551`), while adding `HEVCMainTierLevel51` (`65536`) passed `configure -> createInputSurface -> start`.
- **Production GPU HEVC (`E-OCC2-R2-004`, `012`)**: Both 4K30 and 1080p30 failed `configure` on `OMX.hisi.video.encoder.hevc` with `0x80001001` (format trace confirms `KEY_LEVEL` absent) and fell back to `OMX.hisi.video.encoder.avc`.
- **OCLog2 1080p120 HFR (`E-OCC2-R2-005`)**: Camera reached `[120,120]` (`17` results) and `codec_create(OMX.hisi.video.encoder.hevc)` succeeded, then `configure` threw `0x80001001` with `profile=2` (Main10), **no `KEY_LEVEL`**, `bitrate=80000000`, `color-standard=6`, `color-range=1`, `frame-rate=120`.
- **Phase 2 host tooling implemented (`E-HOST-PHASE2-01`)**: `HevcEncoderLevels` computes H.265 Annex-A minimum Main-tier levels cleanly; `HevcSpsInspector` independently verifies coded/display raster, luma/chroma bit depth, level_idc, and VUI range/transfer/primaries in Annex-B and `hvcC`; `LabDecode` + `LabGeometry` inspect coded-plane luma for consecutive-frame uniqueness (`STATIC_OR_DUPLICATE` vs `DYNAMIC_CONFIRMED`), asymmetric quadrant layout (`REPEAT_HALF_X/Y`), and 10-bit linear ramp step quantization (`mod4ZeroFraction`, `effectiveBitsEstimate`); and `LabSyntheticEncoderProbe` runs an 11-arm camera-closed EGL8/EGL10 Main8/Main10 matrix (`X-02`/`X-03`) on device.
- **Next discriminator**: Run `CODEC_SYNTHETIC_MATRIX` on the Pura 80 Pro to obtain the first empirical Main10 Configure/Geometry/Ramp-Precision truth, then execute Phase 3 live 4K30/4K60 regular-session `OCLog2`.

## P-1080-CADENCE · 1080p60 GPU AVC clean on second run

- `E-OCC2-R2-007` ran 247 frames at `59.920 fps` (`maxGapMs=16.689`, `0` outliers, `40.08 Mbps`), unlike `E-OCC-002`'s single `30.247 ms` excursion.

## P-MAIN10-GEOMETRY · Repeat-half mechanism unresolved; synthetic + coded-plane check needed

- All 9 clips in `E-OCC2-ZIP` (`HEVC Main` and `AVC High`) passed `NO_REPEAT_FLAG` and had distinct consecutive frames (`consec20..25MAD = 0.272..0.880`).
- Main10 was not recorded in `E-OCC2-ZIP` because `005` failed at `configure`. The prior Pura Main10 repeat-half defect (`E-PURA-GEOM`, `E-PURA-5698009/5710757`) remains an open gate blocking Main10/LOG promotion until tested with an asymmetric synthetic input and coded-orientation decoder.

## P-SOURCE-PRECISION & Huawei Video Log / CameraKit / LUT provenance

Four separate layers must never be conflated:
1. **Coded bitstream bit depth**: 8-bit (`Main`) vs 10-bit (`Main10`) in HEVC SPS.
2. **Effective input precision**: whether the producer delivers >8-bit distinct steps (e.g., 10-bit ramp `mod4 != 0` vs 8-bit shifted-left-by-2 `mod4 == 0`).
3. **True dynamic range**: scene highlight/shadow latitude before ISP clipping/tone-mapping.
4. **Post-ISP SDR-derived OCLog2 (`POST_ISP_SDR_LOG`)**: applying `OCLog2` in GLES over an SDR ISP stream (`SDR_BT709_ISP`) yields a valid graded-ready Log container/workflow curve only if Main10 geometry & precision hold, but remains bounded by ISP tone-mapping and 8-bit source quantization unless an HLG10/P010/RAW16/vendor source is verified.

- **Global Pura 80 Pro (LMR-LX9, EMUI 15, API 31) inventory (`E-OCC-CAPS`, `E-OCC2-ZIP`)**: `hlg10Advertised=false`, `raw10Sizes=[]`, `outputFormats=[256, 34, 35, 1144402265]` (`JPEG`, `PRIVATE`, `YUV_420_888`, `DEPTH_JPEG` / vendor-checkable).
- **Huawei Video Log / CameraKit / LUT findings**: On HarmonyOS (China), Huawei Video Log and HDR Vivid live inside Huawei's Camera Engine (CameraKit) / Video Engine (3D-LUT) stack rather than standard Android API 31 public `DynamicRangeProfiles` (which requires API 33+). Huawei does not distribute official standalone `.cube` LUTs for Pura 80 Pro Video Log on public global developer downloads. Any authorized vendor tags (`com.huawei.*` / `hw.*`), `TONEMAP_MODE_CONTRAST_CURVE` ISP response, or `RAW_SENSOR` / `YCBCR_P010` support on logical/physical cameras must be discovered strictly via public read-only `CameraCharacteristics` / `CameraKit` class-presence checks without OS modification.

## P-THERMAL-TELEMETRY · Unexplained Run 1 stop & missing periodic logs

- `E-OCC2-R1-001` stopped after 1 attempt without `summary.json`, while the only logged snapshot (`001-requested.json`) recorded `thermalStatus=1`, `34.0 °C`, `36%`. Because `ResearchLaboratory.checkBudget()` previously threw `LabStop` on the first `thermalStatus >= 2` poll without writing a stop snapshot or periodic telemetry, transient `MODERATE` spikes and thermal headroom trends were invisible. Phase 1 replaces this with `telemetry.jsonl`, `pause.json`, and 45 s cooldown hysteresis.
