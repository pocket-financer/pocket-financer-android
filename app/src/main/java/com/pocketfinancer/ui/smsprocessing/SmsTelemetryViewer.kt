package com.pocketfinancer.ui.smsprocessing

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketfinancer.ui.theme.AppTypography
import com.pocketfinancer.ui.theme.M3_Error
import com.pocketfinancer.ui.theme.M3_OnSurface
import com.pocketfinancer.ui.theme.M3_OnSurfaceVariant
import com.pocketfinancer.ui.theme.M3_OutlineVariant
import com.pocketfinancer.ui.theme.M3_Pos
import com.pocketfinancer.ui.theme.M3_Primary
import com.pocketfinancer.ui.theme.M3_Surface
import com.pocketfinancer.ui.theme.M3_SurfaceContainer
import com.pocketfinancer.ui.theme.M3_SurfaceContainerHigh
import com.pocketfinancer.ui.theme.M3_SurfaceContainerLow
import com.pocketfinancer.ui.theme.M3_SurfaceContainerLowest

/** One modal implementation shared by Home and Transactions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsTelemetryBottomSheet(
    model: SmsTelemetryUiModel,
    onStop: (SmsProcessingTarget) -> Unit,
    onClose: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = M3_SurfaceContainerLow,
        contentColor = M3_OnSurface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(48.dp)
                    .height(6.dp)
                    .background(
                        M3_OutlineVariant.copy(alpha = 0.6f),
                        RoundedCornerShape(3.dp)
                    )
            )
        }
    ) {
        val contentKey = when (val content = model.content) {
            is SmsTelemetryContent.Candidate -> content.candidateKey
            is SmsTelemetryContent.Gap -> "gap:${model.target.runId}"
        }
        key(contentKey) {
            TelemetryLogsViewer(
                model = model,
                onStop = onStop,
                onClose = onClose
            )
        }
    }
}

/** Detailed, model-driven local pipeline log content. */
@Composable
fun TelemetryLogsViewer(
    model: SmsTelemetryUiModel,
    onStop: (SmsProcessingTarget) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (val content = model.content) {
        is SmsTelemetryContent.Gap -> TelemetryGap(
            model = model,
            gap = content,
            onStop = onStop,
            onClose = onClose,
            modifier = modifier
        )

        is SmsTelemetryContent.Candidate -> CandidateTelemetry(
            model = model,
            candidate = content,
            onStop = onStop,
            onClose = onClose,
            modifier = modifier
        )
    }
}

@Composable
private fun TelemetryGap(
    model: SmsTelemetryUiModel,
    gap: SmsTelemetryContent.Gap,
    onStop: (SmsProcessingTarget) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        TelemetryTitle(onClose = onClose)
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = gap.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = M3_OnSurface
                )
                Text(
                    text = gap.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = M3_OnSurfaceVariant
                )
            }
        }
        TelemetryStopButton(model = model, onStop = onStop)
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun CandidateTelemetry(
    model: SmsTelemetryUiModel,
    candidate: SmsTelemetryContent.Candidate,
    onStop: (SmsProcessingTarget) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier
) {
    var expandedStage by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(model.activeStageIndex, model.isActiveCandidate) {
        if (model.isActiveCandidate) {
            expandedStage = when (model.activeStageIndex) {
                0 -> 0
                1, 2 -> 2
                3 -> 3
                else -> expandedStage
            }
        } else if (expandedStage == null) {
            expandedStage = if (
                model.status == SmsTelemetryStatus.FILTERED_OUT
            ) {
                0
            } else {
                2
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TelemetryTitle(onClose = onClose)
        TelemetryStopButton(model = model, onStop = onStop)
        RuntimeBanner(model)
        model.runtimeFacts?.let { RuntimeFactsCard(it) }

        PipelineTimeline(
            model = model,
            candidate = candidate,
            expandedStage = expandedStage,
            onExpandedStageChange = { expandedStage = it }
        )
        LocalProcessingBoundary()
    }
}

@Composable
private fun TelemetryTitle(onClose: () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val stacked =
            maxWidth < 330.dp || LocalDensity.current.fontScale >= 1.5f
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TelemetryHeading()
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text("Close logs")
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TelemetryHeading()
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text("Close logs")
                }
            }
        }
    }
}

@Composable
private fun TelemetryHeading() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.semantics { heading() }
    ) {
        Icon(
            imageVector = Icons.Rounded.Memory,
            contentDescription = null,
            tint = Color(0xFFF2C94C),
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = "ON-DEVICE EXTRACTION LOGS",
            color = M3_OnSurface,
            style = AppTypography.titleSmallBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TelemetryStopButton(
    model: SmsTelemetryUiModel,
    onStop: (SmsProcessingTarget) -> Unit
) {
    if (model.stopState == SmsStopUiState.HIDDEN) return
    val enabled = model.stopState == SmsStopUiState.AVAILABLE
    OutlinedButton(
        onClick = { onStop(model.target) },
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = M3_Error,
            disabledContentColor = M3_OnSurfaceVariant
        ),
        border = BorderStroke(
            1.dp,
            if (enabled) M3_Error.copy(alpha = 0.55f) else M3_OutlineVariant
        )
    ) {
        Icon(
            imageVector = if (enabled) {
                Icons.Rounded.StopCircle
            } else {
                Icons.Rounded.HourglassTop
            },
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            when (model.stopState) {
                SmsStopUiState.AVAILABLE -> "Stop SMS processing"
                SmsStopUiState.STOPPING -> "Stopping safely…"
                SmsStopUiState.COMMIT_UNAVAILABLE ->
                    "Stop unavailable during final commit"
                SmsStopUiState.HIDDEN -> ""
            }
        )
    }
}

@Composable
private fun RuntimeBanner(model: SmsTelemetryUiModel) {
    Card(
        colors = CardDefaults.cardColors(containerColor = M3_Surface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.15f))
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            val stacked =
                maxWidth < 320.dp || LocalDensity.current.fontScale >= 1.4f
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuntimeIdentity(model.activeModelName)
                    RuntimePerformance(model)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RuntimeIdentity(
                        modelName = model.activeModelName,
                        modifier = Modifier.weight(1f)
                    )
                    RuntimePerformance(model)
                }
            }
        }
    }
}

@Composable
private fun RuntimeIdentity(
    modelName: String?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.DeveloperMode,
            contentDescription = null,
            tint = M3_Primary,
            modifier = Modifier.size(20.dp)
        )
        Column {
            Text(
                text = "Local Device CPU Runtime",
                color = M3_OnSurface,
                style = AppTypography.eyebrowBold
            )
            Text(
                text = modelName ?: "Model details unavailable",
                color = M3_OnSurfaceVariant,
                style = AppTypography.timestamp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RuntimePerformance(model: SmsTelemetryUiModel) {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = model.performanceText ?: if (model.isActiveCandidate) {
                "Evaluating…"
            } else {
                "Unavailable"
            },
            color = M3_Primary,
            style = AppTypography.monoBodyBold
        )
        Text(
            text = "llama.cpp JNI",
            color = M3_OnSurfaceVariant,
            style = AppTypography.timestamp
        )
    }
}

@Composable
private fun PipelineTimeline(
    model: SmsTelemetryUiModel,
    candidate: SmsTelemetryContent.Candidate,
    expandedStage: Int?,
    onExpandedStageChange: (Int?) -> Unit
) {
    val active = model.isActiveCandidate
    val settledFacts = telemetrySettledFacts(model.status)
    val filtered = model.status == SmsTelemetryStatus.FILTERED_OUT
    val error = model.status == SmsTelemetryStatus.ERROR
    val exactAutomaticFilter =
        model.target is SmsProcessingTarget.Automatic
    val automaticInferenceRejected =
        model.wasFilteredAfterAutomaticInference()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val stage0Done = when {
            exactAutomaticFilter -> model.filterOutcome != null
            active -> model.activeStageIndex > 0
            else -> settledFacts.upstreamCompleted || filtered || error
        }
        val stage0Active = active &&
            model.activeStageIndex == 0 &&
            (!exactAutomaticFilter || model.filterOutcome == null)
        TimelineStage(
            title = "Stage 1: SMS Pre-Filter Check",
            statusLabel = when {
                model.filterOutcome == SmsTelemetryFilterOutcome.REJECTED ->
                    "Not eligible"
                model.filterOutcome == SmsTelemetryFilterOutcome.PASSED ->
                    "Checked"
                stage0Done -> "Checked"
                stage0Active -> "Checking…"
                settledFacts.upstreamUnavailable -> "Details unavailable"
                else -> "Pending"
            },
            statusColor = stageColor(stage0Done, stage0Active, error = false),
            icon = if (stage0Done) Icons.Rounded.CheckCircle else Icons.Rounded.Info,
            isExpanded = expandedStage == 0,
            onToggle = {
                onExpandedStageChange(if (expandedStage == 0) null else 0)
            }
        ) {
            when (val source = candidate.source) {
                is SmsTelemetrySource.Available -> {
                    Text(
                        text = "ORIGINAL RAW MESSAGE BODY",
                        color = M3_OnSurfaceVariant,
                        style = AppTypography.eyebrow
                    )
                    OutputBox(
                        title = "Sender",
                        content = source.sender.ifBlank { "Unknown sender" }
                    )
                    OutputBox(title = "Message", content = source.body)
                }

                is SmsTelemetrySource.Unavailable -> {
                    Text(
                        text = "SOURCE MESSAGE UNAVAILABLE",
                        color = M3_OnSurfaceVariant,
                        style = AppTypography.eyebrow
                    )
                    OutputBox(
                        title = "Source status",
                        content = source.detail
                    )
                }
            }
            OutputBox(
                title = "SMS Filter Pipeline Logs",
                content = model.filterLogs.joinToString("\n")
            )
        }

        val stage1Done = if (active) {
            model.activeStageIndex >= 1
        } else {
            settledFacts.upstreamCompleted || automaticInferenceRejected
        }
        val stage1Active = active && model.activeStageIndex == 0 && !stage1Done
        TimelineStage(
            title = "Stage 2: KV Cache & Prompt Prep",
            statusLabel = when {
                stage1Done -> "Prompt Compiled"
                stage1Active -> "Compiling…"
                settledFacts.upstreamUnavailable -> "Details unavailable"
                error -> "Status unavailable"
                filtered -> "Not retained"
                else -> "Pending"
            },
            statusColor = stageColor(stage1Done, stage1Active, error = false),
            icon = if (stage1Done) Icons.Rounded.CheckCircle else Icons.Rounded.Layers,
            isExpanded = expandedStage == 1,
            onToggle = {
                onExpandedStageChange(if (expandedStage == 1) null else 1)
            }
        ) {
            OutputBox(
                title = "KV Cache Session Logs",
                content = model.cacheLogs.joinToString("\n")
            )
            OutputBox(
                title = "Prompt content supplied to runtime",
                content = model.slmPrompt
            )
            Text(
                text = "The model-specific chat template is rendered inside " +
                    "the local runtime and is not claimed as an exact rendered " +
                    "template here.",
                color = M3_OnSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
        }

        val stage2Done = if (active) {
            model.activeStageIndex > 1
        } else {
            settledFacts.upstreamCompleted || automaticInferenceRejected
        }
        val stage2Active = active && model.activeStageIndex == 1
        TimelineStage(
            title = "Stage 3: Grounded candidate selection",
            statusLabel = when {
                stage2Done -> "Selection complete"
                stage2Active -> "Selecting grounded result"
                settledFacts.upstreamUnavailable -> "Details unavailable"
                error -> "Status unavailable"
                filtered -> "No transaction"
                else -> "Pending"
            },
            statusColor = stageColor(stage2Done, stage2Active, error = false),
            icon = when {
                stage2Done -> Icons.Rounded.CheckCircle
                error -> Icons.AutoMirrored.Rounded.HelpOutline
                filtered -> Icons.Rounded.Block
                else -> Icons.Rounded.Memory
            },
            isExpanded = expandedStage == 2,
            onToggle = {
                onExpandedStageChange(if (expandedStage == 2) null else 2)
            }
        ) {
            OutputBox(
                title = "Live model output",
                content = model.cumulativeStructuredOutput
                    .ifEmpty { "Waiting for model output…" }
                    .withLiveOutputTruncationNotice(
                        model.jsonOutputTruncated
                    )
            )
            OutputBox(
                title = "Decision Trace",
                content = "The durable analyzer, selector validation, reconstruction, " +
                    "account-resolution, and gate trace is available from Saved alert reviews."
            )
        }

        val stage3Done = settledFacts.ledgerVerified ||
            (active && model.activeStageIndex > 3)
        val stage3Active = active && model.activeStageIndex == 3
        TimelineStage(
            title = "Stage 4: Encrypted Persistence",
            statusLabel = when {
                stage3Done -> telemetrySettledPersistenceLabel(model.status)
                    ?: "Saved to encrypted ledger"
                stage3Active -> "Writing…"
                filtered || error -> "Not saved"
                else -> "Pending"
            },
            statusColor = stageColor(stage3Done, stage3Active, error),
            icon = if (stage3Done) Icons.Rounded.SaveAlt else Icons.Rounded.Storage,
            isExpanded = expandedStage == 3,
            onToggle = {
                onExpandedStageChange(if (expandedStage == 3) null else 3)
            }
        ) {
            OutputBox(
                title = "Parsed Transaction Output",
                content = model.parsedOutput.ifEmpty {
                    "Waiting for parsed data…"
                }
            )
        }
    }
}

/**
 * A filtered automatic result with a passed deterministic filter and runtime
 * facts can only occur after PipelineService completed inference and rejected
 * the extracted result. Keep that distinct from a phase-zero filter rejection.
 */
internal fun SmsTelemetryUiModel.wasFilteredAfterAutomaticInference(): Boolean =
    target is SmsProcessingTarget.Automatic &&
        status == SmsTelemetryStatus.FILTERED_OUT &&
        filterOutcome == SmsTelemetryFilterOutcome.PASSED &&
        runtimeFacts != null

private fun stageColor(done: Boolean, active: Boolean, error: Boolean): Color =
    when {
        done -> M3_Pos
        active -> Color(0xFFF2C94C)
        error -> M3_Error
        else -> M3_OnSurfaceVariant.copy(alpha = 0.4f)
    }

@Composable
private fun RuntimeFactsCard(facts: SmsTelemetryRuntimeFacts) {
    Card(
        colors = CardDefaults.cardColors(containerColor = M3_SurfaceContainer),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "RUNTIME REQUEST SNAPSHOT",
                color = M3_OnSurfaceVariant,
                style = AppTypography.eyebrowBold
            )
            RuntimeFactRow(
                label = "Structured grammar",
                value = if (facts.grammarEnabled) "Enabled" else "Disabled"
            )
            RuntimeFactRow(
                label = "Answer token limit",
                value = facts.answerTokenBudget.toString()
            )
            RuntimeFactRow(
                label = "Prompt evaluation",
                value = facts.promptEvalMs?.let { "$it ms" }
                    ?: "Awaiting inference result"
            )
            RuntimeFactRow(
                label = "Generation",
                value = if (facts.evalMs != null && facts.generatedTokens != null) {
                    "${facts.evalMs} ms • ${facts.generatedTokens} tokens"
                } else {
                    "Awaiting inference result"
                }
            )
            RuntimeFactRow(
                label = "Prefix cache",
                value = when (facts.cacheAttempted) {
                    null -> "Awaiting inference result"
                    false -> "Not attempted • ${facts.cachePrefixTokens ?: 0} prefix tokens"
                    true -> {
                        val outcome = if (facts.cacheHit == true) "Hit" else "Miss"
                        "$outcome • ${facts.cachePrefixTokens ?: 0} prefix tokens"
                    }
                }
            )
        }
    }
}

@Composable
private fun RuntimeFactRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = M3_OnSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            color = M3_OnSurface,
            style = AppTypography.monoBody,
            modifier = Modifier.weight(1.35f)
        )
    }
}

@Composable
private fun TimelineStage(
    title: String,
    statusLabel: String,
    statusColor: Color,
    icon: ImageVector,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = M3_SurfaceContainer),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            M3_OutlineVariant.copy(alpha = if (isExpanded) 0.35f else 0.15f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(statusColor.copy(alpha = 0.1f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            color = M3_OnSurface,
                            style = AppTypography.eyebrowBold
                        )
                        Text(
                            text = statusLabel,
                            color = statusColor,
                            style = AppTypography.eyebrow
                        )
                    }
                }
                Icon(
                    imageVector = if (isExpanded) {
                        Icons.Rounded.KeyboardArrowUp
                    } else {
                        Icons.Rounded.KeyboardArrowDown
                    },
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = M3_OnSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(M3_SurfaceContainerLow.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun OutputBox(title: String, content: String) {
    Column {
        Text(
            text = title,
            color = M3_OnSurfaceVariant,
            style = AppTypography.eyebrow
        )
        Surface(
            color = M3_SurfaceContainerLowest,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.2f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 8.dp)
        ) {
            Text(
                text = content,
                color = M3_OnSurface,
                style = AppTypography.monoBody,
                modifier = Modifier.padding(10.dp)
            )
        }
    }
}

@Composable
private fun LocalProcessingBoundary() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = M3_SurfaceContainerHigh
        ),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.25f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Rounded.Shield,
                contentDescription = null,
                tint = M3_Pos,
                modifier = Modifier.size(18.dp)
            )
            Column {
                Text(
                    text = "Local SMS Processing",
                    color = M3_OnSurface,
                    style = AppTypography.eyebrowBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "SMS extraction and model inference run locally on " +
                        "this device. This pipeline does not transmit SMS content " +
                        "or model output.",
                    color = M3_OnSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
