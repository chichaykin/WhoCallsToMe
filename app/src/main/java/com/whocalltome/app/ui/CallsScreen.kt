package com.whocalltome.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whocalltome.app.R
import com.whocalltome.app.data.db.CallRecordEntity
import com.whocalltome.app.data.db.UserOverrideEntity
import com.whocalltome.app.data.model.CallerCategory
import com.whocalltome.app.data.model.CallerIdentity
import com.whocalltome.app.data.model.PersonalAction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

enum class CallFilter(val id: String, val labelRes: Int) {
    ALL("all", R.string.calls_all_filter),
    MISSED("missed", R.string.calls_missed_filter),
    SPAM("spam", R.string.calls_spam_filter),
    BLOCKED("blocked", R.string.calls_blocked_filter),
    ;

    companion object {
        fun fromId(id: String): CallFilter = entries.firstOrNull { it.id == id } ?: ALL
    }
}

data class CallDayGroup(
    val title: String,
    val calls: List<CallRecordEntity>,
)

private data class CallVisual(
    val label: String,
    val isMissed: Boolean = false,
)

@Composable
fun CallsScreen(
    viewModel: AppViewModel,
    search: String,
    onSearchChange: (String) -> Unit,
    filter: CallFilter,
    listState: LazyListState,
    callLogPermissionGranted: Boolean,
    onRequestCallLogPermission: () -> Unit,
) {
    val calls by viewModel.recentCalls.collectAsState()
    val importState by viewModel.callLogState.collectAsState()
    var selectedCall by remember { mutableStateOf<CallRecordEntity?>(null) }
    val filtered = filterCalls(calls, search, filter)
    val groups = groupCallsByDay(filtered, System.currentTimeMillis(), ZoneId.systemDefault())
    val searchDescription = stringResource(R.string.calls_search)
    val clearSearchDescription = stringResource(R.string.calls_clear_search)
    val listScope = rememberCoroutineScope()
    val updateSearch: (String) -> Unit = { value ->
        if (value != search) {
            onSearchChange(value)
            listScope.launch { listState.scrollToItem(0) }
        }
    }

    Column {
        TextField(
            value = search,
            onValueChange = updateSearch,
            placeholder = { Text(stringResource(R.string.calls_search_hint)) },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = searchDescription,
                )
            },
            trailingIcon = {
                if (search.isNotEmpty()) {
                    IconButton(
                        onClick = { updateSearch("") },
                        modifier = Modifier.semantics {
                            contentDescription = clearSearchDescription
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = null,
                        )
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            colors = TextFieldDefaults.colors(
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        when {
            importState is CallLogUiState.Loading && calls.isEmpty() ->
                LoadingCallsState()
            !callLogPermissionGranted && calls.isEmpty() ->
                PermissionCallsState(onRequestCallLogPermission)
            importState is CallLogUiState.Error && calls.isEmpty() ->
                ImportErrorState(onRetry = { viewModel.refreshSystemCallLog(showResult = true) })
            filtered.isEmpty() ->
                CallsEmptyState(stringResource(callsEmptyMessage(search, filter)))
            else -> {
                if (!callLogPermissionGranted) {
                    PermissionNotice(onRequestCallLogPermission)
                }
                if (importState is CallLogUiState.Error) {
                    ImportErrorNotice { viewModel.refreshSystemCallLog(showResult = true) }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    groups.forEach { group ->
                        item(key = "day-${group.title}") {
                            Text(
                                text = group.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(group.calls, key = CallRecordEntity::id) { call ->
                            CallRow(call = call, onClick = { selectedCall = call })
                        }
                    }
                }
            }
        }
    }

    selectedCall?.let { selected ->
        val call = calls.firstOrNull { it.id == selected.id } ?: selected
        NumberDetailDialog(
            identity = CallerIdentity(
                e164 = call.e164,
                displayName = call.displayName,
                category = call.category,
                source = call.source,
                isSpam = call.category == CallerCategory.SPAM,
                personalSpam = call.source == "personal" && call.category == CallerCategory.SPAM,
                externalSpam = call.source != "personal" && call.category == CallerCategory.SPAM,
                nameSource = if (call.source == "contacts") "contacts" else null,
            ),
            onDismiss = { selectedCall = null },
            viewModel = viewModel,
        )
    }
}

@Composable
private fun CallRow(call: CallRecordEntity, onClick: () -> Unit) {
    val visual = call.visual()
    val warning = callWarningLabel(call)
    val spam = warning != null
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            CallIcon(kind = callIconKind(call))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = call.displayName ?: call.e164,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (call.displayName != null) {
                    Text(
                        text = call.e164,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = callMetadata(call, visual),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (visual.isMissed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (spam) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_warning),
                            contentDescription = null,
                            tint = colorResource(R.color.call_spam_foreground),
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = warning.orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = colorResource(R.color.call_spam_foreground),
                        )
                    }
                }
            }
            Text(
                text = callTimeLabel(call),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 72.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

fun filterCalls(calls: List<CallRecordEntity>, search: String, filter: CallFilter): List<CallRecordEntity> =
    calls.filter { it.matches(search, filter) }

private fun CallRecordEntity.matches(search: String, filter: CallFilter): Boolean {
    val matchesFilter = when (filter) {
        CallFilter.ALL -> true
        CallFilter.MISSED -> direction == "MISSED" && !wasBlocked
        CallFilter.SPAM -> category == CallerCategory.SPAM
        CallFilter.BLOCKED -> wasBlocked || direction == "BLOCKED"
    }
    if (!matchesFilter) return false
    if (search.isBlank()) return true
    val textQuery = search.trim().lowercase(Locale.ROOT)
    val nameMatches = displayName?.lowercase(Locale.ROOT)?.contains(textQuery) == true
    val numberQuery = normalizeNumberSearch(search)
    val numberMatches = numberQuery.isNotEmpty() && normalizeNumberSearch(e164).contains(numberQuery)
    return nameMatches || numberMatches
}

fun callsEmptyMessage(search: String, filter: CallFilter): Int = when {
    search.isNotBlank() -> R.string.calls_no_results
    filter == CallFilter.MISSED -> R.string.calls_no_missed
    filter == CallFilter.SPAM -> R.string.calls_no_spam
    filter == CallFilter.BLOCKED -> R.string.calls_no_blocked
    else -> R.string.calls_empty
}

/** Apply current personal labels for display without rewriting historical call outcomes. */
fun applyPersonalCallOverrides(
    calls: List<CallRecordEntity>,
    overrides: List<UserOverrideEntity>,
): List<CallRecordEntity> {
    val byNumber = overrides.associateBy(UserOverrideEntity::e164)
    return calls.map { call ->
        val personal = byNumber[call.e164]
        val hasPersonalRule = personal?.let {
            it.personalSpam || it.action != PersonalAction.DEFAULT
        } == true
        when {
            (personal == null || (personal.action == PersonalAction.DEFAULT && !personal.personalSpam)) &&
                call.source == "personal" && call.category == CallerCategory.SPAM -> call.copy(
                category = CallerCategory.UNKNOWN,
                source = "local",
            )
            personal?.personalSpam == true -> call.copy(
                category = CallerCategory.SPAM,
                source = "personal",
            )
            hasPersonalRule || (call.source == "personal" && call.category == CallerCategory.SPAM) -> call.copy(
                category = when {
                    personal?.personalSpam == true -> CallerCategory.SPAM
                    call.category == CallerCategory.SPAM -> CallerCategory.SPAM
                    else -> call.category
                },
                source = if (personal?.personalSpam == true) "personal" else call.source,
            )
            else -> call
        }
    }
}

private fun normalizeNumberSearch(value: String): String =
    value.filter { it.isDigit() || it == '+' }

fun groupCallsByDay(calls: List<CallRecordEntity>, nowMillis: Long, zone: ZoneId): List<CallDayGroup> {
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val russian = Locale.forLanguageTag("ru")
    val formatter = DateTimeFormatter.ofPattern("d MMMM", russian)
    val formatterWithYear = DateTimeFormatter.ofPattern("d MMMM yyyy", russian)
    return calls
        .sortedByDescending(CallRecordEntity::eventAt)
        .groupBy { Instant.ofEpochMilli(it.eventAt).atZone(zone).toLocalDate() }
        .map { (date, entries) ->
            val title = when (date) {
                today -> "Сегодня"
                today.minusDays(1) -> "Вчера"
                else -> if (date.year == today.year) date.format(formatter) else date.format(formatterWithYear)
            }
            CallDayGroup(title, entries)
        }
}

private fun CallRecordEntity.visual(): CallVisual = CallVisual(
    label = callStatusLabel(this),
    isMissed = direction == "MISSED" && !wasBlocked,
)

fun callStatusLabel(call: CallRecordEntity): String = when {
    call.wasBlocked || call.direction == "BLOCKED" -> "Заблокирован"
    call.direction == "INCOMING" -> "Входящий"
    call.direction == "OUTGOING" -> "Исходящий"
    call.direction == "MISSED" -> "Пропущен"
    call.direction == "REJECTED" -> "Отклонён"
    call.direction == "VOICEMAIL" -> "Голосовая почта"
    call.direction == "ANSWERED_EXTERNALLY" -> "Принят на другом устройстве"
    else -> "Звонок"
}

fun callWarningLabel(call: CallRecordEntity): String? =
    if (call.category != CallerCategory.SPAM) null
    else if (call.source.split(" + ").contains("personal")) "Личная отметка: спам" else "Возможный спам"

private fun callMetadata(call: CallRecordEntity, visual: CallVisual): String {
    val duration = call.durationSeconds.takeIf { it > 0 }?.let(::formatCallDuration)
    val source = sourceLabel(call)
    return listOfNotNull(visual.label, source, duration).joinToString(" · ")
}

fun callTimeLabel(call: CallRecordEntity, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(call.eventAt)
        .atZone(zone)
        .toLocalTime()
        .format(DateTimeFormatter.ofPattern("HH:mm"))

private fun sourceLabel(call: CallRecordEntity): String? = when {
    callWarningLabel(call) != null -> null
    call.source.split(" + ").contains("personal") -> "Личная запись"
    call.category == CallerCategory.CONTACT || call.source == "contacts" -> "Контакт"
    call.category == CallerCategory.INTERNET -> "Данные из интернета"
    else -> null
}

private fun formatCallDuration(seconds: Long): String =
    if (seconds < 60) "$seconds сек" else "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

@Composable
private fun LoadingCallsState() {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun PermissionCallsState(onRequestPermission: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.calls_permission))
        androidx.compose.material3.Button(onClick = onRequestPermission) {
            Text(stringResource(R.string.calls_permission_button))
        }
    }
}

@Composable
private fun ImportErrorState(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.calls_import_error), color = MaterialTheme.colorScheme.error)
        androidx.compose.material3.OutlinedButton(onClick = onRetry) {
            Text(stringResource(R.string.calls_retry))
        }
    }
}

@Composable
private fun PermissionNotice(onRequestPermission: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.calls_saved_permission),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.TextButton(onClick = onRequestPermission) {
            Text(stringResource(R.string.calls_allow))
        }
    }
}

@Composable
private fun ImportErrorNotice(onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.calls_import_error_short),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.TextButton(onClick = onRetry) {
            Text(stringResource(R.string.calls_retry))
        }
    }
}

@Composable
private fun CallsEmptyState(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
