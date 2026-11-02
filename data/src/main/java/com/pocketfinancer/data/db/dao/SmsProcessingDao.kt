package com.pocketfinancer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.pocketfinancer.data.db.entity.AdmittedSmsSourceEntity
import com.pocketfinancer.data.db.entity.SmsPersistenceDecisionEntity
import com.pocketfinancer.data.db.entity.SmsProcessingAnalysisEntity
import com.pocketfinancer.data.db.entity.SmsProcessingOperationEntity
import com.pocketfinancer.data.db.entity.SmsProcessingTraceEventEntity
import com.pocketfinancer.data.db.entity.SmsReconstructedResultEntity
import com.pocketfinancer.data.db.entity.SmsReviewCaseEntity
import com.pocketfinancer.data.db.entity.SmsReviewCaseV2ExtensionEntity
import com.pocketfinancer.data.db.entity.SmsSelectorAttemptEntity
import com.pocketfinancer.data.db.entity.SmsSourceMetadataEventEntity
import com.pocketfinancer.data.db.entity.SmsTraceImportReceiptEntity
import com.pocketfinancer.data.db.entity.SmsUserFeedbackEventEntity

@Dao
interface SmsProcessingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSource(source: AdmittedSmsSourceEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSourceMetadata(event: SmsSourceMetadataEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOperation(operation: SmsProcessingOperationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAnalysis(analysis: SmsProcessingAnalysisEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSelectorAttempt(attempt: SmsSelectorAttemptEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTraceEvent(event: SmsProcessingTraceEventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReconstructedResult(result: SmsReconstructedResultEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPersistenceDecision(decision: SmsPersistenceDecisionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReviewCase(reviewCase: SmsReviewCaseEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReviewCaseV2Extension(extension: SmsReviewCaseV2ExtensionEntity)

    @Update
    suspend fun updateReviewCase(reviewCase: SmsReviewCaseEntity): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFeedbackEvent(event: SmsUserFeedbackEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTraceImportReceipt(receipt: SmsTraceImportReceiptEntity): Long

    @Query("SELECT * FROM sms_admitted_sources WHERE id = :sourceId")
    suspend fun getSource(sourceId: String): AdmittedSmsSourceEntity?

    @Query("SELECT * FROM sms_processing_operations WHERE id = :operationId")
    suspend fun getOperation(operationId: String): SmsProcessingOperationEntity?

    @Query("SELECT * FROM sms_processing_analyses WHERE operationId = :operationId LIMIT 1")
    suspend fun getAnalysis(operationId: String): SmsProcessingAnalysisEntity?

    @Query("SELECT * FROM sms_selector_attempts WHERE operationId = :operationId ORDER BY attemptIndex DESC LIMIT 1")
    suspend fun getLatestSelectorAttempt(operationId: String): SmsSelectorAttemptEntity?

    @Query("SELECT * FROM sms_reconstructed_results WHERE operationId = :operationId LIMIT 1")
    suspend fun getReconstructedResult(operationId: String): SmsReconstructedResultEntity?

    @Query(
        "SELECT COUNT(*) FROM sms_reconstructed_results " +
            "WHERE operationId != :operationId AND transactionFingerprint = :fingerprint"
    )
    suspend fun countMatchingTransactionFingerprints(
        operationId: String,
        fingerprint: String
    ): Int

    @Query(
        "SELECT COUNT(*) FROM sms_processing_operations " +
            "WHERE id != :operationId AND state = 'persisted' " +
            "AND (sourceId = :sourceId OR stableEventId = :stableEventId)"
    )
    suspend fun countPersistedSourceEvents(
        operationId: String,
        sourceId: String,
        stableEventId: String
    ): Int

    @Query(
        "SELECT COUNT(*) FROM sms_processing_operations " +
            "WHERE id != :operationId AND state != 'discarded' " +
            "AND (sourceId = :sourceId OR stableEventId = :stableEventId)"
    )
    suspend fun countMatchingSourceEvents(
        operationId: String,
        sourceId: String,
        stableEventId: String
    ): Int

    @Query("SELECT * FROM sms_persistence_decisions WHERE operationId = :operationId LIMIT 1")
    suspend fun getPersistenceDecision(operationId: String): SmsPersistenceDecisionEntity?

    @Query("SELECT * FROM sms_review_cases WHERE id = :reviewCaseId")
    suspend fun getReviewCase(reviewCaseId: String): SmsReviewCaseEntity?

    @Query("SELECT * FROM sms_review_case_v2_extensions WHERE reviewCaseId = :reviewCaseId")
    suspend fun getReviewCaseV2Extension(reviewCaseId: String): SmsReviewCaseV2ExtensionEntity?

    @Query("SELECT * FROM sms_review_cases WHERE currentOperationId = :operationId LIMIT 1")
    suspend fun getReviewCaseForOperation(operationId: String): SmsReviewCaseEntity?

    @Query(
        """
        SELECT review.* FROM sms_review_cases AS review
        JOIN sms_processing_operations AS operation
          ON operation.id = review.currentOperationId
        WHERE review.sourceId = :sourceId
          AND review.state = 'open'
          AND review.revision = 0
          AND review.draftJson IS NULL
          AND operation.contractReleaseId IN ('native-integration-v5', 'native-integration-v6')
        ORDER BY operation.createdAt DESC, review.updatedAt DESC, review.id DESC
        LIMIT 1
        """
    )
    suspend fun getUneditedOpenV5ReviewCaseForSource(sourceId: String): SmsReviewCaseEntity?

    @Query("SELECT * FROM sms_user_feedback_events WHERE actionId = :actionId")
    suspend fun getFeedbackByAction(actionId: String): SmsUserFeedbackEventEntity?

    @Query(
        "SELECT * FROM sms_user_feedback_events " +
            "WHERE reviewCaseId = :reviewCaseId ORDER BY resultingReviewRevision DESC LIMIT 1"
    )
    suspend fun getLatestFeedback(reviewCaseId: String): SmsUserFeedbackEventEntity?

    @Query(
        "SELECT * FROM sms_user_feedback_events " +
            "WHERE reviewCaseId = :reviewCaseId ORDER BY resultingReviewRevision ASC"
    )
    suspend fun getFeedbackHistory(reviewCaseId: String): List<SmsUserFeedbackEventEntity>

    @Query(
        "SELECT * FROM sms_user_feedback_events " +
            "WHERE transactionId = :transactionId ORDER BY resultingReviewRevision DESC LIMIT 1"
    )
    suspend fun getLatestTransactionFeedback(transactionId: String): SmsUserFeedbackEventEntity?

    @Query(
        "SELECT * FROM sms_processing_trace_events " +
            "WHERE operationId = :operationId ORDER BY sequence ASC"
    )
    suspend fun getTrace(operationId: String): List<SmsProcessingTraceEventEntity>

    @Query(
        "SELECT * FROM sms_review_cases " +
            "WHERE state IN ('open', 'draft', 'waiting_retry') ORDER BY updatedAt ASC"
    )
    suspend fun getOpenReviewCases(): List<SmsReviewCaseEntity>

    @Query(
        "SELECT * FROM sms_processing_operations " +
            "WHERE settledAt IS NULL ORDER BY updatedAt DESC"
    )
    suspend fun getActiveOperations(): List<SmsProcessingOperationEntity>

    @Query(
        """
        SELECT * FROM sms_processing_operations
        WHERE settledAt IS NULL
          AND updatedAt <= :staleBefore
          AND (ownerToken IS NULL OR claimExpiresAt IS NULL OR claimExpiresAt <= :now)
        ORDER BY createdAt ASC, id ASC
        """
    )
    suspend fun getRecoverableOperations(
        staleBefore: Long,
        now: Long
    ): List<SmsProcessingOperationEntity>

    @Query(
        """
        UPDATE sms_processing_operations
        SET state = 'claimed',
            transitionSequence = transitionSequence + 1,
            ownerToken = :ownerToken,
            ownerGeneration = ownerGeneration + 1,
            claimExpiresAt = :expiresAt,
            updatedAt = :now
        WHERE id = :operationId
          AND settledAt IS NULL
          AND (
            ownerToken IS NULL
            OR ownerToken = :ownerToken
            OR claimExpiresAt IS NULL
            OR claimExpiresAt <= :now
          )
        """
    )
    suspend fun claim(
        operationId: String,
        ownerToken: String,
        now: Long,
        expiresAt: Long
    ): Int

    @Query(
        """
        UPDATE sms_processing_operations
        SET claimExpiresAt = :expiresAt,
            updatedAt = :now
        WHERE id = :operationId
          AND ownerToken = :ownerToken
          AND ownerGeneration = :ownerGeneration
          AND settledAt IS NULL
        """
    )
    suspend fun heartbeat(
        operationId: String,
        ownerToken: String,
        ownerGeneration: Long,
        now: Long,
        expiresAt: Long
    ): Int

    @Query(
        """
        UPDATE sms_processing_operations
        SET state = :nextState,
            transitionSequence = transitionSequence + 1,
            updatedAt = :now
        WHERE id = :operationId
          AND state = :expectedState
          AND ownerToken = :ownerToken
          AND ownerGeneration = :ownerGeneration
          AND claimExpiresAt >= :now
          AND settledAt IS NULL
        """
    )
    suspend fun transition(
        operationId: String,
        ownerToken: String,
        ownerGeneration: Long,
        expectedState: String,
        nextState: String,
        now: Long
    ): Int

    @Query(
        """
        UPDATE sms_processing_operations
        SET ownerGeneration = ownerGeneration + 1,
            ownerToken = NULL,
            claimExpiresAt = NULL,
            state = 'interrupted',
            transitionSequence = transitionSequence + 1,
            updatedAt = :now
        WHERE id = :operationId AND settledAt IS NULL
        """
    )
    suspend fun fenceAndInterrupt(operationId: String, now: Long): Int

    @Query(
        """
        UPDATE sms_processing_operations
        SET ownerGeneration = ownerGeneration + 1,
            ownerToken = NULL,
            claimExpiresAt = NULL,
            state = 'interrupted',
            transitionSequence = transitionSequence + 1,
            updatedAt = :now
        WHERE id = :operationId
          AND ownerGeneration = :observedGeneration
          AND settledAt IS NULL
          AND updatedAt <= :staleBefore
          AND (ownerToken IS NULL OR claimExpiresAt IS NULL OR claimExpiresAt <= :now)
        """
    )
    suspend fun fenceRecoverable(
        operationId: String,
        observedGeneration: Long,
        staleBefore: Long,
        now: Long
    ): Int

    @Query(
        """
        UPDATE sms_processing_operations
        SET state = 'retain_review',
            transitionSequence = transitionSequence + 1,
            ownerToken = NULL,
            claimExpiresAt = NULL,
            settledAt = :now,
            settlementReceiptJson = :receiptJson,
            updatedAt = :now
        WHERE id = :operationId
          AND ownerToken = :ownerToken
          AND ownerGeneration = :ownerGeneration
          AND claimExpiresAt >= :now
          AND settledAt IS NULL
        """
    )
    suspend fun settleToReview(
        operationId: String,
        ownerToken: String,
        ownerGeneration: Long,
        receiptJson: String,
        now: Long
    ): Int

    @Query(
        """
        UPDATE sms_processing_operations
        SET state = 'discarded',
            transitionSequence = transitionSequence + 1,
            ownerToken = NULL,
            claimExpiresAt = NULL,
            settledAt = :now,
            settlementReceiptJson = :receiptJson,
            updatedAt = :now
        WHERE id = :operationId
          AND ownerToken = :ownerToken
          AND ownerGeneration = :ownerGeneration
          AND claimExpiresAt >= :now
          AND settledAt IS NULL
        """
    )
    suspend fun settleDiscarded(
        operationId: String,
        ownerToken: String,
        ownerGeneration: Long,
        receiptJson: String,
        now: Long
    ): Int

    @Query(
        """
        UPDATE sms_processing_operations
        SET state = 'persisted',
            transitionSequence = transitionSequence + 1,
            ownerToken = NULL,
            claimExpiresAt = NULL,
            settledAt = :now,
            settlementReceiptJson = :receiptJson,
            updatedAt = :now
        WHERE id = :operationId
          AND ownerToken = :ownerToken
          AND ownerGeneration = :ownerGeneration
          AND claimExpiresAt >= :now
          AND settledAt IS NULL
        """
    )
    suspend fun settlePersisted(
        operationId: String,
        ownerToken: String,
        ownerGeneration: Long,
        receiptJson: String,
        now: Long
    ): Int

    @Query(
        """
        UPDATE sms_admitted_sources
        SET rawMessage = '', sender = '', retentionState = 'terminally_discarded',
            deletionEpoch = deletionEpoch + 1
        WHERE id = :sourceId AND deletionEpoch = :expectedDeletionEpoch
        """
    )
    suspend fun eraseTerminalSource(sourceId: String, expectedDeletionEpoch: Long): Int

    @Query(
        """
        UPDATE sms_review_cases
        SET state = :state,
            revision = :resultingRevision,
            draftJson = :draftJson,
            updatedAt = :now
        WHERE id = :reviewCaseId AND revision = :expectedRevision
        """
    )
    suspend fun updateReviewRevision(
        reviewCaseId: String,
        expectedRevision: Long,
        resultingRevision: Long,
        state: String,
        draftJson: String?,
        now: Long
    ): Int

    @Transaction
    suspend fun insertFeedbackAndAdvanceReview(
        event: SmsUserFeedbackEventEntity,
        state: String,
        draftJson: String?,
        now: Long
    ): Boolean {
        val reviewCaseId = requireNotNull(event.reviewCaseId) {
            "Review feedback must reference a review case"
        }
        getFeedbackByAction(event.actionId)?.let { return true }
        if (insertFeedbackEvent(event) == -1L) return false
        check(
            updateReviewRevision(
                reviewCaseId = reviewCaseId,
                expectedRevision = event.expectedReviewRevision,
                resultingRevision = event.resultingReviewRevision,
                state = state,
                draftJson = draftJson,
                now = now
            ) == 1
        ) { "Review revision changed before feedback settlement" }
        return true
    }
}
