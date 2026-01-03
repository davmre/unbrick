package com.unbrick.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.unbrick.data.dao.*
import com.unbrick.data.model.*

@Database(
    entities = [
        BlockedApp::class,
        LockState::class,
        NfcTagInfo::class,
        AppSettings::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun blockedAppDao(): BlockedAppDao
    abstract fun lockStateDao(): LockStateDao
    abstract fun nfcTagDao(): NfcTagDao
    abstract fun appSettingsDao(): AppSettingsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "unbrick_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
