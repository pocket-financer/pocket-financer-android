package com.pocketfinancer.ui.home

import android.content.Context
import android.util.Log
import com.pocketfinancer.SlmAppFlowCoordinator
import com.pocketfinancer.SlmAppFlowLease
import com.pocketfinancer.data.model.SmsSourceIdentity
import com.pocketfinancer.data.repository.TransactionRepository
import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.hardware.resolveActiveSlmTier
import com.pocketfinancer.inference.SlmLease
import com.pocketfinancer.inference.SlmModelStorage
import com.pocketfinancer.inference.SlmRuntime
import com.pocketfinancer.inference.SlmRuntimeOwner
import com.pocketfinancer.pipeline.PipelineService
import com.pocketfinancer.pipeline.SmsFilterPipeline
import com.pocketfinancer.setup.AdaptiveHistoryScanPolicy
import com.pocketfinancer.setup.SetupActionableError
import com.pocketfinancer.setup.SetupImportState
import com.pocketfinancer.setup.SetupImportStore
import com.pocketfinancer.sms.SmsRepository
import com.pocketfinancer.sms.SmsReader
import com.pocketfinancer.toModelSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SyncSmsItem(
    val id: String,
    val sender: String,
    val body: String,
    val date: Long,
    val messageType: Int = 1,
    val sourceIdentity: SmsSourceIdentity = SmsSourceIdentity.androidSms(
        providerMessageId = null,
        sender = sender,
        body = body,
        sourceTimestamp = date,
        messageType = messageType
    ),
    var status: String,
    var parsedAmount: Double? = null,
    var parsedMerchant: String? = null
)

internal val SOURCE_EVIDENCE_DISCARDED_STATUSES = setOf(
    "synced",
    "already_saved",
    "filtered_out"
)

/**
 * Applies a queue-state transition without leaving source evidence in the
 * process-wide Home singleton after that evidence has a durable owner (or has
 * been rejected). Retryable states deliberately keep the source so the same
 * candidate can be attempted again.
 */
internal fun SyncSmsItem.withPrivacySafeStatus(status: String): SyncSmsItem =
    when (status) {
        "synced" -> copy(
            sender = "Saved transaction",
            body = "",
            status = status
        )
        "already_saved" -> copy(
            sender = "Already in ledger",
            body = "",
            status = status,
            parsedAmount = null,
            parsedMerchant = null
        )
        "filtered_out" -> copy(
            sender = "Rejected alert",
            body = "",
            status = status,
            parsedAmount = null,
            parsedMerchant = null
        )
        else -> copy(status = status)
    }

internal fun SyncSmsItem.hasDiagnosticSourceEvidence(): Boolean =
    status !in SOURCE_EVIDENCE_DISCARDED_STATUSES &&
        body.isNotBlank()

/**
 * Provider ids are authoritative when both sides have them. Fingerprints are
 * only a bridge for a provider-less broadcast meeting its later provider row;
 * using them between two provider rows would collapse legitimate identical
 * messages that have distinct Android `_id` values.
 */
internal fun sameQueuedSmsSource(
    first: SmsSourceIdentity,
    second: SmsSourceIdentity
): Boolean {
    if (first.connector != second.connector) return false
    if (first.messageId == second.messageId) return true
    if (
        first.providerMessageId != null &&
        second.providerMessageId != null
    ) {
        return false
    }
    val firstFingerprints = setOfNotNull(
        first.fallbackFingerprint,
        first.alternateFingerprint
    )
    val secondFingerprints = setOfNotNull(
        second.fallbackFingerprint,
        second.alternateFingerprint
    )
    return firstFingerprints.any(secondFingerprints::contains)
}

private fun List<SyncSmsItem>.distinctSmsSources(): List<SyncSmsItem> {
    // Provider-backed identities are considered first so one ambiguous
    // provider-less broadcast can bridge to at most one authoritative row,
    // never collapse two distinct provider rows.
    val providerBacked = filter {
        it.sourceIdentity.providerMessageId != null
    }
    val providerLess = filter {
        it.sourceIdentity.providerMessageId == null
    }
    return (providerBacked + providerLess)
        .fold(mutableListOf()) { distinct, item ->
            if (
                distinct.none {
                    sameQueuedSmsSource(
                        it.sourceIdentity,
                        item.sourceIdentity
                    )
                }
            ) {
                distinct += item
            }
            distinct
        }
}

internal fun mergeRecentScanQueue(
    currentQueue: List<SyncSmsItem>,
    providerMessages: List<SmsReader.SmsMessage>
): List<SyncSmsItem> {
    val unmatchedCurrent = currentQueue.toMutableList()
    val scanned = providerMessages.map { message ->
        val source = message.sourceIdentity
        val exactIndex = unmatchedCurrent.indexOfFirst {
            it.sourceIdentity.connector == source.connector &&
                it.sourceIdentity.messageId == source.messageId
        }
        val bridgeIndex = if (exactIndex >= 0) {
            -1
        } else {
            unmatchedCurrent.indexOfFirst {
                sameQueuedSmsSource(it.sourceIdentity, source)
            }
        }
        val matchedIndex = exactIndex.takeIf { it >= 0 } ?: bridgeIndex
        val existing = matchedIndex
            .takeIf { it >= 0 }
            ?.let(unmatchedCurrent::removeAt)
        if (
            existing != null &&
            existing.sourceIdentity.providerMessageId != null
        ) {
            existing
        } else {
            // A provider row enriches the one provider-less queue item it can
            // unambiguously consume. Its authoritative id then keeps any
            // second byte-identical provider row distinct.
            SyncSmsItem(
                id = source.opaqueCandidateKey,
                sender = message.address,
                body = message.body,
                date = message.date,
                messageType = message.type,
                sourceIdentity = source,
                status = existing?.status ?: "pending"
            )
        }
    }
    return (scanned + unmatchedCurrent).distinctSmsSources()
}

data class HomeSyncState(
    val status: Status = Status.IDLE,
    val activeRunId: String? = null,
    val cancellationRequested: Boolean = false,
    val queue: List<SyncSmsItem> = emptyList(),
    val currentIndex: Int? = null,
    val currentStageIndex: Int? = null,
    val decodedTokenDelta: String = "",
    val jsonOutput: String = "",
    val jsonOutputTruncated: Boolean = false,
    val activeSmsPerformance: String? = null,
    val activeModelName: String? = null,
    val recentScanOutcome: RecentScanOutcome = RecentScanOutcome.NOT_RUN,
    val recentScanWindowDays: Int? = null,
    val lastSuccessfulScanMillis: Long? = null,
    val scanError: String? = null,
    val syncError: String? = null
) {
    enum class Status {
        IDLE, SCANNING, SYNCING, CANCELLING, DONE
    }

    enum class RecentScanOutcome {
        NOT_RUN, SUCCESS, PERMISSION_NEEDED, FAILED
    }
}

internal data class ManualServiceStartAcknowledgement(
    val runId: String,
    val accepted: Boolean
)

internal enum class ManualOperationReservationKind {
    RECENT_SCAN,
    SERVICE_START
}

internal data class ManualOperationReservation(
    val id: String,
    val kind: ManualOperationReservationKind
)

internal data class ManualSmsTelemetryOwner(
    val runId: String?,
    val candidateKey: String,
    val attemptToken: String
) {
    init {
        require(candidateKey.isNotBlank())
        require(attemptToken.isNotBlank())
    }
}

@Singleton
class HomeSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val smsRepository: SmsRepository,
    private val smsFilterPipeline: SmsFilterPipeline,
    private val transactionRepository: TransactionRepository,
    private val slmRuntime: SlmRuntime,
    private val appFlowCoordinator: SlmAppFlowCoordinator,
    private val modelStorage: SlmModelStorage,
    private val deviceCapabilities: DeviceCapabilities,
    private val setupImportStore: SetupImportStore,
    private val pipelineService: PipelineService
) {
    private val historyScanPolicy = AdaptiveHistoryScanPolicy()
    /**
     * One operation boundary owns both provider reads and queue execution.
     * Separate locks allow a scan to replace the queue while inference is
     * mutating it, so the full operations intentionally share this mutex.
     */
    private val operationMutex = Mutex()
    private var recentScanCandidateKeys: Set<String> = emptySet()
    private var recentScanCompletedAtMillis: Long? = null
    private val recentScanTrackingLock = Any()
    private val _syncState = MutableStateFlow(HomeSyncState())
    val syncState: StateFlow<HomeSyncState> = _syncState.asStateFlow()
    private val activeManualSmsTelemetryOwner =
        AtomicReference<ManualSmsTelemetryOwner?>(null)
    private val _serviceStartAcknowledgement =
        MutableStateFlow<ManualServiceStartAcknowledgement?>(null)
    internal val serviceStartAcknowledgement:
        StateFlow<ManualServiceStartAcknowledgement?> =
        _serviceStartAcknowledgement.asStateFlow()
    private val serviceStartAdmissionLock = Any()
    private val revokedServiceStartRunIds = mutableSetOf<String>()
    private val serviceStartScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _manualOperationReservation =
        MutableStateFlow<ManualOperationReservation?>(null)
    internal val manualOperationReservation:
        StateFlow<ManualOperationReservation?> =
        _manualOperationReservation.asStateFlow()

    /** One process-wide boundary orders all Home SMS/setup start decisions. */
    internal fun <T> withSmsOperationStartBoundary(
        block: () -> T
    ): T = synchronized(serviceStartAdmissionLock) { block() }

    internal fun tryReserveRecentScan(): String? =
        reserveManualOperation(ManualOperationReservationKind.RECENT_SCAN)

    internal fun tryReserveServiceStart(): String? {
        val runId = reserveManualOperation(
            ManualOperationReservationKind.SERVICE_START
        ) ?: return null
        serviceStartScope.launch {
            delay(MANUAL_SERVICE_START_ACK_TIMEOUT_MILLIS)
            revokeUnacknowledgedServiceStart(runId)
        }
        return runId
    }

    private fun reserveManualOperation(
        kind: ManualOperationReservationKind
    ): String? = synchronized(serviceStartAdmissionLock) {
        val state = _syncState.value
        if (
            _manualOperationReservation.value != null ||
            state.activeRunId != null ||
            state.status in ACTIVE_SERVICE_STATUSES
        ) {
            return@synchronized null
        }
        val id = UUID.randomUUID().toString()
        _manualOperationReservation.value = ManualOperationReservation(id, kind)
        id
    }

    internal fun releaseManualOperationReservation(id: String) {
        synchronized(serviceStartAdmissionLock) {
            if (_manualOperationReservation.value?.id == id) {
                _manualOperationReservation.value = null
            }
        }
    }

    internal fun acknowledgeServiceStart(
        runId: String,
        accepted: Boolean
    ) {
        if (runId.isBlank()) return
        synchronized(serviceStartAdmissionLock) {
            if (
                _manualOperationReservation.value?.id == runId &&
                _manualOperationReservation.value?.kind ==
                    ManualOperationReservationKind.SERVICE_START
            ) {
                _manualOperationReservation.value = null
            }
            _serviceStartAcknowledgement.value =
                ManualServiceStartAcknowledgement(runId, accepted)
        }
    }

    /**
     * Atomically invalidates an Android service start only while its exact
     * reservation is still awaiting acknowledgement. Completed or already
     * acknowledged run IDs are ignored instead of accumulating tombstones.
     * Returns true only when the pending start was revoked.
     */
    internal fun revokeUnacknowledgedServiceStart(runId: String): Boolean =
        synchronized(serviceStartAdmissionLock) {
            if (runId.isBlank()) return@synchronized false
            val reservation = _manualOperationReservation.value
            if (
                reservation?.id != runId ||
                reservation.kind !=
                    ManualOperationReservationKind.SERVICE_START
            ) {
                return@synchronized false
            }
            val accepted = _syncState.value.activeRunId == runId
            if (!accepted) {
                revokedServiceStartRunIds += runId
            }
            _manualOperationReservation.value = null
            _serviceStartAcknowledgement.value =
                ManualServiceStartAcknowledgement(
                    runId = runId,
                    accepted = accepted
                )
            !accepted
        }

    /**
     * Publishes foreground-service ownership before admission or provider work
     * can suspend, so the same run can be stopped throughout its lifetime.
     */
    internal fun beginServiceRun(runId: String): Boolean =
        synchronized(serviceStartAdmissionLock) {
            if (runId.isBlank() || runId in revokedServiceStartRunIds) {
                return@synchronized false
            }
            _manualOperationReservation.value?.let { reservation ->
                if (
                    reservation.kind !=
                        ManualOperationReservationKind.SERVICE_START ||
                    reservation.id != runId
                ) {
                    return@synchronized false
                }
            }
            while (true) {
                val current = _syncState.value
                if (
                    current.activeRunId != null &&
                    current.activeRunId != runId
                ) {
                    return@synchronized false
                }
                val next = current.copy(
                    status = HomeSyncState.Status.SCANNING,
                    activeRunId = runId,
                    cancellationRequested = false,
                    currentIndex = null,
                    currentStageIndex = null,
                    decodedTokenDelta = "",
                    jsonOutput = "",
                    jsonOutputTruncated = false,
                    activeSmsPerformance = null,
                    scanError = null,
                    syncError = null
                )
                if (_syncState.compareAndSet(current, next)) {
                    break
                }
            }
            true
        }

    /** Returns true only when [runId] owns the currently published run. */
    internal fun requestServiceStop(runId: String?): Boolean =
        requestServiceStopMatching(
            runId = runId,
            expectedCandidateKey = null,
            requireCandidateMatch = false
        )

    /**
     * Candidate-scoped UI stop. A delayed action cannot cancel the next
     * candidate in the same run; null identifies an actually rendered gap.
     */
    internal fun requestServiceStop(
        runId: String?,
        expectedCandidateKey: String?
    ): Boolean = requestServiceStopMatching(
        runId = runId,
        expectedCandidateKey = expectedCandidateKey,
        requireCandidateMatch = true
    )

    private fun requestServiceStopMatching(
        runId: String?,
        expectedCandidateKey: String?,
        requireCandidateMatch: Boolean
    ): Boolean {
        while (true) {
            val current = _syncState.value
            if (!manualSyncStopMatches(current.activeRunId, runId)) {
                return false
            }
            if (current.status !in ACTIVE_SERVICE_STATUSES) {
                return false
            }
            if (
                requireCandidateMatch &&
                current.activeServiceCandidateKey() != expectedCandidateKey
            ) {
                return false
            }
            if (
                current.status == HomeSyncState.Status.CANCELLING &&
                current.cancellationRequested
            ) {
                return true
            }
            val next = current.copy(
                status = HomeSyncState.Status.CANCELLING,
                cancellationRequested = true,
                decodedTokenDelta = "",
                jsonOutput = "",
                jsonOutputTruncated = false,
                activeSmsPerformance = null
            )
            if (_syncState.compareAndSet(current, next)) return true
        }
    }

    private fun HomeSyncState.activeServiceCandidateKey(): String? =
        currentIndex
            ?.let(queue::getOrNull)
            ?.takeIf { it.status == "syncing" }
            ?.id
            ?: queue.firstOrNull { it.status == "syncing" }?.id

    /**
     * Idempotently settles cancellation for one run. A current in-flight item
     * becomes retryable, while terminal rows from earlier items are retained.
     */
    internal fun settleServiceCancellation(runId: String?): Boolean {
        while (true) {
            val current = _syncState.value
            val settled = settledManualSyncCancellation(current, runId)
                ?: return false
            if (_syncState.compareAndSet(current, settled)) {
                recordManualCancellationOutcomeSafely(settled.queue)
                return true
            }
        }
    }

    /** Clears service-only ownership on normal, no-work, and error exits. */
    internal fun finishServiceRun(runId: String?): Boolean {
        while (true) {
            val current = _syncState.value
            if (!manualSyncStopMatches(current.activeRunId, runId)) {
                return false
            }
            val terminalStatus = when (current.status) {
                HomeSyncState.Status.SCANNING,
                HomeSyncState.Status.CANCELLING -> HomeSyncState.Status.IDLE
                else -> current.status
            }
            val finished = current.copy(
                status = terminalStatus,
                activeRunId = null,
                cancellationRequested = false,
                currentIndex = current.currentIndex
                    .takeUnless { terminalStatus == HomeSyncState.Status.IDLE },
                currentStageIndex = current.currentStageIndex
                    .takeUnless { terminalStatus == HomeSyncState.Status.IDLE },
                decodedTokenDelta = "",
                jsonOutput = "",
                jsonOutputTruncated = false,
                activeSmsPerformance = null
            )
            if (_syncState.compareAndSet(current, finished)) return true
        }
    }

    /**
     * Linearizes an empty successful scan against Stop. Once this transition
     * wins, Stop is no longer eligible; if Stop wins first, the service must
     * publish cancellation instead of a no-work completion.
     */
    internal fun tryCompleteNoWorkServiceRun(runId: String?): Boolean {
        while (true) {
            val current = _syncState.value
            if (
                !manualSyncStopMatches(current.activeRunId, runId) ||
                current.status != HomeSyncState.Status.SCANNING ||
                current.cancellationRequested ||
                current.recentScanOutcome !=
                    HomeSyncState.RecentScanOutcome.SUCCESS ||
                current.queue.any { it.status == "pending" }
            ) {
                return false
            }
            val completed = current.copy(
                status = HomeSyncState.Status.DONE,
                currentIndex = null,
                currentStageIndex = null,
                decodedTokenDelta = "",
                jsonOutput = "",
                jsonOutputTruncated = false,
                activeSmsPerformance = null,
                syncError = null
            )
            if (_syncState.compareAndSet(current, completed)) return true
        }
    }

    /**
     * Serializes the final service handoff with incoming queue appends. The
     * caller publishes its terminal notification and releases job ownership
     * inside [handoff], then clears manager ownership before this mutex opens
     * to another queued SMS.
     */
    internal suspend fun withCompletedServiceRunHandoff(
        runId: String?,
        handoff: suspend (HomeSyncState) -> Unit
    ): Boolean = operationMutex.withLock {
        val terminal = _syncState.value
        if (
            !manualSyncStopMatches(terminal.activeRunId, runId) ||
            terminal.status != HomeSyncState.Status.DONE ||
            terminal.cancellationRequested ||
            terminal.queue.any {
                it.status == "pending" || it.status == "syncing"
            }
        ) {
            return@withLock false
        }
        handoff(terminal)
        true
    }

    private fun settleUnscopedCancellation() {
        _syncState.update { current ->
            current.copy(
                status = HomeSyncState.Status.IDLE,
                activeRunId = null,
                cancellationRequested = false,
                queue = current.queue.map { item ->
                    if (item.status == "syncing") {
                        item.copy(status = "pending")
                    } else {
                        item
                    }
                },
                currentIndex = null,
                currentStageIndex = null,
                decodedTokenDelta = "",
                jsonOutput = "",
                jsonOutputTruncated = false,
                activeSmsPerformance = null,
                syncError = null
            )
        }
        recordManualCancellationOutcomeSafely(_syncState.value.queue)
    }

    suspend fun checkForUnsyncedSms() =
        checkForUnsyncedSmsWithAdmission(
            admittedFlow = null,
            runId = null
        )

    internal suspend fun checkForUnsyncedSms(
        admittedFlow: SlmAppFlowLease,
        runId: String? = null
    ) = checkForUnsyncedSmsWithAdmission(admittedFlow, runId)

    private suspend fun checkForUnsyncedSmsWithAdmission(
        admittedFlow: SlmAppFlowLease?,
        runId: String?
    ) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            requireHomeSyncAdmission(admittedFlow)
            ensureRunCanContinue(runId)
            if (
                runId == null &&
                _syncState.value.status in ACTIVE_SERVICE_STATUSES
            ) {
                return@withLock
            }
            var ownedFlowLease: SlmAppFlowLease? = null
            if (admittedFlow == null) {
                ownedFlowLease =
                    appFlowCoordinator.tryEnter(SlmRuntimeOwner.HOME_SYNC)
                if (ownedFlowLease == null) {
                    recordManualOperationError(
                        code = "RECENT_SCAN_NOT_STARTED",
                        message =
                            "The recent scan is waiting for another local setup or maintenance operation.",
                        actionLabel = "Try recent scan again"
                    )
                    _syncState.update { state ->
                        state.copy(
                            recentScanOutcome =
                                HomeSyncState.RecentScanOutcome.NOT_RUN,
                            scanError =
                                "The recent scan is waiting for another local setup or maintenance operation."
                        )
                    }
                    return@withLock
                }
            }

            try {
                ensureRunCanContinue(runId)
                val onboardingComplete = context
                    .getSharedPreferences(APP_SETTINGS, Context.MODE_PRIVATE)
                    .getBoolean(ONBOARDING_COMPLETED, false)
                if (!onboardingComplete) return@withLock
                if (
                    !manualRecentSyncAvailable(
                        setupImportStore.state.value.status
                    )
                ) {
                    _syncState.update { state ->
                        state.copy(
                            // Keep service ownership until its error handoff,
                            // but close append admission immediately.
                            status = if (state.cancellationRequested) {
                                HomeSyncState.Status.CANCELLING
                            } else {
                                HomeSyncState.Status.IDLE
                            },
                            recentScanOutcome =
                                HomeSyncState.RecentScanOutcome.FAILED,
                            scanError =
                                "Resume the first-run import before scanning recent alerts."
                        )
                    }
                    return@withLock
                }
                if (!smsRepository.hasPermissions()) {
                    publishRecentPermissionNeeded()
                    return@withLock
                }
                val scanWindowDays = historyScanPolicy.firstWindowDays
                // One immutable upper bound owns both provider selection and
                // persisted coverage. Messages arriving later are handled by
                // normal intake or the next manual scan.
                val durableRecent = setupImportStore.state.value
                val providerMaxDate = recentScanProviderMaxDate(
                    state = durableRecent,
                    scanWindowDays = scanWindowDays,
                    nowMillis = System.currentTimeMillis()
                )
                val rawMessages = smsRepository.fetchHistory(
                    daysBack = scanWindowDays,
                    // Persisted coverage must describe a complete provider read,
                    // not the first page of a potentially larger inbox window.
                    limit = Int.MAX_VALUE,
                    maxDate = providerMaxDate
                )
                ensureRunCanContinue(runId)
                val transactional = rawMessages.filter { message ->
                    smsFilterPipeline.isTransactional(
                        message.address,
                        message.body
                    )
                }
                val unsyncedMessages = mutableListOf<SmsReader.SmsMessage>()
                for (message in transactional) {
                    ensureRunCanContinue(runId)
                    if (
                        !transactionRepository.preserveSourceMetadataIfExists(
                            sourceIdentity = message.sourceIdentity,
                            receivedDate = message.date
                        )
                    ) {
                        unsyncedMessages += message
                    }
                }
                ensureRunCanContinue(runId)
                if (!smsRepository.hasPermissions()) {
                    publishRecentPermissionNeeded()
                    return@withLock
                }
                val scannedCandidateKeys = unsyncedMessages
                    .mapTo(mutableSetOf()) {
                        it.sourceIdentity.opaqueCandidateKey
                    }
                val loadedModel = slmRuntime.state.value.loadedModel
                val completedAt = System.currentTimeMillis()
                synchronized(recentScanTrackingLock) {
                    recentScanCandidateKeys = scannedCandidateKeys
                    recentScanCompletedAtMillis = completedAt
                    _syncState.update { current ->
                        if (
                            runId != null &&
                            (
                                !manualSyncStopMatches(
                                    current.activeRunId,
                                    runId
                                ) || current.cancellationRequested
                            )
                        ) {
                            current
                        } else {
                            // Build from the queue owned by this exact CAS
                            // retry. Incoming candidates may be appended while
                            // repository identity checks are in flight.
                            val latestQueue = mergeRecentScanQueue(
                                currentQueue = current.queue.filter {
                                    it.status == "pending" ||
                                        it.status == "syncing"
                                },
                                providerMessages = unsyncedMessages
                            )
                            HomeSyncState(
                                status = if (runId == null) {
                                    HomeSyncState.Status.IDLE
                                } else {
                                    HomeSyncState.Status.SCANNING
                                },
                                activeRunId = current.activeRunId,
                                cancellationRequested =
                                    current.cancellationRequested,
                                queue = latestQueue,
                                activeModelName = loadedModel?.modelPath
                                    ?.let { File(it).name },
                                recentScanOutcome =
                                    HomeSyncState.RecentScanOutcome.SUCCESS,
                                recentScanWindowDays = scanWindowDays,
                                lastSuccessfulScanMillis = completedAt
                            )
                        }
                    }
                }
                ensureRunCanContinue(runId)
                recordManualScanSuccess(
                    scanWindowDays = scanWindowDays,
                    providerMaxDate = providerMaxDate,
                    completedAt = completedAt,
                    providerMessageCount = rawMessages.size,
                    eligibleCandidateCount = scannedCandidateKeys.size
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                Log.w(TAG, "SMS permission was removed during recent scan", e)
                publishRecentPermissionNeeded()
            } catch (e: Exception) {
                Log.e(TAG, "Failed checking for unsynced SMS", e)
                recordManualOperationError(
                    code = "RECENT_SMS_SCAN_FAILED",
                    message = e.message ?: "The recent SMS scan failed.",
                    actionLabel = "Try recent scan again"
                )
                _syncState.update { state ->
                    state.copy(
                        status = if (state.cancellationRequested) {
                            HomeSyncState.Status.CANCELLING
                        } else {
                            HomeSyncState.Status.IDLE
                        },
                        recentScanOutcome =
                            HomeSyncState.RecentScanOutcome.FAILED,
                        scanError = e.message ?: "The recent SMS scan failed."
                    )
                }
            } finally {
                withContext(NonCancellable) {
                    ownedFlowLease?.release()
                }
            }
        }
    }

    /**
     * Holds a residency lease for the batch, while each SMS is submitted as an
     * independent FIFO native request so other runtime callers can interleave.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun executeSync(serviceContext: Context) =
        executeSyncWithAdmission(
            serviceContext = serviceContext,
            admittedFlow = null,
            runId = null
        )

    internal suspend fun executeSync(
        serviceContext: Context,
        admittedFlow: SlmAppFlowLease,
        runId: String? = null
    ) = executeSyncWithAdmission(serviceContext, admittedFlow, runId)

    @Suppress("UNUSED_PARAMETER")
    private suspend fun executeSyncWithAdmission(
        serviceContext: Context,
        admittedFlow: SlmAppFlowLease?,
        runId: String?
    ) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            requireHomeSyncAdmission(admittedFlow)
            ensureRunCanContinue(runId)
            // Standalone callers wait through the selected-model handoff.
            // SyncService supplies the one outer lease that owns scan,
            // processing, persistence, and terminal notification publication.
            val ownedFlowLease = if (admittedFlow == null) {
                appFlowCoordinator.enterWhenAvailable(
                    SlmRuntimeOwner.HOME_SYNC
                )
            } else {
                null
            }
            try {
                val onboardingComplete = context
                    .getSharedPreferences(APP_SETTINGS, Context.MODE_PRIVATE)
                    .getBoolean(ONBOARDING_COMPLETED, false)
                if (!onboardingComplete) return@withLock
                if (
                    !manualRecentSyncAvailable(
                        setupImportStore.state.value.status
                    )
                ) {
                    _syncState.update { state ->
                        state.copy(
                            status = if (state.cancellationRequested) {
                                HomeSyncState.Status.CANCELLING
                            } else {
                                HomeSyncState.Status.IDLE
                            },
                            syncError =
                                "Resume the first-run import before processing recent alerts."
                        )
                    }
                    return@withLock
                }
                ensureRunCanContinue(runId)
                executeSyncLocked(runId)
            } finally {
                withContext(NonCancellable) {
                    ownedFlowLease?.release()
                }
            }
        }
    }

    private fun requireHomeSyncAdmission(
        admittedFlow: SlmAppFlowLease?
    ) {
        require(
            admittedFlow == null ||
                admittedFlow.owner == SlmRuntimeOwner.HOME_SYNC
        ) {
            "Manual sync requires HOME_SYNC app-flow admission"
        }
    }

    private suspend fun ensureRunCanContinue(runId: String?) {
        currentCoroutineContext().ensureActive()
        if (runId == null) return
        val state = _syncState.value
        if (
            !manualSyncStopMatches(state.activeRunId, runId) ||
            state.cancellationRequested
        ) {
            throw CancellationException(
                "Manual SMS processing run is no longer active"
            )
        }
    }

    internal fun isServiceStopRequested(runId: String?): Boolean {
        val state = _syncState.value
        return manualSyncStopMatches(state.activeRunId, runId) &&
            state.cancellationRequested
    }

    /**
     * A compare-and-set update prevents progress callbacks from overwriting a
     * stop published concurrently by the service main thread.
     */
    private fun updateRunState(
        runId: String?,
        allowCancellationRequested: Boolean = false,
        transform: (HomeSyncState) -> HomeSyncState
    ): Boolean {
        while (true) {
            val current = _syncState.value
            if (
                runId != null &&
                (
                    !manualSyncStopMatches(current.activeRunId, runId) ||
                        (
                            current.cancellationRequested &&
                                !allowCancellationRequested
                            )
                    )
            ) {
                return false
            }
            if (_syncState.compareAndSet(current, transform(current))) {
                return true
            }
        }
    }

    private fun tryPublishServiceDone(runId: String?): Boolean {
        while (true) {
            val current = _syncState.value
            if (
                runId != null &&
                (
                    !manualSyncStopMatches(current.activeRunId, runId) ||
                        current.cancellationRequested
                    )
            ) {
                return false
            }
            if (
                current.queue.any {
                    it.status == "pending" || it.status == "syncing"
                }
            ) {
                return false
            }
            val done = current.copy(
                status = HomeSyncState.Status.DONE,
                currentIndex = null,
                currentStageIndex = null,
                syncError = null
            )
            if (_syncState.compareAndSet(current, done)) return true
        }
    }

    private suspend fun executeSyncLocked(runId: String?) {
        ensureRunCanContinue(runId)
        if (_syncState.value.queue.isEmpty()) {
            updateRunState(runId) { state ->
                state.copy(status = HomeSyncState.Status.IDLE)
            }
            return
        }

        updateRunState(runId) { state ->
            state.copy(
                status = HomeSyncState.Status.SYNCING,
                currentIndex = 0,
                currentStageIndex = 0,
                syncError = null
            )
        }
        ensureRunCanContinue(runId)
        var lease: SlmLease? = null
        try {
            val device = deviceCapabilities.assessDevice()
            val tier = resolveActiveSlmTier(context, modelStorage.modelDirectory, device)
                ?: error("No viable SLM for this device (RAM below minimum).")
            val modelFile = modelStorage.modelFile(tier.modelFile)
            if (!modelFile.exists() || modelFile.length() == 0L) {
                error("Model file ${tier.modelFile} is not downloaded yet.")
            }
            val spec = tier.toModelSpec(modelStorage, device)

            val syncingPublished = updateRunState(runId) { state ->
                state.copy(
                    status = HomeSyncState.Status.SYNCING,
                    currentIndex = 0,
                    currentStageIndex = 0,
                    decodedTokenDelta = "",
                    jsonOutput = "",
                    jsonOutputTruncated = false,
                    activeSmsPerformance = null,
                    activeModelName = modelFile.name
                )
            }
            if (!syncingPublished) ensureRunCanContinue(runId)
            ensureRunCanContinue(runId)

            val batchLease = slmRuntime.acquire(SlmRuntimeOwner.HOME_SYNC, spec)
            lease = batchLease
            ensureRunCanContinue(runId)
            while (true) {
                ensureRunCanContinue(runId)
                val index = _syncState.value.queue.indexOfFirst {
                    it.status == "pending"
                }
                if (index < 0) {
                    val failedCount = _syncState.value.queue.count {
                        it.status == "error"
                    }
                    if (failedCount > 0) {
                        manualProcessingFailureError(failedCount)?.let { error ->
                            recordManualOperationError(
                                code = error.code,
                                message = error.message,
                                actionLabel = error.actionLabel
                            )
                        }
                    } else {
                        clearManualOperationError()
                    }
                    ensureRunCanContinue(runId)
                    if (tryPublishServiceDone(runId)) break
                    // A queue append that wins the terminal CAS belongs to this
                    // run and is processed before service ownership is released.
                    ensureRunCanContinue(runId)
                    continue
                }
                if (!smsRepository.hasPermissions()) {
                    publishRecentPermissionNeeded()
                    throw CancellationException(
                        "SMS permission was revoked during manual processing"
                    )
                }
                updateRunState(runId) { state ->
                    val queue = state.queue.toMutableList()
                    if (index in queue.indices) {
                        queue[index] = queue[index].copy(status = "syncing")
                    }
                    state.copy(
                        queue = queue,
                        currentIndex = index,
                        currentStageIndex = 0,
                        decodedTokenDelta = "",
                        jsonOutput = "",
                        jsonOutputTruncated = false,
                        activeSmsPerformance = null
                    )
                }
                ensureRunCanContinue(runId)

                val item = _syncState.value.queue[index]

                if (
                    transactionRepository.preserveSourceMetadataIfExists(
                        sourceIdentity = item.sourceIdentity,
                        receivedDate = item.date
                    )
                ) {
                    ensureRunCanContinue(runId)
                    updateItemStatus(index, "already_saved", runId)
                    continue
                }
                try {
                    updateRunState(runId) { state ->
                        state.copy(currentStageIndex = 1)
                    }
                    ensureRunCanContinue(runId)
                    val telemetryOwner = ManualSmsTelemetryOwner(
                        runId = runId,
                        candidateKey = item.id,
                        attemptToken = UUID.randomUUID().toString()
                    )
                    check(
                        activeManualSmsTelemetryOwner.compareAndSet(
                            null,
                            telemetryOwner
                        )
                    ) { "Another manual SMS telemetry attempt is still active" }
                    val processingObserver = ManualSmsProcessingObserver(
                        initial = ManualSmsProcessingTelemetry(
                            stageIndex = 1,
                            modelName = modelFile.name
                        ),
                        publish = { telemetry ->
                            publishManualSmsTelemetry(
                                owner = telemetryOwner,
                                index = index,
                                telemetry = telemetry
                            )
                        }
                    )
                    val result = try {
                        pipelineService.processSingle(
                            sms = SmsReader.SmsMessage(
                                address = item.sender,
                                body = item.body,
                                date = item.date,
                                type = item.messageType,
                                providerMessageId =
                                    item.sourceIdentity.providerMessageId,
                                sourceTimestamp = item.date
                            ),
                            lease = batchLease,
                            observer = processingObserver,
                            trigger = "manual"
                        )
                    } finally {
                        processingObserver.close()
                        clearManualSmsTelemetry(telemetryOwner, index)
                        activeManualSmsTelemetryOwner.compareAndSet(
                            telemetryOwner,
                            null
                        )
                    }
                    ensureRunCanContinue(runId)

                    when (result) {
                        is PipelineService.ProcessingResult.Saved ->
                            updateItemStatus(
                                index,
                                if (result.alreadyCommitted) "already_saved" else "synced",
                                runId
                            )
                        is PipelineService.ProcessingResult.Skipped ->
                            updateItemStatus(
                                index,
                                if (
                                    result.reason ==
                                    PipelineService.SkipReason.RETAINED_FOR_REVIEW
                                ) "retained_review" else "filtered_out",
                                runId
                            )
                        PipelineService.ProcessingResult.AwaitingConfiguration -> {
                            recordManualOperationError(
                                code = "PRIMARY_CURRENCY_REQUIRED",
                                message = "Choose a primary currency in Settings before processing saved alerts.",
                                actionLabel = "Open Settings"
                            )
                            updateItemStatus(index, "error", runId)
                        }
                        is PipelineService.ProcessingResult.Failure -> {
                            Log.e(TAG, "Grounded SMS processing failed: ${result.message}")
                            updateItemStatus(index, "error", runId)
                        }
                        PipelineService.ProcessingResult.Stopped ->
                            throw CancellationException("Manual SMS processing stopped")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ensureRunCanContinue(runId)
                    Log.e(TAG, "Failed syncing an SMS candidate", e)
                    updateItemStatus(index, "error", runId)
                }
            }
        } catch (e: CancellationException) {
            if (runId == null) {
                settleUnscopedCancellation()
            }
            throw e
        } catch (e: Exception) {
            if (isServiceStopRequested(runId)) {
                throw CancellationException(
                    "Manual SMS processing was stopped"
                ).apply { initCause(e) }
            }
            Log.e(TAG, "Error in manual sync process", e)
            recordManualOperationError(
                code = "MANUAL_SMS_PROCESSING_FAILED",
                message =
                    e.message ?: "Manual SMS processing could not finish.",
                actionLabel = "Scan recent messages"
            )
            val errorPublished = updateRunState(runId) { state ->
                state.copy(
                    status = HomeSyncState.Status.DONE,
                    currentIndex = null,
                    currentStageIndex = null,
                    syncError =
                        e.message ?: "Manual SMS processing could not finish."
                )
            }
            if (!errorPublished) ensureRunCanContinue(runId)
        } finally {
            withContext(NonCancellable) {
                lease?.release()
            }
        }
    }

    private fun updateItemStatus(
        index: Int,
        status: String,
        runId: String?
    ) {
        val updated = updateRunState(runId) { state ->
            val queue = state.queue.toMutableList()
            if (index !in queue.indices) return@updateRunState state
            queue[index] = queue[index].withPrivacySafeStatus(status)
            state.copy(
                queue = queue,
                decodedTokenDelta = "",
                jsonOutput = "",
                jsonOutputTruncated = false,
                activeSmsPerformance = null,
                activeModelName = null
            )
        }
        if (updated) {
            recordManualProcessingProgressSafely(_syncState.value.queue)
        }
    }

    private fun publishManualSmsTelemetry(
        owner: ManualSmsTelemetryOwner,
        index: Int,
        telemetry: ManualSmsProcessingTelemetry
    ): Boolean {
        if (activeManualSmsTelemetryOwner.get() != owner) return false
        var ownedSnapshot = false
        val updated = updateRunState(owner.runId) { state ->
            ownedSnapshot = state.ownsManualSmsTelemetry(owner, index) &&
                activeManualSmsTelemetryOwner.get() == owner
            if (!ownedSnapshot) {
                state
            } else {
                state.copy(
                    currentStageIndex = telemetry.stageIndex,
                    decodedTokenDelta = telemetry.decodedTokenDelta,
                    jsonOutput = telemetry.cumulativeStructuredOutput,
                    jsonOutputTruncated = telemetry.outputTruncated,
                    activeSmsPerformance = telemetry.performanceText,
                    activeModelName = telemetry.modelName
                )
            }
        }
        return updated && ownedSnapshot &&
            activeManualSmsTelemetryOwner.get() == owner
    }

    private fun clearManualSmsTelemetry(
        owner: ManualSmsTelemetryOwner,
        index: Int
    ) {
        if (activeManualSmsTelemetryOwner.get() != owner) return
        updateRunState(
            runId = owner.runId,
            allowCancellationRequested = true
        ) { state ->
            if (!state.ownsManualSmsTelemetry(owner, index)) {
                state
            } else {
                state.copy(
                    decodedTokenDelta = "",
                    jsonOutput = "",
                    jsonOutputTruncated = false,
                    activeSmsPerformance = null,
                    activeModelName = null
                )
            }
        }
    }

    private fun HomeSyncState.ownsManualSmsTelemetry(
        owner: ManualSmsTelemetryOwner,
        index: Int
    ): Boolean = currentIndex == index &&
        queue.getOrNull(index)?.let { item ->
            item.id == owner.candidateKey && item.status == "syncing"
        } == true

    private fun recordManualOperationError(
        code: String,
        message: String,
        actionLabel: String
    ) {
        try {
            if (
                !manualRecentSyncAvailable(
                    setupImportStore.state.value.status
                )
            ) {
                return
            }
            setupImportStore.update {
                it.copy(
                    actionableError = SetupActionableError(
                        code = code,
                        message = message,
                        actionLabel = actionLabel
                    )
                )
            }
        } catch (error: Exception) {
            runCatching {
                Log.e(TAG, "Could not persist manual operation error", error)
            }
        }
    }

    private fun recordManualProcessingInterrupted() {
        recordManualOperationError(
            code = "MANUAL_PROCESSING_INTERRUPTED",
            message =
                "Manual processing stopped. Completed saves remain available.",
            actionLabel = "Scan recent messages"
        )
    }

    /**
     * A Stop that arrives during the final commit may leave no work to retry.
     * Do not manufacture an attention card after every candidate is terminal.
     */
    private fun recordManualCancellationOutcome(queue: List<SyncSmsItem>) {
        if (queue.any { it.status == "pending" || it.status == "syncing" }) {
            recordManualProcessingInterrupted()
            return
        }
        val failedCount = queue.count { it.status == "error" }
        val failure = manualProcessingFailureError(failedCount)
        if (failure != null) {
            recordManualOperationError(
                code = failure.code,
                message = failure.message,
                actionLabel = failure.actionLabel
            )
        } else {
            clearManualOperationError()
        }
    }

    private fun recordManualCancellationOutcomeSafely(
        queue: List<SyncSmsItem>
    ) {
        try {
            recordManualCancellationOutcome(queue)
        } catch (error: Exception) {
            runCatching {
                Log.e(
                    TAG,
                    "Could not persist manual cancellation outcome",
                    error
                )
            }
        }
    }

    private fun recordManualScanSuccess(
        scanWindowDays: Int,
        providerMaxDate: Long,
        completedAt: Long,
        providerMessageCount: Int,
        eligibleCandidateCount: Int
    ) {
        setupImportStore.update {
            if (!manualRecentSyncAvailable(it.status)) {
                it
            } else {
                it.withSuccessfulRecentScan(
                    scanWindowDays = scanWindowDays,
                    providerMaxDate = providerMaxDate,
                    completedAt = completedAt,
                    providerMessageCount = providerMessageCount,
                    eligibleCandidateCount = eligibleCandidateCount
                )
            }
        }
    }

    private fun recordManualProcessingProgress(queue: List<SyncSmsItem>) {
        val tracking = synchronized(recentScanTrackingLock) {
            val completedAt = recentScanCompletedAtMillis
                ?: return@synchronized null
            if (recentScanCandidateKeys.isEmpty()) {
                return@synchronized null
            }
            completedAt to recentScanCandidateKeys
        } ?: return
        val expectedScanCompletedAt = tracking.first
        val tracked = queue.filter { it.id in tracking.second }
        val processed = tracked.count { it.status in TERMINAL_ITEM_STATUSES }
        val saved = tracked.count {
            it.status == "synced" || it.status == "already_saved"
        }
        val rejected = tracked.count { it.status == "filtered_out" }
        val failed = tracked.count { it.status == "error" }
        setupImportStore.update { state ->
            if (
                state.lastSuccessfulRecentScanMillis !=
                    expectedScanCompletedAt
            ) {
                state
            } else {
                state.copy(
                    recentProcessedCount = processed,
                    recentSavedCount = saved,
                    recentRejectedCount = rejected,
                    recentFailedCount = failed
                )
            }
        }
    }

    private fun publishRecentPermissionNeeded() {
        setupImportStore.reconcilePermission(granted = false)
        _syncState.update { state ->
            state.copy(
                status = if (state.cancellationRequested) {
                    HomeSyncState.Status.CANCELLING
                } else {
                    HomeSyncState.Status.IDLE
                },
                recentScanOutcome =
                    HomeSyncState.RecentScanOutcome.PERMISSION_NEEDED,
                scanError = "SMS access is required to scan recent alerts."
            )
        }
    }

    private fun recordManualProcessingProgressSafely(
        queue: List<SyncSmsItem>
    ) {
        try {
            recordManualProcessingProgress(queue)
        } catch (error: Exception) {
            // A stale durable counter causes a safe deduplicated rediscovery
            // after restart; it must not reverse a terminal in-memory result.
            runCatching {
                Log.e(
                    TAG,
                    "Could not persist recent SMS processing counters",
                    error
                )
            }
        }
    }

    private fun clearManualOperationError() {
        try {
            val code = setupImportStore.state.value.actionableError?.code
            if (
                code?.startsWith("RECENT_") != true &&
                code?.startsWith("MANUAL_") != true
            ) {
                return
            }
            setupImportStore.update {
                it.copy(actionableError = null)
            }
        } catch (error: Exception) {
            runCatching {
                Log.e(TAG, "Could not clear manual operation error", error)
            }
        }
    }

    fun resetState() {
        synchronized(recentScanTrackingLock) {
            while (true) {
                val current = _syncState.value
                if (
                    current.status != HomeSyncState.Status.DONE ||
                    current.activeRunId != null
                ) {
                    return
                }
                if (_syncState.compareAndSet(current, HomeSyncState())) {
                    recentScanCandidateKeys = emptySet()
                    recentScanCompletedAtMillis = null
                    return
                }
            }
        }
    }

    /** Unconditional reset used only inside the admitted financial erase. */
    fun resetStateForErase() {
        synchronized(recentScanTrackingLock) {
            recentScanCandidateKeys = emptySet()
            recentScanCompletedAtMillis = null
            _syncState.value = HomeSyncState()
        }
    }

    private companion object {
        const val TAG = "HomeSyncManager"
        const val APP_SETTINGS = ".app_settings"
        const val ONBOARDING_COMPLETED = "onboarding_completed"
        const val MANUAL_SERVICE_START_ACK_TIMEOUT_MILLIS = 15_000L
        val ACTIVE_SERVICE_STATUSES = setOf(
            HomeSyncState.Status.SCANNING,
            HomeSyncState.Status.SYNCING,
            HomeSyncState.Status.CANCELLING
        )
        val TERMINAL_ITEM_STATUSES = setOf(
            "synced",
            "already_saved",
            "filtered_out",
            "error"
        )
    }
}

internal fun manualSyncStopMatches(
    activeRunId: String?,
    requestedRunId: String?
): Boolean =
    !activeRunId.isNullOrBlank() &&
        !requestedRunId.isNullOrBlank() &&
        activeRunId == requestedRunId

internal fun settledManualSyncCancellation(
    state: HomeSyncState,
    requestedRunId: String?
): HomeSyncState? {
    if (!manualSyncStopMatches(state.activeRunId, requestedRunId)) return null
    return state.copy(
        status = HomeSyncState.Status.IDLE,
        activeRunId = null,
        cancellationRequested = false,
        queue = state.queue.map { item ->
            if (item.status == "syncing") {
                item.copy(status = "pending")
            } else {
                item
            }
        },
        currentIndex = null,
        currentStageIndex = null,
        decodedTokenDelta = "",
        jsonOutput = "",
        jsonOutputTruncated = false,
        activeSmsPerformance = null,
        syncError = null
    )
}

internal fun SetupImportState.withSuccessfulRecentScan(
    scanWindowDays: Int,
    providerMaxDate: Long,
    completedAt: Long,
    providerMessageCount: Int,
    eligibleCandidateCount: Int
): SetupImportState {
    require(scanWindowDays > 0) {
        "Recent scan window must be positive"
    }
    return copy(
        recentCoverageStartMillis =
            (
                providerMaxDate -
                    TimeUnit.DAYS.toMillis(scanWindowDays.toLong())
            ).coerceAtLeast(0L),
        recentCoverageEndMillis = providerMaxDate,
        recentScanWindowDays = scanWindowDays,
        recentProviderMessageCount = providerMessageCount.coerceAtLeast(0),
        recentEligibleCandidateCount = eligibleCandidateCount.coerceAtLeast(0),
        recentProcessedCount = 0,
        recentSavedCount = 0,
        recentRejectedCount = 0,
        recentFailedCount = 0,
        lastSuccessfulRecentScanMillis = completedAt,
        actionableError = null
    )
}

internal fun recentScanProviderMaxDate(
    state: SetupImportState,
    scanWindowDays: Int,
    nowMillis: Long
): Long =
    state.recentCoverageEndMillis
        ?.takeIf {
            state.recentProcessingNeedsAttention &&
                state.recentScanWindowDays == scanWindowDays
        }
        ?: nowMillis

internal fun manualProcessingFailureError(
    failedCount: Int
): SetupActionableError? {
    if (failedCount <= 0) return null
    return SetupActionableError(
        code = "MANUAL_SMS_PROCESSING_FAILED",
        message =
            "$failedCount eligible alert" +
                if (failedCount == 1) {
                    " could not be processed."
                } else {
                    "s could not be processed."
                },
        actionLabel = "Scan recent messages"
    )
}
