# Pocket Financer Android development instructions

Read `AGENTS.md` first. For SMS work, also read
`docs/sms-processing-next-steps.md` and the canonical documents in the sibling
`pocket-financer-intelligence` repository:

- `docs/architecture/SMS_PROCESSING_ARCHITECTURE.md`
- `docs/plans/CROSS_PLATFORM_SMS_ROADMAP.md`
- `docs/plans/SMS_EVALUATION_STRATEGY.md`
- `docs/contracts/NATIVE_SMS_INTEGRATION_CONTRACT.md`

## SMS architecture

The local SLM is the central classifier and extractor. Deterministic SMS analysis
is advisory evidence, not a terminal allowlist. The host owns strict parsing,
Unicode-scalar grounding, exact minor units, account resolution, duplicates,
receipt time, persistence, durable operation ownership, review, and recovery.

The current native v4 path is review-only. Complete valid v4 results still go to
Review. Direct-to-Transactions routing is planned for an additive successor
contract and must not be retrofitted into frozen v1-v4 behavior.

## Repository responsibilities

```text
:app        Compose navigation, Transactions/Review, processing transparency
:pipeline   SMS orchestration, shared-contract adapters, validation and routing
:inference  process-wide llama.cpp/JNI runtime and decoded-token callbacks
:data       SQLCipher Room state, migrations, transactions, review and feedback
:sms        broadcast/outbox and inbox discovery
:hardware   device capability and file-backed model selection
```

Production inference callers use the process-wide `SlmRuntime`/
`SlmRuntimeCoordinator` boundary; they do not access `LlamaEngine` directly.
Automatic SMS intake remains opt-in. Raw financial evidence stays encrypted and
local.

## Review and transparency

Review shows the complete immutable SMS with accessible field highlights and one
active native selection. Confirmation is atomic. Corrections append local,
revision-bound feedback and do not silently become training labels.

While generation is active, show real decoded token deltas and cumulative
structured output as observable runtime data. Keep advisory analysis, model
output, validation, route, persistence, and later correction distinct. Never
invent or expose chain of thought, and never log private SMS/model text.

## Model identity

Android is file-backed. An eligible v4 operation records the real SHA-256 of the
GGUF bytes it used. Never use a placeholder or a hash of a model name/path.
System-managed-runtime provenance is for platforms without a readable artifact.

## Build and verification

Run from Windows PowerShell with the repository Gradle wrapper. Start with focused
module tests, then run the complete applicable unit, instrumentation, lint, and
build gates. Emulator evidence is not physical-device evidence. Existing dated
reports are historical and must not be presented as proof that a current
observation is fixed.

Useful commands and emulator mechanics remain in `docs/emulator_testing.md`.
The active SMS acceptance sequence is in `docs/sms-processing-next-steps.md`.
