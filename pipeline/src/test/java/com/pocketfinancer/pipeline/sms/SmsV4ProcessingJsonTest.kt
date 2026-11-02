package com.pocketfinancer.pipeline.sms

import com.pocketfinancer.data.repository.GroundedAccountResolution
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SmsV4ProcessingJsonTest {
    @Test
    fun `none gate uses shared persistence ordering`() {
        val gate = SmsV4ProcessingJson.gate(
            posted = false,
            accountReason = "account_resolution_unresolved",
            duplicateStatus = "clear"
        )

        assertEquals("not_posted", gate.getString("result"))
        assertEquals("persistence_not_posted", gate.getString("primary_reason"))
        val checks = gate.getJSONArray("checks")
        assertEquals("persistence_not_posted", checks.getJSONObject(4).getString("reason_code"))
        assertEquals(
            "persistence_grounded_fields_missing",
            checks.getJSONObject(5).getString("reason_code")
        )
        assertEquals("persistence_invalid_money", checks.getJSONObject(6).getString("reason_code"))
    }

    @Test
    fun `account and duplicate failures precede review-only rollout`() {
        val account = SmsV4ProcessingJson.gate(
            posted = true,
            accountReason = "account_resolution_ambiguous",
            duplicateStatus = "clear"
        )
        assertEquals("review_required", account.getString("result"))
        assertEquals("account_resolution_ambiguous", account.getString("primary_reason"))

        val duplicate = SmsV4ProcessingJson.gate(
            posted = true,
            accountReason = null,
            duplicateStatus = "already_persisted"
        )
        assertEquals("review_required", duplicate.getString("result"))
        assertEquals("duplicate_already_persisted", duplicate.getString("primary_reason"))

        val reviewOnly = SmsV4ProcessingJson.gate(
            posted = true,
            accountReason = null,
            duplicateStatus = "clear"
        )
        assertEquals("blocked_by_mode", reviewOnly.getString("result"))
        assertEquals("persistence_blocked_by_rollout_mode", reviewOnly.getString("primary_reason"))
    }

    @Test
    fun `empty duplicate assessment has an explicit null fingerprint`() {
        val duplicate = SmsV4ProcessingJson.duplicate(
            status = "clear",
            idempotencyKey = "source",
            sourceEventKey = "event",
            fingerprint = null
        )

        assertEquals(JSONObject.NULL, duplicate.get("transaction_fingerprint"))
    }

    @Test
    fun `account result serializes the canonical alias key`() {
        val unresolved = SmsV4ProcessingJson.account(
            GroundedAccountResolution.Unresolved,
            "1234"
        )
        assertEquals("suffix:1234", unresolved.getString("normalized_reference"))

        val unique = SmsV4ProcessingJson.account(
            GroundedAccountResolution.UniquelyResolved(
                accountId = "account-id",
                matchedAliasHash = "hash",
                provenance = "confirmed_owned_account_alias_v1"
            ),
            "name@bank"
        )
        assertEquals("vpa:name@bank", unique.getString("normalized_reference"))
    }
}
