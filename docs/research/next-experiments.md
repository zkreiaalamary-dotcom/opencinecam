# Next Discriminating Experiments (`LMR-LX9`, Pura 80 Pro)

Prioritized after `نتايج\جديد` (`Run A a26cd8da`, `Run B 922a793c`) and implemented in the unlimited-session Research Lab APK on `research/autonomous-log-lab` (`LabSessionBudget`: `36` core + `18` adaptive = `54` max attempts, `TIME_SLICE_LIMIT_MS` removed).

---

## Top 5 Priority Experiments (Now Automated in `LabAutonomousPlanner`)

### 1. Zero-Drop `4K60 OCLog2 HEVC Main 10` GL Optimization Ladder (`Stage C`)
- **Proven Prerequisite:** Camera2 already delivers `59.94 fps` (`0` sensor drops in `R-B 006/008/012`); `OpenCineLogFrameDiagnostics` localized drops strictly to the GL thread (`maxTotal=32.289 ms > 16.69 ms`, `encDraw=14.612 ms`, `encSwap=18.651 ms`, `prev=5.3 ms` + dual `EGLContext` switch).
- **Single-Variable Escalation Ladder:**
  1. `realtime`: Inject `KEY_PRIORITY=0` + `KEY_OPERATING_RATE=60` on `OMX.hisi.video.encoder.hevc` (`enableRealtimeCodecKeys=true`).
  2. `single_ctx`: Eliminate per-frame `EGLContext` switch when using `egl8` (`singleContextWhen8Bit=true` reusing cached `makeCurrent`).
  3. `prev_30fps`: Decimate UI preview rendering (`previewFpsDivisor=2`) while keeping full `60 fps` encoder output.
  4. `no_preview`: Headless `previewOnly=false` zero-UI baseline isolate.
  5. `bitrate_45m`: Reduce `4K60` bitrate from `60–80 Mbps` to `45 Mbps` to isolate encoder swap backpressure.
- **Success Criterion:** Sustain `>= 59.8 fps` (`0` GL/muxer drops) first for 4 s, then 10–30 s on `LOG_REGULAR_4K60` with `oclog2_full` Main10 (`L6.2`).

### 2. True >8-bit Input Precision: `EGL10` ColorSpaces / `FP16` / Dither & HiSilicon `ByteBuffer` 10-bit Probe (`Stage A 001`)
- **Proven Prerequisite:** `Main10 L6.2 + egl8` (`8-bit` input -> `10-bit` HEVC) is proven at 4K30 & 4K60 (`bits=8`), while `RGBA1010102` with `cs=null` & `BT2020_LINEAR_EXT` produced `0` encoded packets (`R-B 001`).
- **New Synthetic Arms Added (`LabSyntheticEncoderProbe`):**
  1. `main10_l62_4k30_egl10_hlg` (`EGL_GL_COLORSPACE_BT2020_HLG_EXT = 0x3540`) & `main10_l62_4k30_egl10_pq` (`EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3340`).
  2. `main10_l62_4k30_egl16f` (`EGL_RGBA_16F`).
  3. `main10_l62_4k30_egl8_dither` (ordered/triangular dither into `Main10 L6.2 + egl8` to avoid 8-bit contour banding if `egl10` remains blocked).
  4. Camera-closed HiSilicon `ByteBuffer` 10-bit probe (`0x7F00000F`, `0x7F000011`, `YUV420Flexible`).
- **Decision Unlock:** Proves whether `OMX.hisi.video.encoder.hevc` accepts ANY true `>= 10`-bit producer (`bits >= 9`, `mod4 != 0`) via GL Surface or `ByteBuffer`!

### 3. Kirin Post-Gamma `TONEMAP` Gamma Sweep & `kirinGammaAttenuatedRolloffCurve` (`Stage E`)
- **Proven Prerequisite:** Locked-exposure (`16.666 ms`, `ISO 580–581`) `R-B 022/023` proved Kirin `TONEMAP_CURVE` acts in **post-gamma** space (`GAMMA_1_0` clipped `75.2%`, `SAFE_LINEAR_ROLLOFF` clipped `74.8%`), whereas **`TONEMAP_MODE_GAMMA_VALUE = 2.2` recovered `100%` highlights (`0%` clip, `max=172`, `p99=143`)**.
- **New Locked-Exposure Arms (`LabSourceProbe`):**
  1. Gamma sweep `2.2`, `2.0`, `1.8`, `1.6`, `1.4`, `1.2`, `1.0` under `TONEMAP_MODE_GAMMA_VALUE (3)` to find the exact minimum gamma that preserves dynamic range without highlight saturation.
  2. `kirinGammaAttenuatedRolloffCurve` (post-gamma safe shoulder curve `out <= 0.72` at `in = 1.0`, avoiding the `FLAT` overshoot) + 4K30 `LOG_REGULAR` `gamma_1_8` integration (`LabRegularLogRunner`).

### 4. Vendor `cuvaStateVideoMode` (HDR Vivid) Live Admission & Pixel/Metadata Probe (`Stage E`)
- **New `LabPath.ISP_CUVA_HDR_PROBE` (`LabSourceProbe.runCuvaHdrProbe`):**
  - Captures baseline (`cuvaStateVideoMode = 0` or unset) vs `1` vs `2` on `TEMPLATE_RECORD` (`PRIVATE` + `YUV_420_888`), reading back `CaptureResult` / `TotalCaptureResult` `cuvaStateVideoMode`, dynamic range, `hw_algo_*` vendor tags, and output `HardwareBuffer` format/DataSpace to test whether EMUI 15's native HDR Vivid pipeline can be unlocked safely on API 31.

### 5. Clean Unlimited-Session Multi-Stage Verification + Checkpoint Resume
- Because `TIME_SLICE_LIMIT_MS = 300_000L` is now permanently deleted, verify full uninterrupted execution up to the adaptive budget (`54` max attempts, gated strictly by thermal hysteresis, `25%` battery reserve, `5 GiB` disk safety reserve, and `20 GB` lab budget) plus checkpoint resume across app restart (`checkpoint.json`).
