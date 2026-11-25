package com.pocketfinancer.ui.home

import com.pocketfinancer.inference.SlmExtractionResult
import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.inference.SlmPerformanceData
import com.pocketfinancer.pipeline.PipelineService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManualSmsProcessingObserverTest {
    @Test
    fun `decoded callbacks stay ordered and cumulative output is independent`() {
        var now = 0L
        val snapshots = mutableListOf<ManualSmsProcessingTelemetry>()
        val observer = ManualSmsProcessingObserver(
            initial = ManualSmsProcessingTelemetry(),
            publish = { snapshots += it; true },
            nanoTime = { now }
        )

        observer.onEvent(
            PipelineService.ProcessingEvent.JsonTokenDelta("{", "{")
        )
        assertEquals("{", snapshots.last().decodedTokenDelta)
        assertEquals("{", snapshots.last().cumulativeStructuredOutput)

        now += 50_000_000L
        observer.onEvent(
            PipelineService.ProcessingEvent.JsonTokenDelta(
                "\"decision\":\"none\"}",
                "{\"decision\":\"none\"}"
            )
        )
        assertEquals(
            "\"decision\":\"none\"}",
            snapshots.last().decodedTokenDelta
        )
        assertEquals(
            "{\"decision\":\"none\"}",
            snapshots.last().cumulativeStructuredOutput
        )
    }

    @Test
    fun `completion keeps final structured output and clears active delta`() {
        val snapshots = mutableListOf<ManualSmsProcessingTelemetry>()
        val observer = ManualSmsProcessingObserver(
            initial = ManualSmsProcessingTelemetry(),
            publish = { snapshots += it; true }
        )
        val model = SlmModelSpec("test", "/tmp/test.gguf")
        observer.onEvent(
            PipelineService.ProcessingEvent.JsonTokenDelta("partial", "partial")
        )
        observer.onEvent(
            PipelineService.ProcessingEvent.InferenceCompleted(
                SlmExtractionResult.Success(
                    json = "{\"decision\":\"posted\"}",
                    perf = SlmPerformanceData(1, 2, 1_000, 10),
                    model = model
                )
            )
        )

        val completed = snapshots.last()
        assertEquals("", completed.decodedTokenDelta)
        assertEquals(
            "{\"decision\":\"posted\"}",
            completed.cumulativeStructuredOutput
        )
        assertEquals("10 tokens • 10.00 tok/s", completed.performanceText)
    }

    @Test
    fun `close and rejected ownership fence all later callbacks`() {
        var ownsAttempt = true
        val snapshots = mutableListOf<ManualSmsProcessingTelemetry>()
        val observer = ManualSmsProcessingObserver(
            initial = ManualSmsProcessingTelemetry(),
            publish = {
                if (ownsAttempt) snapshots += it
                ownsAttempt
            }
        )

        observer.onEvent(
            PipelineService.ProcessingEvent.JsonTokenDelta("first", "first")
        )
        ownsAttempt = false
        observer.onEvent(PipelineService.ProcessingEvent.PersistenceStarted)
        val countAfterOwnershipLoss = snapshots.size
        observer.onEvent(
            PipelineService.ProcessingEvent.JsonTokenDelta("stale", "stale")
        )
        observer.close()

        assertTrue(countAfterOwnershipLoss > 0)
        assertEquals(countAfterOwnershipLoss, snapshots.size)
    }
}
