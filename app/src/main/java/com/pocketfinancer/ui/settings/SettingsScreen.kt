package com.pocketfinancer.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.inference.DownloadOwner
import com.pocketfinancer.ui.theme.*
import com.pocketfinancer.ui.model.ModelDownloadProgressPanel
import com.pocketfinancer.data.repository.ProcessingConfigurationRepository

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var showEraseConfirmation by rememberSaveable { mutableStateOf(false) }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshPermissionHealth()
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refreshPermissionHealth()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissionHealth()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SmsAndUpdatesCard(
            state = state,
            onAutomaticProcessingChange = viewModel::setProcessIncomingSms,
            onPrimaryCurrencySelected = viewModel::selectPrimaryCurrency,
            onConfirmPrimaryCurrency = viewModel::confirmPrimaryCurrency,
            onRequestSmsPermissions = {
                smsPermissionLauncher.launch(
                    arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
                )
            },
            onRequestNotificationPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onOpenAppSettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    )
                )
            }
        )
        DataAndPrivacyCard(
            state = state,
            onErase = { showEraseConfirmation = true }
        )
        OnDeviceAiCard(state, viewModel)
        AboutCard()
        AdvancedDiagnostics(
            expanded = advancedExpanded,
            onExpandedChange = { advancedExpanded = it },
            state = state,
            viewModel = viewModel
        )

        Spacer(modifier = Modifier.height(8.dp))
    }

    if (showEraseConfirmation) {
        AlertDialog(
            onDismissRequest = { showEraseConfirmation = false },
            title = { Text("Erase all local financial data?") },
            text = {
                Text(
                    "This permanently deletes transactions, accounts, retained source SMS " +
                        "evidence, and setup/import progress from Pocket Financer. Your " +
                        "downloaded on-device AI model stays on this device."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showEraseConfirmation = false
                        viewModel.resetOnboarding {
                            (context as? Activity)?.recreate()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = M3_Error)
                ) {
                    Text("Erase data", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEraseConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SmsAndUpdatesCard(
    state: SettingsUiState,
    onAutomaticProcessingChange: (Boolean) -> Unit,
    onPrimaryCurrencySelected: (String) -> Unit,
    onConfirmPrimaryCurrency: () -> Unit,
    onRequestSmsPermissions: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenAppSettings: () -> Unit
) {
    var currencyMenuExpanded by remember { mutableStateOf(false) }
    SectionCard(title = "SMS & UPDATES") {
        Text(
            "Primary currency",
            color = M3_OnSurface,
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            "Used only when an alert has no explicit currency. Existing processing records keep their original setting snapshot.",
            color = M3_OnSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )
        ExposedDropdownMenuBox(
            expanded = currencyMenuExpanded,
            onExpandedChange = { currencyMenuExpanded = it }
        ) {
            OutlinedTextField(
                value = state.primaryCurrency,
                onValueChange = {},
                readOnly = true,
                label = { Text("Currency") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(currencyMenuExpanded)
                },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = currencyMenuExpanded,
                onDismissRequest = { currencyMenuExpanded = false }
            ) {
                ProcessingConfigurationRepository.SUPPORTED.sorted().forEach { currency ->
                    DropdownMenuItem(
                        text = { Text(currency) },
                        onClick = {
                            onPrimaryCurrencySelected(currency)
                            currencyMenuExpanded = false
                        }
                    )
                }
            }
        }
        Button(
            onClick = onConfirmPrimaryCurrency,
            enabled = state.primaryCurrency != state.confirmedPrimaryCurrency,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text(
                if (state.primaryCurrency == state.confirmedPrimaryCurrency) {
                    "Confirmed ${state.primaryCurrency}"
                } else {
                    "Confirm ${state.primaryCurrency}"
                }
            )
        }
        state.primaryCurrencyError?.let { error ->
            Text(
                error,
                color = M3_Error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        SettingSwitchRow(
            title = "Automatic SMS processing",
            description = if (state.processIncomingSms) {
                "Complete valid alerts become transactions; exceptions go to Review."
            } else {
                "New SMS alerts wait for a manual scan."
            },
            checked = state.processIncomingSms,
            enabled = !state.automaticProcessingChangeRunning,
            onCheckedChange = onAutomaticProcessingChange
        )
        Text(
            text = "Turning this off stops new background analysis; admitted evidence " +
                "and manual scans remain available.",
            color = M3_OnSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp)
        )
        if (state.automaticProcessingChangeRunning) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                color = M3_Primary,
                trackColor = M3_SurfaceContainerLow
            )
        }
        state.automaticProcessingError?.let { error ->
            Text(
                error,
                color = M3_Error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        PermissionStatusRow("Read messages", state.readSmsPermissionGranted)
        PermissionStatusRow("Receive new alerts", state.receiveSmsPermissionGranted)
        PermissionStatusRow(
            "Background progress notifications",
            state.notificationPermissionGranted
        )

        if (!state.smsPermissionGranted) {
            Text(
                "SMS access is incomplete. Historical scans need Read messages; automatic " +
                    "capture needs Receive new alerts.",
                color = M3_Error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
            OutlinedButton(
                onClick = onRequestSmsPermissions,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text("Restore SMS access")
            }
        }
        if (!state.notificationPermissionGranted) {
            Text(
                text = when {
                    state.notificationPermissionRequired &&
                        !state.notificationRuntimePermissionGranted ->
                        "Allow notifications when model preparation or an import continues " +
                            "in the background, so Android can show honest progress."
                    !state.appNotificationsEnabled ->
                        "Notifications are turned off for Pocket Financer in Android " +
                            "settings. Re-enable them to see background progress."
                    !state.progressNotificationChannelEnabled ->
                        "The SMS Transaction Sync notification channel is turned off. " +
                            "Re-enable it in Android settings to see background progress."
                    else ->
                        "Background progress notifications need attention in Android settings."
                },
                color = M3_OnSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (state.notificationPermissionRequired &&
            !state.notificationRuntimePermissionGranted
        ) {
            OutlinedButton(
                onClick = onRequestNotificationPermission,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text("Allow progress notifications")
            }
        }
        if (!state.smsPermissionGranted ||
            !state.notificationPermissionGranted
        ) {
            TextButton(
                onClick = onOpenAppSettings,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Open app settings")
            }
        }
    }
}

@Composable
private fun DataAndPrivacyCard(
    state: SettingsUiState,
    onErase: () -> Unit
) {
    SectionCard(title = "DATA & PRIVACY") {
        Text(
            "Saved transactions keep the original SMS sender and full message body " +
                "indefinitely as evidence. They are stored only in Pocket Financer's " +
                "encrypted local database.",
            color = M3_OnSurface,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Messages rejected as non-transactions are not retained long-term. There is no " +
                "cloud ledger and no retention-period picker.",
            color = M3_OnSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(
            onClick = onErase,
            enabled = state.canResetOnboarding,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = M3_Error),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
        ) {
            Text(if (state.resetRunning) "Erasing..." else "Erase all local financial data")
        }
        if (!state.canResetOnboarding && !state.resetRunning) {
            Text(
                "Erase is available after current model or import work finishes.",
                color = M3_OnSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun OnDeviceAiCard(state: SettingsUiState, viewModel: SettingsViewModel) {
    val download = state.downloadState
    val activeDownloadTier = download.artifactFileName?.let { fileName ->
        SlmTier.ALL_TIERS.find { it.modelFile == fileName }
    }
    SectionCard(title = "ON-DEVICE AI") {
        val selected = state.selectedSlm
        if (selected == null) {
            Text(
                "No compatible local model is available for this device.",
                color = M3_Error,
                style = MaterialTheme.typography.bodyMedium
            )
            return@SectionCard
        }
        Text(
            selected.name,
            color = M3_OnSurface,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        )
        Text(
            "Transaction alerts are interpreted on this device. A model download starts " +
                "only after you choose to prepare it.",
            color = M3_OnSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        InfoRow(
            label = "Status",
            value = when {
                state.modelLoaded -> "Ready"
                download.isComplete -> "Downloaded"
                download.isDownloading -> "Downloading"
                else -> "Not prepared"
            },
            valueColor = if (state.modelLoaded) M3_Pos else M3_OnSurface
        )
        val initialDownload = state.initialSetupDownloadState
        val upgrade = state.upgradeRecommendation
        if (initialDownload != null) {
            ModelDownloadProgressPanel(
                downloadState = initialDownload,
                modifier = Modifier.padding(top = 10.dp),
                label = "Downloading the first on-device model...",
                preparingLabel = "Preparing the first on-device model download..."
            )
        } else if (!state.initialSetupModelPrepared) {
            Text(
                text =
                    "Prepare the first on-device model from Home. The Home " +
                        "setup card keeps the size confirmation, background " +
                        "progress, and restart recovery together.",
                color = M3_OnSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 10.dp)
            )
        } else if (download.isDownloading) {
            val downloadName =
                activeDownloadTier?.name ?: download.artifactFileName ?: "model"
            ModelDownloadProgressPanel(
                downloadState = download,
                modifier = Modifier.padding(top = 10.dp),
                label = "Downloading $downloadName...",
                preparingLabel = "Preparing $downloadName download..."
            )
            OutlinedButton(
                onClick = viewModel::cancelDownload,
                enabled = download.owner == DownloadOwner.SETTINGS,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text(if (download.owner == DownloadOwner.SETTINGS) "Cancel download" else "Managed from Home")
            }
        } else if (upgrade.isUpgradeAvailable) {
            RecommendedModelUpgrade(state = state, viewModel = viewModel)
        } else if (!download.isComplete) {
            Button(
                onClick = viewModel::downloadSelectedModel,
                enabled = !state.runtimeBusy &&
                    !state.flowBusy &&
                    !state.testRunning &&
                    !state.loadingModel &&
                    !state.resetRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                Text("Prepare model (${selected.sizeMb} MB)")
            }
        } else if (!state.modelPinnedByUser) {
            Button(
                onClick = viewModel::loadSelectedModel,
                enabled = state.canLoadModel,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                Text(if (state.loadingModel) "Loading..." else "Use downloaded model")
            }
        }
        state.modelLoadError?.let { error ->
            Text(
                error,
                color = M3_Error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun RecommendedModelUpgrade(
    state: SettingsUiState,
    viewModel: SettingsViewModel
) {
    val upgrade = state.upgradeRecommendation
    val target = upgrade.recommendedSlm ?: return
    val wasCancelled = !upgrade.isRunning && upgrade.statusMessage == "Cancelled"

    HorizontalDivider(
        modifier = Modifier.padding(vertical = 12.dp),
        color = M3_OutlineVariant.copy(alpha = 0.35f)
    )
    Text(
        text = "UPDATE AVAILABLE",
        color = M3_Primary,
        style = MaterialTheme.typography.labelSmall
    )
    Text(
        text = target.name,
        color = M3_OnSurface,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(top = 4.dp)
    )
    Text(
        text = "A higher-quality local model is available (~${"%.1f".format(target.sizeGb)} GB). " +
            "The current model remains active until the download is validated and safely activated.",
        color = M3_OnSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp)
    )

    when {
        upgrade.isDownloading && !upgrade.isCancelling -> {
            ModelDownloadProgressPanel(
                downloadState = upgrade.downloadState,
                modifier = Modifier.padding(top = 10.dp),
                preparingLabel = "Preparing model upgrade download..."
            )
        }
        upgrade.isRunning -> {
            Row(
                modifier = Modifier.padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = M3_Primary
                )
                Column {
                    Text(
                        text = when {
                            upgrade.isCancelling -> "Cancelling model upgrade..."
                            upgrade.isApplying -> "Activating model..."
                            else -> "Preparing model upgrade..."
                        },
                        color = M3_OnSurface,
                        style = MaterialTheme.typography.labelMedium
                    )
                    upgrade.statusMessage?.let { message ->
                        Text(
                            text = message,
                            color = M3_OnSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
        else -> {
            upgrade.error?.let { error ->
                Text(
                    text = error,
                    color = M3_Error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            upgrade.startBlockedMessage?.let { message ->
                Text(
                    text = message,
                    color = M3_OnSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Button(
                onClick = viewModel::startRecommendedModelUpgrade,
                enabled = upgrade.startBlockedMessage == null &&
                    !state.flowBusy &&
                    !state.resetRunning &&
                    !state.testRunning &&
                    !state.loadingModel,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                Text(
                    when {
                        wasCancelled -> "Resume upgrade"
                        upgrade.error != null -> "Retry upgrade"
                        else -> "Download and use update"
                    }
                )
            }
        }
    }

    if (upgrade.canCancel) {
        TextButton(
            onClick = viewModel::cancelRecommendedModelUpgrade,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cancel upgrade")
        }
    }
}

@Composable
private fun AboutCard() {
    SectionCard(title = "ABOUT") {
        Text(
            "Pocket Financer",
            color = M3_OnSurface,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        )
        Text(
            "A local-first financial SMS ledger. Extraction and storage stay on your device; " +
                "network access is used to download the model you explicitly choose.",
            color = M3_OnSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun AdvancedDiagnostics(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    state: SettingsUiState,
    viewModel: SettingsViewModel
) {
    SectionCard(title = "ADVANCED DIAGNOSTICS") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpandedChange(!expanded) }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (expanded) "Hide technical details" else "Show technical details",
                    color = M3_OnSurface,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Text(
                    "Hardware, runtime, grammar and parser-test controls",
                    color = M3_OnSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(if (expanded) "−" else "+", color = M3_Primary, fontSize = 24.sp)
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(12.dp))
            SlmProcessingCard(
                enabled = state.gbnfGrammarEnabled,
                error = state.gbnfGrammarError,
                onEnabledChange = viewModel::setGbnfGrammarEnabled
            )
            Spacer(modifier = Modifier.height(12.dp))
            HardwareCard(state.deviceInfo, state.hardwareError, viewModel)
            Spacer(modifier = Modifier.height(12.dp))
            EngineCard(state, viewModel)
        }
    }
}

@Composable
private fun PermissionStatusRow(label: String, granted: Boolean) {
    InfoRow(
        label = label,
        value = if (granted) "Allowed" else "Needs attention",
        valueColor = if (granted) M3_Pos else M3_Error
    )
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = M3_OnSurface,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                description,
                color = M3_OnSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = M3_Primary
            )
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Section 1: Device Hardware
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun HardwareCard(
    deviceInfo: DeviceCapabilities.DeviceInfo?,
    hardwareError: String?,
    viewModel: SettingsViewModel
) {
    SectionCard(title = "DEVICE HARDWARE") {
        if (hardwareError != null) {
            Text(hardwareError, color = M3_Error, style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }

        val device = deviceInfo ?: run {
            Text("Reading hardware...", color = M3_OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }

        // RAM row
        InfoRow(
            label = "RAM",
            value = "${"%.1f".format(device.ramGb)} GB",
            badge = ramBadge(device.ramTier)
        )

        // GPU row
        device.gpu?.let { gpu ->
            InfoRow(label = "GPU", value = gpu.gpuType)
            if (gpu.renderer.isNotEmpty() && gpu.gpuType != gpu.renderer) {
                LabelValue("Renderer", gpu.renderer)
            }
            LabelValue("OpenGL Version", gpu.version)
        } ?: InfoRow(label = "GPU", value = "Could not detect")

        // CPU row
        device.cpu?.let { cpu ->
            InfoRow(label = "CPU", value = "${cpu.cores} cores")
            val features = buildString {
                append("i8mm ")
                append(if (cpu.hasI8mm) "✓" else "✗")
                append("  dotprod ")
                append(if (cpu.hasDotProd) "✓" else "✗")
                append("  fp16 ")
                append(if (cpu.hasFp16) "✓" else "✗")
            }
            LabelValue("", features)
            cpu.socModel?.let { LabelValue("SoC", it) }
        } ?: InfoRow(label = "CPU", value = "Could not detect")

        // Storage row
        InfoRow(
            label = "Storage",
            value = "${"%.1f".format(device.storage.availableGb)} GB free / ${"%.1f".format(device.storage.totalGb)} GB"
        )
        LabelValue(
            "",
            if (device.storage.canFit(DeviceCapabilities.MODEL_SIZE_BYTES)) "Can fit model? YES" else "Can fit model? NO"
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Section 2: Active Model
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun ActiveModelCard(
    selectedSlm: SlmTier?,
    explanation: String?
) {
    SectionCard(title = "ACTIVE MODEL") {
        if (selectedSlm == null) {
            Text(
                "⚠ No SLM is viable for this device (RAM below minimum)",
                color = M3_Error,
                style = MaterialTheme.typography.bodyMedium
            )
            return@SectionCard
        }

        Column {
            Text(
                text = selectedSlm.name,
                color = M3_Primary,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = selectedSlm.description,
                color = M3_OnSurface,
                style = MaterialTheme.typography.bodyMedium
            )
            explanation?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    color = M3_Pos,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Section 3: Engine Status + Download + Test
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun EngineCard(state: SettingsUiState, viewModel: SettingsViewModel) {
    val ds = state.downloadState
    val activeDownloadTier = ds.artifactFileName?.let { fileName ->
        SlmTier.ALL_TIERS.find { it.modelFile == fileName }
    }

    SectionCard(title = "LLAMA ENGINE") {
        // Status
        InfoRow(
            label = "Status",
            value = when {
                state.runtimePhase != com.pocketfinancer.inference.SlmRuntimePhase.UNLOADED &&
                    state.runtimePhase != com.pocketfinancer.inference.SlmRuntimePhase.READY ->
                    state.runtimePhase.name
                state.loadingModel -> "LOADING..."
                state.runtimePhase == com.pocketfinancer.inference.SlmRuntimePhase.READY -> "READY"
                else -> "NOT LOADED"
            },
            valueColor = when (state.runtimePhase) {
                com.pocketfinancer.inference.SlmRuntimePhase.ERROR -> M3_Error
                com.pocketfinancer.inference.SlmRuntimePhase.READY -> M3_Pos
                else -> M3_OnSurfaceVariant
            }
        )
        if (state.runtimeQueueDepth > 0) {
            LabelValue("Queue", "${state.runtimeQueueDepth} request(s) waiting")
        }
        state.runtimeActiveOwner?.let { owner ->
            LabelValue("Active owner", owner)
        }
        state.pendingRuntimeAction?.let { pending ->
            LabelValue("Pending", pending)
        }

        // Selected model info
        state.selectedSlm?.let { slm ->
            LabelValue("Selected", "${slm.name} — ${slm.description}")
            LabelValue("File", slm.modelFile)
            LabelValue("Size", "${"%.0f".format(slm.sizeMb.toFloat())} MB")
            val fullPath = viewModel.getModelFilePath()
            val shortPath = if (fullPath.contains("/files/models/")) {
                ".../models/" + fullPath.substringAfter("/files/models/")
            } else {
                fullPath
            }
            LabelValue("Path", shortPath)
        }

        // Error
        state.runtimeError?.let { error ->
            Text(
                text = "Runtime: $error",
                color = M3_Error,
                style = AppTypography.monoBody,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        state.modelLoadError
            ?.takeUnless { it == state.runtimeError }
            ?.let { error ->
            Text(
                text = error,
                color = M3_Error,
                style = AppTypography.monoBody,
                modifier = Modifier.padding(top = 8.dp)
            )
            }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Download Section ──

        if (ds.isDownloading) {
            val downloadName =
                activeDownloadTier?.name ?: ds.artifactFileName ?: "model"
            ModelDownloadProgressPanel(
                downloadState = ds,
                label = "Downloading $downloadName...",
                preparingLabel = "Preparing $downloadName download..."
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = { viewModel.cancelDownload() },
                enabled = ds.owner == DownloadOwner.SETTINGS,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = M3_Error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (ds.owner == DownloadOwner.SETTINGS) "CANCEL DOWNLOAD" else "MANAGED FROM HOME", style = MaterialTheme.typography.labelMedium)
            }
        } else {
            // Buttons row when not downloading
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Download button (if not loaded and not already complete)
                if (!state.modelLoaded && !ds.isComplete) {
                    Button(
                        onClick = { viewModel.downloadSelectedModel() },
                        enabled = state.selectedSlm != null &&
                            !state.runtimeBusy &&
                            !state.flowBusy &&
                            !state.testRunning &&
                            !state.loadingModel &&
                            !state.resetRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = M3_PrimaryContainer)
                    ) {
                        val label = state.selectedSlm?.let {
                            "DOWNLOAD (${it.sizeMb}MB)"
                        } ?: "DOWNLOAD MODEL"
                        Text(label, color = M3_OnPrimaryContainer, style = MaterialTheme.typography.labelMedium)
                    }
                }

                // Load button
                Button(
                    onClick = { viewModel.loadSelectedModel() },
                    enabled = state.canLoadModel && !state.modelPinnedByUser,
                    colors = ButtonDefaults.buttonColors(containerColor = M3_PrimaryContainer)
                ) {
                    Text(
                        if (state.loadingModel) "LOADING..." else "LOAD MODEL",
                        color = M3_OnPrimaryContainer,
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                if (state.modelLoaded) {
                    OutlinedButton(
                        onClick = { viewModel.unloadModel() },
                        enabled = state.canUnloadModel,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = M3_Error)
                    ) {
                        Text("UNLOAD", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Run test button
        Button(
            onClick = {
                if (state.testRunning) {
                    viewModel.cancelTestSms()
                } else {
                    viewModel.runTestSms()
                }
            },
            enabled = state.testRunning || state.canRunTest,
            colors = ButtonDefaults.buttonColors(containerColor = M3_SecondaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (state.testRunning) "CANCEL TEST" else "RUN TEST SMS",
                color = M3_OnSecondaryContainer,
                style = MaterialTheme.typography.labelMedium
            )
        }

        // Test output
        state.testProgress?.let { progress ->
            Text(
                text = progress,
                color = M3_OnSurfaceVariant,
                style = AppTypography.monoBodyBold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        state.filterLogs?.let { logs ->
            OutputBox(title = "SMS Filter Pipeline Logs", content = logs.joinToString("\n"))
        }
        state.sessionCacheLogs?.let { logs ->
            OutputBox(title = "KV Cache Session Logs", content = logs.joinToString("\n"))
        }
        state.slmPrompt?.let { prompt ->
            OutputBox(title = "Complete SLM Input Prompt", content = prompt)
        }
        state.testResult?.let { result ->
            OutputBox(title = "Diagnostic result", content = result)
        }
        state.testParsed?.let { parsed ->
            OutputBox(title = "Decision Trace", content = parsed)
        }
        state.testError?.let { error ->
            Text(
                text = error,
                color = M3_Error,
                style = AppTypography.monoBody,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}


// ═══════════════════════════════════════════════════════════════════════════════
// Reusable Components
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = M3_SurfaceContainer),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                color = M3_OnSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    badge: Pair<String, Color>? = null,
    valueColor: Color = M3_OnSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = M3_OnSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                color = valueColor,
                style = MaterialTheme.typography.bodyMedium
            )
            badge?.let { (text, color) ->
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = text,
                    color = color,
                    style = AppTypography.eyebrowBold,
                    modifier = Modifier
                        .background(color.copy(alpha = 0.15f), MaterialTheme.shapes.extraSmall)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        if (label.isNotEmpty()) {
            Text(
                text = label + "  ",
                color = M3_OnSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text(
            text = value,
            color = M3_OnSurfaceVariant,
            style = AppTypography.monoBody
        )
    }
}

@Composable
private fun OutputBox(title: String, content: String) {
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = title,
        color = M3_OnSurfaceVariant,
        style = MaterialTheme.typography.labelSmall
    )
    Surface(
        color = M3_SurfaceContainerLow,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    ) {
        Text(
            text = content,
            color = M3_OnSurface,
            style = AppTypography.monoBody,
            modifier = Modifier.padding(10.dp)
        )
    }
}

@Composable
private fun ramBadge(tier: DeviceCapabilities.RamTier): Pair<String, Color>? {
    return when (tier) {
        DeviceCapabilities.RamTier.OK -> "OK" to M3_Pos
        DeviceCapabilities.RamTier.WARNING -> "WARNING" to Color(0xFFF2C94C)
        DeviceCapabilities.RamTier.BLOCKED -> "BLOCKED" to M3_Error
    }
}

@Composable
private fun SlmProcessingCard(
    enabled: Boolean,
    error: String?,
    onEnabledChange: (Boolean) -> Unit
) {
    SectionCard(title = "SLM SMS PROCESSING") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = enabled,
                    role = Role.Switch,
                    onValueChange = onEnabledChange
                )
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Use GBNF grammar",
                    color = M3_OnSurface,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Constrains the SLM to produce valid transaction JSON. " +
                        "Enabling GBNF slows processing for each SMS, but may improve " +
                        "extraction accuracy and reliability. Changes apply to the next SMS; " +
                        "one already being processed keeps its current setting.",
                    color = M3_OnSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Switch(
                checked = enabled,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = M3_Primary
                )
            )
        }
        error?.let { message ->
            Text(
                text = message,
                color = M3_Error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
