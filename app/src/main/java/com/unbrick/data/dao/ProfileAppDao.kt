package com.unbrick.data.dao

import androidx.room.*
import com.unbrick.data.model.ProfileApp
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileAppDao {
    @Query("SELECT * FROM profile_apps WHERE profileId = :profileId ORDER BY appName ASC")
    fun getAppsForProfile(profileId: Long): Flow<List<ProfileApp>>

    @Query("SELECT * FROM profile_apps WHERE profileId = :profileId")
    suspend fun getAppsForProfileSync(profileId: Long): List<ProfileApp>

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM profile_apps pa
            JOIN blocking_profiles bp ON pa.profileId = bp.id
            WHERE bp.isActive = 1 AND pa.packageName = :packageName AND pa.isBlocked = 1
        )
    """)
    suspend fun isAppInActiveProfile(packageName: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: ProfileApp)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(apps: List<ProfileApp>)

    @Delete
    suspend fun delete(app: ProfileApp)

    @Query("DELETE FROM profile_apps WHERE profileId = :profileId AND packageName = :packageName")
    suspend fun deleteByPackageName(profileId: Long, packageName: String)

    @Query("DELETE FROM profile_apps WHERE profileId = :profileId")
    suspend fun deleteAllForProfile(profileId: Long)

    @Query("SELECT COUNT(*) FROM profile_apps WHERE profileId = :profileId")
    suspend fun getAppCountForProfile(profileId: Long): Int
}
