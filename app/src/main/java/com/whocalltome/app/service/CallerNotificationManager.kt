package com.whocalltome.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.whocalltome.app.data.model.CallerIdentity
import com.whocalltome.app.ui.MainActivity

class CallerNotificationManager(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val sessions = CallerNotificationSessions()

    internal fun beginSession(number: String): Long = sessions.begin(number)

    internal fun finishSession(number: String, session: Long) = sessions.finish(number, session)

    fun createChannel() {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Определение звонков",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Имя и предупреждение при входящем звонке"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    internal fun show(
        identity: CallerIdentity,
        blocked: Boolean,
        lookup: CallerLookupNotificationState,
        session: Long,
        alert: Boolean = false,
    ) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val title = when {
            blocked -> "Звонок заблокирован"
            identity.shouldWarn -> "Возможный спам"
            identity.displayName != null -> identity.displayName
            else -> identity.e164
        }
        val details = buildList {
            add(identity.e164)
            identity.displayName?.takeIf { it != title }?.let(::add)
            if (identity.personalSpam) add("Помечено вами как спам")
            if (identity.externalSpam) {
                val sources = identity.externalReputations
                    .filter { it.isSpam }
                    .map { providerName(it.source) }
                    .distinct()
                    .ifEmpty { listOfNotNull(identity.externalSource?.let(::providerName)) }
                add("Возможный спам${sources.joinToString(", ").takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}")
            }
        }.joinToString(" · ")
        val expandedDetails = (listOf(lookup.summary, details) + lookup.details).joinToString("\n")

        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_PHONE_NUMBER, identity.e164)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            identity.e164.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(title)
            .setContentText(lookup.summary)
            .setStyle(Notification.BigTextStyle().bigText(expandedDetails))
            .setProgress(0, 0, lookup.checking)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!alert)
            .setTimeoutAfter(60_000)
            .build()
        sessions.publish(identity.e164, session) {
            manager.notify(identity.e164.hashCode(), notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "caller_id"
    }
}
