package com.whocalltome.app.ui

import com.whocalltome.app.data.db.CallRecordEntity
import com.whocalltome.app.data.model.CallerCategory
import com.whocalltome.app.data.model.CallerIdentity
import com.whocalltome.app.data.model.ExternalName
import com.whocalltome.app.data.model.ExternalReputation
import com.whocalltome.app.data.model.LookupStatus
import com.whocalltome.app.data.model.ProviderLookupStatus
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ProfileLookupPresentationTest {
    private val number = "+12025550100"
    private val identity = CallerIdentity(number, null, CallerCategory.UNKNOWN, "unknown")
    private val now = Instant.parse("2026-10-06T13:00:00Z").toEpochMilli()

    @Test fun summarizesProviderOutcomesWithoutCallingZeroScoreSafe() {
        val answered = ProviderLookupStatus("phoneblock", LookupStatus.FOUND)
        val failed = ProviderLookupStatus("ipqs", LookupStatus.NETWORK_ERROR)
        val partial = NumberProfileUiState(identity = identity, hasChecked = true, providers = listOf(answered, failed))
        assertEquals("Проверка неполная", presentProfileLookup(partial, now).title)
        assertEquals("Ответили 1 из 2 источников", presentProfileLookup(partial, now).subtitle)
        val warned = partial.copy(identity = identity.copy(externalSpam = true))
        assertEquals("Есть предупреждение о спаме", presentProfileLookup(warned, now).title)
        assertEquals("Ответили 1 из 2 источников", presentProfileLookup(warned, now).subtitle)

        val reputation = partial.copy(identity = identity.copy(externalReputations = listOf(ExternalReputation("phoneblock", 0, false))),
            providers = listOf(answered))
        assertEquals("В полученных данных нет отметки спама", presentProfileLookup(reputation, now).title)
        assertEquals("Найдено имя; данных о репутации нет", presentProfileLookup(reputation.copy(
            identity = identity.copy(externalNames = listOf(ExternalName("ipqs", "Synthetic")))), now).title)
        assertEquals("В источниках нет данных", presentProfileLookup(partial.copy(providers = listOf(
            ProviderLookupStatus("ipqs", LookupStatus.NOT_FOUND))), now).title)
        val missingConfig = partial.copy(providers = listOf(answered, ProviderLookupStatus("ipqs", LookupStatus.NOT_CONFIGURED)))
        assertEquals("Проверка неполная", presentProfileLookup(missingConfig, now).title)
        assertEquals("Ответили 1 из 2 источников", presentProfileLookup(missingConfig, now).subtitle)
        assertEquals("Источники не настроены", presentProfileLookup(missingConfig.copy(
            providers = listOf(ProviderLookupStatus("ipqs", LookupStatus.NOT_CONFIGURED))), now).title)
    }

    @Test fun savedEvidenceAndRetryAreShownWithoutOverridingBackoff() {
        val saved = identity.copy(externalReputations = listOf(ExternalReputation("phoneblock", 0, false)), checkedAt = now - 1000)
        assertEquals("Сохранённые результаты", presentProfileLookup(NumberProfileUiState(identity = saved), now).title)
        val pause = now + 60_000
        val failed = ProviderLookupStatus("ipqs", LookupStatus.NETWORK_ERROR, nextAttemptAt = pause)
        val state = NumberProfileUiState(identity = saved, hasChecked = true, providers = listOf(failed))
        assertNull(presentProfileLookup(state, now).action)
        assertEquals(pause, presentProfileLookup(state, now).retryAt)
        assertEquals("Обновить", presentProfileLookup(state, pause).action)
        val mixed = state.copy(providers = listOf(failed, ProviderLookupStatus("phoneblock", LookupStatus.FOUND)))
        assertEquals("Обновить доступные", presentProfileLookup(mixed, now).action)
        assertEquals("Обновить", presentProfileLookup(mixed, pause).action)
        val transportFailure = NumberProfileUiState(identity = identity, hasChecked = true, lookupError = "Не удалось проверить номер")
        assertEquals("Не удалось проверить номер", presentProfileLookup(transportFailure, now).title)
        assertEquals("Повторить", presentProfileLookup(transportFailure, now).action)
    }

    @Test fun datesUseDeviceZoneAndYearAcrossMidnight() {
        val zone = ZoneId.of("Asia/Singapore")
        val midnight = Instant.parse("2026-10-05T16:30:00Z").toEpochMilli()
        assertEquals("Сегодня, 00:30", formatProfileDateTime(midnight, midnight, zone))
        assertEquals("Вчера, 00:30", formatProfileDateTime(midnight, midnight + 86_400_000, zone))
        val lastYear = Instant.parse("2025-12-31T16:30:00Z").toEpochMilli()
        assertEquals("1 января 2026, 00:30", formatProfileDateTime(lastYear, Instant.parse("2027-01-02T00:00:00Z").toEpochMilli(), zone))
    }

    @Test fun previewContainsOnlyThreeNewestCalls() {
        val calls = (1L..5L).map { CallRecordEntity(it, number, "INCOMING", eventAt = it) }
        assertEquals(listOf(5L, 4L, 3L), recentProfileCalls(calls).map { it.id })
    }
}
