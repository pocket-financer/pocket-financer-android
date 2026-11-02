package com.pocketfinancer.pipeline.sms

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.json.JSONObject

class SmsExtractorValidatorTest {
    @Test fun `passes every frozen sanitized extractor vector`() {
        val root = listOf(
            File("src/main/assets/sms_processing/native-integration-v4"),
            File("pipeline/src/main/assets/sms_processing/native-integration-v4")
        ).firstOrNull(File::isDirectory) ?: error("Native v4 bundle unavailable")
        val vectors = JSONObject(
            File(root, "tests/sms_processing/golden/extractor-v1/sanitized-vectors.json")
                .readText()
        ).getJSONArray("cases")
        repeat(vectors.length()) { index ->
            val vector = vectors.getJSONObject(index)
            val expectedReason = vector.optString("expected_reason").takeIf { it.isNotBlank() }
            if (expectedReason != null) {
                val error = assertFailsWith<SmsExtractorValidationException> {
                    SmsExtractorValidator.validate(
                        vector.getJSONObject("model_output").toString(),
                        vector.getString("sms_body")
                    )
                }
                assertEquals(expectedReason, error.reasonCode, vector.getString("id"))
            } else {
                val parsed = SmsExtractorValidator.validate(
                    vector.getJSONObject("model_output").toString(),
                    vector.getString("sms_body")
                )
                val expected = vector.getJSONObject("expected")
                when (expected.getString("decision")) {
                    "none" -> assertIs<SmsExtractorResult.None>(parsed)
                    "abstain" -> assertIs<SmsExtractorResult.Abstain>(parsed)
                    "posted" -> {
                        val normalized = SmsExtractorNormalizer.normalize(
                            assertIs<SmsExtractorResult.Posted>(parsed),
                            "INR", listOf("core-en", "india")
                        )
                        assertEquals(expected.getLong("amount_minor_units"), normalized.minorUnits)
                        assertEquals(expected.getString("amount_currency"), normalized.currency)
                        assertEquals(expected.getString("direction"), normalized.direction)
                        assertEquals(expected.getString("account_reference"), normalized.accountReference)
                        val counterparty = expected.optString("counterparty")
                            .takeIf { !expected.isNull("counterparty") }
                        assertEquals(counterparty, normalized.counterparty)
                    }
                }
            }
        }
    }

    @Test fun `scalar spans preserve emoji boundaries and normalize exact money`() {
        val source = "Alert 💳: INR 1,250.00 debited from a/c XX1234 at Café."
        val result = SmsExtractorValidator.validate(
            """{"decision":"posted","amount":{"value":"1250.00","currency":"INR","evidence":{"start_scalar":9,"end_scalar":21,"text":"INR 1,250.00"}},"direction":{"value":"debit","evidence":{"start_scalar":22,"end_scalar":29,"text":"debited"}},"account":{"reference":"XX1234","evidence":{"start_scalar":39,"end_scalar":45,"text":"XX1234"}},"counterparty":{"value":"Café","evidence":{"start_scalar":49,"end_scalar":53,"text":"Café"}}}""", source
        )
        val normalized = SmsExtractorNormalizer.normalize(
            assertIs<SmsExtractorResult.Posted>(result),
            primaryCurrency = "INR",
            enabledProfiles = listOf("core-en", "india")
        )
        assertEquals(125000, normalized.minorUnits)
        assertEquals("1234", normalized.accountReference)
        assertEquals("café", normalized.counterparty)
    }

    @Test fun `partial collector retains valid grounded fields when direction is missing`() {
        val source = "INR 75.00 XX1234 at SHOP"
        val raw = """{"decision":"posted","amount":{"value":"75.00","currency":"INR","evidence":{"start_scalar":0,"end_scalar":9,"text":"INR 75.00"}},"account":{"reference":"XX1234","evidence":{"start_scalar":10,"end_scalar":16,"text":"XX1234"}},"counterparty":{"value":"SHOP","evidence":{"start_scalar":20,"end_scalar":24,"text":"SHOP"}}}"""

        val fields = SmsExtractorValidator.collectGroundedFields(
            raw,
            source,
            primaryCurrency = "INR",
            enabledProfiles = listOf("core-en", "india")
        )

        assertEquals(listOf("amount", "account", "counterparty"), fields.map { it.field })
        fields.forEach {
            assertEquals("valid", it.validationState)
            assertEquals("normalization", it.originatingStage)
            assertEquals("slm", it.origin)
        }
        assertEquals(7500L, JSONObject(fields[0].normalizedValueJson!!).getLong("minor_units"))
        assertEquals("XX1234", fields[1].sourceSpan.text)
        assertEquals("\"1234\"", fields[1].normalizedValueJson)
        assertEquals("\"shop\"", fields[2].normalizedValueJson)
    }

    @Test fun `scalar spans reject malformed utf16 source`() {
        assertNull(UnicodeScalarSpans.slice("A\uD800B", 0, 1))
        assertNull(UnicodeScalarSpans.slice("A\uDC00B", 1, 2))
    }

    @Test fun `rejects duplicate keys trailing content and mismatched evidence`() {
        val source = "INR 75.00 debited XX1234"
        listOf(
            "{\"decision\":\"none\",\"decision\":\"abstain\"}",
            "{\"decision\":\"none\"} trailing",
            "{\u2003\"decision\":\"none\"}",
            "{\"decision\":\"posted\",\"amount\":{\"value\":\"75.00\",\"currency\":\"INR\",\"evidence\":{\"start_scalar\":0,\"end_scalar\":3,\"text\":\"BAD\"}},\"direction\":{\"value\":\"debit\",\"evidence\":{\"start_scalar\":10,\"end_scalar\":17,\"text\":\"debited\"}},\"account\":{\"reference\":\"XX1234\",\"evidence\":{\"start_scalar\":18,\"end_scalar\":24,\"text\":\"XX1234\"}},\"counterparty\":null}"
        ).forEach { raw ->
            assertFailsWith<SmsExtractorValidationException> { SmsExtractorValidator.validate(raw, source) }
        }
    }

    @Test fun `rejects unsupported precision and overflow without floating point`() {
        listOf("1.001", "01.00", "+1.00", "92233720368547758.08").forEach { amount ->
            val source = "INR $amount debited 1234"
            val amountEnd = 4 + amount.length
            val directionStart = amountEnd + 1
            val directionEnd = directionStart + 7
            val accountStart = directionEnd + 1
            val raw = """{"decision":"posted","amount":{"value":"$amount","currency":"INR","evidence":{"start_scalar":0,"end_scalar":$amountEnd,"text":"INR $amount"}},"direction":{"value":"debit","evidence":{"start_scalar":$directionStart,"end_scalar":$directionEnd,"text":"debited"}},"account":{"reference":"1234","evidence":{"start_scalar":$accountStart,"end_scalar":${accountStart + 4},"text":"1234"}},"counterparty":null}"""
            val parsed = SmsExtractorValidator.validate(raw, source)
            val error = assertFailsWith<SmsExtractorValidationException> {
                SmsExtractorNormalizer.normalize(
                    assertIs<SmsExtractorResult.Posted>(parsed),
                    primaryCurrency = "INR",
                    enabledProfiles = listOf("core-en", "india")
                )
            }
            assertEquals("extractor_amount_invalid", error.reasonCode)
        }
    }

    @Test fun `accepts exact signed 64 bit money boundary`() {
        val amount = "92233720368547758.07"
        val source = "INR $amount debited 1234"
        val amountEnd = 4 + amount.length
        val directionStart = amountEnd + 1
        val directionEnd = directionStart + 7
        val accountStart = directionEnd + 1
        val raw = """{"decision":"posted","amount":{"value":"$amount","currency":"INR","evidence":{"start_scalar":0,"end_scalar":$amountEnd,"text":"INR $amount"}},"direction":{"value":"debit","evidence":{"start_scalar":$directionStart,"end_scalar":$directionEnd,"text":"debited"}},"account":{"reference":"1234","evidence":{"start_scalar":$accountStart,"end_scalar":${accountStart + 4},"text":"1234"}},"counterparty":null}"""
        val normalized = SmsExtractorNormalizer.normalize(
            assertIs<SmsExtractorResult.Posted>(SmsExtractorValidator.validate(raw, source)),
            primaryCurrency = "INR",
            enabledProfiles = listOf("core-en", "india")
        )
        assertEquals(Long.MAX_VALUE, normalized.minorUnits)
    }

    @Test fun `counterparty uses casefold and scalar length limit`() {
        assertEquals("strasse", SmsExtractorNormalizer.normalizeText("Straße"))
        assertEquals("οσ", SmsExtractorNormalizer.normalizeText("ΟΣ"))
        val tooLong = "a\u0338".repeat(129)
        val source = "INR 1.00 debited 1234 $tooLong"
        val counterpartyStart = "INR 1.00 debited 1234 ".length
        val counterpartyEnd = counterpartyStart + tooLong.codePointCount(0, tooLong.length)
        val raw = """{"decision":"posted","amount":{"value":"1.00","currency":"INR","evidence":{"start_scalar":0,"end_scalar":8,"text":"INR 1.00"}},"direction":{"value":"debit","evidence":{"start_scalar":9,"end_scalar":16,"text":"debited"}},"account":{"reference":"1234","evidence":{"start_scalar":17,"end_scalar":21,"text":"1234"}},"counterparty":{"value":"$tooLong","evidence":{"start_scalar":$counterpartyStart,"end_scalar":$counterpartyEnd,"text":"$tooLong"}}}"""
        val parsed = assertIs<SmsExtractorResult.Posted>(
            SmsExtractorValidator.validate(raw, source)
        )
        val error = assertFailsWith<SmsExtractorValidationException> {
            SmsExtractorNormalizer.normalize(parsed, "INR", listOf("core-en", "india"))
        }
        assertEquals("extractor_counterparty_invalid", error.reasonCode)
    }
}
