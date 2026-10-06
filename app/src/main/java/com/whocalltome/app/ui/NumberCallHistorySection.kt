package com.whocalltome.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import java.time.ZoneId

internal fun LazyListScope.numberCallHistorySection(
    history: NumberCallHistoryUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRetry: () -> Unit,
) {
    item(key = "history-header") {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle)
                    .semantics { stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто" }
                    .testTag("history-toggle").padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when {
                            history.isLoading -> "История звонков · Загружаем…"
                            history.error != null -> "История звонков · Недоступна"
                            else -> "История звонков · ${history.calls.size}"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(if (expanded) "▴" else "▾")
                }
                Text("По сохранённому журналу приложения", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (!expanded) return
    when {
        history.isLoading -> item(key = "history-loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        history.error != null -> item(key = "history-error") {
            Column {
                Text(history.error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text("Повторить загрузку истории") }
            }
        }
        history.calls.isEmpty() -> item(key = "history-empty") {
            Text("В сохранённом журнале нет звонков с этим номером", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> {
            val groups = groupCallsByDay(history.calls, System.currentTimeMillis(), ZoneId.systemDefault())
            groups.forEach { group ->
                item(key = "history-day-${group.title}") {
                    Text(group.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(group.calls, key = { "history-call-${it.id}" }, contentType = { "history-call" }) { call ->
                    CallRow(
                        call = call,
                        showHistoryDetails = true,
                        modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("history-call-${call.id}"),
                    )
                }
            }
        }
    }
}
