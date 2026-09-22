package com.whocalltome.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.whocalltome.app.data.model.CallerCategory
import com.whocalltome.app.data.model.CallerIdentity
import com.whocalltome.app.data.model.PersonalAction
import com.whocalltome.app.ui.MainActivity

class CallerNotificationManager(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

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

    fun show(identity: CallerIdentity, blocked: Boolean) {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val title = when {
            blocked -> "Звонок заблокирован"
            identity.category == CallerCategory.SPAM -> "Возможный спам"
            identity.displayName != null -> identity.displayName
            else -> "Неизвестный номер"
        }
        val details = buildList {
            add(identity.e164)
            identity.displayName?.takeIf { it != title }?.let(::add)
            if (identity.personalAction == PersonalAction.ALLOW) add("разрешено вашим правилом")
            if (identity.personalAction == PersonalAction.BLOCK) add("заблокировано вашим правилом")
            if (identity.personalSpam) add("личная метка: спам")
            if (identity.externalSpam) add("внешнее предупреждение о спаме")
            identity.externalSpamScore?.let { add("риск: $it") }
            add("источник: ${identity.source}")
        }.joinToString(" · ")

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
            .setContentText(details)
            .setStyle(Notification.BigTextStyle().bigText(details))
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        manager.notify(identity.e164.hashCode(), notification)
    }

    companion object {
        private const val CHANNEL_ID = "caller_id"
    }
}
