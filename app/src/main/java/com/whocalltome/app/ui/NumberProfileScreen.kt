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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.whocalltome.app.data.model.CallerIdentity
import com.whocalltome.app.data.model.NumberType
import com.whocalltome.app.data.model.PersonalAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NumberProfileScreen(
    number: String,
    viewModel: AppViewModel,
    roleHeld: Boolean,
    onRequestRole: () -> Unit,
    onBack: () -> Unit,
    onOpenExisting: (String) -> Unit,
) {
    val largeText = LocalDensity.current.fontScale >= 1.3f
    val records by viewModel.personalNumbers.collectAsState()
    val lookup by viewModel.lookupState.collectAsState()
    val record = records.firstOrNull { it.e164 == number }
    var identity by remember(number) { mutableStateOf<CallerIdentity?>(null) }
    var numberInput by rememberSaveable(number) { mutableStateOf(number) }
    var draftName by rememberSaveable(number) { mutableStateOf("") }
    var draftType by rememberSaveable(number) { mutableStateOf(NumberType.UNSPECIFIED) }
    var editing by rememberSaveable(number) { mutableStateOf(number.isBlank()) }
    var saving by remember(number) { mutableStateOf(false) }
    var togglingBlock by remember(number) { mutableStateOf(false) }
    var togglingSpam by remember(number) { mutableStateOf(false) }
    var showDiscard by remember(number) { mutableStateOf(false) }
    var error by remember(number) { mutableStateOf<String?>(null) }

    LaunchedEffect(number, records, lookup) {
        if (number.isNotBlank()) viewModel.loadIdentity(number) { identity = it }
    }
    LaunchedEffect(record?.personalName, record?.numberType) {
        if (!editing) {
            draftName = record?.personalName.orEmpty()
            draftType = record?.numberType ?: NumberType.UNSPECIFIED
        }
    }
    LaunchedEffect(identity?.e164, identity?.displayName, identity?.nameSource) {
        if (number.isNotBlank() && identity != null && identity?.displayName == null && record?.personalName.isNullOrBlank()) {
            editing = true
        }
    }

    val actualName = identity?.displayName
    val isContact = identity?.nameSource == "contacts"
    val blocked = (record?.action ?: identity?.personalAction) == PersonalAction.BLOCK
    val spam = record?.personalSpam ?: identity?.personalSpam ?: false
    val dirty = if (number.isBlank()) {
        numberInput.isNotBlank() || draftName.isNotBlank() || draftType != NumberType.UNSPECIFIED
    } else {
        draftName.trim() != record?.personalName.orEmpty() || draftType != (record?.numberType ?: NumberType.UNSPECIFIED)
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
                        else if (leaveAfterSave) onBack()
                    },
                    onError = { saving = false; error = "Не удалось сохранить номер. Попробуйте ещё раз" },
                )
            }
        }
    }
    val back: () -> Unit = {
        if (editing && dirty) showDiscard = true else onBack()
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
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
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
                                    actualName ?: "Неизвестный номер",
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

            if (!isContact && (editing || number.isBlank())) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Как сохранить номер", style = MaterialTheme.typography.titleMedium)
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
            } else if (!isContact && number.isNotBlank()) {
                Column {
                    TextButton(onClick = { draftName = record?.personalName.orEmpty(); draftType = record?.numberType ?: NumberType.UNSPECIFIED; editing = true }) {
                        Text("Изменить имя и тип")
                    }
                    if (!record?.personalName.isNullOrBlank()) {
                        Text("Имя сохранено в приложении", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (isContact && number.isNotBlank()) {
                Text("Имя из телефонных контактов", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (error != null && number.isNotBlank()) Text(error!!, color = MaterialTheme.colorScheme.error)

            if (number.isNotBlank()) {
                ProfileActions(number, actualName, isContact, viewModel)
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
                            enabled = !togglingBlock,
                        ) { checked ->
                            togglingBlock = true
                            viewModel.setAction(number, if (checked) PersonalAction.BLOCK else PersonalAction.DEFAULT) {
                                togglingBlock = false
                            }
                        }
                        if (!roleHeld) {
                            Text("Для блокировки включите определение звонков", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = onRequestRole) { Text("Включить") }
                        }
                        HorizontalDivider()
                        ProfileSwitch("Пометить как спам", "Предупреждать о спаме", spam, !togglingSpam) { checked ->
                            togglingSpam = true
                            viewModel.markSpam(number, checked) { togglingSpam = false }
                        }
                    }
                }
                if (identity?.externalSpam == true) {
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
            Spacer(Modifier.height(16.dp))
        }
    }
    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text("Сохранить изменения?") },
            text = { Text("Имя или тип номера ещё не сохранены") },
            confirmButton = { TextButton(onClick = save, enabled = !saving) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { showDiscard = false; onBack() }, enabled = !saving) { Text("Не сохранять") } },
        )
    }
}

@Composable
private fun ProfileActions(number: String, name: String?, isContact: Boolean, viewModel: AppViewModel) {
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
        val uri = if (isContact) viewModel.findContactUri(number) else null
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

private fun Context.startSafeProfile(intent: Intent) {
    try { startActivity(intent) } catch (_: ActivityNotFoundException) { }
}
