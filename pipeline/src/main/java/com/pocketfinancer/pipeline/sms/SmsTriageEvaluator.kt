package com.pocketfinancer.pipeline.sms

enum class SmsStorageDisposition { INVOKE, DISCARD, RETAIN_REVIEW }
enum class SmsSelectorAction { RUN_NORMAL, RUN_ASSISTIVE, SKIP }

data class SmsTriageDecision(
    val disposition: SmsStorageDisposition,
    val selectorAction: SmsSelectorAction,
    val reasonCodes: List<String>
)

class SmsTriageEvaluator {
    fun evaluate(analysis: SmsAnalysis): SmsTriageDecision {
        val reasons = analysis.reasonCodes.toMutableSet()
        val directions = analysis.candidates.filter { it.kind == SmsCandidateKind.DIRECTION }
        val amounts = analysis.candidates.filter { it.kind == SmsCandidateKind.AMOUNT }
        val completedClauses = directions.mapNotNull { it.clauseId }.toSet()
        val completeClauses = completedClauses.intersect(amounts.mapNotNull { it.clauseId }.toSet())
        fun result(
            disposition: SmsStorageDisposition,
            action: SmsSelectorAction,
            reason: String
        ): SmsTriageDecision {
            reasons += reason
            return SmsTriageDecision(disposition, action, reasons.sorted())
        }
        if ("invalid_input" in reasons) {
            return result(SmsStorageDisposition.DISCARD, SmsSelectorAction.SKIP, "discard_invalid_input")
        }
        if (completedClauses.isEmpty()) {
            if (reasons.any {
                    it in setOf(
                        "pending_event", "expected_refund_not_posted",
                        "authorization_or_hold_not_posted"
                    )
                }
            ) {
                return result(
                    SmsStorageDisposition.RETAIN_REVIEW,
                    SmsSelectorAction.SKIP,
                    "review_uncertain_financial_state"
                )
            }
            if ("credential_otp" in reasons) {
                return result(
                    SmsStorageDisposition.DISCARD,
                    SmsSelectorAction.SKIP,
                    "discard_standalone_credential_otp"
                )
            }
            if ("request_or_authorization" in reasons) {
                return result(
                    SmsStorageDisposition.DISCARD,
                    SmsSelectorAction.SKIP,
                    "discard_unapproved_request"
                )
            }
            if ("non_posted_failure" in reasons) {
                return result(
                    SmsStorageDisposition.DISCARD,
                    SmsSelectorAction.SKIP,
                    "discard_explicit_non_posted_movement"
                )
            }
            if (reasons.isNotEmpty() && reasons.all { it == "balance_information" }) {
                return result(
                    SmsStorageDisposition.DISCARD,
                    SmsSelectorAction.SKIP,
                    "discard_unambiguous_standalone_non_event"
                )
            }
            return result(
                SmsStorageDisposition.RETAIN_REVIEW,
                SmsSelectorAction.SKIP,
                "review_no_completed_event_candidate"
            )
        }
        if (directions.size > 1) {
            return result(
                SmsStorageDisposition.RETAIN_REVIEW,
                if (completeClauses.isEmpty()) SmsSelectorAction.SKIP else SmsSelectorAction.RUN_ASSISTIVE,
                "review_multiple_completed_event_candidates"
            )
        }
        if (completeClauses.isEmpty()) {
            return result(
                SmsStorageDisposition.RETAIN_REVIEW,
                SmsSelectorAction.SKIP,
                "review_missing_core_candidate"
            )
        }
        val conflicts = setOf(
            "conflicting_currencies", "ambiguous_currency_marker", "unsupported_currency_code",
            "pending_event", "request_or_authorization", "non_posted_failure"
        )
        if (reasons.any { it in conflicts }) {
            return result(
                SmsStorageDisposition.RETAIN_REVIEW,
                SmsSelectorAction.RUN_ASSISTIVE,
                "review_conflicting_or_ambiguous_context"
            )
        }
        return result(
            SmsStorageDisposition.INVOKE,
            SmsSelectorAction.RUN_NORMAL,
            "invoke_grounded_single_event"
        )
    }
}
