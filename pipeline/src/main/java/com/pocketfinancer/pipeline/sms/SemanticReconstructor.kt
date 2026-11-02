package com.pocketfinancer.pipeline.sms

class SemanticReconstructor {
    fun reconstruct(
        selection: GroundedSelectorResult,
        analysis: SmsAnalysis,
        operation: SmsOperationSnapshot
    ): ReconstructedSmsTransaction {
        val selected = selection.posted
            ?: throw IllegalArgumentException("reconstruction_not_posted")
        require(selection.decision == SelectorDecision.POSTED) { "reconstruction_not_posted" }
        val candidates = analysis.candidates.associateBy { it.id }
        val amount = candidates[selected.amountCandidateId]
            ?: throw IllegalArgumentException("reconstruction_candidate_missing")
        val direction = candidates[selected.directionCandidateId]
            ?: throw IllegalArgumentException("reconstruction_candidate_missing")
        val account = candidates[selected.accountCandidateId]
            ?: throw IllegalArgumentException("reconstruction_candidate_missing")
        val counterparty = candidates[selected.counterpartyCandidateId]
            ?: throw IllegalArgumentException("reconstruction_candidate_missing")
        val minorUnits = amount.value["minor_units"]?.toLongOrNull()
            ?.takeIf { it > 0 } ?: throw IllegalArgumentException("reconstruction_invalid_money")
        val currency = amount.value["currency"]
            ?: throw IllegalArgumentException("reconstruction_invalid_money")
        val scale = CurrencyProfileRegistry.scales[currency]
            ?: throw IllegalArgumentException("reconstruction_invalid_money")
        val directionValue = direction.value["direction"]
            ?.takeIf { it == "debit" || it == "credit" }
            ?: throw IllegalArgumentException("reconstruction_invalid_direction")
        return ReconstructedSmsTransaction(
            analysisId = analysis.analysisId,
            stableEventId = operation.stableEventId,
            minorUnits = minorUnits,
            currency = currency,
            currencyScale = scale,
            currencyProvenance = amount.value["currency_provenance"] ?: "unknown",
            direction = directionValue,
            accountEvidence = account.value["identifier"],
            counterpartyEvidence = counterparty.value["surface"],
            occurredAtEpochMs = operation.configuration.sourceTimestampEpochMs,
            timestampProvenance = operation.configuration.sourceTimestampProvenance
        )
    }
}
