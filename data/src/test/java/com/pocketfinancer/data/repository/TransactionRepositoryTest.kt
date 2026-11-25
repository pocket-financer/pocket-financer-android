package com.pocketfinancer.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketfinancer.data.db.AppDatabase
import com.pocketfinancer.data.model.TransactionType
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TransactionRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var accountRepo: AccountRepository
    private lateinit var repo: TransactionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        accountRepo = AccountRepository(db.accountDao(), db.transactionDao(), runConsolidationOnInit = false)
        repo = TransactionRepository(db, db.transactionDao(), accountRepo, db.queuedSmsCandidateDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insert creates transaction with UUID and query filters work accurately`() = runBlocking {
        val account = accountRepo.getOrCreate("A/c XX6254", "Synthetic Bank", "auto-extracted")
        val now = 1_700_000_000_000L
        val tx1 = repo.insert(
            TransactionRepository.NewTransaction(
                amount = 1500.0,
                merchant = "Demo Store",
                date = now - 1_000L,
                type = TransactionType.DEBIT,
                accountId = account.id,
                rawMessage = "Rs.1500 debited",
                sender = "AX-SYNTH"
            )
        )
        repo.insert(
            TransactionRepository.NewTransaction(
                amount = 500.0,
                merchant = "Refund Source",
                date = now - 500L,
                type = TransactionType.CREDIT,
                accountId = account.id,
                rawMessage = "Rs.500 credited",
                sender = "AX-SYNTH"
            )
        )

        assertNotNull(tx1.id)
        assertEquals(2, repo.count())
        assertEquals(2, repo.getAllByDateDesc().first().size)
        assertEquals(1500.0, repo.sumDebitsSince(now - 5_000L))
        assertEquals(500.0, repo.sumCreditsSince(now - 5_000L))
        assertEquals(1, repo.getByType(TransactionType.DEBIT).first().size)
        assertEquals(1, repo.getByType(TransactionType.CREDIT).first().size)
        assertEquals(1, repo.getByDateRange(now - 750L, now).first().size)
        assertTrue(repo.exists("AX-SYNTH", now - 1_000L))
        assertFalse(repo.exists("AX-SYNTH", now + 1_000L))
    }

    @Test
    fun `editProjection appends revision and feedback idempotently and enforces optimistic lock`() =
        runBlocking {
            val account = accountRepo.ensureDefault()
            val saved = repo.insert(
                TransactionRepository.NewTransaction(
                    amount = 100.0,
                    merchant = "Old Merchant",
                    date = 1_000L,
                    type = TransactionType.DEBIT,
                    accountId = account.id,
                    rawMessage = "Rs 100 spent",
                    sender = "AX-SYNTH"
                )
            )
            val actionId = UUID.randomUUID().toString()
            val command = TransactionRepository.ProjectionEditCommand(
                actionId = actionId,
                transactionId = saved.id,
                expectedRevisionId = saved.currentRevisionId,
                amountText = "245.50",
                currencyCode = "INR",
                merchant = "Corrected Merchant",
                type = TransactionType.CREDIT,
                accountId = account.id
            )

            val first = repo.editProjection(command)
            val replay = repo.editProjection(command)

            assertFalse(first.replayed)
            assertTrue(replay.replayed)
            assertEquals(24_550L, first.transaction.exactMinorUnits)
            assertEquals("INR", first.transaction.currencyCode)
            assertEquals("Corrected Merchant", first.transaction.merchant)
            assertEquals(TransactionType.CREDIT, first.transaction.type)
            assertTrue(first.transaction.isEdited)
            assertEquals(2, db.transactionRevisionDao().getHistory(saved.id).size)

            // Stale revision id is rejected cleanly.
            assertFails {
                repo.editProjection(
                    command.copy(
                        actionId = UUID.randomUUID().toString(),
                        expectedRevisionId = "stale-revision",
                        merchant = "Another Edit"
                    )
                )
            }
            // Unsupported currency or negative/zero amount is rejected cleanly.
            assertFails {
                repo.editProjection(
                    command.copy(
                        actionId = UUID.randomUUID().toString(),
                        expectedRevisionId = first.transaction.currentRevisionId,
                        amountText = "0.00"
                    )
                )
            }
            assertEquals(2, db.transactionRevisionDao().getHistory(saved.id).size)
        }

    @Test
    fun `updateTransaction returns null when transaction id is missing and clearDatabase wipes all tables`() =
        runBlocking {
            val account = accountRepo.ensureDefault()
            assertNull(
                @Suppress("DEPRECATION")
                repo.updateTransaction("missing", 200.0, "Merchant", TransactionType.CREDIT, account.id)
            )

            repo.insert(
                TransactionRepository.NewTransaction(
                    100.0, "M1", 1_000L, TransactionType.DEBIT, account.id, "m1", "AX-SYNTH"
                )
            )
            assertEquals(1, repo.count())

            repo.clearDatabase()
            assertEquals(0, repo.count())
        }
}
