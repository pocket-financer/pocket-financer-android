package com.pocketfinancer.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketfinancer.data.db.AppDatabase
import com.pocketfinancer.data.db.entity.AccountEntity
import com.pocketfinancer.data.db.entity.AdmittedSmsSourceEntity
import com.pocketfinancer.data.db.entity.SmsProcessingOperationEntity
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmsAutomaticPersistenceTest {
    private lateinit var database: AppDatabase
    private lateinit var store: SmsProcessingStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = SmsProcessingStore(database, database.smsProcessingDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `failure before owner settlement rolls back every automatic write`() = runBlocking {
        val claim = arrangeClaim()

        assertFails {
            store.persistEligibleTransaction(claim, input(), NOW + 2) {
                error("injected pre-settlement failure")
            }
        }

        assertEquals(0, database.transactionDao().count())
        assertEquals(emptyList(), database.transactionRevisionDao().getHistory(transactionId()))
        assertNull(database.smsProcessingDao().getReconstructedResult(OPERATION_ID))
        assertNull(database.smsProcessingDao().getPersistenceDecision(OPERATION_ID))
        val operation = database.smsProcessingDao().getOperation(OPERATION_ID)!!
        assertEquals("claimed", operation.state)
        assertNull(operation.settledAt)
        assertEquals(claim.ownerToken, operation.ownerToken)
    }

    @Test
    fun `same source event cannot create a second automatic transaction`() = runBlocking {
        val firstClaim = arrangeClaim()
        store.persistEligibleTransaction(firstClaim, input(), NOW + 2)
        store.createOperation(
            operation(
                id = SECOND_OPERATION_ID,
                parentOperationId = OPERATION_ID,
                configurationHash = "c".repeat(64)
            )
        )
        val retryClaim = store.claim(SECOND_OPERATION_ID, NOW + 3)

        assertFails {
            store.persistEligibleTransaction(retryClaim, input(), NOW + 4)
        }

        assertEquals(1, database.transactionDao().count())
        assertEquals(1, database.transactionRevisionDao().getHistory(transactionId()).size)
        assertEquals("persisted", database.smsProcessingDao().getOperation(OPERATION_ID)?.state)
        assertEquals("claimed", database.smsProcessingDao().getOperation(SECOND_OPERATION_ID)?.state)
        assertNull(database.smsProcessingDao().getReconstructedResult(SECOND_OPERATION_ID))
        assertNull(database.smsProcessingDao().getPersistenceDecision(SECOND_OPERATION_ID))
    }

    private suspend fun arrangeClaim(): SmsOperationClaim {
        database.accountDao().insert(
            AccountEntity(ACCOUNT_ID, "A/c XX1234", "Test Bank", "manual", NOW, NOW)
        )
        store.admitSource(
            AdmittedSmsSourceEntity(
                SOURCE_ID,
                "android_sms",
                "provider:test-message",
                "test-message",
                "source-fingerprint",
                null,
                "AX-TEST",
                "INR 10.00 debited from account XX1234 at SHOP.",
                NOW,
                1,
                "manual",
                RECEIPT_ID,
                NOW,
                "admitted"
            )
        )
        store.createOperation(operation(OPERATION_ID, null, "a".repeat(64)))
        return store.claim(OPERATION_ID, NOW + 1)
    }

    private fun operation(
        id: String,
        parentOperationId: String?,
        configurationHash: String
    ) = SmsProcessingOperationEntity(
        id,
        SOURCE_ID,
        parentOperationId,
        EVENT_ID,
        if (parentOperationId == null) "manual" else "retry",
        "{}",
        configurationHash,
        "native-integration-v5",
        "ready",
        0,
        null,
        0,
        null,
        NOW,
        NOW,
        null,
        null,
        0
    )

    private fun input() = SmsAutomaticTransactionInput(
        amountMinorUnits = 1_000,
        currency = "INR",
        direction = "debit",
        counterparty = "shop",
        accountId = ACCOUNT_ID,
        occurredAtEpochMs = NOW,
        timestampProvenance = "platform_received",
        modelIdentifier = "test-model",
        processingResultJson = "{}",
        transactionFingerprint = "b".repeat(64),
        checksJson = "[]",
        accountResolutionJson = "{}"
    )

    private fun transactionId(): String = UUID.nameUUIDFromBytes(
        "$SOURCE_ID|$EVENT_ID".toByteArray(Charsets.UTF_8)
    ).toString()

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val SOURCE_ID = "automatic-source"
        const val ACCOUNT_ID = "account-id"
        const val RECEIPT_ID = "receipt-id"
        const val OPERATION_ID = "11111111-1111-4111-8111-111111111111"
        const val SECOND_OPERATION_ID = "33333333-3333-4333-8333-333333333333"
        const val EVENT_ID = "22222222-2222-4222-8222-222222222222"
    }
}
