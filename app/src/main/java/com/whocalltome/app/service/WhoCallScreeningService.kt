package com.whocalltome.app.service

import android.telecom.Call
import android.telecom.CallScreeningService
import android.os.SystemClock
import android.util.Log
import com.whocalltome.app.appContainer
import com.whocalltome.app.data.model.CallerCategory
import com.whocalltome.app.data.model.CallerIdentity
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

        val localIdentity = runBlocking {
            withTimeoutOrNull(350) { appContainer.repository.resolveLocal(e164) }
        } ?: CallerIdentity(
            e164 = e164,
            displayName = null,
            category = CallerCategory.UNKNOWN,
            source = "timeout",
        )

        val blocked = incoming && localIdentity.shouldBlock
        if (incoming) {
            respondToCall(callDetails, if (blocked) blockResponse() else allowResponse())
            Log.i(
                TAG,
                "screened incoming=true decision=${if (blocked) "BLOCK" else "ALLOW"} " +
                    "source=${localIdentity.source} localMs=${SystemClock.elapsedRealtime() - startedAt}",
            )
        }

        appContainer.applicationScope.launch {
            var identity = localIdentity
            var previousWarning = localIdentity.shouldWarn
            var firstNotification = true
            if (!blocked) {
                appContainer.repository.resolveUpdates(
                    e164 = e164,
                    allowNetwork = true,
                    allowNetworkForContacts = false,
                ).collect { update ->
                    identity = update.identity
                    val becameWarning = !previousWarning && identity.shouldWarn
                    previousWarning = identity.shouldWarn
                    // The notification id is stable per number, so each partial answer updates
                    // the same notification rather than creating another alert.
                    if (incoming) {
                        appContainer.notificationManager.show(
                            identity = identity,
                            blocked = false,
                            alert = (firstNotification && identity.shouldWarn) || becameWarning,
                        )
                        firstNotification = false
                    }
                }
            }
            appContainer.repository.recordCall(
                identity = identity,
                direction = if (incoming) "INCOMING" else "OUTGOING",
                blocked = blocked,
            )
            if (incoming && blocked) appContainer.notificationManager.show(identity, blocked, alert = true)
            Log.i(
                TAG,
                "resolved category=${identity.category} source=${identity.source} " +
                    "blocked=$blocked totalMs=${SystemClock.elapsedRealtime() - startedAt}",
            )
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

    companion object {
        private const val TAG = "WhoCallScreening"
    }
}
