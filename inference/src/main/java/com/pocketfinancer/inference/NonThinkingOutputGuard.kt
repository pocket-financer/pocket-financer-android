package com.pocketfinancer.inference

import java.util.Locale

/**
 * Only JSON response text may reach live telemetry. A model that ignores its
 * non-thinking template is rejected, including when a marker spans callbacks.
 */
internal class NonThinkingOutputGuard(
    private val callback: SlmTokenCallback?
) {
    private val pending = StringBuilder()
    private var objectStarted = false
    private var rejected = false

    fun onToken(chunk: String) {
        if (rejected || chunk.isEmpty()) return
        pending.append(chunk)
        if (!objectStarted) {
            val first = pending.indexOfFirst { !it.isWhitespace() }
            if (first < 0) return
            if (pending[first] != '{') {
                reject()
                return
            }
            objectStarted = true
        }
        if (containsThoughtMarker(pending)) {
            reject()
            return
        }
        val ready = pending.length - MARKER_HOLDBACK
        if (ready > 0) {
            callback?.onToken(pending.substring(0, ready))
            pending.delete(0, ready)
        }
    }

    fun finish(raw: String): String? {
        if (rejected || raw.firstOrNull { !it.isWhitespace() } != '{' ||
            containsThoughtMarker(raw)
        ) {
            reject()
            return null
        }
        if (pending.isNotEmpty()) callback?.onToken(pending.toString())
        pending.clear()
        return raw
    }

    private fun reject() {
        rejected = true
        pending.clear()
    }

    private fun containsThoughtMarker(text: CharSequence): Boolean {
        val normalized = text.toString().lowercase(Locale.ROOT)
        return THOUGHT_MARKERS.any(normalized::contains)
    }

    private companion object {
        val THOUGHT_MARKERS = listOf(
            "<think", "</think", "<analysis", "</analysis",
            "<|channel|>analysis", "<|start_header_id|>analysis",
            "\"reasoning\"", "\"thought\"", "\"analysis\""
        )
        val MARKER_HOLDBACK = THOUGHT_MARKERS.maxOf(String::length) - 1
    }
}
