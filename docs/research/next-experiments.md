# Next Discriminating Experiments (`LMR-LX9`, Pura 80 Pro)

Prioritized after `7d0db6a5` (`49` attempts under Power Saving Mode ON) & `f8d3e998` (`32°C MODERATE` halt) in the cap-free, auto-resuming Research Lab (`LabExplorationGuard`, `LabCheckpointState`, `LabPowerProfile`, `40°C pause -> 35°C auto-resume`).

**Mandatory Prerequisite on Device Before Running:** Turn **OFF** Android/Huawei Power Saving Mode, enable **Huawei Performance Mode**, tap the **`Performance`** chip in the Lab UI (`lab-power-PERFORMANCE`), and tap **Start new session** (for a clean non-throttled baseline) or **Resume session** to finish any paused session.

---

## Top 5 Priority Experiments (Automated in `LabAutonomousPlanner`)

### 1. Power-Clean `4K60 SurfaceTexture` Drain-Only Control (`H-11`) & `4K60 OCLog2/GPU` Escalation (`Stage C`)
- **Proven Prerequisite (`7d0db6a5`):** Under Power Saving ON, all 13 `4K60` GPU/LOG runs dropped to `47.80–48.30 fps` (`11@60 -> 4/1` periodic metronome) even when GL took `14.9–15.2 ms < 16.69 ms`. Meanwhile cooled `DIRECT_HEVC_SURFACETEXTURE` (`720p` & `360p`) and `DIRECT_HEVC` sustained **`59.93 fps` for 30 s with `0` drops**.
- **New Discriminator Sequence:**
  1. `LOG_REGULAR_4K60 previewOnly = true` (`regular_log_preview_diag`): runs Camera2 -> `SurfaceTexture` -> GL preview with **encoder idle** for 4 s to separate Camera2/SurfaceTexture 4K60 delivery from MediaCodec `RGBA->YUV` backpressure and Power Saving throttling.
  2. Full `LOG_REGULAR_4K60` & `GPU_HEVC_4K60` ladder (`realtime`, `single_ctx`, `prev_30fps`, `no_preview`, `bitrate_45m`, `egl8`, `novui`, `scopes_pbo`) + 10 s sustained on any `OBSERVED` winner.

### 2. True >8-bit `ByteBuffer` Vendor Format Encoding (`H-12`, `0x7F00000F` / `0x7F000011` + `YUV420Flexible`) (`Stage A 001`)
- **Proven Prerequisite (`7d0db6a5-001`):** `HISI_VENDOR_0x7F00000F (2130706447)`, `HISI_VENDOR_0x7F000011 (2130706449)`, and `YUV420Flexible` ALL admitted (`admitted=true`) on `OMX.hisi.video.encoder.hevc Main10 L6.2`.
- **Upgrade:** `probeVendor10BitByteBufferAdmission` now queries buffer capacity (`bytesPerPixel`), encodes 15 synthetic ramp frames, muxes to MP4, and inspects `rampEffectiveBits` (`>= 9`?) and `mod4ZeroFraction`!

### 3. Fixed `ISP_CUVA_HDR_PROBE` (`ByteArray` `[B` States `0/1/2` + `sessionParameters` + Companion `PRIVATE`) (`Stage E`)
- **Proven Prerequisite (`7d0db6a5-022`):** Exact failure `"Not an array: class java.lang.Byte"` proved `com.huawei.capture.metadata.cuvaStateVideoMode` requires `ByteArray`.
- **Upgrade:** `runCuvaHdrProbe` sets `byteArrayOf(0/1/2)` with `SessionConfiguration.sessionParameters`, reads back `CaptureResult`, and inspects companion `ImageFormat.PRIVATE` `HardwareBuffer` format/usage/DataSpace under `CUVA_STATE_1`.

### 4. Kirin High-Gamma (`2.6 / 2.4 / 2.2 / 2.0 / 1.8 / 1.0`), `-1 EV` Curve, & Live `gamma_2_0`/`gamma_2_2` 4K30 LOG (`Stage B` & `E`)
- **Proven Prerequisite (`7d0db6a5-019/020`):** `GAMMA_2_0` (`0.056%` clip, `spread=221`) and `GAMMA_2_2` (`0%` clip) hold full highlights while `FLAT`/`ROLLOFF`/`GAMMA<=1.8` clip `25–65%`.
- **Upgrades:** Live 4K30 `LOG_REGULAR` variants `gamma_2_0` & `gamma_2_2` + locked-exposure `CONTRAST_CURVE_MINUS_1EV` (`baseExp / 2`) + extended `2.6..1.0` sweep.

### 5. Tiered Cumulative Reference Audit With Auto-Resuming `40°C / 35°C` Thermal Protection & Persistent Checkpoints
- **Upgrades:** Runs uninterrupted across Stages A–F (>36 attempts without arbitrary termination), pauses gently at `>= 40°C` / `SEVERE`, auto-resumes at `<= 35°C`, and preserves progress across restarts/crashes/reboots/APK upgrades via `checkpoint.json` & `active_session.json`.
