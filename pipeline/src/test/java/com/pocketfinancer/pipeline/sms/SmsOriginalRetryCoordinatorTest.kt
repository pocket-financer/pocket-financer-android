package com.pocketfinancer.pipeline.sms

import android.content.Context
import com.pocketfinancer.data.repository.SmsOperationClaim
import com.pocketfinancer.data.repository.SmsProcessingStore
import com.pocketfinancer.data.repository.SmsSourceEvidence
import com.pocketfinancer.data.repository.SmsTraceReceipt
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/** Invented original-configuration retries and prefilter short-circuiting. */
class SmsOriginalRetryCoordinatorTest {
    private lateinit var store: SmsProcessingStore
    private val claim = SmsOperationClaim("retry-operation", "retry-owner", 1, Long.MAX_VALUE)
    private val source = AdmittedMessageRef(
        "invented-source",
        "invented-receipt",
        SmsProcessingStore.sha256(BODY)
    )

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
        coEvery { store.retainForReview(any(), any(), any(), any()) } returns "retry-review"
    }

    @Test
    fun `consolidated coordinator executes using factory claim without claiming again`() = runTest {
        val factory = mockk<SmsOperationSnapshotFactory>()
        every { factory.configurationMatches(any()) } returns true
        val coordinator = DefaultSmsProcessingCoordinator(
            mockk<Context>(), store, mockk(), mockk(), mockk(), mockk(), mockk(), factory
        )

        val result = assertIs<SmsProcessingOutcome.RetainedForReview>(
            coordinator.process(source, snapshot())
        )

        assertEquals("retry-review", result.reviewCaseId)
        assertEquals(listOf("runtime_unavailable"), result.reasons)
        coVerify(exactly = 1) { store.transition(claim, "claimed", "analyzed", any()) }
        coVerify(exactly = 0) { store.claim(any(), any()) }
        coVerify(exactly = 0) { store.retainUnownedForReview(any(), any(), any()) }
        coVerify(exactly = 1) { store.retainForReview(claim, any(), any(), any()) }
    }

    @Test
    fun `invalid preclaimed retry settles owned review rather than leaving claim stranded`() = runTest {
        val factory = mockk<SmsOperationSnapshotFactory>()
        every { factory.configurationMatches(any()) } returns false
        val coordinator = DefaultSmsProcessingCoordinator(
            mockk<Context>(), store, mockk(), mockk(), mockk(), mockk(), mockk(), factory
        )

        val result = assertIs<SmsProcessingOutcome.RetainedForReview>(
            coordinator.process(source, snapshot())
        )

        assertEquals(listOf("operation_release_incompatible"), result.reasons)
        coVerify(exactly = 0) { store.claim(any(), any()) }
        coVerify(exactly = 0) { store.retainUnownedForReview(any(), any(), any()) }
        coVerify(exactly = 1) { store.retainForReview(claim, any(), any(), any()) }
    }

    @Test
    fun `prefilter excludes otp before analyzer or model runtime and tombstones source`() = runTest {
        val otpBody = "Your OTP is 482910. Do not share."
        val otpRef = AdmittedMessageRef(
            "invented-source",
            "invented-receipt",
            SmsProcessingStore.sha256(otpBody)
        )
        coEvery { store.sourceEvidence(otpRef.sourceId) } returns SmsSourceEvidence(
            otpRef.sourceId, otpRef.admissionReceiptId, "SYNTH", otpBody, "invented-fingerprint",
            SOURCE_TIME, SOURCE_TIME, 0
        )
        val factory = mockk<SmsOperationSnapshotFactory>()
        every { factory.configurationMatches(any()) } returns true
        val extractor = mockk<DefaultDirectSmsExtractor>()
        val runtime = mockk<com.pocketfinancer.inference.SlmRuntime>()
        val coordinator = DefaultSmsProcessingCoordinator(
            mockk<Context>(), store, runtime, mockk(), mockk(), extractor, mockk(), factory
        )

        val result = assertIs<SmsProcessingOutcome.TerminallyDiscarded>(
            coordinator.process(otpRef, snapshot())
        )

        assertEquals("prefilter_otp_verification", result.reason)
        coVerify(exactly = 1) {
            store.settleDiscarded(
                claim = claim,
                reason = "prefilter_otp_verification",
                now = any(),
                retentionState = SmsProcessingStore.RETENTION_TOMBSTONED_EXCLUDED
            )
        }
        coVerify(exactly = 0) { store.recordAnalysis(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { extractor.extract(any(), any()) }
        verify { runtime wasNot Called }
    }

    private fun snapshot() = SmsOperationSnapshot(
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
            admissionTimestampEpochMs = SOURCE_TIME,
            receivedTimestampEpochMs = SOURCE_TIME,
            receivedTimestampProvenance = "acquisition_supplied_message_time",
            timezoneId = "UTC",
            primaryCurrency = "INR",
            enabledProfiles = listOf("core-en", "india"),
            releaseManifestHash = SmsContractAssets.MANIFEST_SHA256,
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
            grammarEnabled = false,
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
