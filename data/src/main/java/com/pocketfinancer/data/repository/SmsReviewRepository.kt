package com.pocketfinancer.data.repository

import androidx.room.withTransaction
import com.pocketfinancer.data.db.AppDatabase
import com.pocketfinancer.data.db.dao.AccountDao
import com.pocketfinancer.data.db.dao.SmsProcessingDao
import com.pocketfinancer.data.db.dao.TransactionDao
import com.pocketfinancer.data.db.dao.TransactionRevisionDao
import com.pocketfinancer.data.db.entity.TransactionEntity
import com.pocketfinancer.data.db.entity.TransactionRevisionEntity
import com.pocketfinancer.data.db.entity.SmsUserFeedbackEventEntity
import com.pocketfinancer.data.db.entity.AccountEntity
import com.pocketfinancer.data.db.entity.AccountAliasEntity
import com.pocketfinancer.data.db.entity.AdmittedSmsSourceEntity
import com.pocketfinancer.data.db.entity.SmsPersistenceDecisionEntity
import com.pocketfinancer.data.db.entity.SmsProcessingOperationEntity
import com.pocketfinancer.data.db.entity.SmsProcessingTraceEventEntity
import com.pocketfinancer.data.db.entity.SmsReconstructedResultEntity
import com.pocketfinancer.data.db.entity.SmsReviewCaseEntity
import org.json.JSONObject
import org.json.JSONTokener
import java.math.BigDecimal
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class SmsReviewAction(val wireValue: String) {
    CONFIRM("confirm"),
    CORRECT("correct"),
    REJECT("reject"),
    RESOLVE_MULTIPLE_EVENTS("resolve_multiple_events"),
    SAVE_DRAFT("save_draft"),
    RETRY("retry")
}

enum class SmsFieldGroundingClassification(val wireValue: String) {
    SELECTED_EXISTING_CANDIDATE("selected_existing_candidate"),
    CHANGED_INTERPRETATION_AMONG_CANDIDATES("changed_interpretation_among_candidates"),
    SUPPLIED_SOURCE_SUPPORTED_CANDIDATE_MISS("supplied_source_supported_candidate_miss"),
    SUPPLIED_MANUAL_UNGROUNDED_VALUE("supplied_manual_ungrounded_value")
}

data class SmsFieldCorrection(
    val field: String,
    val classification: SmsFieldGroundingClassification,
    val previousRevisionId: String?,
    val candidateId: String?,
    val evidenceJson: String?,
    val newValueJson: String
)

data class SmsReviewCommand(
    val actionId: String,
    val reviewCaseId: String,
    val expectedRevision: Long,
    val action: SmsReviewAction,
    val corrections: List<SmsFieldCorrection> = emptyList(),
    val retryConfiguration: String? = null
)

data class SmsReviewReceipt(
    val actionId: String,
    val reviewCaseId: String,
    val resultingRevision: Long,
    val replayed: Boolean
)

data class SmsReviewDetails(
    val reviewCase: SmsReviewCaseEntity,
    val source: AdmittedSmsSourceEntity,
    val operation: SmsProcessingOperationEntity,
    val trace: List<SmsProcessingTraceEventEntity>,
    val reconstructedResult: SmsReconstructedResultEntity?,
    val persistenceDecision: SmsPersistenceDecisionEntity?,
    val feedback: List<SmsUserFeedbackEventEntity>,
    val accounts: List<AccountEntity>,
    val groundedProposal: SmsReviewProposal?,
    val draftProposal: SmsReviewProposal?,
    val draftCorrections: List<SmsFieldCorrection>,
    val retainedEvidence: SmsReviewRetainedEvidence?
)

@Singleton
class SmsReviewRepository @Inject constructor(
    private val database: AppDatabase,
    private val dao: SmsProcessingDao,
    private val transactionDao: TransactionDao,
    private val revisionDao: TransactionRevisionDao,
    private val accountDao: AccountDao
) {
    suspend fun openCases(): List<SmsReviewCaseEntity> = dao.getOpenReviewCases()

    suspend fun activeOperations(): List<SmsProcessingOperationEntity> = dao.getActiveOperations()

    suspend fun details(reviewCaseId: String): SmsReviewDetails {
        val review = dao.getReviewCase(reviewCaseId)
            ?: throw SmsProcessingStoreException("Review case not found")
        val source = dao.getSource(review.sourceId)
            ?: throw SmsProcessingStoreException("Admitted SMS source not found")
        val operation = dao.getOperation(review.currentOperationId)
            ?: throw SmsProcessingStoreException("Processing operation not found")
        val reconstructed = dao.getReconstructedResult(operation.id)
        val extension = dao.getReviewCaseV2Extension(review.id)
        val proposal = SmsReviewGrounding.proposal(
            reconstructed?.semanticResultJson,
            source.rawMessage
        )
        val draftCorrections = runCatching {
            SmsReviewGrounding.correctionsFromJson(review.draftJson)
        }.getOrDefault(emptyList())
        val draftProposal = proposal?.let { base ->
            runCatching {
                SmsReviewGrounding.applyCorrections(
                    base,
                    source.rawMessage,
                    draftCorrections.filter {
                        it.evidenceJson != null || it.field == "counterparty"
                    }
                )
            }.getOrNull()
        }
        return SmsReviewDetails(
            review,
            source,
            operation,
            dao.getTrace(operation.id),
            reconstructed,
            dao.getPersistenceDecision(operation.id),
            dao.getFeedbackHistory(review.id),
            accountDao.getAllOnce(),
            proposal,
            draftProposal,
            draftCorrections,
            SmsReviewGrounding.retainedEvidence(
                extension,
                operation.configurationJson,
                source.rawMessage
            )
        )
    }

    suspend fun resolve(command: SmsReviewCommand, now: Long): SmsReviewReceipt =
        database.withTransaction {
        require(UUID.fromString(command.actionId).toString() == command.actionId.lowercase())
        dao.getFeedbackByAction(command.actionId)?.let { event ->
            check(event.reviewCaseId == command.reviewCaseId) {
                "Feedback action belongs to another review"
            }
            return@withTransaction SmsReviewReceipt(
                event.actionId,
                requireNotNull(event.reviewCaseId),
                event.resultingReviewRevision,
                replayed = true
            )
        }
        val review = dao.getReviewCase(command.reviewCaseId)
            ?: throw SmsProcessingStoreException("Review case not found")
        check(review.revision == command.expectedRevision) { "Review revision conflict" }
        validate(command)
        val resultingRevision = command.expectedRevision + 1
        val previousHash = dao.getLatestFeedback(command.reviewCaseId)?.eventHash
        val correctionsJson = correctionsJson(command.corrections)
        val transactionRevisionId = if (
            command.action == SmsReviewAction.CONFIRM || command.action == SmsReviewAction.CORRECT
        ) {
            projectReview(review.currentOperationId, command, now)
        } else {
            null
        }
        val eventHash = SmsProcessingStore.sha256(
            canonicalFeedback(
                command = command,
                operationId = review.currentOperationId,
                resultingRevision = resultingRevision,
                correctionsJson = correctionsJson,
                previousHash = previousHash
            )
        )
        val event = SmsUserFeedbackEventEntity(
            actionId = command.actionId,
            reviewCaseId = command.reviewCaseId,
            operationId = review.currentOperationId,
            transactionId = transactionRevisionId?.let { revisionId ->
                revisionDao.getById(revisionId)?.transactionId
            },
            transactionRevisionId = transactionRevisionId,
            expectedReviewRevision = command.expectedRevision,
            resultingReviewRevision = resultingRevision,
            action = command.action.wireValue,
            actorClass = "user",
            actorIdHash = SmsProcessingStore.sha256("local-owner"),
            correctionsJson = correctionsJson,
            retryConfiguration = command.retryConfiguration,
            canonicalLabelId = null,
            canonicalLabelRevision = null,
            previousEventHash = previousHash,
            eventHash = eventHash,
            createdAt = now
        )
        val state = when (command.action) {
            SmsReviewAction.CONFIRM,
            SmsReviewAction.RESOLVE_MULTIPLE_EVENTS -> "confirmed"
            SmsReviewAction.CORRECT -> "corrected"
            SmsReviewAction.REJECT -> "rejected"
            SmsReviewAction.SAVE_DRAFT -> "draft"
            SmsReviewAction.RETRY -> "waiting_retry"
        }
        val draft = if (
            command.action == SmsReviewAction.SAVE_DRAFT ||
            command.action == SmsReviewAction.CORRECT
        ) {
            correctionsJson
        } else {
            null
        }
        check(dao.insertFeedbackAndAdvanceReview(event, state, draft, now))
        SmsReviewReceipt(
            command.actionId,
            command.reviewCaseId,
            resultingRevision,
            replayed = false
        )
    }

    private suspend fun projectReview(
        operationId: String,
        command: SmsReviewCommand,
        now: Long
    ): String {
        val operation = dao.getOperation(operationId)
            ?: throw SmsProcessingStoreException("Processing operation not found")
        val source = dao.getSource(operation.sourceId)
            ?: throw SmsProcessingStoreException("Admitted SMS source not found")
        if (operation.contractReleaseId == "native-integration-v4") {
            return projectV4Review(operation, source, command, now)
        }
        if (operation.contractReleaseId in setOf("native-integration-v5", "native-integration-v6")) {
            return projectV5Review(operation, source, command, now)
        }
        val result = dao.getReconstructedResult(operationId)?.semanticResultJson
            ?.let(::JSONObject)
            ?: run {
                check(command.action == SmsReviewAction.CORRECT) { "Grounded proposal is unavailable" }
                val requiredFields = setOf("amount_minor_units", "currency", "direction",
                    "counterparty", "account_id", "occurred_at_epoch_ms")
                require(command.corrections.map { it.field }.toSet() == requiredFields) {
                    "A complete correction is required without a grounded proposal"
                }
                // This is a user projection only; never fabricate a reconstructed model result.
                JSONObject()
            }
        var minorUnits = result.optLong("minor_units", 0)
        var currency = result.optString("currency", "").uppercase(java.util.Locale.ROOT)
        var direction = result.optString("direction", "")
        var merchant = if (result.isNull("counterparty_evidence")) {
            "Unspecified counterparty"
        } else {
            result.optString("counterparty_evidence").ifBlank { "Unspecified counterparty" }
        }
        var occurredAt = result.optLong("occurred_at_epoch_ms", Long.MIN_VALUE)
            .takeUnless { it == Long.MIN_VALUE }
        var accountId = dao.getPersistenceDecision(operationId)?.accountResolutionJson
            ?.let(::JSONObject)
            ?.takeIf { it.optString("result") == "unique" }
            ?.optString("account_id")
            ?.takeIf(String::isNotBlank)
        command.corrections.forEach { correction ->
            val value = decodeScalar(correction.newValueJson)
            when (correction.field) {
                "amount_minor_units" -> minorUnits = value.exactLongOrNull()
                    ?: throw IllegalArgumentException("Corrected amount must be an integer")
                "currency" -> currency = (value as? String)?.uppercase()
                    ?: throw IllegalArgumentException("Corrected currency must be text")
                "direction" -> direction = value as? String
                    ?: throw IllegalArgumentException("Corrected direction must be text")
                "counterparty" -> merchant = (value as? String)?.trim().orEmpty()
                "account_id" -> accountId = value as? String
                "occurred_at_epoch_ms" -> occurredAt = value.exactLongOrNull()
                else -> throw IllegalArgumentException("Unsupported correction field")
            }
        }
        val scale = CurrencyScaleRegistry.scale(currency)
            ?: throw IllegalArgumentException("Unsupported corrected currency")
        require(minorUnits > 0 && direction in setOf("debit", "credit"))
        require(merchant.isNotBlank())
        val resolvedOccurredAt = occurredAt
            ?: throw IllegalArgumentException("A transaction time must be selected")
        val resolvedAccountId = accountId?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("An owned account must be selected")
        check(accountDao.getById(resolvedAccountId) != null) { "Selected account does not exist" }

        val transactionId = transactionDao.getBySourceEvent(source.id, operation.stableEventId)?.id
            ?: UUID.nameUUIDFromBytes(
                "${source.id}|${operation.stableEventId}".toByteArray(Charsets.UTF_8)
            ).toString()
        val history = revisionDao.getHistory(transactionId)
        val previous = history.firstOrNull { it.isCurrentProjection }
        val revisionId = UUID.randomUUID().toString()
        val revisionNumber = (history.maxOfOrNull { it.revision } ?: -1) + 1
        val compatibleAmount = BigDecimal.valueOf(minorUnits)
            .movePointLeft(scale)
            .toDouble()
        val existing = transactionDao.getById(transactionId)
        val projection = TransactionEntity(
            id = transactionId,
            amount = compatibleAmount,
            merchant = merchant,
            date = resolvedOccurredAt,
            type = direction,
            accountId = resolvedAccountId,
            rawMessage = source.rawMessage,
            sender = source.sender,
            isEdited = command.action == SmsReviewAction.CORRECT,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            slmModelName = "grounded-candidate-selector",
            sourceConnector = source.sourceConnector,
            sourceProviderMessageId = source.sourceProviderMessageId,
            sourceMessageId = source.sourceMessageId,
            sourceFingerprint = source.sourceFingerprint,
            sourceAlternateFingerprint = source.sourceAlternateFingerprint,
            sourceId = source.id,
            sourceEventId = operation.stableEventId,
            exactMinorUnits = minorUnits,
            currencyCode = currency,
            currencyScale = scale,
            currencyProvenance = if (command.corrections.any { it.field == "currency" }) {
                "user_supplied"
            } else result.optString("currency_provenance", "unknown"),
            timestampProvenance = if (command.corrections.any { it.field == "occurred_at_epoch_ms" }) {
                "user_corrected_time"
            } else result.optString("timestamp_provenance", "unknown"),
            currentRevisionId = revisionId,
            projectionState = "current",
            legacyPrecisionStatus = "exact_minor_units"
        )
        if (existing == null) {
            check(transactionDao.insert(projection) != -1L) { "Transaction projection conflict" }
        } else {
            check(transactionDao.update(projection) == 1) { "Transaction projection disappeared" }
        }
        revisionDao.clearCurrentProjection(transactionId)
        revisionDao.insertRevision(
            TransactionRevisionEntity(
                id = revisionId,
                transactionId = transactionId,
                sourceId = source.id,
                stableEventId = operation.stableEventId,
                revision = revisionNumber,
                previousRevisionId = previous?.id,
                operationId = operation.id,
                feedbackActionId = command.actionId,
                exactMinorUnits = minorUnits,
                currencyCode = currency,
                currencyScale = scale,
                direction = direction,
                merchant = merchant,
                accountId = resolvedAccountId,
                occurredAt = resolvedOccurredAt,
                provenance = if (command.action == SmsReviewAction.CONFIRM) {
                    "user_confirmed_grounded_proposal"
                } else {
                    "user_corrected_projection"
                },
                isCurrentProjection = true,
                createdAt = now
            )
        )
        return revisionId
    }

    private suspend fun projectV4Review(
        operation: SmsProcessingOperationEntity,
        source: AdmittedSmsSourceEntity,
        command: SmsReviewCommand,
        now: Long
    ): String {
        val configuration = JSONObject(operation.configurationJson)
        check(configuration.optString("config_hash") == operation.configurationHash) {
            "Stored configuration hash changed"
        }
        val payload = JSONObject(operation.configurationJson).apply { remove("config_hash") }
        check(SmsProcessingStore.sha256(canonicalJson(payload)) == operation.configurationHash) {
            "Stored configuration payload changed"
        }
        check(configuration.optString("contract") == "pocketfinancer.processing-config/4")
        check(configuration.getJSONObject("contract_release").optString("release_id") == "native-integration-v4")
        check(configuration.getJSONObject("persistence_policy").optString("rollout_mode") == "review_only")
        val extractor = configuration.getJSONObject("extractor")
        check(extractor.optString("model_identity_kind") == "file_sha256")
        check(extractor.optString("model_file_sha256").matches(Regex("[0-9a-f]{64}")))
        check(configuration.optString("source_ref_hash") == SmsProcessingStore.sha256(source.id)) {
            "Stored source reference changed"
        }
        val analysis = requireNotNull(dao.getAnalysis(operation.id)) {
            "Stored analysis is unavailable"
        }
        check(analysis.configurationHash == operation.configurationHash) {
            "Analysis configuration changed after extraction"
        }
        check(analysis.sourceHash == SmsProcessingStore.sha256(source.rawMessage)) {
            "SMS source changed after extraction"
        }
        val resultEntity = dao.getReconstructedResult(operation.id)
            ?: throw SmsProcessingStoreException("Grounded proposal is unavailable")
        val base = SmsReviewGrounding.proposal(resultEntity.semanticResultJson, source.rawMessage)
            ?: throw SmsProcessingStoreException("Grounded proposal is invalid")
        check(base.duplicateIdempotencyKey == source.id) {
            "Duplicate assessment source identity changed"
        }
        check(base.duplicateSourceEventKey == operation.stableEventId) {
            "Duplicate assessment event identity changed"
        }
        check(base.duplicateStatus != "already_persisted") { "This source event was already saved" }
        val allowed = setOf("amount", "direction", "account", "counterparty")
        require(command.corrections.all { it.field in allowed })
        require(command.corrections.map { it.field }.toSet().size == command.corrections.size)
        val projection = SmsReviewGrounding.applyCorrections(
            base,
            source.rawMessage,
            command.corrections
        )

        val normalizedAlias = if ('@' in projection.accountReference) {
            "vpa:${projection.accountReference}"
        } else {
            "suffix:${projection.accountReference}"
        }
        val aliasHash = SmsProcessingStore.sha256(normalizedAlias)
        val matchingAccounts = revisionDao.findConfirmedAliases(
            aliasHash,
            GroundedAccountResolver.MATCHING_SCOPE
        ).mapNotNull { accountDao.getById(it.accountId) }.distinctBy { it.id }
        check(matchingAccounts.size <= 1) { "Account reference is ambiguous" }
        val account = matchingAccounts.singleOrNull() ?: run {
            val display = if ('@' in projection.accountReference) {
                "UPI account ${projection.accountReference}"
            } else {
                "Account ••${projection.accountReference.takeLast(4)}"
            }
            AccountEntity(
                id = UUID.randomUUID().toString(),
                name = display,
                bank = source.sender.ifBlank { "Unknown Account" },
                type = "sms-review",
                createdAt = now,
                updatedAt = now
            ).also { created ->
                accountDao.insert(created)
                revisionDao.insertAccountAlias(
                    AccountAliasEntity(
                        id = UUID.randomUUID().toString(),
                        accountId = created.id,
                        normalizedAliasHash = aliasHash,
                        aliasKind = if ('@' in projection.accountReference) "vpa" else "suffix",
                        matchingScope = GroundedAccountResolver.MATCHING_SCOPE,
                        confirmedByUser = true,
                        createdAt = now
                    )
                )
            }
        }

        check(transactionDao.getBySourceEvent(source.id, operation.stableEventId) == null) {
            "This SMS event already has a transaction"
        }
        val fingerprint = SmsProcessingStore.sha256(
            "${projection.amountMinorUnits}\u0000${projection.currency}\u0000" +
                "${projection.direction}\u0000${account.id}\u0000${projection.receiptTimestampEpochMs}"
        )
        // Exact source/event duplicates are blocked above. A semantic fingerprint
        // match remains visible in review and is resolved by this explicit action.
        @Suppress("UNUSED_VARIABLE")
        val matchingFingerprints = dao.countMatchingTransactionFingerprints(operation.id, fingerprint)

        val transactionId = UUID.nameUUIDFromBytes(
            "${source.id}|${operation.stableEventId}".toByteArray(Charsets.UTF_8)
        ).toString()
        val revisionId = UUID.randomUUID().toString()
        val merchant = projection.counterparty ?: "Unspecified counterparty"
        val scale = requireNotNull(CurrencyScaleRegistry.scale(projection.currency))
        val transaction = TransactionEntity(
            id = transactionId,
            amount = BigDecimal.valueOf(projection.amountMinorUnits)
                .movePointLeft(scale).toDouble(),
            merchant = merchant,
            date = projection.receiptTimestampEpochMs,
            type = projection.direction,
            accountId = account.id,
            rawMessage = source.rawMessage,
            sender = source.sender,
            isEdited = command.corrections.isNotEmpty(),
            createdAt = now,
            updatedAt = now,
            slmModelName = "direct-sms-extractor-v4",
            sourceConnector = source.sourceConnector,
            sourceProviderMessageId = source.sourceProviderMessageId,
            sourceMessageId = source.sourceMessageId,
            sourceFingerprint = source.sourceFingerprint,
            sourceAlternateFingerprint = source.sourceAlternateFingerprint,
            sourceId = source.id,
            sourceEventId = operation.stableEventId,
            exactMinorUnits = projection.amountMinorUnits,
            currencyCode = projection.currency,
            currencyScale = scale,
            currencyProvenance = "review_source_span",
            timestampProvenance = "${projection.receiptProvenance}_read_only",
            currentRevisionId = revisionId,
            projectionState = "current",
            legacyPrecisionStatus = "exact_minor_units"
        )
        check(transactionDao.insert(transaction) != -1L) { "Transaction projection conflict" }
        revisionDao.insertRevision(
            TransactionRevisionEntity(
                id = revisionId,
                transactionId = transactionId,
                sourceId = source.id,
                stableEventId = operation.stableEventId,
                revision = 0,
                previousRevisionId = null,
                operationId = operation.id,
                feedbackActionId = command.actionId,
                exactMinorUnits = projection.amountMinorUnits,
                currencyCode = projection.currency,
                currencyScale = scale,
                direction = projection.direction,
                merchant = merchant,
                accountId = account.id,
                occurredAt = projection.receiptTimestampEpochMs,
                provenance = if (command.corrections.isEmpty()) {
                    "user_confirmed_grounded_proposal"
                } else {
                    "user_confirmed_source_span_revision"
                },
                isCurrentProjection = true,
                createdAt = now
            )
        )
        return revisionId
    }

    private suspend fun projectV5Review(
        operation: SmsProcessingOperationEntity,
        source: AdmittedSmsSourceEntity,
        command: SmsReviewCommand,
        now: Long
    ): String {
        require(command.action == SmsReviewAction.CORRECT) {
            "Automatic-policy review requires an explicit confirmed projection"
        }
        val configuration = JSONObject(operation.configurationJson)
        check(configuration.optString("config_hash") == operation.configurationHash) {
            "Stored configuration hash changed"
        }
        val payload = JSONObject(operation.configurationJson).apply { remove("config_hash") }
        check(SmsProcessingStore.sha256(canonicalJson(payload)) == operation.configurationHash) {
            "Stored configuration payload changed"
        }
        val releaseId = configuration.getJSONObject("contract_release").optString("release_id")
        check(
            (releaseId == "native-integration-v5" &&
                configuration.optString("contract") == "pocketfinancer.processing-config/5") ||
                (releaseId == "native-integration-v6" &&
                    configuration.optString("contract") == "pocketfinancer.processing-config/6")
        )
        check(configuration.getJSONObject("persistence_policy").optString("rollout_mode") == "automatic")
        check(configuration.optString("source_ref_hash") == SmsProcessingStore.sha256(source.id)) {
            "Stored source reference changed"
        }
        dao.getReconstructedResult(operation.id)?.semanticResultJson?.let { resultJson ->
            val result = JSONObject(resultJson)
            val duplicate = result.optJSONObject("duplicate_assessment")
            check(duplicate?.optString("status") != "already_persisted") {
                "This source event was already saved"
            }
        }
        check(
            dao.countPersistedSourceEvents(
                operation.id,
                source.id,
                operation.stableEventId
            ) == 0
        ) { "This SMS event already has a transaction" }

        val allowed = setOf("amount", "direction", "account", "counterparty", "account_id")
        require(command.corrections.all { it.field in allowed })
        val corrections = command.corrections.associateBy { it.field }
        require(corrections.size == command.corrections.size)

        val amountCorrection = requireNotNull(corrections["amount"]) {
            "A grounded amount is required"
        }
        val amountSpan = requireReviewSpan(amountCorrection, source.rawMessage)
        val amountValue = JSONObject(amountCorrection.newValueJson)
        val currency = amountValue.getString("currency").uppercase(java.util.Locale.ROOT)
        val minorUnits = amountValue.get("minor_units").exactLongOrNull()
            ?: throw IllegalArgumentException("Amount must use exact minor units")
        require(SmsReviewGrounding.minorUnits(amountSpan.text, currency) == minorUnits)

        val directionCorrection = requireNotNull(corrections["direction"]) {
            "A debit or credit direction is required"
        }
        val direction = decodeScalar(directionCorrection.newValueJson) as? String
            ?: throw IllegalArgumentException("Direction must be text")
        require(direction in setOf("debit", "credit"))
        val directionSpan = directionCorrection.evidenceJson?.let {
            requireReviewSpan(directionCorrection, source.rawMessage)
        }
        if (directionSpan == null) {
            require(
                directionCorrection.classification ==
                    SmsFieldGroundingClassification.SUPPLIED_MANUAL_UNGROUNDED_VALUE
            ) { "Direction without source wording must be an explicit owner choice" }
        } else {
            require(SmsReviewGrounding.directionFrom(directionSpan.text) == direction)
        }

        val accountCorrection = requireNotNull(corrections["account"]) {
            "An account reference selected from the SMS is required"
        }
        val accountSpan = requireReviewSpan(accountCorrection, source.rawMessage)
        val accountReference = JSONObject(accountCorrection.newValueJson).getString("reference")
        val normalizedAccountReference = SmsReviewGrounding.normalizeAccount(accountSpan.text)
        require(normalizedAccountReference.isNotBlank())
        require(normalizedAccountReference == SmsReviewGrounding.normalizeAccount(accountReference))
        val account = resolveOrCreateV5Account(
            selectedAccountCorrection = corrections["account_id"],
            accountReference = normalizedAccountReference,
            source = source,
            now = now
        )

        val counterparty = corrections["counterparty"]?.let { correction ->
            if (correction.newValueJson == "null") {
                require(correction.evidenceJson == null)
                null
            } else {
                val span = requireReviewSpan(correction, source.rawMessage)
                val value = decodeScalar(correction.newValueJson) as? String
                    ?: throw IllegalArgumentException("Counterparty must be text")
                val normalized = SmsReviewGrounding.normalizeText(span.text)
                require(normalized.isNotBlank())
                require(normalized == SmsReviewGrounding.normalizeText(value))
                normalized
            }
        }
        val receipt = configuration.getJSONObject("received_timestamp")
        check(receipt.optBoolean("read_only"))
        val occurredAt = receipt.get("epoch_ms").exactLongOrNull()
            ?: throw IllegalStateException("Stored receipt time is invalid")
        val receiptProvenance = receipt.getString("provenance")
        val scale = CurrencyScaleRegistry.scale(currency)
            ?: throw IllegalArgumentException("Unsupported transaction currency")
        require(minorUnits > 0)

        val transactionId = UUID.nameUUIDFromBytes(
            "${source.id}|${operation.stableEventId}".toByteArray(Charsets.UTF_8)
        ).toString()
        val revisionId = UUID.randomUUID().toString()
        val merchant = counterparty ?: "Unspecified counterparty"
        val transaction = TransactionEntity(
            id = transactionId,
            amount = BigDecimal.valueOf(minorUnits).movePointLeft(scale).toDouble(),
            merchant = merchant,
            date = occurredAt,
            type = direction,
            accountId = account.id,
            rawMessage = source.rawMessage,
            sender = source.sender,
            isEdited = true,
            createdAt = now,
            updatedAt = now,
            slmModelName = configuration.getJSONObject("extractor")
                .optString("model_identifier", "direct-sms-extractor"),
            sourceConnector = source.sourceConnector,
            sourceProviderMessageId = source.sourceProviderMessageId,
            sourceMessageId = source.sourceMessageId,
            sourceFingerprint = source.sourceFingerprint,
            sourceAlternateFingerprint = source.sourceAlternateFingerprint,
            sourceId = source.id,
            sourceEventId = operation.stableEventId,
            exactMinorUnits = minorUnits,
            currencyCode = currency,
            currencyScale = scale,
            currencyProvenance = "review_source_span",
            timestampProvenance = "${receiptProvenance}_read_only",
            currentRevisionId = revisionId,
            projectionState = "current",
            legacyPrecisionStatus = "exact_minor_units"
        )
        check(transactionDao.insert(transaction) != -1L) { "Transaction projection conflict" }
        revisionDao.insertRevision(
            TransactionRevisionEntity(
                id = revisionId,
                transactionId = transactionId,
                sourceId = source.id,
                stableEventId = operation.stableEventId,
                revision = 0,
                previousRevisionId = null,
                operationId = operation.id,
                feedbackActionId = command.actionId,
                exactMinorUnits = minorUnits,
                currencyCode = currency,
                currencyScale = scale,
                direction = direction,
                merchant = merchant,
                accountId = account.id,
                occurredAt = occurredAt,
                provenance = "user_confirmed_automatic_review",
                isCurrentProjection = true,
                createdAt = now
            )
        )
        return revisionId
    }

    /** Resolves the SMS-selected account or creates and confirms one in this transaction. */
    private suspend fun resolveOrCreateV5Account(
        selectedAccountCorrection: SmsFieldCorrection?,
        accountReference: String,
        source: AdmittedSmsSourceEntity,
        now: Long
    ): AccountEntity {
        val selectedAccountId = selectedAccountCorrection?.let { correction ->
            require(correction.evidenceJson == null)
            require(
                correction.classification ==
                    SmsFieldGroundingClassification.SUPPLIED_MANUAL_UNGROUNDED_VALUE
            ) { "Account selection must be an explicit owner choice" }
            if (correction.newValueJson == "null") {
                null
            } else {
                decodeScalar(correction.newValueJson) as? String
                    ?: throw IllegalArgumentException("Selected account must be text")
            }
        }
        selectedAccountId?.let { accountId ->
            return accountDao.getById(accountId)
                ?: throw IllegalArgumentException("Selected account does not exist")
        }

        val normalizedAlias = if ('@' in accountReference) {
            "vpa:$accountReference"
        } else {
            "suffix:$accountReference"
        }
        val aliasHash = SmsProcessingStore.sha256(normalizedAlias)
        val aliasMatches = revisionDao.findConfirmedAliases(
            aliasHash,
            GroundedAccountResolver.MATCHING_SCOPE
        ).mapNotNull { accountDao.getById(it.accountId) }.distinctBy { it.id }
        check(aliasMatches.size <= 1) { "Account reference is ambiguous" }
        aliasMatches.singleOrNull()?.let { return it }

        // Accounts created before aliases existed can still be safely reused when
        // the selected SMS reference matches exactly and only one account does.
        val nameMatches = accountDao.getAllOnce().filter {
            SmsReviewGrounding.normalizeAccount(it.name) == accountReference
        }
        check(nameMatches.size <= 1) { "Account reference is ambiguous" }
        nameMatches.singleOrNull()?.let { existing ->
            revisionDao.insertAccountAlias(
                AccountAliasEntity(
                    id = UUID.randomUUID().toString(),
                    accountId = existing.id,
                    normalizedAliasHash = aliasHash,
                    aliasKind = if ('@' in accountReference) "vpa" else "suffix",
                    matchingScope = GroundedAccountResolver.MATCHING_SCOPE,
                    confirmedByUser = true,
                    createdAt = now
                )
            )
            return existing
        }

        val display = if ('@' in accountReference) {
            "UPI account $accountReference"
        } else {
            "Account ••${accountReference.takeLast(4)}"
        }
        val created = AccountEntity(
            id = UUID.randomUUID().toString(),
            name = display,
            bank = source.sender.ifBlank { "Unknown Account" },
            type = "sms-review",
            createdAt = now,
            updatedAt = now
        )
        accountDao.insert(created)
        revisionDao.insertAccountAlias(
            AccountAliasEntity(
                id = UUID.randomUUID().toString(),
                accountId = created.id,
                normalizedAliasHash = aliasHash,
                aliasKind = if ('@' in accountReference) "vpa" else "suffix",
                matchingScope = GroundedAccountResolver.MATCHING_SCOPE,
                confirmedByUser = true,
                createdAt = now
            )
        )
        return created
    }
    private fun requireReviewSpan(
        correction: SmsFieldCorrection,
        source: String
    ): SmsReviewSourceSpan {
        val evidence = JSONObject(requireNotNull(correction.evidenceJson))
        val span = SmsReviewGrounding.span(
            source,
            evidence.getInt("start_scalar"),
            evidence.getInt("end_scalar")
        )
        require(span.text == evidence.getString("text"))
        return span
    }

    private fun Any.exactLongOrNull(): Long? = when (this) {
        is Long -> this
        is Int -> toLong()
        else -> null
    }

    private fun decodeScalar(json: String): Any {
        val value = JSONTokener(json).nextValue()
        require(value !is JSONObject && value !is org.json.JSONArray && value != JSONObject.NULL)
        return value
    }

    private fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(
            prefix = "{", postfix = "}", separator = ","
        ) { key -> "${JSONObject.quote(key)}:${canonicalJson(value.get(key))}" }
        is org.json.JSONArray -> (0 until value.length()).joinToString(
            prefix = "[", postfix = "]", separator = ","
        ) { canonicalJson(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Boolean, is Int, is Long -> value.toString()
        else -> error("Unsupported canonical JSON value")
    }

    private fun validate(command: SmsReviewCommand) {
        require(command.expectedRevision >= 0)
        require(command.corrections.map { it.field }.distinct().size == command.corrections.size) {
            "Duplicate correction fields are not permitted"
        }
        when (command.action) {
            SmsReviewAction.CORRECT -> {
                require(command.corrections.isNotEmpty())
                require(command.retryConfiguration == null)
            }
            SmsReviewAction.SAVE_DRAFT,
            SmsReviewAction.RESOLVE_MULTIPLE_EVENTS -> require(command.retryConfiguration == null)
            SmsReviewAction.RETRY -> {
                require(command.corrections.isEmpty())
                require(command.retryConfiguration in setOf("original", "current"))
            }
            SmsReviewAction.CONFIRM,
            SmsReviewAction.REJECT -> {
                require(command.corrections.isEmpty())
                require(command.retryConfiguration == null)
            }
        }
    }

    private fun correctionsJson(corrections: List<SmsFieldCorrection>): String =
        corrections.joinToString(prefix = "[", postfix = "]", separator = ",") { correction ->
            "{" +
                "\"candidate_id\":${correction.candidateId?.let(JSONObject::quote) ?: "null"}," +
                "\"classification\":${JSONObject.quote(correction.classification.wireValue)}," +
                "\"evidence\":${correction.evidenceJson ?: "null"}," +
                "\"field\":${JSONObject.quote(correction.field)}," +
                "\"new_value\":${correction.newValueJson}," +
                "\"previous_revision_id\":" +
                "${correction.previousRevisionId?.let(JSONObject::quote) ?: "null"}}"
        }

    private fun canonicalFeedback(
        command: SmsReviewCommand,
        operationId: String,
        resultingRevision: Long,
        correctionsJson: String,
        previousHash: String?
    ): String = "{" +
        "\"action\":${JSONObject.quote(command.action.wireValue)}," +
        "\"action_id\":${JSONObject.quote(command.actionId)}," +
        "\"corrections\":$correctionsJson," +
        "\"expected_revision\":${command.expectedRevision}," +
        "\"operation_id\":${JSONObject.quote(operationId)}," +
        "\"previous_event_hash\":${previousHash?.let(JSONObject::quote) ?: "null"}," +
        "\"resulting_revision\":$resultingRevision," +
        "\"retry_configuration\":" +
        "${command.retryConfiguration?.let(JSONObject::quote) ?: "null"}," +
        "\"review_case_id\":${JSONObject.quote(command.reviewCaseId)}}"
}
