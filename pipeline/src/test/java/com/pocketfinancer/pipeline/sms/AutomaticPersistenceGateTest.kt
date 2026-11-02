package com.pocketfinancer.pipeline.sms

import com.pocketfinancer.data.repository.GroundedAccountResolution
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Test

class AutomaticPersistenceGateTest {
    private val gate = AutomaticPersistenceGate()

    @Test
    fun `eligible evidence remains blocked while rollout is shadow`() {
        val decision = evaluate(operation = operation(rolloutMode = "shadow"))

        assertEquals(PersistenceGateResult.BLOCKED_BY_MODE, decision.result)
        assertEquals("persistence_blocked_by_rollout_mode", decision.primaryReason)
    }

    @Test
    fun `eligible evidence can pass only when automatic rollout is explicit`() {
        val decision = evaluate(operation = operation(rolloutMode = "automatic"))

        assertEquals(PersistenceGateResult.ELIGIBLE, decision.result)
        assertEquals("persistence_all_gates_passed", decision.primaryReason)
    }

    @Test
    fun `stale ownership invalidates the operation`() {
        val decision = evaluate(
            operation = operation(rolloutMode = "automatic"),
            claimOwnershipCurrent = false
        )

        assertEquals(PersistenceGateResult.INVALID_OPERATION, decision.result)
        assertEquals("persistence_claim_ownership_invalid", decision.primaryReason)
    }

    @Test
    fun `multiple direction candidates cannot masquerade as one clause event`() {
        val first = directionCandidate("dir-1", "clause-1")
        val second = directionCandidate("dir-2", "clause-1")
        val analysis = analysisFixture(
            directionCandidates = listOf(first, second),
            completedEventCount = 1
        )
        val decision = evaluate(
            operation = operation(rolloutMode = "automatic"),
            analysis = analysis
        )

        assertEquals(PersistenceGateResult.MULTIPLE_EVENTS, decision.result)
        assertFalse(decision.checks.single { it.check == "single_completed_event" }.passed)
    }

    @Test
    fun `family is derived from the selected direction clause only`() {
        val selected = directionCandidate("dir-1", "clause-2")
        val analysis = analysisFixture(
            directionCandidates = listOf(selected),
            annotations = listOf(
                annotation("clause-1", "card_purchase"),
                annotation("clause-2", "loan_offer")
            )
        )
        val decision = evaluate(
            operation = operation(rolloutMode = "automatic"),
            analysis = analysis
        )

        assertEquals(PersistenceGateResult.REVIEW_REQUIRED, decision.result)
        assertEquals("persistence_financial_family_not_supported", decision.primaryReason)
    }

    @Test
    fun `non-posted conflict cue requires review even with a posted selection`() {
        val evidence = evidence("pending")
        val analysis = analysisFixture().copy(
            cues = listOf(
                SmsCue(
                    id = "cue-1",
                    kind = "pending",
                    clauseId = "clause-1",
                    evidence = evidence,
                    reasonCode = "pending_event"
                )
            )
        )
        val decision = evaluate(
            operation = operation(rolloutMode = "automatic"),
            analysis = analysis
        )

        assertEquals(PersistenceGateResult.REVIEW_REQUIRED, decision.result)
        assertEquals("persistence_conflicting_non_posted_evidence", decision.primaryReason)
    }

    private fun evaluate(
        operation: SmsOperationSnapshot,
        analysis: SmsAnalysis = analysisFixture(),
        claimOwnershipCurrent: Boolean = true
    ): PersistenceGateDecision = gate.evaluate(
        analysis = analysis,
        triage = SmsTriageDecision(
            disposition = SmsStorageDisposition.INVOKE,
            selectorAction = SmsSelectorAction.RUN_NORMAL,
            reasonCodes = listOf("invoke_grounded_single_event")
        ),
        selection = GroundedSelectorResult(
            decision = SelectorDecision.POSTED,
            posted = SelectorPostedSelection(
                amountCandidateId = "amount-1",
                directionCandidateId = "dir-1",
                accountCandidateId = "account-1",
                counterpartyCandidateId = "counterparty-1"
            )
        ),
        transaction = ReconstructedSmsTransaction(
            analysisId = analysis.analysisId,
            stableEventId = "stable-event-1",
            minorUnits = 12_345,
            currency = "INR",
            currencyScale = 2,
            currencyProvenance = "explicit_code",
            direction = "debit",
            accountEvidence = "account ending 1234",
            counterpartyEvidence = "merchant",
            occurredAtEpochMs = 1_700_000_000_000,
            timestampProvenance = "acquisition_supplied_message_time"
        ),
        accountResolution = GroundedAccountResolution.UniquelyResolved(
            accountId = "account-id",
            matchedAliasHash = "a".repeat(64),
            provenance = "confirmed_owned_account_alias_v1"
        ),
        operation = operation,
        claimOwnershipCurrent = claimOwnershipCurrent
    )

    private fun analysisFixture(
        directionCandidates: List<SmsCandidate> = listOf(directionCandidate("dir-1", "clause-1")),
        completedEventCount: Int = 1,
        annotations: List<SmsClauseAnnotation> = listOf(annotation("clause-1", "card_purchase"))
    ) = SmsAnalysis(
        analysisId = "analysis-1",
        configurationHash = "configuration-hash",
        sourceHash = "source-hash",
        source = "INR 123.45 debited from account ending 1234",
        clauses = listOf(SmsClause("clause-1", evidence("message"))),
        candidates = directionCandidates,
        cues = emptyList(),
        reasonCodes = emptyList(),
        completedEventCount = completedEventCount,
        profileId = "india",
        primaryCurrency = "INR",
        normalizedStructuralFingerprint = "fingerprint",
        currencyContextHash = "currency-context-hash",
        sourceTimestampEpochMs = 1_700_000_000_000,
        sourceTimestampProvenance = "acquisition_supplied_message_time",
        unicodeDatabaseVersion = "15.0",
        clauseAnnotations = annotations
    )

    private fun directionCandidate(id: String, clauseId: String) = SmsCandidate(
        id = id,
        kind = SmsCandidateKind.DIRECTION,
        clauseId = clauseId,
        evidence = evidence("debited"),
        explicitAbsence = false,
        value = mapOf("direction" to "debit"),
        context = emptyList()
    )

    private fun annotation(clauseId: String, family: String) = SmsClauseAnnotation(
        clauseId = clauseId,
        states = listOf("posted"),
        financialFamilies = listOf(SmsFinancialFamily(family, evidence(family)))
    )

    private fun evidence(text: String) = SmsEvidenceSpan(0, text.length, 0, text.length, text)

    private fun operation(rolloutMode: String) = SmsOperationSnapshot(
        operationId = "operation-1",
        parentOperationId = null,
        stableEventId = "stable-event-1",
        configuration = SmsOperationConfiguration(
            operationId = "operation-1",
            parentOperationId = null,
            sourceId = "source-1",
            sourceRefHash = "b".repeat(64),
            trigger = "diagnostic",
            createdAtEpochMs = 1_700_000_000_000,
            primaryCurrency = "INR",
            enabledProfiles = listOf("core-en", "india"),
            sourceTimestampEpochMs = 1_700_000_000_000,
            sourceTimestampProvenance = "acquisition_supplied_message_time",
            admissionTimestampEpochMs = 1_700_000_000_000,
            timezoneId = "UTC",
            releaseManifestHash = "c".repeat(64),
            currencyAssetHash = "d".repeat(64),
            profileAssetHashes = emptyMap(),
            selectorEligible = true,
            selectorIneligibilityReason = null,
            selectorModelId = "selector-model",
            selectorModelHash = "e".repeat(64),
            selectorRuntimeVersion = "runtime",
            osVersion = "android",
            deviceCohort = "test",
            promptHash = "f".repeat(64),
            rolloutMode = rolloutMode
        ),
        configurationJson = "{}",
        configurationHash = "configuration-hash"
    )
}
