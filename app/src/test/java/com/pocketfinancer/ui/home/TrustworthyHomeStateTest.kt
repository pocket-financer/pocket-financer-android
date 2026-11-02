package com.pocketfinancer.ui.home

import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.setup.SetupActionableError
import com.pocketfinancer.setup.SetupEmptyReason
import com.pocketfinancer.setup.SetupImportState
import com.pocketfinancer.setup.SetupImportStatus
import com.pocketfinancer.setup.SetupPauseReason
import com.pocketfinancer.setup.reconcileSetupModelAvailability
import com.pocketfinancer.ui.onboarding.OnboardingSyncManager
import com.pocketfinancer.ui.smsprocessing.SmsProcessingTarget
import com.pocketfinancer.ui.smsprocessing.SmsPipelinePhase
import com.pocketfinancer.ui.smsprocessing.ownsManualProcessingTarget
import com.pocketfinancer.ui.smsprocessing.toSmsPipelineCardUiModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustworthyHomeStateTest {

    @Test
    fun `manual sync rejects delayed starts after erase or before shell unlock`() {
        assertTrue(
            manualSyncStartAllowed(
                shellUnlocked = true,
                generationIsCurrent = true
            )
        )
        assertFalse(
            manualSyncStartAllowed(
                shellUnlocked = false,
                generationIsCurrent = true
            )
        )
        assertFalse(
            manualSyncStartAllowed(
                shellUnlocked = true,
                generationIsCurrent = false
            )
        )
    }

    @Test
    fun `manual recent scan cannot replace resumable import state`() {
        assertFalse(
            manualRecentSyncAvailable(SetupImportStatus.PAUSED)
        )
        assertFalse(
            manualRecentSyncAvailable(SetupImportStatus.FAILED)
        )
        assertFalse(
            manualRecentSyncAvailable(SetupImportStatus.NOT_STARTED)
        )
        assertTrue(
            manualRecentSyncAvailable(SetupImportStatus.READY)
        )
        assertTrue(
            manualRecentSyncAvailable(SetupImportStatus.READY_NO_HISTORY)
        )
    }

    @Test
    fun `first run keeps the confirmed onboarding artifact on high end devices`() {
        val largerHardwareRecommendation = SlmTier.ALL_TIERS.last {
            it != SlmTier.DEFAULT_ONBOARDING_SLM
        }

        assertEquals(
            SlmTier.DEFAULT_ONBOARDING_SLM,
            setupTierForPreparation(
                modelPrepared = false,
                resolvedActiveTier = largerHardwareRecommendation
            )
        )
        assertEquals(
            largerHardwareRecommendation,
            setupTierForPreparation(
                modelPrepared = true,
                resolvedActiveTier = largerHardwareRecommendation
            )
        )
    }

    @Test
    fun `legacy prepared flag is revoked when its model artifact is missing`() {
        val legacy = SetupImportState(
            status = SetupImportStatus.READY,
            coverageStartMillis = 100,
            coverageEndMillis = 200,
            coverageWindowDays = 90,
            lastSuccessfulScanMillis = 200,
            modelDownloadConfirmed = true,
            modelPrepared = true
        )

        val reconciled = reconcileSetupModelAvailability(
            state = legacy,
            hasPublishedModel = false
        )
        val card = setupImportCardModel(reconciled)

        assertFalse(reconciled.modelPrepared)
        assertFalse(reconciled.modelDownloadConfirmed)
        assertEquals(90, reconciled.coverageWindowDays)
        assertEquals(SetupCardAction.PREPARE_MODEL, card.primaryAction)
        assertTrue(card.body.contains("starts only after you confirm"))
        assertTrue(card.body.contains("verified SMS coverage is preserved"))
    }

    @Test
    fun `published prepared model keeps explicit setup state`() {
        val prepared = SetupImportState(
            status = SetupImportStatus.READY,
            modelDownloadConfirmed = true,
            modelPrepared = true
        )

        assertEquals(
            prepared,
            reconcileSetupModelAvailability(
                state = prepared,
                hasPublishedModel = true
            )
        )
    }

    @Test
    fun `successful recent scan is durable evidence separate from history`() {
        val historical = SetupImportState(
            status = SetupImportStatus.READY,
            coverageStartMillis = 100,
            coverageEndMillis = 200,
            coverageWindowDays = 90,
            lastSuccessfulScanMillis = 200,
            actionableError = SetupActionableError(
                code = "RECENT_SMS_SCAN_FAILED",
                message = "Old failure",
                actionLabel = "Retry"
            )
        )

        val recent = historical.withSuccessfulRecentScan(
            scanWindowDays = 7,
            providerMaxDate = java.util.concurrent.TimeUnit.DAYS.toMillis(9),
            completedAt = java.util.concurrent.TimeUnit.DAYS.toMillis(10),
            providerMessageCount = 12,
            eligibleCandidateCount = 3
        )

        assertEquals(100L, recent.coverageStartMillis)
        assertEquals(200L, recent.coverageEndMillis)
        assertEquals(90, recent.coverageWindowDays)
        assertEquals(
            java.util.concurrent.TimeUnit.DAYS.toMillis(2),
            recent.recentCoverageStartMillis
        )
        assertEquals(
            java.util.concurrent.TimeUnit.DAYS.toMillis(9),
            recent.recentCoverageEndMillis
        )
        assertEquals(7, recent.recentScanWindowDays)
        assertEquals(12, recent.recentProviderMessageCount)
        assertEquals(3, recent.recentEligibleCandidateCount)
        assertEquals(0, recent.recentProcessedCount)
        assertTrue(recent.hasIncompleteRecentProcessing)
        assertEquals(null, recent.actionableError)
        assertEquals(
            recent.recentCoverageEndMillis,
            recentScanProviderMaxDate(
                state = recent,
                scanWindowDays = 7,
                nowMillis = java.util.concurrent.TimeUnit.DAYS.toMillis(12)
            )
        )

        val incompleteCard = setupImportCardModel(
            recent.copy(modelPrepared = true),
            nowMillis = recent.recentCoverageEndMillis!!
        )
        assertEquals(
            "Some recent alerts still need attention",
            incompleteCard.title
        )

        val card = setupImportCardModel(
            recent.copy(
                modelPrepared = true,
                recentProcessedCount = 3,
                recentSavedCount = 2,
                recentRejectedCount = 1
            ),
            nowMillis = recent.recentCoverageEndMillis!!
        )
        assertEquals(
            java.util.concurrent.TimeUnit.DAYS.toMillis(12),
            recentScanProviderMaxDate(
                state = recent.copy(recentProcessedCount = 3),
                scanWindowDays = 7,
                nowMillis = java.util.concurrent.TimeUnit.DAYS.toMillis(12)
            )
        )
        assertEquals("Pocket Financer is up to date", card.title)
        assertTrue(card.body.contains("recent 7-day scan"))
        assertEquals(
            "Recent scan: 12 messages checked · 3 eligible · 2 saved · 1 rejected",
            card.evidence
        )
    }

    @Test
    fun `manual processing failure produces durable actionable copy`() {
        assertEquals(null, manualProcessingFailureError(0))
        assertEquals(
            "1 eligible alert could not be processed.",
            manualProcessingFailureError(1)?.message
        )
        assertEquals(
            "2 eligible alerts could not be processed.",
            manualProcessingFailureError(2)?.message
        )
    }

    @Test
    fun `active wider scan copy does not claim stale coverage evidence`() {
        val card = setupImportCardModel(
            SetupImportState(
                status = SetupImportStatus.SCANNING,
                coverageWindowDays = 90,
                coverageStartMillis = 1L,
                coverageEndMillis = 2L,
                lastSuccessfulScanMillis = 2L,
                activeScanWindowDays = 36_500,
                providerMessageCount = 0,
                eligibleCandidateCount = 0
            )
        )

        assertEquals("Checking all available SMS history", card.title)
        assertEquals(null, card.evidence)
    }

    @Test
    fun `historical setup card keeps aggregate progress without duplicate stop`() {
        val scanning = setupImportCardModel(
            state = SetupImportState(
                status = SetupImportStatus.SCANNING,
                activeScanWindowDays = 90,
                modelPrepared = true
            )
        )
        val processing = setupImportCardModel(
            state = SetupImportState(
                status = SetupImportStatus.PROCESSING,
                eligibleCandidateCount = 4,
                processedCount = 2,
                savedCount = 1,
                rejectedCount = 1,
                modelPrepared = true
            )
        )

        assertEquals(null, scanning.primaryAction)
        assertEquals(null, scanning.primaryLabel)
        assertEquals(null, processing.primaryAction)
        assertEquals(null, processing.primaryLabel)
        assertTrue(processing.evidence!!.contains("2 of 4 checked"))
    }

    @Test
    fun `stopping historical import disables repeat action and keeps progress visible`() {
        val card = setupImportCardModel(
            state = SetupImportState(
                status = SetupImportStatus.PROCESSING,
                eligibleCandidateCount = 4,
                processedCount = 2,
                savedCount = 1,
                rejectedCount = 1,
                modelPrepared = true
            ),
            isCancelling = true
        )

        assertEquals("Stopping SMS processing", card.title)
        assertEquals(null, card.primaryAction)
        assertEquals(null, card.primaryLabel)
        assertTrue(card.showProgress)
        assertTrue(card.body.contains("Completed saves remain"))
    }

    @Test
    fun `historical completion boundary removes inert stop action`() {
        val card = setupImportCardModel(
            state = SetupImportState(
                status = SetupImportStatus.PROCESSING,
                eligibleCandidateCount = 4,
                processedCount = 4,
                savedCount = 3,
                rejectedCount = 1,
                modelPrepared = true
            ),
            isFinishing = true
        )

        assertEquals("Finishing SMS processing", card.title)
        assertEquals(null, card.primaryAction)
        assertEquals(null, card.primaryLabel)
        assertTrue(card.showProgress)
        assertTrue(card.body.contains("completion boundary"))
    }

    @Test
    fun `terminal setup result stays in finishing card until run settles`() {
        listOf(
            SetupImportState(
                status = SetupImportStatus.READY,
                modelPrepared = true
            ),
            SetupImportState(
                status = SetupImportStatus.READY_NO_HISTORY,
                modelPrepared = true
            ),
            SetupImportState(
                status = SetupImportStatus.FAILED,
                modelPrepared = true,
                actionableError = SetupActionableError(
                    code = "SMS_PROCESSING_FAILED",
                    message = "One alert needs another attempt.",
                    actionLabel = "Retry import"
                )
            )
        ).forEach { state ->
            val card = setupImportCardModel(
                state = state,
                isFinishing = true
            )

            assertEquals("Finishing SMS processing", card.title)
            assertEquals(null, card.primaryAction)
            assertEquals(null, card.primaryLabel)
            assertTrue(card.showProgress)
        }
    }

    @Test
    fun `historical phase flags identify only the final commit as finishing`() {
        val active = OnboardingSyncManager.OnboardingSyncState(
            runId = "history-run",
            isRunning = true,
            runPurpose = OnboardingSyncManager.RunPurpose.INITIAL_SETUP
        )

        assertFalse(
            historicalImportIsFinishing(
                active.copy(isPreparingHistoricalModel = true)
            )
        )
        assertTrue(historicalImportIsRunning(active))
        assertFalse(
            historicalImportIsFinishing(
                active.copy(isCancellationAllowed = true)
            )
        )
        assertFalse(
            historicalImportIsFinishing(
                active.copy(isCancelling = true)
            )
        )
        assertTrue(historicalImportIsFinishing(active))
        assertFalse(historicalImportIsFinishing(active.copy(isRunning = false)))
        assertFalse(historicalImportIsRunning(active.copy(isRunning = false)))
        assertFalse(
            historicalImportIsFinishing(
                active.copy(
                    runPurpose =
                        OnboardingSyncManager.RunPurpose.MODEL_UPGRADE
                )
            )
        )
    }

    @Test
    fun `manual start handoff and service ownership block competing setup work`() {
        val idle = HomeSyncState()

        assertFalse(manualSmsOperationIsRunning(idle))
        assertTrue(
            manualSmsOperationIsRunning(
                state = idle,
                startPending = true
            )
        )
        assertTrue(
            manualSmsOperationIsRunning(
                idle.copy(status = HomeSyncState.Status.SCANNING)
            )
        )
        assertTrue(
            manualSmsOperationIsRunning(
                idle.copy(
                    status = HomeSyncState.Status.DONE,
                    activeRunId = "final-handoff"
                )
            )
        )
        assertFalse(
            manualSmsOperationIsRunning(
                idle.copy(status = HomeSyncState.Status.DONE)
            )
        )
    }

    @Test
    fun `setup card does not replace coverage with duplicate manual activity`() {
        val ready = SetupImportState(
            status = SetupImportStatus.READY,
            modelPrepared = true
        )
        val duringManual = setupImportCardModel(state = ready)
        val duringUpgrade = setupImportCardModel(
            state = ready,
            modelUpgradeRunning = true
        )

        assertEquals("READY · COVERAGE UNKNOWN", duringManual.eyebrow)
        assertEquals(SetupCardAction.SCAN_RECENT, duringManual.primaryAction)
        assertFalse(duringManual.showProgress)
        assertEquals("Model upgrade is in progress", duringUpgrade.title)
        assertEquals(null, duringUpgrade.primaryAction)
        assertTrue(duringUpgrade.showProgress)

        listOf(
            SetupImportStatus.NOT_STARTED,
            SetupImportStatus.PAUSED,
            SetupImportStatus.FAILED
        ).forEach { status ->
            val blocked = setupImportCardModel(
                state = ready.copy(status = status),
                modelUpgradeRunning = true
            )
            assertEquals("Model upgrade is in progress", blocked.title)
            assertEquals(null, blocked.primaryAction)
        }

    }

    @Test
    fun `manual stop accepts only the exact rendered run and candidate`() {
        val candidate = SyncSmsItem(
            id = "candidate",
            sender = "AX-BANK",
            body = "Rs 100 debited",
            date = 1L,
            status = "syncing"
        )
        val successor = HomeSyncState(
            status = HomeSyncState.Status.SYNCING,
            activeRunId = "successor-run",
            queue = listOf(candidate),
            currentIndex = 0
        )

        assertFalse(
            successor.ownsManualProcessingTarget(
                SmsProcessingTarget.ManualRecent("stale-run", candidate.id)
            )
        )
        assertFalse(
            successor.ownsManualProcessingTarget(
                SmsProcessingTarget.ManualRecent("successor-run", "next")
            )
        )
        assertTrue(
            successor.ownsManualProcessingTarget(
                SmsProcessingTarget.ManualRecent(
                    "successor-run",
                    candidate.id
                )
            )
        )
        assertFalse(
            successor.copy(
                queue = listOf(candidate.copy(status = "error"))
            ).ownsManualProcessingTarget(
                SmsProcessingTarget.ManualRecent(
                    "successor-run",
                    candidate.id
                )
            )
        )
    }

    @Test
    fun `manual telemetry never rebinds a stale target to a successor`() {
        val candidate = SyncSmsItem(
            id = "candidate",
            sender = "AX-BANK",
            body = "Rs 100 debited",
            date = 1L,
            status = "syncing"
        )
        val successor = HomeSyncState(
            status = HomeSyncState.Status.SYNCING,
            activeRunId = "successor-run",
            queue = listOf(candidate),
            currentIndex = 0
        )

        assertFalse(
            manualTelemetryTargetIsCurrent(
                target = SmsProcessingTarget.ManualRecent(
                    runId = "stale-run",
                    candidateKey = candidate.id
                ),
                state = successor
            )
        )
        assertFalse(
            manualTelemetryTargetIsCurrent(
                target = SmsProcessingTarget.ManualResult(candidate.id),
                state = successor
            )
        )
        assertTrue(
            manualTelemetryTargetIsCurrent(
                target = SmsProcessingTarget.ManualResult(candidate.id),
                state = successor.copy(
                    queue = listOf(candidate.copy(status = "pending"))
                )
            )
        )
        assertTrue(
            manualTelemetryTargetIsCurrent(
                target = SmsProcessingTarget.ManualRecent(
                    runId = "successor-run",
                    candidateKey = candidate.id
                ),
                state = successor
            )
        )
        assertFalse(
            manualTelemetryTargetIsCurrent(
                target = SmsProcessingTarget.ManualRecent(
                    runId = "successor-run",
                    candidateKey = "missing"
                ),
                state = successor
            )
        )
    }

    @Test
    fun `run-level manual telemetry closes instead of rebinding to a candidate`() {
        val candidate = SyncSmsItem(
            id = "candidate-b",
            sender = "AX-BANK",
            body = "Rs 100 debited",
            date = 1L,
            status = "syncing"
        )
        val runLevel = SmsProcessingTarget.ManualRecent(
            runId = "manual-run",
            candidateKey = null
        )
        val scanning = HomeSyncState(
            status = HomeSyncState.Status.SCANNING,
            activeRunId = "manual-run"
        )

        assertTrue(manualTelemetryTargetIsCurrent(runLevel, scanning))
        assertFalse(
            manualTelemetryTargetIsCurrent(
                runLevel,
                scanning.copy(
                    status = HomeSyncState.Status.SYNCING,
                    queue = listOf(candidate),
                    currentIndex = 0
                )
            )
        )
        assertTrue(
            manualTelemetryTargetIsCurrent(
                SmsProcessingTarget.ManualRecent(
                    runId = "manual-run",
                    candidateKey = candidate.id
                ),
                scanning.copy(
                    status = HomeSyncState.Status.SYNCING,
                    queue = listOf(candidate),
                    currentIndex = 0
                )
            )
        )
    }

    @Test
    fun `user requested pause offers truthful resume copy`() {
        val card = setupImportCardModel(
            SetupImportState(
                status = SetupImportStatus.PAUSED,
                pauseReason = SetupPauseReason.USER_REQUESTED,
                savedCount = 2,
                actionableError = SetupActionableError(
                    code = "HISTORICAL_IMPORT_STOPPED_BY_USER",
                    message =
                        "Completed saves remain on this device. Resume to rediscover unfinished messages.",
                    actionLabel = "Resume SMS import"
                ),
                modelPrepared = true
            )
        )

        assertEquals("SMS processing stopped", card.title)
        assertEquals(SetupCardAction.RESUME, card.primaryAction)
        assertEquals("Resume SMS import", card.primaryLabel)
        assertTrue(card.body.contains("Completed saves remain"))
    }

    @Test
    fun `durable pause does not expose resume until stop cleanup finishes`() {
        val card = setupImportCardModel(
            state = SetupImportState(
                status = SetupImportStatus.PAUSED,
                pauseReason = SetupPauseReason.USER_REQUESTED,
                savedCount = 2,
                modelPrepared = true
            ),
            isCancelling = true
        )

        assertEquals("Finishing SMS stop", card.title)
        assertEquals(null, card.primaryAction)
        assertEquals(null, card.primaryLabel)
        assertTrue(card.showProgress)
    }

    @Test
    fun `restored permission pause keeps finishing feedback until commit drains`() {
        val card = setupImportCardModel(
            state = SetupImportState(
                status = SetupImportStatus.PAUSED,
                pauseReason = SetupPauseReason.INTERRUPTED,
                modelPrepared = true
            ),
            isFinishing = true
        )

        assertEquals("Finishing SMS processing", card.title)
        assertEquals(null, card.primaryAction)
        assertTrue(card.showProgress)
    }

    @Test
    fun `restored permission pause leaves recovery with setup card`() {
        val card = setupImportCardModel(
            state = SetupImportState(
                status = SetupImportStatus.PAUSED,
                pauseReason = SetupPauseReason.INTERRUPTED,
                modelPrepared = true
            )
        )

        assertEquals("Setup stopped before it finished", card.title)
        assertEquals(SetupCardAction.RESUME, card.primaryAction)
    }

    @Test
    fun `model download never exposes SMS stop action`() {
        val card = setupImportCardModel(
            SetupImportState(
                status = SetupImportStatus.DOWNLOADING,
                modelDownloadConfirmed = true
            )
        )

        assertEquals(null, card.primaryAction)
        assertTrue(card.showProgress)
    }

    @Test
    fun `permission recovery keeps active model preparation truthful`() {
        val card = setupImportCardModel(
            state = SetupImportState(
                status = SetupImportStatus.PAUSED,
                pauseReason = SetupPauseReason.INTERRUPTED,
                modelDownloadConfirmed = true
            ),
            isPreparingModel = true
        )

        assertEquals("Preparing the on-device model", card.title)
        assertEquals(null, card.primaryAction)
        assertTrue(card.showProgress)
        assertTrue(card.body.contains("SMS scanning has not started"))
    }

    @Test
    fun `cached artifact preparation does not claim SMS scanning started`() {
        val card = setupImportCardModel(
            state = SetupImportState(
                status = SetupImportStatus.DOWNLOADING,
                modelDownloadConfirmed = true,
                modelPrepared = true
            ),
            isPreparingModel = true
        )

        assertEquals("Preparing the on-device model", card.title)
        assertEquals(null, card.primaryAction)
        assertTrue(card.showProgress)
        assertTrue(card.body.contains("SMS scanning has not started"))
    }

    @Test
    fun `permission loss keeps stopping feedback until active work drains`() {
        val card = setupImportCardModel(
            state = SetupImportState(
                status = SetupImportStatus.PERMISSION_NEEDED,
                modelPrepared = true
            ),
            isCancelling = true
        )

        assertEquals("Stopping SMS processing", card.title)
        assertEquals(null, card.primaryAction)
        assertTrue(card.showProgress)
        assertTrue(card.body.contains("completed saves remain"))
    }

    @Test
    fun `permission loss keeps finishing feedback after commit gate closes`() {
        val card = setupImportCardModel(
            state = SetupImportState(
                status = SetupImportStatus.PERMISSION_NEEDED,
                modelPrepared = true
            ),
            isFinishing = true
        )

        assertEquals("Finishing SMS processing", card.title)
        assertEquals(null, card.primaryAction)
        assertTrue(card.showProgress)
        assertTrue(card.body.contains("persistence boundary"))
    }

    @Test
    fun `permission card keeps permission recovery separate from pipeline stop`() {
        val card = setupImportCardModel(
            state = SetupImportState(
                status = SetupImportStatus.PERMISSION_NEEDED,
                modelPrepared = true
            )
        )

        assertEquals("Restore SMS access", card.title)
        assertEquals(SetupCardAction.RESTORE_PERMISSION, card.primaryAction)
        assertEquals("Restore access", card.primaryLabel)
        assertFalse(card.showProgress)
    }

    @Test
    fun `stale verified range is dated and is not called up to date`() {
        val scanEnded = 10_000L
        val card = setupImportCardModel(
            SetupImportState(
                status = SetupImportStatus.READY,
                coverageWindowDays = 90,
                coverageStartMillis = 1_000L,
                coverageEndMillis = scanEnded,
                lastSuccessfulScanMillis = scanEnded,
                modelPrepared = true
            ),
            nowMillis = scanEnded + java.util.concurrent.TimeUnit.DAYS
                .toMillis(30)
        )

        assertEquals("Pocket Financer is ready", card.title)
        assertTrue(card.body.contains("through"))
        assertFalse(card.body.contains("The last 90 days"))
    }

    @Test
    fun `durable recent scan failure overlays but preserves verified coverage`() {
        val card = setupImportCardModel(
            SetupImportState(
                status = SetupImportStatus.READY,
                coverageWindowDays = 90,
                coverageStartMillis = 1_000L,
                coverageEndMillis = 2_000L,
                lastSuccessfulScanMillis = 2_000L,
                modelPrepared = true,
                actionableError = SetupActionableError(
                    code = "RECENT_SMS_SCAN_FAILED",
                    message = "The SMS provider could not be read.",
                    actionLabel = "Try recent scan again"
                )
            )
        )

        assertEquals("The recent SMS scan did not finish", card.title)
        assertEquals(
            SetupCardAction.RETRY_RECENT_SYNC,
            card.primaryAction
        )
        assertEquals(
            SetupCardActionTarget.RETRY_RECENT_SYNC,
            card.primaryAction!!.target()
        )
        assertTrue(card.body.contains("previously verified coverage"))
    }

    @Test
    fun `zero versus zero spending is neutral`() {
        val comparison = spendingComparison(
            currentAmount = 0.0,
            previousAmount = 0.0,
            previousPeriodLabel = "yesterday"
        )

        assertEquals("same", comparison.direction)
        assertEquals("No spending in either period", comparison.label)
    }

    @Test
    fun `first use and selected period empty states are distinct`() {
        assertEquals(
            "No transactions yet. The setup card shows what remains.",
            selectedPeriodEmptyMessage(
                selectedPeriod = "Day",
                totalTransactionCount = 0,
                setupStatus = SetupImportStatus.NOT_STARTED
            )
        )
        assertEquals(
            "No spending transactions in the selected month period",
            selectedPeriodEmptyMessage(
                selectedPeriod = "Month",
                totalTransactionCount = 5,
                setupStatus = SetupImportStatus.READY
            )
        )
        assertEquals(
            "No transactions yet. Finishing local setup; the setup card shows current progress.",
            selectedPeriodEmptyMessage(
                selectedPeriod = "Day",
                totalTransactionCount = 0,
                setupStatus = SetupImportStatus.READY_NO_HISTORY,
                setupFinishing = true
            )
        )
    }

    @Test
    fun `legacy ready state never claims verified freshness`() {
        val state = SetupImportState(
            status = SetupImportStatus.READY,
            modelPrepared = true
        )

        val model = setupImportCardModel(state)

        assertFalse(state.hasVerifiedCoverage)
        assertEquals("READY · COVERAGE UNKNOWN", model.eyebrow)
        assertEquals(SetupCardAction.SCAN_RECENT, model.primaryAction)
    }

    @Test
    fun `empty inbox filtered inbox permission and scan failure have distinct copy`() {
        val emptyInbox = setupImportCardModel(
            readyNoHistory(SetupEmptyReason.EMPTY_INBOX)
        )
        val filteredInbox = setupImportCardModel(
            readyNoHistory(SetupEmptyReason.FILTERED_OUT)
        )
        val permission = setupImportCardModel(
            SetupImportState(status = SetupImportStatus.PERMISSION_NEEDED)
        )
        val failure = setupImportCardModel(
            SetupImportState(
                status = SetupImportStatus.FAILED,
                modelPrepared = true,
                actionableError = SetupActionableError(
                    code = "SMS_SCAN_FAILED",
                    message = "The SMS provider did not complete the scan.",
                    actionLabel = "Try again"
                )
            )
        )

        assertEquals(
            "No SMS were found in the checked history",
            emptyInbox.title
        )
        assertEquals(
            "Messages were found, but none looked transactional",
            filteredInbox.title
        )
        assertEquals("Restore SMS access", permission.title)
        assertEquals(
            "The SMS provider did not complete the scan.",
            failure.body
        )
    }

    @Test
    fun `ready without history does not promise automatic capture when updates are off`() {
        val card = setupImportCardModel(
            state = readyNoHistory(SetupEmptyReason.EMPTY_INBOX),
            automaticProcessingEnabled = false
        )

        assertTrue(
            card.body.contains(
                "will not process new alerts automatically"
            )
        )
        assertFalse(card.body.contains("will capture"))
        assertTrue(
            selectedPeriodEmptyMessage(
                selectedPeriod = "Day",
                totalTransactionCount = 0,
                setupStatus = SetupImportStatus.READY_NO_HISTORY,
                automaticProcessingEnabled = false
            ).contains("will not process new alerts automatically")
        )
    }

    @Test
    fun `home copy defaults automatic SMS processing to off`() {
        val setupCard = setupImportCardModel(
            readyNoHistory(SetupEmptyReason.EMPTY_INBOX)
        )
        val emptyMessage = selectedPeriodEmptyMessage(
            selectedPeriod = "Day",
            totalTransactionCount = 0,
            setupStatus = SetupImportStatus.READY_NO_HISTORY
        )

        assertFalse(HomeUiState().automaticProcessingEnabled)
        assertTrue(
            setupCard.body.contains(
                "will not process new alerts automatically"
            )
        )
        assertTrue(
            emptyMessage.contains(
                "will not process new alerts automatically"
            )
        )
    }

    @Test
    fun `home aggregate state excludes manual source evidence and telemetry`() {
        val source = HomeSyncState(
            status = HomeSyncState.Status.SYNCING,
            activeRunId = "manual-run",
            queue = listOf(
                SyncSmsItem(
                    id = "candidate-private",
                    sender = "PRIVATE-BANK",
                    body = "Account ending 6254 was debited.",
                    date = 0L,
                    status = "syncing"
                )
            ),
            decodedTokenDelta = "private token delta",
            jsonOutput = "private JSON",
            jsonOutputTruncated = true,
            activeSmsPerformance = "12 tok/s",
            activeModelName = "local-model.gguf"
        )

        val scrubbed = source.withoutManualSmsTelemetry()

        assertEquals("", scrubbed.jsonOutput)
        assertEquals("", scrubbed.decodedTokenDelta)
        assertFalse(scrubbed.jsonOutputTruncated)
        assertEquals(null, scrubbed.activeSmsPerformance)
        assertEquals(null, scrubbed.activeModelName)
        assertEquals("", scrubbed.queue.single().sender)
        assertEquals("", scrubbed.queue.single().body)
        assertEquals(source.queue.single().id, scrubbed.queue.single().id)
        assertEquals(source.queue.single().status, scrubbed.queue.single().status)
        assertEquals(source.activeRunId, scrubbed.activeRunId)
        assertEquals(source.status, scrubbed.status)
    }

    @Test
    fun `manual cancellation settlement clears all transient generated text`() {
        val settled = requireNotNull(
            settledManualSyncCancellation(
                state = HomeSyncState(
                    status = HomeSyncState.Status.CANCELLING,
                    activeRunId = "manual-cancel-run",
                    cancellationRequested = true,
                    queue = listOf(
                        SyncSmsItem(
                            id = "candidate",
                            sender = "PRIVATE-BANK",
                            body = "Private message",
                            date = 1L,
                            status = "syncing"
                        )
                    ),
                    currentIndex = 0,
                    currentStageIndex = 1,
                    decodedTokenDelta = "private-delta",
                    jsonOutput = "private-cumulative-output",
                    jsonOutputTruncated = true
                ),
                requestedRunId = "manual-cancel-run"
            )
        )

        assertEquals("", settled.decodedTokenDelta)
        assertEquals("", settled.jsonOutput)
        assertFalse(settled.jsonOutputTruncated)
        assertEquals("pending", settled.queue.single().status)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `sanitized manual projection suppresses token and source-only churn`() =
        runTest {
            val candidate = SyncSmsItem(
                id = "candidate-eager",
                sender = "PRIVATE-BANK",
                body = "Account ending 6254 was debited.",
                date = 0L,
                status = "syncing"
            )
            val source = MutableStateFlow(
                HomeSyncState(
                    status = HomeSyncState.Status.SYNCING,
                    activeRunId = "manual-eager-run",
                    queue = listOf(candidate)
                )
            )
            val projection = sanitizedManualSyncState(
                source = source,
                scope = backgroundScope
            )
            runCurrent()

            val initialProjection = projection.value
            assertEquals("", projection.value.queue.single().sender)

            source.value = source.value.copy(
                decodedTokenDelta = "token",
                jsonOutput = "{partial}",
                jsonOutputTruncated = true,
                activeSmsPerformance = "12 tok/s",
                activeModelName = "local-model.gguf"
            )
            runCurrent()
            assertSame(initialProjection, projection.value)

            source.value = source.value.copy(
                queue = listOf(candidate.copy(sender = "RENAMED-BANK"))
            )
            runCurrent()
            assertSame(initialProjection, projection.value)

            source.value = source.value.copy(currentStageIndex = 2)
            runCurrent()
            assertNotSame(initialProjection, projection.value)
            assertSame(initialProjection.queue, projection.value.queue)

            source.value = HomeSyncState(
                status = HomeSyncState.Status.DONE,
                queue = listOf(candidate.withPrivacySafeStatus("synced"))
            )
            runCurrent()

            assertEquals(HomeSyncState.Status.DONE, projection.value.status)
            assertNotSame(initialProjection.queue, projection.value.queue)
            assertEquals("", projection.value.queue.single().sender)
            assertEquals("", projection.value.queue.single().body)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `manual presentation retains source but suppresses token-only churn`() =
        runTest {
            val candidate = SyncSmsItem(
                id = "candidate-presentation",
                sender = "PRIVATE-BANK",
                body = "Account ending 6254 was debited.",
                date = 0L,
                status = "syncing"
            )
            val source = MutableStateFlow(
                HomeSyncState(
                    status = HomeSyncState.Status.SYNCING,
                    activeRunId = "manual-presentation-run",
                    queue = listOf(candidate)
                )
            )
            val projection = manualSyncPresentationState(source).stateIn(
                scope = backgroundScope,
                started = SharingStarted.Eagerly,
                initialValue = source.value.withoutManualLiveTelemetry()
            )
            runCurrent()

            val initialProjection = projection.value
            assertEquals("PRIVATE-BANK", initialProjection.queue.single().sender)
            assertEquals(candidate.body, initialProjection.queue.single().body)

            source.value = source.value.copy(
                decodedTokenDelta = "token",
                jsonOutput = "{partial}",
                jsonOutputTruncated = true,
                activeSmsPerformance = "12 tok/s",
                activeModelName = "local-model.gguf"
            )
            runCurrent()
            assertSame(initialProjection, projection.value)

            source.value = source.value.copy(
                queue = listOf(candidate.copy(sender = "UPDATED-BANK"))
            )
            runCurrent()
            assertNotSame(initialProjection, projection.value)
            assertEquals("UPDATED-BANK", projection.value.queue.single().sender)
            assertEquals("", projection.value.decodedTokenDelta)
            assertEquals("", projection.value.jsonOutput)
            assertFalse(projection.value.jsonOutputTruncated)

            val sourceUpdatedProjection = projection.value
            source.value = source.value.copy(currentStageIndex = 2)
            runCurrent()
            assertNotSame(sourceUpdatedProjection, projection.value)
            assertEquals(2, projection.value.currentStageIndex)
        }

    @Test
    fun `fatal manual processing failure is never presented as success`() {
        val state = HomeSyncState(
            status = HomeSyncState.Status.DONE,
            syncError = "The on-device model is not prepared."
        )

        assertEquals(
            SmsPipelinePhase.ISSUE,
            state.toSmsPipelineCardUiModel()?.phase
        )
    }

    @Test
    fun `concurrent winner is not counted as a newly saved transaction`() {
        val state = HomeSyncState(
            status = HomeSyncState.Status.DONE,
            queue = listOf(
                SyncSmsItem(
                    id = "new",
                    sender = "AX-BANK",
                    body = "Rs 100 debited",
                    date = 1L,
                    status = "synced"
                ),
                SyncSmsItem(
                    id = "existing",
                    sender = "AX-BANK",
                    body = "Rs 200 debited",
                    date = 2L,
                    status = "already_saved"
                )
            )
        )

        val card = state.toSmsPipelineCardUiModel()
        assertTrue(card!!.detail.contains("1 saved"))
        assertTrue(card.detail.contains("1 already saved"))
    }

    @Test
    fun `distinct provider ids keep identical evidence as separate sources`() {
        val first = com.pocketfinancer.data.model.SmsSourceIdentity.androidSms(
            providerMessageId = "provider-1",
            sender = "AX-BANK",
            body = "Rs 500 debited",
            sourceTimestamp = 1_000L,
            messageType = 1
        )
        val second = com.pocketfinancer.data.model.SmsSourceIdentity.androidSms(
            providerMessageId = "provider-2",
            sender = "AX-BANK",
            body = "Rs 500 debited",
            sourceTimestamp = 1_000L,
            messageType = 1
        )
        val broadcast = com.pocketfinancer.data.model.SmsSourceIdentity.androidSms(
            providerMessageId = null,
            sender = "AX-BANK",
            body = "Rs 500 debited",
            sourceTimestamp = 1_000L,
            messageType = 1
        )

        assertFalse(sameQueuedSmsSource(first, second))
        assertTrue(sameQueuedSmsSource(first, broadcast))
        assertTrue(sameQueuedSmsSource(second, broadcast))

        val merged = mergeRecentScanQueue(
            currentQueue = listOf(
                SyncSmsItem(
                    id = broadcast.opaqueCandidateKey,
                    sender = "AX-BANK",
                    body = "Rs 500 debited",
                    date = 1_000L,
                    sourceIdentity = broadcast,
                    status = "pending"
                )
            ),
            providerMessages = listOf(
                com.pocketfinancer.sms.SmsReader.SmsMessage(
                    address = "AX-BANK",
                    body = "Rs 500 debited",
                    date = 1_000L,
                    type = 1,
                    providerMessageId = "provider-1"
                ),
                com.pocketfinancer.sms.SmsReader.SmsMessage(
                    address = "AX-BANK",
                    body = "Rs 500 debited",
                    date = 1_000L,
                    type = 1,
                    providerMessageId = "provider-2"
                )
            )
        )
        assertEquals(2, merged.size)
        assertEquals(
            setOf("provider-1", "provider-2"),
            merged.mapNotNull {
                it.sourceIdentity.providerMessageId
            }.toSet()
        )
    }

    @Test
    fun `terminal notification never calls a failed sync completed`() {
        val needsAttention = manualSyncTerminalNotificationCopy(
            saved = 2,
            rejected = 1,
            failed = 1
        )
        val complete = manualSyncTerminalNotificationCopy(
            saved = 2,
            rejected = 1,
            failed = 0
        )

        assertEquals("SMS Sync Needs Attention", needsAttention.title)
        assertFalse(needsAttention.title.contains("Complete"))
        assertFalse(needsAttention.text.contains("successfully"))
        assertTrue(needsAttention.text.contains("needs another attempt"))
        assertEquals("SMS Sync Complete", complete.title)
    }

    @Test
    fun `saved and already-saved queue items discard source evidence`() {
        val source = SyncSmsItem(
            id = "candidate",
            sender = "AX-HDFCBK",
            body = "Rs 500 debited at Example Merchant",
            date = 1L,
            status = "syncing",
            parsedAmount = 500.0,
            parsedMerchant = "Example Merchant"
        )

        val saved = source.withPrivacySafeStatus("synced")
        val alreadySaved = source.withPrivacySafeStatus("already_saved")

        assertEquals("Saved transaction", saved.sender)
        assertEquals("", saved.body)
        assertEquals(500.0, saved.parsedAmount)
        assertEquals("Example Merchant", saved.parsedMerchant)
        assertFalse(saved.hasDiagnosticSourceEvidence())

        assertEquals("Already in ledger", alreadySaved.sender)
        assertEquals("", alreadySaved.body)
        assertEquals(null, alreadySaved.parsedAmount)
        assertEquals(null, alreadySaved.parsedMerchant)
        assertFalse(alreadySaved.hasDiagnosticSourceEvidence())
    }

    @Test
    fun `pending in-flight and retryable queue items retain source evidence`() {
        val source = SyncSmsItem(
            id = "candidate",
            sender = "AX-HDFCBK",
            body = "Rs 500 debited at Example Merchant",
            date = 1L,
            status = "pending"
        )

        listOf("pending", "syncing", "error").forEach { status ->
            val transitioned = source.withPrivacySafeStatus(status)

            assertEquals(source.sender, transitioned.sender)
            assertEquals(source.body, transitioned.body)
            assertEquals(status, transitioned.status)
            assertTrue(transitioned.hasDiagnosticSourceEvidence())
        }

        assertTrue(
            source.copy(sender = "").hasDiagnosticSourceEvidence()
        )
    }

    private fun readyNoHistory(reason: SetupEmptyReason) =
        SetupImportState(
            status = SetupImportStatus.READY_NO_HISTORY,
            coverageStartMillis = 100,
            coverageEndMillis = 200,
            coverageWindowDays = 90,
            lastSuccessfulScanMillis = 200,
            emptyReason = reason,
            modelPrepared = true
        )
}
