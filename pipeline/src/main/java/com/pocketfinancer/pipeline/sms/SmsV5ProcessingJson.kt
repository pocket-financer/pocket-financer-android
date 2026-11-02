package com.pocketfinancer.pipeline.sms

import com.pocketfinancer.data.repository.GroundedAccountResolution
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Serialization for the automatic policy; v4 JSON remains frozen separately. */
internal object SmsV5ProcessingJson {
    fun advisoryEvidence(analysis: SmsAnalysis): JSONArray =
        SmsV4ProcessingJson.advisoryEvidence(analysis)

    fun reviewAnalyzerSuggestions(analysis: SmsAnalysis): JSONArray {
        val advisory = advisoryEvidence(analysis)
        return JSONArray().apply {
            repeat(advisory.length()) { index ->
                val source = advisory.getJSONObject(index)
                put(JSONObject()
                    .put("kind", source.getString("kind"))
                    .put("span", source.opt("source_span"))
                    .put("clause", source.opt("clause"))
                    .put("suggested_interpretation", source.opt("suggested_interpretation"))
                    .put("provenance", source.getJSONObject("provenance"))
                    .put("analyzer_version", source.getString("analyzer_version")))
            }
        }
    }

    fun fieldEvidence(
        analysis: SmsAnalysis,
        normalized: NormalizedSmsExtraction? = null,
        partial: List<SmsPartialFieldEvidence> = emptyList()
    ): JSONArray = JSONArray().apply {
        val modelFields = if (partial.isNotEmpty()) partial else normalized?.let(::normalizedFields).orEmpty()
        modelFields.forEach { field -> put(fieldEvidence(field)) }
        val advisory = advisoryEvidence(analysis)
        repeat(advisory.length()) { index ->
            val item = advisory.getJSONObject(index)
            val kind = item.getString("kind")
            val span = item.opt("source_span")
            if (
                kind in setOf("amount", "direction", "account", "counterparty") &&
                span is JSONObject
            ) {
                put(JSONObject()
                    .put("field", kind)
                    .put("source_span", span)
                    .put("normalized_value", item.opt("suggested_interpretation"))
                    .put("validation_state", "suggestion")
                    .put("originating_stage", "analysis_advisory")
                    .put("origin", "advisory_analyzer"))
            }
        }
    }

    fun validated(value: SmsExtractorResult): String = SmsV4ProcessingJson.validated(value)

    fun semantic(value: NormalizedSmsExtraction): JSONObject = SmsV4ProcessingJson.semantic(value)

    fun account(
        value: GroundedAccountResolution,
        normalizedReference: String
    ): JSONObject = SmsV4ProcessingJson.account(value, normalizedReference)

    fun accountReasons(value: GroundedAccountResolution): List<String> =
        SmsV4ProcessingJson.accountReasons(value)

    fun duplicate(
        status: String,
        idempotencyKey: String,
        sourceEventKey: String,
        fingerprint: String?
    ): JSONObject = SmsV4ProcessingJson.duplicate(
        status, idempotencyKey, sourceEventKey, fingerprint
    )

    fun gate(
        posted: Boolean,
        accountReason: String?,
        duplicateStatus: String
    ): JSONObject {
        val duplicateReason = duplicateReason(duplicateStatus)
        val result = when {
            !posted -> "not_posted"
            accountReason != null || duplicateReason != null -> "review_required"
            else -> "eligible"
        }
        val primary = when {
            !posted -> "persistence_not_posted"
            accountReason != null -> accountReason
            duplicateReason != null -> duplicateReason
            else -> "persistence_eligible"
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

    fun persistenceFailureGate(reason: String): JSONObject {
        val values = checks(posted = true, accountReason = null, duplicateStatus = "clear")
        values.put(check("atomic_transaction_write", false, reason))
        return JSONObject()
            .put("result", "review_required")
            .put("primary_reason", reason)
            .put("checks", values)
    }

    fun persistenceFailureChecksJson(reason: String): String =
        CanonicalAndroidJson.stringify(persistenceFailureGate(reason).getJSONArray("checks"))

    fun result(
        status: String,
        decision: String,
        semantic: JSONObject?,
        operation: SmsV5OperationSnapshot,
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

    private fun normalizedFields(value: NormalizedSmsExtraction): List<SmsPartialFieldEvidence> =
        buildList {
            add(SmsPartialFieldEvidence(
                "amount",
                value.amountEvidence,
                "{\"currency\":\"${value.currency}\",\"minor_units\":${value.minorUnits}}",
                "valid",
                "normalization"
            ))
            add(SmsPartialFieldEvidence(
                "direction",
                value.directionEvidence,
                JSONObject.quote(value.direction),
                "valid",
                "normalization"
            ))
            add(SmsPartialFieldEvidence(
                "account",
                value.accountEvidence,
                JSONObject.quote(value.accountReference),
                "valid",
                "normalization"
            ))
            if (value.counterparty != null && value.counterpartyEvidence != null) {
                add(SmsPartialFieldEvidence(
                    "counterparty",
                    value.counterpartyEvidence,
                    JSONObject.quote(value.counterparty),
                    "valid",
                    "normalization"
                ))
            }
        }

    private fun fieldEvidence(value: SmsPartialFieldEvidence): JSONObject = JSONObject()
        .put("field", value.field)
        .put("source_span", span(value.sourceSpan))
        .put(
            "normalized_value",
            value.normalizedValueJson?.let { JSONTokener(it).nextValue() } ?: JSONObject.NULL
        )
        .put("validation_state", value.validationState)
        .put("originating_stage", value.originatingStage)
        .put("origin", value.origin)

    private fun span(value: UnicodeScalarSpan): JSONObject = JSONObject()
        .put("start_scalar", value.start)
        .put("end_scalar", value.end)
        .put("text", value.text)

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
        check(
            "grounded_mandatory_fields",
            posted,
            if (posted) null else "persistence_grounded_fields_missing"
        ),
        check("valid_money", posted, if (posted) null else "persistence_invalid_money"),
        check("receipt_timestamp", true, null),
        check("account_resolution", accountReason == null, accountReason),
        check("duplicate_assessment", duplicateStatus == "clear", duplicateReason(duplicateStatus)),
        check("rollout_mode", true, null)
    ))

    private fun duplicateReason(status: String): String? = when (status) {
        "clear" -> null
        "already_persisted" -> "duplicate_already_persisted"
        else -> "duplicate_possible"
    }

    private fun check(name: String, passed: Boolean, reason: String?): JSONObject = JSONObject()
        .put("check", name)
        .put("passed", passed)
        .put("reason_code", reason ?: JSONObject.NULL)
}
