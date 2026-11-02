# Pocket Financer — Android

[![Build Status](https://img.shields.io/github/actions/workflow/status/ManishAradwad/pocket-financer-android/ci.yml?branch=main&style=flat-square&logo=github)](https://github.com/ManishAradwad/pocket-financer-android/actions)
[![Download APK](https://img.shields.io/github/v/release/ManishAradwad/pocket-financer-android?style=flat-square&label=Download%20APK&logo=android&color=green)](https://github.com/ManishAradwad/pocket-financer-android/releases/latest)
[![Platform](https://img.shields.io/badge/Platform-Android_8.0%2B_64--bit-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com/)
[![Language](https://img.shields.io/badge/Language-Kotlin_100%25-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Database](https://img.shields.io/badge/Database-SQLCipher_Room_AES--256-005C8A?style=flat-square&logo=sqlite&logoColor=white)](https://www.zetetic.net/sqlcipher/)

Pocket Financer is a secure, **privacy-first, on-device financial tracking and analytics application** designed specifically for Indian bank and card SMS notifications. It reads transactional alerts (such as bank debits, credit card swipes, UPI transfers, and deposits) and populates a local dashboard through manual scans or optional automatic processing.

The downloadable APK supports 64-bit ARM Android devices running Android 8.0
or newer. The x86_64 build included in the universal APK supports emulators;
32-bit-only devices are not supported.

By leveraging a local **Small Language Model (SLM)** backed by `llama.cpp` via a native JNI bridge, Pocket Financer runs its entire natural language extraction process 100% offline. **No data ever leaves your device. No cloud servers, no marketing trackers, no external APIs.**

---

## 🌟 Key Features

*   **Offline SLM Inference**: Processes SMS message semantics entirely locally using GGUF-based local LLMs.
*   **Advisory Deterministic Analysis**: Local rules provide cues and candidate spans to the model and reviewer. They are not the semantic answer or an allowlist.
*   **Direct Grounded Extraction**: One local, non-thinking SLM pass decides `none`, `abstain`, or one posted transaction with exact source spans. Strict host code validates Unicode scalars, exact money, accounts, duplicates, and routing.
*   **Live Processing Transparency**: The local monitor can show decoded token deltas and cumulative structured output while keeping analyzer evidence, raw output, validation, routing, and persistence distinct.
*   **Dynamic Hardware Auto-Tuning**: Smart hardware profiling detects device RAM capacities and CPU architectures (specifically checking for `ARMv8.2-A` instruction features like `i8mm` and `dotprod` to accelerate integer math) to select the optimal model size automatically.
*   **Disk-Based KV Cache Caching**: Saves and loads the static prefix KV cache state to/from disk using SHA-256 hashes. This cuts prefill time from ~140 seconds down to `< 100ms` on subsequent runs while automatically cleaning up old stale session files.
*   **Cryptographically Secured Database**: Persists transaction and account information in a Room database encrypted with **SQLCipher (AES-256)**, protecting the local ledger at rest.
*   **Durable Real-time & Historical Ingestion**: Automatic processing is off until the user opts in. When enabled, an Android `BroadcastReceiver` admits raw evidence to an encrypted Room outbox, then gives WorkManager only an opaque candidate key. While the app is open, Home immediately shows each claimed automatic SMS as a separate inspect-only processing card; its process-only details are cleared when the claim ends. Inbox discovery remains available manually, preserves provider IDs, and widens from 7 to 30 to 90 days only when no new eligible candidates are found.
*   **Fast, Resumable First Run**: The pre-shell flow contains one calm introduction and the required SMS permission. Model preparation (about 700 MB) starts only after explicit confirmation, while durable setup/import status, verified coverage, counts, and actionable failures remain visible on Home across recreation or process restart.
*   **Private, Evidence-Backed Ledger**: Saved transactions retain their source SMS sender and body in encrypted local storage. Settings provides a confirmed erase-all-local-financial-data action while preserving downloaded model files.
*   **Modern Jetpack Compose UI**: Designed around Material 3 dark-themed specs to present clean dashboards, transaction histories, system hardware capabilities, and engine diagnostics.
*   **Native Edge-to-Edge System Bars**: Integrated transparent Android system bars padding and custom color styling (`SystemBarStyle.dark`) to ensure that time, wifi, and battery icons remain white and visible on dark background themes across all Light/Dark global OS themes.

---

## ⚙️ How It Works (Dataflow Pipeline)

```mermaid
flowchart LR
    A["SMS + receipt/config"] --> B["Encrypted durable operation"]
    B --> C["Advisory deterministic analysis"]
    C --> D["One local SLM classification/extraction"]
    D --> E["Strict parse + Unicode-scalar grounding"]
    E --> F["Exact money + account + duplicate checks"]
    F --> G["Versioned routing"]
    G -->|"current v4 review-only"| H["Review"]
    G -.->|"planned successor: complete valid"| I["Transactions"]
```

---

## 🏗️ Project Architecture

Pocket Financer is structured into specialized, decoupled Gradle modules to ensure clear separation of concerns, fast builds, and high testability:

```
pocket-financer-android/
├── :app          # Jetpack Compose UI (Material 3 Dark Theme) + Hilt Dependency Injection Root
├── :pipeline     # Pipeline Coordinator (orchestrates SMS parsing flows, Prompt building, & DB persistence)
├── :inference    # llama.cpp JNI Engine, NDK compiled Native C++ library, HuggingFace model downloader
├── :data         # Encrypted Database (Room + SQLCipher integration, DAOs, Entities, and Repositories)
├── :sms          # Real-time BroadcastReceiver + Inbox ContentProvider Scraper
└── :hardware     # Device Capability Profiler (validates RAM limits, CPU Neon, i8mm, and dotprod flags)
```

| Module | Core Responsibility | Key Stack / Components |
| :--- | :--- | :--- |
| **[`:app`](app)** | Main User Interface & Settings | Jetpack Compose, Navigation Compose, Hilt, Material 3 |
| **[`:pipeline`](pipeline)** | SMS Processing & Parsing Flow orchestration | Kotlin Coroutines, Hilt, JSON Serialization |
| **[`:inference`](inference)** | Model runner & Assets manager | llama.cpp Native C++, Android NDK, CMake, JNI Bridge |
| **[`:data`](data)** | Cryptographic Persistence | Room DB, SQLCipher, SQLite, AES-256 |
| **[`:sms`](sms)** | Message capture and monitoring | Telephony API, ContentProvider, Kotlin Flows |
| **[`:hardware`](hardware)** | CPU features profiling & model tuning | System OS API, Android NDK cpufeatures |

---

## 🧠 Shared direct-extractor pipeline

1. **Preserve evidence and configuration:** create a durable operation with the
   unchanged SMS, receipt time, release, currency, and real model-file identity.
2. **Collect advisory evidence:** deterministic analysis records useful cues and
   spans but does not decide the semantic result.
3. **Run one direct SLM extraction:** the model returns only `none`, `abstain`,
   or one posted event with amount, direction, account, optional counterparty, and
   exact Unicode-scalar spans.
4. **Validate deterministically:** the host rejects malformed JSON, bad spans,
   inexact money, unresolved accounts, duplicate conflicts, and incompatible
   provenance.
5. **Route by version:** frozen v4 retains every posted result for review. A
   successor contract will send complete valid results to Transactions and reserve
   Review for exceptions.

---

## ⚡ Performance Optimizations & Caching

To make local inference responsive and preserve battery life, Pocket Financer implements high-performance CPU optimizations, disk-based KV Cache caching, and automatic cleanup:
*   **Arm KleidiAI Acceleration**: Integrates Arm's official KleidiAI micro-kernels dynamically for `arm64-v8a` targets. This leverages hardware-specific `dotprod` (Dot Product), `i8mm` (Int8 Matrix Multiplication), and `sme` (Scalable Matrix Extension) instructions on supported ARMv8 and ARMv9 CPUs to dramatically accelerate quantized matrix multiplication.
*   **Dynamic KV Cache Precision (F16 vs. Q8_0)**: Automatically selects KV cache precision based on hardware capabilities. On modern CPUs supporting native FP16 calculations, it uses `F16` to leverage hardware vector math and CPU Flash Attention. On older CPUs lacking native FP16 hardware (e.g., Galaxy A50), it falls back to `Q8_0` to bypass slow software FP16 emulation overhead.
*   **Auto-Thread Tuning**: Rather than hardcoded limits, the engine configures thread counts dynamically by passing `0` to let `llama.cpp`'s native C++ scheduler determine the thread count. This defaults to `std::min(cores, 4)` (optimizing octa-core CPUs with 4 threads, which fully engages performance cores without thrashing cores or causing thermal build-up).
*   **Static Prefix Caching**: The static prompt prefix (~1,800 tokens of system prompt + few-shot examples) is prefilled once, and the native JNI engine serializes the resulting KV cache to disk as `session_<sha256>.bin` inside the secure app storage.
*   **Instant Load**: On subsequent inference runs, the pre-saved session is loaded from disk in under `100ms`, completely bypassing the heavy prefill phase.
*   **Automatic Cache Invalidation**: The session file name matches the SHA-256 hash of the static prefix. Any modifications to `system_prompt.txt` or `few_shot_examples.json` will automatically trigger a new prefill run on the next execution.
*   **Stale Cache Deletion**: To prevent disk clutter, whenever a new session file is generated, the engine automatically deletes all older stale `session_*.bin` cache files from device storage.

---

## 📱 Dynamic SLM Selection Matrix

To run local inference smoothly without triggering Android's low-memory killer (LMK), the app performs a detailed hardware check on startup and downloads/allocates a model matching the device's capability tier:

| Model ID | Model Family | Quantization | Size | Min. RAM | CPU Requirement | Status / Target |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **`Gemma 4 E2B Q8_0`** | Gemma 4 (E2B) | 8-bit | ~5.00 GB | **8.0 GB** | ARMv8.2-A with `i8mm` + `dotprod` | **Highest Quality** (Default for high-end devices) |
| **`Gemma 4 E2B Q4_K_M`**| Gemma 4 (E2B) | 4-bit (Medium) | ~3.10 GB | **6.0 GB** | ARMv8.2-A with `i8mm` + `dotprod` | **Balanced** (Optimal for mid-to-high devices) |
| **`Qwen3-1.7B Q8_0`** | Qwen 3 (1.7B) | 8-bit | ~1.95 GB | **4.0 GB** | ARMv8.2-A with `i8mm` + `dotprod` | **High Quality Thinking** (Default for mid-range CPU) |
| **`Qwen3-1.7B Q4_K_M`** | Qwen 3 (1.7B) | 4-bit (Medium) | ~1.10 GB | **3.5 GB** | ARMv8.2-A with `i8mm` + `dotprod` | **Balanced Thinking** (Optimal for mid-range) |
| **`Qwen3-0.6B Q8_0`** | Qwen 3 (0.6B) | 8-bit | ~0.70 GB | **2.5 GB** | Standard ARMv8 | **Lightweight Fallback** (For budget devices) |
| **Blocked** | — | — | — | **< 2.5 GB**| — | *Incompatible (Device cannot execute local SLMs)* |

*Note: GPU acceleration is disabled on Android for model selection. CPU instruction execution (using Neon assembly and specialized hardware dot product features) is substantially faster and more power-efficient than mobile GPU JNI roundtrips in llama.cpp.*

---

## 🔒 Hardened Security & Privacy

*   **Financial Data Stays Local**: SMS filtering, model inference, parsing, and database transactions happen on-device. Network access is used to download a model only after explicit confirmation; financial messages are not uploaded.
*   **AES-256 SQLCipher Database**: Room uses SQLCipher with a random key protected by Android Keystore-backed encrypted preferences. Database initialization fails closed if Keystore protection is unavailable, and older plaintext-fallback keys are wrapped and erased during upgrade.
*   **Source Evidence Retention**: A saved transaction retains its original SMS sender and full message body indefinitely in the encrypted local database. Rejected non-transaction messages are not retained long-term.
*   **User-Controlled Erasure**: Settings requires confirmation before erasing all local financial data and setup/import state. There is no retention-period picker.

---

## 🛠️ Getting Started & Build Instructions

### Prerequisites
*   **Android Studio** (Koala / Ladybug or later stable version)
*   **JDK 17** (configured as your Gradle system JDK)
*   **Android SDK 36**
*   **Android NDK 27.3** (specified in your local configurations)

### Local Configuration Setup
Create a file named `local.properties` in the root directory and specify your Android SDK and NDK paths:
```properties
sdk.dir=/path/to/android-sdk
ndk.dir=/path/to/android-sdk/ndk/27.3.13750724
```

### Build Commands
Run these commands from your terminal:

```bash
# 1. Clone the repository
git clone https://github.com/ManishAradwad/pocket-financer-android.git
cd pocket-financer-android

# 2. Run module unit tests (Validates database DAOs, SMS parsers, Prompt Builder logic)
./gradlew :data:test :pipeline:test :hardware:test :sms:test :inference:test -x :inference:build -x :app:build

# 3. Compile and generate debug APK
./gradlew :app:assembleDebug

# Stable release APKs are built, signed, and published only by the protected
# release workflow described in docs/releasing.md.
```

---

## Development and releases

Pocket Financer uses short-lived `codex/*` branches, required pull-request CI,
Conventional Commit titles, and an automated Release Please pull request as the
explicit publication gate. Start with [CONTRIBUTING.md](CONTRIBUTING.md) for
feature development and [docs/releasing.md](docs/releasing.md) for CI/CD,
versioning, signing, publication, and recovery.

---

## 📈 Project Status & Roadmap

The canonical SMS handoff is [docs/sms-processing-next-steps.md](docs/sms-processing-next-steps.md).
It distinguishes implemented v4 source from the planned exception-only review
route and from still-open emulator/physical-device acceptance.

- [x] **Phase 1-4**: Core Native JNI bindings, llama.cpp compilation, & Model Downloader pipeline.
- [x] **Phase 5**: Pipeline service orchestration, validation rules, & SQLCipher secure database persistence.
- [x] **Phase 6**: UI screen development. Dashboard registers (`Transactions` tab with full date-grouped cards, debit/credit filters, and SLM metadata bottom sheets) and Settings debug metrics are active. Dashboard summary (`Home` tab) and analytics graphs (`Insights` tab) are fully implemented and integrated.
- [x] **Phase 7**: Background Worker integration for sleeping SMS parses.

---

## 🧪 Testing & Verification

For testing the application's offline AI ingestion pipeline without using real bank alerts, we provide testing scripts and developer utilities:

### 1. Ingest Mock Transactions
Run the Python script in the workspace root to inject exactly 20 test SMS messages (including both transactional debits/credits and promotional spams) into your emulator's telephony provider:
```powershell
python inject_sms.py
```
By default, the utility reads `DATA/extraction_ds.jsonl` from a sibling
`pocket-financer-intelligence` checkout. For another location, pass
`--dataset /path/to/extraction_ds.jsonl`. It configures shell appops permissions
(`WRITE_SMS`) and restarts the Android Messages application. **It clears the SMS
inbox on connected emulators before injecting messages**; do not run it against
an emulator containing messages you need to keep.

### 2. Replay First Run
To test the onboarding sync visual elements repeatedly:
- Open the application and navigate to the **Settings** tab.
- Open **Data & privacy** and tap **Erase all local financial data**.
- Review the scope and confirm the destructive action.
- The app resets encrypted ledger/outbox tables and setup/import metadata, clears the shell gate, cancels financial notifications, and recreates the activity to launch the short Welcome flow.
- A valid downloaded SLM artifact remains in internal storage. After SMS permission is restored, Home truthfully shows that the local model is ready and waits for an explicit history-scan action; it does not silently start a download or import.

---

## Copyright and Usage

Copyright © 2026 Manish Aradwad. All rights reserved.

This repository is **not open source at this time**. No license is granted to
use, modify, or redistribute its code or assets. The licensing model may be
reconsidered in the future.

---

## 🤝 Acknowledgments

*   [llama.cpp](https://github.com/ggerganov/llama.cpp) — Core engine powering local, on-device SLM execution.
*   [Pocket Financer Intelligence](https://github.com/pocket-financer/pocket-financer-intelligence) — shared SMS contracts, evaluation, and model research.
