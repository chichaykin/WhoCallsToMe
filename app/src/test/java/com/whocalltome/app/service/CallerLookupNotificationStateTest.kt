package com.whocalltome.app.service

import com.whocalltome.app.data.model.CallerCategory
import com.whocalltome.app.data.model.CallerIdentity
import com.whocalltome.app.data.model.ExternalReputation
import com.whocalltome.app.data.model.LookupStatus
import com.whocalltome.app.data.model.LookupUpdate
import com.whocalltome.app.data.model.PersonalAction
import com.whocalltome.app.data.model.ProviderLookupStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerLookupNotificationStateTest {
    private val identity = CallerIdentity(
        e164 = "+6500000000",
        displayName = null,
        category = CallerCategory.UNKNOWN,
        source = "unknown",
    )

    @Test
    fun unchangedIdentityStillShowsProgressAndCompletion() {
        val pending = listOf(ProviderLookupStatus("ipqs"), ProviderLookupStatus("phoneblock"))
        val first = pending.toMutableList().apply { this[0] = ProviderLookupStatus("ipqs", LookupStatus.NOT_FOUND) }
        val all = first.toMutableList().apply { this[1] = ProviderLookupStatus("phoneblock", LookupStatus.NOT_FOUND) }
        val start = state(pending)
        val partial = state(first)
        val complete = state(all, complete = true)

        assertTrue(start.checking)
        assertTrue(start.summary.contains("0/2"))
        assertTrue(partial.checking)
        assertTrue(partial.summary.contains("1/2"))
        assertTrue(partial.details.any { it == "PhoneBlock: ожидаем ответ…" })
        assertNotEquals(start, partial)
        assertNotEquals(partial, complete)
        assertFalse(complete.checking)
        assertEquals("Проверено 2/2 · Нет данных о номере", complete.summary)
    }

    @Test
    fun successfulReputationCheckIsNotCalledSafe() {
        val update = LookupUpdate(
            identity.copy(externalReputations = listOf(ExternalReputation("ipqs", 0, false))),
            listOf(ProviderLookupStatus("ipqs", LookupStatus.FOUND)),
            isComplete = true,
        )
        assertEquals("Проверено 1/1 · Признаков спама не найдено", CallerLookupNotificationState.from(update).summary)
    }

    @Test
    fun nameOnlyResultDoesNotClaimReputationWasChecked() {
        val result = state(listOf(ProviderLookupStatus("ipqs", LookupStatus.FOUND)), complete = true)
        assertEquals("Проверено 1/1 · Нет оценки спама", result.summary)
    }

    @Test
    fun cachedResultsCountWithoutPretendingTheyWereDownloaded() {
        val result = state(
            listOf(
                ProviderLookupStatus("ipqs", LookupStatus.NOT_FOUND, fromCache = true),
                ProviderLookupStatus("phoneblock", LookupStatus.NOT_FOUND, fromCache = true),
            ),
            complete = true,
        )
        assertFalse(result.checking)
        assertEquals("Сохранено 2/2 · Нет данных о номере", result.summary)
        assertTrue(result.details.all { it.contains("сохранённый результат") })
    }

    @Test
    fun oneCachedResultAndOnePendingRequestShowOneOfTwo() {
        val result = state(
            listOf(
                ProviderLookupStatus("ipqs", LookupStatus.FOUND, fromCache = true),
                ProviderLookupStatus("phoneblock"),
            ),
        )
        assertTrue(result.checking)
        assertEquals("Проверяем · Завершено 1/2", result.summary)
    }

    @Test
    fun failureOrBackoffNeverBecomesNoSpamVerdict() {
        for (failure in listOf(LookupStatus.NETWORK_ERROR, LookupStatus.QUOTA_EXHAUSTED, LookupStatus.PROVIDER_ERROR)) {
            val result = state(
                listOf(
                    ProviderLookupStatus("ipqs", failure, nextAttemptAt = Long.MAX_VALUE),
                    ProviderLookupStatus("phoneblock", LookupStatus.NOT_FOUND),
                ),
                complete = true,
            )
            assertFalse(result.checking)
            assertEquals("Проверка неполная · Ответили 1/2", result.summary)
        }
    }

    @Test
    fun interruptedLookupStopsSpinnerAndRetainsCompletedSource() {
        val result = CallerLookupNotificationState.from(
            LookupUpdate(
                identity,
                listOf(ProviderLookupStatus("ipqs", LookupStatus.FOUND), ProviderLookupStatus("phoneblock")),
            ),
            interrupted = true,
        )
        assertFalse(result.checking)
        assertEquals("Проверка неполная · Ожидание прекращено", result.summary)
        assertEquals(listOf("IPQualityScore: данные получены", "PhoneBlock: ответ не получен"), result.details)
    }

    @Test
    fun noConfiguredSourcesIsNotASuccessfulCheck() {
        val result = state(emptyList(), complete = true)
        assertFalse(result.checking)
        assertEquals("Онлайн-проверка недоступна · Источники не подключены", result.summary)
    }

    @Test
    fun localContactAndPersonalBlockNeverShowSpinner() {
        val contact = CallerLookupNotificationState.from(LookupUpdate(identity.copy(nameSource = "contacts")))
        val blocked = CallerLookupNotificationState.from(LookupUpdate(identity.copy(personalAction = PersonalAction.BLOCK)))
        assertFalse(contact.checking)
        assertTrue(contact.summary.contains("Онлайн-проверка не выполнялась"))
        assertFalse(blocked.checking)
        assertEquals("Заблокировано по вашему правилу", blocked.summary)
    }

    @Test
    fun spamWarningSurvivesCompletion() {
        val result = CallerLookupNotificationState.from(
            LookupUpdate(
                identity.copy(externalSpam = true),
                listOf(ProviderLookupStatus("ipqs", LookupStatus.FOUND)),
                isComplete = true,
            ),
        )
        assertEquals("Проверено 1/1 · Возможный спам", result.summary)
        assertFalse(result.checking)
    }

    private fun state(providers: List<ProviderLookupStatus>, complete: Boolean = false) =
        CallerLookupNotificationState.from(LookupUpdate(identity, providers, complete))
}
