# OpenCineCam Wave C — Production Fixes, 10-Bit Hardware Truth & Stock Camera Benchmark

**Target Device:** Huawei Pura 80 Pro (`LMR-LX9`, Kirin 9020, EMUI 15 / Android 12 API 31)  
**Branch:** `research/autonomous-log-lab`  
**Date:** 2026-11-27

---

## 1. Executive Summary

Wave C resolved every major gap observed between field usability on Huawei Kirin 9020 (API 31) and lab probe coverage, while establishing hardware ground truth for 10-bit capture, Huawei CUVA (`0x324`), HEVC Main10 encoding, and camera daemon stability:

1. **Fixed Rotated / Swapped-Axis Zero-Copy `ImageReader` LOG & HLG Video (`Milestone 1`):**  
   Replaced the fixed vertical flip (`IMAGE_READER_Y_FLIP_MATRIX`) in `OpenCineLogGpuPipeline.kt` with sensor-orientation-compensated `imageReaderTextureMatrix(sensorOrientationDegrees)` (`det = -1`) and unified `unmirrorSurfaceTextureMatrix` / `zeroCopyTextureMatrix`. Verified across all 7 orientations and lenses in `Probe 16A` and real-app 4K60 / 1080p60 / Tele 2.4x captures.
2. **Fixed API 31 Gallery Playback for `OCLog2` & `HLG` (`Milestone 2–3`):**  
   Added `SurfaceFrameRenderConfirmation` (`PreciseVideoFrames.kt`, implemented by `LogPlaybackRenderer` after `eglSwapBuffers`), defaulted both `OCLog2` and `HLG/HDR10` clips on API < 33 to direct Surface/GL review in `shouldDefaultNativeSurfaceFrames`, synced `LogPlaybackRenderer` EGL dimensions on resize/swap, and gated `SurfaceView` creation on `logResolved` in `MediaPlaybackDialog.kt` so Frame 0 renders visibly immediately on open instead of black.
3. **Fixed Arabic RTL Overlaps, BiDi Reversals & `4K+` Truncation (`Milestone 2–3`):**  
   Fixed vertical `FittingStack` placement and narrow (`384dp`) lock-row avoidance in `CaptureInstruments.kt`, added `TextDirection.Ltr` / `TextDirection.Content` on technical HUD/gallery/player counters (`ZoomReadout`, `CaptureStatusHud`, `CaptureSlots`, `GalleryBadge`, `DetailChip`, `MediaPlaybackDialog`), and lowered `SlotValue` auto-size minimum from `14.sp` to `11.sp` so `"4K+"` never truncates to `"4..."`. Verified visually in both RTL (`ar-w384dp`) and LTR (`w411dp`) via Compose Driver and on device.
4. **Added Kirin `CameraDaemon` Close-to-Open Settle Cooldown (`Milestone 3`):**  
   Added `HuaweiVendorControls.isHuaweiDevice()` and a `150 ms` `HUAWEI_REOPEN_SETTLE_MS` guard in `Camera2PreviewEngine.kt` between `onClosed(device)` and `runPendingPreviewStart()`. `CameraDaemon` (PID `20500`) sustained all probes (`16A`–`16D`) and E2E captures with zero daemon restarts.
5. **Proved Kirin 9020 10-Bit & CUVA Truth (`Probes 16B–16D`):**  
   - `OMX.hisi.video.encoder.hevc` genuinely honors `COLOR_STANDARD_BT2020` + `COLOR_RANGE_FULL` (`MAE = 1.12 / 1023`).
   - All GPU RGB EGL surfaces other than `RGBA8` (`RGB10_A2`, `RGBA16F`) drop 100% of frames on `OMX.hisi`, all 4 ByteBuffer color formats allocate 8-bit capacity (`3,110,400` bytes at 1080p), and `HardwareBuffer` rejects `0x324` and `0x36` (`P010`) on API 31 — confirming `hisiliconMain10Workaround` (`RGBA8` + highp TPDF dithering) is physically optimal for GL-processed `OCLog2`.
   - Conversely, **Direct Camera2 `0x324` -> `OMX.hisi` Input Surface** succeeds cleanly at true 10-bit (`43/43` frames, `yuv420p10le`, `arib-std-b67`, `928` unique `Y10` values), opening a clear Wave D opportunity for a zero-GL Direct HLG recording path.

---

## 2. Production Fixes Implemented

| Area | File(s) | Root Cause & Fix | Verification |
|---|---|---|---|
| **Zero-Copy LOG Orientation** | `camera/.../OpenCineLogGpuPipeline.kt`, `OpenCineLogGpuPipelineContractTest.kt` | `renderLatestImageReaderFrame()` used `IMAGE_READER_Y_FLIP_MATRIX` ignoring `90°`/`270°` sensor mount. Added `imageReaderTextureMatrix(sensorOrientationDegrees)` (`det = -1`). Shader hashes (`66ba3a4d...`, `25db8970...`) preserved. | `:camera:testDebugUnitTest` PASSED; `Probe 16A` 7/7 clips upright; live 4K60/1080p60/Tele upright. |
| **API 31 OCLog2 & HLG Playback + Frame-0** | `app/.../PreciseVideoFrames.kt`, `LogPlaybackRenderer.kt`, `MediaPlaybackSession.kt`, `MediaPlaybackDialog.kt`, `LogPlaybackApi31DefaultTest.kt` | API < 33 lacked CPU `P010` readback and omitted `OnFrameRenderedListener` callbacks on GL `SurfaceTexture` targets; initial `glSurface` layout resize before `logResolved` left frame 0 black `(0,0,0)`. Added `SurfaceFrameRenderConfirmation`, HLG/OCLog2 API < 33 direct default, `surfaceReady = !video || raw || logResolved`, and `eglQuerySurface` resize redraw. | `LogPlaybackApi31DefaultTest` PASSED; live Gallery `e2e_oclog_frame0.png` (`centerRGB=(111,132,145)`), `e2e_hlg_frame0.png` & `e2e_hlg_play.png` (`centerRGB=(169,174,237)`) verified. |
| **RTL Overlap, BiDi & `4K+` Slot** | `app/.../CaptureInstruments.kt`, `CaptureStatusHud.kt`, `CaptureSlots.kt`, `MediaGrid.kt`, `MediaInspector.kt`, `MediaPlaybackDialog.kt` | In RTL portrait, `FittingStack` placed column items at `x = 0` colliding with lock keys, narrow `384dp` touched lens pills, `SlotValue` (`14.sp`) truncated `4K+`, and `"HEVC 10-bit"` reversed under Arabic BiDi. Aligned RTL vertical stack right, dropped wide lens bars below lock obstacle, lowered `SlotValue` min to `11.sp`, and set explicit `TextDirection.Ltr` / `Content`. | Compose Driver RTL (`ar-w384dp`) & LTR (`w411dp`) 7/7 screenshots + live RTL HUD & Gallery (`e2e_gallery_rtl.png`) verified. |
| **Huawei `CameraDaemon` Cooldown** | `camera/.../HuaweiVendorControls.kt`, `Camera2PreviewEngine.kt` | Rapid `close -> openCamera` transitions risk `CameraDaemon` `ffrtwk` instability on Kirin HAL. Added `HUAWEI_REOPEN_SETTLE_MS = 150L` between `onClosed(device)` and `runPendingPreviewStart()`. | `:camera:testDebugUnitTest` PASSED; `CameraDaemon` PID `20500` invariant across all runs. |

---

## 3. Scientific Probe Results (`Probes 16A–16D`)

### 3.1 `Probe 16A` — Orientation & `SurfaceTexture` Matrix Audit (`WaveCProbeTest#probe16A_OrientationTruth`, `44.273s`)
- Front camera `"1"` (`270°`): `SurfaceTexture.getTransformMatrix` matched expected unmirrored matrix with `maxDiffVsExpected = 0`, `detRaw = 1`, `detUnmirrored = -1`.
- Zero-copy `ImageReader` clips (`p16a_back_port_0_compat`, `p16a_back_land_90_compat`, `p16a_back_revport_180_compat`, `p16a_back_revland_270_compat`, `p16a_back_port_0_native`, `p16a_uw_port_0_compat` on physical `"4"`, `p16a_tele_port_0_compat` on physical `"5"`): all 7 upright or exact container-rotated with zero swapped axes.

### 3.2 `Probe 16B` — Encoder CSC Matrix & Range (`WaveCProbeTest#probe16B_ColorAndEncoderTruth`, `12.478s`)
Synthetic 16-color pattern analyzed in 10-bit `yuv420p10le` (`AnalyzePatches10`):
- `enc_bt2020_full.mp4` (`COLOR_STANDARD_BT2020`, `COLOR_RANGE_FULL`): **MAE = `1.12 / 1023`** vs BT.2020 Full (vs `21.75` Limited, `5.60` BT.709).
- `enc_bt2020_limited.mp4`: **MAE = `1.08 / 1023`** vs BT.2020 Limited.
- `enc_bt709_limited.mp4`: **MAE = `1.06 / 1023`** vs BT.709 Limited.
- Conclusion: `OMX.hisi.video.encoder.hevc` Main10 strictly obeys the requested CSC matrix and range.

### 3.3 `Probe 16C` — 10-bit EGL Surfaces & 9-Stop CUVA `0x324` Curve (`WaveCProbeTest#probe16C_True10BitAndCuvaCurve`, `6.216s`)
- **EGL -> `OMX.hisi` precision:**
  - `rgb10a2Supported = true`, `rgba16fSupported = true`, `bt2020LinearExt = true`, `bt2020HlgExt = false`.
  - Both `RGB10_A2` (`p16c_rgb10a2_none`) and `RGBA16F` (`p16c_rgba16f_none`) complete EGL draws cleanly but `OMX.hisi.video.encoder.hevc` outputs **`0` encoded frames**.
  - `RGBA8` (`p16c_rgba8_none`) encodes `8/8` frames, showing 8-bit input quantization without dithering (`~4`-LSB steps in 10-bit `Y10`).
- **9-stop CUVA `0x324` (`-4 EV` .. `+4 EV`, ISO 200, 5600K) sampled in `GL_RGB10_A2` FBO:**
  - Driver `samplerExternalOES` and raw `__samplerExternal2DY2YEXT` agree within `1–4 / 1023` LSB across all stops (`0 EV`: `RGB=(195.3, 194.6, 232.9)` vs `Y'=199.0`; `+2 EV`: `(414.0, 412.4, 453.7)` vs `417.6`; `+4 EV`: `(703.2, 697.5, 721.0)` vs `701.9`).
  - Empirical slope matches **ITU-R BT.2100 HLG OETF**: square-root gain `~1.45×` per `+1 EV` below `V = 0.50` (`sqrt(2) = 1.414`), logarithmic `+0.131` per `+1 EV` between `+3 EV` and `+4 EV` (`a * ln(2) = +0.124`, within `0.7%`), and `p01 >= 16/1023` (no black crush).

### 3.4 `Probe 16D` — Non-RGB-Surface 10-Bit Paths & Multi-Cam RAW Audit (`WaveCProbeTest#probe16D_True10BitPaths`, `4.027s`)
- **Arm 1 — Direct Camera2 `0x324` (`cuvaStateVideoMode = 1`) -> `OMX.hisi.video.encoder.hevc` Main10 Input Surface:**
  - **SUCCEEDED:** `43` encoded frames (`1.434 s`), `hevc (Main 10)`, `yuv420p10le`, `bt2020nc`, `arib-std-b67` (`HLG`), `color_range = pc`, `Y10 unique = 928`, uniform `mod4 = [511923, 514720, 523079, 523878]`.
- **Arm 2a — ByteBuffer color formats (`0x7f000011`, `0x7f00000f`, `0x7f000001`, `0x7f420888`):**
  - All 4 configured under `HEVCProfileMain10`, yet every one allocated `firstBufferCapacity = 3,110,400` bytes (`1920 * 1080 * 1.5` = 8-bit YUV420 size), confirming no SDK ByteBuffer P010 input path on API 31.
- **Arm 2b — `HardwareBuffer` & `GL_EXT_YUV_target`:**
  - `HardwareBuffer.create(1920, 1080, ...)` succeeded only for `0x23` (`YUV_420_888`) and threw `IllegalArgumentException` for `0x324` (`Invalid pixel format 804`) and `0x36` (`Invalid pixel format 54`). `GL_EXT_YUV_target` `layout(yuv) out` linked cleanly, but lacks a 10-bit allocatable `HardwareBuffer` target on API 31.
- **Arm 3 — Multi-Camera `RAW_SENSOR` / `RAW10` Audit:**
  - Cameras `"0"`, `"1"`, `"2"`, `"4"`, `"5"` all report `hasRawCapability = false`, `rawSensorSizes = []`, `raw10Sizes = []`.

---

## 4. Live Matrix & Stock Huawei Camera Benchmark

### 4.1 Real-App Multi-Config Matrix (`com.librestatic.opencinecam.researchlab`)
- **Sustained 4K60 LOG (`OCC_812e74ca...`):** `45.13 s`, `2684` frames = **`59.48 fps`**, `HEVC Main 10`, `2160x3840`, `yuv420p10le`, `bt2020nc`, Full Range, upright.
- **Locked Tele `2.4×` 1080p60 LOG:** `4.52 s`, `271` frames = **`59.92 fps`** (`OCC_7bf7b828...`), and extended `17.92 s`, `1076` frames = **`60.03 fps`** (`OCC_19fe26d6...`).

### 4.2 Side-by-Side Desk Benchmark (Same Scene, API 31)

| Metric | OpenCineCam `4K 60 LOG` | OpenCineCam `4K 30 LOG` | OpenCineCam `4K 30 HLG` | Stock Huawei (`com.huawei.camera` Video) |
|---|---|---|---|---|
| **Codec / Profile** | `hevc` (`Main 10`) | `hevc` (`Main 10`) | `hevc` (`Main 10`) | `h264` (`High`) |
| **Pixel Format** | `yuv420p10le` (10-bit) | `yuv420p10le` (10-bit) | `yuv420p10le` (10-bit) | `yuv420p` (8-bit) |
| **Color / Range** | `bt2020` / `pc` (Full) | `bt2020` / `pc` (Full) | `bt2020` / `arib-std-b67` (`tv`) | `bt709` / `bt709` (`tv`) |
| **Video Bitrate** | `32.27 Mbps` | `19.65 Mbps` | `19.62 Mbps` | `45.49 Mbps` |
| **Measured FPS / Jitter** | `48.51 fps` (indoor AE ramp, `dtStd 9.42 ms`, `250` pkts) | `29.83 fps` (`dtStd 2.63 ms`, `163` pkts) | `29.99 fps` (`dtStd 0.02 ms`, `166` pkts) | `29.99 fps` (`dtStd 0.04 ms`, `161` pkts) |
| **Unique `Y10` (sub4 sample)** | **`826`** | **`871`** | **`875`** | `225` (`mod4 = [2073600, 0, 0, 0]`) |
| **`Y10` `p01 / p50 / p99`** | `42 / 481 / 757` | `67 / 510 / 768` | `73 / 533 / 790` | `88 / 404 / 848` |
| **Visual Assessment** | Crisp, zero shadow crush, wide highlight headroom | Smooth foliage/wood gradation, zero halos | Rich HLG headroom, `0.02 ms` pacing lock | Strong sharpening halos, deeper shadow crush under desk |

**Container metadata note observed in benchmark:** Embedded AAC audio streams in the tested OpenCineCam MP4s carried monotonic `start_time ≈ 23693.46 s` (`23699 s` container duration in `ffprobe`) while video `start_time = 0.000 s`; Gallery playback (`PreciseVideoFrames.timeline`) uses sample PTS and shows exact duration (`00:00:04:46`, `Frame 1/250`), but normalizing container AAC edit-list / PTS start offset to `0` is recommended for third-party NLE compatibility.

---

## 5. Device Safety & Wave D Recommendations

- **Thermal & Stability:** All Milestone 1–3 tests completed under natural cooling (`Thermal Status: 0`, `shell_frame = 26.4–32.2 °C`; cooled cleanly after 45s soak). `CameraDaemon` PID (`20500`) recorded zero crashes or bounces.
- **Wave D Priorities:**
  1. **Zero-GL Direct 10-bit HLG Mode:** Route Camera2 `0x324` directly to `OMX.hisi.video.encoder.hevc` Main10 Surface (with parallel `ImageReader` for scopes/preview) when overlays/LUTs are disabled, eliminating the `RGBA8` intermediate stage (`Probe 16D` Arm 1).
  2. **Normalize Container AAC Audio `start_time` to `0`:** Align embedded AAC timestamps in MP4 container so NLEs read exact clip duration (`~5.5 s` instead of monotonic clock offset).
  3. **Indoor AE 60fps Floor Hint:** When `targetFps = 60` in LOG, clamp `CONTROL_AE_TARGET_FPS_RANGE` lower bound or surface a clearer exposure-time-exceeds-frame-interval indicator so indoor high-ISO shots do not silently dip below 60 fps (`48.5 fps` vs `59.48 fps` in brighter/locked scenes).
