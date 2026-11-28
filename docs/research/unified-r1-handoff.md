# OpenCineCam Unified R1

## Source and Windows build fix

- Corrected Astra LOG R1 source: `22e32e57b2da1dff483164a494552acbb5fa2fcc` from `research/astra-log-r1`.
- Wave D Milestone 3 Batch 2: `b8b919623c91f6ae6d5f4940a4b55c48ef224572` from `research/autonomous-log-lab`. It and the other inspected GitHub branch tips were already ancestors of the corrected Astra revision. The prior local checkout was at `adc19ce1a8bc141885160b00c8bea83cca948787` with a clean tracked tree, also an ancestor. The integration branch starts at the corrected Astra commit.
- APK source revision: `a121e41b8821cd9025f6f73c580c5e8c619a19dc`. It adds the SHA-256 for the Windows AAPT2 JAR after a byte-for-byte digest match between Google Maven and the local Gradle cache. The `unified-r1` tag includes this handoff document.

## APKs and verification

Both APKs were built with `:app:assembleDebug` on Windows using JDK 17, Gradle 9.5.0, Android SDK platform 37.0 and strict Gradle dependency verification. The builds used `opencinecam.researchLab=false` for the main app and `true` for the Lab, and ran the license catalog check through the installed Python 3 interpreter using a local Gradle init script. Gradle packaged some native libraries with their debug symbols.

| File in Downloads | Android package | SHA-256 |
| --- | --- | --- |
| `OpenCineCam-Unified-R1.apk` | `com.librestatic.opencinecam.debug` | `cb2671e5d91c45d6fb4240683291230d6510a3c41d0169f4bd18cb3493a1ab7c` |
| `OpenCineCam-Unified-R1-ResearchLab.apk` | `com.librestatic.opencinecam.researchlab` | `a9bbd3fe57d3fb0ae24d90a58e01bad474ace1ec1e2cd3c28b7e81c5341cbc0b` |

Both passed APK Signature Scheme v2 verification and ZIP alignment checks. Manifest checks confirmed distinct package IDs, minimum API 29 and `arm64-v8a` support. The SHA-256 of each copy in Downloads matched its build artifact.

All 127 targeted host tests passed.

- 55 camera tests covering the Astra shader, OCLog, HLG and the direct 10-bit recording policy.
- 41 app tests covering P010 luma, LOG/HLG mode claims, clip sidecars and video color metadata.
- 19 camera tests covering Wave D scope analysis and compatibility with its generated LUTs.
- 7 Astra JavaScript and 5 Wave D Python reference tests covering color transforms, LOG curves, LUTs and the floating-point conversion path.

## Field verification pending

Install and launch on the Huawei Pura 80 Pro LMR-LX9. Compare 4K30 and 4K60 live OCLog2 against direct 10-bit HLG, starting on the main camera. Check orientation, highlight separation, chroma, unique-frame cadence and A/V sync, then repeat on the supported secondary lenses. The host tests do not exercise the Huawei driver or compile the live shader on the phone. Use the existing capture procedure for the comparison. [Astra LOG R1 field procedure](astra-log-r1-handoff.md)
