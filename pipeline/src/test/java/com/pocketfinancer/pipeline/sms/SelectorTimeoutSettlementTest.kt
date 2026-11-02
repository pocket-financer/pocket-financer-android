package com.pocketfinancer.pipeline.sms

import android.content.Context
import com.pocketfinancer.data.repository.SmsOperationClaim
import com.pocketfinancer.data.repository.SmsProcessingStore
import com.pocketfinancer.data.repository.SmsSourceEvidence
import com.pocketfinancer.inference.DefaultDirectCandidateSelector
import com.pocketfinancer.inference.DirectCandidateSelectorResult
import com.pocketfinancer.inference.SlmLease
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SelectorNoDeadlineSettlementTest {
    @Test fun selectorCanRunPastFormerDeadline() = verifySettlement(false)

    @Test fun userCancellationStillStopsImport() = verifySettlement(true)

    private fun verifySettlement(userCancelled: Boolean) = runTest {
        val body = "INR 123.45 debited from account ending 1234 at TEST SHOP."
        val context = mockk<Context>(relaxed = true)
        every { context.assets.open(any()) } answers {
            File("src/main/assets", firstArg<String>()).inputStream()
        }
        val store = mockk<SmsProcessingStore>(relaxed = true)
        val snapshotFactory = mockk<SmsOperationSnapshotFactory>()
        val selector = mockk<DefaultDirectCandidateSelector>()
        val lease = mockk<SlmLease>(relaxed = true)
        every { lease.model.modelId } returns "selector-model"
        every { snapshotFactory.configurationMatches(any()) } returns true
        coEvery { store.settledReceipt(any()) } returns null
        coEvery { store.claim(any(), any()) } returns SmsOperationClaim(
            "operation-1", "owner", 1, System.currentTimeMillis() + 120_000)
        coEvery { store.sourceEvidence("source-1") } returns SmsSourceEvidence(
            "source-1", "receipt", "TEST", body, "fingerprint", 1_700_000_000_000,
            1_700_000_000_000, 0)
        coEvery { store.retainForReview(any(), any(), any()) } returns "review-1"
        coEvery { selector.select(any(), any()) } coAnswers {
            if (userCancelled) throw kotlinx.coroutines.CancellationException("User stopped")
            delay(60_001)
            DirectCandidateSelectorResult(
                null, "failed", "runtime_unavailable", lease.model, null, null
            )
        }
        val coordinator = DefaultSmsProcessingCoordinator(context, store, mockk(), mockk(),
            mockk(), selector, mockk(), snapshotFactory)
        val result = coordinator.processUsingLease(
            AdmittedMessageRef("source-1", "receipt", SmsProcessingStore.sha256(body)),
            operation("review_only"), lease)
        coVerify(exactly = 1) { selector.select(any(), any()) }
        if (userCancelled) {
            assertTrue(result is SmsProcessingOutcome.Stopped)
            coVerify(exactly = 1) { store.requestStop(any(), any()) }
            coVerify(exactly = 0) { store.retainForReview(any(), any(), any()) }
        } else {
            assertTrue(result is SmsProcessingOutcome.RetainedForReview)
            coVerify(exactly = 1) { store.retainForReview(any(), listOf("runtime_unavailable"), any()) }
            coVerify(exactly = 1) {
                store.recordSelectorAttempt(any(), any(), any(), null, "failed", null,
                    "runtime_unavailable", any(), any())
            }
            coVerify(exactly = 0) { store.requestStop(any(), any()) }
        }
    }
    private fun operation(rolloutMode: String) = SmsOperationSnapshot(
        operationId = "operation-1",
        parentOperationId = null,
        stableEventId = "stable-event-1",
        configuration = SmsOperationConfiguration(
            operationId = "operation-1",
            parentOperationId = null,
            sourceId = "source-1",
            sourceRefHash = SmsProcessingStore.sha256("source-1"),
            trigger = "diagnostic",
            createdAtEpochMs = 1_700_000_000_000,
            primaryCurrency = "INR",
            enabledProfiles = listOf("core-en", "india"),
            sourceTimestampEpochMs = 1_700_000_000_000,
            sourceTimestampProvenance = "acquisition_supplied_message_time",
            admissionTimestampEpochMs = 1_700_000_000_000,
            timezoneId = "UTC",
            releaseManifestHash = "c".repeat(64),
            currencyAssetHash = "d".repeat(64),
            profileAssetHashes = emptyMap(),
            selectorEligible = true,
            selectorIneligibilityReason = null,
            selectorModelId = "selector-model",
            selectorModelHash = "e".repeat(64),
            selectorRuntimeVersion = "runtime",
            osVersion = "android",
            deviceCohort = "test",
            promptHash = "f".repeat(64),
            rolloutMode = rolloutMode
        ),
        configurationJson = "{}",
        configurationHash = "configuration-hash"
    )
}
