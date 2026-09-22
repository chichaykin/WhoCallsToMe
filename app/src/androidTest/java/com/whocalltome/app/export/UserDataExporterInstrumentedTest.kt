package com.whocalltome.app.export

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whocalltome.app.data.db.AppDatabase
import com.whocalltome.app.data.db.NumberEntryEntity
import com.whocalltome.app.data.db.UserOverrideEntity
import com.whocalltome.app.data.model.CallerCategory
import com.whocalltome.app.data.model.PersonalAction
import com.whocalltome.app.data.settings.AppPreferences
import com.whocalltome.app.data.settings.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserDataExporterInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var preferences: AppPreferences
    private lateinit var originalTheme: ThemeMode

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferences = AppPreferences(context)
        originalTheme = preferences.themeMode
    }

    @After
    fun tearDown() {
        preferences.themeMode = originalTheme
        database.close()
    }

    @Test
    fun exportAndImportRoundTripKeepsOnlyPortableUserData() = runBlocking {
        val dao = database.dao()
        val exporter = UserDataExporter(dao, preferences)
        dao.upsertNumberEntry(
            NumberEntryEntity(
                e164 = "+6599990001",
                note = "Test note",
                category = CallerCategory.SPAM,
            ),
        )
        dao.upsertOverride(
            UserOverrideEntity(
                e164 = "+6599990001",
                action = PersonalAction.BLOCK,
                personalSpam = true,
            ),
        )

        val json = exporter.exportJson()
        assertTrue(json.contains("Test note"))
        assertFalse(json.contains("personalName"))
        assertFalse(json.contains("displayNameOverride"))
        assertFalse(json.contains("lookupProvider"))
        assertFalse(json.contains("api_key", ignoreCase = true))
        assertFalse(json.contains("bearer", ignoreCase = true))

        dao.deletePersonalNumber("+6599990001")
        val result = exporter.importJson(json)

        assertEquals(1, result.entries)
        assertEquals(1, result.overrides)
        assertEquals("Test note", dao.getNumberEntry("+6599990001")?.note)
        assertEquals(PersonalAction.BLOCK, dao.getOverride("+6599990001")?.action)
    }

    @Test
    fun legacyExportImportsRulesButDropsLegacyNames() = runBlocking {
        val exporter = UserDataExporter(database.dao(), preferences)
        val legacy = """
            {
              "format":"who-call-to-me",
              "version":1,
              "numberEntries":[{
                "e164":"+6599990002",
                "personalName":"Old name",
                "note":"Keep this note",
                "category":"CONTACT",
                "createdAt":1,
                "updatedAt":2
              }],
              "settings":{"lookupProvider":"ipqs","themeMode":"dark"},
              "userOverrides":[{
                "e164":"+6599990002",
                "displayNameOverride":"Old override",
                "action":"ALLOW",
                "personalSpam":true,
                "updatedAt":2
              }]
            }
        """.trimIndent()

        val result = exporter.importJson(legacy)

        assertEquals(1, result.entries)
        assertEquals(1, result.overrides)
        assertEquals("Keep this note", database.dao().getNumberEntry("+6599990002")?.note)
        assertEquals(PersonalAction.ALLOW, database.dao().getOverride("+6599990002")?.action)
        assertTrue(database.dao().getOverride("+6599990002")?.personalSpam == true)
        assertEquals(ThemeMode.DARK, preferences.themeMode)
    }
}
