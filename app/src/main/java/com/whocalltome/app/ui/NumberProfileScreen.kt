package com.whocalltome.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.whocalltome.app.R
import com.whocalltome.app.data.model.ContactLookupResult
import com.whocalltome.app.data.model.LookupStatus
import com.whocalltome.app.data.model.NumberType
import com.whocalltome.app.data.model.PersonalAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NumberProfileScreen(
    number: String,
    viewModel: AppViewModel,
    roleHeld: Boolean,
    onRequestRole: () -> Unit,
    onBack: () -> Unit,
    onOpenExisting: (String) -> Unit,
    contactsPermissionStatus: PermissionUiStatus,
    onRequestContactsPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val largeText = LocalDensity.current.fontScale >= 1.3f
    val scrollState = key(number) { rememberLazyListState() }
    var historyRoute by rememberSaveable(number) { mutableStateOf(false) }
    var detailsOpen by rememberSaveable(number) { mutableStateOf(false) }
    val historyScrollState = key(number) { rememberLazyListState() }
    val profileNumber = remember(number) { viewModel.normalizeNumber(number) ?: number }
    val records by viewModel.personalNumbers.collectAsState()
    val profileState by viewModel.numberProfileState.collectAsState()
    val historyState by viewModel.numberCallHistory.collectAsState()
    val history = historyState.takeIf { it.e164 == profileNumber }
        ?: NumberCallHistoryUiState(e164 = profileNumber, isLoading = true)
    val profile = profileState.takeIf { it.e164 == profileNumber }
        ?: NumberProfileUiState(e164 = profileNumber, isLoadingLocal = number.isNotBlank())
    val identity = profile.identity
    val record = records.firstOrNull { it.e164 == profileNumber }
    val savedName = record?.personalName ?: identity?.personalName.orEmpty()
    val savedType = record?.numberType ?: identity?.numberType ?: NumberType.UNSPECIFIED
    var numberInput by rememberSaveable(number) { mutableStateOf(profileNumber) }
    var draftName by rememberSaveable(number) { mutableStateOf("") }
    var draftType by rememberSaveable(number) { mutableStateOf(NumberType.UNSPECIFIED) }
    var editing by rememberSaveable(number) { mutableStateOf(number.isBlank()) }
    var saving by remember(number) { mutableStateOf(false) }
    var togglingBlock by remember(number) { mutableStateOf(false) }
    var togglingSpam by remember(number) { mutableStateOf(false) }
    var showDiscard by remember(number) { mutableStateOf(false) }
    var discardLeavesProfile by remember(number) { mutableStateOf(false) }
    var error by remember(number) { mutableStateOf<String?>(null) }

    LaunchedEffect(number, contactsPermissionStatus) {
        if (number.isNotBlank()) {
            viewModel.openNumberProfile(number)
            viewModel.refreshNumberProfile()
        } else viewModel.closeNumberProfile()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, number) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && number.isNotBlank()) {
                viewModel.openNumberProfile(number)
                viewModel.refreshNumberProfile()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(savedName, savedType) {
        if (!editing) {
            draftName = savedName
            draftType = savedType
        }
    }
    val actualName = identity?.displayName
    val isContact = profile.isContact
    val leaveProfile = { viewModel.closeNumberProfile(); onBack() }
    val action = record?.action ?: identity?.personalAction ?: PersonalAction.DEFAULT
    val blocked = action == PersonalAction.BLOCK
    val spam = record?.personalSpam ?: identity?.personalSpam ?: false
    val dirty = if (number.isBlank()) {
        numberInput.isNotBlank() || draftName.isNotBlank() || draftType != NumberType.UNSPECIFIED
    } else {
        draftName.trim() != savedName || draftType != savedType
    }
    val save: () -> Unit = {
        val normalized = viewModel.normalizeNumber(numberInput)
        when {
            normalized == null -> error = "Введите корректный номер с кодом страны"
            number.isBlank() && viewModel.personalNumber(normalized) != null -> onOpenExisting(normalized)
            else -> {
                val leaveAfterSave = showDiscard && discardLeavesProfile
                saving = true
                error = null
                viewModel.savePersonal(
                    rawNumber = normalized,
                    personalName = draftName,
                    numberType = draftType,
                    onSaved = {
                        saving = false
                        showDiscard = false
                        editing = false
                        if (number.isBlank()) onOpenExisting(normalized)
                        else if (leaveAfterSave) leaveProfile()
                    },
                    onError = { saving = false; error = "Не удалось сохранить номер. Попробуйте ещё раз" },
                )
            }
        }
    }
    val back: () -> Unit = {
        when {
            historyRoute -> historyRoute = false
            editing && dirty -> { discardLeavesProfile = true; showDiscard = true }
            editing && number.isNotBlank() -> editing = false
            else -> leaveProfile()
        }
    }
    BackHandler(onBack = back)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (historyRoute) "История звонков" else if (number.isBlank()) "Новый номер" else if (largeText) "Номер" else "Карточка номера", maxLines = 1) },
                windowInsets = WindowInsets(0, 0, 0, 0),
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        if (historyRoute) {
            FullNumberHistory(history, profileNumber, historyScrollState, viewModel::retryNumberCallHistory, Modifier.padding(padding))
        } else LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            state = scrollState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "profile-details") {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (number.isBlank()) {
                        OutlinedTextField(
                            value = numberInput,
                            onValueChange = { numberInput = it; error = null },
                            label = { Text("Номер телефона") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            isError = error != null,
                            supportingText = error?.let { message -> { Text(message) } },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        ProfileHeader(
                            profileNumber, profile, record?.numberType ?: identity?.numberType ?: NumberType.UNSPECIFIED,
                            action, spam, roleHeld, onRequestRole,
                            editLabel = if (!isContact && identity != null) if (savedName.isBlank()) "Задать имя" else "Изменить имя и тип" else null,
                            onEditName = { draftName = savedName; draftType = savedType; editing = true },
                            onRequestContactsAccess = if (contactsPermissionStatus == PermissionUiStatus.SETTINGS_REQUIRED) onOpenAppSettings else onRequestContactsPermission,
                            onRetryContacts = viewModel::refreshNumberProfile,
                        )
                    }

                    if (number.isNotBlank() && profile.isLoadingLocal) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    if (number.isNotBlank() && profile.localError != null) {
                        Text(profile.localError, color = MaterialTheme.colorScheme.error)
                        if (!profile.isInvalidNumber) TextButton(onClick = viewModel::refreshNumberProfile) { Text("Повторить загрузку") }
                    }
                    if (number.isNotBlank() && actualName == null && identity?.contact == ContactLookupResult.PermissionRequired) {
                        Card {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Разрешите доступ, чтобы находить имена в телефонных контактах")
                                TextButton(onClick = if (contactsPermissionStatus == PermissionUiStatus.SETTINGS_REQUIRED) onOpenAppSettings else onRequestContactsPermission) {
                                    Text(if (contactsPermissionStatus == PermissionUiStatus.SETTINGS_REQUIRED) "Открыть настройки" else "Разрешить доступ")
                                }
                            }
                        }
                    }
                    if (number.isNotBlank() && actualName == null && identity?.contact == ContactLookupResult.ReadError) {
                        Text("Не удалось прочитать телефонные контакты", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::refreshNumberProfile) { Text("Повторить") }
                    }
                    if (number.isNotBlank() && actualName == null && profile.historicalName != null) {
                        Column {
                            Text("Имя в журнале звонков", style = MaterialTheme.typography.labelLarge)
                            Text(profile.historicalName)
                        }
                    }

                    if (!isContact && number.isBlank()) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Имя в приложении", style = MaterialTheme.typography.titleMedium)
                                OutlinedTextField(
                                    value = draftName,
                                    onValueChange = { draftName = it },
                                    label = { Text("Имя или название компании") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text("Тип номера", style = MaterialTheme.typography.labelLarge)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(NumberType.PERSONAL to "Личный", NumberType.BUSINESS to "Бизнес").forEach { (type, label) ->
                                        FilterChip(
                                            selected = draftType == type,
                                            onClick = { draftType = if (draftType == type) NumberType.UNSPECIFIED else type },
                                            label = { Text(label) },
                                        )
                                    }
                                }
                                Button(onClick = save, enabled = !saving && (number.isBlank() || dirty), modifier = Modifier.fillMaxWidth()) {
                                    Text(if (saving) "Сохраняем…" else "Сохранить")
                                }
                            }
                        }
                    }
                    if (error != null && number.isNotBlank()) Text(error!!, color = MaterialTheme.colorScheme.error)
                }
            }

            if (number.isNotBlank() && !profile.isInvalidNumber) {
                item(key = "profile-actions") {
                    ProfileActions(profileNumber, actualName, isContact, (identity?.contact as? ContactLookupResult.Found)?.lookupUri)
                }
                item(key = "profile-lookup") {
                    ProfileLookupCard(profile, onCheck = { viewModel.checkNumberProfile() }, onRefresh = { viewModel.checkNumberProfile(force = true) }, onDetails = { detailsOpen = true })
                }
                item(key = "profile-settings") {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        HorizontalDivider()
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Настройки звонков", style = MaterialTheme.typography.titleMedium)
                                ProfileSwitch(
                                    title = "Блокировать звонки",
                                    detail = if (blocked) {
                                        if (roleHeld) "Входящие звонки отклоняются" else "Нужна роль определения звонков"
                                    } else if (action == PersonalAction.ALLOW) "Номер разрешён вами" else "Входящие звонки разрешены",
                                    checked = blocked,
                                    enabled = !togglingBlock && identity != null && !profile.isLoadingLocal,
                                ) { checked ->
                                    togglingBlock = true
                                    viewModel.setAction(profileNumber, if (checked) PersonalAction.BLOCK else PersonalAction.DEFAULT) {
                                        togglingBlock = false
                                    }
                                }
                                if (!roleHeld) {
                                    Text("Для блокировки включите определение звонков", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    TextButton(onClick = onRequestRole) { Text("Включить") }
                                }
                                HorizontalDivider()
                                ProfileSwitch("Пометить как спам", "Предупреждать о спаме", spam, !togglingSpam && identity != null && !profile.isLoadingLocal) { checked ->
                                    togglingSpam = true
                                    viewModel.markSpam(profileNumber, checked) { togglingSpam = false }
                                }
                            }
                        }
                    }
                }
                numberCallHistorySection(history, onOpen = { historyRoute = true }, onRetry = viewModel::retryNumberCallHistory)
            }
            item(key = "profile-bottom") { Spacer(Modifier.height(16.dp)) }
        }
    }
    if (editing && !showDiscard && number.isNotBlank() && !isContact) {
        AlertDialog(
            onDismissRequest = { if (dirty) { discardLeavesProfile = false; showDiscard = true } else editing = false },
            title = { Text("Имя в приложении") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Будет видно только в этом приложении")
                OutlinedTextField(draftName, { draftName = it }, label = { Text("Имя или название компании") }, singleLine = true)
                Text("Тип номера")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(NumberType.PERSONAL to "Личный", NumberType.BUSINESS to "Бизнес").forEach { (type, label) ->
                        FilterChip(selected = draftType == type, onClick = { draftType = if (draftType == type) NumberType.UNSPECIFIED else type }, label = { Text(label) })
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(onClick = save, enabled = !saving && dirty) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { if (dirty) { discardLeavesProfile = false; showDiscard = true } else editing = false }) { Text("Отмена") } },
        )
    }
    if (detailsOpen) {
        ProfileLookupDetails(profile, onDismiss = { detailsOpen = false })
    }
    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text("Сохранить изменения?") },
            text = { Text("Имя или тип номера ещё не сохранены") },
            confirmButton = { TextButton(onClick = save, enabled = !saving) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { showDiscard = false; editing = false; if (discardLeavesProfile) leaveProfile() }, enabled = !saving) { Text("Не сохранять") } },
        )
    }
}

@Composable
private fun ProfileActions(number: String, name: String?, isContact: Boolean, contactUri: String?) {
    val context = LocalContext.current
    val call = { context.startSafeProfile(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))) }
    val whatsapp = {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${number.filter(Char::isDigit)}"))
            .setPackage("com.whatsapp")
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Обычный WhatsApp не установлен", Toast.LENGTH_SHORT).show()
        }
    }
    val sms = { context.startSafeProfile(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number"))) }
    val contact = {
        val uri = if (isContact) contactUri?.let(Uri::parse) else null
        val intent = when {
            uri != null -> Intent(Intent.ACTION_VIEW, uri)
            isContact -> Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
            else -> Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).apply {
                putExtra(ContactsContract.Intents.Insert.PHONE, number)
                name?.let { putExtra(ContactsContract.Intents.Insert.NAME, it) }
            }
        }
        context.startSafeProfile(intent)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 360.dp || LocalDensity.current.fontScale >= 1.3f
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    ProfileAction("Позвонить", Modifier.weight(1f), R.drawable.ic_phone, call)
                    ProfileAction("WhatsApp", Modifier.weight(1f), R.drawable.ic_whatsapp, whatsapp)
                }
                Row {
                    ProfileAction("SMS", Modifier.weight(1f), R.drawable.ic_message, sms)
                    ProfileAction(if (isContact) "Открыть контакт" else "Добавить в контакты", Modifier.weight(1f), if (isContact) R.drawable.ic_person else R.drawable.ic_person_add, contact)
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.SpaceEvenly) {
                ProfileAction("Позвонить", Modifier.weight(1f), R.drawable.ic_phone, call)
                ProfileAction("WhatsApp", Modifier.weight(1f), R.drawable.ic_whatsapp, whatsapp)
                ProfileAction("SMS", Modifier.weight(1f), R.drawable.ic_message, sms)
                ProfileAction(if (isContact) "Открыть контакт" else "Добавить в контакты", Modifier.weight(1f), if (isContact) R.drawable.ic_person else R.drawable.ic_person_add, contact)
            }
        }
    }
}

@Composable
private fun ProfileAction(label: String, modifier: Modifier = Modifier, iconRes: Int, onClick: () -> Unit) {
    Column(
        modifier.clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(48.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(iconRes), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 2, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ProfileSwitch(title: String, detail: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun ProfileLookupCard(profile: NumberProfileUiState, onCheck: () -> Unit, onRefresh: () -> Unit, onDetails: () -> Unit) {
    val now by produceState(initialValue = System.currentTimeMillis(), profile.providers) {
        while (true) { value = System.currentTimeMillis(); delay(15_000) }
    }
    val summary = presentProfileLookup(profile, now)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Проверка номера", style = MaterialTheme.typography.titleMedium)
            Text(summary.title, color = if (summary.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            summary.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (profile.isChecking) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (summary.warning && profile.hasPartialFailure) Text("Проверка неполная · ответили ${summary.answered} из ${summary.total} источников")
            if (summary.action == "Обновить доступные") Text("Источники на паузе будут пропущены", style = MaterialTheme.typography.bodySmall)
            summary.retryAt?.let { Text("Повторить после ${formatProfileDateTime(it, now)}", style = MaterialTheme.typography.bodySmall) }
            if (profile.providers.any { it.status == LookupStatus.NOT_CONFIGURED || it.nextAttemptAt == Long.MAX_VALUE }) {
                Text("Проверьте настройки источников", style = MaterialTheme.typography.bodySmall)
            }
            profile.lookupError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compact = maxWidth < 340.dp || LocalDensity.current.fontScale >= 1.3f
                Row(verticalAlignment = Alignment.CenterVertically) {
                summary.action?.let { action ->
                    TextButton(onClick = if (profile.hasChecked) onRefresh else onCheck, enabled = summary.canRefresh && !profile.isChecking) { Text(action) }
                }
                TextButton(onClick = onDetails) { Text(if (compact) "Подробнее" else "Источники и детали") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileLookupDetails(profile: NumberProfileUiState, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Источники и детали", style = MaterialTheme.typography.titleLarge)
            val sources = (profile.providers.map { it.source } +
                profile.identity?.externalNames.orEmpty().map { it.source } +
                profile.identity?.externalReputations.orEmpty().map { it.source }).distinct()
            if (sources.isEmpty()) Text("Результатов проверки пока нет")
            sources.forEach { source ->
                val provider = profile.providers.firstOrNull { it.source == source }
                val name = profile.identity?.externalNames?.firstOrNull { it.source == source }
                val reputation = profile.identity?.externalReputations?.firstOrNull { it.source == source }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(providerLabel(source), style = MaterialTheme.typography.titleMedium)
                    if (name != null) Text("Сохранённое имя: ${name.value}")
                    if (reputation != null) Text(if (reputation.isSpam) "Сохранено предупреждение о спаме" else "Сохранены сведения о репутации без отметки спама")
                    if (provider != null) {
                        Text("Последняя попытка: ${providerResultLabel(provider)}${if (provider.fromCache) " · из кэша" else ""}")
                        provider.checkedAt?.let { Text("Проверено: ${formatProfileDateTime(it)}") }
                        provider.nextAttemptAt?.takeIf { it != Long.MAX_VALUE }?.let { Text("Повторить после ${formatProfileDateTime(it)}") }
                    }
                    HorizontalDivider()
                }
            }
            Text("При проверке номер передаётся выбранному источнику и PhoneBlock. Внешняя оценка спама служит предупреждением.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal fun profileProviderStatusText(
    provider: com.whocalltome.app.data.model.ProviderLookupStatus,
    isChecking: Boolean,
): String {
    val status = when (provider.status) {
        null -> if (isChecking) "Проверяется" else "Проверка не завершена"
        LookupStatus.FOUND -> "Данные найдены"
        LookupStatus.NOT_FOUND -> "Данных нет"
        LookupStatus.NOT_CONFIGURED -> "Источник не настроен"
        LookupStatus.NETWORK_ERROR -> "Нет сети"
        LookupStatus.QUOTA_EXHAUSTED -> "Лимит запросов"
        LookupStatus.PROVIDER_ERROR -> "Ошибка источника"
    }
    return if (provider.fromCache) "$status · Из кэша" else status
}

private fun Context.startSafeProfile(intent: Intent) {
    try { startActivity(intent) } catch (_: ActivityNotFoundException) { }
}
