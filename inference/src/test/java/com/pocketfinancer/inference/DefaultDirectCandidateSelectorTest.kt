package com.pocketfinancer.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest

class DefaultDirectCandidateSelectorTest {
    @Test
    fun `selector forwards decoded callback to the runtime request in order`() = runTest {
        val decoded = mutableListOf<String>()
        val model = SlmModelSpec("test", "/tmp/test.gguf")
        var capturedRequest: SlmExtractionRequest? = null
        val lease = object : SlmLease {
            override val owner = SlmRuntimeOwner.HOME_SYNC
            override val model = model
            override val isReleased = false

            override suspend fun extract(
                request: SlmExtractionRequest
            ): SlmExtractionResult {
                capturedRequest = request
                request.jsonCallback?.onToken("{")
                request.jsonCallback?.onToken("\"decision\":\"none\"}")
                return SlmExtractionResult.Success(
                    json = "{\"decision\":\"none\"}",
                    model = model
                )
            }

            override suspend fun countTokens(
                text: String,
                addSpecial: Boolean
            ): Int = 1

            override suspend fun release() = Unit
        }

        val result = DefaultDirectCandidateSelector().select(
            lease = lease,
            request = DirectCandidateSelectorRequest(
                prompt = "prompt",
                candidatePayloadJson = "{}",
                grammar = "root ::= object",
                jsonCallback = SlmTokenCallback(decoded::add)
            )
        )

        assertNotNull(capturedRequest?.jsonCallback)
        assertEquals("{\"decision\":\"none\"}", decoded.joinToString(""))
        assertEquals("{\"decision\":\"none\"}", result.rawOutput)
    }

    @Test
    fun selectorDiscardsUnexpectedThoughtOutputFromAnyModel() = runTest {
        val decoded = mutableListOf<String>()
        val model = SlmModelSpec("thinking-capable-model", "/tmp/test.gguf")
        val lease = object : SlmLease {
            override val owner = SlmRuntimeOwner.HOME_SYNC
            override val model = model
            override val isReleased = false

            override suspend fun extract(request: SlmExtractionRequest): SlmExtractionResult {
                request.jsonCallback?.onToken("<th")
                request.jsonCallback?.onToken("ink>private reasoning")
                return SlmExtractionResult.Success(
                    json = "<think>private reasoning</think>{\"decision\":\"none\"}",
                    model = model
                )
            }

            override suspend fun countTokens(text: String, addSpecial: Boolean): Int = 1
            override suspend fun release() = Unit
        }

        val result = DefaultDirectCandidateSelector().select(
            lease,
            DirectCandidateSelectorRequest(
                prompt = "prompt",
                candidatePayloadJson = "{}",
                grammar = null,
                jsonCallback = SlmTokenCallback(decoded::add)
            )
        )

        assertEquals(null, result.rawOutput)
        assertEquals("invalid", result.completion)
        assertEquals("runtime_mode_violation", result.safeErrorCode)
        assertEquals(emptyList(), decoded)
    }

    @Test
    fun selectorMapsNativeModeViolationWithoutRawOutput() = runTest {
        val model = SlmModelSpec("plain-model", "/tmp/test.gguf")
        val lease = object : SlmLease {
            override val owner = SlmRuntimeOwner.HOME_SYNC
            override val model = model
            override val isReleased = false

            override suspend fun extract(request: SlmExtractionRequest) =
                SlmExtractionResult.Error("runtime_mode_violation", model)

            override suspend fun countTokens(text: String, addSpecial: Boolean): Int = 1
            override suspend fun release() = Unit
        }

        val result = DefaultDirectCandidateSelector().select(
            lease,
            DirectCandidateSelectorRequest("prompt", "{}", null)
        )

        assertEquals(null, result.rawOutput)
        assertEquals("runtime_mode_violation", result.safeErrorCode)
    }
}
