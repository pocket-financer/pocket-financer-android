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
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SmsReviewRepositoryV4Test {
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

    @Test
    fun `confirm creates account transaction feedback and revision exactly once`() = runBlocking {
        val fixture = insertFixture()
        val command = command(fixture.reviewID)

        val first = repository.resolve(command, NOW)
        val replay = repository.resolve(command, NOW + 1)

        assertTrue(!first.replayed)
        assertTrue(replay.replayed)
        assertFails {
            repository.resolve(command.copy(reviewCaseId = "another-review"), NOW + 2)
        }
        assertEquals(1, database.accountDao().getAllOnce().size)
        assertEquals(1, database.transactionDao().count())
        val transaction = database.transactionDao().getBySourceEvent(SOURCE_ID, EVENT_ID)!!
        assertEquals(RECEIPT_TIME, transaction.date)
        assertEquals(1, database.transactionRevisionDao().getHistory(transaction.id).size)
        assertEquals("confirmed", database.smsProcessingDao().getReviewCase(fixture.reviewID)?.state)
    }

    @Test
    fun `invalid grounded result rolls back every confirmation side effect`() = runBlocking {
        val fixture = insertFixture(result = resultJson().replace("125000", "125001"))

        assertFails { repository.resolve(command(fixture.reviewID), NOW) }

        assertEquals(0, database.accountDao().getAllOnce().size)
        assertEquals(0, database.transactionDao().count())
        assertNull(database.smsProcessingDao().getFeedbackByAction(ACTION_ID))
        assertEquals(0, database.smsProcessingDao().getReviewCase(fixture.reviewID)?.revision)
    }

    @Test
    fun `mismatched duplicate identity rolls back every confirmation side effect`() = runBlocking {
        val fixture = insertFixture(
            result = resultJson().replace(
                "\"source_event_key\":\"$EVENT_ID\"",
                "\"source_event_key\":\"another-event\""
            )
        )

        assertFails { repository.resolve(command(fixture.reviewID), NOW) }

        assertEquals(0, database.accountDao().getAllOnce().size)
        assertEquals(0, database.transactionDao().count())
        assertNull(database.smsProcessingDao().getFeedbackByAction(ACTION_ID))
        assertEquals(0, database.smsProcessingDao().getReviewCase(fixture.reviewID)?.revision)
    }

    @Test
    fun `draft reload preserves a cleared required source selection`() = runBlocking {
        val fixture = insertFixture()
        val clearedAmount = SmsFieldCorrection(
            field = "amount",
            classification = SmsFieldGroundingClassification.SUPPLIED_SOURCE_SUPPORTED_CANDIDATE_MISS,
            previousRevisionId = null,
            candidateId = null,
            evidenceJson = null,
            newValueJson = "null"
        )

        repository.resolve(
            command(fixture.reviewID).copy(
                action = SmsReviewAction.SAVE_DRAFT,
                corrections = listOf(clearedAmount)
            ),
            NOW
        )
        val restored = repository.details(fixture.reviewID)

        assertEquals("draft", restored.reviewCase.state)
        assertEquals(1, restored.reviewCase.revision)
        assertEquals(clearedAmount, restored.draftCorrections.single())
        assertEquals(125000, restored.draftProposal?.amountMinorUnits)
        assertEquals(0, database.transactionDao().count())
        assertEquals(0, database.accountDao().getAllOnce().size)
    }

    @Test
    fun `confirmation reuses one matching owned account`() = runBlocking {
        insertOwnedAlias("existing-account", "Existing card")
        val fixture = insertFixture()

        repository.resolve(command(fixture.reviewID), NOW)

        assertEquals(1, database.accountDao().getAllOnce().size)
        assertEquals(
            "existing-account",
            database.transactionDao().getBySourceEvent(SOURCE_ID, EVENT_ID)?.accountId
        )
    }

    @Test
    fun `ambiguous owned account aliases roll back confirmation`() = runBlocking {
        insertOwnedAlias("account-one", "First card")
        insertOwnedAlias("account-two", "Second card")
        val fixture = insertFixture()

        assertFails { repository.resolve(command(fixture.reviewID), NOW) }

        assertEquals(2, database.accountDao().getAllOnce().size)
        assertEquals(0, database.transactionDao().count())
        assertNull(database.smsProcessingDao().getFeedbackByAction(ACTION_ID))
        assertEquals(0, database.smsProcessingDao().getReviewCase(fixture.reviewID)?.revision)
    }

    @Test
    fun `duplicate field corrections roll back every confirmation side effect`() = runBlocking {
        val fixture = insertFixture()
        val correction = SmsFieldCorrection(
            field = "amount",
            classification = SmsFieldGroundingClassification.SUPPLIED_SOURCE_SUPPORTED_CANDIDATE_MISS,
            previousRevisionId = null,
            candidateId = null,
            evidenceJson = """{"start_scalar":0,"end_scalar":12,"text":"INR 1,250.00"}""",
            newValueJson = """{"minor_units":125000,"currency":"INR"}"""
        )

        assertFails {
            repository.resolve(
                command(fixture.reviewID).copy(corrections = listOf(correction, correction)),
                NOW
            )
        }

        assertEquals(0, database.accountDao().getAllOnce().size)
        assertEquals(0, database.transactionDao().count())
        assertNull(database.smsProcessingDao().getFeedbackByAction(ACTION_ID))
        assertEquals(0, database.smsProcessingDao().getReviewCase(fixture.reviewID)?.revision)
    }

    private suspend fun insertFixture(result: String = resultJson()): Fixture {
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
            .put("source_ref_hash", SmsProcessingStore.sha256(SOURCE_ID))
        val configurationHash = SmsProcessingStore.sha256(canonical(configurationPayload))
        val configuration = JSONObject(configurationPayload.toString())
            .put("config_hash", configurationHash)
        val reviewID = UUID.randomUUID().toString()
        dao.insertSource(
            AdmittedSmsSourceEntity(
                id = SOURCE_ID,
                sourceConnector = "synthetic-test",
                sourceMessageId = "message-1",
                sourceProviderMessageId = null,
                sourceFingerprint = "b".repeat(64),
                sourceAlternateFingerprint = null,
                sender = "SYNTH-BANK",
                rawMessage = SOURCE,
                sourceTimestamp = RECEIPT_TIME,
                messageType = 1,
                origin = "test",
                admissionReceiptId = UUID.randomUUID().toString(),
                admittedAt = NOW,
                retentionState = "retained"
            )
        )
        dao.insertOperation(
            SmsProcessingOperationEntity(
                id = OPERATION_ID,
                sourceId = SOURCE_ID,
                parentOperationId = null,
                stableEventId = EVENT_ID,
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
                operationId = OPERATION_ID,
                analysisId = "analysis",
                contractVersion = "pocketfinancer.sms-analysis/2",
                sourceHash = SmsProcessingStore.sha256(SOURCE),
                configurationHash = configurationHash,
                canonicalJson = "{}",
                createdAt = NOW
            )
        )
        dao.insertReconstructedResult(
            SmsReconstructedResultEntity(
                id = UUID.randomUUID().toString(),
                operationId = OPERATION_ID,
                contractVersion = "pocketfinancer.processing-result/3",
                recognitionDecision = "posted",
                semanticResultJson = result,
                createdAt = NOW
            )
        )
        dao.insertReviewCase(
            SmsReviewCaseEntity(
                id = reviewID,
                sourceId = SOURCE_ID,
                currentOperationId = OPERATION_ID,
                state = "open",
                revision = 0,
                reasonCodesJson = "[\"review_only_rollout\"]",
                draftJson = null,
                stableEventIdsJson = "[\"$EVENT_ID\"]",
                createdAt = NOW,
                updatedAt = NOW
            )
        )
        return Fixture(reviewID)
    }

    private fun command(reviewID: String) = SmsReviewCommand(
        actionId = ACTION_ID,
        reviewCaseId = reviewID,
        expectedRevision = 0,
        action = SmsReviewAction.CONFIRM
    )

    private suspend fun insertOwnedAlias(accountID: String, name: String) {
        database.accountDao().insert(
            AccountEntity(
                id = accountID,
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
                accountId = accountID,
                normalizedAliasHash = SmsProcessingStore.sha256("suffix:1234"),
                aliasKind = "suffix",
                matchingScope = GroundedAccountResolver.MATCHING_SCOPE,
                confirmedByUser = true,
                createdAt = NOW
            )
        )
    }

    private fun resultJson(): String = """
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
          "receipt_timestamp":{"epoch_ms":$RECEIPT_TIME,"provenance":"platform_received","read_only":true},
          "account_resolution":{
            "status":"unresolved","match_count":0,"account_id":null,
            "normalized_reference":"suffix:1234","matched_alias_hash":null,
            "provenance":"pocketfinancer.account-resolution-profile/1"
          },
          "duplicate_assessment":{
            "status":"clear","idempotency_key":"$SOURCE_ID",
            "source_event_key":"event-v4","transaction_fingerprint":"${fingerprint()}"
          }
        }
    """.trimIndent()

    private fun fingerprint(): String = SmsProcessingStore.sha256(
        "125000\u0000INR\u0000debit\u0000\u0000$RECEIPT_TIME"
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
        else -> error("Unsupported JSON value")
    }

    private data class Fixture(val reviewID: String)

    private companion object {
        const val NOW = 2_000_000L
        const val RECEIPT_TIME = 1_234_567L
        const val SOURCE_ID = "source-v4"
        const val OPERATION_ID = "operation-v4"
        const val EVENT_ID = "event-v4"
        const val ACTION_ID = "00000000-0000-0000-0000-000000000001"
        const val SOURCE = "INR 1,250.00 debited from XX1234 at SYNTHETIC CAFE"
    }
}
