package com.pocketfinancer.setup

/**
 * Durable, user-visible state for first-run model preparation and SMS import.
 *
 * This state deliberately does not imply freshness when coverage or
 * [lastSuccessfulScanMillis] is unknown. In particular, legacy upgrades are
 * marked ready without inventing a scan range.
 */
data class SetupImportState(
    val status: SetupImportStatus = SetupImportStatus.NOT_STARTED,
    val coverageStartMillis: Long? = null,
    val coverageEndMillis: Long? = null,
    val coverageWindowDays: Int? = null,
    /**
     * Window currently being scanned or processed.
     *
     * This is separate from verified coverage: persisting the requested
     * all-history window before provider I/O makes that explicit action
     * resumable without falsely claiming the read already succeeded.
     */
    val activeScanWindowDays: Int? = null,
    /** Immutable provider upper bound for the active historical scan. */
    val activeScanProviderMaxDateMillis: Long? = null,
    val providerMessageCount: Int = 0,
    val eligibleCandidateCount: Int = 0,
    val processedCount: Int = 0,
    val savedCount: Int = 0,
    val rejectedCount: Int = 0,
    val retainedReviewCount: Int = 0,
    val failedCount: Int = 0,
    val lastSuccessfulScanMillis: Long? = null,
    /**
     * The latest successful manual recent-provider read. These fields are
     * deliberately separate from historical import coverage so a seven-day
     * refresh cannot replace a previously verified 90-day (or all-history)
     * range.
     */
    val recentCoverageStartMillis: Long? = null,
    val recentCoverageEndMillis: Long? = null,
    val recentScanWindowDays: Int? = null,
    val recentProviderMessageCount: Int = 0,
    val recentEligibleCandidateCount: Int = 0,
    /**
     * Durable outcome counters for the most recent manual provider scan.
     *
     * The Home queue is process memory only. Keeping these counts beside the
     * verified provider range prevents a killed process from turning unfinished
     * candidates into a misleading "up to date" state.
     */
    val recentProcessedCount: Int = 0,
    val recentSavedCount: Int = 0,
    val recentRejectedCount: Int = 0,
    val recentFailedCount: Int = 0,
    val lastSuccessfulRecentScanMillis: Long? = null,
    val emptyReason: SetupEmptyReason? = null,
    val pauseReason: SetupPauseReason? = null,
    val actionableError: SetupActionableError? = null,
    val modelDownloadConfirmed: Boolean = false,
    val modelPrepared: Boolean = false
) {
    val isActive: Boolean
        get() = status in ACTIVE_STATUSES

    val hasVerifiedCoverage: Boolean
        get() =
            coverageStartMillis != null &&
                coverageEndMillis != null &&
                coverageWindowDays != null &&
                lastSuccessfulScanMillis != null

    val hasVerifiedRecentCoverage: Boolean
        get() =
            recentCoverageStartMillis != null &&
                recentCoverageEndMillis != null &&
                recentScanWindowDays != null &&
                lastSuccessfulRecentScanMillis != null

    val hasIncompleteRecentProcessing: Boolean
        get() =
            hasVerifiedRecentCoverage &&
                recentProcessedCount < recentEligibleCandidateCount

    val recentProcessingNeedsAttention: Boolean
        get() = hasIncompleteRecentProcessing || recentFailedCount > 0

    companion object {
        val ACTIVE_STATUSES = setOf(
            SetupImportStatus.DOWNLOADING,
            SetupImportStatus.SCANNING,
            SetupImportStatus.PROCESSING
        )
    }
}

enum class SetupImportStatus {
    NOT_STARTED,
    PERMISSION_NEEDED,
    DOWNLOADING,
    SCANNING,
    PROCESSING,
    READY,
    READY_NO_HISTORY,
    PAUSED,
    FAILED
}

enum class SetupEmptyReason {
    EMPTY_INBOX,
    FILTERED_OUT,
    NO_ELIGIBLE_WITHIN_90_DAYS,
    CANDIDATES_REJECTED,
    NO_ADDITIONAL_MESSAGES,
    UNKNOWN
}

enum class SetupPauseReason {
    INTERRUPTED,
    PERMISSION_REVOKED,
    AUTOMATIC_UPDATES_DISABLED,
    USER_REQUESTED
}

data class SetupActionableError(
    val code: String,
    val message: String,
    val actionLabel: String
)

internal fun reconcileSetupModelAvailability(
    state: SetupImportState,
    hasPublishedModel: Boolean
): SetupImportState =
    if (state.modelPrepared && !hasPublishedModel) {
        state.copy(
            modelDownloadConfirmed = false,
            modelPrepared = false
        )
    } else {
        state
    }
