# Research roadmap

Updated after integrating `نتايج\جديد` (`Run A a26cd8da`, `Run B 922a793c`) and implementing the unlimited-session Research Lab + 4K60/10-bit/ISP/CUVA upgrades (`research/autonomous-log-lab`). See [docs/research/engineering-roadmap.md](../docs/research/engineering-roadmap.md) and [docs/research/next-experiments.md](../docs/research/next-experiments.md).

| Priority | Track / Experiment | Decision / Unlock | Current Status |
|---:|---|---|---|
| **1** | **Unlimited-Session Safe Lab + KB Choice/Contradiction Fixes** | Permanently removed `TIME_SLICE_LIMIT_MS = 300_000L` (`LabSessionBudget` `36+18=54`, `checkpoint.json`); fixed `bestOverrideFor` (`oclog2_full`), `029-file.json` (`mime`), & `H-04` repeated-attempt contradiction | **Implemented & Host-Verified** |
| **2** | **`4K30` & `4K60 OCLog2 HEVC Main 10` Zero-Drop Ladder** | `4K30 OCLog2 Main 10` proven (`R-B 003/004`: `29.990 fps`, `0` drops); `4K60` sensor `59.94 fps` (`0` drops) stalled on GL (`32.289 ms`) -> automated `realtime -> single_ctx -> prev_30fps -> no_preview -> bitrate_45m` ladder | **`4K30` PROVEN; `4K60` Ladder Ready for Device** |
| **3** | **True >8-bit Source/Encoder Precision (`EGL10` HLG/PQ/FP16/Dither + `ByteBuffer`)** | Tests `EGL10` HLG/PQ (`0x3540`/`0x3340`), `FP16`, ordered dither + `egl8`, & HiSilicon `ByteBuffer` `0x7F00000F`/`0x7F000011` (`Main10 L6.2 + egl8` already passed) | **Implemented; Awaiting `LMR-LX9` Run** |
| **4** | **Kirin Post-Gamma Gamma Sweep & Safe Shoulder Curve** | `GAMMA=2.2` holds `0%` clip vs `75.2%` (`GAMMA_1_0`) / `74.8%` (`SAFE_LINEAR_ROLLOFF`); sweep `2.2..1.0` + `kirinGammaAttenuatedRolloffCurve` + `gamma_1_8` `LOG_REGULAR` | **Implemented; Awaiting `LMR-LX9` Run** |
| **5** | **`ISP_CUVA_HDR_PROBE` (`cuvaStateVideoMode`) & Clean `SOURCE_FORMAT_PROBE`** | Unblinded `024` confirmed `PRIVATE` (`0,2,4,5`) & `cuvaStateVideoMode`; live `cuvaStateVideoMode = 0/1/2` admission & `HardwareBuffer` probe ready | **Implemented; Awaiting `LMR-LX9` Run** |
