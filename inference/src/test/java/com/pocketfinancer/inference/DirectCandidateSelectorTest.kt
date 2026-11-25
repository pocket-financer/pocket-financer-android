package com.pocketfinancer.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class DirectCandidateSelectorTest {

    // ── DefaultDirectCandidateSelector ────────────────────────────────

    @Test
    fun `selector forwards decoded JSON tokens in order and returns complete result`() = runTest {
        val decoded = mutableListOf<String>()
        var capturedRequest: SlmExtractionRequest? = null
        val lease = fakeLease { request ->
            capturedRequest = request
            request.jsonCallback?.onToken("{")
            request.jsonCallback?.onToken("\"decision\":\"none\"}")
            SlmExtractionResult.Success(json = "{\"decision\":\"none\"}", model = MODEL)
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
        assertEquals("complete", result.completion)
        assertNull(result.safeErrorCode)
    }

    @Test
    fun `selector discards unexpected thought output from any model without streaming`() = runTest {
        val decoded = mutableListOf<String>()
        val lease = fakeLease { request ->
            request.jsonCallback?.onToken("<th")
            request.jsonCallback?.onToken("ink>private reasoning")
            SlmExtractionResult.Success(
                json = "<think>private reasoning</think>{\"decision\":\"none\"}",
                model = MODEL
            )
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

        assertNull(result.rawOutput)
        assertEquals("invalid", result.completion)
        assertEquals("runtime_mode_violation", result.safeErrorCode)
        assertEquals(emptyList(), decoded)
    }

    @Test
    fun `selector rejects oversized output, null, stopped, and native errors accurately`() = runTest {
        val selector = DefaultDirectCandidateSelector()
        val smallProfile = CandidateSelectorRuntimeProfile(rawOutputByteLimit = 16)

        val oversized = selector.select(
            fakeLease { SlmExtractionResult.Success("{\"decision\":\"posted\",\"extra\":\"123456\"}", model = MODEL) },
            DirectCandidateSelectorRequest("prompt", "{}", null, profile = smallProfile)
        )
        assertNull(oversized.rawOutput)
        assertEquals("invalid", oversized.completion)
        assertEquals("runtime_output_truncated", oversized.safeErrorCode)

        val nullResult = selector.select(
            fakeLease { SlmExtractionResult.Null(model = MODEL) },
            DirectCandidateSelectorRequest("prompt", "{}", null)
        )
        assertEquals("failed", nullResult.completion)
        assertEquals("runtime_output_truncated", nullResult.safeErrorCode)

        val modeViolation = selector.select(
            fakeLease { SlmExtractionResult.Error("non_thinking_template_unavailable", MODEL) },
            DirectCandidateSelectorRequest("prompt", "{}", null)
        )
        assertNull(modeViolation.rawOutput)
        assertEquals("failed", modeViolation.completion)
        assertEquals("runtime_mode_violation", modeViolation.safeErrorCode)

        val unavailable = selector.select(
            fakeLease { SlmExtractionResult.Error("context_init_failed", MODEL) },
            DirectCandidateSelectorRequest("prompt", "{}", null)
        )
        assertEquals("failed", unavailable.completion)
        assertEquals("runtime_unavailable", unavailable.safeErrorCode)

        val stopped = selector.select(
            fakeLease { SlmExtractionResult.Stopped(MODEL) },
            DirectCandidateSelectorRequest("prompt", "{}", null)
        )
        assertEquals("interrupted", stopped.completion)
        assertEquals("operation_interrupted", stopped.safeErrorCode)
    }

    // ── NonThinkingOutputGuard ────────────────────────────────────────

    @Test
    fun `guard streams valid JSON and flushes held-back tail on finish`() {
        val streamed = StringBuilder()
        val guard = NonThinkingOutputGuard(SlmTokenCallback(streamed::append))
        val raw = "{\"decision\":\"none\"}"

        guard.onToken("  ")
        guard.onToken("{")
        guard.onToken("\"decision\":")
        guard.onToken("\"none\"}")

        assertEquals(raw, guard.finish(raw))
        assertEquals("  $raw", streamed.toString())
    }

    @Test
    fun `guard rejects non-JSON opening token and split thought markers before telemetry`() {
        val nonJsonStream = StringBuilder()
        val nonJsonGuard = NonThinkingOutputGuard(SlmTokenCallback(nonJsonStream::append))
        nonJsonGuard.onToken("Sure, here is the JSON: {\"decision\":\"none\"}")
        assertNull(nonJsonGuard.finish("{\"decision\":\"none\"}"))
        assertEquals("", nonJsonStream.toString())

        val splitPrefixStream = StringBuilder()
        val splitPrefixGuard = NonThinkingOutputGuard(SlmTokenCallback(splitPrefixStream::append))
        splitPrefixGuard.onToken("<th")
        splitPrefixGuard.onToken("ink>private reasoning")
        assertNull(splitPrefixGuard.finish("<think>private reasoning</think>{\"decision\":\"none\"}"))
        assertEquals("", splitPrefixStream.toString())

        val embeddedMarkerStream = StringBuilder()
        val embeddedGuard = NonThinkingOutputGuard(SlmTokenCallback(embeddedMarkerStream::append))
        embeddedGuard.onToken("{\"decision\":\"none\",\"note\":\"")
        embeddedGuard.onToken("<thi")
        embeddedGuard.onToken("nk>private reasoning\"}")
        assertNull(embeddedGuard.finish("{\"decision\":\"none\",\"note\":\"<think>private reasoning\"}"))
        assertFalse(embeddedMarkerStream.toString().contains('<'))
    }

    @Test
    fun `guard checks final output even when backend did not stream callbacks`() {
        assertNull(NonThinkingOutputGuard(null).finish("<think>private reasoning</think>"))
        assertNull(NonThinkingOutputGuard(null).finish("{\"reasoning\":\"private reasoning\"}"))
        assertNull(NonThinkingOutputGuard(null).finish("{\"thought\":\"private\"}"))
        assertNull(NonThinkingOutputGuard(null).finish("<|channel|>analysis"))
        assertNull(NonThinkingOutputGuard(null).finish("plain text"))
    }

    private fun fakeLease(
        handler: suspend (SlmExtractionRequest) -> SlmExtractionResult
    ): SlmLease = object : SlmLease {
        override val owner = SlmRuntimeOwner.HOME_SYNC
        override val model = MODEL
        override val isReleased = false
        override suspend fun extract(request: SlmExtractionRequest) = handler(request)
        override suspend fun countTokens(text: String, addSpecial: Boolean) = 1
        override suspend fun release() = Unit
    }

    private companion object {
        val MODEL = SlmModelSpec("test-model", "/tmp/test.gguf")
    }
}
