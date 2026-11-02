package com.pocketfinancer.data.repository

import com.pocketfinancer.data.db.entity.SmsReviewCaseV2ExtensionEntity
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class SmsReviewSourceSpan(
    val startScalar: Int,
    val endScalar: Int,
    val text: String
)

data class SmsReviewProposal(
    val amountMinorUnits: Long,
    val currency: String,
    val direction: String,
    val accountReference: String,
    val counterparty: String?,
    val amountSpan: SmsReviewSourceSpan,
    val directionSpan: SmsReviewSourceSpan,
    val accountSpan: SmsReviewSourceSpan,
    val counterpartySpan: SmsReviewSourceSpan?,
    val receiptTimestampEpochMs: Long,
    val receiptProvenance: String,
    val accountStatus: String,
    val resolvedAccountId: String?,
    val duplicateStatus: String,
    val duplicateIdempotencyKey: String,
    val duplicateSourceEventKey: String,
    val transactionFingerprint: String
)

data class SmsReviewFieldEvidence(
    val field: String,
    val sourceSpan: SmsReviewSourceSpan,
    val normalizedValueJson: String?,
    val validationState: String,
    val originatingStage: String,
    val origin: String
)

data class SmsReviewAnalyzerSuggestion(
    val kind: String,
    val sourceSpan: SmsReviewSourceSpan?,
    val summary: String
)

data class SmsReviewRetainedEvidence(
    val furthestStage: String,
    val primaryCurrency: String,
    val receiptTimestampEpochMs: Long,
    val receiptProvenance: String,
    val slmFields: Map<String, SmsReviewFieldEvidence>,
    val analyzerSuggestions: List<SmsReviewAnalyzerSuggestion>
)

/**
 * Parses and revalidates the v4 review projection without trusting UI-provided
 * normalized values. Frozen offsets are Unicode-scalar offsets.
 */
object SmsReviewGrounding {
    fun retainedEvidence(
        extension: SmsReviewCaseV2ExtensionEntity?,
        operationConfigurationJson: String,
        source: String
    ): SmsReviewRetainedEvidence? = runCatching {
        val retained = extension ?: return null
        check(retained.contractVersion == "pocketfinancer.review-case/2")
        val configuration = JSONObject(operationConfigurationJson)
        val releaseId = configuration.getJSONObject("contract_release").getString("release_id")
        check(
            (releaseId == "native-integration-v5" &&
                configuration.getString("contract") == "pocketfinancer.processing-config/5") ||
                (releaseId == "native-integration-v6" &&
                    configuration.getString("contract") == "pocketfinancer.processing-config/6")
        )
        check(configuration.getJSONObject("persistence_policy").getString("rollout_mode") == "automatic")
        val receipt = configuration.getJSONObject("received_timestamp")
        check(receipt.getBoolean("read_only"))
        val receiptProvenance = receipt.getString("provenance")
        check(
            receiptProvenance in
                setOf("platform_received", "acquisition_supplied_message_time")
        )
        val fields = JSONArray(retained.fieldEvidenceJson)
        val modelFields = buildList {
            repeat(fields.length()) { index ->
                val item = fields.getJSONObject(index)
                val origin = item.getString("origin")
                check(origin in setOf("slm", "advisory_analyzer"))
                if (origin != "slm") return@repeat
                val field = item.getString("field")
                check(field in setOf("amount", "direction", "account", "counterparty"))
                val state = item.getString("validation_state")
                check(state in setOf("valid", "grounded_only"))
                add(
                    SmsReviewFieldEvidence(
                        field = field,
                        sourceSpan = item.getJSONObject("source_span").span(source),
                        normalizedValueJson = item.opt("normalized_value")
                            .takeUnless { it == null || it == JSONObject.NULL }
                            ?.toJsonLiteral(),
                        validationState = state,
                        originatingStage = item.getString("originating_stage"),
                        origin = origin
                    )
                )
            }
        }
        check(modelFields.map { it.field }.distinct().size == modelFields.size)
        val suggestions = JSONArray(retained.analyzerSuggestionsJson)
        val analyzer = buildList {
            repeat(suggestions.length()) { index ->
                val item = suggestions.getJSONObject(index)
                val kind = item.getString("kind")
                val span = (item.opt("span") as? JSONObject)?.span(source)
                val suggested = item.opt("suggested_interpretation")
                    .takeUnless { it == null || it == JSONObject.NULL }
                    ?.let(::suggestionSummary)
                    .orEmpty()
                val summary = span?.text?.takeIf(String::isNotBlank)
                    ?: suggested.takeIf(String::isNotBlank)
                    ?: return@repeat
                add(SmsReviewAnalyzerSuggestion(kind, span, summary))
            }
        }
        SmsReviewRetainedEvidence(
            furthestStage = retained.furthestStage,
            primaryCurrency = configuration.getJSONObject("currency_context")
                .getString("primary_currency"),
            receiptTimestampEpochMs = receipt.get("epoch_ms").exactLong(),
            receiptProvenance = receiptProvenance,
            slmFields = modelFields.associateBy { it.field },
            analyzerSuggestions = analyzer
        )
    }.getOrNull()

    fun proposal(resultJson: String?, source: String): SmsReviewProposal? = runCatching {
        val root = JSONObject(resultJson ?: return null)
        if (root.optString("contract") != "pocketfinancer.processing-result/3") return null
        if (root.optString("status") !in setOf("review", "blocked")) return null
        if (root.optString("recognition_decision") != "posted") return null
        val semantic = root.getJSONObject("semantic_result")
        val money = semantic.getJSONObject("money")
        val evidence = semantic.getJSONObject("evidence")
        val account = root.getJSONObject("account_resolution")
        val duplicate = root.getJSONObject("duplicate_assessment")
        val receipt = root.getJSONObject("receipt_timestamp")
        check(receipt.optBoolean("read_only"))
        val amountMinorUnits = money.get("minor_units").exactLong()
        val currency = money.getString("currency").uppercase(Locale.ROOT)
        val direction = semantic.getString("direction")
        val accountReference = semantic.getString("account_reference")
        val receiptTimestampEpochMs = receipt.get("epoch_ms").exactLong()
        val receiptProvenance = receipt.getString("provenance")
        check(
            receiptProvenance in
                setOf("platform_received", "acquisition_supplied_message_time")
        )
        val accountStatus = account.getString("status")
        check(accountStatus in setOf("unresolved", "ambiguous", "uniquely_resolved"))
        val accountMatchCount = account.get("match_count").exactLong()
        check(accountMatchCount in 0..Int.MAX_VALUE)
        check(
            listOf("account_id", "normalized_reference", "matched_alias_hash")
                .all(account::has)
        )
        val resolvedAccountId = account.optNullableString("account_id")
        val normalizedReference = account.optNullableString("normalized_reference")
        val matchedAliasHash = account.optNullableString("matched_alias_hash")
        check(account.getString("provenance") == "pocketfinancer.account-resolution-profile/1")
        val normalizedSemanticAccount = normalizeAccount(accountReference)
        val semanticAliasKey = if ('@' in normalizedSemanticAccount) {
            "vpa:$normalizedSemanticAccount"
        } else {
            "suffix:$normalizedSemanticAccount"
        }
        check(normalizedSemanticAccount.isNotBlank() && normalizedReference == semanticAliasKey)
        when (accountStatus) {
            "unresolved" -> check(
                accountMatchCount == 0L && resolvedAccountId == null && matchedAliasHash == null
            )
            "ambiguous" -> check(
                accountMatchCount >= 2L && resolvedAccountId == null && matchedAliasHash == null
            )
            "uniquely_resolved" -> check(
                accountMatchCount == 1L &&
                    !resolvedAccountId.isNullOrBlank() &&
                    !normalizedReference.isNullOrBlank() &&
                    matchedAliasHash.isSha256() &&
                    matchedAliasHash == SmsProcessingStore.sha256(normalizedReference)
            )
        }
        val duplicateStatus = duplicate.getString("status")
        check(duplicateStatus in setOf("clear", "possible_duplicate", "already_persisted"))
        val duplicateIdempotencyKey = duplicate.getString("idempotency_key")
        val duplicateSourceEventKey = duplicate.getString("source_event_key")
        val transactionFingerprint = duplicate.getString("transaction_fingerprint")
        check(duplicateIdempotencyKey.isNotBlank())
        check(duplicateSourceEventKey.isNotBlank())
        check(transactionFingerprint.isSha256())
        check(
            transactionFingerprint == SmsProcessingStore.sha256(
                "$amountMinorUnits\u0000$currency\u0000$direction\u0000" +
                    "${resolvedAccountId.orEmpty()}\u0000$receiptTimestampEpochMs"
            )
        )
        SmsReviewProposal(
            amountMinorUnits = amountMinorUnits,
            currency = currency,
            direction = direction,
            accountReference = accountReference,
            counterparty = semantic.optNullableString("counterparty"),
            amountSpan = evidence.getJSONObject("amount").span(source),
            directionSpan = evidence.getJSONObject("direction").span(source),
            accountSpan = evidence.getJSONObject("account").span(source),
            counterpartySpan = evidence.optJSONObject("counterparty")?.span(source),
            receiptTimestampEpochMs = receiptTimestampEpochMs,
            receiptProvenance = receiptProvenance,
            accountStatus = accountStatus,
            resolvedAccountId = resolvedAccountId,
            duplicateStatus = duplicateStatus,
            duplicateIdempotencyKey = duplicateIdempotencyKey,
            duplicateSourceEventKey = duplicateSourceEventKey,
            transactionFingerprint = transactionFingerprint
        ).also(::validateProjection)
    }.getOrNull()

    private fun String?.isSha256(): Boolean =
        this != null && matches(Regex("[0-9a-f]{64}"))

    fun applyCorrections(
        base: SmsReviewProposal,
        source: String,
        corrections: List<SmsFieldCorrection>
    ): SmsReviewProposal {
        var value = base
        corrections.forEach { correction ->
            when (correction.field) {
                "amount" -> {
                    val span = requireSpan(correction, source)
                    val minor = minorUnits(span.text, base.currency)
                    val declared = JSONObject(correction.newValueJson)
                    require(declared.getLong("minor_units") == minor)
                    require(declared.getString("currency").uppercase(Locale.ROOT) == base.currency)
                    value = value.copy(amountMinorUnits = minor, amountSpan = span)
                }
                "direction" -> {
                    val span = requireSpan(correction, source)
                    val direction = JSONObject("{\"value\":${correction.newValueJson}}")
                        .getString("value")
                    require(direction in setOf("debit", "credit"))
                    require(directionFrom(span.text) == direction)
                    value = value.copy(direction = direction, directionSpan = span)
                }
                "account" -> {
                    val span = requireSpan(correction, source)
                    val normalized = normalizeAccount(span.text)
                    require(normalized.isNotEmpty())
                    val declared = JSONObject(correction.newValueJson).getString("reference")
                    require(normalizeAccount(declared) == normalized)
                    value = value.copy(
                        accountReference = normalized,
                        accountSpan = span,
                        accountStatus = "unresolved",
                        resolvedAccountId = null
                    )
                }
                "counterparty" -> {
                    if (correction.newValueJson == "null") {
                        require(correction.evidenceJson == null)
                        value = value.copy(counterparty = null, counterpartySpan = null)
                    } else {
                        val span = requireSpan(correction, source)
                        val normalized = normalizeText(span.text)
                        require(normalized.isNotEmpty() && normalized.codePointCount(0, normalized.length) <= 256)
                        val declared = JSONObject("{\"value\":${correction.newValueJson}}")
                            .getString("value")
                        require(normalizeText(declared) == normalized)
                        value = value.copy(counterparty = normalized, counterpartySpan = span)
                    }
                }
                // Historical review commands remain supported by the legacy projector.
                else -> Unit
            }
        }
        validateProjection(value)
        return value
    }

    fun correctionsFromJson(json: String?): List<SmsFieldCorrection> {
        if (json.isNullOrBlank()) return emptyList()
        val array = JSONArray(json)
        return buildList {
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                add(
                    SmsFieldCorrection(
                        field = item.getString("field"),
                        classification = SmsFieldGroundingClassification.entries.firstOrNull {
                            it.wireValue == item.getString("classification")
                        } ?: SmsFieldGroundingClassification.SUPPLIED_SOURCE_SUPPORTED_CANDIDATE_MISS,
                        previousRevisionId = item.optNullableString("previous_revision_id"),
                        candidateId = item.optNullableString("candidate_id"),
                        evidenceJson = item.opt("evidence").takeUnless { it == null || it == JSONObject.NULL }
                            ?.let { JSONObject.wrap(it)?.toString() },
                        newValueJson = item.get("new_value").let { raw ->
                            when (raw) {
                                JSONObject.NULL -> "null"
                                is String -> JSONObject.quote(raw)
                                else -> raw.toString()
                            }
                        }
                    )
                )
            }
        }
    }

    fun span(source: String, startScalar: Int, endScalar: Int): SmsReviewSourceSpan {
        require(startScalar >= 0 && endScalar > startScalar)
        var scalar = 0
        var index = 0
        var startUtf16 = -1
        var endUtf16 = -1
        while (index < source.length) {
            if (scalar == startScalar) startUtf16 = index
            if (scalar == endScalar) {
                endUtf16 = index
                break
            }
            val char = source[index]
            require(!Character.isLowSurrogate(char))
            if (Character.isHighSurrogate(char)) {
                require(index + 1 < source.length && Character.isLowSurrogate(source[index + 1]))
            }
            index += Character.charCount(source.codePointAt(index))
            scalar++
        }
        if (scalar == endScalar && endUtf16 < 0) endUtf16 = index
        require(startUtf16 >= 0 && endUtf16 > startUtf16)
        return SmsReviewSourceSpan(startScalar, endScalar, source.substring(startUtf16, endUtf16))
    }

    fun scalarRange(source: String, startUtf16: Int, endUtf16: Int): IntRange? {
        if (startUtf16 < 0 || endUtf16 <= startUtf16 || endUtf16 > source.length) return null
        if (startUtf16 > 0 && Character.isLowSurrogate(source[startUtf16])) return null
        if (endUtf16 < source.length && Character.isLowSurrogate(source[endUtf16])) return null
        val start = source.codePointCount(0, startUtf16)
        val end = source.codePointCount(0, endUtf16)
        return if (end > start) start until end else null
    }

    fun utf16Range(source: String, span: SmsReviewSourceSpan): IntRange? {
        val verified = runCatching { this.span(source, span.startScalar, span.endScalar) }.getOrNull()
            ?: return null
        if (verified.text != span.text) return null
        val start = source.offsetByCodePoints(0, span.startScalar)
        val end = source.offsetByCodePoints(0, span.endScalar)
        return start until end
    }

    fun normalizeAccount(value: String): String {
        val normalized = normalizeText(value)
        Regex("[a-z0-9][a-z0-9._-]{1,}@[a-z0-9][a-z0-9.-]+")
            .find(normalized)?.value?.let { return it }
        val matches = Regex("(?:[xX*•-]{2,}\\s*)?[0-9]{3,8}").findAll(normalized).toList()
        if (matches.size != 1) return ""
        return matches.single().value.filter(Char::isDigit).takeIf { it.length in 3..8 }.orEmpty()
    }

    fun normalizeText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim().lowercase(Locale.ROOT).replace("ß", "ss").replace("ς", "σ")
        .replace(Regex("\\s+"), " ")

    fun directionFrom(text: String): String? {
        val normalized = normalizeText(text)
        val debit = listOf("debit", "debited", "deducted", "withdrawn", "spent", "paid", "charged")
        val credit = listOf("credit", "credited", "deposited", "received", "refunded")
        return when {
            debit.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(normalized) } -> "debit"
            credit.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(normalized) } -> "credit"
            else -> null
        }
    }

    fun minorUnits(text: String, currency: String): Long {
        val scale = CurrencyScaleRegistry.scale(currency) ?: error("Unsupported currency")
        val values = Regex("(?<![\\w,])(?:[0-9]{1,3}(?:,[0-9]{2})+,[0-9]{3}|[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?(?![\\w,])")
            .findAll(Normalizer.normalize(text, Normalizer.Form.NFKC)).map { it.value }.toList()
        require(values.size == 1)
        val decimal = BigDecimal(values.single().replace(",", ""))
        require(decimal.signum() > 0 && decimal.scale().coerceAtLeast(0) <= scale)
        return decimal.setScale(scale, RoundingMode.UNNECESSARY).movePointRight(scale).longValueExact()
    }

    private fun JSONObject.span(source: String): SmsReviewSourceSpan {
        val value = span(
            source,
            get("start_scalar").exactInt(),
            get("end_scalar").exactInt()
        )
        require(value.text == getString("text"))
        return value
    }

    private fun Any.toJsonLiteral(): String = when (this) {
        is String -> JSONObject.quote(this)
        is JSONObject, is JSONArray -> toString()
        is Boolean, is Int, is Long -> toString()
        else -> error("Unsupported retained field value")
    }

    private fun suggestionSummary(value: Any): String = when (value) {
        is String -> value
        is JSONObject -> value.keys().asSequence().toList().sorted()
            .joinToString(", ") { key -> "$key: ${value.opt(key)}" }
        else -> value.toString()
    }

    private fun requireSpan(
        correction: SmsFieldCorrection,
        source: String
    ): SmsReviewSourceSpan {
        val json = JSONObject(requireNotNull(correction.evidenceJson))
        return json.span(source)
    }

    private fun validateProjection(value: SmsReviewProposal) {
        require(value.amountMinorUnits > 0)
        require(CurrencyScaleRegistry.scale(value.currency) != null)
        require(value.direction in setOf("debit", "credit"))
        require(value.accountReference.isNotBlank())
        require(value.receiptTimestampEpochMs >= 0)
        require((value.counterparty == null) == (value.counterpartySpan == null))
        require(minorUnits(value.amountSpan.text, value.currency) == value.amountMinorUnits)
        require(directionFrom(value.directionSpan.text) == value.direction)
        require(normalizeAccount(value.accountSpan.text) == normalizeAccount(value.accountReference))
        if (value.counterparty != null) {
            require(normalizeText(requireNotNull(value.counterpartySpan).text) == normalizeText(value.counterparty))
        }
    }

    private fun Any.exactLong(): Long = when (this) {
        is Long -> this
        is Int -> toLong()
        else -> error("Expected an integer JSON number")
    }

    private fun Any.exactInt(): Int = when (this) {
        is Int -> this
        is Long -> {
            require(this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            toInt()
        }
        else -> error("Expected an integer JSON number")
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key).takeIf(String::isNotBlank)
}
