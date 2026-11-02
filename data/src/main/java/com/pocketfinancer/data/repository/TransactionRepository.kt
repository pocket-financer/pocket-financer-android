package com.pocketfinancer.data.repository

import com.pocketfinancer.data.db.AppDatabase
import com.pocketfinancer.data.db.dao.QueuedSmsCandidateDao
import com.pocketfinancer.data.db.dao.TransactionDao
import com.pocketfinancer.data.db.entity.TransactionEntity
import com.pocketfinancer.data.db.entity.LegacyTransactionSnapshotEntity
import com.pocketfinancer.data.db.entity.SmsUserFeedbackEventEntity
import com.pocketfinancer.data.db.entity.TransactionRevisionEntity
import com.pocketfinancer.data.model.SmsSourceIdentity
import com.pocketfinancer.data.model.Transaction
import com.pocketfinancer.data.model.TransactionType
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

@Singleton
class TransactionRepository @Inject constructor(
    private val appDatabase: AppDatabase,
    private val transactionDao: TransactionDao,
    private val accountRepository: AccountRepository,
    private val candidateDao: QueuedSmsCandidateDao
) {

    data class ProjectionEditCommand(
        val actionId: String,
        val transactionId: String,
        val expectedRevisionId: String?,
        val amountText: String,
        val currencyCode: String,
        val merchant: String,
        val type: TransactionType,
        val accountId: String
    )

    data class ProjectionEditReceipt(
        val actionId: String,
        val transaction: Transaction,
        val resultingRevision: Long,
        val replayed: Boolean
    )

    suspend fun clearDatabase() {
        appDatabase.clearAllTables()
    }
    fun getAllByDateDesc(): Flow<List<Transaction>> = ledgerFlow {
        transactionDao.getAllByDateDesc()
    }

    fun getByDateRange(startMs: Long, endMs: Long): Flow<List<Transaction>> =
        ledgerFlow { transactionDao.getByDateRange(startMs, endMs) }

    fun getByType(type: TransactionType): Flow<List<Transaction>> =
        ledgerFlow { transactionDao.getByType(type.name.lowercase()) }

    fun getRecent(limit: Int = 20): Flow<List<Transaction>> =
        ledgerFlow { transactionDao.getRecent(limit) }

    private fun ledgerFlow(
        source: () -> Flow<List<TransactionEntity>>
    ): Flow<List<Transaction>> = flow {
        accountRepository.ensureInitialConsolidation()
        emitAll(source().map { list -> list.map { it.toDomain() } })
    }

    /**
     * Compatibility adapter for existing UI callers. Source uniqueness still
     * uses atomic insert-ignore semantics; an existing row is returned without
     * replacing its raw evidence.
     */
    suspend fun insert(data: NewTransaction): Transaction =
        insertIfAbsent(data).transaction

    suspend fun insertIfAbsent(data: NewTransaction): InsertResult {
        val source = data.sourceIdentity ?: SmsSourceIdentity.androidSms(
            providerMessageId = null,
            sender = data.sender,
            body = data.rawMessage,
            sourceTimestamp = data.date,
            messageType = 1
        )
        val entity = TransactionEntity(
            id = UUID.randomUUID().toString(),
            amount = data.amount,
            merchant = data.merchant,
            date = data.date,
            type = data.type.name.lowercase(),
            accountId = data.accountId,
            rawMessage = data.rawMessage,
            sender = data.sender,
            slmPromptEvalMs = data.slmPromptEvalMs,
            slmEvalMs = data.slmEvalMs,
            slmNumTokens = data.slmNumTokens,
            slmModelName = data.slmModelName,
            sourceConnector = source.connector,
            sourceProviderMessageId = source.providerMessageId,
            sourceMessageId = source.messageId,
            sourceFingerprint = source.fallbackFingerprint,
            sourceAlternateFingerprint = source.alternateFingerprint
        )
        return appDatabase.withTransaction {
            val existing = transactionDao.findBySource(
                connector = source.connector,
                messageId = source.messageId,
                providerMessageId = source.providerMessageId,
                fingerprint = source.fallbackFingerprint,
                alternateFingerprint = source.alternateFingerprint
            )
            val inserted: Boolean
            val owned = if (existing != null) {
                inserted = false
                existing
            } else {
                inserted = transactionDao.insert(entity) != -1L
                if (inserted) {
                    entity
                } else {
                    transactionDao.findBySource(
                        connector = source.connector,
                        messageId = source.messageId,
                        providerMessageId = source.providerMessageId,
                        fingerprint = source.fallbackFingerprint,
                        alternateFingerprint = source.alternateFingerprint
                    ) ?: error("Transaction source conflict could not be resolved")
                }
            }
            transactionDao.preserveSourceMetadata(
                transactionId = owned.id,
                messageId = source.messageId,
                providerMessageId = source.providerMessageId,
                fingerprint = source.fallbackFingerprint,
                alternateFingerprint = source.alternateFingerprint,
                receivedDate = data.date
            )
            candidateDao.findBySource(
                connector = source.connector,
                messageId = source.messageId,
                providerMessageId = source.providerMessageId,
                fingerprint = source.fallbackFingerprint,
                alternateFingerprint = source.alternateFingerprint
            )?.let { matchingCandidate ->
                candidateDao.deleteByKey(matchingCandidate.candidateKey)
            }
            val persisted = transactionDao.getById(owned.id)
                ?: error("Persisted transaction disappeared")
            // Convert before Room commits. If a corrupt row cannot be mapped,
            // the source handoff rolls back instead of throwing after an insert.
            InsertResult(
                transaction = persisted.toDomain(),
                inserted = inserted
            )
        }
    }

    /**
     * Applies a user-authorized ledger correction as one append-only feedback
     * event plus one immutable transaction revision. The mutable transaction
     * row is only the current projection of that history.
     */
    suspend fun editProjection(command: ProjectionEditCommand): ProjectionEditReceipt =
        appDatabase.withTransaction {
            require(UUID.fromString(command.actionId).toString() == command.actionId.lowercase())
            val processingDao = appDatabase.smsProcessingDao()
            val revisionDao = appDatabase.transactionRevisionDao()
            processingDao.getFeedbackByAction(command.actionId)?.let { replay ->
                check(replay.transactionId == command.transactionId) {
                    "Feedback action belongs to another transaction"
                }
                val replayTransaction = transactionDao.getById(command.transactionId)
                    ?: error("Edited transaction disappeared")
                return@withTransaction ProjectionEditReceipt(
                    command.actionId,
                    replayTransaction.toDomain(),
                    replay.resultingReviewRevision,
                    replayed = true
                )
            }

            val existing = transactionDao.getById(command.transactionId)
                ?: throw IllegalArgumentException("Transaction does not exist")
            check(existing.currentRevisionId == command.expectedRevisionId) {
                "Transaction revision conflict"
            }
            check(accountRepository.getById(command.accountId) != null) {
                "Selected account does not exist"
            }
            val currency = command.currencyCode.trim().uppercase()
            val scale = CurrencyScaleRegistry.scale(currency)
                ?: throw IllegalArgumentException("Unsupported currency")
            val decimal = BigDecimal(command.amountText.trim())
                .setScale(scale, RoundingMode.UNNECESSARY)
            val minorUnits = decimal.movePointRight(scale).longValueExact()
            require(minorUnits > 0) { "Amount must be positive" }
            val merchant = command.merchant.trim()
            require(merchant.isNotEmpty()) { "Merchant must not be blank" }
            val now = System.currentTimeMillis()
            val sourceId = existing.sourceId ?: existing.sourceMessageId
            val stableEventId = existing.sourceEventId ?: existing.id

            var history = revisionDao.getHistory(existing.id)
            if (history.isEmpty()) {
                val baselineId = UUID.randomUUID().toString()
                revisionDao.insertLegacySnapshot(
                    LegacyTransactionSnapshotEntity(
                        transactionId = existing.id,
                        legacyAmount = existing.amount,
                        merchant = existing.merchant,
                        occurredAt = existing.date,
                        direction = existing.type,
                        accountId = existing.accountId,
                        rawMessage = existing.rawMessage,
                        sender = existing.sender,
                        wasEdited = existing.isEdited,
                        originalEditHistoryKnown = false,
                        capturedAt = now
                    )
                )
                revisionDao.insertRevision(
                    TransactionRevisionEntity(
                        id = baselineId,
                        transactionId = existing.id,
                        sourceId = sourceId,
                        stableEventId = stableEventId,
                        revision = 0,
                        previousRevisionId = null,
                        operationId = null,
                        feedbackActionId = null,
                        exactMinorUnits = existing.exactMinorUnits,
                        currencyCode = existing.currencyCode,
                        currencyScale = existing.currencyScale,
                        direction = existing.type,
                        merchant = existing.merchant,
                        accountId = existing.accountId,
                        occurredAt = existing.date,
                        provenance = "legacy_current_state_original_history_unknown",
                        isCurrentProjection = true,
                        createdAt = now
                    )
                )
                history = revisionDao.getHistory(existing.id)
            }
            val current = history.singleOrNull { it.isCurrentProjection }
                ?: error("Transaction history has no unique current projection")
            check(existing.currentRevisionId == null || existing.currentRevisionId == current.id) {
                "Transaction projection and revision history disagree"
            }
            val revisionNumber = (history.maxOfOrNull { it.revision } ?: -1) + 1
            val revisionId = UUID.randomUUID().toString()
            val corrections = correctionJson(
                existing = existing,
                amountMinorUnits = minorUnits,
                currency = currency,
                merchant = merchant,
                type = command.type,
                accountId = command.accountId,
                previousRevisionId = current.id
            )
            require(corrections != "[]") { "No transaction fields changed" }
            val previousHash = processingDao.getLatestTransactionFeedback(existing.id)?.eventHash
            val eventHash = SmsProcessingStore.sha256(
                canonicalProjectionFeedback(
                    command,
                    revisionId,
                    revisionNumber,
                    corrections,
                    previousHash
                )
            )
            val updated = existing.copy(
                amount = decimal.toDouble(),
                merchant = merchant,
                type = command.type.name.lowercase(),
                accountId = command.accountId,
                isEdited = true,
                updatedAt = now,
                sourceId = sourceId,
                sourceEventId = stableEventId,
                exactMinorUnits = minorUnits,
                currencyCode = currency,
                currencyScale = scale,
                currencyProvenance = "user_confirmed",
                timestampProvenance = existing.timestampProvenance ?: "legacy_stored_time",
                currentRevisionId = revisionId,
                projectionState = "current",
                legacyPrecisionStatus = "exact_minor_units"
            )
            revisionDao.clearCurrentProjection(existing.id)
            revisionDao.insertRevision(
                TransactionRevisionEntity(
                    id = revisionId,
                    transactionId = existing.id,
                    sourceId = sourceId,
                    stableEventId = stableEventId,
                    revision = revisionNumber,
                    previousRevisionId = current.id,
                    operationId = null,
                    feedbackActionId = command.actionId,
                    exactMinorUnits = minorUnits,
                    currencyCode = currency,
                    currencyScale = scale,
                    direction = command.type.name.lowercase(),
                    merchant = merchant,
                    accountId = command.accountId,
                    occurredAt = existing.date,
                    provenance = "user_corrected_projection",
                    isCurrentProjection = true,
                    createdAt = now
                )
            )
            check(transactionDao.update(updated) == 1) { "Transaction projection disappeared" }
            check(
                processingDao.insertFeedbackEvent(
                    SmsUserFeedbackEventEntity(
                        actionId = command.actionId,
                        reviewCaseId = null,
                        operationId = null,
                        transactionId = existing.id,
                        transactionRevisionId = revisionId,
                        expectedReviewRevision = current.revision,
                        resultingReviewRevision = revisionNumber,
                        action = "correct",
                        actorClass = "user",
                        actorIdHash = SmsProcessingStore.sha256("local-owner"),
                        correctionsJson = corrections,
                        retryConfiguration = null,
                        canonicalLabelId = null,
                        canonicalLabelRevision = null,
                        previousEventHash = previousHash,
                        eventHash = eventHash,
                        createdAt = now
                    )
                ) != -1L
            ) { "Feedback action conflict" }
            ProjectionEditReceipt(
                command.actionId,
                updated.toDomain(),
                revisionNumber,
                replayed = false
            )
        }

    @Deprecated("Use editProjection so every correction has append-only history")
    suspend fun updateTransaction(
        id: String,
        amount: Double,
        merchant: String,
        type: TransactionType,
        accountId: String
    ): Transaction? {
        val existing = transactionDao.getById(id) ?: return null
        return editProjection(
            ProjectionEditCommand(
                actionId = UUID.randomUUID().toString(),
                transactionId = id,
                expectedRevisionId = existing.currentRevisionId,
                amountText = BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString(),
                currencyCode = existing.currencyCode ?: "INR",
                merchant = merchant,
                type = type,
                accountId = accountId
            )
        ).transaction
    }

    suspend fun sumDebitsSince(sinceMs: Long): Double =
        transactionDao.sumByTypeSince("debit", sinceMs) ?: 0.0

    suspend fun sumCreditsSince(sinceMs: Long): Double =
        transactionDao.sumByTypeSince("credit", sinceMs) ?: 0.0

    suspend fun count(): Int = transactionDao.count()

    suspend fun exists(sender: String, date: Long): Boolean =
        transactionDao.exists(sender, date)

    suspend fun exists(sourceIdentity: SmsSourceIdentity): Boolean =
        transactionDao.existsBySource(
            connector = sourceIdentity.connector,
            messageId = sourceIdentity.messageId,
            providerMessageId = sourceIdentity.providerMessageId,
            fingerprint = sourceIdentity.fallbackFingerprint,
            alternateFingerprint = sourceIdentity.alternateFingerprint
        )

    /**
     * Atomically resolves an existing source and promotes fallback-only
     * provenance when a later provider row supplies an authoritative id.
     *
     * Callers that skip parsing because a transaction already exists should
     * use this method instead of a read-only [exists] check. It also removes at
     * most one matching queued copy after the ledger owns the raw evidence.
     */
    suspend fun preserveSourceMetadataIfExists(
        sourceIdentity: SmsSourceIdentity,
        receivedDate: Long? = null
    ): Boolean = appDatabase.withTransaction {
        val existing = transactionDao.findBySource(
            connector = sourceIdentity.connector,
            messageId = sourceIdentity.messageId,
            providerMessageId = sourceIdentity.providerMessageId,
            fingerprint = sourceIdentity.fallbackFingerprint,
            alternateFingerprint = sourceIdentity.alternateFingerprint
        ) ?: return@withTransaction false
        transactionDao.preserveSourceMetadata(
            transactionId = existing.id,
            messageId = sourceIdentity.messageId,
            providerMessageId = sourceIdentity.providerMessageId,
            fingerprint = sourceIdentity.fallbackFingerprint,
            alternateFingerprint = sourceIdentity.alternateFingerprint,
            receivedDate = receivedDate
        )
        candidateDao.findBySource(
            connector = sourceIdentity.connector,
            messageId = sourceIdentity.messageId,
            providerMessageId = sourceIdentity.providerMessageId,
            fingerprint = sourceIdentity.fallbackFingerprint,
            alternateFingerprint = sourceIdentity.alternateFingerprint
        )?.let { matchingCandidate ->
            candidateDao.deleteByKey(matchingCandidate.candidateKey)
        }
        true
    }

    suspend fun findBySource(sourceIdentity: SmsSourceIdentity): Transaction? =
        transactionDao.findBySource(
            connector = sourceIdentity.connector,
            messageId = sourceIdentity.messageId,
            providerMessageId = sourceIdentity.providerMessageId,
            fingerprint = sourceIdentity.fallbackFingerprint,
            alternateFingerprint = sourceIdentity.alternateFingerprint
        )?.toDomain()

    private suspend fun TransactionEntity.toDomain(): Transaction {
        val account = accountRepository.getById(accountId)
        return Transaction(
            id = id,
            amount = amount,
            merchant = merchant,
            date = date,
            type = TransactionType.fromString(type),
            accountId = accountId,
            accountLabel = account?.name,
            rawMessage = rawMessage,
            sender = sender,
            isEdited = isEdited,
            slmPromptEvalMs = slmPromptEvalMs,
            slmEvalMs = slmEvalMs,
            slmNumTokens = slmNumTokens,
            slmModelName = slmModelName,
            sourceIdentity = SmsSourceIdentity(
                connector = sourceConnector,
                messageId = sourceMessageId,
                fallbackFingerprint = sourceFingerprint,
                providerMessageId = sourceProviderMessageId,
                alternateFingerprint = sourceAlternateFingerprint
            ),
            exactMinorUnits = exactMinorUnits,
            currencyCode = currencyCode,
            currencyScale = currencyScale,
            currentRevisionId = currentRevisionId,
            legacyPrecisionStatus = legacyPrecisionStatus
        )
    }

    private fun correctionJson(
        existing: TransactionEntity,
        amountMinorUnits: Long,
        currency: String,
        merchant: String,
        type: TransactionType,
        accountId: String,
        previousRevisionId: String
    ): String {
        val values = buildList {
            if (existing.exactMinorUnits != amountMinorUnits) {
                add("amount_minor_units" to amountMinorUnits.toString())
            }
            if (existing.currencyCode?.uppercase() != currency) {
                add("currency" to JSONObject.quote(currency))
            }
            if (existing.merchant != merchant) {
                add("counterparty" to JSONObject.quote(merchant))
            }
            if (existing.type != type.name.lowercase()) {
                add("direction" to JSONObject.quote(type.name.lowercase()))
            }
            if (existing.accountId != accountId) {
                add("account_id" to JSONObject.quote(accountId))
            }
        }
        return values.joinToString(prefix = "[", postfix = "]", separator = ",") { (field, value) ->
            "{" +
                "\"candidate_id\":null," +
                "\"classification\":\"supplied_manual_ungrounded_value\"," +
                "\"evidence\":null," +
                "\"field\":${JSONObject.quote(field)}," +
                "\"new_value\":$value," +
                "\"previous_revision_id\":${JSONObject.quote(previousRevisionId)}}"
        }
    }

    private fun canonicalProjectionFeedback(
        command: ProjectionEditCommand,
        revisionId: String,
        resultingRevision: Long,
        correctionsJson: String,
        previousHash: String?
    ): String = "{" +
        "\"action\":\"correct\"," +
        "\"action_id\":${JSONObject.quote(command.actionId)}," +
        "\"corrections\":$correctionsJson," +
        "\"expected_revision_id\":" +
        "${command.expectedRevisionId?.let(JSONObject::quote) ?: "null"}," +
        "\"previous_event_hash\":${previousHash?.let(JSONObject::quote) ?: "null"}," +
        "\"resulting_revision\":$resultingRevision," +
        "\"transaction_id\":${JSONObject.quote(command.transactionId)}," +
        "\"transaction_revision_id\":${JSONObject.quote(revisionId)}}"

    data class InsertResult(
        val transaction: Transaction,
        val inserted: Boolean
    )

    data class NewTransaction(
        val amount: Double,
        val merchant: String,
        val date: Long,
        val type: TransactionType,
        val accountId: String,
        val rawMessage: String,
        val sender: String,
        val slmPromptEvalMs: Long? = null,
        val slmEvalMs: Long? = null,
        val slmNumTokens: Int? = null,
        val slmModelName: String? = null,
        val sourceIdentity: SmsSourceIdentity? = null
    )
}
