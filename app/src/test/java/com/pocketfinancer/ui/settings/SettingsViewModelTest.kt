package com.pocketfinancer.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.util.Log
import com.pocketfinancer.ProvisionalSelectedModelPin
import com.pocketfinancer.SelectedModelResidency
import com.pocketfinancer.SlmAppFlowCoordinator
import com.pocketfinancer.data.repository.TransactionRepository
import com.pocketfinancer.data.repository.ProcessingConfigurationRepository
import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.inference.DownloadOwner
import com.pocketfinancer.inference.ModelDownloader
import com.pocketfinancer.inference.SlmActiveOperation
import com.pocketfinancer.inference.SlmLease
import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.inference.SlmModelStorage
import com.pocketfinancer.inference.SlmOperationKind
import com.pocketfinancer.inference.SlmPendingAction
import com.pocketfinancer.inference.SlmRuntime
import com.pocketfinancer.inference.SlmRuntimeOwner
import com.pocketfinancer.inference.SlmRuntimePhase
import com.pocketfinancer.inference.SlmRuntimeState
import com.pocketfinancer.pipeline.AutomaticProcessingPreferences
import com.pocketfinancer.pipeline.PipelineService
import com.pocketfinancer.pipeline.SlmProcessingPreferences
import com.pocketfinancer.pipeline.SmsWorkController
import com.pocketfinancer.sms.SmsWorkScheduler
import com.pocketfinancer.setup.SetupImportState
import com.pocketfinancer.setup.SetupImportStore
import com.pocketfinancer.ui.home.HomeSyncManager
import com.pocketfinancer.ui.home.HomeSyncState
import com.pocketfinancer.ui.home.ManualOperationReservation
import com.pocketfinancer.ui.home.ManualOperationReservationKind
import com.pocketfinancer.ui.home.SyncService
import com.pocketfinancer.ui.onboarding.OnboardingRunGenerationStore
import com.pocketfinancer.ui.onboarding.OnboardingSyncManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(Log::class)
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        Dispatchers.resetMain()
    }

    @Test
    fun `grammar preference is exposed and updated through settings state`() =
        runTest(dispatcher) {
            assertFalse(SettingsUiState().gbnfGrammarEnabled)
            val fixture = fixture(gbnfInitiallyEnabled = false)
            val viewModel = fixture.createViewModel()

            runCurrent()
            assertFalse(viewModel.state.value.gbnfGrammarEnabled)

            viewModel.setGbnfGrammarEnabled(true)
            runCurrent()

            assertTrue(viewModel.state.value.gbnfGrammarEnabled)
        }

    @Test
    fun `active initial setup blocks an upgrade at click time`() =
        runTest(dispatcher) {
            val fixture = fixture(initialSetupModelPrepared = true)
            val viewModel = fixture.createViewModel()
            runCurrent()

            // Do not let collectors run after this state change: the action
            // must guard against the click-time race using the manager itself.
            fixture.onboardingState.value =
                OnboardingSyncManager.OnboardingSyncState(
                    isRunning = true,
                    runPurpose = OnboardingSyncManager.RunPurpose.INITIAL_SETUP,
                    selectedSlm = SlmTier.DEFAULT_ONBOARDING_SLM
                )

            viewModel.startRecommendedModelUpgrade()

            verify(exactly = 0) {
                fixture.onboardingSync.startModelUpgrade(any(), any())
            }
            assertEquals(
                "Finish the current setup or history import before starting a model upgrade.",
                viewModel.state.value.upgradeRecommendation.startBlockedMessage
            )
        }

    @Test
    fun `failed grammar persistence leaves observed value and surfaces recovery copy`() =
        runTest(dispatcher) {
            val fixture = fixture(gbnfInitiallyEnabled = false)
            every {
                fixture.preferences.setGbnfGrammarEnabled(true)
            } throws IllegalStateException("preference storage unavailable")
            val viewModel = fixture.createViewModel()
            runCurrent()

            viewModel.setGbnfGrammarEnabled(true)

            assertFalse(viewModel.state.value.gbnfGrammarEnabled)
            assertTrue(
                viewModel.state.value.gbnfGrammarError
                    ?.contains("was not changed") == true
            )
        }

    @Test
    fun `permission health reflects revocation and recovery after refresh`() =
        runTest(dispatcher) {
            val fixture = fixture()
            val viewModel = fixture.createViewModel()
            runCurrent()

            assertTrue(viewModel.state.value.smsPermissionGranted)

            fixture.permissionHealth.value =
                fixture.permissionHealth.value.copy(
                    readSmsPermissionGranted = false
                )
            viewModel.refreshPermissionHealth()

            assertFalse(viewModel.state.value.readSmsPermissionGranted)
            assertTrue(viewModel.state.value.receiveSmsPermissionGranted)
            assertFalse(viewModel.state.value.smsPermissionGranted)

            fixture.permissionHealth.value =
                fixture.permissionHealth.value.copy(
                    readSmsPermissionGranted = true
                )
            viewModel.refreshPermissionHealth()

            assertTrue(viewModel.state.value.smsPermissionGranted)
        }

    @Test
    fun `permission revocation dispatches stop before publishing manual cancellation`() =
        runTest(dispatcher) {
            val fixture = fixture()
            mockkObject(SyncService.Companion)
            try {
                every {
                    SyncService.requestStop(
                        fixture.context,
                        "manual-run"
                    )
                } returns true
                val viewModel = fixture.createViewModel()
                runCurrent()
                fixture.homeSyncState.value = HomeSyncState(
                    status = HomeSyncState.Status.SYNCING,
                    activeRunId = "manual-run"
                )
                fixture.permissionHealth.value =
                    fixture.permissionHealth.value.copy(
                        readSmsPermissionGranted = false
                    )

                viewModel.refreshPermissionHealth()

                verifyOrder {
                    SyncService.requestStop(
                        fixture.context,
                        "manual-run"
                    )
                    fixture.homeSyncManager
                        .requestServiceStop("manual-run")
                }
                verify {
                    fixture.setupImportStore.reconcilePermission(false)
                    fixture.onboardingSyncManager
                        .requestHistoricalImportCancellation(fixture.context)
                }
            } finally {
                unmockkObject(SyncService.Companion)
            }
        }

    @Test
    fun `manual scanning and stopping keep settings operations busy`() =
        runTest(dispatcher) {
            val fixture = fixture()
            val viewModel = fixture.createViewModel()
            runCurrent()

            fixture.homeSyncState.value = HomeSyncState(
                status = HomeSyncState.Status.SCANNING,
                activeRunId = "manual-run"
            )
            runCurrent()
            assertTrue(viewModel.state.value.flowBusy)

            fixture.homeSyncState.value = HomeSyncState(
                status = HomeSyncState.Status.CANCELLING,
                activeRunId = "manual-run",
                cancellationRequested = true
            )
            runCurrent()
            assertTrue(viewModel.state.value.flowBusy)

            fixture.homeSyncState.value = HomeSyncState()
            runCurrent()
            assertFalse(viewModel.state.value.flowBusy)
        }

    @Test
    fun `pending manual start blocks model work and upgrade clicks`() =
        runTest(dispatcher) {
            val fixture = fixture(initialSetupModelPrepared = true)
            val viewModel = fixture.createViewModel()
            runCurrent()

            fixture.manualOperationReservation.value =
                ManualOperationReservation(
                    id = "pending-manual-start",
                    kind = ManualOperationReservationKind.SERVICE_START
                )

            viewModel.startRecommendedModelUpgrade()
            runCurrent()

            assertTrue(viewModel.state.value.flowBusy)
            assertEquals(
                "Finish the current model task before starting a model upgrade.",
                viewModel.state.value.upgradeRecommendation.startBlockedMessage
            )
            verify(exactly = 0) {
                fixture.onboardingSyncManager.startModelUpgrade(any(), any())
            }
        }

    @Test
    fun `notification health reflects global and channel disablement`() =
        runTest(dispatcher) {
            val fixture = fixture()
            val viewModel = fixture.createViewModel()
            runCurrent()

            assertTrue(viewModel.state.value.notificationPermissionGranted)

            fixture.permissionHealth.value =
                fixture.permissionHealth.value.copy(
                    appNotificationsEnabled = false
                )
            viewModel.refreshPermissionHealth()

            assertFalse(viewModel.state.value.appNotificationsEnabled)
            assertFalse(viewModel.state.value.notificationPermissionGranted)

            fixture.permissionHealth.value =
                fixture.permissionHealth.value.copy(
                    appNotificationsEnabled = true,
                    progressNotificationChannelEnabled = false
                )
            viewModel.refreshPermissionHealth()

            assertTrue(viewModel.state.value.appNotificationsEnabled)
            assertFalse(
                viewModel.state.value.progressNotificationChannelEnabled
            )
            assertFalse(viewModel.state.value.notificationPermissionGranted)
        }

    @Test
    fun `automatic processing preference is exposed and updated through settings state`() =
        runTest(dispatcher) {
            assertEquals(
                AutomaticProcessingPreferences.DEFAULT_ENABLED,
                SettingsUiState().processIncomingSms
            )
            val fixture = fixture(automaticProcessingInitiallyEnabled = true)
            val viewModel = fixture.createViewModel()

            runCurrent()
            assertTrue(viewModel.state.value.processIncomingSms)

            viewModel.setProcessIncomingSms(false)
            runCurrent()

            assertFalse(viewModel.state.value.processIncomingSms)
            coVerify(exactly = 1) {
                fixture.automaticProcessingPreferences
                    .disableAndCleanupPending(any())
            }
            coVerify(exactly = 1) {
                fixture.smsWorkController.discardPendingAutomaticWork()
            }

            viewModel.setProcessIncomingSms(true)
            runCurrent()

            assertTrue(viewModel.state.value.processIncomingSms)
            coVerify(exactly = 1) {
                fixture.automaticProcessingPreferences
                    .enableAfterCleanupPending(any())
            }
            coVerify(exactly = 2) {
                fixture.smsWorkController.discardPendingAutomaticWork()
            }
        }

    @Test
    fun `failed automatic cleanup keeps intake off until cleanup retry succeeds`() =
        runTest(dispatcher) {
            val fixture = fixture(automaticProcessingInitiallyEnabled = true)
            coEvery {
                fixture.smsWorkController.discardPendingAutomaticWork()
            } throws IllegalStateException("database unavailable")
            val viewModel = fixture.createViewModel()
            runCurrent()

            viewModel.setProcessIncomingSms(false)
            runCurrent()

            assertFalse(viewModel.state.value.processIncomingSms)
            assertTrue(viewModel.state.value.automaticProcessingError!!.contains("cleanup failed"))

            coEvery {
                fixture.smsWorkController.discardPendingAutomaticWork()
            } returns 2
            viewModel.setProcessIncomingSms(true)
            runCurrent()

            assertTrue(viewModel.state.value.processIncomingSms)
            assertNull(viewModel.state.value.automaticProcessingError)
        }

    @Test
    fun `failed OFF persistence truthfully reports automatic processing remains on`() =
        runTest(dispatcher) {
            val fixture = fixture(automaticProcessingInitiallyEnabled = true)
            coEvery {
                fixture.automaticProcessingPreferences
                    .disableAndCleanupPending(any())
            } throws IllegalStateException("preference storage unavailable")
            val viewModel = fixture.createViewModel()
            runCurrent()

            viewModel.setProcessIncomingSms(false)
            runCurrent()

            assertTrue(viewModel.state.value.processIncomingSms)
            assertTrue(
                viewModel.state.value.automaticProcessingError
                    ?.contains("still on") == true
            )
            coVerify(exactly = 0) {
                fixture.smsWorkController.discardPendingAutomaticWork()
            }
        }

    @Test
    fun `failed ON persistence does not falsely blame pending cleanup`() =
        runTest(dispatcher) {
            val fixture = fixture(automaticProcessingInitiallyEnabled = false)
            coEvery {
                fixture.automaticProcessingPreferences
                    .enableAfterCleanupPending(any())
            } coAnswers {
                firstArg<suspend () -> Int>().invoke()
                throw IllegalStateException("preference storage unavailable")
            }
            val viewModel = fixture.createViewModel()
            runCurrent()

            viewModel.setProcessIncomingSms(true)
            runCurrent()

            assertFalse(viewModel.state.value.processIncomingSms)
            assertTrue(
                viewModel.state.value.automaticProcessingError
                    ?.contains("change could not be completed") == true
            )
            assertFalse(
                viewModel.state.value.automaticProcessingError
                    ?.contains("cleanup failed") == true
            )
            coVerify(exactly = 1) {
                fixture.smsWorkController.discardPendingAutomaticWork()
            }
        }

    @Test
    fun `test SMS uses grounded direct pipeline and never reads legacy grammar`() =
        runTest(dispatcher) {
            val fixture = fixture(gbnfInitiallyEnabled = false, modelLoaded = true)
            coEvery {
                fixture.runtime.acquire(SlmRuntimeOwner.SETTINGS_TEST, fixture.spec)
            } returns fixture.lease
            coEvery {
                fixture.pipelineService.processSingle(any(), fixture.lease, any(), "diagnostic")
            } returns PipelineService.ProcessingResult.Skipped(
                PipelineService.SkipReason.RETAINED_FOR_REVIEW
            )
            coEvery { fixture.lease.release() } returns Unit

            val viewModel = fixture.createViewModel()
            runCurrent()
            viewModel.runTestSms()
            // Change the preference after the click but before inference.
            fixture.gbnf.value = true
            advanceTimeBy(1_801)
            runCurrent()

            coVerify(exactly = 1) {
                fixture.pipelineService.processSingle(any(), fixture.lease, any(), "diagnostic")
            }
            coVerify(exactly = 0) { fixture.lease.extract(any()) }
            verify(exactly = 0) {
                fixture.storage.readTextAsset("sms_extraction.gbnf")
            }
        }

    @Test
    fun `successful parser diagnostic never writes into the real ledger`() =
        runTest(dispatcher) {
            val fixture = fixture(gbnfInitiallyEnabled = false, modelLoaded = true)
            coEvery {
                fixture.runtime.acquire(SlmRuntimeOwner.SETTINGS_TEST, fixture.spec)
            } returns fixture.lease
            coEvery {
                fixture.pipelineService.processSingle(any(), fixture.lease, any(), "diagnostic")
            } returns PipelineService.ProcessingResult.Skipped(
                PipelineService.SkipReason.RETAINED_FOR_REVIEW
            )
            coEvery { fixture.lease.release() } returns Unit

            val viewModel = fixture.createViewModel()
            runCurrent()
            viewModel.runTestSms()
            advanceTimeBy(2_000)
            runCurrent()

            assertTrue(
                "Unexpected diagnostic state: ${viewModel.state.value}",
                viewModel.state.value.testParsed?.contains("Decision Trace") == true
            )
            coVerify(exactly = 0) {
                fixture.transactionRepository.insert(any())
            }
            coVerify(exactly = 0) {
                fixture.transactionRepository.insertIfAbsent(any())
            }
        }

    @Test
    fun `runtime state drives settings busy status and safe control enablement`() =
        runTest(dispatcher) {
            val fixture = fixture(modelLoaded = true)
            val viewModel = fixture.createViewModel()
            runCurrent()

            viewModel.downloadSelectedModel()
            assertTrue(viewModel.state.value.modelLoadError!!.contains("Unload"))

            val foregroundFlow = fixture.appFlowCoordinator.tryEnter(
                SlmRuntimeOwner.HOME_SYNC
            )
            runCurrent()
            assertTrue(viewModel.state.value.flowBusy)
            assertFalse(viewModel.state.value.canUnloadModel)
            assertFalse(viewModel.state.value.canResetOnboarding)
            foregroundFlow!!.release()
            runCurrent()

            val resetPause = fixture.appFlowCoordinator.tryPauseAndDrain(
                SlmRuntimeOwner.SETTINGS_MANUAL
            )
            runCurrent()
            assertTrue(viewModel.state.value.flowBusy)
            assertFalse(viewModel.state.value.canUnloadModel)
            resetPause!!.release()
            runCurrent()

            fixture.runtimeState.value = SlmRuntimeState(
                phase = SlmRuntimePhase.READY,
                loadedModel = fixture.spec,
                leaseCount = 1,
                leasesByOwner = mapOf(SlmRuntimeOwner.HOME_SYNC to 1),
                pinnedModels = mapOf(SlmRuntimeOwner.SELECTED_MODEL to fixture.spec)
            )
            runCurrent()

            assertTrue(viewModel.state.value.runtimeBusy)
            assertEquals(1, viewModel.state.value.runtimeLeaseCount)
            assertFalse(viewModel.state.value.canResetOnboarding)
            assertTrue(viewModel.state.value.canUnloadModel)

            fixture.runtimeState.value = SlmRuntimeState(
                phase = SlmRuntimePhase.RUNNING,
                loadedModel = fixture.spec,
                activeOperation = SlmActiveOperation(
                    requestId = 42,
                    kind = SlmOperationKind.EXTRACT,
                    owner = SlmRuntimeOwner.SMS_WORKER,
                    model = fixture.spec
                ),
                queueDepth = 2,
                pinnedModels = mapOf(SlmRuntimeOwner.SELECTED_MODEL to fixture.spec),
                pendingAction = SlmPendingAction.Unload
            )
            runCurrent()

            assertEquals("sms-worker", viewModel.state.value.runtimeActiveOwner)
            assertEquals(2, viewModel.state.value.runtimeQueueDepth)
            assertFalse(viewModel.state.value.canRunTest)
            assertTrue(viewModel.state.value.pendingRuntimeAction!!.contains("Unload"))

            fixture.runtimeState.value = SlmRuntimeState(
                phase = SlmRuntimePhase.ERROR,
                loadedModel = fixture.spec,
                pinnedModels = mapOf(SlmRuntimeOwner.SELECTED_MODEL to fixture.spec),
                lastError = "native load failed"
            )
            runCurrent()
            assertEquals("native load failed", viewModel.state.value.runtimeError)

            fixture.runtimeState.value = SlmRuntimeState(
                phase = SlmRuntimePhase.READY,
                loadedModel = fixture.spec,
                pinnedModels = mapOf(SlmRuntimeOwner.SELECTED_MODEL to fixture.spec)
            )
            runCurrent()

            assertFalse(viewModel.state.value.runtimeBusy)
            assertNull(viewModel.state.value.runtimeError)
            assertTrue(viewModel.state.value.canRunTest)
            assertTrue(viewModel.state.value.canResetOnboarding)
        }

    @Test
    fun `completed download auto-loads after its settings flow lease is released`() =
        runTest(dispatcher) {
            val fixture = fixture()
            val handoff = mockk<ProvisionalSelectedModelPin>(relaxed = true)
            coEvery {
                fixture.selectedModelResidency.beginProvisionalPin(any(), any())
            } returns handoff
            coEvery { handoff.commit(any()) } coAnswers {
                firstArg<() -> Boolean>().invoke()
            }

            val viewModel = fixture.createViewModel()
            runCurrent()
            val tier = viewModel.state.value.selectedSlm!!
            val output = fixture.storage.modelFile(tier.modelFile)
            coEvery {
                fixture.modelDownloader.download(any(), output)
            } coAnswers {
                output.parentFile?.mkdirs()
                output.writeText("controllable fake model")
                fixture.downloaderState.value = ModelDownloader.DownloadState(
                    isComplete = true,
                    progress = 1f,
                    outputPath = output.absolutePath
                )
                Result.success(output.absolutePath)
            }

            viewModel.downloadSelectedModel()
            runCurrent()

            coVerify(exactly = 1) {
                fixture.selectedModelResidency.beginProvisionalPin(any(), any())
            }
            verify(exactly = 1) {
                fixture.setupImportStore.markModelPrepared(tier.id)
            }
            assertTrue(viewModel.state.value.modelLoadError == null)
        }

    @Test
    fun `fresh shell cannot bypass resumable Home model preparation`() =
        runTest(dispatcher) {
            val fixture = fixture(initialSetupModelPrepared = false)
            val viewModel = fixture.createViewModel()
            runCurrent()

            viewModel.downloadSelectedModel()
            runCurrent()

            assertTrue(viewModel.state.value.modelLoadError!!.contains("from Home"))
            coVerify(exactly = 0) {
                fixture.modelDownloader.download(any(), any())
            }
        }

    @Test
    fun `settings refuses to replace an existing final model artifact`() =
        runTest(dispatcher) {
            val fixture = fixture()
            val viewModel = fixture.createViewModel()
            runCurrent()
            val tier = viewModel.state.value.selectedSlm!!
            val output = fixture.storage.modelFile(tier.modelFile)
            output.parentFile?.mkdirs()
            output.writeText("existing model")

            viewModel.downloadSelectedModel()
            runCurrent()

            assertTrue(viewModel.state.value.modelLoadError!!.contains("will not replace"))
            coVerify(exactly = 0) {
                fixture.modelDownloader.download(any(), any())
            }
        }

    @Test
    fun `active downloader artifact and owner remain truthful in settings state`() =
        runTest(dispatcher) {
            val fixture = fixture()
            val viewModel = fixture.createViewModel()
            runCurrent()

            fixture.downloaderState.value = ModelDownloader.DownloadState(
                isDownloading = true,
                progress = 0.4f,
                artifactFileName = SlmTier.DEFAULT_ONBOARDING_SLM.modelFile,
                owner = DownloadOwner.UPGRADE
            )
            runCurrent()

            assertEquals(
                SlmTier.DEFAULT_ONBOARDING_SLM.modelFile,
                viewModel.state.value.downloadState.artifactFileName
            )
            assertEquals(
                DownloadOwner.UPGRADE,
                viewModel.state.value.downloadState.owner
            )
        }

    @Test
    fun `passive completed download state never repins after manual unload`() =
        runTest(dispatcher) {
            val fixture = fixture()
            fixture.createViewModel()
            runCurrent()

            fixture.downloaderState.value = ModelDownloader.DownloadState(
                isComplete = true,
                progress = 1f,
                outputPath = File(fixture.spec.modelPath).absolutePath
            )
            runCurrent()

            coVerify(exactly = 0) {
                fixture.selectedModelResidency.beginProvisionalPin(any(), any())
            }
        }

    @Test
    fun `successful settings download does not auto-load after onboarding reset`() =
        runTest(dispatcher) {
            val fixture = fixture(onboardingCompleted = false)
            val viewModel = fixture.createViewModel()
            runCurrent()
            val tier = viewModel.state.value.selectedSlm!!
            val output = fixture.storage.modelFile(tier.modelFile)
            coEvery {
                fixture.modelDownloader.download(any(), output)
            } coAnswers {
                output.parentFile?.mkdirs()
                output.writeText("controllable fake model")
                fixture.downloaderState.value = ModelDownloader.DownloadState(
                    isComplete = true,
                    progress = 1f,
                    outputPath = output.absolutePath
                )
                Result.success(output.absolutePath)
            }

            viewModel.downloadSelectedModel()
            runCurrent()

            coVerify(exactly = 0) {
                fixture.selectedModelResidency.beginProvisionalPin(any(), any())
            }
        }

    private fun fixture(
        gbnfInitiallyEnabled: Boolean = true,
        automaticProcessingInitiallyEnabled: Boolean =
            AutomaticProcessingPreferences.DEFAULT_ENABLED,
        modelLoaded: Boolean = false,
        onboardingCompleted: Boolean = true,
        initialSetupModelPrepared: Boolean = true
    ): Fixture {
        val context = mockk<Context>()
        val sharedPreferences = mockk<SharedPreferences>()
        val deviceCapabilities = mockk<DeviceCapabilities>()
        val runtime = mockk<SlmRuntime>()
        val storage = mockk<SlmModelStorage>()
        val preferences = mockk<SlmProcessingPreferences>()
        val automaticProcessingPreferences = mockk<AutomaticProcessingPreferences>()
        val modelDownloader = mockk<ModelDownloader>(relaxed = true)
        val downloaderState = MutableStateFlow(ModelDownloader.DownloadState())
        val selectedModelResidency = mockk<SelectedModelResidency>(relaxed = true)
        val gbnf = MutableStateFlow(gbnfInitiallyEnabled)
        val automaticProcessing = MutableStateFlow(automaticProcessingInitiallyEnabled)
        val transactionRepository = mockk<TransactionRepository>(relaxed = true)
        val smsWorkController = mockk<SmsWorkController>(relaxed = true)
        val setupImportStore = mockk<SetupImportStore>(relaxed = true)
        val setupImportState = MutableStateFlow(
            SetupImportState(modelPrepared = initialSetupModelPrepared)
        )
        val permissionHealthReader = mockk<SettingsPermissionHealthReader>()
        val permissionHealth = MutableStateFlow(
            SettingsPermissionHealthSnapshot(
                readSmsPermissionGranted = true,
                receiveSmsPermissionGranted = true,
                notificationPermissionRequired = true,
                notificationRuntimePermissionGranted = true,
                appNotificationsEnabled = true,
                progressNotificationChannelEnabled = true
            )
        )
        val models = File("build/test-settings-models/${System.nanoTime()}")
        val spec = SlmModelSpec(
            modelId = "test-model",
            modelPath = File(models, "test.gguf").absolutePath,
            artifactRevision = "test"
        )
        val runtimeState = MutableStateFlow(
            if (modelLoaded) {
                SlmRuntimeState(
                    phase = SlmRuntimePhase.READY,
                    loadedModel = spec,
                    pinnedModels = mapOf(SlmRuntimeOwner.SELECTED_MODEL to spec)
                )
            } else {
                SlmRuntimeState()
            }
        )
        val lease = mockk<SlmLease>()
        val appFlowCoordinator = SlmAppFlowCoordinator()
        val homeSyncManager = mockk<HomeSyncManager>(relaxed = true)
        val homeSyncState = MutableStateFlow(HomeSyncState())
        val manualOperationReservation =
            MutableStateFlow<ManualOperationReservation?>(null)
        val onboardingSyncManager =
            mockk<OnboardingSyncManager>(relaxed = true)
        val onboardingState = MutableStateFlow(
            OnboardingSyncManager.OnboardingSyncState()
        )
        val pipelineService = mockk<PipelineService>()

        every {
            context.getSharedPreferences(".app_settings", Context.MODE_PRIVATE)
        } returns sharedPreferences
        every { context.applicationInfo } returns ApplicationInfo()
        every { permissionHealthReader.read() } answers {
            permissionHealth.value
        }
        every { sharedPreferences.getString("selected_slm_id", null) } returns null
        every {
            sharedPreferences.getBoolean("onboarding_completed", false)
        } returns onboardingCompleted
        every { automaticProcessingPreferences.enabled } returns automaticProcessing
        every { setupImportStore.state } returns setupImportState
        coEvery {
            automaticProcessingPreferences.disableAndCleanupPending(any())
        } coAnswers {
            automaticProcessing.value = false
            firstArg<suspend () -> Int>().invoke()
        }
        coEvery {
            automaticProcessingPreferences.enableAfterCleanupPending(any())
        } coAnswers {
            val removed = firstArg<suspend () -> Int>().invoke()
            automaticProcessing.value = true
            removed
        }
        every { preferences.gbnfGrammarEnabled } returns gbnf
        every { preferences.setGbnfGrammarEnabled(any()) } answers {
            gbnf.value = firstArg()
        }
        every { deviceCapabilities.assessDevice() } returns testDeviceInfo()
        every { storage.modelDirectory } returns models
        every { storage.modelFile(any()) } answers { File(models, firstArg<String>()) }
        every { runtime.state } returns runtimeState
        every { modelDownloader.state } returns downloaderState
        every { homeSyncManager.syncState } returns homeSyncState
        every {
            homeSyncManager.withSmsOperationStartBoundary<Unit>(any())
        } answers {
            firstArg<() -> Unit>().invoke()
        }
        every {
            homeSyncManager.manualOperationReservation
        } returns manualOperationReservation
        every { onboardingSyncManager.syncState } returns onboardingState
        every { lease.owner } returns SlmRuntimeOwner.SETTINGS_TEST
        every { lease.model } returns spec
        every { lease.isReleased } returns false

        return Fixture(
            context = context,
            deviceCapabilities = deviceCapabilities,
            runtime = runtime,
            runtimeState = runtimeState,
            storage = storage,
            preferences = preferences,
            gbnf = gbnf,
            automaticProcessingPreferences = automaticProcessingPreferences,
            automaticProcessing = automaticProcessing,
            transactionRepository = transactionRepository,
            smsWorkController = smsWorkController,
            permissionHealthReader = permissionHealthReader,
            permissionHealth = permissionHealth,
            spec = spec,
            lease = lease,
            appFlowCoordinator = appFlowCoordinator,
            modelDownloader = modelDownloader,
            downloaderState = downloaderState,
            selectedModelResidency = selectedModelResidency,
            setupImportStore = setupImportStore,
            homeSyncManager = homeSyncManager,
            homeSyncState = homeSyncState,
            manualOperationReservation = manualOperationReservation,
            onboardingSyncManager = onboardingSyncManager,
            onboardingState = onboardingState,
            pipelineService = pipelineService
        )
    }

    private data class Fixture(
        val context: Context,
        val deviceCapabilities: DeviceCapabilities,
        val runtime: SlmRuntime,
        val runtimeState: MutableStateFlow<SlmRuntimeState>,
        val storage: SlmModelStorage,
        val preferences: SlmProcessingPreferences,
        val gbnf: MutableStateFlow<Boolean>,
        val automaticProcessingPreferences: AutomaticProcessingPreferences,
        val automaticProcessing: MutableStateFlow<Boolean>,
        val transactionRepository: TransactionRepository,
        val smsWorkController: SmsWorkController,
        val permissionHealthReader: SettingsPermissionHealthReader,
        val permissionHealth: MutableStateFlow<SettingsPermissionHealthSnapshot>,
        val spec: SlmModelSpec,
        val lease: SlmLease,
        val appFlowCoordinator: SlmAppFlowCoordinator,
        val modelDownloader: ModelDownloader,
        val downloaderState: MutableStateFlow<ModelDownloader.DownloadState>,
        val selectedModelResidency: SelectedModelResidency,
        val setupImportStore: SetupImportStore,
        val homeSyncManager: HomeSyncManager,
        val homeSyncState: MutableStateFlow<HomeSyncState>,
        val manualOperationReservation:
            MutableStateFlow<ManualOperationReservation?>,
        val onboardingSyncManager: OnboardingSyncManager,
        val onboardingState:
            MutableStateFlow<OnboardingSyncManager.OnboardingSyncState>,
        val pipelineService: PipelineService
    ) {
        val homeSync: HomeSyncManager
            get() = homeSyncManager

        val onboardingSync: OnboardingSyncManager
            get() = onboardingSyncManager

        fun createViewModel(): SettingsViewModel {
            val processingConfiguration = mockk<ProcessingConfigurationRepository>(relaxed = true)
            every { processingConfiguration.confirmedPrimaryCurrency() } returns "INR"
            val smsWorkScheduler = mockk<SmsWorkScheduler>(relaxed = true)
            return SettingsViewModel(
                context = context,
                deviceCapabilities = deviceCapabilities,
                slmRuntime = runtime,
                modelStorage = storage,
                modelDownloader = modelDownloader,
                transactionRepository = transactionRepository,
                automaticProcessingPreferences = automaticProcessingPreferences,
                slmProcessingPreferences = preferences,
                smsWorkController = smsWorkController,
                selectedModelResidency = selectedModelResidency,
                appFlowCoordinator = appFlowCoordinator,
                homeSyncManager = homeSyncManager,
                onboardingSyncManager = onboardingSyncManager,
                onboardingRunGenerationStore = mockk<OnboardingRunGenerationStore>(
                    relaxed = true
                ),
                setupImportStore = setupImportStore,
                permissionHealthReader = permissionHealthReader,
                processingConfigurationRepository = processingConfiguration,
                smsWorkScheduler = smsWorkScheduler,
                pipelineService = pipelineService
            )
        }
    }

    private fun testDeviceInfo() = DeviceCapabilities.DeviceInfo(
        ramGb = 4f,
        ramTier = DeviceCapabilities.RamTier.OK,
        gpu = null,
        cpu = DeviceCapabilities.CpuInfo(
            cores = 4,
            features = emptySet(),
            hasI8mm = false,
            hasDotProd = false,
            hasFp16 = false,
            socModel = null
        ),
        storage = DeviceCapabilities.StorageInfo(
            totalBytes = 10_000_000_000L,
            availableBytes = 8_000_000_000L,
            usedBytes = 2_000_000_000L
        ),
        isHighPerformanceDevice = false
    )
}
