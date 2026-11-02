package com.pocketfinancer.ui.smsprocessing

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketfinancer.ui.home.HomeSyncState
import com.pocketfinancer.ui.home.RecentSmsScanLauncher
import com.pocketfinancer.ui.home.SyncService
import com.pocketfinancer.ui.home.SyncSmsItem
import com.pocketfinancer.ui.theme.PocketFinancerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmsProcessingComposeUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun activityCard_inspectAndStop_dispatchExactRenderedTarget() {
        val target = SmsProcessingTarget.Historical(
            runId = "rendered-history-run",
            candidateKey = "rendered-candidate"
        )
        val inspected = mutableListOf<SmsProcessingTarget>()
        val stopped = mutableListOf<SmsProcessingTarget>()

        composeRule.setContent {
            PocketFinancerTheme {
                SmsPipelineActivityCard(
                    model = activeCardModel(target = target),
                    onInspect = inspected::add,
                    onStop = stopped::add
                )
            }
        }

        composeRule.onNode(
            hasText("Inspect") and hasClickAction()
        ).performClick()
        composeRule.onNode(
            hasText("Stop") and hasClickAction()
        ).performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(target), inspected)
            assertEquals(listOf(target), stopped)
        }
    }

    @Test
    fun automaticActivityCard_isInspectOnlyAndKeepsUnknownSenderEvidence() {
        val target = SmsProcessingTarget.Automatic(
            claimToken = "automatic-claim",
            candidateKey = "automatic-candidate"
        )
        val body = "Account ending 6254 was debited."
        val inspected = mutableListOf<SmsProcessingTarget>()
        val stopped = mutableListOf<SmsProcessingTarget>()

        composeRule.setContent {
            PocketFinancerTheme {
                SmsPipelineActivityCard(
                    model = activeCardModel(
                        target = target,
                        stopState = SmsStopUiState.HIDDEN,
                        source = SmsSourcePreview.Message("", body)
                    ),
                    onInspect = inspected::add,
                    onStop = stopped::add
                )
            }
        }

        composeRule.onNodeWithText("Unknown sender").assertExists()
        composeRule.onNodeWithText(body).assertExists()
        composeRule.onNode(
            hasText("Inspect") and hasClickAction()
        ).performClick()
        composeRule.onNode(
            hasText("Stop") and hasClickAction()
        ).assertDoesNotExist()

        composeRule.runOnIdle {
            assertEquals(listOf(target), inspected)
            assertTrue(stopped.isEmpty())
        }
    }

    @Test
    fun activityCard_stoppingAndCommitControls_areDisabled() {
        val stopRequests = mutableListOf<SmsProcessingTarget>()
        val model = mutableStateOf(
            activeCardModel(
                target = SmsProcessingTarget.ManualRecent(
                    runId = "stopping-run",
                    candidateKey = "stopping-candidate"
                ),
                stopState = SmsStopUiState.STOPPING
            )
        )

        composeRule.setContent {
            PocketFinancerTheme {
                SmsPipelineActivityCard(
                    model = model.value,
                    onInspect = {},
                    onStop = stopRequests::add
                )
            }
        }

        composeRule.onNodeWithText(
            "Stopping safely",
            substring = true
        ).assertIsNotEnabled()

        composeRule.runOnUiThread {
            model.value = activeCardModel(
                target = SmsProcessingTarget.Historical(
                    runId = "commit-run",
                    candidateKey = "commit-candidate"
                ),
                stopState = SmsStopUiState.COMMIT_UNAVAILABLE
            )
        }

        composeRule.onNodeWithText(
            "Finishing save",
            substring = true
        ).assertIsNotEnabled()
        composeRule.runOnIdle { assertTrue(stopRequests.isEmpty()) }
    }

    @Test
    fun activityCard_sourceFreeGap_removesPriorSenderAndBody() {
        val priorSender = "PRIOR-SENDER-ONLY"
        val priorBody = "Sensitive prior SMS body 879421"
        val model = mutableStateOf(
            activeCardModel(
                target = SmsProcessingTarget.Historical(
                    runId = "history-run",
                    candidateKey = "prior-candidate"
                ),
                source = SmsSourcePreview.Message(priorSender, priorBody)
            )
        )

        composeRule.setContent {
            PocketFinancerTheme {
                SmsPipelineActivityCard(
                    model = model.value,
                    onInspect = {},
                    onStop = {}
                )
            }
        }

        composeRule.onNodeWithText(priorSender).assertExists()
        composeRule.onNodeWithText(priorBody).assertExists()

        composeRule.runOnUiThread {
            model.value = historicalSmsPipelineGapUiModel(
                runId = "history-run",
                isCancelling = false,
                isFinishing = false
            )
        }

        composeRule.onNodeWithText(priorSender).assertDoesNotExist()
        composeRule.onNodeWithText(priorBody).assertDoesNotExist()
        composeRule.onNodeWithText("Preparing next eligible message").assertExists()
    }

    @Test
    fun telemetryViewer_stop_dispatchesExactGapTarget() {
        val target = SmsProcessingTarget.Historical(
            runId = "gap-run-without-candidate",
            candidateKey = null
        )
        val stopped = mutableListOf<SmsProcessingTarget>()

        composeRule.setContent {
            PocketFinancerTheme {
                TelemetryLogsViewer(
                    model = gapTelemetryModel(target),
                    onStop = stopped::add,
                    onClose = {}
                )
            }
        }

        composeRule.onNode(
            hasText("Stop SMS processing") and hasClickAction()
        ).performClick()

        composeRule.runOnIdle { assertEquals(listOf(target), stopped) }
    }

    @Test
    fun telemetryViewer_sourceFreeGap_removesPriorSenderAndBody() {
        val priorSender = "PRIOR-TELEMETRY-SENDER"
        val priorBody = "Prior telemetry body must not survive the gap"
        val model = mutableStateOf(
            candidateTelemetryModel(
                target = SmsProcessingTarget.Historical(
                    runId = "telemetry-run",
                    candidateKey = "telemetry-candidate"
                ),
                sender = priorSender,
                body = priorBody
            )
        )

        composeRule.setContent {
            PocketFinancerTheme {
                TelemetryLogsViewer(
                    model = model.value,
                    onStop = {},
                    onClose = {}
                )
            }
        }

        composeRule.onNodeWithText(priorSender).assertExists()
        composeRule.onNodeWithText(priorBody).assertExists()

        composeRule.runOnUiThread {
            model.value = gapTelemetryModel(
                target = SmsProcessingTarget.Historical(
                    runId = "telemetry-run",
                    candidateKey = null
                )
            )
        }

        composeRule.onNodeWithText(priorSender).assertDoesNotExist()
        composeRule.onNodeWithText(priorBody).assertDoesNotExist()
        composeRule.onNodeWithText("Gap with cleared source").assertExists()
    }

    @Test
    fun telemetryViewer_blankSender_keepsBodyAndLabelsUnknownSender() {
        val body = "Account ending 6254 was debited."
        composeRule.setContent {
            PocketFinancerTheme {
                TelemetryLogsViewer(
                    model = candidateTelemetryModel(
                        target = SmsProcessingTarget.ManualRecent(
                            runId = "unknown-sender-run",
                            candidateKey = "unknown-sender-candidate"
                        ),
                        sender = "",
                        body = body
                    ),
                    onStop = {},
                    onClose = {}
                )
            }
        }

        composeRule.onNodeWithText("Unknown sender").assertExists()
        composeRule.onNodeWithText(body).assertExists()
    }

    @Test
    fun automaticTelemetryViewer_hasNoStopControl() {
        val target = SmsProcessingTarget.Automatic(
            claimToken = "automatic-sheet-claim",
            candidateKey = "automatic-sheet-candidate"
        )
        composeRule.setContent {
            PocketFinancerTheme {
                TelemetryLogsViewer(
                    model = candidateTelemetryModel(
                        target = target,
                        sender = "VK-BANK",
                        body = "INR 1,234.00 spent locally"
                    ).copy(stopState = SmsStopUiState.HIDDEN),
                    onStop = { error("automatic telemetry must not dispatch stop") },
                    onClose = {}
                )
            }
        }

        composeRule.onNodeWithText("Stop SMS processing").assertDoesNotExist()
        composeRule.onNodeWithText("VK-BANK").assertExists()
    }

    @Test
    fun telemetryViewer_streamsModelOutputWithoutSeparateDeltaPanel() {
        val model = candidateTelemetryModel(
            target = SmsProcessingTarget.ManualRecent(
                runId = "live-output-run",
                candidateKey = "live-output-candidate"
            ),
            sender = "VK-BANK",
            body = "INR 1,234.00 spent locally"
        ).copy(
            activeStageIndex = 1,
            decodedTokenDelta = "\"posted\"}",
            jsonOutput = "{\"decision\":\"posted\"}"
        )

        composeRule.setContent {
            PocketFinancerTheme {
                TelemetryLogsViewer(
                    model = model,
                    onStop = {},
                    onClose = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Latest decoded token delta").assertDoesNotExist()
        composeRule.onNodeWithText("Live model output").assertExists()
        composeRule.onNodeWithText("{\"decision\":\"posted\"}").assertExists()
    }

    @Test
    fun activityCard_narrowWidthAndLargeFont_controlsRemainDiscoverable() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = density.density,
                    fontScale = 2f
                )
            ) {
                PocketFinancerTheme {
                    SmsPipelineActivityCard(
                        model = activeCardModel(
                            target = SmsProcessingTarget.ManualRecent(
                                runId = "large-text-run",
                                candidateKey = "large-text-candidate"
                            )
                        ),
                        onInspect = {},
                        onStop = {},
                        modifier = Modifier.width(280.dp)
                    )
                }
            }
        }

        val inspectBounds = composeRule.onNode(
            hasText("Inspect") and hasClickAction()
        ).assertExists().fetchSemanticsNode().boundsInRoot
        val stopBounds = composeRule.onNode(
            hasText("Stop") and hasClickAction()
        ).assertExists().fetchSemanticsNode().boundsInRoot

        assertTrue(inspectBounds.width > 0f)
        assertTrue(stopBounds.width > 0f)
        assertTrue(inspectBounds.bottom <= stopBounds.top)
    }

    @Test
    fun activityCard_normalPhoneWidth_keepsActionsOnOneFooterRow() {
        val model = mutableStateOf(
            activeCardModel(
                target = SmsProcessingTarget.ManualRecent(
                    runId = "compact-footer-run",
                    candidateKey = "compact-footer-candidate"
                )
            ).copy(stepValue = "Parsing")
        )
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = density.density,
                    fontScale = 1f
                )
            ) {
                PocketFinancerTheme {
                    SmsPipelineActivityCard(
                        model = model.value,
                        onInspect = {},
                        onStop = {},
                        modifier = Modifier
                            // Home/Transactions leave 328 dp after their
                            // 16 dp side padding on a 360 dp screen.
                            .width(328.dp)
                            .testTag("compact-activity-card")
                    )
                }
            }
        }

        val inspectBounds = composeRule.onNode(
            hasText("Inspect") and hasClickAction()
        ).fetchSemanticsNode().boundsInRoot
        val stopBounds = composeRule.onNode(
            hasText("Stop") and hasClickAction()
        ).fetchSemanticsNode().boundsInRoot

        assertTrue(inspectBounds.bottom > stopBounds.top)
        assertTrue(stopBounds.bottom > inspectBounds.top)
        assertTrue(inspectBounds.right <= stopBounds.left)

        val twoActionHeight = composeRule.onNodeWithTag(
            "compact-activity-card"
        ).fetchSemanticsNode().boundsInRoot.height

        composeRule.runOnUiThread {
            model.value = model.value.copy(stopState = SmsStopUiState.HIDDEN)
        }
        composeRule.waitForIdle()

        val oneActionHeight = composeRule.onNodeWithTag(
            "compact-activity-card"
        ).fetchSemanticsNode().boundsInRoot.height
        assertTrue(twoActionHeight <= oneActionHeight + 1f)
    }

    @Test
    fun activityCard_normalPhoneWidth_givesLongTransitionControlsTheirOwnRow() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = density.density,
                    fontScale = 1f
                )
            ) {
                PocketFinancerTheme {
                    SmsPipelineActivityCard(
                        model = activeCardModel(
                            target = SmsProcessingTarget.ManualRecent(
                                runId = "stopping-footer-run",
                                candidateKey = "stopping-footer-candidate"
                            ),
                            stopState = SmsStopUiState.STOPPING
                        ).copy(
                            phase = SmsPipelinePhase.STOPPING,
                            inspectLabel = "Inspect stopping details"
                        ),
                        onInspect = {},
                        onStop = {},
                        modifier = Modifier.width(328.dp)
                    )
                }
            }
        }

        val stepBounds = composeRule.onNodeWithText(
            "Checking rendered message"
        ).fetchSemanticsNode().boundsInRoot
        val inspectBounds = composeRule.onNodeWithText(
            "Inspect stopping details"
        ).fetchSemanticsNode().boundsInRoot
        val stopBounds = composeRule.onNodeWithText(
            "Stopping safely",
            substring = true
        ).fetchSemanticsNode().boundsInRoot

        assertTrue(stepBounds.bottom <= inspectBounds.top)
        assertTrue(inspectBounds.bottom > stopBounds.top)
        assertTrue(stopBounds.bottom > inspectBounds.top)
        assertTrue(inspectBounds.right <= stopBounds.left)
    }

    @Test
    fun completedManualScan_keepsQueueReviewAndRescanReachable() {
        var queueOpens = 0
        var rescans = 0
        composeRule.setContent {
            PocketFinancerTheme {
                RecentSmsScanLauncher(
                    syncState = HomeSyncState(
                        status = HomeSyncState.Status.DONE,
                        queue = listOf(
                            SyncSmsItem(
                                id = "settled-candidate",
                                sender = "Saved transaction",
                                body = "",
                                date = 1L,
                                status = "synced"
                            )
                        )
                    ),
                    onStartSync = {},
                    onOpenQueue = { queueOpens += 1 },
                    onCheckForUnsynced = { rescans += 1 }
                )
            }
        }

        composeRule.onNodeWithText("Review queue").performClick()
        composeRule.onNodeWithText("Scan recent messages").performClick()
        composeRule.runOnIdle {
            assertEquals(1, queueOpens)
            assertEquals(1, rescans)
        }
    }

    @Test
    fun manualStopIntent_preservesExactRenderedCandidateIncludingGap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val candidateTarget = SmsProcessingTarget.ManualRecent(
            runId = "exact-run",
            candidateKey = "exact-candidate"
        )
        val candidateIntent = SyncService.stopIntent(context, candidateTarget)

        assertEquals(
            "exact-run",
            candidateIntent.getStringExtra(SyncService.EXTRA_RUN_ID)
        )
        assertEquals(
            "exact-candidate",
            candidateIntent.getStringExtra(
                SyncService.EXTRA_EXPECTED_CANDIDATE_KEY
            )
        )
        assertTrue(
            candidateIntent.getBooleanExtra(
                SyncService.EXTRA_REQUIRE_CANDIDATE_MATCH,
                false
            )
        )

        val gapIntent = SyncService.stopIntent(
            context,
            SmsProcessingTarget.ManualRecent("exact-run", null)
        )
        assertTrue(
            gapIntent.getBooleanExtra(
                SyncService.EXTRA_REQUIRE_CANDIDATE_MATCH,
                false
            )
        )
        assertFalse(gapIntent.hasExtra(SyncService.EXTRA_EXPECTED_CANDIDATE_KEY))
        assertFalse(
            SyncService.stopIntent(context, "exact-run").getBooleanExtra(
                SyncService.EXTRA_REQUIRE_CANDIDATE_MATCH,
                false
            )
        )
    }

    private fun activeCardModel(
        target: SmsProcessingTarget,
        stopState: SmsStopUiState = SmsStopUiState.AVAILABLE,
        source: SmsSourcePreview = SmsSourcePreview.Message(
            sender = "VK-BANK",
            body = "INR 1,234.00 spent locally"
        )
    ): SmsPipelineCardUiModel = SmsPipelineCardUiModel(
        target = target,
        phase = SmsPipelinePhase.PROCESSING,
        tone = SmsPipelineTone.PROCESSING,
        title = "Processing rendered message",
        detail = "Rendered operation details",
        badge = "LIVE",
        source = source,
        stepLabel = "CURRENT STEP",
        stepValue = "Checking rendered message",
        inspectState = SmsInspectUiState.AVAILABLE,
        inspectLabel = "Inspect",
        stopState = stopState,
        accessibilityText = "Processing rendered message. Checking rendered message."
    )

    private fun gapTelemetryModel(
        target: SmsProcessingTarget
    ): SmsTelemetryUiModel = SmsTelemetryPresenter.gap(
        target = target,
        phase = SmsPipelinePhase.PROCESSING,
        stopState = SmsStopUiState.AVAILABLE,
        title = "Gap with cleared source",
        detail = "Previous candidate details were cleared"
    )

    private fun candidateTelemetryModel(
        target: SmsProcessingTarget,
        sender: String,
        body: String
    ): SmsTelemetryUiModel = SmsTelemetryUiModel(
        target = target,
        content = SmsTelemetryContent.Candidate(
            candidateKey = requireNotNull(target.candidateKey),
            source = SmsTelemetrySource.Available(
                sender = sender,
                body = body
            )
        ),
        phase = SmsPipelinePhase.PROCESSING,
        status = SmsTelemetryStatus.ACTIVE,
        activeStageIndex = 0,
        jsonOutput = "",
        filterLogs = listOf("Checking local message"),
        cacheLogs = emptyList(),
        slmPrompt = "",
        parsedOutput = "",
        performanceText = null,
        activeModelName = "Test model",
        stopState = SmsStopUiState.AVAILABLE
    )
}
