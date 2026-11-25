## Summary

<!-- What user-visible or engineering outcome does this PR deliver? -->

## Release impact

<!-- The PR title becomes the squash commit. Use feat:, fix:, feat!:, or a
non-release Conventional Commit type. -->

- [ ] Minor (`feat:`)
- [ ] Patch (`fix:`)
- [ ] Major (`!` or `BREAKING CHANGE:`)
- [ ] No release by itself (`docs:`, `test:`, `refactor:`, `build:`, `ci:`,
      `chore:`)

## End-to-end behavior

<!-- Identify affected entry points and state owners. Cover relevant defaults,
upgrades, in-flight/concurrent work, cancellation, failure/retry, restart, and
downstream compatibility. State the consistency boundary for runtime settings. -->

## Verification

<!-- List exact tests, lint/build commands, emulator/device checks, and results. -->

- [ ] Relevant automated tests added or updated
- [ ] Affected module tests and `androidTest` compilation pass
- [ ] Appropriate lint/build check passes
- [ ] Manual or emulator verification completed, or not applicable

## Safety and compatibility

<!-- Note privacy/security, data migration, battery/memory/latency,
accessibility, signing, package/version, or rollback concerns. Write "None"
only after checking. -->

## Evidence

<!-- Screenshots, recordings, logs without secrets/user data, or other evidence
when useful. -->

## Checklist

- [ ] I read `AGENTS.md` and verified relevant code before changing behavior
- [ ] This PR is focused and targets `main` from a short-lived `<type>/<short-description>` branch
- [ ] The PR title is a valid Conventional Commit and describes the squash commit
- [ ] Generated release files (`version.txt` and `CHANGELOG.md`) are untouched,
      unless this is the automated Release Please PR
- [ ] No keystore, credentials, real SMS/financial data, or other secrets are included
