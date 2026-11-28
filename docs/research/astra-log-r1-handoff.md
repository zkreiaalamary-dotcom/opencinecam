# OpenCineCam — Astra LOG R1

## Baseline and scope

This research branch starts at `b8b919623c91f6ae6d5f4940a4b55c48ef224572` on `research/autonomous-log-lab`. It includes Wave D Milestones 1 and 2 and the initial Milestone 3 code, including the direct Main10 HLG graph, vendor cadence changes, source-primaries selection, OCLog3 v1 reference, and technical LUTs. Astra is the specialized LOG branch. The autonomous lab can continue shipping camera, device and field-test changes independently.

## Live CUVA / OCLog2

The Huawei CUVA live pipeline preserves the native OCLog2 encoding gain when MATCH_SDR is selected. The original matching gain of 3.7396 makes optical signal above approximately 0.752 HLG collide at the OCLog2 white code. The view assist already adapts native mid-grey for display. This change preserves that display function while avoiding the gain-and-clamp operation in recorded OCLog2. The stored transfer remains OCLog2 v2 with its existing [0,1] scene-input transport and full-range BT.2020 encoding. Old MP4 and sidecar interpretation is unchanged.

The Astra HLG GLSL variant moves lens correction to decoded linear source RGB and runs HLG decode only once. For the explicit YCbCr sampler it preserves RGB outside the nominal interval until the output transform. HLG is extended continuously by reflecting the inverse through zero and extending its positive branch above nominal white. Some excursions may already be lost in the driver sampler. The final live OCLog2 clamp and HiSilicon RGBA8/TPDF/Main10 transport remain. Dither can reduce visible banding and cannot preserve arbitrary per-pixel sub-LSB detail lost by the EGL surface.

Wave D selects native BT.2020 for Huawei CUVA. Astra carries this as an unmeasured source-profile assumption and keeps BT.709 vs BT.2020 selectable via the research override for controlled shader A/B. The sidecar records the selected interpretation, source path and applied shader hash, while marking Astra live as experimental. The direct HLG sidecar declares that the file is camera-to-encoder. File tags and source-color calibration are separate facts.

The lab reads P010 luma from its fixed MSB alignment and only when the decoder exposes the expected image format. A dark P010 patch with code 8 stores word 512 and must decode back to 8. Code occupancy estimates remain distinct from sensor precision.

## Direct HLG to floating-point Astra LOG

Use the original Wave D direct clip as the camera master. The offline converter retains the original file, decodes its planar 10-bit video, and reconstructs chroma using declared or specified siting with bilinear interpolation. It applies the selected YCbCr matrix and range, signed inverse HLG without any display OOTF, reference-grey gain, an independent linear source-to-BT.2020 matrix, then the Astra transfer. Math is binary64 until export to losslessly compressed float32 RGB OpenEXR. The original audio stays in its source file. The conversion writes a sidecar with source interpretation, time base, reported rotation, SAR and per-frame source timestamps. It creates a new destination and refuses overwrite. Incomplete exports have no success manifest.

```sh
node tools/astra_hlg_to_log.mjs camera-master.mp4 astra-frames --chroma left
```

Use Node.js and FFmpeg/ffprobe on PATH. Supply `--chroma` only if you know the source siting. `--matrix bt2020nc|bt709`, `--range tv|pc` and `--primaries bt2020|bt709` override the stated file interpretation independently. Optionally supply `--calibration path.json` with a row-major `to2020` 3×3 and optional `greyHlg`; `--grey` overrides the reference. Defaults from file tags and the 0.38 HLG grey reference do not certify the physics of a specific camera. The source decoder still cannot reverse ISP processing, 4:2:0 subsampling or HEVC quantization.

The float interchange transfer is `opencinecam.astra-log-r1.float.v1`, with linear BT.2020 D65. For x in [0,16] it matches the existing OCLog3 v1 analytical transfer: 0 → 0.095, 0.18 → 0.400 and 16 → 0.920. The positive branch extends above 16. For x < 0, the code is `0.095 - 0.02 * ln(1 - x * 5.570237810615516 / 0.02)`; it meets the positive toe with the same derivative at zero. The signed and overrange code domain requires floating-point interchange. Reference HLG at 0.38 maps to 0.400; nominal HLG peak maps to ~0.749218 at that scale. A format or LUT that bounds the values to [0,1] can destroy excursions.

In Resolve, import the EXR sequence in an unmanaged project with any automatic EXR input transform disabled, apply `docs/color/astra-log-r1/astra-log-r1-inverse.dctl` as the first operation, then apply the scene-linear BT.2020 to working-space transform and finally the desired display transform. Set sequence rate and display orientation using the recorded source data. For VFR, timestamps are in the sidecar and are not embedded in the EXR sequence. Conform audio against the retained original video. Verify the numerical path locally with a float EXR export before relying on the grade. Use independent technical and display transforms instead of a baked cube for signed excursions.

## Verification and remaining gates

Seven host-side reference cases passed in a JavaScript harness using the same numerical and frame-assembly functions shipped here. Cases cover inverse/monotonicity, derivative at the joins, legacy clipping versus high-light separation, neutral/color signals, distinct near-white 10-bit levels, chroma phase, rejection of ambiguous metadata, chunked frames and DCTL analytical parity. The DCTL was evaluated mathematically. Resolve execution and GLES compilation on the device remain separate gates.

The Windows Gradle wrapper could not fetch its distribution and the local environment then became unavailable. The data plane, shaders and packaging still require Android build and physical qualification. The historical Wave D JSON is baseline evidence, not a measure of Astra image quality. The old Windows media export was inventoried before disconnection; Astra conversion of a physical clip remains pending.

## Device handoff

1. On LMR-LX9, camera 0 at 4K30, compare Wave D direct HLG against Astra live OCLog2 with NATIVE and MATCH_SDR. Use a fixed grey and highlight wedge, saturated patches, locked ISO/shutter/aperture/WB and timestamp-based result matching. Compare 0.75–1.0 HLG codes, chroma and highlight separation, with a display-matched comparison in Resolve.
2. Capture the same RGB/YCbCr source under the two primaries interpretations, a dark patch and a bright neutral patch, and distinguish chroma/range/primaries errors. Leave the configuration experimental until it repeats on another illuminant and on camera 4 and 5. Use a separate characterization for each lens.
3. At 4K60 check unique frame content and timestamp cadence, not only mean FPS. Compare 30 s then 5 min, preview on/off and audio on/off, with fixed power mode, cooling and exposure. Retain frame/run references and separate app loss from sensor cadence.
4. Check calibration-table basis before reuse. The live linear stage belongs before the source gamut conversion. Regress the older OCLog2 sidecars and decoder, alongside the signed offline range and EXR round trip. RAW, native H_LOG, and a GPU-to-encoder true 10-bit transport remain research work.

When merging, preserve the direct session and audio fixes from newer lab work. Reconcile conflicts in `OpenCineLogGpuPipeline.kt`, `AstraLogShader.kt`, `Camera2PreviewEngine.kt`, `CaptureServiceSidecars.kt` and `LabDecode.kt` by behavior. In particular, preserve the position of the linear lens operation, the live unity gain and the exact source/transport provenance. Keep the old OCLog and OCLog3 identifiers, inverse transforms and hashes usable for previously recorded clips. The new float transfer, converter and inverse DCTL must remain numerically synchronized.
