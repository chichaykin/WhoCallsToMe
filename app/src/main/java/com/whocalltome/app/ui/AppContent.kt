package com.whocalltome.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Icon
import com.whocalltome.app.R
import com.whocalltome.app.data.model.CallerCategory
import com.whocalltome.app.data.model.CallerIdentity
import com.whocalltome.app.data.model.PersonalAction
import com.whocalltome.app.data.settings.ThemeMode
import android.Manifest
import kotlinx.coroutines.launch

private enum class AppTab(val title: String, val screenTitle: String, val icon: Int) {
    CALLS("Звонки", "Звонки", R.drawable.ic_phone),
    LOOKUP("Проверка", "Проверить номер", R.drawable.ic_search),
    PERSONAL("Мои номера", "Мои номера", R.drawable.ic_rules),
    SETTINGS("Настройки", "Настройки", R.drawable.ic_settings),
}

private enum class SettingsRoute(val title: String) {
    HOME("Настройки"),
    PROVIDERS("Источники"),
    PERMISSIONS("Разрешения"),
    DATA("Данные"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContent(
    viewModel: AppViewModel,
    snackbarHostState: SnackbarHostState,
    initialNumber: String?,
    roleHeld: Boolean,
    onRequestRole: () -> Unit,
    onRequestPermission: (String) -> Unit,
    onOpenAppSettings: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    callLogPermissionGranted: Boolean,
    permissionStatuses: Map<String, PermissionUiStatus>,
    onRequestCallLogPermission: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(if (initialNumber == null) AppTab.CALLS else AppTab.LOOKUP) }
    var selectedNumber by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsRoute by rememberSaveable { mutableStateOf(SettingsRoute.HOME) }
    val returnToSettingsHome = {
        settingsRoute = SettingsRoute.HOME
    }
    BackHandler(enabled = selectedNumber == null && selectedTab == AppTab.SETTINGS && settingsRoute != SettingsRoute.HOME) {
        returnToSettingsHome()
    }
    var callsSearch by rememberSaveable { mutableStateOf("") }
    var callsFilterId by rememberSaveable { mutableStateOf(CallFilter.ALL.id) }
    val callsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val tabStateHolder = rememberSaveableStateHolder()
    val callsFilter = CallFilter.fromId(callsFilterId)
    val compactNavigation = LocalDensity.current.fontScale >= 1.3f
    val callsListScope = rememberCoroutineScope()
    val changeCallsFilter: (CallFilter) -> Unit = { newFilter ->
        if (newFilter != callsFilter) {
            callsFilterId = newFilter.id
            callsListScope.launch { callsListState.scrollToItem(0) }
        }
    }
    val message by viewModel.message.collectAsState()
    val pendingUndoDelete by viewModel.pendingUndoDelete.collectAsState()
    val importPreview by viewModel.importPreview.collectAsState()

    LaunchedEffect(initialNumber) {
        if (!initialNumber.isNullOrBlank()) {
            selectedTab = AppTab.LOOKUP
            viewModel.lookup(initialNumber)
        }
    }
    LaunchedEffect(message) {
        if (message != null) {
            val result = snackbarHostState.showSnackbar(
                message = message!!,
                actionLabel = if (pendingUndoDelete != null && message == "Личная запись удалена") "Отменить" else null,
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                viewModel.undoDelete()
            } else if (pendingUndoDelete != null) {
                viewModel.clearUndoDelete()
            }
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            if (selectedNumber == null) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compactCallsHeader = selectedTab == AppTab.CALLS &&
                    (maxWidth < 360.dp || LocalDensity.current.fontScale >= 1.5f)
                TopAppBar(
                    expandedHeight = if (compactCallsHeader) {
                        96.dp
                    } else {
                        TopAppBarDefaults.TopAppBarExpandedHeight
                    },
                    title = {
                        if (selectedTab == AppTab.CALLS && compactCallsHeader) {
                            CallsFilterMenu(
                                filter = callsFilter,
                                compactHeader = true,
                                onFilterChange = changeCallsFilter,
                            )
                        } else {
                            Text(
                                if (selectedTab == AppTab.SETTINGS) settingsRoute.title else selectedTab.screenTitle,
                                modifier = Modifier.semantics { heading() },
                                maxLines = 2,
                            )
                        }
                    },
                    navigationIcon = {
                        if (selectedTab == AppTab.SETTINGS && settingsRoute != SettingsRoute.HOME) {
                            IconButton(onClick = returnToSettingsHome) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_arrow_back),
                                    contentDescription = "Назад к настройкам",
                                )
                            }
                        }
                    },
                    actions = {
                        if (selectedTab == AppTab.CALLS && !compactCallsHeader) {
                            CallsFilterMenu(
                                filter = callsFilter,
                                onFilterChange = changeCallsFilter,
                            )
                        }
                    },
                )
            }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (selectedNumber == null) {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = {
                            if (selectedTab != tab) returnToSettingsHome()
                            selectedTab = tab
                        },
                        icon = {
                            Icon(
                                painter = painterResource(tab.icon),
                                contentDescription = tab.title,
                            )
                        },
                        label = {
                            Text(if (compactNavigation) when (tab) {
                                AppTab.CALLS -> "Звонки"
                                AppTab.LOOKUP -> "Поиск"
                                AppTab.PERSONAL -> "Мои"
                                AppTab.SETTINGS -> "Опции"
                            } else tab.title, maxLines = 1)
                        },
                    )
                }
            }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (selectedNumber != null) {
                NumberProfileScreen(
                    number = selectedNumber!!,
                    viewModel = viewModel,
                    roleHeld = roleHeld,
                    onRequestRole = onRequestRole,
                    onBack = { selectedNumber = null },
                    onOpenExisting = { selectedNumber = it },
                    contactsPermissionStatus = permissionStatuses[Manifest.permission.READ_CONTACTS] ?: PermissionUiStatus.NOT_REQUESTED,
                    onRequestContactsPermission = { onRequestPermission(Manifest.permission.READ_CONTACTS) },
                    onOpenAppSettings = onOpenAppSettings,
                )
            } else {
            tabStateHolder.SaveableStateProvider(selectedTab) {
            when (selectedTab) {
                AppTab.CALLS -> CallsScreen(
                    viewModel = viewModel,
                    search = callsSearch,
                    onSearchChange = { callsSearch = it },
                    filter = callsFilter,
                    listState = callsListState,
                    callLogPermissionGranted = callLogPermissionGranted,
                    onRequestCallLogPermission = onRequestCallLogPermission,
                    onOpenNumber = { selectedNumber = it },
                )
                AppTab.LOOKUP -> LookupScreen(viewModel, initialNumber, roleHeld, onOpenNumber = { selectedNumber = it })
                AppTab.PERSONAL -> PersonalNumbersScreen(
                    viewModel = viewModel,
                    roleHeld = roleHeld,
                    onRequestRole = onRequestRole,
                    onOpenNumber = { selectedNumber = it },
                )
                AppTab.SETTINGS -> SettingsScreen(
                    viewModel = viewModel,
                    roleHeld = roleHeld,
                    onRequestRole = onRequestRole,
                    onRequestPermission = onRequestPermission,
                    onOpenAppSettings = onOpenAppSettings,
                    permissionStatuses = permissionStatuses,
                    onExport = onExport,
                    onImport = onImport,
                    route = settingsRoute,
                    onRouteChange = { settingsRoute = it },
                )
            }
            }
            }
        }
    }
    importPreview?.let { preview ->
        ImportPreviewDialog(
            preview = preview,
            onDismiss = viewModel::dismissImportPreview,
            onApply = viewModel::applyImport,
        )
    }
}

@Composable
private fun CallsFilterMenu(
    filter: CallFilter,
    compactHeader: Boolean = false,
    onFilterChange: (CallFilter) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val filterLabel = stringResource(filter.labelRes)
    val filterDescription = stringResource(R.string.calls_filter_description, filterLabel)
    val buttonLabel = if (compactHeader && filter == CallFilter.ALL) {
        stringResource(R.string.calls_all_compact_filter)
    } else {
        filterLabel
    }
    val buttonText = stringResource(R.string.calls_filter_button, buttonLabel)
    TextButton(
        onClick = { expanded = true },
        modifier = if (compactHeader) {
            Modifier.fillMaxWidth().semantics { contentDescription = filterDescription }
        } else {
            Modifier.semantics { contentDescription = filterDescription }
        },
    ) {
        Text(
            text = buttonText,
            maxLines = 2,
        )
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
    ) {
        CallFilter.entries.forEach { item ->
            DropdownMenuItem(
                text = { Text(stringResource(item.labelRes)) },
                onClick = {
                    expanded = false
                    onFilterChange(item)
                },
                trailingIcon = {
                    if (item == filter) Text("✓")
                },
            )
        }
    }
}

@Composable
private fun LookupScreen(viewModel: AppViewModel, initialNumber: String?, roleHeld: Boolean, onOpenNumber: (String) -> Unit) {
    var number by remember(initialNumber) { mutableStateOf(initialNumber.orEmpty()) }
    val state by viewModel.lookupState.collectAsState()
    val recentLookups by viewModel.recentLookups.collectAsState()
    val personalNumbers by viewModel.personalNumbers.collectAsState()
    val keys by viewModel.keyStatus.collectAsState()
    val ready = state as? LookupUiState.Ready
    var displayedIdentity by remember(ready?.identity) { mutableStateOf(ready?.identity) }
    LaunchedEffect(ready?.identity?.e164, personalNumbers) {
        ready?.identity?.e164?.let { e164 ->
            viewModel.loadIdentity(e164) { current ->
                if (current != null) displayedIdentity = current
            }
        }
    }
    var showClearHistory by rememberSaveable { mutableStateOf(false) }
    val normalizedNumber = viewModel.normalizeNumber(number)
    val inputError = number.isNotBlank() && normalizedNumber == null
    val canSubmit = normalizedNumber != null && state !is LookupUiState.Loading
    val numberError = "Введите корректный телефонный номер"
    val externalSources = configuredProviderLabels(keys).ifEmpty { "настроенным источникам" }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Сначала проверяем данные на устройстве. При онлайн-проверке номер передаётся источникам: $externalSources. История остаётся на устройстве.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = number,
            onValueChange = {
                number = it
                viewModel.clearLookupState()
            },
            label = { Text("Номер телефона") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().semantics { if (inputError) error(numberError) },
            isError = inputError,
            supportingText = {
                if (inputError) Text(numberError)
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Search,
            ),
            keyboardActions = KeyboardActions(
                onSearch = { if (canSubmit) viewModel.lookup(number) },
            ),
        )
        Button(
            onClick = { viewModel.lookup(number) },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state is LookupUiState.Loading) "Проверяем…" else "Проверить")
        }
        if (state != LookupUiState.Idle) {
            Box(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { liveRegion = LiveRegionMode.Polite },
                contentAlignment = Alignment.CenterStart,
            ) {
                when (val value = state) {
                    LookupUiState.Idle -> Unit
                    LookupUiState.Loading -> Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Проверяем номер…")
                    }
                    is LookupUiState.Error -> if (!inputError) {
                        Text(value.message, color = MaterialTheme.colorScheme.error)
                    }
                    is LookupUiState.Ready -> Text(
                        "Результат проверки",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
        ready?.let { result ->
            IdentityCard(
                identity = displayedIdentity ?: result.identity,
                providerStates = result.providers,
                isComplete = result.isComplete,
                roleHeld = roleHeld,
                onOpenNumber = onOpenNumber,
                onRefresh = { viewModel.lookupFresh(result.identity.e164) },
            )
        }

        if (recentLookups.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Недавние проверки", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { showClearHistory = true }, enabled = state !is LookupUiState.Loading) {
                    Text("Очистить")
                }
            }
            recentLookups.forEach { lookup ->
                val savedName = personalNumbers.firstOrNull { it.e164 == lookup.e164 }?.displayName
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = state !is LookupUiState.Loading) {
                            number = lookup.e164
                            viewModel.lookup(lookup.e164)
                        },
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(savedName ?: lookup.e164, style = MaterialTheme.typography.bodyLarge)
                        if (savedName != null) {
                            Text(lookup.e164, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            "Проверено: ${formatLookupTime(lookup.lastAttemptAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (showClearHistory) {
        AlertDialog(
            onDismissRequest = { showClearHistory = false },
            title = { Text("Очистить историю проверок?") },
            text = { Text("Очистится только история проверок. Сохранённые результаты проверок, личные записи и правила останутся на устройстве.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearHistory = false
                    viewModel.clearLookupHistory()
                }) { Text("Очистить") }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistory = false }) { Text("Отмена") }
            },
        )
    }
}

private fun formatLookupTime(timestamp: Long): String =
    java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.SHORT,
        java.text.DateFormat.SHORT,
    ).format(java.util.Date(timestamp))

@Composable
private fun IdentityCard(
    identity: CallerIdentity,
    providerStates: List<com.whocalltome.app.data.model.ProviderLookupStatus>,
    isComplete: Boolean,
    roleHeld: Boolean,
    onOpenNumber: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    var detailsExpanded by rememberSaveable(identity.e164) { mutableStateOf(false) }
    val failedSource = providerStates.any { provider ->
        provider.status in setOf(
            com.whocalltome.app.data.model.LookupStatus.NETWORK_ERROR,
            com.whocalltome.app.data.model.LookupStatus.PROVIDER_ERROR,
            com.whocalltome.app.data.model.LookupStatus.QUOTA_EXHAUSTED,
        )
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    painter = painterResource(
                        if (identity.numberType == com.whocalltome.app.data.model.NumberType.BUSINESS) R.drawable.ic_business
                        else R.drawable.ic_person,
                    ),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        identity.displayName ?: "Имя не найдено",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(identity.e164, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (identity.displayName != null && identity.category != CallerCategory.SPAM) CategoryLabel(identity.category)
            if (identity.personalAction == PersonalAction.BLOCK) {
                Text(
                    if (roleHeld) "Блокировка включена" else "Блокировка настроена — включите определение звонков",
                    color = if (roleHeld) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
            } else if (identity.personalAction == PersonalAction.ALLOW) {
                Text("Звонки разрешены", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (identity.shouldWarn) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Возможный спам", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold)
                        if (identity.personalSpam) Text("Помечено вами", color = MaterialTheme.colorScheme.onErrorContainer)
                        if (identity.externalSpam) {
                            Text(
                                "По данным: ${identity.externalReputations.filter { it.isSpam }.joinToString(", ") { providerLabel(it.source) }.ifBlank { identity.externalSource?.let(::providerLabel) ?: "внешнего источника" }}",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
            Button(onClick = { onOpenNumber(identity.e164) }, modifier = Modifier.fillMaxWidth()) {
                Text("Открыть карточку номера")
            }
            if (!isComplete) Text("Проверяем источники…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else if (failedSource) Text("Часть источников недоступна", color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                Text(if (detailsExpanded) "Скрыть данные источников ▴" else "Данные источников ▾")
            }
            if (detailsExpanded) {
                if (identity.externalNames.size > 1) {
                    Text("Имена от источников", style = MaterialTheme.typography.titleSmall)
                    identity.externalNames.forEach { source ->
                        Text("${providerLabel(source.source)} · ${source.value}")
                    }
                }
                identity.externalReputations.forEach { reputation ->
                    reputation.score?.let { score -> Text("Оценка ${providerLabel(reputation.source)}: $score") }
                }
                providerStates.forEach { provider ->
                    val detail = when (provider.status) {
                        null -> "проверяется"
                        com.whocalltome.app.data.model.LookupStatus.FOUND -> if (provider.fromCache) "сохранённые данные" else "данные обновлены"
                        com.whocalltome.app.data.model.LookupStatus.NOT_FOUND -> if (provider.fromCache) "данных нет (сохранённый ответ)" else "данных нет"
                        com.whocalltome.app.data.model.LookupStatus.NETWORK_ERROR -> "нет сети"
                        com.whocalltome.app.data.model.LookupStatus.QUOTA_EXHAUSTED -> "квота временно недоступна"
                        com.whocalltome.app.data.model.LookupStatus.NOT_CONFIGURED -> "не настроен"
                        com.whocalltome.app.data.model.LookupStatus.PROVIDER_ERROR -> "источник вернул ошибку"
                    }
                    Text("${providerLabel(provider.source)} · $detail", style = MaterialTheme.typography.bodySmall)
                    provider.nextAttemptAt?.takeIf { it != Long.MAX_VALUE }?.let { timestamp ->
                        Text(
                            "Повторная попытка: ${formatLookupTime(timestamp)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                    Text("Обновить данные источников")
                }
            }
        }
    }
}

private enum class PersonalFilter(val label: String) {
    ALL("Все"), BLOCKED("Заблокированные"), SPAM("Спам")
}

@Composable
private fun PersonalNumbersScreen(
    viewModel: AppViewModel,
    roleHeld: Boolean,
    onRequestRole: () -> Unit,
    onOpenNumber: (String) -> Unit,
) {
    val numbers by viewModel.personalNumbers.collectAsState()
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(PersonalFilter.ALL) }
    val visible = numbers.filter { item ->
        val query = search.trim()
        (query.isBlank() || item.e164.contains(query) || item.displayName?.contains(query, ignoreCase = true) == true ||
            item.personalName.contains(query, ignoreCase = true)) &&
            when (filter) {
                PersonalFilter.ALL -> true
                PersonalFilter.BLOCKED -> item.action == PersonalAction.BLOCK
                PersonalFilter.SPAM -> item.personalSpam
            }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item {
            Button(onClick = { onOpenNumber("") }, modifier = Modifier.fillMaxWidth()) {
                Text("Добавить номер")
            }
        }
        if (!roleHeld) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Для блокировки включите определение звонков")
                    TextButton(onClick = onRequestRole) { Text("Включить") }
                }
            }
        }
        if (numbers.isEmpty()) {
            item { EmptyState("Сохраняйте имена незнакомых номеров и управляйте блокировкой") }
        } else {
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Поиск по имени или номеру") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PersonalFilter.entries.forEach { option ->
                        FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option.label) })
                    }
                }
            }
            if (visible.isEmpty()) {
                item { EmptyState("Номера не найдены") }
            } else {
                items(visible, key = PersonalNumberUi::e164) { item ->
                    var menuOpen by remember { mutableStateOf(false) }
                    Card(onClick = { onOpenNumber(item.e164) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(item.displayName ?: item.e164, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                if (item.displayName != null) Text(item.e164, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val typeLabel = when (item.numberType) {
                                    com.whocalltome.app.data.model.NumberType.PERSONAL -> "Личный"
                                    com.whocalltome.app.data.model.NumberType.BUSINESS -> "Бизнес"
                                    else -> null
                                }
                                if (typeLabel != null) Text(typeLabel, style = MaterialTheme.typography.labelMedium)
                                if (item.action == PersonalAction.BLOCK) {
                                    Text(
                                        if (roleHeld) "Заблокирован" else "Блокировка настроена",
                                        color = if (roleHeld) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (item.personalSpam) Text("Моя пометка: спам", color = MaterialTheme.colorScheme.error)
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(text = { Text("Удалить") }, onClick = {
                                        menuOpen = false
                                        viewModel.deletePersonal(item.e164)
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    viewModel: AppViewModel,
    roleHeld: Boolean,
    onRequestRole: () -> Unit,
    onRequestPermission: (String) -> Unit,
    onOpenAppSettings: () -> Unit,
    permissionStatuses: Map<String, PermissionUiStatus>,
    onExport: () -> Unit,
    onImport: () -> Unit,
    route: SettingsRoute,
    onRouteChange: (SettingsRoute) -> Unit,
) {
    var showThemeDialog by remember { mutableStateOf(false) }
    val keys by viewModel.keyStatus.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()

    when (route) {
        SettingsRoute.HOME -> SettingsHome(
            roleHeld = roleHeld,
            onRequestRole = onRequestRole,
            keys = keys,
            themeMode = themeMode,
            onProviders = { onRouteChange(SettingsRoute.PROVIDERS) },
            onPermissions = { onRouteChange(SettingsRoute.PERMISSIONS) },
            onData = { onRouteChange(SettingsRoute.DATA) },
            onTheme = { showThemeDialog = true },
        )
        SettingsRoute.PROVIDERS -> ProviderSettingsScreen(
            viewModel = viewModel,
            keys = keys,
        )
        SettingsRoute.PERMISSIONS -> PermissionsSettingsScreen(
            statuses = permissionStatuses,
            onRequestPermission = onRequestPermission,
            onOpenAppSettings = onOpenAppSettings,
        )
        SettingsRoute.DATA -> DataSettingsScreen(
            onExport = onExport,
            onImport = onImport,
            onClear = viewModel::clearExternalCache,
        )
    }

    if (showThemeDialog) {
        ThemeDialog(
            selected = themeMode,
            onSelect = { viewModel.setThemeMode(it); showThemeDialog = false },
            onDismiss = { showThemeDialog = false },
        )
    }
}

@Composable
private fun SettingsHome(
    roleHeld: Boolean,
    onRequestRole: () -> Unit,
    keys: KeyStatus,
    themeMode: ThemeMode,
    onProviders: () -> Unit,
    onPermissions: () -> Unit,
    onData: () -> Unit,
    onTheme: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingsCard("Защита звонков") {
            SettingsStatusRow(if (roleHeld) "Определитель звонков включён" else "Определитель звонков выключен", roleHeld)
            if (!roleHeld) Button(onClick = onRequestRole, modifier = Modifier.fillMaxWidth()) { Text("Включить определитель") }
            Text("Спам-метки предупреждают. Блокируются только номера из вашего списка.", style = MaterialTheme.typography.bodySmall)
        }
        SettingsRow("Источники", "Подключено: ${configuredProviderCount(keys)} из 3", onProviders)
        SettingsRow("Разрешения", "Контакты, журнал звонков и уведомления", onPermissions)
        SettingsRow("Тема", themeMode.label, onTheme)
        SettingsRow("Данные", "Резервная копия и внешний кеш", onData)
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsStatusRow(text: String, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (enabled) "✓" else "!", color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleLarge)
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

internal fun providerLabel(id: String): String = when (id) { "ipqs" -> "IPQualityScore"; "tellows" -> "tellows"; else -> "PhoneBlock" }
private fun providerSaved(id: String, keys: KeyStatus): Boolean = when (id) { "ipqs" -> keys.ipqsSaved; "tellows" -> keys.tellowsSaved; else -> keys.phoneBlockSaved }
private fun configuredProviderCount(keys: KeyStatus): Int = listOf(keys.ipqsSaved, keys.tellowsSaved, keys.phoneBlockSaved).count { it }
private fun configuredProviderLabels(keys: KeyStatus): String = listOfNotNull(
    "IPQualityScore".takeIf { keys.ipqsSaved },
    "tellows".takeIf { keys.tellowsSaved },
    "PhoneBlock".takeIf { keys.phoneBlockSaved },
).joinToString(", ")

internal data class ProviderSetupLink(
    val label: String,
    val url: String,
)

internal data class ProviderSetupGuide(
    val secretName: String,
    val accessSummary: String,
    val steps: List<String>,
    val links: List<ProviderSetupLink>,
)

private val providerSetupGuides = mapOf(
    "ipqs" to ProviderSetupGuide(
        secretName = "API key",
        accessSummary = "Бесплатно: создайте аккаунт и API key в личном кабинете.",
        steps = listOf(
            "Нажмите «Создать аккаунт» и зарегистрируйтесь в IPQualityScore.",
            "После входа откройте раздел «API Keys».",
            "Создайте новый ключ или скопируйте активный API key.",
            "Вернитесь в приложение и вставьте ключ в поле ниже.",
        ),
        links = listOf(
            ProviderSetupLink("Создать аккаунт", "https://www.ipqualityscore.com/create-account/phone-validation"),
            ProviderSetupLink("Открыть API Keys", "https://www.ipqualityscore.com/user/api-keys"),
        ),
    ),
    "tellows" to ProviderSetupGuide(
        secretName = "apikey",
        accessSummary = "Платный личный ключ на 2 года; актуальную цену смотрите на сайте.",
        steps = listOf(
            "Откройте страницу личного API-ключа tellows и оформите покупку.",
            "После оплаты откройте раздел загрузок в своём аккаунте tellows.",
            "Откройте данные доступа и скопируйте только значение apikey.",
            "Не вставляйте URL целиком или partner=tellowskey; вернитесь в приложение и вставьте apikey в поле ниже.",
        ),
        links = listOf(
            ProviderSetupLink("Открыть личный API-ключ tellows", "https://shop.tellows.de/en/tellows-api-key.html"),
        ),
    ),
    "phoneblock" to ProviderSetupGuide(
        secretName = "API token",
        accessSummary = "Бесплатно: после входа создайте отдельный API token.",
        steps = listOf(
            "Откройте настройки PhoneBlock и войдите через Google или email.",
            "В настройках создайте API token и скопируйте его.",
            "Нужен именно API token, а не пароль аккаунта или CardDAV-токен.",
            "Вернитесь в приложение и вставьте token в поле ниже.",
        ),
        links = listOf(
            ProviderSetupLink("Открыть настройки PhoneBlock", "https://phoneblock.net/phoneblock/settings"),
        ),
    ),
)

internal fun providerSetupGuide(id: String): ProviderSetupGuide =
    requireNotNull(providerSetupGuides[id]) { "Unknown provider: $id" }

@Composable
private fun ProviderSettingsScreen(
    viewModel: AppViewModel,
    keys: KeyStatus,
) {
    val checkState by viewModel.providerCheck.collectAsState()
    val selectedProvider by viewModel.lookupProvider.collectAsState()
    var testProvider by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Для автоматической проверки используется выбранный источник и PhoneBlock, если он подключён. Ручное обновление проверяет все подключённые источники, кроме временно приостановленных из-за ошибки или квоты.")
        Text("Источник имени и основной проверки", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ipqs", "tellows").forEach { provider ->
                ProviderChip(provider, providerLabel(provider), selectedProvider, viewModel::setLookupProvider)
            }
        }
        listOf("ipqs", "tellows", "phoneblock").forEach { provider ->
            ProviderSettingsCard(
                viewModel = viewModel,
                provider = provider,
                saved = providerSaved(provider, keys),
                onTest = { viewModel.resetProviderCheck(); testProvider = provider },
            )
        }
    }
    testProvider?.let { provider ->
        ProviderCheckDialog(provider, checkState, viewModel::checkProvider, viewModel::resetProviderCheck) { testProvider = null }
    }
}

@Composable
private fun ProviderSettingsCard(
    viewModel: AppViewModel,
    provider: String,
    saved: Boolean,
    onTest: () -> Unit,
) {
    val context = LocalContext.current
    val setupGuide = providerSetupGuide(provider)
    var key by rememberSaveable(provider) { mutableStateOf("") }
    var confirmDelete by rememberSaveable(provider) { mutableStateOf(false) }
    var guideExpanded by rememberSaveable(provider) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(providerLabel(provider), style = MaterialTheme.typography.titleMedium)
            Text(
                if (saved) "Подключён и участвует в проверках" else "Не подключён",
                color = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { guideExpanded = !guideExpanded }) {
                Text(if (guideExpanded) "Скрыть инструкцию" else "Как получить ключ")
            }
            if (guideExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(setupGuide.accessSummary, style = MaterialTheme.typography.bodySmall)
                    setupGuide.steps.forEachIndexed { index, step ->
                        Text("${index + 1}. $step", style = MaterialTheme.typography.bodySmall)
                    }
                    setupGuide.links.forEach { link ->
                        TextButton(onClick = { context.startSafe(Intent(Intent.ACTION_VIEW, Uri.parse(link.url))) }) {
                            Text(link.label)
                        }
                    }
                }
            }
            OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("Новый ${setupGuide.secretName}") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { viewModel.saveApiKey(provider, key); key = "" }, enabled = key.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Сохранить ключ") }
            OutlinedButton(onClick = onTest, enabled = saved, modifier = Modifier.fillMaxWidth()) { Text("Проверить подключение") }
            if (saved) TextButton(onClick = { confirmDelete = true }) { Text("Удалить ключ") }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Удалить ключ?") }, text = { Text("${providerLabel(provider)} перестанет выполнять внешние проверки, пока ключ не будет добавлен снова.") }, confirmButton = { TextButton(onClick = { viewModel.clearApiKey(provider); confirmDelete = false }) { Text("Удалить") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } })
}

@Composable
private fun PermissionsSettingsScreen(
    statuses: Map<String, PermissionUiStatus>,
    onRequestPermission: (String) -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val notificationStatus = if (android.os.Build.VERSION.SDK_INT >= 33) {
        statuses[Manifest.permission.POST_NOTIFICATIONS] ?: PermissionUiStatus.NOT_REQUESTED
    } else {
        PermissionUiStatus.GRANTED
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PermissionRow(
            "Контакты",
            "Показывать имена из телефонной книги",
            statuses[Manifest.permission.READ_CONTACTS] ?: PermissionUiStatus.NOT_REQUESTED,
            onRequest = { onRequestPermission(Manifest.permission.READ_CONTACTS) },
            onOpenAppSettings = onOpenAppSettings,
        )
        PermissionRow(
            "Журнал звонков",
            "Импортировать историю звонков",
            statuses[Manifest.permission.READ_CALL_LOG] ?: PermissionUiStatus.NOT_REQUESTED,
            onRequest = { onRequestPermission(Manifest.permission.READ_CALL_LOG) },
            onOpenAppSettings = onOpenAppSettings,
        )
        PermissionRow(
            "Уведомления",
            "Показывать результат проверки входящего звонка",
            notificationStatus,
            onRequest = {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    onRequestPermission(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onOpenAppSettings = onOpenAppSettings,
        )
        TextButton(onClick = onOpenAppSettings) { Text("Открыть настройки приложения") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    status: PermissionUiStatus,
    onRequest: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val statusLabel = when (status) {
        PermissionUiStatus.GRANTED -> "Разрешено"
        PermissionUiStatus.NOT_REQUESTED -> "Не разрешено"
        PermissionUiStatus.DENIED -> "Доступ не предоставлен"
        PermissionUiStatus.SETTINGS_REQUIRED -> "Не разрешено"
    }
    Card(Modifier.fillMaxWidth().semantics { stateDescription = statusLabel }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Статус: $statusLabel",
                style = MaterialTheme.typography.labelLarge,
                color = if (status == PermissionUiStatus.GRANTED) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (status == PermissionUiStatus.DENIED) {
                Text(
                    "Повторите запрос или откройте настройки Android.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (status == PermissionUiStatus.SETTINGS_REQUIRED) {
                Text(
                    "Откройте настройки Android, чтобы разрешить доступ.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (status) {
                PermissionUiStatus.GRANTED -> Unit
                PermissionUiStatus.NOT_REQUESTED -> TextButton(onClick = onRequest) { Text("Разрешить") }
                PermissionUiStatus.DENIED -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRequest) { Text("Повторить запрос") }
                    TextButton(onClick = onOpenAppSettings) { Text("Настройки Android") }
                }
                PermissionUiStatus.SETTINGS_REQUIRED -> TextButton(onClick = onOpenAppSettings) {
                    Text("Открыть настройки Android")
                }
            }
        }
    }
}

@Composable
private fun DataSettingsScreen(onExport: () -> Unit, onImport: () -> Unit, onClear: () -> Unit) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("Резервная копия содержит номера, заметки и правила. Ключи и внешний кеш не включаются."); Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("Сохранить резервную копию") }; OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Восстановить из файла") }; OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) { Text("Очистить результаты внешнего поиска") }; Text("Личные записи и правила сохранятся.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable
private fun ThemeDialog(selected: ThemeMode, onSelect: (ThemeMode) -> Unit, onDismiss: () -> Unit) { AlertDialog(onDismissRequest = onDismiss, title = { Text("Тема") }, text = { Column { ThemeMode.entries.forEach { mode -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = selected == mode, onClick = { onSelect(mode) }); Text(mode.label) } } } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }) }

@Composable
private fun ProviderCheckDialog(provider: String, state: ProviderCheckState, onCheck: (String, String) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit) {
    var number by rememberSaveable(provider) { mutableStateOf("") }
    AlertDialog(onDismissRequest = { onReset(); onDismiss() }, title = { Text("Проверить ${providerLabel(provider)}") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Введите номер. Запрос может расходовать квоту источника.", style = MaterialTheme.typography.bodySmall); OutlinedTextField(number, { number = it }, label = { Text("Номер с кодом страны") }, singleLine = true); when (state) { ProviderCheckState.Loading -> Text("Проверяем…"); is ProviderCheckState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error); is ProviderCheckState.Success -> Text("Доступ подтверждён · ${if (state.result.status == com.whocalltome.app.data.model.LookupStatus.FOUND) "данные найдены" else "ответ получен без данных"}\nПроверено: ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(state.checkedAt))}", color = MaterialTheme.colorScheme.primary); ProviderCheckState.Idle -> Unit } } }, confirmButton = { TextButton(onClick = { onCheck(provider, number) }, enabled = number.isNotBlank() && state !is ProviderCheckState.Loading) { Text("Проверить") } }, dismissButton = { TextButton(onClick = { onReset(); onDismiss() }) { Text("Закрыть") } })
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun ImportPreviewDialog(
    preview: ImportPreviewState,
    onDismiss: () -> Unit,
    onApply: (Boolean, Boolean) -> Unit,
) {
    var replace by rememberSaveable { mutableStateOf(false) }
    var restoreSettings by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Проверить восстановление") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Новых номеров: ${preview.summary.newCount}")
                Text("Совпадающих номеров: ${preview.summary.conflictCount}")
                if (preview.summary.changeCount > 0) Text("Потенциальных изменений: ${preview.summary.changeCount}")
                CheckRow("Заменить совпадающие записи данными из файла", replace) { replace = it }
                CheckRow("Восстановить настройки приложения", restoreSettings) { restoreSettings = it }
                Text("При замене будут затронуты имена, заметки и правила разрешения/блокировки.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onApply(replace, restoreSettings) }) { Text("Восстановить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun ProviderChip(id: String, label: String, selected: String, onSelect: (String) -> Unit) {
    FilterChip(selected = selected == id, onClick = { onSelect(id) }, label = { Text(label) })
}

@Composable
private fun CategoryLabel(category: CallerCategory) {
    val color = when (category) {
        CallerCategory.SPAM -> MaterialTheme.colorScheme.error
        CallerCategory.CONTACT -> MaterialTheme.colorScheme.primary
        CallerCategory.PERSONAL -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(categoryText(category), color = color, style = MaterialTheme.typography.labelMedium)
}

private fun categoryText(category: CallerCategory): String = when (category) {
    CallerCategory.CONTACT -> "Контакт"
    CallerCategory.PERSONAL -> "Сохранённый номер"
    CallerCategory.INTERNET -> "Интернет"
    CallerCategory.SPAM -> "Спам"
    CallerCategory.UNKNOWN -> "Неизвестный"
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Text(label)
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun Context.startSafe(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // The action simply remains unavailable when the target app is not installed.
    }
}
