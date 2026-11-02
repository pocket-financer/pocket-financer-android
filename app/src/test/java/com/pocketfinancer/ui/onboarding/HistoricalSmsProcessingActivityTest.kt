package com.pocketfinancer.ui.onboarding

import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.inference.SlmExtractionResult
import com.pocketfinancer.inference.SlmPerformanceData
import com.pocketfinancer.pipeline.PipelineService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoricalSmsProcessingActivityTest {
    @Test
    fun `direct selector events expose json without a reasoning channel`() {
        val snapshots = mutableListOf<HistoricalSmsProcessingActivity>()
        val observer = HistoricalSmsProcessingObserver(activity(), snapshots::add)
        observer.onEvent(
            PipelineService.ProcessingEvent.InferenceStarted(
                model = SlmModelSpec("test", "/tmp/test.gguf"),
                grammarEnabled = true,
                answerTokenBudget = 512
            )
        )
        observer.onEvent(
            PipelineService.ProcessingEvent.JsonTokenDelta(
                "{\"decision\":",
                "{\"decision\":"
            )
        )
        observer.onEvent(
            PipelineService.ProcessingEvent.JsonTokenDelta(
                "\"abstain\"}",
                "{\"decision\":\"abstain\"}"
            )
        )
        observer.flush()

        val latest = snapshots.last()
        assertEquals(HistoricalSmsProcessingStage.GENERATING, latest.stage)
        assertEquals("\"abstain\"}", latest.decodedTokenDelta)
        assertEquals("{\"decision\":\"abstain\"}", latest.jsonOutput)
        assertEquals(512, latest.answerTokenBudget)
        assertTrue(latest.grammarEnabled == true)
    }

    @Test
    fun `json preview is bounded`() {
        val snapshots = mutableListOf<HistoricalSmsProcessingActivity>()
        val observer = HistoricalSmsProcessingObserver(activity(), snapshots::add)
        observer.onEvent(
            PipelineService.ProcessingEvent.JsonTokenDelta(
                "d".repeat(5_000),
                "x".repeat(70_000)
            )
        )
        observer.flush()

        assertEquals(4_096, snapshots.last().decodedTokenDelta.length)
        assertEquals(64_000, snapshots.last().jsonOutput.length)
        assertTrue(snapshots.last().jsonOutputTruncated)
    }

    @Test
    fun `completion publishes final output then close fences delayed callbacks`() {
        val snapshots = mutableListOf<HistoricalSmsProcessingActivity>()
        val observer = HistoricalSmsProcessingObserver(activity(), snapshots::add)
        val model = SlmModelSpec("test", "/tmp/test.gguf")
        observer.onEvent(
            PipelineService.ProcessingEvent.JsonTokenDelta("{", "{")
        )
        observer.onEvent(
            PipelineService.ProcessingEvent.InferenceCompleted(
                SlmExtractionResult.Success(
                    json = "{\"decision\":\"none\"}",
                    perf = SlmPerformanceData(1, 2, 100, 3),
                    model = model
                )
            )
        )

        val completed = snapshots.last()
        assertEquals("", completed.decodedTokenDelta)
        assertEquals("{\"decision\":\"none\"}", completed.jsonOutput)
        assertEquals(3, completed.performance?.generatedTokens)

        val snapshotCount = snapshots.size
        observer.close()
        observer.onEvent(
            PipelineService.ProcessingEvent.JsonTokenDelta("stale", "stale")
        )
        observer.flush()
        assertEquals(snapshotCount, snapshots.size)
    }

    private fun activity() = HistoricalSmsProcessingActivity(
        candidateKey = "candidate",
        sender = "BANK",
        body = "INR 10 debited",
        date = 1L,
        position = 1,
        total = 1
    )
}
