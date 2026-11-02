package com.pocketfinancer.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pocketfinancer.data.repository.CurrencyScaleRegistry
import com.pocketfinancer.data.repository.SmsFieldCorrection
import com.pocketfinancer.data.repository.SmsFieldGroundingClassification
import com.pocketfinancer.data.repository.SmsReviewAction
import com.pocketfinancer.data.repository.SmsReviewDetails
import com.pocketfinancer.data.repository.SmsReviewGrounding
import com.pocketfinancer.data.repository.SmsReviewProposal
import com.pocketfinancer.data.repository.SmsReviewRetainedEvidence
import com.pocketfinancer.data.repository.SmsReviewSourceSpan
import java.math.BigDecimal
import java.text.DateFormat
import java.util.Date
import org.json.JSONObject

@Composable
internal fun GroundedReviewContent(
    details: SmsReviewDetails,
    state: ReviewUiState,
    padding: PaddingValues,
    viewModel: ReviewViewModel
) {
    if (details.operation.contractReleaseId in setOf("native-integration-v5", "native-integration-v6")) {
        AutomaticPolicyReviewContent(details, state, padding, viewModel)
        return
    }
    val original = requireNotNull(details.groundedProposal)
    val initial = details.draftProposal ?: original
    var working by remember(details.reviewCase.revision) { mutableStateOf(initial) }
    var pendingSelection by remember(details.reviewCase.revision) { mutableStateOf<SmsReviewSourceSpan?>(null) }
    var assignmentError by remember(details.reviewCase.revision) { mutableStateOf<String?>(null) }
    val selections = remember(details.reviewCase.revision) {
        mutableStateMapOf<ReviewField, SmsReviewSourceSpan?>().apply {
            put(ReviewField.AMOUNT, initial.amountSpan)
            put(ReviewField.DIRECTION, initial.directionSpan)
            put(ReviewField.ACCOUNT, initial.accountSpan)
            put(ReviewField.COUNTERPARTY, initial.counterpartySpan)
            details.draftCorrections.filter { it.evidenceJson == null }.forEach { correction ->
                ReviewField.entries.firstOrNull {
                    it.name.equals(correction.field, ignoreCase = true)
                }?.let { put(it, null) }
            }
        }
    }
    val source = details.source.rawMessage
    val corrections = reviewCorrections(original, working, selections)
    val requiredReady = selections[ReviewField.AMOUNT] != null &&
        selections[ReviewField.DIRECTION] != null &&
        selections[ReviewField.ACCOUNT] != null
    val duplicateBlocked = working.duplicateStatus == "already_persisted"
    val accountBlocked = working.accountStatus == "ambiguous"
    val canConfirm = requiredReady && !duplicateBlocked && !accountBlocked && !state.loading

    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { pendingSelection = null; assignmentError = null },
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("${details.source.sender} · ${DateFormat.getDateTimeInstance().format(Date(working.receiptTimestampEpochMs))}")
        Text(reasonSummary(details))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            ReviewField.entries.forEach { field ->
                val selected = selections[field]
                AssistChip(
                    onClick = {
                        val span = pendingSelection
                        if (span == null) {
                            assignmentError = "Select wording in the message first."
                        } else {
                            val updated = updateSelection(field, span, working)
                            if (updated == null) {
                                assignmentError = "That wording cannot be used for this field."
                            } else {
                                working = updated
                                selections[field] = span
                                pendingSelection = null
                                assignmentError = null
                            }
                        }
                    },
                    label = { Text(field.label) },
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = if (selections[field] != null) {
                            ReviewHighlightTextColor
                        } else MaterialTheme.colorScheme.onSurface,
                        containerColor = if (selections[field] != null) {
                            field.highlightColor()
                        } else MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = if (selected == null) {
                            "${field.label}, missing"
                        } else {
                            "${field.label}, selected: ${selected.text}"
                        }
                    }
                )
            }
        }
        EvidenceSelectionText(
            source = source,
            selections = selections,
            pendingSelection = pendingSelection,
            onSelectionChanged = { pendingSelection = it; assignmentError = null }
        )
        Text("Select text in the message, then tap the field it belongs to.")
        assignmentError?.let { Text(it) }
        if (pendingSelection != null) {
            TextButton(onClick = { pendingSelection = null; assignmentError = null }) {
                Text("Cancel selection")
            }
        }
        ReviewFieldRow(
            ReviewField.AMOUNT,
            if (selections[ReviewField.AMOUNT] != null) {
                formatReviewAmount(working.amountMinorUnits, working.currency).removePrefix("Amount: ")
            } else "Unassigned",
            selections[ReviewField.AMOUNT] != null,
            onClear = { selections[ReviewField.AMOUNT] = null }
        )
        ReviewFieldRow(
            ReviewField.DIRECTION,
            if (selections[ReviewField.DIRECTION] != null) {
                working.direction.replaceFirstChar(Char::uppercase)
            } else "Unassigned",
            selections[ReviewField.DIRECTION] != null,
            onClear = { selections[ReviewField.DIRECTION] = null }
        )
        ReviewFieldRow(
            ReviewField.ACCOUNT,
            if (selections[ReviewField.ACCOUNT] != null) working.accountReference else "Unassigned",
            selections[ReviewField.ACCOUNT] != null,
            onClear = { selections[ReviewField.ACCOUNT] = null }
        )
        ReviewFieldRow(
            ReviewField.COUNTERPARTY,
            if (selections[ReviewField.COUNTERPARTY] != null) {
                working.counterparty ?: "Not supplied"
            } else "Not supplied",
            selections[ReviewField.COUNTERPARTY] != null,
            onClear = { selections[ReviewField.COUNTERPARTY] = null }
        )
        if (duplicateBlocked) Text("This source event was already saved. Confirmation is blocked.")
        if (accountBlocked) Text("More than one owned account matches this evidence. Confirmation is blocked.")
        Button(
            onClick = {
                viewModel.resolveGrounded(
                    if (corrections.isEmpty()) SmsReviewAction.CONFIRM else SmsReviewAction.CORRECT,
                    corrections
                )
            },
            enabled = canConfirm,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Confirm transaction") }
        OutlinedButton(
            onClick = { viewModel.resolveGrounded(SmsReviewAction.SAVE_DRAFT, corrections) },
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save draft") }
        TextButton(
            enabled = !state.loading,
            onClick = { viewModel.resolve(SmsReviewAction.RETRY, retryConfiguration = "current") }
        ) { Text("Retry extraction") }
        TextButton(
            enabled = !state.loading,
            onClick = { viewModel.resolveGrounded(SmsReviewAction.REJECT, emptyList()) }
        ) { Text("Not a transaction") }
        state.error?.let { Text(it) }
    }
}

@Composable
private fun AutomaticPolicyReviewContent(
    details: SmsReviewDetails,
    state: ReviewUiState,
    padding: PaddingValues,
    viewModel: ReviewViewModel
) {
    val retained = requireNotNull(details.retainedEvidence)
    val seed = remember(details.reviewCase.revision, details.reviewCase.draftJson) {
        automaticReviewSeed(details, retained)
    }
    var working by remember(details.reviewCase.revision) { mutableStateOf(seed.working) }
    var pendingSelection by remember(details.reviewCase.revision) { mutableStateOf<SmsReviewSourceSpan?>(null) }
    var assignmentError by remember(details.reviewCase.revision) { mutableStateOf<String?>(null) }
    var showTechnicalDetails by remember(details.reviewCase.revision) { mutableStateOf(false) }
    val selections = remember(details.reviewCase.revision) {
        mutableStateMapOf<ReviewField, SmsReviewSourceSpan?>().apply {
            putAll(seed.selections)
        }
    }
    val source = details.source.rawMessage
    val duplicateBlocked = details.groundedProposal?.duplicateStatus == "already_persisted" ||
        details.reviewCase.reasonCodesJson.contains("duplicate_already_persisted")
    val missing = buildList {
        if (working.amountMinorUnits?.let { it > 0 } != true ||
            selections[ReviewField.AMOUNT] == null
        ) add("amount")
        if (working.direction !in setOf("debit", "credit")) add("direction")
        if (working.accountReference == null || selections[ReviewField.ACCOUNT] == null) {
            add("account")
        }
    }
    val canConfirm = missing.isEmpty() && !duplicateBlocked && !state.loading
    val corrections = automaticReviewCorrections(working, selections)
    fun assignQuickChoice(field: ReviewField, span: SmsReviewSourceSpan) {
        updateAutomaticSelection(field, span, working)?.let { updated ->
            working = updated
            selections[field] = span
            pendingSelection = null
            assignmentError = null
        }
    }

    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { pendingSelection = null; assignmentError = null },
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "${details.source.sender} · " +
                DateFormat.getDateTimeInstance().format(Date(retained.receiptTimestampEpochMs))
        )
        TextButton(onClick = { showTechnicalDetails = !showTechnicalDetails }) {
            Text(if (showTechnicalDetails) "Hide processing details" else "Why review?")
        }
        if (showTechnicalDetails) {
            Text(reasonSummary(details))
            Text("Processing reached: ${retained.furthestStage.replace('_', ' ')}")
            if (retained.analyzerSuggestions.isNotEmpty()) {
                Text("Analyzer hints (not model output)")
                retained.analyzerSuggestions.distinctBy { it.kind to it.summary }.forEach {
                    Text("${it.kind.replaceFirstChar(Char::uppercase)}: ${it.summary}")
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            ReviewField.entries.forEach { field ->
                val assigned = when (field) {
                    ReviewField.AMOUNT -> working.amountMinorUnits != null && selections[field] != null
                    ReviewField.DIRECTION -> working.direction != null
                    ReviewField.ACCOUNT -> working.accountReference != null && selections[field] != null
                    ReviewField.COUNTERPARTY -> working.counterparty != null
                }
                AssistChip(
                    onClick = {
                        val span = pendingSelection
                        if (span == null) {
                            assignmentError = "Select wording in the message first."
                        } else {
                            val updated = updateAutomaticSelection(field, span, working)
                            if (updated == null) {
                                assignmentError = "That wording cannot be used for this field."
                            } else {
                                working = updated
                                selections[field] = span
                                pendingSelection = null
                                assignmentError = null
                            }
                        }
                    },
                    label = { Text(field.label) },
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = if (selections[field] != null) {
                            ReviewHighlightTextColor
                        } else MaterialTheme.colorScheme.onSurface,
                        containerColor = if (selections[field] != null) {
                            field.highlightColor()
                        } else MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = if (assigned) {
                            "${field.label}, assigned"
                        } else {
                            "${field.label}, unassigned"
                        }
                    }
                )
            }
        }
        EvidenceSelectionText(
            source = source,
            selections = selections,
            pendingSelection = pendingSelection,
            onSelectionChanged = { pendingSelection = it; assignmentError = null }
        )
        Text("Select text in the message, then tap the field it belongs to.")
        assignmentError?.let { Text(it) }
        if (pendingSelection != null) {
            TextButton(onClick = { pendingSelection = null; assignmentError = null }) {
                Text("Cancel selection")
            }
        }
        ReviewFieldRow(
            ReviewField.AMOUNT,
            if (selections[ReviewField.AMOUNT] != null && working.amountMinorUnits != null) {
                formatReviewAmount(working.amountMinorUnits, working.currency).removePrefix("Amount: ")
            } else "Unassigned",
            selections[ReviewField.AMOUNT] != null,
            onClear = {
                working = working.clear(ReviewField.AMOUNT)
                selections[ReviewField.AMOUNT] = null
            }
        )
        if (selections[ReviewField.AMOUNT] == null) {
            QuickSourceChoice(ReviewField.AMOUNT, retained, source, working) { span ->
                assignQuickChoice(ReviewField.AMOUNT, span)
            }
        }
        ReviewFieldRow(
            ReviewField.DIRECTION,
            working.direction?.replaceFirstChar(Char::uppercase) ?: "Unassigned",
            working.direction != null,
            onClear = {
                working = working.clear(ReviewField.DIRECTION)
                selections[ReviewField.DIRECTION] = null
            },
            highlighted = selections[ReviewField.DIRECTION] != null
        )
        if (selections[ReviewField.DIRECTION] == null) {
            QuickSourceChoice(ReviewField.DIRECTION, retained, source, working) { span ->
                assignQuickChoice(ReviewField.DIRECTION, span)
            }
        }
        if (selections[ReviewField.DIRECTION] == null) {
            Text("If the message has no direction wording, choose:")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { working = working.copy(direction = "debit") }) {
                    Text("Debit")
                }
                OutlinedButton(onClick = { working = working.copy(direction = "credit") }) {
                    Text("Credit")
                }
            }
        }
        ReviewFieldRow(
            ReviewField.ACCOUNT,
            if (selections[ReviewField.ACCOUNT] != null) {
                working.accountReference ?: "Unassigned"
            } else "Unassigned",
            selections[ReviewField.ACCOUNT] != null,
            onClear = {
                working = working.clear(ReviewField.ACCOUNT)
                selections[ReviewField.ACCOUNT] = null
            }
        )
        if (selections[ReviewField.ACCOUNT] == null) {
            QuickSourceChoice(ReviewField.ACCOUNT, retained, source, working) { span ->
                assignQuickChoice(ReviewField.ACCOUNT, span)
            }
        }
        ReviewFieldRow(
            ReviewField.COUNTERPARTY,
            working.counterparty ?: "Not supplied",
            working.counterparty != null,
            onClear = {
                working = working.clear(ReviewField.COUNTERPARTY)
                selections[ReviewField.COUNTERPARTY] = null
            },
            highlighted = selections[ReviewField.COUNTERPARTY] != null
        )
        if (selections[ReviewField.COUNTERPARTY] == null) {
            QuickSourceChoice(ReviewField.COUNTERPARTY, retained, source, working) { span ->
                assignQuickChoice(ReviewField.COUNTERPARTY, span)
            }
        }
        if (missing.isNotEmpty()) {
            Text("To confirm, assign ${missing.joinToString(" and ")}.")
        }

        if (duplicateBlocked) {
            Text("This source event was already saved. Confirmation is blocked.")
        }
        Button(
            onClick = {
                viewModel.resolveGrounded(SmsReviewAction.CORRECT, corrections)
            },
            enabled = canConfirm,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Confirm transaction") }
        OutlinedButton(
            onClick = { viewModel.resolveGrounded(SmsReviewAction.SAVE_DRAFT, corrections) },
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save draft") }
        TextButton(
            enabled = !state.loading,
            onClick = { viewModel.resolve(SmsReviewAction.RETRY, retryConfiguration = "current") }
        ) { Text("Retry extraction") }
        TextButton(
            enabled = !state.loading,
            onClick = { viewModel.resolveGrounded(SmsReviewAction.REJECT, emptyList()) }
        ) { Text("Not a transaction") }
        state.error?.let { Text(it) }
    }
}

@Composable
private fun QuickSourceChoice(
    field: ReviewField,
    retained: SmsReviewRetainedEvidence,
    source: String,
    working: AutomaticReviewWorking,
    onAssign: (SmsReviewSourceSpan) -> Unit
) {
    val candidates = buildList {
        retained.slmFields[field.wireName]?.sourceSpan?.let(::add)
        if (field != ReviewField.COUNTERPARTY) {
            retained.analyzerSuggestions.filter { it.kind == field.wireName }
                .mapNotNullTo(this) { it.sourceSpan }
        }
    }.filter { span ->
        span.text.length <= 40 &&
            SmsReviewGrounding.utf16Range(source, span) != null &&
            updateAutomaticSelection(field, span, working) != null
    }.distinctBy { span ->
        when (field) {
            ReviewField.AMOUNT ->
                runCatching { SmsReviewGrounding.minorUnits(span.text, working.currency) }.getOrNull()
            ReviewField.DIRECTION -> SmsReviewGrounding.directionFrom(span.text)
            ReviewField.ACCOUNT -> SmsReviewGrounding.normalizeAccount(span.text)
            ReviewField.COUNTERPARTY -> SmsReviewGrounding.normalizeText(span.text)
        }
    }.take(2)
    if (candidates.isEmpty()) return

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Use text:")
        candidates.forEach { span ->
            AssistChip(onClick = { onAssign(span) }, label = { Text("“${span.text}”") })
        }
    }
}

@Composable
private fun ReviewFieldRow(
    field: ReviewField,
    value: String,
    assigned: Boolean,
    onClear: () -> Unit,
    highlighted: Boolean = assigned
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${field.label}: $value",
            Modifier.weight(1f)
                .background(
                    if (highlighted) field.highlightColor() else MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(8.dp)
                )
                .padding(8.dp),
            color = if (highlighted) ReviewHighlightTextColor else MaterialTheme.colorScheme.onSurface
        )
        if (assigned) TextButton(onClick = onClear) { Text("Clear") }
    }
}
internal fun formatReviewAmount(minorUnits: Long?, currency: String): String {
    val amount = minorUnits ?: return "Amount: Unassigned"
    val scale = CurrencyScaleRegistry.scale(currency) ?: return "Amount: Unassigned"
    val decimal = BigDecimal.valueOf(amount).movePointLeft(scale).setScale(scale)
    return "Amount: ${currency.uppercase()} ${decimal.toPlainString()}"
}
private fun updateSelection(
    field: ReviewField,
    span: SmsReviewSourceSpan,
    current: SmsReviewProposal
): SmsReviewProposal? = runCatching {
    when (field) {
        ReviewField.AMOUNT -> current.copy(
            amountMinorUnits = SmsReviewGrounding.minorUnits(span.text, current.currency),
            amountSpan = span
        )
        ReviewField.DIRECTION -> current.copy(
            direction = requireNotNull(SmsReviewGrounding.directionFrom(span.text)),
            directionSpan = span
        )
        ReviewField.ACCOUNT -> current.copy(
            accountReference = SmsReviewGrounding.normalizeAccount(span.text).also { require(it.isNotBlank()) },
            accountSpan = span,
            accountStatus = "unresolved",
            resolvedAccountId = null
        )
        ReviewField.COUNTERPARTY -> current.copy(
            counterparty = SmsReviewGrounding.normalizeText(span.text).also { require(it.isNotBlank()) },
            counterpartySpan = span
        )
    }
}.getOrNull()

private data class AutomaticReviewWorking(
    val amountMinorUnits: Long?,
    val currency: String,
    val direction: String?,
    val accountReference: String?,
    val counterparty: String?
)

private data class AutomaticReviewSeed(
    val working: AutomaticReviewWorking,
    val selections: Map<ReviewField, SmsReviewSourceSpan?>
)

private fun automaticReviewSeed(
    details: SmsReviewDetails,
    retained: SmsReviewRetainedEvidence
): AutomaticReviewSeed {
    val proposal = details.groundedProposal
    var working = AutomaticReviewWorking(
        amountMinorUnits = proposal?.amountMinorUnits,
        currency = proposal?.currency ?: retained.primaryCurrency,
        direction = proposal?.direction,
        accountReference = proposal?.accountReference,
        counterparty = proposal?.counterparty
    )
    val selections = mutableMapOf<ReviewField, SmsReviewSourceSpan?>(
        ReviewField.AMOUNT to proposal?.amountSpan,
        ReviewField.DIRECTION to proposal?.directionSpan,
        ReviewField.ACCOUNT to proposal?.accountSpan,
        ReviewField.COUNTERPARTY to proposal?.counterpartySpan
    )
    ReviewField.entries.forEach { field ->
        retained.slmFields[field.wireName]?.let { evidence ->
            if (evidence.validationState == "valid") {
                selections[field] = evidence.sourceSpan
                working = working.fromRetained(field, evidence.normalizedValueJson)
            }
        }
    }
    details.draftCorrections.forEach { correction ->
        when (correction.field) {
            "amount" -> {
                selections[ReviewField.AMOUNT] = correction.span(details.source.rawMessage)
                working = if (correction.newValueJson == "null") {
                    working.copy(amountMinorUnits = null)
                } else {
                    val value = JSONObject(correction.newValueJson)
                    working.copy(
                        amountMinorUnits = value.optLong("minor_units").takeIf { it > 0 },
                        currency = value.optString("currency", working.currency).uppercase()
                    )
                }
            }
            "direction" -> {
                selections[ReviewField.DIRECTION] = correction.span(details.source.rawMessage)
                working = working.copy(
                    direction = correction.newValueJson.stringLiteral()
                        ?.takeIf { it in setOf("debit", "credit") }
                )
            }
            "account" -> {
                selections[ReviewField.ACCOUNT] = correction.span(details.source.rawMessage)
                working = if (correction.newValueJson == "null") {
                    working.copy(accountReference = null)
                } else {
                    working.copy(
                        accountReference = JSONObject(correction.newValueJson)
                            .optString("reference").takeIf(String::isNotBlank)
                    )
                }
            }
            "counterparty" -> {
                selections[ReviewField.COUNTERPARTY] = correction.span(details.source.rawMessage)
                working = working.copy(counterparty = correction.newValueJson.stringLiteral())
            }
        }
    }
    return AutomaticReviewSeed(working, selections)
}

private fun updateAutomaticSelection(
    field: ReviewField,
    span: SmsReviewSourceSpan,
    current: AutomaticReviewWorking
): AutomaticReviewWorking? = runCatching {
    when (field) {
        ReviewField.AMOUNT -> current.copy(
            amountMinorUnits = SmsReviewGrounding.minorUnits(span.text, current.currency)
        )
        ReviewField.DIRECTION -> current.copy(
            direction = requireNotNull(SmsReviewGrounding.directionFrom(span.text))
        )
        ReviewField.ACCOUNT -> current.copy(
            accountReference = SmsReviewGrounding.normalizeAccount(span.text)
                .also { require(it.isNotBlank()) }
        )
        ReviewField.COUNTERPARTY -> current.copy(
            counterparty = SmsReviewGrounding.normalizeText(span.text)
                .also { require(it.isNotBlank()) }
        )
    }
}.getOrNull()

private fun AutomaticReviewWorking.clear(field: ReviewField): AutomaticReviewWorking = when (field) {
    ReviewField.AMOUNT -> copy(amountMinorUnits = null)
    ReviewField.DIRECTION -> copy(direction = null)
    ReviewField.ACCOUNT -> copy(accountReference = null)
    ReviewField.COUNTERPARTY -> copy(counterparty = null)
}

private fun AutomaticReviewWorking.fromRetained(
    field: ReviewField,
    normalizedValueJson: String?
): AutomaticReviewWorking = runCatching {
    when (field) {
        ReviewField.AMOUNT -> {
            val value = JSONObject(requireNotNull(normalizedValueJson))
            copy(
                amountMinorUnits = value.getLong("minor_units"),
                currency = value.getString("currency").uppercase()
            )
        }
        ReviewField.DIRECTION -> copy(
            direction = requireNotNull(normalizedValueJson.stringLiteral())
                .also { require(it in setOf("debit", "credit")) }
        )
        ReviewField.ACCOUNT -> copy(
            accountReference = requireNotNull(normalizedValueJson.stringLiteral())
                .takeIf(String::isNotBlank)
        )
        ReviewField.COUNTERPARTY -> copy(
            counterparty = normalizedValueJson.stringLiteral()?.takeIf(String::isNotBlank)
        )
    }
}.getOrElse { clear(field) }

private fun automaticReviewCorrections(
    working: AutomaticReviewWorking,
    selections: Map<ReviewField, SmsReviewSourceSpan?>
): List<SmsFieldCorrection> = buildList {
    fun add(
        field: String,
        span: SmsReviewSourceSpan?,
        value: String,
        classification: SmsFieldGroundingClassification =
            SmsFieldGroundingClassification.SUPPLIED_SOURCE_SUPPORTED_CANDIDATE_MISS
    ) {
        add(
            SmsFieldCorrection(
                field = field,
                classification = classification,
                previousRevisionId = null,
                candidateId = null,
                evidenceJson = span?.let(::spanJson),
                newValueJson = value
            )
        )
    }
    val amountSpan = selections[ReviewField.AMOUNT]
    add(
        "amount",
        amountSpan,
        if (working.amountMinorUnits == null || amountSpan == null) {
            "null"
        } else {
            JSONObject()
                .put("minor_units", working.amountMinorUnits)
                .put("currency", working.currency)
                .toString()
        }
    )
    val directionSpan = selections[ReviewField.DIRECTION]
    add(
        "direction",
        directionSpan,
        working.direction?.let(JSONObject::quote) ?: "null",
        if (directionSpan == null) {
            SmsFieldGroundingClassification.SUPPLIED_MANUAL_UNGROUNDED_VALUE
        } else {
            SmsFieldGroundingClassification.SUPPLIED_SOURCE_SUPPORTED_CANDIDATE_MISS
        }
    )
    val accountSpan = selections[ReviewField.ACCOUNT]
    if (accountSpan != null && working.accountReference != null) {
        add(
            "account",
            accountSpan,
            JSONObject().put("reference", working.accountReference).toString()
        )
    }
    add(
        "account_id",
        null,
        "null",
        SmsFieldGroundingClassification.SUPPLIED_MANUAL_UNGROUNDED_VALUE
    )
    val counterpartySpan = selections[ReviewField.COUNTERPARTY]
    add(
        "counterparty",
        counterpartySpan,
        if (counterpartySpan != null && working.counterparty != null) {
            JSONObject.quote(working.counterparty)
        } else {
            "null"
        }
    )
}

private val ReviewField.wireName: String
    get() = name.lowercase()

private fun SmsFieldCorrection.span(source: String): SmsReviewSourceSpan? =
    evidenceJson?.let { json ->
        runCatching {
            val value = JSONObject(json)
            SmsReviewGrounding.span(
                source,
                value.getInt("start_scalar"),
                value.getInt("end_scalar")
            ).also { require(it.text == value.getString("text")) }
        }.getOrNull()
    }

private fun String?.stringLiteral(): String? {
    if (this == null || this == "null") return null
    return runCatching { JSONObject("{\"value\":$this}").getString("value") }.getOrNull()
}

private fun reviewCorrections(
    original: SmsReviewProposal,
    working: SmsReviewProposal,
    selections: Map<ReviewField, SmsReviewSourceSpan?>
): List<SmsFieldCorrection> = buildList {
    fun add(field: String, span: SmsReviewSourceSpan?, value: String) {
        add(
            SmsFieldCorrection(
                field = field,
                classification = SmsFieldGroundingClassification.SUPPLIED_SOURCE_SUPPORTED_CANDIDATE_MISS,
                previousRevisionId = null,
                candidateId = null,
                evidenceJson = span?.let(::spanJson),
                newValueJson = value
            )
        )
    }
    val amountSpan = selections[ReviewField.AMOUNT]
    if (amountSpan == null || amountSpan != original.amountSpan ||
        working.amountMinorUnits != original.amountMinorUnits
    ) {
        add("amount", amountSpan, if (amountSpan == null) "null" else JSONObject()
            .put("minor_units", working.amountMinorUnits).put("currency", working.currency).toString())
    }
    val directionSpan = selections[ReviewField.DIRECTION]
    if (directionSpan == null || directionSpan != original.directionSpan ||
        working.direction != original.direction
    ) {
        add("direction", directionSpan, if (directionSpan == null) "null" else JSONObject.quote(working.direction))
    }
    val accountSpan = selections[ReviewField.ACCOUNT]
    if (accountSpan == null || accountSpan != original.accountSpan ||
        working.accountReference != original.accountReference
    ) {
        add("account", accountSpan, if (accountSpan == null) "null" else JSONObject()
            .put("reference", working.accountReference).toString())
    }
    val counterpartySpan = selections[ReviewField.COUNTERPARTY]
    if (counterpartySpan != original.counterpartySpan || working.counterparty != original.counterparty) {
        add(
            "counterparty",
            counterpartySpan,
            working.counterparty?.let(JSONObject::quote) ?: "null"
        )
    }
}

private fun spanJson(span: SmsReviewSourceSpan): String = JSONObject()
    .put("start_scalar", span.startScalar)
    .put("end_scalar", span.endScalar)
    .put("text", span.text)
    .toString()

private fun SmsReviewProposal.span(field: ReviewField): SmsReviewSourceSpan? = when (field) {
    ReviewField.AMOUNT -> amountSpan
    ReviewField.DIRECTION -> directionSpan
    ReviewField.ACCOUNT -> accountSpan
    ReviewField.COUNTERPARTY -> counterpartySpan
}

private fun SmsReviewProposal.resetField(
    field: ReviewField,
    original: SmsReviewProposal
): SmsReviewProposal = when (field) {
    ReviewField.AMOUNT -> copy(amountMinorUnits = original.amountMinorUnits, amountSpan = original.amountSpan)
    ReviewField.DIRECTION -> copy(direction = original.direction, directionSpan = original.directionSpan)
    ReviewField.ACCOUNT -> copy(
        accountReference = original.accountReference,
        accountSpan = original.accountSpan,
        accountStatus = original.accountStatus,
        resolvedAccountId = original.resolvedAccountId
    )
    ReviewField.COUNTERPARTY -> copy(
        counterparty = original.counterparty,
        counterpartySpan = original.counterpartySpan
    )
}

private fun reasonSummary(details: SmsReviewDetails): String {
    val reasons = details.reviewCase.reasonCodesJson
        .removePrefix("[").removeSuffix("]")
        .replace("\"", "")
        .split(',').map(String::trim).filter(String::isNotBlank)
    return reasons.firstOrNull()?.replace('_', ' ')?.replaceFirstChar(Char::uppercase)
        ?: "Review required"
}

private fun accountPreview(value: SmsReviewProposal, details: SmsReviewDetails): String = when {
    value.accountStatus == "ambiguous" -> "Account: ambiguous match"
    value.resolvedAccountId != null -> {
        val name = details.accounts.firstOrNull { it.id == value.resolvedAccountId }?.name
        "Account: ${name ?: "matched owned account"}"
    }
    value.accountStatus == "unresolved" ->
        "Account: aliases will be rechecked on confirmation; one match is reused, otherwise a new local account is created"
    else -> "Account: a new local account will be created only when you confirm"
}
