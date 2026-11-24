package com.pocketfinancer.pipeline.sms

import com.pocketfinancer.data.repository.GroundedAccountResolution
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SmsProcessingJsonTest {
    @Test
    fun `none gate uses shared persistence ordering`() {
        val gate = SmsProcessingJson.gate(
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
    fun `account and duplicate failures precede eligible automatic routing`() {
        val account = SmsProcessingJson.gate(
            posted = true,
            accountReason = "account_resolution_ambiguous",
            duplicateStatus = "clear"
        )
        assertEquals("review_required", account.getString("result"))
        assertEquals("account_resolution_ambiguous", account.getString("primary_reason"))

        val alreadySaved = SmsProcessingJson.gate(
            posted = true,
            accountReason = null,
            duplicateStatus = "already_persisted"
        )
        assertEquals("review_required", alreadySaved.getString("result"))
        assertEquals("duplicate_already_persisted", alreadySaved.getString("primary_reason"))

        val possibleDuplicate = SmsProcessingJson.gate(
            posted = true,
            accountReason = null,
            duplicateStatus = "possible_duplicate"
        )
        assertEquals("review_required", possibleDuplicate.getString("result"))
        assertEquals("duplicate_possible", possibleDuplicate.getString("primary_reason"))

        val eligible = SmsProcessingJson.gate(
            posted = true,
            accountReason = null,
            duplicateStatus = "clear"
        )
        assertEquals("eligible", eligible.getString("result"))
        assertEquals("persistence_eligible", eligible.getString("primary_reason"))
    }

    @Test
    fun `empty duplicate assessment has an explicit null fingerprint`() {
        val duplicate = SmsProcessingJson.duplicate(
            status = "clear",
            idempotencyKey = "source",
            sourceEventKey = "event",
            fingerprint = null
        )

        assertEquals(JSONObject.NULL, duplicate.get("transaction_fingerprint"))
    }

    @Test
    fun `account result serializes the canonical alias key`() {
        val unresolved = SmsProcessingJson.account(
            GroundedAccountResolution.Unresolved,
            "1234"
        )
        assertEquals("suffix:1234", unresolved.getString("normalized_reference"))

        val unique = SmsProcessingJson.account(
            GroundedAccountResolution.UniquelyResolved(
                accountId = "account-id",
                matchedAliasHash = "hash",
                provenance = "confirmed_owned_account_alias_v1"
            ),
            "name@bank"
        )
        assertEquals("vpa:name@bank", unique.getString("normalized_reference"))
    }

    @Test
    fun `tombstone result supports null decision and tombstone status`() {
        val snapshot = SmsOperationSnapshot(
            operationId = "11111111-1111-4111-8111-111111111111",
            parentOperationId = null,
            stableEventId = "22222222-2222-4222-8222-222222222222",
            configuration = SmsOperationConfiguration(
                operationId = "11111111-1111-4111-8111-111111111111",
                parentOperationId = null,
                sourceId = "source-1",
                sourceRefHash = "a".repeat(64),
                trigger = "realtime",
                createdAtEpochMs = 1_700_000_000_000L,
                admissionTimestampEpochMs = 1_700_000_000_000L,
                receivedTimestampEpochMs = 1_700_000_000_000L,
                receivedTimestampProvenance = "platform_received",
                timezoneId = "UTC",
                primaryCurrency = "INR",
                enabledProfiles = listOf("core-en", "india"),
                releaseManifestHash = SmsContractAssets.MANIFEST_SHA256,
                currencyAssetHash = "b".repeat(64),
                profileAssetHashes = mapOf("core-en" to "c".repeat(64)),
                extractorEligible = false,
                extractorIneligibilityReason = "runtime_unavailable",
                modelIdentifier = null,
                modelFileSha256 = null,
                modelIdentityKind = "file_sha256",
                runtimeVersion = "llama.cpp-jni",
                osVersion = "14",
                deviceCohort = "test",
                promptHash = "d".repeat(64),
                grammarHash = "e".repeat(64),
                grammarEnabled = false,
                validationProfileHash = "f".repeat(64)
            ),
            configurationJson = "{}",
            configurationHash = "0".repeat(64)
        )
        val json = JSONObject(
            SmsProcessingJson.result(
                status = "tombstoned_excluded",
                decision = null,
                semantic = null,
                operation = snapshot,
                account = null,
                duplicate = null,
                gate = null,
                reasons = listOf("prefilter_otp_verification", "tombstoned_excluded")
            )
        )
        assertEquals(SmsContractAssets.PROCESSING_RESULT_CONTRACT, json.getString("contract"))
        assertEquals("tombstoned_excluded", json.getString("status"))
        assertEquals(JSONObject.NULL, json.get("recognition_decision"))
    }
}
