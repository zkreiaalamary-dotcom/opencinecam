# Research roadmap

Updated after **`082c0332` (`47/47` Performance-Mode run)** + live Kirin 9020 `dumpsys` audit (`2026-11-27`) on `research/autonomous-log-lab`. See [docs/research/engineering-roadmap.md](../docs/research/engineering-roadmap.md) and [docs/research/next-experiments.md](../docs/research/next-experiments.md).

| Priority | Track / Experiment | Decision / Unlock | Current Status |
|---:|---|---|---|
| **1** | **Break `48 fps` `4K60 SurfaceTexture` Wall (`H-11` Refuted by `009`)** | `009` proved `CAM_CB=240` vs `ST=191` (`45` drops) with encoder idle! Test `ImageReader(PRIVATE, USAGE_VIDEO_ENCODE)` vs `SurfaceTexture` & `cameraSessionSceneMode=28/31` | **Ready for Live Probe (`53K0226129002419`)** |
| **2** | **CUVA HDR Vivid (`hwBufferFormat=0x324`) Direct 4K30/4K60 & GPU Recording** | `025` proved `cuvaStateVideoMode=ByteArray[1]` switches buffer `34->804` with `0%` clip! Record real MP4s & measure 10-bit codewords | **Ready for Live Probe (`53K0226129002419`)** |
| **3** | **Fix Lab Completion (`46 done·1 remaining`) & `c8959e8a` Ghost Checkpoint + `LabDecode` 8-bit Mask** | Sync UI `Complete` count, block `latestResumable` from resurrecting old runs after `COMPLETED`, preserve `0x7F000011` MP4s | **Queued in Wave 1 Stage 2** |
| **4** | **Promote Proven `Direct 4K60 HEVC` + `4K30 gamma_2_0/2_2 OCLog2 Main10`** | Both sustained `0`-drop hardware operation under `082c0332` (`59.92–59.97 fps` & `29.99 fps`) | **Wave 2 Production Integration Target** |
| **5** | **Hidden `RAW_SENSOR (32)` / `hwInconsistentRawStream` & `0x7F000011` 10-Bit Decode** | `dumpsys` exposes `hwCaptureRawStreamConfigurations=[32,8192,6144,0]` & `0x7F000011` (`84.5 KB`); verify empirically | **Ready for Live Probe (`53K0226129002419`)** |
