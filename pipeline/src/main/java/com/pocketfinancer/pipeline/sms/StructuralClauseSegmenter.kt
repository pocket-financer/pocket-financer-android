package com.pocketfinancer.pipeline.sms

object StructuralClauseSegmenter {
    private val boundary = Regex("(?:[\\r\\n]+|(?<=[.!?;])\\s+|\\s+(?:but|however|while|whereas)\\s+)", RegexOption.IGNORE_CASE)

    fun split(source: String): List<SmsClause> {
        if (source.isEmpty()) return emptyList()
        val ranges = mutableListOf<IntRange>()
        var cursor = 0
        boundary.findAll(source).forEach { match ->
            trimmedRange(source, cursor, match.range.first)?.let(ranges::add)
            cursor = match.range.last + 1
        }
        trimmedRange(source, cursor, source.length)?.let(ranges::add)
        return ranges.mapIndexed { index, range ->
            SmsClause("cl$index", evidence(source, range.first, range.last + 1))
        }
    }

    fun clauseId(evidence: SmsEvidenceSpan, clauses: List<SmsClause>): String? =
        clauses.firstOrNull {
            evidence.startCodePoint >= it.evidence.startCodePoint &&
                evidence.endCodePoint <= it.evidence.endCodePoint
        }?.id

    fun evidence(source: String, startUtf16: Int, endUtf16: Int): SmsEvidenceSpan {
        require(startUtf16 in 0..<endUtf16 && endUtf16 <= source.length)
        return SmsEvidenceSpan(
            startCodePoint = source.codePointCount(0, startUtf16),
            endCodePoint = source.codePointCount(0, endUtf16),
            startUtf8 = source.substring(0, startUtf16).toByteArray(Charsets.UTF_8).size,
            endUtf8 = source.substring(0, endUtf16).toByteArray(Charsets.UTF_8).size,
            text = source.substring(startUtf16, endUtf16)
        )
    }

    private fun trimmedRange(source: String, start: Int, endExclusive: Int): IntRange? {
        var first = start
        var end = endExclusive
        while (first < end && source[first].isWhitespace()) first++
        while (end > first && source[end - 1].isWhitespace()) end--
        return if (first < end) first until end else null
    }
}
