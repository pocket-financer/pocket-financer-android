package com.pocketfinancer.data.repository

object CurrencyScaleRegistry {
    private val scales = mapOf(
        "AED" to 2, "AUD" to 2, "CAD" to 2, "CHF" to 2, "EUR" to 2,
        "GBP" to 2, "INR" to 2, "JPY" to 0, "SGD" to 2, "USD" to 2
    )

    val supportedCodes: List<String> = scales.keys.sorted()

    fun scale(currency: String): Int? = scales[currency.uppercase()]
}
