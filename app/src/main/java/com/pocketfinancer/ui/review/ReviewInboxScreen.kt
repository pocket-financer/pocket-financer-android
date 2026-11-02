package com.pocketfinancer.ui.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewInboxScreen(
    onOpenReview: (String) -> Unit,
    viewModel: ReviewViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadInbox() }
    Scaffold(topBar = { TopAppBar(title = { Text("Saved alert reviews") }) }) { padding ->
        when {
            state.loading -> Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) { CircularProgressIndicator() }
            state.error != null -> Text(
                state.error.orEmpty(),
                modifier = Modifier.padding(padding).padding(24.dp),
                color = MaterialTheme.colorScheme.error
            )
            state.cases.isEmpty() -> Text(
                "No alerts need review.",
                modifier = Modifier.padding(padding).padding(24.dp),
                style = MaterialTheme.typography.bodyLarge
            )
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(state.cases, key = { it.id }) { review ->
                    ListItem(
                        headlineContent = { Text("Alert needs review") },
                        supportingContent = {
                            Text(review.reasonCodesJson.replace("\",\"", " · ").trim('[', ']', '\"'))
                        },
                        trailingContent = { Text("Revision ${review.revision}") },
                        modifier = Modifier.fillMaxWidth().clickable { onOpenReview(review.id) }
                    )
                }
            }
        }
    }
}
