package com.pocketfinancer.ui.home

import android.content.Context
import com.pocketfinancer.SlmAppFlowCoordinator
import com.pocketfinancer.inference.SlmRuntime
import com.pocketfinancer.inference.SlmRuntimeState
import com.pocketfinancer.pipeline.PipelineService
import com.pocketfinancer.setup.FakeSharedPreferences
import com.pocketfinancer.setup.SetupImportStatus
import com.pocketfinancer.setup.SetupImportStore
import com.pocketfinancer.sms.SmsReader
import com.pocketfinancer.sms.SmsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/** Invented discovery inputs; no model, provider or private database is used. */
class HomeSmsIntakeBoundaryTest {
    private lateinit var smsRepository: SmsRepository
    private lateinit var pipeline: PipelineService
    private lateinit var runtime: SlmRuntime
    private lateinit var setup: SetupImportStore
    private lateinit var flows: SlmAppFlowCoordinator
    private lateinit var manager: HomeSyncManager

    @Before
    fun setUp() {
        val preferences = FakeSharedPreferences(mapOf("onboarding_completed" to true))
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns preferences.preferences
        smsRepository = mockk()
        every { smsRepository.hasPermissions() } returns true
        pipeline = mockk()
        runtime = mockk()
        every { runtime.state } returns MutableStateFlow(SlmRuntimeState())
        setup = SetupImportStore(preferences.preferences, hasSmsPermissions = true)
        setup.update { it.copy(status = SetupImportStatus.READY, modelPrepared = true) }
        flows = SlmAppFlowCoordinator()
        manager = HomeSyncManager(
            context, smsRepository, runtime, flows, mockk(), mockk(), setup, pipeline
        )
    }

    @Test
    fun `recent scan delegates every saved decision and retains uncertain source evidence`() = runTest {
        val saved = message("saved-provider")
        val distinctProvider = message("other-provider")
        val providerLess = message(null)
        val conflict = saved.copy(body = "Different invented alert")
        val input = listOf(saved, distinctProvider, providerLess, providerLess, conflict)
        coEvery { smsRepository.fetchHistory(any(), any(), any()) } returns input
        coEvery { pipeline.hasVerifiedSavedSource(any()) } coAnswers {
            arg<SmsReader.SmsMessage>(0) == saved
        }

        manager.checkForUnsyncedSms()

        assertEquals(listOf(distinctProvider, providerLess, providerLess, conflict),
            manager.syncState.value.queue.map { it.toSourceMessage() })
        assertEquals(HomeSyncState.RecentScanOutcome.SUCCESS, manager.syncState.value.recentScanOutcome)
        assertEquals(4, setup.state.value.recentEligibleCandidateCount)
        input.distinct().forEach { message ->
            coVerify(exactly = input.count { it == message }) { pipeline.hasVerifiedSavedSource(message) }
        }
        coVerify(exactly = 0) { runtime.acquire(any(), any()) }
        coVerify(exactly = 0) { pipeline.processSingle(any(), observer = any(), trigger = any()) }
        assertEquals(0, flows.state.value.activeCount)
    }

    @Test
    fun `cancelled discovery releases admission without publishing successful coverage`() = runTest {
        coEvery { smsRepository.fetchHistory(any(), any(), any()) } returns listOf(message("provider"))
        coEvery { pipeline.hasVerifiedSavedSource(any()) } throws CancellationException("Invented interruption")

        assertFailsWith<CancellationException> { manager.checkForUnsyncedSms() }

        assertEquals(HomeSyncState.RecentScanOutcome.NOT_RUN, manager.syncState.value.recentScanOutcome)
        assertEquals(null, manager.syncState.value.lastSuccessfulScanMillis)
        assertEquals(0, flows.state.value.activeCount)
        coVerify(exactly = 0) { runtime.acquire(any(), any()) }
    }

    private fun message(providerId: String?) = SmsReader.SmsMessage(
        address = "SYNTH", body = "Invented alert", date = 6_000L,
        type = 1, providerMessageId = providerId, sourceTimestamp = 1_000L
    )
}
