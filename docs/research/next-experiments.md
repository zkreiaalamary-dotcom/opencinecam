# Next Discriminating Experiments (`LMR-LX9`, Pura 80 Pro)

Prioritized to eliminate maximum uncertainty per experiment minute while avoiding all 7 bugs found in Runs 1–3. Every experiment isolates **one controlled variable**, locks `AE`/`AWB`/`ISO`/`Shutter` where image brightness is compared, and stays off `main`.

---

## Top 5 Priority Experiments (High Discrimination, Low Cost)

### 1. Fix & Re-Run `CODEC_SYNTHETIC_MATRIX` + `Main10 Level 6.2` Arm (`001`)
- **Fixes Required Before Running (Lab-only):**
  1. Add `.trimIndent()` to `SYNTHETIC_VERTEX_SHADER` and `SYNTHETIC_FRAGMENT_SHADER` in `LabSyntheticEncoderProbe.kt:491-504` (fixes GLSL `P0005` error).
  2. Add arm `main10_l62_4k30_egl10` and `main10_l62_4k60_egl10` with explicit `HEVCMainTierLevel62` (`16777216`, matching `inventory.json`'s exact `2:16777216` capability entry), plus `main10_l51_oclog2_lim_4k30_egl10_nodrop` and 0-byte file cleanup on failure.
- **What It Discriminates Immediately (Camera Closed — ~8 seconds total):**
  - Does `OMX.hisi.video.encoder.hevc` Main10 encode actual frames (non-zero MP4) on `RGBA1010102` Surface, or only on `RGBA8888` (`egl8`)?
  - Does `Level 6.2 (16777216)` vs `Level 5.0/5.1` fix the **0-packet output** bug seen in `002/016`?
  - Does `BT2020_LINEAR_EXT` or `COLOR_RANGE_FULL` vs `LIMITED` cause half-frame repeat geometry, dropped frames, or success?
  - Using Q1 synthetic linear ramp (`analyzeRampPrecision`): proves **quantization bit depth** (`effectiveBitsEstimate >= 9`, `mod4 != 0` = true 10-bit vs shifted 8-bit)!

### 2. Fixed-Bitrate 4K60 `GPU_HEVC` + `LOG_REGULAR` (`30` & `60` fps Single-Variable Matrix)
- **Prerequisite:** Bypass `RecordingDataRate.gpuVideoBps()` double-multiplier in `LabRegularLogRunner` / pipeline cap (`<= 80 Mbps` or `<= 100 Mbps`, not `120 Mbps`), AND wire `gpu.onRecordedFrame = signals::onRecordingFrame` in `LabRegularLogRunner` so `gpu` cadence timestamps are recorded.
- **Arms (~20 seconds total):**
  - `Arm A`: `GPU_HEVC_4K60` (`Main8`, `L5.1=65536`, `60 Mbps`, `EGL8`) — tests whether production `OpenCineLogGpuPipeline` + Camera2 sustains **4K60 HEVC via GPU** now that 4K30 (`003`) succeeded!
  - `Arm B..D`: `LOG_REGULAR_4K30` (`Main10`, `40 Mbps`) injecting the exact winning recipe from Experiment #1 (`Level 6.2` vs `L5`, `EGL10` vs `EGL8`, `FULL` vs `LIMITED`, `with/without BT2020_LINEAR_EXT`).
  - `Arm E`: If Arm B succeeds cleanly at 4K30, immediately escalate to `LOG_REGULAR_4K60` at `60–80 Mbps`.
- **Decision Unlock:** Unlocks production **4K30/4K60 HEVC Main** AND establishes whether **real Camera2 -> GLES -> Main10 OCLog2** succeeds or hits an OMX-specific camera surface constraint.

### 3. Safe Isolated `SOURCE_FORMAT_PROBE` + Physical Cameras + Hidden `YCBCR_P010` / `PRIVATE` Introspection
- **Fixes Required:**
  1. **Remove or quarantine `TONEMAP_PRESET_CURVE_SRGB (4)`** from `ISP_COLOR_CORRECTION_PROBE` (or place `SOURCE_FORMAT_PROBE` **before** it, with per-camera retry + `cameraserver` recovery wait and explicit `FAILED` if `cameras.isEmpty()`).
  2. Probe Camera `"0"`, `"2"` (main 8.5 mm), `"4"` (ultrawide 1.95 mm), `"5"` (tele 15 mm) individually.
- **What It Discriminates:**
  - True `HardwareBuffer.format` and `DataSpace` of Camera 0 / 2 / 4 / 5 `ImageFormat.PRIVATE` and `YUV_420_888` streams (specifically checking whether `PRIVATE` uses 8-bit `YCbCr_420_888`/`IMPLEMENTATION_DEFINED` vs 10-bit `P010` under API 31!).
  - Whether `YCBCR_P010 (54)` or `RAW_SENSOR (32)` / `RAW10 (37)` works on any individual physical camera (`"2"`, `"4"`, `"5"`) despite absence on logical `"0"`.
  - Read values of vendor result keys (`hw_algo_ae_lv`, `hw_algo_mean_y`, `frameLuminance`, `multiCameraMask`) across preview & `cuvaStateVideoMode` admission testing!

### 4. Locked-AE Optical Calibration Sweep: `GAMMA_2_2` (`TONEMAP=3`) vs `TRANSFORM_MATRIX` (`CC=0`) vs Safe Linear `CONTRAST_CURVE` vs Default (`4K30`)
- **Critical Methodology Fix (Missing in Runs 2–3):** Lock `CONTROL_AE_MODE = OFF` with fixed `SENSOR_EXPOSURE_TIME`, `SENSOR_SENSITIVITY` (ISO), `CONTROL_AWB_MODE = OFF` (`RggbChannelVector` locked), and fixed `LENS_FOCUS_DISTANCE` against a **static grayscale step-chart + ColorChecker under constant flicker-free illumination** (no auto-brightness monitor changes, no hand movement).
- **Arms (`YUV_420_888` + `GPU_HEVC_4K30` saved clips so full RGB/luma/chroma analysis can run offline!):**
  1. Baseline (`TONEMAP=FAST(1)`, `CC=HIGH_QUALITY(2)`)
  2. `TONEMAP=GAMMA_VALUE(3)` (`TONEMAP_GAMMA = 2.2` and `1.0` — test if `gamma=1.0` gives linear sensor output!)
  3. `CC=TRANSFORM_MATRIX(0)` + `TONEMAP=GAMMA_VALUE(3)` (linear or `2.2` matrix + tone curve = bypassed consumer look!)
  4. Re-designed gentle linear-domain `CONTRAST_CURVE` (monotonic log-shoulder `out <= in` highlights instead of sRGB-knots-in-linear-domain `FLAT`).
- **What It Discriminates:**
  - Can manual `TRANSFORM_MATRIX + GAMMA_VALUE` bypass Huawei's aggressive consumer sharpening, contrast S-curve, and highlight compression to create a **true low-contrast camera-native LOG pre-grade feed**, eliminating the `44–69%` highlight clipping seen in `008`?
  - Measures genuine usable highlight headroom (clipped steps vs unclipped steps under **identical fixed exposure**!).

### 5. `cuvaStateVideoMode` Vendor HDR Vivid & Dual-Output 4K60 Preview Downscale Probe
- **Part A (`com.huawei.capture.metadata.cuvaStateVideoMode`):**
  - Read its current values in `TotalCaptureResult` / test non-invasive session parameter admission (`0`, `1`, `2`) on `CameraDevice.TEMPLATE_RECORD` + `ImageFormat.PRIVATE` (`SurfaceTexture` / `ImageReader`), inspecting `HardwareBuffer.format`, `DataSpace`, and whether 10-bit HDR Vivid source buffers activate on EMUI 15 API 31 even without API 33 `DynamicRangeProfiles`!
- **Part B (`4K60` Preview Resolution Isolation & `1080p60` Thread Split):**
  - Run `DIRECT_HEVC_SURFACETEXTURE` 4K60 comparing preview target sizes `1280x720` vs `640x360` (matching HAL/GPU workload hypothesis for the `4–9` camera drops in `005/006/012`).
  - Run `GPU_HEVC_1080p60` (and `AVC_1080p60`) with `scopeAnalysis = OFF` vs `ON` (`glReadPixels` stall test!) to test whether synchronous `glReadPixels` in `renderScopeAnalysisIfDue()` or encoder backpressure caused the `10–13` drops in `019/021`.
