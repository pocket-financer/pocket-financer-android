# Pocket Financer Android — Repository Instructions

This file is the single source of truth for engineers and coding agents working
in `pocket-financer-android`. Always verify documentation against the current
code before relying on it.

Supporting guides:

- [docs/architecture.md](docs/architecture.md) — module boundaries, consolidated
  SMS pipeline (`pocketfinancer.sms-pipeline/1`), runtime/model identity, and
  encryption/privacy invariants.
- [docs/design-system.md](docs/design-system.md) — Material 3 typography tokens,
  `AppTypography` aliases, and spacing grid.
- [docs/testing-and-emulator.md](docs/testing-and-emulator.md) — Gradle unit,
  instrumentation, lint, build, and emulator verification procedures.
- [docs/releasing.md](docs/releasing.md) — CI/CD controls, Release Please,
  signing, post-release verification, hotfixes, and recovery.

## Module Responsibilities

```text
:app        Jetpack Compose UI (Material 3 dark theme), navigation, Review, telemetry
:pipeline   SMS orchestration (pocketfinancer.sms-pipeline/1), prefilter, validation, routing
:inference  Process-wide llama.cpp/JNI runtime, NonThinkingOutputGuard, model downloads
:data       SQLCipher Room v8 database, migrations, repositories, review/feedback store
:sms        BroadcastReceiver intake and Android Telephony inbox reader
:hardware   Device capability profiling and file-backed SLM tier selection
```

## Core Architecture & Privacy Invariants

1. **Consolidated SMS pipeline (`pocketfinancer.sms-pipeline/1`)**:
   - Unambiguously non-transactional alerts are excluded pre-SLM by
     `SmsPrefilter` (`sms-prefilter/1`) and settled as body-free
     `tombstoned_excluded` records.
   - Otherwise, a single local, non-thinking SLM pass (`DefaultDirectCandidateSelector`
     guarded by `NonThinkingOutputGuard`) classifies `none`, `abstain`, or one
     posted transaction with Unicode-scalar source spans.
   - Deterministic `StructuralSmsAnalyzer` output is advisory evidence only—never
     a terminal semantic answer or allowlist.
   - Host code owns strict JSON parsing, Unicode-scalar grounding, exact
     minor-unit money normalization, account resolution, the 5-minute duplicate
     window (`300_000` ms), immutable receipt timestamps, atomic persistence to
     `Transactions`, body-free `tombstoned_none` settlement, and `Review` routing.
   - Contract assets live in `pipeline/src/main/assets/sms_processing/` (pinned
     to LF via `.gitattributes` and SHA-256 verified at runtime by
     `SmsContractAssets`) and are synchronized from
     `pocket-financer-intelligence`. Never mutate contract bytes or weaken hash
     checks locally.
2. **Process-wide SLM runtime boundary**:
   - Production inference callers must go through `SlmRuntime` /
     `SlmRuntimeCoordinator`; never invoke `LlamaEngine` directly from feature
     code.
   - Android models are file-backed: eligible operations record the real
     lowercase 64-character SHA-256 of the GGUF file bytes. Never substitute a
     placeholder or a hash of a path/identifier.
3. **Privacy, encryption, and opt-in intake**:
   - Automatic SMS intake is off by default until explicitly enabled.
   - WorkManager `Data` carries only opaque candidate keys—never raw SMS bodies,
     senders, or financial fields.
   - Financial evidence remains encrypted in SQLCipher Room (`allowBackup="false"`,
     Keystore-backed key initialization fails closed). Never log private SMS
     text, model output, or secrets.
   - All tests and developer fixtures must be 100% synthetic (zero real SMS,
     phone numbers, account numbers, or personal file paths).
4. **Review and live processing transparency**:
   - Review displays the complete immutable SMS with accessible field highlights
     and one active native selection. Confirmation is atomic. Corrections append
     local, revision-bound feedback and never silently become training labels.
   - Live processing displays observable cumulative structured output and keeps
     advisory analysis, model output, validation, routing, persistence, and user
     corrections distinct. Never invent or display chain of thought.

## Holistic Change Discipline

For every feature or bug fix, trace behavior end to end before editing:

- Inspect every affected production entry point and state owner: Compose UI and
  ViewModels, persisted preferences, foreground/background services and
  WorkManager jobs, inference/JNI code, Room DAOs/migrations/repositories, and
  restart/recovery paths.
- Resolve all material states and transitions explicitly: defaults and upgrades,
  idle, queued, in-flight, concurrent or repeated actions, cancellation, failure
  and retry, process death/restart, and stored-row compatibility.
- Define an explicit consistency boundary for every runtime setting (safe
  immediate application, per-operation immutable snapshot, or disabled while
  busy). Never allow one operation to execute partly with an old setting value
  and partly with a new one.
- Add or update tests at the lowest practical layer and run affected module
  tests plus the full applicable verification gates before opening a PR.

## Build & Verification Gates

Run from the repository root using the Gradle wrapper (`./gradlew.bat` on
Windows PowerShell, `./gradlew` on Linux/macOS):

```powershell
# All unit tests across all 6 modules
.\gradlew.bat testDebugUnitTest --no-daemon --stacktrace

# Instrumented test compilation & APK packaging (prevents androidTest bit-rot)
.\gradlew.bat compileDebugAndroidTestKotlin assembleDebugAndroidTest --no-daemon --stacktrace

# Android lint
.\gradlew.bat lintDebug --no-daemon --stacktrace

# Full debug APK
.\gradlew.bat :app:assembleDebug --no-daemon --stacktrace
```

Keep evidence categories strictly separate: JVM unit-test results are not
emulator results, and emulator results are not physical-device evidence.

## Version Control & Release Discipline

All changes must land via short-lived feature branches and pull requests
targeting `main`:

1. Update from `origin/main`.
2. Create a branch named `<type>/<short-description>` (for example,
   `feat/budget-alerts`, `fix/duplicate-window`, `chore/overhaul-tests-docs-ci`).
3. Keep commits focused, verify locally, push the branch, and open a pull
   request targeting `main`.
4. Use a Conventional Commit PR title (`feat:`, `fix:`, `feat!:`, `docs:`,
   `test:`, `refactor:`, `build:`, `ci:`, `chore:`). The PR title becomes the
   squash-merge commit message on `main`.
5. Never commit or push directly to `main`, and never bypass required CI checks
   (`Build, lint, and unit tests`).

### Release Please Ownership

Release Please owns `version.txt`, `CHANGELOG.md`, `v*` tags, and stable GitHub
Releases:

- **Never** manually edit `version.txt` or `CHANGELOG.md` in a normal PR, create
  or move a `v*` tag, or upload a stable APK outside `.github/workflows/release.yml`.
- Merges to `main` update one automated Release Please PR. A stable release is
  published only when the repository owner explicitly authorizes merging that
  automated release PR.
- Before changing CI/CD automation, verify `docs/releasing.md` against
  `.github/workflows/ci.yml`, `.github/workflows/release.yml`,
  `.github/dependabot.yml`, `release-please-config.json`, and live GitHub
  repository settings.
