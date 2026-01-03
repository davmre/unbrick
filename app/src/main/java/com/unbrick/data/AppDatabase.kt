package com.unbrick.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.unbrick.data.dao.*
import com.unbrick.data.model.*

@Database(
    entities = [
        BlockingProfile::class,
        ProfileApp::class,
        LockState::class,
        NfcTagInfo::class,
        AppSettings::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun blockingProfileDao(): BlockingProfileDao
    abstract fun profileAppDao(): ProfileAppDao
    abstract fun lockStateDao(): LockStateDao
    abstract fun nfcTagDao(): NfcTagDao
    abstract fun appSettingsDao(): AppSettingsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Migration from v1 to v2:
         * - Creates blocking_profiles table
         * - Creates profile_apps table
         * - Migrates existing blocked_apps to a default profile
         * - Updates app_settings schema (removes blockingMode, adds activeProfileId)
         * - Drops old blocked_apps table
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 1. Create blocking_profiles table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS blocking_profiles (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        blockingMode TEXT NOT NULL DEFAULT 'BLOCKLIST',
                        isActive INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL
                    )
                """)

                // 2. Get existing blocking mode from app_settings (if exists)
                var existingMode = "BLOCKLIST"
                val cursor = database.query("SELECT blockingMode FROM app_settings WHERE id = 1")
                if (cursor.moveToFirst()) {
                    val modeIndex = cursor.getColumnIndex("blockingMode")
                    if (modeIndex >= 0) {
                        existingMode = cursor.getString(modeIndex) ?: "BLOCKLIST"
                    }
                }
                cursor.close()

                // 3. Create default profile with existing blocking mode
                val currentTime = System.currentTimeMillis()
                database.execSQL("""
                    INSERT INTO blocking_profiles (name, blockingMode, isActive, createdAt)
                    VALUES ('Default', '$existingMode', 1, $currentTime)
                """)

                // 4. Create profile_apps table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS profile_apps (
                        profileId INTEGER NOT NULL,
                        packageName TEXT NOT NULL,
                        appName TEXT NOT NULL,
                        isBlocked INTEGER NOT NULL DEFAULT 1,
                        PRIMARY KEY (profileId, packageName),
                        FOREIGN KEY (profileId) REFERENCES blocking_profiles(id) ON DELETE CASCADE
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_profile_apps_profileId ON profile_apps(profileId)")

                // 5. Migrate existing blocked_apps to profile_apps for the default profile (id=1)
                database.execSQL("""
                    INSERT INTO profile_apps (profileId, packageName, appName, isBlocked)
                    SELECT 1, packageName, appName, isBlocked FROM blocked_apps
                """)

                // 6. Recreate app_settings with new schema
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS app_settings_new (
                        id INTEGER PRIMARY KEY NOT NULL,
                        activeProfileId INTEGER,
                        unlockDelayMs INTEGER NOT NULL DEFAULT 86400000,
                        blockSettingsWhenLocked INTEGER NOT NULL DEFAULT 1,
                        setupCompleted INTEGER NOT NULL DEFAULT 0
                    )
                """)

                // 7. Migrate existing settings (set activeProfileId to 1 for the default profile)
                database.execSQL("""
                    INSERT INTO app_settings_new (id, activeProfileId, unlockDelayMs, blockSettingsWhenLocked, setupCompleted)
                    SELECT id, 1, unlockDelayMs, blockSettingsWhenLocked, setupCompleted FROM app_settings
                """)

                // 8. Drop old app_settings and rename new one
                database.execSQL("DROP TABLE app_settings")
                database.execSQL("ALTER TABLE app_settings_new RENAME TO app_settings")

                // 9. Drop old blocked_apps table
                database.execSQL("DROP TABLE blocked_apps")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "unbrick_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
