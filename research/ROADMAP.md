# Research roadmap

Updated after **Stage G Wave 1–3 breakthroughs & live `Probe 8` production verification (`2026-11-27`)** on `research/autonomous-log-lab`. See [docs/research/engineering-roadmap.md](../docs/research/engineering-roadmap.md) and [docs/research/next-experiments.md](../docs/research/next-experiments.md).

| Priority | Track / Experiment | Decision / Unlock | Current Status |
|---:|---|---|---|
| **1** | **Zero-Copy 4K60 `ImageReader(PRIVATE, GPU\|VIDEO_ENCODE)` (`Probe 1, 6, 8`)** | `occ_egl_hwbuffer_jni.cpp` + `OpenCineLogGpuPipeline` breaks the `48 fps` `SurfaceTexture` wall (`camCb 59.940 fps, 0 gaps`; `15 s` 4K60 `Main10 L153` `99.66 Mbps`, upright frame verified) | **SHIPPED & VERIFIED LIVE (`Probe 8`)** |
| **2** | **CUVA HDR Vivid (`0x324`) Production `LOG` / `HLG` Profiles (`Probe 2, 6, 8`)** | `cuvaStateVideoMode=ByteArray[1]` + `HwVideoRecordingPre4K60En` (`<=3840x2160`) unlocks `235` `HLG10_BT2020` CUVA profiles incl. 4K30 & 4K60 | **SHIPPED & VERIFIED LIVE (`Probe 8`)** |
| **3** | **Production Direct `HEVC` Explicit Level & 4K60 Descriptor** | `VideoEncoderSelector` (`1516c3e6`) + `Camera2Descriptors` (`huawei4k60StreamDurationOverride`) exposes `3840x2160@60` without AVC fallback | **SHIPPED & VERIFIED LIVE (`Probe 8`)** |
| **4** | **Hidden `RAW_SENSOR (32)` Streaming (`Probe 4, 7`)** | Camera `"0"` `4000x3000 RAW_SENSOR` (`29.991 / 24.006 fps, 0 gaps`) & `8192x6144`; Camera `"2"` `8000x6016` — next feature candidate | **Hardware Proven (`Probe 4, 7`); Feature Integration Candidate** |
| **5** | **`0x7F000011 = P010_MSB_16LE` & True 10-Bit Input (`Probe 5`)** | `0x7F000011` confirmed `P010_MSB_16LE` (note 8-bit `3.11 MB` buffer size limit on 1080p); Vulkan `HardwareBuffer` / `ImageFormat.RAW_SENSOR` CPU/GPU paths next if >8-bit source input needed beyond CUVA `0x324` | **Characterized (`Probe 5`)** |
