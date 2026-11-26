# OpenCineCam research state

2026-11-26 · Huawei Pura 80 Pro global LMR-LX9 · EMUI 15 · Android API 31.

[Handoff](HANDOFF.md) · [Analysis](ANALYSIS.md) · [Roadmap](ROADMAP.md) · [Evidence index](EVIDENCE.jsonl)

- Active development branch: `research/autonomous-log-lab` (branched from `research/pura80-capture-probes` @ `85574fa3ddfe80b768634ca48ac4c19bb3bb9f70`).
- **New field evidence integrated (`OpenCineCam-research (1).zip`, `E-OCC2-*`, revision `d0ffb123`, 2 runs, 13 attempts, 9 valid MP4s + 1 empty clip, all verified by `ffprobe`/`ffmpeg`):**
  - **Direct 4K60 HEVC Main L5.1 confirmed (`E-OCC2-R2-001` / `002`)**: `59.893 fps` single-target (`43.30 Mbps`) and `59.920 fps` with a second `1280x720 PRIVATE ImageReader` (`214` preview frames drained, `31.54 Mbps`, `maxGapMs=16.689`), zero repeated PTS, clean geometry (`NO_REPEAT_FLAG`), distinct consecutive frames. Run 1 (`E-OCC2-R1-001`) reached `59.291 fps` (`MISMATCH` on the `0.5%` gate) and stopped without `summary.json`.
  - **HEVC explicit level confirmed (`E-OCC2-R2-003`)**: `OMX.hisi.video.encoder.hevc` Main 4K30 failed at `configure` (`0x80001001`) when `KEY_LEVEL` was omitted, and started cleanly (`admitted=true`) with `HEVCMainTierLevel51` (`65536`), `framesSubmitted=0`.
  - **GPU HEVC fallback confirmed (`E-OCC2-R2-004`, `012`)**: production GPU HEVC 4K30 and 1080p30 omit `KEY_LEVEL`, fail `configure` with `0x80001001`, and fall back to `OMX.hisi.video.encoder.avc`.
  - **GPU 4K60 substitution confirmed (`E-OCC2-R2-008`, `009`)**: unlisted 4K60 GPU requests resolve `activeVideoProfile` to `null` and configure `1920x1080@60` (`configured_gpu_source`).
  - **OCLog2 HFR 1080p120 failure localized (`E-OCC2-R2-005`)**: reached `codec_create OMX.hisi.video.encoder.hevc` and failed at `configure` (`0x80001001`, `profile=2` Main10, no `KEY_LEVEL`, `80 Mbps`, `color-standard=6`, `color-range=1`).
  - **1080p60 control clean (`E-OCC2-R2-007`)**: `247` frames, `59.920 fps`, `maxGap=16.689 ms`, `0` outliers.
  - **Thermal blind spot identified**: Run 1 stopped unexpectedly while `environment` showed `thermalStatus=1`, `34.0 °C`, `36%`; `checkBudget()` previously aborted on a single `MODERATE` read without persisting `telemetry.jsonl` or `pause.json`.
- **Strategic priority shift**: highest priority is cinematic **4K30 and 4K60 LOG** (using `OpenCineLogGpuPipeline` + Main10 contract isolation + source precision / ISP tone-map verification) and **sustained direct 4K60 with live preview**, above 1080p120 HFR.

## Phase 1 implemented (`research/autonomous-log-lab`)

- Continuous thermal/resource telemetry (`LabTelemetry.kt`, `telemetry.jsonl`, `pause.json`) with `LabThermalPolicy` hysteresis (1 transient `MODERATE` tolerance during active work, 45 s bounded cooldown wait between attempts, immediate hard stop on `SEVERE` / `>= 42 °C` / `< 25%` battery / low storage).
- Expanded `LabProfiles.inventory()` with per-size `StreamConfigurationMap` min/stall durations, full `MediaCodecList` encoder capability matrix, physical camera inventories, vendor keys, tone-map modes, and read-only Huawei CameraKit presence check.
## Phase 2 implemented (`research/autonomous-log-lab`, host-verified; device run pending)

- **HEVC level helper (`HevcEncoderLevels.kt`)**: Pure Annex-A Main-tier minimum level calculation (`1080p30->L4.0`, `1080p60->L4.1`, `1080p120->L5.0`, `4K30->L5.0`, `4K60->L5.1`, `4K120->L5.2`) and `selectHevcLevel()` verified on JVM (`HevcEncoderLevelsTest`: 4/4 pass).
- **Independent HEVC SPS parser (`HevcSpsInspector.kt`)**: Parses Annex-B `csd-0` and ISO-BMFF `hvcC` SPS NALUs (profile, tier, level, coded vs conformance-cropped display size, luma/chroma bit depth, VUI full-range/primaries/transfer/matrix), wired into `LabVideo.inspectClip()` and verified on JVM (`HevcSpsInspectorTest`: 3/3 pass).
- **Coded-orientation decode, uniqueness & 10-bit ramp analyzer (`LabGeometry.kt`, `LabDecode.kt`, `LabVideo.kt`)**: Added `MediaCodec` `COLOR_FormatYUV420Flexible` coded-plane extraction alongside `MediaMetadataRetriever`, consecutive & spaced frame MAD (`DYNAMIC_CONFIRMED` vs `STATIC_OR_DUPLICATE`), `verifySyntheticQuadrants`, and `analyzeRampPrecision` (`mod4ZeroFraction`, `effectiveBitsEstimate` distinguishing 8-bit shifted from 10-bit ramp), plus 30,000-sample `readClip` cap (`LabGeometryTest`: 5/5 pass).
- **Synthetic camera-closed EGL8/EGL10 matrix (`LabSyntheticEncoderProbe.kt`, `LabPath.CODEC_SYNTHETIC_MATRIX`)**: 10+ arm deterministic Main8/Main10 test pattern probe isolating `KEY_LEVEL`, BT.709 vs BT.2020, Limited vs Full range, HLG/SDR/unspecified VUI, EGL 8-bit vs 10-bit surfaces, `OCLog2` curve transfer (`OpenCineLog2Curve` match), automatic 4K60 escalation, shared `GpuEglDisplayLease`, and preservation of any distorted Main10 sample (`synthetic_distorted_main10.mp4`). Awaiting physical Pura 80 Pro execution for hardware verdicts.
- Next phase (Phase 3): Live regular-session 4K30/4K60 `OCLog2` & GPU HEVC research path (`ResearchEncoderOverride`), ISP `TONEMAP_MODE_CONTRAST_CURVE` A/B & `RAW_SENSOR`/`P010` source probe, and sustained 30 s direct 4K60 + live `SurfaceTexture` preview.
