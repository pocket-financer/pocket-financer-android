package com.pocketfinancer.ui.smsprocessing

import com.pocketfinancer.pipeline.AutomaticSmsProcessingActivity
import com.pocketfinancer.pipeline.AutomaticSmsProcessingStage
import com.pocketfinancer.ui.home.HomeSyncState
import com.pocketfinancer.ui.home.SyncSmsItem
import com.pocketfinancer.ui.home.hasDiagnosticSourceEvidence
import com.pocketfinancer.ui.onboarding.HistoricalSmsProcessingActivity
import com.pocketfinancer.ui.onboarding.HistoricalSmsProcessingStage

/**
 * Opaque identity for the exact UI operation represented by a processing card
 * or telemetry sheet. Action handlers must compare [runId] with their current
 * owner before dispatching so a stale composition cannot affect a successor.
 */
sealed interface SmsProcessingTarget {
    val runId: String?
    val candidateKey: String?

    /**
     * Exact ownership for one claimed automatic WorkManager attempt. The
     * claim token is intentionally exposed through [runId] only so shared
     * presentation code can key an inspect sheet without learning about
     * WorkManager ids or gaining a stop capability.
     */
    data class Automatic(
        val claimToken: String,
        override val candidateKey: String
    ) : SmsProcessingTarget {
        override val runId: String = claimToken

        init {
            require(claimToken.isNotBlank()) {
                "Automatic SMS claim token must not be blank"
            }
            require(candidateKey.isNotBlank()) {
                "Automatic SMS candidate key must not be blank"
            }
        }
    }

    data class ManualRecent(
        override val runId: String,
        override val candidateKey: String?
    ) : SmsProcessingTarget {
        init {
            require(runId.isNotBlank()) { "Manual SMS run id must not be blank" }
            require(candidateKey == null || candidateKey.isNotBlank()) {
                "Manual SMS candidate key must be null or non-blank"
            }
        }
    }

    /** A settled manual result no longer has a live run that can be stopped. */
    data class ManualResult(
        override val candidateKey: String
    ) : SmsProcessingTarget {
        override val runId: String? = null

        init {
            require(candidateKey.isNotBlank()) {
                "Manual SMS result key must not be blank"
            }
        }
    }

    data class Historical(
        override val runId: String,
        override val candidateKey: String?
    ) : SmsProcessingTarget {
        init {
            require(runId.isNotBlank()) {
                "Historical SMS run id must not be blank"
            }
            require(candidateKey == null || candidateKey.isNotBlank()) {
                "Historical SMS candidate key must be null or non-blank"
            }
        }
    }
}

enum class SmsPipelinePhase {
    SCANNING,
    PROCESSING,
    STOPPING,
    FINISHING,
    COMPLETE,
    ISSUE
}

enum class SmsPipelineTone {
    PROCESSING,
    SUCCESS,
    ISSUE
}

sealed interface SmsSourcePreview {
    object Hidden : SmsSourcePreview

    /** A settled candidate whose raw source was deliberately scrubbed. */
    object Cleared : SmsSourcePreview

    data class Message(
        val sender: String,
        val body: String
    ) : SmsSourcePreview
}

enum class SmsInspectUiState {
    HIDDEN,
    AVAILABLE
}

enum class SmsStopUiState {
    HIDDEN,
    AVAILABLE,
    STOPPING,
    COMMIT_UNAVAILABLE
}

data class SmsPipelineCardUiModel(
    /** Null while a manual scan is starting, before an exact run target exists. */
    val target: SmsProcessingTarget?,
    val phase: SmsPipelinePhase,
    val tone: SmsPipelineTone,
    val title: String,
    val detail: String,
    val badge: String,
    val source: SmsSourcePreview,
    val stepLabel: String,
    val stepValue: String,
    val inspectState: SmsInspectUiState,
    val inspectLabel: String,
    val stopState: SmsStopUiState,
    val accessibilityText: String
)

internal fun String.isLedgerVerifiedSuccess(): Boolean =
    this == "synced" || this == "already_saved"

/** Exact claim check used by both the automatic card and telemetry sheet. */
fun AutomaticSmsProcessingActivity.ownsAutomaticProcessingTarget(
    target: SmsProcessingTarget.Automatic
): Boolean = owner.claimToken == target.claimToken &&
    owner.candidateKey == target.candidateKey

/** The candidate that still owns the live manual pipeline, if any. */
fun HomeSyncState.activeSmsPipelineItem(): SyncSmsItem? {
    if (
        status !in setOf(
            HomeSyncState.Status.SCANNING,
            HomeSyncState.Status.SYNCING,
            HomeSyncState.Status.CANCELLING
        )
    ) {
        return null
    }
    return currentIndex
        ?.let(queue::getOrNull)
        ?.takeIf { it.status == "syncing" }
        ?: queue.firstOrNull { it.status == "syncing" }
}

/** Matches the exact run/candidate identity captured by a rendered control. */
fun HomeSyncState.ownsManualProcessingTarget(
    target: SmsProcessingTarget.ManualRecent
): Boolean =
    activeRunId == target.runId &&
        status in setOf(
            HomeSyncState.Status.SCANNING,
            HomeSyncState.Status.SYNCING,
            HomeSyncState.Status.CANCELLING
        ) &&
        activeSmsPipelineItem()?.id == target.candidateKey

/** Selects the candidate whose result the manual processing card represents. */
fun HomeSyncState.smsPipelineCardItem(): SyncSmsItem? = when (status) {
    HomeSyncState.Status.IDLE,
    HomeSyncState.Status.SCANNING -> null

    HomeSyncState.Status.SYNCING,
    HomeSyncState.Status.CANCELLING -> activeSmsPipelineItem()

    HomeSyncState.Status.DONE -> {
        queue.lastOrNull { it.status == "error" }
            ?: queue.lastOrNull {
                !it.status.isLedgerVerifiedSuccess() &&
                    it.status != "filtered_out" &&
                    it.status != "error"
            }
            ?: queue.lastOrNull()
    }
}

/**
 * Pure presentation adapter for a recent manual scan. Scanning and the brief
 * between-candidate handoff deliberately have no source preview.
 */
fun HomeSyncState.toSmsPipelineCardUiModel(
    startPending: Boolean = false
): SmsPipelineCardUiModel? {
    if (
        startPending &&
        activeRunId == null &&
        status in setOf(
            HomeSyncState.Status.IDLE,
            HomeSyncState.Status.SCANNING
        )
    ) {
        return manualStartHandoffCardModel()
    }
    if (status == HomeSyncState.Status.IDLE) {
        return null
    }

    val activeItem = smsPipelineCardItem()
    if (status == HomeSyncState.Status.DONE) {
        return activeItem?.let { completedManualCardModel(it) }
            ?: syncError
                ?.takeIf(String::isNotBlank)
                ?.let(::failedManualCardModel)
    }

    val runId = activeRunId ?: return null
    val target = SmsProcessingTarget.ManualRecent(
        runId = runId,
        candidateKey = activeItem?.id
    )
    val isStopping = status == HomeSyncState.Status.CANCELLING
    val stopState = if (isStopping) {
        SmsStopUiState.STOPPING
    } else {
        SmsStopUiState.AVAILABLE
    }

    if (status == HomeSyncState.Status.SCANNING) {
        val title = if (isStopping) {
            "Stopping SMS processing"
        } else {
            "Scanning recent messages"
        }
        val step = if (isStopping) {
            "Stopping safely"
        } else {
            "Checking recent SMS"
        }
        return SmsPipelineCardUiModel(
            target = target,
            phase = if (isStopping) {
                SmsPipelinePhase.STOPPING
            } else {
                SmsPipelinePhase.SCANNING
            },
            tone = SmsPipelineTone.PROCESSING,
            title = title,
            detail = "Checking the recent SMS window on this device",
            badge = if (isStopping) "STOPPING" else "LIVE",
            source = SmsSourcePreview.Hidden,
            stepLabel = "CURRENT STEP",
            stepValue = step,
            inspectState = SmsInspectUiState.AVAILABLE,
            inspectLabel = if (isStopping) {
                "Inspect stopping details"
            } else {
                "Inspect"
            },
            stopState = stopState,
            accessibilityText = "$title. Current step: $step."
        )
    }

    if (activeItem == null) {
        val title = if (isStopping) {
            "Stopping SMS processing"
        } else {
            "Preparing next message"
        }
        val step = if (isStopping) "Stopping safely" else "Preparing locally"
        return SmsPipelineCardUiModel(
            target = target,
            phase = if (isStopping) {
                SmsPipelinePhase.STOPPING
            } else {
                SmsPipelinePhase.PROCESSING
            },
            tone = SmsPipelineTone.PROCESSING,
            title = title,
            detail = "Previous message details were cleared",
            badge = if (isStopping) "STOPPING" else "LIVE",
            source = SmsSourcePreview.Hidden,
            stepLabel = "CURRENT STEP",
            stepValue = step,
            inspectState = SmsInspectUiState.AVAILABLE,
            inspectLabel = if (isStopping) {
                "Inspect stopping details"
            } else {
                "Inspect"
            },
            stopState = stopState,
            accessibilityText = "$title. Current step: $step."
        )
    }

    val total = queue.size.coerceAtLeast(1)
    val position = queue.indexOfFirst { it.id == activeItem.id }
        .takeIf { it >= 0 }
        ?.plus(1)
        ?: currentIndex?.plus(1)?.coerceIn(1, total)
        ?: 1
    val step = if (isStopping) {
        if (currentStageIndex == 3) "Finishing current save" else "Stopping safely"
    } else {
        manualStepLabel(currentStageIndex)
    }
    val title = when {
        isStopping -> "Stopping SMS processing"
        total == 1 -> "Processing message"
        else -> "Processing message $position of $total"
    }
    val hasSourceEvidence = activeItem.hasDiagnosticSourceEvidence()
    val detail = if (hasSourceEvidence) {
        "From ${activeItem.sender.ifBlank { "Unknown sender" }}"
    } else {
        "Processing the current message on this device"
    }

    return SmsPipelineCardUiModel(
        target = target,
        phase = if (isStopping) {
            SmsPipelinePhase.STOPPING
        } else {
            SmsPipelinePhase.PROCESSING
        },
        tone = SmsPipelineTone.PROCESSING,
        title = title,
        detail = detail,
        badge = if (isStopping) "STOPPING" else "LIVE",
        source = if (hasSourceEvidence) {
            SmsSourcePreview.Message(activeItem.sender, activeItem.body)
        } else {
            SmsSourcePreview.Hidden
        },
        stepLabel = "CURRENT STEP",
        stepValue = step,
        inspectState = SmsInspectUiState.AVAILABLE,
        inspectLabel = if (isStopping) {
            "Inspect stopping details"
        } else {
            "Inspect"
        },
        stopState = stopState,
        accessibilityText = "$title. $detail. Current step: $step."
    )
}

private fun manualStartHandoffCardModel(): SmsPipelineCardUiModel =
    SmsPipelineCardUiModel(
        target = null,
        phase = SmsPipelinePhase.SCANNING,
        tone = SmsPipelineTone.PROCESSING,
        title = "Scanning recent messages",
        detail = "Checking recent SMS on this device",
        badge = "SCANNING",
        source = SmsSourcePreview.Hidden,
        stepLabel = "CURRENT STEP",
        stepValue = "Checking recent SMS",
        inspectState = SmsInspectUiState.HIDDEN,
        inspectLabel = "Inspect",
        stopState = SmsStopUiState.HIDDEN,
        accessibilityText =
            "Scanning recent messages. Checking recent SMS on this device."
    )

private fun failedManualCardModel(error: String): SmsPipelineCardUiModel =
    SmsPipelineCardUiModel(
        target = null,
        phase = SmsPipelinePhase.ISSUE,
        tone = SmsPipelineTone.ISSUE,
        title = "SMS processing could not finish",
        detail = error,
        badge = "ISSUE",
        source = SmsSourcePreview.Hidden,
        stepLabel = "LATEST RESULT",
        stepValue = "No additional transaction was saved",
        inspectState = SmsInspectUiState.HIDDEN,
        inspectLabel = "Inspect",
        stopState = SmsStopUiState.HIDDEN,
        accessibilityText =
            "SMS processing could not finish. $error No additional transaction was saved."
    )

private fun HomeSyncState.completedManualCardModel(
    item: SyncSmsItem
): SmsPipelineCardUiModel {
    val saved = queue.count { it.status == "synced" }
    val alreadySaved = queue.count { it.status == "already_saved" }
    val skipped = queue.count { it.status == "filtered_out" }
    val failed = queue.count { it.status == "error" }
    val incomplete = queue.count {
        !it.status.isLedgerVerifiedSuccess() &&
            it.status != "filtered_out" &&
            it.status != "error"
    }
    val hasIssues = failed > 0 || incomplete > 0
    val counts = buildList {
        if (saved > 0) add("$saved saved")
        if (alreadySaved > 0) add("$alreadySaved already saved")
        if (skipped > 0) add("$skipped skipped")
        if (failed > 0) add("$failed failed")
        if (incomplete > 0) add("$incomplete incomplete")
    }.joinToString(" • ").ifEmpty { "No messages processed" }
    val detail = "Queue: $counts"
    val result = when (item.status) {
        "synced" -> "Saved to transaction ledger"
        "already_saved" -> "Already verified in transaction ledger"
        "filtered_out" -> "Skipped — no transaction found"
        "error" -> "Processing needs attention"
        "pending", "syncing" -> "Sync ended before processing"
        else -> "Result unavailable"
    }
    val title = if (hasIssues) "Sync finished with issues" else "Sync complete"

    return SmsPipelineCardUiModel(
        target = SmsProcessingTarget.ManualResult(item.id),
        phase = if (hasIssues) SmsPipelinePhase.ISSUE else SmsPipelinePhase.COMPLETE,
        tone = if (hasIssues) SmsPipelineTone.ISSUE else SmsPipelineTone.SUCCESS,
        title = title,
        detail = detail,
        badge = if (hasIssues) "REVIEW" else "DONE",
        source = if (item.hasDiagnosticSourceEvidence()) {
            SmsSourcePreview.Message(item.sender, item.body)
        } else {
            SmsSourcePreview.Cleared
        },
        stepLabel = "LATEST RESULT",
        stepValue = result,
        inspectState = SmsInspectUiState.AVAILABLE,
        inspectLabel = if (hasIssues) "Review log" else "Inspect",
        stopState = SmsStopUiState.HIDDEN,
        accessibilityText = "$title. $detail. $result."
    )
}

private fun manualStepLabel(stageIndex: Int?): String =
    when (stageIndex) {
        0, null -> "Checking message"
        1, 2 -> "Selecting grounded candidates"
        3 -> "Saving transaction"
        else -> "Finishing"
    }

/**
 * Automatic work is inspect-only. Disabling future automatic intake does not
 * cancel a candidate that already owns its durable claim, so this model never
 * offers the manual/historical Stop action.
 */
fun AutomaticSmsProcessingActivity.toSmsPipelineCardUiModel():
    SmsPipelineCardUiModel {
    val target = SmsProcessingTarget.Automatic(
        claimToken = owner.claimToken,
        candidateKey = owner.candidateKey
    )
    val terminal = stage in setOf(
        AutomaticSmsProcessingStage.RETRYING,
        AutomaticSmsProcessingStage.FILTERED_OUT,
        AutomaticSmsProcessingStage.SAVED,
        AutomaticSmsProcessingStage.ALREADY_SAVED,
        AutomaticSmsProcessingStage.ERROR
    )
    val title = when (stage) {
        AutomaticSmsProcessingStage.RETRYING ->
            "Automatic SMS processing will retry"
        AutomaticSmsProcessingStage.FILTERED_OUT -> "Message checked"
        AutomaticSmsProcessingStage.SAVED -> "Transaction saved"
        AutomaticSmsProcessingStage.ALREADY_SAVED ->
            "Transaction already saved"
        AutomaticSmsProcessingStage.ERROR ->
            "Automatic SMS processing could not finish"
        else -> "Processing new SMS"
    }
    val step = when (stage) {
        AutomaticSmsProcessingStage.PREPARING -> "Preparing secure processing"
        AutomaticSmsProcessingStage.FILTERING -> "Checking message"
        AutomaticSmsProcessingStage.LOADING_MODEL ->
            "Preparing on-device model"
        AutomaticSmsProcessingStage.GENERATING -> "Selecting grounded candidates"
        AutomaticSmsProcessingStage.PERSISTING -> "Saving transaction"
        AutomaticSmsProcessingStage.RETRYING -> "Retry scheduled"
        AutomaticSmsProcessingStage.FILTERED_OUT ->
            "No transaction was saved"
        AutomaticSmsProcessingStage.SAVED -> "Saved to encrypted ledger"
        AutomaticSmsProcessingStage.ALREADY_SAVED ->
            "Verified in encrypted ledger"
        AutomaticSmsProcessingStage.ERROR -> "No transaction was saved"
    }
    val hasSource = body.isNotBlank()
    val sourceDetail = if (hasSource) {
        "From ${sender.ifBlank { "Unknown sender" }}"
    } else {
        "Processing the claimed message on this device"
    }
    val terminalDetail = detail
        ?.takeIf(String::isNotBlank)
        ?: when (stage) {
            AutomaticSmsProcessingStage.RETRYING ->
                "The claimed message will be retried automatically."
            AutomaticSmsProcessingStage.FILTERED_OUT ->
                "This alert was not an eligible transaction."
            AutomaticSmsProcessingStage.SAVED ->
                "The transaction was saved locally."
            AutomaticSmsProcessingStage.ALREADY_SAVED ->
                "The encrypted ledger already contained this transaction."
            AutomaticSmsProcessingStage.ERROR ->
                "This alert could not be safely processed on device."
            else -> sourceDetail
        }
    val phase = when (stage) {
        AutomaticSmsProcessingStage.PERSISTING -> SmsPipelinePhase.FINISHING
        AutomaticSmsProcessingStage.RETRYING,
        AutomaticSmsProcessingStage.ERROR -> SmsPipelinePhase.ISSUE
        AutomaticSmsProcessingStage.FILTERED_OUT,
        AutomaticSmsProcessingStage.SAVED,
        AutomaticSmsProcessingStage.ALREADY_SAVED -> SmsPipelinePhase.COMPLETE
        else -> SmsPipelinePhase.PROCESSING
    }
    val tone = when (stage) {
        AutomaticSmsProcessingStage.RETRYING,
        AutomaticSmsProcessingStage.ERROR -> SmsPipelineTone.ISSUE
        AutomaticSmsProcessingStage.FILTERED_OUT,
        AutomaticSmsProcessingStage.SAVED,
        AutomaticSmsProcessingStage.ALREADY_SAVED -> SmsPipelineTone.SUCCESS
        else -> SmsPipelineTone.PROCESSING
    }
    val badge = when (stage) {
        AutomaticSmsProcessingStage.RETRYING -> "RETRY"
        AutomaticSmsProcessingStage.FILTERED_OUT -> "CHECKED"
        AutomaticSmsProcessingStage.SAVED -> "SAVED"
        AutomaticSmsProcessingStage.ALREADY_SAVED -> "VERIFIED"
        AutomaticSmsProcessingStage.ERROR -> "ISSUE"
        else -> "AUTO"
    }
    val cardDetail = if (terminal) terminalDetail else sourceDetail

    return SmsPipelineCardUiModel(
        target = target,
        phase = phase,
        tone = tone,
        title = title,
        detail = cardDetail,
        badge = badge,
        source = if (hasSource) {
            SmsSourcePreview.Message(sender, body)
        } else {
            SmsSourcePreview.Hidden
        },
        stepLabel = if (terminal) "LATEST STATE" else "CURRENT STEP",
        stepValue = step,
        inspectState = SmsInspectUiState.AVAILABLE,
        inspectLabel = "Inspect",
        stopState = SmsStopUiState.HIDDEN,
        accessibilityText =
            "$title. Automatic processing. $cardDetail. $step."
    )
}

fun HistoricalSmsProcessingActivity.toSmsPipelineCardUiModel(
    runId: String,
    isCancelling: Boolean,
    isFinishing: Boolean
): SmsPipelineCardUiModel {
    val step = historicalStepLabel(
        stage = stage,
        isCancelling = isCancelling,
        isFinishing = isFinishing
    )
    val title = when {
        isCancelling -> "Stopping historical sync"
        isFinishing -> "Finishing historical sync"
        else -> "Processing message $position of $total"
    }
    val sourceName = sender.ifBlank { "Unknown sender" }
    val detail = if (isCancelling || isFinishing) {
        "Message $position of $total • From $sourceName"
    } else {
        "From $sourceName"
    }
    val phase = when {
        isCancelling -> SmsPipelinePhase.STOPPING
        isFinishing -> SmsPipelinePhase.FINISHING
        else -> SmsPipelinePhase.PROCESSING
    }
    val stopState = when {
        isCancelling -> SmsStopUiState.STOPPING
        isFinishing -> SmsStopUiState.COMMIT_UNAVAILABLE
        else -> SmsStopUiState.AVAILABLE
    }

    return SmsPipelineCardUiModel(
        target = SmsProcessingTarget.Historical(runId, candidateKey),
        phase = phase,
        tone = SmsPipelineTone.PROCESSING,
        title = title,
        detail = detail,
        badge = when {
            isCancelling -> "STOPPING"
            isFinishing -> "FINISHING"
            else -> "LIVE"
        },
        source = SmsSourcePreview.Message(sender, body),
        stepLabel = "CURRENT STEP",
        stepValue = step,
        inspectState = SmsInspectUiState.AVAILABLE,
        inspectLabel = when {
            isCancelling -> "Inspect stopping details"
            isFinishing -> "Inspect finishing details"
            else -> "Inspect"
        },
        stopState = stopState,
        accessibilityText = "$title. $detail. Current step: $step."
    )
}

/** Source-free presentation used while a historical run is between candidates. */
fun historicalSmsPipelineGapUiModel(
    runId: String,
    isCancelling: Boolean,
    isFinishing: Boolean
): SmsPipelineCardUiModel {
    val title = when {
        isCancelling -> "Stopping historical sync"
        isFinishing -> "Finishing historical sync"
        else -> "Preparing next eligible message"
    }
    val step = when {
        isCancelling -> "Stopping safely"
        isFinishing -> "Finalizing setup"
        else -> "Preparing locally"
    }
    return SmsPipelineCardUiModel(
        target = SmsProcessingTarget.Historical(runId, null),
        phase = when {
            isCancelling -> SmsPipelinePhase.STOPPING
            isFinishing -> SmsPipelinePhase.FINISHING
            else -> SmsPipelinePhase.PROCESSING
        },
        tone = SmsPipelineTone.PROCESSING,
        title = title,
        detail = "Previous message details were cleared",
        badge = when {
            isCancelling -> "STOPPING"
            isFinishing -> "FINISHING"
            else -> "LIVE"
        },
        source = SmsSourcePreview.Hidden,
        stepLabel = "CURRENT STEP",
        stepValue = step,
        inspectState = SmsInspectUiState.AVAILABLE,
        inspectLabel = when {
            isCancelling -> "Inspect stopping details"
            isFinishing -> "Inspect finishing details"
            else -> "Inspect"
        },
        stopState = when {
            isCancelling -> SmsStopUiState.STOPPING
            isFinishing -> SmsStopUiState.COMMIT_UNAVAILABLE
            else -> SmsStopUiState.AVAILABLE
        },
        accessibilityText = "$title. Current step: $step."
    )
}

private fun historicalStepLabel(
    stage: HistoricalSmsProcessingStage,
    isCancelling: Boolean,
    isFinishing: Boolean
): String = when {
    isCancelling && stage == HistoricalSmsProcessingStage.PERSISTING ->
        "Finishing current encrypted save"
    isCancelling -> "Stopping safely"
    isFinishing && stage == HistoricalSmsProcessingStage.PERSISTING ->
        "Committing current transaction"
    isFinishing -> "Finalizing setup"
    stage == HistoricalSmsProcessingStage.FILTERING -> "Checking message"
    stage == HistoricalSmsProcessingStage.GENERATING -> "Selecting grounded candidates"
    else -> "Saving transaction"
}
