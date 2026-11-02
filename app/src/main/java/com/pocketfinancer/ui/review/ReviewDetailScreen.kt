package com.pocketfinancer.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketfinancer.data.repository.SmsReviewAction
import com.pocketfinancer.ui.smsprocessing.DecisionTraceTimeline
import org.json.JSONObject
import org.json.JSONArray

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewDetailScreen(
    reviewCaseId: String,
    onFinished: () -> Unit,
    viewModel: ReviewViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(reviewCaseId) { viewModel.loadDetails(reviewCaseId) }
    LaunchedEffect(state.actionCompleted) { if (state.actionCompleted) onFinished() }
    Scaffold(topBar = { TopAppBar(title = { Text("Review saved alert") }) }) { padding ->
        val details = state.details
        if (state.loading && details == null) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) { CircularProgressIndicator() }
            return@Scaffold
        }
        if (details == null) {
            Text(state.error ?: "Review is unavailable.", Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        if (
            details.operation.contractReleaseId == "native-integration-v4" ||
            details.operation.contractReleaseId in setOf("native-integration-v5", "native-integration-v6")
        ) {
            val reviewIsRenderable = if (
                details.operation.contractReleaseId == "native-integration-v4"
            ) {
                details.groundedProposal != null
            } else {
                details.retainedEvidence != null
            }
            if (reviewIsRenderable) {
                GroundedReviewContent(
                    details = details,
                    state = state,
                    padding = padding,
                    viewModel = viewModel
                )
            } else {
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("The on-device result could not be verified")
                    Text("Nothing can be saved from this result. Retry local extraction or mark the alert as not a transaction.")
                    OutlinedButton(
                        enabled = !state.loading,
                        onClick = {
                            viewModel.resolve(
                                SmsReviewAction.RETRY,
                                retryConfiguration = "current"
                            )
                        }
                    ) { Text("Retry extraction") }
                    TextButton(
                        enabled = !state.loading,
                        onClick = { viewModel.resolve(SmsReviewAction.REJECT) }
                    ) { Text("Not a transaction") }
                    state.error?.let { Text(it) }
                }
            }
            return@Scaffold
        }
        val proposal = remember(details.reconstructedResult?.semanticResultJson) {
            details.reconstructedResult?.semanticResultJson?.let(::JSONObject)
        }
        val draft = remember(details.reviewCase.draftJson) {
            details.reviewCase.draftJson?.let(::draftValues).orEmpty()
        }
        var amount by remember(details.reviewCase.draftJson, proposal) {
            mutableStateOf(
                draft["amount_minor_units"]?.toString()
                    ?: proposal?.takeIf { it.has("minor_units") }
                        ?.getLong("minor_units")?.toString().orEmpty()
            )
        }
        var currency by remember(details.reviewCase.draftJson, proposal) {
            mutableStateOf(draft["currency"] as? String ?: proposal?.optString("currency", "INR") ?: "INR")
        }
        var direction by remember(details.reviewCase.draftJson, proposal) {
            mutableStateOf(draft["direction"] as? String ?: proposal?.optString("direction", "debit") ?: "debit")
        }
        var counterparty by remember(details.reviewCase.draftJson, proposal) {
            mutableStateOf(
                draft["counterparty"] as? String
                    ?: proposal?.optString("counterparty_evidence")?.takeUnless { it == "null" }
                    ?: "Unspecified counterparty"
            )
        }
        var occurredAt by remember(details.reviewCase.draftJson, proposal) {
            mutableStateOf(
                draft["occurred_at_epoch_ms"]?.toString()
                    ?: proposal?.takeIf { it.has("occurred_at_epoch_ms") && !it.isNull("occurred_at_epoch_ms") }
                    ?.getLong("occurred_at_epoch_ms")?.toString().orEmpty()
            )
        }
        var accountId by remember(details.reviewCase.draftJson) {
            mutableStateOf(draft["account_id"] as? String ?: "")
        }
        var accountMenu by remember { mutableStateOf(false) }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(details.source.rawMessage)
            Text("Original alert evidence is stored locally and protected.")
            DecisionTraceTimeline(details.trace)
            OutlinedTextField(amount, { amount = it }, label = { Text("Exact amount (minor units)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(currency, { currency = it.uppercase() }, label = { Text("Currency") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(direction, { direction = it.lowercase() }, label = { Text("Direction") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(counterparty, { counterparty = it }, label = { Text("Counterparty") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(occurredAt, { occurredAt = it }, label = { Text("Transaction time (epoch ms)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            ExposedDropdownMenuBox(expanded = accountMenu, onExpandedChange = { accountMenu = it }) {
                OutlinedTextField(
                    details.accounts.firstOrNull { it.id == accountId }?.name.orEmpty(), {},
                    readOnly = true, label = { Text("Owned account") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(accountMenu) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                    details.accounts.forEach { account ->
                        DropdownMenuItem(text = { Text(account.name) }, onClick = {
                            accountId = account.id
                            accountMenu = false
                        })
                    }
                }
            }
            Button(
                onClick = {
                    viewModel.resolve(
                        SmsReviewAction.CORRECT,
                        ReviewCorrectionInput(amount, currency, direction, counterparty, accountId, occurredAt)
                    )
                },
                enabled = !state.loading && amount.toLongOrNull()?.let { it > 0 } == true &&
                    currency.isNotBlank() && direction in setOf("debit", "credit") &&
                    counterparty.isNotBlank() && accountId.isNotBlank() && occurredAt.toLongOrNull() != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Apply correction and add transaction") }
            OutlinedButton(
                onClick = {
                    viewModel.resolve(
                        SmsReviewAction.SAVE_DRAFT,
                        ReviewCorrectionInput(
                            amount,
                            currency,
                            direction,
                            counterparty,
                            accountId,
                            occurredAt
                        )
                    )
                },
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save draft") }
            OutlinedButton(
                onClick = { viewModel.resolve(SmsReviewAction.CONFIRM) },
                enabled = !state.loading && details.reconstructedResult != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Confirm grounded proposal") }
            TextButton(enabled = !state.loading, onClick = { viewModel.resolve(SmsReviewAction.RETRY, retryConfiguration = "original") }) {
                Text("Retry with original settings")
            }
            TextButton(enabled = !state.loading, onClick = { viewModel.resolve(SmsReviewAction.RETRY, retryConfiguration = "current") }) {
                Text("Retry with current settings")
            }
            TextButton(enabled = !state.loading, onClick = { viewModel.resolve(SmsReviewAction.REJECT) }) { Text("Reject alert") }
            state.error?.let { Text(it) }
        }
    }
}

private fun draftValues(json: String): Map<String, Any> = runCatching {
    val array = JSONArray(json)
    buildMap {
        repeat(array.length()) { index ->
            val correction = array.getJSONObject(index)
            if (!correction.isNull("new_value")) {
                put(correction.getString("field"), correction.get("new_value"))
            }
        }
    }
}.getOrDefault(emptyMap())
