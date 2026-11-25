package com.pocketfinancer.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketfinancer.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
class AccountRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AccountRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = AccountRepository(db.accountDao(), db.transactionDao(), runConsolidationOnInit = false)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `getOrCreate should create new account when not found`() {
        runBlocking {
            val account = repo.getOrCreate("A/c XX6254", "HDFC Bank", "auto-extracted")
            assertEquals("HDFC Bank A/c XX6254", account.name)
            assertEquals("HDFC Bank", account.bank)
            assertNotNull(account.id)
        }
    }

    @Test
    fun `concurrent account creation returns one named and one default identity`() =
        runBlocking {
            val named = coroutineScope {
                List(12) {
                    async(Dispatchers.Default) {
                        repo.getOrCreate(
                            "A/c XX6254",
                            "HDFC Bank",
                            "auto-extracted"
                        )
                    }
                }.awaitAll()
            }
            val defaults = coroutineScope {
                List(12) {
                    async(Dispatchers.Default) {
                        repo.ensureDefault()
                    }
                }.awaitAll()
            }

            assertEquals(1, named.map { it.id }.distinct().size)
            assertEquals(1, defaults.map { it.id }.distinct().size)
            assertEquals(2, repo.getAllOnce().size)
        }

    @Test
    fun `getOrCreate should return existing account when found`() {
        runBlocking {
            val first = repo.getOrCreate("A/c XX6254", "HDFC Bank", "auto-extracted")
            val second = repo.getOrCreate("A/c XX6254", "HDFC Bank", "auto-extracted")
            assertEquals(first.id, second.id)
        }
    }

    @Test
    fun `getOrCreate should not match on name alone without matching bank`() {
        runBlocking {
            val first = repo.getOrCreate("A/c XX6254", "HDFC Bank", "auto-extracted")
            val second = repo.getOrCreate("A/c XX6254", "SBI", "auto-extracted")
            assertNotEquals(first.id, second.id)
        }
    }

    @Test
    fun `getOrCreate keeps card and deposit with the same suffix distinct`() {
        runBlocking {
            val deposit = repo.getOrCreate(
                "A/c XX6254",
                "HDFC Bank",
                "auto-extracted"
            )
            val card = repo.getOrCreate(
                "Card XX6254",
                "HDFC Bank",
                "auto-extracted"
            )

            assertNotEquals(deposit.id, card.id)
            assertEquals("HDFC Bank A/c XX6254", deposit.name)
            assertEquals("HDFC Bank Card XX6254", card.name)
            assertEquals(2, repo.getAllOnce().size)
        }
    }

    @Test
    fun `unknown bank does not alias either of multiple known banks`() {
        runBlocking {
            val hdfc = repo.getOrCreate(
                "A/c XX6254",
                "HDFC Bank",
                "auto-extracted"
            )
            val sbi = repo.getOrCreate(
                "A/c XX6254",
                "State Bank of India",
                "auto-extracted"
            )
            val unknown = repo.getOrCreate(
                "A/c XX6254",
                "Unknown Account",
                "auto-extracted"
            )
            val unknownRetry = repo.getOrCreate(
                "A/c XX6254",
                "Unknown Account",
                "auto-extracted"
            )

            assertNotEquals(hdfc.id, sbi.id)
            assertNotEquals(hdfc.id, unknown.id)
            assertNotEquals(sbi.id, unknown.id)
            assertEquals(unknown.id, unknownRetry.id)
            assertEquals("Unknown Account", unknown.bank)
            assertEquals(3, repo.getAllOnce().size)
        }
    }

    @Test
    fun `ensureDefault should create UNKNOWN account on first call`() {
        runBlocking {
            val account = repo.ensureDefault()
            assertEquals("__UNKNOWN__", account.name)
            assertEquals("Unknown Account", account.bank)
            assertEquals("auto-extracted", account.type)
        }
    }

    @Test
    fun `ensureDefault should be idempotent`() {
        runBlocking {
            val first = repo.ensureDefault()
            val second = repo.ensureDefault()
            assertEquals(first.id, second.id)
        }
    }

    @Test
    fun `getAll should return all accounts`() {
        runBlocking {
            repo.getOrCreate("A/c XX6254", "HDFC Bank", "auto-extracted")
            repo.getOrCreate("A/c XX0000", "SBI", "auto-extracted")
            val all = repo.getAll().first()
            assertEquals(2, all.size)
        }
    }

    @Test
    fun `consolidateAccounts should merge duplicate accounts and update transaction references`() {
        runBlocking {
            val acc1 = com.pocketfinancer.data.db.entity.AccountEntity("id1", "Unknown Account A/c XX9141", "Unknown Account", "auto-extracted")
            val acc2 = com.pocketfinancer.data.db.entity.AccountEntity("id2", "A/C **9141", "Unknown Account", "auto-extracted")
            val acc3 = com.pocketfinancer.data.db.entity.AccountEntity("id3", "HDFC Bank A/c XX9141", "HDFC Bank", "auto-extracted")
            
            db.accountDao().insert(acc1)
            db.accountDao().insert(acc2)
            db.accountDao().insert(acc3)

            val tx1 = com.pocketfinancer.data.db.entity.TransactionEntity("tx1", 100.0, "M1", 1000L, "DEBIT", "id1", "raw1", "sender1", false)
            val tx2 = com.pocketfinancer.data.db.entity.TransactionEntity("tx2", 200.0, "M2", 2000L, "DEBIT", "id2", "raw2", "sender2", false)
            val tx3 = com.pocketfinancer.data.db.entity.TransactionEntity("tx3", 300.0, "M3", 3000L, "DEBIT", "id3", "raw3", "sender3", false)

            db.transactionDao().insert(tx1)
            db.transactionDao().insert(tx2)
            db.transactionDao().insert(tx3)

            repo.consolidateAccounts()

            val allAccs = repo.getAllOnce()
            assertEquals(1, allAccs.size)
            assertEquals("id3", allAccs[0].id)
            assertEquals("HDFC Bank A/c XX9141", allAccs[0].name)

            val tx1Updated = db.transactionDao().getById("tx1")
            val tx2Updated = db.transactionDao().getById("tx2")
            val tx3Updated = db.transactionDao().getById("tx3")

            assertEquals("id3", tx1Updated?.accountId)
            assertEquals("id3", tx2Updated?.accountId)
            assertEquals("id3", tx3Updated?.accountId)
        }
    }

    @Test
    fun `startup consolidation finishes before returned account is referenced`() {
        runBlocking {
            val stale = com.pocketfinancer.data.db.entity.AccountEntity(
                "stale",
                "Unknown Account A/c XX9141",
                "Unknown Account",
                "auto-extracted"
            )
            val canonical = com.pocketfinancer.data.db.entity.AccountEntity(
                "canonical",
                "HDFC Bank A/c XX9141",
                "HDFC Bank",
                "auto-extracted"
            )
            db.accountDao().insert(stale)
            db.accountDao().insert(canonical)
            val startupRepository = AccountRepository(
                db.accountDao(),
                db.transactionDao(),
                runConsolidationOnInit = true
            )

            val returned = startupRepository.getOrCreate(
                "A/c XX9141",
                "Unknown Account",
                "auto-extracted"
            )
            db.transactionDao().insert(
                com.pocketfinancer.data.db.entity.TransactionEntity(
                    id = "startup-transaction",
                    amount = 100.0,
                    merchant = "Merchant",
                    date = 1_000L,
                    type = "debit",
                    accountId = returned.id,
                    rawMessage = "Rs 100 debited",
                    sender = "AX-BANK"
                )
            )

            assertEquals(canonical.id, returned.id)
            assertNull(db.accountDao().getById(stale.id))
            assertNotNull(db.accountDao().getById(returned.id))
            assertEquals(
                returned.id,
                db.transactionDao().getById("startup-transaction")?.accountId
            )
        }
    }

    @Test
    fun `startup consolidation runs before first account flow emission`() {
        runBlocking {
            db.accountDao().insert(
                com.pocketfinancer.data.db.entity.AccountEntity(
                    "read-stale",
                    "Unknown Account A/c XX9141",
                    "Unknown Account",
                    "auto-extracted"
                )
            )
            db.accountDao().insert(
                com.pocketfinancer.data.db.entity.AccountEntity(
                    "read-canonical",
                    "HDFC Bank A/c XX9141",
                    "HDFC Bank",
                    "auto-extracted"
                )
            )
            val startupRepository = AccountRepository(
                db.accountDao(),
                db.transactionDao(),
                runConsolidationOnInit = true
            )

            val accounts = startupRepository.getAll().first()

            assertEquals(1, accounts.size)
            assertEquals("read-canonical", accounts.single().id)
            assertNull(db.accountDao().getById("read-stale"))
        }
    }

    @Test
    fun `ledger-only startup consolidates accounts before first transaction emission`() {
        runBlocking {
            db.accountDao().insert(
                com.pocketfinancer.data.db.entity.AccountEntity(
                    "ledger-stale",
                    "Unknown Account A/c XX9141",
                    "Unknown Account",
                    "auto-extracted"
                )
            )
            db.accountDao().insert(
                com.pocketfinancer.data.db.entity.AccountEntity(
                    "ledger-canonical",
                    "HDFC Bank A/c XX9141",
                    "HDFC Bank",
                    "auto-extracted"
                )
            )
            db.transactionDao().insert(
                com.pocketfinancer.data.db.entity.TransactionEntity(
                    id = "ledger-transaction",
                    amount = 100.0,
                    merchant = "Merchant",
                    date = 1_000L,
                    type = "debit",
                    accountId = "ledger-stale",
                    rawMessage = "Rs 100 debited",
                    sender = "AX-BANK"
                )
            )
            val startupAccountRepository = AccountRepository(
                db.accountDao(),
                db.transactionDao(),
                runConsolidationOnInit = true
            )
            val transactionRepository = TransactionRepository(
                db,
                db.transactionDao(),
                startupAccountRepository,
                db.queuedSmsCandidateDao()
            )

            val ledger = transactionRepository.getAllByDateDesc().first()

            assertEquals(1, ledger.size)
            assertEquals("ledger-canonical", ledger.single().accountId)
            assertEquals(
                "HDFC Bank A/c XX9141",
                ledger.single().accountLabel
            )
            assertNull(db.accountDao().getById("ledger-stale"))
            assertEquals(
                "ledger-canonical",
                db.transactionDao()
                    .getById("ledger-transaction")
                    ?.accountId
            )
        }
    }

    @Test
    fun `automatic consolidation is a one-time barrier and explicit runs stay safe`() {
        runBlocking {
            db.accountDao().insert(
                com.pocketfinancer.data.db.entity.AccountEntity(
                    "initial-unknown",
                    "Unknown Account A/c XX9141",
                    "Unknown Account",
                    "auto-extracted"
                )
            )
            db.accountDao().insert(
                com.pocketfinancer.data.db.entity.AccountEntity(
                    "canonical",
                    "HDFC Bank A/c XX9141",
                    "HDFC Bank",
                    "auto-extracted"
                )
            )
            val startupRepository = AccountRepository(
                db.accountDao(),
                db.transactionDao(),
                runConsolidationOnInit = true
            )

            startupRepository.ensureDefault()
            assertNull(db.accountDao().getById("initial-unknown"))

            db.accountDao().insert(
                com.pocketfinancer.data.db.entity.AccountEntity(
                    "late-duplicate",
                    "HDFC A/c XX9141",
                    "HDFC Bank",
                    "auto-extracted"
                )
            )
            startupRepository.ensureDefault()

            // A second entrypoint call does not silently rerun startup work.
            assertNotNull(db.accountDao().getById("late-duplicate"))

            startupRepository.consolidateAccounts()
            startupRepository.consolidateAccounts()

            assertNull(db.accountDao().getById("late-duplicate"))
            assertNotNull(db.accountDao().getById("canonical"))
        }
    }

    @Test
    fun `consolidation does not merge card and deposit with the same suffix`() {
        runBlocking {
            val deposit = com.pocketfinancer.data.db.entity.AccountEntity(
                "deposit",
                "HDFC Bank A/c XX9141",
                "HDFC Bank",
                "auto-extracted"
            )
            val card = com.pocketfinancer.data.db.entity.AccountEntity(
                "card",
                "HDFC Bank Card XX9141",
                "HDFC Bank",
                "auto-extracted"
            )
            db.accountDao().insert(deposit)
            db.accountDao().insert(card)
            db.transactionDao().insert(
                com.pocketfinancer.data.db.entity.TransactionEntity(
                    "deposit-tx",
                    100.0,
                    "Deposit merchant",
                    1_000L,
                    "debit",
                    deposit.id,
                    "deposit raw",
                    "AX-BANK"
                )
            )
            db.transactionDao().insert(
                com.pocketfinancer.data.db.entity.TransactionEntity(
                    "card-tx",
                    200.0,
                    "Card merchant",
                    2_000L,
                    "debit",
                    card.id,
                    "card raw",
                    "AX-BANK"
                )
            )

            repo.consolidateAccounts()

            assertNotNull(db.accountDao().getById(deposit.id))
            assertNotNull(db.accountDao().getById(card.id))
            assertEquals(
                deposit.id,
                db.transactionDao().getById("deposit-tx")?.accountId
            )
            assertEquals(
                card.id,
                db.transactionDao().getById("card-tx")?.accountId
            )
        }
    }

    @Test
    fun `consolidation keeps ambiguous unknown separate from multiple banks`() {
        runBlocking {
            val hdfc = com.pocketfinancer.data.db.entity.AccountEntity(
                "hdfc",
                "HDFC Bank A/c XX9141",
                "HDFC Bank",
                "auto-extracted"
            )
            val sbi = com.pocketfinancer.data.db.entity.AccountEntity(
                "sbi",
                "SBI A/c XX9141",
                "State Bank of India",
                "auto-extracted"
            )
            val unknownCanonical =
                com.pocketfinancer.data.db.entity.AccountEntity(
                    "unknown-canonical",
                    "Unknown Account A/c XX9141",
                    "Unknown Account",
                    "auto-extracted"
                )
            val unknownDuplicate =
                com.pocketfinancer.data.db.entity.AccountEntity(
                    "unknown-duplicate",
                    "A/C **9141",
                    "Unknown Bank",
                    "auto-extracted"
                )
            listOf(hdfc, sbi, unknownCanonical, unknownDuplicate).forEach {
                db.accountDao().insert(it)
            }
            db.transactionDao().insert(
                com.pocketfinancer.data.db.entity.TransactionEntity(
                    "ambiguous-tx",
                    100.0,
                    "Merchant",
                    1_000L,
                    "debit",
                    unknownDuplicate.id,
                    "ambiguous raw",
                    "AX-BANK"
                )
            )

            repo.consolidateAccounts()

            assertNotNull(db.accountDao().getById(hdfc.id))
            assertNotNull(db.accountDao().getById(sbi.id))
            assertNotNull(db.accountDao().getById(unknownCanonical.id))
            assertNull(db.accountDao().getById(unknownDuplicate.id))
            assertEquals(
                unknownCanonical.id,
                db.transactionDao().getById("ambiguous-tx")?.accountId
            )
            assertEquals(3, repo.getAllOnce().size)
        }
    }

    @Test
    fun `getById should return null for missing account`() {
        runBlocking {
            val result = repo.getById("nonexistent")
            assertNull(result)
        }
    }

    @Test
    fun `consolidateAccounts should merge duplicates and delete stale even if no known-bank accounts exist`() {
        runBlocking {
            val acc1 = com.pocketfinancer.data.db.entity.AccountEntity("id1", "Unknown Account A/c XX9141", "Unknown Account", "auto-extracted")
            val acc2 = com.pocketfinancer.data.db.entity.AccountEntity("id2", "A/C **9141", "Unknown Account", "auto-extracted")
            
            db.accountDao().insert(acc1)
            db.accountDao().insert(acc2)

            val tx1 = com.pocketfinancer.data.db.entity.TransactionEntity("tx1", 100.0, "M1", 1000L, "DEBIT", "id2", "raw1", "sender1", false)
            db.transactionDao().insert(tx1)

            repo.consolidateAccounts()

            val allAccs = repo.getAllOnce()
            assertEquals(1, allAccs.size)
            assertEquals("id1", allAccs[0].id)

            val tx1Updated = db.transactionDao().getById("tx1")
            assertEquals("id1", tx1Updated?.accountId)
        }
    }

    @Test
    fun `consolidateAccounts should merge multiple known accounts of same bank`() {
        runBlocking {
            val acc1 = com.pocketfinancer.data.db.entity.AccountEntity("id1", "HDFC Bank A/c XX9141", "HDFC Bank", "auto-extracted")
            val acc2 = com.pocketfinancer.data.db.entity.AccountEntity("id2", "HDFC A/c XX9141", "HDFC Bank", "auto-extracted")
            
            db.accountDao().insert(acc1)
            db.accountDao().insert(acc2)

            val tx1 = com.pocketfinancer.data.db.entity.TransactionEntity("tx1", 100.0, "M1", 1000L, "DEBIT", "id2", "raw1", "sender1", false)
            db.transactionDao().insert(tx1)

            repo.consolidateAccounts()

            val allAccs = repo.getAllOnce()
            assertEquals(1, allAccs.size)
            assertEquals("id1", allAccs[0].id)

            val tx1Updated = db.transactionDao().getById("tx1")
            assertEquals("id1", tx1Updated?.accountId)
        }
    }

    @Test
    fun `getOrCreate should infer bank name from account name`() {
        runBlocking {
            val account = repo.getOrCreate("HDFC Card XX1234", "Unknown Account", "auto-extracted")
            assertEquals("HDFC Bank", account.bank)
            assertEquals("HDFC Bank Card XX1234", account.name)
        }
    }

    @Test
    fun `getOrCreate should handle account name with no normalizable digits`() {
        runBlocking {
            val account = repo.getOrCreate("MyCash", "HDFC Bank", "auto-extracted")
            assertEquals("MyCash", account.name)
            assertEquals("HDFC Bank", account.bank)
        }
    }
}
