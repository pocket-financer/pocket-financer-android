package com.pocketfinancer.pipeline.sms

object CurrencyProfileRegistry {
    val scales = mapOf(
        "AED" to 2, "AUD" to 2, "CAD" to 2, "CHF" to 2, "EUR" to 2,
        "GBP" to 2, "INR" to 2, "JPY" to 0, "SGD" to 2, "USD" to 2
    )

    data class ParsedMoney(
        val minorUnits: Long,
        val currency: String,
        val scale: Int,
        val provenance: String
    )

    fun parse(number: String, currency: String, provenance: String): ParsedMoney? {
        val code = currency.uppercase()
        val scale = scales[code] ?: return null
        val pieces = number.replace(",", "").split('.')
        if (pieces.size > 2 || pieces.first().isEmpty()) return null
        val whole = pieces.first().toLongOrNull() ?: return null
        val fraction = pieces.getOrElse(1) { "" }
        if (whole < 0 || fraction.length > scale || fraction.any { !it.isDigit() }) return null
        val multiplier = repeat(scale, 1L) { value -> Math.multiplyExact(value, 10L) } ?: return null
        val padded = fraction.padEnd(scale, '0')
        val fractional = padded.ifEmpty { "0" }.toLongOrNull() ?: return null
        val amount = runCatching { Math.addExact(Math.multiplyExact(whole, multiplier), fractional) }
            .getOrNull() ?: return null
        if (amount <= 0) return null
        return ParsedMoney(amount, code, scale, provenance)
    }

    private fun repeat(count: Int, initial: Long, transform: (Long) -> Long): Long? =
        runCatching {
            var value = initial
            for (index in 0 until count) {
                value = transform(value)
            }
            value
        }.getOrNull()
}
