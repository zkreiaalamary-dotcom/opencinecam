# Handoff · Huawei Pura 80 Pro (`research/autonomous-log-lab`)

**Active branch:** `research/autonomous-log-lab` (branched from `research/pura80-capture-probes` @ `85574fa3`; earlier baseline `research/self-exploring-lab` @ `adc19ce1`).  
**Target device:** Huawei Pura 80 Pro — LMR-LX9 — Kirin 9020 — EMUI 15 — Android API 31.

> **Authoritative deep documentation:** See [docs/research/agent-handoff.md](../docs/research/agent-handoff.md), [deep-analysis.md](../docs/research/deep-analysis.md), [evidence-index.md](../docs/research/evidence-index.md), [hypotheses.md](../docs/research/hypotheses.md), [engineering-roadmap.md](../docs/research/engineering-roadmap.md), and [next-experiments.md](../docs/research/next-experiments.md).

## Host Upgrades Completed & Ready for `LMR-LX9` Execution (`2026-11-26`)

1. **20 GB Configurable Storage + 5 GiB Reserve + SHA-256 Verified Export & Manual Pruning:** `LabStorageManager.kt`, `LabTelemetry.kt`, `ResearchLabScreen.kt`. Default evidence cap raised to `20 GB` (`5/10/20/30/50 GB` selector), `5 GiB` device reserve protected, canonical-path deduplicated sizing, zero-temp-copy ZIP export with SHA-256 readback verification, and confirmation-gated media pruning keeping logs/reports/`knowledge.json`.
2. **All 7 Lab/Pipeline Bugs Resolved:** Fixed `LabSyntheticEncoderProbe.kt` GLSL `.trimIndent()` + 0-byte cleanup + Main10 `HEVCMainTierLevel62`; fixed `60M -> 120M` bitrate scaling in `LabRegularLogRunner.kt` + `OpenCineLogGpuPipeline.resolveBitratePlan`; quarantined unadvertised `PRESET_SRGB (4)` + added `AvailabilityCallback` wait + empty-cameras failure guard in `LabSourceProbe.kt`; fixed `codec_arm_result` (`H-01`), `baseKey` separation (`previewOnly`/`flatToneMap`/`variant`), `0`-frame `H-02` `INCONCLUSIVE`, and wired `onFirstRecordedFrame` / `onRecordedFrame` + frame diagnostics.
3. **Innovations Ready to Benchmark on Device:** GLES 3.0 async PBO scope readback (`AsyncPboScopeReader` / `scopes_pbo`), profile-aware `HevcEncoderLevels.selectHevcLevelForProfile`, `DIRECT_HEVC_SURFACETEXTURE_360P` (`640x360` 4K60 dual-output test), `safeLinearRolloffCurve` + locked AE/AWB `GAMMA_1_0` / `2.2` ISP probing, and proactive thermal rate-of-rise governor.
4. **Host Verification Status:** `:core:model` (`105`), `:media` (`231`, `0` fail, `2` pre-existing skipped), `:camera` (`1302`), `:app` `research.*` (`43`) all passed; `0` `NewApi` Lint errors across `:app`/`:media`/`:camera`; Research Lab APK built (`67,270,709` B, SHA-256 `2C363BD426A82E782C7BF32BD1E1B51E7824F625C924896DF831A137CA8CA721`). No device connected via `adb` during build; install and run on `LMR-LX9` via `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

1. **`GPU_HEVC_4K30` (`Main` L5.0, `KEY_LEVEL=16384`) PROVEN across Runs 1, 2, 3:** `29.990 fps`, `39.18–39.83 Mbps`, `0` drops, `tv/bt709` (`R1-003`, `R2-003`, `R3-003`).
2. **Sustained `30 s` Direct `4K60` HEVC PROVEN (`R2-007` Grayscale, `R3-007` Motion):** `1,788` / `1,780` frames, `59.920` / `59.923 fps`, `47.0–49.9 Mbps`, `p50=16.689 ms`, **`0` drops**, `thermalStatus <= 1`, continuous per-frame motion confirmed (`+3.0..+5.5 px/frame` on EIZO target). Dual-output `4K60 + 720p` (`005/006/012`) suffers `1–9` HAL drops (`camDrops == fileDrops`), except `R1-005` (`0` drops).
3. **`1080p60` / `1080p30` AVC GPU Drops Localized Post-Sensor:** `cam = 0 drops` while `gpu == mux = 13 / 10 / 1` drops (`R2-019`, `R3-021`, `R3-018`).
4. **Why `001` & `004/017` & `002/016` Failed (Lab/Config Bugs Isolated):**
   - `001 CODEC_SYNTHETIC_MATRIX`: All `10` explicit-level arms passed `configure()` + `start()`, then failed `glCompileShader` (`P0005`) due to missing `.trimIndent()` (`LabSyntheticEncoderProbe.kt:504`). Notice `Main10` only advertises `Level 6.2 (16777216)` in `inventory.json`.
   - `004/017` (`4K60` GPU/LOG): `LabRegularLogRunner.kt:271` passed `60M` which `RecordingDataRate.gpuVideoBps(..., 60)` doubled to `120M > 100M` HiSilicon cap.
   - `002/016` (`LOG_REGULAR_4K30` Main10): Emitted `muxer_track` (`87 B csd-0`) and **`0` video samples** (`MP4 = 3,429 B`), exact Main10/EGL10/L6.2 cause next to test via fixed `001`.
5. **API 31 ISP Tone/Color Probes & Safety Bug:**
   - `008`: `TONEMAP_MODE_CONTRAST_CURVE (0)` works (`maxPts=257`), but sRGB-in-linear `PictureProfile.FLAT` + unlocked AE clipped `44.4%–69.0%` highlights!
   - `009`: Empirically confirmed **`COLOR_CORRECTION_MODE_TRANSFORM_MATRIX (0)`** (`0%` clip) and **`TONEMAP_MODE_GAMMA_VALUE (3)`** (`0%` clip). **Arm 5 (`PRESET_SRGB`, mode `4`) crashed `cameraserver`**, blinding `010 SOURCE_FORMAT_PROBE` (`cameras: []`, false negative `H-08`) and crashing `011 DIRECT_HEVC` (`unknown device 0: -2`).
6. **Grayscale & Red Cast:** Ramp `Y=25..234`, `182–188` distinct bins, `maxGap=1`, `0%` clip, zero red cast (`wall 173/178/168`). All accepted streams remain 8-bit `tv/bt709`; vendor key `com.huawei.capture.metadata.cuvaStateVideoMode` is exposed (`5` req keys, `50` res keys).
7. **Storage Limit Explained:** Hardcoded `950 MB` cumulative cap (`LabTelemetry.kt:124`); Runs 0–3 took `663.6 MB`, so Run 4 hit `950 MB` at `21/24` despite `122 GB` free.
