package com.pocketfinancer.pipeline

import com.pocketfinancer.data.repository.ProcessingConfigurationRepository
import com.pocketfinancer.data.repository.SmsProcessingStore
import com.pocketfinancer.inference.SlmLease
import com.pocketfinancer.inference.DirectCandidateSelectorResult
import com.pocketfinancer.inference.SlmCacheDiagnostics
import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.inference.SlmPerformanceData
import com.pocketfinancer.pipeline.sms.DefaultSmsProcessingCoordinator
import com.pocketfinancer.pipeline.sms.DefaultSmsV4ProcessingCoordinator
import com.pocketfinancer.pipeline.sms.DefaultSmsV5ProcessingCoordinator
import com.pocketfinancer.pipeline.sms.SmsOperationSnapshotFactory
import com.pocketfinancer.pipeline.sms.SmsProcessingOutcome
import com.pocketfinancer.pipeline.sms.SmsProcessingObserver
import com.pocketfinancer.pipeline.sms.SmsProcessingObserverEvent
import com.pocketfinancer.pipeline.sms.SmsProcessingTransientEvent
import com.pocketfinancer.pipeline.sms.SmsV4ModelIdentity
import com.pocketfinancer.pipeline.sms.SmsV4OperationSnapshotFactory
import com.pocketfinancer.pipeline.sms.SmsV5OperationConfiguration
import com.pocketfinancer.pipeline.sms.SmsV5OperationSnapshot
import com.pocketfinancer.pipeline.sms.SmsV5OperationSnapshotFactory
import com.pocketfinancer.sms.SmsReader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class PipelineServiceTest {
    private lateinit var store: SmsProcessingStore
    private lateinit var snapshotFactory: SmsOperationSnapshotFactory
    private lateinit var configuration: ProcessingConfigurationRepository
    private lateinit var coordinator: DefaultSmsProcessingCoordinator
    private lateinit var v4SnapshotFactory: SmsV4OperationSnapshotFactory
    private lateinit var v4Coordinator: DefaultSmsV4ProcessingCoordinator
    private lateinit var v5SnapshotFactory: SmsV5OperationSnapshotFactory
    private lateinit var v5Coordinator: DefaultSmsV5ProcessingCoordinator
    private lateinit var grammarPreferences: SlmProcessingPreferences
    private lateinit var lease: SlmLease
    private lateinit var pipeline: PipelineService
    private val snapshot = snapshot()

    @Before
    fun setUp() {
        store = mockk(relaxed = true)
        snapshotFactory = mockk()
        configuration = mockk()
        coordinator = mockk()
        v4SnapshotFactory = mockk()
        v4Coordinator = mockk()
        v5SnapshotFactory = mockk()
        v5Coordinator = mockk()
        grammarPreferences = mockk()
        every { grammarPreferences.gbnfGrammarEnabled } returns MutableStateFlow(false)
        lease = mockk()
        every { lease.model } returns SlmModelSpec(
            modelId = "test-selector",
            modelPath = "synthetic-model.gguf"
        )
        every { configuration.enabledProfiles("INR") } returns listOf("core-en", "india")
        every { v5Coordinator.modelIdentityForLease(lease) } returns
            SmsV4ModelIdentity(true, "test-selector", "a".repeat(64))
        every { v5Coordinator.currentModelIdentity() } returns
            SmsV4ModelIdentity(false, null, null)
        coEvery {
            v5SnapshotFactory.create(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any()
            )
        } returns snapshot
        pipeline = PipelineService(
            store, snapshotFactory, configuration, coordinator,
            v4SnapshotFactory, v4Coordinator, v5SnapshotFactory, v5Coordinator,
            grammarPreferences
        )
    }

    @Test
    fun `pipeline starts with null state`() = runTest {
        assertNull(pipeline.pipelineState.first())
    }

    @Test
    fun `source is admitted before missing configuration pauses processing`() = runTest {
        every { configuration.confirmedPrimaryCurrency() } returns null

        val result = pipeline.processSingle(message(), lease)

        assertEquals(PipelineService.ProcessingResult.AwaitingConfiguration, result)
        coVerify(exactly = 1) { store.admitSource(any()) }
        coVerify(exactly = 0) {
            v5SnapshotFactory.create(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any()
            )
        }
        coVerify(exactly = 0) { v5Coordinator.processUsingLease(any(), any(), any(), any()) }
    }

    @Test
    fun `configured processing delegates once and retains review outcome`() = runTest {
        every { configuration.confirmedPrimaryCurrency() } returns "INR"
        coEvery { v5Coordinator.processUsingLease(any(), snapshot, lease, any()) } returns
            SmsProcessingOutcome.RetainedForReview(
                snapshot.operationId,
                "review-id",
                listOf("persistence_blocked_by_rollout_mode")
            )

        val result = pipeline.processSingle(message(), lease, trigger = "manual")

        val skipped = assertIs<PipelineService.ProcessingResult.Skipped>(result)
        assertEquals(PipelineService.SkipReason.RETAINED_FOR_REVIEW, skipped.reason)
        coVerify(exactly = 1) { store.admitSource(any()) }
        coVerify(exactly = 1) {
            v5Coordinator.processUsingLease(any(), snapshot, lease, any())
        }
    }

    @Test
    fun `new processing snapshots use the selected grammar setting`() = runTest {
        every { configuration.confirmedPrimaryCurrency() } returns "INR"
        val enabled = MutableStateFlow(false)
        every { grammarPreferences.gbnfGrammarEnabled } returns enabled
        coEvery { v5Coordinator.processUsingLease(any(), snapshot, lease, any()) } returns
            SmsProcessingOutcome.RetainedForReview(snapshot.operationId, "review-id", emptyList())

        pipeline.processSingle(message(), lease, trigger = "manual")
        coVerify(exactly = 1) {
            v5SnapshotFactory.create(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), false
            )
        }

        enabled.value = true
        pipeline.processSingle(message(), lease, trigger = "manual")
        coVerify(exactly = 1) {
            v5SnapshotFactory.create(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), true
            )
        }
    }
    @Test
    fun `worker entrypoint lets coordinator retain an unavailable model`() = runTest {
        every { configuration.confirmedPrimaryCurrency() } returns "INR"
        coEvery { v5Coordinator.process(any(), snapshot, any()) } returns
            SmsProcessingOutcome.RetainedForReview(
                snapshot.operationId,
                "review-id",
                listOf("runtime_unavailable")
            )

        val result = pipeline.processSingle(message(), trigger = "realtime")

        val skipped = assertIs<PipelineService.ProcessingResult.Skipped>(result)
        assertEquals(PipelineService.SkipReason.RETAINED_FOR_REVIEW, skipped.reason)
        coVerify(exactly = 1) { v5Coordinator.process(any(), snapshot, any()) }
        coVerify(exactly = 0) {
            v5Coordinator.processUsingLease(any(), any(), any(), any())
        }
    }

    @Test
    fun `retryable coordinator outcome remains retryable`() = runTest {
        every { configuration.confirmedPrimaryCurrency() } returns "INR"
        coEvery { v5Coordinator.processUsingLease(any(), snapshot, lease, any()) } returns
            SmsProcessingOutcome.RetryableFailure(
                snapshot.operationId,
                "review-id",
                "runtime_unavailable",
                "model_available"
            )

        val failure = assertIs<PipelineService.ProcessingResult.Failure>(
            pipeline.processSingle(message(), lease)
        )

        assertEquals(true, failure.retryable)
        assertEquals("Saved for retry: runtime_unavailable", failure.message)
    }

    @Test
    fun `complete automatic outcome reports saved transaction exactly once`() = runTest {
        every { configuration.confirmedPrimaryCurrency() } returns "INR"
        coEvery { v5Coordinator.processUsingLease(any(), snapshot, lease, any()) } returns
            SmsProcessingOutcome.Persisted(
                snapshot.operationId,
                listOf("transaction-id"),
                alreadyCommitted = false
            )

        val saved = assertIs<PipelineService.ProcessingResult.Saved>(
            pipeline.processSingle(message(), lease)
        )

        assertEquals(listOf("transaction-id"), saved.transactionIds)
        assertEquals(false, saved.alreadyCommitted)
        coVerify(exactly = 1) {
            v5Coordinator.processUsingLease(any(), snapshot, lease, any())
        }
    }

    @Test
    fun `decoded callbacks reach processing observer in order with completed output`() = runTest {
        every { configuration.confirmedPrimaryCurrency() } returns "INR"
        val model = SlmModelSpec("test-selector", "synthetic-model.gguf")
        coEvery {
            v5Coordinator.processUsingLease(any(), snapshot, lease, any())
        } coAnswers {
            val observer = arg<SmsProcessingObserver>(3)
            observer.onEvent(observerEvent(
                SmsProcessingTransientEvent.InferenceStarted(model, true, 512)
            ))
            observer.onEvent(observerEvent(
                SmsProcessingTransientEvent.DecodedToken("{", "{")
            ))
            observer.onEvent(observerEvent(
                SmsProcessingTransientEvent.DecodedToken(
                    "\"decision\":\"none\"}",
                    "{\"decision\":\"none\"}"
                )
            ))
            observer.onEvent(observerEvent(
                SmsProcessingTransientEvent.InferenceCompleted(
                    DirectCandidateSelectorResult(
                        rawOutput = "{\"decision\":\"none\"}",
                        completion = "complete",
                        safeErrorCode = null,
                        model = model,
                        performance = SlmPerformanceData(1, 2, 100, 3),
                        cache = SlmCacheDiagnostics()
                    )
                )
            ))
            SmsProcessingOutcome.TerminallyDiscarded(
                snapshot.operationId,
                "valid_none"
            )
        }
        val events = mutableListOf<PipelineService.ProcessingEvent>()

        val result = pipeline.processSingle(
            message(),
            lease,
            observer = PipelineService.ProcessingObserver(events::add)
        )

        assertIs<PipelineService.ProcessingResult.Skipped>(result)
        assertIs<PipelineService.ProcessingEvent.InferenceStarted>(events[0])
        val first = assertIs<PipelineService.ProcessingEvent.JsonTokenDelta>(events[1])
        val second = assertIs<PipelineService.ProcessingEvent.JsonTokenDelta>(events[2])
        val completed = assertIs<PipelineService.ProcessingEvent.InferenceCompleted>(events[3])
        assertEquals("{", first.cumulativeStructuredOutput)
        assertEquals("{\"decision\":\"none\"}", second.cumulativeStructuredOutput)
        assertEquals("{\"decision\":\"none\"}", completed.json)
        assertTrue(events.size == 4)
    }

    private fun observerEvent(
        transient: SmsProcessingTransientEvent
    ) = SmsProcessingObserverEvent(
        operationId = snapshot.operationId,
        sequence = -1,
        stage = "selector_execution",
        status = "running",
        reasonCodes = emptyList(),
        transient = transient
    )

    private fun message() = SmsReader.SmsMessage(
        address = "SYNTH",
        body = "INR 10 was debited from account **1234 at SYNTH SHOP.",
        date = 1_700_000_000_000,
        type = 1,
        providerMessageId = "synthetic-provider-id"
    )

    private fun snapshot(): SmsV5OperationSnapshot {
        val operationId = "11111111-1111-4111-8111-111111111111"
        return SmsV5OperationSnapshot(
            operationId = operationId,
            parentOperationId = null,
            stableEventId = "22222222-2222-4222-8222-222222222222",
            configuration = SmsV5OperationConfiguration(
                operationId = operationId,
                parentOperationId = null,
                sourceId = "synthetic-source",
                sourceRefHash = "a".repeat(64),
                trigger = "manual",
                createdAtEpochMs = 1_700_000_000_000,
                admissionTimestampEpochMs = 1_700_000_000_000,
                receivedTimestampEpochMs = 1_700_000_000_000,
                receivedTimestampProvenance = "acquisition_supplied_message_time",
                timezoneId = "UTC",
                primaryCurrency = "INR",
                enabledProfiles = listOf("core-en", "india"),
                releaseManifestHash = "b".repeat(64),
                currencyAssetHash = "c".repeat(64),
                profileAssetHashes = emptyMap(),
                extractorEligible = true,
                extractorIneligibilityReason = null,
                modelIdentifier = "test-selector",
                modelFileSha256 = "a".repeat(64),
                modelIdentityKind = "file_sha256",
                runtimeVersion = "test-runtime",
                osVersion = "test-os",
                deviceCohort = "test-device",
                promptHash = "d".repeat(64),
                grammarHash = "e".repeat(64),
                validationProfileHash = "f".repeat(64),
                rolloutMode = "automatic"
            ),
            configurationJson = "{}",
            configurationHash = "e".repeat(64)
        )
    }
}
