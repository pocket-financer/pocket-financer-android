package com.pocketfinancer.pipeline.sms

import java.text.Normalizer
import java.util.Locale

object SmsExtractorNormalizer {
    fun normalizeAmountField(
        amount: ExtractedAmount,
        primaryCurrency: String,
        enabledProfiles: List<String>
    ): NormalizedSmsMoney {
        if (
            primaryCurrency !in CurrencyProfileRegistry.scales ||
            enabledProfiles.isEmpty() ||
            enabledProfiles.any { it !in SUPPORTED_PROFILES }
        ) {
            fail("extractor_currency_invalid")
        }
        validateCurrencyGrounding(
            amount.evidence.text,
            amount.currency,
            primaryCurrency,
            enabledProfiles
        )
        if (!Regex("^(0|[1-9][0-9]*)(\\.[0-9]+)?$").matches(amount.value)) {
            fail("extractor_amount_invalid")
        }
        val declared = CurrencyProfileRegistry.parse(
            amount.value, amount.currency, "extractor_declared"
        ) ?: fail("extractor_amount_invalid")
        val evidenceNumber = moneyNumber(amount.evidence.text)
            ?: fail("extractor_amount_invalid")
        val grounded = CurrencyProfileRegistry.parse(
            evidenceNumber, amount.currency, "extractor_evidence"
        ) ?: fail("extractor_amount_invalid")
        if (declared.minorUnits != grounded.minorUnits) {
            fail("extractor_amount_value_disagreement")
        }
        return NormalizedSmsMoney(grounded.minorUnits, grounded.currency, grounded.scale)
    }

    fun normalizeDirectionField(direction: ExtractedDirection): String {
        if (!directionGrounded(direction.value, direction.evidence.text)) {
            fail("extractor_direction_invalid")
        }
        return direction.value
    }

    fun normalizeAccountField(account: ExtractedAccount): String {
        val normalized = normalizeAccountReference(account.evidence.text)
        if (normalized.isEmpty() || normalizeAccountReference(account.reference) != normalized) {
            fail("extractor_account_reference_invalid")
        }
        return normalized
    }

    fun normalizeCounterpartyField(counterparty: ExtractedCounterparty): String {
        val evidence = normalizeText(counterparty.evidence.text)
        if (
            evidence.isEmpty() ||
            evidence.codePointCount(0, evidence.length) > 256 ||
            normalizeText(counterparty.value) != evidence
        ) {
            fail("extractor_counterparty_invalid")
        }
        return evidence
    }

    fun normalize(
        posted: SmsExtractorResult.Posted,
        primaryCurrency: String,
        enabledProfiles: List<String>
    ): NormalizedSmsExtraction {
        if (
            primaryCurrency !in CurrencyProfileRegistry.scales ||
            enabledProfiles.isEmpty() ||
            enabledProfiles.any { it !in SUPPORTED_PROFILES }
        ) {
            fail("extractor_currency_invalid")
        }
        validateCurrencyGrounding(
            posted.amount.evidence.text,
            posted.amount.currency,
            primaryCurrency,
            enabledProfiles
        )
        if (!directionGrounded(posted.direction.value, posted.direction.evidence.text)) {
            fail("extractor_direction_invalid")
        }
        if (!Regex("^(0|[1-9][0-9]*)(\\.[0-9]+)?$").matches(posted.amount.value)) {
            fail("extractor_amount_invalid")
        }
        val declared = CurrencyProfileRegistry.parse(
            posted.amount.value, posted.amount.currency, "extractor_declared"
        ) ?: fail("extractor_amount_invalid")
        val evidenceNumber = moneyNumber(posted.amount.evidence.text)
            ?: fail("extractor_amount_invalid")
        val grounded = CurrencyProfileRegistry.parse(
            evidenceNumber, posted.amount.currency, "extractor_evidence"
        ) ?: fail("extractor_amount_invalid")
        if (declared.minorUnits != grounded.minorUnits) fail("extractor_amount_value_disagreement")
        val normalizedAccount = normalizeAccountReference(posted.account.evidence.text)
        if (normalizedAccount.isEmpty() ||
            normalizeAccountReference(posted.account.reference) != normalizedAccount
        ) fail("extractor_account_reference_invalid")
        val normalizedCounterparty = posted.counterparty?.let {
            val evidence = normalizeText(it.evidence.text)
            if (
                evidence.isEmpty() ||
                evidence.codePointCount(0, evidence.length) > 256 ||
                normalizeText(it.value) != evidence
            ) {
                fail("extractor_counterparty_invalid")
            }
            evidence
        }
        return NormalizedSmsExtraction(
            grounded.minorUnits, grounded.currency, grounded.scale, posted.direction.value,
            normalizedAccount, posted.account.evidence, normalizedCounterparty,
            posted.amount.evidence, posted.direction.evidence, posted.counterparty?.evidence
        )
    }

    fun normalizeAccountReference(value: String): String {
        val normalized = normalizeText(value)
        val vpa = Regex("[a-z0-9][a-z0-9._-]{1,}@[a-z0-9][a-z0-9.-]+")
            .find(normalized)?.value
        if (vpa != null) return vpa
        val suffixes = Regex("(?:[xX*•-]{2,}\\s*)?[0-9]{3,8}").findAll(normalized)
            .map { it.value }.toList()
        if (suffixes.size != 1) return ""
        val digits = suffixes.single().filter { it.isDigit() }
        return digits.takeIf { it.length in 3..8 } ?: ""
    }

    fun normalizeText(value: String): String = frozenCaseFold(
        Normalizer.normalize(value, Normalizer.Form.NFKC).trim()
    ).replace(Regex("\\s+"), " ")

    private fun frozenCaseFold(value: String): String {
        val result = StringBuilder()
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            var mapped = String(Character.toChars(codePoint)).lowercase(Locale.ROOT)
            if (mapped == "ß") mapped = "ss"
            if (mapped == "ς") mapped = "σ"
            result.append(mapped)
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }

    private fun moneyNumber(text: String): String? {
        val numbers = Regex("(?<![\\w,])(?:[0-9]{1,3}(?:,[0-9]{2})+,[0-9]{3}|[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?(?![\\w,])")
            .findAll(Normalizer.normalize(text, Normalizer.Form.NFKC)).map { it.value }.toList()
        return numbers.singleOrNull()
    }

    private fun directionGrounded(direction: String, text: String): Boolean {
        val terms = when (direction) {
            "debit" -> listOf("debit", "debited", "deducted", "withdrawn", "spent", "paid", "charged")
            "credit" -> listOf("credit", "credited", "deposited", "received", "refunded")
            else -> return false
        }
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        return terms.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(normalized) }
    }

    private fun validateCurrencyGrounding(
        evidence: String,
        currency: String,
        primaryCurrency: String,
        enabledProfiles: List<String>
    ) {
        val normalized = Normalizer.normalize(evidence, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
        val realCodes = Regex("\\b[A-Za-z]{3}\\b")
            .findAll(normalized)
            .map { it.value.uppercase(Locale.ROOT) }
            .filter { it in CurrencyProfileRegistry.scales }
            .toSet()
        if (realCodes.isNotEmpty() && realCodes != setOf(currency)) {
            fail("extractor_currency_invalid")
        }
        val markerCurrencies = enabledProfiles
            .flatMap { PROFILE_MARKERS.getValue(it).entries }
            .filter { (_, markers) ->
                markers.any { marker -> marker.lowercase(Locale.ROOT) in normalized }
            }
            .map { it.key }
            .toSet()
        if (markerCurrencies.isNotEmpty() && markerCurrencies != setOf(currency)) {
            fail("extractor_currency_invalid")
        }
        if (
            realCodes.isEmpty() &&
            markerCurrencies.isEmpty() &&
            currency != primaryCurrency
        ) {
            fail("extractor_currency_invalid")
        }
    }

    private fun fail(reason: String): Nothing = throw SmsExtractorValidationException(reason)

    private val SUPPORTED_PROFILES = setOf("core-en", "india")
    private val PROFILE_MARKERS = mapOf(
        "core-en" to mapOf(
            "EUR" to listOf("€"),
            "GBP" to listOf("£")
        ),
        "india" to mapOf(
            "INR" to listOf("₹", "Rs", "Rs.", "INR")
        )
    )
}
