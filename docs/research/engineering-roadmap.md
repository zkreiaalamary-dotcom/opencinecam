# OpenCineCam Engineering Roadmap & Dynamic Storage Architecture (`LMR-LX9`, Pura 80 Pro)

Grounded in verified findings from `تجربه1` (Runs 0–2) and `تجربه2` (Run 3).  
*Scope note: Per current task rules, this roadmap plans the engineering changes; no production camera code has been altered in this documentation update.*

---

## 1. Ranked Engineering & Feature Opportunities

| Rank | Opportunity | Potential Impact | Evidence Strength | Probability of Success | Engineering Cost |
|---:|---|---|---|---|---|
| **1** | **Fix 3 Lab Blockers + Re-run Matrix** (`.trimIndent()` in `001`, 4K60 `60M` bitrate cap in `LabRegularLogRunner`, quarantine `PRESET_SRGB` crashing `cameraserver` before `010/011`, fix `H-01` & `baseKey` KB bugs, wire `gpu` signals) | **Critical** (unlocks truth on Main10 10-bit ramp, 4K60 GPU HEVC, `002` 0-frame cause, & `010` physical formats) | **Verified directly** (exact lines & error logs isolated) | **99%** | **Low** (0.5 day, lab-only files) |
| **2** | **Enable Production GPU HEVC `4K30` (and test `4K60`) via Explicit `KEY_LEVEL`** (eliminating mandatory fallback to 19 Mbps AVC!) | **Very High** (doubles 4K coding efficiency & fidelity; `39.8 Mbps` Main L5.0 `0` drops proven across 3 runs) | **Confirmed (`003` R1/R2/R3 + `013` L5.1)** | **95% (`4K30`), ~80% (`4K60` once 120M bug fixed)** | **Low-Medium** (1–2 days in `OpenCineLogGpuPipeline` + `HevcEncoderLevels` caps) |
| **3** | **Production Direct `4K60` HEVC Mode** (Single-target `DIRECT_HEVC` 30 s `59.92 fps, 0 drops` proven + tuning preview buffer `640x360` / throttled UI preview) | **Very High** (gives Pura 80 Pro true, sustained zero-drop `4K60` HEVC immediately!) | **Confirmed (`007` R2 & R3 1780–1788 frames, 0 drops)** | **90%** | **Medium** (2–3 days: decouple descriptor `minFrameDurationNs` gate for Direct HEVC + safe preview cadence) |
| **4** | **API 31 ISP Log/Flat Pre-Conditioning via `TONEMAP_MODE_GAMMA_VALUE (3)` + `COLOR_CORRECTION_MODE_TRANSFORM_MATRIX (0)` + Fixed/Gentle Curves** | **High** (bypasses aggressive consumer ISP contrast/sharpening; fixes `PictureProfile.FLAT` clipping catastrophe) | **Confirmed (`009` R2 & R3 empirical capture: `0%` clip vs `44–69%` on `FLAT`)** | **80–85%** | **Medium** (2–3 days: chart calibration + remove `PictureProfile.FLAT` sRGB-in-linear bug) |
| **5** | **Vendor `com.huawei.capture.metadata.cuvaStateVideoMode` (HDR Vivid) Research & Possible 10-Bit Input Unlock** | **Transformative** (could unlock native 10-bit BT.2020/HDR Vivid source buffers on EMUI 15 API 31 even without API 33 `DynamicRangeProfiles`) | **Vendor Key Exposed (`inventory.json`: request + session key!) — Unprobed** | **30–50%** *(vendor-gated)* | **Medium-High** (3–5 days careful empirical probing) |
| **6** | **Eliminate `1080p60` GPU/Encoder Drops (`019/021`) & Separate Analysis/Scope Readback Overhead** | **Medium-High** (raises `1080p60` GPU mode from `56.5–57.5 fps / 10–13 drops` back to native `59.92 fps / 0 drops`) | **Strong (`cam=0 drops` -> `gpu=10-13 drops`)** | **85%** | **Medium** (2 days: profile `glReadPixels`, buffer swap & AVC vs HEVC Main8 at `1080p60`) |
| **7** | **Dynamic Lab Storage Manager (`950 MB` Hard Cap Replacement)** | **High for R&D velocity** (prevents stopping at `21/24` with `122 GB` free; preserves knowledge & verified exports) | **Confirmed (`LabTelemetry.kt:124`)** | **99%** | **Low-Medium** (1–2 days) |
| **8** | **Constrained HFR `1080p120` With Explicit HEVC Level (`Main` L5.0/L5.1 & `Main10` L6.2)** | **Medium** (unlocks true 120 fps GPU/HEVC once encoder level is no longer omitted) | **Testable Hypothesis** (`015` omitted `KEY_LEVEL`) | **70%** (`Main8`), **40%** (`Main10`) | **Low** (1 day once `LabVideo` level injection added) |

---

## 2. Phased Implementation Plan

### Phase 1 — Research Lab Integrity & Dynamic Storage Manager *(Implemented & Host-Verified 2026-11-26; On-Device Execution Pending)*
1. **Fixed all 7 Research Lab & pipeline bugs** (`LabSyntheticEncoderProbe.kt` GLSL `.trimIndent()` + Main10 `HEVCMainTierLevel62` + 0-byte temp deletion; `LabRegularLogRunner.kt` + `OpenCineLogGpuPipeline.resolveBitratePlan` fixing `60M -> 120M` double scaling & wiring `onRecordedFrame`/`onFirstRecordedFrame` + `OpenCineLogFrameDiagnostics`; `LabSourceProbe.kt` quarantining unadvertised `PRESET_SRGB`, adding `CameraManager.AvailabilityCallback`, failing `SOURCE_FORMAT_PROBE` on empty `cameras`, locking AE/AWB + `safeLinearRolloffCurve`; `LabKnowledgeBase.kt` fixing `codec_arm_result` + `baseKey` separation + 0-frame `INCONCLUSIVE` guard).
2. **Implemented 20 GB Dynamic Storage & Safety Manager** (`LabStorageManager.kt`, `LabTelemetry.kt`, `ResearchLabScreen.kt`): `20 GB` configurable limit (`5/10/20/30/50 GB` UI chips), separate `5 GiB` device reserve, deduplicated canonical sizing, zero-temp-copy streaming SHA-256 readback ZIP export, and confirmation-gated verified media pruning preserving logs & knowledge.
3. **Implemented Async PBO Scopes & 360p Dual-Output Check**: `AsyncPboScopeReader` (`GL_PIXEL_PACK_BUFFER`), `DIRECT_HEVC_SURFACETEXTURE_360P`, and failure-driven `LabAutonomousPlanner` variants (`egl8`, `novui`, `scopes_pbo`, `360p`).

### Phase 2 — Device Verification of `Main10` (`001` & `002`) & `4K60 GPU HEVC` (`004`)
1. Run fixed Lab APK on `LMR-LX9`:
   - Execute fixed `001` matrix to get empirical **synthetic Main10 10-bit ramp (`effectiveBitsEstimate`), geometry, Level (`5.0/5.1` vs `6.2`), and EGL (`RGBA1010102` vs `RGBA8888`)** verdicts.
   - Run `003`/`004` with valid bitrate (`<= 80 Mbps` for 4K60) to verify `GPU_HEVC_4K60` (`Main8`) and apply winning Main10 recipe to `LOG_REGULAR_4K30` & `LOG_REGULAR_4K60`.
   - Run clean `SOURCE_FORMAT_PROBE` across physical cameras `"0"`, `"2"`, `"4"`, `"5"` + `YCBCR_P010` + `HardwareBuffer` format/usage inspection.
2. Run **Locked-AE/AWB Optical Chart Comparison** for `GAMMA_VALUE (3)` (`gamma=1.0`, `2.2`) + `TRANSFORM_MATRIX (0)` vs linear `CONTRAST_CURVE` (`0`) vs default ISP.

### Phase 3 — Production Camera Engine Upgrades (Post-Lab Verification)
1. **Unlock Production HEVC Main (`4K30`, and `4K60` if Phase 2 passes):**
   - Ensure `OpenCineLogGpuPipeline` & `HevcEncoderLevels` always pass explicit `KEY_LEVEL` clipped to the encoder's advertised levels (`Main` L5.0/L5.1) and clamp `RecordingDataRate.gpuVideoBps()` against `VideoCapabilities.bitrateRange.upper` (`100 Mbps` on HiSilicon) rather than crashing `configure()`.
   - Update `Camera2Descriptors` (`streamCanReachFps` / profile list policy) cleanly—gated by verified device quirk/probe rather than hardcoded assumptions—so `3840x2160@60` isn't hidden on Direct HEVC (`007` proven `59.923 fps` sustained!). *Keep all accepted Camera2 Surface sizes strictly anchored to the 40 sizes reported in `inventory.json`.*
2. **Production Direct 4K60 HEVC Path:**
   - Expose 30s-proven single-target / low-overhead preview `4K60` HEVC capture (testing whether `640x360` low-rate preview keeps `0` HAL drops).
3. **Production API 31 Color & Tone Control:**
   - Fix `PictureProfile.FLAT` linear-domain scaling mismatch vs ISP `TONEMAP_CURVE` **and/or** adopt `TONEMAP_MODE_GAMMA_VALUE (3)` + `COLOR_CORRECTION_MODE_TRANSFORM_MATRIX (0)` when verified unclipped on locked-AE chart tests.
   - Maintain strict provenance disclosure: label SDR-derived OCLog2 honestly (`POST_ISP_8BIT_SDR_LOG`) unless true `>8-bit` camera buffer + non-clipped dynamic range gain is proven!

### Phase 4 — Advanced Exploration (Vendor CUVA HDR Vivid & HFR)
- Probe `com.huawei.capture.metadata.cuvaStateVideoMode` and `1080p120` explicit-level OCLog2 after Core 4K30/4K60 and ISP controls are solid.

---

## 3. Dynamic Lab Storage Management Architecture (Resolving the 1 GB Cap)

### Why the Warning Triggered at `21/24`
- `LabTelemetry.kt:124` enforces a **static total directory cap** (`MAX_EVIDENCE_BYTES = 950_000_000L`) across ALL runs accumulated inside `context.filesDir/research-lab`.
- Telemetry proved each full 24-attempt run uses `~311 MB` (`Run 1: 40.7 MB` + `Run 2: 311.9 MB` + `Run 3: 310.3 MB` = `663.6 MB`). Therefore a 4th run had only `~286 MB` headroom before hitting `950 MB` around attempt `21/24`, despite **`~122 GB` of free device storage** (`freeBytes = 122,084,057,088`).

### Proposed Architecture (Safe, Non-Destructive, Reversible)
1. **Replace Static 950 MB Cap with Dynamic Storage Policy:**
   - **OS Safety Reserve (`MIN_FREE_BYTES`):** Dynamic `max(2 GB, 5% of total internal storage)` so the phone OS is never starved.
   - **User-Configurable Lab Budget (`evidenceCapBytes`):** Default `2.5 GB` (or `min(4 GB, 25% of freeBytes - reserve)`), adjustable in Research Lab settings UI (`1 GB / 2 GB / 4 GB / Unlimited within free reserve`).
2. **Per-Run Weighting & Graceful Degradation (Never Lose Knowledge or Progress):**
   - Never halt mid-experiment or lose `knowledge.json`, `inventory.json`, `telemetry.jsonl`, `report.md`, or `000..result.json` metadata (~`1–3 MB` per full run!).
   - Before exceeding budget, switch from Hard-Stop to **"Metadata + Critical Clips Only"** warning OR trigger **Export-Verify-Prune**.
3. **Export -> Checksum Verification -> User-Approved Selective Pruning:**
   - Track `"lastVerifiedExport"` (`epochMs`, `uri`, `zipSha256`, `exportedRuns`, `verifiedSizeBytes`) per run (`export_receipt.json`).
   - During `exportAll()`, compute streaming `SHA-256` + entry count and optionally verify readability of the written SAF `DocumentFile` via `ZipInputStream` before marking a run `EXPORT_VERIFIED`.
   - Offer one-tap **"Free Space from Verified Runs"** dialog (explicit user consent only):
     - **Option A (Safe Trim):** Delete large raw `.mp4` binaries from previously *exported & verified* older runs while keeping **100% of `knowledge.json`, `inventory.json`, `telemetry.jsonl`, `report.md`, `ffprobe`/hashes, and attempt JSONs** (reclaims `>99%` of space while keeping full cumulative lab memory!).
     - **Option B (Archive Purge):** Delete full older exported run folders except `global_knowledge.json`.
   - Always delete `0`-byte failed temp `synthetic_*.mp4` placeholders immediately in `finally`.
