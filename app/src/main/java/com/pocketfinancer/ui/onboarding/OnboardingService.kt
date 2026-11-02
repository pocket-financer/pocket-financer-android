package com.pocketfinancer.ui.onboarding

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pocketfinancer.data.repository.TransactionRepository
import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.hardware.isPublishedModelArtifact
import com.pocketfinancer.inference.DownloadOwner
import com.pocketfinancer.inference.ModelDownloader
import com.pocketfinancer.inference.SlmLease
import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.inference.SlmModelStorage
import com.pocketfinancer.inference.SlmRuntime
import com.pocketfinancer.inference.SlmRuntimeOwner
import com.pocketfinancer.pipeline.PipelineService
import com.pocketfinancer.pipeline.SmsFilterPipeline
import com.pocketfinancer.pipeline.SmsNotificationHelper
import com.pocketfinancer.setup.AdaptiveHistoryScanPolicy
import com.pocketfinancer.setup.HistoryScanDecision
import com.pocketfinancer.setup.SetupActionableError
import com.pocketfinancer.setup.SetupEmptyReason
import com.pocketfinancer.setup.SetupImportState
import com.pocketfinancer.setup.SetupImportStatus
import com.pocketfinancer.setup.SetupImportStore
import com.pocketfinancer.setup.SetupPauseReason
import com.pocketfinancer.sms.SmsRepository
import com.pocketfinancer.SelectedModelResidency
import com.pocketfinancer.SlmAppFlowCoordinator
import com.pocketfinancer.toModelSpec
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private const val SETUP_PROGRESS_PERSISTENCE_FAILED =
    "SETUP_PROGRESS_PERSISTENCE_FAILED"
private const val SETUP_PROGRESS_PERSISTENCE_FAILURE_MESSAGE =
    "Transaction data is saved, but import progress could not be stored. " +
        "Try again; already-saved transactions will be recognized safely."
private const val SETUP_CHECKPOINT_PERSISTENCE_FAILURE_MESSAGE =
    "SMS import progress could not be stored. Try again; previously " +
        "processed messages will be checked safely."

@AndroidEntryPoint
class OnboardingService : Service() {

    private data class AcceptedHistoricalStop(
        val runId: String,
        val startId: Int
    )

    companion object {
        private const val TAG = "OnboardingService"
        private const val NOTIFICATION_ID = 10001
        private const val TERMINAL_NOTIFICATION_ID = 10002
        private const val TERMINAL_NOTIFICATION_TIMEOUT_MS = 5_000L
    }

    @Inject
    lateinit var syncManager: OnboardingSyncManager

    @Inject
    lateinit var deviceCapabilities: DeviceCapabilities

    @Inject
    lateinit var slmRuntime: SlmRuntime

    @Inject
    lateinit var modelStorage: SlmModelStorage

    @Inject
    lateinit var selectedModelResidency: SelectedModelResidency

    @Inject
    lateinit var appFlowCoordinator: SlmAppFlowCoordinator

    @Inject
    lateinit var runGenerationStore: OnboardingRunGenerationStore

    @Inject
    lateinit var modelDownloader: ModelDownloader

    @Inject
    lateinit var smsRepository: SmsRepository

    @Inject
    lateinit var smsFilterPipeline: SmsFilterPipeline

    @Inject
    lateinit var pipelineService: PipelineService

    @Inject
    lateinit var transactionRepository: TransactionRepository

    @Inject
    lateinit var setupImportStore: SetupImportStore

    private val historyScanPolicy = AdaptiveHistoryScanPolicy()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val workflowMutex = Mutex()
    private val historicalCancellationLock = Any()
    private var workJob: Job? = null
    private var activeWorkflowRetireThroughStartId: Int? = null
    private var downloadObserverJob: Job? = null
    private var acceptedHistoricalStop: AcceptedHistoricalStop? = null
    private var historicalCancellationCleanupJob: Job? = null
    private var pendingCleanupRetireStartId: Int? = null
    @Volatile
    private var activeRunId: String? = null
    @Volatile
    private var activeRunPurpose: OnboardingSyncManager.RunPurpose? = null
    @Volatile
    private var latestStartId: Int = 0

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "OnboardingService Created")
        SmsNotificationHelper.createNotificationChannel(this)
    }

    private var lastNotificationTitle = "Preparing Onboarding"
    private var lastNotificationText = "Initializing local SLM engine..."
    private var lastNotificationProgress = 0f

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == "ACTION_NOTIFICATION_DISMISSED") {
            val workflowUnfinished = hasUnfinishedWorkflowWork()
            if (
                workflowUnfinished ||
                syncManager.syncState.value.isRunning
            ) {
                Log.i(
                    TAG,
                    "Notification dismissed during active work; re-asserting it"
                )
                reassertNotification()
            } else if (hasUnfinishedServiceWork()) {
                // The atomic retirement handoff below keeps foreground
                // ownership until the remaining cleanup actually drains.
            } else {
                Log.i(TAG, "Retiring stale notification-dismiss command")
            }
            attachDeliveredCommandOrRetire(startId)
            return START_NOT_STICKY
        }

        if (intent?.action == OnboardingSyncManager.ACTION_STOP_HISTORICAL_IMPORT) {
            handleHistoricalImportCancellation(
                requestedRunId = intent.getStringExtra(
                    OnboardingSyncManager.EXTRA_RUN_ID
                ),
                cancellationStartId = startId
            )
            return START_NOT_STICKY
        }

        val slmId = intent?.getStringExtra("EXTRA_SLM_ID")
        val slm = SlmTier.ALL_TIERS.find { it.id == slmId } ?: SlmTier.DEFAULT_ONBOARDING_SLM
        val runId = intent
            ?.getStringExtra(OnboardingSyncManager.EXTRA_RUN_ID)
            .orEmpty()
        val runPurpose = intent
            ?.getStringExtra(OnboardingSyncManager.EXTRA_RUN_PURPOSE)
            ?.let { value ->
                OnboardingSyncManager.RunPurpose.entries
                    .firstOrNull { it.name == value }
            }
            ?: OnboardingSyncManager.RunPurpose.INITIAL_SETUP
        val coveredHistoryWindowDays = intent
            ?.takeIf {
                it.hasExtra(
                    OnboardingSyncManager.EXTRA_COVERED_HISTORY_WINDOW_DAYS
                )
            }
            ?.getIntExtra(
                OnboardingSyncManager.EXTRA_COVERED_HISTORY_WINDOW_DAYS,
                0
            )
            ?.takeIf { it > 0 }
        val resumeHistoryWindowDays = intent
            ?.takeIf {
                it.hasExtra(
                    OnboardingSyncManager.EXTRA_RESUME_HISTORY_WINDOW_DAYS
                )
            }
            ?.getIntExtra(
                OnboardingSyncManager.EXTRA_RESUME_HISTORY_WINDOW_DAYS,
                0
            )
            ?.takeIf { it > 0 }

        Log.i(TAG, "Starting $runPurpose for SLM: ${slm.name}")

        val generationIsCurrent = runGenerationStore.isCurrent(intent)
        val runMatchesManager = onboardingStartMatches(
            state = syncManager.syncState.value,
            requestedRunId = runId,
            requestedPurpose = runPurpose
        )
        if (!generationIsCurrent || !runMatchesManager) {
            Log.i(TAG, "Rejecting stale, mismatched, or unstamped onboarding start")
            if (hasUnfinishedServiceWork()) {
                reassertNotification()
                attachDeliveredCommandOrRetire(startId)
            } else {
                // A startForegroundService delivery must briefly enter the
                // foreground even when its durable generation or opaque run
                // token has become stale before Android delivers it.
                val rejectedNotification = buildInitialNotification(
                    slm = slm,
                    runPurpose = runPurpose,
                    runId = null
                )
                startForegroundCompat(rejectedNotification)
                if (attachDeliveredCommandOrRetire(startId)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
            return START_NOT_STICKY
        }

        if (
            activeRunId == runId &&
            activeRunPurpose == runPurpose &&
            hasUnfinishedServiceWork()
        ) {
            Log.i(TAG, "Ignoring duplicate delivery for active run $runId")
            attachDeliveredCommandOrRetire(startId)
            reassertNotification()
            return START_NOT_STICKY
        }

        activeRunId = runId
        activeRunPurpose = runPurpose
        synchronized(historicalCancellationLock) {
            acceptedHistoricalStop = null
            historicalCancellationCleanupJob = null
        }
        getSystemService(NotificationManager::class.java)
            .cancel(TERMINAL_NOTIFICATION_ID)

        // Start Foreground immediately to satisfy OS requirements.
        val initialNotification = buildInitialNotification(slm, runPurpose, runId)
        startForegroundCompat(initialNotification)

        // A repeated start first cancels and drains the previous coordinator
        // request. Starting a replacement before the old JNI call returned
        // would allow two onboarding workflows to race over model residency.
        val launchedJob = serviceScope.launch(start = CoroutineStart.LAZY) {
            workflowMutex.withLock {
                val flowOwner = when (runPurpose) {
                    OnboardingSyncManager.RunPurpose.INITIAL_SETUP ->
                        SlmRuntimeOwner.ONBOARDING
                    OnboardingSyncManager.RunPurpose.MODEL_UPGRADE ->
                        SlmRuntimeOwner.MODEL_UPGRADE
                }
                val flowLease = appFlowCoordinator.tryEnter(flowOwner)
                if (flowLease == null) {
                    if (
                        runPurpose ==
                            OnboardingSyncManager.RunPurpose.INITIAL_SETUP &&
                        !tryBeginHistoricalImportTerminalCommit(runId)
                    ) {
                        Log.i(
                            TAG,
                            "Historical Stop won while app-flow admission was closing"
                        )
                        return@withLock
                    }
                    Log.i(TAG, "$runPurpose start rejected while app-flow admission is paused")
                    syncManager.updateState {
                        it.copy(
                            isRunning = false,
                            isCancelling = false,
                            isCancellationAllowed = false,
                            isPreparingHistoricalModel = false,
                            isDownloading = false,
                            modelLoadError = if (
                                runPurpose ==
                                    OnboardingSyncManager.RunPurpose.MODEL_UPGRADE
                            ) {
                                "Model upgrade is temporarily paused while other model maintenance completes."
                            } else {
                                "Onboarding start is paused while reset completes."
                            }
                        )
                    }
                    if (
                        runPurpose ==
                        OnboardingSyncManager.RunPurpose.INITIAL_SETUP
                    ) {
                        setupImportStore.update {
                            it.copy(
                                status = SetupImportStatus.PAUSED,
                                pauseReason = SetupPauseReason.INTERRUPTED,
                                actionableError = SetupActionableError(
                                    code = "SETUP_ADMISSION_PAUSED",
                                    message = "Setup is waiting for other local model maintenance to finish.",
                                    actionLabel = "Resume setup"
                                )
                            )
                        }
                    }
                    showTerminalNotification(
                        title = "Model Work Paused",
                        text = "Open Pocket Financer to try again."
                    )
                    return@withLock
                }
                try {
                    runOnboardingWorkflow(
                        slm = slm,
                        runId = runId,
                        runPurpose = runPurpose,
                        coveredHistoryWindowDays = coveredHistoryWindowDays,
                        resumeHistoryWindowDays = resumeHistoryWindowDays
                    )
                } catch (e: CancellationException) {
                    Log.i(TAG, "Onboarding workflow cancelled")
                } catch (e: Exception) {
                    val historicalStopWon =
                        runPurpose ==
                            OnboardingSyncManager.RunPurpose.INITIAL_SETUP &&
                            !tryBeginHistoricalImportTerminalCommit(runId)
                    if (historicalStopWon) {
                        Log.i(
                            TAG,
                            "Treating service failure as the accepted user stop",
                            e
                        )
                    } else {
                        Log.e(TAG, "Error in onboarding workflow", e)
                        syncManager.updateState {
                            it.copy(
                                modelLoadError =
                                    e.message ?: "Unknown service error",
                                isRunning = false,
                                isCancelling = false,
                                isCancellationAllowed = false,
                                isPreparingHistoricalModel = false,
                                isDownloading = false
                            )
                        }
                        if (
                            runPurpose ==
                            OnboardingSyncManager.RunPurpose.INITIAL_SETUP
                        ) {
                            setupImportStore.update {
                                it.copy(
                                    status = SetupImportStatus.FAILED,
                                    actionableError = SetupActionableError(
                                        code = "SETUP_SERVICE_FAILED",
                                        message = e.message
                                            ?: "Background setup stopped unexpectedly.",
                                        actionLabel = "Try again"
                                    )
                                )
                            }
                        }
                        showTerminalNotification(
                            title = if (
                                runPurpose ==
                                    OnboardingSyncManager.RunPurpose.MODEL_UPGRADE
                            ) {
                                "AI Model Upgrade Failed"
                            } else {
                                "Pocket Financer Setup Paused"
                            },
                            text = "Open Pocket Financer to try again."
                        )
                    }
                } finally {
                    withContext(NonCancellable) {
                        flowLease.release()
                    }
                }
            }
        }
        val replacedJob = synchronized(historicalCancellationLock) {
            val previous = workJob
            workJob = launchedJob
            activeWorkflowRetireThroughStartId = startId
            previous
        }
        launchedJob.invokeOnCompletion {
            // Also covers cancellation before the coroutine first runs. A
            // replaced older start cannot finish cancellation for its successor.
            val retireThroughStartId = synchronized(
                historicalCancellationLock
            ) {
                if (workJob !== launchedJob) {
                    null
                } else {
                    workJob = null
                    activeWorkflowRetireThroughStartId.also {
                        activeWorkflowRetireThroughStartId = null
                    }
                }
            } ?: return@invokeOnCompletion
            if (
                runPurpose ==
                    OnboardingSyncManager.RunPurpose.INITIAL_SETUP &&
                syncManager.historicalCancellationRequested(runId)
            ) {
                scheduleHistoricalImportCancellationSettlement(runId)
            } else {
                syncManager.completeCancellationIfRequested(runId)
                stopSelfResult(retireThroughStartId)
            }
        }
        replacedJob?.cancel()
        launchedJob.start()

        return START_NOT_STICKY
    }

    private suspend fun runOnWorkflowProgress(title: String, text: String, progress: Float) {
        lastNotificationTitle = title
        lastNotificationText = text
        lastNotificationProgress = progress

        withContext(Dispatchers.Main) {
            val builder = NotificationCompat.Builder(this@OnboardingService, SmsNotificationHelper.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setProgress(100, (progress * 100).toInt(), false)
                .setDeleteIntent(getDeletePendingIntent())
                .setContentIntent(getAppPendingIntent())
            addHistoricalStopActionIfAllowed(builder)
            
            val notification = builder.build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    /**
     * Ends foreground progress immediately, then posts a short-lived,
     * dismissible result without a progress bar. Keeping the terminal result
     * on a separate ID prevents service destruction from reviving or retaining
     * the old ongoing foreground notification.
     */
    private suspend fun showTerminalNotification(
        title: String,
        text: String
    ) {
        lastNotificationTitle = title
        lastNotificationText = text
        lastNotificationProgress = 1f

        withContext(Dispatchers.Main) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            val notification = NotificationCompat.Builder(
                this@OnboardingService,
                SmsNotificationHelper.CHANNEL_ID
            )
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(false)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, false)
                .setTimeoutAfter(TERMINAL_NOTIFICATION_TIMEOUT_MS)
                .setContentIntent(getAppPendingIntent())
                .build()

            runCatching {
                getSystemService(NotificationManager::class.java)
                    .notify(TERMINAL_NOTIFICATION_ID, notification)
            }.onFailure { error ->
                Log.w(TAG, "Could not post terminal model-work notification", error)
            }
        }
    }

    private fun buildInitialNotification(
        slm: SlmTier,
        runPurpose: OnboardingSyncManager.RunPurpose,
        runId: String?
    ): Notification {
        val title = when (runPurpose) {
            OnboardingSyncManager.RunPurpose.INITIAL_SETUP ->
                "Preparing Onboarding"
            OnboardingSyncManager.RunPurpose.MODEL_UPGRADE ->
                "Preparing AI Model Upgrade"
        }
        val text = "Preparing ${slm.name}..."
        lastNotificationTitle = title
        lastNotificationText = text
        lastNotificationProgress = 0f
        val builder = NotificationCompat.Builder(this, SmsNotificationHelper.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setProgress(100, 0, true)
            .setDeleteIntent(getDeletePendingIntent())
            .setContentIntent(getAppPendingIntent())
        if (runId != null) {
            addHistoricalStopActionIfAllowed(builder)
        }
        return builder.build()
    }

    private fun getDeletePendingIntent(): android.app.PendingIntent {
        val intent = Intent(this, OnboardingService::class.java).apply {
            action = "ACTION_NOTIFICATION_DISMISSED"
        }
        return android.app.PendingIntent.getService(
            this,
            0,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getAppPendingIntent(): android.app.PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        } ?: Intent()

        return android.app.PendingIntent.getActivity(
            this,
            0,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getHistoricalStopPendingIntent(
        runId: String
    ): android.app.PendingIntent {
        val intent = Intent(this, OnboardingService::class.java).apply {
            action = OnboardingSyncManager.ACTION_STOP_HISTORICAL_IMPORT
            data = Uri.Builder()
                .scheme("pocketfinancer")
                .authority("historical-import-stop")
                .appendPath(runId)
                .build()
            putExtra(OnboardingSyncManager.EXTRA_RUN_ID, runId)
        }
        return android.app.PendingIntent.getService(
            this,
            0,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun addHistoricalStopActionIfAllowed(
        builder: NotificationCompat.Builder
    ) {
        val runId = activeRunId ?: return
        val state = syncManager.syncState.value
        if (
            activeRunPurpose != OnboardingSyncManager.RunPurpose.INITIAL_SETUP ||
            !historicalCancellationMatches(
                state = state,
                requestedRunId = runId,
                durableStatus = setupImportStore.state.value.status
            )
        ) {
            return
        }
        builder.addAction(
            android.R.drawable.ic_media_pause,
            "Stop",
            getHistoricalStopPendingIntent(runId)
        )
    }

    private fun reassertNotification() {
        val builder = NotificationCompat.Builder(this, SmsNotificationHelper.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(lastNotificationTitle)
            .setContentText(lastNotificationText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setProgress(100, (lastNotificationProgress * 100).toInt(), false)
            .setDeleteIntent(getDeletePendingIntent())
            .setContentIntent(getAppPendingIntent())
        addHistoricalStopActionIfAllowed(builder)

        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun handleHistoricalImportCancellation(
        requestedRunId: String?,
        cancellationStartId: Int
    ) {
        val runId = requestedRunId?.takeIf { it.isNotBlank() }
        if (runId == null) {
            Log.i(TAG, "Ignoring historical-import Stop without a run ID")
            attachDeliveredCommandOrRetire(cancellationStartId)
            return
        }
        val durableStatus = setupImportStore.state.value.status
        val belongsToActiveServiceRun =
            (
                activeRunId == runId &&
                    activeRunPurpose ==
                        OnboardingSyncManager.RunPurpose.INITIAL_SETUP
                ) ||
                (
                    activeRunId == null &&
                        !hasUnfinishedServiceWork() &&
                        historicalRunMatches(syncManager.syncState.value, runId)
                    )
        if (
            !belongsToActiveServiceRun ||
            !syncManager.acceptHistoricalImportCancellation(
                runId = runId,
                durableStatus = durableStatus
            )
        ) {
            Log.i(
                TAG,
                "Ignoring stale or ineligible historical-import Stop command"
            )
            attachDeliveredCommandOrRetire(cancellationStartId)
            return
        }

        recordAcceptedHistoricalStop(runId, cancellationStartId)
        showHistoricalCancellationInProgress()

        val job = synchronized(historicalCancellationLock) { workJob }
        if (job == null || job.isCompleted) {
            scheduleHistoricalImportCancellationSettlement(runId)
            return
        }

        // Downloader cancellation is owner/job scoped. Coroutine cancellation
        // then propagates into SlmRuntime, whose coordinator stops and drains
        // any active native request before the job's completion callback runs.
        modelDownloader.cancel(job)
        job.cancel(CancellationException("Historical import stopped by user"))
    }

    private fun showHistoricalCancellationInProgress() {
        lastNotificationTitle = "Stopping SMS Processing"
        lastNotificationText =
            "Finishing the current on-device operation safely..."
        val notification = NotificationCompat.Builder(
            this,
            SmsNotificationHelper.CHANNEL_ID
        )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(lastNotificationTitle)
            .setContentText(lastNotificationText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setProgress(100, (lastNotificationProgress * 100).toInt(), false)
            .setDeleteIntent(getDeletePendingIntent())
            .setContentIntent(getAppPendingIntent())
            .build()
        startForegroundCompat(notification)
    }

    private suspend fun tryBeginHistoricalImportTerminalCommit(
        runId: String
    ): Boolean {
        val cancellationWasOpen = syncManager.syncState.value.let { state ->
            historicalRunMatches(state, runId) &&
                state.isCancellationAllowed
        }
        val commitWon = syncManager.tryBeginHistoricalImportCommit(runId)
        if (commitWon && cancellationWasOpen) {
            showHistoricalCommitInProgress()
        }
        return commitWon
    }

    /** Retires the visible Stop action as soon as terminal commit wins. */
    private suspend fun showHistoricalCommitInProgress() {
        lastNotificationTitle = "Finishing SMS Import"
        lastNotificationText = "Saving verified results safely..."
        lastNotificationProgress = 0.98f
        withContext(Dispatchers.Main) {
            val notification = NotificationCompat.Builder(
                this@OnboardingService,
                SmsNotificationHelper.CHANNEL_ID
            )
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(lastNotificationTitle)
                .setContentText(lastNotificationText)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setProgress(100, 98, false)
                .setDeleteIntent(getDeletePendingIntent())
                .setContentIntent(getAppPendingIntent())
                .build()
            startForegroundCompat(notification)
        }
    }

    private fun recordAcceptedHistoricalStop(runId: String, startId: Int) {
        synchronized(historicalCancellationLock) {
            val current = acceptedHistoricalStop
            if (
                current == null ||
                current.runId != runId ||
                startId > current.startId
            ) {
                acceptedHistoricalStop = AcceptedHistoricalStop(runId, startId)
            }
        }
    }

    /**
     * Atomically assigns every delivered Android start ID to exactly one
     * retirement owner. A completion callback uses the same lock, so a command
     * cannot fall between workflow and cleanup ownership.
     *
     * @return true only when this call retired the service immediately.
     */
    private fun attachDeliveredCommandOrRetire(startId: Int): Boolean {
        val retireImmediately = synchronized(historicalCancellationLock) {
            val workflow = workJob
            val cleanup = historicalCancellationCleanupJob
            val pendingRunId = activeRunId ?: acceptedHistoricalStop?.runId
            when {
                workflow != null -> {
                    activeWorkflowRetireThroughStartId = maxOf(
                        activeWorkflowRetireThroughStartId ?: startId,
                        startId
                    )
                    false
                }
                cleanup != null ||
                    pendingRunId?.let {
                        syncManager.historicalCancellationRequested(it)
                    } == true -> {
                    pendingCleanupRetireStartId = maxOf(
                        pendingCleanupRetireStartId ?: startId,
                        startId
                    )
                    false
                }
                else -> true
            }
        }
        return if (retireImmediately) {
            stopSelfResult(startId)
        } else {
            false
        }
    }

    /**
     * `isActive` becomes false as soon as cancellation starts, before a
     * coroutine's NonCancellable persistence/runtime cleanup has returned.
     * Service commands must keep foreground ownership through completion.
     */
    private fun hasUnfinishedServiceWork(): Boolean {
        val serviceSnapshot = synchronized(
            historicalCancellationLock
        ) {
            Triple(
                workJob,
                historicalCancellationCleanupJob,
                acceptedHistoricalStop?.runId
            )
        }
        val pendingCancellationRunId =
            activeRunId ?: serviceSnapshot.third
        return onboardingServiceWorkIsUnfinished(
            workJobIsPresent = serviceSnapshot.first != null,
            workJobIsCompleted = serviceSnapshot.first?.isCompleted == true,
            cleanupJobIsPresent = serviceSnapshot.second != null,
            cleanupJobIsCompleted =
                serviceSnapshot.second?.isCompleted == true,
            cancellationSettlementPending =
                pendingCancellationRunId?.let {
                    syncManager.historicalCancellationRequested(it)
                } == true
        )
    }

    private fun hasUnfinishedWorkflowWork(): Boolean {
        val workflow = synchronized(historicalCancellationLock) { workJob }
        return workflow != null && !workflow.isCompleted
    }

    /**
     * Starts settlement only after the workflow callback proves that native,
     * runtime, model-pin, and app-flow ownership has drained. If the UI CAS won
     * before its service command arrives, the completed workflow deliberately
     * waits in Cancelling until that command supplies its run-scoped start ID.
     */
    private fun scheduleHistoricalImportCancellationSettlement(
        runId: String
    ): Boolean {
        if (!syncManager.historicalCancellationRequested(runId)) return false

        val cleanupJob = synchronized(historicalCancellationLock) {
            if (acceptedHistoricalStop?.runId != runId) {
                return true
            }
            historicalCancellationCleanupJob
                ?.takeIf { it.isActive }
                ?.let { return true }

            serviceScope.launch(start = CoroutineStart.LAZY) {
                settleHistoricalImportCancellation(runId)
            }.also { launched ->
                historicalCancellationCleanupJob = launched
                launched.invokeOnCompletion {
                    val retireStartId = synchronized(
                        historicalCancellationLock
                    ) {
                        if (historicalCancellationCleanupJob === launched) {
                            historicalCancellationCleanupJob = null
                        }
                        pendingCleanupRetireStartId.also {
                            pendingCleanupRetireStartId = null
                        }
                    }
                    if (
                        retireStartId != null &&
                        !hasUnfinishedServiceWork()
                    ) {
                        if (stopSelfResult(retireStartId)) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                        }
                    }
                }
            }
        }
        cleanupJob.start()
        return true
    }

    /** Called only after the workflow completion callback has drained leases. */
    private suspend fun settleHistoricalImportCancellation(runId: String) {
        withContext(NonCancellable) settlement@ {
            if (!syncManager.historicalCancellationRequested(runId)) {
                return@settlement
            }

            var pausePersistenceFailed = false
            val durablePause = try {
                setupImportStore.update { state ->
                    pauseHistoricalImportForUser(state)
                }
            } catch (error: Exception) {
                pausePersistenceFailed = true
                Log.e(
                    TAG,
                    "Could not durably persist historical stop settlement",
                    error
                )
                val fallback = historicalStopPersistenceFallback(
                    setupImportStore.state.value
                )
                setupImportStore.publishVolatilePersistenceFallback(fallback)
            }
            val userPausePublished =
                durablePause.status == SetupImportStatus.PAUSED &&
                    durablePause.pauseReason == SetupPauseReason.USER_REQUESTED

            if (pausePersistenceFailed) {
                showTerminalNotification(
                    title = "SMS Processing Stopped",
                    text =
                        "Completed saves remain. Reopen the app before resuming."
                )
            } else if (userPausePublished) {
                showTerminalNotification(
                    title = "SMS Import Paused",
                    text =
                        "Completed saves remain on this device. Resume anytime."
                )
            } else if (durablePause.status == SetupImportStatus.PERMISSION_NEEDED) {
                showTerminalNotification(
                    title = "SMS Access Needed",
                    text = "Restore SMS access to continue the paused import."
                )
            } else {
                showTerminalNotification(
                    title = "SMS Import Ended",
                    text = "Open Pocket Financer to review setup status."
                )
            }

            // Manager completion and retirement capture share one main-service
            // turn. The cleanup completion callback performs stopSelfResult
            // only after this coroutine itself has fully returned.
            withContext(Dispatchers.Main.immediate) mainTurn@ {
                if (!syncManager.historicalCancellationRequested(runId)) {
                    return@mainTurn
                }
                val acceptedStartId = synchronized(historicalCancellationLock) {
                    acceptedHistoricalStop
                        ?.takeIf { it.runId == runId }
                        ?.startId
                } ?: return@mainTurn
                // Snapshot on the same main-service turn before making the
                // manager resumable. This includes stale/repeated commands
                // already delivered for the old run, but no newer valid run
                // can interleave before the retirement ID is recorded.
                val retireThroughStartId =
                    maxOf(acceptedStartId, latestStartId)
                val completed = if (userPausePublished) {
                    syncManager.completeHistoricalImportCancellation(runId)
                } else {
                    syncManager.completeHistoricalCancellationWithoutUserPause(
                        runId = runId,
                        syncMessage = if (
                            durablePause.status ==
                                SetupImportStatus.PERMISSION_NEEDED
                        ) {
                            "SMS access must be restored to continue setup."
                        } else {
                            "SMS import ended without a user-requested pause."
                        }
                    )
                }
                if (!completed) return@mainTurn
                activeRunId = null
                activeRunPurpose = null
                synchronized(historicalCancellationLock) {
                    pendingCleanupRetireStartId = maxOf(
                        pendingCleanupRetireStartId
                            ?: retireThroughStartId,
                        retireThroughStartId
                    )
                }
            }
        }
    }

    private suspend fun runOnboardingWorkflow(
        slm: SlmTier,
        runId: String,
        runPurpose: OnboardingSyncManager.RunPurpose,
        coveredHistoryWindowDays: Int?,
        resumeHistoryWindowDays: Int?
    ) {
        val destFile = getModelFile(slm)

        // 1. Use an immutable cached final artifact or download through the
        // staging path. Cached onboarding remains available offline; the
        // native GGUF load below is its authoritative validity check.
        Log.i(TAG, "Preparing model artifact...")
        syncManager.updateState {
            it.copy(
                step = OnboardingStep.DOWNLOAD_SLM,
                isDownloading = true
            )
        }
        if (runPurpose == OnboardingSyncManager.RunPurpose.INITIAL_SETUP) {
            setupImportStore.update {
                it.copy(
                    status = SetupImportStatus.DOWNLOADING,
                    pauseReason = null,
                    actionableError = null
                )
            }
        }

        // Observe downloader progress
        downloadObserverJob = serviceScope.launch {
            modelDownloader.state.collect { ds ->
                syncManager.updateState {
                    it.copy(downloadState = ds, isDownloading = ds.isDownloading)
                }

                if (ds.isDownloading) {
                    val progressPercent = (ds.progress * 100).toInt()
                    val speedText = if (ds.speedMbps > 0) " • ${"%.1f".format(ds.speedMbps)} MB/s" else ""
                    val etaText = if (ds.etaSeconds > 0) {
                        val mins = ds.etaSeconds / 60
                        val secs = ds.etaSeconds % 60
                        " • " + (if (mins > 0) "${mins}m ${secs}s" else "${secs}s") + " left"
                    } else ""
                    runOnWorkflowProgress(
                        title = "Downloading Local AI Model",
                        text = "$progressPercent%$speedText$etaText",
                        progress = ds.progress
                    )
                }
            }
        }

        val result = try {
            modelDownloader.prepareForNativeValidation(
                slm.downloadUrl,
                destFile,
                if (runPurpose == OnboardingSyncManager.RunPurpose.MODEL_UPGRADE) {
                    DownloadOwner.UPGRADE
                } else {
                    DownloadOwner.ONBOARDING
                }
            )
        } finally {
            downloadObserverJob?.cancel()
            downloadObserverJob = null
        }
        currentCoroutineContext().ensureActive()

        if (result.isFailure) {
            if (
                runPurpose == OnboardingSyncManager.RunPurpose.INITIAL_SETUP &&
                !tryBeginHistoricalImportTerminalCommit(runId)
            ) {
                throw CancellationException(
                    "Historical import stop won during model validation"
                )
            }
            val errorMsg = result.exceptionOrNull()?.message ?: "Download failed"
            Log.e(TAG, "Model download failed: $errorMsg")
            syncManager.modelPreparationFailed(
                errorMessage = errorMsg,
                terminalDownloadState = modelDownloader.state.value
            )
            showTerminalNotification(
                title = if (
                    runPurpose ==
                        OnboardingSyncManager.RunPurpose.MODEL_UPGRADE
                ) {
                    "AI Model Download Paused"
                } else {
                    "Setup Download Paused"
                },
                text = "Open Pocket Financer to retry."
            )
            return
        }

        // Publish one authoritative state transition. The screen switches on
        // `step`, so this also removes the Download action before sync starts.
        syncManager.modelPreparationCompleted(destFile)

        when (runPurpose) {
            OnboardingSyncManager.RunPurpose.INITIAL_SETUP -> {
                // Initial setup continues into the one-time inbox scan.
                Log.i(TAG, "Starting sync phase...")
                runOnboardingSync(
                    slm = slm,
                    runId = runId,
                    coveredHistoryWindowDays = coveredHistoryWindowDays,
                    resumeHistoryWindowDays = resumeHistoryWindowDays
                )
            }
            OnboardingSyncManager.RunPurpose.MODEL_UPGRADE -> {
                // A post-onboarding model upgrade must not unexpectedly re-run
                // the historical SMS import. Native loading and durable
                // selection are the complete consistency boundary.
                Log.i(TAG, "Activating model upgrade...")
                activateModelUpgrade(slm)
            }
        }
    }

    private suspend fun activateModelUpgrade(slm: SlmTier) {
        syncManager.updateState {
            it.copy(
                step = OnboardingStep.SYNCING,
                syncProgress = 0.94f,
                syncMessage = "Waiting for active AI work to finish...",
                modelLoadError = null
            )
        }
        runOnWorkflowProgress(
            title = "Upgrading Local AI Model",
            text = "Waiting for active AI work to finish...",
            progress = 0.94f
        )

        // Downloading may coexist with foreground/background extraction. The
        // persisted model selection changes only after every already-admitted
        // workflow finishes, while this pause blocks new workflows from
        // resolving the old selection. One extraction therefore never runs
        // partly against the old model and partly against the new model.
        val admissionPause = checkNotNull(
            appFlowCoordinator.tryPauseAdmissionAndDrainOthers(
                SlmRuntimeOwner.MODEL_UPGRADE
            )
        ) {
            "Another model maintenance operation is already in progress"
        }

        var provisionalPin: com.pocketfinancer.ProvisionalSelectedModelPin? = null
        try {
            syncManager.updateState {
                it.copy(
                    syncProgress = 0.96f,
                    syncMessage = "Activating ${slm.name}..."
                )
            }
            runOnWorkflowProgress(
                title = "Upgrading Local AI Model",
                text = "Validating and activating ${slm.name}...",
                progress = 0.96f
            )

            val spec = slm.toModelSpec(
                modelStorage,
                deviceCapabilities.assessDevice()
            )
            val handoff = selectedModelResidency.beginProvisionalPin(
                spec = spec,
                persistedFallback = resolvePersistedSelectedModelSpec()
            )
            provisionalPin = handoff
            if (!syncManager.tryBeginModelUpgradeCommit()) {
                throw CancellationException(
                    "Model upgrade cancellation won before durable commit"
                )
            }
            val committed = withContext(NonCancellable) {
                handoff.commit {
                    persistSelectedModel(slm)
                }
            }
            check(committed) {
                "Selected-model ownership changed before the upgrade completed"
            }

            syncManager.updateState {
                it.copy(
                    runId = null,
                    step = OnboardingStep.COMPLETED,
                    isRunning = false,
                    isCancelling = false,
                    isCancellationAllowed = false,
                    isPreparingHistoricalModel = false,
                    isDownloading = false,
                    isModelLoaded = true,
                    syncProgress = 1f,
                    syncMessage = "${slm.name} is ready",
                    modelLoadError = null
                )
            }
            showTerminalNotification(
                title = "AI Model Upgrade Complete",
                text = "${slm.name} is ready to use."
            )
        } finally {
            withContext(NonCancellable) {
                provisionalPin?.rollbackUnlessCommitted()
                admissionPause.release()
            }
        }
    }

    private suspend fun runOnboardingSync(
        slm: SlmTier,
        runId: String,
        coveredHistoryWindowDays: Int?,
        resumeHistoryWindowDays: Int?
    ) {
        val logs = mutableListOf<String>()

        fun addLog(msg: String) {
            logs.add(msg)
            syncManager.updateState { it.copy(syncLogs = logs.toList()) }
        }

        addLog("System: Initializing local sync...")
        syncManager.updateState {
            it.copy(
                syncProgress = 0.05f,
                syncMessage = "Warming up local AI engine..."
            )
        }
        runOnWorkflowProgress("Syncing Transactions", "Initializing AI engine...", 0.05f)
        delay(800)

        addLog("Model: Loading ${slm.name} into memory...")
        syncManager.updateState { it.copy(syncMessage = "Initializing model layers...") }
        runOnWorkflowProgress("Syncing Transactions", "Loading model layers...", 0.08f)
        val spec = slm.toModelSpec(modelStorage, deviceCapabilities.assessDevice())
        var batchLease: SlmLease? = null
        var provisionalPin: com.pocketfinancer.ProvisionalSelectedModelPin? = null
        try {
            val persistedRollback = resolvePersistedSelectedModelSpec()
            val handoff = selectedModelResidency.beginProvisionalPin(
                spec = spec,
                persistedFallback = persistedRollback
            )
            provisionalPin = handoff
            val activeLease = try {
                slmRuntime.acquire(SlmRuntimeOwner.ONBOARDING, spec)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (!tryBeginHistoricalImportTerminalCommit(runId)) {
                    throw CancellationException(
                        "Historical import stop won during model validation",
                        error
                    )
                }
                val errorMsg = error.message ?: "Unknown error"
                addLog("Error: Failed to load model ($errorMsg)")
                syncManager.updateState {
                    it.copy(
                        modelLoadError = "Failed to load model: $errorMsg",
                        isRunning = false,
                        isCancelling = false,
                        isCancellationAllowed = false,
                        isPreparingHistoricalModel = false,
                        isModelLoaded = false
                    )
                }
                setupImportStore.update {
                    it.copy(
                        status = SetupImportStatus.FAILED,
                        modelPrepared = false,
                        actionableError = SetupActionableError(
                            code = "MODEL_LOAD_FAILED",
                            message = "The downloaded model could not be prepared: $errorMsg",
                            actionLabel = "Try again"
                        )
                    )
                }
                return
            }
            batchLease = activeLease

            suspend fun completeRun(
                notificationTitle: String,
                notificationText: String
            ): Boolean {
                if (!ensureSmsPermissionForTerminalPublication()) {
                    return false
                }
                val stillOwnsSelection = withContext(NonCancellable) {
                    handoff.commit {
                        persistCompletedSelection(slm)
                    }
                }
                check(stillOwnsSelection) {
                    "Selected-model ownership changed before onboarding completed"
                }
                publishOnboardingCompleted()
                showTerminalNotification(
                    title = notificationTitle,
                    text = notificationText
                )
                return true
            }

            addLog("Model: Loaded successfully on device CPU.")
            syncManager.updateState { it.copy(isModelLoaded = true) }
            // Model preparation may take long enough for SMS access to change.
            // Persist the completed preparation independently, then gate the
            // provider-reading phase before claiming that scanning has begun.
            setupImportStore.update { it.copy(modelPrepared = true) }
            if (!smsRepository.hasPermissions()) {
                publishSmsPermissionLoss(
                    "SMS access was removed before history scanning began."
                )
                return
            }
            val scanningState = setupImportStore.update {
                it.copy(
                    status = SetupImportStatus.SCANNING,
                    pauseReason = null,
                    actionableError = null
                )
            }
            if (!smsRepository.hasPermissions()) {
                publishSmsPermissionLoss(
                    "SMS access was removed before history scanning began."
                )
                return
            }
            syncManager.allowHistoricalImportCancellation(
                runId = runId,
                durableStatus = scanningState.status
            )

            var historyWindowDays = initialHistoryWindowDays(
                policy = historyScanPolicy,
                coveredWindowDays = coveredHistoryWindowDays,
                resumeWindowDays = resumeHistoryWindowDays
            )
            // Every adaptive query shares one immutable upper bound. Coverage
            // therefore describes exactly what the provider was asked for,
            // even when discovery widens from 7 to 30 to 90 days.
            val durableBeforeScan = setupImportStore.state.value
            val providerMaxDate = historicalScanProviderMaxDate(
                state = durableBeforeScan,
                resumeWindowDays = resumeHistoryWindowDays,
                nowMillis = System.currentTimeMillis()
            )
            var rawMessages =
                emptyList<com.pocketfinancer.sms.SmsReader.SmsMessage>()
            var transactionalMessages =
                emptyList<com.pocketfinancer.sms.SmsReader.SmsMessage>()
            var alreadySavedCount = 0

            while (true) {
                setupImportStore.update {
                    it.copy(
                        status = SetupImportStatus.SCANNING,
                        activeScanWindowDays = historyWindowDays,
                        activeScanProviderMaxDateMillis = providerMaxDate,
                        emptyReason = null,
                        pauseReason = null,
                        actionableError = null
                    )
                }
                syncManager.updateState {
                    it.copy(
                        syncProgress = 0.15f,
                        syncMessage =
                            "Checking the last $historyWindowDays days..."
                    )
                }
                runOnWorkflowProgress(
                    "Checking SMS History",
                    "Scanning the last $historyWindowDays days...",
                    0.15f
                )
                addLog(
                    "SmsReader: Querying inbox history " +
                        "(last $historyWindowDays days)..."
                )

                rawMessages = try {
                    withContext(Dispatchers.IO) {
                        smsRepository.fetchHistory(
                            daysBack = historyWindowDays,
                            limit = Int.MAX_VALUE,
                            maxDate = providerMaxDate
                        )
                    }
                } catch (securityError: SecurityException) {
                    publishSmsPermissionLoss(
                        "SMS access was removed during the scan."
                    )
                    return
                }
                currentCoroutineContext().ensureActive()
                if (!smsRepository.hasPermissions()) {
                    ensureSmsPermissionForTerminalPublication()
                    return
                }
                val deterministicallyEligible = rawMessages.filter { message ->
                    smsFilterPipeline.isTransactional(
                        message.address,
                        message.body
                    )
                }
                currentCoroutineContext().ensureActive()
                transactionalMessages = withContext(Dispatchers.IO) {
                    val unsaved = mutableListOf<
                        com.pocketfinancer.sms.SmsReader.SmsMessage
                    >()
                    for (message in deterministicallyEligible) {
                        currentCoroutineContext().ensureActive()
                        if (
                            !transactionRepository
                                .preserveSourceMetadataIfExists(
                                    sourceIdentity = message.sourceIdentity,
                                    receivedDate = message.date
                                )
                        ) {
                            unsaved += message
                        }
                    }
                    unsaved
                }
                alreadySavedCount =
                    deterministicallyEligible.size - transactionalMessages.size
                val inboxHasAnyMessage = if (
                    rawMessages.isEmpty() &&
                    historyWindowDays >=
                        historyScanPolicy.widestAutomaticWindowDays
                ) {
                    try {
                        withContext(Dispatchers.IO) {
                            smsRepository.hasAnyInboxMessage(providerMaxDate)
                        }
                    } catch (securityError: SecurityException) {
                        publishSmsPermissionLoss(
                            "SMS access was removed during the scan."
                        )
                        return
                    }
                } else {
                    null
                }
                if (!smsRepository.hasPermissions()) {
                    ensureSmsPermissionForTerminalPublication()
                    return
                }
                val scanCompletedAt = System.currentTimeMillis()
                setupImportStore.update {
                    it.copy(
                        coverageStartMillis = (
                            providerMaxDate -
                                TimeUnit.DAYS.toMillis(
                                    historyWindowDays.toLong()
                                )
                            ).coerceAtLeast(0L),
                        coverageEndMillis = providerMaxDate,
                        coverageWindowDays = historyWindowDays,
                        providerMessageCount = rawMessages.size,
                        eligibleCandidateCount =
                            deterministicallyEligible.size,
                        processedCount = alreadySavedCount,
                        savedCount = alreadySavedCount,
                        rejectedCount = 0,
                        retainedReviewCount = 0,
                        failedCount = 0,
                        lastSuccessfulScanMillis = scanCompletedAt
                    )
                }
                syncManager.updateState {
                    it.copy(
                        syncTotalMessages = rawMessages.size,
                        syncTransactionalCount =
                            transactionalMessages.size
                    )
                }
                addLog(
                    "SmsReader: ${rawMessages.size} messages checked; " +
                        "${transactionalMessages.size} new eligible" +
                        if (alreadySavedCount > 0) {
                            "; $alreadySavedCount already saved."
                        } else {
                            "."
                        }
                )

                when (
                    val decision = historyScanPolicy.decide(
                        windowDays = historyWindowDays,
                        providerMessageCount = rawMessages.size,
                        eligibleCandidateCount =
                            transactionalMessages.size,
                        inboxHasAnyMessage = inboxHasAnyMessage
                    )
                ) {
                    is HistoryScanDecision.Widen -> {
                        addLog(
                            "Discovery: No eligible alerts; widening to " +
                                "${decision.nextWindowDays} days."
                        )
                        historyWindowDays = decision.nextWindowDays
                    }

                    is HistoryScanDecision.Process -> break

                    is HistoryScanDecision.NoEligibleHistory -> {
                        if (!ensureSmsPermissionForTerminalPublication()) {
                            return
                        }
                        if (!tryBeginHistoricalImportTerminalCommit(runId)) {
                            throw CancellationException(
                                "Historical import stop won before completion"
                            )
                        }
                        val terminalStatus =
                            if (alreadySavedCount > 0) {
                                SetupImportStatus.READY
                            } else {
                                SetupImportStatus.READY_NO_HISTORY
                            }
                        val terminalEmptyReason =
                            if (alreadySavedCount > 0) {
                                SetupEmptyReason.NO_ADDITIONAL_MESSAGES
                            } else {
                                decision.reason
                            }
                        setupImportStore.update {
                            it.copy(
                                status = terminalStatus,
                                activeScanWindowDays = null,
                                activeScanProviderMaxDateMillis = null,
                                emptyReason = terminalEmptyReason,
                                actionableError = null
                            )
                        }
                        syncManager.updateState {
                            it.copy(
                                syncProgress = 1f,
                                syncMessage = if (
                                    terminalStatus ==
                                        SetupImportStatus.READY
                                ) {
                                    "Saved transaction history is ready."
                                } else {
                                    "Ready for a future manual or automatic scan."
                                }
                            )
                        }
                        completeRun(
                            notificationTitle =
                                "Pocket Financer Is Ready",
                            notificationText = if (
                                terminalStatus == SetupImportStatus.READY
                            ) {
                                "Previously saved transaction history is ready."
                            } else {
                                "No eligible history was found in the checked range."
                            }
                        )
                        return
                    }
                }
            }

            addLog(
                "Pipeline: Found ${transactionalMessages.size} " +
                    "eligible alerts to process."
            )
            setupImportStore.update {
                it.copy(
                    status = SetupImportStatus.PROCESSING,
                    processedCount = alreadySavedCount,
                    savedCount = alreadySavedCount,
                    rejectedCount = 0,
                    retainedReviewCount = 0,
                    failedCount = 0,
                    emptyReason = null,
                    actionableError = null
                )
            }

        val messagesToProcess = transactionalMessages
        val totalCount = messagesToProcess.size
        addLog("AI: Beginning local offline parsing for $totalCount transactions...")

        val loopStartTime = System.currentTimeMillis()
        val defaultTimePerTxMs = 10000L
        var counters = HistoricalImportCounters(
            processedCount = alreadySavedCount
        )

        for ((index, sms) in messagesToProcess.withIndex()) {
            if (!smsRepository.hasPermissions()) {
                ensureSmsPermissionForTerminalPublication()
                return
            }
            currentCoroutineContext().ensureActive()
            val progressBase = 0.25f
            val progressScale = 0.70f
            val currentProgress = progressBase + (index.toFloat() / totalCount) * progressScale

            // Set initial/dynamic ETA
            val currentRemaining = totalCount - index
            val elapsedSoFar = System.currentTimeMillis() - loopStartTime
            val estimatedAvgTime = if (index > 0) (elapsedSoFar / index) else defaultTimePerTxMs
            val currentEtaSec = ((estimatedAvgTime * currentRemaining) / 1000f).toInt().coerceAtLeast(1)

            syncManager.updateState {
                it.copy(
                    syncProgress = currentProgress,
                    syncMessage =
                        "Analyzing SMS ${index + 1} of $totalCount...",
                    syncEtaSeconds = currentEtaSec
                )
            }
            runOnWorkflowProgress(
                "Syncing Transactions",
                "Analyzing SMS ${index + 1} of $totalCount (${currentEtaSec}s left)",
                currentProgress
            )

            addLog(
                "AI: Analyzing transaction ${index + 1}/$totalCount..."
            )

            val txStartTime = System.currentTimeMillis()
            var checkpointSettlement: HistoricalPersistenceSettlement? = null
            val candidateKey = sms.sourceIdentity.opaqueCandidateKey
            val activity = HistoricalSmsProcessingActivity(
                candidateKey = candidateKey,
                sender = sms.address,
                body = sms.body,
                date = sms.date,
                position = index + 1,
                total = totalCount
            )
            if (!syncManager.beginHistoricalSmsProcessing(runId, activity)) {
                throw CancellationException(
                    "Historical SMS activity no longer belongs to this run"
                )
            }
            val observer = HistoricalSmsProcessingObserver(
                initial = activity,
                publish = { snapshot ->
                    syncManager.updateHistoricalSmsProcessing(
                        runId = runId,
                        candidateKey = candidateKey
                    ) { snapshot }
                }
            )
            val result = try {
                withContext(Dispatchers.IO) {
                    try {
                        pipelineService.processSingle(
                            sms = sms,
                            lease = activeLease,
                            observer = observer,
                            trigger = "historical"
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Sync parse error", e)
                        PipelineService.ProcessingResult.Failure(
                            message = e.message ?: "Unknown pipeline error",
                            retryable = true
                        )
                    }
                }
            } finally {
                try {
                    observer.flush()
                } finally {
                    try {
                        observer.close()
                    } finally {
                        syncManager.clearHistoricalSmsProcessing(
                            runId = runId,
                            candidateKey = candidateKey
                        )
                    }
                }
            }
            val durationMs = System.currentTimeMillis() - txStartTime

            when (result) {
                PipelineService.ProcessingResult.Stopped ->
                    throw CancellationException("Onboarding inference stopped")
                PipelineService.ProcessingResult.AwaitingConfiguration -> {
                    addLog("➔ Paused: choose a primary currency in Settings.")
                    error("Primary currency confirmation is required")
                }
                is PipelineService.ProcessingResult.Saved -> {
                    val updated = counters.copy(
                        processedCount = counters.processedCount + 1,
                        parsedCount = counters.parsedCount +
                            if (result.alreadyCommitted) 0 else 1,
                        concurrentDuplicateCount = counters.concurrentDuplicateCount +
                            if (result.alreadyCommitted) 1 else 0
                    )
                    val settlement = checkpointHistoricalImportCounters(
                        setupImportStore = setupImportStore,
                        alreadySavedCount = alreadySavedCount,
                        counters = updated,
                        persistenceFailureMessage =
                            SETUP_CHECKPOINT_PERSISTENCE_FAILURE_MESSAGE
                    )
                    counters = settlement.counters
                    checkpointSettlement = settlement
                    addLog(
                        if (result.alreadyCommitted) {
                            "➔ Already saved; no duplicate was created " +
                                "[${"%.1f".format(durationMs / 1000f)}s]"
                        } else {
                            "➔ Saved transaction " +
                                "[${"%.1f".format(durationMs / 1000f)}s]"
                        }
                    )
                }
                is PipelineService.ProcessingResult.Failure -> {
                    val settlement = checkpointHistoricalImportCounters(
                        setupImportStore = setupImportStore,
                        alreadySavedCount = alreadySavedCount,
                        counters = counters.copy(
                            processedCount = counters.processedCount + 1,
                            failedCount = counters.failedCount + 1
                        ),
                        persistenceFailureMessage =
                            SETUP_CHECKPOINT_PERSISTENCE_FAILURE_MESSAGE
                    )
                    counters = settlement.counters
                    checkpointSettlement = settlement
                    addLog("➔ Failed: ${result.message}")
                }
                is PipelineService.ProcessingResult.Skipped -> {
                    val settlement = checkpointHistoricalImportCounters(
                        setupImportStore = setupImportStore,
                        alreadySavedCount = alreadySavedCount,
                        counters = counters.recordSkipped(result.reason),
                        persistenceFailureMessage =
                            SETUP_CHECKPOINT_PERSISTENCE_FAILURE_MESSAGE
                    )
                    counters = settlement.counters
                    checkpointSettlement = settlement
                    if (
                        result.reason ==
                        PipelineService.SkipReason.RETAINED_FOR_REVIEW
                    ) {
                        addLog(
                            "➔ Saved for review; no transaction was added " +
                                "[${"%.1f".format(durationMs / 1000f)}s]"
                        )
                    } else {
                        addLog(
                            "➔ Skipped (non-transactional content detected) " +
                                "[${"%.1f".format(durationMs / 1000f)}s]"
                        )
                    }
                }
            }

            val failedCheckpoint = checkpointSettlement
                ?.takeUnless(HistoricalPersistenceSettlement::isDurable)
            if (failedCheckpoint != null) {
                val persistenceError = checkNotNull(
                    failedCheckpoint.persistenceError
                )
                val failureMessage = checkNotNull(
                    failedCheckpoint.persistenceFailureMessage
                )
                if (!tryBeginHistoricalImportTerminalCommit(runId)) {
                    throw CancellationException(
                        "Historical import stop won after checkpoint failure",
                        persistenceError
                    )
                }
                Log.e(
                    TAG,
                    "Historical progress checkpoint failed",
                    persistenceError
                )
                addLog("System: $failureMessage")
                syncManager.updateState {
                    it.copy(
                        runId = null,
                        isRunning = false,
                        isCancelling = false,
                        isCancellationAllowed = false,
                        isPreparingHistoricalModel = false,
                        isDownloading = false,
                        syncMessage = failureMessage,
                        syncEtaSeconds = 0,
                        syncParsedCount = counters.parsedCount,
                        modelLoadError = failureMessage
                    )
                }
                try {
                    showTerminalNotification(
                        title = "SMS Import Needs Attention",
                        text = if (
                            failureMessage ==
                            SETUP_PROGRESS_PERSISTENCE_FAILURE_MESSAGE
                        ) {
                            "Transaction saved; open Pocket Financer to " +
                                "resume safely."
                        } else {
                            "Open Pocket Financer to resume the import safely."
                        }
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (notificationError: Exception) {
                    // The setup fallback is intentionally volatile after the
                    // failed checkpoint. Do not route notification trouble to
                    // the outer handler, which would attempt another durable
                    // setup write.
                    Log.e(
                        TAG,
                        "Could not show checkpoint failure notification",
                        notificationError
                    )
                }
                return
            }
        }

        syncManager.updateState {
            it.copy(
                syncProgress = 0.98f,
                syncMessage = "Finalizing local results...",
                syncEtaSeconds = 0
            )
        }
        runOnWorkflowProgress(
            "Finishing SMS Import",
            "Saving verified results...",
            0.98f
        )

        if (!ensureSmsPermissionForTerminalPublication()) {
            return
        }
        if (!tryBeginHistoricalImportTerminalCommit(runId)) {
            throw CancellationException(
                "Historical import stop won before completion"
            )
        }
        val terminalSavedCount =
            alreadySavedCount +
                counters.parsedCount +
                counters.concurrentDuplicateCount
        val terminalStatus = setupTerminalStatus(
            savedCount = terminalSavedCount,
            failedCount = counters.failedCount,
            concurrentDuplicateCount = 0
        )
        setupImportStore.update {
            it.copy(
                status = terminalStatus,
                processedCount = counters.processedCount,
                savedCount = terminalSavedCount,
                rejectedCount = counters.rejectedCount,
                retainedReviewCount = counters.retainedReviewCount,
                failedCount = counters.failedCount,
                activeScanWindowDays = if (
                    terminalStatus == SetupImportStatus.FAILED
                ) {
                    it.activeScanWindowDays
                } else {
                    null
                },
                activeScanProviderMaxDateMillis = if (
                    terminalStatus == SetupImportStatus.FAILED
                ) {
                    it.activeScanProviderMaxDateMillis
                } else {
                    null
                },
                emptyReason = if (
                    terminalStatus == SetupImportStatus.READY_NO_HISTORY
                ) {
                    SetupEmptyReason.CANDIDATES_REJECTED
                } else {
                    null
                },
                actionableError = if (counters.failedCount > 0) {
                    SetupActionableError(
                        code = "SMS_PROCESSING_FAILED",
                        message =
                            "${counters.failedCount} eligible alert" +
                                if (counters.failedCount == 1) {
                                    " could not be processed."
                                } else {
                                    "s could not be processed."
                                },
                        actionLabel = "Retry import"
                    )
                } else {
                    null
                }
            )
        }
        addLog(
            "System: Import finished: ${counters.parsedCount} saved, " +
                "${counters.rejectedCount} rejected, " +
                "${counters.retainedReviewCount} saved for review, " +
                "${counters.failedCount} failed" +
                if (counters.concurrentDuplicateCount > 0) {
                    ", ${counters.concurrentDuplicateCount} already saved."
                } else {
                    "."
                }
        )
        syncManager.updateState {
            it.copy(
                syncProgress = 1.0f,
                // Durable result rows are ready, but selected-model ownership
                // and the foreground run still have to commit atomically.
                syncMessage = "Finishing SMS import..."
            )
        }
        completeRun(
            notificationTitle = when (terminalStatus) {
                SetupImportStatus.FAILED ->
                    "SMS Import Needs Attention"
                SetupImportStatus.READY_NO_HISTORY ->
                    "Pocket Financer Is Ready"
                else -> "SMS Import Complete"
            },
            notificationText = when (terminalStatus) {
                SetupImportStatus.FAILED ->
                    "${counters.parsedCount} saved; " +
                        "${counters.failedCount} need another attempt."
                SetupImportStatus.READY_NO_HISTORY ->
                    "No transaction was saved from the checked candidates."
                else ->
                    "${counters.parsedCount} transaction" +
                        "${if (counters.parsedCount == 1) "" else "s"} saved locally."
            }
        )
        } finally {
            withContext(NonCancellable) {
                syncManager.clearHistoricalSmsProcessing(runId)
                batchLease?.release()
                provisionalPin?.rollbackUnlessCommitted()
            }
        }
    }

    /**
     * Permission can be revoked while provider rows are filtered or while the
     * model is parsing an already-read candidate. Re-check immediately before
     * any READY publication so restart/recovery shows the permission gate
     * instead of a stale terminal success.
     */
    private fun ensureSmsPermissionForTerminalPublication(): Boolean {
        if (smsRepository.hasPermissions()) return true
        publishSmsPermissionLoss(
            "SMS access was removed before setup could finish."
        )
        return false
    }

    /**
     * Permission reconciliation may race a user cancellation. Preserve the
     * admitted run's cancelling ownership until its non-cancellable cleanup
     * and foreground retirement finish; unrelated runs settle immediately.
     */
    private fun publishSmsPermissionLoss(errorMessage: String) {
        setupImportStore.reconcilePermission(granted = false)
        syncManager.updateState {
            if (
                it.isRunning &&
                it.isCancelling &&
                it.runPurpose ==
                    OnboardingSyncManager.RunPurpose.INITIAL_SETUP
            ) {
                it.copy(
                    isDownloading = false,
                    isCancellationAllowed = false,
                    syncMessage =
                        "Stopping SMS processing after SMS access changed...",
                    modelLoadError = errorMessage
                )
            } else {
                it.copy(
                    runId = null,
                    isRunning = false,
                    isCancelling = false,
                    isDownloading = false,
                    isCancellationAllowed = false,
                    isPreparingHistoricalModel = false,
                    activeHistoricalSms = null,
                    syncMessage =
                        "SMS access must be restored to finish setup.",
                    modelLoadError = errorMessage
                )
            }
        }
    }

    private fun persistCompletedSelection(slm: SlmTier): Boolean {
        val prefs = getSharedPreferences(".app_settings", Context.MODE_PRIVATE)
        return prefs.edit()
            .putBoolean("onboarding_completed", true)
            .putString("selected_slm_id", slm.id)
            .commit()
    }

    private fun persistSelectedModel(slm: SlmTier): Boolean {
        val prefs = getSharedPreferences(".app_settings", Context.MODE_PRIVATE)
        return prefs.edit()
            .putString("selected_slm_id", slm.id)
            .commit()
    }

    private fun publishOnboardingCompleted() {
        syncManager.updateState {
            scrubCompletedOnboardingState(it)
        }
    }

    private fun resolvePersistedSelectedModelSpec(): SlmModelSpec? {
        val selectedId = getSharedPreferences(".app_settings", Context.MODE_PRIVATE)
            .getString("selected_slm_id", null)
            ?: return null
        val tier = SlmTier.ALL_TIERS.find { it.id == selectedId } ?: return null
        val file = modelStorage.modelFile(tier.modelFile)
        if (!isPublishedModelArtifact(file)) return null
        return tier.toModelSpec(modelStorage, deviceCapabilities.assessDevice())
    }

    private fun getModelFile(slm: SlmTier): File {
        return modelStorage.modelFile(slm.modelFile)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "OnboardingService Destroyed")
        val destroyedRunId = activeRunId
        val stillOwnsManagerRun =
            destroyedRunId != null &&
                syncManager.syncState.value.runId == destroyedRunId
        val pendingHistoricalUserStop = destroyedRunId
            ?.let(syncManager::historicalCancellationRequested)
            ?: false
        workJob?.let(modelDownloader::cancel)
        downloadObserverJob?.cancel()
        workJob?.cancel()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        syncManager.updateState {
            if (it.runId != destroyedRunId) {
                it
            } else if (it.isCancelling) {
                // The workflow's non-cancellable finally block owns the
                // terminal Cancelled transition after rollback/gate release.
                it.copy(isDownloading = false)
            } else {
                it.copy(
                    isRunning = false,
                    isCancellationAllowed = false,
                    isPreparingHistoricalModel = false,
                    isDownloading = false,
                    activeHistoricalSms = null
                )
            }
        }
        if (
            setupImportStore.state.value.isActive &&
            stillOwnsManagerRun &&
            !pendingHistoricalUserStop
        ) {
            setupImportStore.update {
                it.copy(
                    status = SetupImportStatus.PAUSED,
                    pauseReason = SetupPauseReason.INTERRUPTED,
                    actionableError = SetupActionableError(
                        code = SetupImportStore.ERROR_INTERRUPTED,
                        message = "Setup stopped before it finished. Completed work is still saved.",
                        actionLabel = "Resume setup"
                    )
                )
            }
        }
        super.onDestroy()
    }
}

internal fun initialHistoryWindowDays(
    policy: AdaptiveHistoryScanPolicy,
    coveredWindowDays: Int?,
    resumeWindowDays: Int?
): Int = resumeWindowDays ?: policy.firstWindowAfter(coveredWindowDays)

internal fun historicalScanProviderMaxDate(
    state: SetupImportState,
    resumeWindowDays: Int?,
    nowMillis: Long
): Long =
    state.activeScanProviderMaxDateMillis
        ?.takeIf {
            resumeWindowDays != null &&
                state.activeScanWindowDays == resumeWindowDays
        }
        ?: state.coverageEndMillis
            ?.takeIf {
                resumeWindowDays != null &&
                    state.coverageWindowDays == resumeWindowDays &&
                    state.lastSuccessfulScanMillis != null
            }
        ?: nowMillis

internal fun scrubCompletedOnboardingState(
    state: OnboardingSyncManager.OnboardingSyncState
): OnboardingSyncManager.OnboardingSyncState = state.copy(
    runId = null,
    step = OnboardingStep.COMPLETED,
    isRunning = false,
    isCancelling = false,
    isCancellationAllowed = false,
    isPreparingHistoricalModel = false,
    isDownloading = false,
    isModelLoaded = true,
    syncMessage = "Setup finished",
    syncLogs = emptyList(),
    activeHistoricalSms = null
)

internal const val USER_REQUESTED_HISTORY_PAUSE_ERROR =
    "HISTORICAL_IMPORT_STOPPED_BY_USER"

/**
 * Durable settlement after all runtime/model leases have drained.
 *
 * Coverage, active scan bounds, and every counter are intentionally inherited
 * unchanged. The active bounds make resume conservatively rediscover current
 * and remaining in-memory candidates, while source identity deduplication
 * keeps already committed transactions idempotent.
 */
internal fun pauseHistoricalImportForUser(
    state: SetupImportState
): SetupImportState {
    if (
        state.status == SetupImportStatus.PAUSED &&
        state.pauseReason == SetupPauseReason.USER_REQUESTED &&
        state.actionableError?.code == USER_REQUESTED_HISTORY_PAUSE_ERROR
    ) {
        return state
    }
    if (!state.status.isHistoricalImportCancellable()) return state
    return state.copy(
        status = SetupImportStatus.PAUSED,
        pauseReason = SetupPauseReason.USER_REQUESTED,
        actionableError = SetupActionableError(
            code = USER_REQUESTED_HISTORY_PAUSE_ERROR,
            message =
                "SMS processing stopped at your request. Completed saves " +
                    "remain on this device. Resume to rediscover and finish " +
                    "any remaining messages safely.",
            actionLabel = "Resume SMS import"
        )
    )
}

internal fun historicalStopPersistenceFallback(
    state: SetupImportState
): SetupImportState = pauseHistoricalImportForUser(state).copy(
    actionableError = SetupActionableError(
        code = "SMS_STOP_STATE_NOT_SAVED",
        message =
            "SMS processing stopped, but the pause state could not be saved. " +
                "Reopen Pocket Financer before resuming.",
        actionLabel = "Resume setup"
    )
)

internal fun onboardingStartMatches(
    state: OnboardingSyncManager.OnboardingSyncState,
    requestedRunId: String,
    requestedPurpose: OnboardingSyncManager.RunPurpose
): Boolean =
    requestedRunId.isNotBlank() &&
        state.runId == requestedRunId &&
        state.runPurpose == requestedPurpose &&
        state.isRunning &&
        !state.isCancelling

internal data class HistoricalImportCounters(
    val processedCount: Int = 0,
    val parsedCount: Int = 0,
    val rejectedCount: Int = 0,
    val retainedReviewCount: Int = 0,
    val failedCount: Int = 0,
    val concurrentDuplicateCount: Int = 0
)

internal data class HistoricalPersistenceSettlement(
    val counters: HistoricalImportCounters,
    val persistenceError: Exception? = null,
    val persistenceFailureMessage: String? = null
) {
    val isDurable: Boolean
        get() = persistenceError == null
}

/**
 * Attempts one restart-facing historical checkpoint. This function is
 * synchronous; callers invoke it either in PipelineService's NonCancellable
 * post-commit callback or immediately after a completed per-item result.
 * Consequently, a CancellationException thrown here is a persistence callback
 * failure, not ambient coroutine cancellation, and receives the same truthful
 * fallback as every other checkpoint exception.
 */
internal fun checkpointHistoricalImportCounters(
    setupImportStore: SetupImportStore,
    alreadySavedCount: Int,
    counters: HistoricalImportCounters,
    persistenceFailureMessage: String
): HistoricalPersistenceSettlement {
    return try {
        persistHistoricalImportCounters(
            setupImportStore = setupImportStore,
            alreadySavedCount = alreadySavedCount,
            counters = counters
        )
        HistoricalPersistenceSettlement(counters = counters)
    } catch (persistenceError: Exception) {
        val committedSavedCount = alreadySavedCount +
            counters.parsedCount +
            counters.concurrentDuplicateCount
        val current = setupImportStore.state.value
        setupImportStore.publishVolatilePersistenceFallback(
            current.copy(
                status = if (
                    current.status == SetupImportStatus.PERMISSION_NEEDED
                ) {
                    SetupImportStatus.PERMISSION_NEEDED
                } else {
                    SetupImportStatus.FAILED
                },
                processedCount = counters.processedCount,
                savedCount = committedSavedCount,
                rejectedCount = counters.rejectedCount,
                retainedReviewCount = counters.retainedReviewCount,
                failedCount = counters.failedCount,
                emptyReason = if (
                    current.status == SetupImportStatus.PERMISSION_NEEDED
                ) {
                    current.emptyReason
                } else {
                    null
                },
                pauseReason = if (
                    current.status == SetupImportStatus.PERMISSION_NEEDED
                ) {
                    current.pauseReason
                } else {
                    null
                },
                actionableError = if (
                    current.status == SetupImportStatus.PERMISSION_NEEDED
                ) {
                    current.actionableError
                } else {
                    SetupActionableError(
                        code = SETUP_PROGRESS_PERSISTENCE_FAILED,
                        message = persistenceFailureMessage,
                        actionLabel = "Try again"
                    )
                }
            )
        )
        HistoricalPersistenceSettlement(
            counters = counters,
            persistenceError = persistenceError,
            persistenceFailureMessage = persistenceFailureMessage
        )
    }
}

internal fun persistHistoricalImportCounters(
    setupImportStore: SetupImportStore,
    alreadySavedCount: Int,
    counters: HistoricalImportCounters
) {
    setupImportStore.update {
        it.copy(
            processedCount = counters.processedCount,
            savedCount = alreadySavedCount +
                counters.parsedCount +
                counters.concurrentDuplicateCount,
            rejectedCount = counters.rejectedCount,
            retainedReviewCount = counters.retainedReviewCount,
            failedCount = counters.failedCount
        )
    }
}

internal fun setupTerminalStatus(
    savedCount: Int,
    failedCount: Int,
    concurrentDuplicateCount: Int
): SetupImportStatus = when {
    failedCount > 0 -> SetupImportStatus.FAILED
    savedCount > 0 || concurrentDuplicateCount > 0 ->
        SetupImportStatus.READY
    else -> SetupImportStatus.READY_NO_HISTORY
}

internal fun onboardingServiceWorkIsUnfinished(
    workJobIsPresent: Boolean,
    workJobIsCompleted: Boolean,
    cleanupJobIsPresent: Boolean,
    cleanupJobIsCompleted: Boolean,
    cancellationSettlementPending: Boolean = false
): Boolean =
    (workJobIsPresent && !workJobIsCompleted) ||
        (cleanupJobIsPresent && !cleanupJobIsCompleted) ||
        cancellationSettlementPending

internal fun nextCleanupOnlyRetirementStartId(
    currentRetirementStartId: Int?,
    deliveredStartId: Int,
    workJobIsPresent: Boolean,
    workJobIsCompleted: Boolean,
    cleanupJobIsPresent: Boolean,
    cleanupJobIsCompleted: Boolean,
    cancellationSettlementPending: Boolean = false
): Int? = if (
    (
        (cleanupJobIsPresent && !cleanupJobIsCompleted) ||
            cancellationSettlementPending
        ) &&
    (!workJobIsPresent || workJobIsCompleted)
) {
    maxOf(currentRetirementStartId ?: deliveredStartId, deliveredStartId)
} else {
    currentRetirementStartId
}

internal fun HistoricalImportCounters.recordSkipped(reason: PipelineService.SkipReason): HistoricalImportCounters = copy(
    processedCount = processedCount + 1,
    rejectedCount = rejectedCount + if (reason == PipelineService.SkipReason.RETAINED_FOR_REVIEW) 0 else 1,
    retainedReviewCount = retainedReviewCount + if (reason == PipelineService.SkipReason.RETAINED_FOR_REVIEW) 1 else 0
)
