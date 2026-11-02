package com.pocketfinancer.pipeline.sms

import com.pocketfinancer.data.repository.GroundedAccountResolution
import java.text.Normalizer
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal object SmsV4ProcessingJson {
    fun advisoryEvidence(analysis: SmsAnalysis): JSONArray = JSONArray().apply {
        analysis.candidates.forEach { candidate ->
            put(JSONObject()
                .put("kind", candidate.kind.wireValue)
                .put("source_span", candidate.evidence?.let(::advisorySpan) ?: JSONObject.NULL)
                .put("clause", candidate.clauseId ?: JSONObject.NULL)
                .put("suggested_interpretation", candidateInterpretation(candidate))
                .put("provenance", JSONObject()
                    .put("candidate_id", candidate.id).put("analyzer_kind", "candidate"))
                .put("analyzer_version", analysis.contract))
        }
        analysis.cues.forEach { cue ->
            put(JSONObject()
                .put("kind", cue.kind)
                .put("source_span", advisorySpan(cue.evidence))
                .put("clause", cue.clauseId)
                .put("suggested_interpretation", JSONObject().put("reason_code", cue.reasonCode))
                .put("provenance", JSONObject()
                    .put("cue_id", cue.id).put("analyzer_kind", "cue"))
                .put("analyzer_version", analysis.contract))
        }
    }

    fun senderFamily(sender: String): String = Normalizer.normalize(
        sender, Normalizer.Form.NFKC
    ).trim().lowercase(Locale.ROOT)
        .replace(Regex("^[a-z]{2}-", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[0-9]+"), "#")
        .ifBlank { "unknown" }

    fun validated(value: SmsExtractorResult): String = when (value) {
        SmsExtractorResult.None -> "{\"decision\":\"none\"}"
        SmsExtractorResult.Abstain -> "{\"decision\":\"abstain\"}"
        is SmsExtractorResult.Posted -> CanonicalAndroidJson.stringify(JSONObject()
            .put("decision", "posted")
            .put("amount", JSONObject().put("value", value.amount.value)
                .put("currency", value.amount.currency).put("evidence", span(value.amount.evidence)))
            .put("direction", JSONObject().put("value", value.direction.value)
                .put("evidence", span(value.direction.evidence)))
            .put("account", JSONObject().put("reference", value.account.reference)
                .put("evidence", span(value.account.evidence)))
            .put("counterparty", value.counterparty?.let {
                JSONObject().put("value", it.value).put("evidence", span(it.evidence))
            } ?: JSONObject.NULL))
    }

    fun semantic(value: NormalizedSmsExtraction): JSONObject = JSONObject()
        .put("money", JSONObject()
            .put("minor_units", value.minorUnits).put("currency", value.currency))
        .put("direction", value.direction)
        .put("account_reference", value.accountReference)
        .put("counterparty", value.counterparty ?: JSONObject.NULL)
        .put("evidence", JSONObject()
            .put("amount", span(value.amountEvidence))
            .put("direction", span(value.directionEvidence))
            .put("account", span(value.accountEvidence))
            .put("counterparty", value.counterpartyEvidence?.let(::span) ?: JSONObject.NULL))

    fun account(
        value: GroundedAccountResolution,
        normalizedReference: String
    ): JSONObject {
        val aliasKey = normalizedReference.takeIf(String::isNotEmpty)?.let {
            if ('@' in it) "vpa:$it" else "suffix:$it"
        }
        return when (value) {
            GroundedAccountResolution.Missing -> baseAccount("missing", 0, null, null, null)
            GroundedAccountResolution.Unresolved -> baseAccount(
                "unresolved", 0, null, aliasKey, null
            )
            is GroundedAccountResolution.Ambiguous -> baseAccount(
                "ambiguous", value.accountIds.size, null, aliasKey, null
            )
            is GroundedAccountResolution.UniquelyResolved -> baseAccount(
                "uniquely_resolved", 1, value.accountId, aliasKey,
                value.matchedAliasHash
            )
        }
    }

    fun accountReasons(value: GroundedAccountResolution): List<String> = when (value) {
        GroundedAccountResolution.Missing -> listOf("account_resolution_unresolved")
        GroundedAccountResolution.Unresolved -> listOf("account_resolution_unresolved")
        is GroundedAccountResolution.Ambiguous -> listOf("account_resolution_ambiguous")
        is GroundedAccountResolution.UniquelyResolved -> emptyList()
    }

    fun duplicate(
        status: String,
        idempotencyKey: String,
        sourceEventKey: String,
        fingerprint: String?
    ): JSONObject = JSONObject()
        .put("status", status)
        .put("idempotency_key", idempotencyKey)
        .put("source_event_key", sourceEventKey)
        .put("transaction_fingerprint", fingerprint ?: JSONObject.NULL)

    fun gate(
        posted: Boolean,
        accountReason: String?,
        duplicateStatus: String
    ): JSONObject {
        val duplicateReason = duplicateReason(duplicateStatus)
        val result = when {
            !posted -> "not_posted"
            accountReason != null || duplicateReason != null -> "review_required"
            else -> "blocked_by_mode"
        }
        val primary = when {
            !posted -> "persistence_not_posted"
            accountReason != null -> accountReason
            duplicateReason != null -> duplicateReason
            else -> "persistence_blocked_by_rollout_mode"
        }
        return JSONObject()
            .put("result", result)
            .put("primary_reason", primary)
            .put("checks", checks(posted, accountReason, duplicateStatus))
    }

    fun checksJson(
        posted: Boolean,
        accountReason: String?,
        duplicateStatus: String
    ): String = CanonicalAndroidJson.stringify(checks(posted, accountReason, duplicateStatus))

    fun result(
        status: String,
        decision: String,
        semantic: JSONObject?,
        operation: SmsV4OperationSnapshot,
        account: JSONObject?,
        duplicate: JSONObject?,
        gate: JSONObject?,
        reasons: List<String>
    ): String = CanonicalAndroidJson.stringify(JSONObject()
        .put("contract", "pocketfinancer.processing-result/3")
        .put("status", status)
        .put("recognition_decision", decision)
        .put("semantic_result", semantic ?: JSONObject.NULL)
        .put("receipt_timestamp", JSONObject()
            .put("epoch_ms", operation.configuration.receivedTimestampEpochMs)
            .put("provenance", operation.configuration.receivedTimestampProvenance)
            .put("read_only", true))
        .put("account_resolution", account ?: JSONObject.NULL)
        .put("duplicate_assessment", duplicate ?: JSONObject.NULL)
        .put("automatic_persistence", gate ?: JSONObject.NULL)
        .put("reason_codes", JSONArray(reasons)))

    private fun candidateInterpretation(candidate: SmsCandidate): JSONObject = JSONObject().apply {
        candidate.value.forEach { (key, value) ->
            put(key, if (key == "minor_units") value.toLong() else value)
        }
    }

    private fun advisorySpan(value: SmsEvidenceSpan): JSONObject = JSONObject()
        .put("start_scalar", value.startCodePoint)
        .put("end_scalar", value.endCodePoint)
        .put("text", value.text)

    private fun span(value: UnicodeScalarSpan): JSONObject = JSONObject()
        .put("start_scalar", value.start)
        .put("end_scalar", value.end)
        .put("text", value.text)

    private fun baseAccount(
        status: String,
        count: Int,
        accountId: String?,
        normalizedReference: String?,
        aliasHash: String?
    ): JSONObject = JSONObject()
        .put("status", status)
        .put("match_count", count)
        .put("account_id", accountId ?: JSONObject.NULL)
        .put("normalized_reference", normalizedReference ?: JSONObject.NULL)
        .put("matched_alias_hash", aliasHash ?: JSONObject.NULL)
        .put("provenance", "pocketfinancer.account-resolution-profile/1")

    private fun checks(
        posted: Boolean,
        accountReason: String?,
        duplicateStatus: String
    ): JSONArray = JSONArray(listOf(
        check("operation_integrity", true, null),
        check("configuration_hash", true, null),
        check("claim_ownership", true, null),
        check("extractor_mode", true, null),
        check("posted_extraction", posted, if (posted) null else "persistence_not_posted"),
        check("grounded_mandatory_fields", posted, if (posted) null else "persistence_grounded_fields_missing"),
        check("valid_money", posted, if (posted) null else "persistence_invalid_money"),
        check("receipt_timestamp", true, null),
        check("account_resolution", accountReason == null, accountReason),
        check("duplicate_assessment", duplicateStatus == "clear", duplicateReason(duplicateStatus)),
        check("rollout_mode", false, "persistence_blocked_by_rollout_mode")
    ))

    private fun duplicateReason(status: String): String? = when (status) {
        "clear" -> null
        "already_persisted" -> "duplicate_already_persisted"
        else -> "duplicate_possible"
    }

    private fun check(name: String, passed: Boolean, reason: String?): JSONObject = JSONObject()
        .put("check", name).put("passed", passed)
        .put("reason_code", reason ?: JSONObject.NULL)
}
