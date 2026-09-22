package com.whocalltome.app.data.repository

import com.whocalltome.app.data.db.LookupEvidenceEntity
import com.whocalltome.app.data.model.LookupResult
import com.whocalltome.app.data.model.LookupStatus
import com.whocalltome.app.data.model.NumberLookupProvider
import com.whocalltome.app.data.model.ExternalName

internal object LookupCachePolicy {
    private val sourceOrder = listOf("ipqs", "tellows", "phoneblock")

    fun sourceRank(source: String): Int = sourceOrder.indexOf(source).takeIf { it >= 0 }
        ?: sourceOrder.size

    fun selectDisplayName(contactName: String?, names: List<ExternalName>): String? =
        contactName ?: names.firstOrNull()?.value

    fun providersNeedingLookup(
        providers: List<NumberLookupProvider>,
        evidence: List<LookupEvidenceEntity>,
        now: Long,
        force: Boolean,
    ): List<NumberLookupProvider> = providers.filter { provider ->
        force || !isCurrent(evidence.firstOrNull { it.source == provider.id }, now)
    }

    fun isNameFresh(evidence: LookupEvidenceEntity, now: Long): Boolean =
        !evidence.displayName.isNullOrBlank() && nameExpiry(evidence) > now

    fun isReputationFresh(evidence: LookupEvidenceEntity, now: Long): Boolean =
        hasReputation(evidence) && reputationExpiry(evidence) > now

    fun isNegativeFresh(evidence: LookupEvidenceEntity, now: Long): Boolean =
        evidence.status == LookupStatus.NOT_FOUND.name && negativeExpiry(evidence) > now

    fun entityFor(
        result: LookupResult,
        previous: LookupEvidenceEntity?,
    ): LookupEvidenceEntity {
        val keepsPreviousName = previous?.takeIf { isNameFresh(it, result.fetchedAt) }
        val keepsPreviousReputation = previous?.takeIf { isReputationFresh(it, result.fetchedAt) }
        val hasNewReputation = result.spamScore != null || result.isSpam || !result.providerCategory.isNullOrBlank()
        val displayName = result.displayName ?: keepsPreviousName?.displayName
        val nameExpiresAt = result.nameExpiresAt ?: keepsPreviousName?.nameExpiresAt
        val nameFetchedAt = if (result.displayName != null) result.fetchedAt else keepsPreviousName?.nameFetchedAt
        val spamScore = if (hasNewReputation) result.spamScore else keepsPreviousReputation?.spamScore
        val isSpam = if (hasNewReputation) result.isSpam else keepsPreviousReputation?.isSpam ?: false
        val providerCategory = if (hasNewReputation) {
            result.providerCategory
        } else {
            keepsPreviousReputation?.providerCategory
        }
        val reputationExpiresAt = if (hasNewReputation) {
            result.reputationExpiresAt
        } else {
            keepsPreviousReputation?.reputationExpiresAt
        }
        val reputationFetchedAt = if (hasNewReputation) result.fetchedAt else keepsPreviousReputation?.reputationFetchedAt
        val latestExpiry = listOfNotNull(
            nameExpiresAt,
            reputationExpiresAt,
            result.refreshExpiresAt,
            result.negativeExpiresAt,
        ).maxOrNull() ?: result.fetchedAt
        return LookupEvidenceEntity(
            e164 = result.e164,
            source = result.source,
            status = result.status.name,
            displayName = displayName,
            spamScore = spamScore,
            isSpam = isSpam,
            providerCategory = providerCategory,
            fetchedAt = result.fetchedAt,
            expiresAt = latestExpiry,
            nameExpiresAt = nameExpiresAt,
            reputationExpiresAt = reputationExpiresAt,
            refreshExpiresAt = result.refreshExpiresAt,
            negativeExpiresAt = result.negativeExpiresAt,
            nameFetchedAt = nameFetchedAt,
            reputationFetchedAt = reputationFetchedAt,
        )
    }

    private fun isCurrent(evidence: LookupEvidenceEntity?, now: Long): Boolean {
        if (evidence == null) return false
        if (isNegativeFresh(evidence, now)) return true
        if (evidence.status != LookupStatus.FOUND.name) return false
        val expiries = buildList {
            if (!evidence.displayName.isNullOrBlank()) add(nameExpiry(evidence))
            evidence.reputationExpiresAt?.let(::add)
                ?: if (hasReputation(evidence)) add(reputationExpiry(evidence)) else Unit
        }
        return expiries.isNotEmpty() &&
            expiries.all { it > now } &&
            (evidence.refreshExpiresAt == null || evidence.refreshExpiresAt > now)
    }

    private fun nameExpiry(evidence: LookupEvidenceEntity): Long =
        evidence.nameExpiresAt ?: evidence.expiresAt

    private fun reputationExpiry(evidence: LookupEvidenceEntity): Long =
        evidence.reputationExpiresAt ?: evidence.expiresAt

    private fun negativeExpiry(evidence: LookupEvidenceEntity): Long =
        evidence.negativeExpiresAt ?: evidence.expiresAt

    private fun hasReputation(evidence: LookupEvidenceEntity): Boolean =
        evidence.spamScore != null || evidence.isSpam || !evidence.providerCategory.isNullOrBlank()
}
