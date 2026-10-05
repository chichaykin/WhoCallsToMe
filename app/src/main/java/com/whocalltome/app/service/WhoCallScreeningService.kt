package com.whocalltome.app.service

import android.telecom.Call
import android.telecom.CallScreeningService
import android.os.SystemClock
import android.util.Log
import com.whocalltome.app.appContainer
import com.whocalltome.app.data.model.CallerCategory
import com.whocalltome.app.data.model.CallerIdentity
import com.whocalltome.app.data.model.LookupUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class WhoCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val startedAt = SystemClock.elapsedRealtime()
        val rawNumber = callDetails.handle?.schemeSpecificPart
        val e164 = rawNumber?.let(appContainer.numberNormalizer::normalize)
        val incoming = callDetails.callDirection == Call.Details.DIRECTION_INCOMING

        if (e164 == null) {
            if (incoming) respondToCall(callDetails, allowResponse())
            Log.i(TAG, "screened incoming=$incoming decision=ALLOW reason=no-number")
            return
        }

        val localIdentity = try {
            runBlocking {
                withTimeoutOrNull(350) { appContainer.repository.resolveLocal(e164) }
            }
        } catch (error: Exception) {
            Log.w(TAG, "local caller lookup unavailable: ${error.javaClass.simpleName}")
            null
        } ?: CallerIdentity(
            e164 = e164,
            displayName = null,
            category = CallerCategory.UNKNOWN,
            source = "timeout",
        )

        val blocked = incoming && localIdentity.shouldBlock
        val notificationSession = if (incoming) {
            appContainer.notificationManager.beginSession(e164)
        } else {
            0L
        }
        val initialLookup = CallerLookupNotificationState.from(LookupUpdate(localIdentity))
        if (incoming) {
            respondToCall(callDetails, if (blocked) blockResponse() else allowResponse())
            postNotification(
                identity = localIdentity,
                blocked = blocked,
                lookup = initialLookup,
                session = notificationSession,
                alert = blocked || localIdentity.shouldWarn,
            )
            Log.i(
                TAG,
                "screened incoming=true decision=${if (blocked) "BLOCK" else "ALLOW"} " +
                    "source=${localIdentity.source} localMs=${SystemClock.elapsedRealtime() - startedAt}",
            )
        }

        appContainer.applicationScope.launch {
            try {
                var identity = localIdentity
                var previousWarning = localIdentity.shouldWarn
                var notifiedIdentity = localIdentity
                var notifiedLookup = initialLookup
                var latestUpdate = LookupUpdate(localIdentity)
                fun publish(update: LookupUpdate, interrupted: Boolean = false) {
                    latestUpdate = update
                    identity = update.identity
                    val becameWarning = !previousWarning && identity.shouldWarn
                    previousWarning = identity.shouldWarn
                    val lookup = CallerLookupNotificationState.from(update, interrupted)
                    // Progress changes must be shown even when the caller's name and score do not change.
                    if (incoming && (identity != notifiedIdentity || lookup != notifiedLookup)) {
                        postNotification(identity, false, lookup, notificationSession, becameWarning)
                        notifiedIdentity = identity
                        notifiedLookup = lookup
                    }
                }
                try {
                    if (!blocked) {
                        collectCallerLookupProgress(
                            initial = latestUpdate,
                            updates = appContainer.repository.resolveUpdates(
                                e164 = e164,
                                allowNetwork = true,
                                allowNetworkForContacts = false,
                            ),
                        ) { update, interrupted -> publish(update, interrupted) }
                    }
                } catch (error: CancellationException) {
                    if (!blocked) publish(latestUpdate, interrupted = true)
                    throw error
                } catch (error: Exception) {
                    if (!blocked) publish(latestUpdate, interrupted = true)
                    Log.w(TAG, "caller lookup unavailable: ${error.javaClass.simpleName}")
                }
                appContainer.repository.recordCall(
                    identity = identity,
                    direction = if (incoming) "INCOMING" else "OUTGOING",
                    blocked = blocked,
                )
                Log.i(
                    TAG,
                    "resolved category=${identity.category} source=${identity.source} " +
                        "blocked=$blocked totalMs=${SystemClock.elapsedRealtime() - startedAt}",
                )
            } finally {
                if (incoming) appContainer.notificationManager.finishSession(e164, notificationSession)
            }
        }
    }

    private fun allowResponse(): CallResponse = CallResponse.Builder()
        .setDisallowCall(false)
        .setRejectCall(false)
        .setSilenceCall(false)
        .setSkipCallLog(false)
        .setSkipNotification(false)
        .build()

    private fun blockResponse(): CallResponse = CallResponse.Builder()
        .setDisallowCall(true)
        .setRejectCall(true)
        .setSilenceCall(true)
        .setSkipCallLog(false)
        .setSkipNotification(false)
        .build()

    private fun postNotification(
        identity: CallerIdentity,
        blocked: Boolean,
        lookup: CallerLookupNotificationState,
        session: Long,
        alert: Boolean,
    ) {
        try {
            appContainer.notificationManager.show(identity, blocked, lookup, session, alert)
        } catch (error: RuntimeException) {
            Log.w(TAG, "caller notification unavailable: ${error.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "WhoCallScreening"
    }
}
