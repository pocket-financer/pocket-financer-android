package com.pocketfinancer.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sms_admitted_sources",
    indices = [
        Index(value = ["sourceConnector", "sourceMessageId"], unique = true),
        Index(value = ["sourceConnector", "sourceProviderMessageId"]),
        Index(value = ["retentionState"])
    ]
)
data class AdmittedSmsSourceEntity(
    @PrimaryKey val id: String,
    val sourceConnector: String,
    val sourceMessageId: String,
    val sourceProviderMessageId: String?,
    val sourceFingerprint: String,
    val sourceAlternateFingerprint: String?,
    val sender: String,
    val rawMessage: String,
    val sourceTimestamp: Long?,
    val messageType: Int?,
    val origin: String,
    val admissionReceiptId: String,
    val admittedAt: Long,
    val retentionState: String,
    val deletionEpoch: Long = 0
)

@Entity(
    tableName = "sms_source_metadata_events",
    indices = [Index(value = ["sourceId", "sequence"], unique = true)]
)
data class SmsSourceMetadataEventEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val sequence: Int,
    val kind: String,
    val payloadJson: String,
    val occurredAt: Long
)

@Entity(
    tableName = "sms_processing_operations",
    indices = [
        Index(value = ["sourceId", "createdAt"]),
        Index(value = ["state"]),
        Index(value = ["parentOperationId"])
    ]
)
data class SmsProcessingOperationEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val parentOperationId: String?,
    val stableEventId: String,
    val trigger: String,
    val configurationJson: String,
    val configurationHash: String,
    val contractReleaseId: String,
    val state: String,
    val transitionSequence: Long,
    val ownerToken: String?,
    val ownerGeneration: Long,
    val claimExpiresAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val settledAt: Long?,
    val settlementReceiptJson: String?,
    val deletionEpoch: Long
)

@Entity(
    tableName = "sms_processing_analyses",
    indices = [Index(value = ["operationId"], unique = true)]
)
data class SmsProcessingAnalysisEntity(
    @PrimaryKey val id: String,
    val operationId: String,
    val analysisId: String,
    val contractVersion: String,
    val sourceHash: String,
    val configurationHash: String,
    val canonicalJson: String,
    val createdAt: Long
)

@Entity(
    tableName = "sms_selector_attempts",
    indices = [Index(value = ["operationId", "attemptIndex"], unique = true)]
)
data class SmsSelectorAttemptEntity(
    @PrimaryKey val id: String,
    val operationId: String,
    val attemptIndex: Int,
    val runtimeProfileJson: String,
    val requestJson: String,
    val rawOutput: String?,
    val outputByteCount: Int?,
    val completion: String,
    val validatedSelectionJson: String?,
    val safeErrorCode: String?,
    val startedAt: Long,
    val completedAt: Long?
)

@Entity(
    tableName = "sms_processing_trace_events",
    indices = [Index(value = ["operationId", "sequence"], unique = true)]
)
data class SmsProcessingTraceEventEntity(
    @PrimaryKey val id: String,
    val operationId: String,
    val sequence: Long,
    val occurredAt: Long,
    val stage: String,
    val status: String,
    val reasonCodesJson: String,
    val detailJson: String?,
    val previousEventHash: String?,
    val eventHash: String
)

@Entity(
    tableName = "sms_reconstructed_results",
    indices = [
        Index(value = ["operationId"], unique = true),
        Index(value = ["transactionFingerprint"])
    ]
)
data class SmsReconstructedResultEntity(
    @PrimaryKey val id: String,
    val operationId: String,
    val contractVersion: String,
    val recognitionDecision: String,
    val semanticResultJson: String?,
    val transactionFingerprint: String? = null,
    val createdAt: Long
)

@Entity(
    tableName = "sms_persistence_decisions",
    indices = [Index(value = ["operationId"], unique = true)]
)
data class SmsPersistenceDecisionEntity(
    @PrimaryKey val id: String,
    val operationId: String,
    val result: String,
    val primaryReason: String,
    val checksJson: String,
    val accountResolutionJson: String,
    val rolloutMode: String,
    val createdAt: Long
)

@Entity(
    tableName = "sms_review_cases",
    indices = [
        Index(value = ["sourceId"]),
        Index(value = ["currentOperationId"]),
        Index(value = ["state", "updatedAt"])
    ]
)
data class SmsReviewCaseEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val currentOperationId: String,
    val state: String,
    val revision: Long,
    val reasonCodesJson: String,
    val draftJson: String?,
    val stableEventIdsJson: String,
    val createdAt: Long,
    val updatedAt: Long
)

/** Additive review-case/2 data; review-case/1 rows remain byte-for-byte intact. */
@Entity(
    tableName = "sms_review_case_v2_extensions",
    indices = [Index(value = ["operationId"], unique = true)]
)
data class SmsReviewCaseV2ExtensionEntity(
    @PrimaryKey val reviewCaseId: String,
    val operationId: String,
    val contractVersion: String,
    val furthestStage: String,
    val analyzerSuggestionsJson: String,
    val fieldEvidenceJson: String,
    val createdAt: Long
)

@Entity(
    tableName = "sms_user_feedback_events",
    indices = [
        Index(value = ["reviewCaseId", "resultingReviewRevision"], unique = true),
        Index(value = ["transactionId", "resultingReviewRevision"], unique = true)
    ]
)
data class SmsUserFeedbackEventEntity(
    @PrimaryKey val actionId: String,
    val reviewCaseId: String?,
    val operationId: String?,
    val transactionId: String?,
    val transactionRevisionId: String?,
    val expectedReviewRevision: Long,
    val resultingReviewRevision: Long,
    val action: String,
    val actorClass: String,
    val actorIdHash: String,
    val correctionsJson: String,
    val retryConfiguration: String?,
    val canonicalLabelId: String?,
    val canonicalLabelRevision: Long?,
    val previousEventHash: String?,
    val eventHash: String,
    val createdAt: Long
)

@Entity(
    tableName = "transaction_revisions",
    indices = [
        Index(value = ["transactionId", "revision"], unique = true),
        Index(value = ["sourceId", "stableEventId", "revision"], unique = true)
    ]
)
data class TransactionRevisionEntity(
    @PrimaryKey val id: String,
    val transactionId: String,
    val sourceId: String,
    val stableEventId: String,
    val revision: Long,
    val previousRevisionId: String?,
    val operationId: String?,
    val feedbackActionId: String?,
    val exactMinorUnits: Long?,
    val currencyCode: String?,
    val currencyScale: Int?,
    val direction: String?,
    val merchant: String?,
    val accountId: String?,
    val occurredAt: Long?,
    val provenance: String,
    val isCurrentProjection: Boolean,
    val createdAt: Long
)

@Entity(
    tableName = "account_aliases",
    indices = [
        Index(value = ["accountId"]),
        Index(value = ["normalizedAliasHash", "matchingScope"])
    ]
)
data class AccountAliasEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val normalizedAliasHash: String,
    val aliasKind: String,
    val matchingScope: String,
    val confirmedByUser: Boolean,
    val createdAt: Long
)

@Entity(tableName = "legacy_transaction_snapshots")
data class LegacyTransactionSnapshotEntity(
    @PrimaryKey val transactionId: String,
    val legacyAmount: Double,
    val merchant: String,
    val occurredAt: Long,
    val direction: String,
    val accountId: String,
    val rawMessage: String,
    val sender: String,
    val wasEdited: Boolean,
    val originalEditHistoryKnown: Boolean,
    val capturedAt: Long
)

@Entity(tableName = "sms_trace_import_receipts")
data class SmsTraceImportReceiptEntity(
    @PrimaryKey val transferId: String,
    val manifestHash: String,
    val sourcePlatform: String,
    val consentedAt: Long,
    val importedAt: Long,
    val operationCount: Int,
    val provenance: String
)
