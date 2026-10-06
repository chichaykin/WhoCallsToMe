package com.whocalltome.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whocalltome.app.data.db.AppDatabase
import com.whocalltome.app.data.db.CallRecordEntity
import com.whocalltome.app.data.db.NumberEntryEntity
import com.whocalltome.app.data.db.UserOverrideEntity
import com.whocalltome.app.data.model.*
import com.whocalltome.app.data.phone.PhoneContactLookup
import com.whocalltome.app.data.remote.LookupProviderCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class NumberProfileRepositoryInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: AppCallerIdentityRepository
    private val number = "+12025550101"
    private val ipqs = FakeProvider("ipqs", LookupStatus.FOUND)
    private val phoneblock = FakeProvider("phoneblock", LookupStatus.NOT_FOUND)
    private val tellows = FakeProvider("tellows", LookupStatus.FOUND)
    private var contact: ContactLookupResult = ContactLookupResult.NotFound
    private var configured = true

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        repository = AppCallerIdentityRepository(database.dao(), object : PhoneContactLookup {
            override fun findContact(phoneNumber: String) = contact
        }, object : LookupProviderCatalog {
            override fun provider(id: String) = listOf(ipqs, phoneblock, tellows).firstOrNull { it.id == id }
            override fun configuredProviders() = if (configured) listOf(ipqs, phoneblock, tellows) else emptyList()
            override fun automaticProviders() = if (configured) listOf(ipqs, phoneblock) else emptyList()
            override fun automaticProviderIds() = listOf(ipqs.id, phoneblock.id)
        })
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun historyQueryIncludesOldCallsOutsideGlobalLimitAndOrdersTiesById() = runBlocking {
        val dao = database.dao()
        dao.insertCallRecords(listOf(
            CallRecordEntity(id = 1, e164 = number, direction = "MISSED", eventAt = 1),
            CallRecordEntity(id = 2, e164 = number, direction = "OUTGOING", eventAt = 2),
            CallRecordEntity(id = 3, e164 = number, direction = "INCOMING", eventAt = 2),
        ) + (1..600).map {
            CallRecordEntity(id = 1000L + it, e164 = "+12025550102", direction = "INCOMING", eventAt = 100L + it)
        })
        assertTrue(repository.recentCalls.first().none { it.e164 == number })
        val history = repository.observeCallsForNumber(number).first()
        assertEquals(listOf(3L, 2L, 1L), history.map { it.id })
        assertTrue(history.all { it.e164 == number })
        assertEquals(listOf("INCOMING", "OUTGOING", "MISSED"), history.map { it.direction })
    }

    @Test
    fun automaticSourcesReusePositiveAndNegativeCacheAndForceRefreshOnlySelectedSources() = runBlocking {
        val first = repository.resolveUpdates(number).last()
        assertEquals(listOf(LookupStatus.FOUND, LookupStatus.NOT_FOUND), first.providers.map { it.status })
        assertTrue(first.providers.all { it.checkedAt != null && !it.fromCache })
        val cached = repository.resolveUpdates(number).last()
        assertTrue(cached.providers.all { it.fromCache && it.checkedAt != null })
        assertEquals(1, ipqs.calls.get())
        assertEquals(1, phoneblock.calls.get())
        repository.resolveUpdates(number, force = true, allowNetworkForContacts = true).last()
        assertEquals(2, ipqs.calls.get())
        assertEquals(2, phoneblock.calls.get())
        assertEquals(0, tellows.calls.get())
    }

    @Test
    fun contactMembershipWithoutNameSkipsAutomaticNetworkAndPreservesPersonalNameAndRules() = runBlocking {
        contact = ContactLookupResult.Found(null, null)
        database.dao().upsertNumberEntry(NumberEntryEntity(number, personalName = "Personal synthetic name", numberType = NumberType.BUSINESS))
        database.dao().upsertOverride(UserOverrideEntity(number, action = PersonalAction.BLOCK, personalSpam = true))
        val local = repository.resolveUpdates(number).last().identity
        assertTrue(local.contact is ContactLookupResult.Found)
        assertEquals("Personal synthetic name", local.displayName)
        assertEquals(0, ipqs.calls.get())

        contact = ContactLookupResult.Found("Synthetic contact", "content://contacts/lookup/test/1")
        val checked = repository.resolveUpdates(number, force = true, allowNetworkForContacts = true).last().identity
        assertEquals("Synthetic contact", checked.displayName)
        assertEquals("contacts", checked.nameSource)
        assertEquals("Personal synthetic name", checked.personalName)
        assertEquals(NumberType.BUSINESS, checked.numberType)
        assertTrue(checked.shouldBlock)
        assertTrue(checked.personalSpam)
    }

    @Test
    fun quotaBackoffIsRespectedEvenWhenForcedAndExternalSpamDoesNotBlock() = runBlocking {
        phoneblock.status = LookupStatus.QUOTA_EXHAUSTED
        ipqs.spam = true
        val first = repository.resolveUpdates(number).last()
        assertTrue(first.identity.externalSpam)
        assertFalse(first.identity.shouldBlock)
        assertNotNull(first.providers.last().nextAttemptAt)
        val forced = repository.resolveUpdates(number, force = true, allowNetworkForContacts = true).last()
        assertEquals(LookupStatus.QUOTA_EXHAUSTED, forced.providers.last().status)
        assertEquals(1, phoneblock.calls.get())
        assertEquals(2, ipqs.calls.get())
    }

    @Test
    fun unconfiguredSelectedProvidersAreReportedWithoutNetworkRequests() = runBlocking {
        configured = false
        val result = repository.resolveUpdates(number).last()
        assertTrue(result.isComplete)
        assertEquals(listOf("ipqs", "phoneblock"), result.providers.map { it.source })
        assertTrue(result.providers.all { it.status == LookupStatus.NOT_CONFIGURED })
        assertEquals(0, ipqs.calls.get())
        assertEquals(0, phoneblock.calls.get())
    }

    @Test
    fun simultaneousConsumersShareProviderRequests() = runBlocking {
        ipqs.gate = CompletableDeferred()
        phoneblock.gate = CompletableDeferred()
        coroutineScope {
            val first = async { repository.resolveUpdates(number).last() }
            val second = async { repository.resolveUpdates(number).last() }
            withTimeout(5_000) {
                while (ipqs.calls.get() == 0 || phoneblock.calls.get() == 0) delay(10)
            }
            ipqs.gate!!.complete(Unit)
            phoneblock.gate!!.complete(Unit)
            assertTrue(first.await().isComplete)
            assertTrue(second.await().isComplete)
        }
        assertEquals(1, ipqs.calls.get())
        assertEquals(1, phoneblock.calls.get())
    }

    @Test
    fun historicalHintUsesLatestNonblankSystemNameAndNeverBecomesCurrentContact() = runBlocking {
        val dao = database.dao()
        dao.insertCallRecords(listOf(
            CallRecordEntity(e164 = number, direction = "INCOMING", eventAt = 1, displayName = "Old synthetic hint", nameSource = "system-call-log"),
            CallRecordEntity(e164 = number, direction = "INCOMING", eventAt = 2, displayName = "Latest synthetic hint", nameSource = "system-call-log"),
            CallRecordEntity(e164 = number, direction = "INCOMING", eventAt = 3, displayName = "External synthetic name", nameSource = "ipqs"),
            CallRecordEntity(e164 = number, direction = "INCOMING", eventAt = 4, displayName = "   ", nameSource = "system-call-log"),
        ))
        assertEquals("Latest synthetic hint", repository.historicalCallName(number))
        val identity = repository.resolveLocal(number)
        assertNull(identity.displayName)
        assertEquals(ContactLookupResult.NotFound, identity.contact)
    }

    private class FakeProvider(override val id: String, var status: LookupStatus) : NumberLookupProvider {
        val calls = AtomicInteger()
        var spam = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun lookup(e164: String): LookupResult {
            calls.incrementAndGet()
            gate?.await()
            val now = System.currentTimeMillis()
            return LookupResult(
                e164, id, status,
                displayName = if (status == LookupStatus.FOUND) "External synthetic name" else null,
                spamScore = if (spam) 90 else null,
                isSpam = spam,
                fetchedAt = now,
                nameExpiresAt = now + 60_000,
                reputationExpiresAt = now + 60_000,
                refreshExpiresAt = now + 60_000,
                negativeExpiresAt = now + 60_000,
            )
        }
    }
}
