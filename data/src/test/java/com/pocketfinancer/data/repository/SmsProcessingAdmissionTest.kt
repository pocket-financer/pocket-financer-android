package com.pocketfinancer.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketfinancer.data.db.AppDatabase
import com.pocketfinancer.data.db.entity.AdmittedSmsSourceEntity
import com.pocketfinancer.data.db.entity.SmsProcessingOperationEntity
import com.pocketfinancer.data.db.entity.TransactionEntity
import com.pocketfinancer.data.model.SmsSourceIdentity
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Invented Room state sequences, not private messages or model-quality evidence. */
@RunWith(RobolectricTestRunner::class)
class SmsProcessingAdmissionTest {
    private lateinit var database: AppDatabase
    private lateinit var store: SmsProcessingStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        store = SmsProcessingStore(database, database.smsProcessingDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `concurrent ordinary deliveries create and claim only one snapshot across store instances`() = runBlocking {
        val otherStore = SmsProcessingStore(database, database.smsProcessingDao())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val snapshotCalls = AtomicInteger()
        val first = async(Dispatchers.IO) {
            store.admitForProcessing(source()) { saved ->
                snapshotCalls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                val operation = operation(saved.id)
                store.createOperation(operation)
                operation.id
            }
        }
        entered.await()
        val second = async(Dispatchers.IO) {
            otherStore.admitForProcessing(source()) {
                snapshotCalls.incrementAndGet()
                error("A concurrent replay must not capture another snapshot")
            }
        }
        release.complete(Unit)
        assertIs<SmsProcessingAdmission.Claimed>(first.await())
        assertIs<SmsProcessingAdmission.Busy>(second.await())
        assertEquals(1, snapshotCalls.get())
        assertEquals(1, count("sms_processing_operations"))
        assertEquals(0, count("sms_review_cases"))
    }

    @Test
    fun `settled review replay preserves evidence snapshot and edited review after settings change`() = runBlocking {
        val first = admit()
        val reviewId = store.retainForReview(first.claim, listOf("runtime_unavailable"), now())
        val dao = database.smsProcessingDao()
        val review = dao.getReviewCase(reviewId)!!
        dao.updateReviewCase(review.copy(state = "draft", revision = 1, draftJson = "{\"owner\":true}"))
        val settled = dao.getOperation(first.operation.id)
        val editedReview = dao.getReviewCase(reviewId)

        val replay = assertIs<SmsProcessingAdmission.Existing>(
            store.admitForProcessing(source().copy(origin = "historical", admittedAt = now())) {
                error("Changed grammar/currency/model settings must not be read on replay")
            }
        )

        assertEquals(reviewId, replay.reviewCaseId)
        assertEquals("draft", replay.reviewState)
        assertEquals(settled, dao.getOperation(first.operation.id))
        assertEquals(editedReview, dao.getReviewCase(reviewId))
        assertEquals(1, count("sms_processing_operations"))
        assertEquals(1, count("sms_review_cases"))
    }

    @Test
    fun `ready snapshot becomes visible retryable recovery instead of current settings resnapshot`() = runBlocking {
        store.admitSource(source())
        val ready = operation(source().id, configuration = "{\"grammar_enabled\":false}")
        store.createOperation(ready)

        val replay = assertIs<SmsProcessingAdmission.Existing>(store.admitForProcessing(source()) {
            error("An existing ready snapshot must never be replaced with current settings")
        })
        val repeated = assertIs<SmsProcessingAdmission.Existing>(store.admitForProcessing(source()) {
            error("Recovery replay must reuse its existing Review")
        })

        assertEquals("interrupted", replay.operation.state)
        assertEquals(ready.configurationJson, replay.operation.configurationJson)
        assertEquals(ready.configurationHash, replay.operation.configurationHash)
        assertTrue(replay.reviewCaseId != null)
        assertEquals(replay.reviewCaseId, repeated.reviewCaseId)
        assertEquals(1, count("sms_review_cases"))
        assertFailsWith<IllegalStateException> { store.claim(ready.id, now()) }
    }

    @Test
    fun `expired first claim recovers once and ordinary replay never invokes extraction again`() = runBlocking {
        val first = admit()
        assertEquals(1, store.recoverExpiredOperations(first.claim.expiresAt + 1))
        assertEquals(0, store.recoverExpiredOperations(first.claim.expiresAt + 1))
        val replay = assertIs<SmsProcessingAdmission.Existing>(store.admitForProcessing(source()) {
            error("Interrupted work requires explicit owner retry")
        })
        assertEquals(first.operation.id, replay.operation.id)
        assertEquals("interrupted", replay.operation.state)
        assertTrue(replay.reviewCaseId != null)
        assertEquals(1, count("sms_processing_operations"))
        assertEquals(1, count("sms_review_cases"))
    }

    @Test
    fun `identity collision checks unchanged body sender timestamp and type without overwriting`() = runBlocking {
        val first = admit()
        val original = database.smsProcessingDao().getSource(first.source.id)
        val conflicts = listOf(
            source().copy(rawMessage = "Different invented text"),
            source().copy(sender = "OTHER-SYNTH"),
            source().copy(sourceTimestamp = SOURCE_TIME + 1),
            source().copy(messageType = 2),
            source().copy(sourceFingerprint = "changed-digest")
        )
        for (incoming in conflicts) {
            assertEquals(SmsProcessingAdmission.Conflict, store.admitForProcessing(incoming) {
                error("An integrity conflict is not new work")
            })
        }
        assertEquals(original, database.smsProcessingDao().getSource(first.source.id))
        assertEquals(1, count("sms_processing_operations"))
        assertEquals(0, count("sms_review_cases"))
    }

    @Test
    fun `valid none replay uses existing retained integrity digest without restoring erased evidence`() = runBlocking {
        val first = admit()
        store.settleDiscarded(first.claim, "valid_none", now())
        val discarded = database.smsProcessingDao().getSource(first.source.id)!!
        assertEquals("", discarded.rawMessage)
        assertEquals("", discarded.sender)

        val replay = assertIs<SmsProcessingAdmission.Existing>(store.admitForProcessing(source()) {
            error("Terminal none replay must make zero new calls")
        })
        assertEquals("discarded", replay.operation.state)
        assertEquals(discarded, database.smsProcessingDao().getSource(first.source.id))
        assertEquals(SmsProcessingAdmission.Conflict,
            store.admitForProcessing(source().copy(sourceFingerprint = "changed-digest")) {
                error("Changed terminal evidence cannot be replayed")
            })
        assertEquals(0, count("sms_review_cases"))
    }

    @Test
    fun `different reliable provider ids with identical evidence remain separate sources`() = runBlocking {
        val first = admit()
        val second = admit(source("other-provider"))
        assertEquals(first.source.sourceFingerprint, second.source.sourceFingerprint)
        assertNotEquals(first.source.id, second.source.id)
        assertNotEquals(first.operation.id, second.operation.id)
        assertEquals(2, count("sms_processing_operations"))
    }

    @Test
    fun `missing configuration retains source and creates no operation before later configured admission`() = runBlocking {
        assertEquals(SmsProcessingAdmission.AwaitingConfiguration,
            store.admitForProcessing(source()) { null })
        assertEquals(0, count("sms_processing_operations"))
        val firstEvidence = database.smsProcessingDao().getSource(source().id)!!
        val admitted = admit(source().copy(admissionReceiptId = "later-receipt", admittedAt = now()))
        assertEquals(firstEvidence, admitted.source)
        assertEquals(1, count("sms_processing_operations"))
    }

    @Test
    fun `explicit retry is one new child preserving parent and cannot steal active ownership`() = runBlocking {
        val first = admit()
        val reviewId = store.retainForReview(first.claim, listOf("runtime_unavailable"), now())
        val parent = database.smsProcessingDao().getOperation(first.operation.id)!!
        assertEquals(parent, store.retryContext(reviewId).operation)
        val child = operation(parent.sourceId, configuration = "{\"grammar_enabled\":true}").copy(
            parentOperationId = parent.id, stableEventId = parent.stableEventId, trigger = "retry"
        )
        val retryClaim = store.createRetryOperationAndClaim(child)
        assertEquals(parent, database.smsProcessingDao().getOperation(parent.id))
        assertEquals(parent.id, database.smsProcessingDao().getOperation(child.id)?.parentOperationId)
        assertFailsWith<SmsAdmissionBusyException> { store.retryContext(reviewId) }
        assertFailsWith<SmsAdmissionBusyException> {
            store.createRetryOperationAndClaim(child.copy(id = UUID.randomUUID().toString()))
        }
        assertIs<SmsProcessingAdmission.Busy>(store.admitForProcessing(source()) {
            error("Ordinary replay cannot steal an owner-requested retry")
        })
        val active = database.smsProcessingDao().getOperation(child.id)!!
        assertEquals(retryClaim.ownerToken, active.ownerToken)
        assertEquals(retryClaim.ownerGeneration, active.ownerGeneration)
        assertEquals(2, count("sms_processing_operations"))
        assertEquals(1, count("sms_review_cases"))
    }

    @Test
    fun `concurrent explicit retries create only one child`() = runBlocking {
        val first = admit()
        store.retainForReview(first.claim, listOf("runtime_unavailable"), now())
        val parent = database.smsProcessingDao().getOperation(first.operation.id)!!
        val results = (1..2).map {
            async(Dispatchers.IO) {
                runCatching {
                    store.createRetryOperationAndClaim(operation(parent.sourceId).copy(
                        parentOperationId = parent.id, stableEventId = parent.stableEventId, trigger = "retry"
                    ))
                }
            }
        }.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        assertIs<SmsAdmissionBusyException>(results.single { it.isFailure }.exceptionOrNull())
        assertEquals(parent, database.smsProcessingDao().getOperation(parent.id))
        assertEquals(2, count("sms_processing_operations"))
    }

    @Test
    fun `owner confirmed ledger outcome is reused by reliable identity not a content fingerprint`() = runBlocking {
        val first = admit()
        store.retainForReview(first.claim, listOf("runtime_unavailable"), now())
        database.transactionDao().insert(TransactionEntity(
            id = "owner-confirmed", amount = 10.0, merchant = "Invented shop", date = SOURCE_TIME,
            type = "debit", accountId = "invented-account", rawMessage = first.source.rawMessage,
            sender = first.source.sender, sourceConnector = first.source.sourceConnector,
            sourceMessageId = first.source.sourceMessageId,
            sourceProviderMessageId = first.source.sourceProviderMessageId,
            sourceFingerprint = first.source.sourceFingerprint, sourceId = first.source.id,
            sourceEventId = first.operation.stableEventId
        ))
        assertEquals(SmsProcessingAdmission.Saved(listOf("owner-confirmed")),
            store.admitForProcessing(source()) { error("Owner confirmation must be reused") })
        admit(source("another-provider"))
        assertEquals(1, database.transactionDao().count())
    }

    @Test
    fun `conflicting ledger only delivery never poisons source and legitimate replay is saved`() = runBlocking {
        val original = source()
        val saved = ledgerTransaction(original)
        database.transactionDao().insert(saved)
        val conflicts = listOf(
            original.copy(rawMessage = "Different invented body"),
            original.copy(sender = "OTHER-SYNTH"),
            original.copy(sourceFingerprint = "different-invented-digest")
        )

        for (incoming in conflicts) {
            assertEquals(SmsProcessingAdmission.Conflict, store.admitForProcessing(incoming) {
                error("Rejected legacy evidence must not create a snapshot")
            })
            assertNull(database.smsProcessingDao().getSource(original.id))
            assertEquals(0, count("sms_admitted_sources"))
        }
        assertEquals(SmsProcessingAdmission.Saved(listOf(saved.id)),
            store.admitForProcessing(original) { error("A legitimate ledger replay is already saved") })
        assertEquals(original, database.smsProcessingDao().getSource(original.id))
        assertEquals(listOf(saved), database.smsProcessingDao().getTransactionsForSourceIdentity(
            original.sourceConnector, original.sourceMessageId
        ))
        assertEquals(0, count("sms_processing_operations"))
    }

    @Test
    fun `ledger only timestamp and type conflicts use immutable source fingerprint`() = runBlocking {
        val original = source()
        val saved = ledgerTransaction(original)
        database.transactionDao().insert(saved)
        val conflicts = listOf(
            source(sourceTimestamp = SOURCE_TIME + 1),
            source(messageType = 2)
        )

        for (incoming in conflicts) {
            assertNotEquals(original.sourceFingerprint, incoming.sourceFingerprint)
            assertEquals(SmsProcessingAdmission.Conflict, store.admitForProcessing(incoming) {
                error("A changed timestamp or message type is not the retained source")
            })
            assertNull(database.smsProcessingDao().getSource(original.id))
        }
        assertEquals(listOf(saved), database.smsProcessingDao().getTransactionsForSourceIdentity(
            original.sourceConnector, original.sourceMessageId
        ))
        assertEquals(0, count("sms_admitted_sources"))
        assertEquals(0, count("sms_processing_operations"))
    }

    @Test
    fun `ledger only source still matches after owner edits transaction occurrence time`() = runBlocking {
        val original = source()
        val saved = ledgerTransaction(original).copy(date = SOURCE_TIME + 86_400_000L, isEdited = true)
        database.transactionDao().insert(saved)

        assertEquals(SmsProcessingAdmission.Saved(listOf(saved.id)),
            store.admitForProcessing(original) { error("Editable date is not source evidence") })
        assertEquals(listOf(saved), database.smsProcessingDao().getTransactionsForSourceIdentity(
            original.sourceConnector, original.sourceMessageId
        ))
        assertEquals(0, count("sms_processing_operations"))
    }

    @Test
    fun `legacy received timestamp fingerprint matches supported incoming sent timestamp alias`() = runBlocking {
        val incoming = source(receivedTimestamp = SOURCE_TIME + 5_000)
        val saved = ledgerTransaction(incoming).copy(
            sourceFingerprint = checkNotNull(incoming.sourceAlternateFingerprint),
            sourceAlternateFingerprint = null,
            date = SOURCE_TIME + 86_400_000L,
            isEdited = true
        )
        database.transactionDao().insert(saved)

        assertNotEquals(saved.sourceFingerprint, incoming.sourceFingerprint)
        assertEquals(SmsProcessingAdmission.Saved(listOf(saved.id)),
            store.admitForProcessing(incoming) { error("The retained timestamp alias is compatible") })
        assertEquals(incoming, database.smsProcessingDao().getSource(incoming.id))
        assertEquals(listOf(saved), database.smsProcessingDao().getTransactionsForSourceIdentity(
            incoming.sourceConnector, incoming.sourceMessageId
        ))
        assertEquals(0, count("sms_processing_operations"))
    }

    @Test
    fun `original v2 and v4 retry claims survive ordinary replay before coordinator execution`() = runBlocking {
        for (version in listOf(2, 4)) {
            val incoming = source("invented-v$version-provider")
            val first = assertIs<SmsProcessingAdmission.Claimed>(store.admitForProcessing(incoming) { saved ->
                val parent = operation(saved.id).copy(contractReleaseId = "native-integration-v$version")
                store.createOperation(parent)
                parent.id
            })
            val reviewId = store.retainForReview(first.claim, listOf("runtime_unavailable"), now())
            val parent = database.smsProcessingDao().getOperation(first.operation.id)!!
            val child = operation(parent.sourceId).copy(
                parentOperationId = parent.id, stableEventId = parent.stableEventId, trigger = "retry",
                contractReleaseId = parent.contractReleaseId
            )
            val created = CompletableDeferred<SmsOperationClaim>()
            val execute = CompletableDeferred<Unit>()
            val retry = async(Dispatchers.IO) {
                val claim = store.createRetryOperationAndClaim(child)
                created.complete(claim)
                execute.await()
                store.transition(claim, "claimed", "analyzed", now())
                store.retainForReview(claim, listOf("runtime_unavailable"), now())
            }
            val claim = created.await()
            try {
                val replay = assertIs<SmsProcessingAdmission.Busy>(store.admitForProcessing(incoming) {
                    error("Ordinary replay cannot interrupt an original-configuration retry")
                })
                assertEquals(child.id, replay.operationId)
                val active = database.smsProcessingDao().getOperation(child.id)!!
                assertEquals("claimed", active.state)
                assertEquals(claim.ownerToken, active.ownerToken)
                assertEquals(claim.ownerGeneration, active.ownerGeneration)
                assertEquals(parent, database.smsProcessingDao().getOperation(parent.id))
                assertEquals(reviewId, store.reviewCaseIdForOperation(parent.id))
                assertNull(store.reviewCaseIdForOperation(child.id))
            } finally {
                execute.complete(Unit)
            }
            assertTrue(retry.await().isNotBlank())
            assertEquals("retain_review", database.smsProcessingDao().getOperation(child.id)?.state)
        }
        assertEquals(4, count("sms_processing_operations"))
    }

    private fun ledgerTransaction(source: AdmittedSmsSourceEntity) = TransactionEntity(
        id = "invented-legacy-transaction", amount = 10.0, merchant = "Invented shop", date = SOURCE_TIME,
        type = "debit", accountId = "invented-account", rawMessage = source.rawMessage,
        sender = source.sender, sourceConnector = source.sourceConnector,
        sourceMessageId = source.sourceMessageId, sourceProviderMessageId = source.sourceProviderMessageId,
        sourceFingerprint = source.sourceFingerprint, sourceAlternateFingerprint = source.sourceAlternateFingerprint
    )

    private suspend fun admit(incoming: AdmittedSmsSourceEntity = source()): SmsProcessingAdmission.Claimed =
        assertIs<SmsProcessingAdmission.Claimed>(store.admitForProcessing(incoming) { saved ->
            val operation = operation(saved.id)
            store.createOperation(operation)
            operation.id
        })

    private fun source(
        providerId: String = "invented-provider",
        sourceTimestamp: Long = SOURCE_TIME,
        messageType: Int = 1,
        receivedTimestamp: Long? = null
    ): AdmittedSmsSourceEntity {
        val body = "Invented INR 10.00 debit alert."
        val identity = SmsSourceIdentity.androidSms(
            providerId, "SYNTH", body, sourceTimestamp, messageType, receivedTimestamp
        )
        return AdmittedSmsSourceEntity(
            id = "source-$providerId", sourceConnector = identity.connector, sourceMessageId = identity.messageId,
            sourceProviderMessageId = providerId, sourceFingerprint = identity.fallbackFingerprint,
            sourceAlternateFingerprint = identity.alternateFingerprint, sender = "SYNTH", rawMessage = body,
            sourceTimestamp = sourceTimestamp, messageType = messageType, origin = "manual",
            admissionReceiptId = "receipt-$providerId", admittedAt = SOURCE_TIME, retentionState = "admitted"
        )
    }

    private fun operation(sourceId: String, configuration: String = "{\"grammar_enabled\":false}") =
        SmsProcessingOperationEntity(
            UUID.randomUUID().toString(), sourceId, null, UUID.randomUUID().toString(), "manual",
            configuration, SmsProcessingStore.sha256(configuration), "native-integration-v6", "ready",
            0, null, 0, null, now(), now(), null, null, 0
        )

    private fun count(table: String): Int = database.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM $table").use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val SOURCE_TIME = 1_700_000_000_000L
    }
}
