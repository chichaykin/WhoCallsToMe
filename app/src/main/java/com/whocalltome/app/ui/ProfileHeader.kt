package com.whocalltome.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.whocalltome.app.R
import com.whocalltome.app.data.model.ContactLookupResult
import com.whocalltome.app.data.model.NumberType
import com.whocalltome.app.data.model.PersonalAction

@Composable
internal fun ProfileHeader(
    number: String,
    profile: NumberProfileUiState,
    type: NumberType,
    action: PersonalAction,
    personalSpam: Boolean,
    roleHeld: Boolean,
    onRequestRole: () -> Unit,
    editLabel: String? = null,
    onEditName: (() -> Unit)? = null,
    onRequestContactsAccess: (() -> Unit)? = null,
    onRetryContacts: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val identity = profile.identity
    val name = identity?.displayName?.takeIf { it.isNotBlank() }
    val formatted = formatDisplayNumber(number)
    val noNameLabel = when {
        profile.isLoadingLocal -> "Загружаем данные…"
        profile.localError != null -> "Данные недоступны"
        profile.isContact -> "Контакт без имени"
        identity?.contact == ContactLookupResult.PermissionRequired -> "Нет доступа к контактам"
        identity?.contact == ContactLookupResult.ReadError -> "Контакты недоступны"
        else -> "Имя не определено"
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(if (type == NumberType.BUSINESS) R.drawable.ic_business else R.drawable.ic_person),
                    contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(name ?: if (profile.isLoadingLocal || profile.localError != null) noNameLabel else formatted,
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Text(if (name == null && !profile.isLoadingLocal && profile.localError == null) noNameLabel else formatted,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                if (name != null) {
                    val source = when {
                        profile.isContact -> "Из контактов"
                        !identity?.personalName.isNullOrBlank() -> "Имя задано вами"
                        identity?.nameSource != null -> "По данным: ${providerLabel(identity.nameSource)}"
                        else -> null
                    }
                    source?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                }
                when (type) {
                    NumberType.PERSONAL -> Text("Личный", style = MaterialTheme.typography.labelMedium)
                    NumberType.BUSINESS -> Text("Бизнес", style = MaterialTheme.typography.labelMedium)
                    else -> Unit
                }
            }
            IconButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Номер", number))
                Toast.makeText(context, "Номер скопирован", Toast.LENGTH_SHORT).show()
            }) { Icon(painterResource(R.drawable.ic_copy), contentDescription = "Копировать номер") }
        }
        if (identity?.contact == ContactLookupResult.PermissionRequired && name != null) {
            Text("Телефонные контакты недоступны", style = MaterialTheme.typography.bodySmall)
            if (onRequestContactsAccess != null) TextButton(onClick = onRequestContactsAccess) { Text("Разрешить доступ к контактам") }
        }
        if (identity?.contact == ContactLookupResult.ReadError && name != null) {
            Text("Не удалось прочитать телефонные контакты", style = MaterialTheme.typography.bodySmall)
            if (onRetryContacts != null) TextButton(onClick = onRetryContacts) { Text("Повторить чтение контактов") }
        }
        if (editLabel != null && onEditName != null) TextButton(onClick = onEditName) { Text(editLabel) }
        if (action == PersonalAction.BLOCK) Text(if (roleHeld) "Заблокирован вами" else "Блокировка настроена, но не действует",
            color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        if (action == PersonalAction.BLOCK && !roleHeld) TextButton(onClick = onRequestRole) { Text("Включить определение звонков") }
        if (action == PersonalAction.ALLOW) Text("Разрешён вами", fontWeight = FontWeight.SemiBold)
        if (personalSpam) Text("Личная отметка: спам", color = MaterialTheme.colorScheme.error)
        if (identity?.externalSpam == true) {
            Text("Возможный спам", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            Text("По данным: ${identity.externalReputations.filter { it.isSpam }.joinToString { providerLabel(it.source) }.ifBlank { identity.externalSource?.let(::providerLabel) ?: "внешнего источника" }}",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal fun formatDisplayNumber(number: String): String = runCatching {
    val util = PhoneNumberUtil.getInstance()
    util.format(util.parse(number, null), PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
}.getOrDefault(number)
