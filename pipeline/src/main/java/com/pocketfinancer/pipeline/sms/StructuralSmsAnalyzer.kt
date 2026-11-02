package com.pocketfinancer.pipeline.sms

import com.pocketfinancer.data.repository.SmsProcessingStore
import org.json.JSONArray
import org.json.JSONObject

class StructuralSmsAnalyzer {
    private val money = Regex(
        "(?<![A-Za-z])(?:(?<code>AED|AUD|CAD|CHF|EUR|GBP|INR|JPY|SGD|USD)|" +
            "(?<marker>₹|Rs\\.?|INR))\\s*[:.-]?\\s*" +
            "(?<number>(?:\\d{1,3}(?:,\\d{2})+,\\d{3}|" +
            "\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,3})?)",
        RegexOption.IGNORE_CASE
    )
    private val directions = listOf(
        Regex("\\b(?:has\\s+been\\s+|was\\s+|is\\s+)?(?:debited|deducted|withdrawn|spent|paid|charged)\\b", RegexOption.IGNORE_CASE) to "debit",
        Regex("\\b(?:has\\s+been\\s+|was\\s+|is\\s+)?(?:credited|deposited|received|refunded)\\b", RegexOption.IGNORE_CASE) to "credit"
    )
    private val accounts = listOf(
        "bank_account" to Regex(
            "\\b(?:a/?c|acct|account)\\s*(?:no\\.?\\s*)?" +
                "(?:ending\\s*(?:in|with)?\\s*)?" +
                "(?<identifier>(?:[xX*•-]{2,}\\s*)?\\d{3,8})\\b",
            RegexOption.IGNORE_CASE
        ),
        "card" to Regex(
            "\\b(?:credit\\s+|debit\\s+)?card\\s*" +
                "(?:ending\\s*(?:in|with)?\\s*)?" +
                "(?<identifier>(?:[xX*•-]{2,}\\s*)?\\d{3,8})\\b",
            RegexOption.IGNORE_CASE
        ),
        "vpa" to Regex(
            "\\b(?<identifier>[A-Z0-9._-]{2,}@[A-Z][A-Z0-9.-]{1,})\\b",
            RegexOption.IGNORE_CASE
        )
    )
    private val counterparty = Regex(
        "\\b(?:at|to|from|by)\\s+(?<name>[A-Z0-9]" +
            "(?:[A-Z0-9&._@/-]*[A-Z0-9&_@/-])?" +
            "(?:\\s+[A-Z0-9](?:[A-Z0-9&._@/-]*[A-Z0-9&_@/-])?){0,4})",
        RegexOption.IGNORE_CASE
    )
    private val cuePatterns = listOf(
        CuePattern(
            "failure", "non_posted_failure",
            Regex("\\b(?:failed|declined|rejected|unsuccessful|could\\s+not\\s+be\\s+processed)\\b", RegexOption.IGNORE_CASE)
        ),
        CuePattern(
            "negation", "negated_movement",
            Regex("\\b(?:not\\s+(?:(?:been|be)\\s+)?(?:debited|credited|charged|processed)|no\\s+money\\s+(?:was\\s+)?(?:debited|credited))\\b", RegexOption.IGNORE_CASE),
            blocksDirection = true
        ),
        CuePattern(
            "pending", "pending_event",
            Regex("\\b(?:pending|processing|in\\s+progress|(?:will|may|scheduled\\s+to|set\\s+to)\\s+(?:be\\s+)?(?:debited|credited|charged|paid))\\b", RegexOption.IGNORE_CASE),
            blocksDirection = true
        ),
        CuePattern(
            "due", "amount_due",
            Regex("\\b(?:amount\\s+due|payment\\s+due|minimum\\s+due|due\\s+date)\\b", RegexOption.IGNORE_CASE)
        ),
        CuePattern(
            "request", "request_or_authorization",
            Regex("\\b(?:collect\\s+request|payment\\s+request|approve|authorize|mandate\\s+request)\\b", RegexOption.IGNORE_CASE),
            blocksDirection = true
        ),
        CuePattern(
            "balance", "balance_information",
            Regex("\\b(?:available|avail|avl|current|closing)\\s+(?:a/?c\\s+)?bal(?:ance)?\\b", RegexOption.IGNORE_CASE)
        ),
        CuePattern(
            "promotion", "promotion",
            Regex("\\b(?:offer|cashback\\s+offer|discount|sale|apply\\s+now|limited\\s+time)\\b", RegexOption.IGNORE_CASE)
        ),
        CuePattern(
            "administrative", "administrative",
            Regex("\\b(?:statement\\s+generated|kyc|profile\\s+updated|nomination|registered)\\b", RegexOption.IGNORE_CASE)
        ),
        CuePattern(
            "credential_otp", "credential_otp",
            Regex("\\b(?:otp|one[- ]time\\s+password|verification\\s+code|login\\s+code|passcode)\\b", RegexOption.IGNORE_CASE)
        ),
        CuePattern(
            "expectation", "expected_refund_not_posted",
            Regex("\\b(?:(?:refund|reversal)(?:\\s+of\\s+(?:[a-z]{3}\\s+)?[\\d,.]+)?\\s+(?:is\\s+|was\\s+)?(?:expected|anticipated|promised)|(?:expect|expected|anticipate|anticipated)\\s+(?:a\\s+)?(?:refund|reversal)|(?:will|may|scheduled\\s+to|set\\s+to)\\s+(?:be\\s+)?(?:refunded|reversed))\\b", RegexOption.IGNORE_CASE),
            blocksDirection = true
        ),
        CuePattern(
            "authorization_hold", "authorization_or_hold_not_posted",
            Regex("\\b(?:authorization\\s+hold|pre[- ]?authori[sz](?:ation|ed)?|(?:payment|charge|transaction|amount)\\s+(?:is\\s+|was\\s+|has\\s+been\\s+)?authori[sz]ed|(?:temporary\\s+)?hold\\s+(?:of|for|on)|(?:payment|charge|transaction|amount)\\s+(?:is\\s+|was\\s+|has\\s+been\\s+)?held)\\b", RegexOption.IGNORE_CASE),
            blocksDirection = true
        )
    )

    fun analyze(source: String, operation: SmsOperationSnapshot): SmsAnalysis = analyze(
        source = source,
        operationId = operation.operationId,
        configurationHash = operation.configurationHash,
        primaryCurrency = operation.configuration.primaryCurrency,
        enabledProfiles = operation.configuration.enabledProfiles,
        sourceTimestampEpochMs = operation.configuration.sourceTimestampEpochMs,
        sourceTimestampProvenance = operation.configuration.sourceTimestampProvenance
    )

    fun analyze(source: String, operation: SmsV4OperationSnapshot): SmsAnalysis = analyze(
        source = source,
        operationId = operation.operationId,
        configurationHash = operation.configurationHash,
        primaryCurrency = operation.configuration.primaryCurrency,
        enabledProfiles = operation.configuration.enabledProfiles,
        sourceTimestampEpochMs = operation.configuration.receivedTimestampEpochMs,
        sourceTimestampProvenance = operation.configuration.receivedTimestampProvenance
    )

    fun analyze(source: String, operation: SmsV5OperationSnapshot): SmsAnalysis = analyze(
        source = source,
        operationId = operation.operationId,
        configurationHash = operation.configurationHash,
        primaryCurrency = operation.configuration.primaryCurrency,
        enabledProfiles = operation.configuration.enabledProfiles,
        sourceTimestampEpochMs = operation.configuration.receivedTimestampEpochMs,
        sourceTimestampProvenance = operation.configuration.receivedTimestampProvenance
    )

    private fun analyze(
        source: String,
        operationId: String,
        configurationHash: String,
        primaryCurrency: String,
        enabledProfiles: List<String>,
        sourceTimestampEpochMs: Long?,
        sourceTimestampProvenance: String
    ): SmsAnalysis {
        val sourceHash = SmsProcessingStore.sha256(source)
        val analysisIdentity = operationId + "\u0000" + sourceHash + "\u0000" +
            configurationHash + "\u0000pocketfinancer.structural-sms-analyzer/2"
        val analysisId = SmsProcessingStore.sha256(analysisIdentity).take(24)
        val structuralView = SmsStructuralView(source)
        val clauses = StructuralClauseSegmenter.split(source)
        val reasons = sortedSetOf<String>()
        val blockedClauses = mutableSetOf<String>()
        val cues = mutableListOf<SmsCue>()
        for (item in cuePatterns) {
            for (match in structuralView.findAll(item.pattern)) {
                reasons += item.reasonCode
                val evidence = match.evidence() ?: continue
                val clauseId = StructuralClauseSegmenter.clauseId(evidence, clauses) ?: "cl_unknown"
                cues += cue(item.kind, item.reasonCode, clauseId, evidence, analysisId)
                if (item.blocksDirection && clauseId != "cl_unknown") blockedClauses += clauseId
            }
        }
        val candidates = mutableListOf<SmsCandidate>()
        for (match in structuralView.findAll(money)) {
            val code = match.normalizedGroup("code")?.uppercase()
                ?: primaryCurrency
            val provenance = if (match.normalizedGroup("code") == null) {
                "explicit_unambiguous_symbol_or_marker"
            } else {
                "explicit_code"
            }
            val parsed = CurrencyProfileRegistry.parse(
                match.normalizedGroup("number")!!,
                code,
                provenance
            ) ?: continue
            val evidence = match.evidence() ?: continue
            candidates += candidate(
                source,
                evidence,
                SmsCandidateKind.AMOUNT,
                mapOf(
                    "minor_units" to parsed.minorUnits.toString(),
                    "currency" to parsed.currency,
                    "currency_provenance" to parsed.provenance
                ),
                analysisId,
                clauses
            )
        }
        for ((pattern, direction) in directions) {
            for (match in structuralView.findAll(pattern)) {
                val evidence = match.evidence() ?: continue
                val clauseId = StructuralClauseSegmenter.clauseId(evidence, clauses)
                if (clauseId == null || clauseId !in blockedClauses) {
                    candidates += candidate(
                        source,
                        evidence,
                        SmsCandidateKind.DIRECTION,
                        mapOf("direction" to direction),
                        analysisId,
                        clauses
                    )
                }
            }
        }
        for ((accountType, pattern) in accounts) {
            for (match in structuralView.findAll(pattern)) {
                val evidence = match.evidence() ?: continue
                val identifier = match.evidence("identifier")?.text ?: continue
                candidates += candidate(
                    source, evidence, SmsCandidateKind.ACCOUNT,
                    mapOf(
                        "account_type" to accountType,
                        "identifier" to identifier
                    ),
                    analysisId, clauses
                )
            }
        }
        for (match in structuralView.findAll(counterparty)) {
            val normalizedName = match.normalizedGroup("name") ?: continue
            if (normalizedName.split(Regex("\\s+")).all { it in COUNTERPARTY_STOP_WORDS }) {
                continue
            }
            val evidence = match.evidence("name") ?: continue
            candidates += candidate(
                source, evidence, SmsCandidateKind.COUNTERPARTY,
                mapOf("surface" to evidence.text), analysisId, clauses
            )
        }
        candidates += absence(SmsCandidateKind.ACCOUNT, analysisId)
        candidates += absence(SmsCandidateKind.COUNTERPARTY, analysisId)
        require(candidates.distinctBy { it.id }.size == candidates.size) {
            "candidate identifier collision"
        }
        val directionsFound = candidates.filter { it.kind == SmsCandidateKind.DIRECTION }
        if (candidates.any { it.kind == SmsCandidateKind.AMOUNT }) {
            reasons += "amount_candidate_present"
        }
        if (directionsFound.isNotEmpty()) reasons += "completed_direction_candidate_present"
        if (source.isBlank()) reasons += "invalid_input"
        val annotations = clauseAnnotations(structuralView, clauses, candidates, cues)
        return SmsAnalysis(
            analysisId = analysisId,
            configurationHash = configurationHash,
            sourceHash = sourceHash,
            source = source,
            clauses = clauses,
            candidates = candidates,
            cues = cues,
            reasonCodes = reasons.toList(),
            completedEventCount = directionsFound.size,
            profileId = enabledProfiles.joinToString("+"),
            primaryCurrency = primaryCurrency,
            normalizedStructuralFingerprint = SmsProcessingStore.sha256(structuralView.normalized),
            currencyContextHash = currencyContextHash(primaryCurrency, enabledProfiles),
            sourceTimestampEpochMs = sourceTimestampEpochMs,
            sourceTimestampProvenance = sourceTimestampProvenance,
            unicodeDatabaseVersion = "14.0.0",
            clauseAnnotations = annotations
        )
    }

    private fun candidate(
        source: String,
        evidence: SmsEvidenceSpan,
        kind: SmsCandidateKind,
        value: Map<String, String>,
        analysisId: String,
        clauses: List<SmsClause>
    ): SmsCandidate {
        val valueJson = if (kind == SmsCandidateKind.AMOUNT) {
            val currency = canonicalQuote(value.getValue("currency"))
            val provenance = canonicalQuote(value.getValue("currency_provenance"))
            val minorUnits = value.getValue("minor_units").toLong()
            "{\"currency\":$currency,\"currency_provenance\":$provenance," +
                "\"minor_units\":$minorUnits}"
        } else {
            value.toSortedMap().entries.joinToString(
                prefix = "{", postfix = "}", separator = ","
            ) { (key, item) -> "${canonicalQuote(key)}:${canonicalQuote(item)}" }
        }
        val material = "$analysisId|${kind.wireValue}|" +
            "${evidence.startCodePoint}:${evidence.endCodePoint}|$valueJson"
        return SmsCandidate(
            id = "${kind.prefix}_${SmsProcessingStore.sha256(material).take(12)}",
            kind = kind,
            clauseId = StructuralClauseSegmenter.clauseId(evidence, clauses),
            evidence = evidence,
            explicitAbsence = false,
            value = value,
            context = emptyList()
        )
    }

    private fun absence(kind: SmsCandidateKind, analysisId: String): SmsCandidate {
        val value = mapOf("state" to "absent")
        val material = "$analysisId|${kind.wireValue}|absent|{\"state\":\"absent\"}"
        return SmsCandidate(
            id = "${kind.prefix}_${SmsProcessingStore.sha256(material).take(12)}",
            kind = kind,
            clauseId = null,
            evidence = null,
            explicitAbsence = true,
            value = value,
            context = listOf("explicit_absence")
        )
    }

    private fun cue(
        kind: String,
        reasonCode: String,
        clauseId: String,
        evidence: SmsEvidenceSpan,
        analysisId: String
    ): SmsCue {
        val material = "$analysisId|cue|$kind|${evidence.startCodePoint}|${evidence.endCodePoint}"
        return SmsCue(
            id = "q_${SmsProcessingStore.sha256(material).take(12)}",
            kind = kind,
            clauseId = clauseId,
            evidence = evidence,
            reasonCode = reasonCode
        )
    }

    private fun clauseAnnotations(
        structuralView: SmsStructuralView,
        clauses: List<SmsClause>,
        candidates: List<SmsCandidate>,
        cues: List<SmsCue>
    ): List<SmsClauseAnnotation> {
        val stateByCue = mapOf(
            "failure" to "failed", "negation" to "failed", "pending" to "pending",
            "due" to "due", "request" to "request", "expectation" to "expectation",
            "authorization_hold" to "authorization", "credential_otp" to "security"
        )
        val states = clauses.associate { it.id to sortedSetOf<String>() }
        cues.forEach { cue ->
            stateByCue[cue.kind]?.let { state -> states[cue.clauseId]?.add(state) }
        }
        candidates.filter { it.kind == SmsCandidateKind.DIRECTION }.forEach { candidate ->
            candidate.clauseId?.let { states[it]?.add("completed") }
        }
        val families = clauses.associate { it.id to mutableListOf<SmsFinancialFamily>() }
        for ((family, pattern) in FAMILY_PATTERNS) {
            for (match in structuralView.findAll(pattern)) {
                val evidence = match.evidence() ?: continue
                val clauseId = StructuralClauseSegmenter.clauseId(evidence, clauses)
                    ?: continue
                families.getValue(clauseId) += SmsFinancialFamily(family, evidence)
            }
        }
        return clauses.map { clause ->
            SmsClauseAnnotation(
                clauseId = clause.id,
                states = states.getValue(clause.id).ifEmpty { sortedSetOf("unknown") }.toList(),
                financialFamilies = families.getValue(clause.id)
            )
        }
    }

    private fun currencyContextHash(primaryCurrency: String, enabledProfiles: List<String>): String {
        val profiles = JSONArray()
        enabledProfiles.forEach { profileId ->
            profiles.put(JSONObject().put("profile_id", profileId).put("revision", 1))
        }
        return SmsProcessingStore.sha256(
            CanonicalAndroidJson.stringify(
                JSONObject()
                    .put("primary_currency", primaryCurrency)
                    .put("profiles", profiles)
            )
        )
    }

    private fun canonicalQuote(value: String): String {
        val quoted = JSONObject.quote(value)
        val output = StringBuilder(quoted.length)
        var index = 0
        while (index < quoted.length) {
            val codePoint = quoted.codePointAt(index)
            if (codePoint <= 0x7f) {
                output.appendCodePoint(codePoint)
            } else if (codePoint <= 0xffff) {
                output.append("\\u").append(codePoint.toString(16).padStart(4, '0'))
            } else {
                val adjusted = codePoint - 0x10000
                val high = 0xd800 + (adjusted shr 10)
                val low = 0xdc00 + (adjusted and 0x3ff)
                output.append("\\u").append(high.toString(16).padStart(4, '0'))
                output.append("\\u").append(low.toString(16).padStart(4, '0'))
            }
            index += Character.charCount(codePoint)
        }
        return output.toString()
    }

    private companion object {
        data class CuePattern(
            val kind: String,
            val reasonCode: String,
            val pattern: Regex,
            val blocksDirection: Boolean = false
        )
        val FAMILY_PATTERNS = listOf(
            "refund" to Regex("\\b(?:refund|refunded|reversal|reversed)\\b", RegexOption.IGNORE_CASE),
            "wallet" to Regex("\\bwallet\\b", RegexOption.IGNORE_CASE),
            "cash_withdrawal" to Regex("\\b(?:cash\\s+withdrawal|withdrawn|atm)\\b", RegexOption.IGNORE_CASE),
            "cash_deposit" to Regex("\\b(?:cash\\s+deposit|deposited)\\b", RegexOption.IGNORE_CASE),
            "fee_charge" to Regex("\\b(?:fee|fees|service\\s+charge)\\b", RegexOption.IGNORE_CASE),
            "salary_income" to Regex("\\bsalary\\b", RegexOption.IGNORE_CASE),
            "upi_transfer" to Regex("\\b(?:upi|vpa)\\b", RegexOption.IGNORE_CASE),
            "bank_transfer" to Regex("\\b(?:transfer|imps|neft|rtgs|nach)\\b", RegexOption.IGNORE_CASE),
            "card_purchase" to Regex("\\b(?:card\\s+purchase|purchase\\s+on\\s+(?:your\\s+)?card)\\b", RegexOption.IGNORE_CASE),
            "merchant_payment" to Regex("\\b(?:merchant\\s+payment|purchase|spent|paid)\\b", RegexOption.IGNORE_CASE)
        )
        val COUNTERPARTY_STOP_WORDS = setOf("your", "the", "a", "an", "account", "card", "bank")
    }
}
