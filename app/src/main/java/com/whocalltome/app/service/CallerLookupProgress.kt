package com.whocalltome.app.service

import com.whocalltome.app.data.model.LookupUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun collectCallerLookupProgress(
    initial: LookupUpdate,
    updates: Flow<LookupUpdate>,
    timeoutMillis: Long = 10_000L,
    publish: (LookupUpdate, Boolean) -> Unit,
) {
    var latest = initial
    val completed = withTimeoutOrNull(timeoutMillis) {
        updates.collect { update ->
            latest = update
            publish(update, false)
        }
        true
    }
    if (completed != true) publish(latest, true)
}
