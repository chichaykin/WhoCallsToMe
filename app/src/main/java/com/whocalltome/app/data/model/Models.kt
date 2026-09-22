package com.whocalltome.app.data.model

import kotlinx.coroutines.flow.Flow

enum class CallerCategory {
    CONTACT,
    INTERNET,
    SPAM,
    UNKNOWN,
}

enum class PersonalAction {
    DEFAULT,
    ALLOW,
    BLOCK,
}

enum class LookupStatus {
    FOUND,
    NOT_FOUND,
    NOT_CONFIGURED,
    QUOTA_EXHAUSTED,
    NETWORK_ERROR,
    PROVIDER_ERROR,
}

data class LookupResult(
    val e164: String,
    val source: String,
    val status: LookupStatus,
    val displayName: String? = null,
    val spamScore: Int? = null,
    val isSpam: Boolean = false,
    val providerCategory: String? = null,
    val fetchedAt: Long = System.currentTimeMillis(),
    val nameExpiresAt: Long? = null,
    val reputationExpiresAt: Long? = null,
    val refreshExpiresAt: Long? = null,
    val negativeExpiresAt: Long? = null,
    val message: String? = null,
) {
    companion object {
        const val NAME_CACHE_MILLIS = 30L * 24L * 60L * 60L * 1_000L
        const val REPUTATION_CACHE_MILLIS = 24L * 60L * 60L * 1_000L
        const val DEFAULT_NEGATIVE_CACHE_MILLIS = 24L * 60L * 60L * 1_000L
    }
}

data class ExternalName(
    val source: String,
    val value: String,
)

data class ExternalReputation(
    val source: String,
    val score: Int?,
    val isSpam: Boolean,
)

data class ProviderLookupStatus(
    val source: String,
    val status: LookupStatus? = null,
    val message: String? = null,
    val nextAttemptAt: Long? = null,
)

data class FeedSyncResult(
    val source: String,
    val cursor: String?,
    val changedRecords: Int,
    val completedAt: Long,
    val error: String? = null,
)

data class CallerIdentity(
    val e164: String,
    val displayName: String?,
    val category: CallerCategory,
    val source: String,
    val spamScore: Int? = null,
    val isSpam: Boolean = false,
    val personalAction: PersonalAction = PersonalAction.DEFAULT,
    val checkedAt: Long? = null,
    val nameSource: String? = null,
    val personalSpam: Boolean = false,
    val externalSpam: Boolean = false,
    val externalSpamScore: Int? = null,
    val externalSource: String? = null,
    val hasPersonalRecord: Boolean = false,
    val externalNames: List<ExternalName> = emptyList(),
    val externalReputations: List<ExternalReputation> = emptyList(),
) {
    val shouldBlock: Boolean
        get() = personalAction == PersonalAction.BLOCK

    val shouldWarn: Boolean
        get() = personalSpam || externalSpam || isSpam
}

data class LookupUpdate(
    val identity: CallerIdentity,
    val providers: List<ProviderLookupStatus> = emptyList(),
    val isComplete: Boolean = false,
)

interface NumberLookupProvider {
    val id: String
    suspend fun lookup(e164: String): LookupResult
}

interface SpamFeedProvider {
    val id: String
    suspend fun sync(cursor: String?): FeedSyncResult
}

interface CallerIdentityRepository {
    suspend fun resolve(
        e164: String,
        allowNetwork: Boolean = true,
        allowNetworkForContacts: Boolean = false,
    ): CallerIdentity
    suspend fun resolveLocal(e164: String): CallerIdentity
    fun resolveUpdates(
        e164: String,
        force: Boolean = false,
        allowNetwork: Boolean = true,
        allowNetworkForContacts: Boolean = false,
        useAllConfiguredProviders: Boolean = false,
    ): Flow<LookupUpdate>
}
