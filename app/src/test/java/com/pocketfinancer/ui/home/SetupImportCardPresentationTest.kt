package com.pocketfinancer.ui.home

import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.pipeline.PipelineService
import com.pocketfinancer.setup.FakeSharedPreferences
import com.pocketfinancer.setup.SetupActionableError
import com.pocketfinancer.setup.SetupEmptyReason
import com.pocketfinancer.setup.SetupImportState
import com.pocketfinancer.setup.SetupImportStatus
import com.pocketfinancer.setup.SetupImportStore
import com.pocketfinancer.setup.SetupPauseReason
import com.pocketfinancer.setup.reconcileSetupModelAvailability
import com.pocketfinancer.ui.onboarding.HistoricalImportCounters
import com.pocketfinancer.ui.onboarding.OnboardingSyncManager
import com.pocketfinancer.ui.onboarding.recordSkipped
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupImportCardPresentationTest {

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
    fun `retained and rejected alerts track separate durable counts and review CTA`() {
        val counters = HistoricalImportCounters()
            .recordSkipped(PipelineService.SkipReason.RETAINED_FOR_REVIEW)
            .recordSkipped(PipelineService.SkipReason.NOT_TRANSACTION)
        assertEquals(2, counters.processedCount)
        assertEquals(1, counters.retainedReviewCount)
        assertEquals(1, counters.rejectedCount)

        val fake = FakeSharedPreferences()
        val store = SetupImportStore(fake.preferences, hasSmsPermissions = true)
        store.update {
            it.copy(
                processedCount = counters.processedCount,
                retainedReviewCount = counters.retainedReviewCount,
                rejectedCount = counters.rejectedCount
            )
        }
        val reopened = SetupImportStore(fake.preferences, hasSmsPermissions = true).state.value
        assertEquals(1, reopened.retainedReviewCount)
        assertEquals(1, reopened.rejectedCount)

        listOf(SetupImportStatus.READY, SetupImportStatus.READY_NO_HISTORY).forEach { status ->
            val card = setupImportCardModel(
                SetupImportState(status = status, retainedReviewCount = 3, modelPrepared = true)
            )
            assertEquals("Alerts saved for your review", card.title)
            assertEquals(SetupCardAction.OPEN_REVIEWS, card.primaryAction)
            assertTrue(card.body.contains("3 alerts"))
            assertFalse(card.body.contains("No transactions were added automatically"))
        }
        assertEquals(SetupCardActionTarget.OPEN_REVIEWS, SetupCardAction.OPEN_REVIEWS.target())

        val rejectedOnly = setupImportCardModel(
            SetupImportState(
                status = SetupImportStatus.READY_NO_HISTORY,
                emptyReason = SetupEmptyReason.CANDIDATES_REJECTED,
                rejectedCount = 14,
                modelPrepared = true
            )
        )
        assertEquals("History checked; no transactions added", rejectedOnly.title)
        assertEquals(SetupCardAction.OPEN_REVIEWS, rejectedOnly.primaryAction)
        assertFalse(rejectedOnly.body.contains("14 alerts"))
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
            providerMaxDate = TimeUnit.DAYS.toMillis(9),
            completedAt = TimeUnit.DAYS.toMillis(10),
            providerMessageCount = 12,
            eligibleCandidateCount = 3
        )

        assertEquals(100L, recent.coverageStartMillis)
        assertEquals(200L, recent.coverageEndMillis)
        assertEquals(90, recent.coverageWindowDays)
        assertEquals(TimeUnit.DAYS.toMillis(2), recent.recentCoverageStartMillis)
        assertEquals(TimeUnit.DAYS.toMillis(9), recent.recentCoverageEndMillis)
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
                nowMillis = TimeUnit.DAYS.toMillis(12)
            )
        )

        val incompleteCard = setupImportCardModel(
            recent.copy(modelPrepared = true),
            nowMillis = recent.recentCoverageEndMillis!!
        )
        assertEquals("Some recent alerts still need attention", incompleteCard.title)

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
            TimeUnit.DAYS.toMillis(12),
            recentScanProviderMaxDate(
                state = recent.copy(recentProcessedCount = 3),
                scanWindowDays = 7,
                nowMillis = TimeUnit.DAYS.toMillis(12)
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
    fun `stopping and finishing boundaries disable repeat actions across terminal states`() {
        val stopping = setupImportCardModel(
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
        assertEquals("Stopping SMS processing", stopping.title)
        assertEquals(null, stopping.primaryAction)
        assertTrue(stopping.showProgress)
        assertTrue(stopping.body.contains("Completed saves remain"))

        val finishingProcessing = setupImportCardModel(
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
        assertEquals("Finishing SMS processing", finishingProcessing.title)
        assertEquals(null, finishingProcessing.primaryAction)
        assertTrue(finishingProcessing.showProgress)
        assertTrue(finishingProcessing.body.contains("completion boundary"))

        listOf(
            SetupImportState(status = SetupImportStatus.READY, modelPrepared = true),
            SetupImportState(status = SetupImportStatus.READY_NO_HISTORY, modelPrepared = true),
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
            val card = setupImportCardModel(state = state, isFinishing = true)
            assertEquals("Finishing SMS processing", card.title)
            assertEquals(null, card.primaryAction)
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

        assertFalse(historicalImportIsFinishing(active.copy(isPreparingHistoricalModel = true)))
        assertTrue(historicalImportIsRunning(active))
        assertFalse(historicalImportIsFinishing(active.copy(isCancellationAllowed = true)))
        assertFalse(historicalImportIsFinishing(active.copy(isCancelling = true)))
        assertTrue(historicalImportIsFinishing(active))
        assertFalse(historicalImportIsFinishing(active.copy(isRunning = false)))
        assertFalse(historicalImportIsRunning(active.copy(isRunning = false)))
        assertFalse(
            historicalImportIsFinishing(
                active.copy(runPurpose = OnboardingSyncManager.RunPurpose.MODEL_UPGRADE)
            )
        )
    }

    @Test
    fun `model upgrade running overrides setup card actions while idle ready preserves coverage`() {
        val ready = SetupImportState(status = SetupImportStatus.READY, modelPrepared = true)
        val idle = setupImportCardModel(state = ready)
        assertEquals("READY · COVERAGE UNKNOWN", idle.eyebrow)
        assertEquals(SetupCardAction.SCAN_RECENT, idle.primaryAction)
        assertFalse(idle.showProgress)

        listOf(
            SetupImportStatus.READY,
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
            assertTrue(blocked.showProgress)
        }
    }

    @Test
    fun `pause permission and model preparation states render truthful copy and actions`() {
        val userPaused = setupImportCardModel(
            SetupImportState(
                status = SetupImportStatus.PAUSED,
                pauseReason = SetupPauseReason.USER_REQUESTED,
                savedCount = 2,
                actionableError = SetupActionableError(
                    code = "HISTORICAL_IMPORT_STOPPED_BY_USER",
                    message = "Completed saves remain on this device. Resume to rediscover unfinished messages.",
                    actionLabel = "Resume SMS import"
                ),
                modelPrepared = true
            )
        )
        assertEquals("SMS processing stopped", userPaused.title)
        assertEquals(SetupCardAction.RESUME, userPaused.primaryAction)
        assertEquals("Resume SMS import", userPaused.primaryLabel)
        assertTrue(userPaused.body.contains("Completed saves remain"))

        val pausingDrain = setupImportCardModel(
            state = SetupImportState(
                status = SetupImportStatus.PAUSED,
                pauseReason = SetupPauseReason.USER_REQUESTED,
                savedCount = 2,
                modelPrepared = true
            ),
            isCancelling = true
        )
        assertEquals("Finishing SMS stop", pausingDrain.title)
        assertEquals(null, pausingDrain.primaryAction)
        assertTrue(pausingDrain.showProgress)

        val interruptedFinishing = setupImportCardModel(
            state = SetupImportState(
                status = SetupImportStatus.PAUSED,
                pauseReason = SetupPauseReason.INTERRUPTED,
                modelPrepared = true
            ),
            isFinishing = true
        )
        assertEquals("Finishing SMS processing", interruptedFinishing.title)
        assertEquals(null, interruptedFinishing.primaryAction)

        val interrupted = setupImportCardModel(
            state = SetupImportState(
                status = SetupImportStatus.PAUSED,
                pauseReason = SetupPauseReason.INTERRUPTED,
                modelPrepared = true
            )
        )
        assertEquals("Setup stopped before it finished", interrupted.title)
        assertEquals(SetupCardAction.RESUME, interrupted.primaryAction)

        val downloading = setupImportCardModel(
            SetupImportState(status = SetupImportStatus.DOWNLOADING, modelDownloadConfirmed = true)
        )
        assertEquals(null, downloading.primaryAction)
        assertTrue(downloading.showProgress)

        listOf(
            SetupImportState(
                status = SetupImportStatus.PAUSED,
                pauseReason = SetupPauseReason.INTERRUPTED,
                modelDownloadConfirmed = true
            ),
            SetupImportState(
                status = SetupImportStatus.DOWNLOADING,
                modelDownloadConfirmed = true,
                modelPrepared = true
            )
        ).forEach { state ->
            val preparing = setupImportCardModel(state = state, isPreparingModel = true)
            assertEquals("Preparing the on-device model", preparing.title)
            assertEquals(null, preparing.primaryAction)
            assertTrue(preparing.showProgress)
            assertTrue(preparing.body.contains("SMS scanning has not started"))
        }

        val permissionStopping = setupImportCardModel(
            state = SetupImportState(status = SetupImportStatus.PERMISSION_NEEDED, modelPrepared = true),
            isCancelling = true
        )
        assertEquals("Stopping SMS processing", permissionStopping.title)
        assertEquals(null, permissionStopping.primaryAction)
        assertTrue(permissionStopping.body.contains("completed saves remain"))

        val permissionFinishing = setupImportCardModel(
            state = SetupImportState(status = SetupImportStatus.PERMISSION_NEEDED, modelPrepared = true),
            isFinishing = true
        )
        assertEquals("Finishing SMS processing", permissionFinishing.title)
        assertEquals(null, permissionFinishing.primaryAction)
        assertTrue(permissionFinishing.body.contains("persistence boundary"))

        val permissionIdle = setupImportCardModel(
            state = SetupImportState(status = SetupImportStatus.PERMISSION_NEEDED, modelPrepared = true)
        )
        assertEquals("Restore SMS access", permissionIdle.title)
        assertEquals(SetupCardAction.RESTORE_PERMISSION, permissionIdle.primaryAction)
        assertEquals("Restore access", permissionIdle.primaryLabel)
        assertFalse(permissionIdle.showProgress)
    }

    @Test
    fun `stale verified range and recent scan failure overlay preserve historical coverage`() {
        val scanEnded = 10_000L
        val stale = setupImportCardModel(
            SetupImportState(
                status = SetupImportStatus.READY,
                coverageWindowDays = 90,
                coverageStartMillis = 1_000L,
                coverageEndMillis = scanEnded,
                lastSuccessfulScanMillis = scanEnded,
                modelPrepared = true
            ),
            nowMillis = scanEnded + TimeUnit.DAYS.toMillis(30)
        )
        assertEquals("Pocket Financer is ready", stale.title)
        assertTrue(stale.body.contains("through"))
        assertFalse(stale.body.contains("The last 90 days"))

        val failedRecent = setupImportCardModel(
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
        assertEquals("The recent SMS scan did not finish", failedRecent.title)
        assertEquals(SetupCardAction.RETRY_RECENT_SYNC, failedRecent.primaryAction)
        assertEquals(SetupCardActionTarget.RETRY_RECENT_SYNC, failedRecent.primaryAction!!.target())
        assertTrue(failedRecent.body.contains("previously verified coverage"))

        val legacyReady = SetupImportState(status = SetupImportStatus.READY, modelPrepared = true)
        assertFalse(legacyReady.hasVerifiedCoverage)
        val legacyCard = setupImportCardModel(legacyReady)
        assertEquals("READY · COVERAGE UNKNOWN", legacyCard.eyebrow)
        assertEquals(SetupCardAction.SCAN_RECENT, legacyCard.primaryAction)
    }

    @Test
    fun `spending comparison empty messages and automatic off defaults stay distinct`() {
        val comparison = spendingComparison(
            currentAmount = 0.0,
            previousAmount = 0.0,
            previousPeriodLabel = "yesterday"
        )
        assertEquals("same", comparison.direction)
        assertEquals("No spending in either period", comparison.label)

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

        val emptyInbox = setupImportCardModel(readyNoHistory(SetupEmptyReason.EMPTY_INBOX))
        val filteredInbox = setupImportCardModel(readyNoHistory(SetupEmptyReason.FILTERED_OUT))
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
        assertEquals("No SMS were found in the checked history", emptyInbox.title)
        assertEquals("Messages were found, but none looked transactional", filteredInbox.title)
        assertEquals("The SMS provider did not complete the scan.", failure.body)

        assertFalse(HomeUiState().automaticProcessingEnabled)
        assertTrue(emptyInbox.body.contains("will not process new alerts automatically"))
        assertFalse(emptyInbox.body.contains("will capture"))
        assertTrue(
            selectedPeriodEmptyMessage(
                selectedPeriod = "Day",
                totalTransactionCount = 0,
                setupStatus = SetupImportStatus.READY_NO_HISTORY,
                automaticProcessingEnabled = false
            ).contains("will not process new alerts automatically")
        )
    }

    private fun readyNoHistory(reason: SetupEmptyReason) = SetupImportState(
        status = SetupImportStatus.READY_NO_HISTORY,
        coverageStartMillis = 100,
        coverageEndMillis = 200,
        coverageWindowDays = 90,
        lastSuccessfulScanMillis = 200,
        emptyReason = reason,
        modelPrepared = true
    )
}
