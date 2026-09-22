package com.whocalltome.app.data.repository

import android.net.Uri
import com.whocalltome.app.data.db.AppDao
import com.whocalltome.app.data.db.CallRecordEntity
import com.whocalltome.app.data.db.LookupEvidenceEntity
import com.whocalltome.app.data.db.ManualLookupEntity
import com.whocalltome.app.data.db.NumberEntryEntity
import com.whocalltome.app.data.db.UserOverrideEntity
import com.whocalltome.app.data.model.CallerCategory
import com.whocalltome.app.data.model.CallerIdentity
import com.whocalltome.app.data.model.CallerIdentityRepository
import com.whocalltome.app.data.model.ExternalName
import com.whocalltome.app.data.model.ExternalReputation
import com.whocalltome.app.data.model.LookupStatus
import com.whocalltome.app.data.model.LookupResult
import com.whocalltome.app.data.model.PersonalAction
import com.whocalltome.app.data.phone.ContactLookup
import com.whocalltome.app.data.remote.LookupProviderCatalog
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

private class NumberLookupLock(
    val mutex: Mutex = Mutex(),
    var users: Int = 0,
)

internal suspend fun lookupProvidersInParallel(
    providers: List<com.whocalltome.app.data.model.NumberLookupProvider>,
    e164: String,
): List<LookupResult> = coroutineScope {
    providers.map { provider ->
        async {
            try {
                provider.lookup(e164)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
        }
    }.mapNotNull { it.await() }
}

class AppCallerIdentityRepository(
    private val dao: AppDao,
    private val contactLookup: ContactLookup,
    private val providers: LookupProviderCatalog,
) : CallerIdentityRepository {
    private val lookupLocks = ConcurrentHashMap<String, NumberLookupLock>()
    suspend fun checkProvider(providerId: String, e164: String): LookupResult {
        val provider = providers.provider(providerId)
            ?: return LookupResult(e164, providerId, LookupStatus.PROVIDER_ERROR, message = "Неизвестный источник")
        return runCatching { provider.lookup(e164) }.getOrElse {
            LookupResult(e164, providerId, LookupStatus.NETWORK_ERROR, message = "Не удалось связаться с источником")
        }
    }
    data class PersonalNumberSnapshot(
        val entry: NumberEntryEntity?,
        val override: UserOverrideEntity?,
    ) {
        val e164: String
            get() = entry?.e164 ?: override?.e164
                ?: error("Personal snapshot has no number")
    }

    val recentCalls: Flow<List<CallRecordEntity>> = dao.observeRecentCalls()
    val numberEntries: Flow<List<NumberEntryEntity>> = dao.observeNumberEntries()
    val overrides: Flow<List<UserOverrideEntity>> = dao.observeOverrides()
    val manualLookups: Flow<List<ManualLookupEntity>> = dao.observeManualLookups()

    suspend fun recordManualLookup(e164: String) {
        dao.recordManualLookup(ManualLookupEntity(e164 = e164))
    }

    suspend fun clearManualLookupHistory() {
        dao.clearManualLookupHistory()
    }

    fun findContactUri(e164: String): Uri? = contactLookup.findContactUri(e164)

    override suspend fun resolveLocal(e164: String): CallerIdentity {
        val override = dao.getOverride(e164)
        val entry = dao.getNumberEntry(e164)
        val action = override?.action ?: PersonalAction.DEFAULT
        val personalSpam = override?.personalSpam == true
        val hasPersonalRecord = override != null || entry != null
        val contactName = contactLookup.findDisplayName(e164)

        val now = System.currentTimeMillis()
        val evidence = dao.getEvidence(e164)
        val externalNames = evidence
            .filter { LookupCachePolicy.isNameFresh(it, now) }
            .sortedWith(compareBy({ LookupCachePolicy.sourceRank(it.source) }, { -it.fetchedAt }))
            .map { ExternalName(it.source, it.displayName!!) }
            .distinctBy(ExternalName::value)
        val externalReputations = evidence
            .filter { LookupCachePolicy.isReputationFresh(it, now) }
            .filter { it.spamScore != null || it.isSpam }
            .sortedWith(compareBy({ LookupCachePolicy.sourceRank(it.source) }, { -it.fetchedAt }))
            .map { ExternalReputation(it.source, it.spamScore, it.isSpam) }
        val freshEvidence = evidence.filter {
            LookupCachePolicy.isNameFresh(it, now) ||
                LookupCachePolicy.isReputationFresh(it, now) ||
                LookupCachePolicy.isNegativeFresh(it, now)
        }
        val nameEvidence = externalNames.firstOrNull()
        val spamEvidence = externalReputations.firstOrNull { it.isSpam }
        val newest = freshEvidence.maxByOrNull { it.fetchedAt }
        val name = LookupCachePolicy.selectDisplayName(contactName, externalNames)
        val nameSource = when {
            contactName != null -> "contacts"
            nameEvidence != null -> nameEvidence.source
            else -> null
        }
        val sources = listOfNotNull(
            if (hasPersonalRecord) "personal" else null,
            if (nameSource != "personal" && nameSource != "contacts") nameSource else null,
            *externalReputations.map(ExternalReputation::source).toTypedArray(),
        ).distinct()
        val externalSpam = spamEvidence != null
        val category = when {
            personalSpam || externalSpam -> CallerCategory.SPAM
            nameSource == "contacts" -> CallerCategory.CONTACT
            nameEvidence != null -> CallerCategory.INTERNET
            else -> CallerCategory.UNKNOWN
        }

        return CallerIdentity(
            e164 = e164,
            displayName = name,
            category = category,
            source = sources.joinToString(" + ").ifBlank {
                when {
                    hasPersonalRecord -> "personal"
                    nameSource == "contacts" -> "contacts"
                    else -> "unknown"
                }
            },
            spamScore = spamEvidence?.score ?: externalReputations.firstOrNull()?.score,
            isSpam = personalSpam || externalSpam,
            personalAction = action,
            checkedAt = newest?.fetchedAt,
            nameSource = nameSource,
            personalSpam = personalSpam,
            externalSpam = externalSpam,
            externalSpamScore = spamEvidence?.score,
            externalSource = spamEvidence?.source,
            hasPersonalRecord = hasPersonalRecord,
            externalNames = externalNames,
            externalReputations = externalReputations,
        )
    }

    override suspend fun resolve(
        e164: String,
        allowNetwork: Boolean,
        allowNetworkForContacts: Boolean,
    ): CallerIdentity {
        val local = resolveLocal(e164)
        if (!allowNetwork || (local.nameSource == "contacts" && !allowNetworkForContacts)) {
            return local
        }
        lookupWithCache(e164, force = false)
        return resolveLocal(e164)
    }

    /** Runs every configured provider even when fresh evidence is already cached. */
    suspend fun resolveFresh(e164: String): ManualLookupOutcome {
        val outcome = lookupWithCache(e164, force = true)
        return ManualLookupOutcome(
            identity = resolveLocal(e164),
            providerSucceeded = outcome.cachedCount > 0,
            providerFailed = outcome.queriedCount > outcome.cachedCount,
        )
    }

    private suspend fun lookupWithCache(e164: String, force: Boolean): ProviderLookupOutcome {
        val lookupLock = lookupLocks.compute(e164) { _, current ->
            (current ?: NumberLookupLock()).also { it.users += 1 }
        }!!
        return try {
            lookupLock.mutex.withLock {
                val configured = providers.configuredProviders()
                val needed = LookupCachePolicy.providersNeedingLookup(
                    providers = configured,
                    evidence = dao.getEvidence(e164),
                    now = System.currentTimeMillis(),
                    force = force,
                )
                val results = lookupProvidersInParallel(needed, e164)
                    .filter { it.status == LookupStatus.FOUND || it.status == LookupStatus.NOT_FOUND }
                results.forEach { result ->
                    val previous = dao.getEvidence(e164).firstOrNull { it.source == result.source }
                    dao.upsertEvidence(LookupCachePolicy.entityFor(result, previous))
                }
                ProviderLookupOutcome(queriedCount = needed.size, cachedCount = results.size)
            }
        } finally {
            lookupLocks.compute(e164) { _, current ->
                if (current === lookupLock && --lookupLock.users == 0) null else current
            }
        }
    }

    suspend fun savePersonalNumber(
        e164: String,
        note: String,
        action: PersonalAction,
        personalSpam: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val existing = dao.getNumberEntry(e164)
        dao.upsertNumberEntry(
            NumberEntryEntity(
                e164 = e164,
                note = note.trim(),
                category = if (personalSpam) CallerCategory.SPAM else CallerCategory.CONTACT,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
        dao.upsertOverride(
            UserOverrideEntity(
                e164 = e164,
                action = action,
                personalSpam = personalSpam,
                updatedAt = now,
            ),
        )
    }

    suspend fun setAction(e164: String, action: PersonalAction) {
        val existing = dao.getOverride(e164)
        dao.upsertOverride(
            (existing ?: UserOverrideEntity(e164 = e164)).copy(
                action = action,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun markPersonalSpam(e164: String, isSpam: Boolean) {
        val existing = dao.getOverride(e164)
        dao.upsertOverride(
            (existing ?: UserOverrideEntity(e164 = e164)).copy(
                personalSpam = isSpam,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deletePersonalNumber(e164: String): PersonalNumberSnapshot? {
        val snapshot = PersonalNumberSnapshot(
            entry = dao.getNumberEntry(e164),
            override = dao.getOverride(e164),
        )
        if (snapshot.entry == null && snapshot.override == null) return null
        dao.deletePersonalNumber(e164)
        return snapshot
    }

    suspend fun restorePersonalNumber(snapshot: PersonalNumberSnapshot): Boolean =
        dao.restorePersonalNumber(snapshot.entry, snapshot.override)

    suspend fun recordCall(identity: CallerIdentity, direction: String, blocked: Boolean) {
        dao.insertCallRecord(
            CallRecordEntity(
                e164 = identity.e164,
                direction = direction,
                eventAt = System.currentTimeMillis(),
                displayName = identity.displayName,
                category = identity.category,
                wasBlocked = blocked,
                source = if (identity.personalSpam && !identity.externalSpam) "personal" else identity.source,
            ),
        )
    }

    suspend fun clearExternalCache() = dao.clearEvidence()
}

data class ManualLookupOutcome(
    val identity: CallerIdentity,
    val providerSucceeded: Boolean,
    val providerFailed: Boolean,
)

private data class ProviderLookupOutcome(
    val queriedCount: Int,
    val cachedCount: Int,
)
