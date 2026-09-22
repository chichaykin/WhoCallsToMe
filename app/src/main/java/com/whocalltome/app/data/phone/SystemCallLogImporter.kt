package com.whocalltome.app.data.phone

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat
import com.whocalltome.app.data.db.AppDao
import com.whocalltome.app.data.db.CallRecordEntity
import com.whocalltome.app.data.repository.AppCallerIdentityRepository

class SystemCallLogImporter(
    private val context: Context,
    private val numberNormalizer: NumberNormalizer,
    private val repository: AppCallerIdentityRepository,
    private val dao: AppDao,
) {
    suspend fun importRecent(limit: Int = DEFAULT_LIMIT): ImportCallLogResult {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ImportCallLogResult.PermissionRequired
        }

        val rows = mutableListOf<SystemCallRow>()
        val queryArgs = Bundle().apply {
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(CallLog.Calls.DATE),
            )
            putInt(
                ContentResolver.QUERY_ARG_SORT_DIRECTION,
                ContentResolver.QUERY_SORT_DIRECTION_DESCENDING,
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            PROJECTION,
            queryArgs,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
            val numberColumn = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val typeColumn = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val dateColumn = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durationColumn = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            val nameColumn = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            while (cursor.moveToNext()) {
                val normalized = numberNormalizer.normalize(cursor.getString(numberColumn)) ?: continue
                rows += SystemCallRow(
                    id = cursor.getLong(idColumn),
                    e164 = normalized,
                    type = cursor.getInt(typeColumn),
                    eventAt = cursor.getLong(dateColumn),
                    durationSeconds = cursor.getLong(durationColumn),
                    cachedName = cursor.getString(nameColumn)?.takeIf(String::isNotBlank),
                )
            }
        }

        val records = rows.map { row ->
            val identity = repository.resolveLocal(row.e164)
            CallRecordEntity(
                e164 = row.e164,
                direction = row.type.toDirection(),
                eventAt = row.eventAt,
                displayName = identity.displayName ?: row.cachedName,
                category = identity.category,
                wasBlocked = row.type == CallLog.Calls.BLOCKED_TYPE,
                source = when {
                    identity.source != "unknown" -> identity.source
                    row.cachedName != null -> "system-call-log"
                    else -> "system-call-log"
                },
                systemCallId = row.id,
                durationSeconds = row.durationSeconds,
            )
        }
        if (records.isNotEmpty()) dao.insertCallRecords(records)
        Log.i(TAG, "Imported ${records.size} system call log rows")
        return ImportCallLogResult.Success(records.size)
    }

    private fun Int.toDirection(): String = when (this) {
        CallLog.Calls.INCOMING_TYPE -> "INCOMING"
        CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
        CallLog.Calls.MISSED_TYPE -> "MISSED"
        CallLog.Calls.REJECTED_TYPE -> "REJECTED"
        CallLog.Calls.BLOCKED_TYPE -> "BLOCKED"
        CallLog.Calls.VOICEMAIL_TYPE -> "VOICEMAIL"
        CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "ANSWERED_EXTERNALLY"
        else -> "UNKNOWN"
    }

    private data class SystemCallRow(
        val id: Long,
        val e164: String,
        val type: Int,
        val eventAt: Long,
        val durationSeconds: Long,
        val cachedName: String?,
    )

    companion object {
        private const val DEFAULT_LIMIT = 500
        private const val TAG = "WhoCallImport"
        private val PROJECTION = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.CACHED_NAME,
        )
    }
}

sealed interface ImportCallLogResult {
    data class Success(val count: Int) : ImportCallLogResult
    data object PermissionRequired : ImportCallLogResult
}
