package com.pocketfinancer.ui.smsprocessing

import com.pocketfinancer.pipeline.AutomaticSmsFilterResult
import com.pocketfinancer.pipeline.AutomaticSmsProcessingActivity
import com.pocketfinancer.pipeline.AutomaticSmsProcessingStage
import com.pocketfinancer.ui.home.HomeSyncState
import com.pocketfinancer.ui.home.SyncSmsItem
import com.pocketfinancer.ui.home.hasDiagnosticSourceEvidence
import com.pocketfinancer.ui.onboarding.HistoricalSmsProcessingActivity
import com.pocketfinancer.ui.onboarding.HistoricalSmsProcessingStage
import java.util.Locale
import org.json.JSONObject

fun formatGroundedSelectorOutput(json: String): String = runCatching {
    JSONObject(json).toString(2)
}.getOrElse {
    "Parsed: null (non-financial)"
}

enum class SmsTelemetryStatus {
    PENDING,
    ACTIVE,
    SAVED,
    ALREADY_SAVED,
    FILTERED_OUT,
    ERROR;

    companion object {
        fun fromStorageStatus(status: String): SmsTelemetryStatus = when (status) {
            "syncing" -> ACTIVE
            "synced" -> SAVED
            "already_saved" -> ALREADY_SAVED
            "filtered_out" -> FILTERED_OUT
            "error" -> ERROR
            else -> PENDING
        }
    }
}

enum class SmsTelemetryFilterOutcome {
    PASSED,
    REJECTED
}

sealed interface SmsTelemetrySource {
    /** Sensitive evidence. Never place this value in saveable UI state. */
    data class Available(
        val sender: String,
        val body: String
    ) : SmsTelemetrySource

    /** The candidate remains inspectable, but its source is absent here. */
    data class Unavailable(
        val detail: String
    ) : SmsTelemetrySource
}

sealed interface SmsTelemetryContent {
    /** Candidate-scoped details; [source] may contain sensitive evidence. */
    data class Candidate(
        val candidateKey: String,
        val source: SmsTelemetrySource
    ) : SmsTelemetryContent {
        init {
            require(candidateKey.isNotBlank()) {
                "Telemetry candidate key must not be blank"
            }
        }
    }

    /** Source-free state shown while an existing run is between candidates. */
    data class Gap(
        val title: String,
        val detail: String
    ) : SmsTelemetryContent
}

data class SmsTelemetryRuntimeFacts(
    val grammarEnabled: Boolean,
    val answerTokenBudget: Int,
    val promptEvalMs: Long? = null,
    val evalMs: Long? = null,
    val generatedTokens: Int? = null,
    val cacheAttempted: Boolean? = null,
    val cacheHit: Boolean? = null,
    val cachePrefixTokens: Int? = null
)

data class SmsTelemetryUiModel(
    val target: SmsProcessingTarget,
    val content: SmsTelemetryContent,
    val phase: SmsPipelinePhase,
    val status: SmsTelemetryStatus,
    val activeStageIndex: Int,
    val jsonOutput: String,
    val filterLogs: List<String>,
    val cacheLogs: List<String>,
    val slmPrompt: String,
    val parsedOutput: String,
    val performanceText: String?,
    val activeModelName: String?,
    val decodedTokenDelta: String = "",
    val runtimeFacts: SmsTelemetryRuntimeFacts? = null,
    val jsonOutputTruncated: Boolean = false,
    val filterOutcome: SmsTelemetryFilterOutcome? = null,
    val stopState: SmsStopUiState = SmsStopUiState.HIDDEN
) {
    val cumulativeStructuredOutput: String
        get() = jsonOutput

    val isActiveCandidate: Boolean
        get() = content is SmsTelemetryContent.Candidate &&
            phase in setOf(
                SmsPipelinePhase.PROCESSING,
                SmsPipelinePhase.STOPPING,
                SmsPipelinePhase.FINISHING
            )
}

fun telemetrySettledPersistenceLabel(status: SmsTelemetryStatus): String? =
    when (status) {
        SmsTelemetryStatus.SAVED -> "Saved to encrypted ledger"
        SmsTelemetryStatus.ALREADY_SAVED -> "Verified in encrypted ledger"
        else -> null
    }

data class SmsTelemetrySettledFacts(
    val upstreamCompleted: Boolean,
    val upstreamUnavailable: Boolean,
    val ledgerVerified: Boolean
)

/**
 * An already-saved result can be produced by source-identity preflight. Its
 * upstream stages are unknown, even though ledger ownership is verified.
 */
fun telemetrySettledFacts(
    status: SmsTelemetryStatus
): SmsTelemetrySettledFacts = SmsTelemetrySettledFacts(
    upstreamCompleted = status == SmsTelemetryStatus.SAVED,
    upstreamUnavailable = status == SmsTelemetryStatus.ALREADY_SAVED,
    ledgerVerified = status == SmsTelemetryStatus.SAVED ||
        status == SmsTelemetryStatus.ALREADY_SAVED
)

fun String.withLiveOutputTruncationNotice(truncated: Boolean): String =
    if (truncated) "$this\n\n[Live output truncated for display.]" else this

/** Consolidates manual and historical telemetry formatting for every screen. */
object SmsTelemetryPresenter {
    fun manual(
        state: HomeSyncState,
        sms: SyncSmsItem,
        filterLogs: List<String>,
        cacheLogs: List<String>,
        slmPrompt: String,
        parseJson: (String) -> String,
        target: SmsProcessingTarget
    ): SmsTelemetryUiModel {
        val requestedTarget = target
        require(
            requestedTarget is SmsProcessingTarget.ManualRecent ||
                requestedTarget is SmsProcessingTarget.ManualResult
        ) {
            "Manual telemetry requires a manual processing target"
        }
        require(requestedTarget.candidateKey == sms.id) {
            "Manual telemetry target must identify the rendered candidate"
        }
        if (
            requestedTarget is SmsProcessingTarget.ManualRecent &&
            !state.ownsManualProcessingTarget(requestedTarget)
        ) {
            return expiredManualTelemetry(requestedTarget)
        }
        val isActive = requestedTarget is SmsProcessingTarget.ManualRecent &&
            state.ownsManualProcessingTarget(requestedTarget) &&
            state.activeSmsPipelineItem()?.id == sms.id
        val activeStageIndex = when {
            isActive -> state.currentStageIndex ?: 0
            sms.status in setOf("synced", "already_saved", "filtered_out") -> 4
            else -> 0
        }
        val status = if (isActive) {
            SmsTelemetryStatus.ACTIVE
        } else {
            SmsTelemetryStatus.fromStorageStatus(sms.status)
        }
        val phase = when {
            isActive && state.status == HomeSyncState.Status.CANCELLING ->
                SmsPipelinePhase.STOPPING
            isActive -> SmsPipelinePhase.PROCESSING
            status == SmsTelemetryStatus.ERROR ||
                status == SmsTelemetryStatus.PENDING ||
                status == SmsTelemetryStatus.ACTIVE -> SmsPipelinePhase.ISSUE
            else -> SmsPipelinePhase.COMPLETE
        }

        return SmsTelemetryUiModel(
            target = requestedTarget,
            content = SmsTelemetryContent.Candidate(
                candidateKey = sms.id,
                source = sms.telemetrySource()
            ),
            phase = phase,
            status = status,
            activeStageIndex = activeStageIndex,
            jsonOutput = manualJsonOutput(state, sms, isActive),
            filterLogs = filterLogs,
            cacheLogs = cacheLogs,
            slmPrompt = slmPrompt,
            parsedOutput = manualParsedOutput(
                state = state,
                sms = sms,
                isActive = isActive,
                activeStageIndex = activeStageIndex,
                parseJson = parseJson
            ),
            performanceText = state.activeSmsPerformance.takeIf { isActive },
            activeModelName = state.activeModelName,
            decodedTokenDelta = state.decodedTokenDelta.takeIf { isActive }
                .orEmpty(),
            jsonOutputTruncated = isActive && state.jsonOutputTruncated,
            stopState = when {
                !isActive || state.activeRunId == null -> SmsStopUiState.HIDDEN
                state.status == HomeSyncState.Status.CANCELLING ->
                    SmsStopUiState.STOPPING
                else -> SmsStopUiState.AVAILABLE
            }
        )
    }

    fun historical(
        activity: HistoricalSmsProcessingActivity,
        runId: String,
        filterLogs: List<String>,
        slmPrompt: String,
        parseJson: (String) -> String,
        stopState: SmsStopUiState
    ): SmsTelemetryUiModel = SmsTelemetryUiModel(
        target = SmsProcessingTarget.Historical(runId, activity.candidateKey),
        content = SmsTelemetryContent.Candidate(
            candidateKey = activity.candidateKey,
            source = SmsTelemetrySource.Available(
                sender = activity.sender,
                body = activity.body
            )
        ),
        phase = when (stopState) {
            SmsStopUiState.STOPPING -> SmsPipelinePhase.STOPPING
            SmsStopUiState.COMMIT_UNAVAILABLE -> SmsPipelinePhase.FINISHING
            else -> SmsPipelinePhase.PROCESSING
        },
        status = SmsTelemetryStatus.ACTIVE,
        activeStageIndex = activity.stageIndex,
        jsonOutput = activity.jsonOutput,
        filterLogs = filterLogs,
        cacheLogs = activity.historicalCacheLogs(),
        slmPrompt = slmPrompt,
        parsedOutput = historicalParsedOutput(activity, parseJson),
        performanceText = activity.historicalPerformanceText(),
        activeModelName = activity.modelName,
        decodedTokenDelta = activity.decodedTokenDelta,
        runtimeFacts = activity.toSmsTelemetryRuntimeFacts(),
        jsonOutputTruncated = activity.jsonOutputTruncated,
        stopState = stopState
    )

    fun automatic(
        activity: AutomaticSmsProcessingActivity,
        filterLogs: List<String>,
        slmPrompt: String,
        parseJson: (String) -> String,
        target: SmsProcessingTarget.Automatic
    ): SmsTelemetryUiModel {
        if (!activity.ownsAutomaticProcessingTarget(target)) {
            return expiredAutomaticTelemetry(target)
        }

        val status = when (activity.stage) {
            AutomaticSmsProcessingStage.SAVED -> SmsTelemetryStatus.SAVED
            AutomaticSmsProcessingStage.ALREADY_SAVED ->
                SmsTelemetryStatus.ALREADY_SAVED
            AutomaticSmsProcessingStage.FILTERED_OUT ->
                SmsTelemetryStatus.FILTERED_OUT
            AutomaticSmsProcessingStage.ERROR -> SmsTelemetryStatus.ERROR
            AutomaticSmsProcessingStage.RETRYING -> SmsTelemetryStatus.PENDING
            else -> SmsTelemetryStatus.ACTIVE
        }
        val phase = when (activity.stage) {
            AutomaticSmsProcessingStage.PERSISTING ->
                SmsPipelinePhase.FINISHING
            AutomaticSmsProcessingStage.RETRYING,
            AutomaticSmsProcessingStage.ERROR -> SmsPipelinePhase.ISSUE
            AutomaticSmsProcessingStage.FILTERED_OUT,
            AutomaticSmsProcessingStage.SAVED,
            AutomaticSmsProcessingStage.ALREADY_SAVED ->
                SmsPipelinePhase.COMPLETE
            else -> SmsPipelinePhase.PROCESSING
        }

        return SmsTelemetryUiModel(
            target = target,
            content = SmsTelemetryContent.Candidate(
                candidateKey = activity.owner.candidateKey,
                source = if (activity.body.isNotBlank()) {
                    SmsTelemetrySource.Available(
                        sender = activity.sender,
                        body = activity.body
                    )
                } else {
                    SmsTelemetrySource.Unavailable(
                        "Source evidence is unavailable for this live claim."
                    )
                }
            ),
            phase = phase,
            status = status,
            activeStageIndex = activity.automaticStageIndex(),
            jsonOutput = activity.jsonOutput,
            filterLogs = filterLogs,
            cacheLogs = activity.automaticCacheLogs(),
            slmPrompt = slmPrompt,
            parsedOutput = automaticParsedOutput(activity, parseJson),
            performanceText = activity.automaticPerformanceText(),
            activeModelName = activity.modelName,
            decodedTokenDelta = activity.decodedTokenDelta,
            runtimeFacts = activity.toSmsTelemetryRuntimeFacts(),
            jsonOutputTruncated = activity.jsonOutputTruncated,
            filterOutcome = when (activity.filterResult) {
                AutomaticSmsFilterResult.PASSED ->
                    SmsTelemetryFilterOutcome.PASSED
                AutomaticSmsFilterResult.REJECTED ->
                    SmsTelemetryFilterOutcome.REJECTED
                null -> null
            },
            stopState = SmsStopUiState.HIDDEN
        )
    }

    fun gap(
        target: SmsProcessingTarget,
        phase: SmsPipelinePhase,
        stopState: SmsStopUiState,
        title: String = when (phase) {
            SmsPipelinePhase.STOPPING -> "Stopping SMS processing"
            SmsPipelinePhase.FINISHING -> "Finishing history import"
            SmsPipelinePhase.SCANNING -> "Scanning recent messages"
            else -> "Preparing next eligible message"
        },
        detail: String =
            "The previous message's details were cleared from this live view."
    ): SmsTelemetryUiModel {
        require(target.candidateKey == null) {
            "A telemetry gap target must not identify a candidate"
        }
        return SmsTelemetryUiModel(
            target = target,
            content = SmsTelemetryContent.Gap(title, detail),
            phase = phase,
            status = SmsTelemetryStatus.ACTIVE,
            activeStageIndex = 0,
            jsonOutput = "",
            filterLogs = emptyList(),
            cacheLogs = emptyList(),
            slmPrompt = "",
            parsedOutput = "",
            performanceText = null,
            activeModelName = null,
            stopState = stopState
        )
    }
}

private fun SyncSmsItem.telemetrySource(): SmsTelemetrySource =
    if (hasDiagnosticSourceEvidence()) {
        SmsTelemetrySource.Available(
            sender = sender,
            body = body
        )
    } else {
        SmsTelemetrySource.Unavailable(
            detail = if (
                status in setOf("synced", "already_saved", "filtered_out")
            ) {
                "Source evidence was cleared from this processing log after the result settled."
            } else {
                "Source evidence is unavailable for this processing result."
            }
        )
    }

private fun expiredManualTelemetry(
    target: SmsProcessingTarget.ManualRecent
): SmsTelemetryUiModel = SmsTelemetryUiModel(
    target = target,
    content = SmsTelemetryContent.Gap(
        title = "Processing details expired",
        detail = "A newer SMS run owns the live processing details."
    ),
    phase = SmsPipelinePhase.ISSUE,
    status = SmsTelemetryStatus.PENDING,
    activeStageIndex = 0,
    jsonOutput = "",
    filterLogs = emptyList(),
    cacheLogs = emptyList(),
    slmPrompt = "",
    parsedOutput = "",
    performanceText = null,
    activeModelName = null,
    stopState = SmsStopUiState.HIDDEN
)

private fun expiredAutomaticTelemetry(
    target: SmsProcessingTarget.Automatic
): SmsTelemetryUiModel = SmsTelemetryUiModel(
    target = target,
    content = SmsTelemetryContent.Gap(
        title = "Processing details expired",
        detail = "A different automatic SMS claim owns the live processing details."
    ),
    phase = SmsPipelinePhase.ISSUE,
    status = SmsTelemetryStatus.PENDING,
    activeStageIndex = 0,
    jsonOutput = "",
    filterLogs = emptyList(),
    cacheLogs = emptyList(),
    slmPrompt = "",
    parsedOutput = "",
    performanceText = null,
    activeModelName = null,
    stopState = SmsStopUiState.HIDDEN
)

private fun manualJsonOutput(
    state: HomeSyncState,
    sms: SyncSmsItem,
    isActive: Boolean
): String = when {
    isActive -> state.jsonOutput
    sms.status == "synced" -> "Raw JSON output was not retained after sync."
    sms.status == "already_saved" ->
        "Raw JSON output was not retained for an existing transaction."
    sms.status == "filtered_out" ->
        "No transaction JSON was retained for this message."
    sms.status == "error" ->
        "Output is unavailable because processing did not finish."
    else -> ""
}

private fun manualParsedOutput(
    state: HomeSyncState,
    sms: SyncSmsItem,
    isActive: Boolean,
    activeStageIndex: Int,
    parseJson: (String) -> String
): String = when {
    isActive && state.jsonOutput.isNotEmpty() -> {
        val parsed = parseJson(state.jsonOutput)
        if (
            parsed == "Parsed: null (non-financial)" &&
            activeStageIndex < 3
        ) {
            "Waiting for complete JSON..."
        } else {
            parsed
        }
    }

    isActive -> ""
    sms.status == "synced" ->
        "Saved transaction: amount=${sms.parsedAmount ?: "-"}, " +
            "counterparty=${sms.parsedMerchant ?: "-"}"
    sms.status == "already_saved" ->
        "The encrypted ledger already owns this source evidence."
    sms.status == "filtered_out" ->
        "No transaction was saved for this message."
    sms.status == "error" ->
        "The message could not be saved. The failed stage was not retained."
    else -> ""
}

fun historicalParsedOutput(
    activity: HistoricalSmsProcessingActivity,
    parseJson: (String) -> String
): String = when {
    activity.jsonOutput.isEmpty() -> ""
    activity.stage == HistoricalSmsProcessingStage.GENERATING &&
        activity.jsonOutputTruncated ->
        "Live JSON preview truncated; waiting for inference to finish."
    activity.stage == HistoricalSmsProcessingStage.GENERATING ->
        "Waiting for complete JSON..."
    activity.jsonOutputTruncated ->
        "Parsed successfully; full JSON was omitted from the live display."
    else -> parseJson(activity.jsonOutput)
}

private fun AutomaticSmsProcessingActivity.automaticStageIndex(): Int =
    when (stage) {
        AutomaticSmsProcessingStage.PREPARING -> -1
        AutomaticSmsProcessingStage.FILTERING,
        AutomaticSmsProcessingStage.LOADING_MODEL -> 0
        AutomaticSmsProcessingStage.GENERATING -> 1
        AutomaticSmsProcessingStage.PERSISTING -> 2
        AutomaticSmsProcessingStage.RETRYING,
        AutomaticSmsProcessingStage.FILTERED_OUT,
        AutomaticSmsProcessingStage.SAVED,
        AutomaticSmsProcessingStage.ALREADY_SAVED,
        AutomaticSmsProcessingStage.ERROR -> 4
    }

private fun automaticParsedOutput(
    activity: AutomaticSmsProcessingActivity,
    parseJson: (String) -> String
): String = when {
    activity.stage == AutomaticSmsProcessingStage.FILTERED_OUT ->
        "No transaction was saved for this message."
    activity.jsonOutput.isEmpty() -> ""
    activity.stage == AutomaticSmsProcessingStage.GENERATING &&
        activity.jsonOutputTruncated ->
        "Live JSON preview truncated; waiting for inference to finish."
    activity.stage == AutomaticSmsProcessingStage.GENERATING ->
        "Waiting for complete JSON..."
    activity.stage in setOf(
        AutomaticSmsProcessingStage.PERSISTING,
        AutomaticSmsProcessingStage.SAVED,
        AutomaticSmsProcessingStage.ALREADY_SAVED
    ) && activity.jsonOutputTruncated ->
        "Parsed successfully; full JSON was omitted from the live display."
    activity.stage in setOf(
        AutomaticSmsProcessingStage.PERSISTING,
        AutomaticSmsProcessingStage.SAVED,
        AutomaticSmsProcessingStage.ALREADY_SAVED
    ) -> parseJson(activity.jsonOutput)
    else -> "Output was produced, but processing did not finish."
}

fun AutomaticSmsProcessingActivity.toSmsTelemetryRuntimeFacts():
    SmsTelemetryRuntimeFacts? {
    val grammar = grammarEnabled ?: return null
    return SmsTelemetryRuntimeFacts(
        grammarEnabled = grammar,
        answerTokenBudget = answerTokenBudget,
        promptEvalMs = performance?.promptEvalMs,
        evalMs = performance?.evalMs,
        generatedTokens = performance?.generatedTokens,
        cacheAttempted = cache?.attempted,
        cacheHit = cache?.hit,
        cachePrefixTokens = cache?.prefixTokens
    )
}

fun AutomaticSmsProcessingActivity.automaticPerformanceText(): String? =
    performance?.let { value ->
        String.format(
            Locale.US,
            "%d tokens • %.2f tok/s",
            value.generatedTokens,
            value.tokensPerSecond
        )
    } ?: when (stage) {
        AutomaticSmsProcessingStage.PREPARING -> "Preparing claim…"
        AutomaticSmsProcessingStage.FILTERING -> "Checking message…"
        AutomaticSmsProcessingStage.LOADING_MODEL -> "Preparing model…"
        else -> null
    }

fun AutomaticSmsProcessingActivity.automaticCacheLogs(): List<String> =
    cache?.let { value ->
        listOf(
            "Prefix cache attempted: ${value.attempted}",
            "Prefix cache hit: ${value.hit}",
            "Cached prefix tokens: ${value.prefixTokens}"
        )
    } ?: if (grammarEnabled != null) {
        listOf("Exact prefix-cache telemetry is awaiting the runtime result.")
    } else {
        listOf("The runtime request has not started yet.")
    }

fun HistoricalSmsProcessingActivity.toSmsTelemetryRuntimeFacts():
    SmsTelemetryRuntimeFacts? {
    val grammar = grammarEnabled ?: return null
    return SmsTelemetryRuntimeFacts(
        grammarEnabled = grammar,
        answerTokenBudget = answerTokenBudget,
        promptEvalMs = performance?.promptEvalMs,
        evalMs = performance?.evalMs,
        generatedTokens = performance?.generatedTokens,
        cacheAttempted = cache?.attempted,
        cacheHit = cache?.hit,
        cachePrefixTokens = cache?.prefixTokens
    )
}

fun HistoricalSmsProcessingActivity.historicalPerformanceText(): String? =
    performance?.let { value ->
        String.format(
            Locale.US,
            "%d tokens • %.2f tok/s",
            value.generatedTokens,
            value.tokensPerSecond
        )
    }

fun HistoricalSmsProcessingActivity.historicalCacheLogs(): List<String> =
    cache?.let { value ->
        listOf(
            "Prefix cache attempted: ${value.attempted}",
            "Prefix cache hit: ${value.hit}",
            "Cached prefix tokens: ${value.prefixTokens}"
        )
    } ?: listOf(
        "Exact prefix-cache telemetry will appear after inference completes."
    )
