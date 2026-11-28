# Wave D Milestone 2 — Verified Hardware Delivery (Huawei Pura 80 Pro)

**Status:** Hardware-Verified (7/7 Exit Gates Passed)  
**Target Device:** Huawei Pura 80 Pro (`LMR-LX9`, Kirin 9020, EMUI 15, Android API 31)  
**Branch:** `research/autonomous-log-lab`  
**Curated Evidence:** [Wave D M1 JSONs](evidence/wave-d-m1/), [Wave D M2 JSONs](evidence/wave-d-m2/)

---

## Executive Summary

Wave D Milestone 2 resolved all blocking production stability, cadence, optical variable aperture, A/V synchronization, and Async PBO scope readback issues on Huawei Pura 80 Pro:

1. **Direct 10-bit 4K60 CUVA HLG (`0x324`) — 0 Dropped Frames Across All 3 Rear Lenses + Live 30fps Viewfinder + Async PBO Scopes:**
   - Dual-surface Kirin HAL rule discovered and solved: pairing a second `USAGE_VIDEO_ENCODE` ImageReader alongside the `0x324` encoder surface deadlocks Kirin HAL at exactly 50 frames (`-16 EBUSY` on `deleteStream`). Restricting the auxiliary preview `ImageReader` to `HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE` (`640x360` at 60fps, `1280x720` at 30fps) eliminates the deadlock completely.
   - Pairing `[withPreview, codecOnly]` 2-frame repeating bursts (`setEngineRepeatingRequest`) with exact-typed Kirin 60fps cadence-lock vendor keys (`hw-video-dynamic-fps-mode=0`, `hw-video-fps=60`, `hwVideoReductionFpsEscape=1`, `hwVideoAiDenoiseEscape=1`) locked sustained 60fps across all rear lenses:
     - `m2_dual_4k60.mp4`: `59.9288 fps`, `0` drops (`180` encoded / `90` preview frames during take).
     - `m2_prod_cam0_4k60_hlg_aac.mp4` (`cam0`, 23mm): `59.9217 fps`, `0` drops (`295` encoded / `151` preview frames).
     - `m2_prod_cam4_4k60_hlg_aac.mp4` (`cam4`, UW): `60.0101 fps`, `0` drops (`242` encoded / `121` preview frames).
     - `m2_prod_cam5_4k60_hlg_aac.mp4` (`cam5`, Tele): `60.0483 fps`, `0` drops (`242` encoded / `123` preview frames).
   - All Direct HLG clips verified `HEVC Main 10`, `yuv420p10le`, `bt2020`, `arib-std-b67`, `tv` range, `869–999` distinct 10-bit luma codes, `74.83–75.34%` sub-LSB variance, `0%` highlight clipping.

2. **Sustained Optical Variable Aperture (`f/1.6` vs `f/4.0` vs Auto `f/2.0`) — 0 Drops from Frame 0:**
   - Root cause isolated: Kirin HAL resets the mechanical iris blades to `f/2.0` at Frame 5 of every fresh Direct 10-bit session and treats variable aperture requests as edge-triggered transitions (`0f -> targetFNum`).
   - Fix in `Camera2SessionGraphs.kt` & `HuaweiVendorControls.kt`: two-phase pre-record preview-only iris settle (`needsVaSettle`, `0f` for `>=7` frames / `>=230ms` -> `0f -> targetFNumber` for `>=6` frames / `>=180ms` before targeting `codecSurface`), plus `vaPerframeFNum` (`0x80040099`) and removal of bokeh `apertureMode=1`.
   - Verified under locked `ISO 400`, `1/60s`, `MANUAL`, `5600K`:
     - `f/1.6`: `mean_y=496.756` (`p50=492`, timeline `500.43 -> 500.73 -> 496.74 -> 490.43`, `0` drops)
     - `f/4.0`: `mean_y=277.734` (`p50=168`, timeline `276.85 -> 277.46 -> 277.72 -> 278.06`, `0` drops)
     - `Auto (f/2.0)`: `mean_y=456.029` (`p50=397`, `0` drops)
     - Sustained brightness ratio `f/1.6 / f/4.0 = 1.7886x` (`aperture_sustained_ok=true`).

3. **A/V Clock Normalization & Async PBO Scopes:**
   - `SHARED_BOOTTIME_CAPTURE_ANCHORS` (`CameraTimestampBootMapper`) eliminates the ~`23,693s` `REALTIME` vs `boottime` A/V offset across 13/13 M2 MP4s (`av_sync_clean=true`).
   - `AsyncPboScopeReader` active on 4K30 (`pboAsyncReads=20`, `0` sync fallbacks) and 4K60 (`pboAsyncReads=13`, `0` sync fallbacks).

4. **GPU OCLog2 Baseline & Smooth ISP Behavior:**
   - `test03` GPU OCLog2 recorded `4K30` at `1` drop (`maxVideoPtsGapUs=66687`, `mean_y=430.45`) and `4K60` at `47.23 fps` (`59` drops on rotated `2160x3840` `COMPATIBLE` path) — establishing the baseline optimized further in Milestone 3.
   - Smooth ISP (`colorMode=2`) verified on CUVA HLG (`mean_y=500.61` vs `451.27` Off, `p99=997` vs `884`, Laplacian edge energy `1.4565x` higher with `0.0949%` clipping) and surfaced with honest UX guidance in Settings.

5. **Device Health:** `cameraserver` (`1375`) and `CameraDaemon` (`20500`) recorded `0` restarts across all M2 runs; `Thermal Status: 0 (NONE)`, `shell_frame=30.19–31.94 °C`.
