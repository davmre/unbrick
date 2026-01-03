package com.unbrick.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_settings")
data class AppSettings(
    @PrimaryKey
    val id: Int = 1, // Singleton
    val blockingMode: String = BlockingMode.BLOCKLIST.name,
    val unlockDelayMs: Long = 24 * 60 * 60 * 1000L, // 24 hours default
    val blockSettingsWhenLocked: Boolean = true,
    val setupCompleted: Boolean = false
)
