# Field protocol for the Pura 80 Pro capture probes

Install the debug APK from the **Pura test APK** workflow on this branch. Select the logical rear camera (ID 0 for the baseline unit). Open camera capabilities → Research Lab → Start a new run. Keep the phone steady in bright, stable light. Frame four independently recognizable corners, a diagonal and a moving marker. Keep the same scene for both direct attempts. The private, silent recordings and JSON can be exported with the existing ZIP action. A pause can be resumed on the same build. Match the revision in run.json to the APK build before comparing sessions.

| Experiment | Read in the exported attempt | Decision |
|---|---|---|
| X-01A DIRECT_HEVC | direct_graph, direct_step, direct_capture_receipt, FILE | Regular Camera2 TEMPLATE_RECORD, fixed [60,60], one recorder target, 3840×2160, 60 Mb/s requested. Test the existence of a short HEVC path. |
| X-01B DIRECT_HEVC_PREVIEW | parent attempt ID, previewImagesDrained, physical ID, lost buffers, sensor and file timing | Same request plus a drained 1280×720 PRIVATE ImageReader. A split implicates this second output. Pura Cinema used a display preview, so buffer consumer differences remain open. |
| X-02 CODEC_LEVEL_PAIR | codec_admission_arm and codec_arm_result for both levels | Fresh instances of the same hardware HEVC Main encoder, UHD30, 20 Mb/s. Only KEY_LEVEL changes from absent to Main Tier 5.1. ADMITTED means both starts succeeded with zero frames submitted. A split warrants an encoded trial before changing defaults. |
| X-00 GPU HEVC and HFR LOG | configured_gpu_source and gpu_encoder_step with enter/ok/failed, exact configure format, native diagnostics and fallback | Identify descriptor substitution or localize failure to selection, creation, configure, input surface, muxer, EGL or start. The configured source describes the requested pipeline input. Sensor precision requires an independent measurement. |
| X-03 every finalized clip | decoded_frame_and_clip_hash → decoder.geometryScreen and geometrySamples | Three requested presentation times. Two samples on the same axis with RGB8 half-shift MAD below 4 and quarter-shift MAD above 15 trigger REVIEW. Flat views are inconclusive. Recheck suspects in coded coordinates with software decode and the asymmetric target. |

Direct attempts require a listed recorder size and fixed AE range. The paired attempt also requires a listed PRIVATE preview size. Check inventory.json when an arm is missing. A four-second take must provide at least 3.5 seconds of sensor and file timing, each within 0.5% of target average, with no repeated timestamps or intervals over 1.5 periods. The file must decode and match HEVC and raster. The two-output arm requires delivered preview images. Inspect the reported physical ID and observed sample payload bitrate. A short-run observation leaves motion uniqueness and thermal endurance open. Advance to 30 and 120 seconds only after the moving marker and chart check out. The suite adds no product recording preset.

Entry points are LabProfiles and LabSearch for admission and ordering, LabDirectRecorder and LabCodecLevelPair for isolated probes, LabVideo and LabSignals for evidence, and LabGeometry for picture screening. Production GPU instrumentation lives in OpenCineLogGpuPipeline.encoderStep. Compare with E-OCC-007/008/011/012, E-PURA-5792636/5799500 and E-PURA-GEOM in [the evidence index](EVIDENCE.jsonl).

## Independent geometry replay

Use Python 3.11+, FFmpeg and ffprobe on an exported MP4. The helper reads the video in coded orientation and prints its SHA-256, sample indices, four RGB distance measurements per frame and screen result. With `--controls`, it injects exact horizontal and vertical repeats in memory using the same decoded images.

```sh
python3 tools/check_lab_geometry.py --controls path/to/clip.mp4
```

`E-HOST-GEOM-01` exercised 24 decoded pictures across the eight archived AVC recordings. All eight originals produced NO_REPEAT_FLAG. All 16 injected clip variants flagged the correct axis. A naturally periodic target can trigger the same detector. Use the four different corners and compare the phone and host receipts.
