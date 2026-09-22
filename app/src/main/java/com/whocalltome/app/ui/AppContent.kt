package com.whocalltome.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
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
import androidx.compose.material3.AssistChip
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
import androidx.compose.material3.Switch
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
    PERSONAL("Мои правила", "Правила для номеров", R.drawable.ic_rules),
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
    var settingsRoute by rememberSaveable { mutableStateOf(SettingsRoute.HOME) }
    val returnToSettingsHome = {
        settingsRoute = SettingsRoute.HOME
    }
    BackHandler(enabled = selectedTab == AppTab.SETTINGS && settingsRoute != SettingsRoute.HOME) {
        returnToSettingsHome()
    }
    var callsSearch by rememberSaveable { mutableStateOf("") }
    var callsFilterId by rememberSaveable { mutableStateOf(CallFilter.ALL.id) }
    val callsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val callsFilter = CallFilter.fromId(callsFilterId)
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
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
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
                        label = { Text(tab.title) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (selectedTab) {
                AppTab.CALLS -> CallsScreen(
                    viewModel = viewModel,
                    search = callsSearch,
                    onSearchChange = { callsSearch = it },
                    filter = callsFilter,
                    listState = callsListState,
                    callLogPermissionGranted = callLogPermissionGranted,
                    onRequestCallLogPermission = onRequestCallLogPermission,
                )
                AppTab.LOOKUP -> LookupScreen(viewModel, initialNumber)
                AppTab.PERSONAL -> PersonalNumbersScreen(
                    viewModel = viewModel,
                    roleHeld = roleHeld,
                    onRequestRole = onRequestRole,
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
private fun LookupScreen(viewModel: AppViewModel, initialNumber: String?) {
    var number by remember(initialNumber) { mutableStateOf(initialNumber.orEmpty()) }
    val state by viewModel.lookupState.collectAsState()
    val recentLookups by viewModel.recentLookups.collectAsState()
    val keys by viewModel.keyStatus.collectAsState()
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
            "Сначала проверяются данные на устройстве. При внешнем запросе номер может передаваться источникам: $externalSources. Результаты и история остаются на устройстве.",
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
                Text(if (inputError) numberError else "Можно ввести местный номер или номер с кодом страны")
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
        (state as? LookupUiState.Ready)?.let { ready ->
            IdentityCard(ready.identity, viewModel)
            OutlinedButton(
                onClick = { viewModel.lookupFresh(ready.identity.e164) },
                enabled = state !is LookupUiState.Loading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Обновить данные источников") }
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
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = state !is LookupUiState.Loading) {
                            number = lookup.e164
                            viewModel.lookup(lookup.e164)
                        },
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(lookup.e164, style = MaterialTheme.typography.bodyLarge)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdentityCard(identity: CallerIdentity, viewModel: AppViewModel) {
    val context = LocalContext.current
    val personalNumbers by viewModel.personalNumbers.collectAsState()
    var showEditor by remember { mutableStateOf(false) }
    Card(
        colors = if (identity.isSpam) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(identity.displayName ?: "Имя не найдено", style = MaterialTheme.typography.titleLarge)
            Text(identity.e164)
            CategoryLabel(identity.category)
            identity.personalAction.takeIf { it != PersonalAction.DEFAULT }?.let { action ->
                Text(
                    if (action == PersonalAction.BLOCK) "Заблокирован вашим правилом" else "Разрешён вашим правилом",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (identity.personalSpam) Text("Моя метка: спам", color = MaterialTheme.colorScheme.error)
            if (identity.externalSpam) {
                Text(
                    "Возможный спам · ${identity.externalReputations.filter { it.isSpam }.joinToString(", ") { providerLabel(it.source) }}",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (identity.externalNames.map { it.value }.distinct().size > 1) {
                Text("Имена по данным источников", style = MaterialTheme.typography.titleSmall)
                identity.externalNames.forEach { externalName ->
                    Text("${providerLabel(externalName.source)} · ${externalName.value}")
                }
            }
            identity.externalReputations.forEach { reputation ->
                reputation.score?.let { score ->
                    Text("Оценка ${providerLabel(reputation.source)}: $score")
                }
            }
            HorizontalDivider()
            NumberActionButtons(
                context = context,
                e164 = identity.e164,
                name = identity.displayName,
                existingContact = identity.nameSource == "contacts",
                resolveContactUri = { viewModel.findContactUri(identity.e164) },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showEditor = true }) {
                    Text("Личное правило")
                }
                OutlinedButton(onClick = { viewModel.setAction(identity.e164, PersonalAction.BLOCK) }) {
                    Text("Блокировать")
                }
                OutlinedButton(onClick = {
                    viewModel.markSpam(identity.e164, !identity.personalSpam)
                }) {
                    Text(if (identity.personalSpam) "Снять мою метку" else "Это спам")
                }
            }
        }
    }
    if (showEditor) {
        PersonalNumberDialog(
            viewModel = viewModel,
            initial = personalNumbers.firstOrNull { it.e164 == identity.e164 },
            numberSeed = identity.e164,
            onDismiss = { showEditor = false },
            onOpenExisting = { showEditor = false },
        )
    }
}

private enum class PersonalFilter(val label: String) {
    ALL("Все"),
    BLOCKED("Заблокированные"),
    ALLOWED("Разрешённые"),
    SPAM("Мой спам"),
}

@Composable
private fun PersonalNumbersScreen(
    viewModel: AppViewModel,
    roleHeld: Boolean,
    onRequestRole: () -> Unit,
) {
    val numbers by viewModel.personalNumbers.collectAsState()
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(PersonalFilter.ALL) }
    var editorItem by remember { mutableStateOf<PersonalNumberUi?>(null) }
    var showEditor by rememberSaveable { mutableStateOf(false) }
    val visible = numbers.filter { item ->
        val query = search.trim().lowercase()
        val matchesSearch = query.isBlank() || listOf(item.e164, item.note)
            .any { it.lowercase().contains(query) }
        val matchesFilter = when (filter) {
            PersonalFilter.ALL -> true
            PersonalFilter.BLOCKED -> item.action == PersonalAction.BLOCK
            PersonalFilter.ALLOWED -> item.action == PersonalAction.ALLOW
            PersonalFilter.SPAM -> item.personalSpam
        }
        matchesSearch && matchesFilter
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            "Имя берётся из книги контактов. Здесь настраиваются правила для номера",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        if (!roleHeld) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            ) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Чтобы блокировка работала, включите определение звонков для приложения", Modifier.weight(1f))
                    TextButton(onClick = onRequestRole) { Text("Включить") }
                }
            }
        }
        Button(
            onClick = {
                editorItem = null
                showEditor = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Добавить номер") }
        Text(
            "Номера, заметки и правила хранятся на этом устройстве",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        )

        if (numbers.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Пока нет личных правил", style = MaterialTheme.typography.titleMedium)
                    Text("Заметка — личная информация о номере только в этом приложении.")
                    Text("Спам-метка — предупреждение без блокировки.")
                    Text("Разрешение или блокировка — правило для входящих звонков.")
                    Text("Имя берётся из книги контактов. Здесь настраиваются правила для номера.")
                }
            }
        } else {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Поиск по имени, номеру или заметке") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                PersonalFilter.entries.forEach { option ->
                    FilterChip(
                        selected = option == filter,
                        onClick = { filter = option },
                        label = { Text(option.label) },
                    )
                }
            }
            if (visible.isEmpty()) {
                EmptyState("По заданным условиям правила не найдены")
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(visible, key = PersonalNumberUi::e164) { item ->
                        PersonalNumberCard(
                            item = item,
                            onEdit = {
                                editorItem = item
                                showEditor = true
                            },
                            onDelete = { viewModel.deletePersonal(item.e164) },
                            onAction = { viewModel.setAction(item.e164, it) },
                        )
                    }
                }
            }
        }
    }

    if (showEditor) {
        PersonalNumberDialog(
            viewModel = viewModel,
            initial = editorItem,
            onDismiss = { showEditor = false },
            onOpenExisting = { existing ->
                editorItem = existing
                showEditor = true
            },
        )
    }
}

@Composable
private fun PersonalNumberCard(
    item: PersonalNumberUi,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAction: (PersonalAction) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(item.e164, fontWeight = FontWeight.SemiBold)
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Text(
                            "⋮",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { contentDescription = "Действия с правилом" },
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Изменить") },
                            onClick = { menuOpen = false; onEdit() },
                        )
                        DropdownMenuItem(
                            text = { Text("Удалить") },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
            if (item.note.isNotBlank()) Text(item.note)
            Text(
                when (item.action) {
                    PersonalAction.DEFAULT -> "Без особого правила"
                    PersonalAction.ALLOW -> "Разрешён"
                    PersonalAction.BLOCK -> "Заблокирован"
                },
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
            if (item.personalSpam) {
                Text("Моя метка: спам", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.action != PersonalAction.ALLOW) {
                    AssistChip(onClick = { onAction(PersonalAction.ALLOW) }, label = { Text("Разрешить") })
                }
                if (item.action != PersonalAction.BLOCK) {
                    AssistChip(onClick = { onAction(PersonalAction.BLOCK) }, label = { Text("Блокировать") })
                }
                if (item.action != PersonalAction.DEFAULT) {
                    AssistChip(onClick = { onAction(PersonalAction.DEFAULT) }, label = { Text("Снять правило") })
                }
            }
        }
    }
}

@Composable
private fun PersonalNumberDialog(
    viewModel: AppViewModel,
    initial: PersonalNumberUi?,
    numberSeed: String? = null,
    onDismiss: () -> Unit,
    onOpenExisting: (PersonalNumberUi) -> Unit,
) {
    val formKey = initial?.e164 ?: numberSeed.orEmpty()
    var number by rememberSaveable(formKey) { mutableStateOf(initial?.e164 ?: numberSeed.orEmpty()) }
    var note by rememberSaveable(initial?.e164) { mutableStateOf(initial?.note.orEmpty()) }
    var action by rememberSaveable(initial?.e164) { mutableStateOf(initial?.action ?: PersonalAction.DEFAULT) }
    var spam by rememberSaveable(initial?.e164) { mutableStateOf(initial?.personalSpam ?: false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var duplicate by remember { mutableStateOf<PersonalNumberUi?>(null) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (initial == null) "Добавить правило" else "Изменить правило") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it; error = null },
                    label = { Text("Номер") },
                    singleLine = true,
                    enabled = initial == null,
                    isError = error != null,
                    supportingText = error?.let { message -> { Text(message) } },
                )
                OutlinedTextField(note, { note = it }, label = { Text("Заметка (необязательно)") })
                Text("Обработка звонков", style = MaterialTheme.typography.titleSmall)
                PersonalAction.entries.forEach { option ->
                    val label = when (option) {
                        PersonalAction.DEFAULT -> "Без особого правила"
                        PersonalAction.ALLOW -> "Разрешать"
                        PersonalAction.BLOCK -> "Блокировать"
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = action == option, onClick = { action = option })
                        Column {
                            Text(label)
                            if (option == PersonalAction.DEFAULT) Text("Предупреждения о спаме остаются", style = MaterialTheme.typography.bodySmall)
                            if (option == PersonalAction.ALLOW) Text("Разрешает звонки, но оставляет предупреждения", style = MaterialTheme.typography.bodySmall)
                            if (option == PersonalAction.BLOCK) Text("Отклоняет входящие звонки", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                CheckRow("Пометить как спам", spam) { spam = it }
                Text(
                    "Только предупреждает. Для отклонения звонков выберите «Блокировать»",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    val normalized = viewModel.normalizeNumber(number)
                    if (normalized == null) {
                        error = "Введите корректный номер с кодом страны"
                        return@TextButton
                    }
                    val existing = viewModel.personalNumber(normalized)
                    if (existing != null && existing.e164 != initial?.e164) {
                        duplicate = existing
                        return@TextButton
                    }
                    saving = true
                    viewModel.savePersonal(
                        rawNumber = normalized,
                        note = note,
                        action = action,
                        personalSpam = spam,
                        onSaved = onDismiss,
                        onError = { saving = false },
                    )
                },
            ) { Text(if (saving) "Сохраняем…" else "Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Отмена") } },
    )

    duplicate?.let { existing ->
        AlertDialog(
            onDismissRequest = { duplicate = null },
            title = { Text("Номер уже добавлен") },
            text = { Text("Открыть существующее правило для ${existing.e164}?") },
            confirmButton = {
                TextButton(onClick = { duplicate = null; onDismiss(); onOpenExisting(existing) }) { Text("Открыть") }
            },
            dismissButton = { TextButton(onClick = { duplicate = null }) { Text("Остаться") } },
        )
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

private fun providerLabel(id: String): String = when (id) { "ipqs" -> "IPQualityScore"; "tellows" -> "tellows"; else -> "PhoneBlock" }
private fun providerSaved(id: String, keys: KeyStatus): Boolean = when (id) { "ipqs" -> keys.ipqsSaved; "tellows" -> keys.tellowsSaved; else -> keys.phoneBlockSaved }
private fun configuredProviderCount(keys: KeyStatus): Int = listOf(keys.ipqsSaved, keys.tellowsSaved, keys.phoneBlockSaved).count { it }
private fun configuredProviderLabels(keys: KeyStatus): String = listOfNotNull(
    "IPQualityScore".takeIf { keys.ipqsSaved },
    "tellows".takeIf { keys.tellowsSaved },
    "PhoneBlock".takeIf { keys.phoneBlockSaved },
).joinToString(", ")

@Composable
private fun ProviderSettingsScreen(
    viewModel: AppViewModel,
    keys: KeyStatus,
) {
    val checkState by viewModel.providerCheck.collectAsState()
    var testProvider by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Все источники с сохранёнными ключами проверяются параллельно. Повторная проверка использует кэш, пока ответ источника актуален.")
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
    var key by rememberSaveable(provider) { mutableStateOf("") }
    var confirmDelete by rememberSaveable(provider) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(providerLabel(provider), style = MaterialTheme.typography.titleMedium)
            Text(
                if (saved) "Подключён и участвует в проверках" else "Не подключён",
                color = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("Новый ключ или токен") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { viewModel.saveApiKey(provider, key); key = "" }, enabled = key.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Сохранить ключ") }
            OutlinedButton(onClick = onTest, enabled = saved, modifier = Modifier.fillMaxWidth()) { Text("Проверить подключение") }
            if (saved) TextButton(onClick = { confirmDelete = true }) { Text("Удалить ключ") }
            TextButton(onClick = { context.startSafe(Intent(Intent.ACTION_VIEW, Uri.parse(providerUrl(provider)))) }) { Text("Открыть страницу источника") }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Удалить ключ?") }, text = { Text("${providerLabel(provider)} перестанет выполнять внешние проверки, пока ключ не будет добавлен снова.") }, confirmButton = { TextButton(onClick = { viewModel.clearApiKey(provider); confirmDelete = false }) { Text("Удалить") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } })
}

private fun providerUrl(id: String): String = when (id) { "ipqs" -> "https://www.ipqualityscore.com/create-account/phone-validation"; "tellows" -> "https://shop.tellows.de/de/tellows-api-key.html"; else -> "https://phoneblock.net/phoneblock/settings" }

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
fun NumberDetailDialog(
    identity: CallerIdentity,
    onDismiss: () -> Unit,
    viewModel: AppViewModel,
) {
    val context = LocalContext.current
    val personalNumbers by viewModel.personalNumbers.collectAsState()
    val personal = personalNumbers.firstOrNull { it.e164 == identity.e164 }
    var showEditor by remember { mutableStateOf(false) }
    val action = personal?.action ?: identity.personalAction
    val blocked = action == PersonalAction.BLOCK
    val personalSpam = personal?.personalSpam == true || identity.personalSpam
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(identity.displayName ?: identity.e164) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (identity.displayName != null) Text(identity.e164)
                CategoryLabel(identity.category)
                if (action == PersonalAction.ALLOW) Text("Разрешён вашим правилом")
                if (blocked) Text("Заблокирован вашим правилом")
                if (identity.externalSpam) {
                    Text(
                        "Возможный спам${identity.externalSource?.let { " · источник: $it" }.orEmpty()}",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                NumberActionButtons(
                    context = context,
                    e164 = identity.e164,
                    name = identity.displayName,
                    existingContact = identity.nameSource == "contacts",
                    resolveContactUri = { viewModel.findContactUri(identity.e164) },
                )
                HorizontalDivider()
                OutlinedButton(
                    onClick = { showEditor = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Изменить личное правило") }
                OutlinedButton(
                    onClick = {
                        viewModel.setAction(identity.e164, if (blocked) PersonalAction.DEFAULT else PersonalAction.BLOCK)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (blocked) "Разблокировать" else "Блокировать")
                }
                OutlinedButton(
                    onClick = { viewModel.markSpam(identity.e164, !personalSpam) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (personalSpam) "Снять мою пометку спама" else "Это спам")
                }
                Text(
                    if (blocked) "Будущие звонки будут отклоняться. Пометка спама не влияет на блокировку."
                    else "Блокировка отклоняет будущие звонки. Пометка спама только предупреждает.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
    if (showEditor) {
        PersonalNumberDialog(
            viewModel = viewModel,
            initial = personal,
            onDismiss = { showEditor = false },
            onOpenExisting = { showEditor = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NumberActionButtons(
    context: Context,
    e164: String,
    name: String?,
    existingContact: Boolean,
    resolveContactUri: () -> Uri?,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = { context.startSafe(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$e164"))) }, label = { Text("Позвонить") })
        AssistChip(onClick = {
            val digits = e164.filter(Char::isDigit)
            context.startSafe(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")))
        }, label = { Text("WhatsApp") })
        AssistChip(onClick = { context.startSafe(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$e164"))) }, label = { Text("SMS") })
        AssistChip(onClick = {
            val contactUri = if (existingContact) resolveContactUri() else null
            val intent = if (contactUri != null) {
                Intent(Intent.ACTION_VIEW, contactUri)
            } else if (existingContact) {
                Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
            } else {
                Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).apply {
                    putExtra(ContactsContract.Intents.Insert.PHONE, e164)
                    name?.let { putExtra(ContactsContract.Intents.Insert.NAME, it) }
                }
            }
            context.startSafe(intent)
        }, label = { Text(if (existingContact) "Открыть контакт" else "Добавить в контакты") })
    }
}

@Composable
private fun CategoryLabel(category: CallerCategory) {
    val color = when (category) {
        CallerCategory.SPAM -> MaterialTheme.colorScheme.error
        CallerCategory.CONTACT -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(categoryText(category), color = color, style = MaterialTheme.typography.labelMedium)
}

private fun categoryText(category: CallerCategory): String = when (category) {
    CallerCategory.CONTACT -> "Контакт"
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
