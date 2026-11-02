package com.pocketfinancer.pipeline

import android.util.Log
import com.pocketfinancer.data.model.TransactionType
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Legacy parser retained only for displaying pre-grounded extraction records.
 *
 * Steps:
 * New SMS processing never invokes this parser; it uses grounded candidate IDs
 * and exact integer minor units through the coordinator.
 *
 * Ported from the Python eval pipeline (DATA/utils.py).
 */
@Singleton
class ExtractionParser @Inject constructor() {

    data class ExtractedTransaction(
        val amount: Double,
        val counterparty: String?,
        val type: TransactionType,
        val account: String?
    )

    /**
     * Parse the raw SLM output into either null (non-financial) or an
     * ExtractedTransaction. Applies the nonnull filter: if any required
     * field (amount, type, account) is missing or null, reject as non-financial.
     */
    fun parse(rawOutput: String): ExtractedTransaction? {
        val cleaned = rawOutput.trim()

        // Check for literal "null"
        if (cleaned.equals("null", ignoreCase = true)) return null

        // Find the JSON object
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start == -1 || end < start) return null

        val jsonStr = cleaned.substring(start, end + 1)

        return try {
            val obj = JSONObject(jsonStr)

            // ── Nonnull filter ──
            // amount, type, and account must be non-null for a real transaction.
            // If any contains null (or the string "null"), treat as non-financial.
            if (isNullish(obj.opt("amount"))) return null
            if (isNullish(obj.opt("type"))) return null
            if (isNullish(obj.opt("account"))) return null

            // ── Coerce amount ──
            val amount = coerceAmount(obj.get("amount"))
                ?: return null

            // ── Coerce type ──
            val type = coerceType(obj.optString("type")) ?: run {
                Log.w(TAG, "Rejecting extraction: type field is not 'debit' or 'credit' — " +
                        "got '${obj.optString("type")}'")
                return null
            }

            // ── Account ──
            val account = obj.optString("account", "")
                .takeIf { it.isNotBlank() }
 
            // ── Counterparty ──
            val counterparty = obj.optString("counterparty", "")
                .takeIf { it.isNotBlank() && !it.trim().equals("null", ignoreCase = true) }
 
            ExtractedTransaction(amount, counterparty, type, account)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Check if a value is JSON null or the literal string "null".
     * Grammar-constrained outputs use the string "null" for non-nullable
     * fields; unconstrained output may use either JSON null or that string.
     */
    private fun isNullish(value: Any?): Boolean {
        if (value == null) return true
        if (value == JSONObject.NULL) return true
        if (value is String && value.trim().equals("null", ignoreCase = true)) return true
        return false
    }

    /**
     * Coerce amount from various formats:
     * - 1500.0     → 1500.0
     * - "1,500"    → 1500.0
     * - "Rs.500"   → 500.0
     * - 500        → 500.0
     */
    private fun coerceAmount(raw: Any?): Double? {
        if (raw == null) return null
        return when (raw) {
            is Number -> {
                val d = raw.toDouble()
                if (d.isFinite() && d > 0) d else null
            }
        is String -> {
            // Strip common currency symbols and keep only digit-like patterns
            val cleaned = raw.replace(Regex("[^0-9.]"), "")
            // Remove leading dots that result from stripped prefix (e.g., "Rs.500" → ".500")
            val normalized = cleaned.trimStart('.')
            val d = normalized.toDoubleOrNull()
            if (d != null && d.isFinite() && d > 0) d else null
        }
            else -> null
        }
    }

    /**
     * Coerce the type field. Returns null for anything that isn't
     * exactly "debit" or "credit". Grammar-constrained generation enforces
     * these values, while unconstrained output is validated here. Anything
     * else is rejected rather than silently defaulted to DEBIT.
     */
    private fun coerceType(raw: String): TransactionType? {
        return when (raw.trim().lowercase()) {
            "credit" -> TransactionType.CREDIT
            "debit"  -> TransactionType.DEBIT
            else     -> null
        }
    }

    companion object {
        private const val TAG = "ExtractionParser"
        /**
         * Normalize an account string for display/storage.
         * Returns the last 4 significant digits and whether it's a card or account.
         */
        fun normalizeAccount(account: String?): Pair<String, String>? {
            if (account.isNullOrBlank()) return null
            val category = if (account.contains("card", ignoreCase = true)) "card" else "account"
            val runs = Regex("\\d+").findAll(account).toList()
            val longRuns = runs.filter { it.value.length >= 3 }
            if (longRuns.isEmpty()) return null
            val lastDigits = longRuns.last().value.takeLast(4)
            return Pair(category, lastDigits)
        }
    }
}
