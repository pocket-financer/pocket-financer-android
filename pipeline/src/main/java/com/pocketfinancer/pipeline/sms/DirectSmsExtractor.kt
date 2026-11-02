package com.pocketfinancer.pipeline.sms

import com.pocketfinancer.inference.CandidateSelectorRuntimeProfile
import com.pocketfinancer.inference.DefaultDirectCandidateSelector
import com.pocketfinancer.inference.DirectCandidateSelectorRequest
import com.pocketfinancer.inference.DirectCandidateSelectorResult
import com.pocketfinancer.inference.SlmLease
import com.pocketfinancer.inference.SlmTokenCallback
import javax.inject.Inject
import org.json.JSONArray

/**
 * v3/v4 extraction transport. The analyzer payload is advisory context only;
 * validation always grounds the returned values in the complete immutable SMS.
 */
fun interface DirectSmsExtractor {
    suspend fun extract(lease: SlmLease, request: DirectSmsExtractorRequest): DirectCandidateSelectorResult
}

data class DirectSmsExtractorRequest(
    val source: String,
    val senderFamily: String,
    val primaryCurrency: String,
    val profileIds: List<String>,
    val advisoryEvidence: JSONArray,
    val prompt: String,
    val grammar: String,
    val jsonCallback: SlmTokenCallback? = null
) {
    fun payload(): String = CanonicalAndroidJson.stringify(
        org.json.JSONObject()
            .put("contract", "pocketfinancer.sms-extractor-input/1")
            .put("output_contract", "pocketfinancer.sms-extractor/1")
            .put("message", source)
            .put("sender_family", senderFamily)
            .put("primary_currency", primaryCurrency)
            .put("enabled_profile_ids", org.json.JSONArray(profileIds))
            .put("advisory_evidence", advisoryEvidence)
            .put("output_rules", org.json.JSONObject()
                .put("one_json_document", true)
                .put("decisions", org.json.JSONArray(listOf("none", "abstain", "posted")))
                .put("posted_required_fields", org.json.JSONArray(listOf("amount", "direction", "account", "counterparty")))
                .put("source_spans", "zero_based_half_open_unicode_scalars")
                .put("transaction_time_forbidden", true))
    )
}

class DefaultDirectSmsExtractor @Inject constructor(
    private val transport: DefaultDirectCandidateSelector
) : DirectSmsExtractor {
    override suspend fun extract(
        lease: SlmLease,
        request: DirectSmsExtractorRequest
    ): DirectCandidateSelectorResult = transport.select(
        lease,
        DirectCandidateSelectorRequest(
            prompt = request.prompt,
            candidatePayloadJson = request.payload(),
            grammar = request.grammar,
            profile = CandidateSelectorRuntimeProfile(),
            jsonCallback = request.jsonCallback
        )
    )
}
