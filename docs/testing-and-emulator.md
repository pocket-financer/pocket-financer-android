# Testing & Emulator Guide

All automated tests, scripts, and manual fixtures in Pocket Financer Android
must be **100% synthetic**. Never commit or log real SMS bodies, phone numbers,
bank account identifiers, or personal filesystem paths.

## Evidence Discipline

Keep verification claims strictly separated:

- **JVM unit tests** verify pure logic, Robolectric/Room schemas, migrations,
  contracts, coordinators, and ViewModels. They do not prove Android JNI/SQLCipher
  or UI behavior.
- **Emulator instrumentation / manual runs** verify Android runtime integration,
  SQLCipher reopen, Compose semantics, and WorkManager behavior on an AVD. They
  are **not** physical-device evidence.
- **Physical-device runs** are required for final hardware performance, thermal,
  battery, memory/LMK, and end-to-end model quality validation.

## Test Suite Layout

| Module | Unit Tests (`src/test`) | Instrumented Tests (`src/androidTest`) |
| :--- | :--- | :--- |
| `:app` | ViewModels (`Onboarding`, `Review`, `Transactions`, `Insights`, `Settings`), Home/Setup card presentation, manual sync state & privacy scrubbing, model upgrade policy, local erase boundary & recovery, flow coordination | Compose evidence selection, live processing UI, non-thinking guard runtime check, opt-in synthetic runtime audit |
| `:pipeline` | `SmsContractAssets` & `CurrencyProfileRegistry`, `SmsPrefilter` (54 golden vectors), `StructuralSmsAnalyzer`, `SmsExtractorValidator`, `DefaultSmsProcessingCoordinator`, `PipelineService`, `SmsWorkSchedulerImpl`, `SmsParserWorkerPolicy`, preferences & notifications | `PackagedSmsContractTest` (verifies packaged APK LF assets and SHA-256 digests) |
| `:inference` | `LlamaEngine`, `DirectCandidateSelector` + `NonThinkingOutputGuard`, `SlmRuntimeCoordinator`, `ModelDownloader`, `ParallelRangeDownloader` | `ModelDownloaderAndroidFilesystemTest` |
| `:data` | Room migrations (`v1 -> v8`, `v3/v4/v6/v7 -> v8`), `TransactionDao`, `AccountDao`, `AccountRepository`, `TransactionRepository`, `ProcessingConfigurationRepository`, `GroundedAccountResolver`, `SmsReviewGrounding`, `SmsReviewRepository`, `SmsAutomaticPersistence`, `SmsIngestionRepository`, `SmsSourceIdentity`, `SmsDatabaseKeyProvider` | `SmsProcessingRecoveryDeviceTest` (encrypted SQLCipher reopen, atomic persistence, tombstones, recovery) |
| `:sms` | `SmsReader`, `SmsRepository`, `SmsReceiver` | — |
| `:hardware` | `DeviceCapabilities`, `SlmSelector` | — |

## Local Verification Commands

Run from the repository root (`.\gradlew.bat` on Windows PowerShell,
`./gradlew` on Linux/macOS):

```powershell
# 1. All debug unit tests (all 6 modules)
.\gradlew.bat testDebugUnitTest --no-daemon --stacktrace

# 2. Verify every androidTest source compiles and packages into test APKs
.\gradlew.bat compileDebugAndroidTestKotlin assembleDebugAndroidTest --no-daemon --stacktrace

# 3. Android lint
.\gradlew.bat lintDebug --no-daemon --stacktrace

# 4. Build debug APK (com.pocketfinancer.debug)
.\gradlew.bat :app:assembleDebug --no-daemon --stacktrace
```

Targeted module examples:

```powershell
.\gradlew.bat :pipeline:testDebugUnitTest --tests "com.pocketfinancer.pipeline.sms.SmsPrefilterTest" --no-daemon
.\gradlew.bat :data:testDebugUnitTest --tests "com.pocketfinancer.data.db.AppDatabaseMigrationTest" --no-daemon
```

### Windows Line-Ending Verification

`SmsContractAssets` validates exact SHA-256 digests over LF-encoded assets in
`pipeline/src/main/assets/sms_processing/`. `.gitattributes` pins those paths to
`eol=lf`. If `SmsContractAssetsTest` fails on Windows after a checkout, refresh
the working tree line endings (`git add --renormalize` is not needed; use
`git checkout -- pipeline/src/main/assets/sms_processing pipeline/src/test/resources`).

## Connected Emulator / Device Verification

### Install & Launch Debug Build

```powershell
# Build and install com.pocketfinancer.debug
.\gradlew.bat installDebug --no-daemon

# Restart the app cleanly
adb shell am force-stop com.pocketfinancer.debug
adb shell am start -n com.pocketfinancer.debug/com.pocketfinancer.MainActivity
```

### Run Connected Instrumentation Suites

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest :data:connectedDebugAndroidTest :pipeline:connectedDebugAndroidTest :inference:connectedDebugAndroidTest --no-daemon
```

### Opt-In Aggregate Synthetic Runtime Audit

`SmsSyntheticRuntimeAuditTest` is skipped during ordinary instrumentation runs
and outputs only aggregate counts (no SMS bodies, generated text, or IDs). To
run it against an already-populated synthetic debug emulator:

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest --no-daemon
adb shell am instrument -w -e smsSyntheticAudit true -e class com.pocketfinancer.ui.smsprocessing.SmsSyntheticRuntimeAuditTest com.pocketfinancer.debug.test/androidx.test.runner.AndroidJUnitRunner
```

### Synthetic SMS Injection on an Emulator

1. **Single synthetic incoming SMS (real-time broadcast path)**:

   ```powershell
   adb emu sms send "SYNTHBANK" "INR 245.00 debited from A/c XX4321 at DEMO CAFE"
   adb emu sms send "SYNTHOTP" "Your OTP is 482910. Do not share with anyone."
   ```

2. **Bulk synthetic inbox seed (`inject_sms.py`)**:

   > [!WARNING]
   > `inject_sms.py` **clears the emulator SMS inbox** before inserting messages.
   > Never run it on a device or AVD with messages you need to keep.

   ```powershell
   python inject_sms.py --dataset /path/to/pocket-financer-intelligence/DATA/extraction_ds.jsonl
   ```

   Without `--dataset`, the script looks for a sibling
   `../pocket-financer-intelligence/DATA/extraction_ds.jsonl` checkout.

### Useful `adb` Debugging Commands

```powershell
# Dump UI hierarchy for accessibility / bounds inspection
adb shell uiautomator dump /sdcard/window_dump.xml
adb pull /sdcard/window_dump.xml .\window_dump.xml

# Inspect app-private downloaded GGUF models
adb shell run-as com.pocketfinancer.debug ls -la files/models/

# Inspect recent logs (remember: never share logs containing private data)
adb logcat -d -s PocketFinancer:D AndroidRuntime:E
```
