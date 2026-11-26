# Deep Research & Engineering Analysis — Huawei Pura 80 Pro (`LMR-LX9`, Runs 0–3)

Independent forensic analysis of `OpenCineCam-research (تجربه1).zip` and `OpenCineCam-research (تجربه2).zip` from `C:\Users\zkrei\Downloads\نتايج`, cross-checked directly against raw `.mp4` packet streams (`ffprobe`/`ffmpeg` 9.0.2), full-resolution (`3840x2160`) pixel measurements, temporal ROI frame diffs, JSON attempt journals, `telemetry.jsonl`, `inventory.json`, and Kotlin/GLSL source code on `research/autonomous-log-lab` (`281dc2b8`).

---

## 1. Archive Provenance & Experimental Identity

- **Relationship between the two ZIPs:** `LabJournal.exportAll()` archives the entire `filesDir/research-lab` tree cumulatively. Every single file in `OpenCineCam-research (تجربه1).zip` (`700/700` files: Run 0, Run 1, Run 2) is **100% byte-identical** (`0` SHA-256 differences) inside `OpenCineCam-research (تجربه2).zip` (`1,269` files), which adds the `+569` files of **Run 3**.
- **True Run Identity (Verified Visually from Decoded Frames, NOT Filenames):**
  - **Run 1 (`1795663114426-5b971718...`, 5 attempts, user-paused):** Windows File Explorer on monitor + manual hand-held box on a wooden stick during setup (`R1-003` & `R1-005`).
  - **Run 2 (`1795663181380-583f93ca...`, `تجربه1` primary 24/24 run):** **Grayscale Gradient Test** — full-screen horizontal/vertical (in portrait-rotated output) smooth dark-to-bright grayscale gradient on the PC monitor (`x0..x450`), red rose bouquet left of screen, off-white room wall top-right, RGB lit PC tower bottom-right. Note: hand/box briefly crossed bottom-left corner in `R2-003` and `R2-007 t=2s` (`t=15s` and `t=26s` clean).
  - **Run 3 (`1795666664106-e2c703f5...`, `تجربه2` primary 24/24 run):** **Motion Test** — EIZO Monitor Test page **`13. RESPONSE TIME`**, UI showing Background `Gray (50 %)`, Left rectangle `Blue`, Right rectangle `White`, Speed `1880 pixels per second`, Distance `150 pixels`. Both rectangles sweep smoothly across the monitor at constant velocity.

---

## 2. Measurement Confounds: Tripod Wobble & Uncalibrated PC Monitor

| Artifact / Confound | Quantitative Observation | Analytical Separation Applied |
|---|---|---|
| **Tripod Micro-Wobble (Unintended Phone Motion)** | Measuring a static ROI (white room wall + rose petals, `270x480` proxy) yielded inter-frame `MAD = 0.26–0.88` (max `1.51`) on Motion Run 3 clips and **`MAD = 0.74`** (max `1.51`) on the completely static Grayscale control (`R2-007`). | Static wall/rose ROI is never counted as target motion. Moving EIZO strips exhibit `movingROI_MAD = 2.01–6.79` (up to `10.80`), ~`4–14x` higher than wobble, AND clean blue/white centroid displacement (`2.3–6.6 px/frame`) vs `0.07 px/frame` on `R2-007`. |
| **Uncalibrated PC Monitor & Refresh/PWM Coupling** | Background `50% Gray` patch temporal fluctuation in Run 3: `bgFlickerStd = 0.155–0.587` (`range = 0.65–2.25` 8-bit luma). Monitor white balance looks visibly cool blue (`B-G = +20..+31` on the ramp) while room white wall is nearly neutral (`R=172.8, G=177.6, B=168.0`). | Monitor cannot establish absolute sensor DR (stops), true native colorimetry, or high-frequency flicker beyond exposure integration. Camera quantization, clipping, relative tone-map shifts, and drop locations remain valid comparative measurements. |
| **Pixel-Response Ghosting & Rolling Shutter** | EIZO moving rectangles move at `1880 px/s` across the LCD; combined with camera exposure integration and LCD pixel response (`response time` test pattern itself!), leading/trailing edges smear slightly (`rectStepPx` jumps only when frames drop or wrap around). | Temporal frame uniqueness was verified by centroid tracking across `25` consecutive frames rather than raw whole-frame MAD alone. |

---

## 3. Frames, Timing & Bottleneck Isolation (4K60 & 1080p60)

### Exact Stage-by-Stage Cadence Alignment (`Sensor -> GPU -> Muxer -> MP4`)

| Run / Attempt | Path & Request | Sensor (`cam`) | GPU Submit (`gpu`) | Muxer In (`mux`) | MP4 File | Drops (`cam -> gpu -> mux`) & True Origin |
|---|---|---:|---:|---:|---:|---|
| `R1-005` | `DIRECT_HEVC_ST` 4K60 (`4 s`) | `227` (`maxG` clean) | — | — | `229` (`59.920 fps`, `maxG=16.689`) | `0 -> — -> 0` (**CLEAN**) |
| **`R2-007`** | `DIRECT_HEVC` 4K60 (**`30 s`**) | `1784` (`59.925`, `0` drops) | — | — | **`1788`** (`59.920 fps`, `maxG=16.689`) | **`0 -> — -> 0` (ZERO-DROP SUSTAINED 4K60)** |
| **`R3-007`** | `DIRECT_HEVC` 4K60 (**`30 s`**) | `1777` (`59.924`, `0` drops) | — | — | **`1780`** (`59.923 fps`, `maxG=17.389`) | **`0 -> — -> 0` (ZERO-DROP SUSTAINED 4K60)** |
| `R2-005/006/012` | Direct 2-target 4K60 (`ST` / `PRIVATE`) | `6`, `4`, `9` drops | — | — | `6`, `4`, `9` drops (`maxG=33.48 ms`) | **HAL Dual-Target Contention** (`camDrops == fileDrops` 1:1!) |
| `R3-005/006/012` | Direct 2-target 4K60 (`ST` / `PRIVATE`) | `6`, `7`, `1` drops | — | — | `6`, `7`, `1` drops (`maxG=33.38 ms`) | **HAL Dual-Target Contention** (`camDrops == fileDrops` 1:1!) |
| `R1/R2/R3-003` | **`GPU_HEVC_4K30`** (`OMX` HEVC Main) | `131..132` (`29.991`, `0` drops) | *(unwired bug)* | `122..124` (`0` drops) | `122..124` (`29.990 fps`, `39.2–39.8 Mbps`) | `0 -> — -> 0` (**CLEAN GPU->HEVC 4K30**) |
| `R2-018` | `AVC 1080p30` (GPU) | `132` (`29.991`, `0` drops) | `121` (`0` drops) | `121` (`0` drops) | `121` (`29.990 fps`, `0` drops) | `0 -> 0 -> 0` (**CLEAN**) |
| `R3-018` | `AVC 1080p30` (GPU) | `132` (`29.991`, **`0` drops**) | `122` (**`1` drop**, `66.69 ms`) | `122` (`1` drop) | `122` (`29.744 fps`, `1` drop) | **Post-Sensor GL / SurfaceTexture / Encoder Drop** (`cam=0 -> gpu=1`) |
| `R2-019` | `AVC 1080p60` (GPU) | `270` (`59.925`, **`0` drops**) | `234` (**`13` drops**, `50.06 ms`) | `234` (`13` drops) | `234` (`56.526 fps`, `13` drops) | **Post-Sensor GL / Encoder Backpressure** (`cam=0 -> gpu=13`) |
| `R3-021` | `AVC 1080p60` (GPU) | `264` (`59.925`, **`0` drops**) | `234` (**`10` drops**, `33.38 ms`) | `234` (`10` drops) | `234` (`57.456 fps`, `10` drops) | **Post-Sensor GL / Encoder Backpressure** (`cam=0 -> gpu=10`) |

### Visual Motion Verification (`R3_007` vs `R3_003` vs `R3_021` in `270x480` scaled portrait space)
- **`R3-007` (`DIRECT_HEVC` 4K60, `0` PTS drops):** Tracking the blue EIZO rectangle centroid across `25` consecutive frames shows continuous, monotonic forward progression on **every single frame**: `y = 289.6 -> 292.6 -> 297.2 -> 301.4 -> 304.9 -> ... -> 389.6` (`+3.0 to +5.5 px/frame`, mean `+4.17 px/frame` in 270x480 = `~33.3 px/frame` in 4K = `~1998 px/s` matching EIZO `1880 px/s` within framing/lens perspective!). **Zero duplicate/frozen frames.**
- **`R3-003` (`GPU_HEVC_4K30`, `0` PTS drops):** Clean `~2x` step per frame (`6–10 px/frame` at 30 fps) with wrap-around when rectangle exits/re-enters (`f13->f14`), zero duplicates.
- **`R3-021` (`AVC 1080p60`, `10` PTS drops):** Visual tracker caught exact frozen duplicate-like transitions (`stripMAD = 0.00`, `blueY = 396.8 -> 396.8` at `f04->f05` followed immediately by a `2x` jump `396.8 -> 405.8`!), matching the `gpu/mux` timestamp drops (`maxG = 33.38 ms`). **Drops are genuine frame drops inside the GPU/encoder loop, not monitor stutter.**

---

## 4. Grayscale Ramp & Image Quality Forensics (Run 2 vs Run 3)

### 4.1 Full-Resolution (`3840x2160`) Grayscale Monitor Ramp ROI (Run 2)
Sampling `451` horizontal columns (`nx = 1350..3150`, `ny = 560..680`) cleanly across the monitor gradient:

| Clip | Ramp Y Range `[min..max]` | Span | Distinct Bins | Single-Bin Gaps (`maxGap`) | Clip `>=235` / `>=250` | Crush `<=16` | Within-Column Noise Std | Mean Cast (`R-G` / `B-G`) | Wall Neutral (`R, G, B`) |
|---|---|---:|---:|---:|---|---|---:|---|---|
| `R2-003` (`GPU_HEVC_4K30`) | `28..234` | `206` | `182` | `25` (`max=1`) | `0% / 0%` | `0%` | `0.74` | `-5.39 / +19.84` | `(172.8, 177.6, 168.0)` |
| **`R2-007` (`DIRECT_HEVC_4K60`)** | `26..231` | `205` | **`188`** | **`18`** (`max=1`) | `0% / 0%` | `0%` | `0.56` | `-5.96 / +20.88` | `(169.8, 172.8, 163.5)` |
| `R2-014` (`AVC_FB_4K30`) | `29..234` | `205` | `183` | `23` (`max=1`) | `0% / 0%` | `0%` | `0.53` | `-4.51 / +19.91` | `(174.4, 177.4, 167.9)` |
| `R2-023` (`AVC_4K30`) | `25..230` | `205` | `183` | `23` (`max=1`) | `0% / 0%` | `0%` | `0.53` | `-5.72 / +19.66` | `(166.8, 172.9, 163.2)` |

### 4.2 Answers to Specific Visual Quality Questions
1. **Brightness, Black/White Levels & Clipping:** Recorded SDR clips (`tv` Limited `16–235` BT.709, decoded to full-range RGB) keep `Y=[25..234]` (`0%` highlight clip `>=235`, `0%` black crush `<=16`, `BRNG=0.0000` in `signalstats`). Contrast, gamma, and highlight preservation on default SDR ISP are **well-behaved and unclipped** (`YAVG=89.7–91.5` in R2; `113.8–125.0` in R3).
2. **Banding / Posterization:** No multi-bin staircase banding exists inside the ramp (`maxEmptyGap = 1` across `182–188` distinct bins out of `205` Y codes; `18–25` single-step skips expected from 8-bit `tv->pc` rounding + H.264/HEVC quantization). `4K60` HEVC at `47 Mbps` (`R2-007`) preserves slightly more tonal gradations (`188` distinct bins, `18` single gaps, `0` non-monotonic reversals) than `GPU_HEVC` (`182`) and `19 Mbps` AVC (`183`).
3. **Why NO Red Tint Appeared in Run 2/3, & Origin of the Prior Red/Exposure Discrepancy:**
   - **No red cast exists** in the Run 2/3 recorded `.mp4` files: the ambient wall is neutral slightly warm-green (`R=172.8, G=177.6, B=168.0`, `VAVG=125.5–128.6` vs `128`), and the PC monitor ramp is cool **blue** (`B-G = +19.7 to +20.9`, peak `+31` at mid-gray) due to uncalibrated monitor white point + AWB reacting to crimson roses.
   - **Wait — why did earlier reports / UI mention abnormal brightness (`FLAT`) or LOG preview mismatch?**
     - **Abnormal Brightness & Highlight Clipping (`008 ISP_TONEMAP_AB`):** Confirmed caused by **`PictureProfileCurves.FLAT`** (`PictureProfile.kt:29`), whose sRGB-encoded lifted knots (`0.05->0.08`, `0.5->0.56`) sent to linear ISP `TONEMAP_CURVE` without AE lock drove `mean` from `88.1 -> 179.9` (R2) and `121.7 -> 212.9` (R3) and **clipped `44.41%` / `68.98%` of pixels to `255`**!
     - **LOG Preview vs Recording Mismatch (Code Inspection):** In `OpenCineLogGpuPipeline.kt`:
       - Preview runs `viewAssist709()` or `flatMonitor` (`mix(luma, displayLinear, 0.68)` + `encodeOcLog2`) on an **8-bit EGL surface (`config8`, `previewContext`)**.
       - LOG recording encodes `encodeOcLog2(uSceneGain * bt709ToBt2020(inverseRec709(...)))` onto **`config10` (`RGBA1010102`)** with `EGL_GL_COLORSPACE_BT2020_LINEAR_EXT` and tags MediaFormat `BT2020 / FULL` (while `SDR_BT709_ISP` actually assumes BT.709 OETF input!). On Huawei Kirin Mali/OMX, either `config10` / `BT2020_LINEAR_EXT` shifts color interpretation or drops frames (`002`), AND viewing raw BT.2020-tagged/VUI-unspecified `OCLog2` without the Resolve DCTL misinterprets primaries & transfer.
     - **Post-ISP SDR-LOG vs True LOG & 10-bit Coded vs >8-bit Info:** Strictly **zero true LOG or 10-bit sensor capture exists yet** on Pura 80 Pro (`SDR_BT709_ISP` takes 8-bit post-ISP YUV/PRIVATE frames already tone-mapped by Huawei ISP and applies a mathematical `log(1 + 50x)` remapping).

---

## 5. End-to-End Hardware & Software Technical Pipeline (`LMR-LX9` Inventory)

- **Camera2 Logical Camera `0`:** `LIMITED` (`hardwareLevel` empty/limited, no `RAW`), `outputFormats = [256 JPEG, 34 PRIVATE, 35 YUV_420_888, 1144402265 DEPTH16]`, **`hlg10Advertised = false`**, `rawSensorSizes = []`, `raw10Sizes = []`.
- **Physical Cameras behind Logical `0`:** `id="2"` (`8.5 mm`, `7600x5704`, `[256, 34, 35]`), `id="4"` (`1.95 mm`, `7296x5472`, `[256, 34, 35]`), `id="5"` (`15 mm`, `8000x6000`, `[256, 34, 35]`).
- **All 40 Accepted Engine Output Sizes (`SurfaceTexture` == `MediaRecorder`, Identical List):**
  `4096x3072`, `4096x2304`, `4096x1904`, `4096x1832`, `4096x1760`, `3840x2160`, `3072x3072`, `3264x2448`, `2720x1536`, `2560x1080`, `2416x1080`, `2408x1080`, `1920x1440`, `1920x1080`, `1440x1440`, `1440x1080`, `1280x1080`, `1248x1088`, `1280x960`, `1080x1080`, `1280x768`, `1280x720`, `1024x768`, `960x720`, `1120x480`, `720x720`, `1056x480`, `800x600`, `864x480`, `640x640`, `800x480`, `720x480`, `640x480`, `640x400`, `480x480`, `640x360`, `672x288`, `352x288`, `320x240`, `176x144`.
- **Descriptor Rejection Mechanism (`descriptorRejectionDiagnostics`):** `3840x2160` and `4096x2304` report `minFrameDurationNs = 33,331,760` (`30.001 fps`), while `1920x1080` reports `16,666,666` (`60 fps`). Even though `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` advertises `[60, 60]` and `[30, 60]` and hardware runs `3840x2160@59.923 fps` cleanly, `Camera2Descriptors.kt:78` (`streamCanReachFps`) strips `4K60` from `videoProfiles`, causing production `GPU_4K60` requests (`024`) to substitute `1920x1080@60`.
- **MediaCodec Matrix (`inventory.json`):**
  - **`OMX.hisi.video.encoder.hevc`** (`HW`): Color formats `[COLOR_FormatSurface=2130708361, YUV420Flexible=2135033992, ...]`; Bitrate `[10_000, 100_000_000]` bps; Sizes `176x144 .. 4096x4096`; `areSizeAndRateSupported = true` for `1080p30/60/120` AND `4K30/60`.
    - **`HEVCProfileMain (1)`** advertises Levels `1, 4, 16, 64, 256, 1024, 4096, 16384 (L5), 65536 (L5.1), 262144 (L5.2), 1048576, 4194304, 16777216 (L6.2)`. **MUST pass explicit `KEY_LEVEL`; omitting causes `0x80001001`.**
    - **`HEVCProfileMain10 (2)`** advertises **ONLY `level = 16777216` (`HEVCMainTierLevel62`)!**
  - **`OMX.hisi.video.encoder.avc`** (`HW`): Bitrate `[10_000, 100_000_000]`, High profile (`8`) Levels `1..65536 (L5.1)`.
- **Huawei Vendor Metadata Keys (`inventory.json`):**
  - Request/Session (5): **`com.huawei.capture.metadata.cuvaStateVideoMode`**, `cameraSessionSceneMode`, `dmWaterMarkMode`, `hwTofRepairMode`, `stellarRepairMode`.
  - Result (50): includes `hw_algo_ae_lv`, `hw_algo_mean_y`, `frameLuminance`, `multiCameraMask`, `focusVcmValue`, `isoState`, `superCameraExposureTime`, `superSlowMotionStatus`, `exposureStateHint`, `currentPpAlgo`.
- **Thermal Telemetry (`telemetry.jsonl`):** Kirin 9020 exposes real `/sys/class/thermal` zones (`soc_thermal <= 60000`, `system_h <= 38388`, `pa_0 <= 41352`, `charger <= 40294`), while `PowerManager.getThermalHeadroom()` returns `null` on EMUI 15 API 31. Full 24-attempt runs stayed at `thermalStatus <= 1` (`LIGHT`), battery `31–34 °C`, zero thermal pauses or throttles.

---

## 6. Research Lab Audit — 7 Bugs & Structural Improvements Found

| # | Location | Bug / Defect Observed in Runs 1–3 | Impact on Results |
|---:|---|---|---|
| **1** | `LabSyntheticEncoderProbe.kt:491-504` | `SYNTHETIC_VERTEX_SHADER` / `SYNTHETIC_FRAGMENT_SHADER` lack `.trimIndent()` | Leading newline before `#version 300 es` caused `0:2: P0005` on **all 10 valid arms** (`001`), preventing Main10 ramp & geometry measurement, AND leaving `10` zero-byte `synthetic_*.mp4` placeholders behind. |
| **2** | `LabRegularLogRunner.kt:271` vs `OpenCineLogGpuPipeline.kt:1044` | `bitrate = 60_000_000` multiplied by `RecordingDataRate.gpuVideoBps(..., 60)` -> `120_000_000` bps | Exceeded HiSilicon `[10K, 100M]` limit, failing `004 GPU_HEVC_4K60` and `017 LOG_REGULAR_4K60` at `configure()`. |
| **3** | `LabSourceProbe.kt:203,522` | `PRESET_SRGB (4)` in `009` crashes `cameraserver`; `010` silently skips unavailable cameras (`?: continue`) | Blinded `010 SOURCE_FORMAT_PROBE` (`cameras: []`, logged false `OBSERVED`) and crashed `011 DIRECT_HEVC` (`unknown device 0: -2`). |
| **4** | `LabKnowledgeBase.kt:283` vs `LabCodecLevelPair.kt:49,66` | KB filters `event == "codec_level_probe"` instead of `codec_arm_result` | Kept `H-01 INCONCLUSIVE` despite `013` proving `H-01` twice (`R2-013`, `R3-013`). |
| **5** | `LabKnowledgeBase.kt:250` | `baseKey` omits `previewOnly` | Emitted false `contradictions` between `AVC 1080p` recording vs `previewOnly=true` (`R2 019/020`, `R3 018/019`, `R3 021/022`). |
| **6** | `LabRegularLogRunner.kt` & `LabSourceProbe.kt:438` | Forgot `gpu.onRecordedFrame = signals::onRecordingFrame`; `ISP_TONEMAP_AB` / `ISP_COLOR_CORRECTION` leave AE/AWB unlocked | `gpu` timestamps = `0` in `002/003/016`; unlocked AE allowed `FLAT` to over-expose/clip `44–69%`. |
| **7** | `LabTelemetry.kt:124` | Hardcoded cumulative `MAX_EVIDENCE_BYTES = 950_000_000L` across all historical runs | Stopped a 4th subsequent run at `21/24` despite `~122 GB` free storage; solved by Dynamic Storage Manager in [engineering-roadmap.md](engineering-roadmap.md). |

---

## 7. Complete Epistemic Knowledge Classification (`5 Tiers`)

| Classification | Conclusion | Direct Citations (Attempt / File / Code) | Generalization Bounds |
|---|---|---|---|
| **1. Confirmed** | **Direct single-target HEVC 4K60 sustains ~30 s at `59.92 fps` with `0` drops & genuine continuous motion** | `R2-007` (`1788` f, `59.920 fps`, `0` drops), `R3-007` (`1780` f, `59.923 fps`, `0` drops, EIZO `+3.0..+5.5 px/f`), `telemetry.jsonl` (`status<=1`) | Single `MediaRecorder` target on Camera `0`, `Main` L5.1, `47–50 Mbps`, `31–34 °C`, no UI preview. |
| **1. Confirmed** | **Production `OpenCineLogGpuPipeline` records `GPU_HEVC_4K30` (`Main L5.0`, `39.2–39.8 Mbps`, `0` drops) cleanly on Pura 80 Pro when `KEY_LEVEL=16384` is passed** | `R1-003`, `R2-003`, `R3-003` (`122–124` f, `29.990 fps`, `0` drops, `OMX.hisi.video.encoder.hevc`); contrast `014 HEVC` (`level=null` -> `0x80001001` -> AVC) | SDR 8-bit `tv/bt709` (`passthroughSdr=true`, `EGL8`). Does not prove `Main10` or `4K60` GPU yet. |
| **1. Confirmed** | **`OMX.hisi.video.encoder.hevc` Main 4K30/4K60 & Main10 require explicit `KEY_LEVEL` at `configure()`** | `R2-013`, `R3-013`, `001` (`no_level` -> `0x80001001`, explicit levels `16384/65536/262144` -> `start() ok`), `LabCodecLevelPair.kt` | Proven for `Main` (`profile=1`) and `Main10` (`profile=2`) admission (`configure`+`start`). |
| **1. Confirmed** | **Empirically discovered API 31 HAL supports `COLOR_CORRECTION_MODE_TRANSFORM_MATRIX (0)` and `TONEMAP_MODE_GAMMA_VALUE (3)` despite API 36/incomplete characteristic keys** | `R2-009`, `R3-009`, `LabApiCompat.kt`, `LabSourceProbe.kt:159-243` | YUV 4K30 burst probes on Camera `0`; unlocked AE; `PRESET_SRGB (4)` is unsafe/crashes `cameraserver`. |
| **1. Confirmed** | **2-target Direct 4K60 drops originate in Camera2 HAL, whereas 1080p60/1080p30 AVC GPU drops originate post-sensor in GL/Encoder** | `R2/R3 005,006,012` (`camDrops == fileDrops`), `R2-019/R3-021/R3-018` (`cam=0 drops` -> `gpu==mux` `13/10/1` drops + R3-021 visual freeze) | Hardware/OS timing under EMUI 15 API 31 with `1280x720` second surface or single-thread GL loop. |
| **1. Confirmed** | **SDR clips in `تجربه1` & `تجربه2` have zero red cast, clean `182–188`-bin grayscale ramp fidelity, and zero highlight/shadow clipping (`BRNG=0`)** | Full-res `R2-003/007/014/023` ROI (`Y=25..234`, `B-G=+20`, `wall 173/178/168`) & `ffmpeg signalstats` | Default ISP SDR pipeline (`yuv420p tv bt709`). Does NOT hold for `FLAT` (`008`). |
| **1. Confirmed** | **Vendor HDR Vivid/CUVA key `com.huawei.capture.metadata.cuvaStateVideoMode` is exposed as request & session key on `LMR-LX9`** | `inventory.json` (`vendorKeys.request` & `vendorKeys.session`, `5` keys) | Key advertisement proven; encoding behavior & 10-bit impact remain unprobed. |
| **2. Partially Supported** | **Dual-output Direct 4K60 with live `SurfaceTexture` preview can hit zero drops (`R1-005`), but exhibits intermittent `1–9` HAL drops under current `1280x720` preview config** | `R1-005` (`59.920 fps`, `0` drops) vs `R2-005/006/012` & `R3-005/006/012` (`1–9` drops) | Needs preview-size (`640x360`) and warmup sensitivity check. |
| **3. Testable Hypothesis** | **`OMX.hisi.video.encoder.hevc` Main10 requires `Level 6.2 (16777216)` and/or `EGL8`/`LIMITED`/non-`BT2020_LINEAR_EXT` to emit frames (`002` produced `0` frames); synthetic `001` will resolve in 1 run** | `inventory.json` (`2:16777216` only Main10 PL), `R2/R3-002` (`mux=0`, `3,429 B`), `001` shader bug | Directly testable by fixing `.trimIndent()` in `LabSyntheticEncoderProbe.kt`. |
| **3. Testable Hypothesis** | **`GPU_HEVC_4K60` Main8 succeeds once `120 Mbps` double-scaling bug (`RecordingDataRate.gpuVideoBps`) is clamped to `<= 80–100 Mbps`** | `004/017` `120M > 100M` vs `001` `60M` 4K60 `start() ok` + Camera2 `59.92 fps` proven | Needs live GPU test after fixing `LabRegularLogRunner.kt:271` / `OpenCineLogGpuPipeline.kt:1044`. |
| **3. Testable Hypothesis** | **Combining `TONEMAP_MODE_GAMMA_VALUE (3)` + `TRANSFORM_MATRIX (0)` (or linear-domain `CONTRAST_CURVE`) yields clean flat Log-ready pre-conditioning without `FLAT`'s `44–69%` highlight clip** | `R2/R3-009` (`0%` clip) vs `R2/R3-008` (`44–69%` clip) | Needs locked-AE/AWB chart test. |
| **4. Inconclusive** | **Main10 true >8-bit ramp precision (`H-03`), clean Main10 geometry (`H-02`), 4K60 LOG (`H-05`), `RAW`/`YCBCR_P010` admission on physical IDs `"2","4","5"` (`H-08`), & explicit-level 1080p120 (`H-10`)** | `001` P0005 bug; `004/017` 120M bug; `010` `cameras:[]` post-crash; `015` omitted `KEY_LEVEL` | All ready to resolve on next fixed-lab run. |
| **5. Refuted (Under Specific Conditions)** | **Sending `PictureProfile.FLAT` (sRGB knots) with `EDGE=OFF/NR=OFF` & unlocked AE improves dynamic range on Pura ISP** | `R2-008` (`44.41%` highlight clip), `R3-008` (`68.98%` highlight clip, `p50=255`) | Refuted under unlocked-AE / current `FLAT_KNOTS` domain; linear-domain curves remain open. |
| **5. Refuted (Under Specific Conditions)** | **Submitting `TONEMAP_MODE_PRESET_CURVE` (`4`) safely queries support on `LMR-LX9` API 31** | `R2-009` & `R3-009` Arm 5 (`CameraDevice was already closed` -> `unknown device 0 (-2)`) | Crashes HAL/cameraserver; must not be sent without fallback/recovery isolation. |
| **5. Refuted (Under Specific Conditions)** | **Sending GPU 60 fps bitrate >= `60M * 2 = 120 Mbps` to `OMX.hisi.video.encoder.hevc` is admitted** | `R2/R3-004`, `R2/R3-017` (`IllegalArgumentException`, ceiling `[10K, 100M]`) | Hard encoder ceiling `100,000,000` bps. |

---

## 8. Integration of `نتايج\جديد` (Run A `a26cd8da` & Run B `922a793c`, APK `local-1795672928556`)

1. **Deduplication & Corrupted Tail Handling:** `OpenCineCam-research (1).zip` (`2,264,357,006` B) is a `100%` strict superset of `OpenCineCam-research.zip` (`1,911,843,118` B; `2,965` SHA-256 matches, `5` resumed updates in `922a793c`, `0` missing). `OpenCineCam-research (2).zip` (`990,622,525` B) has a valid ZIP local header (`50 4B 03 04`) but is a truncated stream without EOCD. Counting only the two unique active runs yields `19` attempts in **Run A (`a26cd8da`)** and `24` completed attempts (`26` dirs) in **Run B (`922a793c`)**.
2. **First Live 4K30 `OCLog2` `HEVC Main 10` Proof (`R-B 003`, `004`):** Both succeeded at `29.990 fps` (`39.29–39.46 Mbps`, `123`/`122` pkts, `0` drops, `hevc/Main 10/yuv420p10le`, verified `YMIN=38..YMAX=890` on `003` and shifted `YAVG=806.8` on `004` `FLAT`), unblocked by **`Main10 L6.2 (16777216) + egl8`**. Two KB bugs explained why automation missed the win: `bestOverrideFor` chose `bt709_lim` (`tv/bt709`) instead of `oclog2_full` (`pc/bt2020nc`), and `LabKnowledgeBase` read `030-file.json` (`decoded_frame_and_clip_hash`, missing `mime`) instead of `029-file.json` (`mime=video/hevc`), leaving `H-04` `INCONCLUSIVE`.
3. **Why Synthetic `egl10` Produced 0 Samples & 4K60 GPU Dropped 47–49 Frames:** In `R-B 001`, all `Main8` and `Main10` `egl10` (`RGBA1010102`) arms (`cs=null` and `cs=BT2020_LINEAR_EXT 0x333F`) passed `configure()+start()+swp=30+outFmt=True` yet produced `0` encoded packets; meanwhile `Main10 L6.2 + egl8` encoded cleanly at 4K30 and 4K60 (`30/30` samples, `bits=8`). On live 4K60 (`R-B 006`, `008`, `012`), Camera2 delivered `59.94 fps` (`263`/`261` frames, `0` drops) into `SurfaceTexture`, while `OpenCineLogFrameDiagnostics` localized drops inside the GL loop (`maxTotal=32.289 ms > 16.69 ms`: `encDraw=14.612 ms`, `encSwap=18.651 ms`, `prevDraw+prevSwap=5.3 ms` + dual `EGLContext` switch + missing `KEY_PRIORITY=0` / `KEY_OPERATING_RATE=60`).
4. **360p Dual-Target vs Single-Target 4K60 & Kirin Post-Gamma ISP Discovery:** `DIRECT_HEVC_SURFACETEXTURE_360P` passed 4 s (`R-A 017` `59.902 fps` `0` drops, `R-B 016` `59.896 fps` `0` drops) and dropped `39–57` frames in 30 s sustained (`R-B 017`, `020`), while single-target `DIRECT_HEVC` 30 s (`R-B 021`) achieved **`0` drops** (`1,785` pkts, `59.922 fps`, `49.21 Mbps`). On ISP (`R-B 022/023`, locked `16.666 ms` / `ISO 580–581`), `GAMMA_1_0` (`75.2%` clip) and `SAFE_LINEAR_ROLLOFF` (`74.8%` clip, proving Kirin `TONEMAP_CURVE` acts post-gamma) saturated, whereas **`GAMMA=2.2` recovered `100%` highlight headroom (`0%` clip, `max=172`, `p99=143`)**. Unblinded `024` checked `0,2,4,5`, confirming no public RAW/P010, `PRIVATE` admission, and `cuvaStateVideoMode`.
5. **Unlimited Session Time Implemented:** Both Run A (`019`) and Run B (`018`) were halted artificially at `+299.9 s` (`TIME_SLICE_LIMIT_MS`) while thermally cool (`30–31 °C`, `thermalStatus=0`, `118–120 GB` free). `TIME_SLICE_LIMIT_MS` has now been permanently removed alongside finite `LabSessionBudget` (`36` core + `18` adaptive = `54` max attempts), checkpoint recovery, and 4K60/EGL10/ISP/CUVA upgrades.
