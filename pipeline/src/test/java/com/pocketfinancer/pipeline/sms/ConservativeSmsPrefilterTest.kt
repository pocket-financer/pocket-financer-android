package com.pocketfinancer.pipeline.sms

import java.security.MessageDigest
import java.util.Locale
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Test

class ConservativeSmsPrefilterTest {
    @Test
    fun `shared invented vectors preserve source and deterministic decisions`() {
        // Canonical source: intelligence tests/sms_processing/golden/prefilter-v1.json.
        val bytes = requireNotNull(javaClass.getResourceAsStream("/prefilter-v1.json")) {
            "Shared prefilter fixture is missing"
        }.use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("373337f584cd70d4d165e7ff557fb45ece8967f3795d0d2d05a02f61db5053c9", hash)
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals(1, root.getInt("schema_version"))
        assertEquals(ConservativeSmsPrefilter.POLICY_ID, root.getString("policy_id"))
        assertTrue(root.getString("provenance").startsWith("Invented for the prefilter pilot;"))
        val vectors = root.getJSONArray("vectors")
        assertEquals(38, vectors.length())
        val ids = mutableSetOf<String>()
        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            val id = vector.getString("id")
            assertTrue(ids.add(id), id)
            val body = vector.getString("body")
            val original = body.toByteArray(Charsets.UTF_8)
            val result = ConservativeSmsPrefilter.evaluate(body)
            assertEquals(vector.getString("decision"), result.decision.wireValue, id)
            assertEquals(vector.getString("reason"), result.reason.wireValue, id)
            assertEquals(result, ConservativeSmsPrefilter.evaluate(body), id)
            assertContentEquals(original, body.toByteArray(Charsets.UTF_8), id)
            // Sender metadata is never supplied to the body-only policy.
            assertTrue(vector.has("sender"), id)
        }
    }

    @Test
    fun `matching is independent of the device locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(
                ConservativeSmsPrefilter.Reason.STANDALONE_VERIFICATION,
                ConservativeSmsPrefilter.evaluate(
                    "YOUR LOGIN VERIFICATION CODE IS 4321. DO NOT SHARE IT."
                ).reason
            )
        } finally {
            Locale.setDefault(original)
        }
    }
}
