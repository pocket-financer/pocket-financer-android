package com.pocketfinancer.ui.home

import com.pocketfinancer.data.model.SmsSourceIdentity
import com.pocketfinancer.setup.SetupImportState
import com.pocketfinancer.setup.SetupImportStatus
import com.pocketfinancer.sms.SmsReader
import com.pocketfinancer.ui.smsprocessing.SmsPipelinePhase
import com.pocketfinancer.ui.smsprocessing.SmsProcessingTarget
import com.pocketfinancer.ui.smsprocessing.ownsManualProcessingTarget
import com.pocketfinancer.ui.smsprocessing.toSmsPipelineCardUiModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualSmsSyncStateTest {

    @Test
    fun `manual sync start and recent availability enforce shell and setup boundaries`() {
        assertTrue(manualSyncStartAllowed(shellUnlocked = true, generationIsCurrent = true))
        assertFalse(manualSyncStartAllowed(shellUnlocked = false, generationIsCurrent = true))
        assertFalse(manualSyncStartAllowed(shellUnlocked = true, generationIsCurrent = false))

        assertFalse(manualRecentSyncAvailable(SetupImportStatus.PAUSED))
        assertFalse(manualRecentSyncAvailable(SetupImportStatus.FAILED))
        assertFalse(manualRecentSyncAvailable(SetupImportStatus.NOT_STARTED))
        assertTrue(manualRecentSyncAvailable(SetupImportStatus.READY))
        assertTrue(manualRecentSyncAvailable(SetupImportStatus.READY_NO_HISTORY))

        val idle = HomeSyncState()
        assertFalse(manualSmsOperationIsRunning(idle))
        assertTrue(manualSmsOperationIsRunning(state = idle, startPending = true))
        assertTrue(manualSmsOperationIsRunning(idle.copy(status = HomeSyncState.Status.SCANNING)))
        assertTrue(
            manualSmsOperationIsRunning(
                idle.copy(status = HomeSyncState.Status.DONE, activeRunId = "final-handoff")
            )
        )
        assertFalse(manualSmsOperationIsRunning(idle.copy(status = HomeSyncState.Status.DONE)))
    }

    @Test
    fun `manual processing failure error and terminal notification copy stay truthful`() {
        assertEquals(null, manualProcessingFailureError(0))
        assertEquals(
            "1 eligible alert could not be processed.",
            manualProcessingFailureError(1)?.message
        )
        assertEquals(
            "2 eligible alerts could not be processed.",
            manualProcessingFailureError(2)?.message
        )

        val fatalState = HomeSyncState(
            status = HomeSyncState.Status.DONE,
            syncError = "The on-device model is not prepared."
        )
        assertEquals(SmsPipelinePhase.ISSUE, fatalState.toSmsPipelineCardUiModel()?.phase)

        val concurrentWinner = HomeSyncState(
            status = HomeSyncState.Status.DONE,
            queue = listOf(
                SyncSmsItem("new", "AX-BANK", "Rs 100 debited", 1L, status = "synced"),
                SyncSmsItem("existing", "AX-BANK", "Rs 200 debited", 2L, status = "already_saved")
            )
        )
        val card = concurrentWinner.toSmsPipelineCardUiModel()
        assertTrue(card!!.detail.contains("1 saved"))
        assertTrue(card.detail.contains("1 already saved"))

        val needsAttention = manualSyncTerminalNotificationCopy(saved = 2, rejected = 1, failed = 1)
        val complete = manualSyncTerminalNotificationCopy(saved = 2, rejected = 1, failed = 0)
        assertEquals("SMS Sync Needs Attention", needsAttention.title)
        assertFalse(needsAttention.title.contains("Complete"))
        assertFalse(needsAttention.text.contains("successfully"))
        assertTrue(needsAttention.text.contains("needs another attempt"))
        assertEquals("SMS Sync Complete", complete.title)
    }

    @Test
    fun `manual stop and telemetry target fencing never rebind stale or run-level targets`() {
        val candidate = SyncSmsItem("candidate", "AX-BANK", "Rs 100 debited", 1L, status = "syncing")
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
                SmsProcessingTarget.ManualRecent("successor-run", candidate.id)
            )
        )
        assertFalse(
            successor.copy(queue = listOf(candidate.copy(status = "error")))
                .ownsManualProcessingTarget(
                    SmsProcessingTarget.ManualRecent("successor-run", candidate.id)
                )
        )

        assertFalse(
            manualTelemetryTargetIsCurrent(
                SmsProcessingTarget.ManualRecent("stale-run", candidate.id),
                successor
            )
        )
        assertFalse(
            manualTelemetryTargetIsCurrent(
                SmsProcessingTarget.ManualResult(candidate.id),
                successor
            )
        )
        assertTrue(
            manualTelemetryTargetIsCurrent(
                SmsProcessingTarget.ManualResult(candidate.id),
                successor.copy(queue = listOf(candidate.copy(status = "pending")))
            )
        )
        assertTrue(
            manualTelemetryTargetIsCurrent(
                SmsProcessingTarget.ManualRecent("successor-run", candidate.id),
                successor
            )
        )
        assertFalse(
            manualTelemetryTargetIsCurrent(
                SmsProcessingTarget.ManualRecent("successor-run", "missing"),
                successor
            )
        )

        val runLevel = SmsProcessingTarget.ManualRecent(runId = "manual-run", candidateKey = null)
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
    }

    @Test
    fun `privacy scrubbing and cancellation settlement strip telemetry and terminal source evidence`() {
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

        val settled = requireNotNull(
            settledManualSyncCancellation(
                state = source.copy(
                    status = HomeSyncState.Status.CANCELLING,
                    activeRunId = "manual-cancel-run",
                    cancellationRequested = true,
                    currentIndex = 0,
                    currentStageIndex = 1
                ),
                requestedRunId = "manual-cancel-run"
            )
        )
        assertEquals("", settled.decodedTokenDelta)
        assertEquals("", settled.jsonOutput)
        assertFalse(settled.jsonOutputTruncated)
        assertEquals("pending", settled.queue.single().status)

        val item = SyncSmsItem(
            id = "candidate",
            sender = "AX-HDFCBK",
            body = "Rs 500 debited at Example Merchant",
            date = 1L,
            status = "syncing",
            parsedAmount = 500.0,
            parsedMerchant = "Example Merchant"
        )
        val saved = item.withPrivacySafeStatus("synced")
        assertEquals("Saved transaction", saved.sender)
        assertEquals("", saved.body)
        assertEquals(500.0, saved.parsedAmount)
        assertFalse(saved.hasDiagnosticSourceEvidence())

        val alreadySaved = item.withPrivacySafeStatus("already_saved")
        assertEquals("Already in ledger", alreadySaved.sender)
        assertEquals("", alreadySaved.body)
        assertEquals(null, alreadySaved.parsedAmount)
        assertFalse(alreadySaved.hasDiagnosticSourceEvidence())

        listOf("pending", "syncing", "error").forEach { status ->
            val retained = item.withPrivacySafeStatus(status)
            assertEquals(item.sender, retained.sender)
            assertEquals(item.body, retained.body)
            assertTrue(retained.hasDiagnosticSourceEvidence())
        }
        assertTrue(item.copy(sender = "").hasDiagnosticSourceEvidence())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `sanitized and presentation projections suppress token-only churn`() = runTest {
        val candidate = SyncSmsItem(
            id = "candidate-proj",
            sender = "PRIVATE-BANK",
            body = "Account ending 6254 was debited.",
            date = 0L,
            status = "syncing"
        )
        val source = MutableStateFlow(
            HomeSyncState(
                status = HomeSyncState.Status.SYNCING,
                activeRunId = "manual-proj-run",
                queue = listOf(candidate)
            )
        )
        val sanitized = sanitizedManualSyncState(source = source, scope = backgroundScope)
        val presentation = manualSyncPresentationState(source).stateIn(
            scope = backgroundScope,
            started = SharingStarted.Eagerly,
            initialValue = source.value.withoutManualLiveTelemetry()
        )
        runCurrent()

        val initialSanitized = sanitized.value
        val initialPresentation = presentation.value
        assertEquals("", initialSanitized.queue.single().sender)
        assertEquals("PRIVATE-BANK", initialPresentation.queue.single().sender)

        source.value = source.value.copy(
            decodedTokenDelta = "token",
            jsonOutput = "{partial}",
            jsonOutputTruncated = true,
            activeSmsPerformance = "12 tok/s",
            activeModelName = "local-model.gguf"
        )
        runCurrent()
        assertSame(initialSanitized, sanitized.value)
        assertSame(initialPresentation, presentation.value)

        source.value = source.value.copy(
            queue = listOf(candidate.copy(sender = "UPDATED-BANK"))
        )
        runCurrent()
        assertSame(initialSanitized, sanitized.value)
        assertNotSame(initialPresentation, presentation.value)
        assertEquals("UPDATED-BANK", presentation.value.queue.single().sender)
        assertEquals("", presentation.value.decodedTokenDelta)

        source.value = source.value.copy(currentStageIndex = 2)
        runCurrent()
        assertNotSame(initialSanitized, sanitized.value)
        assertSame(initialSanitized.queue, sanitized.value.queue)
        assertEquals(2, presentation.value.currentStageIndex)

        source.value = HomeSyncState(
            status = HomeSyncState.Status.DONE,
            queue = listOf(candidate.withPrivacySafeStatus("synced"))
        )
        runCurrent()
        assertEquals(HomeSyncState.Status.DONE, sanitized.value.status)
        assertEquals("", sanitized.value.queue.single().body)
    }

    @Test
    fun `recent queue merge preserves distinct provider ids conflicts and provider-less deliveries`() {
        val first = SmsSourceIdentity.androidSms(
            providerMessageId = "provider-1",
            sender = "AX-BANK",
            body = "Rs 500 debited",
            sourceTimestamp = 1_000L,
            messageType = 1
        )
        val second = SmsSourceIdentity.androidSms(
            providerMessageId = "provider-2",
            sender = "AX-BANK",
            body = "Rs 500 debited",
            sourceTimestamp = 1_000L,
            messageType = 1
        )
        val broadcast = SmsSourceIdentity.androidSms(
            providerMessageId = null,
            sender = "AX-BANK",
            body = "Rs 500 debited",
            sourceTimestamp = 1_000L,
            messageType = 1
        )

        assertFalse(sameQueuedSmsSource(first, second))
        assertFalse(sameQueuedSmsSource(first, broadcast))
        assertFalse(sameQueuedSmsSource(broadcast, broadcast))
        assertFalse(sameQueuedSmsSource(first, first.copy(connector = "other_connector")))

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
                SmsReader.SmsMessage("AX-BANK", "Rs 500 debited", 1_000L, 1, "provider-1"),
                SmsReader.SmsMessage("AX-BANK", "Rs 500 debited", 1_000L, 1, "provider-2")
            )
        )
        assertEquals(3, merged.size)
        assertEquals(3, merged.map { it.id }.toSet().size)

        // Repeat provider scan preserves existing queued attempt and distinct source timestamps.
        val msg = SmsReader.SmsMessage(
            address = "SYNTH",
            body = "Invented alert",
            date = 6_000L,
            type = 1,
            providerMessageId = "invented-provider",
            sourceTimestamp = 1_000L
        )
        val syncing = mergeRecentScanQueue(emptyList(), listOf(msg)).single().copy(status = "syncing")
        val repeated = mergeRecentScanQueue(listOf(syncing), listOf(msg, msg))
        assertEquals(1, repeated.size)
        assertSame(syncing, repeated.single())
        assertEquals(1_000L, syncing.sourceTimestamp)
        assertEquals(6_000L, syncing.date)

        // Provider identity conflicts keep every variant for authoritative admission.
        val original = SmsReader.SmsMessage("SYNTH", "Invented alert", 1_000L, 1, "conflict-id")
        val queuedOriginal = mergeRecentScanQueue(emptyList(), listOf(original)).single()
        val conflicting = listOf(
            original.copy(body = "Different invented alert"),
            original.copy(address = "OTHER-SYNTH"),
            original.copy(type = 2),
            original.copy(sourceTimestamp = 999L),
            original.copy(date = 1_001L)
        )
        val withConflicts = mergeRecentScanQueue(listOf(queuedOriginal), conflicting)
        assertEquals(6, withConflicts.size)
        assertEquals(6, withConflicts.map { it.id }.toSet().size)
        assertEquals(withConflicts, mergeRecentScanQueue(withConflicts, listOf(original) + conflicting))

        // Provider-less deliveries never collapse or lend state to provider rows.
        val providerLess = SmsReader.SmsMessage("SYNTH", "Invented alert", 1_000L, 1)
        val dupes = mergeRecentScanQueue(emptyList(), listOf(providerLess, providerLess))
        assertEquals(2, dupes.size)
        val failedProviderLess = dupes.first().copy(status = "error")
        val plusProvider = mergeRecentScanQueue(
            listOf(failedProviderLess),
            listOf(providerLess.copy(providerMessageId = "provider-x"))
        )
        assertEquals(2, plusProvider.size)
        assertEquals("pending", plusProvider.last().status)
    }

    @Test
    fun `recent scan coverage tracks exact scanned keys and recovers when errors resolve`() {
        val original = SmsReader.SmsMessage("SYNTH", "Invented alert", 1_000L, 1, "invented-provider")
        val conflicting = original.copy(body = "Different invented alert")
        val providerLess = original.copy(providerMessageId = null)
        val unscanned = mergeRecentScanQueue(
            emptyList(),
            listOf(original.copy(providerMessageId = "unscanned-provider"))
        ).single()

        val current = mergeRecentScanQueue(listOf(unscanned), listOf(original))
        val merged = mergeRecentScanQueueWithCoverage(current, listOf(original, conflicting, providerLess))
        assertEquals(3, merged.scannedCandidateKeys.size)
        assertFalse(unscanned.id in merged.scannedCandidateKeys)

        val scanned = SetupImportState(status = SetupImportStatus.READY).withSuccessfulRecentScan(
            scanWindowDays = 7,
            providerMaxDate = 2_000L,
            completedAt = 3_000L,
            providerMessageCount = 3,
            eligibleCandidateCount = merged.scannedCandidateKeys.size
        )
        val terminal = merged.queue.map { item ->
            item.copy(status = if (item.toSourceMessage() == conflicting) "error" else "synced")
        }

        val progress = scanned.withRecentProcessingProgress(terminal, merged.scannedCandidateKeys, 3_000L)
        assertEquals(3, progress.recentProcessedCount)
        assertEquals(2, progress.recentSavedCount)
        assertEquals(1, progress.recentFailedCount)
        assertFalse(progress.hasIncompleteRecentProcessing)
        assertTrue(progress.recentProcessingNeedsAttention)
        assertEquals(2_000L, recentScanProviderMaxDate(progress, 7, 4_000L))

        val recovered = progress.withRecentProcessingProgress(
            terminal.map { it.copy(status = "already_saved") },
            merged.scannedCandidateKeys,
            3_000L
        )
        assertEquals(3, recovered.recentSavedCount)
        assertFalse(recovered.recentProcessingNeedsAttention)
        assertEquals(4_000L, recentScanProviderMaxDate(recovered, 7, 4_000L))
        assertSame(scanned, scanned.withRecentProcessingProgress(terminal, merged.scannedCandidateKeys, 2_999L))
    }
}
