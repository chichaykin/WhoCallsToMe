package com.whocalltome.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.whocalltome.app.appContainer

class CacheCleanupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val dao = applicationContext.appContainer.dao
        dao.deleteExpiredEvidence(System.currentTimeMillis() - STALE_GRACE_MILLIS)
        val overflow = dao.countEvidence() - MAX_CACHE_RECORDS
        if (overflow > 0) {
            dao.deleteEvidence(dao.getOldestEvidence(overflow))
        }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "lookup-cache-maintenance"
        private const val MAX_CACHE_RECORDS = 50_000
        private const val STALE_GRACE_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    }
}
