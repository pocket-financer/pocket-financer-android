package com.pocketfinancer.data.model

/**
 * Domain model — decoupled from Room entity.
 */
data class Transaction(
    val id: String,
    val amount: Double,
    val merchant: String,
    val date: Long,
    val type: TransactionType,
    val accountId: String,
    val accountLabel: String?,
    val rawMessage: String,
    val sender: String,
    val isEdited: Boolean = false,
    val slmPromptEvalMs: Long? = null,
    val slmEvalMs: Long? = null,
    val slmNumTokens: Int? = null,
    val slmModelName: String? = null,
    val sourceIdentity: SmsSourceIdentity? = null,
    val exactMinorUnits: Long? = null,
    val currencyCode: String? = null,
    val currencyScale: Int? = null,
    val currentRevisionId: String? = null,
    val legacyPrecisionStatus: String = "legacy_double_original_precision_unknown"
)

enum class TransactionType {
    DEBIT, CREDIT;

    companion object {
        fun fromString(s: String): TransactionType =
            if (s.equals("credit", ignoreCase = true)) CREDIT else DEBIT
    }
}
