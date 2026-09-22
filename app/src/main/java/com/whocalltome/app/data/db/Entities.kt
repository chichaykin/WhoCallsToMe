package com.whocalltome.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.whocalltome.app.data.model.CallerCategory
import com.whocalltome.app.data.model.PersonalAction

@Entity(tableName = "number_entries")
data class NumberEntryEntity(
    @PrimaryKey val e164: String,
    val note: String = "",
    val category: CallerCategory = CallerCategory.UNKNOWN,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "lookup_evidence",
    primaryKeys = ["e164", "source"],
    indices = [Index("expiresAt"), Index("fetchedAt")],
)
data class LookupEvidenceEntity(
    val e164: String,
    val source: String,
    val status: String,
    val displayName: String? = null,
    val spamScore: Int? = null,
    val isSpam: Boolean = false,
    val providerCategory: String? = null,
    val fetchedAt: Long,
    val expiresAt: Long,
    val nameExpiresAt: Long? = null,
    val reputationExpiresAt: Long? = null,
    val refreshExpiresAt: Long? = null,
    val negativeExpiresAt: Long? = null,
    /** Null means the timestamp predates field-level tracking. */
    val nameFetchedAt: Long? = null,
    /** Null means the timestamp predates field-level tracking. */
    val reputationFetchedAt: Long? = null,
)

@Entity(tableName = "lookup_provider_state")
data class LookupProviderStateEntity(
    @PrimaryKey val source: String,
    val consecutiveFailures: Int = 0,
    val nextAttemptAt: Long = 0,
    val lastStatus: String? = null,
    val lastMessage: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "call_records",
    indices = [Index("e164"), Index("eventAt"), Index(value = ["systemCallId"], unique = true)],
)
data class CallRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val e164: String,
    val direction: String,
    val eventAt: Long,
    val displayName: String? = null,
    val category: CallerCategory = CallerCategory.UNKNOWN,
    val wasBlocked: Boolean = false,
    val source: String = "local",
    val systemCallId: Long? = null,
    val durationSeconds: Long = 0,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val source: String,
    val cursor: String? = null,
    val lastSuccessAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val lastError: String? = null,
    val downloadedBytes: Long = 0,
)

@Entity(tableName = "user_overrides")
data class UserOverrideEntity(
    @PrimaryKey val e164: String,
    val action: PersonalAction = PersonalAction.DEFAULT,
    val personalSpam: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "manual_lookup_history",
    indices = [Index("lastAttemptAt")],
)
data class ManualLookupEntity(
    @PrimaryKey val e164: String,
    val lastAttemptAt: Long = System.currentTimeMillis(),
)
