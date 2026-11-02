package com.pocketfinancer.pipeline.sms

import com.pocketfinancer.inference.DirectCandidateSelectorResult
import com.pocketfinancer.inference.SlmModelSpec

data class AdmittedMessageRef(
    val sourceId: String,
    val admissionReceiptId: String,
    val sourceDigest: String
)

data class SmsOperationConfiguration(
    val contract: String = "pocketfinancer.processing-config/2",
    val releaseId: String = "native-integration-v2",
    val operationId: String,
    val parentOperationId: String?,
    val sourceId: String,
    val sourceRefHash: String,
    val trigger: String,
    val createdAtEpochMs: Long,
    val primaryCurrency: String,
    val enabledProfiles: List<String>,
    val sourceTimestampEpochMs: Long?,
    val sourceTimestampProvenance: String,
    val admissionTimestampEpochMs: Long,
    val timezoneId: String,
    val releaseManifestHash: String,
    val currencyAssetHash: String,
    val profileAssetHashes: Map<String, String>,
    val selectorEligible: Boolean,
    val selectorIneligibilityReason: String?,
    val selectorModelId: String,
    val selectorModelHash: String?,
    val selectorRuntimeVersion: String,
    val osVersion: String,
    val deviceCohort: String,
    val promptHash: String,
    val rolloutMode: String = "shadow",
    val generationMode: String = "DIRECT_NON_THINKING",
    val decoding: String = "greedy",
    val answerTokenLimit: Int = 512,
    val rawOutputByteLimit: Int = 16_384,
    val parserDeadlineMs: Long = 0
)

data class SmsOperationSnapshot(
    val operationId: String,
    val parentOperationId: String?,
    val stableEventId: String,
    val configuration: SmsOperationConfiguration,
    val configurationJson: String,
    val configurationHash: String
)

data class SmsEvidenceSpan(
    val startCodePoint: Int,
    val endCodePoint: Int,
    val startUtf8: Int,
    val endUtf8: Int,
    val text: String
)

enum class SmsCandidateKind(val wireValue: String, val prefix: String) {
    AMOUNT("amount", "amt"),
    DIRECTION("direction", "dir"),
    ACCOUNT("account", "acc"),
    COUNTERPARTY("counterparty", "cp")
}

data class SmsClause(val id: String, val evidence: SmsEvidenceSpan)

data class SmsCandidate(
    val id: String,
    val kind: SmsCandidateKind,
    val clauseId: String?,
    val evidence: SmsEvidenceSpan?,
    val explicitAbsence: Boolean,
    val value: Map<String, String>,
    val context: List<String>
)

data class SmsCue(
    val id: String,
    val kind: String,
    val clauseId: String,
    val evidence: SmsEvidenceSpan,
    val reasonCode: String
)

data class SmsFinancialFamily(
    val family: String,
    val evidence: SmsEvidenceSpan
)

data class SmsClauseAnnotation(
    val clauseId: String,
    val states: List<String>,
    val financialFamilies: List<SmsFinancialFamily>
)

data class SmsAnalysis(
    val contract: String = "pocketfinancer.sms-analysis/2",
    val analysisId: String,
    val configurationHash: String,
    val sourceHash: String,
    val source: String,
    val clauses: List<SmsClause>,
    val candidates: List<SmsCandidate>,
    val cues: List<SmsCue>,
    val reasonCodes: List<String>,
    val completedEventCount: Int,
    val profileId: String,
    val primaryCurrency: String,
    val normalizedStructuralFingerprint: String,
    val currencyContextHash: String,
    val sourceTimestampEpochMs: Long?,
    val sourceTimestampProvenance: String,
    val unicodeDatabaseVersion: String,
    val clauseAnnotations: List<SmsClauseAnnotation>
)

enum class SelectorDecision { NONE, ABSTAIN, POSTED }

data class SelectorPostedSelection(
    val amountCandidateId: String,
    val directionCandidateId: String,
    val accountCandidateId: String,
    val counterpartyCandidateId: String
)

data class GroundedSelectorResult(
    val decision: SelectorDecision,
    val posted: SelectorPostedSelection? = null
)

data class ReconstructedSmsTransaction(
    val analysisId: String,
    val stableEventId: String,
    val minorUnits: Long,
    val currency: String,
    val currencyScale: Int,
    val currencyProvenance: String,
    val direction: String,
    val accountEvidence: String?,
    val counterpartyEvidence: String?,
    val occurredAtEpochMs: Long?,
    val timestampProvenance: String
)

enum class PersistenceGateResult(val wireValue: String) {
    ELIGIBLE("eligible"),
    REVIEW_REQUIRED("review_required"),
    NOT_POSTED("not_posted"),
    MULTIPLE_EVENTS("multiple_events"),
    BLOCKED_BY_MODE("blocked_by_mode"),
    INVALID_OPERATION("invalid_operation")
}

data class PersistenceGateCheck(
    val check: String,
    val passed: Boolean,
    val reasonCode: String?
)

data class PersistenceGateDecision(
    val result: PersistenceGateResult,
    val primaryReason: String,
    val checks: List<PersistenceGateCheck>
)

sealed interface SmsProcessingOutcome {
    val operationId: String

    data class TerminallyDiscarded(
        override val operationId: String,
        val reason: String
    ) : SmsProcessingOutcome

    data class RetainedForReview(
        override val operationId: String,
        val reviewCaseId: String,
        val reasons: List<String>
    ) : SmsProcessingOutcome

    data class Persisted(
        override val operationId: String,
        val transactionIds: List<String>,
        val alreadyCommitted: Boolean
    ) : SmsProcessingOutcome

    data class RetryableFailure(
        override val operationId: String,
        val reviewCaseId: String?,
        val reason: String,
        val retryCondition: String
    ) : SmsProcessingOutcome

    data class Stopped(
        override val operationId: String,
        val reviewCaseId: String?,
        val reason: String
    ) : SmsProcessingOutcome
}

fun interface SmsProcessingObserver {
    fun onEvent(event: SmsProcessingObserverEvent)

    companion object {
        val None = SmsProcessingObserver { }
    }
}

data class SmsProcessingObserverEvent(
    val operationId: String,
    val sequence: Long,
    val stage: String,
    val status: String,
    val reasonCodes: List<String>,
    val transient: SmsProcessingTransientEvent? = null
)

/** Process-only inference visibility. Durable truth remains the selector attempt row. */
sealed interface SmsProcessingTransientEvent {
    data class InferenceStarted(
        val model: SlmModelSpec,
        val grammarEnabled: Boolean,
        val answerTokenBudget: Int
    ) : SmsProcessingTransientEvent

    data class DecodedToken(
        val delta: String,
        val cumulativeStructuredOutput: String
    ) : SmsProcessingTransientEvent

    data class InferenceCompleted(
        val result: DirectCandidateSelectorResult
    ) : SmsProcessingTransientEvent
}
