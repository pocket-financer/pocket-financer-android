package com.pocketfinancer.data.repository

import androidx.room.withTransaction
import com.pocketfinancer.data.db.AppDatabase
import com.pocketfinancer.data.db.dao.SmsProcessingDao
import com.pocketfinancer.data.db.entity.AdmittedSmsSourceEntity
import com.pocketfinancer.data.db.entity.SmsPersistenceDecisionEntity
import com.pocketfinancer.data.db.entity.SmsProcessingAnalysisEntity
import com.pocketfinancer.data.db.entity.SmsProcessingOperationEntity
import com.pocketfinancer.data.db.entity.SmsProcessingTraceEventEntity
import com.pocketfinancer.data.db.entity.SmsReconstructedResultEntity
import com.pocketfinancer.data.db.entity.SmsReviewCaseEntity
import com.pocketfinancer.data.db.entity.SmsReviewCaseV2ExtensionEntity
import com.pocketfinancer.data.db.entity.SmsSelectorAttemptEntity
import com.pocketfinancer.data.db.entity.TransactionEntity
import com.pocketfinancer.data.db.entity.TransactionRevisionEntity
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class SmsOperationClaim(
    val operationId: String,
    val ownerToken: String,
    val ownerGeneration: Long,
    val expiresAt: Long
)

data class SmsTraceReceipt(
    val eventId: String,
    val sequence: Long,
    val eventHash: String
)

data class SmsStopReceipt(
    val operationId: String,
    val reviewCaseId: String?,
    val state: String,
    val committed: Boolean
)

data class SmsSourceEvidence(
    val sourceId: String,
    val admissionReceiptId: String,
    val sender: String,
    val body: String,
    val sourceFingerprint: String,
    val sourceTimestamp: Long?,
    val admittedAt: Long,
    val deletionEpoch: Long
)

data class SmsReviewRetryContext(
    val reviewCaseId: String,
    val source: AdmittedSmsSourceEntity,
    val operation: SmsProcessingOperationEntity
)

data class SmsDuplicateMatchCounts(
    val persistedSourceEvents: Int,
    val matchingSourceEvents: Int,
    val matchingTransactionFingerprints: Int
)

data class SmsReviewV2Evidence(
    val furthestStage: String,
    val analyzerSuggestionsJson: String,
    val fieldEvidenceJson: String
)

data class SmsAutomaticTransactionInput(
    val amountMinorUnits: Long,
    val currency: String,
    val direction: String,
    val counterparty: String?,
    val accountId: String,
    val occurredAtEpochMs: Long,
    val timestampProvenance: String,
    val modelIdentifier: String,
    val processingResultJson: String,
    val transactionFingerprint: String,
    val checksJson: String,
    val accountResolutionJson: String
)

data class SmsAutomaticPersistenceReceipt(
    val transactionId: String,
    val revisionId: String
)

class SmsAutomaticPersistenceConflict(message: String) : IllegalStateException(message)

class SmsProcessingStoreException(message: String) : IllegalStateException(message)

@Singleton
class SmsProcessingStore @Inject constructor(
    private val database: AppDatabase,
    private val dao: SmsProcessingDao
) {
    suspend fun admitSource(source: AdmittedSmsSourceEntity): Boolean {
        require(source.id.isNotBlank())
        require(source.rawMessage.toByteArray(Charsets.UTF_8).size <= 16_384)
        require(source.sender.toByteArray(Charsets.UTF_8).size <= 512)
        val inserted = dao.insertSource(source) != -1L
        val stored = dao.getSource(source.id)
            ?: throw SmsProcessingStoreException("Admitted SMS source disappeared")
        check(
            stored.sourceConnector == source.sourceConnector &&
                stored.sourceMessageId == source.sourceMessageId &&
                stored.sourceFingerprint == source.sourceFingerprint
        ) { "Admitted SMS source identity conflict" }
        return inserted
    }

    suspend fun sourceEvidence(sourceId: String): SmsSourceEvidence {
        val source = dao.getSource(sourceId)
            ?: throw SmsProcessingStoreException("Admitted SMS source not found")
        return SmsSourceEvidence(
            source.id,
            source.admissionReceiptId,
            source.sender,
            source.rawMessage,
            source.sourceFingerprint,
            source.sourceTimestamp,
            source.admittedAt,
            source.deletionEpoch
        )
    }

    suspend fun settledReceipt(operationId: String): Pair<String, String?>? {
        val operation = dao.getOperation(operationId) ?: return null
        return operation.settledAt?.let { operation.state to operation.settlementReceiptJson }
    }

    suspend fun reviewCaseIdForOperation(operationId: String): String? =
        dao.getReviewCaseForOperation(operationId)?.id

    suspend fun reviewV2Extension(reviewCaseId: String): SmsReviewCaseV2ExtensionEntity? =
        dao.getReviewCaseV2Extension(reviewCaseId)

    suspend fun retryContext(reviewCaseId: String): SmsReviewRetryContext {
        val review = dao.getReviewCase(reviewCaseId)
            ?: throw SmsProcessingStoreException("Review case not found")
        val source = dao.getSource(review.sourceId)
            ?: throw SmsProcessingStoreException("Admitted SMS source not found")
        val operation = dao.getOperation(review.currentOperationId)
            ?: throw SmsProcessingStoreException("Processing operation not found")
        return SmsReviewRetryContext(review.id, source, operation)
    }

    /**
     * Converts abandoned operation snapshots into visible review cases. The
     * generation-aware fence cannot steal a refreshed or newly claimed owner.
     */
    suspend fun recoverExpiredOperations(now: Long = System.currentTimeMillis()): Int =
        database.withTransaction {
            val staleBefore = now - CLAIM_LEASE_MS
            dao.getRecoverableOperations(staleBefore, now).count { operation ->
                if (
                    dao.fenceRecoverable(
                        operation.id,
                        operation.ownerGeneration,
                        staleBefore,
                        now
                    ) != 1
                ) {
                    false
                } else {
                    retainInterruptedOperation(
                        operation,
                        "operation_interrupted",
                        now
                    )
                    true
                }
            }
        }

    suspend fun createOperation(operation: SmsProcessingOperationEntity) {
        require(UUID.fromString(operation.id).toString() == operation.id.lowercase())
        require(UUID.fromString(operation.stableEventId).toString() == operation.stableEventId.lowercase())
        require(operation.configurationHash.matches(SHA256_REGEX))
        database.withTransaction {
            check(dao.getSource(operation.sourceId) != null) { "Admitted SMS source is missing" }
            dao.getOperation(operation.id)?.let { existing ->
                check(
                    existing.sourceId == operation.sourceId &&
                        existing.configurationHash == operation.configurationHash
                ) { "Operation identity was reused with different immutable input" }
                return@withTransaction
            }
            dao.insertOperation(operation)
        }
    }

    suspend fun claim(operationId: String, now: Long): SmsOperationClaim {
        val ownerToken = UUID.randomUUID().toString()
        val expiresAt = now + CLAIM_LEASE_MS
        check(dao.claim(operationId, ownerToken, now, expiresAt) == 1) {
            "SMS operation is already owned"
        }
        val operation = dao.getOperation(operationId)
            ?: throw SmsProcessingStoreException("Claimed operation disappeared")
        return SmsOperationClaim(
            operationId = operationId,
            ownerToken = ownerToken,
            ownerGeneration = operation.ownerGeneration,
            expiresAt = expiresAt
        )
    }

    suspend fun heartbeat(claim: SmsOperationClaim, now: Long): SmsOperationClaim {
        val expiresAt = now + CLAIM_LEASE_MS
        check(
            dao.heartbeat(
                claim.operationId,
                claim.ownerToken,
                claim.ownerGeneration,
                now,
                expiresAt
            ) == 1
        ) { "SMS operation ownership was lost" }
        return claim.copy(expiresAt = expiresAt)
    }

    suspend fun transition(
        claim: SmsOperationClaim,
        expectedState: String,
        nextState: String,
        now: Long
    ) {
        check(
            dao.transition(
                operationId = claim.operationId,
                ownerToken = claim.ownerToken,
                ownerGeneration = claim.ownerGeneration,
                expectedState = expectedState,
                nextState = nextState,
                now = now
            ) == 1
        ) { "SMS operation transition lost ownership or state" }
    }

    suspend fun appendTrace(
        claim: SmsOperationClaim,
        stage: String,
        status: String,
        reasonCodes: List<String> = emptyList(),
        detailJson: String? = null,
        now: Long
    ): SmsTraceReceipt = database.withTransaction {
        require(stage in TRACE_STAGES)
        require(status in TRACE_STATUSES)
        require(reasonCodes.size == reasonCodes.distinct().size)
        requireOwned(claim, now)
        val existing = dao.getTrace(claim.operationId)
        val sequence = existing.size.toLong()
        check(existing.withIndex().all { (index, event) -> event.sequence == index.toLong() }) {
            "SMS trace sequence is corrupt"
        }
        val previousHash = existing.lastOrNull()?.eventHash
        val eventId = UUID.randomUUID().toString()
        val payload = canonicalTracePayload(
            sequence = sequence,
            eventId = eventId,
            occurredAt = now,
            stage = stage,
            status = status,
            reasonCodes = reasonCodes,
            detailJson = detailJson,
            previousHash = previousHash
        )
        val eventHash = sha256(payload)
        dao.insertTraceEvent(
            SmsProcessingTraceEventEntity(
                id = eventId,
                operationId = claim.operationId,
                sequence = sequence,
                occurredAt = now,
                stage = stage,
                status = status,
                reasonCodesJson = jsonArray(reasonCodes),
                detailJson = detailJson,
                previousEventHash = previousHash,
                eventHash = eventHash
            )
        )
        SmsTraceReceipt(eventId, sequence, eventHash)
    }

    suspend fun recordAnalysis(
        claim: SmsOperationClaim,
        analysisId: String,
        contractVersion: String,
        sourceHash: String,
        configurationHash: String,
        canonicalJson: String,
        now: Long
    ) = database.withTransaction {
        requireOwned(claim, now)
        if (dao.getAnalysis(claim.operationId) == null) {
            dao.insertAnalysis(
                SmsProcessingAnalysisEntity(
                    id = UUID.randomUUID().toString(),
                    operationId = claim.operationId,
                    analysisId = analysisId,
                    contractVersion = contractVersion,
                    sourceHash = sourceHash,
                    configurationHash = configurationHash,
                    canonicalJson = canonicalJson,
                    createdAt = now
                )
            )
        }
    }

    suspend fun recordSelectorAttempt(
        claim: SmsOperationClaim,
        runtimeProfileJson: String,
        requestJson: String,
        rawOutput: String?,
        completion: String,
        validatedSelectionJson: String?,
        safeErrorCode: String?,
        startedAt: Long,
        completedAt: Long
    ) = database.withTransaction {
        requireOwned(claim, completedAt)
        val existing = dao.getLatestSelectorAttempt(claim.operationId)
        check(existing == null) { "Only one selector attempt is permitted per operation" }
        dao.insertSelectorAttempt(
            SmsSelectorAttemptEntity(
                id = UUID.randomUUID().toString(),
                operationId = claim.operationId,
                attemptIndex = 0,
                runtimeProfileJson = runtimeProfileJson,
                requestJson = requestJson,
                rawOutput = rawOutput,
                outputByteCount = rawOutput?.toByteArray(Charsets.UTF_8)?.size,
                completion = completion,
                validatedSelectionJson = validatedSelectionJson,
                safeErrorCode = safeErrorCode,
                startedAt = startedAt,
                completedAt = completedAt
            )
        )
    }

    suspend fun recordReconstruction(
        claim: SmsOperationClaim,
        semanticResultJson: String,
        now: Long,
        contractVersion: String = "pocketfinancer.processing-result/2",
        recognitionDecision: String = "posted",
        transactionFingerprint: String? = null
    ) = database.withTransaction {
        requireOwned(claim, now)
        if (dao.getReconstructedResult(claim.operationId) == null) {
            dao.insertReconstructedResult(
                SmsReconstructedResultEntity(
                    id = UUID.randomUUID().toString(),
                    operationId = claim.operationId,
                    contractVersion = contractVersion,
                    recognitionDecision = recognitionDecision,
                    semanticResultJson = semanticResultJson,
                    transactionFingerprint = transactionFingerprint,
                    createdAt = now
                )
            )
        }
    }

    suspend fun duplicateMatchCounts(
        operationId: String,
        sourceId: String,
        stableEventId: String,
        transactionFingerprint: String
    ): SmsDuplicateMatchCounts = SmsDuplicateMatchCounts(
        persistedSourceEvents = dao.countPersistedSourceEvents(
            operationId, sourceId, stableEventId
        ),
        matchingSourceEvents = dao.countMatchingSourceEvents(
            operationId, sourceId, stableEventId
        ),
        matchingTransactionFingerprints = dao.countMatchingTransactionFingerprints(
            operationId, transactionFingerprint
        )
    )

    suspend fun recordGateDecision(
        claim: SmsOperationClaim,
        result: String,
        primaryReason: String,
        checksJson: String,
        accountResolutionJson: String,
        rolloutMode: String,
        now: Long
    ) = database.withTransaction {
        requireOwned(claim, now)
        if (dao.getPersistenceDecision(claim.operationId) == null) {
            dao.insertPersistenceDecision(
                SmsPersistenceDecisionEntity(
                    id = UUID.randomUUID().toString(),
                    operationId = claim.operationId,
                    result = result,
                    primaryReason = primaryReason,
                    checksJson = checksJson,
                    accountResolutionJson = accountResolutionJson,
                    rolloutMode = rolloutMode,
                    createdAt = now
                )
            )
        }
    }

    /**
     * Writes the ledger projection, immutable revision, processing evidence,
     * gate decision, and operation settlement in one Room transaction. The
     * optional hook is a test-only fault boundary and executes before commit.
     */
    suspend fun persistEligibleTransaction(
        claim: SmsOperationClaim,
        input: SmsAutomaticTransactionInput,
        now: Long,
        beforeSettlement: suspend () -> Unit = {}
    ): SmsAutomaticPersistenceReceipt = database.withTransaction {
        val operation = requireOwned(claim, now)
        val source = dao.getSource(operation.sourceId)
            ?: throw SmsProcessingStoreException("Admitted SMS source not found")
        require(input.amountMinorUnits > 0)
        val currency = input.currency.uppercase()
        val scale = CurrencyScaleRegistry.scale(currency)
            ?: throw SmsProcessingStoreException("Unsupported transaction currency")
        require(input.direction in setOf("debit", "credit"))
        require(input.occurredAtEpochMs >= 0L)
        require(input.transactionFingerprint.matches(SHA256_REGEX))
        check(database.accountDao().getById(input.accountId) != null) {
            "Resolved account disappeared before persistence"
        }
        if (database.transactionDao().getBySourceEvent(source.id, operation.stableEventId) != null) {
            throw SmsAutomaticPersistenceConflict("Source event already owns a transaction")
        }

        val transactionId = UUID.nameUUIDFromBytes(
            "${source.id}|${operation.stableEventId}".toByteArray(Charsets.UTF_8)
        ).toString()
        val revisionId = UUID.nameUUIDFromBytes(
            "${operation.id}|automatic-revision-0".toByteArray(Charsets.UTF_8)
        ).toString()
        val transaction = TransactionEntity(
            id = transactionId,
            amount = BigDecimal.valueOf(input.amountMinorUnits)
                .movePointLeft(scale).toDouble(),
            merchant = input.counterparty ?: "Unspecified counterparty",
            date = input.occurredAtEpochMs,
            type = input.direction,
            accountId = input.accountId,
            rawMessage = source.rawMessage,
            sender = source.sender,
            isEdited = false,
            createdAt = now,
            updatedAt = now,
            slmModelName = input.modelIdentifier,
            sourceConnector = source.sourceConnector,
            sourceProviderMessageId = source.sourceProviderMessageId,
            sourceMessageId = source.sourceMessageId,
            sourceFingerprint = source.sourceFingerprint,
            sourceAlternateFingerprint = source.sourceAlternateFingerprint,
            sourceId = source.id,
            sourceEventId = operation.stableEventId,
            exactMinorUnits = input.amountMinorUnits,
            currencyCode = currency,
            currencyScale = scale,
            currencyProvenance = "extractor_source_span",
            timestampProvenance = "${input.timestampProvenance}_read_only",
            currentRevisionId = revisionId,
            projectionState = "current",
            legacyPrecisionStatus = "exact_minor_units"
        )
        if (database.transactionDao().insert(transaction) == -1L) {
            throw SmsAutomaticPersistenceConflict("Transaction projection conflict")
        }
        database.transactionRevisionDao().insertRevision(
            TransactionRevisionEntity(
                id = revisionId,
                transactionId = transactionId,
                sourceId = source.id,
                stableEventId = operation.stableEventId,
                revision = 0,
                previousRevisionId = null,
                operationId = operation.id,
                feedbackActionId = null,
                exactMinorUnits = input.amountMinorUnits,
                currencyCode = currency,
                currencyScale = scale,
                direction = input.direction,
                merchant = transaction.merchant,
                accountId = input.accountId,
                occurredAt = input.occurredAtEpochMs,
                provenance = "automatic_grounded_extractor",
                isCurrentProjection = true,
                createdAt = now
            )
        )
        check(dao.getReconstructedResult(operation.id) == null) {
            "Processing result was already recorded before automatic persistence"
        }
        dao.insertReconstructedResult(
            SmsReconstructedResultEntity(
                id = UUID.randomUUID().toString(),
                operationId = operation.id,
                contractVersion = "pocketfinancer.processing-result/3",
                recognitionDecision = "posted",
                semanticResultJson = input.processingResultJson,
                transactionFingerprint = input.transactionFingerprint,
                createdAt = now
            )
        )
        check(dao.getPersistenceDecision(operation.id) == null) {
            "Persistence gate was already recorded before automatic persistence"
        }
        dao.insertPersistenceDecision(
            SmsPersistenceDecisionEntity(
                id = UUID.randomUUID().toString(),
                operationId = operation.id,
                result = "persist",
                primaryReason = "persistence_eligible",
                checksJson = input.checksJson,
                accountResolutionJson = input.accountResolutionJson,
                rolloutMode = "automatic",
                createdAt = now
            )
        )
        beforeSettlement()
        val receiptJson = "{" +
            "\"revision_id\":${JSONObject.quote(revisionId)}," +
            "\"transaction_ids\":[${JSONObject.quote(transactionId)}]}"
        check(
            dao.settlePersisted(
                operation.id,
                claim.ownerToken,
                claim.ownerGeneration,
                receiptJson,
                now
            ) == 1
        ) { "SMS operation lost ownership before transaction settlement" }
        SmsAutomaticPersistenceReceipt(transactionId, revisionId)
    }

    suspend fun retainForReview(
        claim: SmsOperationClaim,
        reasons: List<String>,
        now: Long,
        v2Evidence: SmsReviewV2Evidence? = null
    ): String = database.withTransaction {
        val operation = requireOwned(claim, now)
        val existing = dao.getReviewCaseForOperation(operation.id)
            ?: operation.parentOperationId?.let { dao.getReviewCaseForOperation(it) }
            ?: reusableV5Review(operation)
        val reviewCase = when {
            existing == null -> SmsReviewCaseEntity(
                id = UUID.randomUUID().toString(),
                sourceId = operation.sourceId,
                currentOperationId = operation.id,
                state = "open",
                revision = 0,
                reasonCodesJson = jsonArray(reasons),
                draftJson = null,
                stableEventIdsJson = jsonArray(listOf(operation.stableEventId)),
                createdAt = now,
                updatedAt = now
            ).also { check(dao.insertReviewCase(it) != -1L) }
            existing.currentOperationId == operation.id -> existing
            shouldPromoteReview(existing, operation) -> existing.copy(
                currentOperationId = operation.id,
                state = "open",
                reasonCodesJson = jsonArray(reasons),
                stableEventIdsJson = mergedReviewEventIds(existing, operation.stableEventId),
                updatedAt = now
            ).also { check(dao.updateReviewCase(it) == 1) }
            else -> existing
        }
        if (operation.contractReleaseId in setOf("native-integration-v5", "native-integration-v6") &&
            reviewCase.currentOperationId == operation.id) {
            val evidence = v2Evidence ?: SmsReviewV2Evidence(
                furthestStage = stageForState(operation.state),
                analyzerSuggestionsJson = "[]",
                fieldEvidenceJson = "[]"
            )
            dao.upsertReviewCaseV2Extension(
                SmsReviewCaseV2ExtensionEntity(
                    reviewCaseId = reviewCase.id,
                    operationId = operation.id,
                    contractVersion = "pocketfinancer.review-case/2",
                    furthestStage = evidence.furthestStage,
                    analyzerSuggestionsJson = evidence.analyzerSuggestionsJson,
                    fieldEvidenceJson = evidence.fieldEvidenceJson,
                    createdAt = now
                )
            )
        }
        val receipt = "{" +
            "\"reasons\":${jsonArray(reasons)}," +
            "\"review_case_id\":${JSONObject.quote(reviewCase.id)}}"
        check(
            dao.settleToReview(
                operationId = operation.id,
                ownerToken = claim.ownerToken,
                ownerGeneration = claim.ownerGeneration,
                receiptJson = receipt,
                now = now
            ) == 1
        ) { "SMS operation lost ownership before review settlement" }
        reviewCase.id
    }

    suspend fun settleDiscarded(
        claim: SmsOperationClaim,
        reason: String,
        now: Long
    ) = database.withTransaction {
        val operation = requireOwned(claim, now)
        check(dao.eraseTerminalSource(operation.sourceId, operation.deletionEpoch) == 1) {
            "SMS source deletion epoch changed before terminal discard"
        }
        val receipt = "{\"reason\":${JSONObject.quote(reason)}}"
        check(
            dao.settleDiscarded(
                operation.id,
                claim.ownerToken,
                claim.ownerGeneration,
                receipt,
                now
            ) == 1
        ) { "SMS operation lost ownership before terminal discard" }
    }

    suspend fun requestStop(operationId: String, now: Long): SmsStopReceipt =
        database.withTransaction {
            val operation = dao.getOperation(operationId)
                ?: throw SmsProcessingStoreException("SMS operation not found")
            if (operation.settledAt != null) {
                return@withTransaction SmsStopReceipt(
                    operationId,
                    dao.getReviewCaseForOperation(operationId)?.id,
                    operation.state,
                    operation.state == "persisted"
                )
            }
            check(dao.fenceAndInterrupt(operationId, now) == 1)
            val reviewCaseId = retainInterruptedOperation(operation, "operation_interrupted", now)
            SmsStopReceipt(operationId, reviewCaseId, "interrupted", false)
        }

    /**
     * Makes an unowned invalid/interrupted operation visible without stealing
     * a still-live owner. The operation remains recoverable rather than being
     * represented by a fabricated review identifier.
     */
    suspend fun retainUnownedForReview(
        operationId: String,
        reason: String,
        now: Long
    ): String? = database.withTransaction {
        val operation = dao.getOperation(operationId) ?: return@withTransaction null
        dao.getReviewCaseForOperation(operationId)?.let { return@withTransaction it.id }
        if (operation.settledAt != null) return@withTransaction null
        if (operation.ownerToken != null && (operation.claimExpiresAt ?: Long.MIN_VALUE) > now) {
            return@withTransaction null
        }
        check(dao.fenceAndInterrupt(operationId, now) == 1)
        retainInterruptedOperation(operation, reason, now)
    }

    private suspend fun retainInterruptedOperation(
        operation: SmsProcessingOperationEntity,
        reason: String,
        now: Long
    ): String {
        val existing = dao.getReviewCaseForOperation(operation.id)
            ?: operation.parentOperationId?.let { dao.getReviewCaseForOperation(it) }
            ?: reusableV5Review(operation)
        if (existing != null) {
            if (!shouldPromoteReview(existing, operation)) return existing.id
            val updated = existing.copy(
                currentOperationId = operation.id,
                state = "open",
                reasonCodesJson = jsonArray(listOf(reason)),
                stableEventIdsJson = mergedReviewEventIds(existing, operation.stableEventId),
                updatedAt = now
            )
            check(dao.updateReviewCase(updated) == 1)
            retainEmptyV2ExtensionIfNeeded(updated.id, operation, now)
            return updated.id
        }
        val review = SmsReviewCaseEntity(
            id = UUID.randomUUID().toString(),
            sourceId = operation.sourceId,
            currentOperationId = operation.id,
            state = "open",
            revision = 0,
            reasonCodesJson = jsonArray(listOf(reason)),
            draftJson = null,
            stableEventIdsJson = jsonArray(listOf(operation.stableEventId)),
            createdAt = now,
            updatedAt = now
        )
        check(dao.insertReviewCase(review) != -1L)
        retainEmptyV2ExtensionIfNeeded(review.id, operation, now)
        return review.id
    }

    private suspend fun reusableV5Review(
        operation: SmsProcessingOperationEntity
    ): SmsReviewCaseEntity? = if (operation.contractReleaseId in setOf("native-integration-v5", "native-integration-v6")) {
        dao.getUneditedOpenV5ReviewCaseForSource(operation.sourceId)
    } else {
        null
    }

    private suspend fun shouldPromoteReview(
        existing: SmsReviewCaseEntity,
        operation: SmsProcessingOperationEntity
    ): Boolean {
        if (existing.currentOperationId == operation.id) return true
        val current = dao.getOperation(existing.currentOperationId) ?: return false
        return operation.createdAt >= current.createdAt
    }

    private fun mergedReviewEventIds(
        existing: SmsReviewCaseEntity,
        stableEventId: String
    ): String {
        val stored = JSONArray(existing.stableEventIdsJson)
        val ids = (0 until stored.length()).map { stored.getString(it) }
        return jsonArray((ids + stableEventId).distinct().sorted())
    }

    private suspend fun retainEmptyV2ExtensionIfNeeded(
        reviewCaseId: String,
        operation: SmsProcessingOperationEntity,
        now: Long
    ) {
        if (operation.contractReleaseId !in setOf("native-integration-v5", "native-integration-v6")) return
        dao.upsertReviewCaseV2Extension(
            SmsReviewCaseV2ExtensionEntity(
                reviewCaseId = reviewCaseId,
                operationId = operation.id,
                contractVersion = "pocketfinancer.review-case/2",
                furthestStage = stageForState(operation.state),
                analyzerSuggestionsJson = "[]",
                fieldEvidenceJson = "[]",
                createdAt = now
            )
        )
    }

    private fun stageForState(state: String): String = when (state) {
        "ready", "claimed" -> "configuration"
        "analyzed", "triaged" -> "analysis_advisory"
        "selector_running" -> "extractor_execution"
        "selector_recorded", "validated" -> "extractor_validation"
        "reconstructed" -> "normalization"
        else -> "settlement"
    }

    private suspend fun requireOwned(
        claim: SmsOperationClaim,
        now: Long
    ): SmsProcessingOperationEntity {
        val operation = dao.getOperation(claim.operationId)
            ?: throw SmsProcessingStoreException("SMS operation not found")
        check(
            operation.ownerToken == claim.ownerToken &&
                operation.ownerGeneration == claim.ownerGeneration &&
                (operation.claimExpiresAt ?: Long.MIN_VALUE) >= now &&
                operation.settledAt == null
        ) { "SMS operation ownership was lost" }
        return operation
    }

    companion object {
        const val CLAIM_LEASE_MS = 120_000L
        const val CLAIM_HEARTBEAT_MS = 15_000L
        private val SHA256_REGEX = Regex("[0-9a-f]{64}")
        private val TRACE_STAGES = setOf(
            "admission",
            "configuration",
            "claim",
            "analysis",
            "triage",
            "selector_execution",
            "selector_validation",
            "reconstruction",
            "account_resolution",
            "persistence_gate",
            "settlement",
            "review",
            "recovery",
            "erase"
        )
        private val TRACE_STATUSES = setOf(
            "pending",
            "running",
            "completed",
            "skipped",
            "failed",
            "interrupted",
            "retained"
        )

        private fun canonicalTracePayload(
            sequence: Long,
            eventId: String,
            occurredAt: Long,
            stage: String,
            status: String,
            reasonCodes: List<String>,
            detailJson: String?,
            previousHash: String?
        ): String = "{" +
            "\"detail\":${detailJson ?: "null"}," +
            "\"event_id\":${JSONObject.quote(eventId)}," +
            "\"occurred_at_epoch_ms\":$occurredAt," +
            "\"previous_event_hash\":${previousHash?.let(JSONObject::quote) ?: "null"}," +
            "\"reason_codes\":${jsonArray(reasonCodes)}," +
            "\"sequence\":$sequence," +
            "\"stage\":${JSONObject.quote(stage)}," +
            "\"status\":${JSONObject.quote(status)}}"

        internal fun jsonArray(values: List<String>): String =
            values.joinToString(prefix = "[", postfix = "]", separator = ",") {
                JSONObject.quote(it)
            }

        fun sha256(value: String): String = MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
