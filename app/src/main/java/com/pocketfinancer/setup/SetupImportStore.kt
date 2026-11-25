package com.pocketfinancer.setup

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SharedPreferences-backed source of truth for setup and historical import.
 *
 * Updates use synchronous commits because every active/terminal transition is
 * restart-facing state. The payload is intentionally small and contains no SMS
 * contents or transaction evidence.
 */
@Singleton
class SetupImportStore private constructor(
    private val preferences: SharedPreferences,
    private val permissionChecker: () -> Boolean
) {
    @Inject
    constructor(
        @ApplicationContext context: Context
    ) : this(
        preferences = context.getSharedPreferences(
            APP_SETTINGS,
            Context.MODE_PRIVATE
        ),
        permissionChecker = { hasRequiredSmsPermissions(context) }
    )

    internal constructor(
        preferences: SharedPreferences,
        hasSmsPermissions: Boolean
    ) : this(preferences, permissionChecker = { hasSmsPermissions })

    private val _state: MutableStateFlow<SetupImportState>
    val state: StateFlow<SetupImportState>

    init {
        _state = MutableStateFlow(loadOrUpgrade())
        state = _state.asStateFlow()
    }

    /**
     * Persists the shell unlock and initial setup state in one commit.
     */
    fun completeRequiredPermissions(): SetupImportState =
        checkNotNull(tryCompleteRequiredPermissions()) {
            "Local financial erase recovery must finish before unlocking the app"
        }

    /**
     * Atomically unlocks the shell only when no durable erase recovery owns it.
     */
    @Synchronized
    fun tryCompleteRequiredPermissions(): SetupImportState? {
        val current = _state.value
        if (isLocalFinancialErasePending()) {
            return null
        }
        val next = if (
            current.status == SetupImportStatus.PERMISSION_NEEDED ||
            current.status == SetupImportStatus.NOT_STARTED
        ) {
            current.copy(
                status = SetupImportStatus.NOT_STARTED,
                pauseReason = null,
                actionableError = null
            )
        } else {
            current
        }
        persist(next, onboardingCompleted = true)
        return next
    }

    /**
     * Keeps permission loss visible without discarding the last truthful
     * terminal state. Recovery restores that state, while an interrupted active
     * operation resumes as PAUSED rather than pretending it is still running.
     */
    @Synchronized
    fun reconcilePermission(granted: Boolean = permissionChecker()): SetupImportState {
        val current = _state.value
        if (!granted) {
            if (current.status != SetupImportStatus.PERMISSION_NEEDED) {
                preferences.edit()
                    .putString(KEY_STATUS_BEFORE_PERMISSION_LOSS, current.status.name)
                    .putNullableString(
                        KEY_PAUSE_REASON_BEFORE_PERMISSION_LOSS,
                        current.pauseReason?.name
                    )
                    .putNullableString(
                        KEY_ERROR_CODE_BEFORE_PERMISSION_LOSS,
                        current.actionableError?.code
                    )
                    .putNullableString(
                        KEY_ERROR_MESSAGE_BEFORE_PERMISSION_LOSS,
                        current.actionableError?.message
                    )
                    .putNullableString(
                        KEY_ERROR_ACTION_BEFORE_PERMISSION_LOSS,
                        current.actionableError?.actionLabel
                    )
                    .commitOrThrow()
            }
            val next = current.copy(
                status = SetupImportStatus.PERMISSION_NEEDED,
                pauseReason = SetupPauseReason.PERMISSION_REVOKED,
                actionableError = SetupActionableError(
                    code = ERROR_SMS_PERMISSION_REQUIRED,
                    message = "SMS access is off. Pocket Financer cannot scan or capture alerts until it is restored.",
                    actionLabel = "Restore SMS access"
                )
            )
            persist(next)
            return next
        }

        if (current.status != SetupImportStatus.PERMISSION_NEEDED) return current

        val previous = preferences
            .getString(KEY_STATUS_BEFORE_PERMISSION_LOSS, null)
            ?.toEnumOrNull<SetupImportStatus>()
        val previousPauseReason = preferences
            .getString(KEY_PAUSE_REASON_BEFORE_PERMISSION_LOSS, null)
            ?.toEnumOrNull<SetupPauseReason>()
        val previousActionableError = preferences
            .getString(KEY_ERROR_CODE_BEFORE_PERMISSION_LOSS, null)
            ?.let { code ->
                SetupActionableError(
                    code = code,
                    message = preferences.getString(
                        KEY_ERROR_MESSAGE_BEFORE_PERMISSION_LOSS,
                        ""
                    ).orEmpty(),
                    actionLabel = preferences.getString(
                        KEY_ERROR_ACTION_BEFORE_PERMISSION_LOSS,
                        ""
                    ).orEmpty()
                )
            }
        val previousWasActive = previous in SetupImportState.ACTIVE_STATUSES
        val recoveredStatus = when {
            previous == null -> SetupImportStatus.NOT_STARTED
            previousWasActive -> SetupImportStatus.PAUSED
            previous == SetupImportStatus.PERMISSION_NEEDED ->
                SetupImportStatus.NOT_STARTED
            else -> previous
        }
        val next = current.copy(
            status = recoveredStatus,
            pauseReason = when {
                previousWasActive -> SetupPauseReason.INTERRUPTED
                previous == null ||
                    previous == SetupImportStatus.PERMISSION_NEEDED -> null
                else -> previousPauseReason
            },
            actionableError = when {
                previousWasActive -> SetupActionableError(
                    code = ERROR_INTERRUPTED,
                    message =
                        "Setup paused when Pocket Financer stopped. " +
                            "Your completed work is still saved.",
                    actionLabel = "Resume setup"
                )
                previous == null ||
                    previous == SetupImportStatus.PERMISSION_NEEDED -> null
                else -> previousActionableError
            }
        )
        persist(next, clearPermissionRecoveryStatus = true)
        return next
    }

    @Synchronized
    fun update(
        transform: (SetupImportState) -> SetupImportState
    ): SetupImportState {
        val next = transform(_state.value).normalized()
        persist(next)
        return next
    }

    /**
     * Publishes a truthful in-process fallback after durable persistence has
     * already failed. The next process start still repairs any persisted
     * active state to INTERRUPTED; this prevents the current UI from claiming
     * that stopped work is still running.
     */
    @Synchronized
    internal fun publishVolatilePersistenceFallback(
        state: SetupImportState
    ): SetupImportState = state.normalized().also { _state.value = it }

    fun setModelDownloadConfirmed(confirmed: Boolean): SetupImportState =
        update { it.copy(modelDownloadConfirmed = confirmed) }

    /**
     * Publishes a successfully prepared model and its selected-model id in the
     * same SharedPreferences commit. Settings uses this after native loading
     * succeeds so Home cannot restart with a selected artifact but stale setup
     * metadata claiming that no model was downloaded.
     */
    @Synchronized
    fun markModelPrepared(selectedModelId: String): SetupImportState {
        require(selectedModelId.isNotBlank()) {
            "Selected model id must not be blank"
        }
        val next = _state.value.copy(
            modelDownloadConfirmed = true,
            modelPrepared = true,
            actionableError = null
        )
        persist(next, selectedModelId = selectedModelId)
        return next
    }

    /**
     * Revokes stale preparation metadata when no published artifact remains.
     * Coverage and import results stay intact; callers can then require a new
     * explicit model-download confirmation without manufacturing scan loss.
     */
    @Synchronized
    fun reconcileModelAvailability(
        hasPublishedModel: Boolean
    ): SetupImportState {
        val current = _state.value
        val next = reconcileSetupModelAvailability(
            state = current,
            hasPublishedModel = hasPublishedModel
        )
        if (next != current) persist(next)
        return next
    }

    fun isLocalFinancialErasePending(): Boolean =
        preferences.getBoolean(KEY_LOCAL_FINANCIAL_ERASE_PENDING, false)

    /**
     * Durably closes every restart-facing admission gate before financial
     * evidence is deleted.
     *
     * The marker makes the operation recoverable after process death. The shell
     * lock, service generation, selected-model removal, and marker share one
     * synchronous commit so no restart can observe only part of this boundary.
     */
    @Synchronized
    fun beginLocalFinancialErase(nextRunGeneration: Long): Long {
        require(nextRunGeneration >= 0L) {
            "Onboarding run generation must not be negative"
        }
        if (isLocalFinancialErasePending()) {
            return preferences.getLong(
                KEY_ONBOARDING_RUN_GENERATION,
                nextRunGeneration
            )
        }
        check(
            preferences.edit()
                .putBoolean(KEY_LOCAL_FINANCIAL_ERASE_PENDING, true)
                .putBoolean(KEY_ONBOARDING_COMPLETED, false)
                .putLong(KEY_ONBOARDING_RUN_GENERATION, nextRunGeneration)
                .remove(KEY_SELECTED_SLM_ID)
                .commit()
        ) {
            "Could not durably begin local financial data erasure"
        }
        return nextRunGeneration
    }

    /**
     * Publishes the reset setup state and clears the recovery marker in the same
     * commit. Call only after encrypted evidence and financial notifications
     * have been cleared.
     */
    @Synchronized
    fun finishLocalFinancialErase(
        retainedModelPrepared: Boolean
    ): SetupImportState {
        check(isLocalFinancialErasePending()) {
            "No local financial data erase is pending"
        }
        val next = initialState(permissionChecker()).copy(
            modelDownloadConfirmed = retainedModelPrepared,
            modelPrepared = retainedModelPrepared
        )
        persist(
            state = next,
            onboardingCompleted = false,
            clearSelectedModel = true,
            clearPermissionRecoveryStatus = true,
            clearLocalFinancialEraseMarker = true
        )
        return next
    }

    /**
     * Reset hook for the confirmed erase flow. The caller owns deletion of the
     * encrypted ledger/model artifacts; this only resets setup metadata.
     */
    @Synchronized
    fun resetForFirstRun(
        retainedModelPrepared: Boolean = false,
        nextRunGeneration: Long? = null,
        clearSelectedModel: Boolean = false
    ): SetupImportState {
        require(nextRunGeneration == null || nextRunGeneration >= 0L) {
            "Onboarding run generation must not be negative"
        }
        val next = initialState(permissionChecker()).copy(
            modelDownloadConfirmed = retainedModelPrepared,
            modelPrepared = retainedModelPrepared
        )
        persist(
            state = next,
            onboardingCompleted = false,
            onboardingRunGeneration = nextRunGeneration,
            clearSelectedModel = clearSelectedModel,
            clearPermissionRecoveryStatus = true
        )
        return next
    }

    private fun loadOrUpgrade(): SetupImportState {
        val hasPersistedSetup =
            preferences.contains(KEY_SCHEMA_VERSION) ||
                preferences.contains(KEY_STATUS)
        val loaded = if (!hasPersistedSetup) {
            val legacyCompleted = preferences.getBoolean(
                KEY_ONBOARDING_COMPLETED,
                false
            )
            if (legacyCompleted) {
                // Old onboarding only completed after the model was selected
                // and loaded. Coverage was never durably recorded, so keep it
                // unknown instead of manufacturing freshness.
                SetupImportState(
                    status = SetupImportStatus.READY,
                    modelDownloadConfirmed = true,
                    modelPrepared = true
                )
            } else {
                initialState(permissionChecker())
            }
        } else {
            readPersistedState()
        }

        val reconciled = if (loaded.isActive) {
            loaded.copy(
                status = SetupImportStatus.PAUSED,
                pauseReason = SetupPauseReason.INTERRUPTED,
                actionableError = SetupActionableError(
                    code = ERROR_INTERRUPTED,
                    message = "Setup paused when Pocket Financer stopped. Your completed work is still saved.",
                    actionLabel = "Resume setup"
                )
            )
        } else {
            loaded
        }.normalized()

        persist(reconciled, publish = false)
        return reconciled
    }

    private fun readPersistedState(): SetupImportState {
        val status = preferences.getString(
            KEY_STATUS,
            SetupImportStatus.NOT_STARTED.name
        ).toEnumOrNull<SetupImportStatus>() ?: SetupImportStatus.NOT_STARTED

        return SetupImportState(
            status = status,
            coverageStartMillis = preferences.nullableLong(KEY_COVERAGE_START),
            coverageEndMillis = preferences.nullableLong(KEY_COVERAGE_END),
            coverageWindowDays = preferences.nullableInt(KEY_COVERAGE_WINDOW_DAYS),
            activeScanWindowDays =
                preferences.nullableInt(KEY_ACTIVE_SCAN_WINDOW_DAYS),
            activeScanProviderMaxDateMillis =
                preferences.nullableLong(KEY_ACTIVE_SCAN_PROVIDER_MAX_DATE),
            providerMessageCount = preferences.getInt(KEY_PROVIDER_COUNT, 0),
            eligibleCandidateCount = preferences.getInt(KEY_ELIGIBLE_COUNT, 0),
            processedCount = preferences.getInt(KEY_PROCESSED_COUNT, 0),
            savedCount = preferences.getInt(KEY_SAVED_COUNT, 0),
            rejectedCount = preferences.getInt(KEY_REJECTED_COUNT, 0),
            retainedReviewCount = preferences.getInt(KEY_RETAINED_REVIEW_COUNT, 0),
            failedCount = preferences.getInt(KEY_FAILED_COUNT, 0),
            lastSuccessfulScanMillis = preferences.nullableLong(KEY_LAST_SUCCESSFUL_SCAN),
            recentCoverageStartMillis =
                preferences.nullableLong(KEY_RECENT_COVERAGE_START),
            recentCoverageEndMillis =
                preferences.nullableLong(KEY_RECENT_COVERAGE_END),
            recentScanWindowDays =
                preferences.nullableInt(KEY_RECENT_SCAN_WINDOW_DAYS),
            recentProviderMessageCount =
                preferences.getInt(KEY_RECENT_PROVIDER_COUNT, 0),
            recentEligibleCandidateCount =
                preferences.getInt(KEY_RECENT_ELIGIBLE_COUNT, 0),
            recentProcessedCount =
                preferences.getInt(KEY_RECENT_PROCESSED_COUNT, 0),
            recentSavedCount =
                preferences.getInt(KEY_RECENT_SAVED_COUNT, 0),
            recentRejectedCount =
                preferences.getInt(KEY_RECENT_REJECTED_COUNT, 0),
            recentFailedCount =
                preferences.getInt(KEY_RECENT_FAILED_COUNT, 0),
            lastSuccessfulRecentScanMillis =
                preferences.nullableLong(KEY_LAST_SUCCESSFUL_RECENT_SCAN),
            emptyReason = preferences.getString(KEY_EMPTY_REASON, null)
                .toEnumOrNull<SetupEmptyReason>(),
            pauseReason = preferences.getString(KEY_PAUSE_REASON, null)
                .toEnumOrNull<SetupPauseReason>(),
            actionableError = preferences
                .getString(KEY_ERROR_CODE, null)
                ?.let { code ->
                    SetupActionableError(
                        code = code,
                        message = preferences.getString(KEY_ERROR_MESSAGE, "")
                            .orEmpty(),
                        actionLabel = preferences.getString(KEY_ERROR_ACTION, "")
                            .orEmpty()
                    )
                },
            modelDownloadConfirmed = preferences.getBoolean(
                KEY_MODEL_DOWNLOAD_CONFIRMED,
                false
            ),
            modelPrepared = preferences.getBoolean(KEY_MODEL_PREPARED, false)
        )
    }

    private fun persist(
        state: SetupImportState,
        onboardingCompleted: Boolean? = null,
        selectedModelId: String? = null,
        onboardingRunGeneration: Long? = null,
        clearSelectedModel: Boolean = false,
        clearPermissionRecoveryStatus: Boolean = false,
        clearLocalFinancialEraseMarker: Boolean = false,
        publish: Boolean = true
    ) {
        val normalized = state.normalized()
        val editor = preferences.edit()
            .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            .putString(KEY_STATUS, normalized.status.name)
            .putNullableLong(KEY_COVERAGE_START, normalized.coverageStartMillis)
            .putNullableLong(KEY_COVERAGE_END, normalized.coverageEndMillis)
            .putNullableInt(KEY_COVERAGE_WINDOW_DAYS, normalized.coverageWindowDays)
            .putNullableInt(
                KEY_ACTIVE_SCAN_WINDOW_DAYS,
                normalized.activeScanWindowDays
            )
            .putNullableLong(
                KEY_ACTIVE_SCAN_PROVIDER_MAX_DATE,
                normalized.activeScanProviderMaxDateMillis
            )
            .putInt(KEY_PROVIDER_COUNT, normalized.providerMessageCount)
            .putInt(KEY_ELIGIBLE_COUNT, normalized.eligibleCandidateCount)
            .putInt(KEY_PROCESSED_COUNT, normalized.processedCount)
            .putInt(KEY_SAVED_COUNT, normalized.savedCount)
            .putInt(KEY_REJECTED_COUNT, normalized.rejectedCount)
            .putInt(KEY_RETAINED_REVIEW_COUNT, normalized.retainedReviewCount)
            .putInt(KEY_FAILED_COUNT, normalized.failedCount)
            .putNullableLong(
                KEY_LAST_SUCCESSFUL_SCAN,
                normalized.lastSuccessfulScanMillis
            )
            .putNullableLong(
                KEY_RECENT_COVERAGE_START,
                normalized.recentCoverageStartMillis
            )
            .putNullableLong(
                KEY_RECENT_COVERAGE_END,
                normalized.recentCoverageEndMillis
            )
            .putNullableInt(
                KEY_RECENT_SCAN_WINDOW_DAYS,
                normalized.recentScanWindowDays
            )
            .putInt(
                KEY_RECENT_PROVIDER_COUNT,
                normalized.recentProviderMessageCount
            )
            .putInt(
                KEY_RECENT_ELIGIBLE_COUNT,
                normalized.recentEligibleCandidateCount
            )
            .putInt(
                KEY_RECENT_PROCESSED_COUNT,
                normalized.recentProcessedCount
            )
            .putInt(KEY_RECENT_SAVED_COUNT, normalized.recentSavedCount)
            .putInt(
                KEY_RECENT_REJECTED_COUNT,
                normalized.recentRejectedCount
            )
            .putInt(KEY_RECENT_FAILED_COUNT, normalized.recentFailedCount)
            .putNullableLong(
                KEY_LAST_SUCCESSFUL_RECENT_SCAN,
                normalized.lastSuccessfulRecentScanMillis
            )
            .putNullableString(KEY_EMPTY_REASON, normalized.emptyReason?.name)
            .putNullableString(KEY_PAUSE_REASON, normalized.pauseReason?.name)
            .putNullableString(
                KEY_ERROR_CODE,
                normalized.actionableError?.code
            )
            .putNullableString(
                KEY_ERROR_MESSAGE,
                normalized.actionableError?.message
            )
            .putNullableString(
                KEY_ERROR_ACTION,
                normalized.actionableError?.actionLabel
            )
            .putBoolean(
                KEY_MODEL_DOWNLOAD_CONFIRMED,
                normalized.modelDownloadConfirmed
            )
            .putBoolean(KEY_MODEL_PREPARED, normalized.modelPrepared)

        onboardingCompleted?.let {
            editor.putBoolean(KEY_ONBOARDING_COMPLETED, it)
        }
        selectedModelId?.let {
            editor.putString(KEY_SELECTED_SLM_ID, it)
        }
        onboardingRunGeneration?.let {
            editor.putLong(KEY_ONBOARDING_RUN_GENERATION, it)
        }
        if (clearSelectedModel) {
            editor.remove(KEY_SELECTED_SLM_ID)
        }
        if (clearPermissionRecoveryStatus) {
            editor
                .remove(KEY_STATUS_BEFORE_PERMISSION_LOSS)
                .remove(KEY_PAUSE_REASON_BEFORE_PERMISSION_LOSS)
                .remove(KEY_ERROR_CODE_BEFORE_PERMISSION_LOSS)
                .remove(KEY_ERROR_MESSAGE_BEFORE_PERMISSION_LOSS)
                .remove(KEY_ERROR_ACTION_BEFORE_PERMISSION_LOSS)
        }
        if (clearLocalFinancialEraseMarker) {
            editor.remove(KEY_LOCAL_FINANCIAL_ERASE_PENDING)
        }
        editor.commitOrThrow()
        if (publish) {
            _state.value = normalized
        }
    }

    private fun SetupImportState.normalized(): SetupImportState = copy(
        coverageWindowDays = coverageWindowDays?.coerceAtLeast(1),
        activeScanWindowDays = activeScanWindowDays?.coerceAtLeast(1),
        activeScanProviderMaxDateMillis =
            activeScanProviderMaxDateMillis?.coerceAtLeast(0L),
        recentScanWindowDays = recentScanWindowDays?.coerceAtLeast(1),
        providerMessageCount = providerMessageCount.coerceAtLeast(0),
        eligibleCandidateCount = eligibleCandidateCount.coerceAtLeast(0),
        recentProviderMessageCount =
            recentProviderMessageCount.coerceAtLeast(0),
        recentEligibleCandidateCount =
            recentEligibleCandidateCount.coerceAtLeast(0),
        recentProcessedCount = recentProcessedCount.coerceAtLeast(0),
        recentSavedCount = recentSavedCount.coerceAtLeast(0),
        recentRejectedCount = recentRejectedCount.coerceAtLeast(0),
        recentFailedCount = recentFailedCount.coerceAtLeast(0),
        processedCount = processedCount.coerceAtLeast(0),
        savedCount = savedCount.coerceAtLeast(0),
        rejectedCount = rejectedCount.coerceAtLeast(0),
        retainedReviewCount = retainedReviewCount.coerceAtLeast(0),
        failedCount = failedCount.coerceAtLeast(0)
    )

    companion object {
        const val APP_SETTINGS = ".app_settings"
        const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        const val KEY_LOCAL_FINANCIAL_ERASE_PENDING =
            "local_financial_erase_pending"
        const val ERROR_INTERRUPTED = "INTERRUPTED"
        const val ERROR_SMS_PERMISSION_REQUIRED = "SMS_PERMISSION_REQUIRED"

        private const val SCHEMA_VERSION = 6
        private const val KEY_SCHEMA_VERSION = "setup_import_schema_version"
        private const val KEY_STATUS = "setup_import_status"
        private const val KEY_STATUS_BEFORE_PERMISSION_LOSS =
            "setup_import_status_before_permission_loss"
        private const val KEY_PAUSE_REASON_BEFORE_PERMISSION_LOSS =
            "setup_import_pause_reason_before_permission_loss"
        private const val KEY_ERROR_CODE_BEFORE_PERMISSION_LOSS =
            "setup_import_error_code_before_permission_loss"
        private const val KEY_ERROR_MESSAGE_BEFORE_PERMISSION_LOSS =
            "setup_import_error_message_before_permission_loss"
        private const val KEY_ERROR_ACTION_BEFORE_PERMISSION_LOSS =
            "setup_import_error_action_before_permission_loss"
        private const val KEY_COVERAGE_START = "setup_import_coverage_start"
        private const val KEY_COVERAGE_END = "setup_import_coverage_end"
        private const val KEY_COVERAGE_WINDOW_DAYS =
            "setup_import_coverage_window_days"
        private const val KEY_ACTIVE_SCAN_WINDOW_DAYS =
            "setup_import_active_scan_window_days"
        private const val KEY_ACTIVE_SCAN_PROVIDER_MAX_DATE =
            "setup_import_active_scan_provider_max_date"
        private const val KEY_PROVIDER_COUNT = "setup_import_provider_count"
        private const val KEY_ELIGIBLE_COUNT = "setup_import_eligible_count"
        private const val KEY_PROCESSED_COUNT = "setup_import_processed_count"
        private const val KEY_SAVED_COUNT = "setup_import_saved_count"
        private const val KEY_RETAINED_REVIEW_COUNT = "setup_import_retained_review_count"
        private const val KEY_REJECTED_COUNT = "setup_import_rejected_count"
        private const val KEY_FAILED_COUNT = "setup_import_failed_count"
        private const val KEY_LAST_SUCCESSFUL_SCAN =
            "setup_import_last_successful_scan"
        private const val KEY_RECENT_COVERAGE_START =
            "setup_import_recent_coverage_start"
        private const val KEY_RECENT_COVERAGE_END =
            "setup_import_recent_coverage_end"
        private const val KEY_RECENT_SCAN_WINDOW_DAYS =
            "setup_import_recent_scan_window_days"
        private const val KEY_RECENT_PROVIDER_COUNT =
            "setup_import_recent_provider_count"
        private const val KEY_RECENT_ELIGIBLE_COUNT =
            "setup_import_recent_eligible_count"
        private const val KEY_RECENT_PROCESSED_COUNT =
            "setup_import_recent_processed_count"
        private const val KEY_RECENT_SAVED_COUNT =
            "setup_import_recent_saved_count"
        private const val KEY_RECENT_REJECTED_COUNT =
            "setup_import_recent_rejected_count"
        private const val KEY_RECENT_FAILED_COUNT =
            "setup_import_recent_failed_count"
        private const val KEY_LAST_SUCCESSFUL_RECENT_SCAN =
            "setup_import_last_successful_recent_scan"
        private const val KEY_EMPTY_REASON = "setup_import_empty_reason"
        private const val KEY_PAUSE_REASON = "setup_import_pause_reason"
        private const val KEY_ERROR_CODE = "setup_import_error_code"
        private const val KEY_ERROR_MESSAGE = "setup_import_error_message"
        private const val KEY_ERROR_ACTION = "setup_import_error_action"
        private const val KEY_MODEL_DOWNLOAD_CONFIRMED =
            "setup_import_model_download_confirmed"
        private const val KEY_MODEL_PREPARED = "setup_import_model_prepared"
        private const val KEY_SELECTED_SLM_ID = "selected_slm_id"
        private const val KEY_ONBOARDING_RUN_GENERATION =
            "onboarding_run_generation"

        private fun initialState(hasSmsPermissions: Boolean): SetupImportState =
            SetupImportState(
                status = if (hasSmsPermissions) {
                    SetupImportStatus.NOT_STARTED
                } else {
                    SetupImportStatus.PERMISSION_NEEDED
                },
                pauseReason = if (hasSmsPermissions) {
                    null
                } else {
                    SetupPauseReason.PERMISSION_REVOKED
                }
            )

        private fun hasRequiredSmsPermissions(context: Context): Boolean =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_SMS
            ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECEIVE_SMS
                ) == PackageManager.PERMISSION_GRANTED
    }
}

private inline fun <reified T : Enum<T>> String?.toEnumOrNull(): T? =
    this?.let { value ->
        enumValues<T>().firstOrNull { it.name == value }
    }

private fun SharedPreferences.nullableLong(key: String): Long? =
    if (contains(key)) getLong(key, 0L) else null

private fun SharedPreferences.nullableInt(key: String): Int? =
    if (contains(key)) getInt(key, 0) else null

private fun SharedPreferences.Editor.putNullableLong(
    key: String,
    value: Long?
): SharedPreferences.Editor =
    if (value == null) remove(key) else putLong(key, value)

private fun SharedPreferences.Editor.putNullableInt(
    key: String,
    value: Int?
): SharedPreferences.Editor =
    if (value == null) remove(key) else putInt(key, value)

private fun SharedPreferences.Editor.putNullableString(
    key: String,
    value: String?
): SharedPreferences.Editor =
    if (value == null) remove(key) else putString(key, value)

private fun SharedPreferences.Editor.commitOrThrow() {
    check(commit()) { "Could not persist setup/import state" }
}
