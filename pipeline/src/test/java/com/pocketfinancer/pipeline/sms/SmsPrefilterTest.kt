package com.pocketfinancer.pipeline.sms

import kotlin.test.Test
import kotlin.test.assertEquals
import org.json.JSONObject

class SmsPrefilterTest {
    @Test
    fun `matches every synced prefilter golden vector`() {
        val stream = javaClass.getResourceAsStream("/prefilter-vectors.json")
            ?: error("Missing test resource /prefilter-vectors.json")
        val root = JSONObject(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        assertEquals(SmsPrefilter.POLICY_ID, root.getString("policy_id"))
        val vectors = root.getJSONArray("vectors")
        assertEquals(54, vectors.length())

        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            val id = vector.getString("id")
            val body = vector.getString("body")
            val expectedDecision = vector.getString("decision")
            val expectedReason = vector.getString("reason")

            val actual = SmsPrefilter.evaluate(body)
            assertEquals(expectedDecision, actual.decision.wireValue, "decision mismatch for $id")
            assertEquals(expectedReason, actual.reason, "reason mismatch for $id")
            assertEquals(actual, SmsPrefilter.evaluate(body), "non-deterministic result for $id")
        }
    }

    @Test
    fun `future or conditional collect wording does not trigger completed guard`() {
        val onceApproved = SmsPrefilter.evaluate(
            "Merchant has requested Rs 500. Once approved, money will be debited from your A/c."
        )
        assertEquals(SmsPrefilter.Decision.EXCLUDE, onceApproved.decision)
        assertEquals("collect_or_mandate_request", onceApproved.reason)

        val onApproving = SmsPrefilter.evaluate(
            "Rs. 500 collect request. On approving the request, the sum is deducted."
        )
        assertEquals(SmsPrefilter.Decision.EXCLUDE, onApproving.decision)
        assertEquals("collect_or_mandate_request", onApproving.reason)
    }

    @Test
    fun `completed movement phrases pass even alongside otp wording`() {
        val withoutOtp = SmsPrefilter.evaluate(
            "INR 500.00 spent without OTP on HDFC Bank Card XX1234 at DEMO SHOP."
        )
        assertEquals(SmsPrefilter.Decision.PASS, withoutOtp.decision)
        assertEquals("eligible", withoutOtp.reason)
    }
}
