package com.pocketfinancer.ui.transactions

import android.content.Context
import com.pocketfinancer.SlmAppFlowCoordinator
import com.pocketfinancer.data.model.Account
import com.pocketfinancer.data.model.Transaction
import com.pocketfinancer.data.model.TransactionType
import com.pocketfinancer.data.repository.AccountRepository
import com.pocketfinancer.data.repository.SmsReviewRepository
import com.pocketfinancer.data.repository.TransactionRepository
import com.pocketfinancer.inference.SlmRuntimeOwner
import com.pocketfinancer.ui.home.HomeSyncManager
import com.pocketfinancer.ui.home.HomeSyncState
import com.pocketfinancer.ui.home.SyncSmsItem
import com.pocketfinancer.ui.home.withPrivacySafeStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `erase pause cancels and drains an admitted ledger edit`() = runTest(testDispatcher) {
        val coordinator = SlmAppFlowCoordinator()
        val editStarted = CompletableDeferred<Unit>()
        var reachedAfterSuspension = false

        val edit = launch {
            withLedgerEditAdmission(coordinator) {
                editStarted.complete(Unit)
                awaitCancellation()
                reachedAfterSuspension = true
            }
        }
        editStarted.await()

        val pause = coordinator.tryPauseAndDrain(SlmRuntimeOwner.SETTINGS_MANUAL)

        assertTrue(edit.isCancelled)
        assertFalse(reachedAfterSuspension)
        assertTrue(coordinator.state.value.admissionPaused)
        pause!!.release()
    }

    @Test
    fun `ledger edits are rejected while erase owns admission`() = runTest(testDispatcher) {
        val coordinator = SlmAppFlowCoordinator()
        val pause = coordinator.tryPauseAndDrain(SlmRuntimeOwner.SETTINGS_MANUAL)!!
        var mutated = false

        val result = withLedgerEditAdmission(coordinator) {
            mutated = true
        }
        runCurrent()

        assertNull(result)
        assertFalse(mutated)
        pause.release()
    }

    @Test
    fun `filters by segment account search query and applies all sort orders`() =
        runTest(testDispatcher) {
            val txs = listOf(
                tx("1", 500.0, "Coffee Roasters", TransactionType.DEBIT, 3_000L, "acct-1", "HDFC ••1234"),
                tx("2", 1_500.0, "Payroll Deposit", TransactionType.CREDIT, 2_000L, "acct-1", "HDFC ••1234"),
                tx("3", 120.0, "Metro Transit", TransactionType.DEBIT, 1_000L, "acct-2", "ICICI ••5678")
            )
            val viewModel = buildViewModel(txs)
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            runCurrent()

            assertEquals(listOf("1", "2", "3"), viewModel.uiState.value.transactions.map { it.id })

            viewModel.updateSegment("Debits")
            runCurrent()
            assertEquals(listOf("1", "3"), viewModel.uiState.value.transactions.map { it.id })

            viewModel.updateSegment("Credits")
            runCurrent()
            assertEquals(listOf("2"), viewModel.uiState.value.transactions.map { it.id })

            viewModel.updateSegment("All")
            viewModel.selectAccount("acct-2")
            runCurrent()
            assertEquals(listOf("3"), viewModel.uiState.value.transactions.map { it.id })

            viewModel.selectAccount("All")
            viewModel.updateSearchQuery("icici")
            runCurrent()
            assertEquals(listOf("3"), viewModel.uiState.value.transactions.map { it.id })

            viewModel.updateSearchQuery("1500")
            runCurrent()
            assertEquals(listOf("2"), viewModel.uiState.value.transactions.map { it.id })

            viewModel.updateSearchQuery("")
            viewModel.updateSortOption(SortOption.DATE_ASC)
            runCurrent()
            assertEquals(listOf("3", "2", "1"), viewModel.uiState.value.transactions.map { it.id })

            viewModel.updateSortOption(SortOption.AMOUNT_DESC)
            runCurrent()
            assertEquals(listOf("2", "1", "3"), viewModel.uiState.value.transactions.map { it.id })

            viewModel.updateSortOption(SortOption.AMOUNT_ASC)
            runCurrent()
            assertEquals(listOf("3", "1", "2"), viewModel.uiState.value.transactions.map { it.id })

            collector.cancel()
        }

    @Test
    fun `updateTransaction persists projection edit and refreshes selected transaction`() =
        runTest(testDispatcher) {
            val original = tx("tx-1", 250.0, "Demo Shop", TransactionType.DEBIT, 1_000L, "acct-1", "HDFC")
            val updated = original.copy(amount = 300.0, merchant = "Updated Shop", currentRevisionId = "rev-2")
            val txRepo = mockk<TransactionRepository>()
            every { txRepo.getAllByDateDesc() } returns MutableStateFlow(listOf(original))
            coEvery { txRepo.editProjection(any()) } returns TransactionRepository.ProjectionEditReceipt(
                actionId = "action-1",
                transaction = updated,
                resultingRevision = 2L,
                replayed = false
            )
            val viewModel = buildViewModel(listOf(original), transactionRepository = txRepo)
            viewModel.selectTransaction(original)

            var callbackResult: Boolean? = null
            viewModel.updateTransaction(
                id = "tx-1",
                expectedRevisionId = "rev-1",
                amountText = "300.00",
                currencyCode = "INR",
                merchant = "Updated Shop",
                type = TransactionType.DEBIT,
                accountId = "acct-1"
            ) { callbackResult = it }
            runCurrent()

            assertEquals(true, callbackResult)
            assertEquals(updated, viewModel.selectedTransaction.value)
        }

    @Test
    fun `diagnostic log helpers respect terminal privacy scrubbing`() = runTest(testDispatcher) {
        val viewModel = buildViewModel(emptyList())
        val pending = SyncSmsItem("c1", "AX-BANK", "Your OTP is 123456", 1L, status = "pending")
        val filterLogs = viewModel.getFilterLogs(pending)
        assertTrue(filterLogs.any { it.contains("sms-prefilter/1") })
        assertTrue(filterLogs.any { it.contains("exclude") })
        assertTrue(viewModel.getKvCacheLogs(pending).any { it.contains("KV cache") })
        assertTrue(viewModel.getSlmPrompt(pending).contains("Decision Trace"))

        val scrubbed = pending.withPrivacySafeStatus("synced")
        assertEquals(
            listOf("Source evidence is unavailable after terminal processing."),
            viewModel.getFilterLogs(scrubbed)
        )
        assertEquals(
            listOf("Source evidence is unavailable after terminal processing."),
            viewModel.getKvCacheLogs(scrubbed)
        )
        assertEquals(
            "Source evidence is unavailable after terminal processing.",
            viewModel.getSlmPrompt(scrubbed)
        )
    }

    private fun buildViewModel(
        transactions: List<Transaction>,
        transactionRepository: TransactionRepository = mockk<TransactionRepository>().apply {
            every { getAllByDateDesc() } returns MutableStateFlow(transactions)
        }
    ): TransactionsViewModel {
        val accountRepo = mockk<AccountRepository>()
        every { accountRepo.getAll() } returns MutableStateFlow(
            listOf(
                Account("acct-1", "HDFC ••1234", "HDFC", "bank"),
                Account("acct-2", "ICICI ••5678", "ICICI", "bank")
            )
        )
        val syncManager = mockk<HomeSyncManager>(relaxed = true)
        every { syncManager.syncState } returns MutableStateFlow(HomeSyncState())
        val reviewRepo = mockk<SmsReviewRepository>()
        coEvery { reviewRepo.activeOperations() } returns emptyList()
        coEvery { reviewRepo.openCases() } returns emptyList()

        return TransactionsViewModel(
            context = mockk<Context>(relaxed = true),
            transactionRepository = transactionRepository,
            accountRepository = accountRepo,
            syncManager = syncManager,
            appFlowCoordinator = SlmAppFlowCoordinator(),
            smsReviewRepository = reviewRepo
        )
    }

    private fun tx(
        id: String,
        amount: Double,
        merchant: String,
        type: TransactionType,
        date: Long,
        accountId: String,
        accountLabel: String?
    ) = Transaction(
        id = id,
        amount = amount,
        merchant = merchant,
        type = type,
        date = date,
        accountId = accountId,
        accountLabel = accountLabel,
        rawMessage = "Synthetic SMS for $merchant",
        sender = "AX-SYNTH",
        currentRevisionId = "rev-1"
    )
}
