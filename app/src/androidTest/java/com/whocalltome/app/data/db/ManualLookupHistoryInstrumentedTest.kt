package com.whocalltome.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualLookupHistoryInstrumentedTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun historyKeepsTwentyNewestDistinctNumbersAndMovesRepeatedNumberToTop() = runBlocking {
        val dao = database.dao()
        repeat(21) { index ->
            dao.recordManualLookup(
                ManualLookupEntity(
                    e164 = "+65000000${index.toString().padStart(2, '0')}",
                    lastAttemptAt = index.toLong(),
                ),
            )
        }

        var history = dao.observeManualLookups().first()
        assertEquals(20, history.size)
        assertFalse(history.any { it.e164.endsWith("00") })
        assertEquals("+6500000020", history.first().e164)

        dao.recordManualLookup(ManualLookupEntity("+6500000001", lastAttemptAt = 100))
        history = dao.observeManualLookups().first()
        assertEquals("+6500000001", history.first().e164)
        assertEquals(20, history.size)

        dao.clearManualLookupHistory()
        assertTrue(dao.observeManualLookups().first().isEmpty())
    }
}
