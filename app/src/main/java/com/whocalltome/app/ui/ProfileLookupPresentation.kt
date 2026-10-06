package com.whocalltome.app.ui

import com.whocalltome.app.data.model.LookupStatus
import com.whocalltome.app.data.model.ProviderLookupStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class ProfileLookupPresentation(
    val title: String,
    val subtitle: String? = null,
    val warning: Boolean = false,
    val action: String? = null,
    val canRefresh: Boolean = false,
    val retryAt: Long? = null,
    val answered: Int = 0,
    val total: Int = 0,
)

internal fun presentProfileLookup(profile: NumberProfileUiState, now: Long): ProfileLookupPresentation {
    val identity = profile.identity
    val expected = profile.providers
    val configured = expected.filter { it.status != LookupStatus.NOT_CONFIGURED }
    val answered = expected.count { it.status == LookupStatus.FOUND || it.status == LookupStatus.NOT_FOUND }
    val failed = expected.count { it.status !in setOf(LookupStatus.FOUND, LookupStatus.NOT_FOUND) }
    val hasFailure = expected.any { it.status != null && it.status !in setOf(LookupStatus.FOUND, LookupStatus.NOT_FOUND) }
    val paused = configured.filter { it.nextAttemptAt != null && it.nextAttemptAt > now }
    val refreshable = configured.count { it !in paused }
    val retryAt = paused.mapNotNull { it.nextAttemptAt?.takeIf { time -> time != Long.MAX_VALUE } }.minOrNull()
    val hasSaved = identity?.checkedAt != null || !identity?.externalNames.isNullOrEmpty() || !identity?.externalReputations.isNullOrEmpty()
    val hasSpam = identity?.externalSpam == true
    val title = when {
        profile.isLoadingLocal -> "Загружаем данные…"
        profile.isChecking -> "Проверяем номер…"
        !profile.hasChecked && hasSaved -> "Сохранённые результаты"
        !profile.hasChecked -> "Проверка не выполнялась"
        hasSpam -> "Есть предупреждение о спаме"
        expected.isEmpty() && profile.lookupError != null -> "Не удалось проверить номер"
        expected.isNotEmpty() && configured.isEmpty() -> "Источники не настроены"
        expected.all { it.status == null } -> "Проверка не завершена"
        answered > 0 && failed > 0 -> "Проверка неполная"
        answered == 0 && failed > 0 -> "Не удалось проверить номер"
        !identity?.externalReputations.isNullOrEmpty() -> "В полученных данных нет отметки спама"
        !identity?.externalNames.isNullOrEmpty() -> "Найдено имя; данных о репутации нет"
        answered > 0 -> "В источниках нет данных"
        hasSaved -> "Сохранённые результаты"
        else -> "Проверка не завершена"
    }
    val subtitle = when {
        profile.isChecking -> null
        answered > 0 && failed > 0 -> "Ответили $answered из ${expected.size} источников"
        (hasFailure || profile.lookupError != null) && hasSaved -> "Сохранённые данные доступны; последняя попытка завершилась ошибкой"
        !profile.hasChecked && hasSaved -> identity?.checkedAt?.let { "Сохранено: ${formatProfileDateTime(it, now)}" }
        else -> null
    }
    val action = when {
        profile.isChecking || identity == null -> null
        !profile.hasChecked -> "Проверить номер"
        expected.isEmpty() && profile.lookupError != null -> "Повторить"
        refreshable == 0 -> null
        paused.isNotEmpty() -> "Обновить доступные"
        else -> "Обновить"
    }
    return ProfileLookupPresentation(title, subtitle, hasSpam, action, action != null,
        if (refreshable == 0) retryAt else null, answered, expected.size)
}

internal fun formatProfileDateTime(timestamp: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    val dateTime = Instant.ofEpochMilli(timestamp).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val time = dateTime.format(DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("ru")))
    return when (dateTime.toLocalDate()) {
        today -> "Сегодня, $time"
        today.minusDays(1) -> "Вчера, $time"
        else -> {
            val pattern = if (dateTime.year == today.year) "d MMMM" else "d MMMM yyyy"
            "${dateTime.format(DateTimeFormatter.ofPattern(pattern, Locale.forLanguageTag("ru")))}, $time"
        }
    }
}

internal fun providerResultLabel(provider: ProviderLookupStatus): String = when (provider.status) {
    LookupStatus.FOUND -> "Данные найдены"
    LookupStatus.NOT_FOUND -> "Записей нет"
    LookupStatus.NOT_CONFIGURED -> "Источник не настроен"
    LookupStatus.NETWORK_ERROR -> "Ошибка сети"
    LookupStatus.QUOTA_EXHAUSTED -> "Лимит запросов"
    LookupStatus.PROVIDER_ERROR -> "Ошибка источника"
    null -> "Проверка не завершена"
}
