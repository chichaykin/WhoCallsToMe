package com.whocalltome.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class CallerNotificationSessionsTest {
    @Test
    fun lateResponseCannotOverwriteRepeatedCallEvenAfterNewCallFinishes() {
        val sessions = CallerNotificationSessions()
        val published = mutableListOf<String>()
        val first = sessions.begin("caller")
        sessions.publish("caller", first) { published += "first pending" }
        val second = sessions.begin("caller")
        sessions.publish("caller", first) { published += "old response" }
        sessions.finish("caller", first)
        sessions.publish("caller", second) { published += "second complete" }
        sessions.finish("caller", second)
        sessions.publish("caller", first) { published += "old timeout" }

        assertEquals(listOf("first pending", "second complete"), published)
    }

    @Test
    fun independentCallersDoNotSuppressEachOther() {
        val sessions = CallerNotificationSessions()
        val published = mutableListOf<String>()
        val first = sessions.begin("caller one")
        val second = sessions.begin("caller two")
        sessions.publish("caller one", first) { published += "one" }
        sessions.finish("caller one", first)
        sessions.publish("caller two", second) { published += "two" }

        assertEquals(listOf("one", "two"), published)
    }
}
