package com.pocketfinancer.pipeline

import com.pocketfinancer.data.model.QueuedSmsCandidate
import com.pocketfinancer.data.model.SmsCandidateOrigin
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Exact, process-only ownership for one automatic WorkManager claim.
 *
 * [claimToken] is the WorkSpec id that established the durable claim. Including
 * it prevents a delayed callback from an older attempt at [candidateKey] from
 * updating or clearing a successor attempt.
 */
data class AutomaticSmsProcessingOwner(
    val candidateKey: String,
    val claimToken: String
) {
    init {
        require(candidateKey.isNotBlank()) {
            "Automatic SMS activity requires an opaque candidate key"
        }
        require(claimToken.isNotBlank()) {
            "Automatic SMS activity requires an exact claim token"
        }
    }
}

enum class AutomaticSmsProcessingStage {
    PREPARING,
    FILTERING,
    LOADING_MODEL,
    GENERATING,
    PERSISTING,
    RETRYING,
    FILTERED_OUT,
    SAVED,
    ALREADY_SAVED,
    ERROR
}

enum class AutomaticSmsFilterResult {
    PASSED,
    REJECTED
}

data class AutomaticSmsSlmPerformance(
    val promptEvalMs: Long,
    val evalMs: Long,
    val generatedTokens: Int
) {
    val tokensPerSecond: Double
        get() = if (evalMs > 0L) {
            generatedTokens.toDouble() / (evalMs / 1_000.0)
        } else {
            0.0
        }
}

/** Deliberately excludes the private native session-file path. */
data class AutomaticSmsSlmCacheTelemetry(
    val attempted: Boolean,
    val hit: Boolean,
    val prefixTokens: Int
)

/**
 * In-memory detail for the currently claimed automatic candidate.
 *
 * Source evidence and model output are never written to preferences,
 * WorkManager data, a database, or saved UI state. The worker exact-clears this
 * snapshot in a non-cancellable boundary whenever the claim stops being live.
 */
data class AutomaticSmsProcessingActivity(
    val owner: AutomaticSmsProcessingOwner,
    val sender: String,
    val body: String,
    val date: Long,
    val stage: AutomaticSmsProcessingStage =
        AutomaticSmsProcessingStage.PREPARING,
    val filterResult: AutomaticSmsFilterResult? = null,
    val modelName: String? = null,
    val grammarEnabled: Boolean? = null,
    val answerTokenBudget: Int = 0,
    /** Most recent decoded callback only; never reconstructed as reasoning. */
    val decodedTokenDelta: String = "",
    /** Bounded cumulative structured output received from the runtime relay. */
    val jsonOutput: String = "",
    val jsonOutputTruncated: Boolean = false,
    val performance: AutomaticSmsSlmPerformance? = null,
    val cache: AutomaticSmsSlmCacheTelemetry? = null,
    val detail: String? = null
) {
    init {
        require(answerTokenBudget >= 0) {
            "Answer token budget must not be negative"
        }
        require(jsonOutput.length <= MAX_AUTOMATIC_OUTPUT_CHARS) {
            "Automatic JSON telemetry exceeded its in-memory bound"
        }
        require(decodedTokenDelta.length <= MAX_AUTOMATIC_TOKEN_DELTA_CHARS) {
            "Automatic decoded-token telemetry exceeded its in-memory bound"
        }
    }
}

/**
 * Process-scoped read owner for automatic SMS activity.
 *
 * App consumers can observe [activity], while all mutations remain internal to
 * the pipeline module and require the exact claim owner.
 */
@Singleton
class AutomaticSmsProcessingActivityStore @Inject constructor() {
    private val _activity = MutableStateFlow<AutomaticSmsProcessingActivity?>(null)
    val activity: StateFlow<AutomaticSmsProcessingActivity?> =
        _activity.asStateFlow()

    internal fun begin(
        owner: AutomaticSmsProcessingOwner,
        sender: String,
        body: String,
        date: Long,
        nanoTime: () -> Long = System::nanoTime
    ): AutomaticSmsProcessingSession? {
        val initial = AutomaticSmsProcessingActivity(
            owner = owner,
            sender = sender,
            body = body,
            date = date,
            stage = AutomaticSmsProcessingStage.PREPARING,
            detail = "Preparing automatic SMS processing."
        )
        // AutomaticSmsOperationGate means this should normally be null. Keep
        // the begin itself exact as a defence in depth: a delayed old claimant
        // must never overwrite a different activity already published by a
        // successor.
        if (!_activity.compareAndSet(expect = null, update = initial)) {
            return null
        }
        return AutomaticSmsProcessingSession(
            initial = initial,
            publish = { next -> publish(owner, next) },
            clear = { clear(owner) },
            nanoTime = nanoTime
        )
    }

    /** Returns false when [owner] has already been replaced or cleared. */
    internal fun publish(
        owner: AutomaticSmsProcessingOwner,
        next: AutomaticSmsProcessingActivity
    ): Boolean {
        require(next.owner == owner) {
            "Automatic SMS activity cannot change claim ownership"
        }
        while (true) {
            val current = _activity.value ?: return false
            if (current.owner != owner) return false
            if (_activity.compareAndSet(current, next)) return true
        }
    }

    /** Exact clear: a stale attempt can never scrub its successor. */
    internal fun clear(owner: AutomaticSmsProcessingOwner): Boolean {
        while (true) {
            val current = _activity.value ?: return false
            if (current.owner != owner) return false
            if (_activity.compareAndSet(current, null)) return true
        }
    }
}

/**
 * Run-scoped PipelineService observer plus worker preparation transitions.
 * Token callbacks are bounded and coalesced before reaching StateFlow.
 */
internal class AutomaticSmsProcessingSession(
    initial: AutomaticSmsProcessingActivity,
    private val publish: (AutomaticSmsProcessingActivity) -> Boolean,
    private val clear: () -> Boolean,
    private val nanoTime: () -> Long = System::nanoTime
) : PipelineService.ProcessingObserver {
    val owner: AutomaticSmsProcessingOwner = initial.owner

    private var activity = initial
    private val json = StringBuilder(initial.jsonOutput)
    private var decodedTokenDelta = initial.decodedTokenDelta
    private var jsonTruncated = initial.jsonOutputTruncated
    private var receivedDecodedToken = false
    private var lastPublishedNanos: Long? = null
    private var closed = false

    @Synchronized
    fun filtering() {
        transition(
            stage = AutomaticSmsProcessingStage.FILTERING,
            detail = "Checking whether this alert is transactional."
        )
    }

    @Synchronized
    fun loadingModel() {
        transition(
            stage = AutomaticSmsProcessingStage.LOADING_MODEL,
            detail = "Preparing the on-device model."
        )
    }

    @Synchronized
    fun filterPassed() {
        if (closed) return
        activity = activity.copy(filterResult = AutomaticSmsFilterResult.PASSED)
        publishSnapshot(force = true)
    }

    @Synchronized
    fun retrying(detail: String) {
        transition(AutomaticSmsProcessingStage.RETRYING, detail)
    }

    @Synchronized
    fun filteredOut(deterministicFilterRejected: Boolean = false) {
        if (closed) return
        activity = activity.copy(
            stage = AutomaticSmsProcessingStage.FILTERED_OUT,
            filterResult = if (deterministicFilterRejected) {
                AutomaticSmsFilterResult.REJECTED
            } else {
                activity.filterResult
            },
            detail = "This alert was not an eligible transaction."
        )
        publishSnapshot(force = true)
    }

    @Synchronized
    fun saved(newlyInserted: Boolean) {
        transition(
            stage = if (newlyInserted) {
                AutomaticSmsProcessingStage.SAVED
            } else {
                AutomaticSmsProcessingStage.ALREADY_SAVED
            },
            detail = if (newlyInserted) {
                "Transaction saved locally."
            } else {
                "Transaction was already saved."
            }
        )
    }

    @Synchronized
    fun error(detail: String) {
        transition(AutomaticSmsProcessingStage.ERROR, detail)
    }

    @Synchronized
    override fun onEvent(event: PipelineService.ProcessingEvent) {
        if (closed) return
        when (event) {
            is PipelineService.ProcessingEvent.GroundedStage -> {
                val nextStage = when (event.stage) {
                    "claim", "analysis", "triage" -> AutomaticSmsProcessingStage.FILTERING
                    "selector_execution", "selector_validation", "reconstruction" ->
                        AutomaticSmsProcessingStage.GENERATING
                    "account_resolution", "persistence_gate", "settlement" ->
                        AutomaticSmsProcessingStage.PERSISTING
                    else -> activity.stage
                }
                val detail = when (event.stage) {
                    "analysis" -> "Analyzing grounded evidence."
                    "triage" -> "Checking deterministic transaction state."
                    "selector_execution" -> "Selecting supplied candidate IDs on device."
                    "selector_validation" -> "Validating the grounded selector output."
                    "reconstruction" -> "Reconstructing verified transaction fields."
                    "persistence_gate" -> "Checking whether ledger storage is allowed."
                    "settlement" -> "Saving the result for review."
                    else -> activity.detail
                }
                activity = activity.copy(stage = nextStage, detail = detail)
                publishSnapshot(force = true)
            }

            PipelineService.ProcessingEvent.DeterministicFilterStarted -> {
                // The worker already performed the same inexpensive filter
                // before model acquisition. Do not regress LOADING_MODEL back
                // to FILTERING when PipelineService defensively rechecks it.
                if (
                    activity.stage == AutomaticSmsProcessingStage.PREPARING ||
                    activity.stage == AutomaticSmsProcessingStage.FILTERING
                ) {
                    transition(
                        AutomaticSmsProcessingStage.FILTERING,
                        "Checking whether this alert is transactional."
                    )
                }
            }

            PipelineService.ProcessingEvent.DeterministicFilterPassed ->
                filterPassed()

            PipelineService.ProcessingEvent.DeterministicFilterRejected ->
                filteredOut(deterministicFilterRejected = true)

            is PipelineService.ProcessingEvent.InferenceStarted -> {
                decodedTokenDelta = ""
                json.clear()
                jsonTruncated = false
                receivedDecodedToken = false
                activity = activity.copy(
                    stage = AutomaticSmsProcessingStage.GENERATING,
                    modelName = File(event.model.modelPath).name
                        .take(MAX_AUTOMATIC_MODEL_NAME_CHARS),
                    grammarEnabled = event.grammarEnabled,
                    answerTokenBudget = event.answerTokenBudget,
                    detail = "Selecting grounded candidates on device."
                )
                publishSnapshot(force = true)
            }

            is PipelineService.ProcessingEvent.JsonTokenDelta -> {
                val enteredJson =
                    activity.stage != AutomaticSmsProcessingStage.GENERATING
                val firstDecodedToken = !receivedDecodedToken
                receivedDecodedToken = true
                decodedTokenDelta = event.delta
                    .take(MAX_AUTOMATIC_TOKEN_DELTA_CHARS)
                val truncatedThisOutput =
                    json.replaceBounded(event.cumulativeStructuredOutput)
                jsonTruncated = truncatedThisOutput
                activity = activity.copy(
                    stage = AutomaticSmsProcessingStage.GENERATING,
                    detail = "Generating transaction details on device."
                )
                publishSnapshot(
                    force = enteredJson || firstDecodedToken ||
                        truncatedThisOutput
                )
            }

            is PipelineService.ProcessingEvent.InferenceCompleted -> {
                decodedTokenDelta = ""
                event.json?.let { completedJson ->
                    jsonTruncated = json.replaceBounded(completedJson)
                }
                activity = activity.copy(
                    stage = AutomaticSmsProcessingStage.GENERATING,
                    modelName = File(event.model.modelPath).name
                        .take(MAX_AUTOMATIC_MODEL_NAME_CHARS),
                    performance = event.perf?.let { perf ->
                        AutomaticSmsSlmPerformance(
                            promptEvalMs = perf.tPromptEvalMs,
                            evalMs = perf.tEvalMs,
                            generatedTokens = perf.nTokens
                        )
                    },
                    cache = event.cache?.let { cache ->
                        AutomaticSmsSlmCacheTelemetry(
                            attempted = cache.attempted,
                            hit = cache.hit,
                            prefixTokens = cache.prefixTokens
                        )
                    },
                    detail = "On-device analysis finished."
                )
                publishSnapshot(force = true)
            }

            PipelineService.ProcessingEvent.PersistenceStarted -> {
                transition(
                    AutomaticSmsProcessingStage.PERSISTING,
                    "Saving the transaction locally."
                )
            }
        }
    }

    /** Scrubs local buffers and exact-clears the public snapshot. */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        decodedTokenDelta = ""
        json.clear()
        activity = activity.copy(
            sender = "",
            body = "",
            decodedTokenDelta = "",
            jsonOutput = "",
            jsonOutputTruncated = false,
            performance = null,
            cache = null,
            detail = null
        )
        clear()
    }

    private fun transition(
        stage: AutomaticSmsProcessingStage,
        detail: String?
    ) {
        if (closed) return
        activity = activity.copy(
            stage = stage,
            detail = detail?.take(MAX_AUTOMATIC_DETAIL_CHARS)
        )
        publishSnapshot(force = true)
    }

    private fun publishSnapshot(force: Boolean) {
        if (closed) return
        val now = nanoTime()
        val previous = lastPublishedNanos
        if (
            !force &&
            previous != null &&
            now - previous < TOKEN_PUBLISH_INTERVAL_NANOS
        ) {
            return
        }
        activity = activity.copy(
            decodedTokenDelta = decodedTokenDelta,
            jsonOutput = json.toString(),
            jsonOutputTruncated = jsonTruncated
        )
        publish(activity)
        lastPublishedNanos = now
    }

    /** Returns true when any part of [delta] could not be retained. */
    private fun StringBuilder.appendBounded(delta: String): Boolean {
        val remaining = MAX_AUTOMATIC_OUTPUT_CHARS - length
        if (remaining <= 0) return delta.isNotEmpty()
        if (delta.length <= remaining) {
            append(delta)
            return false
        }
        append(delta, 0, remaining)
        return true
    }

    private fun StringBuilder.replaceBounded(value: String): Boolean {
        clear()
        return appendBounded(value)
    }
}

/**
 * Opens activity only for a durable automatic claim and guarantees exact,
 * non-cancellable clearing for every return, retry, exception, or cancellation.
 */
internal suspend fun <T> withAutomaticSmsProcessingActivity(
    candidate: QueuedSmsCandidate,
    claimToken: String,
    store: AutomaticSmsProcessingActivityStore,
    block: suspend (AutomaticSmsProcessingSession?) -> T
): T {
    if (candidate.origin != SmsCandidateOrigin.AUTOMATIC) {
        return block(null)
    }
    val session = store.begin(
        owner = AutomaticSmsProcessingOwner(
            candidateKey = candidate.candidateKey,
            claimToken = claimToken
        ),
        sender = candidate.sender,
        body = candidate.rawMessage,
        date = candidate.date
    )
    return try {
        block(session)
    } finally {
        withContext(NonCancellable) {
            session?.close()
        }
    }
}

internal const val MAX_AUTOMATIC_OUTPUT_CHARS = 64_000
internal const val MAX_AUTOMATIC_TOKEN_DELTA_CHARS = 4_096
private const val MAX_AUTOMATIC_DETAIL_CHARS = 512
private const val MAX_AUTOMATIC_MODEL_NAME_CHARS = 256
private const val TOKEN_PUBLISH_INTERVAL_NANOS = 50_000_000L
