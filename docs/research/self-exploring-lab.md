# OpenCineCam Research Lab

The Android lab runs short experiments through the production camera engine. Build it with `-Popencinecam.researchLab=true` and `:app:assembleDebug`. The APK identifies itself as **OpenCineCam Lab** with package `com.librestatic.opencinecam.researchlab`. It can install alongside both the Play and the ordinary debug packages. Open **Settings → Camera capabilities → Research Lab**. Select a camera on the capabilities screen to choose its ID, point it at a well-lit static scene, and start. The page holds the camera only during a user-initiated run. Back during a run requests a stop. The next Back returns to Camera capabilities.

## Source and decisions

This branch starts from the Pura build commit `97021442`. At that point the difference from `main` at `59a5260a` is the APK workflow. The shipped source already separates regular and constrained high-speed recording and treats file signaling and effective source precision as separate evidence.

| Stage | Production entry point | Evidence in the lab |
| --- | --- | --- |
| Discover profiles | `Camera2PreviewEngine.descriptors`, `Camera2Descriptors` | App menu tuples, public output formats and sizes, public AE ranges and HLG10 gate |
| Configure and start | `Camera2PreviewEngine.startPreview`, regular or constrained paths in `Camera2SessionGraphs` | Preview readiness, actual request/result FPS range, sensor frame numbers and timestamps, graph loss events |
| Render | The production GLES `OpenCineLogGpuPipeline` for SDR, OCLog2, HLG and HDR10 | GPU submission PTS, source dataspace when present, YCbCr conversion or driver delegation, EGL target depth, OCLog2 shader hash, preview-sink HardwareBuffer description |
| Encode and mux | `startVideo` or `startOpenCineLogVideo`, production Surface encoder, `EncodedSampleListener` | Selected codec and fallbacks, input track format, sample bytes and PTS, finalization and descriptor retirement |
| Inspect published bytes | Android `MediaExtractor` and production `Main10FileChecks` | Container format, sample PTS cadence and gaps, color signaling, result against requested raster and codec, one platform-decoded thumbnail check, and clip SHA-256 |
| Isolate RAW stages | Production `RawVideoQualifier`, with RAM camera stage followed where possible by its JNI block-pack stage | Per-stage RAW reports, capabilities, progress and native encoding results |

### Source history that constrains the experiments

- The regular LOG descriptors require an HLG10 source and an advertised BT.2020 HLG color profile on this source revision. The HFR descriptors admit a constrained session with a standard-range ISP source. They share OCLog2 output tooling with different source provenance. Related commits include `acfe23b3`, `8917e6f1`, `426fef11`, `1e58e3c9`, and the HDR10 signaling revisions `45b9628f` and `0225ecaf`. See [PLAN-061](../plans/PLAN-061-oclog2-specification-interchange-and-device-qualification.md).
- RAW qualification stages separate camera throughput, native compression and storage. Commit `22a44c70` documents the shorter qualification path. Revisions `0efc5837` and `59a5260a` document high-rate metadata load and an observed provider stall on a zoom change during RAW recording. Research leaves these guards in place.
- The production Java/Kotlin GLES path owns the camera texture and encoder surface. The native AHardwareBuffer import/processing and C++ hardening contracts in [native-pipeline](../../native-pipeline/README.md) are host-only on this revision. Android builds include native RAW codecs and CRC32C through `:media`. The lab describes a buffer from its preview sink. Its format describes that sink, and the camera source precision remains unknown.
- The portable APV contract in `media/apv/ApvQualification.kt` has no production recording engine on this revision. The lab also leaves vendor-only dynamic-range controls outside its test envelope.

## Search and persistence

The first controls emphasize 1080p30 SDR, UHD OCLog2 when requestable, HFR OCLog2 and RAW10 when advertised. Candidate sizes and frame rates come from the production descriptors and public Camera2 output/rate information. If a regular tuple is hidden by the app, the lab may submit it using a public texture size and a range that contains the target. It preserves the engine's normal request policy, so the submitted range and observed cadence can differ from the target. HLG-derived trials additionally require an advertised HLG10 dynamic range. HDR10 uses the engine's existing profile gate.

After a failure, the search can hold the camera/GPU graph fixed and leave the codec idle, lower only the frame rate or raster, or compare AVC and HEVC. After a success, it can raise rate or size, compare OCLog2 and HLG on the same HLG source, or repeat for longer. Each follow-up names its parent and hypothesis. Candidate loss due to API or advertisement is scoped to the public interface and exact camera ID, which leaves hardware-level capabilities open.

The batch admits at most 12 completed or interrupted attempts. Each foreground window is bounded to about three minutes plus retirement, and takes are normally four seconds, with selected ten-second comparisons. The lab checks thermal status, battery, free storage and its own evidence consumption. Normal imaging uses a separate data directory by package identity. Lab recording is silent and writes private files by descriptor. There is no MediaStore publication, automatic upload, or mutation of the operator's camera settings or RAW qualification records. The Camera2 observer is absent in ordinary capture.

The ZIP contains one directory per run with `run.json` and `inventory.json`. Each numbered attempt has its requested settings and parent first, incrementally written stage JSON, the clip if created, and the terminal `result.json`. A finished run has `summary.json`. When a process stops before `result.json`, resume marks that attempt as interrupted with its last journaled stage and moves past it. A manual stop is resumable. Missing tags and timestamps appear as JSON null. Export uses Android's document picker.

## Reading outcomes

- **OBSERVED** means the specified check ran on the selected graph. A short clip that passes its MP4 checks provides cadence, file and representative frame-decode evidence. RGB preview statistics may reflect Android display processing. RAW stages provide camera or codec throughput evidence for a short window.
- **MISMATCH** means a recorded file differed from the requested codec, signal, raster or cadence.
- **FAILED**, **INTERRUPTED**, and **STOPPED** retain the furthest journaled boundary or reason. A timeout is scoped to the stage and test settings.
- Main10 signaling establishes file profile. A claim about effective sensor-to-file precision requires controlled chart capture, independent decoder observations and a matching source graph. The app's upstream [Resolve protocol](../color/oclog2-resolve-protocol.md) and `tools/qualify_main10_file.py` cover independent work.

The debug variant is appropriate for bring-up and short comparisons. Sustained thermal and cadence sign-off requires physical Pura footage with controlled light and a measured production-like build. The test scene, stabilization, temperature, sensor route and software version should be fixed when comparing multiple runs.
