package com.unbrick.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lock_state")
data class LockState(
    @PrimaryKey
    val id: Int = 1, // Singleton - only one lock state
    val isLocked: Boolean = false,
    val lockedAt: Long? = null,
    val emergencyUnlockRequestedAt: Long? = null,
    val unlockDelayMs: Long = 24 * 60 * 60 * 1000L // Default 24 hours
)

enum class BlockingMode {
    BLOCKLIST, // Block selected apps when locked
    ALLOWLIST  // Only allow selected apps when locked
}
