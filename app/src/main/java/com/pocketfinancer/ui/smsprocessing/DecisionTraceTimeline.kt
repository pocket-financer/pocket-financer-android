package com.pocketfinancer.ui.smsprocessing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pocketfinancer.data.db.entity.SmsProcessingTraceEventEntity

@Composable
fun DecisionTraceTimeline(events: List<SmsProcessingTraceEventEntity>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Decision trace",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
        events.sortedBy { it.sequence }.forEach { event ->
            Surface(
                tonalElevation = 1.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("${event.sequence + 1}", style = MaterialTheme.typography.labelLarge)
                    Column {
                        Text(
                            event.stage.replace('_', ' ').replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            event.status.replace('_', ' '),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (event.reasonCodesJson != "[]") {
                            Text(
                                event.reasonCodesJson,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
