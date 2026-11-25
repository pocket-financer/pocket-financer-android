package com.pocketfinancer.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketfinancer.data.db.entity.SmsReviewCaseEntity
import com.pocketfinancer.data.repository.SmsFieldCorrection
import com.pocketfinancer.data.repository.SmsFieldGroundingClassification
import com.pocketfinancer.data.repository.SmsReviewAction
import com.pocketfinancer.data.repository.SmsReviewCommand
import com.pocketfinancer.data.repository.SmsReviewDetails
import com.pocketfinancer.data.repository.SmsReviewRepository
import com.pocketfinancer.pipeline.PipelineService
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

data class ReviewUiState(
    val loading: Boolean = false,
    val cases: List<SmsReviewCaseEntity> = emptyList(),
    val details: SmsReviewDetails? = null,
    val error: String? = null,
    val actionCompleted: Boolean = false
)

data class ReviewCorrectionInput(
    val minorUnits: String,
    val currency: String,
    val direction: String,
    val counterparty: String,
    val accountId: String,
    val occurredAtEpochMs: String
)

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val repository: SmsReviewRepository,
    private val pipelineService: PipelineService
) : ViewModel() {
    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    fun loadInbox() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { repository.openCases() }
                .onSuccess { cases -> _state.update { it.copy(loading = false, cases = cases) } }
                .onFailure { _state.update { it.copy(loading = false, error = SAFE_ERROR) } }
        }
    }

    fun loadDetails(reviewCaseId: String) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, actionCompleted = false) }
            runCatching { repository.details(reviewCaseId) }
                .onSuccess { details -> _state.update { it.copy(loading = false, details = details) } }
                .onFailure { _state.update { it.copy(loading = false, error = SAFE_ERROR) } }
        }
    }

    fun resolve(
        action: SmsReviewAction,
        correction: ReviewCorrectionInput? = null,
        retryConfiguration: String? = null
    ) {
        if (_state.value.loading || _state.value.actionCompleted) return
        val details = _state.value.details ?: return
        val retryMode = retryConfiguration?.takeIf { it in setOf("original", "current") }
        if (action == SmsReviewAction.RETRY && retryMode == null) {
            _state.update { it.copy(error = SAFE_ERROR) }
            return
        }
        val corrections = if (
            action in setOf(SmsReviewAction.CORRECT, SmsReviewAction.SAVE_DRAFT) &&
            correction != null
        ) {
            listOf(
                field("amount_minor_units", correction.minorUnits.asJsonNumberOrNull()),
                field("currency", JSONObject.quote(correction.currency)),
                field("direction", JSONObject.quote(correction.direction)),
                field("counterparty", JSONObject.quote(correction.counterparty)),
                field(
                    "account_id",
                    correction.accountId.takeIf(String::isNotBlank)?.let(JSONObject::quote) ?: "null"
                ),
                field("occurred_at_epoch_ms", correction.occurredAtEpochMs.asJsonNumberOrNull())
            )
        } else {
            emptyList()
        }
        val command = SmsReviewCommand(
            actionId = UUID.randomUUID().toString(),
            reviewCaseId = details.reviewCase.id,
            expectedRevision = details.reviewCase.revision,
            action = action,
            corrections = corrections,
            retryConfiguration = retryMode
        )
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                repository.resolve(command, System.currentTimeMillis())
                if (action == SmsReviewAction.RETRY) {
                    when (pipelineService.retryReview(details.reviewCase.id, retryMode!!)) {
                        PipelineService.ProcessingResult.AwaitingConfiguration ->
                            error("Primary currency confirmation is required")
                        is PipelineService.ProcessingResult.Failure ->
                            error("Retry could not complete")
                        else -> Unit
                    }
                }
                if (action == SmsReviewAction.SAVE_DRAFT) {
                    repository.details(details.reviewCase.id)
                } else {
                    null
                }
            }
                .onSuccess { refreshed ->
                    _state.update {
                        it.copy(
                            loading = false,
                            details = refreshed ?: it.details,
                            actionCompleted = action != SmsReviewAction.SAVE_DRAFT
                        )
                    }
                    if (action != SmsReviewAction.SAVE_DRAFT) loadInbox()
                }
                .onFailure { _state.update { it.copy(loading = false, error = SAFE_ERROR) } }
        }
    }

    fun resolveGrounded(
        action: SmsReviewAction,
        corrections: List<SmsFieldCorrection>
    ) {
        if (_state.value.loading || _state.value.actionCompleted) return
        val details = _state.value.details ?: return
        if (action !in setOf(
                SmsReviewAction.CONFIRM,
                SmsReviewAction.CORRECT,
                SmsReviewAction.SAVE_DRAFT,
                SmsReviewAction.REJECT
            )
        ) {
            _state.update { it.copy(error = SAFE_ERROR) }
            return
        }
        val command = SmsReviewCommand(
            actionId = UUID.randomUUID().toString(),
            reviewCaseId = details.reviewCase.id,
            expectedRevision = details.reviewCase.revision,
            action = action,
            corrections = corrections
        )
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                repository.resolve(command, System.currentTimeMillis())
                if (action == SmsReviewAction.SAVE_DRAFT) {
                    repository.details(details.reviewCase.id)
                } else {
                    null
                }
            }.onSuccess { refreshed ->
                _state.update {
                    it.copy(
                        loading = false,
                        details = refreshed ?: it.details,
                        actionCompleted = action != SmsReviewAction.SAVE_DRAFT
                    )
                }
                if (action != SmsReviewAction.SAVE_DRAFT) loadInbox()
            }.onFailure {
                _state.update { it.copy(loading = false, error = SAFE_ERROR) }
            }
        }
    }

    private fun field(name: String, jsonValue: String) = SmsFieldCorrection(
        field = name,
        classification = SmsFieldGroundingClassification.SUPPLIED_MANUAL_UNGROUNDED_VALUE,
        previousRevisionId = null,
        candidateId = null,
        evidenceJson = null,
        newValueJson = jsonValue
    )

    private fun String.asJsonNumberOrNull(): String =
        trim().takeIf { it.toLongOrNull() != null } ?: "null"

    private companion object {
        const val SAFE_ERROR = "This review changed or local storage was unavailable. Reload and try again."
    }
}
