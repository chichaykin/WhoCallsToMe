package com.whocalltome.app.ui

import com.whocalltome.app.data.db.CallRecordEntity
import com.whocalltome.app.data.db.UserOverrideEntity
import com.whocalltome.app.data.model.CallerCategory
import com.whocalltome.app.data.model.PersonalAction
import com.whocalltome.app.R
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallsScreenLogicTest {
    private val zone = ZoneId.of("Asia/Singapore")

    @Test
    fun filterCombinesMissedAndNormalizedNumberSearch() {
        val calls = listOf(
            call(1, "MISSED", "+65 6123 4567", "Алина"),
            call(2, "INCOMING", "+65 6999 0000", "Алина"),
            call(3, "MISSED", "+65 6123 9999", null),
        )

        val result = filterCalls(calls, "+65 (6123) 4567", CallFilter.MISSED)

        assertEquals(listOf(1L), result.map(CallRecordEntity::id))
    }

    @Test
    fun groupsTodayYesterdayAndPreviousYearInLocalZone() {
        val now = Instant.parse("2026-01-01T00:30:00Z").toEpochMilli()
        val calls = listOf(
            call(1, "INCOMING", "+1", null, Instant.parse("2025-12-31T16:20:00Z").toEpochMilli()),
            call(2, "INCOMING", "+2", null, Instant.parse("2025-12-30T16:20:00Z").toEpochMilli()),
            call(3, "INCOMING", "+3", null, Instant.parse("2024-12-30T16:20:00Z").toEpochMilli()),
        )

        val groups = groupCallsByDay(calls, now, zone)

        assertEquals(listOf("Сегодня", "Вчера", "31 декабря 2024"), groups.map(CallDayGroup::title))
        assertEquals(listOf(1L), groups[0].calls.map(CallRecordEntity::id))
    }

    @Test
    fun allSupportedDirectionsHaveHumanReadableLabels() {
        val expected = mapOf(
            "INCOMING" to "Входящий",
            "OUTGOING" to "Исходящий",
            "MISSED" to "Пропущен",
            "REJECTED" to "Отклонён",
            "BLOCKED" to "Заблокирован",
            "VOICEMAIL" to "Голосовая почта",
            "ANSWERED_EXTERNALLY" to "Принят на другом устройстве",
            "OTHER" to "Звонок",
        )

        expected.forEach { (direction, label) ->
            assertEquals(label, callStatusLabel(call(1, direction, "+1", null)))
        }
    }

    @Test
    fun callTimeUsesDeviceZoneAndTwentyFourHourFormat() {
        val eventAt = Instant.parse("2026-01-01T22:05:00Z").toEpochMilli()

        assertEquals("06:05", callTimeLabel(call(1, "INCOMING", "+1", null, eventAt), zone))
    }

    @Test
    fun spamWarningIsIndependentFromBlockStatus() {
        val externalSpam = call(1, "BLOCKED", "+1", null).copy(category = CallerCategory.SPAM, source = "ipqs")
        val personalSpam = call(2, "INCOMING", "+2", null).copy(category = CallerCategory.SPAM, source = "personal")
        val ordinary = call(3, "INCOMING", "+3", null)

        assertEquals("Возможный спам", callWarningLabel(externalSpam))
        assertEquals("Личная отметка: спам", callWarningLabel(personalSpam))
        assertNull(callWarningLabel(ordinary))
        assertEquals("Заблокирован", callStatusLabel(externalSpam))
    }

    @Test
    fun nameSearchIsCaseInsensitive() {
        val result = filterCalls(listOf(call(1, "INCOMING", "+1", "Лена-Таганрог")), "лена", CallFilter.ALL)

        assertTrue(result.isNotEmpty())
    }

    @Test
    fun spamAndBlockedFiltersOverlapOnlyForBlockedSpam() {
        val calls = listOf(
            call(1, "MISSED", "+1", null).copy(category = CallerCategory.SPAM, source = "ipqs"),
            call(2, "INCOMING", "+2", null).copy(category = CallerCategory.SPAM, source = "personal"),
            call(3, "INCOMING", "+3", null).copy(wasBlocked = true),
            call(4, "BLOCKED", "+4", null).copy(category = CallerCategory.SPAM),
            call(5, "REJECTED", "+5", null),
            call(6, "MISSED", "+6", null),
            call(7, "MISSED", "+7", null).copy(wasBlocked = true),
        )

        assertEquals(listOf(1L, 2L, 4L), filterCalls(calls, "", CallFilter.SPAM).map { it.id })
        assertEquals(listOf(3L, 4L, 7L), filterCalls(calls, "", CallFilter.BLOCKED).map { it.id })
        assertEquals(listOf(1L, 6L), filterCalls(calls, "", CallFilter.MISSED).map { it.id })
        assertEquals(calls, filterCalls(calls, "", CallFilter.ALL))
    }

    @Test
    fun newFiltersCombineWithNameAndNumberSearch() {
        val calls = listOf(
            call(1, "INCOMING", "+65 6000 0001", "Доставка").copy(category = CallerCategory.SPAM),
            call(2, "BLOCKED", "+65 6000 0002", "Опрос"),
            call(3, "INCOMING", "+65 6000 0003", "Опрос"),
        )

        assertEquals(listOf(1L), filterCalls(calls, "+65 (6000) 0001", CallFilter.SPAM).map { it.id })
        assertEquals(listOf(2L), filterCalls(calls, "ОПРОС", CallFilter.BLOCKED).map { it.id })
        assertTrue(filterCalls(calls, "Опрос", CallFilter.SPAM).isEmpty())
    }

    @Test
    fun personalSpamImmediatelyUpdatesHistoryWithoutChangingCallOutcome() {
        val calls = listOf(call(1, "MISSED", "+1", "Тестовый контакт"))
        val marked = applyPersonalCallOverrides(calls, listOf(UserOverrideEntity(e164 = "+1", personalSpam = true)))

        assertEquals(listOf(1L), filterCalls(marked, "", CallFilter.SPAM).map { it.id })
        assertEquals("Личная отметка: спам", callWarningLabel(marked.single()))
        assertEquals(calls.single().displayName, marked.single().displayName)
        assertEquals("Пропущен", callStatusLabel(marked.single()))
        assertTrue(filterCalls(marked, "", CallFilter.BLOCKED).isEmpty())
        assertEquals(CallerCategory.UNKNOWN, calls.single().category)
        assertEquals(calls, applyPersonalCallOverrides(calls, listOf(UserOverrideEntity(e164 = "+1"))))
    }

    @Test
    fun personalBlockDoesNotRetroactivelyBlockOldCalls() {
        val calls = listOf(call(1, "INCOMING", "+1", null))
        val updated = applyPersonalCallOverrides(calls, listOf(UserOverrideEntity(e164 = "+1", action = PersonalAction.BLOCK)))

        assertTrue(filterCalls(updated, "", CallFilter.BLOCKED).isEmpty())
        assertEquals("Входящий", callStatusLabel(updated.single()))
    }

    @Test
    fun removingPersonalRulesDoesNotEraseHistoricalBlockOutcome() {
        val calls = listOf(call(1, "INCOMING", "+1", null).copy(
            category = CallerCategory.SPAM,
            source = "personal",
            wasBlocked = true,
        ))
        val removed = applyPersonalCallOverrides(calls, emptyList())
        val unmarked = applyPersonalCallOverrides(calls, listOf(UserOverrideEntity(e164 = "+1")))

        listOf(removed, unmarked).forEach { result ->
            assertTrue(filterCalls(result, "", CallFilter.SPAM).isEmpty())
            assertEquals(listOf(1L), filterCalls(result, "", CallFilter.BLOCKED).map { it.id })
            assertEquals("Заблокирован", callStatusLabel(result.single()))
        }
    }

    @Test
    fun personalAllowKeepsExternalSpamWarningAndHistoricalOutcome() {
        val calls = listOf(call(1, "BLOCKED", "+1", null).copy(category = CallerCategory.SPAM, source = "ipqs"))
        val allowed = applyPersonalCallOverrides(calls, listOf(UserOverrideEntity(
            e164 = "+1",
            action = PersonalAction.ALLOW,
        )))

        assertEquals(listOf(1L), filterCalls(allowed, "", CallFilter.SPAM).map { it.id })
        assertNull(allowed.single().displayName)
        assertEquals(listOf(1L), filterCalls(allowed, "", CallFilter.BLOCKED).map { it.id })
    }

    @Test
    fun emptySearchResultsDoNotClaimAnEntireCategoryIsEmpty() {
        CallFilter.entries.forEach { filter ->
            assertEquals(R.string.calls_no_results, callsEmptyMessage("нет совпадений", filter))
            assertEquals(filter, CallFilter.fromId(filter.id))
        }
        assertEquals(R.string.calls_no_spam, callsEmptyMessage("", CallFilter.SPAM))
        assertEquals(R.string.calls_no_blocked, callsEmptyMessage("  ", CallFilter.BLOCKED))
        assertEquals(R.string.calls_no_missed, callsEmptyMessage("", CallFilter.MISSED))
        assertEquals(R.string.calls_empty, callsEmptyMessage("", CallFilter.ALL))
        assertEquals(CallFilter.ALL, CallFilter.fromId("unknown"))
    }

    private fun call(
        id: Long,
        direction: String,
        number: String,
        name: String?,
        eventAt: Long = 1_000L,
    ) = CallRecordEntity(
        id = id,
        e164 = number,
        direction = direction,
        eventAt = eventAt,
        displayName = name,
    )
}
