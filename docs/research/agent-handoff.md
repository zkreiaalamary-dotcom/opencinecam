# OpenCineCam Engineering Handoff — Huawei Pura 80 Pro (`LMR-LX9`)

**Last updated:** 2026-11-26 (post forensic analysis of `تجربه1` & `تجربه2` exports in `C:\Users\zkrei\Downloads\نتايج`)  
**Target device:** Huawei Pura 80 Pro (`LMR-LX9`, Kirin 9020, EMUI 15, Android API 31, `HUAWEI/LMR-L29/...`)  
**Active research branch:** `research/autonomous-log-lab` (HEAD `281dc2b8` prior to these docs)

> **Branch Topology Clarification:** `research/self-exploring-lab` (`adc19ce1`) was the earlier baseline branch. Subsequent development progressed through `research/pura80-capture-probes` (`85574fa3`) onto **`research/autonomous-log-lab` (`281dc2b8`)**, which produced the APK (`local-1795662984859`) tested in `تجربه1` and `تجربه2`. Keep `main` and `pura-apk-build` untouched unless explicitly instructed.

---

## Documentation Map (`docs/research/`)

- [Deep Forensic Analysis](deep-analysis.md) — Scene verification, tripod wobble & monitor confounds, sensor-to-muxer cadence, grayscale ramp & color cast measurements, GPU/LOG mismatch trace, Lab bug audit, and 5-tier epistemic classification.
- [Evidence Index](evidence-index.md) — Exact SHA-256 hashes, run UUIDs, all 24 valid MP4 packet-PTS metrics, failed/header-only (`3,429 B`) container breakdown, and non-video probes.
- [Hypotheses & Causal Ranking](hypotheses.md) — Corrected `H-01..H-10` table + ranked competing causes for every failure (`001`, `002`, `004/017`, `005/006`, `008`, `009->010->011`, `015`, `019/021`).
- [Engineering Roadmap & Dynamic Storage](engineering-roadmap.md) — Ranked unused opportunities, phased app/lab roadmap, and architecture replacing the hardcoded 950 MB evidence limit.
- [Next Experiments](next-experiments.md) — Top 5 single-variable discriminating experiments with locked AE/AWB controls.
- [Reproducible Analyzer Script](../../tools/analyze_research_export.ps1) — Offline PowerShell + `ffprobe`/`ffmpeg` verifier.

---

## Ground-Truth Capability Snapshot (After `تجربه1` & `تجربه2`)

| Domain | Verified Fact (With Evidence) |
|---|---|
| **GPU -> HEVC `4K30` (`Main` L5.0)** | **PROVEN (`R1-003`, `R2-003`, `R3-003`):** First-ever Pura 80 Pro `OpenCineLogGpuPipeline` -> `OMX.hisi.video.encoder.hevc` 4K30 (`29.990 fps`, `39.2–39.8 Mbps`, `0` drops across all 3 runs) using explicit `KEY_LEVEL=16384`. Previous AVC fallbacks (`014`) occurred strictly because production omitted `KEY_LEVEL`. |
| **Direct `4K60` HEVC (`Main` L5.1, `30 s`)** | **PROVEN (`R2-007`, `R3-007`):** Sustained `~30 s` (`1,788` / `1,780` frames), `59.920` / `59.923 fps`, `47.0–49.9 Mbps`, `p50=16.689 ms`, **`0` drops**, `thermalStatus <= 1`, clean visual motion (`+3.0..+5.5 px/frame` on EIZO target). |
| **2-Target Direct `4K60` (`+ 720p Preview`)** | **PARTIAL (`R1-005` `0` drops vs `R2/R3 005,006,012` `1–9` drops):** Exact `camDrops == fileDrops` proves drops occur inside Camera2/HAL servicing dual outputs, not thermal throttle or encoder. |
| **HEVC `Main10` (`4K30` & `4K60`)** | **UNRESOLVED / BLOCKED BY LAB BUGS:** `001` proved `Main10` + `10-bit EGL` `configure()` & `start()` succeed (`0/10` failures before shader compile!), then crashed in `glCompileShader` due to missing `.trimIndent()` (`P0005`). Live `002/016` emitted `muxer_track` (`87 B csd-0`) and **`0` encoded frames** (`3,429 B` MP4); Note `inventory.json` advertises Main10 **ONLY at Level 6.2 (`16777216`)**, not Level 5.0 (`16384`). |
| **`GPU_HEVC_4K60` & `LOG_REGULAR_4K60`** | **UNTESTED (BUG):** `LabRegularLogRunner` passed `60M` which `RecordingDataRate.gpuVideoBps` doubled to **`120 Mbps > 100 Mbps`** HiSilicon ceiling (`004/017`). |
| **`1080p60` / `1080p30` AVC GPU Drops** | **POST-SENSOR BOTTLENECK PROVEN:** `cam = 0 drops` while `gpu == mux = 13/10/1 drops` (`R2-019`, `R3-021`, `R3-018`) with real frozen frames confirmed visually. |
| **API 31 ISP Controls (`008`, `009`)** | **`TONEMAP_MODE_CONTRAST_CURVE (0)`** (`FLAT`) works in HAL (`maxPts=257`) **but** sRGB-in-linear + unlocked AE causes **`44–69%` highlight clip**! Empirically discovered **`COLOR_CORRECTION_MODE_TRANSFORM_MATRIX (0)`** and **`TONEMAP_MODE_GAMMA_VALUE (3)`** with **`0%` clip** on API 31. Warning: **`PRESET_SRGB (4)` crashes `cameraserver`**, which blinded `010` (`cameras: []`) and killed `011` (`unknown device 0: -2`). |
| **Grayscale & Color Quality (`R2`)** | Clean monitor ramp `Y=25..234`, `182–188` distinct bins, `maxEmptyGap=1` (no posterization), `0%` clip/crush, **zero red cast** (`wall R/G/B=173/178/168`). Effective >8-bit & true LOG remain unproven (`SDR_BT709_ISP` is post-ISP 8-bit). |
| **Accepted Sizes & Vendor Keys (`cam 0`)** | `40` identical `SurfaceTexture` & `MediaRecorder` sizes (max `4096x3072`, `3840x2160` reports `33.33 ms` min duration hiding `4K60`). **5 Huawei vendor request/session keys** including **`com.huawei.capture.metadata.cuvaStateVideoMode`** (HDR Vivid) + **50 result keys**. |
| **1 GB Storage Limit (`950 MB`)** | Cumulative across runs (`Runs 0–3 = 663.6 MB`); a 4th run crosses `950 MB` at `21/24` despite `122 GB` free. Neither `تجربه1` nor `تجربه2` lost data (`24/24` completed in both). |

---

## Immediate Next Actions for Following Agent

1. *(Completed on host 2026-11-26 post `نتايج\جديد`)* Forensically integrated Run `7d0db6a5` (`49` attempts under Huawei Power Saving ON & Performance OFF) & Run `f8d3e998` (`0` attempts aborted at `32.0 °C / MODERATE`), proving **4K60 Direct `HEVC Main 10` (`024`/`025`, `59.93–59.94 fps`, `0` drops)**, **unconfounded `GAMMA_2_0` vs `GAMMA_1_8` ISP curve (`019`/`020`)**, **`0x7F00000F`/`0x7F000011` `admitted=true`**, **`ISP_CUVA_HDR_PROBE` `ByteArray` requirement (`022`)**, and the **power-mode confound** behind the `47.8–48.3 fps` `11@60 -> 4/1` GPU metronome (`007..016`, `029..031`).
2. *(Upgrades implemented & host-verified)* Added `LabPowerProfile` (OS power-save + Huawei hint + `cpufreq` governor + `ManualPowerMode` selector), battery `>= 40.0 °C` pause / `<= 35.0 °C` auto-resume hysteresis without timeout abort, permanent removal of fixed `36`/`54` & `TIME_SLICE_LIMIT_MS` caps (`LabExplorationGuard` cycle/duplicate guard + `20 GB` / `5 GiB` storage protection), durable `LabCheckpointState` (`VERSION=3` + `LEGACY_VERSION_2`, `active_session.json`, `latestResumable` skipping `0`-attempt runs), `0x7F00000F`/`0x7F000011` `ByteBuffer` 10-bit ramp encode, `ByteArray`-first `ISP_CUVA_HDR_PROBE`, `GAMMA 2.6..1.0` & `-1 EV` curve, `gamma_2_0`/`gamma_2_2` 4K30 LOG, `4K60 previewOnly` discriminator, and `1080p120` explicit level. Verified `:core:model:test` (`105/105`), `:media:testDebugUnitTest` (`231`, `0` fail, `2` pre-existing skip), `:camera:testDebugUnitTest` (`1302/1302`), `:app:testDebugUnitTest` (`54/54`), Lint (`:media` & `:camera` `0` errors; `:app` `0` `NewApi` errors & `0` findings in `research\`, `2646` pre-existing `MissingTranslation`), and `:app:assembleDebug` (`67,273,193` B, SHA-256 `B38E48FBD7296D447E4B7A0EF8F577D1DB6BDBBE9F627BDF6DD4EB9C18344117`).
3. Disable Huawei Power Saving Mode & enable Performance Mode on `LMR-LX9`, then install upgrade-compatibly (`adb devices` = `0` during host verification): `adb install -r app/build/outputs/apk/debug/app-debug.apk` (preserving `filesDir/research-lab`).
4. Run the upgraded Research Lab session cleanly (and validate auto-resume/thermal `40 °C -> 35 °C`, `0x7F00000F/11` `ByteBuffer` ramp bits, `ByteArray` `CUVA HDR`, `GAMMA 2.6..1.0` / `-1 EV`, and unconfounded 4K60 GPU frame timings) before promoting verified controls to production defaults.
