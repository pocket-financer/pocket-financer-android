package com.pocketfinancer.pipeline.sms

import org.json.JSONException
import org.json.JSONObject

class GroundedSelectorValidationException(val reasonCode: String) : IllegalArgumentException(reasonCode)

class GroundedSelectorValidator {
    fun validate(
        rawOutput: String,
        analysis: SmsAnalysis,
        byteLimit: Int = 16_384
    ): GroundedSelectorResult {
        if (rawOutput.toByteArray(Charsets.UTF_8).size > byteLimit) {
            fail("runtime_output_truncated")
        }
        if (duplicateKeys(rawOutput).isNotEmpty()) fail("selector_duplicate_json_key")
        val payload = try {
            JSONObject(rawOutput)
        } catch (_: JSONException) {
            fail("selector_malformed_json")
        }
        val normalized = rawOutput.trim()
        if (!normalized.startsWith('{') || !normalized.endsWith('}')) {
            fail("selector_malformed_json")
        }
        val decisionValue = payload.opt("decision")
        if (decisionValue !is String) fail("selector_decision_type_invalid")
        val decision = decisionValue as String
        val keys = payload.keys().asSequence().toSet()
        when (decision) {
            "none", "abstain" -> {
                if (keys != setOf("decision")) fail("selector_non_posted_extra_fields")
                return GroundedSelectorResult(
                    if (decision == "none") SelectorDecision.NONE else SelectorDecision.ABSTAIN
                )
            }
            "posted" -> Unit
            else -> fail("selector_unknown_decision")
        }
        val expected = setOf("decision", "amount", "direction", "account", "counterparty")
        if (keys != expected) fail("selector_posted_field_set_invalid")
        val selected = expected.minus("decision").associateWith { field ->
            val id = payload.opt(field)
            if (id !is String || id.isBlank()) fail("selector_candidate_id_invalid")
            id
        }
        val byId = analysis.candidates.associateBy { it.id }
        if (byId.size != analysis.candidates.size) fail("selector_candidate_ids_ambiguous")
        val amount = resolve(byId, selected.getValue("amount"), SmsCandidateKind.AMOUNT)
        val direction = resolve(byId, selected.getValue("direction"), SmsCandidateKind.DIRECTION)
        val account = resolve(byId, selected.getValue("account"), SmsCandidateKind.ACCOUNT)
        val counterparty = resolve(byId, selected.getValue("counterparty"), SmsCandidateKind.COUNTERPARTY)
        if (amount.explicitAbsence || direction.explicitAbsence) {
            fail("selector_required_candidate_absent")
        }
        if (amount.evidence == null || direction.evidence == null) {
            fail("selector_required_evidence_missing")
        }
        if (amount.clauseId != direction.clauseId) fail("selector_cross_clause_core_selection")
        validateCoreMetadata(amount, direction)
        validateOptionalMetadata(account)
        validateOptionalMetadata(counterparty)
        return GroundedSelectorResult(
            SelectorDecision.POSTED,
            SelectorPostedSelection(
                amount.id,
                direction.id,
                account.id,
                counterparty.id
            )
        )
    }

    private fun resolve(
        candidates: Map<String, SmsCandidate>,
        id: String,
        kind: SmsCandidateKind
    ): SmsCandidate {
        val candidate = candidates[id] ?: fail("selector_unknown_or_cross_message_candidate")
        if (candidate.kind != kind) fail("selector_candidate_kind_mismatch")
        return candidate
    }

    private fun validateCoreMetadata(amount: SmsCandidate, direction: SmsCandidate) {
        val minorUnits = amount.value["minor_units"]?.toLongOrNull()
        val currency = amount.value["currency"]
        val provenance = amount.value["currency_provenance"]
        if (
            minorUnits == null || minorUnits <= 0 || currency !in CurrencyProfileRegistry.scales ||
            provenance !in setOf(
                "explicit_code",
                "explicit_unambiguous_symbol_or_marker",
                "user_primary_default"
            ) || direction.value["direction"] !in setOf("debit", "credit")
        ) {
            fail("selector_candidate_metadata_invalid")
        }
    }

    private fun validateOptionalMetadata(candidate: SmsCandidate) {
        if (candidate.explicitAbsence) {
            if (
                candidate.evidence != null || candidate.clauseId != null ||
                candidate.value != mapOf("state" to "absent")
            ) {
                fail("selector_absent_candidate_metadata_invalid")
            }
            return
        }
        if (candidate.evidence == null || candidate.clauseId == null) {
            fail("selector_optional_evidence_missing")
        }
        when (candidate.kind) {
            SmsCandidateKind.ACCOUNT -> if (
                candidate.value["account_type"] !in setOf("bank_account", "card", "vpa") ||
                candidate.value["identifier"].isNullOrBlank()
            ) {
                fail("selector_candidate_metadata_invalid")
            }
            SmsCandidateKind.COUNTERPARTY -> if (candidate.value["surface"].isNullOrBlank()) {
                fail("selector_candidate_metadata_invalid")
            }
            else -> Unit
        }
    }

    private fun duplicateKeys(raw: String): Set<String> {
        val pattern = Regex("\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"\\s*:")
        val seen = mutableSetOf<String>()
        val duplicates = mutableSetOf<String>()
        pattern.findAll(raw).forEach { match ->
            val key = runCatching {
                JSONObject("{${match.value}null}").keys().next()
            }.getOrNull() ?: return@forEach
            if (!seen.add(key)) duplicates += key
        }
        return duplicates
    }

    private fun fail(reason: String): Nothing = throw GroundedSelectorValidationException(reason)
}
