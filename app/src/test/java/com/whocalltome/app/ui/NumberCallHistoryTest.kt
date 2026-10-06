package com.whocalltome.app.ui

import com.whocalltome.app.data.db.CallRecordEntity
import com.whocalltome.app.data.db.NumberEntryEntity
import com.whocalltome.app.data.db.UserOverrideEntity
import com.whocalltome.app.data.model.CallerCategory
import com.whocalltome.app.data.model.PersonalAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class NumberCallHistoryTest {
    private val first = "+12025550101"
    private val second = "+12025550102"

    @Test
    fun historyUpdatesRowsAndRulesWithoutChangingHistoricalCallOutcome() = runTest {
        val selected = MutableStateFlow<String?>(first)
        val calls = MutableStateFlow(listOf(call(1, first, "MISSED")))
        val entries = MutableStateFlow(emptyList<NumberEntryEntity>())
        val overrides = MutableStateFlow(emptyList<UserOverrideEntity>())
        val states = mutableListOf<NumberCallHistoryUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeNumberCallHistory(selected, flowOf(0L), { calls }, entries, overrides).collect { states += it }
        }
        runCurrent()
        assertEquals(1, states.last().calls.size)
        entries.value = listOf(NumberEntryEntity(first, personalName = "Synthetic saved name"))
        overrides.value = listOf(UserOverrideEntity(first, action = PersonalAction.BLOCK, personalSpam = true))
        calls.value += call(2, first, "OUTGOING")
        runCurrent()
        val result = states.last()
        assertEquals(2, result.calls.size)
        assertTrue(result.calls.all { it.displayName == "Synthetic saved name" && it.category == CallerCategory.SPAM })
        assertFalse(result.calls.first().wasBlocked)
        assertEquals("MISSED", result.calls.first().direction)
        assertEquals("OUTGOING", result.calls.last().direction)
    }

    @Test
    fun switchingNumbersDiscardsOldRowsAndLateEmissionsAndClosingClearsHistory() = runTest {
        val selected = MutableStateFlow<String?>(first)
        val oldCalls = MutableSharedFlow<List<CallRecordEntity>>(replay = 1)
        oldCalls.emit(listOf(call(1, first)))
        val newCalls = MutableStateFlow(listOf(call(2, second)))
        val states = mutableListOf<NumberCallHistoryUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeNumberCallHistory(selected, flowOf(0L), { if (it == first) oldCalls else newCalls }, flowOf(emptyList()), flowOf(emptyList()))
                .collect { states += it }
        }
        runCurrent()
        selected.value = second
        runCurrent()
        oldCalls.emit(listOf(call(3, first)))
        runCurrent()
        assertEquals(second, states.last().e164)
        assertEquals(listOf(2L), states.last().calls.map { it.id })
        assertTrue(states.any { it.e164 == second && it.isLoading && it.calls.isEmpty() })
        selected.value = null
        runCurrent()
        assertNull(states.last().e164)
        assertTrue(states.last().calls.isEmpty())
    }

    @Test
    fun synchronousQueryFailureIsExplicitAndRetryResubscribesSuccessfully() = runTest {
        var attempts = 0
        val retry = MutableStateFlow(0L)
        val states = mutableListOf<NumberCallHistoryUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeNumberCallHistory(
                flowOf(first), retry,
                {
                    if (++attempts == 1) error("Private provider message")
                    flowOf(listOf(call(1, first)))
                },
                flowOf(emptyList()), flowOf(emptyList()),
            ).collect { states += it }
        }
        runCurrent()
        assertEquals("Не удалось загрузить историю звонков", states.last().error)
        assertFalse(states.last().isLoading)
        retry.value++
        runCurrent()
        assertNull(states.last().error)
        assertEquals(1, states.last().calls.size)
    }

    @Test
    fun historyDatesUseFullDateAndDeviceZoneAcrossYearBoundary() {
        val call = call(1, first).copy(eventAt = Instant.parse("2025-12-31T22:05:00Z").toEpochMilli())
        assertEquals("01.01.2026 · 06:05", callDateTimeLabel(call, ZoneId.of("Asia/Singapore")))
        assertEquals("31.12.2025 · 22:05", callDateTimeLabel(call, ZoneId.of("UTC")))
    }

    @Test
    fun historyDurationDistinguishesKnownZeroUnknownAndPositiveWithoutChangingMainJournal() {
        val call = call(1, first)
        assertNull(callDurationLabel(call))
        assertEquals("Длительность неизвестна", callDurationLabel(call, includeMissing = true))
        assertEquals("0 сек", callDurationLabel(call.copy(systemCallId = 1), includeMissing = true))
        assertEquals("Длительность неизвестна", callDurationLabel(call.copy(durationSeconds = -1), includeMissing = true))
        assertEquals("59 сек", callDurationLabel(call.copy(durationSeconds = 59)))
        assertEquals("1:00", callDurationLabel(call.copy(durationSeconds = 60)))
        assertEquals("1:05", callDurationLabel(call.copy(durationSeconds = 65)))
        assertEquals("60:00", callDurationLabel(call.copy(durationSeconds = 3600)))
    }

    private fun call(id: Long, number: String, direction: String = "INCOMING") =
        CallRecordEntity(id, number, direction, eventAt = id)
}
