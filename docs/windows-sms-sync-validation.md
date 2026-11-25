# Windows SMS sync validation — 2026-09-10

> Historical evidence for the named baseline and follow-ups. It does not establish
> current selector/extractor accuracy, direct-to-Transactions routing, physical
> device readiness, or that later emulator observations are fixed. Use
> [sms-processing-next-steps.md](sms-processing-next-steps.md) for current work.

Baseline: Android PR #40, commit 64a91f7871f59036e8ac45e5f5023f8a268b788c.

## Failures reproduced

- The Windows checkout used core.autocrlf=true. Frozen SMS assets were LF in Git
  and CRLF in the worktree/APK. Every admitted message failed the pinned currency
  asset hash check before structural analysis. Repeated import retried the same
  incompatible asset bytes.
- After preserving the release bytes, the selector deadline was treated as user
  cancellation. Historical import stopped and displayed Setup paused.

## Changes

- Pin the frozen asset directory to LF using .gitattributes. Do not normalize at
  runtime or weaken the expected hashes.
- Add an Android instrumentation test that hashes the actual packaged currency,
  prompt, and locale profile assets against the frozen runtime constants.
- Settle a local selector timeout as retained review with runtime_timeout and a
  recorded failed attempt. Parent cancellation still propagates as cancellation.

## Verification so far

- Windows debug build/install and packaged-asset instrumentation test passed.
- 110 pipeline unit tests passed, including selector timeout settlement and user cancellation.
- Retried the emulator's existing failed import without clearing application data.
  The complete batch finished: 24 messages checked, 14 eligible, with the same app
  process alive throughout and no new sync exception. Pipeline lint also passed.
  This is emulator functional evidence, not physical-device or model-accuracy evidence.
- Automatic persistence remains disabled. No private messages or model output are
  included in this report.

Other uncommitted recovery/review tests are separate ongoing work and are not
part of the Windows asset fix's validation claim.

## Review correction follow-up

The encrypted device regression reproduced a separate failure: an alert without
reconstructed model output could not accept a complete manual correction. The
repository now requires all six correction fields in that case and projects only
an explicit user transaction. It never inserts a fabricated model result.
Incomplete input, confirmation without a proposal, duplicate fields, fractional
numbers, overflowing integers, and numeric strings are rejected atomically.
Currency/time corrections retain explicit user provenance.

Review actions reserve their busy state before coroutine dispatch, preventing
rapid repeated taps from racing. Confirmation is unavailable without a proposal.

Verification: four encrypted Android device tests passed, covering recovery after
database reopen, live heartbeat exclusion, stale-owner fencing, durable drafts,
idempotent feedback/projection, manual correction, and invalid-input rollback.
All 210 app unit tests passed, including repeated-action coverage. These fixtures
are invented and use a separate test database; the installed app data is not
cleared. Process-death and physical-device coverage remain separate gates.

## Import summary and session handoff

Historical import now persists a separate retained-review count through progress
checkpoints, reset, and terminal settlement. Retained alerts are no longer counted
as rejected. Completed review imports link directly to Saved alert reviews from
Home. Older imports keep their unknown category breakdown; the UI offers review
navigation without inventing a historical review total.

Verification: all 213 app unit tests, debug build/install, and app lint passed.
The emulator's existing completion card was checked and its new button opened the
Saved alert reviews inbox. Whitespace validation passed.

Session boundary requested by the user: this session's fixes are complete. The
investigation into why all Android messages are being retained for review belongs
to the user's other session. Do not treat the successful import or review UI tests
as proof of selector accuracy or automatic-persistence readiness. Automatic
persistence remains disabled. The updated app is installed on the emulator.
