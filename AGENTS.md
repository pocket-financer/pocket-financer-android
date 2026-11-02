# Repository Development Instructions

Read `copilot-instructions.md` for project architecture, build, test, and
emulator context. Verify documentation against the current code before relying
on it.

## Cross-repository access on Windows

For PocketFinancer work on this workstation, use these existing checkouts:

| Repository | Windows location | Native tooling |
| --- | --- | --- |
| Shared SMS/data/model research | `\\wsl.localhost\Ubuntu\home\tojinotzenin\pF_slm_selection` | Ubuntu: `/home/tojinotzenin/pF_slm_selection` |
| Android | `D:\Personal_Projects\pocket-financer\pocket-financer-android` | Windows Git, PowerShell, `gradlew.bat` |
| iOS | `D:\Personal_Projects\pocket-financer\pocket-financer-ios` | Windows Git for local edits; macOS/Xcode for builds and devices |

The shared GitHub repository is now named `pocket-financer-intelligence`. The
workstation path above records the existing checkout's former directory name;
verify it before moving any private workspace.

Read each target repo's `AGENTS.md` and respect the active task scope and workspace
permissions. If a Windows sandbox denies WSL with `E_ACCESSDENIED`, start the
execution tool from `C:\Users\manis`, then use its supported sandbox-escalation
approval path for a harmless probe and authorized WSL commands:

```powershell
wsl.exe -d Ubuntu --cd /home/tojinotzenin/pF_slm_selection --exec bash -lc '<command>'
```

For Codex this means `exec_command` with
`sandbox_permissions="require_escalated"` and a precise justification; automatic
review applies when configured. Do not claim the repo is unavailable after only
a sandboxed failure, ask the user to repeat access history, or use a Codex restart
as the default remedy. If review rejects the request, respect it and report the
action and reason. This procedure does not grant permissions or alter the sandbox.

Keep Git/tooling native to each checkout. Linux Git against Windows app checkouts
can show file-mode noise; do not reset files to remove it. Never copy private data
between checkouts to solve access. The shared guide is
`docs/guides/POCKETFINANCER_WINDOWS_WSL_ACCESS.md` in the WSL repo. A general Windows/WSL
bootstrap is installed in this workstation's `C:\Users\manis\.codex\AGENTS.md`
so a new session can learn the procedure before opening WSL.

## Codex collaboration model profile

This repository currently uses the following Codex collaboration profile
(recorded 2026-08-31). It governs the coding agents working on this repository,
not the models shipped in the Pocket Financer app.

- The primary/root agent uses `gpt-5.6-sol` with `medium` reasoning. It owns
  scope, integration, and final verification.
- An `explorer` subagent uses `gpt-5.6-luna` with `medium` reasoning for
  read-only repository discovery and behavior tracing.
- A `worker` subagent uses `gpt-5.6-terra` with `medium` reasoning for bounded
  implementation and verification tasks.
- A `reviewer` subagent uses `gpt-5.6-terra` with `high` reasoning for read-only
  correctness, security, regression, and test review.
- A `default` subagent inherits the parent agent's model and reasoning effort
  unless the spawning task explicitly overrides them.

The active Codex runtime and task instructions are authoritative if this dated
profile differs from the live configuration. A model or reasoning override does
not change repository rules, task scope, permissions, or review requirements;
record any deliberate override in the task handoff.

## Holistic Change Discipline

For every feature and bug fix, trace the behavior end to end before
implementation. Inspect every relevant production entry point and state owner,
including UI and ViewModels, persisted settings, foreground and background
processing, services and workers, inference and native code, repositories, and
restart or recovery paths.

Explicitly resolve all material states and transitions: defaults and upgrades,
idle, queued and in-flight behavior, concurrent or repeated actions,
cancellation, failure and retry, app or process restart, and downstream data or
cache compatibility. Consider privacy, security, latency, battery, memory,
accessibility, and truthful user feedback wherever relevant.

Runtime settings must have a defined consistency boundary. Choose explicitly
between safe immediate application, application to the next operation using a
per-operation snapshot, or temporarily disabling or deferring the control while
busy. Never allow one operation to run partly with the old value and partly with
the new value. Surface any material product choice rather than silently
guessing.

Test the primary behavior and relevant transitions or regressions at the lowest
practical layer. Run affected module tests and the appropriate build check.
Document intentionally unsupported cases and meaningful tradeoffs without
adding speculative complexity.

## Version Control and Release Discipline

All future Codex implementation work must use a short-lived branch and pull
request:

1. Update from `origin/main`.
2. Create a branch named `codex/<type>-<short-description>`, such as
   `codex/feat-budget-alerts` or `codex/fix-duplicate-import`.
3. Keep the branch focused, run the relevant tests and build checks, then push
   the branch.
4. Open a pull request targeting `main`.
5. Use a Conventional Commit pull-request title. The squash-merge title becomes
   the commit on `main`.

Codex should create the branch, commit, push, and open the pull request itself
when repository credentials permit; do not hand routine Git steps back to the
user. If authentication or repository policy blocks an action, report the exact
blocker without bypassing protection.

Do not commit or push feature, fix, refactor, documentation, dependency, or CI
work directly to `main`. Do not bypass required checks. A user may explicitly
authorize an exceptional bootstrap or recovery operation, but that does not
change the default for later work.

Use these release-relevant title forms:

- `feat: ...` for a backward-compatible feature (minor version).
- `fix: ...` for a backward-compatible correction (patch version).
- `feat!: ...` or a `BREAKING CHANGE:` footer for an incompatible change
  (major version).
- `docs:`, `test:`, `refactor:`, `build:`, `ci:`, and `chore:` for work that
  should not trigger a version by itself.

Release Please owns `version.txt`, `CHANGELOG.md`, `v*` tags, and stable GitHub
Releases during normal operation. Never manually edit a version or changelog in
a feature pull request, create or move a release tag, or upload a stable APK.
Merges to `main` cause Release Please to create or update one automated release
pull request. A release happens only when that automated release pull request is
merged.

Codex may inspect the release pull request and report whether its version,
changelog, and checks are correct. Codex must merge it only after the user
explicitly asks to publish or ship the release. Follow
`docs/releasing.md` for the current CI/CD controls, signing setup, verification,
hotfixes, and recovery. Before changing automation, verify that guide against
`.github/workflows/ci.yml`, `.github/workflows/release.yml`,
`.github/dependabot.yml`, `release-please-config.json`, and the live GitHub
repository settings.
