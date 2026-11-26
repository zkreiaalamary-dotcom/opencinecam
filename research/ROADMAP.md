# Research roadmap

Priority order updated for the primary objective: **highest-quality cinematic 4K30 and 4K60 LOG capture** on Huawei Pura 80 Pro (LMR-LX9, API 31), separating hardware limits from API/software gates, while evolving the app into an autonomous research lab.

| Priority | Experiment / Track | Decision it unlocks | Status / Evidence |
|---|---|---|---|
| 1 | `X-02` / `X-03` Synthetic EGL Main8 & Main10 matrix | Isolates HEVC `Main`/`Main10` configure/level/range/EGL contracts, 10-bit ramp precision, and repeat-half geometry with camera closed | Phase 2 implemented & host-tested (`E-HOST-PHASE2-01`); device execution pending |
| 2 | `X-LOG-4K` Regular-session 4K30 then 4K60 `OCLog2` & GPU HEVC | Tests whether `OpenCineLogGpuPipeline` (`SDR_BT709_ISP` + `OCLog2` + learned Main10 recipe, and Main8 GPU HEVC) runs cleanly at 4K30/4K60 on API 31 | Phase 3 implemented & host-tested (`E-HOST-PHASE3-01`); device run pending |
| 3 | `X-01` / `X-04` Direct 4K60 sustained (30 s) + `SurfaceTexture` live preview | Determines if 4K60 HEVC sustains thermally with a live display-style preview and initial `[60,60]` session parameters | Phase 3 implemented (`DIRECT_HEVC_SURFACETEXTURE`, 30 s sustained, `E-HOST-PHASE3-01`); short 4 s direct passed (`E-OCC2-R2-001/002`) |
| 4 | `X-05` / `X-06` ISP ToneMap A/B & High-Precision Source Probe | Verifies whether `TONEMAP_MODE_CONTRAST_CURVE` (`PictureProfile.FLAT`) reduces clipping on Pura ISP, and probes `RAW_SENSOR`/`P010`/vendor keys across physical IDs | Phase 3 implemented (`LabSourceProbe.kt`, `E-HOST-PHASE3-01`); device run pending |
| 5 | `X-AUTO` Autonomous Lab Engine & Thermal Telemetry | Eliminates external agent round-trips: hypothesis ledger, auto-escalation (4K30 -> 4K60, 4 s -> 30 s), inconclusive retries, continuous `telemetry.jsonl`, `report.md` | Phase 4 complete on host + Research Lab APK built (`E-HOST-PHASE4-01`); ready for Pura 80 Pro run |
| 6 | `X-07` HFR 1080p120 OCLog2 (with explicit level) | Tests whether explicit HEVC level unblocks 1080p120 after 4K LOG is resolved | Lower priority; `E-OCC2-R2-005` failed at `configure` without level |
| 7 | `X-08` Regional stock / CameraKit provenance | Compares global SDR/HDR vs China HarmonyOS Video Log when specimen files are available | Read-only `CameraKit` & vendor-key inventory added in Phase 1 |
