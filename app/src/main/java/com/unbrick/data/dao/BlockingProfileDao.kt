package com.unbrick.data.dao

import androidx.room.*
import com.unbrick.data.model.BlockingProfile
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockingProfileDao {
    @Query("SELECT * FROM blocking_profiles ORDER BY name ASC")
    fun getAllProfiles(): Flow<List<BlockingProfile>>

    @Query("SELECT * FROM blocking_profiles ORDER BY name ASC")
    suspend fun getAllProfilesSync(): List<BlockingProfile>

    @Query("SELECT * FROM blocking_profiles WHERE id = :id")
    suspend fun getProfileById(id: Long): BlockingProfile?

    @Query("SELECT * FROM blocking_profiles WHERE isActive = 1 LIMIT 1")
    fun getActiveProfile(): Flow<BlockingProfile?>

    @Query("SELECT * FROM blocking_profiles WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveProfileSync(): BlockingProfile?

    @Insert
    suspend fun insert(profile: BlockingProfile): Long

    @Update
    suspend fun update(profile: BlockingProfile)

    @Delete
    suspend fun delete(profile: BlockingProfile)

    @Query("DELETE FROM blocking_profiles WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE blocking_profiles SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE blocking_profiles SET isActive = 1 WHERE id = :profileId")
    suspend fun setActive(profileId: Long)

    @Query("SELECT COUNT(*) FROM blocking_profiles")
    suspend fun getProfileCount(): Int

    @Query("UPDATE blocking_profiles SET name = :name WHERE id = :id")
    suspend fun updateName(id: Long, name: String)

    @Query("UPDATE blocking_profiles SET blockingMode = :mode WHERE id = :id")
    suspend fun updateBlockingMode(id: Long, mode: String)

    @Transaction
    suspend fun setActiveProfile(profileId: Long) {
        deactivateAll()
        setActive(profileId)
    }
}
