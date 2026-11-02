package com.pocketfinancer.ui.home

import com.pocketfinancer.setup.SetupEmptyReason
import com.pocketfinancer.setup.SetupImportState
import com.pocketfinancer.setup.SetupImportStatus
import com.pocketfinancer.setup.SetupPauseReason
import com.pocketfinancer.pipeline.AutomaticProcessingPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class SetupImportCardModel(
    val eyebrow: String,
    val title: String,
    val body: String,
    val evidence: String? = null,
    val primaryAction: SetupCardAction? = null,
    val primaryLabel: String? = null,
    val showProgress: Boolean = false
)

internal enum class SetupCardAction {
    OPEN_REVIEWS,
    PREPARE_MODEL,
    RESUME,
    RESTORE_PERMISSION,
    SCAN_OLDER,
    SCAN_RECENT,
    RETRY_RECENT_SYNC
}

internal enum class SetupCardActionTarget {
    OPEN_REVIEWS,
    START_SETUP,
    SCAN_OLDER,
    RETRY_RECENT_SYNC,
    RESTORE_PERMISSION
}

internal fun SetupCardAction.target(): SetupCardActionTarget = when (this) {
    SetupCardAction.OPEN_REVIEWS -> SetupCardActionTarget.OPEN_REVIEWS
    SetupCardAction.PREPARE_MODEL,
    SetupCardAction.RESUME,
    SetupCardAction.SCAN_RECENT -> SetupCardActionTarget.START_SETUP
    SetupCardAction.SCAN_OLDER -> SetupCardActionTarget.SCAN_OLDER
    SetupCardAction.RETRY_RECENT_SYNC ->
        SetupCardActionTarget.RETRY_RECENT_SYNC
    SetupCardAction.RESTORE_PERMISSION ->
        SetupCardActionTarget.RESTORE_PERMISSION
}

internal fun manualRecentSyncAvailable(
    status: SetupImportStatus
): Boolean = status == SetupImportStatus.READY ||
    status == SetupImportStatus.READY_NO_HISTORY

internal fun setupImportCardModel(
    state: SetupImportState,
    automaticProcessingEnabled: Boolean =
        AutomaticProcessingPreferences.DEFAULT_ENABLED,
    isCancelling: Boolean = false,
    isFinishing: Boolean = false,
    isPreparingModel: Boolean = false,
    modelUpgradeRunning: Boolean = false,
    nowMillis: Long = System.currentTimeMillis()
): SetupImportCardModel {
    if (isPreparingModel) {
        val permissionNeeded =
            state.status == SetupImportStatus.PERMISSION_NEEDED
        return SetupImportCardModel(
            eyebrow = "MODEL PREPARATION",
            title = "Preparing the on-device model",
            body = if (permissionNeeded) {
                "Model preparation can finish without reading messages. " +
                    "Restore SMS access before the history scan begins."
            } else {
                "Local model preparation is still running in the background. " +
                    "SMS scanning has not started yet."
            },
            primaryAction = SetupCardAction.RESTORE_PERMISSION
                .takeIf { permissionNeeded },
            primaryLabel = "Restore access".takeIf { permissionNeeded },
            showProgress = true
        )
    }
    if (isFinishing) {
        val permissionNeeded =
            state.status == SetupImportStatus.PERMISSION_NEEDED
        return SetupImportCardModel(
            eyebrow = "FINISHING SAFELY",
            title = "Finishing SMS processing",
            body = if (permissionNeeded) {
                "SMS access is off. The final import commit has crossed its " +
                    "persistence boundary and is finishing; verified results " +
                    "remain available."
            } else {
                "The final completion boundary has started. Verified " +
                    "results and coverage are being finalized. Completed " +
                    "saves remain on this device."
            },
            showProgress = true
        )
    }
    if (
        modelUpgradeRunning &&
        !state.isActive &&
        state.status != SetupImportStatus.PERMISSION_NEEDED
    ) {
        return SetupImportCardModel(
            eyebrow = "MODEL UPGRADE",
            title = "Model upgrade is in progress",
            body =
                "Finish or cancel the model upgrade before starting another " +
                    "SMS history scan.",
            showProgress = true
        )
    }
    val needsExplicitModelPreparation =
        !state.modelPrepared &&
            !state.modelDownloadConfirmed &&
            !state.isActive &&
            state.status != SetupImportStatus.PERMISSION_NEEDED
    if (needsExplicitModelPreparation) {
        return SetupImportCardModel(
            eyebrow = "PRIVATE, ON-DEVICE SETUP",
            title = "Prepare the on-device model",
            body = buildString {
                append(
                    "No usable local model is available. Preparing it uses " +
                        "about 700 MB and starts only after you confirm."
                )
                if (state.retainedReviewCount > 0) {
            retainedReviewCard(state)
        } else if (state.hasVerifiedCoverage) {
                    append(" Your previously verified SMS coverage is preserved.")
                }
            },
            evidence = coverageDescription(state)
                .takeIf { state.hasVerifiedCoverage },
            primaryAction = SetupCardAction.PREPARE_MODEL,
            primaryLabel = "Prepare local AI"
        )
    }

    val manualError = state.actionableError?.takeIf { error ->
        manualRecentSyncAvailable(state.status) &&
            (
                error.code.startsWith("RECENT_") ||
                    error.code.startsWith("MANUAL_")
                )
    }
    val recentProcessingNeedsAttention =
        manualRecentSyncAvailable(state.status) &&
            state.recentProcessingNeedsAttention
    if (manualError != null || recentProcessingNeedsAttention) {
        val unfinishedCount =
            (
                state.recentEligibleCandidateCount -
                    state.recentProcessedCount
            ).coerceAtLeast(0)
        return SetupImportCardModel(
            eyebrow = "RECENT SCAN NEEDS ATTENTION",
            title = if (manualError?.code?.startsWith("RECENT_") == true) {
                "The recent SMS scan did not finish"
            } else {
                "Some recent alerts still need attention"
            },
            body = manualError?.let { error ->
                if (error.code.startsWith("RECENT_")) {
                    "${error.message} Your previously verified coverage is unchanged."
                } else {
                    "${error.message} The verified provider range is preserved; " +
                        "a new scan can safely rediscover unfinished messages."
                }
            } ?: buildString {
                if (unfinishedCount > 0) {
                    append(
                        "$unfinishedCount eligible alert" +
                            if (unfinishedCount == 1) {
                                " was not completed."
                            } else {
                                "s were not completed."
                            }
                    )
                }
                if (state.recentFailedCount > 0) {
                    if (isNotEmpty()) append(" ")
                    append(
                        "${state.recentFailedCount} alert" +
                            if (state.recentFailedCount == 1) {
                                " needs another attempt."
                            } else {
                                "s need another attempt."
                            }
                    )
                }
                append(
                    " Scan again to rediscover unfinished messages safely."
                )
            },
            evidence = coverageDescription(state),
            primaryAction = SetupCardAction.RETRY_RECENT_SYNC,
            primaryLabel = manualError?.actionLabel
                ?.takeIf { it.isNotBlank() }
                ?: "Try recent scan again"
        )
    }

    return when (state.status) {
    SetupImportStatus.NOT_STARTED -> SetupImportCardModel(
        eyebrow = "PRIVATE, ON-DEVICE SETUP",
        title = "Prepare Pocket Financer when you are ready",
        body = if (state.modelPrepared) {
            "The local model is ready. Start a resumable scan to look for eligible transaction alerts."
        } else {
            "No model has been downloaded. Preparing the local AI uses about 700 MB and starts only after you confirm."
        },
        primaryAction = if (state.modelPrepared) {
            SetupCardAction.RESUME
        } else {
            SetupCardAction.PREPARE_MODEL
        },
        primaryLabel = if (state.modelPrepared) {
            "Start history scan"
        } else {
            "Prepare local AI"
        }
    )

    SetupImportStatus.PERMISSION_NEEDED -> SetupImportCardModel(
        eyebrow = when {
            isCancelling -> "STOPPING SAFELY"
            isFinishing -> "FINISHING SAFELY"
            else -> "SMS ACCESS NEEDED"
        },
        title = when {
            isCancelling -> "Stopping SMS processing"
            isFinishing -> "Finishing SMS processing"
            else -> "Restore SMS access"
        },
        body = when {
            isCancelling ->
                "SMS access is off. Stopping the active on-device operation safely; completed saves remain available."
            isFinishing ->
                "SMS access is off. A save that already crossed the persistence boundary is finishing; completed saves remain available."
            else ->
                "Pocket Financer cannot scan or capture alerts while SMS access is off. Existing encrypted transactions stay available."
        },
        primaryAction = when {
            isCancelling || isFinishing -> null
            else -> SetupCardAction.RESTORE_PERMISSION
        },
        primaryLabel = when {
            isCancelling || isFinishing -> null
            else -> "Restore access"
        },
        showProgress = isCancelling || isFinishing
    )

    SetupImportStatus.DOWNLOADING -> SetupImportCardModel(
        eyebrow = "MODEL PREPARATION",
        title = "Downloading the on-device model",
        body = "The confirmed download is running in the background. You can keep using the app.",
        showProgress = true
    )

    SetupImportStatus.SCANNING -> SetupImportCardModel(
        eyebrow = when {
            isCancelling -> "STOPPING SAFELY"
            isFinishing -> "FINISHING SAFELY"
            else -> "HISTORY DISCOVERY"
        },
        title = when {
            isCancelling -> "Stopping SMS history scan"
            isFinishing -> "Finishing SMS history scan"
            else -> {
                when (
                    val days =
                        state.activeScanWindowDays ?: state.coverageWindowDays
                ) {
                    null -> "Checking SMS history"
                    in 36_500..Int.MAX_VALUE ->
                        "Checking all available SMS history"
                    else -> "Checking the last $days days"
                }
            }
        },
        body = when {
            isCancelling ->
                "Finishing the current on-device operation safely. Completed saves remain on this device."
            isFinishing ->
                "The completion boundary has started. Verified results and coverage are being finalized."
            else ->
                "Messages are filtered locally before any model parsing. This state is saved so an interruption is visible."
        },
        // Persisted counts and coverage continue to describe the last
        // successful provider read until this query commits. Showing them as
        // evidence for the active window would be misleading.
        evidence = null,
        showProgress = true
    )

    SetupImportStatus.PROCESSING -> SetupImportCardModel(
        eyebrow = when {
            isCancelling -> "STOPPING SAFELY"
            isFinishing -> "FINISHING SAFELY"
            else -> "LOCAL PROCESSING"
        },
        title = when {
            isCancelling -> "Stopping SMS processing"
            isFinishing -> "Finishing SMS processing"
            else -> "Checking eligible alerts"
        },
        body = when {
            isCancelling ->
                "Finishing the current on-device operation safely. Completed saves remain on this device."
            isFinishing ->
                "The final completion boundary has started. Completed saves remain on this device."
            else ->
                "Each candidate is processed on this device. Saved transactions keep their encrypted source evidence; rejected messages are not retained long-term."
        },
        evidence =
            "${state.processedCount} of ${state.eligibleCandidateCount} checked · " +
                "${state.savedCount} saved · ${state.retainedReviewCount} for review · ${state.rejectedCount} rejected" +
                if (state.failedCount > 0) " · ${state.failedCount} failed" else "",
        showProgress = true
    )

    SetupImportStatus.READY -> {
        if (state.retainedReviewCount > 0) {
            retainedReviewCard(state)
        } else if (state.hasVerifiedCoverage) {
            SetupImportCardModel(
                eyebrow = "READY",
                title = if (coverageIsFresh(state, nowMillis)) {
                    "Pocket Financer is up to date"
                } else {
                    "Pocket Financer is ready"
                },
                body = coverageDescription(state),
                evidence = scanEvidence(state),
                primaryAction = SetupCardAction.SCAN_OLDER,
                primaryLabel = "Scan older messages"
            )
        } else {
            SetupImportCardModel(
                eyebrow = "READY · COVERAGE UNKNOWN",
                title = "Your previous setup is preserved",
                body = "This app version cannot verify when the legacy import last succeeded. Run a scan to establish truthful coverage.",
                primaryAction = SetupCardAction.SCAN_RECENT,
                primaryLabel = "Scan recent messages"
            )
        }
    }

    SetupImportStatus.READY_NO_HISTORY -> if (state.retainedReviewCount > 0) {
        retainedReviewCard(state)
    } else SetupImportCardModel(
        eyebrow = if (automaticProcessingEnabled) {
            "READY FOR THE NEXT ALERT"
        } else {
            "READY · AUTOMATIC SMS PROCESSING OFF"
        },
        title = when (state.emptyReason) {
            SetupEmptyReason.EMPTY_INBOX ->
                "No SMS were found in the checked history"
            SetupEmptyReason.FILTERED_OUT ->
                "Messages were found, but none looked transactional"
            SetupEmptyReason.CANDIDATES_REJECTED ->
                "History checked; no transactions added"
            SetupEmptyReason.NO_ADDITIONAL_MESSAGES ->
                "No additional eligible alerts were found"
            SetupEmptyReason.NO_ELIGIBLE_WITHIN_90_DAYS ->
                "No eligible alerts were found in the last 90 days"
            else -> "No eligible transaction history was found"
        },
        body = buildString {
            if (state.emptyReason == SetupEmptyReason.CANDIDATES_REJECTED) {
                append("Open saved alert reviews to check for retained alerts. ")
            }
            append(coverageDescription(state))
            if (automaticProcessingEnabled) {
                append(
                    " Pocket Financer will capture the next eligible alert."
                )
            } else {
                append(
                    " Pocket Financer will not process new alerts " +
                        "automatically. Run a manual scan or turn automatic " +
                        "SMS processing on."
                )
            }
        },
        evidence = scanEvidence(state),
        primaryAction = if (state.emptyReason == SetupEmptyReason.CANDIDATES_REJECTED) {
            SetupCardAction.OPEN_REVIEWS
        } else SetupCardAction.SCAN_OLDER,
        primaryLabel = if (state.emptyReason == SetupEmptyReason.CANDIDATES_REJECTED) {
            "Open saved alert reviews"
        } else "Scan older messages"
    )

    SetupImportStatus.PAUSED -> when {
        isCancelling -> SetupImportCardModel(
            eyebrow = "STOPPING SAFELY",
            title = "Finishing SMS stop",
            body =
                "Runtime cleanup and foreground feedback are finishing. " +
                    "Completed saves remain on this device.",
            evidence = scanEvidence(state),
            showProgress = true
        )
        isFinishing -> SetupImportCardModel(
            eyebrow = "FINISHING SAFELY",
            title = "Finishing SMS processing",
            body =
                "A save that already crossed the persistence boundary is " +
                    "finishing. Completed saves remain on this device.",
            evidence = scanEvidence(state),
            showProgress = true
        )
        else -> SetupImportCardModel(
            eyebrow = "SETUP PAUSED",
            title = when (state.pauseReason) {
                SetupPauseReason.INTERRUPTED ->
                    "Setup stopped before it finished"
                SetupPauseReason.USER_REQUESTED ->
                    "SMS processing stopped"
                SetupPauseReason.AUTOMATIC_UPDATES_DISABLED ->
                    "Automatic setup work is paused"
                else -> "Setup is paused"
            },
            body = state.actionableError?.message
                ?: "Completed work is still saved. Resume when this device is ready.",
            evidence = scanEvidence(state),
            primaryAction = SetupCardAction.RESUME,
            primaryLabel = state.actionableError?.actionLabel
                ?.takeIf { it.isNotBlank() }
                ?: "Resume setup"
        )
    }

    SetupImportStatus.FAILED -> SetupImportCardModel(
        eyebrow = "SETUP NEEDS ATTENTION",
        title = "Pocket Financer could not finish setup",
        body = state.actionableError?.message
            ?: "The last setup attempt failed before completion.",
        evidence = scanEvidence(state),
        primaryAction = SetupCardAction.RESUME,
        primaryLabel = state.actionableError?.actionLabel
            ?.takeIf { it.isNotBlank() }
            ?: "Try again"
    )
    }
}

private fun coverageDescription(state: SetupImportState): String {
    val historical = when {
        !state.hasVerifiedCoverage ->
            "No verified scan range is available."
        (state.coverageWindowDays ?: 0) >= 36_500 ->
            "All available SMS history through " +
                "${formatCoverageDate(state.coverageEndMillis!!)} was checked."
        else ->
            "SMS history from ${formatCoverageDate(state.coverageStartMillis!!)} " +
                "through ${formatCoverageDate(state.coverageEndMillis!!)} was checked."
    }
    val recentEnd = state.recentCoverageEndMillis
    return if (
        state.hasVerifiedRecentCoverage &&
        recentEnd != null &&
        recentEnd > (state.coverageEndMillis ?: Long.MIN_VALUE)
    ) {
        "$historical A recent ${state.recentScanWindowDays}-day scan through " +
            "${formatCoverageDate(recentEnd)} also succeeded."
    } else {
        historical
    }
}

private fun coverageIsFresh(
    state: SetupImportState,
    nowMillis: Long
): Boolean {
    val coverageEnd = listOfNotNull(
        state.coverageEndMillis,
        state.recentCoverageEndMillis
            .takeIf { state.hasVerifiedRecentCoverage }
    ).maxOrNull() ?: return false
    return !state.recentProcessingNeedsAttention &&
        coverageEnd >= nowMillis - TimeUnit.HOURS.toMillis(24) &&
        coverageEnd <= nowMillis + TimeUnit.MINUTES.toMillis(5)
}

private fun formatCoverageDate(timestampMillis: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        .format(Date(timestampMillis))

private fun scanEvidence(state: SetupImportState): String? {
    if (
        state.hasVerifiedRecentCoverage &&
        (state.recentCoverageEndMillis ?: Long.MIN_VALUE) >=
        (state.coverageEndMillis ?: Long.MIN_VALUE)
    ) {
        return buildString {
            append("Recent scan: ${state.recentProviderMessageCount} messages checked")
            append(" · ${state.recentEligibleCandidateCount} eligible")
            if (state.recentSavedCount > 0) {
                append(" · ${state.recentSavedCount} saved")
            }
            if (state.recentRejectedCount > 0) {
                append(" · ${state.recentRejectedCount} rejected")
            }
            val pending = (
                state.recentEligibleCandidateCount -
                    state.recentProcessedCount
            ).coerceAtLeast(0)
            if (pending > 0) append(" · $pending unfinished")
            if (state.recentFailedCount > 0) {
                append(" · ${state.recentFailedCount} failed")
            }
        }
    }
    if (!state.hasVerifiedCoverage && state.providerMessageCount == 0) return null
    return buildString {
        append("${state.providerMessageCount} messages checked")
        append(" · ${state.eligibleCandidateCount} eligible")
        if (state.savedCount > 0) append(" · ${state.savedCount} saved")
    }
}

private fun retainedReviewCard(state: SetupImportState) = SetupImportCardModel(
    eyebrow = "HISTORY CHECKED",
    title = "Alerts saved for your review",
    body = "${state.retainedReviewCount} alerts need review. Open them to confirm, correct, retry, or reject; transactions already saved remain in Transactions.",
    evidence = scanEvidence(state),
    primaryAction = SetupCardAction.OPEN_REVIEWS,
    primaryLabel = "Open saved alert reviews"
)
