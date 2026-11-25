package com.pocketfinancer.ui.home

import com.pocketfinancer.pipeline.PipelineService
import java.io.File
import java.util.Locale

/** Process-only live inference facts for the currently owned manual candidate. */
internal data class ManualSmsProcessingTelemetry(
    val stageIndex: Int = 0,
    val decodedTokenDelta: String = "",
    val cumulativeStructuredOutput: String = "",
    val outputTruncated: Boolean = false,
    val performanceText: String? = null,
    val modelName: String? = null
) {
    init {
        require(decodedTokenDelta.length <= MAX_MANUAL_TOKEN_DELTA_CHARS)
        require(cumulativeStructuredOutput.length <= MAX_MANUAL_OUTPUT_CHARS)
    }
}

/**
 * Coalesces callback traffic without rebuilding output from UI emissions.
 * [publish] is responsible for exact run/candidate/attempt ownership fencing.
 */
internal class ManualSmsProcessingObserver(
    initial: ManualSmsProcessingTelemetry,
    private val publish: (ManualSmsProcessingTelemetry) -> Boolean,
    private val nanoTime: () -> Long = System::nanoTime
) : PipelineService.ProcessingObserver {
    private var telemetry = initial
    private val cumulative = StringBuilder(initial.cumulativeStructuredOutput)
    private var decodedTokenDelta = initial.decodedTokenDelta
    private var outputTruncated = initial.outputTruncated
    private var receivedDecodedToken = false
    private var lastPublishedNanos: Long? = null
    private var closed = false

    @Synchronized
    override fun onEvent(event: PipelineService.ProcessingEvent) {
        if (closed) return
        when (event) {
            is PipelineService.ProcessingEvent.GroundedStage -> {
                telemetry = telemetry.copy(
                    stageIndex = when (event.stage) {
                        "claim", "analysis", "triage" -> 0
                        "selector_execution", "selector_validation" -> 1
                        "reconstruction", "account_resolution",
                        "persistence_gate" -> 2
                        "settlement" -> 3
                        else -> telemetry.stageIndex
                    }
                )
                publishSnapshot(force = true)
            }

            PipelineService.ProcessingEvent.DeterministicFilterStarted,
            PipelineService.ProcessingEvent.DeterministicFilterPassed,
            PipelineService.ProcessingEvent.DeterministicFilterRejected -> {
                telemetry = telemetry.copy(stageIndex = 0)
                publishSnapshot(force = true)
            }

            is PipelineService.ProcessingEvent.InferenceStarted -> {
                decodedTokenDelta = ""
                cumulative.clear()
                outputTruncated = false
                receivedDecodedToken = false
                telemetry = telemetry.copy(
                    stageIndex = 1,
                    modelName = File(event.model.modelPath).name
                )
                publishSnapshot(force = true)
            }

            is PipelineService.ProcessingEvent.JsonTokenDelta -> {
                val firstDecodedToken = !receivedDecodedToken
                receivedDecodedToken = true
                decodedTokenDelta = event.delta.take(MAX_MANUAL_TOKEN_DELTA_CHARS)
                outputTruncated = cumulative.replaceBounded(
                    event.cumulativeStructuredOutput
                )
                telemetry = telemetry.copy(stageIndex = 1)
                publishSnapshot(force = firstDecodedToken || outputTruncated)
            }

            is PipelineService.ProcessingEvent.InferenceCompleted -> {
                decodedTokenDelta = ""
                event.json?.let { output ->
                    outputTruncated = cumulative.replaceBounded(output)
                }
                telemetry = telemetry.copy(
                    stageIndex = 1,
                    modelName = File(event.model.modelPath).name,
                    performanceText = event.perf?.let { perf ->
                        val tokensPerSecond = if (perf.tEvalMs > 0L) {
                            perf.nTokens.toDouble() / (perf.tEvalMs / 1_000.0)
                        } else {
                            0.0
                        }
                        String.format(
                            Locale.US,
                            "%d tokens • %.2f tok/s",
                            perf.nTokens,
                            tokensPerSecond
                        )
                    }
                )
                publishSnapshot(force = true)
            }

            PipelineService.ProcessingEvent.PersistenceStarted -> {
                decodedTokenDelta = ""
                telemetry = telemetry.copy(stageIndex = 3)
                publishSnapshot(force = true)
            }
        }
    }

    /** Scrubs local callback buffers and ignores all delayed callbacks. */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        decodedTokenDelta = ""
        cumulative.clear()
        outputTruncated = false
        telemetry = ManualSmsProcessingTelemetry()
    }

    private fun publishSnapshot(force: Boolean) {
        if (closed) return
        val now = nanoTime()
        val previous = lastPublishedNanos
        if (
            !force && previous != null &&
            now - previous < TOKEN_PUBLISH_INTERVAL_NANOS
        ) {
            return
        }
        telemetry = telemetry.copy(
            decodedTokenDelta = decodedTokenDelta,
            cumulativeStructuredOutput = cumulative.toString(),
            outputTruncated = outputTruncated
        )
        if (!publish(telemetry)) {
            close()
            return
        }
        lastPublishedNanos = now
    }

    private fun StringBuilder.appendBounded(delta: String): Boolean {
        val remaining = MAX_MANUAL_OUTPUT_CHARS - length
        if (remaining <= 0) return delta.isNotEmpty()
        if (delta.length <= remaining) {
            append(delta)
            return false
        }
        append(delta, 0, remaining)
        return true
    }

    private fun StringBuilder.replaceBounded(value: String): Boolean {
        clear()
        return appendBounded(value)
    }

    private companion object {
        const val TOKEN_PUBLISH_INTERVAL_NANOS = 50_000_000L
    }
}

internal const val MAX_MANUAL_OUTPUT_CHARS = 64_000
internal const val MAX_MANUAL_TOKEN_DELTA_CHARS = 4_096
