package com.pocketfinancer.pipeline.sms

import java.util.Locale

/**
 * Pure pilot policy, not wired into intake, inference, storage, or erasure.
 *
 * conservative-prefilter-v1: exclude only whole ASCII messages matching the closed
 * templates below. Ignore ASCII case and runs of space/tab/CR/LF for matching only.
 * Non-ASCII and all other messages pass. Sender/analyzer/regex misses are not inputs.
 * Callers retain the unchanged source. Passing does not classify a transaction.
 * Intelligence owns semantics and the byte-identical prefilter-v1.json test fixture.
 */
object ConservativeSmsPrefilter {
    const val POLICY_ID = "conservative-prefilter-v1"

    enum class Decision(val wireValue: String) { PASS("pass"), EXCLUDE("exclude") }

    enum class Reason(val wireValue: String) {
        UNCERTAIN("uncertain"),
        STANDALONE_VERIFICATION("standalone_verification"),
        STANDALONE_PROMOTION("standalone_promotion")
    }

    data class Result(val decision: Decision, val reason: Reason)

    private val verification = Regex(
        "your (?:otp|login verification code) is [0-9]{4,8}\\. do not share it\\."
    )
    private val promotion = Regex(
        "advertisement: save [0-9]{1,2}% in our weekend sale\\. shop now\\."
    )
    private val asciiWhitespace = Regex("[ \\t\\r\\n]+")

    fun evaluate(body: String): Result {
        if (body.all { it.code <= 127 }) {
            val normalized = asciiWhitespace.replace(body.lowercase(Locale.ROOT), " ").trim(' ')
            if (verification.matches(normalized)) {
                return Result(Decision.EXCLUDE, Reason.STANDALONE_VERIFICATION)
            }
            if (promotion.matches(normalized)) {
                return Result(Decision.EXCLUDE, Reason.STANDALONE_PROMOTION)
            }
        }
        return Result(Decision.PASS, Reason.UNCERTAIN)
    }
}
