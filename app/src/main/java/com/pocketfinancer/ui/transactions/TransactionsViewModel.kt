package com.pocketfinancer.ui.transactions

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketfinancer.data.model.Transaction
import com.pocketfinancer.data.model.TransactionType
import com.pocketfinancer.data.model.Account
import com.pocketfinancer.data.repository.TransactionRepository
import com.pocketfinancer.data.repository.AccountRepository
import com.pocketfinancer.data.repository.SmsReviewRepository
import com.pocketfinancer.data.db.entity.SmsProcessingOperationEntity
import com.pocketfinancer.data.db.entity.SmsReviewCaseEntity
import com.pocketfinancer.ui.home.HomeSyncManager
import com.pocketfinancer.ui.smsprocessing.SmsProcessingTarget
import com.pocketfinancer.ui.home.HomeSyncState
import com.pocketfinancer.ui.home.SyncService
import com.pocketfinancer.ui.home.SyncSmsItem
import com.pocketfinancer.ui.home.hasDiagnosticSourceEvidence
import com.pocketfinancer.ui.home.manualSyncPreDispatchRejectionMessage
import com.pocketfinancer.ui.home.manualSyncPresentationState
import com.pocketfinancer.ui.home.sanitizedManualSyncState
import com.pocketfinancer.ui.home.showManualSyncStopRejectionFeedback
import com.pocketfinancer.pipeline.SmsFilterPipeline
import com.pocketfinancer.inference.SlmRuntimeOwner
import com.pocketfinancer.SlmAppFlowCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class SortOption {
    DATE_DESC,   // Newest First (Default)
    DATE_ASC,    // Oldest First
    AMOUNT_DESC, // Highest Amount First
    AMOUNT_ASC   // Lowest Amount First
}

data class TransactionsUiState(
    val transactions: List<Transaction> = emptyList(),
    val activeSegment: String = "All",
    val selectedTransaction: Transaction? = null,
    val accounts: List<Account> = emptyList(),
    val selectedAccountId: String = "All",
    val syncState: HomeSyncState = HomeSyncState(),
    val searchQuery: String = "",
    val sortOption: SortOption = SortOption.DATE_DESC,
    val processingOperations: List<SmsProcessingOperationEntity> = emptyList(),
    val reviewCases: List<SmsReviewCaseEntity> = emptyList()
)

@HiltViewModel
class TransactionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val syncManager: HomeSyncManager,
    private val smsFilterPipeline: SmsFilterPipeline,
    private val appFlowCoordinator: SlmAppFlowCoordinator,
    private val smsReviewRepository: SmsReviewRepository
) : ViewModel() {

    private val _activeSegment = MutableStateFlow("All")
    val activeSegment: StateFlow<String> = _activeSegment.asStateFlow()

    private val _selectedTransaction = MutableStateFlow<Transaction?>(null)
    val selectedTransaction: StateFlow<Transaction?> = _selectedTransaction.asStateFlow()

    private val _selectedAccountId = MutableStateFlow("All")
    val selectedAccountId: StateFlow<String> = _selectedAccountId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortOption = MutableStateFlow(SortOption.DATE_DESC)
    val sortOption: StateFlow<SortOption> = _sortOption.asStateFlow()

    private val _processingOperations = MutableStateFlow<List<SmsProcessingOperationEntity>>(emptyList())
    private val _reviewCases = MutableStateFlow<List<SmsReviewCaseEntity>>(emptyList())

    init {
        refreshNativeWork()
    }

    /** Full evidence is collected only by an open telemetry sheet. */
    val manualSyncTelemetry: StateFlow<HomeSyncState> = syncManager.syncState

    /** Source-preserving, telemetry-free state for the visible screen. */
    val manualSyncPresentation = manualSyncPresentationState(
        syncManager.syncState
    )

    val manualSyncUiState = sanitizedManualSyncState(
        source = syncManager.syncState,
        scope = viewModelScope
    )

    val uiState: StateFlow<TransactionsUiState> = combine(
        transactionRepository.getAllByDateDesc(),
        _activeSegment,
        _selectedTransaction,
        accountRepository.getAll(),
        _selectedAccountId,
        manualSyncUiState,
        _searchQuery,
        _sortOption,
        _processingOperations,
        _reviewCases
    ) { flowsArray ->
        val txs = flowsArray[0] as List<Transaction>
        val segment = flowsArray[1] as String
        val selected = flowsArray[2] as Transaction?
        val accountsList = flowsArray[3] as List<Account>
        val selectedAccId = flowsArray[4] as String
        val syncState = flowsArray[5] as HomeSyncState
        val query = flowsArray[6] as String
        val sort = flowsArray[7] as SortOption
        val processing = flowsArray[8] as List<SmsProcessingOperationEntity>
        val reviews = flowsArray[9] as List<SmsReviewCaseEntity>

        val filteredBySegment = when (segment) {
            "Debits" -> txs.filter { it.type == TransactionType.DEBIT }
            "Credits" -> txs.filter { it.type == TransactionType.CREDIT }
            else -> txs
        }
        val filteredByAccount = if (selectedAccId == "All") {
            filteredBySegment
        } else {
            filteredBySegment.filter { it.accountId == selectedAccId }
        }

        val filteredBySearch = if (query.isBlank()) {
            filteredByAccount
        } else {
            filteredByAccount.filter { tx ->
                tx.merchant.contains(query, ignoreCase = true) ||
                tx.amount.toString().contains(query) ||
                (tx.accountLabel?.contains(query, ignoreCase = true) == true)
            }
        }

        val sortedTransactions = when (sort) {
            SortOption.DATE_DESC -> filteredBySearch.sortedByDescending { it.date }
            SortOption.DATE_ASC -> filteredBySearch.sortedBy { it.date }
            SortOption.AMOUNT_DESC -> filteredBySearch.sortedByDescending { it.amount }
            SortOption.AMOUNT_ASC -> filteredBySearch.sortedBy { it.amount }
        }

        TransactionsUiState(
            transactions = sortedTransactions,
            activeSegment = segment,
            selectedTransaction = selected,
            accounts = accountsList,
            selectedAccountId = selectedAccId,
            syncState = syncState,
            searchQuery = query,
            sortOption = sort,
            processingOperations = processing,
            reviewCases = reviews
        )
    }
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = TransactionsUiState()
    )

    fun updateSegment(segment: String) {
        _activeSegment.value = segment
    }

    fun selectTransaction(transaction: Transaction?) {
        _selectedTransaction.value = transaction
    }

    fun selectAccount(accountId: String) {
        _selectedAccountId.value = accountId
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateSortOption(option: SortOption) {
        _sortOption.value = option
    }

    fun refreshNativeWork() {
        viewModelScope.launch {
            runCatching {
                smsReviewRepository.activeOperations() to smsReviewRepository.openCases()
            }.onSuccess { (processing, reviews) ->
                _processingOperations.value = processing
                _reviewCases.value = reviews
            }
        }
    }

    fun resetSyncState() {
        syncManager.resetState()
    }

    fun stopManualSync(target: SmsProcessingTarget.ManualRecent) {
        manualSyncPreDispatchRejectionMessage(
            state = syncManager.syncState.value,
            target = target
        )?.let { message ->
            showManualSyncStopRejectionFeedback(
                context = context,
                message = message
            )
            return
        }
        val commandAccepted = try {
            SyncService.requestStop(context, target)
        } catch (_: RuntimeException) {
            false
        }
        if (!commandAccepted) {
            android.widget.Toast.makeText(
                context,
                "Could not request a stop. Please try again.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        // SyncService applies the exact run/candidate CAS on delivery. Do not
        // publish a run-only cancellation here: the candidate may advance
        // between this UI callback and the service command.
    }

    fun updateTransaction(
        id: String,
        expectedRevisionId: String?,
        amountText: String,
        currencyCode: String,
        merchant: String,
        type: TransactionType,
        accountId: String,
        onResult: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            val receipt = runCatching {
                withLedgerEditAdmission(appFlowCoordinator) {
                    transactionRepository.editProjection(
                        TransactionRepository.ProjectionEditCommand(
                            actionId = java.util.UUID.randomUUID().toString(),
                            transactionId = id,
                            expectedRevisionId = expectedRevisionId,
                            amountText = amountText,
                            currencyCode = currencyCode,
                            merchant = merchant,
                            type = type,
                            accountId = accountId
                        )
                    )
                }
            }.getOrNull()
            val updated = receipt?.transaction
            if (updated != null && _selectedTransaction.value?.id == id) {
                _selectedTransaction.value = updated
            }
            onResult(updated != null)
        }
    }

    fun getFilterLogs(item: SyncSmsItem): List<String> {
        if (!item.hasDiagnosticSourceEvidence()) {
            return listOf(SOURCE_EVIDENCE_UNAVAILABLE)
        }
        return smsFilterPipeline.filterWithDetails(item.sender, item.body).logs
    }

    fun getKvCacheLogs(item: SyncSmsItem): List<String> {
        if (!item.hasDiagnosticSourceEvidence()) {
            return listOf(SOURCE_EVIDENCE_UNAVAILABLE)
        }
        return listOf(
            "KV cache telemetry is captured from the exact runtime request.",
            "Historical transactions do not currently persist cache-hit diagnostics."
        )
    }

    fun getSlmPrompt(item: SyncSmsItem): String {
        if (!item.hasDiagnosticSourceEvidence()) {
            return SOURCE_EVIDENCE_UNAVAILABLE
        }
        return DECISION_TRACE_MESSAGE
    }

    private companion object {
        const val SOURCE_EVIDENCE_UNAVAILABLE =
            "Source evidence is unavailable after terminal processing."
        const val DECISION_TRACE_MESSAGE =
            "The legacy extraction prompt is no longer used. Open Saved alert reviews to inspect the durable Decision Trace."
    }
}

/**
 * Admits the complete financial write before it can create an account or
 * mutate a ledger row. Erase-all pauses this coordinator, cancels and joins
 * already-admitted edits, then rejects new edits until encrypted tables are
 * empty and the shell has been durably reset.
 */
internal suspend fun <T> withLedgerEditAdmission(
    coordinator: SlmAppFlowCoordinator,
    block: suspend () -> T
): T? {
    val lease = coordinator.tryEnter(SlmRuntimeOwner.LEDGER_EDIT) ?: return null
    return try {
        block()
    } finally {
        withContext(NonCancellable) {
            lease.release()
        }
    }
}
