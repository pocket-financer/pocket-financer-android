package com.pocketfinancer.pipeline.sms

/** A half-open source range expressed in Unicode scalars, never UTF-16 units. */
data class UnicodeScalarSpan(val start: Int, val end: Int, val text: String) {
    init {
        require(start >= 0 && end > start && text.isNotEmpty())
    }
}

object UnicodeScalarSpans {
    fun slice(source: String, start: Int, end: Int): String? {
        if (start < 0 || end <= start || hasMalformedUtf16(source)) return null
        var scalar = 0
        var index = 0
        var startUtf16 = -1
        var endUtf16 = -1
        while (index < source.length) {
            if (scalar == start) startUtf16 = index
            if (scalar == end) {
                endUtf16 = index
                break
            }
            val codePoint = source.codePointAt(index)
            index += Character.charCount(codePoint)
            scalar++
        }
        if (scalar == end && endUtf16 < 0) endUtf16 = index
        return if (startUtf16 >= 0 && endUtf16 >= startUtf16) {
            source.substring(startUtf16, endUtf16)
        } else null
    }

    fun validate(source: String, start: Int, end: Int, exactText: String): UnicodeScalarSpan? {
        val slice = slice(source, start, end) ?: return null
        return if (slice == exactText) UnicodeScalarSpan(start, end, slice) else null
    }

    private fun hasMalformedUtf16(source: String): Boolean {
        var index = 0
        while (index < source.length) {
            val current = source[index]
            when {
                Character.isHighSurrogate(current) -> {
                    if (
                        index + 1 >= source.length ||
                        !Character.isLowSurrogate(source[index + 1])
                    ) {
                        return true
                    }
                    index += 2
                }
                Character.isLowSurrogate(current) -> return true
                else -> index += 1
            }
        }
        return false
    }
}
