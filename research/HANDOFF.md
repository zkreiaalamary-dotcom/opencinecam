# Handoff · Huawei Pura 80 Pro (`research/autonomous-log-lab`)

**Current branch:** `research/autonomous-log-lab` (branched from `research/pura80-capture-probes` @ `85574fa3ddfe80b768634ca48ac4c19bb3bb9f70`).
**Target device:** Huawei Pura 80 Pro — LMR-LX9 — Kirin 9020 — EMUI 15 — Android API 31.

## Verified baseline (including `OpenCineCam-research (1).zip`)

| Area | Verified fact | Evidence |
|---|---|---|
| Direct 4K60 HEVC | `DIRECT_HEVC` (`59.893 fps`, `43.30 Mbps`) and `DIRECT_HEVC_PREVIEW` (`59.920 fps`, `214` drained 720p `PRIVATE` frames, `31.54 Mbps`) both passed 4 s file/geometry/content-diff checks on `d0ffb123`. Run 1 reached `59.291 fps` (`MISMATCH`). | `E-OCC2-R1-001`, `E-OCC2-R2-001`, `E-OCC2-R2-002`, `E-HOST-GEOM-02` |
| HEVC `KEY_LEVEL` | `OMX.hisi.video.encoder.hevc` Main 4K30 rejected `configure` (`0x80001001`) with `level=null` and started cleanly with `HEVCMainTierLevel51` (`65536`, `framesSubmitted=0`). | `E-OCC2-R2-003` |
| GPU HEVC & 4K60 | Production GPU HEVC omitted `KEY_LEVEL` and fell back to AVC (`4K30`, `1080p30`). Unlisted GPU `4K60` fell back to `1920x1080@60`. | `E-OCC2-R2-004`, `008`, `009`, `012` |
| HFR 1080p120 LOG | Reached `codec_create(OMX.hisi.video.encoder.hevc)` and failed `configure` (`0x80001001`, `profile=2`, no `KEY_LEVEL`, `80 Mbps`). | `E-OCC2-R2-005` |
| Thermal pause gap | Run 1 ended after 1 attempt without `summary.json`; only `001-requested.json` had `thermalStatus=1`, `34.0 °C`. Phase 1 adds `telemetry.jsonl`, `pause.json`, and 45 s cooldown hysteresis. | `E-OCC2-R1-001`, `LabTelemetry.kt` |
| Main10 & source precision | Main10 geometry, 10-bit ramp precision, regular 4K30/4K60 OCLog2, ISP ToneMap curve control, and `RAW_SENSOR`/physical cameras remain unverified on device. | `E-PURA-GEOM`, `E-OCC-CAPS` |

## Active build order

1. **Phase 1 (Complete):** Branch `research/autonomous-log-lab`, CI workflow update, `OpenCineCam-research (1).zip` evidence integration, `LabTelemetry.kt` + `telemetry.jsonl`/`pause.json` + cooldown hysteresis, enriched `LabProfiles.inventory()`.
2. **Phase 2 (Next):** `HevcEncoderLevels`, `HevcSpsInspector`, coded-orientation content-uniqueness & ramp-precision analyzer, synthetic EGL Main8/Main10 matrix (`X-02`/`X-03`).
3. **Phase 3:** Live 4K30/4K60 regular-session `OCLog2` & GPU HEVC research path, ISP `TONEMAP_MODE_CONTRAST_CURVE` A/B + `RAW_SENSOR`/`P010` source probe, sustained 30 s direct 4K60 + live `SurfaceTexture` preview.
4. **Phase 4:** Autonomous planner, cumulative `knowledge.json`, inconclusive retry & contradiction tracking, `report.md`/`report.json` generator, UI readout, full test pass & Research Lab APK build.
