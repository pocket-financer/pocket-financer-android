package com.pocketfinancer.ui.insights

import com.pocketfinancer.data.model.Transaction
import com.pocketfinancer.data.model.TransactionType
import com.pocketfinancer.data.repository.TransactionRepository
import io.mockk.every
import io.mockk.mockk
import java.util.Calendar
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class InsightsViewModelTest {
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
    fun `computes savings micro-spends top merchants account splits and velocity buckets`() =
        runTest(testDispatcher) {
            val now = Calendar.getInstance()
            val morning = timestampInCurrentMonth(now, hour = 9)
            val afternoon = timestampInCurrentMonth(now, hour = 14)
            val evening = timestampInCurrentMonth(now, hour = 19)
            val night = timestampInCurrentMonth(now, hour = 23)

            val txs = listOf(
                tx("1", 1000.0, "Employer", TransactionType.CREDIT, morning, "HDFC ••1234"),
                tx("2", 250.0, "Grocer A", TransactionType.DEBIT, morning, "HDFC ••1234"),
                tx("3", 100.0, "Tea Stall", TransactionType.DEBIT, afternoon, "HDFC ••1234"),
                tx("4", 50.0, " ", TransactionType.DEBIT, evening, null),
                tx("5", 60.0, "Bakery B", TransactionType.DEBIT, night, "ICICI ••5678"),
                tx("6", 40.0, "Bookshop C", TransactionType.DEBIT, morning, "ICICI ••5678"),
                tx("7", 30.0, "Pharmacy D", TransactionType.DEBIT, morning, "ICICI ••5678"),
                tx("8", 20.0, "Newsstand E", TransactionType.DEBIT, morning, "ICICI ••5678")
            )
            val repo = mockk<TransactionRepository>()
            every { repo.getAllByDateDesc() } returns MutableStateFlow(txs)

            val viewModel = InsightsViewModel(repo)
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            runCurrent()

            val state = viewModel.uiState.value
            assertEquals(TimeframePreset.THIS_MONTH, state.selectedTimeframe)
            assertEquals(550.0, state.totalDebit, 0.001)
            assertEquals(1000.0, state.totalCredit, 0.001)
            assertEquals(450.0, state.netSavings, 0.001)
            assertEquals(45.0, state.savingsRate, 0.001)

            // Micro-spends include amounts <= 100.0 (100 + 50 + 60 + 40 + 30 + 20 = 300 across 6 debits).
            assertEquals(6, state.microSpendsCount)
            assertEquals(300.0, state.microSpendsSum, 0.001)

            // Top 5 merchants capped at 5 and blank merchant normalized to Unknown Merchant.
            assertEquals(5, state.topMerchants.size)
            assertEquals("Grocer A", state.topMerchants[0].merchant)
            assertEquals("Tea Stall", state.topMerchants[1].merchant)
            assertEquals("Bakery B", state.topMerchants[2].merchant)
            assertEquals("Unknown Merchant", state.topMerchants[3].merchant)
            assertEquals("Bookshop C", state.topMerchants[4].merchant)

            // Account splits group missing label under Unknown Account.
            assertEquals(3, state.accountSplits.size)
            assertEquals("HDFC ••1234", state.accountSplits[0].accountName)
            assertEquals(350.0, state.accountSplits[0].totalAmount, 0.001)
            assertEquals("ICICI ••5678", state.accountSplits[1].accountName)
            assertEquals(150.0, state.accountSplits[1].totalAmount, 0.001)
            assertEquals("Unknown Account", state.accountSplits[2].accountName)
            assertEquals(50.0, state.accountSplits[2].totalAmount, 0.001)

            // Hourly velocity covers all 4 buckets and sums to totalDebit.
            assertEquals(340.0, state.hourlyVelocity["Morning (6 AM - 12 PM)"]!!, 0.001)
            assertEquals(100.0, state.hourlyVelocity["Afternoon (12 PM - 5 PM)"]!!, 0.001)
            assertEquals(50.0, state.hourlyVelocity["Evening (5 PM - 9 PM)"]!!, 0.001)
            assertEquals(60.0, state.hourlyVelocity["Night (9 PM - 6 AM)"]!!, 0.001)
            assertEquals(7, state.dayOfWeekVelocity.size)
            assertEquals(550.0, state.dayOfWeekVelocity.values.sum(), 0.001)

            collector.cancel()
        }

    @Test
    fun `timeframe switching filters last month and all time and zero credit yields zero savings rate`() =
        runTest(testDispatcher) {
            val thisMonthTs = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 10)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val lastMonthTs = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_MONTH, -2)
            }.timeInMillis

            val oldTs = Calendar.getInstance().apply {
                add(Calendar.MONTH, -4)
            }.timeInMillis

            val txs = listOf(
                tx("curr", 200.0, "Current Store", TransactionType.DEBIT, thisMonthTs, "HDFC"),
                tx("last", 350.0, "Last Month Store", TransactionType.DEBIT, lastMonthTs, "HDFC"),
                tx("old", 450.0, "Old Store", TransactionType.DEBIT, oldTs, "HDFC")
            )
            val repo = mockk<TransactionRepository>()
            every { repo.getAllByDateDesc() } returns MutableStateFlow(txs)

            val viewModel = InsightsViewModel(repo)
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            runCurrent()

            assertEquals(200.0, viewModel.uiState.value.totalDebit, 0.001)
            assertEquals(0.0, viewModel.uiState.value.savingsRate, 0.001)

            viewModel.selectTimeframe(TimeframePreset.LAST_MONTH)
            runCurrent()
            assertEquals(350.0, viewModel.uiState.value.totalDebit, 0.001)
            assertEquals(
                listOf("Last Month Store"),
                viewModel.uiState.value.topMerchants.map { it.merchant }
            )

            viewModel.selectTimeframe(TimeframePreset.ALL_TIME)
            runCurrent()
            assertEquals(1000.0, viewModel.uiState.value.totalDebit, 0.001)
            assertEquals(3, viewModel.uiState.value.topMerchants.size)

            collector.cancel()
        }

    private fun timestampInCurrentMonth(base: Calendar, hour: Int): Long =
        (base.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun tx(
        id: String,
        amount: Double,
        merchant: String,
        type: TransactionType,
        date: Long,
        accountLabel: String?
    ) = Transaction(
        id = id,
        amount = amount,
        merchant = merchant,
        type = type,
        date = date,
        accountId = "acct-1",
        accountLabel = accountLabel,
        rawMessage = "Synthetic SMS for $merchant",
        sender = "AX-SYNTH"
    )
}
