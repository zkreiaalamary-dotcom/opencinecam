# Research roadmap

Priority order updated for the primary objective: **highest-quality cinematic 4K30 and 4K60 LOG capture** on Huawei Pura 80 Pro (LMR-LX9, API 31), separating hardware limits from API/software gates, while evolving the app into an autonomous research lab.

| Priority | Experiment / Track | Decision it unlocks | Status / Evidence |
|---|---|---|---|
| 1 | `X-02` / `X-03` Synthetic EGL Main8 & Main10 matrix | Isolates HEVC `Main`/`Main10` configure/level/range/EGL contracts, 10-bit ramp precision, and repeat-half geometry with camera closed | Phase 2 implemented & host-tested (`E-HOST-PHASE2-01`); device execution pending |
| 2 | `X-LOG-4K` Regular-session 4K30 then 4K60 `OCLog2` & GPU HEVC | Tests whether `OpenCineLogGpuPipeline` (`SDR_BT709_ISP` + `OCLog2` + learned Main10 recipe, and Main8 GPU HEVC) runs cleanly at 4K30/4K60 on API 31 | Blocked previously by missing `KEY_LEVEL` and `videoProfiles` gating (Phase 3) |
| 3 | `X-01` / `X-04` Direct 4K60 sustained (30 s) + `SurfaceTexture` live preview | Determines if 4K60 HEVC sustains thermally with a live display-style preview and initial `[60,60]` session parameters | Short 4 s direct + `ImageReader` passed at `59.893`/`59.920 fps` (`E-OCC2-R2-001/002`) (Phase 3) |
| 4 | `X-05` / `X-06` ISP ToneMap A/B & High-Precision Source Probe | Verifies whether `TONEMAP_MODE_CONTRAST_CURVE` (`PictureProfile.FLAT`) reduces clipping on Pura ISP, and probes `RAW_SENSOR`/`P010`/vendor keys across physical IDs | `raw10Sizes=[]` on camera 0; physical cameras & `RAW_SENSOR` untested (Phase 3) |
| 5 | `X-AUTO` Autonomous Lab Engine & Thermal Telemetry | Eliminates external agent round-trips: hypothesis ledger, auto-escalation (4K30 -> 4K60, 4 s -> 30 s), inconclusive retries, continuous `telemetry.jsonl`, `report.md` | Phase 1 telemetry done; planner/KB/report in Phase 4 |
| 6 | `X-07` HFR 1080p120 OCLog2 (with explicit level) | Tests whether explicit HEVC level unblocks 1080p120 after 4K LOG is resolved | Lower priority; `E-OCC2-R2-005` failed at `configure` without level |
| 7 | `X-08` Regional stock / CameraKit provenance | Compares global SDR/HDR vs China HarmonyOS Video Log when specimen files are available | Read-only `CameraKit` & vendor-key inventory added in Phase 1 |
