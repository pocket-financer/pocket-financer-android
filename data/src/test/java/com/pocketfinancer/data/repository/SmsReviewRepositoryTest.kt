package com.pocketfinancer.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketfinancer.data.db.AppDatabase
import com.pocketfinancer.data.db.entity.AccountAliasEntity
import com.pocketfinancer.data.db.entity.AccountEntity
import com.pocketfinancer.data.db.entity.AdmittedSmsSourceEntity
import com.pocketfinancer.data.db.entity.SmsProcessingAnalysisEntity
import com.pocketfinancer.data.db.entity.SmsProcessingOperationEntity
import com.pocketfinancer.data.db.entity.SmsReconstructedResultEntity
import com.pocketfinancer.data.db.entity.SmsReviewCaseEntity
import com.pocketfinancer.data.db.entity.SmsReviewCaseV2ExtensionEntity
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmsReviewRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: SmsReviewRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = SmsReviewRepository(
            database,
            database.smsProcessingDao(),
            database.transactionDao(),
            database.transactionRevisionDao(),
            database.accountDao()
        )
    }

    @After
    fun tearDown() = database.close()

    // ── Consolidated sms-pipeline/1 automatic review flow ────────────

    @Test
    fun `details keep partial model fields separate from analyzer suggestions`() = runBlocking {
        insertAutomaticFixture(releaseId = CONSOLIDATED_RELEASE)

        val details = repository.details(REVIEW_ID)
        val retained = requireNotNull(details.retainedEvidence)

        assertNull(details.groundedProposal)
        assertEquals(0, details.windowDuplicateMatchCount)
        assertEquals(setOf("amount", "account", "counterparty"), retained.slmFields.keys)
        assertFalse("direction" in retained.slmFields)
        assertTrue(retained.slmFields.values.all { it.origin == "slm" })
        assertEquals("valid", retained.slmFields.getValue("amount").validationState)
        assertEquals(1, retained.analyzerSuggestions.size)
        assertEquals("direction", retained.analyzerSuggestions.single().kind)
        assertEquals("sent", retained.analyzerSuggestions.single().summary)
        assertEquals(RECEIPT_TIME, retained.receiptTimestampEpochMs)
    }

    @Test
    fun `explicit direction and existing account confirm partial review exactly once`() = runBlocking {
        insertAutomaticFixture(releaseId = CONSOLIDATED_RELEASE)
        val command = automaticCommand(includeAccount = true)

        val first = repository.resolve(command, NOW)
        val replay = repository.resolve(command, NOW + 1)

        assertFalse(first.replayed)
        assertTrue(replay.replayed)
        assertEquals(1, database.transactionDao().count())
        val transaction = database.transactionDao().getBySourceEvent(SOURCE_ID, EVENT_ID)!!
        assertEquals(ACCOUNT_ID, transaction.accountId)
        assertEquals("debit", transaction.type)
        assertEquals(1_000L, transaction.exactMinorUnits)
        assertEquals(10.0, transaction.amount)
        assertEquals(RECEIPT_TIME, transaction.date)
        assertEquals(1, database.transactionRevisionDao().getHistory(transaction.id).size)
        assertEquals("corrected", database.smsProcessingDao().getReviewCase(REVIEW_ID)?.state)
    }

    @Test
    fun `sms account creates an owned account and replays without duplicates`() = runBlocking {
        insertAutomaticFixture(releaseId = CONSOLIDATED_RELEASE, includeExistingAccount = false)
        val command = automaticCommand(includeAccount = false)

        val first = repository.resolve(command, NOW)
        val replay = repository.resolve(command, NOW + 1)

        assertFalse(first.replayed)
        assertTrue(replay.replayed)
        assertEquals(1, database.accountDao().getAllOnce().size)
        val account = database.accountDao().getAllOnce().single()
        assertEquals("Account ••1234", account.name)
        assertEquals("AX-TEST", account.bank)
        assertEquals(account.id, database.transactionDao().getBySourceEvent(SOURCE_ID, EVENT_ID)?.accountId)
    }

    @Test
    fun `unique existing SMS account is reused without an explicit picker choice`() = runBlocking {
        insertAutomaticFixture(releaseId = CONSOLIDATED_RELEASE)

        repository.resolve(automaticCommand(includeAccount = false), NOW)

        assertEquals(1, database.accountDao().getAllOnce().size)
        assertEquals(ACCOUNT_ID, database.transactionDao().getBySourceEvent(SOURCE_ID, EVENT_ID)?.accountId)
    }

    @Test
    fun `failed correction rolls back atomically and can be retried`() = runBlocking {
        insertAutomaticFixture(releaseId = CONSOLIDATED_RELEASE)

        assertFails {
            repository.resolve(
                automaticCommand(includeAccount = false, includeAccountSpan = false),
                NOW
            )
        }

        assertEquals(0, database.transactionDao().count())
        assertEquals(1, database.accountDao().getAllOnce().size)
        assertNull(database.smsProcessingDao().getFeedbackByAction(ACTION_ID))
        assertEquals(0, database.smsProcessingDao().getReviewCase(REVIEW_ID)?.revision)

        val retried = repository.resolve(automaticCommand(includeAccount = false), NOW + 1)
        assertFalse(retried.replayed)
        assertEquals(1, database.transactionDao().count())
    }

    @Test
    fun `reject closes review case without creating transaction or account`() = runBlocking {
        insertAutomaticFixture(releaseId = CONSOLIDATED_RELEASE, includeExistingAccount = false)
        val rejectCommand = SmsReviewCommand(
            actionId = ACTION_ID,
            reviewCaseId = REVIEW_ID,
            expectedRevision = 0,
            action = SmsReviewAction.REJECT
        )

        val first = repository.resolve(rejectCommand, NOW)
        val replay = repository.resolve(rejectCommand, NOW + 1)

        assertFalse(first.replayed)
        assertTrue(replay.replayed)
        assertEquals("rejected", database.smsProcessingDao().getReviewCase(REVIEW_ID)?.state)
        assertEquals(0, database.transactionDao().count())
        assertEquals(0, database.accountDao().getAllOnce().size)
        assertTrue(repository.openCases().isEmpty())
    }

    // ── Legacy stored review-case backward compatibility ─────────────

    @Test
    fun `legacy stored native-integration-v5 partial review remains readable and confirmable`() =
        runBlocking {
            insertAutomaticFixture(releaseId = "native-integration-v5")

            val details = repository.details(REVIEW_ID)
            assertEquals(1_000L, details.retainedEvidence?.slmFields?.get("amount")
                ?.normalizedValueJson?.let(::JSONObject)?.getLong("minor_units"))

            repository.resolve(automaticCommand(includeAccount = true), NOW)
            assertEquals(1, database.transactionDao().count())
        }

    @Test
    fun `legacy review-only proposal confirms idempotently and reuses owned alias`() = runBlocking {
        insertOwnedAlias("existing-account", "Existing card")
        val reviewId = insertLegacyReviewOnlyFixture()
        val command = SmsReviewCommand(ACTION_ID, reviewId, 0, SmsReviewAction.CONFIRM)

        val first = repository.resolve(command, NOW)
        val replay = repository.resolve(command, NOW + 1)

        assertFalse(first.replayed)
        assertTrue(replay.replayed)
        assertFails {
            repository.resolve(command.copy(reviewCaseId = "another-review"), NOW + 2)
        }
        assertEquals(1, database.accountDao().getAllOnce().size)
        assertEquals(1, database.transactionDao().count())
        val transaction = database.transactionDao().getBySourceEvent(LEGACY_SOURCE_ID, LEGACY_EVENT_ID)!!
        assertEquals("existing-account", transaction.accountId)
        assertEquals(LEGACY_RECEIPT_TIME, transaction.date)
        assertEquals(1, database.transactionRevisionDao().getHistory(transaction.id).size)
        assertEquals("confirmed", database.smsProcessingDao().getReviewCase(reviewId)?.state)
    }

    @Test
    fun `legacy review-only confirm creates account when absent and saves draft safely`() = runBlocking {
        val reviewId = insertLegacyReviewOnlyFixture()
        val clearedAmount = SmsFieldCorrection(
            field = "amount",
            classification = SmsFieldGroundingClassification.SUPPLIED_SOURCE_SUPPORTED_CANDIDATE_MISS,
            previousRevisionId = null,
            candidateId = null,
            evidenceJson = null,
            newValueJson = "null"
        )

        repository.resolve(
            SmsReviewCommand(ACTION_ID, reviewId, 0, SmsReviewAction.SAVE_DRAFT, listOf(clearedAmount)),
            NOW
        )
        val restored = repository.details(reviewId)
        assertEquals("draft", restored.reviewCase.state)
        assertEquals(1, restored.reviewCase.revision)
        assertEquals(clearedAmount, restored.draftCorrections.single())
        assertEquals(125_000, restored.draftProposal?.amountMinorUnits)
        assertEquals(0, database.transactionDao().count())
        assertEquals(0, database.accountDao().getAllOnce().size)

        repository.resolve(
            SmsReviewCommand(UUID.randomUUID().toString(), reviewId, 1, SmsReviewAction.CONFIRM),
            NOW + 1
        )
        assertEquals(1, database.accountDao().getAllOnce().size)
        assertEquals(1, database.transactionDao().count())
    }

    @Test
    fun `invalid grounded result, mismatched event, duplicate fields, and ambiguous aliases roll back`() =
        runBlocking {
            val badResultReview = insertLegacyReviewOnlyFixture(
                result = legacyResultJson().replace("125000", "125001")
            )
            assertFails {
                repository.resolve(
                    SmsReviewCommand(ACTION_ID, badResultReview, 0, SmsReviewAction.CONFIRM),
                    NOW
                )
            }
            assertNoLegacySideEffects(badResultReview)

            database.clearAllTables()
            val badEventReview = insertLegacyReviewOnlyFixture(
                result = legacyResultJson().replace(
                    "\"source_event_key\":\"$LEGACY_EVENT_ID\"",
                    "\"source_event_key\":\"another-event\""
                )
            )
            assertFails {
                repository.resolve(
                    SmsReviewCommand(ACTION_ID, badEventReview, 0, SmsReviewAction.CONFIRM),
                    NOW
                )
            }
            assertNoLegacySideEffects(badEventReview)

            database.clearAllTables()
            val dupFieldReview = insertLegacyReviewOnlyFixture()
            val duplicateCorrection = SmsFieldCorrection(
                field = "amount",
                classification = SmsFieldGroundingClassification.SUPPLIED_SOURCE_SUPPORTED_CANDIDATE_MISS,
                previousRevisionId = null,
                candidateId = null,
                evidenceJson = """{"start_scalar":0,"end_scalar":12,"text":"INR 1,250.00"}""",
                newValueJson = """{"minor_units":125000,"currency":"INR"}"""
            )
            assertFails {
                repository.resolve(
                    SmsReviewCommand(
                        ACTION_ID,
                        dupFieldReview,
                        0,
                        SmsReviewAction.CONFIRM,
                        listOf(duplicateCorrection, duplicateCorrection)
                    ),
                    NOW
                )
            }
            assertNoLegacySideEffects(dupFieldReview)

            database.clearAllTables()
            insertOwnedAlias("account-one", "First card")
            insertOwnedAlias("account-two", "Second card")
            val ambiguousReview = insertLegacyReviewOnlyFixture()
            assertFails {
                repository.resolve(
                    SmsReviewCommand(ACTION_ID, ambiguousReview, 0, SmsReviewAction.CONFIRM),
                    NOW
                )
            }
            assertEquals(2, database.accountDao().getAllOnce().size)
            assertEquals(0, database.transactionDao().count())
            assertNull(database.smsProcessingDao().getFeedbackByAction(ACTION_ID))
            assertEquals(0, database.smsProcessingDao().getReviewCase(ambiguousReview)?.revision)
        }

    private suspend fun assertNoLegacySideEffects(reviewId: String) {
        assertEquals(0, database.accountDao().getAllOnce().size)
        assertEquals(0, database.transactionDao().count())
        assertNull(database.smsProcessingDao().getFeedbackByAction(ACTION_ID))
        assertEquals(0, database.smsProcessingDao().getReviewCase(reviewId)?.revision)
    }

    private suspend fun insertAutomaticFixture(
        releaseId: String,
        includeExistingAccount: Boolean = true
    ) {
        if (includeExistingAccount) {
            database.accountDao().insert(
                AccountEntity(ACCOUNT_ID, "A/c XX1234", "Test Bank", "manual", NOW, NOW)
            )
        }
        database.smsProcessingDao().insertSource(
            AdmittedSmsSourceEntity(
                SOURCE_ID, "android_sms", "provider:test-message", "test-message",
                "fingerprint", null, "AX-TEST", SOURCE, RECEIPT_TIME, 1,
                "manual", "receipt-id", NOW - 100, "admitted"
            )
        )
        val contract = if (releaseId == CONSOLIDATED_RELEASE) {
            CONSOLIDATED_RELEASE
        } else {
            "pocketfinancer.processing-config/5"
        }
        val payload = JSONObject()
            .put("contract", contract)
            .put("operation_id", OPERATION_ID)
            .put("source_ref_hash", SmsProcessingStore.sha256(SOURCE_ID))
            .put("contract_release", JSONObject().put("release_id", releaseId))
            .put("persistence_policy", JSONObject().put("rollout_mode", "automatic"))
            .put("currency_context", JSONObject().put("primary_currency", "INR"))
            .put(
                "received_timestamp",
                JSONObject()
                    .put("epoch_ms", RECEIPT_TIME)
                    .put("provenance", "platform_received")
                    .put("read_only", true)
            )
            .put("extractor", JSONObject().put("model_identifier", "test-model"))
        val hash = SmsProcessingStore.sha256(canonical(payload))
        payload.put("config_hash", hash)
        database.smsProcessingDao().insertOperation(
            SmsProcessingOperationEntity(
                OPERATION_ID, SOURCE_ID, null, EVENT_ID, "manual",
                canonical(payload), hash, releaseId, "retain_review",
                1, null, 1, null, NOW - 100, NOW - 50, NOW - 50, "{}", 0
            )
        )
        database.smsProcessingDao().insertReviewCase(
            SmsReviewCaseEntity(
                REVIEW_ID, SOURCE_ID, OPERATION_ID, "open", 0,
                "[\"extractor_missing_direction\"]", null, "[\"$EVENT_ID\"]", NOW - 50, NOW - 50
            )
        )
        database.smsProcessingDao().upsertReviewCaseV2Extension(
            SmsReviewCaseV2ExtensionEntity(
                REVIEW_ID,
                OPERATION_ID,
                "pocketfinancer.review-case/2",
                "extractor_validation",
                JSONArray().put(
                    JSONObject()
                        .put("kind", "direction")
                        .put("span", span(10, 14, "sent"))
                        .put("clause", JSONObject.NULL)
                        .put("suggested_interpretation", "debit")
                        .put("provenance", JSONObject().put("producer", "structural_analyzer"))
                        .put("analyzer_version", "pocketfinancer.structural-sms-analyzer/2")
                ).toString(),
                fieldEvidence().toString(),
                NOW - 50
            )
        )
    }

    private suspend fun insertLegacyReviewOnlyFixture(result: String = legacyResultJson()): String {
        val dao = database.smsProcessingDao()
        val configurationPayload = JSONObject()
            .put("contract", "pocketfinancer.processing-config/4")
            .put("contract_release", JSONObject().put("release_id", "native-integration-v4"))
            .put(
                "extractor",
                JSONObject()
                    .put("model_file_sha256", "a".repeat(64))
                    .put("model_identity_kind", "file_sha256")
            )
            .put("persistence_policy", JSONObject().put("rollout_mode", "review_only"))
            .put("source_ref_hash", SmsProcessingStore.sha256(LEGACY_SOURCE_ID))
        val configurationHash = SmsProcessingStore.sha256(canonical(configurationPayload))
        val configuration = JSONObject(configurationPayload.toString())
            .put("config_hash", configurationHash)
        val reviewId = UUID.randomUUID().toString()
        dao.insertSource(
            AdmittedSmsSourceEntity(
                id = LEGACY_SOURCE_ID,
                sourceConnector = "synthetic-test",
                sourceMessageId = "message-1",
                sourceProviderMessageId = null,
                sourceFingerprint = "b".repeat(64),
                sourceAlternateFingerprint = null,
                sender = "SYNTH-BANK",
                rawMessage = LEGACY_SOURCE,
                sourceTimestamp = LEGACY_RECEIPT_TIME,
                messageType = 1,
                origin = "test",
                admissionReceiptId = UUID.randomUUID().toString(),
                admittedAt = NOW,
                retentionState = "retained"
            )
        )
        dao.insertOperation(
            SmsProcessingOperationEntity(
                id = LEGACY_OPERATION_ID,
                sourceId = LEGACY_SOURCE_ID,
                parentOperationId = null,
                stableEventId = LEGACY_EVENT_ID,
                trigger = "test",
                configurationJson = canonical(configuration),
                configurationHash = configurationHash,
                contractReleaseId = "native-integration-v4",
                state = "retained_for_review",
                transitionSequence = 1,
                ownerToken = null,
                ownerGeneration = 1,
                claimExpiresAt = null,
                createdAt = NOW,
                updatedAt = NOW,
                settledAt = NOW,
                settlementReceiptJson = null,
                deletionEpoch = 0
            )
        )
        dao.insertAnalysis(
            SmsProcessingAnalysisEntity(
                id = UUID.randomUUID().toString(),
                operationId = LEGACY_OPERATION_ID,
                analysisId = "analysis",
                contractVersion = "pocketfinancer.sms-analysis/2",
                sourceHash = SmsProcessingStore.sha256(LEGACY_SOURCE),
                configurationHash = configurationHash,
                canonicalJson = "{}",
                createdAt = NOW
            )
        )
        dao.insertReconstructedResult(
            SmsReconstructedResultEntity(
                id = UUID.randomUUID().toString(),
                operationId = LEGACY_OPERATION_ID,
                contractVersion = "pocketfinancer.processing-result/3",
                recognitionDecision = "posted",
                semanticResultJson = result,
                createdAt = NOW
            )
        )
        dao.insertReviewCase(
            SmsReviewCaseEntity(
                id = reviewId,
                sourceId = LEGACY_SOURCE_ID,
                currentOperationId = LEGACY_OPERATION_ID,
                state = "open",
                revision = 0,
                reasonCodesJson = "[\"review_only_rollout\"]",
                draftJson = null,
                stableEventIdsJson = "[\"$LEGACY_EVENT_ID\"]",
                createdAt = NOW,
                updatedAt = NOW
            )
        )
        return reviewId
    }

    private suspend fun insertOwnedAlias(accountId: String, name: String) {
        database.accountDao().insert(
            AccountEntity(
                id = accountId,
                name = name,
                bank = "Synthetic bank",
                type = "card",
                createdAt = NOW,
                updatedAt = NOW
            )
        )
        database.transactionRevisionDao().insertAccountAlias(
            AccountAliasEntity(
                id = UUID.randomUUID().toString(),
                accountId = accountId,
                normalizedAliasHash = SmsProcessingStore.sha256("suffix:1234"),
                aliasKind = "suffix",
                matchingScope = GroundedAccountResolver.MATCHING_SCOPE,
                confirmedByUser = true,
                createdAt = NOW
            )
        )
    }

    private fun automaticCommand(
        includeAccount: Boolean,
        includeAccountSpan: Boolean = true
    ): SmsReviewCommand {
        val corrections = mutableListOf(
            correction(
                "amount",
                span(0, 9, "INR 10.00"),
                JSONObject().put("minor_units", 1_000).put("currency", "INR").toString()
            ),
            correction(
                "direction",
                null,
                JSONObject.quote("debit"),
                SmsFieldGroundingClassification.SUPPLIED_MANUAL_UNGROUNDED_VALUE
            ),
            correction("counterparty", span(30, 34, "SHOP"), JSONObject.quote("shop"))
        )
        if (includeAccountSpan) {
            corrections += correction(
                "account",
                span(20, 26, "XX1234"),
                JSONObject().put("reference", "1234").toString()
            )
        }
        if (includeAccount) {
            corrections += correction(
                "account_id",
                null,
                JSONObject.quote(ACCOUNT_ID),
                SmsFieldGroundingClassification.SUPPLIED_MANUAL_UNGROUNDED_VALUE
            )
        }
        return SmsReviewCommand(ACTION_ID, REVIEW_ID, 0, SmsReviewAction.CORRECT, corrections)
    }

    private fun correction(
        field: String,
        evidence: JSONObject?,
        value: String,
        classification: SmsFieldGroundingClassification =
            SmsFieldGroundingClassification.SUPPLIED_SOURCE_SUPPORTED_CANDIDATE_MISS
    ) = SmsFieldCorrection(field, classification, null, null, evidence?.toString(), value)

    private fun fieldEvidence(): JSONArray = JSONArray()
        .put(field("amount", span(0, 9, "INR 10.00"), JSONObject().put("currency", "INR").put("minor_units", 1_000)))
        .put(field("account", span(20, 26, "XX1234"), "1234"))
        .put(field("counterparty", span(30, 34, "SHOP"), "shop"))
        .put(
            JSONObject()
                .put("field", "direction")
                .put("source_span", span(10, 14, "sent"))
                .put("normalized_value", "debit")
                .put("validation_state", "suggestion")
                .put("originating_stage", "analysis_advisory")
                .put("origin", "advisory_analyzer")
        )

    private fun field(name: String, sourceSpan: JSONObject, normalized: Any) = JSONObject()
        .put("field", name)
        .put("source_span", sourceSpan)
        .put("normalized_value", normalized)
        .put("validation_state", "valid")
        .put("originating_stage", "normalization")
        .put("origin", "slm")

    private fun span(start: Int, end: Int, text: String) = JSONObject()
        .put("start_scalar", start)
        .put("end_scalar", end)
        .put("text", text)

    private fun legacyResultJson(): String = """
        {
          "contract":"pocketfinancer.processing-result/3",
          "status":"blocked",
          "recognition_decision":"posted",
          "semantic_result":{
            "money":{"minor_units":125000,"currency":"INR"},
            "direction":"debit",
            "account_reference":"1234",
            "counterparty":"synthetic cafe",
            "evidence":{
              "amount":{"start_scalar":0,"end_scalar":12,"text":"INR 1,250.00"},
              "direction":{"start_scalar":13,"end_scalar":20,"text":"debited"},
              "account":{"start_scalar":26,"end_scalar":32,"text":"XX1234"},
              "counterparty":{"start_scalar":36,"end_scalar":50,"text":"SYNTHETIC CAFE"}
            }
          },
          "receipt_timestamp":{"epoch_ms":$LEGACY_RECEIPT_TIME,"provenance":"platform_received","read_only":true},
          "account_resolution":{
            "status":"unresolved","match_count":0,"account_id":null,
            "normalized_reference":"suffix:1234","matched_alias_hash":null,
            "provenance":"pocketfinancer.account-resolution-profile/1"
          },
          "duplicate_assessment":{
            "status":"clear","idempotency_key":"$LEGACY_SOURCE_ID",
            "source_event_key":"$LEGACY_EVENT_ID","transaction_fingerprint":"${legacyFingerprint()}"
          }
        }
    """.trimIndent()

    private fun legacyFingerprint(): String = SmsProcessingStore.sha256(
        "125000\u0000INR\u0000debit\u0000\u0000$LEGACY_RECEIPT_TIME"
    )

    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(
            prefix = "{", postfix = "}", separator = ","
        ) { key -> "${JSONObject.quote(key)}:${canonical(value.get(key))}" }
        is JSONArray -> (0 until value.length()).joinToString(
            prefix = "[", postfix = "]", separator = ","
        ) { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Boolean, is Int, is Long -> value.toString()
        else -> error("Unsupported value")
    }

    private companion object {
        const val CONSOLIDATED_RELEASE = "pocketfinancer.sms-pipeline/1"
        const val SOURCE = "INR 10.00 sent from XX1234 to SHOP."
        const val NOW = 1_700_000_000_000L
        const val RECEIPT_TIME = NOW - 1_000
        const val SOURCE_ID = "review-source"
        const val ACCOUNT_ID = "account-id"
        const val OPERATION_ID = "11111111-1111-4111-8111-111111111111"
        const val EVENT_ID = "22222222-2222-4222-8222-222222222222"
        const val REVIEW_ID = "33333333-3333-4333-8333-333333333333"
        const val ACTION_ID = "44444444-4444-4444-8444-444444444444"

        const val LEGACY_RECEIPT_TIME = 1_234_567L
        const val LEGACY_SOURCE_ID = "source-legacy"
        const val LEGACY_OPERATION_ID = "operation-legacy"
        const val LEGACY_EVENT_ID = "event-legacy"
        const val LEGACY_SOURCE = "INR 1,250.00 debited from XX1234 at SYNTHETIC CAFE"
    }
}
