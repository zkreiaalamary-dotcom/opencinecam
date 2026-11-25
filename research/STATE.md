# OpenCineCam research state

2026-11-25 · Huawei Pura 80 Pro global LMR-LX9 · EMUI 15 · Android API 31.

[Handoff](HANDOFF.md) · [Analysis](ANALYSIS.md) · [Roadmap](ROADMAP.md) · [Evidence index](EVIDENCE.jsonl)

- Branch base `adc19ce1a8bc141885160b00c8bea83cca948787` from `research/self-exploring-lab`. The [existing handoff](../docs/research/agent-handoff.md) identifies `f02a4813d7157fb550437243c64c1818dbf0efb4` as the last built code. Two documentation files are the only delta to the reviewed base. The archive supplies model/SDK/fingerprint and run metadata. APK build identity is absent.
- Current evidence is complete for the supplied run: 12 attempts, eight valid MP4s (all AVC High 8-bit), two zero-byte placeholders, two preview-only attempts. Eight clip hashes and sample timing match Android extraction. A separate software decode sampled 55 frames. Lab summary closed on the attempt budget.
- UHD60 `007/008`: the descriptor discarded the unlisted UHD60 tuple, then built a 1080p source. The recorder correctly rejected 4K geometry. Prior Pura recorder-only HEVC Main reached 59.920 fps in a short UHD file. Sustained operation in OpenCineCam is pending.
- HEVC `012`: HiSilicon refused one exact configure request. Prior Pura HEVC Main files narrow recovery to the route and codec contract. Main10 was previously coded successfully, with near-repeat-half geometry in three independently audited originals.
- 1080p60 `002`: one 30.247 ms sensor-origin interval, no duplicate PTS. HFR OCLog2 `011`: generic codec prep failure after camera results. Regular HLG-derived OCLog2 is gated by this API level and app policy.
- Current positive OpenCineCam control is short UHD AVC at 24/25/30. The longest take is 10.036 s at UHD24. No new physical handset runs were made in this audit.

## Implementation and next decision

The branch `research/pura80-capture-probes` implements the short `X-01` direct HEVC pair, `X-00` source/encoder receipts, the UHD30 Main explicit-level arm of `X-02`, and an RGB8 repeated-half screen for `X-03`. Host tests exercise scheduling, cadence, format parity and the geometry screen. Hardware results for these changes are pending. Start one session on the global handset and follow [CAPTURE-PROBES.md](CAPTURE-PROBES.md). Reserve sustained recording, synthetic Main10 rendering, source precision and Log qualification for the subsequent evidence gates.
