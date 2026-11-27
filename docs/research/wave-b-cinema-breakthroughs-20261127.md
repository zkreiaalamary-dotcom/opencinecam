# Wave B & Incident Hardening Breakthroughs — Huawei Pura 80 Pro (`LMR-LX9`, 2026-11-27)

**Branch:** `research/autonomous-log-lab`  
**Target device:** Huawei Pura 80 Pro (`LMR-LX9`, Kirin 9020, EMUI 15, Android API 31)

---

## 1. CameraService Incident Hardening (`incident_report_20261127`)

Following the pre-reboot `CameraDaemon` / lens-control incident, the following safeguards were implemented and unit-tested:

1. **`TONEMAP_MODE_PRESET_CURVE` (`0x45f`) Quarantine (`LabSourceProbe.kt`):**
   - `isHuaweiKirinDevice()` and `tonemapPresetCurveQuarantineReason()` identify Kirin HAL builds where `CONTROL_TONEMAP_PRESET_CURVE` triggers a fatal `cameraserver` / `CameraDaemon` crash.
   - `LabSourceProbe.kt` removes the `builder.set(TONEMAP_PRESET_CURVE, ...)` call completely.
2. **64-Zone Thermal Telemetry & Thermal Rate Tracking (`LabTelemetry.kt`):**
   - `readSysThermalZones(maxZones = 64)` sorts `/sys/class/thermal/thermal_zone*` numerically via `parseThermalZoneIndex` so board NTCs and shell sensors past zone 32 are never truncated.
   - Tracks `shellFrameC`, `shellLevelC`, `boardHotspotC`, `LabThermalRateTracker` (`dT/dt`), `activeCaseKey`/`activeCameraId`, and multi-zone pause/resume thresholds (`shell_frame >= 41.5 °C -> <= 37.0 °C`, `boardHotspot >= 48.0 °C -> <= 42.0 °C`).
3. **Engine Watchdog & Continuous Filtered Logcat Ring (`CameraHealthWatchdog.kt`, `LabDiagnosticRecorder.kt`):**
   - `CameraHealthTracker` / `CameraHealthWatchdog` monitors `CameraDaemon` multi-camera bounce bursts, Camera2 errors, and `6 s` `openCamera` timeouts in `Camera2PreviewEngine`.
   - `LabDiagnosticRecorder` maintains a rolling 2 MB filtered `logcat -b main,system,events,crash` ring buffer with `snapshotAnomaly(...)`.

---

## 2. Wave B Empirical Probes (`Probes 14A`, `14B`, `15`)

### 2.1 `Probe 14A`: Settling `OMX.hisi.video.encoder.hevc` `0x7F000011` ByteBuffer UV Layout
- Tested across 6 synthetic ByteBuffer memory layouts (`offset_blocks`, `align16_1088`, `align64_1152`, `lsb10`, `packed10_5div4`, `y_rowtag_uv_wh2`).
- **Finding:** `OMX.hisi.video.encoder.hevc` reads luma (`Y`) as 16-bit `P010` (`<< 6`, `965/1024` distinct 10-bit codes on rowtag), **but reads both `U` and `V` planes from offset `0` (`Y` row `0`)**, ignoring the `[w*h*2 .. cap)` UV region in ByteBuffer mode.
- **Decision:** Keep the zero-copy `ImageReader(..., 0x324) -> EGL8 + achromatic TPDF dithering -> HEVC Main 10` pipeline (`OpenCineLogGpuPipeline`) as the Wave B production path.

### 2.2 `Probe 14B`: Huawei Manual Kelvin WB, `colorMode = 2`, and CUVA `0x324` OETF
- Verified `hwFmtHex = "0x324"` stays active across `auto_baseline`, `2800K`, `5600K`, `7500K`, `restore_auto_zero`, and `-3..+2 EV` sweeps.
- **Manual Kelvin WB (`2800–10000 K`):** `HuaweiVendorControls` drives `com.huawei.capture.metadata.sensorWbValue` while keeping `CONTROL_AWB_MODE = AUTO` (since standard `AWB_MODE_OFF` outputs black frames on API 31 Kirin HAL). `Probe 14B` proved `null` leaves AWB partially stuck (`cb=139.2`), whereas writing `0` (`clearKelvin`) restores baseline Auto WB (`cb=130.0, cr=128.7, ccGains=[2.786, 2.478]`). `professionalMode` is unnecessary.
- **`colorMode = 2` Rejected for Default OCLog:** Crushes shadows on YUV-only (`clipLoFrac 0.14% -> 8.49%`) and increases shadow clip on CUVA (`4.3e-6 -> 1.44e-4`), so it stays off by default.
- **CUVA `0x324` OETF Characterization:** Center Rec.709 luma across EV stops (`-3EV=0.146`, `-2EV=0.175`, `-1EV=0.238`, `0EV=0.362`, `+1EV=0.548`, `+2EV=0.666`) follows the HLG (`ARIB STD-B67`) OETF.

---

## 3. Multi-Lens `4K60` / `4K30` CUVA Admission & `Probe 15` End-to-End Production Verification

### 3.1 Descriptor & Request Fixes (`Camera2Descriptors.kt`, `Camera2RequestControls.kt`)
- **UW Physical Camera `"4"` `4K60` Admission:** HAL device `"4"` advertises `HwVideoRecordingPre4K60En=[1]` and `videoCuvaHdrSupported=[1]`, but omits `[60, 60]` from `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`. Added `huaweiBack4k60SyntheticFps` (gated strictly to `LENS_FACING_BACK && hasHuawei4k60 && hasHuaweiCuva`) plus `resolveTargetFpsBounds`/`resolveTargetFpsRange` fallback in `applyTargetFps` so `"4"` admits `3840x2160@60[CUVA]` while front camera `"1"` stays excluded (`any60 = 0`).
- **Tele Physical Camera `"5"` `4K24/25/30` CUVA Admission:** Added `huaweiCuvaUhdStreamDurationOverride` for CUVA back cameras with `HwVideoRecordingPre4K60En=[1]` so `"5"` exposes `3840x2160@24/25/30/60[CUVA]`.

### 3.2 `Probe 15` (`WaveBCProbeTest#probe15_ProductionEndToEndVerification`) Results
All clips recorded cleanly through production `Camera2PreviewEngine` + `OpenCineLogGpuPipeline` with `hwFmtHex = "0x324"`, `OMX.hisi.video.encoder.hevc` (`HEVC Main 10`), zero engine/watchdog health errors, `CameraDaemon PID = 1404` unchanged, `Thermal Status = 0` (`shell_frame = 30.7–31.0 °C`, `Battery = 29.0 °C`):

| Clip | Camera / Lens | Size & FPS (ffprobe) | Profile / Format | VUI / Transfer | `uniq10` / `mod4` / Chroma |
|---|---|---|---|---|---|
| `p15_main_4k60_oclog2.mp4` | `"0"` Main | `2160x3840`, `237 f` (`96.8 Mbps`) | `hevc Main 10`, `yuv420p10le` | `bt2020`, `enc=1 / ctr=2` | `907`, `[0.251,0.251,0.250,0.248]` |
| `p15_main_4k30_oclog2.mp4` | `"0"` Main | `2160x3840`, `123 f`, `0 gaps` (`49.8 Mbps`) | `hevc Main 10`, `yuv420p10le` | `bt2020`, `enc=1 / ctr=2` | `851`, `[0.250,0.251,0.251,0.248]` |
| `p15_main_4k24_oclog2.mp4` | `"0"` Main | `2160x3840`, `97 f`, `0 gaps` (`39.7 Mbps`) | `hevc Main 10`, `yuv420p10le` | `bt2020`, `enc=1 / ctr=2` | `870`, `[0.251,0.251,0.248,0.250]` |
| `p15_uw_4k60_oclog2.mp4` | `"4"` UW | `2160x3840`, `241 f` (`59.53 fps`, `99.3 Mbps`) | `hevc Main 10`, `yuv420p10le` | `bt2020`, `enc=1 / ctr=2` | `863`, `[0.250,0.249,0.251,0.250]` |
| `p15_tele_4k60_oclog2.mp4` | `"5"` Tele | `2160x3840`, `241 f` (`59.51 fps`, `49.5 Mbps`) | `hevc Main 10`, `yuv420p10le` | `bt2020`, `enc=1 / ctr=2` | `696`, `[0.249,0.250,0.250,0.251]` |
| `p15_tele_4k30_oclog2.mp4` | `"5"` Tele | `2160x3840`, `91 f`, `0 gaps` (`51.5 Mbps`) | `hevc Main 10`, `yuv420p10le` | `bt2020`, `enc=1 / ctr=2` | `683`, `[0.252,0.251,0.250,0.247]` |
| `p15_opengate_4096x3072_30_oclog2.mp4` | `"0"` Open-Gate | `3072x4096`, `122 f`, `0 gaps` (`89.4 Mbps`) | `hevc Main 10`, `yuv420p10le` | `bt2020`, `enc=1 / ctr=2` | `837`, `[0.249,0.250,0.251,0.250]` |
| `p15_main_4k30_hlg10.mp4` | `"0"` HLG10 | `2160x3840`, `122 f`, `0 gaps` (`49.8 Mbps`) | `hevc Main 10`, `yuv420p10le` | `arib-std-b67` (`18 / 18`) | `886`, `[0.250,0.250,0.250,0.250]` |
| `p15_wb_2800k.mp4` (`FIXED:ok`) | `"0"` `2800K` | `2160x3840`, `61 f`, `0 gaps` (`49.4 Mbps`) | `hevc Main 10`, `yuv420p10le` | `bt2020`, `enc=1 / ctr=2` | `840`, `U_mean=623.1, V_mean=514.8` |
| `p15_wb_7500k.mp4` (`FIXED:ok`) | `"0"` `7500K` | `2160x3840`, `62 f` (`48.4 Mbps`) | `hevc Main 10`, `yuv420p10le` | `bt2020`, `enc=1 / ctr=2` | `871`, `U_mean=487.6, V_mean=516.6` |
| `p15_wb_auto.mp4` (`Auto`) | `"0"` Reset | `2160x3840`, `62 f`, `0 gaps` (`49.1 Mbps`) | `hevc Main 10`, `yuv420p10le` | `bt2020`, `enc=1 / ctr=2` | `868`, `U_mean=521.9, V_mean=516.2` |

Visual PNG inspection confirmed crisp upright framing across UW `"4"` `4K60`, Tele `"5"` `4K30`, clear cool tungsten (`2800K`) vs warm daylight (`7500K`) separation (`135.5` 10-bit `U` code delta), and exact Auto WB restoration (`p15_wb_auto`).
