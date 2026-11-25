package com.pocketfinancer.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class NonThinkingOutputGuardTest {
    @Test
    fun validJsonContinuesToStreamWithOriginalContent() {
        val streamed = StringBuilder()
        val guard = NonThinkingOutputGuard(SlmTokenCallback(streamed::append))
        val raw = "{\"decision\":\"none\"}"

        guard.onToken("{")
        guard.onToken("\"decision\":")
        guard.onToken("\"none\"}")
        assertEquals(raw, guard.finish(raw))
        assertEquals(raw, streamed.toString())
    }

    @Test
    fun thoughtPrefixSplitAcrossCallbacksNeverReachesTelemetry() {
        val streamed = StringBuilder()
        val guard = NonThinkingOutputGuard(SlmTokenCallback(streamed::append))

        guard.onToken("<th")
        guard.onToken("ink>private reasoning")
        assertNull(guard.finish("<think>private reasoning</think>{\"decision\":\"none\"}"))
        assertEquals("", streamed.toString())
    }

    @Test
    fun thoughtMarkerAfterJsonStartIsHeldBackAndRejected() {
        val streamed = StringBuilder()
        val guard = NonThinkingOutputGuard(SlmTokenCallback(streamed::append))

        guard.onToken("{\"decision\":\"none\",\"note\":\"")
        guard.onToken("<thi")
        guard.onToken("nk>private reasoning\"}")
        assertNull(guard.finish(
            "{\"decision\":\"none\",\"note\":\"<think>private reasoning\"}"
        ))
        assertFalse(streamed.toString().contains('<'))
    }

    @Test
    fun finalResponseIsCheckedEvenWhenBackendDidNotStream() {
        val guard = NonThinkingOutputGuard(null)
        assertNull(guard.finish("<think>private reasoning</think>"))
        assertNull(guard.finish("{\"reasoning\":\"private reasoning\"}"))
    }
}
