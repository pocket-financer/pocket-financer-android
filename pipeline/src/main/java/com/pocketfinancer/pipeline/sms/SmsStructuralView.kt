package com.pocketfinancer.pipeline.sms

import java.text.Normalizer
import java.util.Locale

class SmsStructuralView(val source: String) {
    val normalized: String
    private val sourceStartUtf16: IntArray
    private val sourceEndUtf16: IntArray

    init {
        val output = StringBuilder()
        val starts = mutableListOf<Int>()
        val ends = mutableListOf<Int>()
        var sourceOffset = 0
        var previousSpace = false
        while (sourceOffset < source.length) {
            val codePoint = source.codePointAt(sourceOffset)
            val sourceLength = Character.charCount(codePoint)
            var mapped = Normalizer.normalize(
                String(Character.toChars(codePoint)),
                Normalizer.Form.NFKC
            ).lowercase(Locale.ROOT)
            if (mapped == "ß") mapped = "ss"
            var mappedOffset = 0
            while (mappedOffset < mapped.length) {
                val mappedCodePoint = mapped.codePointAt(mappedOffset)
                val whitespace = Character.isWhitespace(mappedCodePoint)
                if (!(whitespace && previousSpace)) {
                    val text = if (whitespace) " " else String(Character.toChars(mappedCodePoint))
                    output.append(text)
                    repeat(text.length) {
                        starts += sourceOffset
                        ends += sourceOffset + sourceLength
                    }
                }
                previousSpace = whitespace
                mappedOffset += Character.charCount(mappedCodePoint)
            }
            sourceOffset += sourceLength
        }
        normalized = output.toString()
        sourceStartUtf16 = starts.toIntArray()
        sourceEndUtf16 = ends.toIntArray()
    }

    fun findAll(regex: Regex): Sequence<StructuralMatch> =
        regex.findAll(normalized).map { StructuralMatch(it) }

    inner class StructuralMatch(private val result: MatchResult) {
        fun normalizedGroup(name: String): String? = result.groups[name]?.value

        fun evidence(group: String? = null): SmsEvidenceSpan? {
            val range = if (group == null) result.range else result.groups[group]?.range ?: return null
            if (range.isEmpty()) return null
            val start = sourceStartUtf16.getOrNull(range.first) ?: return null
            val end = sourceEndUtf16.getOrNull(range.last) ?: return null
            return StructuralClauseSegmenter.evidence(source, start, end)
        }
    }
}
