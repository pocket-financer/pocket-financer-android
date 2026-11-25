package com.pocketfinancer.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketfinancer.data.model.Transaction
import com.pocketfinancer.data.model.TransactionType
import com.pocketfinancer.data.model.Account
import com.pocketfinancer.data.repository.CurrencyScaleRegistry
import com.pocketfinancer.ui.home.HomeSyncState
import com.pocketfinancer.ui.home.collectSensitiveManualState
import com.pocketfinancer.ui.smsprocessing.SmsPipelineActivityCard
import com.pocketfinancer.ui.smsprocessing.SmsPipelinePhase
import com.pocketfinancer.ui.smsprocessing.SmsProcessingTarget
import com.pocketfinancer.ui.smsprocessing.SmsStopUiState
import com.pocketfinancer.ui.smsprocessing.SmsTelemetryBottomSheet
import com.pocketfinancer.ui.smsprocessing.SmsTelemetryPresenter
import com.pocketfinancer.ui.smsprocessing.activeSmsPipelineItem
import com.pocketfinancer.ui.smsprocessing.formatGroundedSelectorOutput
import com.pocketfinancer.ui.smsprocessing.ownsManualProcessingTarget
import com.pocketfinancer.ui.smsprocessing.smsPipelineCardItem
import com.pocketfinancer.ui.smsprocessing.toSmsPipelineCardUiModel
import com.pocketfinancer.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.flow.StateFlow

private fun nativeStageLabel(state: String): String = when (state) {
    "ready" -> "Alert saved"
    "claimed", "analysis_started", "analyzed" -> "Inspecting message"
    "selector_started" -> "Running on-device AI"
    "selector_completed", "validated" -> "Validating details"
    "reconstructed" -> "Matching account"
    "retained_for_review" -> "Ready for review"
    "interrupted" -> "Interrupted — retry available"
    else -> "Processing locally"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    onNavigateToTab: (String) -> Unit = {},
    onOpenReview: (String) -> Unit = {},
    viewModel: TransactionsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val aggregateManualSyncState by
        viewModel.manualSyncUiState.collectAsStateWithLifecycle()
    val manualSyncPresentation by
        viewModel.manualSyncPresentation.collectSensitiveManualState()
    val renderedManualSyncState =
        manualSyncPresentation ?: aggregateManualSyncState
    var selectedProcessingTarget by remember {
        mutableStateOf<SmsProcessingTarget?>(null)
    }
    var isSearching by remember { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearching) {
        if (isSearching) {
            focusRequester.requestFocus()
        }
    }
    LaunchedEffect(Unit) { viewModel.refreshNativeWork() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(M3_Surface)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // ── Header ──
            if (isSearching) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            isSearching = false
                            viewModel.updateSearchQuery("")
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = M3_OnSurface
                        )
                    }
                    TextField(
                        value = state.searchQuery,
                        onValueChange = { viewModel.updateSearchQuery(it) },
                        placeholder = {
                            Text(
                                text = "Search transactions...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = M3_OnSurfaceVariant.copy(alpha = 0.6f)
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = M3_Primary
                        ),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = M3_OnSurface),
                        trailingIcon = {
                            if (state.searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { viewModel.updateSearchQuery("") }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear",
                                        tint = M3_OnSurfaceVariant
                                    )
                                }
                            }
                        }
                    )
                }
            } else {
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
                                        listOf(M3_Secondary.copy(alpha = 0.3f), M3_Secondary)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.List,
                                contentDescription = "Transactions Icon",
                                tint = M3_OnSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Text(
                            text = "transactionLedger",
                            color = M3_OnSurface,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = { onNavigateToTab("reviews") },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FactCheck,
                                contentDescription = "Review saved alerts",
                                tint = M3_OnSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        IconButton(
                            onClick = { isSearching = true },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = M3_OnSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Box {
                            IconButton(
                                onClick = { sortMenuExpanded = true },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FilterList,
                                    contentDescription = "Filters",
                                    tint = M3_OnSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = sortMenuExpanded,
                                onDismissRequest = { sortMenuExpanded = false },
                                modifier = Modifier.background(M3_SurfaceContainerLow)
                            ) {
                                val sortOptions = listOf(
                                    SortOption.DATE_DESC to "Date (Newest First)",
                                    SortOption.DATE_ASC to "Date (Oldest First)",
                                    SortOption.AMOUNT_DESC to "Amount (Highest First)",
                                    SortOption.AMOUNT_ASC to "Amount (Lowest First)"
                                )
                                sortOptions.forEach { (option, label) ->
                                    val isSelected = state.sortOption == option
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = label,
                                                color = if (isSelected) M3_Primary else M3_OnSurface,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        onClick = {
                                            viewModel.updateSortOption(option)
                                            sortMenuExpanded = false
                                        },
                                        leadingIcon = {
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Check,
                                                    contentDescription = null,
                                                    tint = M3_Primary
                                                )
                                            } else {
                                                Spacer(modifier = Modifier.size(24.dp))
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Segmented Control ──
            val segments = listOf("All", "Debits", "Credits")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(M3_SurfaceContainerLow)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                segments.forEach { label ->
                    val isSelected = state.activeSegment == label
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) M3_SecondaryContainer else Color.Transparent)
                            .clickable { viewModel.updateSegment(label) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) M3_OnSecondaryContainer else M3_OnSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }


            // ── Accounts Filter Chips ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isAllSelected = state.selectedAccountId == "All"
                FilterChip(
                    selected = isAllSelected,
                    onClick = { viewModel.selectAccount("All") },
                    label = { Text("All Accounts", style = MaterialTheme.typography.labelMedium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = M3_Primary,
                        selectedLabelColor = M3_OnPrimary,
                        containerColor = M3_SurfaceContainerLow,
                        labelColor = M3_OnSurfaceVariant
                    )
                )

                state.accounts.forEach { acc ->
                    val isSelected = state.selectedAccountId == acc.id
                    val shortName = getAccountShortName(acc)
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.selectAccount(acc.id) },
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(getAccountColor(acc.name), CircleShape)
                                )
                                Text(shortName, style = MaterialTheme.typography.labelMedium)
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = M3_SurfaceContainerHigh,
                            selectedLabelColor = M3_OnSurface,
                            containerColor = M3_SurfaceContainerLow,
                            labelColor = M3_OnSurfaceVariant
                        ),
                        border = if (isSelected) BorderStroke(1.dp, M3_Primary) else null
                    )
                }
            }

            // ── Month Summary Metric Row ──
            val debitsSum = state.transactions
                .filter { it.type == TransactionType.DEBIT }
                .sumOf { it.amount }
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .background(M3_SurfaceContainerLowest, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Current View Outbound",
                    color = M3_OnSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "OUT  ₹${String.format("%,.2f", debitsSum)}",
                    color = M3_OnSurface,
                    style = AppTypography.amountCompact
                )
            }

            // ── Group transactions by date ──
            val groupedTransactions = remember(state.transactions) {
                state.transactions.groupBy { formatDateKey(it.date) }
            }

            val pipelineCard = renderedManualSyncState.toSmsPipelineCardUiModel()
            val hasNativeWork =
                state.processingOperations.isNotEmpty() || state.reviewCases.isNotEmpty()
            val showEmptyState = !hasNativeWork && shouldShowTransactionsEmptyState(
                hasTransactions = state.transactions.isNotEmpty(),
                hasSyncCard = pipelineCard != null,
                syncStatus = renderedManualSyncState.status
            )

            if (showEmptyState) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No transactions found",
                        color = M3_OnSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                ) {
                    if (state.processingOperations.isNotEmpty()) {
                        item(key = "native-processing-header") {
                            Text(
                                "Processing",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        items(
                            count = state.processingOperations.size,
                            key = { index -> state.processingOperations[index].id }
                        ) { index ->
                            val operation = state.processingOperations[index]
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = M3_SurfaceContainerLow)
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(nativeStageLabel(operation.state), fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "Saved locally · on-device processing",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = M3_OnSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    if (state.reviewCases.isNotEmpty()) {
                        item(key = "native-review-header") {
                            Text(
                                "Needs Review",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        items(
                            count = state.reviewCases.size,
                            key = { index -> state.reviewCases[index].id }
                        ) { index ->
                            val review = state.reviewCases[index]
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .clickable { onOpenReview(review.id) },
                                colors = CardDefaults.cardColors(containerColor = M3_SecondaryContainer)
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("Review extracted transaction", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "Tap to verify source evidence · revision ${review.revision}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = M3_OnSecondaryContainer
                                    )
                                }
                            }
                        }
                    }
                    pipelineCard?.let { model ->
                        item(key = "manual-sms-pipeline") {
                            SmsPipelineActivityCard(
                                model = model,
                                modifier = Modifier.padding(
                                    horizontal = 16.dp,
                                    vertical = 4.dp
                                ),
                                onInspect = { target ->
                                    selectedProcessingTarget = target
                                },
                                onStop = { target ->
                                    if (target is SmsProcessingTarget.ManualRecent) {
                                        viewModel.stopManualSync(target)
                                    }
                                }
                            )
                        }
                    }

                    if (state.transactions.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillParentMaxSize()
                                    .padding(bottom = 64.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No transactions found",
                                    color = M3_OnSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    } else {
                        groupedTransactions.forEach { (dateKey, txList) ->
                        // Header showing date and total debit/credit for the day
                        val dayDebits = txList.filter { it.type == TransactionType.DEBIT }.sumOf { it.amount }
                        val dayCredits = txList.filter { it.type == TransactionType.CREDIT }.sumOf { it.amount }

                        item {
                            DayHeaderRow(
                                dateText = formatDayHeader(txList.first().date),
                                debitTotal = dayDebits,
                                creditTotal = dayCredits
                            )
                        }

                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = M3_SurfaceContainerLow
                                ),
                                border = BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.3f))
                            ) {
                                Column {
                                    txList.forEachIndexed { index, transaction ->
                                        val itemShape = when {
                                            txList.size == 1 -> RoundedCornerShape(16.dp)
                                            index == 0 -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                                            index == txList.size - 1 -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                                            else -> androidx.compose.ui.graphics.RectangleShape
                                        }
                                        TransactionItem(
                                            transaction = transaction,
                                            shape = itemShape,
                                            showDivider = index < txList.size - 1,
                                            onClick = { viewModel.selectTransaction(transaction) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Transaction Details Bottom Sheet ──
        if (state.selectedTransaction != null) {
            val tx = state.selectedTransaction!!

            val gradient = getAvatarGradient(tx.merchant)
            val textColor = getAvatarTextColor(tx.merchant)
            val icon = getMerchantIcon(tx.merchant)

            var isEditing by remember(tx.id) { mutableStateOf(false) }

            var editAmount by remember(tx.id) { mutableStateOf(tx.amount.toString()) }
            var editMerchant by remember(tx.id) { mutableStateOf(tx.merchant) }
            var editAccountId by remember(tx.id) { mutableStateOf(tx.accountId) }
            var editCurrency by remember(tx.id) { mutableStateOf(tx.currencyCode ?: "INR") }
            var editType by remember(tx.id) { mutableStateOf(tx.type) }
            var typeDropdownExpanded by remember { mutableStateOf(false) }
            var accountDropdownExpanded by remember { mutableStateOf(false) }
            var currencyDropdownExpanded by remember { mutableStateOf(false) }
            var editError by remember(tx.id) { mutableStateOf<String?>(null) }

            ModalBottomSheet(
                onDismissRequest = { viewModel.selectTransaction(null) },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = M3_SurfaceContainerLow,
                contentColor = M3_OnSurface,
                dragHandle = {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .width(48.dp)
                            .height(6.dp)
                            .background(M3_OutlineVariant.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
                    )
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 32.dp)
                ) {
                    if (!isEditing) {
                        // Centered Receipt Header
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .background(gradient, RoundedCornerShape(18.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (icon != null) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = textColor,
                                        modifier = Modifier.size(32.dp)
                                    )
                                } else {
                                    Text(
                                        text = getInitials(tx.merchant),
                                        color = textColor,
                                        style = AppTypography.screenHeader
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = tx.merchant,
                                color = M3_OnSurface,
                                style = AppTypography.screenHeader,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${formatFullDate(tx.date)} · ${formatTime(tx.date)}",
                                color = M3_OnSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (tx.isEdited) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier
                                        .background(M3_PrimaryContainer.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = M3_OnPrimaryContainer,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = "Corrected by User",
                                        color = M3_OnPrimaryContainer,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = (if (tx.type == TransactionType.CREDIT) "+" else "−") + "₹${String.format("%,.2f", tx.amount)}",
                                color = if (tx.type == TransactionType.CREDIT) M3_Pos else M3_OnSurface,
                                style = AppTypography.amountHero
                            )
                        }

                        // Centered Badges Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Type Badge
                                val badgeBg = if (tx.type == TransactionType.CREDIT) M3_PosContainer else M3_SecondaryContainer
                                val badgeText = if (tx.type == TransactionType.CREDIT) M3_OnPosContainer else M3_OnSecondaryContainer
                                Row(
                                    modifier = Modifier
                                        .background(badgeBg, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (tx.type == TransactionType.CREDIT) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                        contentDescription = null,
                                        tint = badgeText,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = if (tx.type == TransactionType.CREDIT) "Credit" else "Debit",
                                        color = badgeText,
                                        style = AppTypography.bodySmallBold
                                    )
                                }

                                // Account Badge
                                Row(
                                    modifier = Modifier
                                        .background(M3_SurfaceContainer, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CreditCard,
                                        contentDescription = null,
                                        tint = M3_OnSurfaceVariant,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = tx.accountLabel ?: "Unknown Account",
                                        color = M3_OnSurface,
                                        style = AppTypography.accountCode
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = M3_OutlineVariant.copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.height(16.dp))

                        // SLM OUTPUT Codeblock Section
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Terminal,
                                contentDescription = null,
                                tint = M3_OnSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "SLM OUTPUT",
                                color = M3_OnSurfaceVariant,
                                style = AppTypography.eyebrow
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(M3_SurfaceContainerLowest, RoundedCornerShape(12.dp))
                                .border(BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.3f)), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            val slmProps = listOf(
                                "amount" to tx.amount.toString(),
                                "type" to "\"${tx.type.name.lowercase()}\"",
                                "counterparty" to "\"${tx.merchant}\"",
                                "date" to "\"${formatDate(tx.date)}\"",
                                "account" to "\"${tx.accountLabel ?: ""}\""
                            )
                            slmProps.forEach { (key, value) ->
                                Row(
                                    modifier = Modifier.padding(vertical = 2.dp)
                                ) {
                                    Text(
                                        text = key,
                                        color = M3_OnSurfaceVariant,
                                        style = AppTypography.monoBody,
                                        modifier = Modifier.width(100.dp)
                                    )
                                    Text(
                                        text = ":",
                                        color = M3_OnSurfaceVariant,
                                        style = AppTypography.monoBody,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                    Text(
                                        text = value,
                                        color = if (value.startsWith("\"")) Color(0xFF98C379) else Color(0xFF61AFEF),
                                        style = AppTypography.monoBody,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        if (tx.slmPromptEvalMs != null || tx.slmEvalMs != null || tx.slmNumTokens != null || tx.slmModelName != null) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.FlashOn,
                                    contentDescription = null,
                                    tint = M3_OnSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "SLM RUN TELEMETRY",
                                    color = M3_OnSurfaceVariant,
                                    style = AppTypography.eyebrow
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(M3_SurfaceContainerLowest, RoundedCornerShape(12.dp))
                                    .border(BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.3f)), RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                val promptEvalMs = tx.slmPromptEvalMs
                                val evalMs = tx.slmEvalMs
                                val numTokens = tx.slmNumTokens
                                val modelName = tx.slmModelName

                                val tps = if (evalMs != null && evalMs > 0 && numTokens != null) {
                                    (numTokens.toDouble() / (evalMs.toDouble() / 1000.0))
                                } else {
                                    0.0
                                }

                                val telemetryProps = listOf(
                                    "model_name" to (modelName ?: "Unknown Model"),
                                    "prompt_eval" to (promptEvalMs?.let { "${it}ms" } ?: "N/A"),
                                    "generation" to (evalMs?.let { "${it}ms" } ?: "N/A"),
                                    "tokens" to (numTokens?.let { "$it tokens" } ?: "N/A"),
                                    "tps" to if (tps > 0.0) String.format("%.1f tok/s", tps) else "N/A"
                                )

                                telemetryProps.forEach { (key, value) ->
                                    Row(
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = key,
                                            color = M3_OnSurfaceVariant,
                                            style = AppTypography.monoBody,
                                            modifier = Modifier.width(100.dp)
                                        )
                                        Text(
                                            text = ":",
                                            color = M3_OnSurfaceVariant,
                                            style = AppTypography.monoBody,
                                            modifier = Modifier.padding(horizontal = 4.dp)
                                        )
                                        Text(
                                            text = value,
                                            color = if (key == "model_name") Color(0xFFE5C07B) else if (key == "tps") M3_Primary else M3_OnSurface,
                                            style = AppTypography.monoBody,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { isEditing = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = M3_Primary,
                                contentColor = M3_OnPrimary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Correct Extraction Details",
                                style = AppTypography.titleSmallBold
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    } else {
                        // Edit Mode Title Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Correct Extracted Info",
                                color = M3_OnSurface,
                                style = AppTypography.screenHeader
                            )
                            Text(
                                text = "EDIT MODE",
                                color = M3_Primary,
                                style = AppTypography.eyebrow,
                                modifier = Modifier
                                    .background(M3_PrimaryContainer.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        // Editable form inputs
                        OutlinedTextField(
                            value = editMerchant,
                            onValueChange = { editMerchant = it },
                            label = { Text("Merchant / Counterparty") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = M3_Primary,
                                unfocusedBorderColor = M3_OutlineVariant,
                                focusedLabelColor = M3_Primary,
                                unfocusedLabelColor = M3_OnSurfaceVariant,
                                focusedTextColor = M3_OnSurface,
                                unfocusedTextColor = M3_OnSurface
                            )
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = editAmount,
                            onValueChange = { editAmount = it },
                            label = { Text("Amount ($editCurrency)") },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = M3_Primary,
                                unfocusedBorderColor = M3_OutlineVariant,
                                focusedLabelColor = M3_Primary,
                                unfocusedLabelColor = M3_OnSurfaceVariant,
                                focusedTextColor = M3_OnSurface,
                                unfocusedTextColor = M3_OnSurface
                            )
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = editCurrency,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Currency") },
                                trailingIcon = {
                                    IconButton(
                                        onClick = {
                                            currencyDropdownExpanded =
                                                !currencyDropdownExpanded
                                        }
                                    ) {
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        currencyDropdownExpanded = !currencyDropdownExpanded
                                    }
                            )
                            DropdownMenu(
                                expanded = currencyDropdownExpanded,
                                onDismissRequest = { currencyDropdownExpanded = false }
                            ) {
                                CurrencyScaleRegistry.supportedCodes.forEach { code ->
                                    DropdownMenuItem(
                                        text = { Text(code) },
                                        onClick = {
                                            editCurrency = code
                                            currencyDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))

                        Box(modifier = Modifier.fillMaxWidth()) {
                            val selectedAccount = state.accounts.firstOrNull {
                                it.id == editAccountId
                            }
                            OutlinedTextField(
                                value = selectedAccount?.name ?: "Select an existing account",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Owned account / card") },
                                trailingIcon = {
                                    IconButton(
                                        onClick = {
                                            accountDropdownExpanded = !accountDropdownExpanded
                                        }
                                    ) {
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        accountDropdownExpanded = !accountDropdownExpanded
                                    }
                            )
                            DropdownMenu(
                                expanded = accountDropdownExpanded,
                                onDismissRequest = { accountDropdownExpanded = false }
                            ) {
                                state.accounts.forEach { account ->
                                    DropdownMenuItem(
                                        text = { Text(account.name) },
                                        onClick = {
                                            editAccountId = account.id
                                            accountDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))

                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = if (editType == TransactionType.CREDIT) "Credit" else "Debit",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Transaction Type") },
                                trailingIcon = {
                                    IconButton(onClick = { typeDropdownExpanded = !typeDropdownExpanded }) {
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = null,
                                            tint = M3_OnSurfaceVariant
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().clickable { typeDropdownExpanded = !typeDropdownExpanded },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = M3_Primary,
                                    unfocusedBorderColor = M3_OutlineVariant,
                                    focusedLabelColor = M3_Primary,
                                    unfocusedLabelColor = M3_OnSurfaceVariant,
                                    focusedTextColor = M3_OnSurface,
                                    unfocusedTextColor = M3_OnSurface
                                )
                            )
                            DropdownMenu(
                                expanded = typeDropdownExpanded,
                                onDismissRequest = { typeDropdownExpanded = false },
                                modifier = Modifier
                                    .fillMaxWidth(0.85f)
                                    .background(M3_SurfaceContainerHigh)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Debit", color = M3_OnSurface) },
                                    onClick = {
                                        editType = TransactionType.DEBIT
                                        typeDropdownExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Credit", color = M3_OnSurface) },
                                    onClick = {
                                        editType = TransactionType.CREDIT
                                        typeDropdownExpanded = false
                                    }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(20.dp))

                        editError?.let { message ->
                            Text(
                                text = message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { isEditing = false },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = M3_OnSurfaceVariant
                                ),
                                border = BorderStroke(1.dp, M3_OutlineVariant)
                            ) {
                                Text("Cancel")
                            }
                            Button(
                                onClick = {
                                    editError = null
                                    viewModel.updateTransaction(
                                        id = tx.id,
                                        expectedRevisionId = tx.currentRevisionId,
                                        amountText = editAmount,
                                        currencyCode = editCurrency,
                                        merchant = editMerchant,
                                        type = editType,
                                        accountId = editAccountId,
                                        onResult = { saved ->
                                            if (saved) {
                                                isEditing = false
                                            } else {
                                                editError =
                                                    "Could not save this correction. " +
                                                    "Check the amount and account, then retry."
                                            }
                                        }
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = M3_Primary,
                                    contentColor = M3_OnPrimary
                                )
                            ) {
                                Text("Save Changes")
                            }
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        HorizontalDivider(color = M3_OutlineVariant.copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // SOURCE SMS Section
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Sms,
                            contentDescription = null,
                            tint = M3_OnSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "SOURCE SMS",
                            color = M3_OnSurfaceVariant,
                            style = AppTypography.eyebrow
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(M3_SurfaceContainerLowest, RoundedCornerShape(12.dp))
                            .border(BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.3f)), RoundedCornerShape(12.dp))
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(M3_SurfaceContainerLow)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "from  ",
                                color = M3_OnSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = tx.sender,
                                color = M3_OnPrimaryContainer,
                                style = AppTypography.accountCode,
                                modifier = Modifier
                                    .background(M3_PrimaryContainer, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = tx.rawMessage,
                            color = M3_OnSurfaceVariant,
                            style = AppTypography.monoBody,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }

        // ── Shared telemetry details bottom sheet ──
        val processingTarget = selectedProcessingTarget
        when (processingTarget) {
            null -> Unit
            is SmsProcessingTarget.Automatic,
            is SmsProcessingTarget.Historical -> {
                LaunchedEffect(processingTarget) {
                    selectedProcessingTarget = null
                }
            }
            else -> TransactionsManualTelemetrySheet(
                requestedTarget = processingTarget,
                stateFlow = viewModel.manualSyncTelemetry,
                viewModel = viewModel,
                onTargetExpired = { selectedProcessingTarget = null },
                onStop = { target ->
                    if (target is SmsProcessingTarget.ManualRecent) {
                        viewModel.stopManualSync(target)
                    }
                },
                onClose = {
                    viewModel.resetSyncState()
                    selectedProcessingTarget = null
                }
            )
        }
    }
}
}

@Composable
private fun TransactionsManualTelemetrySheet(
    requestedTarget: SmsProcessingTarget,
    stateFlow: StateFlow<HomeSyncState>,
    viewModel: TransactionsViewModel,
    onTargetExpired: () -> Unit,
    onStop: (SmsProcessingTarget) -> Unit,
    onClose: () -> Unit
) {
    val syncState by stateFlow.collectSensitiveManualState()
    val currentState = syncState ?: return

    val targetIsCurrent = when (requestedTarget) {
        is SmsProcessingTarget.Automatic -> false
        is SmsProcessingTarget.ManualRecent ->
            currentState.ownsManualProcessingTarget(requestedTarget)
        is SmsProcessingTarget.ManualResult ->
            currentState.queue.any {
                it.id == requestedTarget.candidateKey && it.status != "syncing"
            }
        is SmsProcessingTarget.Historical -> false
    }
    LaunchedEffect(
        requestedTarget,
        currentState.activeRunId,
        currentState.status,
        currentState.queue
    ) {
        if (!targetIsCurrent) onTargetExpired()
    }
    if (!targetIsCurrent) return

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
            currentState.activeRunId != target.runId -> SmsStopUiState.HIDDEN
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

internal fun shouldShowTransactionsEmptyState(
    hasTransactions: Boolean,
    hasSyncCard: Boolean,
    syncStatus: HomeSyncState.Status
): Boolean =
    !hasTransactions &&
        !hasSyncCard &&
        syncStatus != HomeSyncState.Status.SCANNING &&
        syncStatus != HomeSyncState.Status.SYNCING &&
        syncStatus != HomeSyncState.Status.CANCELLING


@Composable
fun DayHeaderRow(
    dateText: String,
    debitTotal: Double,
    creditTotal: Double
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(M3_Surface.copy(alpha = 0.95f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = dateText.uppercase(),
            color = M3_OnSurfaceVariant,
            style = MaterialTheme.typography.labelSmall
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (debitTotal > 0.0) {
                Text(
                    text = "↓ ₹${String.format("%,.0f", debitTotal)}",
                    color = M3_OnErrorContainer,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .background(M3_ErrorContainer, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            if (creditTotal > 0.0) {
                Text(
                    text = "↑ ₹${String.format("%,.0f", creditTotal)}",
                    color = M3_OnPosContainer,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .background(M3_PosContainer, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
fun TransactionItem(
    transaction: Transaction,
    shape: androidx.compose.ui.graphics.Shape,
    showDivider: Boolean,
    onClick: () -> Unit
) {
    val gradient = getAvatarGradient(transaction.merchant)
    val textColor = getAvatarTextColor(transaction.merchant)
    val icon = getMerchantIcon(transaction.merchant)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                            text = getInitials(transaction.merchant),
                            color = textColor,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = transaction.merchant,
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
                            text = getAccountShortLabel(transaction.accountLabel),
                            color = M3_OnSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (transaction.isEdited) {
                            Text(
                                text = "Corrected",
                                color = M3_OnPrimaryContainer,
                                style = AppTypography.eyebrow,
                                modifier = Modifier
                                    .background(M3_PrimaryContainer, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text(
                    text = (if (transaction.type == TransactionType.CREDIT) "+" else "−") + "₹${String.format("%,.2f", transaction.amount)}",
                    color = if (transaction.type == TransactionType.CREDIT) M3_Pos else M3_OnSurface,
                    style = AppTypography.amountCompact
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formatTime(transaction.date),
                    color = M3_OnSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = M3_OutlineVariant.copy(alpha = 0.2f)
            )
        }
    }
}

// ── Helpers ──

private fun formatDateKey(timestampMs: Long): String {
    val date = Date(timestampMs)
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return fmt.format(date)
}

private fun formatDayHeader(timestampMs: Long): String {
    val date = Date(timestampMs)
    val now = Date()
    val fmtDate = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
    val headerFmt = SimpleDateFormat("EEE, d MMM", Locale.getDefault())

    val isToday = fmtDate.format(date) == fmtDate.format(now)
    val formatted = headerFmt.format(date)
    return if (isToday) "Today · $formatted" else formatted
}

private fun formatTime(timestampMs: Long): String {
    val date = Date(timestampMs)
    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    return fmt.format(date)
}

private fun formatDate(timestampMs: Long): String {
    val date = Date(timestampMs)
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return fmt.format(date)
}

private fun formatFullDate(timestampMs: Long): String {
    val date = Date(timestampMs)
    val fmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    return fmt.format(date)
}

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

@Composable
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

private fun getAccountShortName(acc: Account): String {
    val bank = if (acc.bank == "Unknown Bank" || acc.bank == "Unknown") "Unknown Account" else acc.bank
    val name = acc.name
    val runs = Regex("\\d+").findAll(name).toList()
    val digits = if (runs.isNotEmpty()) runs.last().value.takeLast(4) else ""
    return if (digits.isNotEmpty()) "$bank $digits" else name
}

private fun getAccountColor(name: String): Color {
    val hash = name.hashCode()
    return when (Math.abs(hash) % 5) {
        0 -> Color(0xFFF2C94C)
        1 -> Color(0xFF27AE60)
        2 -> Color(0xFF2F80ED)
        3 -> Color(0xFF9B51E0)
        else -> Color(0xFFEB5757)
    }
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
