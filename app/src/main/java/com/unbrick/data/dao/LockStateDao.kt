package com.unbrick.data.dao

import androidx.room.*
import com.unbrick.data.model.LockState
import kotlinx.coroutines.flow.Flow

@Dao
interface LockStateDao {
    @Query("SELECT * FROM lock_state WHERE id = 1")
    fun getLockState(): Flow<LockState?>

    @Query("SELECT * FROM lock_state WHERE id = 1")
    suspend fun getLockStateSync(): LockState?

    @Query("SELECT isLocked FROM lock_state WHERE id = 1")
    suspend fun isLocked(): Boolean?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(state: LockState)

    @Update
    suspend fun update(state: LockState)

    @Query("UPDATE lock_state SET isLocked = :locked, lockedAt = :lockedAt WHERE id = 1")
    suspend fun setLocked(locked: Boolean, lockedAt: Long?)

    @Query("UPDATE lock_state SET emergencyUnlockRequestedAt = :requestedAt WHERE id = 1")
    suspend fun setEmergencyUnlockRequested(requestedAt: Long?)
}
