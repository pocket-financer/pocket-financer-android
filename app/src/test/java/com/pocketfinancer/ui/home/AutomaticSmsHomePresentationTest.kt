package com.pocketfinancer.ui.home

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.pocketfinancer.pipeline.AutomaticSmsProcessingActivity
import com.pocketfinancer.pipeline.AutomaticSmsProcessingOwner
import com.pocketfinancer.pipeline.AutomaticSmsProcessingStage
import com.pocketfinancer.pipeline.AutomaticSmsSlmCacheTelemetry
import com.pocketfinancer.pipeline.AutomaticSmsSlmPerformance
import com.pocketfinancer.ui.onboarding.HistoricalSmsProcessingActivity
import com.pocketfinancer.ui.smsprocessing.SmsProcessingTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticSmsHomePresentationTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `automatic card projection is cold token scrubbed and clears terminal owner`() =
        runTest {
            val initial = activity().copy(
                modelName = "private-model-file.gguf",
                decodedTokenDelta = "private delta",
                jsonOutput = "private json",
                performance = AutomaticSmsSlmPerformance(1, 2, 3),
                cache = AutomaticSmsSlmCacheTelemetry(true, true, 4)
            )
            val source = MutableStateFlow<AutomaticSmsProcessingActivity?>(initial)
            val exposed = automaticSmsCardState(source)
            val emissions = mutableListOf<AutomaticSmsProcessingActivity?>()
            val collection = backgroundScope.launch {
                exposed.collect(emissions::add)
            }
            runCurrent()

            assertEquals(1, emissions.size)
            val first = requireNotNull(emissions.single())
            assertEquals(initial.sender, first.sender)
            assertEquals(initial.body, first.body)
            assertEquals("", first.decodedTokenDelta)
            assertEquals("", first.jsonOutput)
            assertNull(first.modelName)
            assertNull(first.performance)
            assertNull(first.cache)

            source.value = initial.copy(
                jsonOutput = "another private token",
                performance = AutomaticSmsSlmPerformance(10, 20, 30)
            )
            runCurrent()
            assertEquals(1, emissions.size)

            source.value = source.value?.copy(
                stage = AutomaticSmsProcessingStage.FILTERING
            )
            runCurrent()
            assertEquals(2, emissions.size)
            assertEquals(
                AutomaticSmsProcessingStage.FILTERING,
                emissions.last()?.stage
            )
            assertEquals("", emissions.last()?.jsonOutput)

            source.value = null
            runCurrent()
            assertEquals(3, emissions.size)
            assertNull(emissions.last())
            collection.cancel()
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `stopped home immediately drops automatic source and raw telemetry`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val sensitive = activity().copy(
                decodedTokenDelta = "private delta",
                jsonOutput = "private output"
            )
            val source = MutableStateFlow<AutomaticSmsProcessingActivity?>(sensitive)
            val owner = TestLifecycleOwner()
            var displayed: AutomaticSmsProcessingActivity? = null
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
                assertSame(sensitive, displayed)

                owner.registry.currentState = Lifecycle.State.CREATED
                runCurrent()
                assertNull(displayed)

                source.value = null
                owner.registry.currentState = Lifecycle.State.STARTED
                runCurrent()
                assertNull(displayed)

                owner.registry.currentState = Lifecycle.State.DESTROYED
            } finally {
                collection.cancel()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `automatic telemetry identity includes candidate and exact claim`() {
        val current = activity().owner
        assertTrue(
            automaticTelemetryTargetIsCurrent(
                requestedTarget = SmsProcessingTarget.Automatic(
                    claimToken = current.claimToken,
                    candidateKey = current.candidateKey
                ),
                currentOwner = current
            )
        )
        assertEquals(
            false,
            automaticTelemetryTargetIsCurrent(
                requestedTarget = SmsProcessingTarget.Automatic(
                    claimToken = "successor-claim",
                    candidateKey = current.candidateKey
                ),
                currentOwner = current
            )
        )
        assertEquals(
            false,
            automaticTelemetryTargetIsCurrent(
                requestedTarget = SmsProcessingTarget.Automatic(
                    claimToken = current.claimToken,
                    candidateKey = "successor-candidate"
                ),
                currentOwner = current
            )
        )
    }

    @Test
    fun `automatic card remains separately renderable beside manual or historical work`() {
        val manualState = HomeSyncState(
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
        val manualCard = homePipelineCardModel(
            manualState = manualState,
            manualStartPending = false,
            historicalRunId = null,
            historicalActivity = null,
            historicalCancelling = false,
            historicalFinishing = false,
            historicalPreparingModel = false
        )
        val manualAndAutomatic = homePipelineCardModels(
            userInitiatedCard = manualCard,
            automaticActivity = activity()
        )
        assertEquals(2, manualAndAutomatic.size)
        assertTrue(
            manualAndAutomatic[0].target is SmsProcessingTarget.ManualRecent
        )
        assertTrue(
            manualAndAutomatic[1].target is SmsProcessingTarget.Automatic
        )

        val historicalCard = homePipelineCardModel(
            manualState = manualState,
            manualStartPending = false,
            historicalRunId = "history-run",
            historicalActivity = historicalActivity(),
            historicalCancelling = false,
            historicalFinishing = false,
            historicalPreparingModel = false
        )
        val historicalAndAutomatic = homePipelineCardModels(
            userInitiatedCard = historicalCard,
            automaticActivity = activity()
        )
        assertEquals(2, historicalAndAutomatic.size)
        assertTrue(
            historicalAndAutomatic[0].target is SmsProcessingTarget.Historical
        )
        assertTrue(
            historicalAndAutomatic[1].target is SmsProcessingTarget.Automatic
        )
    }

    private fun activity() = AutomaticSmsProcessingActivity(
        owner = AutomaticSmsProcessingOwner(
            candidateKey = "automatic-candidate",
            claimToken = "automatic-claim"
        ),
        sender = "",
        body = "Account ending 6254 was debited.",
        date = 1L,
        stage = AutomaticSmsProcessingStage.PREPARING
    )

    private class TestLifecycleOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle = registry
    }

    private fun historicalActivity() = HistoricalSmsProcessingActivity(
        candidateKey = "historical-candidate",
        sender = "Historical bank",
        body = "Historical source",
        date = 1L,
        position = 1,
        total = 1
    )
}
