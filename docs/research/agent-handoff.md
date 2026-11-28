# OpenCineCam Engineering Handoff — Huawei Pura 80 Pro (`LMR-LX9`)

> **Astra LOG R1** is the specialized LOG development on `research/astra-log-r1`, based on `b8b91962` with Wave D M2 and initial M3. See the [Astra engineering handoff](astra-log-r1-handoff.md) for live CUVA, offline HLG conversion, qualification and merge boundaries. The autonomous lab can carry newer work outside LOG.

**Last updated:** 2026-11-28 (Post Wave D Milestone 2 Hardware Verification; Entering Wave D Milestone 3 Offline Engineering)  
**Target device:** Huawei Pura 80 Pro (`LMR-LX9`, Kirin 9020, EMUI 15, Android API 31)  
**Active research branch:** `research/autonomous-log-lab`  

---

## Documentation Map (`docs/research/`)

- [Wave D Milestone 2 Verification (2026-11-28)](wave-d-milestone-2-verified.md) — **All 7 hardware exit gates PASSED** on live Pura 80 Pro (`53K0226129002419`): 4K60 Direct 10-bit HLG `0` drops (`cam0/4/5`), dual-surface `USAGE_GPU_SAMPLED_IMAGE` preview, 2-phase Variable Aperture iris settle (`f/1.6` vs `f/4.0`), A/V boottime sync, Async PBO scopes, and curated [M1](evidence/wave-d-m1/) / [M2](evidence/wave-d-m2/) JSONs.
- [Wave C Production & 10-Bit Truth (2026-11-27)](wave-c-production-and-10bit-truth-20261127.md) — Variable aperture (`vaFNumber`), Kelvin WB, OIS isolation, and 10-bit quantization forensics.
- [Wave B & Incident Hardening Breakthroughs (2026-11-27)](wave-b-cinema-breakthroughs-20261127.md) — `CameraDaemon` hardening, `Probe 14A/14B/15`, quarantined `PRESET_CURVE (0x45f)`.
- [Deep Forensic Analysis](deep-analysis.md) · [Evidence Index](evidence-index.md) · [Hypotheses](hypotheses.md) · [Engineering Roadmap](engineering-roadmap.md)

---

## Proven Kirin 9020 Hardware Laws (Wave D M1 & M2)

1. **Direct 10-bit CUVA HLG (`0x324`) Dual-Surface Law:** Two `USAGE_VIDEO_ENCODE` surfaces deadlock Kirin HAL at Frame 50 (`-16 EBUSY` on `deleteStream`). Keeping Stream 1 preview `USAGE_GPU_SAMPLED_IMAGE`-only (`640x360` @60fps, `1280x720` @30fps) with 2-frame burst `[withPreview, codecOnly]` and Kirin cadence-lock vendor keys holds **59.92–60.05 fps / 0 drops** across `cam0`, `cam4`, and `cam5`.
2. **Optical Variable Aperture Settle Law:** Kirin HAL resets iris blades to `f/2.0` around Frame 5 of fresh Direct 10-bit sessions and requires an edge transition (`0f -> targetFNumber`). Running a pre-record preview-only settle (`>=7f / >=230ms` neutral `0f`, then `>=6f / >=180ms` target `f/N`) holds `f/1.6`, `f/2.0`, `f/2.8`, `f/4.0` from Frame 0 with `0` drops.
3. **A/V Clock Normalization:** `CameraTimestampBootMapper` maps `SENSOR_TIMESTAMP` + AAC `AudioTimestamp` onto shared `boottime` anchors (`13/13` M2 clips clean, `av_sync_clean=true`).
4. **Open Research & Offline Targets (Milestone 3):**
   - **OCLog2 Color Accuracy & Range Bugs:** Real M2 clip comparison exposed (a) spurious `bt709ToBt2020` matrix applied to already-BT.2020 CUVA HLG input (`-46%` red chroma crush), (b) `OpenCineLogYcbcrConversion` defaulting `null` dataspace to `BT601` instead of `BT2020` on API 31, (c) HiSilicon `COLOR_RANGE_FULL` RGBA8 `-60` code shadow shift (`8.41%` pixels `< 102`), and (d) `-1.9` stop middle-grey mismatch in native OCLog2.
   - **GPU 4K60 OCLog2 Bottleneck:** Rotated `COMPATIBLE` 4K60 GPU OCLog2 ran at `47.23 fps` (`59` drops) due to redundant View-Assist/FlatMonitor shader ALU + rotated 4K swap overhead.
   - **Hidden `RAW_SENSOR (32)` (`4000x3000@24/30`, `3648x2736@30`):** Streams cleanly (`0` gaps), but `probe17d` pixel stats showed sparse non-zero values and flat EV — requires buffer layout forensics and experimental `RAW16` `OCRAW` / `RAW -> LOG` tooling.
   - **Huawei Native `H_LOG` & >8-Bit P010 Compute:** HarmonyOS exposes `H_LOG`; EMUI 15 API 31 lacks `CameraKit`, while `OMX.hisi` accepts `COLOR_FormatYUVP010` ByteBuffers and `ANativeWindow(0x324)` lock/post.
