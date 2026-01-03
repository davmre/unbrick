package com.unbrick.data.dao

import androidx.room.*
import com.unbrick.data.model.AppSettings
import kotlinx.coroutines.flow.Flow

@Dao
interface AppSettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 1")
    fun getSettings(): Flow<AppSettings?>

    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun getSettingsSync(): AppSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(settings: AppSettings)

    @Update
    suspend fun update(settings: AppSettings)

    @Query("UPDATE app_settings SET activeProfileId = :profileId WHERE id = 1")
    suspend fun setActiveProfileId(profileId: Long)

    @Query("UPDATE app_settings SET unlockDelayMs = :delayMs WHERE id = 1")
    suspend fun setUnlockDelay(delayMs: Long)

    @Query("UPDATE app_settings SET setupCompleted = :completed WHERE id = 1")
    suspend fun setSetupCompleted(completed: Boolean)
}
