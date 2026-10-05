package com.whocalltome.app.service

/** An older lookup must never overwrite a later call's notification for the same number. */
internal class CallerNotificationSessions {
    private var nextSession = 0L
    private val current = mutableMapOf<String, Long>()

    @Synchronized
    fun begin(number: String): Long = (++nextSession).also { current[number] = it }

    @Synchronized
    fun publish(number: String, session: Long, action: () -> Unit) {
        if (current[number] == session) action()
    }

    @Synchronized
    fun finish(number: String, session: Long) {
        if (current[number] == session) current.remove(number)
    }
}
