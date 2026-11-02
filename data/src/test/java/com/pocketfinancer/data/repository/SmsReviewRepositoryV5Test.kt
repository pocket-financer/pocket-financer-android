package com.pocketfinancer.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketfinancer.data.db.AppDatabase
import com.pocketfinancer.data.db.entity.AccountEntity
import com.pocketfinancer.data.db.entity.AdmittedSmsSourceEntity
import com.pocketfinancer.data.db.entity.SmsProcessingOperationEntity
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
class SmsReviewRepositoryV5Test {
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
    fun `details keep partial model fields separate from analyzer suggestions`() = runBlocking {
        insertFixture()

        val details = repository.details(REVIEW_ID)
        val retained = requireNotNull(details.retainedEvidence)

        assertNull(details.groundedProposal)
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
        insertFixture()
        val command = command(includeAccount = true)

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
        insertFixture(includeExistingAccount = false)
        val command = command(includeAccount = false)

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
        insertFixture()

        repository.resolve(command(includeAccount = false), NOW)

        assertEquals(1, database.accountDao().getAllOnce().size)
        assertEquals(ACCOUNT_ID, database.transactionDao().getBySourceEvent(SOURCE_ID, EVENT_ID)?.accountId)
    }

    @Test
    fun `failed correction rolls back and can be retried`() = runBlocking {
        insertFixture()

        assertFails { repository.resolve(command(includeAccount = false, includeAccountSpan = false), NOW) }

        assertEquals(0, database.transactionDao().count())
        assertEquals(1, database.accountDao().getAllOnce().size)
        assertNull(database.smsProcessingDao().getFeedbackByAction(ACTION_ID))
        assertEquals(0, database.smsProcessingDao().getReviewCase(REVIEW_ID)?.revision)

        val retried = repository.resolve(command(includeAccount = false), NOW + 1)
        assertFalse(retried.replayed)
        assertEquals(1, database.transactionDao().count())
    }

    private suspend fun insertFixture(includeExistingAccount: Boolean = true) {
        if (includeExistingAccount) {
            database.accountDao().insert(
                AccountEntity(ACCOUNT_ID, "A/c XX1234", "Test Bank", "manual", NOW, NOW)
            )
        }
        database.smsProcessingDao().insertSource(
            AdmittedSmsSourceEntity(
                SOURCE_ID,
                "android_sms",
                "provider:test-message",
                "test-message",
                "fingerprint",
                null,
                "AX-TEST",
                SOURCE,
                RECEIPT_TIME,
                1,
                "manual",
                "receipt-id",
                NOW - 100,
                "admitted"
            )
        )
        val payload = configurationPayload()
        val hash = SmsProcessingStore.sha256(canonical(payload))
        payload.put("config_hash", hash)
        database.smsProcessingDao().insertOperation(
            SmsProcessingOperationEntity(
                OPERATION_ID,
                SOURCE_ID,
                null,
                EVENT_ID,
                "manual",
                canonical(payload),
                hash,
                "native-integration-v5",
                "retain_review",
                1,
                null,
                1,
                null,
                NOW - 100,
                NOW - 50,
                NOW - 50,
                "{}",
                0
            )
        )
        database.smsProcessingDao().insertReviewCase(
            SmsReviewCaseEntity(
                REVIEW_ID,
                SOURCE_ID,
                OPERATION_ID,
                "open",
                0,
                "[\"extractor_missing_direction\"]",
                null,
                "[\"$EVENT_ID\"]",
                NOW - 50,
                NOW - 50
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

    private fun command(includeAccount: Boolean, includeAccountSpan: Boolean = true): SmsReviewCommand {
        val corrections = mutableListOf(
            correction("amount", span(0, 9, "INR 10.00"),
                JSONObject().put("minor_units", 1_000).put("currency", "INR").toString()),
            correction(
                "direction",
                null,
                JSONObject.quote("debit"),
                SmsFieldGroundingClassification.SUPPLIED_MANUAL_UNGROUNDED_VALUE
            ),
            correction("counterparty", span(30, 34, "SHOP"), JSONObject.quote("shop"))
        )
        if (includeAccountSpan) {
            corrections += correction("account", span(20, 26, "XX1234"),
                JSONObject().put("reference", "1234").toString())
        }
        if (includeAccount) {
            corrections += correction(
                "account_id",
                null,
                JSONObject.quote(ACCOUNT_ID),
                SmsFieldGroundingClassification.SUPPLIED_MANUAL_UNGROUNDED_VALUE
            )
        }
        return SmsReviewCommand(
            ACTION_ID,
            REVIEW_ID,
            0,
            SmsReviewAction.CORRECT,
            corrections
        )
    }

    private fun correction(
        field: String,
        evidence: JSONObject?,
        value: String,
        classification: SmsFieldGroundingClassification =
            SmsFieldGroundingClassification.SUPPLIED_SOURCE_SUPPORTED_CANDIDATE_MISS
    ) = SmsFieldCorrection(field, classification, null, null, evidence?.toString(), value)

    private fun fieldEvidence(): JSONArray = JSONArray()
        .put(field("amount", span(0, 9, "INR 10.00"),
            JSONObject().put("currency", "INR").put("minor_units", 1_000)))
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

    private fun configurationPayload() = JSONObject()
        .put("contract", "pocketfinancer.processing-config/5")
        .put("operation_id", OPERATION_ID)
        .put("source_ref_hash", SmsProcessingStore.sha256(SOURCE_ID))
        .put("contract_release", JSONObject().put("release_id", "native-integration-v5"))
        .put("persistence_policy", JSONObject().put("rollout_mode", "automatic"))
        .put("currency_context", JSONObject().put("primary_currency", "INR"))
        .put("received_timestamp", JSONObject()
            .put("epoch_ms", RECEIPT_TIME)
            .put("provenance", "platform_received")
            .put("read_only", true))
        .put("extractor", JSONObject().put("model_identifier", "test-model"))

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
        const val SOURCE = "INR 10.00 sent from XX1234 to SHOP."
        const val NOW = 1_700_000_000_000L
        const val RECEIPT_TIME = NOW - 1_000
        const val SOURCE_ID = "review-source"
        const val ACCOUNT_ID = "account-id"
        const val OPERATION_ID = "11111111-1111-4111-8111-111111111111"
        const val EVENT_ID = "22222222-2222-4222-8222-222222222222"
        const val REVIEW_ID = "33333333-3333-4333-8333-333333333333"
        const val ACTION_ID = "44444444-4444-4444-8444-444444444444"
    }
}
