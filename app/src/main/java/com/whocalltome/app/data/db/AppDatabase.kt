package com.whocalltome.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        NumberEntryEntity::class,
        LookupEvidenceEntity::class,
        CallRecordEntity::class,
        SyncStateEntity::class,
        UserOverrideEntity::class,
        ManualLookupEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE call_records ADD COLUMN systemCallId INTEGER DEFAULT NULL")
                db.execSQL(
                    "ALTER TABLE call_records ADD COLUMN durationSeconds INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_call_records_systemCallId " +
                        "ON call_records(systemCallId)",
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS number_entries_new (" +
                        "e164 TEXT NOT NULL, " +
                        "note TEXT NOT NULL, " +
                        "category TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(e164))",
                )
                db.execSQL(
                    "INSERT INTO number_entries_new(e164, note, category, createdAt, updatedAt) " +
                        "SELECT e164, note, category, createdAt, updatedAt FROM number_entries",
                )
                db.execSQL("DROP TABLE number_entries")
                db.execSQL("ALTER TABLE number_entries_new RENAME TO number_entries")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS user_overrides_new (" +
                        "e164 TEXT NOT NULL, " +
                        "action TEXT NOT NULL, " +
                        "personalSpam INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(e164))",
                )
                db.execSQL(
                    "INSERT INTO user_overrides_new(e164, action, personalSpam, updatedAt) " +
                        "SELECT e164, action, personalSpam, updatedAt FROM user_overrides",
                )
                db.execSQL("DROP TABLE user_overrides")
                db.execSQL("ALTER TABLE user_overrides_new RENAME TO user_overrides")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS manual_lookup_history (" +
                        "e164 TEXT NOT NULL, " +
                        "lastAttemptAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(e164))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_manual_lookup_history_lastAttemptAt " +
                        "ON manual_lookup_history(lastAttemptAt)",
                )
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE lookup_evidence ADD COLUMN nameExpiresAt INTEGER")
                db.execSQL("ALTER TABLE lookup_evidence ADD COLUMN reputationExpiresAt INTEGER")
                db.execSQL("ALTER TABLE lookup_evidence ADD COLUMN negativeExpiresAt INTEGER")
                db.execSQL(
                    "UPDATE lookup_evidence SET nameExpiresAt = expiresAt " +
                        "WHERE displayName IS NOT NULL AND TRIM(displayName) != ''",
                )
                db.execSQL(
                    "UPDATE lookup_evidence SET reputationExpiresAt = expiresAt " +
                        "WHERE spamScore IS NOT NULL OR isSpam = 1 OR " +
                        "(providerCategory IS NOT NULL AND TRIM(providerCategory) != '')",
                )
                db.execSQL(
                    "UPDATE lookup_evidence SET reputationExpiresAt = " +
                        "MIN(expiresAt, fetchedAt + 86400000) " +
                        "WHERE status = 'FOUND' AND reputationExpiresAt IS NULL",
                )
                db.execSQL(
                    "UPDATE lookup_evidence SET negativeExpiresAt = expiresAt " +
                        "WHERE status = 'NOT_FOUND'",
                )
            }
        }

        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE lookup_evidence ADD COLUMN refreshExpiresAt INTEGER")
                db.execSQL(
                    "UPDATE lookup_evidence SET refreshExpiresAt = " +
                        "MIN(expiresAt, fetchedAt + 86400000) WHERE status = 'FOUND'",
                )
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "who-call-to-me.db",
            ).addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
            ).build()
    }
}
