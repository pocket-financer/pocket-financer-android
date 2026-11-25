package com.pocketfinancer.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketfinancer.data.db.AppDatabase
import com.pocketfinancer.data.db.entity.AdmittedSmsSourceEntity
import com.pocketfinancer.data.db.entity.SmsProcessingOperationEntity
import java.util.UUID
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses a separate encrypted test database; never opens the installed app's data. */
@RunWith(AndroidJUnit4::class)
class SmsProcessingRecoveryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "sms-recovery-${UUID.randomUUID()}.db"
    private val key = UUID.randomUUID().toString().toByteArray()
    private lateinit var database: AppDatabase
    private lateinit var store: SmsProcessingStore

    @Before fun setUp() {
        System.loadLibrary("sqlcipher")
        open()
    }

    private fun open() {
        database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(key.copyOf())).build()
        store = SmsProcessingStore(database, database.smsProcessingDao())
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(name)
    }

    @Test fun expiredClaimRecoversAfterReopenAndFencesLateWriter() = runBlocking {
        val operation = admit()
        val claim = store.claim(operation.id, 1_001)
        database.close()
        open()
        val now = claim.expiresAt + 1
        assertEquals(1, store.recoverExpiredOperations(now))
        assertEquals(0, store.recoverExpiredOperations(now))
        val review = database.smsProcessingDao().getReviewCaseForOperation(operation.id)
        assertNotNull(review)
        assertEquals("open", review!!.state)
        assertEquals("Invented payment alert for recovery testing.", store.sourceEvidence(operation.sourceId).body)
        assertTrue(runCatching { store.recordReconstruction(claim, "{}", now) }.isFailure)
        assertNull(database.smsProcessingDao().getReconstructedResult(operation.id))
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM transactions").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
    }

    @Test fun liveHeartbeatPreventsRecovery() = runBlocking {
        val operation = admit()
        val claim = store.claim(operation.id, 1_001)
        val renewed = store.heartbeat(claim, claim.expiresAt - 1)
        assertEquals(0, store.recoverExpiredOperations(claim.expiresAt + 1))
        assertNull(database.smsProcessingDao().getReviewCaseForOperation(operation.id))
        assertEquals(1, store.recoverExpiredOperations(renewed.expiresAt + 1))
    }

    @Test fun draftAndIdempotentFeedbackSurviveEncryptedReopen() = runBlocking {
        val operation = admit()
        val claim = store.claim(operation.id, 1_001)
        val reviewId = store.retainForReview(claim, listOf("runtime_unavailable"), 1_002)
        val command = SmsReviewCommand(
            UUID.randomUUID().toString(), reviewId, 0, SmsReviewAction.SAVE_DRAFT,
            listOf(SmsFieldCorrection("amount_minor_units",
                SmsFieldGroundingClassification.SUPPLIED_MANUAL_UNGROUNDED_VALUE,
                null, null, null, "12345"))
        )
        reviewRepository().resolve(command, 1_003)
        database.close()
        open()
        val details = reviewRepository().details(reviewId)
        assertEquals("draft", details.reviewCase.state)
        assertTrue(details.reviewCase.draftJson!!.contains("12345"))
        assertEquals(1L, details.reviewCase.revision)
        assertTrue(reviewRepository().resolve(command, 1_004).replayed)
        assertEquals(1, reviewRepository().details(reviewId).feedback.size)
        assertTrue(runCatching {
            reviewRepository().resolve(command.copy(actionId = UUID.randomUUID().toString()), 1_005)
        }.isFailure)
    }

    @Test fun completeManualCorrectionDoesNotRequireModelProposal() = runBlocking {
        val operation = admit()
        val claim = store.claim(operation.id, 1_001)
        val reviewId = store.retainForReview(claim, listOf("runtime_unavailable"), 1_002)
        val accountId = UUID.randomUUID().toString()
        database.accountDao().insert(com.pocketfinancer.data.db.entity.AccountEntity(
            accountId, "Invented account", "Test bank", "manual"))
        val values = mapOf("amount_minor_units" to "12345", "currency" to "\"INR\"",
            "direction" to "\"debit\"", "counterparty" to "\"Invented shop\"",
            "account_id" to "\"$accountId\"", "occurred_at_epoch_ms" to "1000")
        val command = SmsReviewCommand(UUID.randomUUID().toString(), reviewId, 0,
            SmsReviewAction.CORRECT, values.map { (field, value) ->
                SmsFieldCorrection(field, SmsFieldGroundingClassification.SUPPLIED_MANUAL_UNGROUNDED_VALUE,
                    null, null, null, value)
            })
        // Partial correction must remain uncommitted, with no feedback revision advance.
        assertTrue(runCatching {
            reviewRepository().resolve(command.copy(corrections = command.corrections.dropLast(1)), 1_003)
        }.isFailure)
        assertEquals(0L, reviewRepository().details(reviewId).reviewCase.revision)
        assertTrue(runCatching {
            reviewRepository().resolve(command.copy(action = SmsReviewAction.CONFIRM,
                corrections = emptyList()), 1_003)
        }.isFailure)
        listOf("1.5", "9223372036854775808", "\"12345\"").forEach { invalidAmount ->
            assertTrue(runCatching {
                reviewRepository().resolve(command.copy(corrections = command.corrections.map {
                    if (it.field == "amount_minor_units") it.copy(newValueJson = invalidAmount) else it
                }), 1_003)
            }.isFailure)
        }
        assertTrue(runCatching {
            reviewRepository().resolve(command.copy(corrections = command.corrections + command.corrections.first()), 1_003)
        }.isFailure)
        assertEquals(0L, reviewRepository().details(reviewId).reviewCase.revision)
        reviewRepository().resolve(command, 1_004)
        val transaction = database.transactionDao().getBySourceEvent(operation.sourceId, operation.stableEventId)!!
        assertEquals(12345L, transaction.exactMinorUnits)
        assertEquals("INR", transaction.currencyCode)
        assertEquals("user_supplied", transaction.currencyProvenance)
        assertEquals("user_corrected_time", transaction.timestampProvenance)
        assertNull(database.smsProcessingDao().getReconstructedResult(operation.id))
        assertEquals(1, database.transactionRevisionDao().getHistory(transaction.id).size)
        assertTrue(reviewRepository().resolve(command, 1_005).replayed)
        assertEquals(1, database.transactionRevisionDao().getHistory(transaction.id).size)
    }

    @Test fun automaticPersistRollsBackOnFaultAndFencesRetryAcrossEncryptedReopen() = runBlocking {
        val now = 1_700_000_100_000L
        val sourceId = UUID.randomUUID().toString()
        val eventId = UUID.randomUUID().toString()
        val accountId = UUID.randomUUID().toString()
        database.accountDao().insert(com.pocketfinancer.data.db.entity.AccountEntity(
            accountId, "Synthetic A/c XX1234", "Test bank", "manual"))
        store.admitSource(AdmittedSmsSourceEntity(
            sourceId, "synthetic", sourceId, null, SmsProcessingStore.sha256(sourceId),
            null, "TEST", "INR 10.00 debited from account XX1234.", now, 1,
            "diagnostic", UUID.randomUUID().toString(), now, "admitted"))
        val first = SmsProcessingOperationEntity(
            UUID.randomUUID().toString(), sourceId, null, eventId, "realtime",
            "{}", SmsProcessingStore.sha256("{}"), "native-integration-v5",
            "ready", 0, null, 0, null, now, now, null, null, 0)
        store.createOperation(first)
        val claim = store.claim(first.id, now + 1)
        val input = SmsAutomaticTransactionInput(
            1_000, "INR", "debit", null, accountId, now, "platform_received",
            "deterministic-device-fixture", "{}", "b".repeat(64), "[]", "{}")

        assertTrue(runCatching {
            store.persistEligibleTransaction(claim, input, now + 2) {
                error("synthetic pre-settlement fault")
            }
        }.isFailure)
        assertEquals(0, database.transactionDao().count())
        assertNull(database.smsProcessingDao().getReconstructedResult(first.id))
        assertNull(database.smsProcessingDao().getPersistenceDecision(first.id))
        assertEquals("claimed", database.smsProcessingDao().getOperation(first.id)?.state)

        val receipt = store.persistEligibleTransaction(claim, input, now + 3)
        database.close()
        open()
        val saved = database.transactionDao().getBySourceEvent(sourceId, eventId)
        assertNotNull(saved)
        assertEquals(receipt.transactionId, saved!!.id)
        assertEquals(1_000L, saved.exactMinorUnits)
        assertEquals(1, database.transactionRevisionDao().getHistory(saved.id).size)
        assertEquals("persisted", database.smsProcessingDao().getOperation(first.id)?.state)
        assertEquals("persist", database.smsProcessingDao().getPersistenceDecision(first.id)?.result)

        val retry = first.copy(
            id = UUID.randomUUID().toString(), parentOperationId = first.id,
            trigger = "retry", createdAt = now + 4, updatedAt = now + 4)
        store.createOperation(retry)
        val retryClaim = store.claim(retry.id, now + 5)
        val duplicates = store.duplicateMatchCounts(
            retry.id, sourceId, eventId, input.transactionFingerprint)
        assertEquals(1, duplicates.persistedSourceEvents)
        assertTrue(runCatching {
            store.persistEligibleTransaction(retryClaim, input, now + 6)
        }.isFailure)
        assertEquals(1, database.transactionDao().count())
        assertEquals(1, database.transactionRevisionDao().getHistory(saved.id).size)
        assertNull(database.smsProcessingDao().getReconstructedResult(retry.id))
        assertNull(database.smsProcessingDao().getPersistenceDecision(retry.id))
        assertEquals("claimed", database.smsProcessingDao().getOperation(retry.id)?.state)
    }

    @Test fun newerReplayReviewSurvivesOlderClaimRecoveryAfterEncryptedReopen() = runBlocking {
        val first = admitV5()
        val firstClaim = store.claim(first.id, 1_001)
        val replay = first.copy(
            id = UUID.randomUUID().toString(),
            stableEventId = UUID.randomUUID().toString(),
            createdAt = 2_000, updatedAt = 2_000)
        store.createOperation(replay)
        val replayClaim = store.claim(replay.id, 2_001)
        val reviewId = store.retainForReview(
            replayClaim, listOf("extractor_malformed_json"), 2_002)
        database.close()
        open()

        assertEquals(1, store.recoverExpiredOperations(firstClaim.expiresAt + 1))
        assertEquals(0, store.recoverExpiredOperations(firstClaim.expiresAt + 1))
        assertEquals(1, reviewCount())
        val review = database.smsProcessingDao().getReviewCase(reviewId)!!
        assertEquals(replay.id, review.currentOperationId)
        assertEquals("[\"extractor_malformed_json\"]", review.reasonCodesJson)
        assertEquals(replay.id, store.reviewV2Extension(reviewId)?.operationId)
        assertEquals("interrupted", database.smsProcessingDao().getOperation(first.id)?.state)
        assertEquals("retain_review", database.smsProcessingDao().getOperation(replay.id)?.state)
    }

    @Test fun newerReplayReusesRecoveredV5ReviewAfterEncryptedReopen() = runBlocking {
        val first = admitV5()
        val firstClaim = store.claim(first.id, 1_001)
        assertEquals(1, store.recoverExpiredOperations(firstClaim.expiresAt + 1))
        val recoveredReview = database.smsProcessingDao().getReviewCaseForOperation(first.id)!!
        database.close()
        open()

        val replayAt = firstClaim.expiresAt + 2
        val replay = first.copy(
            id = UUID.randomUUID().toString(),
            stableEventId = UUID.randomUUID().toString(),
            createdAt = replayAt, updatedAt = replayAt)
        store.createOperation(replay)
        val replayClaim = store.claim(replay.id, replayAt + 1)
        val reviewId = store.retainForReview(
            replayClaim, listOf("extractor_malformed_json"), replayAt + 2)

        assertEquals(recoveredReview.id, reviewId)
        assertEquals(1, reviewCount())
        val review = database.smsProcessingDao().getReviewCase(reviewId)!!
        assertEquals(replay.id, review.currentOperationId)
        assertEquals("[\"extractor_malformed_json\"]", review.reasonCodesJson)
        assertEquals(replay.id, store.reviewV2Extension(reviewId)?.operationId)
        assertTrue(review.stableEventIdsJson.contains(first.stableEventId))
        assertTrue(review.stableEventIdsJson.contains(replay.stableEventId))
    }

    @Test fun replayDoesNotOverwriteOwnerEditedReview() = runBlocking {
        val first = admitV5()
        val firstClaim = store.claim(first.id, 1_001)
        val firstReviewId = store.retainForReview(
            firstClaim, listOf("extractor_malformed_json"), 1_002)
        reviewRepository().resolve(SmsReviewCommand(
            UUID.randomUUID().toString(), firstReviewId, 0, SmsReviewAction.SAVE_DRAFT,
            listOf(SmsFieldCorrection(
                "amount_minor_units",
                SmsFieldGroundingClassification.SUPPLIED_MANUAL_UNGROUNDED_VALUE,
                null, null, null, "123"))
        ), 1_003)
        database.close()
        open()

        val replay = first.copy(
            id = UUID.randomUUID().toString(),
            stableEventId = UUID.randomUUID().toString(),
            createdAt = 2_000, updatedAt = 2_000)
        store.createOperation(replay)
        val replayClaim = store.claim(replay.id, 2_001)
        val replayReviewId = store.retainForReview(
            replayClaim, listOf("extractor_evidence_mismatch"), 2_002)

        assertNotEquals(firstReviewId, replayReviewId)
        assertEquals(2, reviewCount())
        val edited = database.smsProcessingDao().getReviewCase(firstReviewId)!!
        assertEquals(first.id, edited.currentOperationId)
        assertEquals("draft", edited.state)
        assertEquals(1L, edited.revision)
        assertEquals(replay.id,
            database.smsProcessingDao().getReviewCase(replayReviewId)?.currentOperationId)
    }

    private fun reviewCount(): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM sms_review_cases").use {
            assertTrue(it.moveToFirst())
            it.getInt(0)
        }

    private suspend fun admitV5(): SmsProcessingOperationEntity {
        val sourceId = UUID.randomUUID().toString()
        store.admitSource(AdmittedSmsSourceEntity(
            sourceId, "synthetic", sourceId, null, SmsProcessingStore.sha256(sourceId),
            null, "TEST", "Invented payment alert for replay testing.",
            1_000, 1, "diagnostic", UUID.randomUUID().toString(), 1_000, "retained"))
        return SmsProcessingOperationEntity(
            UUID.randomUUID().toString(), sourceId, null, UUID.randomUUID().toString(),
            "realtime", "{}", SmsProcessingStore.sha256("{}"),
            "native-integration-v5", "admitted", 0, null, 0, null,
            1_000, 1_000, null, null, 0
        ).also { store.createOperation(it) }
    }

    private fun reviewRepository() = SmsReviewRepository(database, database.smsProcessingDao(),
        database.transactionDao(), database.transactionRevisionDao(), database.accountDao())

    private suspend fun admit(): SmsProcessingOperationEntity {
        val sourceId = UUID.randomUUID().toString()
        store.admitSource(AdmittedSmsSourceEntity(sourceId, "synthetic", sourceId, null,
            SmsProcessingStore.sha256(sourceId), null, "TEST", "Invented payment alert for recovery testing.",
            1_000, 1, "diagnostic", UUID.randomUUID().toString(), 1_000, "retained"))
        return SmsProcessingOperationEntity(UUID.randomUUID().toString(), sourceId, null,
            UUID.randomUUID().toString(), "diagnostic", "{}", SmsProcessingStore.sha256("{}"),
            "native-integration-v1", "admitted", 0, null, 0, null, 1_000, 1_000, null, null, 0)
            .also { store.createOperation(it) }
    }
}
