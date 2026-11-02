package com.pocketfinancer.ui.smsprocessing

import com.pocketfinancer.pipeline.AutomaticSmsFilterResult
import com.pocketfinancer.pipeline.AutomaticSmsProcessingActivity
import com.pocketfinancer.pipeline.AutomaticSmsProcessingOwner
import com.pocketfinancer.pipeline.AutomaticSmsProcessingStage
import com.pocketfinancer.pipeline.AutomaticSmsSlmCacheTelemetry
import com.pocketfinancer.pipeline.AutomaticSmsSlmPerformance
import com.pocketfinancer.ui.home.HomeSyncState
import com.pocketfinancer.ui.home.SyncSmsItem
import com.pocketfinancer.ui.onboarding.HistoricalSmsProcessingActivity
import com.pocketfinancer.ui.onboarding.HistoricalSmsProcessingStage
import com.pocketfinancer.ui.onboarding.HistoricalSlmCacheTelemetry
import com.pocketfinancer.ui.onboarding.HistoricalSlmPerformance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsProcessingPresentationTest {

    @Test
    fun `manual scan start has truthful copy and no actionable evidence`() {
        val model = HomeSyncState(
            status = HomeSyncState.Status.SCANNING
        ).toSmsPipelineCardUiModel(startPending = true)

        requireNotNull(model)
        assertEquals("Scanning recent messages", model.title)
        assertEquals("Checking recent SMS on this device", model.detail)
        assertNull(model.target)
        assertEquals(SmsSourcePreview.Hidden, model.source)
        assertEquals(SmsInspectUiState.HIDDEN, model.inspectState)
        assertEquals(SmsStopUiState.HIDDEN, model.stopState)
        assertEquals(SmsPipelinePhase.SCANNING, model.phase)
    }

    @Test
    fun `manual scanning card carries exact run with no candidate source`() {
        val model = HomeSyncState(
            status = HomeSyncState.Status.SCANNING,
            activeRunId = "manual-run-41"
        ).toSmsPipelineCardUiModel()

        requireNotNull(model)
        assertEquals(
            SmsProcessingTarget.ManualRecent("manual-run-41", null),
            model.target
        )
        assertEquals(SmsSourcePreview.Hidden, model.source)
        assertEquals(SmsStopUiState.AVAILABLE, model.stopState)
    }

    @Test
    fun `manual processing card identifies exact run and candidate`() {
        val sms = sms(id = "candidate-7", sender = "VK-AMEXIN", status = "syncing")
        val model = HomeSyncState(
            status = HomeSyncState.Status.SYNCING,
            activeRunId = "manual-run-42",
            queue = listOf(sms),
            currentIndex = 0,
            currentStageIndex = 2
        ).toSmsPipelineCardUiModel()

        requireNotNull(model)
        assertEquals(
            SmsProcessingTarget.ManualRecent("manual-run-42", "candidate-7"),
            model.target
        )
        assertEquals(
            SmsSourcePreview.Message("VK-AMEXIN", sms.body),
            model.source
        )
        assertEquals(SmsPipelinePhase.PROCESSING, model.phase)
        assertEquals(SmsStopUiState.AVAILABLE, model.stopState)
        assertEquals("Selecting grounded candidates", model.stepValue)
    }

    @Test
    fun `automatic preparation card is exact inspect only and keeps unknown sender body`() {
        val activity = automaticActivity()
        val model = activity.toSmsPipelineCardUiModel()

        assertEquals(
            SmsProcessingTarget.Automatic(
                claimToken = "automatic-claim",
                candidateKey = "automatic-candidate"
            ),
            model.target
        )
        assertEquals("Processing new SMS", model.title)
        assertEquals("From Unknown sender", model.detail)
        assertEquals(
            SmsSourcePreview.Message("", activity.body),
            model.source
        )
        assertEquals("Preparing secure processing", model.stepValue)
        assertEquals(SmsInspectUiState.AVAILABLE, model.inspectState)
        assertEquals(SmsStopUiState.HIDDEN, model.stopState)
    }

    @Test
    fun `automatic terminal and retry cards report outcome without stop`() {
        val cases = listOf(
            Triple(
                AutomaticSmsProcessingStage.RETRYING,
                "Automatic SMS processing will retry",
                "Retry scheduled"
            ),
            Triple(
                AutomaticSmsProcessingStage.FILTERED_OUT,
                "Message checked",
                "No transaction was saved"
            ),
            Triple(
                AutomaticSmsProcessingStage.SAVED,
                "Transaction saved",
                "Saved to encrypted ledger"
            ),
            Triple(
                AutomaticSmsProcessingStage.ALREADY_SAVED,
                "Transaction already saved",
                "Verified in encrypted ledger"
            ),
            Triple(
                AutomaticSmsProcessingStage.ERROR,
                "Automatic SMS processing could not finish",
                "No transaction was saved"
            )
        )

        cases.forEach { (stage, title, step) ->
            val model = automaticActivity().copy(
                stage = stage,
                detail = "Exact stage detail"
            ).toSmsPipelineCardUiModel()
            assertEquals(title, model.title)
            assertEquals(step, model.stepValue)
            assertEquals("Exact stage detail", model.detail)
            assertEquals(SmsStopUiState.HIDDEN, model.stopState)
        }
    }

    @Test
    fun `automatic telemetry keeps exact runtime facts and never gains stop`() {
        val activity = automaticActivity().copy(
            stage = AutomaticSmsProcessingStage.GENERATING,
            filterResult = AutomaticSmsFilterResult.PASSED,
            modelName = "local-model.gguf",
            grammarEnabled = false,
            answerTokenBudget = 256,
            decodedTokenDelta = "}",
            jsonOutput = "{partial}",
            performance = AutomaticSmsSlmPerformance(12, 2_000, 40),
            cache = AutomaticSmsSlmCacheTelemetry(true, false, 300)
        )
        val target = SmsProcessingTarget.Automatic(
            claimToken = activity.owner.claimToken,
            candidateKey = activity.owner.candidateKey
        )
        val model = SmsTelemetryPresenter.automatic(
            activity = activity,
            filterLogs = listOf("eligible"),
            slmPrompt = "prompt",
            parseJson = { error("live partial JSON must not be parsed") },
            target = target
        )

        assertEquals(target, model.target)
        assertEquals(SmsStopUiState.HIDDEN, model.stopState)
        assertEquals(SmsTelemetryStatus.ACTIVE, model.status)
        assertEquals(SmsTelemetryFilterOutcome.PASSED, model.filterOutcome)
        assertEquals(false, model.runtimeFacts?.grammarEnabled)
        assertEquals(true, model.runtimeFacts?.cacheAttempted)
        assertEquals(false, model.runtimeFacts?.cacheHit)
        assertEquals("40 tokens • 20.00 tok/s", model.performanceText)
        assertEquals("}", model.decodedTokenDelta)
        assertEquals("{partial}", model.cumulativeStructuredOutput)
        assertEquals("Waiting for complete JSON...", model.parsedOutput)
        val source = (model.content as SmsTelemetryContent.Candidate).source
        assertEquals(
            SmsTelemetrySource.Available("", activity.body),
            source
        )
    }

    @Test
    fun `automatic filtered telemetry distinguishes prefilter from inference rejection`() {
        val target = SmsProcessingTarget.Automatic(
            claimToken = "automatic-claim",
            candidateKey = "automatic-candidate"
        )
        val deterministicReject = SmsTelemetryPresenter.automatic(
            activity = automaticActivity().copy(
                stage = AutomaticSmsProcessingStage.FILTERED_OUT,
                filterResult = AutomaticSmsFilterResult.REJECTED
            ),
            filterLogs = listOf("not eligible"),
            slmPrompt = "No prompt was supplied.",
            parseJson = { it },
            target = target
        )
        val inferenceReject = SmsTelemetryPresenter.automatic(
            activity = automaticActivity().copy(
                stage = AutomaticSmsProcessingStage.FILTERED_OUT,
                filterResult = AutomaticSmsFilterResult.PASSED,
                grammarEnabled = false,
                modelName = "local-model.gguf"
            ),
            filterLogs = listOf("eligible"),
            slmPrompt = "prompt",
            parseJson = { it },
            target = target
        )

        assertFalse(deterministicReject.wasFilteredAfterAutomaticInference())
        assertTrue(inferenceReject.wasFilteredAfterAutomaticInference())
    }

    @Test
    fun `stale automatic claim cannot rebind to successor telemetry`() {
        val successor = automaticActivity().copy(
            decodedTokenDelta = "successor private delta",
            jsonOutput = "successor private json"
        )
        val staleTarget = SmsProcessingTarget.Automatic(
            claimToken = "stale-claim",
            candidateKey = successor.owner.candidateKey
        )
        val model = SmsTelemetryPresenter.automatic(
            activity = successor,
            filterLogs = listOf("private filter logs"),
            slmPrompt = "private prompt",
            parseJson = { error("stale target must not parse successor output") },
            target = staleTarget
        )

        assertEquals(staleTarget, model.target)
        assertTrue(model.content is SmsTelemetryContent.Gap)
        assertEquals("", model.jsonOutput)
        assertEquals("", model.decodedTokenDelta)
        assertEquals("", model.slmPrompt)
        assertTrue(model.filterLogs.isEmpty())
        assertTrue(model.cacheLogs.isEmpty())
        assertEquals(SmsStopUiState.HIDDEN, model.stopState)

        val differentCandidate = SmsTelemetryPresenter.automatic(
            activity = successor.copy(
                owner = successor.owner.copy(
                    candidateKey = "successor-candidate"
                )
            ),
            filterLogs = listOf("successor private filter logs"),
            slmPrompt = "successor private prompt",
            parseJson = {
                error("stale target must not parse a different candidate")
            },
            target = staleTarget
        )
        assertTrue(differentCandidate.content is SmsTelemetryContent.Gap)
        assertEquals("", differentCandidate.jsonOutput)
    }

    @Test
    fun `blank sender retains real body as unknown-sender evidence`() {
        val sms = SyncSmsItem(
            id = "candidate-unknown-sender",
            sender = "",
            body = "Account ending 6254 was debited.",
            date = 0L,
            status = "syncing"
        )
        val state = HomeSyncState(
            status = HomeSyncState.Status.SYNCING,
            activeRunId = "manual-run-unknown-sender",
            queue = listOf(sms),
            currentIndex = 0
        )
        val card = state.toSmsPipelineCardUiModel()

        requireNotNull(card)
        assertEquals("From Unknown sender", card.detail)
        assertEquals(SmsSourcePreview.Message("", sms.body), card.source)

        val telemetry = SmsTelemetryPresenter.manual(
            state = state,
            sms = sms,
            filterLogs = listOf("checked"),
            cacheLogs = emptyList(),
            slmPrompt = "prompt",
            parseJson = { "" },
            target = SmsProcessingTarget.ManualRecent(
                "manual-run-unknown-sender",
                "candidate-unknown-sender"
            )
        )
        val source =
            (telemetry.content as SmsTelemetryContent.Candidate).source
        assertEquals(SmsTelemetrySource.Available("", sms.body), source)
    }

    @Test
    fun `manual processing card hides source omitted from aggregate state`() {
        val sms = SyncSmsItem(
            id = "candidate-sanitized",
            sender = "",
            body = "",
            date = 0L,
            status = "syncing"
        )
        val model = HomeSyncState(
            status = HomeSyncState.Status.SYNCING,
            activeRunId = "manual-run-sanitized",
            queue = listOf(sms),
            currentIndex = 0
        ).toSmsPipelineCardUiModel()

        requireNotNull(model)
        assertEquals(SmsSourcePreview.Hidden, model.source)
        assertEquals(
            "Processing the current message on this device",
            model.detail
        )
    }

    @Test
    fun `manual handoff after a settled item clears prior source`() {
        val prior = sms(id = "prior", sender = "PRIVATE-BANK", status = "error")
        val model = HomeSyncState(
            status = HomeSyncState.Status.SYNCING,
            activeRunId = "manual-run-gap",
            queue = listOf(
                prior,
                sms(id = "next", status = "pending")
            ),
            currentIndex = 0,
            currentStageIndex = 2,
            jsonOutput = "prior private output"
        ).toSmsPipelineCardUiModel()

        requireNotNull(model)
        assertEquals(
            SmsProcessingTarget.ManualRecent("manual-run-gap", null),
            model.target
        )
        assertEquals(SmsSourcePreview.Hidden, model.source)
        assertEquals("Preparing next message", model.title)
    }

    @Test
    fun `manual cancellation preserves inspect and disables repeated stop`() {
        val sms = sms(id = "candidate-8", status = "syncing")
        val model = HomeSyncState(
            status = HomeSyncState.Status.CANCELLING,
            activeRunId = "manual-run-43",
            cancellationRequested = true,
            queue = listOf(sms),
            currentIndex = 0,
            currentStageIndex = 3
        ).toSmsPipelineCardUiModel()

        requireNotNull(model)
        assertEquals(SmsPipelinePhase.STOPPING, model.phase)
        assertEquals(SmsInspectUiState.AVAILABLE, model.inspectState)
        assertEquals(SmsStopUiState.STOPPING, model.stopState)
        assertEquals("Finishing current save", model.stepValue)
    }

    @Test
    fun `settled manual result has candidate identity but no run or stop`() {
        val sms = sms(id = "settled-9", status = "synced")
        val model = HomeSyncState(
            status = HomeSyncState.Status.DONE,
            queue = listOf(sms)
        ).toSmsPipelineCardUiModel()

        requireNotNull(model)
        assertEquals(SmsProcessingTarget.ManualResult("settled-9"), model.target)
        assertEquals(SmsPipelinePhase.COMPLETE, model.phase)
        assertEquals(SmsStopUiState.HIDDEN, model.stopState)
        assertEquals(SmsSourcePreview.Cleared, model.source)
    }

    @Test
    fun `historical candidate and gap retain exact run identity`() {
        val activity = historicalActivity()
        val candidate = activity.toSmsPipelineCardUiModel(
            runId = "history-run-5",
            isCancelling = false,
            isFinishing = false
        )
        val gap = historicalSmsPipelineGapUiModel(
            runId = "history-run-5",
            isCancelling = false,
            isFinishing = true
        )

        assertEquals(
            SmsProcessingTarget.Historical("history-run-5", "history-candidate"),
            candidate.target
        )
        assertTrue(candidate.source is SmsSourcePreview.Message)
        assertEquals(
            SmsProcessingTarget.Historical("history-run-5", null),
            gap.target
        )
        assertEquals(SmsSourcePreview.Hidden, gap.source)
        assertEquals(SmsPipelinePhase.FINISHING, gap.phase)
        assertEquals(SmsStopUiState.COMMIT_UNAVAILABLE, gap.stopState)
    }

    @Test
    fun `manual telemetry presenter centralizes active and settled output`() {
        val sms = sms(id = "telemetry-candidate", status = "syncing")
        val active = SmsTelemetryPresenter.manual(
            state = HomeSyncState(
                status = HomeSyncState.Status.SYNCING,
                activeRunId = "manual-run-telemetry",
                queue = listOf(sms),
                currentIndex = 0,
                currentStageIndex = 2,
                decodedTokenDelta = "partial-token",
                jsonOutput = "{partial}",
                jsonOutputTruncated = true
            ),
            sms = sms,
            filterLogs = listOf("checked"),
            cacheLogs = listOf("cache pending"),
            slmPrompt = "prompt",
            parseJson = { "Parsed: null (non-financial)" },
            target = SmsProcessingTarget.ManualRecent(
                "manual-run-telemetry",
                "telemetry-candidate"
            )
        )

        assertEquals(
            SmsProcessingTarget.ManualRecent(
                "manual-run-telemetry",
                "telemetry-candidate"
            ),
            active.target
        )
        assertEquals(SmsTelemetryStatus.ACTIVE, active.status)
        assertEquals("partial-token", active.decodedTokenDelta)
        assertEquals("{partial}", active.cumulativeStructuredOutput)
        assertTrue(active.jsonOutputTruncated)
        assertEquals("Waiting for complete JSON...", active.parsedOutput)
        assertEquals(SmsStopUiState.AVAILABLE, active.stopState)
        assertTrue(active.isActiveCandidate)

        val settledSms = sms.copy(
            status = "already_saved",
            sender = "Already in ledger",
            body = ""
        )
        val settled = SmsTelemetryPresenter.manual(
            state = HomeSyncState(
                status = HomeSyncState.Status.DONE,
                queue = listOf(settledSms)
            ),
            sms = settledSms,
            filterLogs = emptyList(),
            cacheLogs = emptyList(),
            slmPrompt = "",
            parseJson = { error("settled output must not be parsed") },
            target = SmsProcessingTarget.ManualResult("telemetry-candidate")
        )
        assertEquals(SmsTelemetryStatus.ALREADY_SAVED, settled.status)
        assertEquals(SmsStopUiState.HIDDEN, settled.stopState)
        assertFalse(settled.isActiveCandidate)
        val settledCandidate =
            settled.content as SmsTelemetryContent.Candidate
        assertTrue(
            settledCandidate.source is SmsTelemetrySource.Unavailable
        )
        assertFalse(
            (settledCandidate.source as SmsTelemetrySource.Unavailable)
                .detail.contains("Already in ledger")
        )
    }

    @Test
    fun `stale manual telemetry target cannot rebind to successor run`() {
        val sms = sms(id = "same-candidate", status = "syncing")
        val model = SmsTelemetryPresenter.manual(
            state = HomeSyncState(
                status = HomeSyncState.Status.SYNCING,
                activeRunId = "successor-run",
                queue = listOf(sms),
                currentIndex = 0,
                currentStageIndex = 2,
                decodedTokenDelta = "successor private delta",
                jsonOutput = "successor private output"
            ),
            sms = sms,
            filterLogs = emptyList(),
            cacheLogs = emptyList(),
            slmPrompt = "",
            parseJson = { error("stale target must not parse live output") },
            target = SmsProcessingTarget.ManualRecent(
                runId = "stale-run",
                candidateKey = "same-candidate"
            )
        )

        assertEquals(
            SmsProcessingTarget.ManualRecent("stale-run", "same-candidate"),
            model.target
        )
        assertTrue(model.content is SmsTelemetryContent.Gap)
        assertFalse(model.isActiveCandidate)
        assertEquals(SmsStopUiState.HIDDEN, model.stopState)
        assertFalse(model.jsonOutput.contains("successor private output"))
        assertEquals("", model.decodedTokenDelta)
        assertTrue(model.filterLogs.isEmpty())
        assertTrue(model.cacheLogs.isEmpty())
        assertEquals("", model.slmPrompt)
    }

    @Test
    fun `stale manual candidate cannot rebind within the same run`() {
        val successor = sms(id = "next-candidate", status = "syncing")
        val model = SmsTelemetryPresenter.manual(
            state = HomeSyncState(
                status = HomeSyncState.Status.SYNCING,
                activeRunId = "same-run",
                queue = listOf(successor),
                currentIndex = 0,
                jsonOutput = "next private output"
            ),
            sms = successor.copy(id = "prior-candidate"),
            filterLogs = listOf("private filter output"),
            cacheLogs = listOf("private cache output"),
            slmPrompt = "private prompt",
            parseJson = { error("stale target must not parse successor output") },
            target = SmsProcessingTarget.ManualRecent(
                runId = "same-run",
                candidateKey = "prior-candidate"
            )
        )

        assertTrue(model.content is SmsTelemetryContent.Gap)
        assertEquals(SmsStopUiState.HIDDEN, model.stopState)
        assertEquals("", model.jsonOutput)
        assertTrue(model.filterLogs.isEmpty())
        assertTrue(model.cacheLogs.isEmpty())
        assertEquals("", model.slmPrompt)
    }

    @Test
    fun `historical telemetry derives runtime cache performance and truncation`() {
        val model = SmsTelemetryPresenter.historical(
            activity = historicalActivity().copy(
                grammarEnabled = false,
                answerTokenBudget = 256,
                performance = HistoricalSlmPerformance(
                    promptEvalMs = 12,
                    evalMs = 2_000,
                    generatedTokens = 40
                ),
                cache = HistoricalSlmCacheTelemetry(
                    attempted = true,
                    hit = true,
                    prefixTokens = 300
                ),
                decodedTokenDelta = "latest-token",
                jsonOutput = "{partial}"
            ),
            runId = "history-run-6",
            filterLogs = listOf("eligible"),
            slmPrompt = "prompt",
            parseJson = { "parsed" },
            stopState = SmsStopUiState.AVAILABLE
        )

        assertEquals(
            SmsProcessingTarget.Historical(
                "history-run-6",
                "history-candidate"
            ),
            model.target
        )
        assertEquals(256, model.runtimeFacts?.answerTokenBudget)
        assertEquals(true, model.runtimeFacts?.cacheHit)
        assertEquals(listOf(
            "Prefix cache attempted: true",
            "Prefix cache hit: true",
            "Cached prefix tokens: 300"
        ), model.cacheLogs)
        assertEquals("40 tokens • 20.00 tok/s", model.performanceText)
        assertEquals("latest-token", model.decodedTokenDelta)
        assertEquals("{partial}", model.cumulativeStructuredOutput)
    }

    @Test
    fun `telemetry gap contains no candidate evidence and cannot leak prior text`() {
        val model = SmsTelemetryPresenter.gap(
            target = SmsProcessingTarget.Historical("history-run-7", null),
            phase = SmsPipelinePhase.STOPPING,
            stopState = SmsStopUiState.STOPPING
        )

        assertTrue(model.content is SmsTelemetryContent.Gap)
        assertEquals("", model.jsonOutput)
        assertEquals("", model.decodedTokenDelta)
        assertEquals("", model.slmPrompt)
        assertTrue(model.filterLogs.isEmpty())
        assertTrue(model.cacheLogs.isEmpty())
        assertEquals(SmsStopUiState.STOPPING, model.stopState)
    }

    @Test
    fun `live output truncation notice does not alter complete output`() {
        assertEquals(
            "partial\n\n[Live output truncated for display.]",
            "partial".withLiveOutputTruncationNotice(true)
        )
        assertEquals(
            "complete",
            "complete".withLiveOutputTruncationNotice(false)
        )
    }

    private fun sms(
        id: String,
        sender: String = "Bank",
        status: String
    ) = SyncSmsItem(
        id = id,
        sender = sender,
        body = "Account ending 6254 was debited.",
        date = 0L,
        status = status
    )

    private fun historicalActivity() = HistoricalSmsProcessingActivity(
        candidateKey = "history-candidate",
        sender = "HDFC Bank",
        body = "Account ending 6254 was debited.",
        date = 0L,
        position = 2,
        total = 8,
        stage = HistoricalSmsProcessingStage.GENERATING
    )

    private fun automaticActivity() = AutomaticSmsProcessingActivity(
        owner = AutomaticSmsProcessingOwner(
            candidateKey = "automatic-candidate",
            claimToken = "automatic-claim"
        ),
        sender = "",
        body = "Account ending 6254 was debited.",
        date = 0L,
        stage = AutomaticSmsProcessingStage.PREPARING
    )
}
