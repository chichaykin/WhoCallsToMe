package com.whocalltome.app.service

import com.whocalltome.app.data.model.CallerCategory
import com.whocalltome.app.data.model.CallerIdentity
import com.whocalltome.app.data.model.LookupStatus
import com.whocalltome.app.data.model.LookupUpdate
import com.whocalltome.app.data.model.ProviderLookupStatus
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerLookupProgressTest {
    private val initial = LookupUpdate(
        CallerIdentity("+6500000000", null, CallerCategory.UNKNOWN, "unknown"),
    )

    @Test
    fun slowSourceEndsWithIncompleteResultAndKeepsAlreadyReceivedAnswer() = runBlocking {
        val partial = initial.copy(providers = listOf(
            ProviderLookupStatus("ipqs", LookupStatus.NOT_FOUND),
            ProviderLookupStatus("phoneblock"),
        ))
        val published = mutableListOf<Pair<LookupUpdate, Boolean>>()
        collectCallerLookupProgress(
            initial,
            flow {
                emit(partial)
                awaitCancellation()
            },
            timeoutMillis = 30,
        ) { update, interrupted -> published += update to interrupted }

        assertEquals(listOf(partial to false, partial to true), published)
        val finalState = CallerLookupNotificationState.from(published.last().first, published.last().second)
        assertFalse(finalState.checking)
        assertTrue(finalState.summary.contains("Проверка неполная"))
    }

    @Test
    fun completedLookupHasNoTimeoutResult() = runBlocking {
        val completed = initial.copy(
            providers = listOf(ProviderLookupStatus("ipqs", LookupStatus.NOT_FOUND)),
            isComplete = true,
        )
        val published = mutableListOf<Pair<LookupUpdate, Boolean>>()
        collectCallerLookupProgress(initial, flow { emit(completed) }) { update, interrupted ->
            published += update to interrupted
        }

        assertEquals(listOf(completed to false), published)
        assertFalse(CallerLookupNotificationState.from(completed).checking)
    }
}
