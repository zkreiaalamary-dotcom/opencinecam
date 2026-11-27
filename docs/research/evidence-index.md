# OpenCineCam Evidence Index (`تجربه1` & `تجربه2`, Pura 80 Pro `LMR-LX9`)

Device under test: **Huawei Pura 80 Pro (`LMR-LX9`, Kirin 9020, EMUI 15, Android API 31)**  
Build fingerprint: `HUAWEI/LMR-L29/HWLMR:12/HUAWEILMR-L29/104.3.1.187C185:user/release-keys`  
Repository branch: `research/autonomous-log-lab` (branched from `research/pura80-capture-probes` @ `85574fa3`; see lineage note in [agent-handoff.md](agent-handoff.md)).  
Host verification tool: [analyze_research_export.ps1](../../tools/analyze_research_export.ps1)

---

## 1. Original Export Archives (`C:\Users\zkrei\Downloads\نتايج`)

> **Path note:** Actual Windows folder name is `نتايج` (without `ال`). Originals were never modified or deleted. Large `.mp4` and `.zip` binaries are kept out of git and indexed here by byte size, SHA-256, and container telemetry.

| Archive | Size (Bytes) | SHA-256 | Total Entries | Contained Runs | Notes |
|---|---:|---|---:|---|---|
| `OpenCineCam-research (تجربه1).zip` | `370,092,250` | `5998690928E0729556C51A0CAAAD67A9EECAAEE77D69FB12D47FAC15E477B790` | 700 | Run 0, Run 1, Run 2 | ZIP header `50 4B 03 04` |
| `OpenCineCam-research (تجربه2).zip` | `695,176,384` | `70D31CD843CFBF4FDDB762F8788EB7229949A802E991C93BB9E45AA52CEDCEDE` | 1,269 | Run 0, Run 1, Run 2, Run 3 | Cumulative `LabJournal.exportAll` export; **700/700 files from `تجربه1` are byte-identical** (`0` diffs), `+569` new files belong to Run 3 |

---

## 2. Run Inventory (Runs 0–3)

| Run Label | Run Directory UUID | Created (Local `UTC+03`) | APK Revision | Attempts | Valid / Total MP4s | MP4 Size | `evidenceBytes` Range | Battery / Thermal / SoC Max | Visual Test Type (Verified from Frames) |
|---|---|---|---|---:|---:|---:|---:|---|---|
| **Run 0** | `1795660189408-46a3e2a7-58e8-407a-95e5-a7ed1c24b169` | `1795660189408` | pre-fix (`local-1795655794308`) | 0 | 0 / 0 | 0 B | 0 | `soc_thermal=30000`, `status=0` | Pre-API-31-fix crash (prior `NoSuchFieldError`) |
| **Run 1** | `1795663114426-5b971718-e655-465c-bf76-4898aab52222` | `1795663114426` | `local-1795662984859` | 5 (paused) | 2 / 14 | 41.17 MB | `0 -> 40.7 MB` | `34.0 °C`, `status<=1`, `socMax=60000`, `sysHMax=37666` | Setup / Windows File Explorer + waving object on stick |
| **Run 2 (`تجربه1` full)** | `1795663181380-583f93ca-7798-4797-919c-a3bb20c73bac` | `1795663181380` | `local-1795662984859` | **24 / 24** (`Experiment budget reached.`) | 11 / 26 | 311.26 MB | `41.4 -> 353.3 MB` | `status<=1`, `socMax=60000`, `sysHMax=38388` | **Grayscale Gradient Test** (full-screen dark-to-white ramp + red rose bouquet left + white wall top-right + RGB PC fan bottom-right) |
| **Run 3 (`تجربه2` full)** | `1795666664106-e2c703f5-b1f6-451f-a33e-3446b5b8848c` | `1795666664106` | `local-1795662984859` | **24 / 24** (`Experiment budget reached.`) | 11 / 26 | 309.60 MB | `353.3 -> 663.6 MB` | `status<=1`, `socMax=60000`, `sysHMax=37055` | **Motion Test** (EIZO Monitor Test `13. RESPONSE TIME`, Background `Gray 50%`, Blue & White rects, Speed `1880 px/s`, Distance `150 px`) |

> **Why Run 4 hit the 1 GB warning:** `LabTelemetry.kt:124` sets `MAX_EVIDENCE_BYTES = 950_000_000L` across **all runs combined** in `filesDir/research-lab`. After Runs 0–3 consumed `663.6 MB`, a 4th subsequent run (~`310 MB` per full run) crossed `950 MB` around attempt `21/24` (`~663.6 + 286.4 = 950 MB`), explaining the exact UI readout reported by the user (`Stage F — Baseline`, `21/24`, `2/10`, `31°C`, `Thermal status: 0`). Neither Run 2 nor Run 3 was truncated (`24/24` completed in both).

---

## 3. Complete Forensics Table — All 24 Valid MP4 Clips (`ffprobe` + Packet PTS)

All 24 decoded MP4s report `yuv420p`, `color_range = tv` (Limited), `color_space = bt709`, `color_transfer = bt709`, `color_primaries = bt709`, `duplicatePts = 0`, `decodeReversals = 0`. `level = 150` = HEVC L5.0; `153` = HEVC L5.1; `40`/`42`/`51` = AVC L4.0/L4.2/L5.1.

| Run | Att | LabPath & Request | Lab Verdict | Size (B) | SHA-256 (Full) | Codec / Prof / Lvl | Raster | Frames | Dur (s) | Calc FPS | Mbps | p50 Gap (ms) | p95 Gap (ms) | Max Gap (ms) | Drops (>1.5x) |
|---|---|---|---|---:|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| R1 (`5b971718`) | `003` | `GPU_HEVC_4K30` 4K30 | `OBSERVED` | 20,255,323 | `38917AD35FF2...` *(see `ffprobe_all`)* | `hevc` Main L150 | 3840x2160 | 122 | 4.068 | 29.990 | 39.83 | 33.344 | 33.345 | 40.733 | 0 |
| R1 (`5b971718`) | `005` | `DIRECT_HEVC_SURFACETEXTURE` 4K60 | `OBSERVED` | 22,914,051 | `3BA195D4AFCB...` | `hevc` Main L153 | 3840x2160 | 229 | 3.821 | 59.920 | 47.97 | 16.689 | 16.689 | **16.689** | **0** |
| R2 (`583f93ca`) | `003` | `GPU_HEVC_4K30` 4K30 | `OBSERVED` | 19,922,267 | `50256F54D818...` | `hevc` Main L150 | 3840x2160 | 122 | 4.068 | 29.990 | 39.18 | 33.344 | 33.345 | 33.345 | 0 |
| R2 (`583f93ca`) | `005` | `DIRECT_HEVC_SURFACETEXTURE` 4K60 | `MISMATCH` | 22,420,520 | `C686F5AF5A5F...` | `hevc` Main L153 | 3840x2160 | 219 | 3.755 | 58.316 | 47.77 | 16.689 | 16.689 | 33.433 | 6 |
| R2 (`583f93ca`) | `006` | `DIRECT_HEVC_SURFACETEXTURE` 4K60 (retry) | `MISMATCH` | 22,021,130 | `D38DB5422FE4...` | `hevc` Main L153 | 3840x2160 | 219 | 3.721 | 58.841 | 47.34 | 16.689 | 16.689 | 33.367 | 4 |
| R2 (`583f93ca`) | `007` | `DIRECT_HEVC` 4K60 (`30 s`) | **`OBSERVED`** | 175,362,676 | `9EAF10B3E5ED...` | `hevc` Main L153 | 3840x2160 | **1788** | **29.837** | **59.920** | 47.02 | 16.689 | 16.689 | **16.689** | **0** |
| R2 (`583f93ca`) | `012` | `DIRECT_HEVC_PREVIEW` 4K60 | `MISMATCH` | 21,946,516 | `1DCAB7E0E29F...` | `hevc` Main L153 | 3840x2160 | 214 | 3.719 | 57.531 | 47.21 | 16.689 | 16.767 | 33.478 | 9 |
| R2 (`583f93ca`) | `014` | `HEVC` 4K30 (prod fallback -> AVC) | `MISMATCH` | 9,514,152 | `648A726999E6...` | `h264` High L51 | 3840x2160 | 120 | 4.037 | 29.724 | 18.85 | 33.344 | 33.356 | 68.922 | 1 |
| R2 (`583f93ca`) | `018` | `AVC` 1080p30 | `OBSERVED` | 9,810,839 | `EFA612F61BBF...` | `h264` High L40 | 1920x1080 | 121 | 4.035 | 29.990 | 19.45 | 33.344 | 33.345 | 33.345 | 0 |
| R2 (`583f93ca`) | `019` | `AVC` 1080p60 | `MISMATCH` | 19,476,575 | `A3D85BBAAB0D...` | `h264` High L42 | 1920x1080 | 234 | 4.138 | 56.526 | 37.65 | 16.689 | 33.366 | 50.056 | 13 |
| R2 (`583f93ca`) | `021` | `AVC` 1080p25 | `OBSERVED` | 7,972,539 | `84F2EA2BB4AE...` | `h264` High L40 | 1920x1080 | 101 | 4.040 | 25.000 | 15.79 | 40.000 | 40.000 | 40.000 | 0 |
| R2 (`583f93ca`) | `022` | `AVC` 4K25 | `OBSERVED` | 8,106,393 | `72C1DA684B10...` | `h264` High L51 | 3840x2160 | 101 | 4.040 | 25.000 | 16.05 | 40.000 | 40.000 | 40.000 | 0 |
| R2 (`583f93ca`) | `023` | `AVC` 4K30 | `OBSERVED` | 9,816,040 | `B8ED69B53EEE...` | `h264` High L51 | 3840x2160 | 122 | 4.068 | 29.990 | 19.30 | 33.344 | 33.345 | 33.345 | 0 |
| R3 (`e2c703f5`) | `003` | `GPU_HEVC_4K30` 4K30 | `OBSERVED` | 20,510,945 | `4DBD015A928B...` | `hevc` Main L150 | 3840x2160 | 124 | 4.135 | 29.990 | 39.69 | 33.344 | 33.345 | 33.345 | 0 |
| R3 (`e2c703f5`) | `005` | `DIRECT_HEVC_SURFACETEXTURE` 4K60 | `MISMATCH` | 20,262,535 | `6A898C84F906...` | `hevc` Main L153 | 3840x2160 | 215 | 3.689 | 58.279 | 43.95 | 16.689 | 16.689 | 33.378 | 6 |
| R3 (`e2c703f5`) | `006` | `DIRECT_HEVC_SURFACETEXTURE` 4K60 (retry) | `MISMATCH` | 20,603,806 | `6891982940AA...` | `hevc` Main L153 | 3840x2160 | 218 | 3.754 | 58.063 | 43.91 | 16.689 | 16.689 | 33.367 | 7 |
| R3 (`e2c703f5`) | `007` | `DIRECT_HEVC` 4K60 (`30 s`) | **`OBSERVED`** | 185,226,017 | `FFAD3928AD95...` | `hevc` Main L153 | 3840x2160 | **1780** | **29.704** | **59.923** | 49.89 | 16.689 | 16.722 | **17.389** | **0** |
| R3 (`e2c703f5`) | `012` | `DIRECT_HEVC_PREVIEW` 4K60 | `MISMATCH` | 16,933,604 | `BDE98A0712F4...` | `hevc` Main L153 | 3840x2160 | 223 | 3.739 | 59.646 | 36.23 | 16.689 | 16.711 | 33.333 | 1 |
| R3 (`e2c703f5`) | `014` | `HEVC` 4K30 (prod fallback -> AVC) | `MISMATCH` | 9,620,035 | `F252039E8772...` | `h264` High L51 | 3840x2160 | 121 | 4.076 | 29.680 | 18.88 | 33.344 | 33.356 | 68.422 | 1 |
| R3 (`e2c703f5`) | `018` | `AVC` 1080p30 | `MISMATCH` | 9,119,634 | `68F5FFEBAE3B...` | `h264` High L40 | 1920x1080 | 122 | 4.101 | 29.744 | 17.79 | 33.344 | 33.345 | 66.678 | 1 |
| R3 (`e2c703f5`) | `020` | `AVC` 1080p25 | `OBSERVED` | 7,665,236 | `FBA6773CAA04...` | `h264` High L40 | 1920x1080 | 102 | 4.080 | 25.000 | 15.03 | 40.000 | 40.000 | 40.744 | 0 |
| R3 (`e2c703f5`) | `021` | `AVC` 1080p60 | `MISMATCH` | 19,382,434 | `B54FD11B98E8...` | `h264` High L42 | 1920x1080 | 234 | 4.072 | 57.456 | 38.08 | 16.689 | 16.689 | 33.378 | 10 |
| R3 (`e2c703f5`) | `023` | `AVC` 1080p24 | `OBSERVED` | 7,559,087 | `0DC519A3537F...` | `h264` High L40 | 1920x1080 | 99 | 4.125 | 24.002 | 14.66 | 41.656 | 41.656 | 42.377 | 0 |
| R3 (`e2c703f5`) | `024` | `AVC` 4K24 | `OBSERVED` | 7,744,691 | `ED4C9C92B690...` | `h264` High L51 | 3840x2160 | 98 | 4.082 | 24.006 | 15.18 | 41.656 | 41.667 | 42.366 | 0 |

---

## 6. New Field Archives (`C:\Users\zkrei\Downloads\نتايج\جديد`, APK `local-1795672928556`)

| Archive | Size (Bytes) | SHA-256 | ZIP Status & Entries | Contained Active Runs |
|---|---:|---|---|---|
| `OpenCineCam-research.zip` | `1,911,843,118` | `CCD58E4FCAC8EEBF398A99C4353EB2B3D4FE096A2CBE09CC12954DB5F2A7C39F` | Valid (`2,971` entries / `2,970` manifest) | `13` run dirs (`11` old/0-attempt + **Run A** `a26cd8da` + **Run B** `922a793c`) |
| `OpenCineCam-research (1).zip` | `2,264,357,006` | `728B4C7D68E0B0FA8A40F3E245B8E8EE583AE84A18271208CFAA2988873F4F0F` | **100% strict superset** (`3,146` entries / `3,145` manifest; `2,965` identical, `5` updated on resume, `0` missing) | Same + completed **Run B** (`922a793c` after resume from pauses) |
| `OpenCineCam-research (2).zip` | `990,622,525` | `68DEC1E61611C428B9FD5AFD5F45697AF033590182B0064D0B0C928AFC1E9289` | **Malformed / truncated stream** (ZIP header `50 4B 03 04`, no EOCD; `ZipFile.OpenRead` fails) | Unreadable incomplete export |

### Active Runs Extracted Without Double Counting

- **Run A (`1795675379470-a26cd8da-2059-40a4-85af-305f064ce7ff`):** `19` attempts (`5` `OBSERVED`, `455.4 MB`, `19` MP4s), stopped at **Attempt `019`** (`Stage D · DIRECT_HEVC_SURFACETEXTURE_360P · sustained · retry #1`) at `+299.9 s` (`TIME_SLICE_LIMIT_MS`), `batt=31.0 °C`, `thermalStatus=0`, `evMB=1410.5`, `freeGB=120.69`.
- **Run B (`1795675910967-922a793c-bd14-4f5f-b2a3-d932ba87eba9`):** `26` attempt dirs (`24` completed: `10` `OBSERVED`, `853.0 MB`, `23` MP4s), paused at **Attempt `018`** (`+299.9 s`, `Time limit reached`, usable `26.65 s` / `86,212,271 B` / `1,560` pkts / `58.533 fps` MP4) and manually paused at **Attempt `019`** (`+557.4 s`, usable `2.415 s` / `11,160,456 B` MP4), then resumed to `Experiment budget reached (24/24 attempts)` (`batt=30 °C`, `thermalStatus=0`). Note also three `0`-attempt battery-limit stops (`38e84a29`, `2eadb3c3`, `5e997001`) + one `0`-attempt (`aa4037c4`) on `local-1795672928556`.
- **Breakthroughs in Run A & Run B:**
  - **Live `4K30 OCLog2 HEVC Main 10` (`R-B 003`, `004`):** `39.29–39.46 Mbps`, `123` / `122` pkts, `29.990 fps`, `p50=33.344 ms`, `maxG=33.345 ms`, **`0` drops**, `hevc/Main 10/yuv420p10le`, `ffprobe` + `ffmpeg signalstats` confirmed valid 10-bit pixels (`YMIN=38..YMAX=890` on `003`, shifted high `YAVG=806.8` on `004` `FLAT`).
  - **Synthetic `001` (`Main10 L6.2 + egl8` vs `egl10`):** `Main10 L6.2 (16777216) + egl8` PASSED at both 4K30 (`main10_prof_level_bt709_lim_4k30_egl8`, `main10_prof_level_oclog2_full_4k30_egl8`) and 4K60 (`main10_4k60_best`): `30/30` samples, `yuv420p10le`, `layout=PASS`, `bits=8` (8-bit source quantized in 10-bit ramp). Both `Main8` & `Main10` `egl10` (`RGBA1010102`) arms passed `configure()+start()+swp=30+outFmt=True` and emitted **`0` encoded samples**.
  - **`4K60` GPU Bottleneck (`R-B 006/008/012`):** Camera2 delivered `59.94 fps` (`263` / `261` frames, `0` drops), while GL/Muxer dropped `47–49` frames (`48.09–48.45 fps`, `192–196` pkts). `OpenCineLogFrameDiagnostics` localized GL stall `maxTotal=32.289 ms > 16.69 ms` (`encDraw=14.612 ms`, `encSwap=18.651 ms`, `prevDraw+prevSwap=5.3 ms`).
  - **`DIRECT_HEVC_SURFACETEXTURE_360P`:** Passed 4 s (`R-A 017` `59.902 fps` `0` drops; `R-B 016` `59.896 fps` `0` drops) alongside `1`-drop `R-A 016`; failed 30 s sustained (`R-B 017`: `57` drops; `R-B 020`: `39` drops), whereas single-target `DIRECT_HEVC` 30 s (`R-B 021`: `1,785` pkts, `59.922 fps`, `49.21 Mbps`) had **`0` drops**.
  - **Kirin ISP Highlight Recovery (`R-B 022/023`):** Locked exposure (`16.666 ms`, `ISO 580–581`): `GAMMA_1_0` clipped `75.2%` (`mean=235.9`, `p50=255`), `SAFE_LINEAR_ROLLOFF` clipped `74.8%` (`mean=236.4`), whereas **`TONEMAP_MODE_GAMMA_VALUE = 2.2`** gave `max=172`, `p99=143`, `mean=92.7`, **`0%` clip & `0%` crush**.
  - **Unblinded `SOURCE_FORMAT_PROBE` (`R-B 024`):** Cleanly probed cameras `0, 2, 4, 5`: no `RAW_SENSOR`/`RAW10`/`YCBCR_P010` admitted; `PRIVATE` (`4096x3072` / `3984x2240` / `4000x3000`) admitted (`dataSpace=null`); **`com.huawei.capture.metadata.cuvaStateVideoMode`** confirmed as request & session key.

---

## 7. Run `f8d3e998` & `7d0db6a5` (`49` Attempts, APK `local-1795682313520`, Power Saving Confound)

- **Archive:** `C:\Users\zkrei\Downloads\نتايج\جديد\OpenCineCam-research.zip` (`1,049,545,595` B, SHA-256 `ABD3A2B8EC919C5B4825B0E65336E3D500335A344EEAE4DAFA83155C4E72C466`, `4,457` entries, `15` run dirs).
- **Run `1795682876020-f8d3e998-2a48-4266-b201-496a0bf5026a` (`0` attempts):** Aborted by `Research paused after thermal cooldown (32.0 °C, status=MODERATE).` (`batteryTempTenthsC=320`, `thermalStatus=2`, `sysZones.soc_thermal=60000`).
- **Run `1795682997766-7d0db6a5-158a-47fc-aee9-ad3ea5334e87` (`49` attempts, `26` `OBSERVED`, `38` MP4s / `997.4 MB`, `1.05 GB`, `batt=[22.0..32.0 °C]`, ran under Huawei Power Saving ON & Performance OFF):**
  - Stopped prematurely by `Core experiment budget reached (36/36)` (`core=36, adaptive=13`), marking `summary.json` finished prematurely.
  - **`001` (`17` arms + `ByteBuffer`):** `Main10 L6.2 + egl8` PASSED (`4` Main10 arms incl. `dither` & `4K60`, `layout=PASS`, `bits=8`); all `egl10` (`RGBA1010102`, incl. `HLG`/`PQ`/`FP16`) emitted `0` pkts; `HISI_VENDOR_0x7F00000F` (`2130706447`) and `HISI_VENDOR_0x7F000011` (`2130706449`) BOTH **admitted (`admitted=true`)** on `Main10 L6.2`.
  - **`002..006` 4K30 LOG/GPU:** `003` (`29.99 fps, 0d`), `004` FLAT (`29.99 fps, 0d`), `005` `gamma_1_8` (`30.00 fps, 0d`), `006` `GPU_HEVC_4K30` (`30.00 fps, 0d`) ALL `OBSERVED`.
  - **`007..016`, `029..031` 4K60 GPU/LOG (`POWER_MODE_CONFOUNDED`):** All `13` attempts throttled to `47.80–48.30 fps` (`192–196` pkts, `46–50` drops, `11@60 -> 4/1` periodic metronome) even with GL `maxTotalUs = 14.92–15.18 ms` (`015`/`016`); must be re-tested with Power Saving OFF + `previewOnly` 4K60 control.
  - **`017/018`, `023/024/025`, `032/033`, `037` Direct 4K60 & 1080p60 (`22–27 °C`):** ALL **`OBSERVED`** (`0` drops), including **`018` `DIRECT_HEVC_SURFACETEXTURE 720p` 30 s (`1,786` pkts, `59.93 fps`)**, **`033` `360p` 30 s (`1,784` pkts, `59.93 fps`)**, **`024` `DIRECT_HEVC` 30 s (`1,801` pkts, `59.93 fps`)**, and **`037` `AVC 1080p60` (`245` pkts, `59.92 fps`)**.
  - **`019` & `020` Locked ISP (`20 ms`, `ISO 407`):** `GAMMA_2_2` (`max=199, 0%` clip) and `GAMMA_2_0` (`max=253, 0.056%` clip, `spread=221`) preserved highlights while `GAMMA_1_8..1_0` (`25.6%–64.8%`) and `FLAT`/`ROLLOFF`/`KIRIN_ATTENUATED` (`60.9–64.6%`) clipped.
- **`OpenCineCam-research (2).zip` (Latest Performance-Mode Export, `2026-11-27`):** `2,019,408,174` B (`1.88 GiB`), `SHA-256: 9278423C4ECD83625C5555D94E2305281A0A819DD290EE073C473F59C8E90428`, `5,902` entries across `17` runs (`001..047` in **`1795725149085-082c0332-9c36-41d0-bfb8-03f7e650e440`**).
  - **Key `082c0332` Records (`local-1795724731646`, `PERFORMANCE`, cooler ON, `batt=30–32 °C`, `status<=1`):**
    - `001`: `17` synthetic arms (`4 Main10 L6.2 egl8 PASS`, all `egl10` 0 pkts) + `ByteBuffer`: `YUV420Flexible` (`20,266 B`), `0x7F00000F` (`19,993 B`), **`0x7F000011` (`84,556 B`)**, all `spsBitDepthLuma=10`.
    - `003..007`: 4K30 `OCLog2 Main10` (`29.99 fps`, `0` drops, `39.7–39.8 Mbps`, `yuv420p10le`); `004 (gamma_2_0)` Y=[13..975] (`884/1024` distinct); `005 (gamma_2_2)` Y=[24..878] (`807/1024` distinct); `007 (FLAT)` YLOW=582 / YAVG=841.8.
    - `009 (4K60 PREV, encoder idle)`: `CAM_CB=240` (`59.43 fps`), `glFrames=191`, `glGaps=45` (~48 fps) despite `maxTotalUs=1.852 ms`.
    - `010..019, 032..034` (`4K60 GPU/LOG`): `47.60–48.04 fps` (`47–49` drops) with `CAM_CB 59.93 fps` (`0` drops).
    - `020/021, 026/027, 028, 035/036` (`Direct 4K60` 4s & 30s): **`59.92–59.97 fps`, `0` drops** across single-target, `720p ST`, and `360p ST`!
    - `022, 023` (`10/20 ms, ISO 504` locked): `GAMMA_2_6..2_0` (`0%` clip) vs `GAMMA_1_8` (`13.56%`), `GAMMA_1_0` (`71.60%`), `FLAT` (`74.85%`).
    - `025 (ISP_CUVA_HDR_PROBE)`: `cuvaStateVideoMode = ByteArray[1]` accepted (`hwBufferFormat: 34 -> 804 (0x324)`), `highlightClip: 0.00118 -> 0.0`, `mean: 144.72 -> 160.28`.
    - Ghost run isolated: `1795684064118-c8959e8a-db77-4540-beae-32dfa8dc4f49` (1 `STOPPED` attempt, `PAUSED`, `v2`) caused false UI checkpoint banner after `082c0332` `COMPLETED`.

- **`022` `ISP_CUVA_HDR_PROBE`:** Threw `IllegalArgumentException: Not an array: class java.lang.Byte`, isolating exact **`ByteArray` (`[B`)** requirement for `cuvaStateVideoMode`.

---

## 4. Failed, Header-Only & Zero-Byte Artifacts (42 MP4 files across Runs 1–3)

| Attempts | File Pattern | Bytes | Container / Failure Signature | Root Cause Summary |
|---|---|---:|---|---|
| `R1-001`, `R2-001`, `R3-001` | `synthetic_*.mp4` (10 files/run, 30 total) | `0` | Empty file created by `MediaMuxer(...)` before GL shader compile failure; not deleted in `finally` | `main8_no_level` & `main10_no_level` failed at `configure` (`0x80001001`); all **10 explicit-level arms passed `configure()` + `egl_window` + `start()`** and failed at GLSL compile `0:2: P0005: #version must be on the first line` (`LabSyntheticEncoderProbe.kt:504` lacked `.trimIndent()`). |
| `R1-002`, `R2-002`, `R2-016`, `R3-002`, `R3-016` | `clip.mp4` (5 files) | **`3,429`** | Top-level ISO-BMFF boxes: `ftyp(24), moov(793), free(2596), mdat(1)`; **0 video samples** (`mux=0`, `cam=128..129`) | `LOG_REGULAR_4K30` (`Main10`, `level=16384`, `EGL10`, `BT2020_LINEAR_EXT`) passed `configure`, `egl_window`, `codec_start`, and emitted `muxer_track` (`csd-0 = 87 B`), then emitted **zero packets** over `~4.3 s` and failed `hasRequiredEncodedSamples(0)` at finalization. |
| `R1-004`, `R2-004`, `R3-004`, `R2-017`, `R3-017` | `clip.mp4` (5 files) | `0` | Empty placeholder; exception at `OpenCineLogGpuPipeline.kt:1083` (`configure`) | `GPU_HEVC_4K60` / `LOG_REGULAR_4K60` passed `60_000_000` into `RecordingDataRate.gpuVideoBps(..., 60)`, doubling bitrate to `120_000_000` bps (`> 100_000_000` encoder ceiling) -> `IllegalArgumentException`. |
| `R2-015`, `R3-015` | `clip.mp4` (2 files) | `0` | Empty placeholder; `MediaCodec$CodecException: Error 0x80001001` at `configure` | `LOG` HFR `1080p120` configured `profile=2 (Main10)`, **no `KEY_LEVEL`**, `bitrate=80_000_000` (Main10 only advertises `2:16777216` L6.2, and `LabVideo` omits explicit `KEY_LEVEL`). |

---

## 5. Non-Video Empirical Probe Attempts (No MP4 Expected)

| Run / Attempt | Path | Verdict | Key Quantitative Result |
|---|---|---|---|
| `R2-008`, `R3-008` | `ISP_TONEMAP_AB` (4K30 YUV) | `OBSERVED` | `TONEMAP_MODE_CONTRAST_CURVE (0)` accepted (`maxPts=257`). R2: `mean 88.1 -> 179.9`, `p50 53 -> 226`, `highlightClip 0.02% -> 44.41%`. R3: `mean 121.7 -> 212.9`, `p50 149 -> 255`, `highlightClip 0.02% -> 68.98%`. |
| `R2-009`, `R3-009` | `ISP_COLOR_CORRECTION_PROBE` | `OBSERVED` | API 31 empirical discovery: `COLOR_CORRECTION_MODE` `[1=FAST, 2=HIGH_QUALITY, 0=TRANSFORM_MATRIX]` (mode 0 `mean=107.3, max=233, 0% clip`), AND `TONEMAP_MODE_GAMMA_VALUE (3)` (`gamma=2.2`, `mean=101.5, min=19, max=199, 0% clip`). Arm 5 (`PRESET_SRGB`, mode `4`) triggered `CameraDevice was already closed`. |
| `R2-010`, `R3-010` | `SOURCE_FORMAT_PROBE` | **`OBSERVED` (FALSE NEGATIVE)** | **`cameras: []` (empty!)** because `cameraserver` transiently restarted/hid Camera 0 right after `009` Arm 5 crashed it! Only verified `huaweiCameraKitClasses = false`. `RAW_SENSOR`/`RAW10`/`P010` probe NEVER ran! |
| `R2-011`, `R3-011` | `DIRECT_HEVC` 4K60 (4s) | `FAILED` | `getCameraCharacteristics:967: Unable to retrieve camera characteristics for unknown device 0: No such file or directory (-2)` — direct casualty of the transient CameraService fault after `009`/`010`. |
| `R2-013`, `R3-013` | `CODEC_LEVEL_PAIR` | `MISMATCH` | `OMX.hisi.video.encoder.hevc` Main 4K30: `level=null` -> `configure` `0x80001001`; `level=65536 (L5.1)` -> `start` OK (`admitted=true`). Reproduces across R2 and R3 (`H-01` proven, though `knowledge.json` missed it via event-name bug). |
| `R2-020`, `R3-019`, `R3-022` | `AVC previewOnly=true` | `OBSERVED` | 1080p60 (`R2-020`, `R3-022`) & 1080p30 (`R3-019`) preview-only Camera2 baselines completed without error. |
| `R2-024` | `AVC 4K60` (unlisted) | `MISMATCH` | Sensor reported `59.925 fps` (`10` frames, `[60, 60]`), rejected because `Camera2PreviewEngine` substituted `1920x1080` for unlisted `3840x2160@60`. |

---

## 6. Stage G Live Hardware Probes (`Probes 1–8`, `53K0226129002419`, `2026-11-27`)

| Wave / Probe | Artifact & Scope | Outcome / Key Numbers |
|---|---|---|
| Wave 1 `Probe 1` | `probe1_4k60_input.json` | `1A` ST `48.83 fps (25 gaps)` & `1B` `GPU_SAMPLED` `49.22 fps (26 gaps)` vs **`1C` `ImageReader(PRIVATE, GPU\|VIDEO_ENCODE)`: `camCb=59.36 fps (0 gaps)`, `consumer=158 frames / 59.36 fps (0 gaps, max 16.907 ms)`** (`30 -> 31 °C`) |
| Wave 1 `Probe 3` | `probe3_synth_4k60_priority.json` | `EGL_CONTEXT_PRIORITY_HIGH_IMG (0x3101)` verified (`0x3102 -> 0x3101`); `Main10 L6.2 4K` `p95` `19.66 -> 16.32 ms` (`84.40 fps`) |
| Wave 1 `Probe 4` | `probe4_hidden_raw.json` | Paired `720p YUV`: Camera `"0"` `8192x6144` (`100.66 MB`) & `4000x3000` (`24.19 MB`) `RAW_SENSOR (32)`; Camera `"2"` `8000x6016` (`96.26 MB`) |
| Wave 2 `Probe 6` | `probe6_zerocopy_4k60_oclog2.json` | **`6C_zerocopy_4k60_oclog2_cuva` (`31,846,362 B`):** `hwFmt=0x324`, `eglCreateOk=191 (0 fail)`, `glErrors=0`, `camCb=59.925 fps (0 gaps)`, `glRendered=59.925 fps (0 gaps)`, `79.93 Mbps`, 10-bit `Y`: `757/1024` distinct, `lsbNz=0.7523`, `clipLo=0, clipHi=0` |
| Wave 2 `Probe 2` | `probe2_cuva_hdr.json` | `2C1` Direct `4K60 CUVA`: `15,312,784 B`, `59.920 fps (0 gaps, 158 frames, Main10 L153)`; `2D` `EGL10` WindowSurface confirmed `3,429 B / 0 pkts` |
| Wave 2 `Probe 5` | `probe5_bb10.json` | `0x7F000011` (`bb_7f11_p010_msb.mp4`, `7,973 B`, `15` samples, `spsBitDepthLuma=10`) confirmed `P010_MSB_16LE` |
| Wave 2 `Probe 7` | `probe7_raw_streaming.json` | Camera `"0"` `4000x3000 RAW_SENSOR`: `29.991 fps (79 frames, 0 gaps)` & `24.006 fps (64 frames, 0 gaps)` |
| **Wave 3 `Probe 8`** | `probe8_production_verification.json` | **`OK (1 test)` (`41.508 s`, `21 -> 23 °C`):** `235` LOG profiles (`4K60 CUVA=true`, `4K30 CUVA=true`, `4K60 video=true`); `prod_4k60_oclog2_15s.mp4` (`187,090,075 B`, `camCb=930 f / 59.940 fps (0 gaps)`, `898` pkts, `59.787 fps`, `99.66 Mbps`, `Main10 L153`, `containerVuiTransfer=2`, `Y10=[78..876]`, `755/1024` distinct, `clip=0`, upright verified); `prod_4k30_oclog2_5s.mp4` (`35,433,952 B`, `29.990 fps`, `151` pkts, `0` gaps) |
