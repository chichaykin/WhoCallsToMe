package com.whocalltome.app.service

import com.whocalltome.app.data.model.LookupStatus
import com.whocalltome.app.data.model.LookupUpdate

/** Separate progress from identity: a successful check can leave the identity unchanged. */
internal data class CallerLookupNotificationState(
    val summary: String,
    val details: List<String> = emptyList(),
    val checking: Boolean = false,
) {
    companion object {
        fun from(update: LookupUpdate, interrupted: Boolean = false): CallerLookupNotificationState {
            val identity = update.identity
            if (identity.shouldBlock) return CallerLookupNotificationState("Заблокировано по вашему правилу")
            if (identity.nameSource == "contacts") {
                return CallerLookupNotificationState("Номер из контактов · Онлайн-проверка не выполнялась")
            }
            val providers = update.providers
            val checking = !update.isComplete && !interrupted
            val successful = providers.filter {
                it.status == LookupStatus.FOUND || it.status == LookupStatus.NOT_FOUND
            }
            val finished = providers.count { it.status != null }
            val sourceDetails = providers.map { provider ->
                val state = when (provider.status) {
                    null -> if (checking) "ожидаем ответ…" else "ответ не получен"
                    LookupStatus.FOUND -> "данные получены"
                    LookupStatus.NOT_FOUND -> "нет данных о номере"
                    LookupStatus.NETWORK_ERROR -> "нет связи"
                    LookupStatus.QUOTA_EXHAUSTED -> "квота исчерпана"
                    LookupStatus.NOT_CONFIGURED -> "не подключён"
                    LookupStatus.PROVIDER_ERROR -> "ошибка источника"
                }
                "${providerName(provider.source)}: $state" +
                    if (provider.fromCache) " (сохранённый результат)" else ""
            }
            val summary = when {
                checking && providers.isEmpty() -> "Проверяем номер…"
                checking -> "Проверяем · Завершено $finished/${providers.size}"
                interrupted -> "Проверка неполная · Ожидание прекращено"
                providers.isEmpty() -> "Онлайн-проверка недоступна · Источники не подключены"
                successful.size != providers.size -> "Проверка неполная · Ответили ${successful.size}/${providers.size}"
                else -> {
                    val verdict = when {
                        identity.shouldWarn -> "Возможный спам"
                        successful.all { it.status == LookupStatus.NOT_FOUND } -> "Нет данных о номере"
                        identity.externalReputations.none { reputation ->
                            successful.any { it.source == reputation.source } &&
                                (reputation.score != null || reputation.isSpam)
                        } -> "Нет оценки спама"
                        else -> "Признаков спама не найдено"
                    }
                    val prefix = if (successful.all { it.fromCache }) "Сохранено" else "Проверено"
                    "$prefix ${successful.size}/${providers.size} · $verdict"
                }
            }
            return CallerLookupNotificationState(summary, sourceDetails, checking)
        }
    }
}

internal fun providerName(source: String): String = when (source) {
    "ipqs" -> "IPQualityScore"
    "phoneblock" -> "PhoneBlock"
    "tellows" -> "tellows"
    else -> source
}
