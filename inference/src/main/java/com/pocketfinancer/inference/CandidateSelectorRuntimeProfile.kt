package com.pocketfinancer.inference

data class CandidateSelectorRuntimeProfile(
    val generationMode: String = "DIRECT_NON_THINKING",
    val decoding: String = "greedy",
    val answerTokenLimit: Int = 512,
    val rawOutputByteLimit: Int = 16_384,
    val deadlineMs: Long = 0
) {
    init {
        require(generationMode == "DIRECT_NON_THINKING")
        require(decoding == "greedy")
        require(answerTokenLimit in 1..512)
        require(rawOutputByteLimit in 1..16_384)
        require(deadlineMs == 0L)
    }
}
