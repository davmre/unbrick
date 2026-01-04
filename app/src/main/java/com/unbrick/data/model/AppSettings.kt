package com.unbrick.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_settings")
data class AppSettings(
    @PrimaryKey
    val id: Int = 1, // Singleton
    val activeProfileId: Long? = null,
    val unlockDelayMs: Long = 1 * 60 * 60 * 1000L, // 1 hour default
    val blockSettingsWhenLocked: Boolean = true,
    val setupCompleted: Boolean = false
)
