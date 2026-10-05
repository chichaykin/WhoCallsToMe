package com.whocalltome.app.data.repository

import android.net.Uri
import com.whocalltome.app.data.db.AppDao
import com.whocalltome.app.data.db.CallRecordEntity
import com.whocalltome.app.data.db.LookupEvidenceEntity
import com.whocalltome.app.data.db.LookupProviderStateEntity
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
import com.whocalltome.app.data.model.LookupUpdate
import com.whocalltome.app.data.model.ProviderLookupStatus
import com.whocalltome.app.data.model.PersonalAction
import com.whocalltome.app.data.model.NumberType
import com.whocalltome.app.data.phone.ContactLookup
import com.whocalltome.app.data.remote.LookupProviderCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Kept internal so cache-policy tests can verify that a slow provider does not serialize peers. */
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
    private val lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightLookups = ConcurrentHashMap<String, Deferred<ProviderLookupStatus>>()

    private companion object {
        val RETRY_DELAYS_MILLIS = longArrayOf(60_000L, 5 * 60_000L, 15 * 60_000L, 60 * 60_000L)
        const val QUOTA_DELAY_MILLIS = 60 * 60_000L
    }
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
        val personalName = entry?.personalName?.takeIf(String::isNotBlank)
        val name = LookupCachePolicy.selectDisplayName(contactName, externalNames, personalName)
        val nameSource = when {
            contactName != null -> "contacts"
            personalName != null -> "personal"
            nameEvidence != null -> nameEvidence.source
            else -> null
        }
        val sources = listOfNotNull(
            if (hasPersonalRecord) "personal" else null,
            if (nameSource == "contacts") "contacts" else null,
            if (nameSource != "personal" && nameSource != "contacts") nameSource else null,
            *externalReputations.map(ExternalReputation::source).toTypedArray(),
        ).distinct()
        val externalSpam = spamEvidence != null
        val category = when {
            personalSpam || externalSpam -> CallerCategory.SPAM
            nameSource == "contacts" -> CallerCategory.CONTACT
            personalName != null -> CallerCategory.PERSONAL
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
            numberType = entry?.numberType ?: NumberType.UNSPECIFIED,
        )
    }

    override suspend fun resolve(
        e164: String,
        allowNetwork: Boolean,
        allowNetworkForContacts: Boolean,
    ): CallerIdentity = resolveUpdates(
        e164 = e164,
        allowNetwork = allowNetwork,
        allowNetworkForContacts = allowNetworkForContacts,
    ).last().identity

    /** Runs every configured provider even when fresh evidence is already cached. */
    suspend fun resolveFresh(e164: String): ManualLookupOutcome {
        val updates = resolveUpdates(
            e164 = e164,
            force = true,
            allowNetworkForContacts = true,
            useAllConfiguredProviders = true,
        ).toList()
        val outcome = updates.last()
        return ManualLookupOutcome(
            identity = outcome.identity,
            providerSucceeded = outcome.providers.any {
                it.status == LookupStatus.FOUND || it.status == LookupStatus.NOT_FOUND
            },
            providerFailed = outcome.providers.any {
                it.status != null && it.status !in setOf(LookupStatus.FOUND, LookupStatus.NOT_FOUND)
            },
        )
    }

    override fun resolveUpdates(
        e164: String,
        force: Boolean,
        allowNetwork: Boolean,
        allowNetworkForContacts: Boolean,
        useAllConfiguredProviders: Boolean,
    ): Flow<LookupUpdate> = flow {
        val local = resolveLocal(e164)
        emit(LookupUpdate(local))
        if (!allowNetwork || (local.nameSource == "contacts" && !allowNetworkForContacts)) {
            emit(LookupUpdate(local, isComplete = true))
            return@flow
        }

        val available = if (useAllConfiguredProviders) {
            providers.configuredProviders()
        } else {
            providers.automaticProviders()
        }
        val evidence = dao.getEvidence(e164)
        val now = System.currentTimeMillis()
        val needed = LookupCachePolicy.providersNeedingLookup(
            providers = available,
            evidence = evidence,
            now = now,
            force = force,
        )
        val updates = available.map { provider ->
            if (provider in needed) ProviderLookupStatus(provider.id)
            else LookupCachePolicy.cachedStatus(provider.id, evidence, now) ?: ProviderLookupStatus(provider.id)
        }.toMutableList()
        if (needed.isEmpty()) {
            emit(LookupUpdate(resolveLocal(e164), updates, isComplete = true))
            return@flow
        }

        emit(LookupUpdate(resolveLocal(e164), updates.toList()))
        val channel = Channel<ProviderLookupStatus>(needed.size)
        coroutineScope {
            needed.forEach { provider ->
                launch { channel.send(sharedLookup(provider, e164, force).await()) }
            }
            repeat(needed.size) {
                val update = channel.receive()
                val index = updates.indexOfFirst { it.source == update.source }
                if (index >= 0) updates[index] = update else updates += update
                emit(LookupUpdate(resolveLocal(e164), updates.toList()))
            }
        }
        channel.close()
        emit(LookupUpdate(resolveLocal(e164), updates, isComplete = true))
    }

    private fun sharedLookup(
        provider: com.whocalltome.app.data.model.NumberLookupProvider,
        e164: String,
        force: Boolean,
    ): Deferred<ProviderLookupStatus> {
        val key = "${provider.id}|$e164"
        inFlightLookups[key]?.let { return it }
        val created = lookupScope.async(start = CoroutineStart.LAZY) {
            performLookup(provider, e164, force)
        }
        val existing = inFlightLookups.putIfAbsent(key, created)
        if (existing != null) {
            created.cancel()
            return existing
        }
        created.invokeOnCompletion { inFlightLookups.remove(key, created) }
        created.start()
        return created
    }

    private suspend fun performLookup(
        provider: com.whocalltome.app.data.model.NumberLookupProvider,
        e164: String,
        force: Boolean,
    ): ProviderLookupStatus {
        // A peer can complete after resolveUpdates selected this provider but before this
        // deferred begins. Re-checking here avoids a second paid request in that window.
        if (!force) {
            LookupCachePolicy.cachedStatus(provider.id, dao.getEvidence(e164), System.currentTimeMillis())
                ?.let { return it }
        }
        val now = System.currentTimeMillis()
        val state = dao.getProviderState(provider.id)
        if (state != null && state.nextAttemptAt > now) {
            return ProviderLookupStatus(
                source = provider.id,
                status = state.lastStatus?.let { runCatching { LookupStatus.valueOf(it) }.getOrNull() },
                message = state.lastMessage,
                nextAttemptAt = state.nextAttemptAt,
            )
        }
        val result = try {
            provider.lookup(e164)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            LookupResult(e164, provider.id, LookupStatus.NETWORK_ERROR, message = "Нет связи с источником")
        }
        if (result.status == LookupStatus.FOUND || result.status == LookupStatus.NOT_FOUND) {
            val previous = dao.getEvidence(e164).firstOrNull { it.source == result.source }
            dao.upsertEvidence(LookupCachePolicy.entityFor(result, previous))
            dao.deleteProviderState(provider.id)
            return ProviderLookupStatus(provider.id, result.status)
        }

        val nextAttemptAt = recordProviderFailure(provider.id, state, result)
        return ProviderLookupStatus(provider.id, result.status, result.message, nextAttemptAt)
    }

    private suspend fun recordProviderFailure(
        source: String,
        previous: LookupProviderStateEntity?,
        result: LookupResult,
    ): Long {
        val now = System.currentTimeMillis()
        val permanent = result.status == LookupStatus.PROVIDER_ERROR &&
            result.message?.contains("недейств", ignoreCase = true) == true
        val failures = if (result.status == LookupStatus.QUOTA_EXHAUSTED || permanent) {
            previous?.consecutiveFailures ?: 0
        } else {
            (previous?.consecutiveFailures ?: 0) + 1
        }
        val delay = when {
            permanent -> Long.MAX_VALUE
            result.status == LookupStatus.QUOTA_EXHAUSTED -> QUOTA_DELAY_MILLIS
            else -> RETRY_DELAYS_MILLIS[(failures - 1).coerceIn(0, RETRY_DELAYS_MILLIS.lastIndex)]
        }
        val nextAttemptAt = if (delay == Long.MAX_VALUE) delay else now + delay
        dao.upsertProviderState(
            LookupProviderStateEntity(
                source = source,
                consecutiveFailures = failures,
                nextAttemptAt = nextAttemptAt,
                lastStatus = result.status.name,
                lastMessage = result.message,
                updatedAt = now,
            ),
        )
        return nextAttemptAt
    }

    suspend fun clearProviderFailure(source: String) = dao.deleteProviderState(source)

    suspend fun savePersonalNumber(e164: String, personalName: String, numberType: NumberType) {
        val now = System.currentTimeMillis()
        val existing = dao.getNumberEntry(e164)
        dao.upsertNumberEntry(
            NumberEntryEntity(
                e164 = e164,
                personalName = personalName.trim(),
                numberType = numberType,
                category = existing?.category ?: CallerCategory.UNKNOWN,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }

    suspend fun setAction(e164: String, action: PersonalAction) {
        dao.updateAction(e164, action)
    }

    suspend fun markPersonalSpam(e164: String, isSpam: Boolean) {
        dao.updatePersonalSpam(e164, isSpam)
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
                nameSource = identity.nameSource,
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
