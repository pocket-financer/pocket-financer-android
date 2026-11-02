package com.pocketfinancer.ui.home

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.pocketfinancer.ui.onboarding.HistoricalSlmCacheTelemetry
import com.pocketfinancer.ui.onboarding.HistoricalSlmPerformance
import com.pocketfinancer.ui.onboarding.HistoricalSmsProcessingActivity
import com.pocketfinancer.ui.onboarding.HistoricalSmsProcessingStage
import com.pocketfinancer.ui.onboarding.OnboardingSyncManager
import com.pocketfinancer.ui.onboarding.withoutHistoricalSmsActivity
import com.pocketfinancer.ui.smsprocessing.SmsProcessingTarget
import com.pocketfinancer.ui.smsprocessing.SmsSourcePreview
import com.pocketfinancer.ui.smsprocessing.historicalCacheLogs
import com.pocketfinancer.ui.smsprocessing.historicalParsedOutput
import com.pocketfinancer.ui.smsprocessing.toSmsTelemetryRuntimeFacts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalSmsHomePresentationTest {

    @Test
    fun `home exposes activity only while initial setup is active`() {
        val activity = activity()

        assertSame(
            activity,
            activeHistoricalSmsForHome(
                OnboardingSyncManager.OnboardingSyncState(
                    isRunning = true,
                    runPurpose = OnboardingSyncManager.RunPurpose.INITIAL_SETUP,
                    activeHistoricalSms = activity
                )
            )
        )
        assertNull(
            activeHistoricalSmsForHome(
                OnboardingSyncManager.OnboardingSyncState(
                    isRunning = false,
                    runPurpose = OnboardingSyncManager.RunPurpose.INITIAL_SETUP,
                    activeHistoricalSms = activity
                )
            )
        )
        assertNull(
            activeHistoricalSmsForHome(
                OnboardingSyncManager.OnboardingSyncState(
                    isRunning = true,
                    runPurpose = OnboardingSyncManager.RunPurpose.MODEL_UPGRADE,
                    activeHistoricalSms = activity
                )
            )
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `historical card projection is cold and suppresses token-only updates`() = runTest {
        val activity = activity()
        val source = MutableStateFlow(
            OnboardingSyncManager.OnboardingSyncState(
                isRunning = true,
                runPurpose = OnboardingSyncManager.RunPurpose.INITIAL_SETUP,
                activeHistoricalSms = activity
            )
        )
        val exposed = historicalSmsCardState(source)
        val emissions = mutableListOf<HistoricalSmsProcessingActivity?>()
        val collection = backgroundScope.launch {
            exposed.collect(emissions::add)
        }
        runCurrent()
        assertEquals(listOf(activity.cardSnapshot()), emissions)

        source.value = source.value.copy(
            activeHistoricalSms = activity.copy(
                decodedTokenDelta = "private delta",
                jsonOutput = "private output"
            )
        )
        runCurrent()
        assertEquals(1, emissions.size)

        source.value = source.value.copy(
            activeHistoricalSms = activity.copy(
                stage = HistoricalSmsProcessingStage.PERSISTING
            )
        )
        runCurrent()
        assertEquals(2, emissions.size)
        assertEquals(
            HistoricalSmsProcessingStage.PERSISTING,
            emissions.last()?.stage
        )

        collection.cancel()

        source.value = source.value.copy(
            isRunning = false,
            activeHistoricalSms = null
        )
        runCurrent()

        assertNull(exposed.first())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `stopped home immediately drops activity and stays scrubbed`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val activity = activity().copy(
            decodedTokenDelta = "private delta",
            jsonOutput = "private output"
        )
        val source = MutableStateFlow<HistoricalSmsProcessingActivity?>(activity)
        val owner = TestLifecycleOwner()
        var displayed: HistoricalSmsProcessingActivity? = null
        val collection = backgroundScope.launch(dispatcher) {
            collectNullableStateWhileStarted(
                lifecycle = owner.lifecycle,
                source = source,
                publish = { displayed = it }
            )
        }

        try {
            owner.registry.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertSame(activity, displayed)

            owner.registry.currentState = Lifecycle.State.CREATED
            runCurrent()
            assertNull(displayed)

            source.value = null
            runCurrent()
            assertNull(displayed)

            owner.registry.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertNull(displayed)

            owner.registry.currentState = Lifecycle.State.DESTROYED
        } finally {
            collection.cancel()
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `stopped screen immediately drops manual source and telemetry`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val sensitiveState = HomeSyncState(
            status = HomeSyncState.Status.SYNCING,
            activeRunId = "manual-sensitive-run",
            queue = listOf(
                SyncSmsItem(
                    id = "manual-sensitive-candidate",
                    sender = "PRIVATE-BANK",
                    body = "Account ending 6254 was debited.",
                    date = 0L,
                    status = "syncing"
                )
            ),
            decodedTokenDelta = "private delta",
            jsonOutput = "private output"
        )
        val source = MutableStateFlow(sensitiveState)
        val owner = TestLifecycleOwner()
        var displayed: HomeSyncState? = null
        val collection = backgroundScope.launch(dispatcher) {
            collectSensitiveStateWhileStarted(
                lifecycle = owner.lifecycle,
                source = source,
                publish = { displayed = it }
            )
        }

        try {
            owner.registry.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertSame(sensitiveState, displayed)

            owner.registry.currentState = Lifecycle.State.CREATED
            runCurrent()
            assertNull(displayed)

            owner.registry.currentState = Lifecycle.State.DESTROYED
        } finally {
            collection.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `token-only activity changes are excluded from aggregate home state`() {
        val first = OnboardingSyncManager.OnboardingSyncState(
            isRunning = true,
            runPurpose = OnboardingSyncManager.RunPurpose.INITIAL_SETUP,
            activeHistoricalSms = activity().copy(
                decodedTokenDelta = "first delta",
                jsonOutput = "first"
            )
        )
        val next = first.copy(
            activeHistoricalSms = first.activeHistoricalSms?.copy(
                decodedTokenDelta = "private delta",
                jsonOutput = "private output"
            )
        )

        assertEquals(
            first.withoutHistoricalSmsActivity(),
            next.withoutHistoricalSmsActivity()
        )
        assertNull(next.withoutHistoricalSmsActivity().activeHistoricalSms)
    }

    @Test
    fun `historical telemetry stays bound to exact rendered run and candidate`() {
        val candidate = SmsProcessingTarget.Historical(
            runId = "history-run",
            candidateKey = "candidate-a"
        )
        assertTrue(
            historicalTelemetryTargetIsCurrent(
                requestedTarget = candidate,
                currentRunId = "history-run",
                currentCandidateKey = "candidate-a"
            )
        )
        assertFalse(
            historicalTelemetryTargetIsCurrent(
                requestedTarget = candidate,
                currentRunId = "successor-run",
                currentCandidateKey = "candidate-a"
            )
        )
        assertFalse(
            historicalTelemetryTargetIsCurrent(
                requestedTarget = candidate,
                currentRunId = "history-run",
                currentCandidateKey = "candidate-b"
            )
        )
        assertTrue(
            historicalTelemetryTargetIsCurrent(
                requestedTarget = SmsProcessingTarget.Historical(
                    runId = "history-run",
                    candidateKey = null
                ),
                currentRunId = "history-run",
                currentCandidateKey = null
            )
        )
    }

    @Test
    fun `historical pipeline card wins over manual and gaps clear source`() {
        val manual = HomeSyncState(
            status = HomeSyncState.Status.SYNCING,
            activeRunId = "manual-run",
            queue = listOf(
                SyncSmsItem(
                    id = "manual-candidate",
                    sender = "Manual bank",
                    body = "Manual source",
                    date = 1L,
                    status = "syncing"
                )
            ),
            currentIndex = 0
        )

        val historical = homePipelineCardModel(
            manualState = manual,
            manualStartPending = false,
            historicalRunId = "history-run",
            historicalActivity = activity(),
            historicalCancelling = false,
            historicalFinishing = false,
            historicalPreparingModel = false
        )
        val gap = homePipelineCardModel(
            manualState = manual,
            manualStartPending = false,
            historicalRunId = "history-run",
            historicalActivity = null,
            historicalCancelling = false,
            historicalFinishing = false,
            historicalPreparingModel = false
        )

        assertTrue(historical?.target is SmsProcessingTarget.Historical)
        assertEquals("history-run", historical?.target?.runId)
        assertEquals(SmsSourcePreview.Hidden, gap?.source)
        assertEquals("history-run", gap?.target?.runId)
    }

    @Test
    fun `truncated JSON never produces a false non-financial parse label`() {
        val generating = activity().copy(
            stage = HistoricalSmsProcessingStage.GENERATING,
            jsonOutput = "{\"amount\":500",
            jsonOutputTruncated = true
        )
        val persisting = generating.copy(
            stage = HistoricalSmsProcessingStage.PERSISTING
        )

        assertEquals(
            "Live JSON preview truncated; waiting for inference to finish.",
            historicalParsedOutput(generating) {
                error("truncated preview must not be parsed")
            }
        )
        assertEquals(
            "Parsed successfully; full JSON was omitted from the live display.",
            historicalParsedOutput(persisting) {
                error("truncated output must not be parsed")
            }
        )
    }

    @Test
    fun `telemetry mapping preserves exact runtime and cache facts`() {
        val activity = activity().copy(
            grammarEnabled = false,
            answerTokenBudget = 256,
            performance = HistoricalSlmPerformance(
                promptEvalMs = 321,
                evalMs = 2_500,
                generatedTokens = 75
            ),
            cache = HistoricalSlmCacheTelemetry(
                attempted = true,
                hit = false,
                prefixTokens = 411
            )
        )

        val facts = activity.toSmsTelemetryRuntimeFacts()
        assertEquals(false, facts?.grammarEnabled)
        assertEquals(256, facts?.answerTokenBudget)
        assertEquals(321L, facts?.promptEvalMs)
        assertEquals(2_500L, facts?.evalMs)
        assertEquals(75, facts?.generatedTokens)
        assertEquals(true, facts?.cacheAttempted)
        assertEquals(false, facts?.cacheHit)
        assertEquals(411, facts?.cachePrefixTokens)

        val logs = activity.historicalCacheLogs()
        assertEquals("Prefix cache attempted: true", logs[0])
        assertEquals("Prefix cache hit: false", logs[1])
        assertEquals("Cached prefix tokens: 411", logs[2])
        assertFalse(logs.joinToString().contains("session", ignoreCase = true))
    }

    private fun activity() = HistoricalSmsProcessingActivity(
        candidateKey = "opaque-key",
        sender = "Bank",
        body = "Account ending 6254 was debited.",
        date = 0L,
        position = 1,
        total = 4
    )

    private class TestLifecycleOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle = registry
    }
}
