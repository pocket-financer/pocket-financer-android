package com.pocketfinancer.ui.home

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.core.content.ContextCompat
import com.pocketfinancer.inference.ModelDownloader
import com.pocketfinancer.ui.model.ModelDownloadProgressPanel
import kotlinx.coroutines.launch
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.pocketfinancer.data.model.Transaction
import com.pocketfinancer.data.model.TransactionType
import com.pocketfinancer.pipeline.AutomaticProcessingPreferences
import com.pocketfinancer.pipeline.AutomaticSmsFilterResult
import com.pocketfinancer.pipeline.AutomaticSmsProcessingActivity
import com.pocketfinancer.pipeline.AutomaticSmsProcessingOwner
import com.pocketfinancer.pipeline.AutomaticSmsProcessingStage
import com.pocketfinancer.setup.SetupImportStatus
import com.pocketfinancer.ui.theme.*
import com.pocketfinancer.ui.onboarding.HistoricalSmsProcessingActivity
import com.pocketfinancer.ui.smsprocessing.SmsPipelineActivityCard
import com.pocketfinancer.ui.smsprocessing.SmsPipelineCardUiModel
import com.pocketfinancer.ui.smsprocessing.SmsPipelinePhase
import com.pocketfinancer.ui.smsprocessing.SmsProcessingTarget
import com.pocketfinancer.ui.smsprocessing.SmsStopUiState
import com.pocketfinancer.ui.smsprocessing.SmsTelemetryBottomSheet
import com.pocketfinancer.ui.smsprocessing.SmsTelemetryPresenter
import com.pocketfinancer.ui.smsprocessing.activeSmsPipelineItem
import com.pocketfinancer.ui.smsprocessing.formatGroundedSelectorOutput
import com.pocketfinancer.ui.smsprocessing.historicalSmsPipelineGapUiModel
import com.pocketfinancer.ui.smsprocessing.ownsManualProcessingTarget
import com.pocketfinancer.ui.smsprocessing.ownsAutomaticProcessingTarget
import com.pocketfinancer.ui.smsprocessing.smsPipelineCardItem
import com.pocketfinancer.ui.smsprocessing.toSmsPipelineCardUiModel
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

internal sealed interface HomeSheetTarget {
    data object Queue : HomeSheetTarget

    data class Manual(
        val processingTarget: SmsProcessingTarget,
        val returnToQueue: Boolean = false
    ) : HomeSheetTarget

    data class Historical(
        val processingTarget: SmsProcessingTarget.Historical
    ) : HomeSheetTarget

    data class Automatic(
        val processingTarget: SmsProcessingTarget.Automatic
    ) : HomeSheetTarget
}

internal fun SmsProcessingTarget.toHomeSheetTarget(): HomeSheetTarget = when (this) {
    is SmsProcessingTarget.Automatic -> HomeSheetTarget.Automatic(this)
    is SmsProcessingTarget.Historical -> HomeSheetTarget.Historical(this)
    is SmsProcessingTarget.ManualRecent,
    is SmsProcessingTarget.ManualResult -> HomeSheetTarget.Manual(this)
}

internal fun homePipelineCardModel(
    manualState: HomeSyncState,
    manualStartPending: Boolean,
    historicalRunId: String?,
    historicalActivity: HistoricalSmsProcessingActivity?,
    historicalCancelling: Boolean,
    historicalFinishing: Boolean,
    historicalPreparingModel: Boolean
): SmsPipelineCardUiModel? {
    if (historicalRunId != null && !historicalPreparingModel) {
        return historicalActivity?.toSmsPipelineCardUiModel(
            runId = historicalRunId,
            isCancelling = historicalCancelling,
            isFinishing = historicalFinishing
        ) ?: historicalSmsPipelineGapUiModel(
            runId = historicalRunId,
            isCancelling = historicalCancelling,
            isFinishing = historicalFinishing
        )
    }
    return manualState.toSmsPipelineCardUiModel(
        startPending = manualStartPending
    )
}

/**
 * Automatic processing may legitimately overlap a manual or historical flow.
 * Keep it as a second card instead of applying single-card priority that would
 * hide either live operation.
 */
internal fun homePipelineCardModels(
    userInitiatedCard: SmsPipelineCardUiModel?,
    automaticActivity: AutomaticSmsProcessingActivity?
): List<SmsPipelineCardUiModel> = buildList {
    userInitiatedCard?.let(::add)
    automaticActivity?.toSmsPipelineCardUiModel()?.let(::add)
}

internal fun manualQueueItemTarget(
    state: HomeSyncState,
    item: SyncSmsItem
): SmsProcessingTarget = if (
    state.activeRunId != null &&
    state.activeSmsPipelineItem()?.id == item.id &&
    state.status in setOf(
        HomeSyncState.Status.SYNCING,
        HomeSyncState.Status.CANCELLING
    )
) {
    SmsProcessingTarget.ManualRecent(state.activeRunId, item.id)
} else {
    SmsProcessingTarget.ManualResult(item.id)
}

private fun HomeViewModel.stopRenderedSmsTarget(
    target: SmsProcessingTarget
) {
    when (target) {
        is SmsProcessingTarget.Automatic -> Unit
        is SmsProcessingTarget.Historical ->
            stopHistoricalImport(target)
        is SmsProcessingTarget.ManualRecent ->
            stopManualSync(target)
        is SmsProcessingTarget.ManualResult -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToTab: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedPeriod by viewModel.selectedPeriod.collectAsStateWithLifecycle()
    val aggregateManualSyncState by
        viewModel.manualSyncUiState.collectAsStateWithLifecycle()
    val manualSyncPresentation by
        viewModel.manualSyncPresentation.collectSensitiveManualState()
    val activeHistoricalSmsCard by
        viewModel.activeHistoricalSmsCard.collectSensitiveNullableState()
    val activeAutomaticSmsCard by
        viewModel.automaticSmsPresentation.collectSensitiveNullableState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val pData = state.periodData[selectedPeriod] ?: PeriodData()
    val renderedManualSyncState =
        manualSyncPresentation ?: aggregateManualSyncState
    val userInitiatedPipelineCard = homePipelineCardModel(
        manualState = renderedManualSyncState,
        manualStartPending = state.manualOperationStartPending,
        historicalRunId = state.historicalImportRunId,
        historicalActivity = activeHistoricalSmsCard,
        historicalCancelling = state.historicalImportCancelling,
        historicalFinishing = state.historicalImportFinishing,
        historicalPreparingModel = state.historicalImportPreparingModel
    )
    val pipelineCards = homePipelineCardModels(
        userInitiatedCard = userInitiatedPipelineCard,
        automaticActivity = activeAutomaticSmsCard
    )
    var sheetTarget by remember { mutableStateOf<HomeSheetTarget?>(null) }
    var showModelDownloadConfirmation by remember { mutableStateOf(false) }
    var showHowThisWorks by remember { mutableStateOf(false) }
    var pendingBackgroundAction by remember {
        mutableStateOf<(() -> Unit)?>(null)
    }

    LaunchedEffect(state.historicalImportRunId) {
        val target = sheetTarget
        if (
            target is HomeSheetTarget.Historical &&
            target.processingTarget.runId != state.historicalImportRunId
        ) {
            sheetTarget = null
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissionHealth()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val runSetupAction: (SetupCardAction) -> Unit = { action ->
        when (action.target()) {
            SetupCardActionTarget.OPEN_REVIEWS -> onNavigateToTab("reviews")
            SetupCardActionTarget.SCAN_OLDER ->
                viewModel.scanOlderMessages()
            SetupCardActionTarget.RETRY_RECENT_SYNC ->
                viewModel.checkForUnsynced()
            SetupCardActionTarget.START_SETUP ->
                viewModel.startSetupOrResume()
            SetupCardActionTarget.RESTORE_PERMISSION -> Unit
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        pendingBackgroundAction?.invoke()
        pendingBackgroundAction = null
    }
    val requestNotificationThenRun: (() -> Unit) -> Unit = { action ->
        val needsRuntimePermission =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
        if (needsRuntimePermission) {
            pendingBackgroundAction = action
            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            action()
        }
    }
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val readGranted =
            result[Manifest.permission.READ_SMS] == true ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_SMS
                ) == PackageManager.PERMISSION_GRANTED
        val receiveGranted =
            result[Manifest.permission.RECEIVE_SMS] == true ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECEIVE_SMS
                ) == PackageManager.PERMISSION_GRANTED
        viewModel.onSmsPermissionResult(readGranted && receiveGranted)
    }

    val onSetupAction: (SetupCardAction) -> Unit = { action ->
        when (action) {
            SetupCardAction.OPEN_REVIEWS -> runSetupAction(action)
            SetupCardAction.RESTORE_PERMISSION -> {
                smsPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_SMS,
                        Manifest.permission.RECEIVE_SMS
                    )
                )
            }
            SetupCardAction.PREPARE_MODEL -> {
                if (state.setupImportState.modelDownloadConfirmed) {
                    requestNotificationThenRun {
                        runSetupAction(action)
                    }
                } else {
                    showModelDownloadConfirmation = true
                }
            }
            SetupCardAction.RETRY_RECENT_SYNC ->
                requestNotificationThenRun {
                    runSetupAction(action)
                }
            else -> requestNotificationThenRun {
                runSetupAction(action)
            }
        }
    }

    val dateEyebrow = when (selectedPeriod) {
        "Day" -> {
            val date = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date())
            "Today · $date"
        }
        "Week" -> {
            val today = Calendar.getInstance()
            val start = today.clone() as Calendar
            val currentDay = start.get(Calendar.DAY_OF_WEEK)
            val diff = if (currentDay == Calendar.SUNDAY) -6 else Calendar.MONDAY - currentDay
            start.add(Calendar.DAY_OF_YEAR, diff)
            val end = start.clone() as Calendar
            end.add(Calendar.DAY_OF_YEAR, 6)
            val fmt = SimpleDateFormat("MMM d", Locale.getDefault())
            "This week · ${fmt.format(start.time)} – ${fmt.format(end.time)}"
        }
        else -> {
            val date = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date())
            "This month · $date"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(M3_Surface)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // ── Header ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(M3_Primary.copy(alpha = 0.3f), M3_Primary)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "₹",
                            color = M3_OnPrimary,
                            style = AppTypography.sectionHeadingBold
                        )
                    }
                    Text(
                        text = "pocketFinancer",
                        color = M3_OnSurface,
                        style = MaterialTheme.typography.titleLarge
                    )
                }

                IconButton(
                    onClick = { onNavigateToTab("settings") },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = M3_OnSurfaceVariant
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    SetupImportCard(
                        state = state.setupImportState,
                        downloadState = state.modelDownloadState,
                        isCancelling = state.historicalImportCancelling,
                        isFinishing = state.historicalImportFinishing,
                        isPreparingModel =
                            state.historicalImportPreparingModel,
                        modelUpgradeRunning =
                            state.upgradeRecommendation.isRunning,
                        automaticProcessingEnabled =
                            state.automaticProcessingEnabled,
                        setupActionsEnabled =
                            !state.manualSmsOperationRunning &&
                                !state.manualOperationStartPending,
                        onAction = onSetupAction,
                        onHowThisWorks = { showHowThisWorks = true }
                    )
                }

                pipelineCards.forEach { model ->
                    val renderedTarget = model.target
                    val automatic =
                        renderedTarget is SmsProcessingTarget.Automatic
                    item(
                        key = if (automatic) {
                            "automatic-sms-pipeline:" +
                                "${renderedTarget.runId}:${renderedTarget.candidateKey}"
                        } else {
                            "active-sms-pipeline"
                        }
                    ) {
                        SmsPipelineActivityCard(
                            model = model,
                            modifier = Modifier.padding(horizontal = 16.dp),
                            onInspect = { target ->
                                sheetTarget = target.toHomeSheetTarget()
                            },
                            onStop = if (automatic) {
                                {}
                            } else {
                                viewModel::stopRenderedSmsTarget
                            }
                        )
                    }
                }

                if (
                    renderedManualSyncState.status in setOf(
                        HomeSyncState.Status.SCANNING,
                        HomeSyncState.Status.SYNCING,
                        HomeSyncState.Status.CANCELLING
                    ) &&
                    renderedManualSyncState.queue.isNotEmpty() &&
                    !state.historicalImportRunning
                ) {
                    item(key = "manual-sms-queue-action") {
                        OutlinedButton(
                            onClick = { sheetTarget = HomeSheetTarget.Queue },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .heightIn(min = 48.dp)
                        ) {
                            Text("Review recent scan queue")
                        }
                    }
                }

                // ── Hero Card ──
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = M3_SurfaceContainerLow),
                        border = BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.25f))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(
                                            if (pData.amount > 0.0) {
                                                M3_Error
                                            } else {
                                                M3_OnSurfaceVariant.copy(alpha = 0.55f)
                                            },
                                            CircleShape
                                        )
                                )
                                Text(
                                    text = "$dateEyebrow • Spends",
                                    color = M3_OnSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "₹",
                                    color = M3_OnSurfaceVariant.copy(alpha = 0.6f),
                                    style = AppTypography.amountMedium.copy(
                                        fontWeight = FontWeight.Light
                                    )
                                )
                                Text(
                                    text = String.format("%,.2f", pData.amount),
                                    color = M3_OnSurface,
                                    style = AppTypography.amountHero
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${pData.txnCount} transactions",
                                    color = M3_OnSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                                )
                                val isLess = pData.deltaDir == "less"
                                val isSame = pData.deltaDir == "same"
                                val containerColor = when {
                                    isSame -> M3_SurfaceContainerHigh
                                    isLess -> M3_PosContainer
                                    else -> M3_ErrorContainer
                                }
                                val textColor = when {
                                    isSame -> M3_OnSurfaceVariant
                                    isLess -> M3_OnPosContainer
                                    else -> M3_OnErrorContainer
                                }
                                val icon = if (isLess) {
                                    Icons.Rounded.ArrowDownward
                                } else {
                                    Icons.Rounded.ArrowUpward
                                }

                                Row(
                                    modifier = Modifier
                                        .background(containerColor, RoundedCornerShape(100))
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (!isSame) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = textColor,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                    Text(
                                        text = pData.deltaLabel,
                                        color = textColor,
                                        style = AppTypography.bodySmallBold
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Model Upgrade Banner ──
                val upgradeRec = state.upgradeRecommendation
                if (
                    state.setupImportState.modelPrepared &&
                    upgradeRec.isUpgradeAvailable &&
                    !upgradeRec.isDismissed &&
                    !state.historicalImportRunning &&
                    (
                        !state.manualSmsOperationRunning ||
                            upgradeRec.isRunning
                        )
                ) {
                    item {
                        ModelUpgradeBanner(
                            recommendation = upgradeRec,
                            onUpgrade = {
                                requestNotificationThenRun {
                                    viewModel.startModelUpgrade()
                                }
                            },
                            onCancel = { viewModel.cancelModelUpgrade() },
                            onDismiss = {
                                viewModel.dismissUpgradeBanner()
                                coroutineScope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = "You can upgrade later from Settings > On-device AI.",
                                        actionLabel = "Open settings",
                                        duration = SnackbarDuration.Long
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        onNavigateToTab("settings")
                                    }
                                }
                            }
                        )
                    }
                }

                // ── Sync Banner ──
                if (
                    (
                        userInitiatedPipelineCard == null ||
                            renderedManualSyncState.status == HomeSyncState.Status.DONE
                        ) &&
                    renderedManualSyncState.status in setOf(
                        HomeSyncState.Status.IDLE,
                        HomeSyncState.Status.DONE
                    ) &&
                    !state.manualSmsOperationRunning &&
                    !state.manualOperationStartPending &&
                    state.setupImportState.modelPrepared &&
                    manualRecentSyncAvailable(
                        state.setupImportState.status
                    ) &&
                    !state.historicalImportRunning &&
                    !state.upgradeRecommendation.isRunning
                ) {
                    item {
                        RecentSmsScanLauncher(
                            syncState = renderedManualSyncState,
                            onStartSync = {
                                requestNotificationThenRun {
                                    viewModel.startSync()
                                }
                            },
                            onOpenQueue = {
                                sheetTarget = HomeSheetTarget.Queue
                            },
                            onCheckForUnsynced = {
                                requestNotificationThenRun {
                                    viewModel.resetSyncState()
                                    viewModel.checkForUnsynced()
                                }
                            }
                        )
                    }
                }

                // ── Period Switcher ──
                item {
                    val periods = listOf("Day", "Week", "Month")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(M3_SurfaceContainerLow)
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        periods.forEach { period ->
                            val isSelected = selectedPeriod == period
                            val periodInfo = state.periodData[period] ?: PeriodData()
                            val btnBg = if (isSelected) M3_SecondaryContainer else Color.Transparent
                            val btnText = if (isSelected) M3_OnSecondaryContainer else M3_OnSurfaceVariant
                            val valText = if (isSelected) M3_OnSecondaryContainer.copy(alpha = 0.8f) else M3_OnSurfaceVariant.copy(alpha = 0.6f)

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(btnBg)
                                    .clickable { viewModel.selectPeriod(period) }
                                    .padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = period,
                                    color = btnText,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "₹${String.format("%,.0f", periodInfo.amount)}",
                                    color = valText,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }

                // ── Recent synced transactions card ──
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = M3_SurfaceContainerLow),
                        border = BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.3f))
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Recent synced transactions",
                                    color = M3_OnSurface,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(100))
                                        .clickable { onNavigateToTab("transactions") }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = "All",
                                        color = M3_Primary,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = null,
                                        tint = M3_Primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            HorizontalDivider(color = M3_OutlineVariant.copy(alpha = 0.2f))

                            if (pData.recent.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = selectedPeriodEmptyMessage(
                                            selectedPeriod = selectedPeriod,
                                            totalTransactionCount =
                                                state.totalTransactionCount,
                                            setupStatus =
                                                state.setupImportState.status,
                                            setupFinishing =
                                                state.historicalImportFinishing,
                                            automaticProcessingEnabled =
                                                state.automaticProcessingEnabled
                                        ),
                                        color = M3_OnSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            } else {
                                pData.recent.forEachIndexed { idx, tx ->
                                    val gradient = getAvatarGradient(tx.merchant)
                                    val textColor = getAvatarTextColor(tx.merchant)
                                    val icon = getMerchantIcon(tx.merchant)

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onNavigateToTab("transactions") }
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .background(gradient, RoundedCornerShape(12.dp)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (icon != null) {
                                                    Icon(
                                                        imageVector = icon,
                                                        contentDescription = null,
                                                        tint = textColor,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                } else {
                                                    Text(
                                                        text = getInitials(tx.merchant),
                                                        color = textColor,
                                                        style = MaterialTheme.typography.titleMedium
                                                    )
                                                }
                                            }
                                            Column {
                                                Text(
                                                    text = tx.merchant,
                                                    color = M3_OnSurface,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.CreditCard,
                                                        contentDescription = null,
                                                        tint = M3_OnSurfaceVariant.copy(alpha = 0.6f),
                                                        modifier = Modifier.size(12.dp)
                                                    )
                                                    Text(
                                                        text = getAccountShortLabel(tx.accountLabel),
                                                        color = M3_OnSurfaceVariant,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                        Column(
                                            horizontalAlignment = Alignment.End,
                                            modifier = Modifier.padding(start = 8.dp)
                                        ) {
                                            Text(
                                                text = (if (tx.type == TransactionType.CREDIT) "+" else "−") + "₹${String.format("%,.2f", tx.amount)}",
                                                color = if (tx.type == TransactionType.CREDIT) M3_Pos else M3_OnSurface,
                                                style = AppTypography.amountCompact
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = formatTime(tx.date),
                                                color = M3_OnSurfaceVariant,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                    if (idx < pData.recent.size - 1) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                            color = M3_OutlineVariant.copy(alpha = 0.2f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Drawer Overlay ──
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )

        when (val target = sheetTarget) {
            HomeSheetTarget.Queue -> {
                ModalBottomSheet(
                    onDismissRequest = { sheetTarget = null },
                    sheetState = rememberModalBottomSheetState(
                        skipPartiallyExpanded = true
                    ),
                    containerColor = M3_SurfaceContainer,
                    contentColor = M3_OnSurface
                ) {
                    SmsQueueContent(
                        syncState = renderedManualSyncState,
                        onClose = { sheetTarget = null },
                        onItemClick = { item ->
                            sheetTarget = HomeSheetTarget.Manual(
                                manualQueueItemTarget(
                                    state = renderedManualSyncState,
                                    item = item
                                ),
                                returnToQueue = true
                            )
                        }
                    )
                }
            }

            is HomeSheetTarget.Manual -> {
                HomeManualTelemetrySheet(
                    requestedTarget = target.processingTarget,
                    stateFlow = viewModel.manualSyncTelemetry,
                    viewModel = viewModel,
                    onStop = viewModel::stopRenderedSmsTarget,
                    onTargetExpired = {
                        sheetTarget = if (target.returnToQueue) {
                            HomeSheetTarget.Queue
                        } else {
                            null
                        }
                    },
                    onClose = {
                        if (target.returnToQueue) {
                            sheetTarget = HomeSheetTarget.Queue
                        } else {
                            // Preserve the terminal acknowledgement boundary
                            // from the old log drawer. The state owner ignores
                            // resets until the manual operation has settled.
                            viewModel.resetSyncState()
                            sheetTarget = null
                        }
                    }
                )
            }

            is HomeSheetTarget.Historical -> {
                HomeHistoricalTelemetrySheet(
                    requestedTarget = target.processingTarget,
                    currentRunId = state.historicalImportRunId,
                    currentCandidateKey = activeHistoricalSmsCard?.candidateKey,
                    activityFlow = viewModel.activeHistoricalSms,
                    viewModel = viewModel,
                    isStopping = state.historicalImportCancelling,
                    canStop = state.historicalImportCancellationAllowed,
                    isFinishing = state.historicalImportFinishing,
                    onStop = viewModel::stopRenderedSmsTarget,
                    onClose = { sheetTarget = null }
                )
            }


            is HomeSheetTarget.Automatic -> {
                HomeAutomaticTelemetrySheet(
                    requestedTarget = target.processingTarget,
                    currentOwner = activeAutomaticSmsCard?.owner,
                    activityFlow = viewModel.automaticSmsTelemetry,
                    viewModel = viewModel,
                    onClose = { sheetTarget = null }
                )
            }

            null -> Unit
        }
    }

    if (showModelDownloadConfirmation) {
        AlertDialog(
            onDismissRequest = { showModelDownloadConfirmation = false },
            title = { Text("Prepare the on-device AI model?") },
            text = {
                Text(
                    "This explicitly starts an approximately 700 MB download. " +
                        "The model stays on this device. Historical SMS scanning " +
                        "begins after the model is ready and can resume if interrupted."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.confirmModelDownload()
                        showModelDownloadConfirmation = false
                        requestNotificationThenRun(
                            {
                                runSetupAction(
                                    SetupCardAction.PREPARE_MODEL
                                )
                            }
                        )
                    }
                ) {
                    Text("Download and continue")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showModelDownloadConfirmation = false }
                ) {
                    Text("Not now")
                }
            }
        )
    }

    if (showHowThisWorks) {
        AlertDialog(
            onDismissRequest = { showHowThisWorks = false },
            title = { Text("How this works") },
            text = {
                Text(
                    "Pocket Financer first checks 7 days of SMS locally. If no " +
                        "eligible alert is found, it widens to 30 and then 90 days. " +
                        "Only eligible candidates reach the on-device model. A saved " +
                        "transaction keeps its encrypted source SMS and sender; " +
                        "rejected messages are removed after processing. You can stop " +
                        "a history import or recent batch safely: an already-started " +
                        "save finishes, completed transactions remain, and unfinished " +
                        "messages can be rediscovered when you resume."
                )
            },
            confirmButton = {
                TextButton(onClick = { showHowThisWorks = false }) {
                    Text("Got it")
                }
            }
        )
    }
}

internal fun historicalTelemetryTargetIsCurrent(
    requestedTarget: SmsProcessingTarget.Historical,
    currentRunId: String?,
    currentCandidateKey: String?
): Boolean =
    requestedTarget.runId == currentRunId &&
        requestedTarget.candidateKey == currentCandidateKey

internal fun manualTelemetryTargetIsCurrent(
    target: SmsProcessingTarget,
    state: HomeSyncState
): Boolean = when (target) {
    is SmsProcessingTarget.Automatic -> false
    is SmsProcessingTarget.ManualRecent ->
        state.ownsManualProcessingTarget(target)
    is SmsProcessingTarget.ManualResult ->
        state.queue.any {
            it.id == target.candidateKey && it.status != "syncing"
        }
    is SmsProcessingTarget.Historical -> false
}

@Composable
private fun HomeManualTelemetrySheet(
    requestedTarget: SmsProcessingTarget,
    stateFlow: StateFlow<HomeSyncState>,
    viewModel: HomeViewModel,
    onStop: (SmsProcessingTarget) -> Unit,
    onTargetExpired: () -> Unit,
    onClose: () -> Unit
) {
    val syncState by stateFlow.collectSensitiveManualState()
    val currentState = syncState ?: return

    LaunchedEffect(
        requestedTarget,
        currentState.activeRunId,
        currentState.status,
        currentState.queue
    ) {
        if (!manualTelemetryTargetIsCurrent(requestedTarget, currentState)) {
            onTargetExpired()
        }
    }
    if (!manualTelemetryTargetIsCurrent(requestedTarget, currentState)) return

    val candidate = when (requestedTarget) {
        is SmsProcessingTarget.Automatic -> null
        is SmsProcessingTarget.ManualRecent ->
            currentState.activeSmsPipelineItem()?.takeIf {
                it.id == requestedTarget.candidateKey
            }
        is SmsProcessingTarget.ManualResult ->
            currentState.queue.firstOrNull {
                it.id == requestedTarget.candidateKey
            }
        is SmsProcessingTarget.Historical -> null
    }

    val model = if (candidate != null) {
        val filterLogs = remember(
            candidate.id,
            candidate.sender,
            candidate.body,
            candidate.status
        ) { viewModel.getFilterLogs(candidate) }
        val cacheLogs = remember(
            candidate.id,
            candidate.sender,
            candidate.body,
            candidate.status
        ) { viewModel.getKvCacheLogs(candidate) }
        val slmPrompt = remember(
            candidate.id,
            candidate.sender,
            candidate.body,
            candidate.status
        ) { viewModel.getSlmPrompt(candidate) }
        SmsTelemetryPresenter.manual(
            state = currentState,
            sms = candidate,
            filterLogs = filterLogs,
            cacheLogs = cacheLogs,
            slmPrompt = slmPrompt,
            parseJson = ::formatGroundedSelectorOutput,
            target = requestedTarget
        )
    } else {
        val target = requestedTarget as? SmsProcessingTarget.ManualRecent
            ?: return
        val gapTarget = SmsProcessingTarget.ManualRecent(
            runId = target.runId,
            candidateKey = null
        )
        val phase = when (currentState.status) {
            HomeSyncState.Status.SCANNING -> SmsPipelinePhase.SCANNING
            HomeSyncState.Status.CANCELLING -> SmsPipelinePhase.STOPPING
            else -> SmsPipelinePhase.PROCESSING
        }
        val stopState = when {
            currentState.activeRunId != target.runId ->
                SmsStopUiState.HIDDEN
            currentState.status == HomeSyncState.Status.CANCELLING ->
                SmsStopUiState.STOPPING
            currentState.status in setOf(
                HomeSyncState.Status.SCANNING,
                HomeSyncState.Status.SYNCING
            ) -> SmsStopUiState.AVAILABLE
            else -> SmsStopUiState.HIDDEN
        }
        SmsTelemetryPresenter.gap(
            target = gapTarget,
            phase = phase,
            stopState = stopState,
            title = if (phase == SmsPipelinePhase.SCANNING) {
                "Scanning recent messages"
            } else {
                "Preparing next message"
            }
        )
    }

    key(model.target.candidateKey ?: "manual-gap") {
        SmsTelemetryBottomSheet(
            model = model,
            onStop = onStop,
            onClose = onClose
        )
    }
}

internal fun automaticTelemetryTargetIsCurrent(
    requestedTarget: SmsProcessingTarget.Automatic,
    currentOwner: AutomaticSmsProcessingOwner?
): Boolean = currentOwner?.candidateKey == requestedTarget.candidateKey &&
    currentOwner.claimToken == requestedTarget.claimToken

@Composable
private fun HomeAutomaticTelemetrySheet(
    requestedTarget: SmsProcessingTarget.Automatic,
    currentOwner: AutomaticSmsProcessingOwner?,
    activityFlow: Flow<AutomaticSmsProcessingActivity?>,
    viewModel: HomeViewModel,
    onClose: () -> Unit
) {
    val rawActivity by activityFlow.collectSensitiveNullableState()
    val targetIsCurrent = automaticTelemetryTargetIsCurrent(
        requestedTarget = requestedTarget,
        currentOwner = currentOwner
    )
    LaunchedEffect(requestedTarget, currentOwner) {
        if (!targetIsCurrent) onClose()
    }
    if (!targetIsCurrent) return

    val activity = rawActivity?.takeIf {
        it.ownsAutomaticProcessingTarget(requestedTarget)
    } ?: return
    val filterLogs = remember(
        activity.owner,
        activity.sender,
        activity.body,
        activity.filterResult
    ) {
        when (activity.filterResult) {
            AutomaticSmsFilterResult.PASSED,
            AutomaticSmsFilterResult.REJECTED ->
                viewModel.getAutomaticFilterLogs(activity)
            null -> listOf(
                if (activity.stage == AutomaticSmsProcessingStage.FILTERING) {
                    "The deterministic SMS filter is running."
                } else {
                    "The deterministic SMS filter has not completed yet."
                }
            )
        }
    }
    val promptAvailable = activity.grammarEnabled != null
    val slmPrompt = remember(
        activity.owner,
        activity.sender,
        activity.body,
        promptAvailable,
        activity.filterResult
    ) {
        when {
            promptAvailable -> viewModel.getAutomaticPromptContent(activity)
            activity.filterResult == AutomaticSmsFilterResult.REJECTED ->
                "No prompt was supplied because local filtering rejected this message."
            else ->
                "The extraction prompt has not been supplied to the runtime yet."
        }
    }
    val model = SmsTelemetryPresenter.automatic(
        activity = activity,
        filterLogs = filterLogs,
        slmPrompt = slmPrompt,
        parseJson = ::formatGroundedSelectorOutput,
        target = requestedTarget
    )

    key("${requestedTarget.claimToken}:${requestedTarget.candidateKey}") {
        SmsTelemetryBottomSheet(
            model = model,
            onStop = {},
            onClose = onClose
        )
    }
}

@Composable
private fun HomeHistoricalTelemetrySheet(
    requestedTarget: SmsProcessingTarget.Historical,
    currentRunId: String?,
    currentCandidateKey: String?,
    activityFlow: Flow<HistoricalSmsProcessingActivity?>,
    viewModel: HomeViewModel,
    isStopping: Boolean,
    canStop: Boolean,
    isFinishing: Boolean,
    onStop: (SmsProcessingTarget) -> Unit,
    onClose: () -> Unit
) {
    val historicalActivity by
        activityFlow.collectSensitiveNullableState()

    val targetIsCurrent = historicalTelemetryTargetIsCurrent(
        requestedTarget = requestedTarget,
        currentRunId = currentRunId,
        currentCandidateKey = currentCandidateKey
    )
    LaunchedEffect(
        requestedTarget,
        currentRunId,
        currentCandidateKey
    ) {
        if (!targetIsCurrent) onClose()
    }
    if (!targetIsCurrent) return

    val stopState = when {
        isStopping -> SmsStopUiState.STOPPING
        isFinishing -> SmsStopUiState.COMMIT_UNAVAILABLE
        canStop -> SmsStopUiState.AVAILABLE
        else -> SmsStopUiState.HIDDEN
    }
    val activity = historicalActivity?.takeIf {
        it.candidateKey == requestedTarget.candidateKey
    }
    val model = if (requestedTarget.candidateKey != null) {
        activity ?: return
        val historicalFilterLogs = remember(
            activity.candidateKey,
            activity.sender,
            activity.body
        ) {
            viewModel.getHistoricalFilterLogs(activity)
        }
        val historicalPrompt = remember(
            activity.candidateKey,
            activity.sender,
            activity.body
        ) {
            viewModel.getHistoricalPromptContent(activity)
        }
        SmsTelemetryPresenter.historical(
            activity = activity,
            runId = requestedTarget.runId,
            filterLogs = historicalFilterLogs,
            slmPrompt = historicalPrompt,
            parseJson = ::formatGroundedSelectorOutput,
            stopState = stopState
        )
    } else {
        SmsTelemetryPresenter.gap(
            target = requestedTarget,
            phase = when {
                isStopping -> SmsPipelinePhase.STOPPING
                isFinishing -> SmsPipelinePhase.FINISHING
                else -> SmsPipelinePhase.PROCESSING
            },
            stopState = stopState
        )
    }

    key(model.target.candidateKey ?: "historical-gap") {
        SmsTelemetryBottomSheet(
            model = model,
            onStop = onStop,
            onClose = onClose
        )
    }
}

/**
 * Stops high-frequency telemetry collection with the UI lifecycle and clears
 * the Compose holder immediately on STOP so raw SMS/model output is not kept
 * by an inactive composition.
 */
@Composable
internal fun <T> Flow<T?>.collectSensitiveNullableState(): State<T?> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState<T?>(
        initialValue = null,
        this,
        lifecycle
    ) {
        collectNullableStateWhileStarted(
            lifecycle = lifecycle,
            source = this@collectSensitiveNullableState,
            publish = { value = it }
        )
    }
}

@Composable
internal fun <T : Any> Flow<T>.collectSensitiveManualState(): State<T?> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState<T?>(
        initialValue = null,
        this,
        lifecycle
    ) {
        collectSensitiveStateWhileStarted(
            lifecycle = lifecycle,
            source = this@collectSensitiveManualState,
            publish = { value = it }
        )
    }
}

internal suspend fun <T> collectNullableStateWhileStarted(
    lifecycle: Lifecycle,
    source: Flow<T?>,
    publish: (T?) -> Unit
) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        try {
            source.collect { publish(it) }
        } finally {
            publish(null)
        }
    }
}

internal suspend fun <T : Any> collectSensitiveStateWhileStarted(
    lifecycle: Lifecycle,
    source: Flow<T>,
    publish: (T?) -> Unit
) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        try {
            source.collect { publish(it) }
        } finally {
            publish(null)
        }
    }
}

@Composable
private fun SetupImportCard(
    state: com.pocketfinancer.setup.SetupImportState,
    isCancelling: Boolean,
    isFinishing: Boolean,
    isPreparingModel: Boolean,
    modelUpgradeRunning: Boolean,
    automaticProcessingEnabled: Boolean,
    setupActionsEnabled: Boolean,
    downloadState: ModelDownloader.DownloadState,
    onAction: (SetupCardAction) -> Unit,
    onHowThisWorks: () -> Unit
) {
    val model = setupImportCardModel(
        state = state,
        automaticProcessingEnabled = automaticProcessingEnabled,
        isCancelling = isCancelling,
        isFinishing = isFinishing,
        isPreparingModel = isPreparingModel,
        modelUpgradeRunning = modelUpgradeRunning
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                stateDescription = "${model.title}. ${model.body}"
            },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (state.status) {
                SetupImportStatus.PERMISSION_NEEDED,
                SetupImportStatus.FAILED -> M3_ErrorContainer.copy(alpha = 0.42f)
                SetupImportStatus.READY,
                SetupImportStatus.READY_NO_HISTORY ->
                    M3_PosContainer.copy(alpha = 0.42f)
                else -> M3_SurfaceContainerLow
            }
        ),
        border = BorderStroke(
            1.dp,
            M3_OutlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = model.eyebrow,
                    color = M3_OnSurfaceVariant,
                    style = AppTypography.eyebrow
                )
                Icon(
                    imageVector = when (state.status) {
                        SetupImportStatus.PERMISSION_NEEDED ->
                            Icons.Rounded.Lock
                        SetupImportStatus.DOWNLOADING ->
                            Icons.Rounded.Download
                        SetupImportStatus.SCANNING ->
                            Icons.Rounded.Search
                        SetupImportStatus.PROCESSING ->
                            Icons.Rounded.Memory
                        SetupImportStatus.READY,
                        SetupImportStatus.READY_NO_HISTORY ->
                            Icons.Rounded.Verified
                        SetupImportStatus.FAILED ->
                            Icons.Rounded.ErrorOutline
                        else -> Icons.Rounded.Shield
                    },
                    contentDescription = null,
                    tint = M3_Primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = model.title,
                color = M3_OnSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = model.body,
                color = M3_OnSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
            model.evidence?.let { evidence ->
                Text(
                    text = evidence,
                    color = M3_OnSurface,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            if (model.showProgress) {
                if (state.status == SetupImportStatus.DOWNLOADING) {
                    ModelDownloadProgressPanel(
                        downloadState = downloadState,
                        label = "Downloading the on-device model...",
                        preparingLabel = "Preparing the on-device model download..."
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = M3_Primary,
                        trackColor = M3_SurfaceContainerHigh
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                model.primaryAction?.let { action ->
                    Button(
                        onClick = { onAction(action) },
                        enabled = setupActionsEnabled,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(model.primaryLabel.orEmpty())
                    }
                }
                TextButton(
                    onClick = onHowThisWorks,
                    modifier = if (model.primaryAction == null) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier
                    }
                ) {
                    Text("How this works")
                }
            }
        }
    }
}

@Composable
fun RecentSmsScanLauncher(
    syncState: HomeSyncState,
    onStartSync: () -> Unit,
    onOpenQueue: () -> Unit,
    onCheckForUnsynced: () -> Unit
) {
    if (
        syncState.status !in setOf(
            HomeSyncState.Status.IDLE,
            HomeSyncState.Status.DONE
        )
    ) return

    val pendingCount = syncState.queue.count { it.status == "pending" }
    val hasQueue = syncState.queue.isNotEmpty()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (pendingCount > 0) {
                M3_PrimaryContainer
            } else {
                M3_SurfaceContainerLow
            }
        ),
        border = BorderStroke(
            1.dp,
            M3_OutlineVariant.copy(alpha = 0.25f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(M3_SurfaceContainerHigh, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (pendingCount > 0) {
                            Icons.Rounded.MarkEmailUnread
                        } else {
                            Icons.Default.Refresh
                        },
                        contentDescription = null,
                        tint = M3_Primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "MANUAL SMS SCAN",
                        color = M3_OnSurfaceVariant,
                        style = AppTypography.eyebrow
                    )
                    Text(
                        text = if (pendingCount > 0) {
                            "$pendingCount eligible " +
                                if (pendingCount == 1) {
                                    "message is ready"
                                } else {
                                    "messages are ready"
                                }
                        } else {
                            "Check for recent eligible alerts"
                        },
                        color = M3_OnSurface,
                        style = AppTypography.bodyMediumBold
                    )
                    Text(
                        text = if (pendingCount > 0) {
                            "Review the queue or process it entirely on this device."
                        } else {
                            "Look for recent messages that have not been processed yet."
                        },
                        color = M3_OnSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (hasQueue) {
                OutlinedButton(
                    onClick = onOpenQueue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text("Review queue")
                }
            }
            if (pendingCount > 0) {
                Button(
                    onClick = onStartSync,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Process messages")
                }
            } else {
                Button(
                    onClick = onCheckForUnsynced,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Scan recent messages")
                }
            }
        }
    }
}

@Composable
private fun SmsQueueContent(
    syncState: HomeSyncState,
    onClose: () -> Unit,
    onItemClick: (SyncSmsItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "SMS PROCESSING QUEUE",
                    color = M3_OnSurface,
                    style = AppTypography.eyebrowBold
                )
                Text(
                    text = "Candidate evidence stays on this device",
                    color = M3_OnSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(
                onClick = onClose,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Close")
            }
        }

        HorizontalDivider(color = M3_OutlineVariant.copy(alpha = 0.2f))

        if (syncState.queue.isEmpty()) {
            Text(
                text = "No queued messages are available.",
                color = M3_OnSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 24.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(
                    items = syncState.queue,
                    key = { _, item -> item.id }
                ) { index, item ->
                    val isCurrent = index == syncState.currentIndex &&
                        syncState.status in setOf(
                            HomeSyncState.Status.SYNCING,
                            HomeSyncState.Status.CANCELLING
                        )
                    val statusLabel = when {
                        isCurrent -> "ACTIVE"
                        item.status == "synced" -> "SAVED"
                        item.status == "already_saved" -> "IN LEDGER"
                        item.status == "filtered_out" -> "SKIPPED"
                        item.status == "error" -> "NEEDS ATTENTION"
                        else -> "PENDING"
                    }
                    val statusColor = when {
                        isCurrent -> M3_Primary
                        item.status == "synced" ||
                            item.status == "already_saved" -> M3_Pos
                        item.status == "error" -> M3_Error
                        else -> M3_OnSurfaceVariant
                    }

                    Card(
                        onClick = { onItemClick(item) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = M3_SurfaceContainerLow
                        ),
                        border = BorderStroke(
                            1.dp,
                            statusColor.copy(alpha = 0.22f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement =
                                    Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = item.sender.ifBlank {
                                        "Message ${index + 1}"
                                    },
                                    color = M3_OnSurface,
                                    style = AppTypography.bodySmallBold,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = statusLabel,
                                    color = statusColor,
                                    style = AppTypography.eyebrow
                                )
                            }
                            Text(
                                text = item.body.ifBlank {
                                    "Source evidence was cleared after processing."
                                },
                                color = M3_OnSurfaceVariant,
                                style = AppTypography.monoBody,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = formatTime(item.date),
                                color = M3_OnSurfaceVariant.copy(alpha = 0.75f),
                                style = AppTypography.timestamp
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Shared Color Helpers ──

private fun getInitials(name: String): String {
    if (name.isEmpty()) return "?"
    if (name.contains("@")) return name[0].uppercase()
    val parts = name.trim().split("\\s+".toRegex())
    return if (parts.size > 1) {
        (parts[0][0].toString() + parts[1][0].toString()).uppercase()
    } else {
        name.take(2).uppercase()
    }
}

private fun getAvatarGradient(name: String): Brush {
    val hash = name.hashCode()
    val index = Math.abs(hash) % 5
    return when (index) {
        0 -> Brush.linearGradient(listOf(Color(0xFF452B0E), Color(0xFF2C1B08)))
        1 -> Brush.linearGradient(listOf(Color(0xFF12472B), Color(0xFF0A2919)))
        2 -> Brush.linearGradient(listOf(Color(0xFF0E3E75), Color(0xFF072445)))
        3 -> Brush.linearGradient(listOf(Color(0xFF421863), Color(0xFF260D3A)))
        else -> Brush.linearGradient(listOf(Color(0xFF611221), Color(0xFF380812)))
    }
}

private fun getAvatarTextColor(name: String): Color {
    val hash = name.hashCode()
    val index = Math.abs(hash) % 5
    return when (index) {
        0 -> Color(0xFFFFDCC1)
        1 -> Color(0xFFC7F3C8)
        2 -> Color(0xFFD3E4FF)
        3 -> Color(0xFFF2DAFF)
        else -> Color(0xFFFFDAD9)
    }
}

private fun getMerchantIcon(name: String): ImageVector? {
    val lower = name.lowercase()
    return when {
        lower.contains("zomato") || lower.contains("swiggy") || lower.contains("food") || lower.contains("restaurant") || lower.contains("dine") || lower.contains("cafe") -> Icons.Rounded.Restaurant
        lower.contains("uber") || lower.contains("ola") || lower.contains("cab") || lower.contains("ride") || lower.contains("auto") || lower.contains("transport") -> Icons.Rounded.DirectionsCar
        lower.contains("amazon") || lower.contains("flipkart") || lower.contains("tatacliq") || lower.contains("myntra") || lower.contains("mall") || lower.contains("shop") || lower.contains("store") || lower.contains("grocer") -> Icons.Rounded.ShoppingBag
        lower.contains("netflix") || lower.contains("spotify") || lower.contains("hotstar") || lower.contains("prime") || lower.contains("music") || lower.contains("youtube") || lower.contains("media") || lower.contains("movie") || lower.contains("show") -> Icons.Rounded.PlayArrow
        lower.contains("gym") || lower.contains("fit") || lower.contains("sport") || lower.contains("workout") -> Icons.Rounded.FitnessCenter
        lower.contains("flight") || lower.contains("travel") || lower.contains("hotel") || lower.contains("trip") || lower.contains("makemytrip") || lower.contains("irctc") || lower.contains("rail") -> Icons.Rounded.Flight
        lower.contains("hospital") || lower.contains("pharmacy") || lower.contains("med") || lower.contains("health") || lower.contains("clinic") || lower.contains("doctor") -> Icons.Rounded.MedicalServices
        lower.contains("electric") || lower.contains("water") || lower.contains("gas") || lower.contains("bill") || lower.contains("recharge") || lower.contains("telecom") || lower.contains("jio") || lower.contains("airtel") || lower.contains("vi ") -> Icons.Rounded.Receipt
        lower.contains("bank") || lower.contains("paytm") || lower.contains("gpay") || lower.contains("phonepe") || lower.contains("upi") || lower.contains("hdfc") || lower.contains("sbi") || lower.contains("icici") || lower.contains("axis") || lower.contains("transfer") -> Icons.Rounded.AccountBalance
        lower.contains("card") || lower.contains("visa") || lower.contains("mastercard") || lower.contains("amex") || lower.contains("rupay") || lower.contains("credit") -> Icons.Rounded.CreditCard
        else -> null
    }
}

private fun formatTime(timestampMs: Long): String {
    val date = Date(timestampMs)
    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    return fmt.format(date)
}

private fun getAccountShortLabel(label: String?): String {
    if (label == null || label == "__UNKNOWN__") return "Unknown"
    
    val runs = Regex("\\d+").findAll(label).toList()
    val digits = if (runs.isNotEmpty()) runs.last().value.takeLast(4) else ""
    
    val cleanLabel = label
        .replace(Regex("A/c|Card|Account", RegexOption.IGNORE_CASE), "")
        .replace(Regex("XX\\d*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        
    val shortBank = when {
        cleanLabel.contains("State Bank of India", ignoreCase = true) || cleanLabel.contains("SBI", ignoreCase = true) -> "SBI"
        cleanLabel.contains("HDFC", ignoreCase = true) -> "HDFC"
        cleanLabel.contains("ICICI", ignoreCase = true) -> "ICICI"
        cleanLabel.contains("Axis", ignoreCase = true) -> "Axis"
        cleanLabel.contains("Kotak", ignoreCase = true) -> "Kotak"
        cleanLabel.contains("Standard Chartered", ignoreCase = true) -> "SCB"
        cleanLabel.contains("Federal", ignoreCase = true) -> "Federal"
        cleanLabel.contains("Paytm", ignoreCase = true) -> "Paytm"
        cleanLabel.contains("PhonePe", ignoreCase = true) -> "PhonePe"
        cleanLabel.isBlank() -> "Bank"
        else -> {
            val firstWord = cleanLabel.split(" ").firstOrNull() ?: "Bank"
            if (firstWord.length > 8) firstWord.take(8) else firstWord
        }
    }
    
    return if (digits.isNotEmpty()) "$shortBank ••$digits" else shortBank
}

internal fun selectedPeriodEmptyMessage(
    selectedPeriod: String,
    totalTransactionCount: Int,
    setupStatus: SetupImportStatus,
    setupFinishing: Boolean = false,
    automaticProcessingEnabled: Boolean =
        AutomaticProcessingPreferences.DEFAULT_ENABLED
): String {
    if (totalTransactionCount > 0) {
        return "No spending transactions in the selected ${selectedPeriod.lowercase()} period"
    }
    if (setupFinishing) {
        return "No transactions yet. Finishing local setup; the setup card shows current progress."
    }
    return when (setupStatus) {
        SetupImportStatus.PERMISSION_NEEDED ->
            "SMS access is off. Restore it to discover transaction alerts."
        SetupImportStatus.NOT_STARTED,
        SetupImportStatus.DOWNLOADING,
        SetupImportStatus.SCANNING,
        SetupImportStatus.PROCESSING,
        SetupImportStatus.PAUSED ->
            "No transactions yet. The setup card shows what remains."
        SetupImportStatus.FAILED ->
            "No transactions were saved. The setup card explains what needs attention."
        SetupImportStatus.READY_NO_HISTORY ->
            if (automaticProcessingEnabled) {
                "No eligible transaction history was found. The next eligible alert will appear here."
            } else {
                "No eligible transaction history was found. Pocket Financer will not process new alerts automatically; scan manually or turn automatic SMS processing on."
            }
        SetupImportStatus.READY ->
            "No saved spending transactions in this period."
    }
}

@Composable
fun ModelUpgradeBanner(
    recommendation: ModelUpgradeRecommendation,
    onUpgrade: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    val recSlm = recommendation.recommendedSlm ?: return
    val ds = recommendation.downloadState
    val wasCancelled =
        !recommendation.isRunning &&
            recommendation.statusMessage == "Cancelled"
    val eyebrow = if (recommendation.isDebugEmulatorOverride) {
        "DEBUG EMULATOR UPGRADE TEST"
    } else {
        "ACCURACY UPGRADE AVAILABLE"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = M3_SurfaceContainerLow),
        border = BorderStroke(1.dp, M3_Primary.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(M3_Primary.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Bolt,
                            contentDescription = null,
                            tint = M3_Primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = eyebrow,
                        color = M3_Primary,
                        style = AppTypography.eyebrow
                    )
                }

                if (!recommendation.isRunning) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Dismiss model upgrade",
                            tint = M3_OnSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (recommendation.isDebugEmulatorOverride) {
                        "Test ${recSlm.name} on this emulator"
                    } else {
                        "Improve accuracy with ${recSlm.name}"
                    },
                    color = M3_OnSurface,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = if (recommendation.isDebugEmulatorOverride) {
                        "This debug emulator has enough RAM and storage to exercise the larger-model flow (~${"%.1f".format(recSlm.sizeGb)} GB). Inference may be slower than on an accelerated phone."
                    } else {
                        "Your phone supports a higher quality local AI model (~${"%.1f".format(recSlm.sizeGb)} GB) that can improve transaction extraction accuracy."
                    },
                    color = M3_OnSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (wasCancelled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(M3_SurfaceContainer, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PauseCircle,
                        contentDescription = null,
                        tint = M3_OnSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Upgrade canceled. Downloaded progress is kept for when you resume.",
                        color = M3_OnSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            recommendation.error
                ?.takeIf { !recommendation.isRunning }
                ?.let { error ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                M3_ErrorContainer.copy(alpha = 0.24f),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = M3_Error,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = error,
                            color = M3_OnErrorContainer,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

            if (recommendation.isDownloading && !recommendation.isCancelling) {
                ModelDownloadProgressPanel(
                    downloadState = ds,
                    preparingLabel = "Preparing model upgrade download..."
                )
            } else if (recommendation.isRunning) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(M3_SurfaceContainer, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = M3_Primary,
                        trackColor = M3_OutlineVariant.copy(alpha = 0.3f)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = when {
                                recommendation.isCancelling ->
                                    "Cancelling model upgrade..."
                                recommendation.isApplying ->
                                    "Activating model..."
                                else -> "Preparing model upgrade..."
                            },
                            color = M3_OnSurface,
                            style = AppTypography.bodySmallBold
                        )
                        Text(
                            text = recommendation.statusMessage
                                ?: when {
                                    recommendation.isCancelling ->
                                        "Waiting for model work to stop safely."
                                    recommendation.isApplying ->
                                        "Validating the download and safely switching models."
                                    else ->
                                        "Starting the background download service."
                                },
                            color = M3_OnSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            } else {
                recommendation.startBlockedMessage?.let { message ->
                    Text(
                        text = message,
                        color = M3_OnSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Later", color = M3_OnSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onUpgrade,
                        enabled = recommendation.startBlockedMessage == null,
                        colors = ButtonDefaults.buttonColors(containerColor = M3_Primary),
                        shape = RoundedCornerShape(100)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when {
                                wasCancelled -> "Resume Upgrade"
                                recommendation.error != null -> "Retry Upgrade"
                                else -> "Upgrade Model"
                            },
                            style = AppTypography.titleSmallBold
                        )
                    }
                }
            }

            if (recommendation.canCancel) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            tint = M3_OnSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Cancel upgrade",
                            color = M3_OnSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}
