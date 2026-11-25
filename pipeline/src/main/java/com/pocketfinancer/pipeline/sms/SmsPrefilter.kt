package com.pocketfinancer.pipeline.sms

/**
 * Pure, deterministic, strictly fail-open SMS prefilter (`sms-prefilter/1`).
 *
 * Parity port of `pocketfinancer_sms/prefilter.py`: excludes unambiguously
 * non-transactional alerts before local model execution while letting any
 * uncertainty or completed-movement cue pass through to the SLM.
 */
object SmsPrefilter {
    const val POLICY_ID = "sms-prefilter/1"

    enum class Decision(val wireValue: String) {
        PASS("pass"),
        EXCLUDE("exclude")
    }

    data class Result(
        val decision: Decision,
        val reason: String
    ) {
        val passed: Boolean get() = decision == Decision.PASS
        val excluded: Boolean get() = decision == Decision.EXCLUDE
        val traceReasonCode: String get() = "prefilter_$reason"
    }

    private val FLAGS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

    private val HAS_DIGIT = Regex("""\d""")

    private val FUTURE_OR_CONDITIONAL = Regex(
        """(?:\b(?:once\s+approved|on\s+approving\s+the\s+request)\b[^.!?\n]*|\b(?:will|shall|may|might|would|should|scheduled\s+to|set\s+to|about\s+to|going\s+to|yet\s+to|not(?:\s+been)?)\s+(?:be\s+)?(?:debited|credited|spent|deducted|withdrawn|deposited|transferred|refunded|reversed|charged)\b)""",
        FLAGS
    )

    private val COMPLETED_GUARD = Regex(
        """\b(?:debited|credited|spent|deducted|withdrawn|deposited|transferred|refunded|reversed|autopaid|auto-?debited|sent\s+(?:rs|inr|₹)|money\s+transfer|payment\s+of\s+(?:rs\.?|inr|₹).*(?:successful|received)|transaction\s+of\s+(?:rs\.?|inr|₹).*successful|has\s+been\s+received|received\s+towards|without\s+otp|txn\s+(?:rs\.?|inr|₹))\b""",
        FLAGS
    )

    private val GAMING_PROMO = Regex(
        """\b(?:rummy|my11circle|dream11|junglee\s*rummy|mpl\s*pro|winzo)\b""",
        FLAGS
    )

    private val COLLECT_OR_MANDATE_REQUEST = Regex(
        """(?:\bhas\s+requested\s+(?:rs\.?|inr|₹|money)\b|\bonce\s+approved,?\s*(?:money|the\s+amount)\s+will\s+be\s+debited\b|\bon\s+approving\s+the\s+request\b|\bupi[- ]mandate\s+request\b|\bcollect\s+request\b)""",
        FLAGS
    )

    private val OTP_VERIFICATION = Regex(
        """(?:\b(?:otp|one[- ]time[- ]password|verification\s+code|security\s+code|login\s+code|auth(?:entication)?\s+code)\b.*\b(?:is|for|:)\b|\b\d{4,8}\b\s+is\s+(?:your|the)\s+(?:otp|one[- ]time[- ]password|verification\s+code|security\s+code|secret\s+code|pin|mpin)|\botp\s*(?:is|:|-)\s*\d{4,8}\b|\buse\s+(?:otp\s*)?\d{4,8}\s+(?:as|to)\b)""",
        FLAGS
    )

    private val TELECOM_NOTICE = Regex(
        """(?:\bdaily\s+data\s+quota\s+used\b|\bhigh[- ]speed\s+data\b|\bplan\s+has\s+expired\b|प्लॅन\s+(?:समाप्त|लवकरच)|कॉलरट्यून्स|\bvi\s+super\s+hour\b|\bvi\s+exclusive\b|\bbinge\s+all\s+night\b|\bcaller\s*tune\b|\bmissed\s+call\s+alert\b|\brecharge\s+(?:now|today|with\s+rs|your\s+(?:jio|vi|airtel))\b|\bunlimited\s+pack\b.*\bexpir)""",
        FLAGS
    )

    private val BROKERAGE_EOD_REPORT = Regex(
        """(?:\breported\s+your\s+fund\s+bal(?:ance)?\b|\byour\s+traded\s+value\s+for\b.*national\s+stock\s+exchange|\btotal\s+amount\s+payable\s+to\s+you\s+at\s+the\s+end\s+of\s+date\b)""",
        FLAGS
    )

    private val FINANCIAL_CUE = Regex(
        """(?:rs\.?|inr|₹|usd|\$|eur|€|gbp|£|aed|sgd|aud|cad|jpy|¥|\b(?:bank|a/c|acct|account|card|upi|vpa|neft|imps|rtgs|wallet|paytm|phonepe|gpay|simpl|slice|onecard|cred|apay|amazon\s*pay|debited|credited|spent|paid|deducted|withdrawn|deposited|transferred|received|refund|reversed|txn|transaction|payment|bill|emi|mandate|autopay|balance|salary|dividend|sip|redemption|payout)\b)""",
        FLAGS
    )

    fun evaluate(body: String): Result {
        if (body.isBlank()) return Result(Decision.EXCLUDE, "empty")
        if (!HAS_DIGIT.containsMatchIn(body)) return Result(Decision.EXCLUDE, "no_digits")
        if (GAMING_PROMO.containsMatchIn(body)) return Result(Decision.EXCLUDE, "gaming_promo")

        val hasCompleted = COMPLETED_GUARD.containsMatchIn(
            FUTURE_OR_CONDITIONAL.replace(body, " ")
        )
        if (!hasCompleted && COLLECT_OR_MANDATE_REQUEST.containsMatchIn(body)) {
            return Result(Decision.EXCLUDE, "collect_or_mandate_request")
        }
        if (!hasCompleted && OTP_VERIFICATION.containsMatchIn(body)) {
            return Result(Decision.EXCLUDE, "otp_verification")
        }
        if (!hasCompleted && TELECOM_NOTICE.containsMatchIn(body)) {
            return Result(Decision.EXCLUDE, "telecom_notice")
        }
        if (BROKERAGE_EOD_REPORT.containsMatchIn(body)) {
            return Result(Decision.EXCLUDE, "brokerage_eod_report")
        }
        if (!FINANCIAL_CUE.containsMatchIn(body)) {
            return Result(Decision.EXCLUDE, "no_financial_cue")
        }
        return Result(Decision.PASS, "eligible")
    }
}
