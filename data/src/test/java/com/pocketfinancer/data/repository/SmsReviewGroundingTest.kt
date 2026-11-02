package com.pocketfinancer.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class SmsReviewGroundingTest {
    private val source = "🔔 INR 1,250.00 debited from XX1234 at Café"

    @Test
    fun `v4 proposal preserves scalar spans and immutable receipt time`() {
        val proposal = SmsReviewGrounding.proposal(result(), source)!!

        assertEquals(125000, proposal.amountMinorUnits)
        assertEquals("INR 1,250.00", proposal.amountSpan.text)
        assertEquals(123456789, proposal.receiptTimestampEpochMs)
        assertEquals("Café", proposal.counterpartySpan?.text)
    }

    @Test
    fun `source supported correction is renormalized and mismatched text fails`() {
        val proposal = SmsReviewGrounding.proposal(result(), source)!!
        val valid = correction(
            "counterparty",
            38,
            42,
            "Café",
            "\"café\""
        )
        assertEquals(
            "café",
            SmsReviewGrounding.applyCorrections(proposal, source, listOf(valid)).counterparty
        )

        assertFails {
            SmsReviewGrounding.applyCorrections(
                proposal,
                source,
                listOf(valid.copy(evidenceJson = valid.evidenceJson!!.replace("Café", "Cafe")))
            )
        }
    }

    @Test
    fun `non posted or malformed result is not reviewable`() {
        assertNull(SmsReviewGrounding.proposal("{\"decision\":\"none\"}", source))
    }

    @Test
    fun `floating point contract integers are rejected`() {
        assertNull(
            SmsReviewGrounding.proposal(
                result().replace("\"minor_units\":125000", "\"minor_units\":125000.0"),
                source
            )
        )
        assertNull(
            SmsReviewGrounding.proposal(
                result().replace("\"match_count\":0", "\"match_count\":1"),
                source
            )
        )
        assertNull(
            SmsReviewGrounding.proposal(
                result().replace("\"transaction_fingerprint\":\"${fingerprint()}\"", "\"transaction_fingerprint\":\"bad\""),
                source
            )
        )
        assertNull(
            SmsReviewGrounding.proposal(
                result().replace("\"epoch_ms\":123456789", "\"epoch_ms\":123456789.0"),
                source
            )
        )
    }

    @Test
    fun `semantic value must be derived from its evidence`() {
        assertNull(
            SmsReviewGrounding.proposal(
                result().replace("\"minor_units\":125000", "\"minor_units\":125001"),
                source
            )
        )
    }

    @Test
    fun `unknown processing statuses and receipt provenance fail closed`() {
        assertNull(
            SmsReviewGrounding.proposal(
                result().replace("\"status\":\"blocked\"", "\"status\":\"eligible\""),
                source
            )
        )
        assertNull(
            SmsReviewGrounding.proposal(
                result().replace("\"status\":\"unresolved\"", "\"status\":\"guessed\""),
                source
            )
        )
        assertNull(
            SmsReviewGrounding.proposal(
                result().replace("\"status\":\"clear\"", "\"status\":\"unknown\""),
                source
            )
        )
        assertNull(
            SmsReviewGrounding.proposal(
                result().replace("\"provenance\":\"platform_received\"", "\"provenance\":\"inferred\""),
                source
            )
        )
        assertNull(
            SmsReviewGrounding.proposal(
                result().replace(
                    "\"duplicate_assessment\":{",
                    "\"duplicate_assessment\":null,\"ignored_duplicate_assessment\":{"
                ),
                source
            )
        )
    }

    @Test
    fun `account resolution must use the canonical alias key`() {
        assertNull(
            SmsReviewGrounding.proposal(
                result().replace("\"normalized_reference\":\"suffix:1234\"", "\"normalized_reference\":\"1234\""),
                source
            )
        )
        assertNull(
            SmsReviewGrounding.proposal(
                result().replace("\"matched_alias_hash\":null", "\"matched_alias_hash\":\"${SmsProcessingStore.sha256("suffix:1234")}\""),
                source
            )
        )
    }

    private fun correction(
        field: String,
        start: Int,
        end: Int,
        text: String,
        value: String
    ) = SmsFieldCorrection(
        field = field,
        classification = SmsFieldGroundingClassification.SUPPLIED_SOURCE_SUPPORTED_CANDIDATE_MISS,
        previousRevisionId = null,
        candidateId = null,
        evidenceJson =
            """{"start_scalar":$start,"end_scalar":$end,"text":"$text"}""",
        newValueJson = value
    )

    private fun result(): String = """
        {
          "contract":"pocketfinancer.processing-result/3",
          "status":"blocked",
          "recognition_decision":"posted",
          "semantic_result":{
            "money":{"minor_units":125000,"currency":"INR"},
            "direction":"debit",
            "account_reference":"1234",
            "counterparty":"café",
            "evidence":{
              "amount":{"start_scalar":2,"end_scalar":14,"text":"INR 1,250.00"},
              "direction":{"start_scalar":15,"end_scalar":22,"text":"debited"},
              "account":{"start_scalar":28,"end_scalar":34,"text":"XX1234"},
              "counterparty":{"start_scalar":38,"end_scalar":42,"text":"Café"}
            }
          },
          "receipt_timestamp":{"epoch_ms":123456789,"provenance":"platform_received","read_only":true},
          "account_resolution":{
            "status":"unresolved","match_count":0,"account_id":null,
            "normalized_reference":"suffix:1234","matched_alias_hash":null,
            "provenance":"pocketfinancer.account-resolution-profile/1"
          },
          "duplicate_assessment":{
            "status":"clear","idempotency_key":"operation-v4",
            "source_event_key":"event-v4","transaction_fingerprint":"${fingerprint()}"
          }
        }
    """.trimIndent()

    private fun fingerprint(): String = SmsProcessingStore.sha256(
        "125000\u0000INR\u0000debit\u0000\u0000123456789"
    )
}
