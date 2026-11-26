# Research roadmap

Updated after integrating `7d0db6a5` (`49` attempts under Power Saving Mode ON) & `f8d3e998` (`32°C MODERATE` halt) and overhauling power-mode provenance, `40°C / 35°C` thermal auto-resume, cap-free exploration, and persistent checkpoints (`research/autonomous-log-lab`). See [docs/research/engineering-roadmap.md](../docs/research/engineering-roadmap.md) and [docs/research/next-experiments.md](../docs/research/next-experiments.md).

| Priority | Track / Experiment | Decision / Unlock | Current Status |
|---:|---|---|---|
| **1** | **Power-Clean Re-Run (Power Saving OFF + Performance ON)** | Eliminates the `Power Saving ON` confound from `7d0db6a5` (`48 fps` metronome on `4K60 GPU/LOG`); verifies `H-11` (`LOG_REGULAR_4K60 previewOnly=true`) | **Implemented (`LabPowerProfile` + `H-11`); Awaiting `LMR-LX9` Run** |
| **2** | **`40°C / 35°C` Auto-Resuming Thermal + Cap-Free `LabExplorationGuard` + `LabCheckpointState`** | Fixes `f8d3e998` (`32°C` pause) and `7d0db6a5` (`36/36` stop & Restart-from-0 bug); preserves all runs across restarts/crashes/upgrades | **Implemented & Host-Verified (`54` app tests)** |
| **3** | **True >8-bit HiSilicon `ByteBuffer` (`0x7F00000F`/`0x7F000011`) (`H-12`)** | Both vendor formats admitted in `7d0db6a5-001`; encodes 15 ramp frames to test `rampEffectiveBits >= 9` | **Implemented (`LabSyntheticEncoderProbe`); Awaiting `LMR-LX9`** |
| **4** | **Fixed `ByteArray` `[B` `ISP_CUVA_HDR_PROBE` (`0/1/2` + Companion `PRIVATE`)** | Fixes `Not an array: class java.lang.Byte` from `7d0db6a5-022`; checks `HardwareBuffer`/luma changes | **Implemented (`LabSourceProbe`); Awaiting `LMR-LX9`** |
| **5** | **`GAMMA_2_0` / `GAMMA_2_2` 4K30 LOG + `CONTRAST_CURVE_MINUS_1EV` (`Stage B/E`)** | Capitalizes on `7d0db6a5-020` (`GAMMA_2_0`: `0.056%` clip, `spread=221`; `GAMMA_2_2`: `0%` clip) for unclipped 4K30/4K60 LOG | **Implemented; Awaiting `LMR-LX9` Run** |
