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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    var historyExpanded by rememberSaveable(number) { mutableStateOf(false) }
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
    val blocked = (record?.action ?: identity?.personalAction) == PersonalAction.BLOCK
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
                val leaveAfterSave = showDiscard
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
        if (editing && dirty) showDiscard = true else leaveProfile()
    }
    BackHandler(onBack = back)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (number.isBlank()) "Новый номер" else if (largeText) "Номер" else "Карточка номера", maxLines = 1) },
                windowInsets = WindowInsets(0, 0, 0, 0),
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
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
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val compactHeader = maxWidth < 360.dp || largeText
                            val typeLabel = when (record?.numberType ?: identity?.numberType) {
                                NumberType.PERSONAL -> "Личный"
                                NumberType.BUSINESS -> "Бизнес"
                                else -> null
                            }
                            val blockLabel = if (blocked) {
                                if (roleHeld) "Заблокирован" else "Блокировка настроена"
                            } else null
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                ) {
                                    Box(
                                        Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            painter = painterResource(
                                                if ((record?.numberType ?: identity?.numberType) == NumberType.BUSINESS) R.drawable.ic_business
                                                else R.drawable.ic_person,
                                            ),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(32.dp),
                                        )
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            actualName ?: when {
                                                profile.isLoadingLocal -> "Загружаем данные…"
                                                profile.localError != null -> "Данные недоступны"
                                                isContact -> "Контакт без имени"
                                                identity?.contact == ContactLookupResult.PermissionRequired -> "Нет доступа к контактам"
                                                identity?.contact == ContactLookupResult.ReadError -> "Контакты недоступны"
                                                else -> "Неизвестный номер"
                                            },
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (!compactHeader) {
                                            Text(number, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                            if (blockLabel != null) Text(blockLabel, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                                            if (typeLabel != null) Text(typeLabel, style = MaterialTheme.typography.labelLarge)
                                        }
                                    }
                                }
                                if (compactHeader) {
                                    Text(number, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    if (blockLabel != null) Text(blockLabel, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                                    if (typeLabel != null) Text(typeLabel, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }

                    if (number.isNotBlank() && profile.isLoadingLocal) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    if (number.isNotBlank() && profile.localError != null) {
                        Text(profile.localError, color = MaterialTheme.colorScheme.error)
                        if (!profile.isInvalidNumber) TextButton(onClick = viewModel::refreshNumberProfile) { Text("Повторить загрузку") }
                    }
                    if (number.isNotBlank() && identity?.contact == ContactLookupResult.PermissionRequired) {
                        Card {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Нужен доступ к телефонным контактам, чтобы показать имя")
                                TextButton(onClick = if (contactsPermissionStatus == PermissionUiStatus.SETTINGS_REQUIRED) onOpenAppSettings else onRequestContactsPermission) {
                                    Text(if (contactsPermissionStatus == PermissionUiStatus.SETTINGS_REQUIRED) "Открыть настройки" else "Разрешить доступ")
                                }
                            }
                        }
                    }
                    if (number.isNotBlank() && identity?.contact == ContactLookupResult.ReadError) {
                        Text("Не удалось прочитать телефонные контакты", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::refreshNumberProfile) { Text("Повторить") }
                    }
                    if (number.isNotBlank() && actualName == null && profile.historicalName != null) {
                        Column {
                            Text("Имя в журнале звонков", style = MaterialTheme.typography.labelLarge)
                            Text(profile.historicalName)
                        }
                    }

                    if (!isContact && (number.isBlank() || (editing && identity != null))) {
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
                    } else if (!isContact && number.isNotBlank() && identity != null) {
                        Column {
                            TextButton(onClick = { draftName = savedName; draftType = savedType; editing = true }) {
                                Text(if (savedName.isBlank()) "Сохранить имя в приложении" else "Изменить имя и тип")
                            }
                            if (savedName.isNotBlank()) {
                                Text("Имя сохранено в приложении", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (isContact && number.isNotBlank()) {
                        Text("Из контактов", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (error != null && number.isNotBlank()) Text(error!!, color = MaterialTheme.colorScheme.error)
                }
            }

            if (number.isNotBlank() && !profile.isInvalidNumber) {
                item(key = "profile-actions") {
                    ProfileActions(profileNumber, actualName, isContact, (identity?.contact as? ContactLookupResult.Found)?.lookupUri)
                }
                numberCallHistorySection(
                    history = history,
                    expanded = historyExpanded,
                    onToggle = { historyExpanded = !historyExpanded },
                    onRetry = viewModel::retryNumberCallHistory,
                )
                item(key = "profile-lookup") {
                    ProfileLookupCard(profile, onCheck = { viewModel.checkNumberProfile() }, onRefresh = { viewModel.checkNumberProfile(force = true) })
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
                                        if (roleHeld) "Входящие звонки отклоняются" else "Блокировка включена в приложении"
                                    } else "Входящие звонки разрешены",
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
                if (identity?.externalSpam == true) {
                    item(key = "profile-spam") {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    "Возможный спам",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(
                                    "По данным: ${identity?.externalReputations?.filter { it.isSpam }?.joinToString { providerLabel(it.source) }?.ifBlank { identity?.externalSource?.let(::providerLabel) ?: "внешнего источника" }}",
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }
            }
            item(key = "profile-bottom") { Spacer(Modifier.height(16.dp)) }
        }
    }
    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text("Сохранить изменения?") },
            text = { Text("Имя или тип номера ещё не сохранены") },
            confirmButton = { TextButton(onClick = save, enabled = !saving) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { showDiscard = false; leaveProfile() }, enabled = !saving) { Text("Не сохранять") } },
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
                    ProfileAction(null, "Позвонить", Modifier.weight(1f), R.drawable.ic_phone, call)
                    ProfileAction("WA", "WhatsApp", Modifier.weight(1f), onClick = whatsapp)
                }
                Row {
                    ProfileAction(null, "SMS", Modifier.weight(1f), R.drawable.ic_message, sms)
                    ProfileAction("＋", if (isContact) "Открыть контакт" else "В контакты телефона", Modifier.weight(1f), onClick = contact)
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.SpaceEvenly) {
                ProfileAction(null, "Позвонить", Modifier.weight(1f), R.drawable.ic_phone, call)
                ProfileAction("WA", "WhatsApp", Modifier.weight(1f), onClick = whatsapp)
                ProfileAction(null, "SMS", Modifier.weight(1f), R.drawable.ic_message, sms)
                ProfileAction("＋", if (isContact) "Открыть контакт" else "В контакты телефона", Modifier.weight(1f), onClick = contact)
            }
        }
    }
}

@Composable
private fun ProfileAction(symbol: String?, label: String, modifier: Modifier = Modifier, iconRes: Int? = null, onClick: () -> Unit) {
    Column(
        modifier.clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(48.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (iconRes != null) Icon(painterResource(iconRes), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            else Text(symbol.orEmpty(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
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
private fun ProfileLookupCard(profile: NumberProfileUiState, onCheck: () -> Unit, onRefresh: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Проверка по базам", style = MaterialTheme.typography.titleMedium)
            when {
                profile.isLoadingLocal -> Text("Сначала загружаем данные на устройстве")
                profile.isChecking -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Проверяем номер…")
                }
                !profile.hasChecked -> Text("Нажмите «Проверить по базам», чтобы получить актуальный результат")
            }
            if (profile.hasPartialFailure) {
                Text("Получены данные не от всех источников", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            profile.identity?.externalNames?.forEach { name ->
                Text("${providerLabel(name.source)} · ${name.value}")
            }
            profile.identity?.externalReputations?.forEach { reputation ->
                Text("${providerLabel(reputation.source)} · ${if (reputation.isSpam) "Возможный спам" else "Репутация найдена"}")
                reputation.score?.let { Text("Оценка источника: $it") }
            }
            profile.providers.forEach { provider ->
                Text("${providerLabel(provider.source)} · ${profileProviderStatusText(provider, profile.isChecking)}")
                provider.checkedAt?.let { Text("Проверено: ${formatProfileTime(it)}", style = MaterialTheme.typography.bodySmall) }
                provider.nextAttemptAt?.takeIf { it != Long.MAX_VALUE }?.let {
                    Text("Повторная попытка доступна: ${formatProfileTime(it)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (profile.providers.isEmpty() && profile.identity?.checkedAt != null) {
                Text("Сохранённые данные · ${formatProfileTime(profile.identity.checkedAt)}", style = MaterialTheme.typography.bodySmall)
            }
            profile.lookupError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(
                "При проверке номер передаётся выбранному источнику и PhoneBlock. Внешняя оценка спама служит предупреждением.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = if (profile.hasChecked) onRefresh else onCheck,
                enabled = profile.identity != null && !profile.isLoadingLocal && !profile.isChecking,
            ) { Text(if (profile.hasChecked) "Обновить" else "Проверить по базам") }
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

private fun formatProfileTime(timestamp: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        .format(java.util.Date(timestamp))

private fun Context.startSafeProfile(intent: Intent) {
    try { startActivity(intent) } catch (_: ActivityNotFoundException) { }
}
