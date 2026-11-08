package com.pocketfinancer.pipeline

import com.pocketfinancer.data.db.entity.AdmittedSmsSourceEntity
import com.pocketfinancer.data.db.entity.SmsProcessingOperationEntity
import com.pocketfinancer.data.repository.ProcessingConfigurationRepository
import com.pocketfinancer.data.repository.SmsAdmissionBusyException
import com.pocketfinancer.data.repository.SmsOperationClaim
import com.pocketfinancer.data.repository.SmsProcessingAdmission
import com.pocketfinancer.data.repository.SmsReviewRetryContext
import com.pocketfinancer.data.repository.SmsProcessingStore
import com.pocketfinancer.inference.SlmLease
import com.pocketfinancer.inference.DirectCandidateSelectorResult
import com.pocketfinancer.inference.SlmCacheDiagnostics
import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.inference.SlmPerformanceData
import com.pocketfinancer.pipeline.sms.DefaultSmsProcessingCoordinator
import com.pocketfinancer.pipeline.sms.DefaultSmsV4ProcessingCoordinator
import com.pocketfinancer.pipeline.sms.DefaultSmsV5ProcessingCoordinator
import com.pocketfinancer.pipeline.sms.SmsOperationSnapshot
import com.pocketfinancer.pipeline.sms.SmsOperationSnapshotFactory
import com.pocketfinancer.pipeline.sms.SmsProcessingOutcome
import com.pocketfinancer.pipeline.sms.SmsProcessingObserver
import com.pocketfinancer.pipeline.sms.SmsProcessingObserverEvent
import com.pocketfinancer.pipeline.sms.SmsProcessingTransientEvent
import com.pocketfinancer.pipeline.sms.SmsV4ModelIdentity
import com.pocketfinancer.pipeline.sms.SmsV4OperationSnapshot
import com.pocketfinancer.pipeline.sms.SmsV4OperationSnapshotFactory
import com.pocketfinancer.pipeline.sms.SmsV5OperationConfiguration
import com.pocketfinancer.pipeline.sms.SmsV5OperationSnapshot
import com.pocketfinancer.pipeline.sms.SmsV5OperationSnapshotFactory
import com.pocketfinancer.sms.SmsReader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
    private val admittedSources = mutableListOf<AdmittedSmsSourceEntity>()

    @Before
    fun setUp() {
        store = mockk(relaxed = true)
        admittedSources.clear()
        coEvery { store.admitForProcessing(any(), any()) } coAnswers {
            val source = arg<AdmittedSmsSourceEntity>(0)
            admittedSources += source
            val create = arg<suspend (AdmittedSmsSourceEntity) -> String?>(1)
            if (create(source) == null) SmsProcessingAdmission.AwaitingConfiguration else
                SmsProcessingAdmission.Claimed(
                    source, operation(source.id),
                    SmsOperationClaim(snapshot.operationId, "owner", 1, Long.MAX_VALUE)
                )
        }
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
        coVerify(exactly = 1) { store.admitForProcessing(any(), any()) }
        coVerify(exactly = 0) {
            v5SnapshotFactory.create(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any()
            )
        }
        coVerify(exactly = 0) { v5Coordinator.processAdmitted(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `configured processing delegates once and retains review outcome`() = runTest {
        every { configuration.confirmedPrimaryCurrency() } returns "INR"
        coEvery { v5Coordinator.processAdmitted(any(), snapshot, any(), lease, any()) } returns
            SmsProcessingOutcome.RetainedForReview(
                snapshot.operationId,
                "review-id",
                listOf("persistence_blocked_by_rollout_mode")
            )

        val result = pipeline.processSingle(message(), lease, trigger = "manual")

        val skipped = assertIs<PipelineService.ProcessingResult.Skipped>(result)
        assertEquals(PipelineService.SkipReason.RETAINED_FOR_REVIEW, skipped.reason)
        coVerify(exactly = 1) { store.admitForProcessing(any(), any()) }
        coVerify(exactly = 1) {
            v5Coordinator.processAdmitted(any(), snapshot, any(), lease, any())
        }
    }

    @Test
    fun `distinct new admissions snapshot the selected grammar setting`() = runTest {
        every { configuration.confirmedPrimaryCurrency() } returns "INR"
        val enabled = MutableStateFlow(false)
        every { grammarPreferences.gbnfGrammarEnabled } returns enabled
        coEvery { v5Coordinator.processAdmitted(any(), snapshot, any(), lease, any()) } returns
            SmsProcessingOutcome.RetainedForReview(snapshot.operationId, "review-id", emptyList())

        pipeline.processSingle(message(), lease, trigger = "manual")
        coVerify(exactly = 1) {
            v5SnapshotFactory.create(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), false
            )
        }

        enabled.value = true
        pipeline.processSingle(message().copy(providerMessageId = "another-provider-id"), lease, trigger = "manual")
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
        coEvery { v5Coordinator.processAdmitted(any(), snapshot, any(), null, any()) } returns
            SmsProcessingOutcome.RetainedForReview(
                snapshot.operationId,
                "review-id",
                listOf("runtime_unavailable")
            )

        val result = pipeline.processSingle(message(), trigger = "realtime")

        val skipped = assertIs<PipelineService.ProcessingResult.Skipped>(result)
        assertEquals(PipelineService.SkipReason.RETAINED_FOR_REVIEW, skipped.reason)
        coVerify(exactly = 1) { v5Coordinator.processAdmitted(any(), snapshot, any(), null, any()) }
        coVerify(exactly = 0) {
            v5Coordinator.processAdmitted(any(), any(), any(), lease, any())
        }
    }

    @Test
    fun `retryable coordinator outcome remains retryable`() = runTest {
        every { configuration.confirmedPrimaryCurrency() } returns "INR"
        coEvery { v5Coordinator.processAdmitted(any(), snapshot, any(), lease, any()) } returns
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
        coEvery { v5Coordinator.processAdmitted(any(), snapshot, any(), lease, any()) } returns
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
            v5Coordinator.processAdmitted(any(), snapshot, any(), lease, any())
        }
    }

    @Test
    fun `decoded callbacks reach processing observer in order with completed output`() = runTest {
        every { configuration.confirmedPrimaryCurrency() } returns "INR"
        val model = SlmModelSpec("test-selector", "synthetic-model.gguf")
        coEvery {
            v5Coordinator.processAdmitted(any(), snapshot, any(), lease, any())
        } coAnswers {
            val observer = arg<SmsProcessingObserver>(4)
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

    @Test
    fun `settled replay ignores changed settings across manual historical and realtime entrypoints`() = runTest {
        coEvery { store.admitForProcessing(any(), any()) } returns SmsProcessingAdmission.Existing(
            operation().copy(state = "persisted", settledAt = 2,
                settlementReceiptJson = "{\"transaction_ids\":[\"saved-id\"]}"), null
        )
        val enabled = MutableStateFlow(false)
        every { grammarPreferences.gbnfGrammarEnabled } returns enabled
        for (trigger in listOf("manual", "historical", "realtime")) {
            enabled.value = !enabled.value
            val result = if (trigger == "realtime") pipeline.processSingle(message(), trigger = trigger) else
                pipeline.processSingle(message(), lease, trigger = trigger)
            assertEquals(PipelineService.ProcessingResult.Saved(listOf("saved-id"), true), result)
        }
        assertNoNewExtraction()
        verify(exactly = 0) { configuration.confirmedPrimaryCurrency() }
        verify(exactly = 0) { grammarPreferences.gbnfGrammarEnabled }
    }

    @Test
    fun `busy and conflicting source identities never create snapshot or call coordinator`() = runTest {
        coEvery { store.admitForProcessing(any(), any()) } returns
            SmsProcessingAdmission.Busy(snapshot.operationId)
        assertTrue(assertIs<PipelineService.ProcessingResult.Failure>(
            pipeline.processSingle(message(), lease)
        ).retryable)
        coEvery { store.admitForProcessing(any(), any()) } returns SmsProcessingAdmission.Conflict
        assertEquals(false, assertIs<PipelineService.ProcessingResult.Failure>(
            pipeline.processSingle(message(), trigger = "realtime")
        ).retryable)
        assertNoNewExtraction()
    }

    @Test
    fun `recovered ready and interrupted operations reuse their visible review without resnapshot`() = runTest {
        coEvery { store.admitForProcessing(any(), any()) } returns SmsProcessingAdmission.Existing(
            operation().copy(state = "interrupted"), "existing-review"
        )
        assertEquals(
            PipelineService.ProcessingResult.Skipped(PipelineService.SkipReason.RETAINED_FOR_REVIEW),
            pipeline.processSingle(message(), lease)
        )
        assertNoNewExtraction()
    }

    @Test
    fun `none and owner rejected replay create no extraction or duplicate review`() = runTest {
        coEvery { store.admitForProcessing(any(), any()) } returnsMany listOf(
            SmsProcessingAdmission.Existing(operation().copy(state = "discarded", settledAt = 2), null),
            SmsProcessingAdmission.Existing(operation().copy(state = "retain_review", settledAt = 2),
                "rejected-review", "rejected")
        )
        repeat(2) {
            assertEquals(PipelineService.ProcessingResult.Skipped(PipelineService.SkipReason.NOT_TRANSACTION),
                pipeline.processSingle(message()))
        }
        assertNoNewExtraction()
    }

    @Test
    fun `owner confirmed transaction is reused without another model call`() = runTest {
        coEvery { store.admitForProcessing(any(), any()) } returns
            SmsProcessingAdmission.Saved(listOf("owner-confirmed-id"))
        assertEquals(PipelineService.ProcessingResult.Saved(listOf("owner-confirmed-id"), true),
            pipeline.processSingle(message()))
        assertNoNewExtraction()
    }

    @Test
    fun `identical providerless content does not become reliable identity`() = runTest {
        every { configuration.confirmedPrimaryCurrency() } returns "INR"
        coEvery { v5Coordinator.processAdmitted(any(), snapshot, any(), null, any()) } returns
            SmsProcessingOutcome.RetainedForReview(snapshot.operationId, "review-id", emptyList())
        val sms = message().copy(providerMessageId = null)
        repeat(2) { pipeline.processSingle(sms, trigger = "realtime") }
        assertEquals(2, admittedSources.map { it.id }.distinct().size)
        assertEquals(2, admittedSources.map { it.sourceMessageId }.distinct().size)
        assertEquals(1, admittedSources.map { it.sourceFingerprint }.distinct().size)
        assertTrue(admittedSources.all { it.sourceProviderMessageId == null })
    }

    @Test
    fun `explicit retry cannot steal active source processing`() = runTest {
        coEvery { store.retryContext("review-id") } throws SmsAdmissionBusyException()
        assertTrue(assertIs<PipelineService.ProcessingResult.Failure>(
            pipeline.retryReview("review-id", "current")
        ).retryable)
        assertNoNewExtraction()
    }

    @Test
    fun `explicit current settings retry passes preserved parent and event lineage`() = runTest {
        val source = source()
        val parent = operation(source.id).copy(
            state = "retain_review", settledAt = 2,
            configurationJson = """{"contract":"pocketfinancer.processing-config/6","currency_context":{"primary_currency":"INR","enabled_profile_ids":["core-en","india"]}}"""
        )
        coEvery { store.retryContext("review-id") } returns SmsReviewRetryContext("review-id", source, parent)
        every { configuration.confirmedPrimaryCurrency() } returns "INR"
        every { grammarPreferences.gbnfGrammarEnabled } returns MutableStateFlow(true)
        coEvery { v5Coordinator.process(any(), snapshot, any()) } returns
            SmsProcessingOutcome.RetainedForReview(snapshot.operationId, "review-id", emptyList())

        pipeline.retryReview("review-id", "current")

        coVerify(exactly = 1) {
            v5SnapshotFactory.create(
                any(), "retry", "INR", listOf("core-en", "india"), any(), any(), source.admittedAt,
                any(), "llama.cpp-jni", any(), parent.stableEventId, parent.id, any(), true
            )
        }
    }

    @Test
    fun `original v2 retry passes its snapshot and preserved lineage to its coordinator`() = runTest {
        val source = source()
        val parent = operation(source.id).copy(
            state = "retain_review", settledAt = 2,
            configurationJson = """{"contract":"pocketfinancer.processing-config/2","currency_context":{"primary_currency":"INR","enabled_profile_ids":["core-en","india"]},"selector":{"model_identifier":"invented-selector","model_file_sha256":"${"a".repeat(64)}","runtime_version":"invented-runtime"}}"""
        )
        val retrySnapshot = mockk<SmsOperationSnapshot>()
        coEvery { store.retryContext("review-id") } returns SmsReviewRetryContext("review-id", source, parent)
        coEvery {
            snapshotFactory.create(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any())
        } returns retrySnapshot
        coEvery { coordinator.process(any(), retrySnapshot, any()) } returns
            SmsProcessingOutcome.RetainedForReview("retry-operation", "review-id", emptyList())

        val result = pipeline.retryReview("review-id", "original")

        assertIs<PipelineService.ProcessingResult.Skipped>(result)
        coVerify(exactly = 1) {
            snapshotFactory.create(
                any(), "retry", "INR", listOf("core-en", "india"), source.sourceTimestamp,
                "acquisition_supplied_message_time", source.admittedAt, "invented-selector",
                "a".repeat(64), "invented-runtime", any(), parent.stableEventId, parent.id, any()
            )
        }
        coVerify(exactly = 1) { coordinator.process(any(), retrySnapshot, any()) }
        assertNoNewExtraction()
    }

    @Test
    fun `original v4 retry passes its snapshot and preserved lineage to its coordinator`() = runTest {
        val source = source()
        val parent = operation(source.id).copy(
            state = "retain_review", settledAt = 2,
            configurationJson = """{"contract":"pocketfinancer.processing-config/4","currency_context":{"primary_currency":"INR","enabled_profile_ids":["core-en","india"]},"extractor":{"eligible":false,"model_identifier":null,"model_file_sha256":null},"received_timestamp":{"epoch_ms":1700000000000,"provenance":"acquisition_supplied_message_time"}}"""
        )
        val retrySnapshot = mockk<SmsV4OperationSnapshot>()
        coEvery { store.retryContext("review-id") } returns SmsReviewRetryContext("review-id", source, parent)
        coEvery {
            v4SnapshotFactory.create(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any())
        } returns retrySnapshot
        coEvery { v4Coordinator.process(any(), retrySnapshot, any()) } returns
            SmsProcessingOutcome.RetainedForReview("retry-operation", "review-id", emptyList())

        val result = pipeline.retryReview("review-id", "original")

        assertIs<PipelineService.ProcessingResult.Skipped>(result)
        coVerify(exactly = 1) {
            v4SnapshotFactory.create(
                any(), "retry", "INR", listOf("core-en", "india"), 1_700_000_000_000L,
                "acquisition_supplied_message_time", source.admittedAt, SmsV4ModelIdentity(false, null, null),
                "llama.cpp-jni", any(), parent.stableEventId, parent.id, any()
            )
        }
        coVerify(exactly = 1) { v4Coordinator.process(any(), retrySnapshot, any()) }
        assertNoNewExtraction()
    }

    private fun assertNoNewExtraction() {
        coVerify(exactly = 0) {
            v5SnapshotFactory.create(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { v5Coordinator.processAdmitted(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { v5Coordinator.currentModelIdentity() }
        verify(exactly = 0) { v5Coordinator.modelIdentityForLease(any()) }
    }

    private fun source(): AdmittedSmsSourceEntity {
        val sms = message()
        val identity = sms.sourceIdentity
        return AdmittedSmsSourceEntity(identity.opaqueCandidateKey, identity.connector,
            identity.messageId, identity.providerMessageId, identity.fallbackFingerprint, null,
            sms.address, sms.body, sms.sourceTimestamp, sms.type, "manual", "receipt-id", sms.date, "admitted")
    }

    private fun operation(sourceId: String = "synthetic-source") = SmsProcessingOperationEntity(
        snapshot.operationId, sourceId, null, snapshot.stableEventId, "manual", "{}", "a".repeat(64),
        "native-integration-v6", "ready", 0, null, 0, null, 1, 1, null, null, 0
    )

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
