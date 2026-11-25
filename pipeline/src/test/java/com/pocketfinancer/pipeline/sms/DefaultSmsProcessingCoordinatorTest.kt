package com.pocketfinancer.pipeline.sms

import android.content.Context
import com.pocketfinancer.data.repository.GroundedAccountResolution
import com.pocketfinancer.data.repository.GroundedAccountResolver
import com.pocketfinancer.data.repository.SmsAutomaticPersistenceConflict
import com.pocketfinancer.data.repository.SmsAutomaticPersistenceReceipt
import com.pocketfinancer.data.repository.SmsAutomaticTransactionInput
import com.pocketfinancer.data.repository.SmsDuplicateMatchCounts
import com.pocketfinancer.data.repository.SmsOperationClaim
import com.pocketfinancer.data.repository.SmsProcessingStore
import com.pocketfinancer.data.repository.SmsReviewV2Evidence
import com.pocketfinancer.data.repository.SmsSourceEvidence
import com.pocketfinancer.data.repository.SmsStopReceipt
import com.pocketfinancer.data.repository.SmsTraceReceipt
import com.pocketfinancer.inference.DefaultDirectCandidateSelector
import com.pocketfinancer.inference.DirectCandidateSelectorResult
import com.pocketfinancer.inference.SlmLease
import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.inference.SlmRuntime
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSmsProcessingCoordinatorTest {
    private lateinit var context: Context
    private lateinit var store: SmsProcessingStore
    private lateinit var runtime: SlmRuntime
    private lateinit var selector: DefaultDirectCandidateSelector
    private lateinit var extractor: DefaultDirectSmsExtractor
    private lateinit var accountResolver: GroundedAccountResolver
    private lateinit var snapshotFactory: SmsOperationSnapshotFactory
    private lateinit var lease: SlmLease
    private lateinit var model: SlmModelSpec
    private lateinit var modelFile: File

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        val assetsRoot = listOf(
            File("src/main/assets"),
            File("pipeline/src/main/assets")
        ).firstOrNull(File::isDirectory) ?: error("pipeline assets dir not found")
        every { context.assets.open(any()) } answers {
            File(assetsRoot, firstArg<String>()).inputStream()
        }
        store = mockk(relaxed = true)
        runtime = mockk(relaxed = true)
        selector = mockk()
        extractor = DefaultDirectSmsExtractor(selector)
        accountResolver = mockk()
        snapshotFactory = mockk()
        every { snapshotFactory.configurationMatches(any()) } returns true
        coEvery { store.settledReceipt(any()) } returns null
        coEvery { store.claim(any(), any()) } returns CLAIM
        coEvery { store.heartbeat(any(), any()) } returns CLAIM
        coEvery { store.appendTrace(any(), any(), any(), any(), any(), any()) } returns
            SmsTraceReceipt("trace-1", 0, "a".repeat(64))
        coEvery { store.retainForReview(any(), any(), any(), any()) } returns "review-id"
        coEvery { store.requestStop(any(), any()) } returns
            SmsStopReceipt(OP_ID, "stop-review", "stopped", committed = false)

        modelFile = File.createTempFile("coord-test-model", ".gguf").apply {
            writeText("synthetic-model-bytes")
            deleteOnExit()
        }
        model = SlmModelSpec("test-selector", modelFile.absolutePath)
        lease = mockk(relaxed = true)
        every { lease.model } returns model
    }

    @Test
    fun `prefilter excludes otp before analyzer or model runtime and tombstones source`() = runTest {
        val otpBody = "Your OTP is 482910. Do not share."
        stubSource(otpBody)
        val spyExtractor = mockk<DefaultDirectSmsExtractor>()
        val coordinator = DefaultSmsProcessingCoordinator(
            context, store, runtime, mockk(), mockk(), spyExtractor, accountResolver, snapshotFactory
        )

        val result = assertIs<SmsProcessingOutcome.TerminallyDiscarded>(
            coordinator.process(refFor(otpBody), snapshot(SmsModelIdentity(false, null, null)))
        )

        assertEquals("prefilter_otp_verification", result.reason)
        coVerify(exactly = 1) {
            store.settleDiscarded(
                claim = CLAIM,
                reason = "prefilter_otp_verification",
                now = any(),
                retentionState = SmsProcessingStore.RETENTION_TOMBSTONED_EXCLUDED
            )
        }
        coVerify(exactly = 0) { store.recordAnalysis(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { spyExtractor.extract(any(), any()) }
        verify { runtime wasNot Called }
    }

    @Test
    fun `valid none extraction settles body-free tombstoned_none`() = runTest {
        val body = "Your monthly credit card bill summary for May 2026 is 1200."
        stubSource(body)
        stubSelectorOutput("""{"decision":"none"}""")

        val coordinator = coordinator()
        val result = assertIs<SmsProcessingOutcome.TerminallyDiscarded>(
            coordinator.processUsingLease(
                refFor(body),
                snapshot(coordinator.modelIdentityForLease(lease)),
                lease
            )
        )

        assertEquals("extractor_none", result.reason)
        coVerify(exactly = 1) {
            store.settleDiscarded(
                claim = CLAIM,
                reason = "extractor_none",
                now = any(),
                retentionState = SmsProcessingStore.RETENTION_TOMBSTONED_NONE
            )
        }
    }

    @Test
    fun `abstain extraction routes to review with extractor_abstained`() = runTest {
        val body = "INR 500.00 transaction reference updated on A/c XX4321."
        stubSource(body)
        stubSelectorOutput("""{"decision":"abstain"}""")

        val coordinator = coordinator()
        val result = assertIs<SmsProcessingOutcome.RetainedForReview>(
            coordinator.processUsingLease(
                refFor(body),
                snapshot(coordinator.modelIdentityForLease(lease)),
                lease
            )
        )

        assertEquals(listOf("extractor_abstained"), result.reasons)
        coVerify(exactly = 0) { store.persistEligibleTransaction(any(), any(), any(), any()) }
        coVerify(exactly = 1) {
            store.retainForReview(CLAIM, listOf("extractor_abstained"), any(), any())
        }
    }

    @Test
    fun `eligible posted extraction with unique account and clear duplicates persists atomically`() =
        runTest {
            stubSource(POSTED_BODY)
            stubSelectorOutput(POSTED_RAW_JSON)
            coEvery { accountResolver.resolve("4321") } returns GroundedAccountResolution.UniquelyResolved(
                accountId = "acct-1",
                matchedAliasHash = "b".repeat(64),
                provenance = "confirmed_owned_account_alias_v1"
            )
            stubDuplicateCounts(SmsDuplicateMatchCounts(0, 0, 0, 0))
            val inputSlot = slot<SmsAutomaticTransactionInput>()
            coEvery {
                store.persistEligibleTransaction(CLAIM, capture(inputSlot), any(), any())
            } returns SmsAutomaticPersistenceReceipt(
                transactionId = "tx-persisted-1",
                revisionId = "rev-1"
            )

            val coordinator = coordinator()
            val result = assertIs<SmsProcessingOutcome.Persisted>(
                coordinator.processUsingLease(
                    refFor(POSTED_BODY),
                    snapshot(coordinator.modelIdentityForLease(lease)),
                    lease
                )
            )

            assertEquals(listOf("tx-persisted-1"), result.transactionIds)
            assertFalse(result.alreadyCommitted)
            assertEquals(24_500L, inputSlot.captured.amountMinorUnits)
            assertEquals("INR", inputSlot.captured.currency)
            assertEquals("debit", inputSlot.captured.direction)
            assertEquals("demo cafe", inputSlot.captured.counterparty)
            assertEquals("acct-1", inputSlot.captured.accountId)
            coVerify(exactly = 0) { store.retainForReview(any(), any(), any(), any()) }
        }

    @Test
    fun `5 minute duplicate window match routes posted extraction to review with duplicate_possible`() =
        runTest {
            stubSource(POSTED_BODY)
            stubSelectorOutput(POSTED_RAW_JSON)
            coEvery { accountResolver.resolve("4321") } returns GroundedAccountResolution.UniquelyResolved(
                accountId = "acct-1",
                matchedAliasHash = "b".repeat(64),
                provenance = "confirmed_owned_account_alias_v1"
            )
            stubDuplicateCounts(
                SmsDuplicateMatchCounts(
                    persistedSourceEvents = 0,
                    matchingSourceEvents = 0,
                    matchingTransactionFingerprints = 0,
                    windowDuplicateMatches = 1
                )
            )

            val coordinator = coordinator()
            val result = assertIs<SmsProcessingOutcome.RetainedForReview>(
                coordinator.processUsingLease(
                    refFor(POSTED_BODY),
                    snapshot(coordinator.modelIdentityForLease(lease)),
                    lease
                )
            )

            assertTrue("duplicate_possible" in result.reasons)
            coVerify(exactly = 0) { store.persistEligibleTransaction(any(), any(), any(), any()) }
            coVerify(exactly = 1) {
                store.retainForReview(CLAIM, listOf("duplicate_possible"), any(), any())
            }
        }

    @Test
    fun `unresolved and ambiguous account resolutions route posted extraction to review`() = runTest {
        stubSource(POSTED_BODY)
        stubSelectorOutput(POSTED_RAW_JSON)
        stubDuplicateCounts(SmsDuplicateMatchCounts(0, 0, 0, 0))

        val coordinator = coordinator()
        val snap = snapshot(coordinator.modelIdentityForLease(lease))

        coEvery { accountResolver.resolve("4321") } returns GroundedAccountResolution.Unresolved
        val unresolved = assertIs<SmsProcessingOutcome.RetainedForReview>(
            coordinator.processUsingLease(refFor(POSTED_BODY), snap, lease)
        )
        assertEquals(listOf("account_resolution_unresolved"), unresolved.reasons)

        coEvery { accountResolver.resolve("4321") } returns
            GroundedAccountResolution.Ambiguous(listOf("acct-1", "acct-2"))
        val ambiguous = assertIs<SmsProcessingOutcome.RetainedForReview>(
            coordinator.processUsingLease(refFor(POSTED_BODY), snap, lease)
        )
        assertEquals(listOf("account_resolution_ambiguous"), ambiguous.reasons)
        coVerify(exactly = 0) { store.persistEligibleTransaction(any(), any(), any(), any()) }
    }

    @Test
    fun `atomic persistence conflict and unexpected write error fall back cleanly to review`() =
        runTest {
            stubSource(POSTED_BODY)
            stubSelectorOutput(POSTED_RAW_JSON)
            coEvery { accountResolver.resolve("4321") } returns GroundedAccountResolution.UniquelyResolved(
                accountId = "acct-1",
                matchedAliasHash = "b".repeat(64),
                provenance = "confirmed_owned_account_alias_v1"
            )
            stubDuplicateCounts(SmsDuplicateMatchCounts(0, 0, 0, 0))

            val coordinator = coordinator()
            val snap = snapshot(coordinator.modelIdentityForLease(lease))

            coEvery { store.persistEligibleTransaction(any(), any(), any(), any()) } throws
                SmsAutomaticPersistenceConflict("already committed")
            val conflict = assertIs<SmsProcessingOutcome.RetainedForReview>(
                coordinator.processUsingLease(refFor(POSTED_BODY), snap, lease)
            )
            assertEquals(listOf("duplicate_already_persisted"), conflict.reasons)

            coEvery { store.persistEligibleTransaction(any(), any(), any(), any()) } throws
                IllegalStateException("disk busy")
            val writeFailure = assertIs<SmsProcessingOutcome.RetainedForReview>(
                coordinator.processUsingLease(refFor(POSTED_BODY), snap, lease)
            )
            assertEquals(listOf("persistence_atomic_write_failed"), writeFailure.reasons)
        }

    @Test
    fun `invalid extractor JSON routes to review with validation error and partial evidence`() =
        runTest {
            stubSource(POSTED_BODY)
            stubSelectorOutput(
                """{"decision":"posted","amount":{"value":"245.00","currency":"INR","evidence":{"start_scalar":0,"end_scalar":10,"text":"INR 245.00"}}}"""
            )
            val evidenceSlot = slot<SmsReviewV2Evidence>()
            coEvery {
                store.retainForReview(CLAIM, any(), any(), capture(evidenceSlot))
            } returns "review-invalid"

            val coordinator = coordinator()
            val result = assertIs<SmsProcessingOutcome.RetainedForReview>(
                coordinator.processUsingLease(
                    refFor(POSTED_BODY),
                    snapshot(coordinator.modelIdentityForLease(lease)),
                    lease
                )
            )

            assertEquals("review-invalid", result.reviewCaseId)
            assertEquals("extractor_validation", evidenceSlot.captured.furthestStage)
            assertTrue(evidenceSlot.captured.fieldEvidenceJson.contains("24500"))
            coVerify(exactly = 1) {
                store.recordSelectorAttempt(
                    claim = CLAIM,
                    runtimeProfileJson = any(),
                    requestJson = any(),
                    rawOutput = any(),
                    completion = "invalid",
                    validatedSelectionJson = null,
                    safeErrorCode = any(),
                    startedAt = any(),
                    completedAt = any()
                )
            }
        }

    @Test
    fun `model file sha mismatch routes to review as runtime_ineligible without running selector`() =
        runTest {
            stubSource(POSTED_BODY)
            val coordinator = coordinator()
            val mismatchedIdentity = SmsModelIdentity(
                eligible = true,
                modelIdentifier = model.modelId,
                modelFileSha256 = "f".repeat(64)
            )

            val result = assertIs<SmsProcessingOutcome.RetainedForReview>(
                coordinator.processUsingLease(refFor(POSTED_BODY), snapshot(mismatchedIdentity), lease)
            )

            assertEquals(listOf("runtime_ineligible"), result.reasons)
            coVerify(exactly = 0) { selector.select(any(), any()) }
        }

    @Test
    fun `selector running past former 60s deadline still settles cleanly without timeout cancellation`() =
        runTest {
            stubSource(POSTED_BODY)
            coEvery { selector.select(any(), any()) } coAnswers {
                delay(60_001)
                DirectCandidateSelectorResult(
                    null, "failed", "runtime_unavailable", model, null, null
                )
            }

            val coordinator = coordinator()
            val result = assertIs<SmsProcessingOutcome.RetainedForReview>(
                coordinator.processUsingLease(
                    refFor(POSTED_BODY),
                    snapshot(coordinator.modelIdentityForLease(lease)),
                    lease
                )
            )

            assertEquals(listOf("runtime_unavailable"), result.reasons)
            coVerify(exactly = 1) {
                store.retainForReview(CLAIM, listOf("runtime_unavailable"), any(), any())
            }
            coVerify(exactly = 0) { store.requestStop(any(), any()) }
        }

    @Test
    fun `user cancellation stops processing via store requestStop without retaining review`() =
        runTest {
            stubSource(POSTED_BODY)
            coEvery { selector.select(any(), any()) } coAnswers {
                throw CancellationException("User stopped")
            }

            val coordinator = coordinator()
            val result = assertIs<SmsProcessingOutcome.Stopped>(
                coordinator.processUsingLease(
                    refFor(POSTED_BODY),
                    snapshot(coordinator.modelIdentityForLease(lease)),
                    lease
                )
            )

            assertEquals("processing_cancelled", result.reason)
            coVerify(exactly = 1) { store.requestStop(OP_ID, any()) }
            coVerify(exactly = 0) { store.retainForReview(any(), any(), any(), any()) }
        }

    @Test
    fun `preclaimed retry executes without reclaiming and invalid preclaimed retry releases cleanly`() =
        runTest {
            val retryClaim = SmsOperationClaim("retry-op", "retry-owner", 1, Long.MAX_VALUE)
            stubSource(POSTED_BODY)
            val retrySnapshot = snapshot(
                identity = SmsModelIdentity(false, null, null),
                operationId = retryClaim.operationId,
                parentOperationId = "parent-op",
                trigger = "retry",
                ineligibilityReason = "runtime_unavailable",
                admissionClaim = retryClaim
            )

            val coordinator = coordinator()
            val validResult = assertIs<SmsProcessingOutcome.RetainedForReview>(
                coordinator.process(refFor(POSTED_BODY), retrySnapshot)
            )
            assertEquals(listOf("runtime_unavailable"), validResult.reasons)
            coVerify(exactly = 1) { store.transition(retryClaim, "claimed", "analyzed", any()) }
            coVerify(exactly = 0) { store.claim(any(), any()) }
            coVerify(exactly = 0) { store.retainUnownedForReview(any(), any(), any()) }

            every { snapshotFactory.configurationMatches(any()) } returns false
            val invalidResult = assertIs<SmsProcessingOutcome.RetainedForReview>(
                coordinator.process(refFor(POSTED_BODY), retrySnapshot)
            )
            assertEquals(listOf("operation_release_incompatible"), invalidResult.reasons)
            coVerify(exactly = 0) { store.claim(any(), any()) }
            coVerify(exactly = 1) {
                store.retainForReview(retryClaim, listOf("operation_release_incompatible"), any(), any())
            }
        }

    @Test
    fun `source digest mismatch routes to review before prefilter or model execution`() = runTest {
        stubSource(POSTED_BODY)
        val tamperedRef = AdmittedMessageRef(SOURCE_ID, RECEIPT_ID, "0".repeat(64))

        val coordinator = coordinator()
        val result = assertIs<SmsProcessingOutcome.RetainedForReview>(
            coordinator.processUsingLease(
                tamperedRef,
                snapshot(coordinator.modelIdentityForLease(lease)),
                lease
            )
        )

        assertEquals(listOf("persistence_configuration_hash_mismatch"), result.reasons)
        coVerify(exactly = 0) { selector.select(any(), any()) }
    }

    @Test
    fun `already settled receipts short-circuit idempotently without re-claiming`() = runTest {
        val coordinator = coordinator()
        val snap = snapshot(coordinator.modelIdentityForLease(lease))

        coEvery { store.settledReceipt(OP_ID) } returns
            ("persisted" to """{"transaction_ids":["tx-existing"]}""")
        val persisted = assertIs<SmsProcessingOutcome.Persisted>(
            coordinator.processUsingLease(refFor(POSTED_BODY), snap, lease)
        )
        assertEquals(listOf("tx-existing"), persisted.transactionIds)
        assertTrue(persisted.alreadyCommitted)

        coEvery { store.settledReceipt(OP_ID) } returns ("discarded" to null)
        val discarded = assertIs<SmsProcessingOutcome.TerminallyDiscarded>(
            coordinator.processUsingLease(refFor(POSTED_BODY), snap, lease)
        )
        assertEquals("already_discarded", discarded.reason)
        coVerify(exactly = 0) { store.claim(any(), any()) }
    }

    private fun coordinator() = DefaultSmsProcessingCoordinator(
        context, store, runtime, mockk(), mockk(), extractor, accountResolver, snapshotFactory
    )

    private fun refFor(body: String) = AdmittedMessageRef(
        SOURCE_ID, RECEIPT_ID, SmsProcessingStore.sha256(body)
    )

    private fun stubSource(body: String) {
        coEvery { store.sourceEvidence(SOURCE_ID) } returns SmsSourceEvidence(
            SOURCE_ID, RECEIPT_ID, "SYNTH", body, "fp", TIME, TIME, 0
        )
    }

    private fun stubSelectorOutput(raw: String) {
        coEvery { selector.select(any(), any()) } returns DirectCandidateSelectorResult(
            rawOutput = raw,
            completion = "complete",
            safeErrorCode = null,
            model = model,
            performance = null,
            cache = null
        )
    }

    private fun stubDuplicateCounts(counts: SmsDuplicateMatchCounts) {
        coEvery {
            store.duplicateMatchCounts(
                operationId = OP_ID,
                sourceId = SOURCE_ID,
                stableEventId = EVENT_ID,
                transactionFingerprint = any(),
                accountId = "acct-1",
                direction = "debit",
                minorUnits = 24_500L,
                currency = "INR",
                occurredAtEpochMs = TIME,
                duplicateWindowMs = SmsContractAssets.DUPLICATE_WINDOW_MS
            )
        } returns counts
    }

    private fun snapshot(
        identity: SmsModelIdentity,
        operationId: String = OP_ID,
        parentOperationId: String? = null,
        trigger: String = "realtime",
        ineligibilityReason: String? = null,
        admissionClaim: SmsOperationClaim? = null
    ) = SmsOperationSnapshot(
        operationId = operationId,
        parentOperationId = parentOperationId,
        stableEventId = EVENT_ID,
        configuration = SmsOperationConfiguration(
            operationId = operationId,
            parentOperationId = parentOperationId,
            sourceId = SOURCE_ID,
            sourceRefHash = SmsProcessingStore.sha256(SOURCE_ID),
            trigger = trigger,
            createdAtEpochMs = TIME,
            admissionTimestampEpochMs = TIME,
            receivedTimestampEpochMs = TIME,
            receivedTimestampProvenance = "platform_received",
            timezoneId = "UTC",
            primaryCurrency = "INR",
            enabledProfiles = listOf("core-en", "india"),
            releaseManifestHash = SmsContractAssets.MANIFEST_SHA256,
            currencyAssetHash = "c".repeat(64),
            profileAssetHashes = mapOf("core-en" to "d".repeat(64)),
            extractorEligible = identity.eligible,
            extractorIneligibilityReason = ineligibilityReason,
            modelIdentifier = identity.modelIdentifier,
            modelFileSha256 = identity.modelFileSha256,
            modelIdentityKind = identity.kind,
            runtimeVersion = "llama.cpp-jni",
            osVersion = "14",
            deviceCohort = "Pixel 9",
            promptHash = "e".repeat(64),
            grammarHash = "f".repeat(64),
            grammarEnabled = false,
            validationProfileHash = "0".repeat(64)
        ),
        configurationJson = "{}",
        configurationHash = "1".repeat(64),
        admissionClaim = admissionClaim
    )

    private companion object {
        const val OP_ID = "11111111-1111-4111-8111-111111111111"
        const val EVENT_ID = "22222222-2222-4222-8222-222222222222"
        const val SOURCE_ID = "source-1"
        const val RECEIPT_ID = "receipt-1"
        const val TIME = 1_700_000_000_000L
        const val POSTED_BODY = "INR 245.00 sent via UPI from A/c XX4321 at DEMO CAFE"
        const val POSTED_RAW_JSON =
            """{"decision":"posted","amount":{"value":"245.00","currency":"INR","evidence":{"start_scalar":0,"end_scalar":10,"text":"INR 245.00"}},"direction":{"value":"debit","evidence":{"start_scalar":11,"end_scalar":15,"text":"sent"}},"account":{"reference":"XX4321","evidence":{"start_scalar":33,"end_scalar":39,"text":"XX4321"}},"counterparty":{"value":"DEMO CAFE","evidence":{"start_scalar":43,"end_scalar":52,"text":"DEMO CAFE"}}}"""
        val CLAIM = SmsOperationClaim(OP_ID, "owner", 1, Long.MAX_VALUE)
    }
}
