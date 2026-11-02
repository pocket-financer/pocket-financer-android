package com.pocketfinancer.pipeline

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deterministic multi-stage SMS filtering pipeline to identify transactional messages.
 * Ported from the Python `new_pipeline.py` implementation in `pocket-financer-intelligence`.
 */
@Singleton
class SmsFilterPipeline @Inject constructor() {

    // Stage 0: Mobile number regex (for identifying personal/mobile senders to drop)
    private val mobileNumberRegex = Regex("""^\+?[0-9]{10,15}$""")

    // Stage 1: Presence of an amount
    private val amountRegex = Regex(
        """(?:rs\.?|inr|₹)\s*[\d,]+(?:\.\d{1,2})?|[\d,]+(?:\.\d{1,2})?\s*(?:rs\.?|inr|₹)""",
        RegexOption.IGNORE_CASE
    )

    // Stage 2: Masked account or card pattern
    private val acctRegex = Regex(
        """a/c\s*(?:no\.?\s*)?[X*x]+\d+|a/?c\s*(?:no\.?\s*)?\*+\d+|card\s*(?:no\.?\s*)?[Xx*]+\d+|card\s+\d{4}\b|card\s+ending\s+[Xx*]*\d+""",
        RegexOption.IGNORE_CASE
    )

    // Stage 3: Transaction action verb or phrase
    private val txnVerbRegex = Regex(
        """\b(?:debited|credited|deducted|spent|paid|received|transferred|sent|reversed|refunded|used|withdrawn|deposited)(?=[^a-zA-Z]|$)|""" +
        """\btxn\b|""" +
        """\bhas\s+(?:a\s+)?debit\s+by\b|""" +
        """\bhas\s+credit\s+for\b|""" +
        """\bwithout\s+OTP\b|""" +
        """\bauto.?debit\b|""" +
        """\bDebit\s+in\s+a/c\b|""" +
        """\btxn\s+of\s+Rs\b|""" +
        """\bRedemption\s+payout\b|""" +
        """\b(?:money\s+transfer|amt\s+sent|amt\s+received)\b|""" +
        """you've\s+hand-?picked""",
        RegexOption.IGNORE_CASE
    )

    // Stage 4: OTP exclusion pattern
    private val otpRegex = Regex(
        """\botp\b|\bone.?time.?password\b|\bverification.?code\b""",
        RegexOption.IGNORE_CASE
    )

    // Stage 5: Collect request exclusion pattern
    private val collectRegex = Regex(
        """has\s+requested\s+money|requested\s+Rs\.?|collect\s+request|mandate\s+request|request\s+from\s+you""",
        RegexOption.IGNORE_CASE
    )

    fun filterWithDetails(sender: String, text: String): FilterResult {
        val logs = mutableListOf<String>()

        // Stage 0: Drop personal mobile numbers
        val senderTrimmed = sender.trim()
        val isMobile = senderTrimmed.matches(mobileNumberRegex)
        logs.add("Stage 0 (Sender Check): sender='$senderTrimmed', isMobile=$isMobile -> ${if (isMobile) "REJECTED" else "PASSED"}")
        if (isMobile) return FilterResult(false, logs)

        // Stage 1: Amount Requirement
        val hasAmount = amountRegex.containsMatchIn(text)
        logs.add("Stage 1 (Amount Check): hasAmount=$hasAmount -> ${if (!hasAmount) "REJECTED" else "PASSED"}")
        if (!hasAmount) return FilterResult(false, logs)

        // Stage 2: Masked Account/Card Requirement
        val hasAcct = acctRegex.containsMatchIn(text)
        logs.add("Stage 2 (Account/Card Check): hasAccountOrCard=$hasAcct -> ${if (!hasAcct) "REJECTED" else "PASSED"}")
        if (!hasAcct) return FilterResult(false, logs)

        // Stage 3: Transaction Action Verb Requirement
        val hasVerb = txnVerbRegex.containsMatchIn(text)
        logs.add("Stage 3 (Verb Check): hasVerb=$hasVerb -> ${if (!hasVerb) "REJECTED" else "PASSED"}")
        if (!hasVerb) return FilterResult(false, logs)

        // Stage 4: OTP Exclusion
        val isOtp = otpRegex.containsMatchIn(text)
        logs.add("Stage 4 (OTP Exclusion): isOtp=$isOtp -> ${if (isOtp) "REJECTED" else "PASSED"}")
        if (isOtp) return FilterResult(false, logs)

        // Stage 5: Collect Request Exclusion
        val isCollect = collectRegex.containsMatchIn(text)
        logs.add("Stage 5 (Collect Request Exclusion): isCollect=$isCollect -> ${if (isCollect) "REJECTED" else "PASSED"}")
        if (isCollect) return FilterResult(false, logs)

        return FilterResult(true, logs)
    }

    /**
     * Determines whether an SMS is a valid transaction.
     *
     * @param sender The SMS sender address (e.g. "AX-HDFCBK" or "+919999999999")
     * @param text The body content of the SMS.
     * @return true if the SMS is classified as a transaction, false otherwise.
     */
    fun isTransactional(sender: String, text: String): Boolean {
        return filterWithDetails(sender, text).isTransactional
    }
}

data class FilterResult(
    val isTransactional: Boolean,
    val logs: List<String>
)
