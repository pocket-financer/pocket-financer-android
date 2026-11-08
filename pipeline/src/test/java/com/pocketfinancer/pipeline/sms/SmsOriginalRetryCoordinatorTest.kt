package com.pocketfinancer.pipeline.sms

import android.content.Context
import com.pocketfinancer.data.repository.SmsOperationClaim
import com.pocketfinancer.data.repository.SmsProcessingStore
import com.pocketfinancer.data.repository.SmsSourceEvidence
import com.pocketfinancer.data.repository.SmsTraceReceipt
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/** Invented original-configuration retries; Room replay interleaving is covered in data tests. */
class SmsOriginalRetryCoordinatorTest {
    private lateinit var store: SmsProcessingStore
    private val claim = SmsOperationClaim("retry-operation", "retry-owner", 1, Long.MAX_VALUE)
    private val source = AdmittedMessageRef("invented-source", "invented-receipt", SmsProcessingStore.sha256(BODY))

    @Before
    fun setUp() {
        store = mockk(relaxed = true)
        coEvery { store.settledReceipt(any()) } returns null
        coEvery { store.sourceEvidence(source.sourceId) } returns SmsSourceEvidence(
            source.sourceId, source.admissionReceiptId, "SYNTH", BODY, "invented-fingerprint",
            SOURCE_TIME, SOURCE_TIME, 0
        )
        coEvery { store.appendTrace(any(), any(), any(), any(), any(), any()) } returns
            SmsTraceReceipt("invented-trace", 0, "a".repeat(64))
        coEvery { store.retainForReview(any(), any(), any()) } returns "retry-review"
    }

    @Test
    fun `original v2 coordinator executes using factory claim without claiming again`() = runTest {
        val factory = mockk<SmsOperationSnapshotFactory>()
        every { factory.configurationMatches(any()) } returns true
        val coordinator = DefaultSmsProcessingCoordinator(
            mockk<Context>(), store, mockk(), mockk(), mockk(), mockk(), mockk(), factory
        )

        val result = assertIs<SmsProcessingOutcome.RetainedForReview>(
            coordinator.process(source, v2Snapshot())
        )

        assertEquals("retry-review", result.reviewCaseId)
        assertTrue("runtime_unavailable" in result.reasons)
        assertUsedInitialClaim()
    }

    @Test
    fun `original v4 coordinator executes using factory claim without claiming again`() = runTest {
        val factory = mockk<SmsV4OperationSnapshotFactory>()
        every { factory.configurationMatches(any()) } returns true
        val coordinator = DefaultSmsV4ProcessingCoordinator(
            mockk<Context>(), store, mockk(), mockk(), mockk(), mockk(), mockk(), factory
        )

        val result = assertIs<SmsProcessingOutcome.RetainedForReview>(
            coordinator.process(source, v4Snapshot())
        )

        assertEquals("retry-review", result.reviewCaseId)
        assertEquals(listOf("runtime_unavailable"), result.reasons)
        assertUsedInitialClaim()
    }

    @Test
    fun `invalid preclaimed v2 retry settles owned review rather than leaving claim stranded`() = runTest {
        val factory = mockk<SmsOperationSnapshotFactory>()
        every { factory.configurationMatches(any()) } returns false
        val coordinator = DefaultSmsProcessingCoordinator(
            mockk<Context>(), store, mockk(), mockk(), mockk(), mockk(), mockk(), factory
        )

        val result = assertIs<SmsProcessingOutcome.RetainedForReview>(
            coordinator.process(source, v2Snapshot())
        )

        assertEquals(listOf("persistence_configuration_hash_mismatch"), result.reasons)
        assertRetainedInitialClaim()
    }

    @Test
    fun `invalid preclaimed v4 retry settles owned review rather than leaving claim stranded`() = runTest {
        val factory = mockk<SmsV4OperationSnapshotFactory>()
        every { factory.configurationMatches(any()) } returns false
        val coordinator = DefaultSmsV4ProcessingCoordinator(
            mockk<Context>(), store, mockk(), mockk(), mockk(), mockk(), mockk(), factory
        )

        val result = assertIs<SmsProcessingOutcome.RetainedForReview>(
            coordinator.process(source, v4Snapshot())
        )

        assertEquals(listOf("persistence_configuration_hash_mismatch"), result.reasons)
        assertRetainedInitialClaim()
    }

    private fun assertUsedInitialClaim() {
        coVerify(exactly = 1) { store.transition(claim, "claimed", "analyzed", any()) }
        assertRetainedInitialClaim()
    }

    private fun assertRetainedInitialClaim() {
        coVerify(exactly = 0) { store.claim(any(), any()) }
        coVerify(exactly = 0) { store.retainUnownedForReview(any(), any(), any()) }
        coVerify(exactly = 1) { store.retainForReview(claim, any(), any()) }
    }

    private fun v2Snapshot() = SmsOperationSnapshot(
        operationId = claim.operationId,
        parentOperationId = "parent-operation",
        stableEventId = "preserved-event",
        configuration = SmsOperationConfiguration(
            operationId = claim.operationId,
            parentOperationId = "parent-operation",
            sourceId = source.sourceId,
            sourceRefHash = SmsProcessingStore.sha256(source.sourceId),
            trigger = "retry",
            createdAtEpochMs = SOURCE_TIME,
            primaryCurrency = "INR",
            enabledProfiles = listOf("core-en", "india"),
            sourceTimestampEpochMs = SOURCE_TIME,
            sourceTimestampProvenance = "acquisition_supplied_message_time",
            admissionTimestampEpochMs = SOURCE_TIME,
            timezoneId = "UTC",
            releaseManifestHash = "a".repeat(64),
            currencyAssetHash = "b".repeat(64),
            profileAssetHashes = emptyMap(),
            selectorEligible = false,
            selectorIneligibilityReason = "runtime_unavailable",
            selectorModelId = "unavailable",
            selectorModelHash = null,
            selectorRuntimeVersion = "invented-runtime",
            osVersion = "invented-os",
            deviceCohort = "invented-cohort",
            promptHash = "c".repeat(64)
        ),
        configurationJson = "{}",
        configurationHash = "d".repeat(64),
        admissionClaim = claim
    )

    private fun v4Snapshot() = SmsV4OperationSnapshot(
        operationId = claim.operationId,
        parentOperationId = "parent-operation",
        stableEventId = "preserved-event",
        configuration = SmsV4OperationConfiguration(
            operationId = claim.operationId,
            parentOperationId = "parent-operation",
            sourceId = source.sourceId,
            sourceRefHash = SmsProcessingStore.sha256(source.sourceId),
            trigger = "retry",
            createdAtEpochMs = SOURCE_TIME,
            admissionTimestampEpochMs = SOURCE_TIME,
            receivedTimestampEpochMs = SOURCE_TIME,
            receivedTimestampProvenance = "acquisition_supplied_message_time",
            timezoneId = "UTC",
            primaryCurrency = "INR",
            enabledProfiles = listOf("core-en", "india"),
            releaseManifestHash = NativeSmsV4Assets.MANIFEST_SHA256,
            currencyAssetHash = "b".repeat(64),
            profileAssetHashes = emptyMap(),
            extractorEligible = false,
            extractorIneligibilityReason = "runtime_unavailable",
            modelIdentifier = null,
            modelFileSha256 = null,
            modelIdentityKind = "file_sha256",
            runtimeVersion = "invented-runtime",
            osVersion = "invented-os",
            deviceCohort = "invented-cohort",
            promptHash = "c".repeat(64),
            grammarHash = "d".repeat(64),
            validationProfileHash = "e".repeat(64)
        ),
        configurationJson = "{}",
        configurationHash = "f".repeat(64),
        admissionClaim = claim
    )

    private companion object {
        const val SOURCE_TIME = 1_700_000_000_000L
        const val BODY = "INR 10.00 debited from account ending 1234 at INVENTED SHOP."
    }
}
