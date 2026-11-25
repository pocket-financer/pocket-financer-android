# Pocket Financer — Android

[![CI](https://img.shields.io/github/actions/workflow/status/pocket-financer/pocket-financer-android/ci.yml?branch=main&style=flat-square&logo=github)](https://github.com/pocket-financer/pocket-financer-android/actions)
[![Latest Release](https://img.shields.io/github/v/release/pocket-financer/pocket-financer-android?style=flat-square&label=Download%20APK&logo=android&color=green)](https://github.com/pocket-financer/pocket-financer-android/releases/latest)
[![Platform](https://img.shields.io/badge/Platform-Android_8.0%2B_64--bit-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com/)
[![Language](https://img.shields.io/badge/Language-Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Database](https://img.shields.io/badge/Database-SQLCipher_Room_AES--256-005C8A?style=flat-square&logo=sqlite&logoColor=white)](https://www.zetetic.net/sqlcipher/)

Pocket Financer is a **privacy-first, on-device financial tracker** for Android
that turns transactional bank, card, and UPI SMS alerts into an encrypted local
ledger and spending insights dashboard—with **zero cloud inference, zero
analytics trackers, and zero financial data leaving the device**.

The published APK targets **64-bit Android 8.0+ (API 26+)** (`arm64-v8a` for
physical devices and `x86_64` for emulators). 32-bit-only devices are not
supported.

## Ecosystem Repositories

- **[pocket-financer-android](https://github.com/pocket-financer/pocket-financer-android)** —
  Android app (Kotlin, Jetpack Compose, Room + SQLCipher, `llama.cpp` JNI).
- **[pocket-financer-intelligence](https://github.com/pocket-financer/pocket-financer-intelligence)** —
  Canonical SMS processing contracts (`pocketfinancer.sms-pipeline/1`), golden
  prefilter vectors, prompt/grammar artifacts, and model evaluation.
- **[pocket-financer-ios](https://github.com/pocket-financer/pocket-financer-ios)** —
  Sibling iOS client sharing the same SMS processing contracts.

## Key Capabilities

- **Consolidated On-Device SMS Pipeline (`pocketfinancer.sms-pipeline/1`)**:
  Deterministic `SmsPrefilter` (`sms-prefilter/1`) drops obvious
  non-transactional alerts (OTPs, promos, collect/mandate requests, telecom,
  brokerage summaries) pre-SLM as body-free `tombstoned_excluded` records.
  Eligible alerts run through advisory structural analysis and a single direct,
  non-thinking local SLM pass (`NonThinkingOutputGuard`), followed by strict
  Unicode-scalar grounding, exact minor-unit currency normalization, account
  resolution, and a 5-minute duplicate check (`300_000` ms).
- **Automatic Persistence & Grounded Review**: Complete, clear posted
  transactions persist atomically to `Transactions`; valid `none` alerts settle
  as body-free `tombstoned_none`; ambiguous, unresolved, duplicate, or invalid
  extractions enter `Review` with immutable source text, accessible highlights,
  and revision-bound local feedback.
- **Encrypted Local Storage**: Room (v8) backed by **SQLCipher (AES-256)** with
  an Android Keystore-wrapped database key that fails closed if Keystore is
  unavailable, `android:allowBackup="false"`, and a confirmed full-erase control
  in Settings (preserving downloaded GGUF models).
- **Opt-In Background Intake & Manual Sync**: Automatic SMS processing is off by
  default. When enabled, incoming broadcasts are admitted to an encrypted outbox
  and scheduled in WorkManager using only opaque candidate keys. Manual recent
  and historical inbox scans remain available from Home.
- **Hardware-Tuned Local Inference**: `llama.cpp` JNI with Arm KleidiAI
  acceleration (`arm64-v8a`), dynamic KV cache precision (`F16` on FP16-capable
  CPUs, `Q8_0` fallback), auto thread sizing, and SHA-256-keyed static prefix
  KV caching on disk.

## Module Architecture

```text
pocket-financer-android/
├── :app        Jetpack Compose Material 3 UI, navigation, Home/Transactions/Insights/Review/Settings
├── :pipeline   Consolidated SMS coordinator, SmsPrefilter, StructuralSmsAnalyzer, validator, WorkManager
├── :inference  Process-wide SlmRuntimeCoordinator, llama.cpp C++/JNI engine, model downloader
├── :data       SQLCipher Room v8 database, migrations (v1–v8), DAOs, repositories, Review store
├── :sms        SMS BroadcastReceiver and Android Telephony inbox reader
└── :hardware   RAM/CPU/storage capability profiler and SLM tier selector
```

See [docs/architecture.md](docs/architecture.md) for the full pipeline dataflow,
contract verification, and concurrency/privacy invariants.

## Dynamic SLM Selection Matrix

Every tier executes the same **direct non-thinking JSON extraction** path
enforced by `NonThinkingOutputGuard`. Initial onboarding prepares the compact
starter model (`Qwen3-0.6B Q8_0`, ~0.7 GB) after explicit confirmation; devices
with higher RAM and ARMv8.2-A `i8mm` + `dotprod` CPU support can upgrade to a
larger tier from Home or Settings.

| Tier ID | Model | Quant | Approx. Size | Min RAM | CPU Requirement | Role |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `gemma4-e2b-q8_0` | Gemma 4 E2B | `Q8_0` | ~5.0 GB | 8.0 GB | ARMv8.2-A (`i8mm` + `dotprod`) | Highest quality |
| `gemma4-e2b-q4_k_m` | Gemma 4 E2B | `Q4_K_M` | ~3.1 GB | 6.0 GB | ARMv8.2-A (`i8mm` + `dotprod`) | Balanced mid-high |
| `qwen3-1.7b-q8_0` | Qwen3-1.7B | `Q8_0` | ~1.9 GB | 4.0 GB | ARMv8.2-A (`i8mm` + `dotprod`) | High-quality mid-range |
| `qwen3-1.7b-q4_k_m` | Qwen3-1.7B | `Q4_K_M` | ~1.1 GB | 3.5 GB | ARMv8.2-A (`i8mm` + `dotprod`) | Balanced mid-range |
| `qwen3-0.6b-q8_0` | Qwen3-0.6B | `Q8_0` | ~0.7 GB | 2.5 GB | Standard 64-bit ARMv8 / x86_64 | Default onboarding & fallback |
| Blocked | — | — | — | < 2.5 GB | — | Unsupported for local SLM |

GPU offloading is intentionally disabled on Android because CPU NEON / KleidiAI
micro-kernels outperform mobile Vulkan/OpenCL JNI roundtrips for these models.

## Getting Started

### Prerequisites

- **JDK 17**
- **Android SDK 36** (`platforms;android-36`, `build-tools;36.0.0`, `platform-tools`)
- **Android NDK `27.3.13750724`** and **CMake `3.22.1`**
- **Android Studio** (recent stable release) or command-line SDK tools

Create a local `local.properties` (git-ignored):

```properties
sdk.dir=/path/to/android-sdk
ndk.dir=/path/to/android-sdk/ndk/27.3.13750724
```

### Build & Test Commands

```bash
# Clone
git clone https://github.com/pocket-financer/pocket-financer-android.git
cd pocket-financer-android

# Run all unit tests across all 6 modules
./gradlew testDebugUnitTest --no-daemon --stacktrace

# Verify instrumented tests compile and package cleanly
./gradlew compileDebugAndroidTestKotlin assembleDebugAndroidTest --no-daemon --stacktrace

# Run Android lint
./gradlew lintDebug --no-daemon --stacktrace

# Build the debug APK (com.pocketfinancer.debug)
./gradlew :app:assembleDebug --no-daemon --stacktrace
```

On Windows PowerShell, substitute `.\gradlew.bat`. Stable signed release APKs
are built and published exclusively through `.github/workflows/release.yml` (see
[docs/releasing.md](docs/releasing.md)).

## Documentation

- [AGENTS.md](AGENTS.md) — engineering rules, invariants, and branch/release discipline
- [CONTRIBUTING.md](CONTRIBUTING.md) — branch workflow, Conventional Commits, and PR expectations
- [docs/architecture.md](docs/architecture.md) — modules, SMS pipeline, runtime, and security architecture
- [docs/design-system.md](docs/design-system.md) — Material 3 typography tokens and spacing grid
- [docs/testing-and-emulator.md](docs/testing-and-emulator.md) — unit/instrumented testing, `adb`, and synthetic SMS injection
- [docs/releasing.md](docs/releasing.md) — CI/CD controls, Release Please, signing, verification, and recovery

## Copyright and Usage

Copyright © 2026 Manish Aradwad. All rights reserved.

This repository is **not open source at this time**. No license is granted to
use, modify, or redistribute its code or assets.

## Acknowledgments

- [llama.cpp](https://github.com/ggml-org/llama.cpp) — core local inference engine.
- [Pocket Financer Intelligence](https://github.com/pocket-financer/pocket-financer-intelligence) — shared SMS contracts, golden vectors, and model evaluation.
