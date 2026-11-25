# Contributing to Pocket Financer Android

Pocket Financer Android uses short-lived feature branches, protected pull
requests, Conventional Commit titles, and automated Release Please releases.
`main` must always remain tested and releasable.

Read [AGENTS.md](AGENTS.md) before making code changes, and consult
[docs/releasing.md](docs/releasing.md) for CI/CD controls, release signing, and
incident recovery.

## Branch & Pull Request Workflow

All changes go through a branch and pull request targeting `main`—never push
implementation, documentation, test, or CI commits directly to `main`.

```text
origin/main
  -> <type>/<short-description>
  -> pull request + required CI ("Build, lint, and unit tests")
  -> squash merge
  -> Release Please updates its automated release pull request
  -> owner explicitly merges the release pull request
  -> signed stable APK + SHA-256 checksum + GitHub Release
```

Use concise, descriptive branch names such as:

- `feat/category-budgets`
- `fix/duplicate-window-boundary`
- `docs/refresh-architecture-guide`
- `chore/overhaul-tests-docs-ci`

## Pull Request Titles & Release Impact

The pull request title becomes the squash-merge commit on `main` and drives
Release Please versioning:

| PR Title Example | Release Effect |
| :--- | :--- |
| `feat: add category budgets` | Minor version bump |
| `fix: prevent duplicate SMS ingestion` | Patch version bump |
| `feat!: replace local export schema` | Major version bump |
| `docs: update testing guide` | No release by itself |
| `test: expand coordinator coverage` | No release by itself |
| `refactor: simplify review grounding` | No release by itself |
| `build: bump Android build tools` | No release by itself |
| `ci: verify androidTest compilation` | No release by itself |
| `chore: clean up test fixtures` | No release by itself |

Optional scopes are encouraged when helpful (for example, `fix(sms): ...` or
`test(pipeline): ...`). For a breaking change without `!`, include a
`BREAKING CHANGE: <migration impact>` footer in the squash commit body.

## Pull Request Checklist

Before requesting merge:

1. Trace affected entry points, state transitions, settings consistency
   boundaries, and restart/recovery behavior end to end ([AGENTS.md](AGENTS.md)).
2. Keep all test fixtures and documentation 100% synthetic (zero real SMS,
   phone numbers, personal paths, or secrets).
3. Add or update unit and/or instrumented tests at the lowest practical layer.
4. Run the local verification gates:
   - `testDebugUnitTest`
   - `compileDebugAndroidTestKotlin assembleDebugAndroidTest`
   - `lintDebug`
   - `:app:assembleDebug`
5. Confirm required GitHub Actions checks pass and resolve review conversations.
6. Update documentation in the same pull request whenever behavior or automation
   changes.

Use **squash merge** and let GitHub delete the merged source branch. Do not
merge while required checks are pending or failing.

## Versions, Changelogs, and Releases

- Normal pull requests must **never** edit `version.txt` or `CHANGELOG.md`,
  create or move `v*` tags, or upload APKs manually.
- Release Please derives the next SemVer version from squash commits on `main`
  and maintains one automated release pull request.
- Multiple `feat:` and `fix:` pull requests can accumulate in that release PR
  without publishing an APK.
- A stable release is published only when the repository owner explicitly
  approves and merges the verified Release Please pull request (see
  [docs/releasing.md](docs/releasing.md)).
