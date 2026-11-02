# Android SMS processing next steps

Status: **Android gates and synthetic Pixel_9 retry/recovery checks passed; model-driven automatic save and physical-device verification pending**
Last runtime checkpoint: 2026-09-25
Source/guidance reconciliation: 2026-10-05 (no new emulator or device run)

## Direct-only SMS output invariant

The Android runtime renders the selected GGUF model's chat template with thinking
disabled. Every SMS model uses the same direct JSON path, regardless of the
model's advertised thinking capability. A non-JSON first token aborts generation
before a live callback. The selector also checks streamed and final output for
thought markers across token boundaries; violating output is neither shown nor
stored as raw attempt text. The operation stays reviewable with
`runtime_mode_violation`. A model that cannot render the direct template or
produce direct JSON is ineligible for this path; no thinking fallback is used.

The synthetic Qwen3 emulator diagnostic and focused JVM tests cover this boundary.
A separate physical-device and broader model matrix remains open.

The shared repository `pocket-financer-intelligence` owns the canonical architecture,
versioned contracts, sanitized vectors, host GGUF evaluation, native-trace import,
and the planned Android/iOS native scoring lanes. This repository owns the
Windows/Gradle, Kotlin/JNI, Room, Compose, emulator, and Android-device
implementation lane.

## Implemented source baseline

The existing source includes strict extractor parsing, Unicode-scalar conversion,
exact minor-unit normalization, account resolution, sanitized vectors, durable
review/recovery state, review drafts, atomic confirmation, full-source review,
per-field highlights, and one active native text selection. Stored v1-v4
operations and readers remain supported without reinterpretation.

Commit `fcf6614` binds the shared automatic-routing contract to Android. The
`native-integration-v5` name is an internal immutable release identifier required
to preserve stored v1-v4 behavior; it is not another user-facing pipeline, screen,
or plan. New operations now use the single final policy: complete valid posted
results with one existing account and a clear duplicate assessment are persisted
atomically; valid `none` settles without a transaction; exceptions enter Review.
The frozen v4 path remains `review_only` for its existing operations and
original-configuration retries.

Current snapshots also bind `native-integration-v6` / `processing-config/6`
when they capture an explicit grammar choice. `PipelineService` passes the
current GBNF preference once to `SmsV5OperationSnapshotFactory`; the snapshot
persists the matching release/configuration and runtime parameters. A null
grammar choice preserves the frozen v5 binding. This source behavior is
implemented; it does not close the model-driven save or device gates.

Implemented in `fcf6614`:

- hash verification for the shared automatic contract bundle;
- explicit retry lineage with a new operation and unchanged prior operation;
- additive review-case/2 evidence storage while review-case/1 remains unchanged;
- independent retention of safely grounded extractor fields and separately
  labelled analyzer suggestions;
- one Room transaction for transaction, revision, processing result, persistence
  decision, and owner-fenced settlement;
- source-event and fingerprint duplicate fencing, with retained parent reviews
  excluded from false duplicate classification;
- decoded-token callback propagation from JNI-facing extraction through the
  coordinator and `PipelineService` observer contract.

Commit `f6079ff` completes the existing Review surface for the automatic policy:

- `review-case/2` partial SLM fields are projected without converting analyzer
  suggestions into model output;
- all valid source spans remain highlighted while missing fields remain visibly
  unassigned;
- missing direction wording uses an explicit debit/credit owner control;
- confirmation requires a deliberate existing-account selection and every
  mandatory field to be valid;
- confirmation reuses the revision-bound atomic transaction path; and
- selecting a Needs Review card opens that case directly while the Review inbox
  remains available from its existing entry point.

Commit `01f3861` restores live generation visibility on the existing processing
surface. Automatic, historical, and manual operations now expose a bounded latest
decoded-token delta separately from bounded cumulative structured output. The
cumulative value is mandatory on every callback and is not reconstructed or
described as reasoning. Manual updates are fenced by run, candidate, and exact
attempt; automatic updates retain exact claim fencing. Terminal, cancellation,
stale-owner, and lifecycle paths scrub private transient text.

## 2026-09-24 emulator feedback (historical requested behavior)

The owner tested the current PR on the emulator and found that the Review flow is
still difficult to use. This section records the requested behavior; earlier
checkpoints below remain dated evidence, not acceptance of this UX.
The list preserves the request made on that date. Grammar setting capture is now
implemented through the v6 source binding described above; use the open
observations and verification sections for the remaining acceptance work.

1. Show the model's output growing live in one readable area. Remove the
   separate "Latest decoded token delta" card. Keep decoded-token callbacks and
   cumulative output local, bounded, and cleared when the operation ends.
2. Make the "Use GBNF grammar" setting control actual SMS processing. It defaults
   off. Capture its value once per operation, persist that choice with a versioned
   configuration, and report the same value in diagnostics. Preserve the grammar
   behavior of already stored operations and original-configuration retries;
   current-configuration retries use the current setting. Do not alter frozen v5
   assets in place.
3. Remove the receipt-time explanatory sentence from Review. The receipt timestamp
   itself remains immutable.
4. Make annotation selection-first: select wording in the complete SMS, then tap
   Amount, Direction, Account, or Counterparty to assign it. Clear the transient
   native selection after assignment or when tapping elsewhere. Show an immediate
   result and a clear way to replace or remove an assignment. Offer short,
   source-backed, one-tap choices for missing fields (especially Account) so
   precise drag selection is optional. Do not require typed field values.
   Preserve exact Unicode-scalar grounding.
5. Present confirmed fields clearly. Prefer distinct, accessible source highlights
   that do not interfere with native selection. If native selection makes that
   unreliable, use colored field chips with an adjacent source excerpt or a
   tap-to-focus evidence preview. Verify the chosen design with actual touch
   interaction and screen-reader labels; color alone must not carry meaning.
6. Keep direction as one resolved field. Selecting "debited" and assigning
   Direction should yield Debit, clear the selection, and avoid a second "Paid"
   suggestion in the main flow. Retain analyzer suggestions only in an optional,
   clearly labeled technical details area.
7. Remove the existing-account prerequisite from Review. When the owner confirms
   a grounded account reference, reuse a unique matching account or create a new
   account and alias atomically with the transaction. Do not create duplicates
   on retry/replay; keep ambiguity and write failures reviewable.
8. Display money in major units (for example, INR 125.00), while retaining exact
   12,500 minor units in storage for a two-decimal currency. Amount is still
   required for a valid transaction, but can come from accepted extractor evidence
   or source-text annotation. Confirmation must explain any remaining blocker
   instead of staying disabled without a reason.

Acceptance: use the synthetic SMS from the emulator report to select each field
and confirm with no pre-existing account; verify the saved transaction is INR
125.00 and has one newly created account. Repeat with a matching account, a
duplicate action, an interrupted/retried operation, grammar off/on, and selection
dismissal. Run focused JVM and Compose checks, the full Android unit/lint/build
gate, then connected emulator interaction. Record emulator evidence separately
from physical-device evidence.
## Open Android observations

The emulator was not running during commits `fcf6614`, `f6079ff`, or `01f3861`,
so those commits originally had only local JVM/compile verification. Subsequent
Pixel_9 instrumentation and direct Review interaction passed as recorded below.
A complete model-driven automatic save, runtime duplicate fencing after such a
save, full accessibility, and physical-device behavior still need end-to-end
verification. Synthetic Review retry and controlled process recovery are checked
below.

## Next implementation change

Implemented and verified locally in `f6079ff`: review-case/2 projection through
the existing `GroundedReviewContent` and `EvidenceSelectionText`, explicit
direction fallback, deliberate existing-account selection, mandatory-field
confirmation gating, and direct Review-card navigation.

Implemented and verified locally in `01f3861`: separate live decoded-token and
cumulative structured-output presentation, coalescing, exact stale-operation
fencing, terminal/cancellation/lifecycle cleanup, and completed-output handling
across automatic, historical, and manual processing.

Planned verification next:

1. Continue synthetic-only model/runtime checks for a complete valid posted
   result and duplicate assessment on Pixel_9; the sampled local models have
   only reached Review. Complete the full accessibility matrix and physical
   Android-device checks. Keep deterministic connected store proof distinct from
   actual model-driven saving.
2. Keep corrections revision-bound, append-only, local label evidence. Explicit
   export and adjudication are required before approved, source-grounded,
   split-safe labels may improve the SLM or another pipeline component.

## Transparency and review verification

The processing UI must show the unchanged SMS, advisory analyzer evidence, model
request, one growing direct JSON output pane, validation, route, persistence
result, and later owner correction as distinct facts. It must not display
thought output or put private text in logs.

Re-run accessibility coverage for the full source SMS, separate amount/direction/
account/counterparty highlights, screen-reader labels, focus order, and the
single-active-selection invariant. A later failure must not hide fields that were
successfully grounded earlier: Review shows every valid partial field from the
last completed stage on the unchanged SMS body.

## Shared evaluation lane

`pocket-financer-intelligence` already provides the host GGUF evaluator and encrypted
native-trace import. A dedicated Android native evaluator/scorer is still planned.
It will run the exact app contract and model on emulator/device, export a
provenance-bound encrypted trace bundle, and score aggregate contract, model,
routing, review, recovery, latency, and resource results in the shared repository.
Host GGUF evidence is not Android runtime evidence.

## Windows/Android verification order

1. Verify shared bundle hashes and sanitized parity vectors without modifying
   frozen release assets.
2. Run focused parser, money, scalar, account, routing, Room migration, recovery,
   atomic confirmation, and presentation tests.
3. Run the complete Gradle unit/instrumentation/lint/build gates.
4. Use a fresh emulator state plus explicit migrated-store fixtures. Record new
   results separately from `docs/windows-sms-sync-validation.md`.
5. Run the target physical Android device matrix for model identity, inference,
   live decoding, process death, accessibility, memory, latency, thermal, and
   battery behavior.

Keep the implemented automatic-processing preference opt-in and off by default.
Do not broaden that default or describe the full SMS implementation as complete
until the shared evaluation strategy and physical-device gates pass.

## 2026-09-22 handoff checkpoint

Implemented commit: `fcf6614 feat(sms): enable automatic grounded routing`.

Verified locally:

- `./gradlew.bat :app:compileDebugKotlin --no-daemon`;
- focused `PipelineServiceTest`, frozen-v4 gate, automatic bundle hash, extractor
  partial-field, Room 7-to-8 migration, duplicate-fencing, and atomic rollback
  tests.

Not verified at the `fcf6614` checkpoint: emulator/device runtime, UI navigation,
accessibility, process-death presentation recovery, full unit/lint/build gates.
Its recorded next starting points were `SmsReviewRepository`,
`ReviewDetailScreen`, `GroundedReviewContent`, and `TransactionsScreen`, followed
by live output state.

Review/UI implementation commit:
`f6079ff feat(sms): complete partial evidence review`.

Verified locally for `f6079ff`:

- `./gradlew.bat :data:testDebugUnitTest --tests
  "com.pocketfinancer.data.repository.SmsReviewRepositoryV5Test"
  :app:compileDebugKotlin --no-daemon`;
- `./gradlew.bat :app:testDebugUnitTest --tests
  "com.pocketfinancer.ui.review.ReviewViewModelActionTest" --no-daemon`.

These are JVM/compile results only. Emulator navigation, accessibility, and
interaction behavior remain unverified. At the `f6079ff` checkpoint, the next
recommended starting point was live-output state and cleanup in
`AutomaticSmsProcessingActivity`, `HistoricalSmsProcessingActivity`,
`HomeSyncManager`, `HomeViewModel`, and `SmsTelemetryViewer`.

Live-output implementation commit:
`01f3861 feat(sms): show live structured generation`.

Verified locally for `01f3861`:

- focused `DefaultDirectCandidateSelectorTest`,
  `AutomaticSmsProcessingActivityTest`, `PipelineServiceTest`,
  `HistoricalSmsProcessingActivityTest`, `ManualSmsProcessingObserverTest`,
  `SmsProcessingPresentationTest`, `TrustworthyHomeStateTest`,
  `AutomaticSmsHomePresentationTest`, and
  `HistoricalSmsHomePresentationTest` via the three module
  `testDebugUnitTest` tasks;
- `./gradlew.bat :app:compileDebugAndroidTestKotlin --no-daemon`.

The compiled instrumentation coverage includes separate decoded-delta and
cumulative-output rendering, but it was not executed on an emulator. The complete
Gradle gate subsequently passed; the fresh-emulator matrix remains.
Relevant sources are `AutomaticSmsProcessingActivity`,
`HistoricalSmsProcessingActivity`, `ManualSmsProcessingObserver`,
`HomeSyncManager`, `SmsTelemetryModels`, and `SmsTelemetryViewer`.

## 2026-09-23 verification checkpoint

The legacy migration test fixture was updated to register the already-shipped
Room 7-to-8 migration when reopening its v3-upgraded store; production migration
registration and schema were unchanged. The focused
`./gradlew.bat :data:testDebugUnitTest --tests
"com.pocketfinancer.data.db.AppDatabaseMigrationTest" --no-daemon` passed.
The complete `./gradlew.bat testDebugUnitTest lintDebug assembleDebug --no-daemon`
then passed (356 actionable tasks). This is local JVM/lint/build verification,
not emulator or physical-device verification. A non-wiping cold boot restored
ADB on Pixel_9. `./gradlew.bat :app:connectedDebugAndroidTest
:data:connectedDebugAndroidTest --no-daemon` passed there: 15 Compose UI tests
and 4 encrypted-recovery tests. Directly tapping a Needs Review card opened its
specific grounded detail, and explicit Debit fallback updated the field. A
locally provisioned Qwen3-0.6B Q8_0 model completed the built-in synthetic SMS
diagnostic in 20,305 ms; with no existing account, it entered Review. These
observations do not prove the complete automatic-save or live-token path.

The original AVD contained 25 inbox messages. With owner authorization, its
exact inbox IDs were removed (25/25), and only this task's debug-app state was
reset. The existing model was re-provisioned; fresh onboarding then checked
0 messages and reached READY. No other AVD is needed. Corrected Settings,
setup-card, and diagnostic copy now describes the automatic policy; focused
`RetainedReviewImportPresentationTest` and `SettingsViewModelTest` passed, and
the complete debug unit/lint/build gate passed again (356 actionable tasks).
Pixel_9 synthetic runtime checkpoint (same AVD, debug app only): two invented
incoming messages were tried with the locally provisioned Qwen3-0.6B Q8_0
model. The app's normal debug-emulator upgrade flow then activated a locally
provisioned Qwen3-1.7B Q4_K_M model for two further invented incoming messages.
One 1.7B attempt was interrupted when an instrumentation audit moved the app
out of the foreground; this is **not** a controlled process-death recovery pass.
Commit `0f61438` adds the explicit opt-in, aggregate-only
`SmsSyntheticRuntimeAuditTest`. It reported
7 operations (5 realtime, 2 manual), 6 completed selector attempts (4 strict
evidence mismatches, 2 malformed JSON), 7 Review cases including the
interrupted operation, 0 transactions, 0 queued candidates, and 1 seeded
synthetic account. The Review extensions retained 2 valid SLM amount fields and
1 valid SLM direction field; analyzer suggestions remained separately labeled.
Neither model produced a complete valid output in this small emulator sample,
so automatic transaction persistence and duplicate fencing are still verified
only by local automated tests, **not** by the actual model/emulator path.

On Pixel_9, the active automatic inspector displayed a decoded-token delta and
a separately growing cumulative structured output (108, 174, 332, then 481
characters observed while Stage 3 remained active). Only lengths and status
were recorded; no SMS or generated text was logged. In the existing grounded
Review UI, selecting a specific Transactions card opened that case, explicit
Debit fallback changed its field, and selecting the seeded existing account
changed its account field. Confirmation correctly stayed disabled while amount
was missing. The retained partial SLM fields were then checked in the existing Review UI on
Pixel_9: one saved exception showed separate amount and direction highlights on
the unchanged synthetic SMS, with the account still unassigned. Retry and
controlled process-death recovery remain open.

The opt-in audit was compiled with `./gradlew.bat :app:assembleDebugAndroidTest
--no-daemon` and run manually with `smsSyntheticAudit=true` on Pixel_9 (`OK
(1 test)`). It is skipped in the ordinary instrumentation suite and reports
only aggregate states, reason codes, and field categories. The complete Gradle
unit/lint/build and connected suites recorded above predate this audit-only
test change; rerun them before handoff. Next: inspect one of the retained
partial-field Review cases, then run final gates and reconcile the shared
roadmap. Relevant files are
`AppDatabaseMigrationTest`, `AutomaticSmsProcessingActivity`,
`SmsReviewRepository`, `ReviewDetailScreen`, `TransactionsScreen`, and
`SmsTelemetryViewer`, plus `SmsSyntheticRuntimeAuditTest`, `SettingsScreen`,
and `SetupImportCardModel`.

## 2026-09-23 follow-up verification checkpoint

On the same original Pixel_9, a retained exception displayed separate valid SLM
amount and direction highlights on the complete unchanged synthetic SMS. The
account stayed visibly unassigned. This closes the visual partial-highlight
observation for that case, not the full accessibility and editing matrix.

One additional synthetic incoming alert matching the frozen prompt example was
processed by the locally provisioned Qwen3-1.7B Q4_K_M. It reached Review with
malformed JSON, not Transactions. The opt-in aggregate audit now reports 8
operations (6 realtime, 2 manual), 7 invalid completions (4 strict evidence
mismatches, 3 malformed JSON), 8 Review cases including the earlier interrupted
operation, 0 transactions, and 1 account. Its aggregate-only output diagnostics
found three short, open JSON fragments among the malformed outputs. Among four
readable posted objects, only two amount and one direction spans were exact;
account evidence was absent from the source in three and offset in one. These
are local model/runtime observations, not a reason to relax strict grounding.
The audit never emits SMS, generated text, IDs, or per-row predictions.

The opt-in audit now adds aggregate JSON-shape and scalar-span categories.
Its updated Android test APK compiled, and the opt-in audit passed on Pixel_9
(1 test). A separate deterministic connected SQLCipher test verified rollback
before settlement, successful atomic automatic persistence, encrypted reopen,
and retry duplicate fencing. The complete data connected suite passed there
(5 tests). This is device execution of a synthetic host/store fixture; the
real model has still not produced a valid automatic save. The retry and
controlled recovery results are recorded below.

### Retry and controlled recovery on Pixel_9

A Retry extraction action in the existing Review UI created one parent-linked
retry operation for the same source/event and reused its Review case. Its model
output was malformed. The aggregate audit rose from 8 to 9 operations and stayed
at 8 Review cases, with 0 transactions. This verifies retry lineage and case
reuse on the emulator, not a successful model-driven save.

A controlled kill of the debug app process during a subsequent synthetic
Qwen3-1.7B inference caused WorkManager to resume in a new process. The
pre-fix audit rose from 9 to 11 operations and from 8 to 10 Review cases for
one source: recovery had created one interrupted case and the replay had
created a second malformed-output case. The cause was that automatic replay
creates a fresh operation without a parent link, while Review reuse only
looked up the current or parent operation. The store now reuses an unedited
open v5 Review case for the same source and leaves a newer operation's reason
and partial evidence intact when an older claim is recovered. It does not
change stored v1-v4 release behavior or merge user-edited cases.

Both replay orders and protection of a user-edited draft passed isolated
encrypted SQLCipher tests on Pixel_9, including close/reopen, one case for each
unedited source, current-operation selection, and v2 extension preservation. After installing the fix without wiping the debug
app, a new synthetic alert was interrupted during active inference. The app
restarted under a new process and completed its replay. The opt-in audit rose
from 11 to 13 operations but only from 10 to 11 Review cases; interrupted
operations rose from 2 to 3 and invalid completions from 9 to 10. The new
Review reason was malformed JSON; the older interrupted claim did not replace
it. The one duplicate case pair produced by the earlier build remains in
this synthetic emulator data; the fix protects new unedited v5 cases.

Across these sampled local-model attempts, the audit found six short, open
unreadable JSON outputs and four readable posted objects with strict evidence
mismatches. The frozen runtime requests grammar-constrained greedy decoding
with a 512-token answer limit; the native API does not persist which stop
condition ended a completion. A count-only log check found no retained
context-limit warnings. These observations do not establish whether early
end-of-generation or the token cap caused each open fragment. Grounding and
automatic-save gates were unchanged. Neither sampled Qwen model produced a
complete valid posted result; 0 model-driven transactions were saved.

After the code and test changes, the full
`./gradlew.bat testDebugUnitTest lintDebug assembleDebug --no-daemon` gate
passed (356 actionable tasks). Pixel_9 passed 15 app UI tests, 8 encrypted
data tests, and 2 pipeline connected tests; the opt-in audit skipped in the
ordinary app suite and separately passed with `smsSyntheticAudit=true`
(1 test). The deterministic connected SQLCipher fixture verifies an atomic
automatic save, rollback, encrypted reopen, and retry duplicate fencing at
the store layer. Actual model-driven automatic saving and runtime duplicate
fencing remain unverified on the emulator. Full Review accessibility and a
physical Android device remain unverified.
