package com.pocketfinancer.inference

import javax.inject.Inject
import javax.inject.Singleton

data class DirectCandidateSelectorRequest(
    val prompt: String,
    val candidatePayloadJson: String,
    val grammar: String?,
    val profile: CandidateSelectorRuntimeProfile = CandidateSelectorRuntimeProfile(),
    val jsonCallback: SlmTokenCallback? = null
)

data class DirectCandidateSelectorResult(
    val rawOutput: String?,
    val completion: String,
    val safeErrorCode: String?,
    val model: SlmModelSpec,
    val performance: SlmPerformanceData?,
    val cache: SlmCacheDiagnostics?
)

fun interface DirectCandidateSelector {
    suspend fun select(
        lease: SlmLease,
        request: DirectCandidateSelectorRequest
    ): DirectCandidateSelectorResult
}

@Singleton
class DefaultDirectCandidateSelector @Inject constructor() : DirectCandidateSelector {
    override suspend fun select(
        lease: SlmLease,
        request: DirectCandidateSelectorRequest
    ): DirectCandidateSelectorResult {
        val outputGuard = NonThinkingOutputGuard(request.jsonCallback)
        val runtimeRequest = SlmExtractionRequest(
            messages = listOf(
                SlmChatMessage("system", request.prompt),
                SlmChatMessage("user", request.candidatePayloadJson)
            ),
            fallbackPrompt = request.prompt + "\n" + request.candidatePayloadJson,
            grammar = request.grammar,
            answerTokens = request.profile.answerTokenLimit,
            jsonCallback = SlmTokenCallback(outputGuard::onToken)
        )
        return when (val result = lease.extract(runtimeRequest)) {
            is SlmExtractionResult.Success -> {
                val safeOutput = outputGuard.finish(result.json)
                val bytes = safeOutput?.toByteArray(Charsets.UTF_8)?.size
                when {
                    safeOutput == null -> DirectCandidateSelectorResult(
                        null, "invalid", "runtime_mode_violation",
                        result.model, result.perf, result.cache
                    )
                    bytes != null && bytes > request.profile.rawOutputByteLimit ->
                        DirectCandidateSelectorResult(
                            null, "invalid", "runtime_output_truncated",
                            result.model, result.perf, result.cache
                        )
                    else -> DirectCandidateSelectorResult(
                        safeOutput, "complete", null,
                        result.model, result.perf, result.cache
                    )
                }
            }
            is SlmExtractionResult.Null -> DirectCandidateSelectorResult(
                null, "failed", "runtime_output_truncated", result.model, result.perf, result.cache
            )
            is SlmExtractionResult.Error -> DirectCandidateSelectorResult(
                null, "failed",
                if (result.message in setOf(
                        "runtime_mode_violation", "non_thinking_template_unavailable"
                    )
                ) "runtime_mode_violation" else "runtime_unavailable",
                result.model, null, null
            )
            is SlmExtractionResult.Stopped -> DirectCandidateSelectorResult(
                null, "interrupted", "operation_interrupted", result.model, null, null
            )
        }
    }
}
