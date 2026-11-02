package com.pocketfinancer.pipeline.sms

import com.pocketfinancer.data.repository.GroundedAccountResolution

class AutomaticPersistenceGate {
    fun evaluate(
        analysis: SmsAnalysis,
        triage: SmsTriageDecision,
        selection: GroundedSelectorResult,
        transaction: ReconstructedSmsTransaction?,
        accountResolution: GroundedAccountResolution,
        operation: SmsOperationSnapshot,
        claimOwnershipCurrent: Boolean
    ): PersistenceGateDecision {
        val contractValid = analysis.contract == "pocketfinancer.sms-analysis/2"
        val configurationHashValid = analysis.configurationHash == operation.configurationHash
        val selectorModeValid =
            operation.configuration.generationMode == "DIRECT_NON_THINKING" &&
                operation.configuration.decoding == "greedy"
        val posted = selection.decision == SelectorDecision.POSTED && transaction != null
        val completedEventCandidates = analysis.candidates
            .filter { it.kind == SmsCandidateKind.DIRECTION }
        val completedEventClauseCount = completedEventCandidates
            .mapNotNull { it.clauseId }
            .distinct()
            .size
        val exactlyOneEvent =
            analysis.completedEventCount == 1 &&
                completedEventCandidates.size == 1 &&
                completedEventClauseCount == 1
        val normalSelection = triage.selectorAction == SmsSelectorAction.RUN_NORMAL
        val triageInvokes = triage.disposition == SmsStorageDisposition.INVOKE
        val noNonPostedConflict = analysis.cues.none {
            it.kind in NON_POSTED_CONFLICT_CUES
        }
        val exactMoney = transaction?.let {
            it.minorUnits > 0 &&
                CurrencyProfileRegistry.scales[it.currency] == it.currencyScale
        } == true
        val currencyProvenanceApproved = transaction?.currencyProvenance in
            APPROVED_CURRENCY_PROVENANCE
        val selectedClauseId = selection.posted?.directionCandidateId?.let { selectedId ->
            analysis.candidates.firstOrNull {
                it.id == selectedId && it.kind == SmsCandidateKind.DIRECTION
            }?.clauseId
        }
        val supportedFamily = analysis.clauseAnnotations
            .filter { it.clauseId == selectedClauseId }
            .flatMap { it.financialFamilies }
            .map { it.family }
            .any { it in AUTOMATIC_FAMILIES }
        val accountPresent = transaction?.accountEvidence != null
        val uniqueAccount = accountResolution is GroundedAccountResolution.UniquelyResolved
        val timestampAccepted = transaction?.let {
            it.occurredAtEpochMs != null &&
                it.timestampProvenance in APPROVED_TIMESTAMP_PROVENANCE
        } == true
        val modeEnabled = operation.configuration.rolloutMode == "automatic"
        val checks = listOf(
            check(
                "analysis_contract_known",
                contractValid,
                "persistence_unknown_analysis_contract"
            ),
            check(
                "configuration_hash_valid",
                configurationHashValid,
                "persistence_configuration_hash_mismatch"
            ),
            check(
                "claim_ownership_current",
                claimOwnershipCurrent,
                "persistence_claim_ownership_invalid"
            ),
            check("selector_mode_valid", selectorModeValid, "persistence_selector_mode_invalid"),
            check("selector_posted", posted, "persistence_not_posted"),
            check("single_completed_event", exactlyOneEvent, "persistence_not_exactly_one_event"),
            check("exact_money", exactMoney, "persistence_invalid_money"),
            check(
                "currency_provenance_approved",
                currencyProvenanceApproved,
                "persistence_currency_provenance_not_approved"
            ),
            check(
                "timestamp_accepted",
                timestampAccepted,
                "persistence_timestamp_provenance_invalid"
            ),
            check(
                "account_present",
                accountPresent,
                "persistence_account_not_present"
            ),
            check(
                "account_uniquely_resolved",
                uniqueAccount,
                "persistence_account_not_uniquely_resolved"
            ),
            check(
                "supported_family",
                supportedFamily,
                "persistence_financial_family_not_supported"
            ),
            check(
                "triage_disposition",
                triageInvokes,
                "persistence_triage_requires_review"
            ),
            check(
                "normal_selection",
                normalSelection,
                "persistence_assistive_selection_not_eligible"
            ),
            check(
                "non_posted_conflict",
                noNonPostedConflict,
                "persistence_conflicting_non_posted_evidence"
            ),
            check(
                "automatic_rollout_enabled",
                modeEnabled,
                "persistence_blocked_by_rollout_mode"
            )
        )
        val failed = checks.filterNot { it.passed }
        val primaryReason = failed.firstOrNull()?.reasonCode
            ?: "persistence_all_gates_passed"
        val integrityChecks = setOf(
            "analysis_contract_known",
            "configuration_hash_valid",
            "claim_ownership_current"
        )
        val result = when {
            failed.any { it.check in integrityChecks } -> PersistenceGateResult.INVALID_OPERATION
            selection.decision == SelectorDecision.NONE -> PersistenceGateResult.NOT_POSTED
            analysis.completedEventCount > 1 ||
                completedEventCandidates.size > 1 ||
                completedEventClauseCount > 1 ->
                PersistenceGateResult.MULTIPLE_EVENTS
            failed.any { it.check != "automatic_rollout_enabled" } ->
                PersistenceGateResult.REVIEW_REQUIRED
            failed.isNotEmpty() -> PersistenceGateResult.BLOCKED_BY_MODE
            else -> PersistenceGateResult.ELIGIBLE
        }
        return decision(result, primaryReason, checks)
    }

    private fun check(name: String, passed: Boolean, failureReason: String) =
        PersistenceGateCheck(name, passed, if (passed) null else failureReason)

    private fun decision(
        result: PersistenceGateResult,
        reason: String,
        checks: List<PersistenceGateCheck>
    ) = PersistenceGateDecision(result, reason, checks)

    private companion object {
        val APPROVED_CURRENCY_PROVENANCE = setOf(
            "explicit_code",
            "explicit_unambiguous_symbol_or_marker",
            "user_primary_default"
        )
        val APPROVED_TIMESTAMP_PROVENANCE = setOf(
            "source_supplied_transaction_time",
            "acquisition_supplied_message_time"
        )
        val AUTOMATIC_FAMILIES = setOf(
            "bank_transfer",
            "bill_payment",
            "card_purchase",
            "cash_deposit",
            "cash_withdrawal",
            "fee_charge",
            "interest",
            "merchant_payment",
            "refund",
            "salary_income",
            "upi_transfer"
        )
        val NON_POSTED_CONFLICT_CUES = setOf(
            "failure",
            "negation",
            "pending",
            "request",
            "expectation",
            "authorization_hold"
        )
    }
}
