package com.whocalltome.app.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LookupEvidenceMigrationInstrumentedTest {
    private lateinit var context: Context
    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var database: SupportSQLiteDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DATABASE_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE lookup_evidence (" +
                                "e164 TEXT NOT NULL, source TEXT NOT NULL, status TEXT NOT NULL, " +
                                "displayName TEXT, spamScore INTEGER, isSpam INTEGER NOT NULL, " +
                                "providerCategory TEXT, fetchedAt INTEGER NOT NULL, expiresAt INTEGER NOT NULL, " +
                                "PRIMARY KEY(e164, source))",
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        database = helper.writableDatabase
    }

    @After
    fun tearDown() {
        helper.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun migrationPreservesExistingCacheAndSplitsItsExpiryByDataKind() {
        database.execSQL(
            "INSERT INTO lookup_evidence VALUES " +
                "('+6500000000', 'ipqs', 'FOUND', 'Cached caller', 42, 0, 'mobile', 10, 100)",
        )
        database.execSQL(
            "INSERT INTO lookup_evidence VALUES " +
                "('+6500000001', 'phoneblock', 'NOT_FOUND', NULL, NULL, 0, NULL, 10, 200)",
        )

        AppDatabase.MIGRATION_4_5.migrate(database)
        AppDatabase.MIGRATION_5_6.migrate(database)

        database.query(
            "SELECT nameExpiresAt, reputationExpiresAt, refreshExpiresAt, negativeExpiresAt " +
                "FROM lookup_evidence WHERE source = 'ipqs'",
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals(100, cursor.getLong(0))
            assertEquals(100, cursor.getLong(1))
            assertEquals(100, cursor.getLong(2))
            assertEquals(true, cursor.isNull(3))
        }
        database.query(
            "SELECT negativeExpiresAt FROM lookup_evidence WHERE source = 'phoneblock'",
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals(200, cursor.getLong(0))
        }
    }

    private companion object {
        const val DATABASE_NAME = "lookup-evidence-migration-test.db"
    }
}
