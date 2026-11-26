# Next Discriminating Experiments (`LMR-LX9`, Pura 80 Pro)

Updated after **`082c0332` (`47/47` Performance-Mode run)** + live Kirin 9020 `dumpsys` audit (`2026-11-27`). Targeted empirical hardware probes (`Pura80BreakthroughProbeTest` on `.debug.test`, preserving `.researchlab` intact) directly resolve every remaining high-impact unknown.

---

## Top 5 High-Impact Hardware Breakthrough Probes

### 1. Break the `48 FPS` `4K60 SurfaceTexture` Wall (`probe1_4k60CameraInputIsolation`)
- **Why (`082c0332-009..019`):** Even with Power Saving OFF, Performance Mode ON, and encoder completely idle (`1.852 ms` GL frame), Camera2 -> `4K60 SurfaceTexture` drops from `59.43–59.93 fps` (`CAM_CB`) to `48.0 fps` (`191` frames, `45` gaps). Meanwhile `Direct MediaCodec` + `720p/360p SurfaceTexture` sustains `59.92 fps` (`0` drops) for `30 s`!
- **Arms:** Compare `SurfaceTexture` control vs `ImageReader(PRIVATE, maxImages=6)` with: (a) `USAGE_GPU_SAMPLED_IMAGE`, (b) `USAGE_GPU_SAMPLED_IMAGE | USAGE_VIDEO_ENCODE`, (c) `USAGE_VIDEO_ENCODE`, (d) Huawei `cameraSessionSceneMode = 28 / 31` (`hwVideoRecordingPrev60FpsScene=[28,31,68]`), and (e) `SurfaceTexture` + `360p USAGE_VIDEO_ENCODE` companion.

### 2. Live **CUVA HDR Vivid (`0x324`)** Direct & GPU Recording (`probe2_CuvaHdrDirectAndGpuRecording`)
- **Why (`082c0332-025`):** `cuvaStateVideoMode = ByteArray[1]` switched companion `PRIVATE` buffer `34 -> 804 (0x324)` and drove highlight clipping to `0.0%` (`144.7 -> 160.3`).
- **Arms:** Record real MP4s (Direct 4K30 CUVA OFF vs ON under locked exposure, **Direct 4K60 HLG/Main10 WITH CUVA ON**, and **GPU 4K30 CUVA ON**) and inspect with `ffprobe` + 10-bit `yuv420p10le` `Y`-plane code distribution & luma headroom!

### 3. Camera-Closed `4K60` Synthetic `Main10 L6.2` Throughput + EGL Priority (`probe3_Synthetic4k60ThroughputAndPriority`)
- **Why:** Isolate whether `OMX.hisi.video.encoder.hevc` itself sustains `60.0 fps` at `4K60` (`Main10 L6.2` vs `Main8 L5.2`) when freed from Camera2 `SurfaceTexture`, and measure `EGL_IMG_context_priority` (`HIGH`) + `KEY_PRIORITY=0` + `KEY_OPERATING_RATE=60` on swap time (`15.5–17.7 ms` -> `< 16.6 ms`?).

### 4. Hidden `RAW_SENSOR (32)` / `hwInconsistentRawStream` Probe (`probe4_HiddenRawSensorAdmission`)
- **Why (`dumpsys media.camera`):** Logical/physical characteristics hide RAW from `SCALER_STREAM_CONFIGURATION_MAP`, yet expose `hwCaptureRawStreamConfigurations = [32, 8192, 6144, 0]` / `[32, 8000, 6016, 0]`, `hwCaptureRawFormat = 32`, and `hwInconsistentRawStreamSupported = 1`.

### 5. `0x7F000011` (`84.5 KB`) vs `0x7F00000F` / `YUV420Flexible` True 10-Bit Ramp Encode (`probe5_VendorByteBuffer10BitClips`)
- **Why (`082c0332-001` & `LabDecode` bug):** `0x7F000011` emitted `84,556` B (`4.2×` `0x7F00000F` / `YUV420Flexible`) with `spsBitDepthLuma=10`, but `LabDecode`'s `COLOR_FormatYUV420Flexible` truncated decoded frames to 8-bit. Encode NV12 / planar I420 / packed-10-bit ramps and pull MP4s for host `ffmpeg yuv420p10le` inspection!
