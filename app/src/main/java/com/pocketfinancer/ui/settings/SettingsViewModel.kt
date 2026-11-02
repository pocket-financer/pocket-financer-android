package com.pocketfinancer.ui.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.app.NotificationManagerCompat
import com.pocketfinancer.data.repository.TransactionRepository
import com.pocketfinancer.data.repository.ProcessingConfigurationRepository
import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.hardware.explainTierSelection
import com.pocketfinancer.hardware.isPublishedModelArtifact
import com.pocketfinancer.hardware.resolveActiveSlmTier
import com.pocketfinancer.inference.DownloadOwner
import com.pocketfinancer.inference.ModelDownloader
import com.pocketfinancer.inference.SlmLease
import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.inference.SlmModelStorage
import com.pocketfinancer.inference.SlmPendingAction
import com.pocketfinancer.inference.SlmRuntime
import com.pocketfinancer.inference.SlmRuntimeOwner
import com.pocketfinancer.inference.SlmRuntimePhase
import com.pocketfinancer.inference.SlmRuntimeState
import com.pocketfinancer.pipeline.AutomaticProcessingPreferences
import com.pocketfinancer.pipeline.PipelineService
import com.pocketfinancer.pipeline.SlmProcessingPreferences
import com.pocketfinancer.pipeline.SmsWorkAdmissionPause
import com.pocketfinancer.pipeline.SmsWorkController
import com.pocketfinancer.sms.SmsWorkScheduler
import com.pocketfinancer.sms.SmsReader
import com.pocketfinancer.setup.SetupImportStore
import com.pocketfinancer.SelectedModelResidency
import com.pocketfinancer.SelectedModelMutationPause
import com.pocketfinancer.SlmAppFlowCoordinator
import com.pocketfinancer.SlmAppFlowPause
import com.pocketfinancer.toModelSpec
import com.pocketfinancer.ui.home.HomeSyncManager
import com.pocketfinancer.ui.home.HomeSyncState
import com.pocketfinancer.ui.home.ModelUpgradeRecommendation
import com.pocketfinancer.ui.home.allowDebugEmulatorModelUpgrade
import com.pocketfinancer.ui.home.canCancelModelUpgrade
import com.pocketfinancer.ui.home.isHigherQualityModel
import com.pocketfinancer.ui.home.manualSmsOperationIsRunning
import com.pocketfinancer.ui.home.modelUpgradeStartBlockedMessage
import com.pocketfinancer.ui.home.selectModelUpgradeTarget
import com.pocketfinancer.ui.home.unfinishedModelUpgradeTarget
import com.pocketfinancer.ui.onboarding.OnboardingStep
import com.pocketfinancer.ui.home.SyncService
import com.pocketfinancer.ui.onboarding.OnboardingRunGenerationStore
import com.pocketfinancer.ui.onboarding.OnboardingService
import com.pocketfinancer.ui.onboarding.OnboardingSyncManager
import com.pocketfinancer.ui.onboarding.withoutHistoricalSmsActivity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val deviceInfo: DeviceCapabilities.DeviceInfo? = null,
    val hardwareError: String? = null,
    val selectedSlm: SlmTier? = null,
    val recommendedSlm: SlmTier? = null,
    val allTiers: List<SlmTier> = SlmTier.ALL_TIERS,
    val tierExplanations: Map<String, String> = emptyMap(),
    val downloadState: ModelDownloader.DownloadState = ModelDownloader.DownloadState(),
    val initialSetupDownloadState: ModelDownloader.DownloadState? = null,
    val upgradeRecommendation: ModelUpgradeRecommendation = ModelUpgradeRecommendation(),
    val modelLoaded: Boolean = false,
    val modelPath: String? = null,
    val modelPinnedByUser: Boolean = false,
    val loadingModel: Boolean = false,
    val modelLoadError: String? = null,
    val runtimeError: String? = null,
    val runtimePhase: SlmRuntimePhase = SlmRuntimePhase.UNLOADED,
    val runtimeBusy: Boolean = false,
    val runtimeQueueDepth: Int = 0,
    val runtimeLeaseCount: Int = 0,
    val runtimeActiveOwner: String? = null,
    val pendingRuntimeAction: String? = null,
    val flowBusy: Boolean = false,
    val resetRunning: Boolean = false,
    val testRunning: Boolean = false,
    val testProgress: String? = null,
    val testResult: String? = null,
    val testParsed: String? = null,
    val testError: String? = null,
    val filterLogs: List<String>? = null,
    val sessionCacheLogs: List<String>? = null,
    val slmPrompt: String? = null,
    val processIncomingSms: Boolean = AutomaticProcessingPreferences.DEFAULT_ENABLED,
    val automaticProcessingChangeRunning: Boolean = false,
    val automaticProcessingError: String? = null,
    val primaryCurrency: String = "INR",
    val confirmedPrimaryCurrency: String? = null,
    val primaryCurrencyError: String? = null,
    val gbnfGrammarEnabled: Boolean = false,
    val gbnfGrammarError: String? = null,
    val readSmsPermissionGranted: Boolean = false,
    val receiveSmsPermissionGranted: Boolean = false,
    val notificationPermissionRequired: Boolean = false,
    val notificationRuntimePermissionGranted: Boolean = true,
    val appNotificationsEnabled: Boolean = true,
    val progressNotificationChannelEnabled: Boolean = true,
    val notificationPermissionGranted: Boolean = true,
    val initialSetupModelPrepared: Boolean = false
) {
    val smsPermissionGranted: Boolean
        get() = readSmsPermissionGranted && receiveSmsPermissionGranted

    val canLoadModel: Boolean
        get() = selectedSlm != null &&
            downloadState.isComplete &&
            !loadingModel &&
            !testRunning &&
            !resetRunning &&
            !flowBusy &&
            runtimePhase != SlmRuntimePhase.MAINTENANCE

    val canUnloadModel: Boolean
        get() = modelPinnedByUser &&
            !loadingModel &&
            !testRunning &&
            !resetRunning &&
            !flowBusy

    val canRunTest: Boolean
        get() = modelLoaded &&
            !testRunning &&
            !loadingModel &&
            !resetRunning &&
            !flowBusy &&
            pendingRuntimeAction == null

    val canResetOnboarding: Boolean
        get() = !runtimeBusy &&
            !flowBusy &&
            !downloadState.isDownloading &&
            !loadingModel &&
            !testRunning &&
            !resetRunning
}

private data class ActiveModelRefreshKey(
    val runPurpose: OnboardingSyncManager.RunPurpose,
    val step: OnboardingStep,
    val selectedSlmId: String?,
    val isRunning: Boolean,
    val isModelLoaded: Boolean
)

private data class SettingsFlowSnapshot(
    val downloadState: ModelDownloader.DownloadState,
    val isBusy: Boolean,
    val onboarding: OnboardingSyncManager.OnboardingSyncState,
    val upgradeStartBlockedMessage: String?
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceCapabilities: DeviceCapabilities,
    private val slmRuntime: SlmRuntime,
    private val modelStorage: SlmModelStorage,
    private val modelDownloader: ModelDownloader,
    private val transactionRepository: TransactionRepository,
    private val automaticProcessingPreferences: AutomaticProcessingPreferences,
    private val slmProcessingPreferences: SlmProcessingPreferences,
    private val smsWorkController: SmsWorkController,
    private val selectedModelResidency: SelectedModelResidency,
    private val appFlowCoordinator: SlmAppFlowCoordinator,
    private val homeSyncManager: HomeSyncManager,
    private val onboardingSyncManager: OnboardingSyncManager,
    private val onboardingRunGenerationStore: OnboardingRunGenerationStore,
    private val setupImportStore: SetupImportStore,
    private val permissionHealthReader: SettingsPermissionHealthReader,
    private val processingConfigurationRepository: ProcessingConfigurationRepository,
    private val smsWorkScheduler: SmsWorkScheduler,
    private val pipelineService: PipelineService
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()
    private var testJob: Job? = null
    private var modelActionJob: Job? = null
    private var downloadJob: Job? = null
    private var resetJob: Job? = null
    private val onboardingFlowState = onboardingSyncManager.syncState
        .map { it.withoutHistoricalSmsActivity() }
        .distinctUntilChanged()

    init {
        assessDevice()
        _state.value = _state.value.copy(
            processIncomingSms = automaticProcessingPreferences.enabled.value,
            gbnfGrammarEnabled = slmProcessingPreferences.gbnfGrammarEnabled.value,
            primaryCurrency = processingConfigurationRepository.confirmedPrimaryCurrency() ?: "INR",
            confirmedPrimaryCurrency = processingConfigurationRepository.confirmedPrimaryCurrency(),
            initialSetupModelPrepared =
                setupImportStore.state.value.modelPrepared
        )
        refreshPermissionHealth()
        applyRuntimeState(slmRuntime.state.value)

        viewModelScope.launch {
            slmRuntime.state.collect { runtimeState ->
                applyRuntimeState(runtimeState)
            }
        }
        viewModelScope.launch {
            slmProcessingPreferences.gbnfGrammarEnabled.collect { enabled ->
                _state.value = _state.value.copy(gbnfGrammarEnabled = enabled)
            }
        }
        viewModelScope.launch {
            automaticProcessingPreferences.enabled.collect { enabled ->
                _state.value = _state.value.copy(processIncomingSms = enabled)
            }
        }
        viewModelScope.launch {
            setupImportStore.state.collect { setup ->
                _state.value = _state.value.copy(
                    initialSetupModelPrepared = setup.modelPrepared
                )
            }
        }
        viewModelScope.launch {
            combine(
                homeSyncManager.syncState,
                onboardingFlowState,
                appFlowCoordinator.state,
                modelDownloader.state,
                homeSyncManager.manualOperationReservation
            ) { home, onboarding, appFlows, downloaderState, manualReservation ->
                val normalized = normalizedDownloadState(downloaderState)
                val otherFlowBusy = manualSmsOperationIsRunning(
                    state = home,
                    startPending = manualReservation != null
                ) ||
                    appFlows.activeCount > 0 ||
                    appFlows.admissionPaused
                SettingsFlowSnapshot(
                    downloadState = normalized,
                    isBusy = onboarding.isRunning || otherFlowBusy,
                    onboarding = onboarding,
                    upgradeStartBlockedMessage = modelUpgradeStartBlockedMessage(
                        onboarding = onboarding,
                        otherFlowBusy = otherFlowBusy
                    )
                )
            }.collect { snapshot ->
                val normalized = snapshot.downloadState
                val onboarding = snapshot.onboarding
                val currentState = _state.value
                val activeSlm = settingsActiveSlm(currentState.selectedSlm, onboarding)
                val managedTarget = unfinishedModelUpgradeTarget(onboarding)
                val recommendedSlm = managedTarget ?: currentState.recommendedSlm
                val upgradeRecommendation = settingsModelUpgradeRecommendation(
                    currentSlm = activeSlm,
                    recommendedSlm = recommendedSlm,
                    onboarding = onboarding,
                    fallbackDownloadState = normalized,
                    startBlockedMessage = snapshot.upgradeStartBlockedMessage
                )
                val initialSetupDownloadState = onboarding.downloadState.takeIf {
                    onboarding.runPurpose ==
                        OnboardingSyncManager.RunPurpose.INITIAL_SETUP &&
                        onboarding.isRunning &&
                        onboarding.isDownloading
                }
                _state.value = currentState.copy(
                    selectedSlm = activeSlm,
                    downloadState = normalized,
                    initialSetupDownloadState = initialSetupDownloadState,
                    upgradeRecommendation = upgradeRecommendation,
                    flowBusy = snapshot.isBusy
                )
            }
        }
        viewModelScope.launch {
            onboardingSyncManager.syncState
                .map { onboarding ->
                    ActiveModelRefreshKey(
                        runPurpose = onboarding.runPurpose,
                        step = onboarding.step,
                        selectedSlmId = onboarding.selectedSlm?.id,
                        isRunning = onboarding.isRunning,
                        isModelLoaded = onboarding.isModelLoaded
                    )
                }
                .distinctUntilChanged()
                .drop(1)
                .collectLatest {
                    refreshActiveSlmFromStorage()
                }
        }
    }

    private suspend fun refreshActiveSlmFromStorage() {
        val device = _state.value.deviceInfo ?: return
        val activeSlm = try {
            withContext(Dispatchers.IO) {
                resolveActiveSlmTier(context, modelStorage.modelDirectory, device)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Could not refresh the active model tier", error)
            return
        }
        // Resolution suspends on IO. Re-read state and apply the durable
        // completion rule before copying the result.
        val currentState = _state.value
        val onboarding = onboardingSyncManager.syncState.value
        val recommendedSlm = unfinishedModelUpgradeTarget(onboarding)
            ?: currentState.recommendedSlm
        val currentSlm = settingsActiveSlm(activeSlm, onboarding)
        _state.value = currentState.copy(
            selectedSlm = currentSlm,
            upgradeRecommendation = settingsModelUpgradeRecommendation(
                currentSlm = currentSlm,
                recommendedSlm = recommendedSlm,
                onboarding = onboarding,
                fallbackDownloadState = currentState.downloadState,
                startBlockedMessage = currentModelUpgradeStartBlockedMessage(onboarding)
            )
        )
    }

    private fun assessDevice() {
        try {
            val device = deviceCapabilities.assessDevice()
            val recommended = settingsRecommendedSlm(
                device = device,
                allowDebugEmulatorOverride = allowDebugEmulatorModelUpgrade(context)
            )
            val active = resolveActiveSlmTier(context, modelStorage.modelDirectory, device)
            _state.value = _state.value.copy(
                deviceInfo = device,
                selectedSlm = active,
                recommendedSlm = recommended,
                tierExplanations = SlmTier.ALL_TIERS.associate { tier ->
                    tier.id to explainTierSelection(tier, device, tier == recommended)
                }
            )
        } catch (error: Exception) {
            _state.value = _state.value.copy(
                hardwareError = "Failed to read hardware: ${error.message}"
            )
        }
    }

    fun downloadSelectedModel() {
        if (!initialModelManagementAvailable()) return
        val tier = _state.value.selectedSlm ?: return
        if (downloadJob?.isActive == true) return
        if (_state.value.runtimeBusy ||
            _state.value.modelLoaded ||
            _state.value.flowBusy ||
            _state.value.testRunning ||
            _state.value.resetRunning
        ) {
            _state.value = _state.value.copy(
                modelLoadError =
                    "Unload the resident model and wait for current model work before downloading a replacement."
            )
            return
        }
        val destination = modelStorage.modelFile(tier.modelFile)
        if (destination.exists()) {
            _state.value = _state.value.copy(
                modelLoadError =
                    "A model file already exists at ${destination.absolutePath}. " +
                        "Settings will not replace an existing model artifact; " +
                        "the file was left unchanged."
            )
            return
        }
        downloadJob = viewModelScope.launch {
            val flowLease = appFlowCoordinator.tryEnter(SlmRuntimeOwner.SETTINGS_MANUAL)
            if (flowLease == null) {
                _state.value = _state.value.copy(
                    modelLoadError = "Downloads are paused while onboarding reset completes."
                )
                return@launch
            }
            _state.value = _state.value.copy(modelLoadError = null)
            var downloadedPath: String? = null
            try {
                val result = modelDownloader.download(tier.downloadUrl, destination, DownloadOwner.SETTINGS)
                result.fold(
                    onSuccess = { downloadedPath = it },
                    onFailure = { error ->
                        _state.value = _state.value.copy(
                            modelLoadError = error.message ?: "Model download failed."
                        )
                    }
                )
            } finally {
                withContext(NonCancellable) {
                    flowLease.release()
                }
            }

            // Auto-load belongs only to this successful Settings action. A
            // passive downloader/app-flow StateFlow emission must never undo a
            // manual unload or repin a model after onboarding reset.
            val completedPath = downloadedPath ?: return@launch
            ensureActive()
            val onboardingCompleted = context
                .getSharedPreferences(APP_SETTINGS, Context.MODE_PRIVATE)
                .getBoolean(ONBOARDING_COMPLETED, false)
            if (onboardingCompleted) {
                loadModelFromPath(completedPath)
            }
        }
    }

    fun startRecommendedModelUpgrade() {
        homeSyncManager.withSmsOperationStartBoundary {
            val current = _state.value
            if (!current.initialSetupModelPrepared) {
                _state.value = current.copy(
                    modelLoadError =
                        "Prepare the first on-device model from Home before upgrading."
                )
                return@withSmsOperationStartBoundary
            }
            val blockedMessage = currentModelUpgradeStartBlockedMessage()
            if (blockedMessage != null) {
                _state.value = current.copy(
                    upgradeRecommendation = current.upgradeRecommendation.copy(
                        startBlockedMessage = blockedMessage
                    )
                )
                return@withSmsOperationStartBoundary
            }
            val recommendation = current.upgradeRecommendation
            val target = recommendation.recommendedSlm
                ?: return@withSmsOperationStartBoundary
            if (
                !recommendation.isUpgradeAvailable ||
                recommendation.isRunning
            ) return@withSmsOperationStartBoundary

            _state.value = current.copy(modelLoadError = null)
            onboardingSyncManager.startModelUpgrade(context, target)
        }
    }

    private fun currentModelUpgradeStartBlockedMessage(
        onboarding: OnboardingSyncManager.OnboardingSyncState =
            onboardingSyncManager.syncState.value
    ): String? {
        val appFlows = appFlowCoordinator.state.value
        val otherFlowBusy = manualSmsOperationIsRunning(
            state = homeSyncManager.syncState.value,
            startPending =
                homeSyncManager.manualOperationReservation.value != null
        ) ||
            appFlows.activeCount > 0 ||
            appFlows.admissionPaused
        return modelUpgradeStartBlockedMessage(
            onboarding = onboarding,
            otherFlowBusy = otherFlowBusy
        )
    }

    fun cancelRecommendedModelUpgrade() {
        if (!canCancelModelUpgrade(onboardingSyncManager.syncState.value)) return
        onboardingSyncManager.requestModelUpgradeCancellation(context)
    }

    fun cancelDownload() {
        if (!initialModelManagementAvailable()) return
        downloadJob?.let(modelDownloader::cancel)
    }

    fun loadSelectedModel() {
        if (!initialModelManagementAvailable()) return
        val tier = _state.value.selectedSlm ?: return
        val file = modelStorage.modelFile(tier.modelFile)
        if (!file.exists() || file.length() == 0L) {
            _state.value = _state.value.copy(
                modelLoadError = "Model not downloaded yet.\nTap \"DOWNLOAD MODEL\" first."
            )
            return
        }
        loadModelFromPath(file.absolutePath)
    }

    private fun initialModelManagementAvailable(): Boolean {
        if (_state.value.initialSetupModelPrepared) return true
        _state.value = _state.value.copy(
            modelLoadError =
                "Prepare the first on-device model from Home. That flow keeps " +
                    "download confirmation and restart progress together."
        )
        return false
    }

    private fun loadModelFromPath(path: String) {
        if (modelActionJob?.isActive == true) return
        modelActionJob = viewModelScope.launch {
            val flowLease = appFlowCoordinator.tryEnter(SlmRuntimeOwner.SETTINGS_MANUAL)
            if (flowLease == null) {
                _state.value = _state.value.copy(
                    loadingModel = false,
                    modelLoadError = "Model changes are paused while onboarding reset completes."
                )
                return@launch
            }
            var provisionalPin: com.pocketfinancer.ProvisionalSelectedModelPin? = null
            try {
                val tier = _state.value.selectedSlm ?: return@launch
                val file = File(path)
                val diagnostics = buildDiagnostics(file)
                if (!file.exists() || file.length() == 0L) {
                    _state.value = _state.value.copy(
                        loadingModel = false,
                        modelLoadError = "Model file missing or empty.\n\n$diagnostics"
                    )
                    return@launch
                }
                if (file.canonicalFile != modelStorage.modelFile(tier.modelFile).canonicalFile) {
                    _state.value = _state.value.copy(
                        modelLoadError = "Downloaded artifact does not match the selected model."
                    )
                    return@launch
                }

                _state.value = _state.value.copy(
                    loadingModel = true,
                    modelLoadError = null,
                    pendingRuntimeAction = "Loading ${tier.name}"
                )
                val device = _state.value.deviceInfo ?: deviceCapabilities.assessDevice()
                val spec = tier.toModelSpec(modelStorage, device)
                val handoff = selectedModelResidency.beginProvisionalPin(
                    spec = spec,
                    persistedFallback = resolvePersistedSelectedModelSpec()
                )
                provisionalPin = handoff

                // Commit selection only after the exact model successfully loads
                // and owns its persistent residency pin. Selection and setup
                // readiness share one durable commit so Home cannot observe a
                // selected model while still claiming preparation is required.
                val committed = withContext(NonCancellable) {
                    handoff.commit {
                        runCatching {
                            setupImportStore.markModelPrepared(tier.id)
                            true
                        }.getOrDefault(false)
                    }
                }
                check(committed) {
                    "Could not durably persist the selected model."
                }
                _state.value = _state.value.copy(
                    loadingModel = false,
                    modelLoadError = null
                )
            } catch (cancelled: CancellationException) {
                _state.value = _state.value.copy(loadingModel = false)
                throw cancelled
            } catch (error: Exception) {
                val nativeHint = if (error.message?.contains("Failed to load model") == true) {
                    "\n\nCheck logcat for native error: adb logcat -s pocketfinancer_llm:*"
                } else {
                    ""
                }
                val diagnostics = buildDiagnostics(File(path))
                _state.value = _state.value.copy(
                    loadingModel = false,
                    modelLoadError = "${error.message}$nativeHint\n\n$diagnostics"
                )
            } finally {
                withContext(NonCancellable) {
                    provisionalPin?.rollbackUnlessCommitted()
                    flowLease.release()
                }
            }
        }
    }

    fun unloadModel() {
        if (!_state.value.canUnloadModel) return
        if (modelActionJob?.isActive == true) return
        modelActionJob = viewModelScope.launch {
            val flowLease = appFlowCoordinator.tryEnter(SlmRuntimeOwner.SETTINGS_MANUAL)
            if (flowLease == null) {
                _state.value = _state.value.copy(
                    modelLoadError = "Model changes are paused while onboarding reset completes."
                )
                return@launch
            }
            _state.value = _state.value.copy(
                pendingRuntimeAction =
                    "Releasing selected-model residency; active work will drain safely"
            )
            try {
                // Releases only the Settings/user pin. Other leases or pins keep
                // the model resident and physical unload remains deferred.
                selectedModelResidency.unpin()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    modelLoadError = "Could not release model: ${error.message}"
                )
            } finally {
                withContext(NonCancellable) {
                    flowLease.release()
                }
            }
        }
    }

    fun refreshModelStatus() {
        applyRuntimeState(slmRuntime.state.value)
        _state.value = _state.value.copy(
            downloadState = normalizedDownloadState(_state.value.downloadState)
        )
    }

    fun runTestSms() {
        if (testJob?.isActive == true) return
        val spec = slmRuntime.state.value.loadedModel
        if (spec == null || !_state.value.canRunTest) {
            _state.value = _state.value.copy(testError = "Model not loaded or runtime is busy.")
            return
        }

        testJob = viewModelScope.launch {
            runTestSms(spec)
        }
    }

    fun cancelTestSms() {
        testJob?.cancel()
    }

    private suspend fun runTestSms(spec: SlmModelSpec) {
        val sender = "AX-HDFCBK"
        val body =
            "HDFC Bank: Rs.500.00 credited to a/c XXXXXX0000 on 01-01-20 by " +
                "a/c linked to VPA demouser000@examplebank (UPI Ref No 000000000000)."
        val appFlowLease = appFlowCoordinator.tryEnter(SlmRuntimeOwner.SETTINGS_TEST)
        if (appFlowLease == null) {
            _state.value = _state.value.copy(
                testRunning = false,
                testProgress = null,
                testError = "Test is paused while onboarding reset completes."
            )
            return
        }
        var lease: SlmLease? = null

        _state.value = _state.value.copy(
            testRunning = true,
            testProgress = "Preparing deterministic analysis...",
            testResult = null,
            testParsed = null,
            testError = null,
            filterLogs = null,
            sessionCacheLogs = null,
            slmPrompt = null
        )

        try {
            _state.value = _state.value.copy(
                testProgress = "Running one Candidate Selector pass..."
            )
            val testLease = slmRuntime.acquire(SlmRuntimeOwner.SETTINGS_TEST, spec)
            lease = testLease
            val startedAt = System.currentTimeMillis()
            val now = System.currentTimeMillis()
            val result = pipelineService.processSingle(
                sms = SmsReader.SmsMessage(
                    address = sender,
                    body = body,
                    date = now,
                    type = 1,
                    providerMessageId = "synthetic-${java.util.UUID.randomUUID()}",
                    sourceTimestamp = now
                ),
                lease = testLease,
                trigger = "diagnostic"
            )
            val elapsedMs = System.currentTimeMillis() - startedAt
            val summary = when (result) {
                is PipelineService.ProcessingResult.Saved ->
                    if (result.alreadyCommitted) {
                        "Direct extraction completed. The synthetic transaction was already saved."
                    } else {
                        "Direct extraction completed and saved the synthetic transaction."
                    }
                is PipelineService.ProcessingResult.Skipped ->
                    if (result.reason == PipelineService.SkipReason.RETAINED_FOR_REVIEW) {
                        "Direct Candidate Selector completed. The synthetic result is saved for review."
                    } else {
                        "Deterministic analysis classified the synthetic message as not posted."
                    }
                PipelineService.ProcessingResult.AwaitingConfiguration ->
                    "Confirm a primary currency before running this diagnostic."
                PipelineService.ProcessingResult.Stopped -> "Diagnostic stopped."
                is PipelineService.ProcessingResult.Failure -> result.message
            }
            _state.value = _state.value.copy(
                testRunning = false,
                testProgress = null,
                testResult = "$summary\nElapsed: ${elapsedMs}ms",
                testParsed = if (result is PipelineService.ProcessingResult.Saved) {
                    "Open Transactions for the saved synthetic transaction."
                } else {
                    "Open Saved alert reviews for the durable Decision Trace."
                },
                testError = if (result is PipelineService.ProcessingResult.Failure) summary else null
            )
        } catch (cancelled: CancellationException) {
            _state.value = _state.value.copy(
                testRunning = false,
                testProgress = null,
                testError = "Test cancelled"
            )
        } catch (error: Exception) {
            Log.e(TAG, "Test failed with exception", error)
            _state.value = _state.value.copy(
                testRunning = false,
                testProgress = null,
                testError = "Test failed: ${error.message}"
            )
        } finally {
            withContext(NonCancellable) {
                lease?.release()
                appFlowLease.release()
            }
        }
    }

    fun toggleProcessIncomingSms() {
        setProcessIncomingSms(!_state.value.processIncomingSms)
    }

    fun setProcessIncomingSms(enabled: Boolean) {
        if (_state.value.automaticProcessingChangeRunning) return
        changeAutomaticProcessing(enabled = enabled)
    }

    fun selectPrimaryCurrency(currency: String) {
        val normalized = currency.uppercase()
        if (normalized !in ProcessingConfigurationRepository.SUPPORTED) return
        _state.value = _state.value.copy(
            primaryCurrency = normalized,
            primaryCurrencyError = null
        )
    }

    fun confirmPrimaryCurrency() {
        viewModelScope.launch {
            try {
                processingConfigurationRepository.confirmPrimaryCurrency(
                    _state.value.primaryCurrency
                )
                _state.value = _state.value.copy(
                    confirmedPrimaryCurrency = _state.value.primaryCurrency,
                    primaryCurrencyError = null
                )
                smsWorkScheduler.reconcilePendingAutomaticWork()
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    primaryCurrencyError = "Primary currency could not be saved locally."
                )
            }
        }
    }

    fun setGbnfGrammarEnabled(enabled: Boolean) {
        try {
            slmProcessingPreferences.setGbnfGrammarEnabled(enabled)
            _state.value = _state.value.copy(gbnfGrammarError = null)
        } catch (error: Exception) {
            _state.value = _state.value.copy(
                gbnfGrammarError =
                    "Grammar preference was not changed: " +
                        (error.message ?: "preference storage unavailable")
            )
        }
    }

    fun refreshPermissionHealth() {
        val health = permissionHealthReader.read()
        val smsPermissionGranted =
            health.readSmsPermissionGranted &&
                health.receiveSmsPermissionGranted
        _state.value = _state.value.copy(
            readSmsPermissionGranted = health.readSmsPermissionGranted,
            receiveSmsPermissionGranted = health.receiveSmsPermissionGranted,
            notificationPermissionRequired = health.notificationPermissionRequired,
            notificationRuntimePermissionGranted =
                health.notificationRuntimePermissionGranted,
            appNotificationsEnabled = health.appNotificationsEnabled,
            progressNotificationChannelEnabled =
                health.progressNotificationChannelEnabled,
            notificationPermissionGranted = health.progressNotificationsHealthy
        )
        setupImportStore.reconcilePermission(smsPermissionGranted)
        if (!smsPermissionGranted) {
            requestActiveSmsProcessingStopForPermissionLoss()
        }
    }

    private fun requestActiveSmsProcessingStopForPermissionLoss() {
        val manual = homeSyncManager.syncState.value
        val runId = manual.activeRunId
        if (
            runId != null &&
            manual.status in setOf(
                HomeSyncState.Status.SCANNING,
                HomeSyncState.Status.SYNCING
            )
        ) {
            val accepted = try {
                SyncService.requestStop(context, runId)
            } catch (error: RuntimeException) {
                Log.e(TAG, "Could not dispatch manual SMS stop", error)
                false
            }
            if (accepted) {
                homeSyncManager.requestServiceStop(runId)
            }
        }
        if (!onboardingSyncManager.requestHistoricalImportCancellation(context)) {
            Log.i(
                TAG,
                "No cancellable historical SMS operation accepted a permission stop"
            )
        }
    }

    private fun changeAutomaticProcessing(
        enabled: Boolean
    ) {
        _state.value = _state.value.copy(
            automaticProcessingChangeRunning = true,
            automaticProcessingError = null
        )
        viewModelScope.launch {
            try {
                when {
                    !enabled ->
                        automaticProcessingPreferences.disableAndCleanupPending {
                            smsWorkController.discardPendingAutomaticWork()
                        }
                    else ->
                        automaticProcessingPreferences.enableAfterCleanupPending {
                            smsWorkController.discardPendingAutomaticWork()
                        }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val automaticUpdatesStillEnabled =
                    automaticProcessingPreferences.enabled.value
                _state.value = _state.value.copy(
                    automaticProcessingError = when {
                        !enabled && automaticUpdatesStillEnabled ->
                            "Automatic SMS processing is still on because turning it off " +
                                "could not be saved: " +
                                (error.message ?: "unknown error")
                        !enabled ->
                            "Automatic SMS processing is off, but pending-work cleanup failed: " +
                                (error.message ?: "unknown error")
                        else ->
                            "Automatic SMS processing remains off because the change could not " +
                                "be completed: " +
                                (error.message ?: "unknown error")
                    }
                )
            } finally {
                _state.value = _state.value.copy(
                    automaticProcessingChangeRunning = false
                )
            }
        }
    }

    fun resetOnboarding(onSuccess: () -> Unit) {
        if (resetJob?.isActive == true) return
        if (!_state.value.canResetOnboarding) {
            _state.value = _state.value.copy(
                modelLoadError = "Reset is unavailable while model work or downloads are active."
            )
            return
        }
        resetJob = viewModelScope.launch {
            _state.value = _state.value.copy(resetRunning = true, modelLoadError = null)
            var appFlowPause: SlmAppFlowPause? = null
            var selectionPause: SelectedModelMutationPause? = null
            var maintenance: com.pocketfinancer.inference.SlmMaintenanceLease? = null
            var smsWorkPause: SmsWorkAdmissionPause? = null
            var previousSelectedPin: SlmModelSpec? = null
            var resetIntentDurable = false
            var invokeSuccess = false
            try {
                // Non-suspending acquisition means cancellation cannot close
                // admission without delivering the token to this finally.
                smsWorkPause = smsWorkController.pauseAdmissions()
                smsWorkPause.cancelPending()

                // Pause admission first and synchronously drain every admitted
                // foreground workflow. Service stop alone is asynchronous and
                // cannot close the scan/download-before-native race.
                appFlowPause = appFlowCoordinator.tryPauseAndDrain(
                    SlmRuntimeOwner.SETTINGS_MANUAL
                )
                if (appFlowPause == null) {
                    error("Another onboarding reset is already draining app flows.")
                }

                selectionPause = selectedModelResidency.tryPauseMutations()
                if (selectionPause == null) {
                    error("A selected-model handoff is still completing.")
                }
                previousSelectedPin =
                    selectedModelResidency.currentSelectedSpec()
                        ?: resolvePersistedSelectedModelSpec()

                SyncService.stop(context)
                context.stopService(
                    android.content.Intent(context, OnboardingService::class.java)
                )
                smsWorkController.cancelPending()

                maintenance = slmRuntime.tryAcquireMaintenance(
                    owner = SlmRuntimeOwner.SETTINGS_MANUAL,
                    pinsToRelease = setOf(SlmRuntimeOwner.SELECTED_MODEL)
                )
                if (maintenance == null) {
                    error("Runtime became busy. Wait for it to finish, then retry reset.")
                }
                // Runtime maintenance removed the selected pin directly.
                selectionPause.recordExternalPinMutation()

                // Close the WorkManager admission race a second time while the
                // native and app-flow gates are both held.
                smsWorkController.cancelPending()
                withContext(Dispatchers.IO) {
                    val retainedModelPrepared = SlmTier.ALL_TIERS.any { tier ->
                        isPublishedModelArtifact(
                            modelStorage.modelFile(tier.modelFile)
                        )
                    }
                    val nextGeneration =
                        onboardingRunGenerationStore.nextGeneration()
                    val nonEssentialCleanupFailure =
                        runLocalFinancialEraseCriticalSection(
                            beginDurableErase = {
                                setupImportStore.beginLocalFinancialErase(
                                    nextRunGeneration = nextGeneration
                                )
                            },
                            onDurableEraseStarted = {
                                resetIntentDurable = true
                            },
                            clearEncryptedData = {
                                transactionRepository.clearDatabase()
                                processingConfigurationRepository.clear()
                            },
                            cancelFinancialNotifications = {
                                NotificationManagerCompat.from(context).cancelAll()
                            },
                            resetInMemoryState = {
                                homeSyncManager.resetStateForErase()
                                onboardingSyncManager.reset()
                            },
                            commitSetupReset = {
                                setupImportStore.finishLocalFinancialErase(
                                    retainedModelPrepared = retainedModelPrepared
                                )
                            },
                            onCommitted = {
                                invokeSuccess = true
                            }
                        )
                    if (nonEssentialCleanupFailure != null) {
                        Log.e(
                            TAG,
                            "Financial data erased, but ancillary cleanup failed",
                            nonEssentialCleanupFailure
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    modelLoadError = "Reset failed: ${error.message}"
                )
            } finally {
                val cleanupError = withContext(NonCancellable) {
                    var firstFailure: Throwable? = null

                    suspend fun captureCleanup(block: suspend () -> Unit): Boolean {
                        try {
                            block()
                            return true
                        } catch (failure: Throwable) {
                            if (firstFailure == null) {
                                firstFailure = failure
                            } else {
                                firstFailure!!.addSuppressed(failure)
                            }
                            return false
                        }
                    }

                    val maintenanceReleased = if (maintenance == null) {
                        true
                    } else {
                        captureCleanup { maintenance?.release() }
                    }
                    // Never queue a restore behind a maintenance lease whose
                    // release failed; release every admission gate and surface
                    // the original cleanup failure instead.
                    if (maintenance != null &&
                        maintenanceReleased &&
                        !resetIntentDurable
                    ) {
                        captureCleanup {
                            selectionPause?.restorePin(previousSelectedPin)
                        }
                    }
                    captureCleanup { selectionPause?.release() }
                    captureCleanup { appFlowPause?.release() }
                    captureCleanup { smsWorkPause?.release() }
                    firstFailure
                }
                try {
                    if (cleanupError != null) {
                        invokeSuccess = false
                        Log.e(TAG, "Reset cleanup failed", cleanupError)
                        _state.value = _state.value.copy(
                            modelLoadError = "Reset cleanup failed: ${cleanupError.message}"
                        )
                    }
                } finally {
                    _state.value = _state.value.copy(resetRunning = false)
                }
            }
            if (invokeSuccess) {
                viewModelScope.launch {
                    refreshActiveSlmFromStorage()
                }
                onSuccess()
            }
        }
    }

    fun getModelFilePath(): String {
        val tier = _state.value.selectedSlm ?: return "No SLM selected"
        return modelStorage.modelFile(tier.modelFile).absolutePath
    }

    private fun resolvePersistedSelectedModelSpec(): SlmModelSpec? {
        val selectedId = context.getSharedPreferences(APP_SETTINGS, Context.MODE_PRIVATE)
            .getString(SELECTED_SLM_ID, null)
            ?: return null
        val tier = SlmTier.ALL_TIERS.find { it.id == selectedId } ?: return null
        val file = modelStorage.modelFile(tier.modelFile)
        if (!isPublishedModelArtifact(file)) return null
        val device = _state.value.deviceInfo ?: deviceCapabilities.assessDevice()
        return tier.toModelSpec(modelStorage, device)
    }

    private fun normalizedDownloadState(
        downloaderState: ModelDownloader.DownloadState
    ): ModelDownloader.DownloadState {
        if (downloaderState.isDownloading || downloaderState.isComplete) return downloaderState
        val tier = _state.value.selectedSlm ?: return downloaderState
        val file = modelStorage.modelFile(tier.modelFile)
        return if (isPublishedModelArtifact(file)) {
            downloaderState.copy(
                isComplete = true,
                progress = 1f,
                downloadedMb = file.length() / 1_048_576f,
                totalMb = file.length() / 1_048_576f,
                outputPath = file.absolutePath
            )
        } else {
            downloaderState
        }
    }

    private fun applyRuntimeState(runtime: SlmRuntimeState) {
        val selectedPin = runtime.pinnedModels[SlmRuntimeOwner.SELECTED_MODEL]
        val pending = when (val action = runtime.pendingAction) {
            is SlmPendingAction.ModelChange ->
                "Waiting to load ${File(action.model.modelPath).name}"
            SlmPendingAction.Unload ->
                "Unload pending until active work and owners finish"
            is SlmPendingAction.Maintenance ->
                "Maintenance requested by ${action.owner.value}"
            null -> null
        }
        val testIsQueued = _state.value.testRunning &&
            runtime.leasesByOwner.containsKey(SlmRuntimeOwner.SETTINGS_TEST) &&
            runtime.activeOperation?.owner != SlmRuntimeOwner.SETTINGS_TEST
        _state.value = _state.value.copy(
            modelLoaded = runtime.loadedModel != null,
            modelPath = runtime.loadedModel?.modelPath,
            modelPinnedByUser = selectedPin != null,
            runtimePhase = runtime.phase,
            // Scoped leases remain held through parsing/database persistence,
            // even while the native actor itself is idle.
            runtimeBusy = runtime.isBusy || runtime.leaseCount > 0,
            runtimeQueueDepth = runtime.queueDepth,
            runtimeLeaseCount = runtime.leaseCount,
            runtimeActiveOwner = runtime.activeOperation?.owner?.value,
            pendingRuntimeAction = pending,
            testProgress = if (testIsQueued) {
                "Queued behind ${runtime.activeOperation?.owner?.value ?: "another request"}"
            } else {
                _state.value.testProgress
            },
            runtimeError = runtime.lastError
        )
    }

    private fun buildDiagnostics(file: File): String = buildString {
        append("File: ${file.name}\n")
        append("Exists: ${file.exists()}\n")
        if (file.exists()) {
            append("Size: ${file.length() / 1_048_576} MB (${file.length()} bytes)\n")
            append("Readable: ${file.canRead()}\n")
        }
        _state.value.deviceInfo?.let { device ->
            append("RAM: ${"%.1f".format(device.ramGb)} GB (tier: ${device.ramTier})\n")
            append("High-perf CPU: ${device.isHighPerformanceDevice}\n")
            append("Free storage: ${"%.1f".format(device.storage.availableGb)} GB\n")
        }
    }

    private companion object {
        const val TAG = "PocketFinancer"
        const val APP_SETTINGS = ".app_settings"
        const val SELECTED_SLM_ID = "selected_slm_id"
        const val ONBOARDING_COMPLETED = "onboarding_completed"
        const val GRAMMAR_ASSET = "sms_extraction.gbnf"
    }
}

internal fun settingsActiveSlm(
    currentSlm: SlmTier?,
    onboarding: OnboardingSyncManager.OnboardingSyncState
): SlmTier? =
    onboarding.selectedSlm.takeIf {
        onboarding.step == OnboardingStep.COMPLETED &&
            !onboarding.isRunning &&
            onboarding.isModelLoaded
    } ?: currentSlm

internal fun settingsModelUpgradeRecommendation(
    currentSlm: SlmTier?,
    recommendedSlm: SlmTier?,
    onboarding: OnboardingSyncManager.OnboardingSyncState,
    fallbackDownloadState: ModelDownloader.DownloadState,
    startBlockedMessage: String? = modelUpgradeStartBlockedMessage(onboarding)
): ModelUpgradeRecommendation {
    val hasUpgrade = isHigherQualityModel(currentSlm, recommendedSlm)
    val isThisUpgradeRun =
        onboarding.runPurpose == OnboardingSyncManager.RunPurpose.MODEL_UPGRADE &&
            onboarding.selectedSlm == recommendedSlm
    val isUpgradeRunning = isThisUpgradeRun && onboarding.isRunning
    val downloadState = if (isThisUpgradeRun) {
        onboarding.downloadState
    } else {
        fallbackDownloadState
    }
    return ModelUpgradeRecommendation(
        isUpgradeAvailable = hasUpgrade,
        recommendedSlm = recommendedSlm,
        currentSlm = currentSlm,
        downloadState = downloadState,
        isDownloading = isUpgradeRunning && onboarding.isDownloading,
        isRunning = isUpgradeRunning,
        isCancelling = isUpgradeRunning && onboarding.isCancelling,
        canCancel = canCancelModelUpgrade(onboarding),
        isApplying = isUpgradeRunning &&
            !onboarding.isCancelling &&
            onboarding.step == OnboardingStep.SYNCING,
        statusMessage = onboarding.syncMessage.takeIf { isThisUpgradeRun && it.isNotBlank() },
        error = onboarding.modelLoadError.takeIf { isThisUpgradeRun },
        startBlockedMessage = startBlockedMessage
    )
}

internal fun settingsRecommendedSlm(
    device: DeviceCapabilities.DeviceInfo,
    allowDebugEmulatorOverride: Boolean
): SlmTier? = selectModelUpgradeTarget(
    device = device,
    allowDebugEmulatorOverride = allowDebugEmulatorOverride
).tier
