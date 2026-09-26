package com.whocalltome.app.data.db

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonalProfileMigrationInstrumentedTest {
    @Test
    fun migrationDropsNotesAndKeepsNumbersRulesAndTimestamps() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "personal-profile-migration-test.db"
        context.deleteDatabase(dbName)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE number_entries (e164 TEXT NOT NULL PRIMARY KEY, note TEXT NOT NULL, category TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE call_records (id INTEGER NOT NULL PRIMARY KEY, e164 TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE user_overrides (e164 TEXT NOT NULL PRIMARY KEY, action TEXT NOT NULL, personalSpam INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        try {
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO number_entries VALUES ('+6500000000', 'old note', 'UNKNOWN', 10, 20)")
            db.execSQL("INSERT INTO user_overrides VALUES ('+6500000000', 'BLOCK', 1, 30)")
            AppDatabase.MIGRATION_7_8.migrate(db)
            AppDatabase.MIGRATION_8_9.migrate(db)
            db.query("SELECT personalName, numberType, category, createdAt, updatedAt FROM number_entries").use { cursor ->
                cursor.moveToFirst()
                assertEquals("", cursor.getString(0))
                assertEquals("UNSPECIFIED", cursor.getString(1))
                assertEquals("UNKNOWN", cursor.getString(2))
                assertEquals(10L, cursor.getLong(3))
                assertEquals(20L, cursor.getLong(4))
            }
            db.query("PRAGMA table_info(number_entries)").use { cursor ->
                val columnIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) assertFalse(cursor.getString(columnIndex) == "note")
            }
            db.query("PRAGMA table_info(call_records)").use { cursor ->
                val columnIndex = cursor.getColumnIndexOrThrow("name")
                var found = false
                while (cursor.moveToNext()) found = found || cursor.getString(columnIndex) == "nameSource"
                assertEquals(true, found)
            }
            db.query("SELECT action, personalSpam FROM user_overrides").use { cursor ->
                cursor.moveToFirst()
                assertEquals("BLOCK", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
            }
        } finally {
            helper.close()
            context.deleteDatabase(dbName)
        }
    }
}
