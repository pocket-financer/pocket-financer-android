package com.pocketfinancer.pipeline.sms

import android.content.Context
import com.pocketfinancer.data.repository.GroundedAccountResolution
import com.pocketfinancer.data.repository.GroundedAccountResolver
import com.pocketfinancer.data.repository.SmsDuplicateMatchCounts
import com.pocketfinancer.data.repository.SmsOperationClaim
import com.pocketfinancer.data.repository.SmsProcessingStore
import com.pocketfinancer.data.repository.SmsSourceEvidence
import com.pocketfinancer.data.repository.SmsTraceReceipt
import com.pocketfinancer.inference.DefaultDirectCandidateSelector
import com.pocketfinancer.inference.DirectCandidateSelectorResult
import com.pocketfinancer.inference.SlmLease
import com.pocketfinancer.inference.SlmModelSpec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class DefaultSmsProcessingCoordinatorTest {
    private lateinit var context: Context
    private lateinit var store: SmsProcessingStore
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
        every { context.assets.open(any()) } answers {
            File("src/main/assets", firstArg<String>()).inputStream()
        }
        store = mockk(relaxed = true)
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

        modelFile = File.createTempFile("coord-test-model", ".gguf").apply {
            writeText("synthetic-model")
            deleteOnExit()
        }
        model = SlmModelSpec("test-selector", modelFile.absolutePath)
        lease = mockk(relaxed = true)
        every { lease.model } returns model
    }

    @Test
    fun `valid none extraction settles body-free tombstoned_none`() = runTest {
        val body = "Your monthly credit card bill summary for May 2026 is 1200."
        coEvery { store.sourceEvidence(SOURCE_ID) } returns SmsSourceEvidence(
            SOURCE_ID, RECEIPT_ID, "SYNTH", body, "fp", TIME, TIME, 0
        )
        coEvery { selector.select(any(), any()) } returns DirectCandidateSelectorResult(
            rawOutput = """{"decision":"none"}""",
            completion = "complete",
            safeErrorCode = null,
            model = model,
            performance = null,
            cache = null
        )

        val coordinator = coordinator()
        val modelIdentity = coordinator.modelIdentityForLease(lease)
        val result = assertIs<SmsProcessingOutcome.TerminallyDiscarded>(
            coordinator.processUsingLease(
                AdmittedMessageRef(SOURCE_ID, RECEIPT_ID, SmsProcessingStore.sha256(body)),
                snapshot(modelIdentity),
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
    fun `5 minute duplicate window match routes posted extraction to review with duplicate_possible`() = runTest {
        val body = "INR 245.00 sent via UPI from A/c XX4321 at DEMO CAFE"
        coEvery { store.sourceEvidence(SOURCE_ID) } returns SmsSourceEvidence(
            SOURCE_ID, RECEIPT_ID, "SYNTH", body, "fp", TIME, TIME, 0
        )
        val raw = """{"decision":"posted","amount":{"value":"245.00","currency":"INR","evidence":{"start_scalar":0,"end_scalar":10,"text":"INR 245.00"}},"direction":{"value":"debit","evidence":{"start_scalar":11,"end_scalar":15,"text":"sent"}},"account":{"reference":"XX4321","evidence":{"start_scalar":33,"end_scalar":39,"text":"XX4321"}},"counterparty":{"value":"DEMO CAFE","evidence":{"start_scalar":43,"end_scalar":52,"text":"DEMO CAFE"}}}"""
        coEvery { selector.select(any(), any()) } returns DirectCandidateSelectorResult(
            rawOutput = raw,
            completion = "complete",
            safeErrorCode = null,
            model = model,
            performance = null,
            cache = null
        )
        coEvery { accountResolver.resolve("4321") } returns GroundedAccountResolution.UniquelyResolved(
            accountId = "acct-1",
            matchedAliasHash = "b".repeat(64),
            provenance = "confirmed_owned_account_alias_v1"
        )
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
        } returns SmsDuplicateMatchCounts(
            persistedSourceEvents = 0,
            matchingSourceEvents = 0,
            matchingTransactionFingerprints = 0,
            windowDuplicateMatches = 1
        )

        val coordinator = coordinator()
        val result = assertIs<SmsProcessingOutcome.RetainedForReview>(
            coordinator.processUsingLease(
                AdmittedMessageRef(SOURCE_ID, RECEIPT_ID, SmsProcessingStore.sha256(body)),
                snapshot(coordinator.modelIdentityForLease(lease)),
                lease
            )
        )

        assertTrue("duplicate_possible" in result.reasons)
        coVerify(exactly = 0) { store.persistEligibleTransaction(any(), any(), any(), any()) }
        coVerify(exactly = 1) { store.retainForReview(CLAIM, listOf("duplicate_possible"), any(), any()) }
    }

    private fun coordinator() = DefaultSmsProcessingCoordinator(
        context, store, mockk(), mockk(), mockk(), extractor, accountResolver, snapshotFactory
    )

    private fun snapshot(identity: SmsModelIdentity) = SmsOperationSnapshot(
        operationId = OP_ID,
        parentOperationId = null,
        stableEventId = EVENT_ID,
        configuration = SmsOperationConfiguration(
            operationId = OP_ID,
            parentOperationId = null,
            sourceId = SOURCE_ID,
            sourceRefHash = SmsProcessingStore.sha256(SOURCE_ID),
            trigger = "realtime",
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
            extractorIneligibilityReason = null,
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
        configurationHash = "1".repeat(64)
    )

    private companion object {
        const val OP_ID = "11111111-1111-4111-8111-111111111111"
        const val EVENT_ID = "22222222-2222-4222-8222-222222222222"
        const val SOURCE_ID = "source-1"
        const val RECEIPT_ID = "receipt-1"
        const val TIME = 1_700_000_000_000L
        val CLAIM = SmsOperationClaim(OP_ID, "owner", 1, Long.MAX_VALUE)
    }
}
