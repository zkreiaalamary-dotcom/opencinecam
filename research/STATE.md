# OpenCineCam research state

2026-11-25 · Huawei Pura 80 Pro global LMR-LX9 · EMUI 15 · Android API 31.

## Baseline

- Source branch `research/self-exploring-lab` at `adc19ce1a8bc141885160b00c8bea83cca948787`. Its [existing handoff](../docs/research/agent-handoff.md) identifies `f02a4813d7157fb550437243c64c1818dbf0efb4` as the last built code. The source delta between those revisions contains two research documents. APK identity is absent from the run journal.
- [EVIDENCE.jsonl](EVIDENCE.jsonl) is the digest-addressed index. The export contains 12 completed attempt records, eight valid AVC MP4s, two zero-byte placeholders and two preview-only attempts. Clip bytes, capability report and previous Pura Cinema evidence stay in their original locations.
- `E-OCC-002` localizes the 1080p60 interval anomaly to Camera2 sensor timestamps. `E-OCC-007/008` reached camera callbacks after the engine dropped the unlisted UHD60 profile. `E-OCC-012` proves a specific HiSilicon HEVC configure refusal and successful AVC fallback.
- Earlier Pura Cinema data changes the priority: `E-PURA-5799500` reports 59.920 fps in short recorder-only UHD HEVC Main; `E-PURA-5698009` reports UHD Main10 with a suspected half-repeat. `E-PURA-GEOM` records repeat-like geometry across independently decoded originals.

## Next

Finish the code-level analysis and rank two first tests: carry the known-good recorder-only control into the lab, then isolate Main10 surface/format preparation and image geometry. Treat regular OCLog2 availability, source precision and sustained operation as independent questions.
