package com.whocalltome.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.whocalltome.app.data.model.PersonalAction
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Query("SELECT * FROM call_records ORDER BY eventAt DESC LIMIT :limit")
    fun observeRecentCalls(limit: Int = 500): Flow<List<CallRecordEntity>>

    @Query("SELECT * FROM number_entries ORDER BY updatedAt DESC")
    fun observeNumberEntries(): Flow<List<NumberEntryEntity>>

    @Query("SELECT * FROM user_overrides ORDER BY updatedAt DESC")
    fun observeOverrides(): Flow<List<UserOverrideEntity>>

    @Query("SELECT * FROM manual_lookup_history ORDER BY lastAttemptAt DESC LIMIT :limit")
    fun observeManualLookups(limit: Int = 20): Flow<List<ManualLookupEntity>>

    @Query("SELECT * FROM number_entries WHERE e164 = :e164 LIMIT 1")
    suspend fun getNumberEntry(e164: String): NumberEntryEntity?

    @Query("SELECT * FROM user_overrides WHERE e164 = :e164 LIMIT 1")
    suspend fun getOverride(e164: String): UserOverrideEntity?

    @Query(
        "SELECT * FROM lookup_evidence " +
            "WHERE e164 = :e164 AND expiresAt > :now " +
            "ORDER BY isSpam DESC, displayName IS NOT NULL DESC, fetchedAt DESC LIMIT 1",
    )
    suspend fun getFreshEvidence(e164: String, now: Long): LookupEvidenceEntity?

    @Query("SELECT * FROM lookup_evidence WHERE e164 = :e164 ORDER BY fetchedAt DESC")
    suspend fun getEvidence(e164: String): List<LookupEvidenceEntity>

    @Query("SELECT * FROM lookup_provider_state WHERE source = :source LIMIT 1")
    suspend fun getProviderState(source: String): LookupProviderStateEntity?

    @Query("SELECT * FROM number_entries ORDER BY updatedAt DESC")
    suspend fun getAllNumberEntries(): List<NumberEntryEntity>

    @Query("SELECT * FROM user_overrides ORDER BY updatedAt DESC")
    suspend fun getAllOverrides(): List<UserOverrideEntity>

    @Query("SELECT * FROM sync_state ORDER BY source")
    suspend fun getAllSyncStates(): List<SyncStateEntity>

    @Upsert
    suspend fun upsertNumberEntry(value: NumberEntryEntity)

    @Upsert
    suspend fun upsertOverride(value: UserOverrideEntity)

    @Transaction
    suspend fun updateAction(e164: String, action: PersonalAction) {
        val existing = getOverride(e164)
        upsertOverride((existing ?: UserOverrideEntity(e164 = e164)).copy(
            action = action,
            updatedAt = System.currentTimeMillis(),
        ))
    }

    @Transaction
    suspend fun updatePersonalSpam(e164: String, isSpam: Boolean) {
        val existing = getOverride(e164)
        upsertOverride((existing ?: UserOverrideEntity(e164 = e164)).copy(
            personalSpam = isSpam,
            updatedAt = System.currentTimeMillis(),
        ))
    }

    @Upsert
    suspend fun upsertManualLookup(value: ManualLookupEntity)

    @Upsert
    suspend fun upsertEvidence(value: LookupEvidenceEntity)

    @Upsert
    suspend fun upsertProviderState(value: LookupProviderStateEntity)

    @Query("DELETE FROM lookup_provider_state WHERE source = :source")
    suspend fun deleteProviderState(source: String)

    @Upsert
    suspend fun upsertSyncState(value: SyncStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallRecord(value: CallRecordEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallRecords(values: List<CallRecordEntity>)

    @Delete
    suspend fun deleteNumberEntry(value: NumberEntryEntity)

    @Delete
    suspend fun deleteOverride(value: UserOverrideEntity)

    @Query("DELETE FROM number_entries WHERE e164 = :e164")
    suspend fun deleteNumberEntryByNumber(e164: String)

    @Query("DELETE FROM user_overrides WHERE e164 = :e164")
    suspend fun deleteOverrideByNumber(e164: String)

    @Query("DELETE FROM lookup_evidence")
    suspend fun clearEvidence()

    @Query("DELETE FROM manual_lookup_history")
    suspend fun clearManualLookupHistory()

    @Query(
        "DELETE FROM manual_lookup_history WHERE e164 NOT IN " +
            "(SELECT e164 FROM manual_lookup_history ORDER BY lastAttemptAt DESC LIMIT :keep)",
    )
    suspend fun pruneManualLookupHistory(keep: Int = 20)

    @Transaction
    suspend fun recordManualLookup(value: ManualLookupEntity) {
        upsertManualLookup(value)
        pruneManualLookupHistory()
    }

    @Query("DELETE FROM lookup_evidence WHERE expiresAt < :cutoff")
    suspend fun deleteExpiredEvidence(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM lookup_evidence")
    suspend fun countEvidence(): Int

    @Query("SELECT * FROM lookup_evidence ORDER BY fetchedAt ASC LIMIT :limit")
    suspend fun getOldestEvidence(limit: Int): List<LookupEvidenceEntity>

    @Delete
    suspend fun deleteEvidence(values: List<LookupEvidenceEntity>)

    @Transaction
    suspend fun replaceUserData(
        entries: List<NumberEntryEntity>,
        overrides: List<UserOverrideEntity>,
    ) {
        entries.forEach { upsertNumberEntry(it) }
        overrides.forEach { upsertOverride(it) }
    }

    @Transaction
    suspend fun applyUserData(
        entries: List<NumberEntryEntity>,
        overrides: List<UserOverrideEntity>,
        replaceConflicts: Boolean,
    ) {
        val existing = (getAllNumberEntries().map(NumberEntryEntity::e164) +
            getAllOverrides().map(UserOverrideEntity::e164)).toSet()
        entries.filter { replaceConflicts || it.e164 !in existing }.forEach { upsertNumberEntry(it) }
        overrides.filter { replaceConflicts || it.e164 !in existing }.forEach { upsertOverride(it) }
    }

    @Transaction
    suspend fun deletePersonalNumber(e164: String) {
        deleteNumberEntryByNumber(e164)
        deleteOverrideByNumber(e164)
    }

    @Transaction
    suspend fun restorePersonalNumber(
        entry: NumberEntryEntity?,
        override: UserOverrideEntity?,
    ): Boolean {
        val number = entry?.e164 ?: override?.e164 ?: return false
        if (getNumberEntry(number) != null || getOverride(number) != null) return false
        entry?.let { upsertNumberEntry(it) }
        override?.let { upsertOverride(it) }
        return true
    }
}
