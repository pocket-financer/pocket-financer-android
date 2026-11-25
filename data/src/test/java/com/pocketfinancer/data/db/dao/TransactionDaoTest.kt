package com.pocketfinancer.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketfinancer.data.db.AppDatabase
import com.pocketfinancer.data.db.entity.TransactionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
class TransactionDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: TransactionDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.transactionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insert and getById should return the transaction`() {
        runBlocking {
            val tx = TransactionEntity(
                id = UUID.randomUUID().toString(),
                amount = 1500.0,
                merchant = "Amazon",
                date = 1000L,
                type = "debit",
                accountId = "acct-1",
                rawMessage = "Rs.1500 debited from a/c XX6254",
                sender = "AX-HDFCBK"
            )
            dao.insert(tx)
            val loaded = dao.getById(tx.id)
            assertNotNull(loaded)
            assertEquals(tx.id, loaded!!.id)
            assertEquals(1500.0, loaded.amount)
            assertEquals("debit", loaded.type)
        }
    }

    @Test
    fun `getByType should filter transactions`() {
        runBlocking {
            val debit = TransactionEntity(
                id = UUID.randomUUID().toString(), amount = 100.0, merchant = "M1",
                date = 1000L, type = "debit", accountId = "a1",
                rawMessage = "Rs.100 debited", sender = "AX-BANK"
            )
            val credit = TransactionEntity(
                id = UUID.randomUUID().toString(), amount = 500.0, merchant = "M2",
                date = 2000L, type = "credit", accountId = "a2",
                rawMessage = "Rs.500 credited", sender = "AX-BANK"
            )
            dao.insertAll(listOf(debit, credit))

            val debits = dao.getByType("debit").first()
            assertEquals(1, debits.size)
            assertEquals(100.0, debits[0].amount)

            val credits = dao.getByType("credit").first()
            assertEquals(1, credits.size)
            assertEquals(500.0, credits[0].amount)
        }
    }

    @Test
    fun `getByDateRange should return transactions in range`() {
        runBlocking {
            val old = TransactionEntity(
                id = UUID.randomUUID().toString(), amount = 100.0, merchant = "Old",
                date = 1000L, type = "debit", accountId = "a1",
                rawMessage = "old", sender = "AX-BANK"
            )
            val mid = TransactionEntity(
                id = UUID.randomUUID().toString(), amount = 200.0, merchant = "Mid",
                date = 2000L, type = "debit", accountId = "a1",
                rawMessage = "mid", sender = "AX-BANK"
            )
            val recent = TransactionEntity(
                id = UUID.randomUUID().toString(), amount = 300.0, merchant = "Recent",
                date = 3000L, type = "credit", accountId = "a2",
                rawMessage = "recent", sender = "AX-BANK"
            )
            dao.insertAll(listOf(old, mid, recent))

            val inRange = dao.getByDateRange(1500L, 2500L).first()
            assertEquals(1, inRange.size)
            assertEquals("Mid", inRange[0].merchant)
        }
    }

    @Test
    fun `getByAccount should filter by account`() {
        runBlocking {
            val tx1 = TransactionEntity(
                id = UUID.randomUUID().toString(), amount = 100.0, merchant = "M1",
                date = 1000L, type = "debit", accountId = "acct-A",
                rawMessage = "msg1", sender = "AX-BANK"
            )
            val tx2 = TransactionEntity(
                id = UUID.randomUUID().toString(), amount = 200.0, merchant = "M2",
                date = 2000L, type = "credit", accountId = "acct-B",
                rawMessage = "msg2", sender = "AX-BANK"
            )
            dao.insertAll(listOf(tx1, tx2))

            val accountA = dao.getByAccount("acct-A").first()
            assertEquals(1, accountA.size)
            assertEquals(100.0, accountA[0].amount)
        }
    }

    @Test
    fun `explicit update changes editable fields without replace semantics`() {
        runBlocking {
            val id = UUID.randomUUID().toString()
            val original = TransactionEntity(
                id = id, amount = 100.0, merchant = "Original",
                date = 1000L, type = "debit", accountId = "a1",
                rawMessage = "original", sender = "AX-BANK"
            )
            dao.insert(original)

            val updated = original.copy(
                amount = 999.0,
                merchant = "Updated",
                type = "credit"
            )
            dao.update(updated)

            val loaded = dao.getById(id)
            assertNotNull(loaded)
            assertEquals(999.0, loaded!!.amount)
            assertEquals("credit", loaded.type)
        }
    }

    @Test
    fun `count should return correct number`() {
        runBlocking {
            assertEquals(0, dao.count())
            dao.insert(TransactionEntity(
                id = "t1", amount = 100.0, merchant = "M", date = 1000L,
                type = "debit", accountId = "a1", rawMessage = "m", sender = "AX-BANK"
            ))
            assertEquals(1, dao.count())
            dao.insert(TransactionEntity(
                id = "t2", amount = 200.0, merchant = "M2", date = 2000L,
                type = "credit", accountId = "a2", rawMessage = "m2", sender = "AX-BANK"
            ))
            assertEquals(2, dao.count())
        }
    }

    @Test
    fun `getRecent should return latest N`() {
        runBlocking {
            val txs = (1..5).map { i ->
                TransactionEntity(
                    id = UUID.randomUUID().toString(), amount = i * 100.0, merchant = "M$i",
                    date = i * 1000L, type = "debit", accountId = "a1",
                    rawMessage = "m$i", sender = "AX-BANK"
                )
            }
            dao.insertAll(txs)

            val recent = dao.getRecent(3).first()
            assertEquals(3, recent.size)
            assertEquals("M5", recent[0].merchant)
            assertEquals("M4", recent[1].merchant)
            assertEquals("M3", recent[2].merchant)
        }
    }

    @Test
    fun `exists should return true when a matching transaction is found`() {
        runBlocking {
            val tx = TransactionEntity(
                id = "tx1", amount = 100.0, merchant = "M", date = 1000L,
                type = "debit", accountId = "a1", rawMessage = "m", sender = "AX-BANK"
            )
            dao.insert(tx)

            assertTrue(dao.exists("AX-BANK", 1000L))
            assertFalse(dao.exists("AX-BANK", 2000L))
            assertFalse(dao.exists("AX-OTHER", 1000L))
        }
    }

    @Test
    fun `sumByTypeSince should return sum of matching transactions`() {
        runBlocking {
            val now = 5000L
            val tx1 = TransactionEntity(
                id = "t1", amount = 100.0, merchant = "M1", date = now - 1000,
                type = "debit", accountId = "a1", rawMessage = "m1", sender = "AX-BANK"
            )
            val tx2 = TransactionEntity(
                id = "t2", amount = 200.0, merchant = "M2", date = now - 500,
                type = "debit", accountId = "a1", rawMessage = "m2", sender = "AX-BANK"
            )
            val tx3 = TransactionEntity(
                id = "t3", amount = 300.0, merchant = "M3", date = now - 2000,
                type = "debit", accountId = "a1", rawMessage = "m3", sender = "AX-BANK"
            )
            val creditTx = TransactionEntity(
                id = "t4", amount = 500.0, merchant = "M4", date = now - 1000,
                type = "credit", accountId = "a1", rawMessage = "m4", sender = "AX-BANK"
            )
            dao.insertAll(listOf(tx1, tx2, tx3, creditTx))

            val sumDebit = dao.sumByTypeSince("debit", now - 1500)
            assertEquals(300.0, sumDebit)
            
            val sumCredit = dao.sumByTypeSince("credit", now - 1500)
            assertEquals(500.0, sumCredit)
            
            val sumNone = dao.sumByTypeSince("debit", now + 1000)
            assertNull(sumNone)
        }
    }

    @Test
    fun `updateTransactionsAccount should reassign account ID of transactions`() {
        runBlocking {
            val tx1 = TransactionEntity(
                id = "t1", amount = 100.0, merchant = "M1", date = 1000L,
                type = "debit", accountId = "old_acct", rawMessage = "m1", sender = "AX-BANK"
            )
            val tx2 = TransactionEntity(
                id = "t2", amount = 200.0, merchant = "M2", date = 2000L,
                type = "debit", accountId = "old_acct", rawMessage = "m2", sender = "AX-BANK"
            )
            val tx3 = TransactionEntity(
                id = "t3", amount = 300.0, merchant = "M3", date = 3000L,
                type = "debit", accountId = "other_acct", rawMessage = "m3", sender = "AX-BANK"
            )
            dao.insertAll(listOf(tx1, tx2, tx3))

            dao.updateTransactionsAccount("old_acct", "new_acct")

            val tx1Updated = dao.getById("t1")
            assertEquals("new_acct", tx1Updated?.accountId)

            val tx2Updated = dao.getById("t2")
            assertEquals("new_acct", tx2Updated?.accountId)

            val tx3Updated = dao.getById("t3")
            assertEquals("other_acct", tx3Updated?.accountId)
        }
    }

    @Test
    fun `countMatchingTransactionsInWindow enforces 5 minute window and dimensions`() {
        runBlocking {
            val baseTime = 1_700_000_000_000L
            dao.insert(
                TransactionEntity(
                    id = "tx-base",
                    amount = 500.0,
                    merchant = "Cafe",
                    date = baseTime,
                    type = "debit",
                    accountId = "acct-1",
                    rawMessage = "INR 500.00 debited from XX1234",
                    sender = "SYNTH",
                    sourceId = "src-1",
                    sourceEventId = "evt-1",
                    exactMinorUnits = 50_000L,
                    currencyCode = "INR",
                    currencyScale = 2
                )
            )

            assertEquals(
                1,
                dao.countMatchingTransactionsInWindow(
                    accountId = "acct-1",
                    direction = "debit",
                    minorUnits = 50_000L,
                    currency = "INR",
                    occurredAtEpochMs = baseTime - 300_000L,
                    windowMs = 300_000L,
                    excludeSourceId = "src-2",
                    excludeOperationId = "op-2"
                )
            )
            assertEquals(
                0,
                dao.countMatchingTransactionsInWindow(
                    accountId = "acct-1",
                    direction = "debit",
                    minorUnits = 50_000L,
                    currency = "INR",
                    occurredAtEpochMs = baseTime,
                    windowMs = 300_000L,
                    excludeSourceId = "src-1",
                    excludeOperationId = "op-2"
                )
            )
            assertEquals(
                0,
                dao.countMatchingTransactionsInWindow(
                    accountId = "acct-1",
                    direction = "debit",
                    minorUnits = 50_000L,
                    currency = "INR",
                    occurredAtEpochMs = baseTime + 300_001L,
                    windowMs = 300_000L,
                    excludeSourceId = "src-2",
                    excludeOperationId = "op-2"
                )
            )
            assertEquals(
                0,
                dao.countMatchingTransactionsInWindow(
                    accountId = "acct-1",
                    direction = "credit",
                    minorUnits = 50_000L,
                    currency = "INR",
                    occurredAtEpochMs = baseTime + 1_000L,
                    windowMs = 300_000L,
                    excludeSourceId = "src-2",
                    excludeOperationId = "op-2"
                )
            )
            assertEquals(
                0,
                dao.countMatchingTransactionsInWindow(
                    accountId = "acct-1",
                    direction = "debit",
                    minorUnits = 50_000L,
                    currency = "USD",
                    occurredAtEpochMs = baseTime + 1_000L,
                    windowMs = 300_000L,
                    excludeSourceId = "src-2",
                    excludeOperationId = "op-2"
                )
            )
        }
    }
}
