package com.pocketfinancer.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketfinancer.SlmAppFlowCoordinator
import com.pocketfinancer.SlmAppFlowState
import com.pocketfinancer.data.model.Transaction
import com.pocketfinancer.data.model.TransactionType
import com.pocketfinancer.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.pocketfinancer.pipeline.SmsFilterPipeline
import com.pocketfinancer.pipeline.AutomaticProcessingPreferences
import com.pocketfinancer.pipeline.AutomaticSmsProcessingActivity
import com.pocketfinancer.pipeline.AutomaticSmsProcessingActivityStore
import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.hardware.isPublishedModelArtifact
import com.pocketfinancer.hardware.resolveActiveSlmTier
import com.pocketfinancer.inference.ModelDownloader
import com.pocketfinancer.inference.SlmModelStorage
import com.pocketfinancer.setup.SetupImportState
import com.pocketfinancer.setup.SetupImportStatus
import com.pocketfinancer.setup.SetupImportStore
import com.pocketfinancer.setup.reconcileSetupModelAvailability
import com.pocketfinancer.sms.SmsRepository
import com.pocketfinancer.ui.onboarding.OnboardingStep
import com.pocketfinancer.ui.onboarding.OnboardingSyncManager
import com.pocketfinancer.ui.onboarding.HistoricalSmsProcessingActivity
import com.pocketfinancer.ui.onboarding.withoutHistoricalSmsActivity
import com.pocketfinancer.ui.smsprocessing.SmsProcessingTarget
import java.util.Calendar
import javax.inject.Inject

data class PeriodData(
    val amount: Double = 0.0,
    val txnCount: Int = 0,
    val deltaDir: String = "same", // "less" | "more" | "same"
    val deltaLabel: String = "No spending in either period",
    val recent: List<Transaction> = emptyList()
)

data class ModelUpgradeRecommendation(
    val isUpgradeAvailable: Boolean = false,
    val recommendedSlm: SlmTier? = null,
    val currentSlm: SlmTier? = null,
    val downloadState: ModelDownloader.DownloadState = ModelDownloader.DownloadState(),
    val isDownloading: Boolean = false,
    val isRunning: Boolean = false,
    val isCancelling: Boolean = false,
    val canCancel: Boolean = false,
    val isApplying: Boolean = false,
    val statusMessage: String? = null,
    val error: String? = null,
    val isDebugEmulatorOverride: Boolean = false,
    val isDismissed: Boolean = false,
    val startBlockedMessage: String? = null
)

data class HomeUiState(
    val selectedPeriod: String = "Day", // "Day" | "Week" | "Month"
    val periodData: Map<String, PeriodData> = emptyMap(),
    val totalTransactionCount: Int = 0,
    val syncState: HomeSyncState = HomeSyncState(),
    val modelDownloadState: ModelDownloader.DownloadState = ModelDownloader.DownloadState(),
    val upgradeRecommendation: ModelUpgradeRecommendation = ModelUpgradeRecommendation(),
    val setupImportState: SetupImportState = SetupImportState(),
    val historicalImportCancelling: Boolean = false,
    val historicalImportCancellationAllowed: Boolean = false,
    val historicalImportRunning: Boolean = false,
    val historicalImportRunId: String? = null,
    val historicalImportFinishing: Boolean = false,
    val historicalImportPreparingModel: Boolean = false,
    val manualSmsOperationRunning: Boolean = false,
    val manualOperationStartPending: Boolean = false,
    val automaticProcessingEnabled: Boolean =
        AutomaticProcessingPreferences.DEFAULT_ENABLED
)

/**
 * Initial history import has one explicit phase at a time: preparation,
 * cancellable SMS work, cancellation drain, or non-cancellable final commit.
 */
internal fun historicalImportIsFinishing(
    state: OnboardingSyncManager.OnboardingSyncState
): Boolean =
    historicalImportIsRunning(state) &&
        !state.isPreparingHistoricalModel &&
        !state.isCancellationAllowed &&
        !state.isCancelling

internal fun historicalImportIsRunning(
    state: OnboardingSyncManager.OnboardingSyncState
): Boolean =
    state.runPurpose == OnboardingSyncManager.RunPurpose.INITIAL_SETUP &&
        state.isRunning

internal fun activeHistoricalSmsForHome(
    state: OnboardingSyncManager.OnboardingSyncState
): HistoricalSmsProcessingActivity? =
    state.activeHistoricalSms.takeIf { historicalImportIsRunning(state) }

internal fun HistoricalSmsProcessingActivity.cardSnapshot(): HistoricalSmsProcessingActivity =
    copy(
        modelName = null,
        grammarEnabled = null,
        answerTokenBudget = 0,
        decodedTokenDelta = "",
        jsonOutput = "",
        jsonOutputTruncated = false,
        performance = null,
        cache = null
    )

/**
 * Source evidence needed by the visible automatic card is retained, while
 * high-frequency and runtime-only telemetry remains available only to an open
 * details sheet.
 */
internal fun AutomaticSmsProcessingActivity.cardSnapshot():
    AutomaticSmsProcessingActivity = copy(
        modelName = null,
        grammarEnabled = null,
        answerTokenBudget = 0,
        decodedTokenDelta = "",
        jsonOutput = "",
        jsonOutputTruncated = false,
        performance = null,
        cache = null
    )

/**
 * The Home aggregate state needs stage and queue ownership, but not source
 * evidence or live model output. Sensitive fields are collected separately by
 * the visible screen and cleared from its Compose holder when the lifecycle
 * stops. Keeping them out of this replay cache prevents an off-screen Home
 * destination from retaining or briefly replaying a completed candidate.
 */
internal fun HomeSyncState.withoutManualSmsTelemetry(): HomeSyncState = copy(
    queue = queue.map { item ->
        item.copy(
            sender = "",
            body = ""
        )
    },
    decodedTokenDelta = "",
    jsonOutput = "",
    jsonOutputTruncated = false,
    activeSmsPerformance = null,
    activeModelName = null
)

/** Keeps source evidence for a visible card/queue but omits live model output. */
internal fun HomeSyncState.withoutManualLiveTelemetry(): HomeSyncState = copy(
    decodedTokenDelta = "",
    jsonOutput = "",
    jsonOutputTruncated = false,
    activeSmsPerformance = null,
    activeModelName = null
)

private fun sameManualStateOutsideQueueAndTelemetry(
    first: HomeSyncState,
    second: HomeSyncState
): Boolean =
    first.status == second.status &&
        first.activeRunId == second.activeRunId &&
        first.cancellationRequested == second.cancellationRequested &&
        first.currentIndex == second.currentIndex &&
        first.currentStageIndex == second.currentStageIndex &&
        first.recentScanOutcome == second.recentScanOutcome &&
        first.recentScanWindowDays == second.recentScanWindowDays &&
        first.lastSuccessfulScanMillis == second.lastSuccessfulScanMillis &&
        first.scanError == second.scanError &&
        first.syncError == second.syncError

private fun sameAggregateQueue(
    first: List<SyncSmsItem>,
    second: List<SyncSmsItem>
): Boolean {
    if (first === second) return true
    if (first.size != second.size) return false
    var index = 0
    while (index < first.size) {
        val firstItem = first[index]
        val secondItem = second[index]
        val sameItem = firstItem === secondItem ||
            (
                firstItem.id == secondItem.id &&
                    firstItem.date == secondItem.date &&
                    firstItem.messageType == secondItem.messageType &&
                    firstItem.sourceIdentity == secondItem.sourceIdentity &&
                    firstItem.status == secondItem.status &&
                    firstItem.parsedAmount == secondItem.parsedAmount &&
                    firstItem.parsedMerchant == secondItem.parsedMerchant
                )
        if (!sameItem) return false
        index += 1
    }
    return true
}

internal fun sameManualAggregateState(
    first: HomeSyncState,
    second: HomeSyncState
): Boolean = first === second ||
    (
        sameManualStateOutsideQueueAndTelemetry(first, second) &&
            sameAggregateQueue(first.queue, second.queue)
        )

internal fun sameManualPresentationState(
    first: HomeSyncState,
    second: HomeSyncState
): Boolean = first === second ||
    (
        sameManualStateOutsideQueueAndTelemetry(first, second) &&
            (
                first.queue === second.queue ||
                    first.queue == second.queue
                )
        )

/** Visible cards/queues retain source evidence but never observe token churn. */
internal fun manualSyncPresentationState(
    source: StateFlow<HomeSyncState>
): Flow<HomeSyncState> = flow {
    var previousSnapshot: HomeSyncState? = null
    source.collect { state ->
        val previous = previousSnapshot
        if (
            previous == null ||
            !sameManualPresentationState(previous, state)
        ) {
            val snapshot = state.withoutManualLiveTelemetry()
            previousSnapshot = snapshot
            emit(snapshot)
        }
    }
}

/**
 * Keeps only the scrubbed manual projection hot so off-screen terminal
 * transitions replace an obsolete LIVE snapshot without retaining evidence.
 */
internal fun sanitizedManualSyncState(
    source: StateFlow<HomeSyncState>,
    scope: CoroutineScope
): StateFlow<HomeSyncState> {
    val initialSource = source.value
    val initialValue = initialSource.withoutManualSmsTelemetry()
    return flow {
        var previousSnapshot = initialValue
        source.collect { state ->
            if (!sameManualAggregateState(previousSnapshot, state)) {
                val sanitizedQueue = if (
                    sameAggregateQueue(previousSnapshot.queue, state.queue)
                ) {
                    previousSnapshot.queue
                } else {
                    state.queue.map { item ->
                        item.copy(sender = "", body = "")
                    }
                }
                val snapshot = state.copy(
                    queue = sanitizedQueue,
                    decodedTokenDelta = "",
                    jsonOutput = "",
                    jsonOutputTruncated = false,
                    activeSmsPerformance = null,
                    activeModelName = null
                )
                previousSnapshot = snapshot
                emit(snapshot)
            }
        }
    }
        .stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = initialValue
    )
}

/** Ignores telemetry-only activity changes before creating a card snapshot. */
private fun sameHistoricalCardActivity(
    first: HistoricalSmsProcessingActivity?,
    second: HistoricalSmsProcessingActivity?
): Boolean {
    if (first === second) return true
    if (first == null || second == null) return false
    return first.candidateKey == second.candidateKey &&
        first.sender == second.sender &&
        first.body == second.body &&
        first.date == second.date &&
        first.position == second.position &&
        first.total == second.total &&
        first.stage == second.stage
}

/** Source-preserving historical card state exists only while Home collects it. */
internal fun historicalSmsCardState(
    source: StateFlow<OnboardingSyncManager.OnboardingSyncState>
): Flow<HistoricalSmsProcessingActivity?> = flow {
    var initialized = false
    var previousSnapshot: HistoricalSmsProcessingActivity? = null
    source.collect { state ->
        val activity = activeHistoricalSmsForHome(state)
        if (
            !initialized ||
            !sameHistoricalCardActivity(previousSnapshot, activity)
        ) {
            val snapshot = activity?.cardSnapshot()
            initialized = true
            previousSnapshot = snapshot
            emit(snapshot)
        }
    }
}

/** Ignores automatic token/runtime churn before allocating a card snapshot. */
private fun sameAutomaticCardActivity(
    first: AutomaticSmsProcessingActivity?,
    second: AutomaticSmsProcessingActivity?
): Boolean {
    if (first === second) return true
    if (first == null || second == null) return false
    return first.owner == second.owner &&
        first.sender == second.sender &&
        first.body == second.body &&
        first.date == second.date &&
        first.stage == second.stage &&
        first.detail == second.detail
}

/** Source-preserving automatic card state exists only while Home collects it. */
internal fun automaticSmsCardState(
    source: StateFlow<AutomaticSmsProcessingActivity?>
): Flow<AutomaticSmsProcessingActivity?> = flow {
    var initialized = false
    var previousSnapshot: AutomaticSmsProcessingActivity? = null
    source.collect { activity ->
        if (
            !initialized ||
            !sameAutomaticCardActivity(previousSnapshot, activity)
        ) {
            val snapshot = activity?.cardSnapshot()
            initialized = true
            previousSnapshot = snapshot
            emit(snapshot)
        }
    }
}

/**
 * Covers both an accepted service run and the short Android service-start
 * handoff before [HomeSyncManager] can publish its run id.
 */
internal fun manualSmsOperationIsRunning(
    state: HomeSyncState,
    startPending: Boolean = false
): Boolean =
    startPending ||
        state.activeRunId != null ||
        state.status in setOf(
            HomeSyncState.Status.SCANNING,
            HomeSyncState.Status.SYNCING,
            HomeSyncState.Status.CANCELLING
        )

internal fun HomeSyncState.ownsManualStopTarget(
    expectedRunId: String
): Boolean = expectedRunId.isNotBlank() && activeRunId == expectedRunId

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transactionRepository: TransactionRepository,
    private val syncManager: HomeSyncManager,
    private val smsFilterPipeline: SmsFilterPipeline,
    private val modelStorage: SlmModelStorage,
    private val deviceCapabilities: DeviceCapabilities,
    private val modelDownloader: ModelDownloader,
    private val onboardingSyncManager: OnboardingSyncManager,
    private val appFlowCoordinator: SlmAppFlowCoordinator,
    private val smsRepository: SmsRepository,
    private val setupImportStore: SetupImportStore,
    private val modelUpgradeSessionDismissalStore: ModelUpgradeSessionDismissalStore,
    private val automaticProcessingPreferences:
        AutomaticProcessingPreferences,
    private val automaticSmsProcessingActivityStore:
        AutomaticSmsProcessingActivityStore
) : ViewModel() {

    private val _selectedPeriod = MutableStateFlow("Day")
    val selectedPeriod: StateFlow<String> = _selectedPeriod.asStateFlow()
    private var requestedManualServiceRunId: String? = null

    val activeHistoricalSms: Flow<HistoricalSmsProcessingActivity?> =
        onboardingSyncManager.syncState.map(::activeHistoricalSmsForHome)

    /** Full evidence collected only by an open telemetry sheet. */
    val manualSyncTelemetry: StateFlow<HomeSyncState> = syncManager.syncState

    /** Source-preserving, telemetry-free state for the visible Home surface. */
    val manualSyncPresentation: Flow<HomeSyncState> =
        manualSyncPresentationState(syncManager.syncState)

    val activeHistoricalSmsCard: Flow<HistoricalSmsProcessingActivity?> =
        historicalSmsCardState(onboardingSyncManager.syncState)

    /** Full automatic evidence is collected only by an open telemetry sheet. */
    val automaticSmsTelemetry: StateFlow<AutomaticSmsProcessingActivity?> =
        automaticSmsProcessingActivityStore.activity

    /** Visible-card projection: source-preserving, cold, and token-scrubbed. */
    val automaticSmsPresentation: Flow<AutomaticSmsProcessingActivity?> =
        automaticSmsCardState(automaticSmsProcessingActivityStore.activity)

    private val onboardingUiState = onboardingSyncManager.syncState
        .map { it.withoutHistoricalSmsActivity() }
        .distinctUntilChanged()

    val manualSyncUiState = sanitizedManualSyncState(
        source = syncManager.syncState,
        scope = viewModelScope
    )

    val uiState: StateFlow<HomeUiState> = combine(
        transactionRepository.getAllByDateDesc(),
        _selectedPeriod,
        manualSyncUiState,
        modelDownloader.state,
        onboardingUiState,
        modelUpgradeSessionDismissalStore.dismissedTierIds,
        setupImportStore.state,
        automaticProcessingPreferences.enabled,
        appFlowCoordinator.state,
        syncManager.manualOperationReservation
    ) { flows ->
        @Suppress("UNCHECKED_CAST")
        val txs = flows[0] as List<Transaction>
        val period = flows[1] as String
        val syncState = flows[2] as HomeSyncState
        val downloadState = flows[3] as ModelDownloader.DownloadState
        val onboardingSyncState = flows[4] as OnboardingSyncManager.OnboardingSyncState
        @Suppress("UNCHECKED_CAST")
        val dismissedTierIds = flows[5] as Set<String>
        val setupImportState = flows[6] as SetupImportState
        val automaticProcessingEnabled = flows[7] as Boolean
        val appFlowState = flows[8] as SlmAppFlowState
        val manualOperationStartPending =
            (flows[9] as ManualOperationReservation?) != null

        val periodDataMap = calculatePeriodData(txs)
        val device = deviceCapabilities.assessDevice()
        val currentSlm = resolveActiveSlmTier(context, modelStorage.modelDirectory, device)
        val allowDebugOverride = allowDebugEmulatorModelUpgrade(context)
        val upgradeTarget = selectModelUpgradeTarget(
            device = device,
            allowDebugEmulatorOverride = allowDebugOverride
        )
        // Once a model-upgrade operation starts, its target is an immutable
        // operation snapshot. Keep showing that same target after cancellation
        // or failure even if live storage changes while the partial/final
        // artifact is written.
        val unfinishedManagedTarget =
            unfinishedModelUpgradeTarget(onboardingSyncState)
        val recommendedSlm = unfinishedManagedTarget ?: upgradeTarget.tier
        val hasUpgrade = isHigherQualityModel(currentSlm, recommendedSlm)
        val isThisUpgradeRun =
            onboardingSyncState.runPurpose ==
                OnboardingSyncManager.RunPurpose.MODEL_UPGRADE &&
                onboardingSyncState.selectedSlm == recommendedSlm
        val isUpgradeRunning = isThisUpgradeRun && onboardingSyncState.isRunning
        val activeDs = if (isThisUpgradeRun) {
            onboardingSyncState.downloadState
        } else {
            downloadState
        }

        val upgradeRec = ModelUpgradeRecommendation(
            isUpgradeAvailable = hasUpgrade,
            recommendedSlm = recommendedSlm,
            currentSlm = currentSlm,
            downloadState = activeDs,
            isDownloading =
                isUpgradeRunning && onboardingSyncState.isDownloading,
            isRunning = isUpgradeRunning,
            isCancelling =
                isUpgradeRunning && onboardingSyncState.isCancelling,
            canCancel =
                isUpgradeRunning &&
                    onboardingSyncState.isCancellationAllowed &&
                    !onboardingSyncState.isCancelling,
            isApplying =
                isUpgradeRunning &&
                    !onboardingSyncState.isCancelling &&
                    onboardingSyncState.step == OnboardingStep.SYNCING,
            statusMessage = onboardingSyncState.syncMessage
                .takeIf { isThisUpgradeRun && it.isNotBlank() },
            error = onboardingSyncState.modelLoadError
                .takeIf { isThisUpgradeRun },
            isDebugEmulatorOverride =
                upgradeTarget.isDebugEmulatorOverride ||
                    (
                        allowDebugOverride &&
                            unfinishedManagedTarget ==
                                SlmTier.QWEN3_1_7B_Q4_K_M &&
                            selectModelUpgradeTarget(
                                device = device,
                                allowDebugEmulatorOverride = false
                            ).tier == SlmTier.DEFAULT_ONBOARDING_SLM
                    ),
            isDismissed = recommendedSlm?.id in dismissedTierIds,
            startBlockedMessage = modelUpgradeStartBlockedMessage(
                onboarding = onboardingSyncState,
                otherFlowBusy = manualSmsOperationIsRunning(
                    state = syncState,
                    startPending = manualOperationStartPending
                ) ||
                    downloadState.isDownloading ||
                    appFlowState.activeCount > 0 ||
                    appFlowState.admissionPaused
            )
        )

        val isHistoricalRun =
            onboardingSyncState.runPurpose ==
                OnboardingSyncManager.RunPurpose.INITIAL_SETUP &&
                onboardingSyncState.isRunning
        val manualSmsOperationRunning = manualSmsOperationIsRunning(
            state = syncState,
            startPending = manualOperationStartPending
        )
        HomeUiState(
            selectedPeriod = period,
            periodData = periodDataMap,
            totalTransactionCount = txs.size,
            syncState = syncState,
            modelDownloadState = downloadState,
            upgradeRecommendation = upgradeRec,
            setupImportState = setupImportState,
            historicalImportCancelling =
                isHistoricalRun && onboardingSyncState.isCancelling,
            historicalImportCancellationAllowed =
                isHistoricalRun &&
                    onboardingSyncState.isCancellationAllowed &&
                    !onboardingSyncState.isCancelling,
            historicalImportRunning = isHistoricalRun,
            historicalImportRunId = onboardingSyncState.runId
                .takeIf { isHistoricalRun },
            historicalImportFinishing =
                historicalImportIsFinishing(onboardingSyncState),
            historicalImportPreparingModel =
                isHistoricalRun &&
                    onboardingSyncState.isPreparingHistoricalModel &&
                    !onboardingSyncState.isCancelling,
            manualSmsOperationRunning = manualSmsOperationRunning,
            manualOperationStartPending = manualOperationStartPending,
            automaticProcessingEnabled = automaticProcessingEnabled
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState()
    )

    init {
        refreshPermissionHealth()
        viewModelScope.launch {
            syncManager.serviceStartAcknowledgement
                .filterNotNull()
                .collect { acknowledgement ->
                    val matchesRequest =
                        acknowledgement.runId ==
                        requestedManualServiceRunId
                    if (matchesRequest) {
                        requestedManualServiceRunId = null
                    }
                    if (matchesRequest && !acknowledgement.accepted) {
                        showManualStartFailure()
                    }
                }
        }
    }

    fun selectPeriod(period: String) {
        _selectedPeriod.value = period
    }

    fun checkForUnsynced() {
        val reservationId = syncManager.withSmsOperationStartBoundary {
            if (
                onboardingSyncManager.syncState.value.isRunning ||
                manualSmsOperationIsRunning(
                    state = syncManager.syncState.value,
                    startPending =
                        syncManager.manualOperationReservation.value != null
                ) ||
                !manualRecentSyncAvailable(
                    setupImportStore.state.value.status
                )
            ) {
                null
            } else if (!smsRepository.hasPermissions()) {
                setupImportStore.reconcilePermission(granted = false)
                null
            } else {
                syncManager.tryReserveRecentScan()
            }
        }
        if (reservationId == null) return

        viewModelScope.launch {
            try {
                syncManager.checkForUnsyncedSms()
            } finally {
                syncManager.releaseManualOperationReservation(reservationId)
            }
            val scanState = syncManager.syncState.value
            val pendingCount = scanState.queue.count { it.status == "pending" }
            val toastMsg = when (scanState.recentScanOutcome) {
                HomeSyncState.RecentScanOutcome.FAILED ->
                    scanState.scanError ?: "The recent SMS scan failed."
                HomeSyncState.RecentScanOutcome.PERMISSION_NEEDED ->
                    "Restore SMS access to scan recent alerts."
                HomeSyncState.RecentScanOutcome.SUCCESS ->
                    if (pendingCount > 0) {
                        "Found $pendingCount eligible message" +
                            if (pendingCount == 1) {
                                " ready to process."
                            } else {
                                "s ready to process."
                            }
                    } else {
                        "No new eligible alerts were found in this recent scan."
                    }
                HomeSyncState.RecentScanOutcome.NOT_RUN ->
                    scanState.scanError
                        ?: "The recent SMS scan did not start."
            }
            android.widget.Toast.makeText(context, toastMsg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun startSync() {
        val runId = syncManager.withSmsOperationStartBoundary {
            if (
                onboardingSyncManager.syncState.value.isRunning ||
                manualSmsOperationIsRunning(
                    state = syncManager.syncState.value,
                    startPending =
                        syncManager.manualOperationReservation.value != null
                ) ||
                !manualRecentSyncAvailable(
                    setupImportStore.state.value.status
                )
            ) {
                null
            } else if (!smsRepository.hasPermissions()) {
                setupImportStore.reconcilePermission(granted = false)
                null
            } else {
                syncManager.tryReserveServiceStart()
            }
        }
        if (runId == null) return
        requestedManualServiceRunId = runId

        try {
            SyncService.start(context, runId)
        } catch (_: RuntimeException) {
            syncManager.acknowledgeServiceStart(
                runId = runId,
                accepted = false
            )
        }
    }

    private fun showManualStartFailure() {
        android.widget.Toast.makeText(
            context,
            "SMS processing did not start. Please try again.",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    fun stopManualSync(target: SmsProcessingTarget.ManualRecent) {
        requestManualSyncStop(
            expectedTarget = target,
            showFailureToast = true
        )
    }

    private fun requestManualSyncStop(
        expectedTarget: SmsProcessingTarget.ManualRecent? = null,
        showFailureToast: Boolean
    ): Boolean {
        val state = syncManager.syncState.value
        if (expectedTarget != null) {
            val rejectionMessage = manualSyncPreDispatchRejectionMessage(
                state = state,
                target = expectedTarget
            )
            if (rejectionMessage != null) {
                if (showFailureToast) {
                    showManualSyncStopRejectionFeedback(
                        context = context,
                        message = rejectionMessage
                    )
                }
                return false
            }
        }
        val runId = state.activeRunId ?: return false
        val commandAccepted = try {
            if (expectedTarget != null) {
                SyncService.requestStop(context, expectedTarget)
            } else {
                SyncService.requestStop(context, runId)
            }
        } catch (_: RuntimeException) {
            false
        }
        if (!commandAccepted && showFailureToast) {
            android.widget.Toast.makeText(
                context,
                "Could not request a stop. Please try again.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return false
        }
        if (!commandAccepted) return false
        if (expectedTarget != null) {
            // The service performs the exact run/candidate CAS when Android
            // delivers this command. A local run-only update here would let a
            // delayed action cancel the next candidate in the same run.
            return true
        }
        // Publish only after Android accepts the run-scoped command. The UI
        // still changes immediately, without creating a rollback window in
        // which the worker can observe a stop that was never dispatched.
        return syncManager.requestServiceStop(runId)
    }

    fun stopHistoricalImport(target: SmsProcessingTarget.Historical) {
        val requested =
            onboardingSyncManager.requestHistoricalImportCancellation(
                context = context,
                expectedRunId = target.runId,
                expectedCandidateKey = target.candidateKey
            )
        if (
            !requested &&
            onboardingSyncManager.syncState.value.runId == target.runId &&
            onboardingSyncManager.syncState.value.activeHistoricalSms
                ?.candidateKey == target.candidateKey &&
            onboardingSyncManager.syncState.value.isCancellationAllowed
        ) {
            android.widget.Toast.makeText(
                context,
                "Could not request a stop. Please try again.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun refreshPermissionHealth() {
        reconcilePreparedModelAvailability()
        reconcileSmsPermission(smsRepository.hasPermissions())
    }

    fun onSmsPermissionResult(granted: Boolean) {
        reconcileSmsPermission(granted)
    }

    private fun reconcileSmsPermission(granted: Boolean) {
        setupImportStore.reconcilePermission(granted)
        if (!granted) {
            // Revocation is also a stop signal for any already-read batch.
            // Prior commits remain, while native work is cancelled and drained
            // instead of continuing invisibly behind a permission card.
            requestManualSyncStop(showFailureToast = false)
            onboardingSyncManager.requestHistoricalImportCancellation(context)
        }
    }

    fun confirmModelDownload() {
        setupImportStore.setModelDownloadConfirmed(true)
    }

    fun startSetupOrResume() {
        syncManager.withSmsOperationStartBoundary {
            if (
                onboardingSyncManager.syncState.value.isRunning ||
                manualSmsOperationIsRunning(
                    state = syncManager.syncState.value,
                    startPending =
                        syncManager.manualOperationReservation.value != null
                )
            ) return@withSmsOperationStartBoundary

            val setup = reconcilePreparedModelAvailability()
            if (!smsRepository.hasPermissions()) {
                setupImportStore.reconcilePermission(granted = false)
                return@withSmsOperationStartBoundary
            }
            if (!setup.modelPrepared && !setup.modelDownloadConfirmed) {
                return@withSmsOperationStartBoundary
            }

            val resumableWindow =
                (setup.activeScanWindowDays ?: setup.coverageWindowDays)
                ?.takeIf {
                    setup.status in setOf(
                        SetupImportStatus.PAUSED,
                        SetupImportStatus.FAILED
                    )
                }
            if (resumableWindow != null) {
                onboardingSyncManager.resumeHistoricalImport(
                    context = context,
                    slm = currentSetupTier(),
                    resumeWindowDays = resumableWindow
                )
            } else {
                onboardingSyncManager.startOnboarding(
                    context,
                    currentSetupTier()
                )
            }
        }
    }

    fun scanOlderMessages() {
        syncManager.withSmsOperationStartBoundary {
            if (
                onboardingSyncManager.syncState.value.isRunning ||
                manualSmsOperationIsRunning(
                    state = syncManager.syncState.value,
                    startPending =
                        syncManager.manualOperationReservation.value != null
                )
            ) return@withSmsOperationStartBoundary

            if (!smsRepository.hasPermissions()) {
                setupImportStore.reconcilePermission(granted = false)
                return@withSmsOperationStartBoundary
            }
            val setup = reconcilePreparedModelAvailability()
            if (!setup.modelPrepared) {
                startSetupOrResume()
                return@withSmsOperationStartBoundary
            }
            onboardingSyncManager.startHistoricalImport(
                context = context,
                slm = currentSetupTier(),
                coveredWindowDays = setup.coverageWindowDays
            )
        }
    }

    fun startModelUpgrade() {
        syncManager.withSmsOperationStartBoundary {
            val onboarding = onboardingSyncManager.syncState.value
            val appFlows = appFlowCoordinator.state.value
            val otherFlowBusy =
                manualSmsOperationIsRunning(
                    state = syncManager.syncState.value,
                    startPending =
                        syncManager.manualOperationReservation.value != null
                ) ||
                    modelDownloader.state.value.isDownloading ||
                    appFlows.activeCount > 0 ||
                    appFlows.admissionPaused
            if (
                modelUpgradeStartBlockedMessage(
                    onboarding = onboarding,
                    otherFlowBusy = otherFlowBusy
                ) != null
            ) return@withSmsOperationStartBoundary

            val device = deviceCapabilities.assessDevice()
            val recommendedSlm =
                unfinishedModelUpgradeTarget(onboarding)
                    ?: selectModelUpgradeTarget(
                        device = device,
                        allowDebugEmulatorOverride =
                            allowDebugEmulatorModelUpgrade(context)
                    ).tier
                ?: return@withSmsOperationStartBoundary
            onboardingSyncManager.startModelUpgrade(context, recommendedSlm)
        }
    }

    fun cancelModelUpgrade() {
        val activeRun = onboardingSyncManager.syncState.value
        if (!canCancelModelUpgrade(activeRun)) return

        // The downloader keeps its partial artifact, so starting the upgrade
        // again resumes rather than discarding already downloaded bytes.
        onboardingSyncManager.requestModelUpgradeCancellation(context)
    }

    fun dismissUpgradeBanner() {
        uiState.value.upgradeRecommendation.recommendedSlm
            ?.let { modelUpgradeSessionDismissalStore.dismiss(it.id) }
    }

    fun resetSyncState() {
        syncManager.resetState()
    }

    private fun currentSetupTier(): SlmTier {
        val preparedModel = publishedSetupTier()
        return setupTierForPreparation(
            modelPrepared =
                setupImportStore.state.value.modelPrepared &&
                    preparedModel != null,
            resolvedActiveTier = preparedModel
        )
    }

    /**
     * Setup may reuse only a model artifact that is actually published on
     * disk. Hardware recommendation is intentionally not a fallback here:
     * before preparation it could turn the explicit ~700 MB confirmation into
     * an unconfirmed multi-gigabyte download.
     */
    private fun publishedSetupTier(): SlmTier? {
        val selectedId = context
            .getSharedPreferences(APP_SETTINGS, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_SLM_ID, null)
        val selectedTier = SlmTier.ALL_TIERS.find { it.id == selectedId }
        return buildList {
            selectedTier?.let(::add)
            add(SlmTier.DEFAULT_ONBOARDING_SLM)
            addAll(SlmTier.ALL_TIERS)
        }
            .distinctBy { it.id }
            .firstOrNull { tier ->
                isPublishedModelArtifact(
                    modelStorage.modelFile(tier.modelFile)
                )
            }
    }

    private fun reconcilePreparedModelAvailability(): SetupImportState {
        return setupImportStore.reconcileModelAvailability(
            hasPublishedModel = publishedSetupTier() != null
        )
    }

    private fun calculatePeriodData(txs: List<Transaction>): Map<String, PeriodData> {
        // Date timestamps
        val now = System.currentTimeMillis()

        // 1. Today calculations
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayStart = calendar.timeInMillis
        val yesterdayStart = todayStart - 24 * 60 * 60 * 1000L
        val yesterdayEnd = todayStart - 1

        val todayDebits = txs.filter { it.date >= todayStart && it.type == TransactionType.DEBIT }
        val todayAmount = todayDebits.sumOf { it.amount }
        val todayCount = todayDebits.size
        val yesterdayAmount = txs.filter { it.date in yesterdayStart..yesterdayEnd && it.type == TransactionType.DEBIT }.sumOf { it.amount }
        
        val todayComparison = spendingComparison(
            currentAmount = todayAmount,
            previousAmount = yesterdayAmount,
            previousPeriodLabel = "yesterday"
        )

        // 2. Week calculations (Start of current week, e.g. Monday)
        val weekCal = Calendar.getInstance()
        weekCal.set(Calendar.HOUR_OF_DAY, 0)
        weekCal.set(Calendar.MINUTE, 0)
        weekCal.set(Calendar.SECOND, 0)
        weekCal.set(Calendar.MILLISECOND, 0)
        // Set to Monday of this week
        val currentDayOfWeek = weekCal.get(Calendar.DAY_OF_WEEK)
        val daysDiff = if (currentDayOfWeek == Calendar.SUNDAY) -6 else Calendar.MONDAY - currentDayOfWeek
        weekCal.add(Calendar.DAY_OF_YEAR, daysDiff)
        val thisWeekStart = weekCal.timeInMillis
        val lastWeekStart = thisWeekStart - 7 * 24 * 60 * 60 * 1000L
        val lastWeekEnd = thisWeekStart - 1

        val thisWeekDebits = txs.filter { it.date >= thisWeekStart && it.type == TransactionType.DEBIT }
        val thisWeekAmount = thisWeekDebits.sumOf { it.amount }
        val thisWeekCount = thisWeekDebits.size
        val lastWeekAmount = txs.filter { it.date in lastWeekStart..lastWeekEnd && it.type == TransactionType.DEBIT }.sumOf { it.amount }

        val weekComparison = spendingComparison(
            currentAmount = thisWeekAmount,
            previousAmount = lastWeekAmount,
            previousPeriodLabel = "last week"
        )

        // 3. Month calculations
        val monthCal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val thisMonthStart = monthCal.timeInMillis
        monthCal.add(Calendar.MONTH, -1)
        val lastMonthStart = monthCal.timeInMillis
        val lastMonthEnd = thisMonthStart - 1

        val thisMonthDebits = txs.filter { it.date >= thisMonthStart && it.type == TransactionType.DEBIT }
        val thisMonthAmount = thisMonthDebits.sumOf { it.amount }
        val thisMonthCount = thisMonthDebits.size
        val lastMonthAmount = txs.filter { it.date in lastMonthStart..lastMonthEnd && it.type == TransactionType.DEBIT }.sumOf { it.amount }

        val monthComparison = spendingComparison(
            currentAmount = thisMonthAmount,
            previousAmount = lastMonthAmount,
            previousPeriodLabel = "last month"
        )

        return mapOf(
            "Day" to PeriodData(
                amount = todayAmount,
                txnCount = todayCount,
                deltaDir = todayComparison.direction,
                deltaLabel = todayComparison.label,
                recent = todayDebits.take(5)
            ),
            "Week" to PeriodData(
                amount = thisWeekAmount,
                txnCount = thisWeekCount,
                deltaDir = weekComparison.direction,
                deltaLabel = weekComparison.label,
                recent = thisWeekDebits.take(5)
            ),
            "Month" to PeriodData(
                amount = thisMonthAmount,
                txnCount = thisMonthCount,
                deltaDir = monthComparison.direction,
                deltaLabel = monthComparison.label,
                recent = thisMonthDebits.take(5)
            )
        )
    }

    fun getFilterLogs(item: SyncSmsItem): List<String> {
        if (!item.hasDiagnosticSourceEvidence()) {
            return listOf(SOURCE_EVIDENCE_UNAVAILABLE)
        }
        return smsFilterPipeline.filterWithDetails(item.sender, item.body).logs
    }

    fun getHistoricalFilterLogs(
        activity: HistoricalSmsProcessingActivity
    ): List<String> = smsFilterPipeline
        .filterWithDetails(activity.sender, activity.body)
        .logs

    fun getAutomaticFilterLogs(
        activity: AutomaticSmsProcessingActivity
    ): List<String> = smsFilterPipeline
        .filterWithDetails(activity.sender, activity.body)
        .logs

    fun getHistoricalPromptContent(
        @Suppress("UNUSED_PARAMETER") activity: HistoricalSmsProcessingActivity
    ): String = DECISION_TRACE_MESSAGE

    fun getAutomaticPromptContent(
        @Suppress("UNUSED_PARAMETER") activity: AutomaticSmsProcessingActivity
    ): String = DECISION_TRACE_MESSAGE

    fun getKvCacheLogs(item: SyncSmsItem): List<String> {
        if (!item.hasDiagnosticSourceEvidence()) {
            return listOf(SOURCE_EVIDENCE_UNAVAILABLE)
        }
        return listOf(
            "KV cache telemetry is captured from the exact runtime request.",
            "Historical transactions do not currently persist cache-hit diagnostics."
        )
    }

    fun getSlmPrompt(item: SyncSmsItem): String {
        if (!item.hasDiagnosticSourceEvidence()) {
            return SOURCE_EVIDENCE_UNAVAILABLE
        }
        return DECISION_TRACE_MESSAGE
    }

    private companion object {
        const val SOURCE_EVIDENCE_UNAVAILABLE =
            "Source evidence is unavailable after terminal processing."
        const val DECISION_TRACE_MESSAGE =
            "The legacy extraction prompt is no longer used. Open Saved alert reviews to inspect the durable Decision Trace."
        const val APP_SETTINGS = ".app_settings"
        const val KEY_SELECTED_SLM_ID = "selected_slm_id"
    }

}

internal fun setupTierForPreparation(
    modelPrepared: Boolean,
    resolvedActiveTier: SlmTier?
): SlmTier = if (modelPrepared) {
    resolvedActiveTier ?: SlmTier.DEFAULT_ONBOARDING_SLM
} else {
    SlmTier.DEFAULT_ONBOARDING_SLM
}

internal fun canCancelModelUpgrade(
    state: OnboardingSyncManager.OnboardingSyncState
): Boolean =
    state.isRunning &&
        !state.isCancelling &&
        state.isCancellationAllowed &&
        state.runPurpose == OnboardingSyncManager.RunPurpose.MODEL_UPGRADE

internal fun unfinishedModelUpgradeTarget(
    state: OnboardingSyncManager.OnboardingSyncState
): SlmTier? =
    state.selectedSlm.takeIf {
        state.runPurpose == OnboardingSyncManager.RunPurpose.MODEL_UPGRADE &&
            state.step != OnboardingStep.COMPLETED
    }

internal data class SpendingComparison(
    val direction: String,
    val label: String
)

internal fun spendingComparison(
    currentAmount: Double,
    previousAmount: Double,
    previousPeriodLabel: String
): SpendingComparison {
    val delta = currentAmount - previousAmount
    if (kotlin.math.abs(delta) < 0.005) {
        return SpendingComparison(
            direction = "same",
            label = if (currentAmount == 0.0 && previousAmount == 0.0) {
                "No spending in either period"
            } else {
                "Same as $previousPeriodLabel"
            }
        )
    }
    val direction = if (delta > 0) "more" else "less"
    return SpendingComparison(
        direction = direction,
        label =
            "₹${String.format("%,.0f", kotlin.math.abs(delta))} " +
                "$direction than $previousPeriodLabel"
    )
}
