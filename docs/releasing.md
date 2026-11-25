# Releasing Pocket Financer Android

Pocket Financer Android uses **Release Please** as a controlled publication
gate. Routine changes merge to `main` via short-lived pull requests, while a
stable signed APK is published only when the repository owner explicitly merges
the automated Release Please pull request.

Executable workflow and Gradle configuration files are the final authority;
whenever automation changes, update this guide in the same pull request.

## Live Automation Reference

### Pull-Request CI (`.github/workflows/ci.yml`)

Runs on pushes to `main`, pull requests targeting `main`, and manual
`workflow_dispatch`. Superseded runs on the same PR or ref are cancelled.

The required branch-protection check job is named **`Build, lint, and unit tests`**
(running on `ubuntu-latest`, JDK 17 Temurin, Android SDK 36, `build-tools;36.0.0`,
`cmake;3.22.1`, NDK `27.3.13750724`, validated Gradle wrapper):

1. Validates `.github/workflows/release.yml` retains the repository-scoped CI
   dispatch pattern (`gh workflow run ci.yml --repo "$GITHUB_REPOSITORY" --ref "$head_branch"`).
2. `./gradlew testDebugUnitTest --no-daemon --stacktrace`
3. `./gradlew compileDebugAndroidTestKotlin assembleDebugAndroidTest --no-daemon --stacktrace`
4. `./gradlew lintDebug --no-daemon --stacktrace`
5. `./gradlew :app:assembleDebug --no-daemon --stacktrace`

Unit-test reports, Room schemas, lint reports, and the debug APK are retained as
workflow artifacts for 14 days.

### Versioning & Release Automation (`.github/workflows/release.yml`)

`version.txt` holds the stable `major.minor.patch` SemVer string.
`app/build.gradle.kts` derives:

```text
versionName = major.minor.patch
versionCode = major * 1,000,000 + minor * 1,000 + patch
```

Minor and patch components must be `0..999`, and `versionCode` must be in
`1..2,100,000,000`. Debug builds use application ID `com.pocketfinancer.debug`
and `-debug` suffix so they coexist safely with stable builds
(`com.pocketfinancer`).

`.github/workflows/release.yml`, `release-please-config.json`, and
`.release-please-manifest.json` manage releases:

- `fix:` requests a patch release, `feat:` a minor release, and `!` or
  `BREAKING CHANGE:` a major release. Non-release types (`docs:`, `test:`,
  `refactor:`, `build:`, `ci:`, `chore:`) do not trigger a release on their own.
- Release Please maintains one automated release pull request updating
  `version.txt` and `CHANGELOG.md`.
- When `RELEASE_PLEASE_TOKEN` is not configured, the preparation job uses
  `github.token` and explicitly dispatches `ci.yml` on the release branch via
  `gh workflow run ci.yml --repo "$GITHUB_REPOSITORY" --ref "$head_branch"`.
- **Required GitHub Actions permission**: When using `github.token` (without
  `RELEASE_PLEASE_TOKEN`), repository/organization Actions settings must enable
  **"Allow GitHub Actions to create and approve pull requests"**
  (`can_approve_pull_request_reviews = true`). If that setting is disabled and
  `RELEASE_PLEASE_TOKEN` is unset, `release-please` fails with
  `GitHub Actions is not permitted to create or approve pull requests`.
- Merging the automated release pull request causes Release Please to create a
  draft `v<version>` tag and GitHub Release, triggering `build-and-publish`
  under the non-cancelling `stable-release` concurrency group.

The `build-and-publish` job (75-minute timeout, `environment: release`,
`contents: write`):

1. Checks out the exact release commit or recovery tag (`refs/tags/<release_tag>`).
2. Validates `version.txt`, tag (`v<version>`), and commit SHA agreement.
3. Decodes the production keystore from secrets into `$RUNNER_TEMP` (`umask 077`).
4. Runs `./gradlew testDebugUnitTest lintRelease :app:assembleRelease --no-daemon --stacktrace`.
5. Rejects any unsigned APK, validates `versionName` via `aapt`, verifies the
   signature via `apksigner`, and confirms the signer SHA-256 fingerprint matches
   `release-signing-cert.sha256`.
6. Generates `pocket-financer-<tag>.apk` and `.sha256`, preserves mapping/native
   symbols/lint diagnostics for 90 days, uploads verified assets, publishes the
   draft release as stable and latest, and always deletes the temporary keystore.

Signed release artifact tasks in `app/build.gradle.kts` fail closed if any
signing environment variable is missing (`lintRelease` alone does not require
signing).

### Branch Protection & Dependency Automation

Live `main` branch controls for
`pocket-financer/pocket-financer-android`:

- Pull requests required; required status check **`Build, lint, and unit tests`**
  with up-to-date branch (`strict: true`).
- Enforced for administrators; linear history required; review conversations
  must be resolved; force pushes and branch deletion blocked.
- Squash merge only; merged head branches auto-deleted.
- Default Actions workflow token permission is `read`.

`.github/dependabot.yml` checks Gradle and GitHub Actions weekly on Monday
(`Asia/Kolkata`), grouping minor/patch updates into at most one open PR per
ecosystem while ignoring major upgrades and pinned platform/AGP-coupled
dependencies (Dagger 2.59+, Room 2.8+, Kotlin 2.4+, Lifecycle 2.11+, Core 1.19+,
Hilt Navigation 1.4+). Nothing auto-merges.

## Production Signing Key Custody & Secrets

Required repository/environment secrets (names only—never log or commit values):

- `ANDROID_RELEASE_KEYSTORE_BASE64`
- `ANDROID_RELEASE_STORE_PASSWORD`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_KEY_PASSWORD`
- `RELEASE_PLEASE_TOKEN` *(optional fine-grained PAT if org policy disables
  `GITHUB_TOKEN` PR creation)*

Keystore generation and upload (run only on a trusted local machine outside the
repo, with at least two encrypted offline backups):

```powershell
keytool -genkeypair -v `
  -keystore pocket-financer-release.jks `
  -alias pocket-financer `
  -keyalg RSA `
  -keysize 4096 `
  -validity 10000

$keystoreBytes = [IO.File]::ReadAllBytes((Resolve-Path ".\pocket-financer-release.jks"))
$keystoreBase64 = [Convert]::ToBase64String($keystoreBytes)
$keystoreBase64 | gh secret set ANDROID_RELEASE_KEYSTORE_BASE64
Remove-Variable keystoreBase64, keystoreBytes

gh secret set ANDROID_RELEASE_STORE_PASSWORD
gh secret set ANDROID_RELEASE_KEY_ALIAS
gh secret set ANDROID_RELEASE_KEY_PASSWORD
```

Confirm secret presence without printing values via `gh secret list`.

## Stable Update Invariants

Every stable APK updating existing installations must preserve:

1. Application ID `com.pocketfinancer`.
2. Strictly increasing `versionCode` (`major * 1,000,000 + minor * 1,000 + patch`).
3. The same production signing certificate matching `release-signing-cert.sha256`
   (established with the initial stable baseline
   [v1.0.0](https://github.com/pocket-financer/pocket-financer-android/releases/tag/v1.0.0),
   signer SHA-256
   `08:65:6A:08:56:A1:C1:4E:30:4E:8E:C9:F6:06:82:F8:EC:7C:E4:09:DB:CC:A9:61:07:75:D5:2F:BC:88:45:B1`).

Because `android:allowBackup="false"`, uninstalling permanently erases the
encrypted database, downloaded models, and settings. Never rotate the production
signing key silently.

## Publishing a Routine Release

1. Inspect the open automated Release Please pull request: verify its proposed
   SemVer version, `CHANGELOG.md` entries, and green `Build, lint, and unit tests`
   check.
2. Only after explicit owner approval, squash-merge the automated Release Please
   pull request (never manufacture a manual replacement PR or tag).
3. Monitor the **Release** workflow to completion.

### Post-Release Verification

For every published release (`<tag>` = e.g. `v1.2.0`):

```powershell
# Download assets from the GitHub Release and verify checksum
Get-FileHash .\pocket-financer-<tag>.apk -Algorithm SHA256
Get-Content .\pocket-financer-<tag>.apk.sha256

# Verify signature and compare SHA-256 fingerprint against release-signing-cert.sha256
apksigner verify --verbose --print-certs .\pocket-financer-<tag>.apk
```

Smoke-test the downloaded APK on a clean test device across launch, onboarding,
permissions, SMS ingestion, encrypted persistence, and restart recovery.

## Hotfixes, Rollbacks & Recovery

- **Emergency hotfix**: Branch `fix/<urgent-slug>` from `origin/main`, add a
  regression test, open a `fix:` PR, merge after CI passes, then inspect and
  merge the updated Release Please PR to publish a higher `versionCode`.
- **Bad release / rollback**: Android rejects downgrades to a lower
  `versionCode`. Never delete or retarget an immutable tag. Mark the affected
  GitHub Release with a warning (and remove `latest` if needed), revert on a
  `fix/revert-<slug>` PR, and ship a fix-forward patch release.
- **Release PR missing or failing**: Confirm the merged PR title used a
  release-triggering Conventional Commit type and inspect the `release-please`
  job logs. If it fails with `GitHub Actions is not permitted to create or approve
  pull requests`, either enable PR creation for GitHub Actions in repository/org
  settings or configure `RELEASE_PLEASE_TOKEN`.
- **Release PR CI did not start**: Confirm `release.yml` retains `actions: write`
  and `gh workflow run ci.yml --repo "$GITHUB_REPOSITORY" --ref "$head_branch"`.
- **Failed `build-and-publish` after merging the release PR**: Fix the workflow
  via a normal `fix/<slug>` PR to `main`, then trigger **Release** manually via
  `workflow_dispatch` with `release_tag` set to the existing **draft** tag (do
  not rerun the stale workflow job or invent a new tag).
