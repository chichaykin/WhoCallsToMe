package com.whocalltome.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import com.whocalltome.app.data.db.CallRecordEntity
import java.time.ZoneId

internal fun LazyListScope.numberCallHistorySection(history: NumberCallHistoryUiState, onOpen: () -> Unit, onRetry: () -> Unit) {
    item(key = "history-preview") {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Последние звонки", style = MaterialTheme.typography.titleMedium)
                when {
                    history.isLoading -> Text("Загружаем историю…")
                    history.error != null -> {
                        Text("История недоступна")
                        TextButton(onClick = onRetry) { Text("Повторить") }
                    }
                    history.calls.isEmpty() -> Text("В сохранённом журнале нет звонков с этим номером")
                    else -> {
                        HistoryCallGroups(recentProfileCalls(history.calls))
                        if (history.calls.size > 3) TextButton(onClick = onOpen, modifier = Modifier.testTag("history-open")) {
                            Text("Вся история · ${history.calls.size}")
                        }
                    }
                }
            }
        }
    }
}

internal fun recentProfileCalls(calls: List<CallRecordEntity>): List<CallRecordEntity> =
    calls.sortedByDescending { it.eventAt }.take(3)

@Composable
internal fun FullNumberHistory(history: NumberCallHistoryUiState, number: String, listState: LazyListState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier, state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(formatDisplayNumber(number), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) }
        when {
            history.isLoading -> item { Text("Загружаем историю…", Modifier.padding(16.dp)) }
            history.error != null -> item { Column(Modifier.padding(16.dp)) {
                Text("История недоступна")
                TextButton(onClick = onRetry) { Text("Повторить") }
            } }
            history.calls.isEmpty() -> item { Text("В сохранённом журнале нет звонков с этим номером", Modifier.padding(16.dp)) }
            else -> {
                groupCallsByDay(history.calls, System.currentTimeMillis(), ZoneId.systemDefault()).forEach { group ->
                    item(key = "day-${group.title}") { Text(group.title, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium) }
                    items(group.calls, key = { "call-${it.id}" }) { call ->
                        CompactHistoryRow(call, Modifier.padding(horizontal = 16.dp).testTag("history-call-${call.id}"))
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryCallGroups(calls: List<CallRecordEntity>) {
    groupCallsByDay(calls, System.currentTimeMillis(), ZoneId.systemDefault()).forEach { group ->
        Text(group.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        group.calls.forEach { call -> CompactHistoryRow(call, Modifier.testTag("history-call-${call.id}")) }
    }
}

@Composable
internal fun CompactHistoryRow(call: CallRecordEntity, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CallIcon(kind = callIconKind(call))
        Column(Modifier.weight(1f)) {
            Text(callStatusLabel(call), style = MaterialTheme.typography.bodyLarge)
            Text(callDurationLabel(call, includeMissing = true).orEmpty(), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(callTimeLabel(call), style = MaterialTheme.typography.bodyMedium)
    }
}
