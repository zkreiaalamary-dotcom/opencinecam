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

1. *(Completed on device `2026-11-27` — `OpenCineCam-research (2).zip`, Run `082c0332` `47/47` under Performance Mode ON + cooler ON)* Confirmed `0` thermal pauses (`30–32 °C, status<=1`), Direct `4K60` (`720p`/`360p` ST & single-target `30 s`) at **`59.92–59.97 fps, 0 drops`**, **CUVA HDR Vivid `cuvaStateVideoMode=ByteArray[1]` (`hwBufferFormat 34 -> 804 / 0x324`, `0.0%` highlight clip)**, Kirin inverted gamma (`y ~ x^gamma`, making `gamma_2_0` / `gamma_2_2` the unclipped `4K30 OCLog2 Main10` winners), `4.2x` `0x7F000011` ByteBuffer encode (`84,556 B`, `spsBitDepthLuma=10`), and **REFUTED `H-11`** (`009` encoder-idle `4K60 previewOnly`: `CAM_CB=240` vs `SurfaceTexture` GL=`191 / ~48 fps` despite `1.852 ms` GL time).
2. *(In Progress — Wave 1 Stage 2 & 3 on connected `53K0226129002419`)* Fixing `46 done · 1 remaining` completion-sync & legacy ghost checkpoint (`c8959e8a`) resurrection bugs, fixing `LabDecode` 8-bit truncation provenance and `bestLogRecipeSummary` ranking, and executing targeted live hardware probes (`ImageReader USAGE_VIDEO_ENCODE` vs `SurfaceTexture`, `cameraSessionSceneMode=28/31`, live CUVA `0x324` MP4s, `EGL_IMG_context_priority`, hidden `RAW_SENSOR (32)`, `0x7F000011` 10-bit decode) before Wave 2 production feature promotion.
