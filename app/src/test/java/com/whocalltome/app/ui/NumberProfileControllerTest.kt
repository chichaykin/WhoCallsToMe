package com.whocalltome.app.ui

import com.whocalltome.app.data.model.CallerCategory
import com.whocalltome.app.data.model.CallerIdentity
import com.whocalltome.app.data.model.CallerIdentityRepository
import com.whocalltome.app.data.model.ContactLookupResult
import com.whocalltome.app.data.model.ExternalReputation
import com.whocalltome.app.data.model.LookupStatus
import com.whocalltome.app.data.model.LookupUpdate
import com.whocalltome.app.data.model.ProviderLookupStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NumberProfileControllerTest {
    private val first = "+6500000000"
    private val second = "+6500000001"

    @Test
    fun unknownNumberAutomaticallyChecksOnceWithoutWritingManualHistory() = runTest {
        val fixture = Fixture(this)
        fixture.controller.open(first)
        assertTrue(fixture.controller.state.value.isLoadingLocal)
        assertNull(fixture.controller.state.value.identity)
        advanceUntilIdle()

        assertEquals(listOf(Request(first, false, false, false)), fixture.repository.requests)
        assertTrue(fixture.history.isEmpty())
        assertFalse(fixture.controller.state.value.isChecking)
        assertTrue(fixture.controller.state.value.hasChecked)
        fixture.controller.open(first) // Recomposition/configuration recreation keeps the session.
        fixture.controller.refresh()
        advanceUntilIdle()
        assertEquals(1, fixture.repository.requests.size)
    }

    @Test
    fun contactsIncludingThoseWithoutNamesAndPersonalNamesDoNotAutomaticallyCheck() = runTest {
        for (identity in listOf(
            identity(first).copy(contact = ContactLookupResult.Found("Test contact", "content://contacts/lookup/test/1")),
            identity(first).copy(contact = ContactLookupResult.Found(null, null)),
            identity(first).copy(personalName = "Personal test name"),
        )) {
            val fixture = Fixture(this)
            fixture.repository.identities[first] = identity
            fixture.controller.open(first)
            advanceUntilIdle()
            assertTrue(fixture.repository.requests.isEmpty())
            assertEquals(identity.contact is ContactLookupResult.Found, fixture.controller.state.value.isContact)
            fixture.controller.close()
        }
    }

    @Test
    fun missingPermissionAndReadErrorsPreventAutomaticNetworkUntilContactStatusIsKnown() = runTest {
        for (contact in listOf(ContactLookupResult.PermissionRequired, ContactLookupResult.ReadError)) {
            val fixture = Fixture(this)
            fixture.repository.identities[first] = identity(first).copy(contact = contact)
            fixture.controller.open(first)
            advanceUntilIdle()
            assertEquals(contact, fixture.controller.state.value.identity?.contact)
            assertTrue(fixture.repository.requests.isEmpty())
            fixture.repository.identities[first] = identity(first)
            fixture.controller.refresh() // Permission granted/read retry succeeded.
            advanceUntilIdle()
            assertEquals(1, fixture.repository.requests.size)
            fixture.controller.close()
        }
    }

    @Test
    fun returningFromContactsRefreshesCreatedRenamedRemovedAndPermissionRevokedContact() = runTest {
        val fixture = Fixture(this)
        fixture.repository.identities[first] = identity(first).copy(contact = ContactLookupResult.PermissionRequired)
        fixture.controller.open(first)
        advanceUntilIdle()

        for (name in listOf("Created test name", "Renamed test name")) {
            fixture.repository.identities[first] = identity(first).copy(
                displayName = name,
                nameSource = "contacts",
                contact = ContactLookupResult.Found(name, "content://contacts/lookup/test/1"),
            )
            fixture.controller.refresh()
            advanceUntilIdle()
            assertEquals(name, fixture.controller.state.value.identity?.displayName)
            assertTrue(fixture.controller.state.value.isContact)
            assertTrue(fixture.repository.requests.isEmpty())
        }
        fixture.repository.identities[first] = identity(first).copy(contact = ContactLookupResult.PermissionRequired)
        fixture.controller.refresh()
        advanceUntilIdle()
        assertFalse(fixture.controller.state.value.isContact)
        assertNull(fixture.controller.state.value.identity?.displayName)
        assertTrue(fixture.repository.requests.isEmpty())

        fixture.repository.identities[first] = identity(first) // Contact deleted, access restored.
        fixture.controller.refresh()
        advanceUntilIdle()
        assertEquals(1, fixture.repository.requests.size)
    }

    @Test
    fun manualRefreshUsesSelectedSourcesAndForceAndRecordsHistoryEvenForContact() = runTest {
        val fixture = Fixture(this)
        fixture.repository.identities[first] = identity(first).copy(contact = ContactLookupResult.Found(null, null))
        fixture.controller.open(first)
        advanceUntilIdle()
        fixture.controller.check()
        fixture.controller.check() // A second tap during the same request is ignored.
        advanceUntilIdle()
        fixture.controller.check(force = true)
        advanceUntilIdle()
        assertEquals(listOf(Request(first, false, true, false), Request(first, true, true, false)), fixture.repository.requests)
        assertEquals(listOf(first, first), fixture.history)
    }

    @Test
    fun manualHistoryFailureDoesNotPreventLookupAndDoesNotExposeExceptionMessage() = runTest {
        val fixture = Fixture(this, failHistory = true)
        fixture.repository.identities[first] = identity(first).copy(contact = ContactLookupResult.PermissionRequired)
        fixture.controller.open(first)
        advanceUntilIdle()
        fixture.controller.check()
        advanceUntilIdle()
        assertEquals(1, fixture.repository.requests.size)
        assertNull(fixture.controller.state.value.lookupError)
    }

    @Test
    fun switchingCardsCancelsOldSubscriptionAndKeepsNewNumberAndHistoricalHint() = runTest {
        val fixture = Fixture(this)
        fixture.repository.gate = CompletableDeferred()
        fixture.controller.open(first)
        runCurrent()
        assertTrue(fixture.controller.state.value.isChecking)
        fixture.repository.identities[second] = identity(second).copy(personalName = "Saved test name")
        fixture.controller.open(second)
        runCurrent()
        fixture.repository.gate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(second, fixture.controller.state.value.e164)
        assertEquals(second, fixture.controller.state.value.identity?.e164)
        assertEquals("Historical test hint", fixture.controller.state.value.historicalName)
        assertFalse(fixture.controller.state.value.isContact)
        assertFalse(fixture.controller.state.value.hasChecked)
        fixture.controller.close()
        assertNull(fixture.controller.state.value.e164)
    }

    @Test
    fun partialFailuresAndReputationWithoutNameRemainVisibleAsFoundData() = runTest {
        val fixture = Fixture(this)
        fixture.repository.results = listOf(
            ProviderLookupStatus("ipqs", LookupStatus.FOUND, fromCache = true, checkedAt = 42),
            ProviderLookupStatus("phoneblock", LookupStatus.QUOTA_EXHAUSTED, nextAttemptAt = 99),
        )
        fixture.repository.reputation = ExternalReputation("ipqs", 50, true)
        fixture.controller.open(first)
        advanceUntilIdle()
        val state = fixture.controller.state.value
        assertTrue(state.hasPartialFailure)
        assertNull(state.identity?.displayName)
        assertEquals(listOf(fixture.repository.reputation), state.identity?.externalReputations)
        assertEquals("Данные найдены · Из кэша", profileProviderStatusText(state.providers.first(), false))
        assertEquals("Лимит запросов", profileProviderStatusText(state.providers.last(), false))
    }

    @Test
    fun localFailureCanBeRetriedAndLookupFailureKeepsLocalIdentity() = runTest {
        val fixture = Fixture(this)
        fixture.repository.failLocal = true
        fixture.controller.open(first)
        advanceUntilIdle()
        assertNotNull(fixture.controller.state.value.localError)
        assertTrue(fixture.repository.requests.isEmpty())
        fixture.repository.failLocal = false
        fixture.repository.failLookup = true
        fixture.controller.refresh()
        advanceUntilIdle()
        assertNull(fixture.controller.state.value.localError)
        assertNotNull(fixture.controller.state.value.identity)
        assertNotNull(fixture.controller.state.value.lookupError)
        assertFalse(fixture.controller.state.value.isChecking)
    }

    @Test
    fun rejectingInvalidNumberClearsOldCardAndPreventsFurtherReadsAndLookups() = runTest {
        val fixture = Fixture(this)
        fixture.repository.gate = CompletableDeferred()
        fixture.controller.open(first)
        runCurrent()
        val reads = fixture.repository.localReads
        val requests = fixture.repository.requests.size
        fixture.controller.rejectNumber("+invalid")
        fixture.controller.refresh()
        fixture.controller.check(force = true)
        fixture.repository.gate!!.complete(Unit)
        advanceUntilIdle()
        val state = fixture.controller.state.value
        assertTrue(state.isInvalidNumber)
        assertFalse(state.isLoadingLocal)
        assertFalse(state.isChecking)
        assertNull(state.identity)
        assertNull(state.historicalName)
        assertTrue(state.providers.isEmpty())
        assertNotNull(state.localError)
        assertEquals(reads, fixture.repository.localReads)
        assertEquals(requests, fixture.repository.requests.size)
    }

    @Test
    fun providerStatusDistinguishesNoDataNetworkConfigurationAndProviderFailure() {
        val statuses = mapOf(
            LookupStatus.NOT_FOUND to "Данных нет",
            LookupStatus.NOT_CONFIGURED to "Источник не настроен",
            LookupStatus.NETWORK_ERROR to "Нет сети",
            LookupStatus.PROVIDER_ERROR to "Ошибка источника",
        )
        statuses.forEach { (status, expected) ->
            assertEquals(expected, profileProviderStatusText(ProviderLookupStatus("ipqs", status), false))
        }
    }

    private class Fixture(scope: TestScope, failHistory: Boolean = false) {
        val repository = FakeRepository()
        val history = mutableListOf<String>()
        val controller = NumberProfileController(
            scope, repository,
            historicalName = { "Historical test hint" },
            recordManualLookup = {
                if (failHistory) error("Sensitive exception must not be shown")
                history += it
            },
            ioDispatcher = StandardTestDispatcher(scope.testScheduler),
        )
    }

    private data class Request(val e164: String, val force: Boolean, val allowContacts: Boolean, val useAll: Boolean)

    private class FakeRepository : CallerIdentityRepository {
        val identities = mutableMapOf<String, CallerIdentity>()
        val requests = mutableListOf<Request>()
        var results = listOf(ProviderLookupStatus("ipqs", LookupStatus.NOT_FOUND))
        var reputation: ExternalReputation? = null
        var gate: CompletableDeferred<Unit>? = null
        var failLocal = false
        var localReads = 0
        var failLookup = false

        override suspend fun resolveLocal(e164: String): CallerIdentity {
            localReads++
            if (failLocal) error("Local read failed")
            return identities[e164] ?: identity(e164)
        }

        override suspend fun resolve(e164: String, allowNetwork: Boolean, allowNetworkForContacts: Boolean) = resolveLocal(e164)

        override fun resolveUpdates(
            e164: String, force: Boolean, allowNetwork: Boolean, allowNetworkForContacts: Boolean, useAllConfiguredProviders: Boolean,
        ): Flow<LookupUpdate> = flow {
            requests += Request(e164, force, allowNetworkForContacts, useAllConfiguredProviders)
            assertTrue(allowNetwork)
            emit(LookupUpdate(resolveLocal(e164)))
            gate?.await()
            if (failLookup) error("Lookup failed")
            reputation?.let { identities[e164] = resolveLocal(e164).copy(externalReputations = listOf(it)) }
            emit(LookupUpdate(resolveLocal(e164), results, isComplete = true))
        }
    }

    companion object {
        private fun identity(e164: String) = CallerIdentity(e164, null, CallerCategory.UNKNOWN, "unknown")
    }
}
