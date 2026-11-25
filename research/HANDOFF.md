# Handoff · Huawei Pura 80 Pro global

**First action on the capture-probes branch.** Install the research APK and start a fresh run on logical back camera 0. Use [the field protocol](CAPTURE-PROBES.md) to interpret the direct UHD60 pair, HEVC level experiment, GPU preparation receipts and decoded geometry screen. The original observations below remain the baseline.

| Question | What you can rely on | Evidence |
|---|---|---|
| Does the OpenCineCam run prove UHD60 recording? | Its 4K60 profile was dropped before encode. A 1080p source was handed a UHD recording geometry. Separately, Pura Cinema measured 59.920 fps through one camera output for a short HEVC Main take, with no logged long intervals. | `E-OCC-007/008`, `E-PURA-5792636/5799500` |
| Why did HEVC fail here? | HiSilicon refused the exact UHD30 HEVC Main configure request. The file is disclosed AVC fallback. Pura's MediaRecorder path reaches HEVC Main UHD30. A missing explicit HEVC level is the highest-value format delta to test. | `E-OCC-012`, `E-PURA-5759920`, `X-02` |
| Can Main10 produce a file? | Yes on a prior Pura route, with 10-bit SPS and independent decode on archived originals. Three originals across two paths exhibit near-duplicated encoded-image halves. This blocks any usable Main10 or Log qualification. | `E-PURA-5698009/5710757`, `E-PURA-GEOM`, `X-03` |
| Is the 1080p60 mismatch real? | One 30.247 ms interval appears first in consecutive sensor timestamps and then in GPU and file. No duplicate PTS or observed frame-number drop. | `E-OCC-002/003`, `X-04` |
| Does this device have verified OCLog2? | Regular HLG-derived OCLog2 is gated on API 31; the separate SDR-ISP HFR branch stopped during preparation. Source precision and any global stock Log are open. | `E-OCC-011/CAPS`, `E-PURA-PRECISION/STOCK`, `X-07/08` |

## Read in this order

1. [STATE.md](STATE.md) for branch context and current policy.
2. [ANALYSIS.md](ANALYSIS.md) for causal chains, competing hypotheses and the pinned source links. Start within `P-UHD60-GRAPH`, `P-HEVC-MAIN` and `P-MAIN10-GEOMETRY`.
3. [EVIDENCE.jsonl](EVIDENCE.jsonl) for exact attempt folders, clip and source hashes, and directly comparable measurements. Retrieve the raw attachment or authorized Pura evidence only by the targeted file/experiment IDs. Entries labelled prior are reports and audited metrics preserved from the earlier project.
4. [ROADMAP.md](ROADMAP.md) for small differential tests and their promotion conditions. Use the existing [lab design](../docs/research/self-exploring-lab.md) and [OCLog2 editor protocol](../docs/color/oclog2-resolve-protocol.md) for surrounding contracts.

## Delegate these unresolved questions to the next engineer

- **UHD60** · Which exact descriptor filter rejects the tuple, and how much of the repeatable direct path benefit is due to output count, consumer usage or ISP mode? Get the per-consumer duration and actual buffer receipt before changing product gating.
- **Codec and geometry** · Does a minimal explicit HEVC level fix configure for Main8 and Main10 independently? Does an asymmetric synthetic RGB10 input reproduce the repeat-half artifact? A clean bitstream and clean geometry must both pass.
- **Source fidelity** · Can a verified 10-bit source reach the app on this build? The prior P010 rejection and RAW_SENSOR/YUV observations already constrain the next experiment. Test packing and black/white response before a RAW throughput or OCLog2 claim.
- **Product strategy** · Qualify reliable UHD24/25/30 capture and stock-quality SDR grading first. Advance direct UHD60 and 1080p120 as independent modes. Treat Main10, regular OCLog2 and the regional stock path as separate gates.

This branch is a research handoff based on the original lab branch. Evidence and thresholds apply to the stated graph. New phone testing and production code changes remain outside the completed investigation.
