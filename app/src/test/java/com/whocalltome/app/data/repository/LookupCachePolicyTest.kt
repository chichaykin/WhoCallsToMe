package com.whocalltome.app.data.repository

import com.whocalltome.app.data.db.LookupEvidenceEntity
import com.whocalltome.app.data.model.LookupResult
import com.whocalltome.app.data.model.LookupStatus
import com.whocalltome.app.data.model.NumberLookupProvider
import com.whocalltome.app.data.model.ExternalName
import com.whocalltome.app.data.remote.foundOrNotFound
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class LookupCachePolicyTest {
    private val now = 1_000_000L
    private val ipqs = FakeProvider("ipqs")
    private val tellows = FakeProvider("tellows")

    @Test
    fun onlyExpiredSourceIsRequestedWhenOtherSourceHasFreshCache() {
        val freshIpqs = evidence(
            source = "ipqs",
            nameExpiresAt = now + 1,
            reputationExpiresAt = now + 1,
        )
        val expiredTellows = evidence(
            source = "tellows",
            nameExpiresAt = now - 1,
        )

        val needed = LookupCachePolicy.providersNeedingLookup(
            providers = listOf(ipqs, tellows),
            evidence = listOf(freshIpqs, expiredTellows),
            now = now,
            force = false,
        )

        assertEquals(listOf("tellows"), needed.map(NumberLookupProvider::id))
    }

    @Test
    fun negativeResultIsCachedForItsOwnExpiry() {
        val negativeResult = foundOrNotFound(
            e164 = "+6500000000",
            source = "ipqs",
            name = null,
            spamScore = null,
            spam = false,
            providerCategory = null,
        )
        val negative = LookupCachePolicy.entityFor(negativeResult, previous = null)

        assertEquals(LookupStatus.NOT_FOUND.name, negative.status)
        assertTrue(negative.negativeExpiresAt!! > negative.fetchedAt)
        assertTrue(
            LookupCachePolicy.providersNeedingLookup(
                listOf(ipqs), listOf(negative), negative.fetchedAt + 1, false,
            ).isEmpty(),
        )
        assertEquals(
            listOf("ipqs"),
            LookupCachePolicy.providersNeedingLookup(
                listOf(ipqs), listOf(negative), negative.negativeExpiresAt!!, false,
            )
                .map(NumberLookupProvider::id),
        )
    }

    @Test
    fun freshNameDoesNotKeepExpiredReputationFromBeingRefreshed() {
        val mixedExpiry = evidence(
            source = "ipqs",
            nameExpiresAt = now + 1,
            reputationExpiresAt = now - 1,
        )

        assertEquals(
            listOf("ipqs"),
            LookupCachePolicy.providersNeedingLookup(listOf(ipqs), listOf(mixedExpiry), now, false)
                .map(NumberLookupProvider::id),
        )
        assertTrue(LookupCachePolicy.isNameFresh(mixedExpiry, now))
        assertFalse(LookupCachePolicy.isReputationFresh(mixedExpiry, now))
    }

    @Test
    fun refreshPreservesStillFreshNameWhenProviderOnlyReturnsReputation() {
        val previous = evidence(
            source = "ipqs",
            nameExpiresAt = now + 100,
            reputationExpiresAt = now - 1,
        )
        val refreshed = LookupCachePolicy.entityFor(
            LookupResult(
                e164 = previous.e164,
                source = previous.source,
                status = LookupStatus.FOUND,
                spamScore = 7,
                fetchedAt = now,
                reputationExpiresAt = now + 10,
            ),
            previous,
        )

        assertEquals("Caller", refreshed.displayName)
        assertEquals(now + 100, refreshed.nameExpiresAt)
        assertEquals(7, refreshed.spamScore)
    }

    @Test
    fun nameOnlyResponseRefreshesProviderWhenItsReputationWindowExpires() {
        val nameOnly = LookupCachePolicy.entityFor(
            LookupResult(
                e164 = "+6500000000",
                source = "ipqs",
                status = LookupStatus.FOUND,
                displayName = "Caller",
                fetchedAt = now,
                nameExpiresAt = now + 100,
                refreshExpiresAt = now + 1,
            ),
            previous = null,
        )

        assertTrue(LookupCachePolicy.providersNeedingLookup(listOf(ipqs), listOf(nameOnly), now, false).isEmpty())
        assertEquals(
            listOf("ipqs"),
            LookupCachePolicy.providersNeedingLookup(listOf(ipqs), listOf(nameOnly), now + 1, false)
                .map(NumberLookupProvider::id),
        )
    }

    @Test
    fun nameOnlyRefreshDoesNotExtendAnOlderReputation() {
        val previous = evidence(
            source = "ipqs",
            reputationExpiresAt = now + 2,
        )
        val refreshed = LookupCachePolicy.entityFor(
            LookupResult(
                e164 = previous.e164,
                source = previous.source,
                status = LookupStatus.FOUND,
                displayName = "New name",
                fetchedAt = now,
                nameExpiresAt = now + 100,
                refreshExpiresAt = now + 10,
            ),
            previous,
        )

        assertEquals(5, refreshed.spamScore)
        assertEquals(now + 2, refreshed.reputationExpiresAt)
        assertEquals(now + 10, refreshed.refreshExpiresAt)
        assertEquals(
            listOf("ipqs"),
            LookupCachePolicy.providersNeedingLookup(
                listOf(ipqs),
                listOf(refreshed),
                now + 2,
                false,
            ).map(NumberLookupProvider::id),
        )
    }

    @Test
    fun partialRefreshKeepsTheOriginalFieldFetchTimes() {
        val previous = evidence(
            source = "ipqs",
            nameExpiresAt = now + 100,
            reputationExpiresAt = now + 100,
        ).copy(nameFetchedAt = now - 90, reputationFetchedAt = now - 80)

        val refreshed = LookupCachePolicy.entityFor(
            LookupResult(
                e164 = previous.e164,
                source = previous.source,
                status = LookupStatus.FOUND,
                displayName = "New caller name",
                fetchedAt = now,
                nameExpiresAt = now + 100,
                refreshExpiresAt = now + 10,
            ),
            previous,
        )

        assertEquals(now, refreshed.nameFetchedAt)
        assertEquals(now - 80, refreshed.reputationFetchedAt)
    }

    @Test
    fun providersRunInParallelAndOneFailureDoesNotDiscardOtherResults() = runBlocking {
        val successfulCalls = AtomicInteger()
        val start = System.nanoTime()
        val results = lookupProvidersInParallel(
            listOf(
                FakeProvider("ipqs", delayMillis = 150) { successfulCalls.incrementAndGet() },
                FakeProvider("tellows", delayMillis = 150) { throw IllegalStateException("offline") },
                FakeProvider("phoneblock", delayMillis = 150) { successfulCalls.incrementAndGet() },
            ),
            "+6500000000",
        )
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000

        assertEquals(2, successfulCalls.get())
        assertEquals(listOf("ipqs", "phoneblock"), results.map(LookupResult::source))
        assertTrue("Expected parallel requests, took ${elapsedMillis}ms", elapsedMillis < 400)
    }

    @Test
    fun forcedRefreshIncludesEveryConfiguredProvider() {
        val fresh = evidence(source = "ipqs", nameExpiresAt = now + 1)

        assertEquals(
            listOf("ipqs", "tellows"),
            LookupCachePolicy.providersNeedingLookup(listOf(ipqs, tellows), listOf(fresh), now, true)
                .map(NumberLookupProvider::id),
        )
    }

    @Test
    fun contactNameHasPriorityOverExternalNamesAndIpqsLeadsTheFallbackOrder() {
        val names = listOf(
            ExternalName("ipqs", "IPQS name"),
            ExternalName("tellows", "tellows name"),
        )

        assertEquals("Contact name", LookupCachePolicy.selectDisplayName("Contact name", names))
        assertEquals("IPQS name", LookupCachePolicy.selectDisplayName(null, names))
        assertEquals("Contact name", LookupCachePolicy.selectDisplayName("Contact name", names, "Saved name"))
        assertEquals("Saved name", LookupCachePolicy.selectDisplayName(null, names, "Saved name"))
    }

    private fun evidence(
        source: String,
        status: LookupStatus = LookupStatus.FOUND,
        nameExpiresAt: Long? = null,
        reputationExpiresAt: Long? = null,
        negativeExpiresAt: Long? = null,
    ) = LookupEvidenceEntity(
        e164 = "+6500000000",
        source = source,
        status = status.name,
        displayName = nameExpiresAt?.let { "Caller" },
        spamScore = reputationExpiresAt?.let { 5 },
        fetchedAt = now - 100,
        expiresAt = now - 100,
        nameExpiresAt = nameExpiresAt,
        reputationExpiresAt = reputationExpiresAt,
        negativeExpiresAt = negativeExpiresAt,
    )

    private class FakeProvider(
        override val id: String,
        private val delayMillis: Long = 0,
        private val action: () -> Unit = {},
    ) : NumberLookupProvider {
        override suspend fun lookup(e164: String): LookupResult {
            delay(delayMillis)
            action()
            return LookupResult(
                e164 = e164,
                source = id,
                status = LookupStatus.FOUND,
                nameExpiresAt = Long.MAX_VALUE,
            )
        }
    }
}
