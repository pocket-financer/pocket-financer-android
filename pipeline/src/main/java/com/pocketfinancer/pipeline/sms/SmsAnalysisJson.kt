package com.pocketfinancer.pipeline.sms

import org.json.JSONArray
import org.json.JSONObject

internal fun groundedSelectorPayload(source: String, analysis: SmsAnalysis): String {
    val candidates = JSONArray()
    analysis.candidates.forEach { candidate ->
        val item = JSONObject()
            .put("id", candidate.id)
            .put("kind", candidate.kind.wireValue)
            .put("clause", candidate.clauseId ?: JSONObject.NULL)
            .put("absent", candidate.explicitAbsence)
        candidate.evidence?.let { item.put("evidence", it.text) }
        candidate.value["direction"]?.let { item.put("direction", it) }
        candidates.put(item)
    }
    return CanonicalAndroidJson.stringify(
        JSONObject()
            .put("contract", "pocketfinancer.grounded-candidate-selector-input/1")
            .put("analysis_id", analysis.analysisId)
            .put("message", source)
            .put("candidates", candidates)
    )
}

internal fun SmsAnalysis.canonicalJson(): String = CanonicalAndroidJson.stringify(
    JSONObject()
        .put("analysis_id", analysisId)
        .put("candidates", JSONArray(candidates.map(::candidateJson)))
        .put("clauses", JSONArray(clauses.map(::clauseJson)))
        .put("config_hash", configurationHash)
        .put("contract", contract)
        .put("cues", JSONArray(cues.map(::cueJson)))
        .put(
            "metadata",
            JSONObject()
                .put("analyzer_behavior_version", "pocketfinancer.structural-sms-analyzer/2")
                .put(
                    "clause_annotations",
                    JSONArray(clauseAnnotations.map(::clauseAnnotationJson))
                )
                .put("completed_event_candidate_count", completedEventCount)
                .put(
                    "completed_event_clause_count",
                    candidates.filter { it.kind == SmsCandidateKind.DIRECTION }
                        .mapNotNull { it.clauseId }.distinct().size
                )
                .put("currency_context_hash", currencyContextHash)
                .put("input_valid", source.isNotBlank())
                .put("is_outgoing", false)
                .put("normalized_structural_fingerprint", normalizedStructuralFingerprint)
                .put(
                    "source_timestamp",
                    JSONObject()
                        .put("epoch_ms", sourceTimestampEpochMs ?: JSONObject.NULL)
                        .put("provenance", sourceTimestampProvenance)
                )
                .put(
                    "unicode_behavior",
                    JSONObject()
                        .put("normalization", "per_code_point_nfkc_casefold")
                        .put("unicode_database_version", unicodeDatabaseVersion)
                        .put("whitespace", "collapse_unicode_whitespace")
                )
        )
        .put("primary_currency", primaryCurrency)
        .put("profile_id", profileId)
        .put("reason_codes", JSONArray(reasonCodes))
        .put("source_fingerprint", sourceHash)
        .put("source_length_chars", source.codePointCount(0, source.length))
        .put("source_length_utf8", source.toByteArray(Charsets.UTF_8).size)
)

private fun clauseJson(value: SmsClause) = JSONObject()
    .put("clause_id", value.id)
    .put("evidence", evidenceJson(value.evidence))

private fun candidateJson(value: SmsCandidate): JSONObject {
    val candidateValue = JSONObject(value.value)
    if (value.kind == SmsCandidateKind.AMOUNT) {
        candidateValue.put("minor_units", value.value.getValue("minor_units").toLong())
    }
    return JSONObject()
        .put("candidate_id", value.id)
        .put("clause_id", value.clauseId ?: JSONObject.NULL)
        .put("context", JSONArray(value.context))
        .put("evidence", value.evidence?.let(::evidenceJson) ?: JSONObject.NULL)
        .put("explicit_absence", value.explicitAbsence)
        .put("kind", value.kind.wireValue)
        .put("value", candidateValue)
}

private fun cueJson(value: SmsCue) = JSONObject()
    .put("clause_id", value.clauseId)
    .put("cue_id", value.id)
    .put("evidence", evidenceJson(value.evidence))
    .put("kind", value.kind)
    .put("reason_code", value.reasonCode)

private fun clauseAnnotationJson(value: SmsClauseAnnotation) = JSONObject()
    .put("clause_id", value.clauseId)
    .put(
        "financial_families",
        JSONArray(value.financialFamilies.map { family ->
            JSONObject()
                .put("evidence", evidenceJson(family.evidence))
                .put("family", family.family)
        })
    )
    .put("states", JSONArray(value.states))

private fun evidenceJson(value: SmsEvidenceSpan) = JSONObject()
    .put("start_char", value.startCodePoint)
    .put("end_char", value.endCodePoint)
    .put("start_utf8", value.startUtf8)
    .put("end_utf8", value.endUtf8)
    .put("text", value.text)
