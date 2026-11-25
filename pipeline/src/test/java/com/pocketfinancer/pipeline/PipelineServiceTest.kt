package com.pocketfinancer.pipeline

import com.pocketfinancer.data.db.entity.AdmittedSmsSourceEntity
import com.pocketfinancer.data.db.entity.SmsProcessingOperationEntity
import com.pocketfinancer.data.repository.ProcessingConfigurationRepository
import com.pocketfinancer.data.repository.SmsAdmissionBusyException
import com.pocketfinancer.data.repository.SmsOperationClaim
import com.pocketfinancer.data.repository.SmsProcessingAdmission
import com.pocketfinancer.data.repository.SmsProcessingStore
import com.pocketfinancer.data.repository.SmsReviewRetryContext
import com.pocketfinancer.inference.DirectCandidateSelectorResult
import com.pocketfinancer.inference.SlmCacheDiagnostics
import com.pocketfinancer.inference.SlmLease
import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.inference.SlmPerformanceData
import com.pocketfinancer.pipeline.sms.DefaultSmsProcessingCoordinator
import com.pocketfinancer.pipeline.sms.SmsContractAssets
import com.pocketfinancer.pipeline.sms.SmsModelIdentity
import com.pocketfinancer.pipeline.sms.SmsOperationConfiguration
import com.pocketfinancer.pipeline.sms.SmsOperationSnapshot
import com.pocketfinancer.pipeline.sms.SmsOperationSnapshotFactory
import com.pocketfinancer.pipeline.sms.SmsProcessingObserver
import com.pocketfinancer.pipeline.sms.SmsProcessingObserverEvent
import com.pocketfinancer.pipeline.sms.SmsProcessingOutcome
import com.pocketfinancer.pipeline.sms.SmsProcessingTransientEvent
import com.pocketfinancer.sms.SmsReader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        grammarPreferences = mockk()
        every { grammarPreferences.gbnfGrammarEnabled } returns MutableStateFlow(false)
        lease = mockk()
        every { lease.model } returns SlmModelSpec(
            modelId = "test-selector",
            modelPath = "synthetic-model.gguf"
        )
        every { configuration.enabledProfiles("INR") } returns listOf("core-en", "india")
        every { coordinator.modelIdentityForLease(lease) } returns
            SmsModelIdentity(true, "test-selector", "a".repeat(64))
        every { coordinator.currentModelIdentity() } returns
            SmsModelIdentity(false, null, null)
        coEvery {
            snapshotFactory.create(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any()
            )
        } returns snapshot
        pipeline = PipelineService(
            store, snapshotFactory, configuration, coordinator, grammarPreferences
        )
    }

    @Test
    fun `discovery checks reliable saved evidence without settings snapshots or runtime calls`() = runTest {
        val checked = mutableListOf<AdmittedSmsSourceEntity>()
        coEvery { store.verifiedSavedSourceTransactionIds(any()) } coAnswers {
            checked += arg<AdmittedSmsSourceEntity>(0)
            listOf("saved-transaction")
        }
        val sms = message().copy(sourceTimestamp = message().date - 5_000L)

        assertTrue(pipeline.hasVerifiedSavedSource(sms))

        val source = checked.single()
        assertEquals(sms.sourceIdentity.opaqueCandidateKey, source.id)
        assertEquals(sms.sourceIdentity.messageId, source.sourceMessageId)
        assertEquals(sms.sourceIdentity.fallbackFingerprint, source.sourceFingerprint)
        assertEquals(sms.sourceIdentity.alternateFingerprint, source.sourceAlternateFingerprint)
        assertEquals(sms.address, source.sender)
        assertEquals(sms.body, source.rawMessage)
        assertEquals(sms.sourceTimestamp, source.sourceTimestamp)
        assertEquals(sms.type, source.messageType)
        coVerify(exactly = 0) { store.admitForProcessing(any(), any()) }
        verify(exactly = 0) { configuration.confirmedPrimaryCurrency() }
        verify(exactly = 0) { grammarPreferences.gbnfGrammarEnabled }
        assertNoNewExtraction()
    }

    @Test
    fun `provider less discovery bypasses outcome lookup and unknown provider reaches admission`() = runTest {
        coEvery { store.verifiedSavedSourceTransactionIds(any()) } returns emptyList()

        assertFalse(pipeline.hasVerifiedSavedSource(message().copy(providerMessageId = null)))
        coVerify(exactly = 0) { store.verifiedSavedSourceTransactionIds(any()) }

        every { configuration.confirmedPrimaryCurrency() } returns null
        assertFalse(pipeline.hasVerifiedSavedSource(message()))
        assertEquals(PipelineService.ProcessingResult.AwaitingConfiguration, pipeline.processSingle(message()))
        coVerify(exactly = 1) { store.verifiedSavedSourceTransactionIds(any()) }
        coVerify(exactly = 1) { store.admitForProcessing(any(), any()) }
        verify(exactly = 1) { configuration.confirmedPrimaryCurrency() }
        assertNoNewExtraction()
    }

    @Test
    fun `discovery and normal admission share provider source evidence with distinct timestamps`() = runTest {
        val sms = message().copy(sourceTimestamp = message().date - 5_000L)
        var discovered: AdmittedSmsSourceEntity? = null
        coEvery { store.verifiedSavedSourceTransactionIds(any()) } coAnswers {
            discovered = arg(0)
            emptyList()
        }
        every { configuration.confirmedPrimaryCurrency() } returns null

        assertFalse(pipeline.hasVerifiedSavedSource(sms))
        assertEquals(PipelineService.ProcessingResult.AwaitingConfiguration, pipeline.processSingle(sms))

        val admitted = admittedSources.single()
        assertEquals(
            admitted.copy(origin = "discovery", admittedAt = checkNotNull(discovered).admittedAt),
            discovered
        )
        assertNoNewExtraction()
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
            snapshotFactory.create(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any()
            )
        }
        coVerify(exactly = 0) { coordinator.processAdmitted(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `configured processing delegates once and retains review outcome`() = runTest {
        every { configuration.confirmedPrimaryCurrency() } returns "INR"
        coEvery { coordinator.processAdmitted(any(), snapshot, any(), lease, any()) } returns
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
            coordinator.processAdmitted(any(), snapshot, any(), lease, any())
        }
    }

    @Test
    fun `distinct new admissions snapshot the selected grammar setting`() = runTest {
        every { configuration.confirmedPrimaryCurrency() } returns "INR"
        val enabled = MutableStateFlow(false)
        every { grammarPreferences.gbnfGrammarEnabled } returns enabled
        coEvery { coordinator.processAdmitted(any(), snapshot, any(), lease, any()) } returns
            SmsProcessingOutcome.RetainedForReview(snapshot.operationId, "review-id", emptyList())

        pipeline.processSingle(message(), lease, trigger = "manual")
        coVerify(exactly = 1) {
            snapshotFactory.create(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), false
            )
        }

        enabled.value = true
        pipeline.processSingle(
            message().copy(providerMessageId = "another-provider-id"),
            lease,
            trigger = "manual"
        )
        coVerify(exactly = 1) {
            snapshotFactory.create(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), true
            )
        }
    }

    @Test
    fun `worker entrypoint lets coordinator retain an unavailable model`() = runTest {
        every { configuration.confirmedPrimaryCurrency() } returns "INR"
        coEvery { coordinator.processAdmitted(any(), snapshot, any(), null, any()) } returns
            SmsProcessingOutcome.RetainedForReview(
                snapshot.operationId,
                "review-id",
                listOf("runtime_unavailable")
            )

        val result = pipeline.processSingle(message(), trigger = "realtime")

        val skipped = assertIs<PipelineService.ProcessingResult.Skipped>(result)
        assertEquals(PipelineService.SkipReason.RETAINED_FOR_REVIEW, skipped.reason)
        coVerify(exactly = 1) { coordinator.processAdmitted(any(), snapshot, any(), null, any()) }
        coVerify(exactly = 0) {
            coordinator.processAdmitted(any(), any(), any(), lease, any())
        }
    }

    @Test
    fun `queued realtime candidate operates on durable admittedSourceId`() = runTest {
        every { configuration.confirmedPrimaryCurrency() } returns "INR"
        coEvery { coordinator.processAdmitted(any(), snapshot, any(), null, any()) } returns
            SmsProcessingOutcome.TerminallyDiscarded(snapshot.operationId, "prefilter_otp_verification")
        val providerlessSms = message().copy(providerMessageId = null)

        val result = pipeline.processSingle(
            sms = providerlessSms,
            trigger = "realtime",
            admittedSourceId = "durable-candidate-key-1"
        )

        val skipped = assertIs<PipelineService.ProcessingResult.Skipped>(result)
        assertEquals(PipelineService.SkipReason.NOT_TRANSACTION, skipped.reason)
        val admitted = admittedSources.single()
        assertEquals("durable-candidate-key-1", admitted.id)
        assertEquals(providerlessSms.sourceIdentity.messageId, admitted.sourceMessageId)
    }

    @Test
    fun `retryable coordinator outcome remains retryable`() = runTest {
        every { configuration.confirmedPrimaryCurrency() } returns "INR"
        coEvery { coordinator.processAdmitted(any(), snapshot, any(), lease, any()) } returns
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
        coEvery { coordinator.processAdmitted(any(), snapshot, any(), lease, any()) } returns
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
            coordinator.processAdmitted(any(), snapshot, any(), lease, any())
        }
    }

    @Test
    fun `decoded callbacks reach processing observer in order with completed output`() = runTest {
        every { configuration.confirmedPrimaryCurrency() } returns "INR"
        val model = SlmModelSpec("test-selector", "synthetic-model.gguf")
        coEvery {
            coordinator.processAdmitted(any(), snapshot, any(), lease, any())
        } coAnswers {
            val observer = arg<SmsProcessingObserver>(4)
            observer.onEvent(
                observerEvent(
                    SmsProcessingTransientEvent.InferenceStarted(model, true, 512)
                )
            )
            observer.onEvent(
                observerEvent(
                    SmsProcessingTransientEvent.DecodedToken("{", "{")
                )
            )
            observer.onEvent(
                observerEvent(
                    SmsProcessingTransientEvent.DecodedToken(
                        "\"decision\":\"none\"}",
                        "{\"decision\":\"none\"}"
                    )
                )
            )
            observer.onEvent(
                observerEvent(
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
                )
            )
            SmsProcessingOutcome.TerminallyDiscarded(
                snapshot.operationId,
                "extractor_none"
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
            operation().copy(
                state = "persisted",
                settledAt = 2,
                settlementReceiptJson = "{\"transaction_ids\":[\"saved-id\"]}"
            ),
            null
        )
        val enabled = MutableStateFlow(false)
        every { grammarPreferences.gbnfGrammarEnabled } returns enabled
        for (trigger in listOf("manual", "historical", "realtime")) {
            enabled.value = !enabled.value
            val result = if (trigger == "realtime") {
                pipeline.processSingle(message(), trigger = trigger)
            } else {
                pipeline.processSingle(message(), lease, trigger = trigger)
            }
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
        assertTrue(
            assertIs<PipelineService.ProcessingResult.Failure>(
                pipeline.processSingle(message(), lease)
            ).retryable
        )
        coEvery { store.admitForProcessing(any(), any()) } returns SmsProcessingAdmission.Conflict
        assertEquals(
            false,
            assertIs<PipelineService.ProcessingResult.Failure>(
                pipeline.processSingle(message(), trigger = "realtime")
            ).retryable
        )
        assertNoNewExtraction()
    }

    @Test
    fun `recovered ready and interrupted operations reuse their visible review without resnapshot`() = runTest {
        coEvery { store.admitForProcessing(any(), any()) } returns SmsProcessingAdmission.Existing(
            operation().copy(state = "interrupted"),
            "existing-review"
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
            SmsProcessingAdmission.Existing(
                operation().copy(state = "discarded", settledAt = 2),
                null
            ),
            SmsProcessingAdmission.Existing(
                operation().copy(state = "retain_review", settledAt = 2),
                "rejected-review",
                "rejected"
            )
        )
        repeat(2) {
            assertEquals(
                PipelineService.ProcessingResult.Skipped(PipelineService.SkipReason.NOT_TRANSACTION),
                pipeline.processSingle(message())
            )
        }
        assertNoNewExtraction()
    }

    @Test
    fun `owner confirmed transaction is reused without another model call`() = runTest {
        coEvery { store.admitForProcessing(any(), any()) } returns
            SmsProcessingAdmission.Saved(listOf("owner-confirmed-id"))
        assertEquals(
            PipelineService.ProcessingResult.Saved(listOf("owner-confirmed-id"), true),
            pipeline.processSingle(message())
        )
        assertNoNewExtraction()
    }

    @Test
    fun `identical providerless manual content does not become reliable identity`() = runTest {
        every { configuration.confirmedPrimaryCurrency() } returns "INR"
        coEvery { coordinator.processAdmitted(any(), snapshot, any(), null, any()) } returns
            SmsProcessingOutcome.RetainedForReview(snapshot.operationId, "review-id", emptyList())
        val sms = message().copy(providerMessageId = null)
        repeat(2) { pipeline.processSingle(sms, trigger = "manual") }
        assertEquals(2, admittedSources.map { it.id }.distinct().size)
        assertEquals(2, admittedSources.map { it.sourceMessageId }.distinct().size)
        assertEquals(1, admittedSources.map { it.sourceFingerprint }.distinct().size)
        assertTrue(admittedSources.all { it.sourceProviderMessageId == null })
    }

    @Test
    fun `explicit retry cannot steal active source processing`() = runTest {
        coEvery { store.retryContext("review-id") } throws SmsAdmissionBusyException()
        assertTrue(
            assertIs<PipelineService.ProcessingResult.Failure>(
                pipeline.retryReview("review-id", "current")
            ).retryable
        )
        assertNoNewExtraction()
    }

    @Test
    fun `explicit current settings retry passes preserved parent and event lineage`() = runTest {
        val source = source()
        val parent = operation(source.id).copy(
            state = "retain_review",
            settledAt = 2,
            configurationJson = """{"contract":"${SmsContractAssets.PROCESSING_CONFIG_CONTRACT}","currency_context":{"primary_currency":"INR","enabled_profile_ids":["core-en","india"]}}"""
        )
        coEvery { store.retryContext("review-id") } returns SmsReviewRetryContext(
            "review-id",
            source,
            parent
        )
        every { configuration.confirmedPrimaryCurrency() } returns "INR"
        every { grammarPreferences.gbnfGrammarEnabled } returns MutableStateFlow(true)
        coEvery { coordinator.process(any(), snapshot, any()) } returns
            SmsProcessingOutcome.RetainedForReview(snapshot.operationId, "review-id", emptyList())

        pipeline.retryReview("review-id", "current")

        coVerify(exactly = 1) {
            snapshotFactory.create(
                any(), "retry", "INR", listOf("core-en", "india"), any(), any(), source.admittedAt,
                any(), "llama.cpp-jni", any(), parent.stableEventId, parent.id, any(), true
            )
        }
    }

    @Test
    fun `original consolidated retry decodes stored snapshot and executes with frozen fields`() = runTest {
        val source = source()
        val parent = operation(source.id).copy(
            state = "retain_review",
            settledAt = 2,
            configurationJson = """{"contract":"${SmsContractAssets.PROCESSING_CONFIG_CONTRACT}"}"""
        )
        val frozenSnapshot = snapshot().let {
            it.copy(
                configuration = it.configuration.copy(
                    extractorEligible = false,
                    extractorIneligibilityReason = "runtime_unavailable",
                    modelIdentifier = null,
                    modelFileSha256 = null,
                    grammarEnabled = true
                )
            )
        }
        val retrySnapshot = mockk<SmsOperationSnapshot>()
        coEvery { store.retryContext("review-id") } returns SmsReviewRetryContext(
            "review-id",
            source,
            parent
        )
        every { snapshotFactory.decode(parent) } returns frozenSnapshot
        coEvery {
            snapshotFactory.create(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any()
            )
        } returns retrySnapshot
        coEvery { coordinator.process(any(), retrySnapshot, any()) } returns
            SmsProcessingOutcome.RetainedForReview("retry-operation", "review-id", emptyList())

        val result = pipeline.retryReview("review-id", "original")

        assertIs<PipelineService.ProcessingResult.Skipped>(result)
        coVerify(exactly = 1) {
            snapshotFactory.create(
                any(),
                "retry",
                "INR",
                listOf("core-en", "india"),
                frozenSnapshot.configuration.receivedTimestampEpochMs,
                frozenSnapshot.configuration.receivedTimestampProvenance,
                source.admittedAt,
                SmsModelIdentity(false, null, null),
                "llama.cpp-jni",
                any(),
                parent.stableEventId,
                parent.id,
                any(),
                true
            )
        }
        coVerify(exactly = 1) { coordinator.process(any(), retrySnapshot, any()) }
        coVerify(exactly = 0) { coordinator.processAdmitted(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { coordinator.currentModelIdentity() }
        verify(exactly = 0) { coordinator.modelIdentityForLease(any()) }
    }

    @Test
    fun `original retry for retired historical config contracts fails closed cleanly`() = runTest {
        val source = source()
        val parent = operation(source.id).copy(
            state = "retain_review",
            settledAt = 2,
            contractReleaseId = "native-integration-v4",
            configurationJson = """{"contract":"pocketfinancer.processing-config/4"}"""
        )
        coEvery { store.retryContext("review-id") } returns SmsReviewRetryContext(
            "review-id",
            source,
            parent
        )

        val result = assertIs<PipelineService.ProcessingResult.Failure>(
            pipeline.retryReview("review-id", "original")
        )

        assertFalse(result.retryable)
        assertNoNewExtraction()
    }

    private fun assertNoNewExtraction() {
        coVerify(exactly = 0) {
            snapshotFactory.create(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any()
            )
        }
        coVerify(exactly = 0) { coordinator.processAdmitted(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { coordinator.currentModelIdentity() }
        verify(exactly = 0) { coordinator.modelIdentityForLease(any()) }
    }

    private fun source(): AdmittedSmsSourceEntity {
        val sms = message()
        val identity = sms.sourceIdentity
        return AdmittedSmsSourceEntity(
            identity.opaqueCandidateKey,
            identity.connector,
            identity.messageId,
            identity.providerMessageId,
            identity.fallbackFingerprint,
            null,
            sms.address,
            sms.body,
            sms.sourceTimestamp,
            sms.type,
            "manual",
            "receipt-id",
            sms.date,
            "admitted"
        )
    }

    private fun operation(sourceId: String = "synthetic-source") = SmsProcessingOperationEntity(
        snapshot.operationId,
        sourceId,
        null,
        snapshot.stableEventId,
        "manual",
        "{}",
        "a".repeat(64),
        SmsContractAssets.RELEASE_ID,
        "ready",
        0,
        null,
        0,
        null,
        1,
        1,
        null,
        null,
        0
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

    private fun snapshot(): SmsOperationSnapshot {
        val operationId = "11111111-1111-4111-8111-111111111111"
        return SmsOperationSnapshot(
            operationId = operationId,
            parentOperationId = null,
            stableEventId = "22222222-2222-4222-8222-222222222222",
            configuration = SmsOperationConfiguration(
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
                releaseManifestHash = SmsContractAssets.MANIFEST_SHA256,
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
                grammarEnabled = false,
                validationProfileHash = "f".repeat(64),
                rolloutMode = "automatic"
            ),
            configurationJson = "{}",
            configurationHash = "e".repeat(64)
        )
    }
}
