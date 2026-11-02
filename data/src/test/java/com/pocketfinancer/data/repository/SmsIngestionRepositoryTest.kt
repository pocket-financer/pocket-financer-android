package com.pocketfinancer.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketfinancer.data.db.AppDatabase
import com.pocketfinancer.data.model.SmsCandidateOrigin
import com.pocketfinancer.data.model.SmsSourceIdentity
import com.pocketfinancer.data.model.TransactionType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SmsIngestionRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var ingestionRepository: SmsIngestionRepository
    private lateinit var transactionRepository: TransactionRepository
    private lateinit var accountRepository: AccountRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ingestionRepository = SmsIngestionRepository(
            database,
            database.transactionDao(),
            database.queuedSmsCandidateDao(),
            database.smsProcessingDao()
        )
        accountRepository = AccountRepository(
            database.accountDao(),
            database.transactionDao(),
            runConsolidationOnInit = false
        )
        transactionRepository = TransactionRepository(
            database,
            database.transactionDao(),
            accountRepository,
            database.queuedSmsCandidateDao()
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `provider row and broadcast fallback converge while provider id is preserved`() =
        runBlocking {
            val sentAt = TEST_DATE
            val receivedAt = TEST_DATE + 500L
            val broadcast = candidate(
                providerId = null,
                date = sentAt,
                sourceTimestamp = sentAt
            )
            val provider = candidate(
                providerId = "991",
                date = receivedAt,
                sourceTimestamp = sentAt
            )

            val first = ingestionRepository.admit(broadcast)
            val second = ingestionRepository.admit(provider)

            assertEquals(first.candidateKey, second.candidateKey)
            assertEquals(
                broadcast.sourceIdentity.opaqueCandidateKey,
                second.candidateKey
            )
            assertFalse(
                provider.sourceIdentity.opaqueCandidateKey ==
                    second.candidateKey
            )
            assertEquals(1, ingestionRepository.pendingCount())
            assertEquals(
                "991",
                ingestionRepository.get(first.candidateKey)
                    ?.sourceIdentity
                    ?.providerMessageId
            )
            assertEquals(
                provider.sourceIdentity.alternateFingerprint,
                ingestionRepository.get(first.candidateKey)
                    ?.sourceIdentity
                    ?.alternateFingerprint
            )
            assertEquals(
                provider.sourceIdentity.messageId,
                ingestionRepository.get(first.candidateKey)
                    ?.sourceIdentity
                    ?.messageId
            )
            assertEquals(
                receivedAt,
                ingestionRepository.get(first.candidateKey)?.date
            )
        }

    @Test
    fun `concurrent fallback retries admit one durable candidate`() = runBlocking {
        val attempts = coroutineScope {
            List(12) {
                async(Dispatchers.Default) {
                    ingestionRepository.admit(candidate(providerId = null))
                }
            }.awaitAll()
        }

        assertEquals(1, ingestionRepository.pendingCount())
        assertEquals(1, attempts.map { it.candidateKey }.distinct().size)
    }

    @Test
    fun `same sender and timestamp with different evidence remains legitimate`() =
        runBlocking {
            val first = candidate(
                providerId = "100",
                body = "Rs 100 debited at Merchant A"
            )
            val second = candidate(
                providerId = "101",
                body = "Rs 200 debited at Merchant B"
            )

            ingestionRepository.admit(first)
            ingestionRepository.admit(second)

            assertEquals(2, ingestionRepository.pendingCount())
            assertTrue(
                first.sourceIdentity.fallbackFingerprint !=
                    second.sourceIdentity.fallbackFingerprint
            )
        }

    @Test
    fun `exact identical candidates with distinct provider ids coexist`() =
        runBlocking {
            val first = candidate(providerId = "provider-a")
            val second = candidate(providerId = "provider-b")

            val admissions = listOf(
                ingestionRepository.admit(first),
                ingestionRepository.admit(second)
            )

            assertEquals(2, ingestionRepository.pendingCount())
            assertEquals(2, admissions.map { it.candidateKey }.distinct().size)
            assertFalse(
                first.sourceIdentity.opaqueCandidateKey ==
                    second.sourceIdentity.opaqueCandidateKey
            )
        }

    @Test
    fun `automatic off removes pending but preserves claimed and manual candidates`() =
        runBlocking {
            val claimedAdmission = ingestionRepository.admit(
                candidate(providerId = "1", body = "Rs 1 debited")
            )
            val pendingAdmission = ingestionRepository.admit(
                candidate(providerId = "2", body = "Rs 2 debited")
            )
            val manual = candidate(
                providerId = "3",
                body = "Rs 3 debited",
                origin = SmsCandidateOrigin.MANUAL
            )
            val manualAdmission = ingestionRepository.admit(manual)
            assertNotNull(
                ingestionRepository.claim(
                    claimedAdmission.candidateKey,
                    claimToken = "running-worker"
                )
            )
            assertEquals(
                listOf(pendingAdmission.candidateKey),
                ingestionRepository.pendingAutomaticCandidates()
                    .map { it.candidateKey }
            )

            assertEquals(1, ingestionRepository.discardPendingAutomatic())

            assertNotNull(ingestionRepository.get(claimedAdmission.candidateKey))
            assertNotNull(ingestionRepository.get(manualAdmission.candidateKey))
            assertEquals(2, ingestionRepository.pendingCount())
        }

    @Test
    fun `startup recovery lists only pending automatic candidates`() =
        runBlocking {
            val first = ingestionRepository.admit(
                candidate(providerId = "recovery-1", body = "Rs 1 debited")
            )
            val claimed = ingestionRepository.admit(
                candidate(providerId = "recovery-2", body = "Rs 2 debited")
            )
            ingestionRepository.admit(
                candidate(
                    providerId = "recovery-manual",
                    body = "Rs 3 debited",
                    origin = SmsCandidateOrigin.MANUAL
                )
            )
            val last = ingestionRepository.admit(
                candidate(providerId = "recovery-3", body = "Rs 4 debited")
            )
            assertNotNull(
                ingestionRepository.claim(
                    candidateKey = claimed.candidateKey,
                    claimToken = "already-running"
                )
            )

            assertEquals(
                setOf(first.candidateKey, last.candidateKey),
                ingestionRepository.pendingAutomaticCandidateKeys().toSet()
            )
        }

    @Test
    fun `claim-owned discard cannot delete a replacement claim`() = runBlocking {
        val admitted = ingestionRepository.admit(
            candidate(providerId = "claim-boundary")
        )
        assertNotNull(
            ingestionRepository.claim(
                candidateKey = admitted.candidateKey,
                claimToken = "first",
                now = 1_000L
            )
        )
        assertNotNull(
            ingestionRepository.claim(
                candidateKey = admitted.candidateKey,
                claimToken = "replacement",
                now = 1_000L +
                    SmsIngestionRepository.DEFAULT_STALE_CLAIM_MS +
                    1L
            )
        )

        assertFalse(
            ingestionRepository.discardClaimed(
                candidateKey = admitted.candidateKey,
                claimToken = "first"
            )
        )
        assertFalse(
            ingestionRepository.discardAutomaticBeforeClaim(
                candidateKey = admitted.candidateKey,
                claimToken = "first"
            )
        )
        assertNotNull(ingestionRepository.get(admitted.candidateKey))
        assertTrue(
            ingestionRepository.discardClaimed(
                candidateKey = admitted.candidateKey,
                claimToken = "replacement"
            )
        )
        assertNull(ingestionRepository.get(admitted.candidateKey))
    }

    @Test
    fun `retry release and process restart claim never duplicate the candidate`() =
        runBlocking {
            val admitted = ingestionRepository.admit(candidate(providerId = "9"))
            val firstClaim = ingestionRepository.claim(
                candidateKey = admitted.candidateKey,
                claimToken = "work-id",
                now = 10_000L
            )
            assertNotNull(firstClaim)
            assertTrue(
                ingestionRepository.releaseForRetry(
                    candidateKey = admitted.candidateKey,
                    claimToken = "work-id",
                    error = "retry"
                )
            )

            val retryClaim = ingestionRepository.claim(
                candidateKey = admitted.candidateKey,
                claimToken = "work-id",
                now = 11_000L
            )
            assertNotNull(retryClaim)
            assertEquals(2, retryClaim.attemptCount)

            val competingClaim = ingestionRepository.claim(
                candidateKey = admitted.candidateKey,
                claimToken = "different-work",
                now = 11_001L
            )
            assertNull(competingClaim)
            val recoveredAfterProcessDeath = ingestionRepository.claim(
                candidateKey = admitted.candidateKey,
                claimToken = "replacement-work",
                now = 11_000L +
                    SmsIngestionRepository.DEFAULT_STALE_CLAIM_MS +
                    1L
            )
            assertNotNull(recoveredAfterProcessDeath)
            assertEquals("replacement-work", recoveredAfterProcessDeath.claimToken)
            assertEquals(1, ingestionRepository.pendingCount())
        }

    @Test
    fun `concurrent fallback persistence inserts once and consumes candidate`() =
        runBlocking {
            val admitted = ingestionRepository.admit(candidate(providerId = null))
            val account = accountRepository.ensureDefault()
            val source = candidate(providerId = null).sourceIdentity
            val newTransaction = TransactionRepository.NewTransaction(
                amount = 500.0,
                merchant = "Merchant",
                date = TEST_DATE,
                type = TransactionType.DEBIT,
                accountId = account.id,
                rawMessage = TEST_BODY,
                sender = TEST_SENDER,
                sourceIdentity = source
            )

            val results = coroutineScope {
                List(8) {
                    async(Dispatchers.Default) {
                        transactionRepository.insertIfAbsent(newTransaction)
                    }
                }.awaitAll()
            }

            assertEquals(1, results.count { it.inserted })
            assertEquals(1, transactionRepository.count())
            assertNull(ingestionRepository.get(admitted.candidateKey))
            assertTrue(transactionRepository.exists(source))
        }

    @Test
    fun `exact provider match wins and distinct authoritative ids are not merged`() =
        runBlocking {
            val account = accountRepository.ensureDefault()
            val firstSource = candidate(providerId = "provider-a").sourceIdentity
            val secondSource = candidate(providerId = "provider-b").sourceIdentity
            val unknownProvider = candidate(
                providerId = "provider-c"
            ).sourceIdentity
            val fallbackSource = candidate(providerId = null).sourceIdentity

            val first = transactionRepository.insertIfAbsent(
                transaction(firstSource, account.id)
            )
            val second = transactionRepository.insertIfAbsent(
                transaction(secondSource, account.id)
            )
            val firstRetry = transactionRepository.insertIfAbsent(
                transaction(firstSource, account.id)
            )

            assertTrue(first.inserted)
            assertTrue(second.inserted)
            assertFalse(firstRetry.inserted)
            assertEquals(first.transaction.id, firstRetry.transaction.id)
            assertFalse(first.transaction.id == second.transaction.id)
            assertEquals(2, transactionRepository.count())
            assertEquals(
                first.transaction.id,
                transactionRepository.findBySource(firstSource)?.id
            )
            assertEquals(
                second.transaction.id,
                transactionRepository.findBySource(secondSource)?.id
            )
            assertFalse(transactionRepository.exists(unknownProvider))

            // A provenance-free broadcast is allowed to fall back to evidence.
            assertTrue(transactionRepository.exists(fallbackSource))
        }

    @Test
    fun `skip path enriches broadcast transaction with provider provenance`() =
        runBlocking {
            val account = accountRepository.ensureDefault()
            val broadcastSource = candidate(providerId = null).sourceIdentity
            val providerSource = candidate(providerId = "provider-a")
                .sourceIdentity
            val distinctProvider = candidate(providerId = "provider-b")
                .sourceIdentity
            val saved = transactionRepository.insertIfAbsent(
                transaction(broadcastSource, account.id)
            )

            assertTrue(
                transactionRepository.preserveSourceMetadataIfExists(
                    sourceIdentity = providerSource,
                    receivedDate = TEST_DATE + 500L
                )
            )

            val enriched = assertNotNull(
                transactionRepository.findBySource(providerSource)
            )
            assertEquals(saved.transaction.id, enriched.id)
            assertEquals("provider-a", enriched.sourceIdentity?.providerMessageId)
            assertEquals(providerSource.messageId, enriched.sourceIdentity?.messageId)
            assertEquals(TEST_DATE + 500L, enriched.date)
            assertFalse(
                transactionRepository.preserveSourceMetadataIfExists(
                    sourceIdentity = distinctProvider,
                    receivedDate = TEST_DATE + 500L
                )
            )
            assertEquals(1, transactionRepository.count())
        }

    @Test
    fun `edits and repository restart preserve raw evidence and source identity`() =
        runBlocking {
            val account = accountRepository.ensureDefault()
            val source = SmsSourceIdentity.androidSms(
                providerMessageId = "88",
                sender = TEST_SENDER,
                body = TEST_BODY,
                sourceTimestamp = TEST_DATE,
                messageType = 1
            )
            val saved = transactionRepository.insert(
                TransactionRepository.NewTransaction(
                    amount = 500.0,
                    merchant = "Before",
                    date = TEST_DATE,
                    type = TransactionType.DEBIT,
                    accountId = account.id,
                    rawMessage = TEST_BODY,
                    sender = TEST_SENDER,
                    sourceIdentity = source
                )
            )

            val edited = transactionRepository.updateTransaction(
                id = saved.id,
                amount = 600.0,
                merchant = "After",
                type = TransactionType.CREDIT,
                accountId = account.id
            )
            val restartedRepository = TransactionRepository(
                database,
                database.transactionDao(),
                accountRepository,
                database.queuedSmsCandidateDao()
            )
            val reloaded = restartedRepository.findBySource(source)

            assertNotNull(edited)
            assertNotNull(reloaded)
            assertEquals(TEST_BODY, reloaded.rawMessage)
            assertEquals(TEST_SENDER, reloaded.sender)
            assertEquals(source, reloaded.sourceIdentity)
            assertTrue(reloaded.isEdited)
            assertFalse(reloaded.merchant == "Before")
        }

    @Test
    fun `explicit local data erase removes ledger evidence and queued candidates`() =
        runBlocking {
            val account = accountRepository.ensureDefault()
            val savedSource = candidate(providerId = "saved").sourceIdentity
            transactionRepository.insert(
                TransactionRepository.NewTransaction(
                    amount = 500.0,
                    merchant = "Merchant",
                    date = TEST_DATE,
                    type = TransactionType.DEBIT,
                    accountId = account.id,
                    rawMessage = TEST_BODY,
                    sender = TEST_SENDER,
                    sourceIdentity = savedSource
                )
            )
            ingestionRepository.admit(
                candidate(
                    providerId = "queued",
                    body = "Rs 700 credited at Another Merchant"
                )
            )

            transactionRepository.clearDatabase()

            assertEquals(0, transactionRepository.count())
            assertEquals(0, ingestionRepository.pendingCount())
            assertTrue(accountRepository.getAllOnce().isEmpty())
            assertNull(transactionRepository.findBySource(savedSource))
        }

    @Test
    fun `file backed restart preserves raw evidence and provider identity`() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val databaseName = "raw-evidence-restart.db"
            context.deleteDatabase(databaseName)
            val source = candidate(providerId = "restart-provider").sourceIdentity
            var savedId: String? = null

            val firstDatabase = Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                databaseName
            )
                .allowMainThreadQueries()
                .build()
            try {
                val firstAccountRepository = AccountRepository(
                    firstDatabase.accountDao(),
                    firstDatabase.transactionDao(),
                    runConsolidationOnInit = false
                )
                val firstRepository = TransactionRepository(
                    firstDatabase,
                    firstDatabase.transactionDao(),
                    firstAccountRepository,
                    firstDatabase.queuedSmsCandidateDao()
                )
                val account = firstAccountRepository.ensureDefault()
                savedId = firstRepository.insert(
                    transaction(source, account.id)
                ).id
            } finally {
                firstDatabase.close()
            }

            val reopenedDatabase = Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                databaseName
            )
                .allowMainThreadQueries()
                .build()
            try {
                val reopenedAccountRepository = AccountRepository(
                    reopenedDatabase.accountDao(),
                    reopenedDatabase.transactionDao(),
                    runConsolidationOnInit = false
                )
                val reopenedRepository = TransactionRepository(
                    reopenedDatabase,
                    reopenedDatabase.transactionDao(),
                    reopenedAccountRepository,
                    reopenedDatabase.queuedSmsCandidateDao()
                )
                val reloaded = assertNotNull(
                    reopenedRepository.findBySource(source)
                )

                assertEquals(savedId, reloaded.id)
                assertEquals(TEST_BODY, reloaded.rawMessage)
                assertEquals(TEST_SENDER, reloaded.sender)
                assertEquals(source, reloaded.sourceIdentity)
            } finally {
                reopenedDatabase.close()
                context.deleteDatabase(databaseName)
            }
        }

    private fun candidate(
        providerId: String?,
        body: String = TEST_BODY,
        origin: SmsCandidateOrigin = SmsCandidateOrigin.AUTOMATIC,
        date: Long = TEST_DATE,
        sourceTimestamp: Long = date
    ): SmsIngestionRepository.NewCandidate {
        val source = SmsSourceIdentity.androidSms(
            providerMessageId = providerId,
            sender = TEST_SENDER,
            body = body,
            sourceTimestamp = sourceTimestamp,
            messageType = 1,
            receivedTimestamp = date
        )
        return SmsIngestionRepository.NewCandidate(
            sourceIdentity = source,
            sender = TEST_SENDER,
            rawMessage = body,
            date = date,
            sourceTimestamp = sourceTimestamp,
            messageType = 1,
            origin = origin
        )
    }

    private fun transaction(
        sourceIdentity: SmsSourceIdentity,
        accountId: String
    ) = TransactionRepository.NewTransaction(
        amount = 500.0,
        merchant = "Merchant",
        date = TEST_DATE,
        type = TransactionType.DEBIT,
        accountId = accountId,
        rawMessage = TEST_BODY,
        sender = TEST_SENDER,
        sourceIdentity = sourceIdentity
    )

    private companion object {
        const val TEST_SENDER = "AX-HDFCBK"
        const val TEST_BODY = "Rs 500 debited at Merchant"
        const val TEST_DATE = 1_234_567L
    }
}
