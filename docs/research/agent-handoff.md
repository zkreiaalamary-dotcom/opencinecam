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
- [Wave B & Incident Hardening Breakthroughs (2026-11-27)](wave-b-cinema-breakthroughs-20261127.md) — `CameraDaemon` hardening, `Probe 14A` (`0x7F000011` UV layout), `Probe 14B` (Kelvin WB + CUVA OETF), and `Probe 15` production multi-lens `4K60`/`4K30`/Open-Gate/HLG10/Kelvin verification.
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

## Wave 1–3 Breakthroughs & Production 4K60 OCLog2 / CUVA HDR Verification (`2026-11-27`)

All blocking bottlenecks between Huawei Pura 80 Pro (`LMR-LX9`, Kirin 9020, API 31) and **sustained `4K60` / `4K30` `OCLog2` `HEVC Main 10` with CUVA HDR Vivid (`0x324`)** were solved and verified on device:

1. **Zero-Copy `ImageReader(PRIVATE, USAGE_GPU_SAMPLED_IMAGE | USAGE_VIDEO_ENCODE)` (`Probe 1`, `Probe 6`, `Probe 8`):** Replaced `4K60 SurfaceTexture` input (`48.83 fps / 25 gaps` in `1A`) with `ImageReader(..., HARDWARE_BUFFER | USAGE_GPU_SAMPLED_IMAGE | USAGE_VIDEO_ENCODE)` (`1C`: **`59.36 fps / 0 gaps`**), bound directly to Maleoon 920 `samplerExternalOES` via new native JNI/EGL extension bridge (`occ_egl_hwbuffer_jni.cpp` / `EglHardwareBufferBridge.kt`, `extMask=15`, `IMAGE_READER_Y_FLIP_MATRIX`) plus `EGL_CONTEXT_PRIORITY_HIGH_IMG (0x3101)` (`Probe 3`: `p95` `19.66 -> 16.32 ms`).
2. **HiSilicon `Main10 L6.2` + CUVA HDR Vivid (`0x324`) + `HwVideoRecordingPre4K60En` (`Probe 2`, `Probe 6`, `Probe 8`):** Production `OpenCineLogGpuPipeline` enables `hisiliconMain10Workaround` (`egl8` + `L6.2` `16777216`), `Camera2SessionGraphs` injects `cuvaStateVideoMode = ByteArray[1]` (`0x324`), and `Camera2Descriptors` advertises `235` `HLG10_BT2020` CUVA profiles including `3840x2160@60` (`has4k60CuvaProfile=true`, `has4k60VideoProfile=true`, `hasUntestedOpenGate4k60=false`).
3. **Live Sustained Production Verification (`Probe 8`, `OK (1 test)`, `41.508 s`, `21 -> 23 °C`):**
   - `prod_4k60_oclog2_15s.mp4` (`187,090,075 B`): `camCb=930 f / 59.940 fps (0 gaps)`, `898` encoded frames / `59.787 fps`, `99.66 Mbps`, `Main 10 L153`, `containerVuiTransfer=2`, 10-bit `Y=[78..876]`, `755/1024` distinct, `lsbNz=0.751`, `clipLo=0, clipHi=0`, visually verified upright.
   - `prod_4k30_oclog2_5s.mp4` (`35,433,952 B`): `29.990 fps`, `151` frames, `56.30 Mbps`, `Main 10 L150`, `0` PTS gaps.
4. **Hidden `RAW_SENSOR (32)` & `0x7F000011 = P010_MSB_16LE` (`Probes 4, 5, 7`):** Camera `"0"` streams `4000x3000 RAW_SENSOR` at `29.991 / 24.006 fps (0 gaps)` and `8192x6144` when paired with `720p YUV`; Camera `"2"` delivers `8000x6016 RAW_SENSOR`; `OMX.hisi` `0x7F000011` accepts `P010_MSB_16LE`.
5. **Wave B Cinema Production Promotion + Incident Hardening (`2026-11-27`, `Probes 14A, 14B, 15`):** See [Wave B & Incident Hardening Breakthroughs](wave-b-cinema-breakthroughs-20261127.md):
   - Incident hardening: quarantined `TONEMAP_MODE_PRESET_CURVE` (`0x45f`), 64-zone thermal telemetry + `shell_frame`/`boardHotspot`/`dT/dt`, `CameraHealthWatchdog`, and filtered rolling `LabDiagnosticRecorder`.
   - `Probe 14A` settled `0x7F000011` ByteBuffer mode (`U`/`V` read from offset `0`), locking in `EGL8 + achromatic TPDF -> HEVC Main 10` (`mod4 ≈ 0.250`, ~`850–907` distinct 10-bit `Y` codes) for Wave B.
   - `Probe 14B` & `Probe 15` proved True Manual Kelvin WB (`2800–10000 K` via `sensorWbValue` + `0` reset, `FIXED:ok`), CUVA HLG (`ARIB STD-B67`) OETF (`0x324`), multi-lens `4K60`/`4K30` (`"0"`, `"2"`, `"4"`, `"5"` while keeping front `"1"` capped), `4096x3072@30` Open-Gate OCLog2, and `HLG10` (`18/18` VUI) end-to-end on `Camera2PreviewEngine`.

---

## Immediate Next Actions for Following Agent

1. **Completed Wave B Production Delivery (`2026-11-27`):** Incident hardening + `Probe 14A/14B/15` verified on Pura 80 Pro (`LMR-LX9`). Debug + ResearchLab APKs built and latest ResearchLab APK installed.
2. **Next Wave C Candidates:**
   - Capture UI exposure of Variable Aperture (`f/1.6`, `f/2.0`, `f/2.8`, `f/4.0`) and Optical Image Stabilization / Video Stabilization toggles where `Probe 12` / hardware support dictates.
   - Production RAW Video (`RAW_SENSOR (32)`) integration on Pura 80 Pro (`"0"` `4000x3000@24/30`, `"2"` `8000x6016` DNG/RAW sequence).
   - Investigate minor `4K60` frame pacing jitter (`1–2` PTS gaps over threshold at thermal soak) with high-priority GL/codec scheduling or thermal headroom management.
