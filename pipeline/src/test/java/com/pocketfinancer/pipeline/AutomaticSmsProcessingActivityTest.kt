package com.pocketfinancer.pipeline

import com.pocketfinancer.data.model.QueuedSmsCandidate
import com.pocketfinancer.data.model.SmsCandidateOrigin
import com.pocketfinancer.data.model.SmsSourceIdentity
import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.inference.SlmExtractionResult
import com.pocketfinancer.inference.SlmPerformanceData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class AutomaticSmsProcessingActivityTest {
    @Test
    fun `direct selector activity bounds decoded delta separately from cumulative output`() {
        val store = AutomaticSmsProcessingActivityStore()
        val owner = AutomaticSmsProcessingOwner("candidate", "claim")
        val session = requireNotNull(store.begin(owner, "BANK", "INR 10 debited", 1L))

        session.onEvent(
            PipelineService.ProcessingEvent.InferenceStarted(
                model = SlmModelSpec("test", "/tmp/test.gguf"),
                grammarEnabled = true,
                answerTokenBudget = 512
            )
        )
        session.onEvent(
            PipelineService.ProcessingEvent.JsonTokenDelta(
                delta = "d".repeat(5_000),
                cumulativeStructuredOutput = "x".repeat(70_000)
            )
        )

        val activity = requireNotNull(store.activity.value)
        assertEquals(AutomaticSmsProcessingStage.GENERATING, activity.stage)
        assertEquals(512, activity.answerTokenBudget)
        assertEquals(4_096, activity.decodedTokenDelta.length)
        assertEquals(64_000, activity.jsonOutput.length)
        assertTrue(activity.jsonOutputTruncated)

        session.close()
        assertNull(store.activity.value)
    }

    @Test
    fun `callbacks remain ordered and completion replaces preview with final output`() {
        var now = 0L
        val store = AutomaticSmsProcessingActivityStore()
        val owner = AutomaticSmsProcessingOwner("candidate", "claim")
        val session = requireNotNull(
            store.begin(owner, "BANK", "body", 1L) { now }
        )
        val model = SlmModelSpec("test", "/tmp/test.gguf")

        session.onEvent(
            PipelineService.ProcessingEvent.InferenceStarted(
                model = model,
                grammarEnabled = true,
                answerTokenBudget = 512
            )
        )
        session.onEvent(
            PipelineService.ProcessingEvent.JsonTokenDelta("{", "{")
        )
        assertEquals("{", store.activity.value?.decodedTokenDelta)
        assertEquals("{", store.activity.value?.jsonOutput)

        now += 50_000_000L
        session.onEvent(
            PipelineService.ProcessingEvent.JsonTokenDelta(
                "\"decision\":\"none\"}",
                "{\"decision\":\"none\"}"
            )
        )
        assertEquals(
            "\"decision\":\"none\"}",
            store.activity.value?.decodedTokenDelta
        )
        assertEquals(
            "{\"decision\":\"none\"}",
            store.activity.value?.jsonOutput
        )

        session.onEvent(
            PipelineService.ProcessingEvent.InferenceCompleted(
                SlmExtractionResult.Success(
                    json = "{\"decision\":\"posted\"}",
                    perf = SlmPerformanceData(1, 2, 100, 4),
                    model = model
                )
            )
        )
        val completed = requireNotNull(store.activity.value)
        assertEquals("", completed.decodedTokenDelta)
        assertEquals("{\"decision\":\"posted\"}", completed.jsonOutput)
        assertEquals(4, completed.performance?.generatedTokens)
    }

    @Test
    fun `stale owner cannot clear successor activity`() {
        val store = AutomaticSmsProcessingActivityStore()
        val oldOwner = AutomaticSmsProcessingOwner("candidate", "old-claim")
        val oldSession = requireNotNull(
            store.begin(oldOwner, "OLD", "old body", 1L)
        )

        assertTrue(store.clear(oldOwner))
        val successorOwner = AutomaticSmsProcessingOwner(
            "successor-candidate",
            "new-claim"
        )
        requireNotNull(store.begin(successorOwner, "NEW", "new body", 2L))

        oldSession.onEvent(
            PipelineService.ProcessingEvent.JsonTokenDelta("stale", "stale")
        )
        oldSession.close()

        val successor = requireNotNull(store.activity.value)
        assertEquals(successorOwner, successor.owner)
        assertEquals("new body", successor.body)
        assertEquals("", successor.jsonOutput)
    }

    @Test
    fun `cancellation clears source delta and cumulative output`() = runTest {
        val store = AutomaticSmsProcessingActivityStore()
        val started = CompletableDeferred<Unit>()
        val job = launch {
            withAutomaticSmsProcessingActivity(
                candidate = automaticCandidate(),
                claimToken = "claim",
                store = store
            ) { session ->
                requireNotNull(session).onEvent(
                    PipelineService.ProcessingEvent.JsonTokenDelta(
                        "private-delta",
                        "private-cumulative-output"
                    )
                )
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        assertEquals("private-delta", store.activity.value?.decodedTokenDelta)
        assertEquals(
            "private-cumulative-output",
            store.activity.value?.jsonOutput
        )

        job.cancelAndJoin()

        assertNull(store.activity.value)
    }

    private fun automaticCandidate() = QueuedSmsCandidate(
        candidateKey = "candidate",
        sourceIdentity = SmsSourceIdentity.androidSms(
            providerMessageId = "provider-id",
            sender = "BANK",
            body = "INR 10 debited",
            sourceTimestamp = 1L,
            messageType = 1,
            receivedTimestamp = 1L
        ),
        sender = "BANK",
        rawMessage = "INR 10 debited",
        date = 1L,
        sourceTimestamp = 1L,
        messageType = 1,
        origin = SmsCandidateOrigin.AUTOMATIC,
        claimToken = "claim",
        attemptCount = 1
    )
}
